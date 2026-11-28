package com.openminis.app.assistant.execution

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.openminis.app.assistant.notes.NotesRepository
import com.openminis.app.assistant.routing.RouteDecision
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.offload.MediaPlayerManager
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.offload.AlarmOffloadHandler
import com.openminis.app.sandbox.offload.AlarmTimeParser
import com.openminis.app.sandbox.offload.CalendarOffloadHandler
import com.openminis.app.sandbox.offload.ContactsOffloadHandler
import com.openminis.app.sandbox.offload.DeviceOffloadHandler
import com.openminis.app.sandbox.offload.LocationOffloadHandler
import com.openminis.app.sandbox.offload.NotificationOffloadHandler
import com.openminis.app.sandbox.offload.OpenOffloadHandler
import com.openminis.app.sandbox.offload.PlayerOffloadHandler
import com.openminis.app.sandbox.offload.WeatherOffloadHandler
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.scheduled.ScheduledTriggerKind
import com.openminis.app.speech.SpeechAside
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class LocalExecutionResult(
    val output: String,
    val success: Boolean,
    val toolName: String,
    val title: String,
    /**
     * 助手气泡里显示的正文。空串表示只显示标题。
     * 由 [LocalResultFormatter] 生成：JSON 结果翻成一句话，纯文本原样显示。
     */
    val answer: String = "",
    /**
     * 只读信息类结果的简短语音摘要（新闻标题、指数行情）。
     * 空串表示不自动播报；不影响气泡里的完整 [answer]。
     */
    val spokenSummary: String = "",
    /**
     * [T-alarm-truthful-result] The action was handed to the system, but
     * somebody else still has to confirm it — the OEM Clock app asks the
     * user to tap the confirm button itself.
     *
     * This is neither success nor failure: the app did everything it could,
     * and the alarm exists only after the user confirms it in the Clock app.
     * The bubble and the spoken completion must say so instead of claiming
     * success or crying failure.
     */
    val needsUserConfirmation: Boolean = false,
)

/**
 * A preflight result for a local write. Calendar update/delete must resolve a
 * title to one event before the confirmation card is shown; a broad title is
 * returned as a choice rather than letting the executor pick the first row.
 */
data class LocalRoutePreparation(
    val decision: RouteDecision.Local,
    val choices: List<CalendarEventCandidate> = emptyList(),
    val totalChoices: Int = choices.size,
    val error: String? = null,
)

/**
 * Executes only the local life capabilities. Existing agent tools continue to
 * use ChatViewModel.executeTool so their block and permission behaviour stays
 * byte-for-byte compatible.
 */
class LocalCapabilityExecutor(private val context: Context) {

    private val notes = NotesRepository.get(context)
    private val scheduled = ScheduledTaskManager(context)

    suspend fun prepare(decision: RouteDecision.Local): LocalRoutePreparation =
        withContext(Dispatchers.IO) {
            if (decision.capability != "calendar" ||
                decision.action !in setOf("update", "delete") ||
                decision.args.has("id")
            ) {
                return@withContext LocalRoutePreparation(decision)
            }
            val query = decision.args.optString("id_or_title")
                .ifBlank { decision.args.optString("title") }
                .trim()
            if (query.isBlank()) {
                return@withContext LocalRoutePreparation(
                    decision,
                    error = "没有找到要操作的日程名称，未执行。",
                )
            }

            val start = LocalDateTime.now().minusYears(1)
            val end = LocalDateTime.now().plusYears(1)
            val result = offload(
                "android-calendar",
                CalendarOffloadHandler(context),
                listOf(
                    "android-calendar", "list",
                    "--start", start.format(ISO),
                    "--end", end.format(ISO),
                    "--limit", "100",
                ),
            )
            if (result.exitCode != 0) {
                return@withContext LocalRoutePreparation(
                    decision,
                    error = "查询日程失败，未执行：${result.output.trim()}",
                )
            }
            val events = CalendarCandidateMatcher.parseEvents(result.output)
            return@withContext when (val match = CalendarCandidateMatcher.match(events, query)) {
                is CalendarMatchResult.Found -> {
                    val args = JSONObject(decision.args.toString()).put("id", match.event.id.toString())
                    LocalRoutePreparation(decision.copy(args = args))
                }
                is CalendarMatchResult.Multiple -> LocalRoutePreparation(
                    decision = decision,
                    choices = match.events,
                    totalChoices = match.total,
                )
                CalendarMatchResult.None -> LocalRoutePreparation(
                    decision,
                    error = "没有找到匹配“$query”的日程，未执行。",
                )
            }
        }

    suspend fun execute(
        decision: RouteDecision.Local,
        sessionId: String,
    ): LocalExecutionResult = withContext(Dispatchers.IO) {
        val args = decision.args
        // media 的 play 分支要写回 tool_title；decision.args 与 UI 共享
        // （JSONObject 非线程安全），执行中改写它有并发读风险。给该分支
        // 一个私有副本，标题随后从副本读回。
        var titleArgs = args
        val result = try {
            when (decision.capability) {
                "calendar" -> calendar(decision.action, args)
                "alarm" -> alarm(decision.action, args)
                "reminder" -> reminder(decision.action, args, sessionId)
                "note" -> note(decision.action, args)
                "settings" -> settings(decision.action, args)
                "scheduled_task" -> scheduledTask(decision.action, args, sessionId)
                "weather" -> weather(decision.action, args, sessionId)
                "device" -> device(decision.action, args)
                "flashlight" -> flashlight(decision.action)
                "volume" -> volume(decision.action, args)
                "media" -> {
                    titleArgs = JSONObject(args.toString())
                    media(decision.action, titleArgs, decision.route.label)
                }
                "contacts" -> contacts(decision.action, args)
                "app_launch" -> appLaunch(decision.action, args)
                else -> NativeOffloadResult(2, "Unsupported local capability: ${decision.capability}")
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            AppLogger.warning(TAG, "${decision.capability}/${decision.action} failed: ${t.message}")
            NativeOffloadResult(1, "本地执行失败：${t.message ?: t.javaClass.simpleName}")
        }
        val success = result.exitCode == 0
        // [T-alarm-truthful-result] exit code 3 = 「已经交出去了，但要用户
        // 自己在系统时钟里确认」。既不是成功也不是失败，气泡和播报都要
        // 如实这么说。
        val needsUserConfirmation = result.exitCode == EXIT_NEEDS_USER_CONFIRMATION
        val answer = LocalResultFormatter.answer(
            capability = decision.capability,
            action = decision.action,
            output = result.output,
            success = success,
        )
        LocalExecutionResult(
            output = result.output.trim(),
            success = success,
            toolName = decision.capability,
            title = titleArgs.optString("tool_title").ifBlank { decision.route.label },
            answer = answer,
            spokenSummary = LocalSpokenSummary.forCapability(
                capability = decision.capability,
                action = decision.action,
                output = result.output,
                answer = answer,
                success = success,
            ),
            needsUserConfirmation = needsUserConfirmation,
        )
    }

    private fun calendar(action: String, args: JSONObject): NativeOffloadResult {
        when (action) {
            "list" -> {
                val argv = mutableListOf("android-calendar", "list")
                args.optString("start").takeIf { it.isNotBlank() }?.let {
                    argv += listOf("--start", it)
                }
                args.optString("end").takeIf { it.isNotBlank() }?.let {
                    argv += listOf("--end", it)
                }
                argv += listOf("--limit", "100")
                return offload("android-calendar", CalendarOffloadHandler(context), argv)
            }
            "freebusy" -> {
                val argv = mutableListOf("android-calendar", "freebusy")
                args.optString("start").takeIf { it.isNotBlank() }?.let {
                    argv += listOf("--start", it)
                }
                args.optString("end").takeIf { it.isNotBlank() }?.let {
                    argv += listOf("--end", it)
                }
                return offload("android-calendar", CalendarOffloadHandler(context), argv)
            }
            "create" -> {
                val argv = mutableListOf(
                    "android-calendar", "create",
                    "--title", args.optString("title", "日程"),
                    "--start", args.optString("start"),
                )
                args.optString("end").takeIf { it.isNotBlank() }?.let {
                    argv += listOf("--end", it)
                }
                if (args.optBoolean("allDay", false)) argv += "--all-day"
                args.optString("location").takeIf { it.isNotBlank() }?.let {
                    argv += listOf("--location", it)
                }
                args.optString("notes").takeIf { it.isNotBlank() }?.let {
                    argv += listOf("--notes", it)
                }
                if (args.has("alarmMinutes")) {
                    argv += listOf("--alarm", args.optInt("alarmMinutes").toString())
                }
                return offload("android-calendar", CalendarOffloadHandler(context), argv)
            }
            "delete", "update" -> {
                val id = resolveCalendarId(args)
                    ?: return NativeOffloadResult(
                        1,
                        "找到多个或无匹配日程，请说得更具体，或使用日程 id。",
                    )
                if (action == "delete") {
                    return offload(
                        "android-calendar",
                        CalendarOffloadHandler(context),
                        listOf("android-calendar", "delete", "--id", id.toString()),
                    )
                }
                val argv = mutableListOf("android-calendar", "update", "--id", id.toString())
                args.optString("title").takeIf { it.isNotBlank() && it != "日程" }?.let {
                    argv += listOf("--title", it)
                }
                args.optString("start").takeIf { it.isNotBlank() }?.let {
                    argv += listOf("--start", it)
                }
                args.optString("end").takeIf { it.isNotBlank() }?.let {
                    argv += listOf("--end", it)
                }
                return offload("android-calendar", CalendarOffloadHandler(context), argv)
            }
        }
        return NativeOffloadResult(2, "Unsupported calendar action: $action")
    }

    private fun resolveCalendarId(args: JSONObject): Long? {
        if (args.has("id")) return args.optString("id").toLongOrNull()
        val query = args.optString("id_or_title").ifBlank { args.optString("title") }
        if (query.isBlank()) return null
        val start = LocalDateTime.now().minusYears(1)
        val end = LocalDateTime.now().plusYears(1)
        val argv = listOf(
            "android-calendar", "list",
            "--start", start.format(ISO),
            "--end", end.format(ISO),
            "--limit", "100",
        )
        val result = offload("android-calendar", CalendarOffloadHandler(context), argv)
        if (result.exitCode != 0) return null
        val events = CalendarCandidateMatcher.parseEvents(result.output)
        return when (val match = CalendarCandidateMatcher.match(events, query)) {
            is CalendarMatchResult.Found -> match.event.id
            is CalendarMatchResult.Multiple -> {
                val choices = match.events.joinToString("；") {
                    "id=${it.id} ${it.title} ${it.start}"
                }
                throw IllegalStateException("匹配到多个日程：$choices")
            }
            CalendarMatchResult.None -> null
        }
    }

    private fun alarm(action: String, args: JSONObject): NativeOffloadResult {
        val handler = AlarmOffloadHandler(context)
        return when (action) {
            "set" -> {
                val time = args.optString("time")
                if (time.isBlank()) return NativeOffloadResult(2, "没有听清闹钟时间。")
                val target = AlarmTimeParser.parse(time)
                val repeatMode = runCatching {
                    com.openminis.app.offload.RepeatMode.valueOf(
                        args.optString("repeat", "ONCE").uppercase(),
                    )
                }.getOrDefault(com.openminis.app.offload.RepeatMode.ONCE)
                if (target != null) {
                    val unsupported = AlarmTimeParser.unsupportedExplicitDate(target, repeatMode)
                    if (unsupported != null) {
                        return NativeOffloadResult(
                            2,
                            "系统时钟只能设置下一次 ${target.hour.toString().padStart(2, '0')}:" +
                                "${target.minute.toString().padStart(2, '0')}，无法指定 " +
                                "$unsupported。已取消，请改用“提醒我”或定时任务。",
                        )
                    }
                }
                val argv = mutableListOf(
                    "android-alarm", "set",
                    "--time", time,
                    "--label", args.optString("label", "闹钟"),
                )
                args.optString("repeat").takeIf { it.isNotBlank() }?.let {
                    argv += listOf("--repeat", it)
                }
                val dispatched = offload("android-alarm", handler, argv)
                // [T-alarm-truthful-result] 派发成功 ≠ 闹钟建成了。厂商时钟
                // 会弹自己的设置界面要求用户点勾，用户点「取消」时闹钟根本
                // 没建，而这里原来直接报了 success=true。
                //
                // 校验在 [AlarmOffloadHandler] 里做（回读系统里真实注册的
                // 闹钟，只认新增的那些），这样 CLI 路径与快路径共用同一套
                // 判定，不会两处漂移。它已经用 exit code 3 表达了「需要
                // 用户在系统时钟里确认」。
                dispatched
            }
            "timer" -> {
                val seconds = args.optLong("duration")
                if (seconds <= 0) return NativeOffloadResult(2, "没有听清计时时长。")
                offload(
                    "android-alarm", handler,
                    listOf(
                        "android-alarm", "timer",
                        "--duration", seconds.toString(),
                        "--label", args.optString("label", "计时器"),
                    ),
                )
            }
            "open" -> offload(
                "android-alarm", handler,
                listOf("android-alarm", "open"),
            )
            else -> NativeOffloadResult(2, "Unsupported alarm action: $action")
        }
    }

    private fun reminder(
        action: String,
        args: JSONObject,
        sessionId: String,
    ): NativeOffloadResult {
        val handler = NotificationOffloadHandler(context)
        return when (action) {
            "schedule" -> {
                val argv = mutableListOf(
                    "android-notification", "schedule",
                    "--title", args.optString("title", "提醒"),
                    "--body", args.optString("body", args.optString("title", "提醒")),
                )
                when {
                    args.has("after") && args.optLong("after") > 0 ->
                        argv += listOf("--after", args.optLong("after").toString())
                    args.optString("at").isNotBlank() ->
                        argv += listOf("--at", args.optString("at"))
                    else -> return NativeOffloadResult(2, "需要指定提醒时间。")
                }
                offload("android-notification", handler, argv)
            }
            "pending" -> offload(
                "android-notification", handler,
                listOf("android-notification", "pending"),
            )
            "list" -> offload(
                "android-notification", handler,
                listOf("android-notification", "history", "--limit", "50"),
            )
            "cancel" -> {
                val id = args.optString("id").ifBlank {
                    resolvePendingNotificationId(args) ?: ""
                }
                if (id.isBlank()) return NativeOffloadResult(2, "没有找到要取消的提醒。")
                offload(
                    "android-notification", handler,
                    listOf("android-notification", "cancel", "--id", id),
                )
            }
            else -> NativeOffloadResult(2, "Unsupported reminder action: $action")
        }
    }

    private fun resolvePendingNotificationId(args: JSONObject): String? {
        val query = args.optString("id").ifBlank { args.optString("title") }
        if (query.isBlank()) return null
        val result = offload(
            "android-notification", NotificationOffloadHandler(context),
            listOf("android-notification", "pending"),
        )
        if (result.exitCode != 0) return null
        val notifications = runCatching { JSONObject(result.output.trim()).optJSONArray("notifications") }
            .getOrNull() ?: return null
        val matches = mutableListOf<JSONObject>()
        for (i in 0 until notifications.length()) {
            val n = notifications.optJSONObject(i) ?: continue
            // Only a not-yet-claimed reminder can be cancelled; delivering
            // records are already being posted and terminal ones are history.
            val state = n.optString("state")
            if (state.isNotBlank() && state != "pending") continue
            val haystack = n.optString("title") + " " + n.optString("body")
            if (haystack.contains(query, ignoreCase = true)) matches += n
        }
        return matches.singleOrNull()?.optString("id")
    }

    private fun note(action: String, args: JSONObject): NativeOffloadResult {
        return when (action) {
            "create" -> {
                val content = args.optString("content")
                if (content.isBlank()) return NativeOffloadResult(2, "笔记内容为空。")
                val note = notes.create(
                    content = content,
                    title = args.optString("title").takeIf { it.isNotBlank() },
                )
                NativeOffloadResult(
                    0,
                    JSONObject()
                        .put("id", note.id)
                        .put("title", note.title)
                        .put("created", true)
                        .toString(2),
                )
            }
            "search" -> {
                val query = args.optString("query")
                val hits = notes.search(query)
                val arr = JSONArray()
                hits.forEach { (note, excerpt) ->
                    arr.put(
                        JSONObject()
                            .put("id", note.id)
                            .put("title", note.title)
                            .put("excerpt", excerpt)
                            .put("updatedAt", note.updatedAt),
                    )
                }
                NativeOffloadResult(0, JSONObject().put("notes", arr).put("count", arr.length()).toString(2))
            }
            "list" -> {
                val arr = JSONArray()
                notes.list().forEach { arr.put(it.toJson()) }
                NativeOffloadResult(0, JSONObject().put("notes", arr).put("count", arr.length()).toString(2))
            }
            "delete" -> {
                val id = args.optString("id")
                val resolved = id.ifBlank {
                    val hits = notes.search(args.optString("id_or_query"), limit = 5)
                    if (hits.size == 1) hits.first().first.id else ""
                }
                if (resolved.isBlank()) return NativeOffloadResult(2, "没有唯一匹配的笔记。")
                NativeOffloadResult(0, JSONObject().put("deleted", notes.delete(resolved)).put("id", resolved).toString(2))
            }
            else -> NativeOffloadResult(2, "Unsupported note action: $action")
        }
    }

    private fun settings(action: String, args: JSONObject): NativeOffloadResult {
        val target = args.optString("target", "settings")
        if (action == "open") return openSettings(target)
        val value = args.optString("value")
        if (value.isBlank()) return NativeOffloadResult(2, "没有听清要设置的值。")

        // [T-android-settings-direct-write] Battery-saver / performance are written
        // straight to Settings.Global `low_power` via the verified bridge (Shizuku
        // when available, otherwise the WRITE_SECURE_SETTINGS direct write). This
        // works even when Shizuku is stopped — which the old open-the-page fallback
        // did not — and a blank/mismatched readback is an error, not a success.
        if (target in setOf("power_save", "battery_saver", "low_power", "performance", "battery")) {
            val on = when (value.trim().lowercase()) {
                "on", "true", "1", "enable", "enabled", "开", "开启", "打开", "省电", "省电模式", "节能" -> true
                "off", "false", "0", "disable", "disabled", "关", "关闭", "关掉", "性能", "性能模式" -> false
                else -> return NativeOffloadResult(2, "省电 / 性能开关需要「开」或「关」。")
            }
            // "打开性能" means battery saver OFF; everything else maps on -> low_power=1.
            val lowPower = if (target == "performance") !on else on
            val wanted = if (lowPower) "1" else "0"
            val res = com.openminis.app.sandbox.offload.SystemSettingsBridge.write(
                context, "global", "low_power", wanted,
            )
            if (!res.ok) {
                return NativeOffloadResult(1, "省电模式未生效：${res.error ?: "读回与写入不一致"}")
            }
            return NativeOffloadResult(
                0,
                JSONObject()
                    .put("changed", true)
                    .put("target", target)
                    .put("key", "low_power")
                    .put("value", res.expected)
                    .put("actual", res.actual ?: JSONObject.NULL)
                    .put("backend", res.backend?.name?.lowercase() ?: "none")
                    .put("verified", true)
                    .toString(2),
            )
        }

        if (!ShizukuManager.isReady()) {
            val opened = openSettings(target)
            return NativeOffloadResult(
                1,
                "Shizuku 未就绪，已打开对应设置页，请手动调整。\n${opened.output}",
            )
        }

        val command = when (target) {
            "brightness" -> {
                val raw = value.removeSuffix("%").toIntOrNull() ?: return NativeOffloadResult(2, "亮度需要 0-100。")
                val level = (raw.coerceIn(0, 100) * 255 / 100)
                arrayOf(
                    arrayOf("settings", "put", "system", "screen_brightness_mode", "0"),
                    arrayOf("settings", "put", "system", "screen_brightness", level.toString()),
                )
            }
            "screen_timeout" -> {
                val sec = value.toIntOrNull() ?: return NativeOffloadResult(2, "息屏时间需要秒数。")
                arrayOf(arrayOf("settings", "put", "system", "screen_off_timeout", (sec * 1000).toString()))
            }
            "rotation" -> arrayOf(
                arrayOf(
                    "settings", "put", "system", "accelerometer_rotation",
                    if (value == "off") "0" else "1",
                ),
            )
            "dnd" -> arrayOf(
                arrayOf("cmd", "notification", "set_dnd", if (value == "off") "off" else "on"),
            )
            "wifi" -> arrayOf(
                arrayOf("svc", "wifi", if (value == "off") "disable" else "enable"),
            )
            "bluetooth" -> arrayOf(
                arrayOf("svc", "bluetooth", if (value == "off") "disable" else "enable"),
            )
            "airplane" -> arrayOf(
                arrayOf("settings", "put", "global", "airplane_mode_on", if (value == "off") "0" else "1"),
                arrayOf(
                    "am", "broadcast", "-a", "android.intent.action.AIRPLANE_MODE",
                    "--ez", "state", if (value == "off") "false" else "true",
                ),
            )
            else -> {
                // [T-android-settings-direct-write] Opening a settings page is NOT a
                // successful change. This used to return exit 0 ("已打开设置页"), which
                // reported success for a setting that was never written — the
                // 2026-11-23 power-save false-success bug. Report it as a failure that
                // asks the user to adjust manually.
                val opened = openSettings(target)
                return NativeOffloadResult(
                    1,
                    "无法直接修改「$target」，已打开对应设置页，请手动调整。\n${opened.output}",
                )
            }
        }

        val outputs = mutableListOf<String>()
        var commandOk = true
        for (argv in command) {
            val r = ShizukuManager.runProcess(argv)
            if (r.exitCode != 0) {
                commandOk = false
                outputs += "${argv.joinToString(" ")}: ${r.combined}"
                break
            }
        }

        // [T-settings-verify-state] `svc bluetooth/wifi` 在部分机型上改动
        // 已经生效，进程却被系统 kill（实测退出码 137），只看退出码会把
        // 成功判成失败，还会因此多弹一次设置页。以系统里读回的状态为准。
        val applied = settingApplied(target, value)
        if (applied == true) {
            return NativeOffloadResult(
                0,
                JSONObject()
                    .put("changed", true)
                    .put("target", target)
                    .put("value", value)
                    .put("verified", true)
                    .toString(2),
            )
        }
        if (!commandOk || applied == false) {
            openSettings(target)
            val reason = if (commandOk) "设置未生效，已打开对应设置页。" else "设置修改失败，已打开对应设置页。"
            return NativeOffloadResult(
                1,
                if (outputs.isEmpty()) reason else "$reason\n${outputs.joinToString("\n")}",
            )
        }
        return NativeOffloadResult(
            0,
            JSONObject().put("changed", true).put("target", target).put("value", value).toString(2),
        )
    }

    /**
     * 读回 [target] 的当前状态，确认改动是否真的生效。
     *
     * @return true 已到目标值；false 明确没到；null 无法读回（或该目标不做
     *   读回校验）。状态是异步落盘的，第一次读不到时短暂轮询再下结论。
     */
    private fun settingApplied(target: String, value: String): Boolean? {
        val expected = when (target) {
            "wifi" -> Triple("global", "wifi_on", if (value == "off") "0" else "1")
            "bluetooth" -> Triple("global", "bluetooth_on", if (value == "off") "0" else "1")
            "airplane" -> Triple("global", "airplane_mode_on", if (value == "off") "0" else "1")
            "rotation" -> Triple("system", "accelerometer_rotation", if (value == "off") "0" else "1")
            "screen_timeout" -> Triple(
                "system", "screen_off_timeout",
                value.toIntOrNull()?.let { (it * 1000).toString() } ?: return null,
            )
            // zen_mode: 0 = 关闭，非 0 = 某种勿扰档位，具体档位不校验。
            "dnd" -> Triple("global", "zen_mode", "")
            "brightness" -> Triple(
                "system", "screen_brightness",
                value.removeSuffix("%").toIntOrNull()
                    ?.let { (it.coerceIn(0, 100) * 255 / 100).toString() }
                    ?: return null,
            )
            else -> return null
        }
        val (namespace, key, wanted) = expected
        repeat(READBACK_ATTEMPTS) { attempt ->
            val result = ShizukuManager.runProcess(arrayOf("settings", "get", namespace, key))
            if (result.exitCode == 0) {
                val current = result.stdout.trim()
                val matched = when (target) {
                    "dnd" -> if (value == "off") {
                        current.isEmpty() || current == "null" || current == "0"
                    } else {
                        current.isNotEmpty() && current != "null" && current != "0"
                    }
                    else -> current == wanted
                }
                if (matched) return true
            }
            if (attempt < READBACK_ATTEMPTS - 1) Thread.sleep(READBACK_INTERVAL_MS)
        }
        // 亮度可能被厂商换算成别的刻度：读回不一致时不下「失败」结论。
        return if (target == "brightness") null else false
    }

    private fun openSettings(target: String): NativeOffloadResult {
        val intent = when (target) {
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "airplane" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "app" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
            "brightness", "screen_timeout", "rotation" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "dnd" -> Intent("android.settings.ZEN_MODE_SETTINGS")
            else -> Intent(Settings.ACTION_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            NativeOffloadResult(0, "已打开设置页：$target")
        } catch (t: Throwable) {
            NativeOffloadResult(1, "无法打开设置页：${t.message}")
        }
    }

    private fun scheduledTask(
        action: String,
        args: JSONObject,
        sessionId: String,
    ): NativeOffloadResult {
        return when (action) {
            "create" -> {
                val rawPrompt = args.optString("prompt")
                if (rawPrompt.isBlank()) return NativeOffloadResult(2, "定时任务内容为空。")
                // [T-android-cjk-punct-repair] The model/provider's tool-call
                // template rewrites full-width Chinese punctuation into ASCII
                // and can drop whole spans of the text on the way here: a
                // request to "每天晚上7点半，检查壁挂炉水压" was stored as
                // ", ,检查壁挂炉水压," with the first segment gone (observed
                // 2026-11-20). Restore the punctuation we can restore, and
                // tell the caller when the text looks damaged — the agent
                // can then re-create the task instead of reporting a wrong
                // schedule as done.
                val prompt = repairCjkPunctuation(rawPrompt)
                val label = repairCjkPunctuation(args.optString("label", prompt.take(40)))
                val damage = detectDamagedText(rawPrompt)
                val at = args.optString("at")
                val after = args.optLong("after", 0L)
                if (at.isBlank() && after <= 0L) return NativeOffloadResult(2, "需要指定定时任务时间。")
                val dateTime = if (at.isNotBlank()) {
                    runCatching { LocalDateTime.parse(at, ISO) }.getOrNull()
                } else null
                val repeat = args.optString("repeat", "ONCE")
                val task = if (after > 0L) {
                    ScheduledTask(
                        label = label,
                        timeOfDayHour = LocalDateTime.now().hour,
                        timeOfDayMinute = LocalDateTime.now().minute,
                        repeatMode = ScheduledRepeatMode.ONCE,
                        prompt = prompt,
                        targetMode = if (sessionId.isNotBlank()) {
                            ScheduledTargetMode.AppendToSession(sessionId)
                        } else ScheduledTargetMode.NewSession,
                        triggerKind = ScheduledTriggerKind.AFTER,
                        delaySec = after,
                        creatorSessionId = sessionId.takeIf { it.isNotBlank() },
                    )
                } else {
                    val dt = dateTime ?: return NativeOffloadResult(2, "无法解析定时任务时间。")
                    ScheduledTask(
                        label = label,
                        timeOfDayHour = dt.hour,
                        timeOfDayMinute = dt.minute,
                        repeatMode = when (repeat) {
                            "DAILY" -> ScheduledRepeatMode.DAILY
                            "WEEKDAYS" -> ScheduledRepeatMode.WEEKDAYS
                            "WEEKLY" -> ScheduledRepeatMode.CUSTOM
                            else -> ScheduledRepeatMode.ONCE
                        },
                        customDays = if (repeat == "WEEKLY") setOf(dt.dayOfWeek.value % 7 + 1) else emptySet(),
                        prompt = prompt,
                        targetMode = if (sessionId.isNotBlank()) {
                            ScheduledTargetMode.AppendToSession(sessionId)
                        } else ScheduledTargetMode.NewSession,
                        triggerKind = ScheduledTriggerKind.CALENDAR,
                        creatorSessionId = sessionId.takeIf { it.isNotBlank() },
                    )
                }
                val created = scheduled.create(task)
                val receipt = JSONObject()
                    .put("created", true)
                    .put("id", created.id)
                    .put("label", created.label)
                if (damage != null) {
                    // [T-android-cjk-punct-repair] Say the stored text looks
                    // damaged instead of silently storing it. The agent can
                    // delete and re-create the task; the user would otherwise
                    // get a schedule that quietly says something else.
                    receipt.put("warning", damage)
                        .put("stored_prompt", created.prompt)
                }
                NativeOffloadResult(0, receipt.toString(2))
            }
            "list" -> {
                val arr = JSONArray()
                scheduled.list().forEach { arr.put(it.toJson()) }
                NativeOffloadResult(0, JSONObject().put("tasks", arr).put("count", arr.length()).toString(2))
            }
            "cancel" -> {
                val query = args.optString("id")
                    .ifBlank { args.optString("id_or_query") }
                    .ifBlank { args.optString("query") }
                    .trim()
                if (query.isBlank()) {
                    return NativeOffloadResult(2, "没有找到要取消的定时任务。")
                }
                val id = args.optString("id").trim()
                val resolved = id.ifBlank {
                    val matches = scheduled.list().filter {
                        it.label.contains(query, true) || it.prompt.contains(query, true)
                    }
                    if (matches.size == 1) matches.first().id else ""
                }
                if (resolved.isBlank()) return NativeOffloadResult(2, "没有唯一匹配的定时任务。")
                scheduled.delete(resolved)
                NativeOffloadResult(0, JSONObject().put("cancelled", resolved).toString(2))
            }
            else -> NativeOffloadResult(2, "Unsupported scheduled task action: $action")
        }
    }

    /**
     * [T-fastpath-weather-ms] 「当前天气」走亚秒路径（见 [WeatherFastAnswer]）：
     * 最近一次已知位置 + 高德/Open-Meteo 精简报文 + 15 分钟缓存，不再等
     * 全新 GPS 定位，也不再拉 48h/10d 全量报文。
     *
     * 只有 current 走这条路；hourly / daily / report 仍用原来的
     * android-weather 子命令（它们由云端按需调用，不是一句话直达场景）。
     */
    private fun weather(
        action: String,
        args: JSONObject,
        sessionId: String,
    ): NativeOffloadResult {
        if (action == "current") return weatherCurrentFast()
        val loc = offload(
            "android-location",
            LocationOffloadHandler(context),
            listOf("android-location", "current", "--timeout", "8"),
        )
        if (loc.exitCode != 0) return loc
        val obj = runCatching { JSONObject(loc.output.trim()) }.getOrNull()
            ?: return NativeOffloadResult(1, "无法解析当前位置。")
        val lat = obj.optDouble("latitude")
        val lon = obj.optDouble("longitude")
        if (lat.isNaN() || lon.isNaN()) return NativeOffloadResult(1, "无法获取当前位置。")
        return offload(
            "android-weather",
            WeatherOffloadHandler(context),
            listOf(
                "android-weather", "current",
                "--lat", lat.toString(),
                "--lon", lon.toString(),
            ),
        )
    }

    private fun weatherCurrentFast(): NativeOffloadResult {
        val started = System.currentTimeMillis()
        val cached = runCatching { WeatherFastAnswer.cachedLine() }.getOrNull()
        if (cached != null) {
            com.openminis.app.llm.mnn.AssistantFastPath.trace(
                context,
                "weather-fast: source=cache elapsed=${System.currentTimeMillis() - started}ms",
            )
            return NativeOffloadResult(0, cached)
        }
        val outcome = runCatching { WeatherFastAnswer.refreshBlocking(context) }
            .getOrElse { WeatherFastAnswer.Outcome(error = "天气查询失败：${it.message ?: it.javaClass.simpleName}") }
        com.openminis.app.llm.mnn.AssistantFastPath.trace(
            context,
            "weather-fast: source=${outcome.source.ifBlank { "none" }} " +
                "elapsed=${System.currentTimeMillis() - started}ms" +
                (outcome.error?.let { " error=${it.take(80)}" } ?: ""),
        )
        val line = outcome.line
            ?: return NativeOffloadResult(1, outcome.error ?: "天气查询失败。")
        return NativeOffloadResult(0, line)
    }

    private fun device(action: String, args: JSONObject): NativeOffloadResult {
        val sub = when (action) {
            "battery" -> "battery"
            "storage" -> "storage"
            "info" -> "info"
            else -> "all"
        }
        return offload(
            "android-device",
            DeviceOffloadHandler(context),
            listOf("android-device", sub),
        )
    }

    /**
     * [T-local-flashlight] The torch is driven directly through the camera
     * service; the controller returns user-facing Chinese so the bubble shows
     * the real outcome (opened / closed / current state / why it failed).
     */
    private fun flashlight(action: String): NativeOffloadResult {
        val controller = FlashlightController.get(context)
        val result = when (action) {
            "on" -> controller.setTorch(true)
            "off" -> controller.setTorch(false)
            "status" -> controller.status()
            else -> FlashlightController.TorchResult(false, "不支持的手电筒操作。")
        }
        return NativeOffloadResult(if (result.success) 0 else 1, result.message)
    }

    /**
     * [T-local-volume] 音量走进程内 AudioManager，一次性完成；回执只报调整后
     * 的音量（`音乐音量73%`），语音场景直接念这一句。
     */
    private fun volume(action: String, args: JSONObject): NativeOffloadResult {
        val controller = VolumeController.get(context)
        val stream = args.optString("stream").takeIf { it.isNotBlank() }
        val result = when (action) {
            "set" -> if (!args.has("percent")) {
                VolumeController.VolumeResult(false, "没有识别出要设置到多少音量。")
            } else {
                controller.set(args.optInt("percent", 0), stream)
            }
            "adjust" -> if (!args.has("percent")) {
                VolumeController.VolumeResult(false, "没有识别出音量要调大还是调小。")
            } else {
                controller.adjust(args.optInt("percent", 0), stream)
            }
            "status" -> controller.status(stream)
            else -> VolumeController.VolumeResult(false, "不支持的音量操作。")
        }
        return NativeOffloadResult(if (result.success) 0 else 1, result.message)
    }

    private suspend fun media(
        action: String,
        args: JSONObject,
        label: String,
    ): NativeOffloadResult {
        val requested = args.optString("action", action)
        // [T-music-player-top] The in-app music player owns the common
        // cases: it is what the user sees at the top of the chat, so the
        // assistant drives the same thing. The generic file-playback
        // sessions stay the fallback for what the player does not know.
        val musicResult = withContext(Dispatchers.Main) { musicControl(requested, args, label) }
        if (musicResult != null) return musicResult

        val handler = PlayerOffloadHandler()
        if (requested == "list") {
            return offload("android-player", handler, listOf("android-player", "list"))
        }
        var session = args.optString("session")
        if (session.isBlank()) {
            val listed = MediaPlayerManager.listSessions()
            session = Regex("""-\s+([^:\s]+):\s+state=PLAYING""")
                .find(listed)?.groupValues?.get(1)
                ?: Regex("""-\s+([^:\s]+):\s+state=PAUSED""")
                    .find(listed)?.groupValues?.get(1)
                .orEmpty()
        }
        if (session.isBlank()) return NativeOffloadResult(2, "当前没有可控制的播放会话。")
        return offload(
            "android-player",
            handler,
            listOf("android-player", requested, session),
        )
    }

    /**
     * Drive the music player. Returns null when the request is not one the
     * music player can serve, so the caller can fall back to the generic
     * playback sessions.
     */
    private suspend fun musicControl(
        action: String,
        args: JSONObject,
        label: String,
    ): NativeOffloadResult? {
        val music = com.openminis.app.media.music.MusicPlayerController.get(context)
        return when (action) {
            "play" -> {
                val kind = args.optString("kind").trim()
                val query = args.optString("query").trim()
                val artist = args.optString("artist").trim()
                val chart = args.optString("chart").trim()
                val played = music.playRequest(kind, query, artist, chart)
                if (played == null) {
                    NativeOffloadResult(
                        1,
                        if (query.isBlank()) {
                            "没有找到可播放的歌曲。"
                        } else {
                            "没有找到「$query」或该歌曲暂时无法播放。"
                        },
                    )
                } else {
                    val track = played.track
                    val trackArtist = track.artist.takeIf { it.isNotBlank() }
                    // The tool pill shows the song that is really playing -
                    // 「放一首周杰伦的稻香」 resolves to 稻香, not to the
                    // phrase the user typed.
                    args.put("tool_title", "$label: ${track.title}")
                    // [T-music-slot-semantics] 回显解析结果：这句话被理解成
                    // 歌名/歌手/流派/场景/榜单的哪一种，误解 1 秒内可见。
                    // 补注由 SpeechAside 生成：屏幕上保留，播报时不念。
                    val understood = describeMusicResolution(played, artist)
                    NativeOffloadResult(
                        0,
                        musicPlaybackText(track.title, trackArtist, understood),
                    )
                }
            }
            "pause" -> if (music.state.value.hasTrack) {
                music.pause()
                NativeOffloadResult(0, "已暂停播放。")
            } else {
                null
            }
            "resume" -> if (music.state.value.hasTrack) {
                music.resume()
                NativeOffloadResult(0, "已继续播放。")
            } else {
                null
            }
            "next" -> if (music.state.value.hasTrack) {
                music.next()
                NativeOffloadResult(0, "已切到下一首。")
            } else {
                null
            }
            "previous" -> if (music.state.value.hasTrack) {
                music.previous()
                NativeOffloadResult(0, "已回到上一首。")
            } else {
                null
            }
            "stop" -> if (music.state.value.hasTrack) {
                music.stop()
                NativeOffloadResult(0, "已停止播放。")
            } else {
                null
            }
            "status" -> if (music.state.value.hasTrack) {
                val state = music.state.value
                val position = state.positionMs / 1000
                val duration = state.durationMs / 1000
                NativeOffloadResult(
                    0,
                    "正在播放：${state.track?.title.orEmpty()}（$position/$duration 秒）" +
                        (if (state.isPlaying) "，播放中" else "，已暂停"),
                )
            } else {
                null
            }
            "list" -> if (music.state.value.queue.isNotEmpty()) {
                val lines = music.state.value.queue.mapIndexed { index, track ->
                    val artist = track.artist.takeIf { it.isNotBlank() }
                    "${index + 1}. ${track.title}${artist?.let { " — $it" }.orEmpty()}"
                }
                NativeOffloadResult(0, lines.joinToString("\n"))
            } else {
                null
            }
            else -> null
        }
    }

    /**
     * [T-music-slot-semantics] 把一次点歌的解析结果写成一句可回显的摘要。
     * 用户应该一眼看出这句话被理解成了「歌名 / 歌手 / 流派 / 场景 / 榜单」。
     */
    private fun describeMusicResolution(
        played: com.openminis.app.media.music.MusicPlayback,
        requestedArtist: String,
    ): String {
        val list = played.contextLabel.takeIf { it.isNotBlank() }
        val count = played.matchedCount.takeIf { it > 0 }
        return when (played.kind) {
            "genre", "scene" -> buildString {
                append(if (played.kind == "genre") "流派=" else "场景=").append(played.query)
                if (list != null && list.startsWith("歌单")) {
                    append(" · 匹配").append(list)
                    if (count != null) append(" · ").append(count).append(" 首")
                } else if (list != null) {
                    append(" · 未匹配到歌单，走").append(list)
                }
            }
            "artist" -> buildString {
                append("歌手=").append(played.query)
                if (list != null) append(" · ").append(list)
                if (count != null) append(" · ").append(count).append(" 首")
            }
            "chart" -> list?.let { "榜单=$it" }.orEmpty()
            "none" -> list?.let { "未指定曲目 · 走$it" }.orEmpty()
            else -> buildString {
                append("歌名=").append(played.query)
                if (requestedArtist.isNotBlank()) append(" · 歌手=").append(requestedArtist)
            }
        }
    }

    private fun contacts(action: String, args: JSONObject): NativeOffloadResult {
        val query = args.optString("name").ifBlank { args.optString("query") }
        if (query.isBlank()) return NativeOffloadResult(2, "联系人名称为空。")
        val result = offload(
            "android-contacts",
            ContactsOffloadHandler(context),
            listOf("android-contacts", "search", query, "--max", "10"),
        )
        if (result.exitCode != 0) return result
        val body = result.output.trim()
        val arr = runCatching { JSONArray(body) }.getOrNull()
            ?: return NativeOffloadResult(
                1,
                if (body.contains("No contacts found", ignoreCase = true)) {
                    "没有找到联系人：$query"
                } else {
                    "无法解析联系人结果。"
                },
            )
        if (arr.length() == 0) return NativeOffloadResult(1, "没有找到联系人：$query")
        val contacts = buildList {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { add(it) }
            }
        }
        val chosen = contacts.firstOrNull { it.optString("name").equals(query, true) }
            ?: contacts.singleOrNull()
            ?: return NativeOffloadResult(
                1,
                "找到多个联系人：" + contacts.take(8).joinToString("，") { it.optString("name") } +
                    "。请说得更具体。",
            )
        val phone = chosen.optJSONArray("phones")?.optString(0).orEmpty()
        if (phone.isBlank()) return NativeOffloadResult(1, "${chosen.optString("name")} 没有电话号码。")
        if (action == "search") {
            return NativeOffloadResult(0, chosen.toString(2))
        }
        return dialContact(
            name = chosen.optString("name"),
            phone = phone,
            direct = args.optBoolean("directCall", false),
        )
    }

    private fun dialContact(
        name: String,
        phone: String,
        direct: Boolean,
    ): NativeOffloadResult {
        val wantsDirect = direct && ensureCallPermission()
        val method = if (wantsDirect) "ACTION_CALL" else "ACTION_DIAL"
        return try {
            context.startActivity(
                Intent(
                    if (wantsDirect) Intent.ACTION_CALL else Intent.ACTION_DIAL,
                    Uri.parse("tel:${Uri.encode(phone)}"),
                )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            NativeOffloadResult(
                0,
                JSONObject()
                    .put("dialed", name)
                    .put("number", phone)
                    .put("method", method)
                    .put(
                        "note",
                        if (direct && !wantsDirect) {
                            "CALL_PHONE is not granted; opened the dialer instead."
                        } else {
                            ""
                        },
                    )
                    .toString(2),
            )
        } catch (t: Throwable) {
            NativeOffloadResult(1, "无法打开拨号盘：${t.message}")
        }
    }

    private fun ensureCallPermission(): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return true
        return runCatching {
            runBlocking {
                OffloadPermissionManager.requestAndroidPermission(
                    listOf(Manifest.permission.CALL_PHONE),
                )
            }
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CALL_PHONE,
            ) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    private fun appLaunch(action: String, args: JSONObject): NativeOffloadResult {
        val name = args.optString("app")
        if (name.isBlank()) return NativeOffloadResult(2, "应用名称为空。")
        val normalized = name.lowercase().replace(Regex("""\s+"""), "")
        val known = KNOWN_APPS.entries.firstOrNull { (alias, _) ->
            normalized == alias.lowercase()
        }?.value
        val launchIntent = when {
            known != null && known.packageName != null ->
                context.packageManager.getLaunchIntentForPackage(known.packageName)
            known?.intent != null -> known.intent(context)
            else -> null
        } ?: resolveLaunchIntentByLabel(name)
        if (launchIntent == null) return NativeOffloadResult(1, "没有找到应用：$name")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launchIntent)
            NativeOffloadResult(0, "已打开应用：$name")
        } catch (t: Throwable) {
            NativeOffloadResult(1, "无法打开应用：${t.message}")
        }
    }

    private fun resolveLaunchIntentByLabel(name: String): Intent? {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = runCatching { pm.queryIntentActivities(main, 0) }.getOrDefault(emptyList())
        val match = activities.firstOrNull {
            it.loadLabel(pm).toString().contains(name, ignoreCase = true)
        } ?: return null
        return pm.getLaunchIntentForPackage(match.activityInfo.packageName)
    }

    private data class KnownApp(
        val packageName: String? = null,
        val intent: ((Context) -> Intent)? = null,
    )

    private fun offload(
        name: String,
        handler: com.openminis.app.sandbox.NativeOffloadHandler,
        argv: List<String>,
    ): NativeOffloadResult {
        return handler.handle(
            NativeOffloadRequest(
                pid = android.os.Process.myPid(),
                argv = argv,
                env = emptyMap(),
                cwd = "/",
            ),
        )
    }

    companion object {
        private const val TAG = "LocalCapabilityExecutor"
        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

        /**
         * [T-alarm-truthful-result] Exit code for 「已经交出去了，但要用户
         * 自己在系统时钟里确认」——既不是成功(0)也不是失败(1)。
         */
        const val EXIT_NEEDS_USER_CONFIRMATION = 3

        /** 设置读回校验的轮询次数与间隔（状态异步落盘，给系统一点时间）。 */
        private const val READBACK_ATTEMPTS = 6
        private const val READBACK_INTERVAL_MS = 400L

        /**
         * Capabilities served by [execute] — the local life surface. Callers
         * use this to decide whether a routed decision runs on-device or is
         * an agent tool (shell/file/memory/...). Keep it in step with the
         * `when (decision.capability)` below; [FlashlightController] was the
         * first capability to be forgotten here and surfaced as
         * "Unknown tool: flashlight" on 2026-11-15.
         *
         * [T-cloud-fastpath-catalog] The set itself now lives in
         * [com.openminis.app.assistant.routing.LocalCapabilityRegistry] so the
         * cloud prompt catalog, `local_capability` dispatch and this executor
         * read one source; this alias keeps existing callers unchanged.
         */
        val SUPPORTED_CAPABILITIES: Set<String> =
            com.openminis.app.assistant.routing.LocalCapabilityRegistry.inProcessCapabilities

        private val KNOWN_APPS: Map<String, KnownApp> = mapOf(
            "微信" to KnownApp("com.tencent.mm"),
            "wechat" to KnownApp("com.tencent.mm"),
            "支付宝" to KnownApp("com.eg.android.AlipayGphone"),
            "alipay" to KnownApp("com.eg.android.AlipayGphone"),
            "qq" to KnownApp("com.tencent.mobileqq"),
            "淘宝" to KnownApp("com.taobao.taobao"),
            "抖音" to KnownApp("com.ss.android.ugc.aweme"),
            "douyin" to KnownApp("com.ss.android.ugc.aweme"),
            "哔哩哔哩" to KnownApp("tv.danmaku.bili"),
            "bilibili" to KnownApp("tv.danmaku.bili"),
            "微博" to KnownApp("com.sina.weibo"),
            "weibo" to KnownApp("com.sina.weibo"),
            "高德" to KnownApp("com.autonavi.minimap"),
            "百度地图" to KnownApp("com.baidu.BaiduMap"),
            "相机" to KnownApp(intent = { Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) }),
            "camera" to KnownApp(intent = { Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) }),
            "时钟" to KnownApp(intent = { Intent(AlarmClock.ACTION_SHOW_ALARMS) }),
            "clock" to KnownApp(intent = { Intent(AlarmClock.ACTION_SHOW_ALARMS) }),
            "日历" to KnownApp(intent = { Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI) }),
            "calendar" to KnownApp(intent = { Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI) }),
            "设置" to KnownApp(intent = { Intent(Settings.ACTION_SETTINGS) }),
            "settings" to KnownApp(intent = { Intent(Settings.ACTION_SETTINGS) }),
        )
    }
}

/**
 * [T-music-slot-semantics] media/play 的结果文本：一句主句 + 可选的解释性补注。
 *
 * 主句是播报内容（「已播放：稻香 — 周杰伦」），补注只给眼睛看
 * （[SpeechAside]）：屏幕上保留「（理解为：流派=轻音乐）」，语音播报时去掉。
 *
 * 独立成函数是为了能直接单测这两条契约，不用起整个执行器。
 */
internal fun musicPlaybackText(title: String, artist: String?, understood: String): String {
    val head = if (artist.isNullOrBlank()) {
        "已播放：$title"
    } else {
        "已播放：$title — $artist"
    }
    val aside = SpeechAside.wrap(understood)
    return if (aside.isBlank()) head else "$head\n$aside"
}

/**
 * [T-android-cjk-punct-repair] Restore full-width Chinese punctuation that
 * the provider's tool-call template flattened to ASCII.
 *
 * Only punctuation sitting BETWEEN two CJK characters is rewritten — an ASCII
 * comma inside a path, a URL, a JSON blob or a shell command must stay
 * ASCII. The observed damage (2026-11-20) was exactly this: "，真实执行：" and
 * "、" arrived as "," and ":".
 */
internal fun repairCjkPunctuation(text: String): String {
    if (text.isEmpty() || text.none { it.isCjkChar() }) return text
    val out = StringBuilder(text.length)
    for (i in text.indices) {
        val c = text[i]
        val replacement = when (c) {
            ',' -> if (text.cjkNeighbours(i)) '，' else c
            ':' -> if (text.cjkNeighbours(i)) '：' else c
            ';' -> if (text.cjkNeighbours(i)) '；' else c
            else -> c
        }
        out.append(replacement)
    }
    return out.toString()
}

/**
 * [T-android-cjk-punct-repair] Detect text that lost a chunk on the way
 * here. Only strong signals count — a leading separator or two separators
 * in a row mean an empty segment, i.e. words that vanished. A rewritten
 * full-width comma is NOT reported: that one is repaired silently.
 */
internal fun detectDamagedText(text: String): String? {
    val signals = mutableListOf<String>()
    if (Regex("(?:[,，]\\s*){2,}").containsMatchIn(text)) {
        signals.add("出现了连续的分隔符，中间有一段是空的")
    }
    val trimmedStart = text.trimStart()
    if (trimmedStart.startsWith(",") || trimmedStart.startsWith("，")) {
        signals.add("开头就是一个分隔符，开头的文字可能丢了")
    }
    if (signals.isEmpty()) return null
    return "落库的定时任务文本疑似在传输中被改写（${signals.joinToString("；")}）。" +
        "请用 minis-scheduled list 回读落库内容并与用户原话核对；不一致就删掉重建并如实说明。"
}

private fun Char.isCjkChar(): Boolean =
    this in '一'..'鿿' || this in '㐀'..'䶿' || this in '豈'..'﫿'

/** True when the characters on both sides of [index] are CJK. */
private fun String.cjkNeighbours(index: Int): Boolean {
    val before = getOrNull(index - 1) ?: return false
    val after = getOrNull(index + 1) ?: return false
    return before.isCjkChar() && after.isCjkChar()
}
