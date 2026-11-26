package com.openminis.app.speech.wake

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.OverlayPosition
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Floating "voice status ball" shown while Minis is in the background.
 *
 * It is deliberately independent from [com.openminis.app.service.ToolOverlayController]:
 * the tool capsule is a half-screen pill anchored bottom-left and only exists
 * while an agent turn is running; this one is a compact ball anchored to the
 * right edge and exists whenever wake-word listening is enabled.
 *
 * The ball has three looks (see [VoiceBallState]) and every one of them keeps
 * moving, so the current state reads at a glance without any text:
 *  - asleep — dim microphone, one slow breath per cycle;
 *  - listening — microphone beats like a pulse, a ring pulse leaves the ball;
 *  - replying — speaker bounces, three sound waves radiate out.
 *
 * One animator drives both the icon and the ring so the two never drift apart.
 * The sleep motion is intentionally slow and low-amplitude: this window can
 * stay on screen for hours.
 */
class VoiceStatusOverlay(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)

    private var root: LinearLayout? = null
    private var ring: PulseRingView? = null
    private var ball: View? = null
    private var icon: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var latest: VoiceWakeSnapshot = VoiceWakeSnapshot()

    /** Shared clock for the icon and the ring: 0..1, one full cycle. */
    private var animator: ValueAnimator? = null
    private var cycleFraction = 0f

    /** Animators are disabled system-wide (battery saver / accessibility). */
    private var staticFrame = false

    private var ballState = VoiceBallState.SLEEP

    /** State currently painted by [icon]; avoids re-decoding the drawable. */
    private var drawnState: VoiceBallState? = null

    /** Throttles the long-running sleep animation; see [applyFrame]. */
    private var lastFrameAt = 0L
    private var lastFrameState: VoiceBallState? = null

    var isShown: Boolean = false
        private set

    private val positionStore = object : OverlayPosition.Store {
        override fun get(orientation: OverlayPosition.Orientation): OverlayPosition.Point? =
            WakeWordSettings.getOverlayPosition(orientation == OverlayPosition.Orientation.LANDSCAPE)
                ?.let { OverlayPosition.Point(it.first, it.second) }

        override fun set(orientation: OverlayPosition.Orientation, point: OverlayPosition.Point) =
            WakeWordSettings.setOverlayPosition(
                point.x,
                point.y,
                orientation == OverlayPosition.Orientation.LANDSCAPE,
            )
    }

    fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun update(snapshot: VoiceWakeSnapshot) {
        latest = snapshot
        main.post { apply(snapshot) }
    }

    fun setVisible(visible: Boolean) {
        main.post {
            if (visible) {
                if (!hasOverlayPermission()) {
                    if (isShown) hideInternal()
                    return@post
                }
                if (root == null) attach()
                apply(latest)
            } else if (isShown) {
                hideInternal()
            }
        }
    }

    fun hide() {
        main.post { hideInternal() }
    }

    fun onConfigurationChanged() {
        main.post {
            if (!isShown) return@post
            hideInternal()
            if (hasOverlayPermission()) {
                attach()
                apply(latest)
            }
        }
    }

    private fun apply(snapshot: VoiceWakeSnapshot) {
        val r = ring ?: return
        val b = ball ?: return
        val icon = icon ?: return
        val color = when (snapshot.phase) {
            VoiceWakePhase.WAITING,
            VoiceWakePhase.LISTENING,
            VoiceWakePhase.THINKING,
            VoiceWakePhase.REPLYING,
            VoiceWakePhase.WAKING,
            -> COLOR_ACTIVE
            VoiceWakePhase.PAUSED -> COLOR_PAUSED
            VoiceWakePhase.ERROR -> COLOR_ERROR
            VoiceWakePhase.OFF -> COLOR_OFF
        }
        // Hollow ball: the outline drawn by PulseRingView is the state colour,
        // while the disc itself stays transparent. The icon follows the same
        // colour so it remains visible on light wallpapers.
        r.color = color
        icon.setColorFilter(color)

        // The icon is the state: a microphone while the assistant sleeps or
        // listens, a loudspeaker while it answers.
        val next = snapshot.phase.toBallState()
        ballState = next
        if (drawnState != next) {
            icon.setImageResource(
                if (next == VoiceBallState.SPEAK) {
                    R.drawable.ic_voice_speaker
                } else {
                    R.drawable.ic_voice_mic
                },
            )
            drawnState = next
        }

        ensureAnimator()
        applyFrame()

        val detail = snapshot.detail.trim()
        root?.contentDescription = detail.ifEmpty {
            context.getString(
                when (snapshot.phase) {
                    VoiceWakePhase.OFF -> R.string.voice_wake_state_off
                    VoiceWakePhase.WAITING -> R.string.voice_wake_state_waiting
                    VoiceWakePhase.WAKING -> R.string.voice_wake_state_waking
                    VoiceWakePhase.LISTENING -> R.string.voice_wake_state_listening
                    VoiceWakePhase.THINKING -> R.string.voice_wake_state_thinking
                    VoiceWakePhase.REPLYING -> R.string.voice_wake_state_replying
                    VoiceWakePhase.PAUSED -> R.string.voice_wake_state_paused
                    VoiceWakePhase.ERROR -> R.string.voice_wake_state_error
                },
            )
        }
    }

    /**
     * Starts the shared clock on first use. When system animations are off we
     * paint one representative frame instead: a `ValueAnimator` with a zero
     * duration scale would otherwise spin the main thread while doing nothing.
     */
    private fun ensureAnimator() {
        if (animator != null || staticFrame) return
        if (!ValueAnimator.areAnimatorsEnabled()) {
            staticFrame = true
            cycleFraction = STATIC_FRAME_FRACTION
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                cycleFraction = it.animatedFraction
                applyFrame()
            }
            start()
        }
    }

    /** Paints one frame from [cycleFraction] for the current [ballState]. */
    private fun applyFrame() {
        val icon = icon ?: return
        val r = ring ?: return
        // Sleep can sit for hours and thinking can wait on a model for tens of
        // seconds; both run fine at 20fps and save the redraws. Listening and
        // replying keep the full rate.
        val now = SystemClock.elapsedRealtime()
        val slow = ballState == VoiceBallState.SLEEP || ballState == VoiceBallState.THINK
        if (slow &&
            lastFrameState == ballState &&
            now - lastFrameAt < SLEEP_FRAME_INTERVAL_MS
        ) {
            return
        }
        lastFrameAt = now
        lastFrameState = ballState
        val f = cycleFraction
        when (ballState) {
            // Asleep: a slow breath — dim, shrink a touch, come back. Low
            // amplitude on purpose: this runs for as long as the ball is up.
            VoiceBallState.SLEEP -> {
                val breath = ((1.0 - cos(2.0 * PI * f)) / 2.0).toFloat()
                val scale = 0.94f + 0.06f * breath
                icon.scaleX = scale
                icon.scaleY = scale
                icon.alpha = 0.55f + 0.35f * breath
                r.mode = RingMode.NONE
                r.phase = f
            }
            // Listening: the microphone beats, a pulse leaves the ball once
            // per beat.
            VoiceBallState.LISTEN -> {
                val p = (f * LISTEN_BEATS) % 1f
                val beat = sin(PI * p).toFloat()
                val scale = 1f + 0.09f * beat
                icon.scaleX = scale
                icon.scaleY = scale
                icon.alpha = 1f
                r.mode = RingMode.PULSE
                r.phase = p
            }
            // Working: an arc runs around the hollow ball. This is the only
            // rotational look, so it reads apart from the radial pulse/waves.
            VoiceBallState.THINK -> {
                icon.scaleX = 1f
                icon.scaleY = 1f
                icon.alpha = 0.85f + 0.15f * sin(PI * f).toFloat()
                r.mode = RingMode.SPIN
                r.phase = f
            }
            // Replying: the speaker bounces in step with the outgoing waves.
            VoiceBallState.SPEAK -> {
                val p = (f * SPEAK_WAVES) % 1f
                val bounce = sin(PI * p).toFloat()
                val scale = 1f + 0.05f * bounce
                icon.scaleX = scale
                icon.scaleY = scale
                icon.alpha = 1f
                r.mode = RingMode.WAVE
                r.phase = p
            }
        }
        r.invalidate()
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
        staticFrame = false
        cycleFraction = 0f
    }

    private fun attach() {
        val container = buildView()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        container.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val dm = context.resources.displayMetrics
        val at = OverlayPosition.displayed(
            positionStore,
            defaultPosition(dm.widthPixels, dm.heightPixels, container.measuredWidth, container.measuredHeight),
            dm.widthPixels,
            dm.heightPixels,
            container.measuredWidth.coerceAtLeast(dp(48)),
            container.measuredHeight.coerceAtLeast(dp(56)),
            OverlayPosition.avoidFor(
                OverlayPosition.Orientation.of(dm.widthPixels, dm.heightPixels),
                left = 0,
                right = 0,
                bottom = 0,
                top = statusBarHeight(),
            ),
        )
        p.x = at.x
        p.y = at.y

        attachDrag(container, p)
        runCatching {
            windowManager.addView(container, p)
            root = container
            params = p
            isShown = true
            AppLogger.info(TAG, "语音悬浮球显示 at=${p.x},${p.y}")
        }.onFailure {
            AppLogger.info(TAG, "语音悬浮球添加失败：${it.javaClass.simpleName}: ${it.message}")
            root = null
            params = null
            isShown = false
        }
    }

    private fun buildView(): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            // No plate under the ball: the circle IS the indicator. A
            // capsule behind it made the state harder to read at a glance
            // and covered whatever the user was doing.
            setPadding(dp(4), dp(4), dp(4), dp(4))
            elevation = dp(8).toFloat()
        }

        // The frame is larger than the ball so the ripples have room to
        // travel: a ring clipped to the 40dp ball was barely visible.
        val ballFrame = FrameLayout(context)
        val ballSize = dp(BALL_SIZE_DP)
        val frameSize = dp(BALL_FRAME_DP)
        ballFrame.addView(
            View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    // Hollow: PulseRingView supplies the coloured outline.
                    setColor(Color.TRANSPARENT)
                }
            },
            FrameLayout.LayoutParams(ballSize, ballSize, Gravity.CENTER),
        )
        ball = ballFrame.getChildAt(0)

        val ring = PulseRingView(context, ballSize.toFloat())
        ballFrame.addView(
            ring,
            FrameLayout.LayoutParams(frameSize, frameSize, Gravity.CENTER),
        )
        this.ring = ring

        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_voice_mic)
            setColorFilter(COLOR_ACTIVE)
        }
        ballFrame.addView(icon, FrameLayout.LayoutParams(dp(ICON_SIZE_DP), dp(ICON_SIZE_DP), Gravity.CENTER))
        this.icon = icon

        container.addView(ballFrame, LinearLayout.LayoutParams(frameSize, frameSize))
        return container
    }

    private fun attachDrag(container: LinearLayout, p: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        val slop = dp(6)

        container.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        p.x = startX + dx
                        p.y = startY + dy
                        runCatching { windowManager.updateViewLayout(view, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        val dm = context.resources.displayMetrics
                        OverlayPosition.dragEnded(
                            positionStore,
                            dm.widthPixels,
                            dm.heightPixels,
                            OverlayPosition.Point(p.x, p.y),
                        )
                    } else {
                        view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        container.setOnClickListener { openVoiceChat() }
    }

    private fun openVoiceChat() {
        val sessionId = WakeWordSettings.voiceSessionId.value
        val uri = if (sessionId.isNullOrBlank()) {
            "minis://action/new_chat"
        } else {
            "minis://session/$sessionId"
        }
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(uri),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { AppLogger.info(TAG, "打开语音对话失败：${it.message}") }
    }

    private fun hideInternal() {
        stopAnimation()
        val view = root ?: return
        runCatching { windowManager.removeView(view) }
        root = null
        ring = null
        ball = null
        icon = null
        params = null
        drawnState = null
        isShown = false
    }

    private fun defaultPosition(screenW: Int, screenH: Int, viewW: Int, viewH: Int): OverlayPosition.Point {
        val width = viewW.coerceAtLeast(dp(100))
        val height = viewH.coerceAtLeast(dp(60))
        return OverlayPosition.Point(
            x = (screenW - width - dp(8)).coerceAtLeast(dp(8)),
            y = (screenH * 0.38f - height / 2f).toInt().coerceAtLeast(statusBarHeight() + dp(8)),
        )
    }

    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /**
     * Rings around the ball. It owns no animator: [VoiceStatusOverlay] pushes
     * the shared [phase] every frame, so icon and ring stay in step.
     */
    private class PulseRingView(context: Context, ballDiameterPx: Float) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
        }
        private val ballRadius = ballDiameterPx / 2f

        var color: Int = COLOR_ACTIVE
        var mode: RingMode = RingMode.NONE
        var phase: Float = 0f
        private val spinRect = android.graphics.RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            // Start at the hoop's own edge so the pulse looks like it
            // leaves the ball rather than appearing inside it.
            val base = ballRadius - paint.strokeWidth / 2f
            paint.color = color
            when (mode) {
                RingMode.NONE -> {
                    // Idle: the plain outline, nothing moving.
                    paint.alpha = 210
                    canvas.drawCircle(cx, cy, base, paint)
                }
                RingMode.PULSE -> {
                    paint.alpha = 210
                    canvas.drawCircle(cx, cy, base, paint)
                    if (phase > 0f) {
                        paint.alpha = ((1f - phase) * 180).toInt()
                        canvas.drawCircle(cx, cy, base + phase * base * 0.34f, paint)
                    }
                }
                RingMode.WAVE -> {
                    // Answering: sound waves going out of the ball.
                    val waves = 3
                    for (i in 0 until waves) {
                        val p = (phase + i.toFloat() / waves) % 1f
                        paint.alpha = ((1f - p) * 180).toInt()
                        canvas.drawCircle(cx, cy, base + p * base * 0.55f, paint)
                    }
                }
                RingMode.SPIN -> {
                    // Working: a 90° arc runs around the hollow ball. Its
                    // rotation is the only directional cue in the four looks.
                    paint.alpha = 220
                    spinRect.set(cx - base, cy - base, cx + base, cy + base)
                    val start = -90f + (phase * 360f * THINK_TURNS) % 360f
                    canvas.drawArc(spinRect, start, 90f, false, paint)
                }
            }
        }
    }

    private enum class RingMode { NONE, PULSE, WAVE, SPIN }

    private companion object {
        const val TAG = "VoiceWake"
        /**
         * The ball is only a little bigger than the microphone glyph inside
         * it — a big disc covered a chunk of whatever the user was doing.
         */
        const val BALL_SIZE_DP = 40
        const val ICON_SIZE_DP = 28

        /** Transparent frame around the ball; gives the ripples room to move. */
        const val BALL_FRAME_DP = 64

        /** One shared cycle; the per-state periods are integer fractions of it. */
        const val CYCLE_MS = 4800L

        /** Pulse every 1600ms, wave every 1200ms; both divide [CYCLE_MS]. */
        const val LISTEN_BEATS = 3f
        const val SPEAK_WAVES = 4f

        /** Working arc turns twice per shared cycle (one turn every 2400ms). */
        const val THINK_TURNS = 2f

        /** A representative still when system animations are turned off. */
        const val STATIC_FRAME_FRACTION = 0.25f

        /** 20fps is plenty for the slow sleep breath; see [applyFrame]. */
        const val SLEEP_FRAME_INTERVAL_MS = 50L

        /** Green at 96% opacity (0xF5 = 245/255). */
        const val COLOR_ACTIVE = 0xF534C759.toInt()
        const val COLOR_PAUSED = 0xF5FF9F0A.toInt()
        const val COLOR_ERROR = 0xF5FF453A.toInt()
        const val COLOR_OFF = 0xF58E8E93.toInt()
    }
}
