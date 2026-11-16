package com.openminis.app.media.music

import android.content.Context
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/** How the queue moves on when a track ends. */
enum class PlayMode { SEQUENCE, REPEAT_ALL, REPEAT_ONE }

/** Everything the player UI renders. */
data class MusicUiState(
    val track: MusicTrack? = null,
    val queue: List<MusicTrack> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lyricText: String = "",
    val lyricsReady: Boolean = false,
    val mode: PlayMode = PlayMode.REPEAT_ALL,
    val shuffle: Boolean = false,
    val error: String? = null,
) {
    val hasTrack: Boolean get() = track != null
    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * [T-music-slot-semantics] 一次点歌的解析结果。
 *
 * [kind] 来自抽取层（song/artist/genre/scene/chart/none），[contextLabel]
 * 是实际命中的歌单/榜单/歌手，回复里用它回显「理解为」。
 */
data class MusicPlayback(
    val track: MusicTrack,
    val kind: String,
    val query: String,
    val contextLabel: String = "",
    val matchedCount: Int = 0,
)

/**
 * 音乐播放器，逻辑移植自 pilot 的 `MusicPlayer`：
 *
 *  - **一首歌一个 ExoPlayer**：`startCurrent()` 新建，放完/出错/停止即
 *    release —— 空载时没有播放器对象常驻；
 *  - 队列、顺序/列表循环/单曲循环、随机 + 历史回退、连续失败自动跳过；
 *  - 播放地址每首现取（代理给的 URL ~20 分钟过期），歌词同时解析；
 *  - 「最近播放」本地缓存 30 首，网易云接口为空时仍可用。
 *
 * 与 app 的其他部分通过 [state] 单向往外报；语音层用
 * [com.openminis.app.speech.VoiceOutputState.CaptureSuspendable] 在开麦前
 * 暂停音乐，TTS 播报时自动压低音量。
 */
class MusicPlayerController private constructor(
    private val appContext: Context,
) : com.openminis.app.speech.VoiceOutputState.CaptureSuspendable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val api = NeteaseMusicApi { MusicPreferences.load(appContext) }

    private val _state = MutableStateFlow(MusicUiState())
    val state: StateFlow<MusicUiState> = _state.asStateFlow()

    /** The player for the CURRENT track only; null while idle. */
    private var player: ExoPlayer? = null

    private var lyrics: List<LyricLine> = emptyList()
    private val history = ArrayDeque<Int>()
    private var consecutiveErrors = 0
    private var tickerJob: Job? = null
    private var suspendedForCapture = false

    init {
        com.openminis.app.speech.VoiceOutputState.registerCaptureSuspendable(this)
        tickerJob = scope.launch {
            while (isActive) {
                val exo = player
                if (exo != null && exo.isPlaying) {
                    val position = exo.currentPosition.coerceAtLeast(0L)
                    _state.update { current ->
                        current.copy(
                            positionMs = position,
                            lyricText = lyricAt(position),
                        )
                    }
                    applySpeechDucking(exo)
                }
                delay(TICK_MS)
            }
        }
    }

    // ─────────────────────────────────────────────── playlists

    /**
     * 「播放最近」：网易云账号的最近播放 + 本地缓存，去重。
     */
    suspend fun playRecent(): MusicTrack? {
        val remote = runCatching { api.recentSongs(PLAYLIST_LIMIT) }.getOrDefault(emptyList())
        val songs = (remote + MusicHistoryStore.recent(appContext, PLAYLIST_LIMIT))
            .distinctBy { it.id }
            .take(PLAYLIST_LIMIT)
        return startList(songs, "最近播放")
    }

    /** 「每日推荐」（需登录）。 */
    suspend fun playDaily(): MusicTrack? {
        val songs = runCatching { api.dailyRecommendations(PLAYLIST_LIMIT) }
            .getOrDefault(emptyList())
        return startList(songs, "每日推荐")
    }

    /** 「我喜欢的音乐」（需登录）。 */
    suspend fun playLiked(): MusicTrack? {
        val songs = runCatching { api.likedSongs(PLAYLIST_LIMIT) }.getOrDefault(emptyList())
        return startList(songs, "我喜欢的音乐")
    }

    /** 官方榜：热歌 / 新歌 / 飙升。 */
    suspend fun playChart(chart: MusicChart): MusicTrack? {
        val songs = runCatching { api.chartSongs(chart.id, PLAYLIST_LIMIT) }
            .getOrDefault(emptyList())
        return startList(songs, chart.label)
    }

    /** 「热点/随便放」——热歌榜。 */
    suspend fun playHot(): MusicTrack? = playChart(MusicChart.HOT)

    /**
     * [T-music-slot-semantics] 按抽取层给出的 kind 分派。
     *
     * kind 由 SlotExtractor 判定（song/artist/genre/scene/chart/none），
     * 执行层只消费，不再用 contains("热门") 之类嗅探意图。返回的
     * [MusicPlayback] 带着解析摘要，回复里直接回显「理解为」。
     */
    suspend fun playRequest(
        kind: String,
        query: String,
        artist: String = "",
        chart: String = "",
    ): MusicPlayback? {
        val keyword = query.trim()
        return when (kind.trim().lowercase(Locale.ROOT)) {
            "genre", "scene" -> playPlaylist(keyword, kind.trim().lowercase(Locale.ROOT))
            "artist" -> playArtist(keyword)
            "chart", "none" -> playChartNamed(chart, kind.trim().lowercase(Locale.ROOT), keyword)
            "song" -> if (keyword.isBlank()) {
                playChartNamed(chart, "none", keyword)
            } else {
                playSearchSong(keyword, artist.trim())
            }
            "" -> legacyPlay(keyword)
            else -> if (keyword.isBlank()) {
                playChartNamed(chart, "none", "")
            } else {
                playSearchSong(keyword, artist.trim())
            }
        }
    }

    /**
     * 旧入口，保留给还没有 kind 的调用方。kind 缺省时按旧词表推断；
     * 助理路径现在总是带 kind，不会再走到字符串嗅探。
     */
    suspend fun playQuery(query: String): MusicTrack? = playRequest("", query)?.track

    private suspend fun legacyPlay(keyword: String): MusicPlayback? {
        if (keyword.isBlank()) return playChartNamed("", "none", "")
        return when {
            keyword.contains("热门") || keyword.contains("热歌") ->
                playChartNamed("hot", "chart", keyword)
            keyword.contains("最近") -> playChartNamed("recent", "chart", keyword)
            keyword.contains("每日推荐") || keyword == "推荐" ->
                playChartNamed("daily", "chart", keyword)
            keyword.contains("我喜欢") || keyword.contains("收藏") ->
                playChartNamed("liked", "chart", keyword)
            keyword.contains("新歌") -> playChartNamed("new", "chart", keyword)
            keyword.contains("飙升") -> playChartNamed("rising", "chart", keyword)
            else -> playSearchSong(keyword, "")
        }
    }

    /**
     * 流派/场景 → `type=1000` 歌单，取第一个有曲目的结果。
     * 歌单检索失败时不拿流派词去搜歌名（那正是歌名《轻》的来路），
     * 直接退回热歌榜。
     */
    private suspend fun playPlaylist(keyword: String, kind: String): MusicPlayback? {
        if (keyword.isBlank()) return playChartNamed("", kind, keyword)
        val playlists = runCatching { api.searchPlaylists(keyword, PLAYLIST_SEARCH_LIMIT) }
            .getOrDefault(emptyList())
        val playlist = playlists.firstOrNull { it.trackCount > 0 } ?: playlists.firstOrNull()
        if (playlist != null) {
            val tracks = runCatching { api.playlistTracks(playlist.id, PLAYLIST_LIMIT) }
                .getOrDefault(emptyList())
            if (tracks.isNotEmpty()) {
                MusicHistoryStore.remember(appContext, tracks)
                val track = startQueue(tracks, 0)
                if (track != null) {
                    return MusicPlayback(
                        track = track,
                        kind = kind,
                        query = keyword,
                        contextLabel = "歌单《${playlist.name}》",
                        matchedCount = playlist.trackCount.takeIf { it > 0 } ?: tracks.size,
                    )
                }
            }
        }
        return playChartNamed("", kind, keyword)
    }

    /** 歌手 → `type=100` 找到 artist id，再取热门歌曲。 */
    private suspend fun playArtist(name: String): MusicPlayback? {
        if (name.isBlank()) return playChartNamed("", "none", "")
        val artist = runCatching { api.searchArtists(name, 1) }
            .getOrDefault(emptyList())
            .firstOrNull()
        if (artist != null) {
            val tracks = runCatching { api.artistHotSongs(artist.id, PLAYLIST_LIMIT) }
                .getOrDefault(emptyList())
            if (tracks.isNotEmpty()) {
                MusicHistoryStore.remember(appContext, tracks)
                val track = startQueue(tracks, 0)
                if (track != null) {
                    return MusicPlayback(
                        track = track,
                        kind = "artist",
                        query = name,
                        contextLabel = "热门歌曲",
                        matchedCount = tracks.size,
                    )
                }
            }
        }
        return playSearchSong(name, "")
    }

    /** 榜单 / 固定列表。chart 为空时走热歌榜。 */
    private suspend fun playChartNamed(chart: String, kind: String, query: String): MusicPlayback? {
        val resolved = chart.trim().lowercase(Locale.ROOT)
        val primary: Pair<MusicTrack?, String> = when (resolved) {
            "new" -> playChart(MusicChart.NEW) to MusicChart.NEW.label
            "rising" -> playChart(MusicChart.RISING) to MusicChart.RISING.label
            "recent" -> playRecent() to "最近播放"
            "daily" -> playDaily() to "每日推荐"
            "liked" -> playLiked() to "我喜欢的音乐"
            else -> playHot() to MusicChart.HOT.label
        }
        val started = if (primary.first == null && resolved in FALLBACK_CHARTS) {
            // 未登录时每日推荐 / 我喜欢为空：退回热歌榜，并如实说明。
            playHot() to MusicChart.HOT.label
        } else {
            primary
        }
        val track = started.first ?: return null
        return MusicPlayback(
            track = track,
            kind = kind.ifBlank { "none" },
            query = query,
            contextLabel = started.second,
            matchedCount = _state.value.queue.size,
        )
    }

    /** 歌名检索：query 是歌名，artist 是抽取层拆出的歌手（可空）。 */
    private suspend fun playSearchSong(keyword: String, artist: String): MusicPlayback? {
        val queries = buildList {
            add(keyword)
            if (artist.isNotBlank()) add("$artist $keyword")
        }
        val candidates = mutableListOf<List<MusicTrack>>()
        for (query in queries) {
            candidates += runCatching { api.search(query, PLAYLIST_LIMIT, type = 1) }
                .getOrDefault(emptyList())
        }
        var best: MusicTrack? = null
        var bestScore = Int.MIN_VALUE
        var bestQueue: List<MusicTrack> = emptyList()
        for (list in candidates) {
            for (track in list) {
                val score = score(track, keyword, artist)
                if (score > bestScore) {
                    bestScore = score
                    best = track
                    bestQueue = list
                }
            }
        }
        val chosen = best
        if (chosen == null) {
            _state.update { it.copy(error = "没有找到歌曲") }
            return null
        }
        MusicHistoryStore.remember(appContext, bestQueue)
        val index = bestQueue.indexOfFirst { it.id == chosen.id }.coerceAtLeast(0)
        val track = startQueue(bestQueue, index) ?: return null
        return MusicPlayback(
            track = track,
            kind = "song",
            query = keyword,
            contextLabel = if (artist.isNotBlank()) "歌手 $artist" else "",
            matchedCount = bestQueue.size,
        )
    }

    /** 开始一个列表（推荐下拉 / 榜单 / 搜索都用它）。 */
    suspend fun startList(songs: List<MusicTrack>, label: String): MusicTrack? {
        if (songs.isEmpty()) {
            _state.update { it.copy(error = "「$label」暂无歌曲") }
            return null
        }
        MusicHistoryStore.remember(appContext, songs)
        return startQueue(songs, 0)
    }

    /** 搜索接口返回的完整候选（供 UI / 命令使用）。 */
    suspend fun search(query: String): List<MusicTrack> = api.search(query, PLAYLIST_LIMIT)

    // ─────────────────────────────────────────────── transport

    /** Replace the queue and start playing at [startIndex]. */
    fun playTracks(tracks: List<MusicTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        scope.launch {
            _state.update { it.copy(queue = tracks, error = null) }
            startQueue(tracks, startIndex.coerceIn(0, tracks.lastIndex))
        }
    }

    fun toggle() {
        scope.launch {
            val exo = player
            if (exo == null) {
                // Nothing loaded: restart the current track.
                if (_state.value.hasTrack) startCurrent() else return@launch
                return@launch
            }
            // An explicit play/pause is the user speaking over the automatic
            // capture pause, so it must win: otherwise a "暂停播放" command
            // during a voice turn would be resumed when the turn ends.
            suspendedForCapture = false
            if (exo.isPlaying) {
                exo.pause()
                _state.update { it.copy(isPlaying = false) }
            } else {
                exo.play()
                _state.update { it.copy(isPlaying = true) }
            }
        }
    }

    fun pause() {
        scope.launch {
            suspendedForCapture = false
            player?.pause()
            _state.update { it.copy(isPlaying = false) }
        }
    }

    fun resume() {
        scope.launch {
            suspendedForCapture = false
            if (_state.value.track == null) return@launch
            val exo = player
            if (exo == null) {
                startCurrent()
                return@launch
            }
            exo.play()
            _state.update { it.copy(isPlaying = true) }
        }
    }

    fun next() {
        scope.launch { advance(automatic = false) }
    }

    fun previous() {
        scope.launch {
            val current = _state.value
            if (current.queue.isEmpty()) return@launch
            val target = when {
                current.shuffle && history.isNotEmpty() -> history.removeLast()
                current.index > 0 -> current.index - 1
                current.mode != PlayMode.SEQUENCE -> current.queue.lastIndex
                else -> return@launch
            }
            history.addLast(current.index)
            while (history.size > 100) history.removeFirst()
            startAt(target)
        }
    }

    fun playAt(index: Int) {
        scope.launch {
            if (index !in _state.value.queue.indices) return@launch
            if (index == _state.value.index) return@launch
            history.addLast(_state.value.index)
            while (history.size > 100) history.removeFirst()
            startAt(index)
        }
    }

    fun seekTo(positionMs: Long) {
        scope.launch {
            val exo = player ?: return@launch
            val duration = _state.value.durationMs
            val target = positionMs.coerceIn(0L, if (duration > 0L) duration else Long.MAX_VALUE)
            exo.seekTo(target)
            _state.update { it.copy(positionMs = target, lyricText = lyricAt(target)) }
        }
    }

    fun cycleMode(): PlayMode {
        val nextMode = when (_state.value.mode) {
            PlayMode.SEQUENCE -> PlayMode.REPEAT_ALL
            PlayMode.REPEAT_ALL -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.SEQUENCE
        }
        _state.update { it.copy(mode = nextMode) }
        return nextMode
    }

    fun toggleShuffle(): Boolean {
        val next = !_state.value.shuffle
        _state.update { it.copy(shuffle = next) }
        return next
    }

    /** Stop and clear the queue - the top bar disappears with it. */
    fun stop() {
        scope.launch {
            suspendedForCapture = false
            releasePlayer()
            lyrics = emptyList()
            history.clear()
            consecutiveErrors = 0
            _state.value = MusicUiState(
                mode = _state.value.mode,
                shuffle = _state.value.shuffle,
            )
        }
    }

    /** Release the player. Only used when the process is going away. */
    fun release() {
        tickerJob?.cancel()
        scope.cancel()
        runCatching { player?.release() }
        player = null
    }

    // ─────────────────────────────────────────────── capture / speech

    /**
     * The mic is about to open. The song would be recorded with it, so the
     * music pauses; [resumeAfterCapture] picks it up where it left off.
     */
    override fun suspendForCapture() {
        scope.launch {
            val exo = player ?: return@launch
            if (!exo.isPlaying) return@launch
            suspendedForCapture = true
            exo.pause()
            _state.update { it.copy(isPlaying = false) }
            AppLogger.info(TAG, "paused for mic capture")
        }
    }

    override fun resumeAfterCapture() {
        scope.launch {
            if (!suspendedForCapture) return@launch
            suspendedForCapture = false
            val exo = player ?: return@launch
            exo.play()
            _state.update { it.copy(isPlaying = true) }
            AppLogger.info(TAG, "resumed after mic capture")
        }
    }

    /** The assistant's spoken answer ducks the music instead of vanishing. */
    private fun applySpeechDucking(exo: ExoPlayer) {
        val speaking = com.openminis.app.speech.VoiceOutputState.anySpeechSpeaking()
        val wanted = if (speaking) DUCK_VOLUME else 1f
        if (kotlin.math.abs(exo.volume - wanted) < 0.01f) return
        exo.volume = wanted
    }

    // ─────────────────────────────────────────────── internals

    private fun currentTrack(): MusicTrack? =
        _state.value.queue.getOrNull(_state.value.index)

    private suspend fun startQueue(tracks: List<MusicTrack>, index: Int): MusicTrack? {
        val started = CompletableDeferred<MusicTrack?>()
        _state.update { it.copy(queue = tracks, index = index, error = null) }
        startCurrent(started)
        return started.await()
    }

    private fun startAt(index: Int) {
        _state.update { it.copy(index = index) }
        scope.launch { startCurrent() }
    }

    private fun startCurrent(result: CompletableDeferred<MusicTrack?>? = null) {
        scope.launch {
            val track = currentTrack()
            if (track == null) {
                result?.complete(null)
                return@launch
            }
            // A new track is an explicit request ("播放…"/"下一首"); it must
            // not be rewound into the capture bookkeeping of the previous one.
            suspendedForCapture = false
            consecutiveErrors = 0
            lyrics = emptyList()
            _state.update {
                it.copy(
                    track = track,
                    isLoading = true,
                    isPlaying = false,
                    error = null,
                    positionMs = 0L,
                    durationMs = track.durationMs,
                    lyricText = "",
                    lyricsReady = false,
                )
            }
            val url = runCatching { api.playUrl(track.id) }.getOrNull()
            if (url.isNullOrBlank()) {
                fail("无法获取播放地址（可能需要登录网易云或该歌曲受限）", result)
                return@launch
            }
            // One ExoPlayer per track, released the moment it is done.
            releasePlayer()
            val exo = createPlayer()
            player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (player !== exo) return
                    when (playbackState) {
                        Player.STATE_BUFFERING -> _state.update { it.copy(isLoading = true) }
                        Player.STATE_READY -> {
                            val duration = exo.duration.takeIf { it > 0L } ?: _state.value.durationMs
                            _state.update { it.copy(isLoading = false, durationMs = duration) }
                        }
                        Player.STATE_ENDED -> scope.launch { onCurrentEnded() }
                        else -> Unit
                    }
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    if (player !== exo) return
                    _state.update { it.copy(isPlaying = playing) }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (player !== exo) return
                    AppLogger.warning(TAG, "playback error: ${error.errorCodeName}")
                    scope.launch { fail("播放失败：${error.errorCodeName}") }
                }
            })
            runCatching {
                exo.setMediaItem(
                    MediaItem.Builder()
                        .setUri(url)
                        .setMediaId(track.id.toString())
                        .build(),
                )
                exo.prepare()
                exo.play()
            }.onFailure {
                fail("播放失败：${it.message ?: it.javaClass.simpleName}", result)
                return@launch
            }
            AppLogger.info(TAG, "playing ${track.title} - ${track.artist}")
            result?.complete(track)
            loadLyrics(track.id)
        }
    }

    private fun createPlayer(): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setDefaultRequestProperties(
                mapOf("Referer" to "https://music.163.com/"),
            )
        val dataSourceFactory = DefaultDataSource.Factory(appContext, httpFactory)
        return ExoPlayer.Builder(appContext)
            .setLooper(Looper.getMainLooper())
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                setHandleAudioBecomingNoisy(true)
            }
    }

    private suspend fun loadLyrics(songId: Long) {
        val lines = runCatching { api.lyrics(songId) }.getOrDefault(emptyList())
        lyrics = lines
        _state.update { it.copy(lyricsReady = true) }
    }

    private fun lyricAt(positionMs: Long): String {
        if (lyrics.isEmpty()) return ""
        var low = 0
        var high = lyrics.lastIndex
        var match = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lyrics[mid].timeMs <= positionMs) {
                match = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return lyrics.getOrNull(match)?.text.orEmpty()
    }

    private suspend fun onCurrentEnded() {
        if (_state.value.mode == PlayMode.REPEAT_ONE) {
            startCurrent()
            return
        }
        advance(automatic = true)
    }

    /**
     * Move to the next track. Returns false when the queue ended.
     *
     * @param automatic true when the track ended by itself, false when the
     *   user pressed "next" - the difference matters at the end of a
     *   sequential queue: an automatic end stops, a manual next wraps.
     */
    private suspend fun advance(automatic: Boolean): Boolean {
        val current = _state.value
        if (current.queue.isEmpty()) return false
        val target = when {
            current.shuffle && current.queue.size > 1 ->
                current.queue.indices.filter { it != current.index }.randomOrNull() ?: -1
            current.index + 1 <= current.queue.lastIndex -> current.index + 1
            current.mode == PlayMode.REPEAT_ALL -> 0
            automatic -> -1
            else -> 0
        }
        if (target < 0) {
            // Sequence finished: stop and let the bar disappear, like pilot.
            stop()
            return false
        }
        history.addLast(current.index)
        while (history.size > 100) history.removeFirst()
        startAt(target)
        return true
    }

    /** A track could not be played: skip it, or give up and report why. */
    private suspend fun fail(message: String, result: CompletableDeferred<MusicTrack?>? = null) {
        consecutiveErrors++
        val queueSize = _state.value.queue.size
        if (consecutiveErrors < queueSize && advance(automatic = true)) {
            AppLogger.info(TAG, "skipping unplayable track: $message")
            result?.complete(null)
            return
        }
        releasePlayer()
        _state.update { it.copy(isLoading = false, isPlaying = false, error = message) }
        result?.complete(null)
    }

    private fun releasePlayer() {
        val old = player
        player = null
        old?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        _state.update { it.copy(isLoading = false) }
    }

    /**
     * How well a search hit answers the request: the title has to match the
     * song part, and the artist (when the extractor split one out) has to
     * match the artist part - that is what separates the original from the
     * dozens of covers with the same title.
     */
    private fun score(track: MusicTrack, keyword: String, artist: String): Int {
        var score = 0
        val title = track.title.trim()
        val trackArtist = track.artist.trim()
        if (title.equals(keyword, ignoreCase = true)) {
            score += 6
        } else if (title.contains(keyword, ignoreCase = true)) {
            score += 3
        } else if (keyword.contains(title, ignoreCase = true)) {
            score += 1
        }
        if (artist.isNotBlank()) {
            if (trackArtist.contains(artist, ignoreCase = true)) {
                score += 4
            } else if (title.equals(keyword, ignoreCase = true)) {
                // Same title, different artist: probably a cover.
                score -= 1
            }
        }
        return score
    }

    companion object {
        private const val TAG = "MusicPlayer"

        private const val TICK_MS = 400L
        private const val PLAYLIST_LIMIT = 20
        private const val PLAYLIST_SEARCH_LIMIT = 5

        /** 这些固定列表在未登录时可能为空，空了就退回热歌榜。 */
        private val FALLBACK_CHARTS = setOf("recent", "daily", "liked")

        /** Volume while the assistant is speaking over the song. */
        private const val DUCK_VOLUME = 0.18f

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36"

        @Volatile
        private var instance: MusicPlayerController? = null

        fun get(context: Context): MusicPlayerController = instance ?: synchronized(this) {
            instance ?: MusicPlayerController(context.applicationContext).also { instance = it }
        }
    }
}

/** 网易云官方榜，和 pilot 用同一组 playlist id。 */
enum class MusicChart(val id: Long, val label: String) {
    HOT(3778678L, "网易云热歌榜"),
    NEW(3779629L, "网易云新歌榜"),
    RISING(19723756L, "网易云飙升榜"),
}
