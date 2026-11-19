package com.openminis.app.hub

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.openminis.app.logging.AppLogger
import com.openminis.app.network.NetworkHttp
import com.openminis.app.network.NetworkScope
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The phone's end of the hub: one outbound WebSocket to the relay, kept up.
 *
 * Everything here follows the contract, not taste:
 *  - C1.2 the first frame after connect is `hello`, sent at once;
 *  - C1.4 OkHttp's `pingInterval` is set explicitly (its default is 0, i.e.
 *    no heartbeat at all — the number-one cause of "the peer thinks it is
 *    online while it is dead"), plus an application-level `ping` every 25s and
 *    a watchdog that drops the link when ~50s pass with no inbound frame;
 *  - C1.5 the reconnect backoff starts at 1s, doubles, caps at 60s and resets
 *    on any inbound frame; a refused credential (4001/4002) stops the backoff
 *    and retries once a minute;
 *  - C2.1 an unknown frame is answered with `error bad_frame` and the socket
 *    stays open;
 *  - C8.2 the outbound queue is bounded (64 frames / 8MB) — a saturated link
 *    fails loudly instead of growing without bound.
 *
 * The client never listens on a port and never dials the peer: everything
 * goes through the relay, so the phone works from any network.
 */
class HubClient(private val listener: Listener) {

    interface Listener {
        /** The socket is open; the hello frame has been sent. */
        fun onOpen()

        /** Any inbound frame, already parsed. */
        fun onFrame(frame: JSONObject)

        /** The link went down for a reason we may retry. */
        fun onDisconnected(code: Int, reason: String)

        /** The relay refused this credential (4001/4002) — back off hard. */
        fun onRefused(code: Int, reason: String)

        /** Local problem worth surfacing. */
        fun onError(message: String)
    }

    enum class State { IDLE, CONNECTING, CONNECTED, REFUSED }

    companion object {
        private const val TAG = "HubClient"

        /** C1.4 — the WS-level heartbeat. */
        private const val WS_PING_SECONDS = 20L

        /** C1.4 — the application-level heartbeat. */
        private const val APP_PING_MS = 15_000L

        /** C1.4 — no inbound frame at all for this long means the link is dead. */
        private const val INBOUND_SILENCE_MS = 50_000L

        /** C1.5 — first retry delay; it doubles up to the cap. */
        private const val BACKOFF_MIN_MS = 1_000L
        private const val BACKOFF_MAX_MS = 60_000L

        /** C1.5 — a refused credential is retried once a minute, not on backoff. */
        private const val REFUSED_RETRY_MS = 60_000L

        /** C1.5 — `hub_paused` means "wait a minute", not "retry now". */
        private const val PAUSED_RETRY_MS = 60_000L

        /** [F-007] The reconnect delay after deliberately switching back to the preferred relay. */
        private const val SWITCH_RETRY_MS = 500L

        /** The probe must not hang the loop: the endpoint may be a black hole. */
        private const val PROBE_TIMEOUT_S = 5L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The http client. Built per start() because the route may differ per
     * configuration.
     *
     * Two ways to reach the endpoint:
     *  - an explicit "proxy" in hub.json — the old escape hatch, used verbatim;
     *  - otherwise the app's network stack. The hub endpoint is a tailnet
     *    name, which only resolves inside the embedded tsnet, so the socket
     *    must leave through the local entry. The MCP scope is reused because
     *    it is already the scope routed to Tailscale for the pchub bridge.
     *
     * Route stability is the reason this matters: on the phone's own route
     * the VPN tears the websocket down every 20-40 seconds; through the
     * entry the same socket stays up for hours.
     */
    private var http: OkHttpClient = defaultHttp(null)

    /** AppLogger survives the release build; android.util.Log does not. */
    private fun log(message: String) = AppLogger.info(TAG, message)

    private fun defaultHttp(proxy: String?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .pingInterval(WS_PING_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
        if (!proxy.isNullOrBlank()) {
            try {
                val parts = proxy.split(":")
                val host = parts[0].trim()
                val port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 7890
                builder.proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(host, port)))
                AppLogger.info(TAG, "proxy = $host:$port")
            } catch (e: Exception) {
                AppLogger.info(TAG, "proxy ignored: ${e.message}")
            }
        }
        val base = builder.build()
        // An explicit proxy wins; otherwise follow Settings > Network (MCP).
        return if (proxy.isNullOrBlank()) NetworkHttp.following(NetworkScope.MCP.tag, base) else base
    }

    private var socket: WebSocket? = null
    /** The endpoints to try, in order of preference (LAN first). */
    private var urls: List<String> = emptyList()
    private var urlIndex = 0
    private var apiKey: String? = null
    private var hello: JSONObject? = null

    private val outFrames = AtomicInteger(0)
    private val outBytes = AtomicLong(0)
    private val lastInbound = AtomicLong(0)
    private var backoffMs = BACKOFF_MIN_MS
    private var pingJob: Job? = null
    private var watchdogJob: Job? = null
    /**
     * [F-007] The periodic probe of the preferred relay. It is what brings
     * the phone back to the direct endpoint after the PC restarted — without
     * anyone editing the phone's configuration.
     */
    private var probeJob: Job? = null
    /** Set while we deliberately move back to the preferred relay. */
    @Volatile
    private var preferredSwitch = false
    /**
     * Set by [stop]. Without it the close callback runs the reconnect path
     * and the socket comes back on its own — an orphan connection that then
     * fights the next one for the same device id.
     */
    @Volatile
    private var stopped = false

    @Volatile
    var state: State = State.IDLE
        private set

    /** Connect (or reconnect) with the given endpoint. */
    fun start(urls: List<String>, apiKey: String, hello: JSONObject, proxy: String? = null) {
        http = defaultHttp(proxy)
        this.urls = urls.filter { it.isNotBlank() }
        this.urlIndex = 0
        this.apiKey = apiKey
        this.hello = hello
        stopped = false
        openSocket()
        startPreferredProbe()
    }

    fun stop() {
        stopped = true
        pingJob?.cancel()
        watchdogJob?.cancel()
        probeJob?.cancel()
        socket?.close(1000, "client stop")
        socket = null
        state = State.IDLE
    }

    private fun openSocket() {
        if (stopped) return
        val index = urlIndex
        val endpoint = urls.getOrNull(index) ?: return
        val key = apiKey ?: return
        state = State.CONNECTING
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $key")
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                AppLogger.info(TAG, "socket open: $endpoint")
                state = State.CONNECTED
                backoffMs = BACKOFF_MIN_MS
                // [F-007] Remember WHICH endpoint opened. Writing 0 here
                // made the next drop move "one past the preferred one" —
                // from the fallback that meant straight back to the
                // fallback, so the phone never returned to the direct
                // endpoint after a PC restart.
                urlIndex = index
                lastInbound.set(System.currentTimeMillis())
                hello?.let { send(it) }
                startHeartbeats()
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastInbound.set(System.currentTimeMillis())
                backoffMs = BACKOFF_MIN_MS
                if (text.length > HubFrames.MAX_FRAME_BYTES) {
                    AppLogger.warning(TAG, "inbound frame over the ceiling: ${text.length}")
                    return
                }
                val frame = HubFrames.parse(text)
                if (frame == null) {
                    // C2.1 — bad frame, keep the socket open.
                    send(HubFrames.error("bad_frame", "unparseable frame"))
                    return
                }
                listener.onFrame(frame)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                AppLogger.info(TAG, "closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                AppLogger.info(TAG, "closed: $code $reason")
                onLinkDown(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                AppLogger.warning(TAG, "failure: ${t.message}")
                onLinkDown(-1, t.message ?: "")
            }
        })
    }

    private fun onLinkDown(code: Int, reason: String) {
        pingJob?.cancel()
        watchdogJob?.cancel()
        socket = null
        if (stopped) {
            state = State.IDLE
            return
        }
        when {
            code == 4001 || code == 4002 -> {
                // C1.5 — the credential is bad or the identity is taken.
                state = State.REFUSED
                listener.onRefused(code, reason)
                scheduleRetry(REFUSED_RETRY_MS)
            }
            code == 4003 && reason.contains("hub_paused") -> {
                state = State.CONNECTING
                scheduleRetry(PAUSED_RETRY_MS)
            }
            code == 4003 -> {
                // Replaced by a newer socket of the same id: reconnect.
                state = State.CONNECTING
                scheduleRetry(1_000L)
            }
            else -> {
                state = State.CONNECTING
                listener.onDisconnected(code, reason)
                if (preferredSwitch) {
                    // [F-007] We are going back to the preferred relay on
                    // purpose — do not push the index past it.
                    preferredSwitch = false
                    scheduleRetry(SWITCH_RETRY_MS)
                    return
                }
                // [F-007] A drop on the preferred endpoint falls back to an
                // alternative; a drop on an alternative goes back to the
                // preferred one (the PC may just have restarted).
                urlIndex = RelayChoice.nextIndex(urlIndex, urls.size)
                scheduleRetry(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
            }
        }
    }

    private fun scheduleRetry(delayMs: Long) {
        scope.launch {
            delay(delayMs)
            if (state != State.IDLE) openSocket()
        }
    }

    /**
     * [F-007] Probe the preferred relay while we sit on an alternative.
     *
     * The PC's direct endpoint comes back on its own after a restart; the
     * phone has to notice. A plain GET is enough — the endpoint answers
     * "426 Upgrade Required" to it, which means the process is up.
     */
    private fun startPreferredProbe() {
        probeJob?.cancel()
        probeJob = scope.launch {
            // [T-003] Consecutive failures double the wait, capped at
            // RelayChoice.PROBE_MAX_MS; the first success (or a return to the
            // preferred endpoint) puts it back to PROBE_MS. A PC that is off
            // for hours is then one probe per five minutes, not one every
            // 30 s.
            var delayMs = RelayChoice.PROBE_MS
            while (true) {
                delay(delayMs)
                if (stopped) return@launch
                if (state != State.CONNECTED) continue
                if (!RelayChoice.shouldProbe(urlIndex, urls.size)) {
                    delayMs = RelayChoice.PROBE_MS
                    continue
                }
                val alive = preferredAlive()
                delayMs = RelayChoice.nextProbeDelay(alive, delayMs)
                if (alive) {
                    AppLogger.info(TAG, "preferred relay is back — switching back")
                    switchToPreferred()
                    // [F-007b] Do NOT end the loop after a successful switch.
                    // This job is the ONLY thing that brings the phone home;
                    // ending it made the return one-shot. On 2026-11-17 the
                    // first probe returned the phone to the direct endpoint,
                    // then the executor was restarted again, the phone fell
                    // back to the relay — and with the probe gone it stayed
                    // there indefinitely. Keep probing for the life of the
                    // client; while on the preferred endpoint shouldProbe()
                    // makes every tick a no-op.
                } else if (delayMs > RelayChoice.PROBE_MS) {
                    AppLogger.info(TAG, "preferred relay probe backoff: next attempt in ${delayMs / 1000}s")
                }
            }
        }
    }

    /** Whether the preferred relay answers right now. */
    private suspend fun preferredAlive(): Boolean {
        val endpoint = urls.firstOrNull() ?: return false
        val key = apiKey ?: return false
        return try {
            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $key")
                .build()
            val probe = http.newBuilder()
                .callTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
                .connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
                .build()
            probe.newCall(request).execute().use { response ->
                // Any HTTP answer means the endpoint is up: a websocket path
                // answers "426 Upgrade Required" to a plain GET.
                AppLogger.info(TAG, "preferred relay probe: HTTP ${response.code}")
                true
            }
        } catch (e: Exception) {
            AppLogger.info(TAG, "preferred relay probe failed: ${e.message}")
            false
        }
    }

    /** Move back to the preferred relay; the close callback reconnects. */
    private fun switchToPreferred() {
        preferredSwitch = true
        urlIndex = 0
        socket?.cancel()
    }

    private fun startHeartbeats() {        pingJob?.cancel()
        watchdogJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(APP_PING_MS)
                send(HubFrames.ping())
            }
        }
        watchdogJob = scope.launch {
            while (true) {
                delay(5_000L)
                val silent = System.currentTimeMillis() - lastInbound.get()
                if (silent > INBOUND_SILENCE_MS && state == State.CONNECTED) {
                    AppLogger.warning(TAG, "no inbound frame for ${silent}ms — dropping the link")
                    socket?.cancel()
                    return@launch
                }
            }
        }
    }

    /**
     * Send one frame. Returns false when the outbound budget is exhausted —
     * the caller must fail loudly (contract C8.2), never queue forever.
     */
    fun send(frame: JSONObject): Boolean {
        val text = frame.toString()
        // [H-004] The command inside the frame as it goes on the wire. The PC
        // side logs the same value; two logs, one comparison.
        frame.optJSONObject("args")?.optString("command")?.takeIf { it.isNotEmpty() }?.let { command ->
            AppLogger.info(TAG, "hub frame command: ${debugRepr(command)}")
        }
        val size = text.toByteArray(Charsets.UTF_8).size
        if (outFrames.get() >= HubFrames.MAX_OUT_FRAMES || outBytes.get() + size > HubFrames.MAX_OUT_BYTES) {
            listener.onError("hub send queue is full (${outFrames.get()} frames / ${outBytes.get()} bytes)")
            return false
        }
        val ws = socket ?: return false
        val ok = ws.send(text)
        if (ok) {
            outFrames.incrementAndGet()
            outBytes.addAndGet(size.toLong())
        }
        return ok
    }

    /** Called once an inbound frame was handled: the queue has room again. */
    fun noteInbound() {
        outFrames.set(0)
        outBytes.set(0)
    }
}
