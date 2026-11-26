package com.openminis.app.ondevice

import android.content.Context
import com.openminis.app.llm.mnn.LocalMnnModel
import com.openminis.app.logging.AppLogger
import com.openminis.app.network.NetworkHttp
import com.openminis.app.speech.SpeechRecognitionManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 端侧模型的「应用内下载」入口。
 *
 * 这里的两个模型都不进 APK、不入库，历史上只能靠 `scripts/deploy_*.sh`
 * 用 adb 推到 `getExternalFilesDir(null)/models/<dir>`。新装机的用户没有
 * 这两条脚本，于是端侧语音识别和快路径小模型都处于「缺文件」状态 —— 本类
 * 就是为了让用户在设置里自己下载。
 *
 * 与两个消费方共用同一份目录约定（改这里就要同步改它俩）：
 *  - [com.openminis.app.speech.LocalSenseVoiceEngine] → `models/asr-sensevoice`
 *  - [com.openminis.app.llm.mnn.LocalMnnModel] → `models/mnn-llm-0.3b`
 * 目录查找顺序也一致：外部私有目录优先（adb push 可达），私有 filesDir 其次。
 *
 * 下载行为：
 *  - 每个文件先写 `<name>.<mirror>.part`，大小 + SHA-256 都对上才改名就位，
 *    所以半截文件永远不会被消费方当成模型。
 *  - 支持 HTTP Range 续传；进程被杀后重新进设置页点「继续下载」即可。
 *  - 多个镜像依次尝试（ModelScope / hf-mirror / HuggingFace），一个失败换下一个。
 *  - 走 [NetworkHttp] 的默认路由，用户配了代理才会走代理，和更新下载一致。
 */
object OnDeviceModelManager {

    private const val TAG = "OnDeviceModel"

    /** 端侧语音识别（sherpa-onnx SenseVoice Small int8）。 */
    const val ASR_SENSEVOICE = "asr-sensevoice"

    /** 快路径小模型（MNN-LLM Pocket 0.3B int4）。 */
    const val FAST_LLM = "mnn-llm-0.3b"

    private const val SENSEVOICE_REPO = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"

    data class ModelFile(
        val name: String,
        val bytes: Long,
        val sha256: String,
    )

    data class ModelSpec(
        val key: String,
        val dirName: String,
        val files: List<ModelFile>,
        /**
         * 镜像基地址，末尾带 `/`；完整 URL = 基地址 + 文件名。
         * 顺序即尝试顺序：国内优先。
         */
        val mirrors: List<String>,
    ) {
        val totalBytes: Long get() = files.sumOf { it.bytes }
    }

    enum class Phase { IDLE, DOWNLOADING, VERIFYING, DONE, FAILED }

    data class ModelState(
        val installed: Boolean = false,
        /** 已下载的 .part 字节数；没开始过为 0。 */
        val partialBytes: Long = 0,
        val phase: Phase = Phase.IDLE,
        val bytesDone: Long = 0,
        val totalBytes: Long = 0,
        val error: String? = null,
    ) {
        val isBusy: Boolean
            get() = phase == Phase.DOWNLOADING || phase == Phase.VERIFYING
    }

    /**
     * 模型清单。大小与哈希取自 HuggingFace 仓库的元数据，并与本机
     * `models/` 目录下的既有部署文件逐一核对过（sha256 完全一致）。
     */
    val specs: List<ModelSpec> = listOf(
        ModelSpec(
            key = ASR_SENSEVOICE,
            dirName = "asr-sensevoice",
            files = listOf(
                ModelFile(
                    name = "model.int8.onnx",
                    bytes = 239_233_841L,
                    sha256 = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
                ),
                ModelFile(
                    name = "tokens.txt",
                    bytes = 315_894L,
                    sha256 = "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
                ),
            ),
            mirrors = listOf(
                // ModelScope（国内直连；与官方 HF 文件哈希一致）
                "https://modelscope.cn/models/gomodels/sherpa/resolve/master/$SENSEVOICE_REPO/",
                "https://modelscope.cn/models/poloniumrock/SenseVoiceSmallOnnx/resolve/master/",
                // hf-mirror 面向国内；最后才是 HuggingFace 直连
                "https://hf-mirror.com/csukuangfj/$SENSEVOICE_REPO/resolve/main/",
                "https://huggingface.co/csukuangfj/$SENSEVOICE_REPO/resolve/main/",
            ),
        ),
        ModelSpec(
            key = FAST_LLM,
            dirName = "mnn-llm-0.3b",
            files = listOf(
                ModelFile(
                    name = "llm.mnn.weight",
                    bytes = 171_481_330L,
                    sha256 = "c9e0f2950718c057aeb2c20e5b8dd669cd02477c6c355b46d153256a312a248d",
                ),
                ModelFile(
                    name = "llm.mnn",
                    bytes = 953_112L,
                    sha256 = "d42a7d9c7152b45b2b6805f9a5ab6dd95c485ad718be88ec723087d4f5d71d7c",
                ),
                ModelFile(
                    name = "tokenizer.mtok",
                    bytes = 1_710_861L,
                    sha256 = "9f806cad88e8be82e0ca3868903f3528eb1ffcbe4de1437e20549b94fccb4440",
                ),
                ModelFile(
                    name = "config.json",
                    bytes = 502L,
                    sha256 = "801311d0461dceffb4fe06f71a38b8ffb9616022626109537d3343ecc747619f",
                ),
                ModelFile(
                    name = "llm_config.json",
                    bytes = 3_237L,
                    sha256 = "64e1b9fc5905fc9e91a8b2007c225b70dc82ffb183ecdb4bfc28e01ac42adc45",
                ),
            ),
            mirrors = listOf(
                "https://hf-mirror.com/taobao-mnn/MNN-Pocket-0.3B-MNN/resolve/main/",
                "https://huggingface.co/taobao-mnn/MNN-Pocket-0.3B-MNN/resolve/main/",
            ),
        ),
    )

    private val initialStates: Map<String, ModelState> =
        specs.associate { it.key to ModelState(totalBytes = it.totalBytes) }

    private val _states = MutableStateFlow(initialStates)
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val calls = HashMap<String, Call>()

    @Volatile
    private var appContext: Context? = null

    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            // 300 MB 级下载：整次调用不能有总超时，否则慢速网络会被中途掐断。
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val client: OkHttpClient
        get() = NetworkHttp.client(NetworkHttp.DEFAULT_TAG, baseClient)

    fun spec(key: String): ModelSpec? = specs.firstOrNull { it.key == key }

    /**
     * 按磁盘现状刷新两个模型的状态。下载进行中的模型不动，避免把进度覆盖掉。
     * 设置页进入时调用；也用于 Settings 首页那行副标题。
     */
    fun refresh(context: Context) {
        val app = context.applicationContext
        appContext = app
        for (spec in specs) {
            val current = _states.value[spec.key]
            if (current?.isBusy == true) continue
            _states.update { it + (spec.key to inspect(app, spec)) }
        }
    }

    /** 后台刷新（UI 入口用，避免在组合线程里做文件 stat）。 */
    fun refreshAsync(context: Context) {
        val app = context.applicationContext
        appContext = app
        scope.launch { refresh(app) }
    }

    /** 开始（或继续）下载 [key]。已在下载中则忽略。 */
    fun install(context: Context, key: String) {
        val spec = spec(key) ?: return
        if (jobs[key]?.isActive == true) return
        val app = context.applicationContext
        appContext = app
        jobs[key] = scope.launch {
            val target = targetDir(app, spec)
            val start = inspect(app, spec)
            if (start.installed) {
                markInstalled(spec)
                return@launch
            }
            publish(key) {
                ModelState(
                    installed = false,
                    partialBytes = start.partialBytes,
                    phase = Phase.DOWNLOADING,
                    bytesDone = 0,
                    totalBytes = spec.totalBytes,
                )
            }
            try {
                runMirrors(spec, target) { done, total ->
                    publish(key) { state ->
                        state.copy(bytesDone = done, totalBytes = total)
                    }
                }
                publish(key) {
                    ModelState(
                        installed = true,
                        phase = Phase.DONE,
                        bytesDone = spec.totalBytes,
                        totalBytes = spec.totalBytes,
                    )
                }
                onInstalled(app, spec)
                AppLogger.info(TAG, "${spec.key} 安装完成 @${target.absolutePath}")
            } catch (e: CancellationException) {
                publish(key) { it.copy(phase = Phase.IDLE, error = null) }
                throw e
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "${spec.key} 下载失败: ${t.javaClass.simpleName}: ${t.message}")
                publish(key) {
                    it.copy(
                        phase = Phase.FAILED,
                        error = t.message ?: t.javaClass.simpleName,
                        partialBytes = partialBytesIn(target),
                    )
                }
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (jobs[key] === job) jobs.remove(key)
                if (jobs[key] == null) calls.remove(key)
            }
        }
    }

    /** 取消下载，已完成的 .part 保留，下次可续传。 */
    fun cancel(key: String) {
        calls.remove(key)?.cancel()
        jobs[key]?.cancel()
    }

    /** 删除模型文件（两个候选目录都清），并通知消费方回退。 */
    fun delete(context: Context, key: String) {
        val spec = spec(key) ?: return
        val app = context.applicationContext
        appContext = app
        calls.remove(key)?.cancel()
        jobs[key]?.cancel()
        jobs.remove(key)
        for (dir in modelDirs(app, spec)) {
            runCatching { dir.deleteRecursively() }
                .onFailure { AppLogger.warning(TAG, "删除 ${dir.absolutePath} 失败: ${it.message}") }
        }
        onRemoved(app, spec)
        _states.update { it + (key to inspect(app, spec)) }
        AppLogger.info(TAG, "${spec.key} 已删除")
    }

    // ── 下载实现 ──────────────────────────────────────────────────────────

    private suspend fun runMirrors(
        spec: ModelSpec,
        target: File,
        onProgress: (done: Long, total: Long) -> Unit,
    ) {
        var lastError: Throwable? = null
        for ((index, base) in spec.mirrors.withIndex()) {
            try {
                var done = spec.files.sumOf { if (isFileGood(target, it)) it.bytes else 0L }
                onProgress(done, spec.totalBytes)
                for (file in spec.files) {
                    if (isFileGood(target, file)) continue
                    publish(spec.key) { it.copy(phase = Phase.DOWNLOADING) }
                    val part = File(target, "${file.name}.$index.part")
                    downloadOne(
                        client = client,
                        url = mirrorUrl(base, file.name),
                        expectedBytes = file.bytes,
                        part = part,
                        onProgress = { partBytes ->
                            onProgress(done + minOf(partBytes, file.bytes), spec.totalBytes)
                        },
                        onCall = { call ->
                            if (call != null) calls[spec.key] = call else calls.remove(spec.key)
                        },
                    )
                    publish(spec.key) { it.copy(phase = Phase.VERIFYING) }
                    verifyFile(part, file)
                    installInto(part, File(target, file.name))
                    done += file.bytes
                    onProgress(done, spec.totalBytes)
                }
                // 成功后清掉其它镜像留下的 .part
                target.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (!currentCoroutineContext().isActive) throw CancellationException()
                lastError = t
                AppLogger.warning(
                    TAG,
                    "${spec.key} 镜像失败 ${base.take(60)}…: ${t.javaClass.simpleName}: ${t.message}",
                )
                // 这个镜像的残留半截文件对下一个镜像不可续传（不同服务器可能有
                // 不同版本），删掉重下更稳；已校验完成的文件不受影响。
                target.listFiles()?.filter { it.name.endsWith(".$index.part") }?.forEach { it.delete() }
            }
        }
        throw IOException(lastError?.message ?: "所有下载源均失败")
    }

    /** 大小 + SHA-256 双重校验；不通过就删掉半成品并抛错。 */
    private fun verifyFile(part: File, file: ModelFile) {
        if (!part.isFile || part.length() != file.bytes) {
            part.delete()
            throw IOException("${file.name} 大小不符")
        }
        val actual = sha256Of(part)
        if (!actual.equals(file.sha256, ignoreCase = true)) {
            part.delete()
            throw IOException("${file.name} 校验失败")
        }
    }

    private fun installInto(part: File, target: File) {
        target.delete()
        if (part.renameTo(target)) return
        part.copyTo(target, overwrite = true)
        part.delete()
    }

    // ── 目录与状态 ────────────────────────────────────────────────────────

    /** 外部私有目录优先，其次私有 filesDir；与两个引擎的查找顺序一致。 */
    private fun modelDirs(context: Context, spec: ModelSpec): List<File> {
        val dirs = ArrayList<File>(2)
        context.getExternalFilesDir(null)?.let { dirs.add(File(it, "models/${spec.dirName}")) }
        dirs.add(File(context.filesDir, "models/${spec.dirName}"))
        return dirs
    }

    private fun targetDir(context: Context, spec: ModelSpec): File {
        val dirs = modelDirs(context, spec)
        return dirs.firstOrNull { dir ->
            spec.files.all { isFileGood(dir, it) }
        } ?: dirs.first()
    }

    private fun isFileGood(dir: File, file: ModelFile): Boolean {
        val f = File(dir, file.name)
        return f.isFile && f.length() == file.bytes
    }

    private fun partialBytesIn(dir: File): Long =
        dir.listFiles()?.filter { it.name.endsWith(".part") }?.sumOf { it.length() } ?: 0L

    private fun inspect(context: Context, spec: ModelSpec): ModelState {
        val dirs = modelDirs(context, spec)
        val installedDir = dirs.firstOrNull { dir ->
            spec.files.all { isFileGood(dir, it) }
        }
        if (installedDir != null) {
            return ModelState(
                installed = true,
                phase = Phase.DONE,
                bytesDone = spec.totalBytes,
                totalBytes = spec.totalBytes,
            )
        }
        return ModelState(
            installed = false,
            partialBytes = partialBytesIn(dirs.first()),
            phase = Phase.IDLE,
            totalBytes = spec.totalBytes,
        )
    }

    private fun publish(key: String, block: (ModelState) -> ModelState) {
        _states.update { all ->
            val current = all[key] ?: ModelState()
            all + (key to block(current))
        }
    }

    private fun markInstalled(spec: ModelSpec) {
        _states.update {
            it + (
                spec.key to ModelState(
                    installed = true,
                    phase = Phase.DONE,
                    bytesDone = spec.totalBytes,
                    totalBytes = spec.totalBytes,
                )
                )
        }
        appContext?.let { onInstalled(it, spec) }
    }

    // ── 消费方通知 ────────────────────────────────────────────────────────

    private fun onInstalled(context: Context, spec: ModelSpec) {
        when (spec.key) {
            ASR_SENSEVOICE -> SpeechRecognitionManager.onLocalModelInstalled()
            FAST_LLM -> runCatching { LocalMnnModel.get(context).release() }
        }
    }

    private fun onRemoved(context: Context, spec: ModelSpec) {
        when (spec.key) {
            ASR_SENSEVOICE -> SpeechRecognitionManager.onLocalModelRemoved()
            FAST_LLM -> runCatching { LocalMnnModel.get(context).release() }
        }
    }
}

// ── 纯函数（可单测） ──────────────────────────────────────────────────────

/** 镜像基地址 + 文件名；基地址末尾没有 `/` 也能拼对。 */
internal fun mirrorUrl(base: String, fileName: String): String =
    if (base.endsWith("/")) base + fileName else "$base/$fileName"

/**
 * 把 `bytes 0-4095/1234567` 解析成起始偏移。返回 null 表示服务器没给可用的
 * Content-Range（此时调用方应从头写）。
 */
internal fun parseContentRangeStart(header: String?): Long? {
    val value = header?.trim() ?: return null
    if (!value.startsWith("bytes ", ignoreCase = true)) return null
    // removePrefix 区分大小写，这里已经按忽略大小写匹配过前缀，直接切掉 6 个字符。
    val range = value.substring(6).substringBefore('/')
    val start = range.substringBefore('-').trim().toLongOrNull() ?: return null
    return start.takeIf { it >= 0 }
}

/**
 * 把 [url] 流式写入 [part]，返回最终字节数。
 *
 * - [part] 已存在且长度 > 0 时带 `Range` 续传；
 * - 服务器忽略 Range 返回 200 时清空重写；
 * - 服务器回 416 且长度已等于 [expectedBytes] 时视为已完成（返回现有长度）。
 *
 * 单测见 `OnDeviceModelDownloadTest`（MockWebServer）。
 */
internal fun downloadOne(
    client: OkHttpClient,
    url: String,
    expectedBytes: Long,
    part: File,
    onProgress: (Long) -> Unit = {},
    onCall: (Call?) -> Unit = {},
): Long {
    part.parentFile?.mkdirs()
    var existing = if (part.isFile) part.length() else 0L
    if (existing > expectedBytes) {
        part.delete()
        existing = 0L
    }
    if (existing == expectedBytes && expectedBytes > 0) {
        onProgress(existing)
        return existing
    }

    val request = Request.Builder()
        .url(url)
        .apply { if (existing > 0) header("Range", "bytes=$existing-") }
        .build()

    val call = client.newCall(request)
    onCall(call)
    try {
        call.execute().use { response ->
            if (response.code == 416 && existing == expectedBytes) return existing
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")

            // 206 = 服务器接受了 Range，追加；200 = 忽略了 Range，从头重写。
            val appending = existing > 0 && response.code == 206
            if (appending) {
                val start = parseContentRangeStart(response.header("Content-Range"))
                if (start != null && start != existing) {
                    throw IOException("续传位置不符：$start/$existing")
                }
            } else {
                existing = 0L
            }

            val body = response.body ?: throw IOException("空响应体")
            var written = existing
            var lastReported = written
            body.byteStream().use { input ->
                FileOutputStream(part, appending).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written - lastReported >= 256 * 1024) {
                            lastReported = written
                            onProgress(written)
                        }
                    }
                }
            }
            onProgress(written)
            if (expectedBytes > 0 && written != expectedBytes) {
                throw IOException("大小不符：$written/$expectedBytes")
            }
            return written
        }
    } finally {
        onCall(null)
    }
}

/** 流式 SHA-256；下载完成后校验用。 */
internal fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
