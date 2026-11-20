package com.openminis.app.hub

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONObject

/**
 * The `hub_call` tool (contract M4): one tool, one action, one target.
 *
 * Two rules the contract insists on and that this file implements:
 *  - the `device` enum is rebuilt from the live device table, so the model
 *    never has to guess an id;
 *  - the result carries a provenance marker ([PROVENANCE_PREFIX]). Without it
 *    the model cannot tell the remote output from one of its own local tool
 *    results, and the semantics of the turn fall apart.
 */
object HubCallTool {
    const val NAME = "hub_call"

    /** M4 — the marker that tells the model where the output came from. */
    const val PROVENANCE_PREFIX = "[来自 %s] "

    /** The actions the PC side implements (contract C3.1 / C3.2). */
    val ACTIONS = listOf("info", "shell", "files", "file.get", "file.put", "screen", "mode.set", "notify")

    fun definition(devices: List<String>): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Run an action on another device of your account (a computer that is signed " +
            "in to the same relay) — read its files, list a directory, run a shell command there, " +
            "take a screenshot of its screen, or pop a desktop notification there. The other device " +
            "answers, and a risky action (anything that writes) asks the person for approval on " +
            "this phone first. Use it for work that belongs to the other machine: building there, " +
            "reading a log file there, checking a service there, telling the person on that screen " +
            "that something is done. The output comes back tagged with the device it came from.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user on the block in the tool bar and in the transcript. Use the same language as the user."),
            "device" to AgentToolParam(
                "string",
                "The remote device to act on — one of the devices currently online in your account.",
                enumValues = devices.ifEmpty { listOf("（暂无在线设备）") },
            ),
            "action" to AgentToolParam(
                "string",
                "What to do on that device. `info`: system facts (includes the current approval mode). " +
                    "`shell`: run one command (/bin/sh -c). " +
                    "`files`: list a directory. `file.get`: read a file (base64 comes back; prefer `files` " +
                    "or `shell` for small reads). `file.put`: write a file. `screen`: a screenshot of the screen. " +
                    "`mode.set`: change how often that device asks for approval (`ask` every step, `smart` " +
                    "read-only free + 10-minute grants, `auto` everything except destructive commands). " +
                    "A mode change always asks the person first. " +
                    "`notify`: pop a desktop notification on that device, with the given title and body.",
                enumValues = ACTIONS,
            ),
            "args" to AgentToolParam(
                "object",
                "The arguments of the action: shell → {command, cwd?, timeout_ms?}; files → {path?, depth?}; " +
                    "file.get → {path, offset?, length?}; file.put → {path, mime, data(base64)}; " +
                    "screen → {display?}; info → {}; mode.set → {mode: ask|smart|auto}; " +
                    "notify → {title?, body?, receipt?} — title/body optional, title defaults to \"minis\"; " +
                    "receipt is an optional machine-readable handover " +
                    "{re, status: working|done|blocked, summary, artifacts:[{path, sha256?, lines?}], needs:[…]} " +
                    "that the other side can read without parsing the text.",
            ),
        ),
        required = listOf("tool_title", "device", "action", "args"),
        propertyOrdering = listOf("tool_title", "device", "action", "args"),
    )

    /**
     * Run the call and render the answer for the transcript.
     *
     * @param sessionId the chat session the card of a pending approval belongs to.
     * @param toolCallId the tool call's own id — the approval card is rendered
     *   inside this tool block, so the person answers next to the request.
     */
    suspend fun execute(
        argsJson: String,
        sessionId: String,
        toolCallId: String,
        context: Context,
    ): ToolExecutionResult {
        val args = try {
            JSONObject(argsJson)
        } catch (e: Exception) {
            return ToolExecutionResult("参数不是合法 JSON：${e.message}", false)
        }
        val deviceName = args.optString("device", "").trim()
        val action = args.optString("action", "").trim()
        val actionArgs = actionArgs(args)

        if (deviceName.isEmpty() || action.isEmpty()) {
            return ToolExecutionResult("device 与 action 都要填。", false)
        }
        val device = Hub.find(deviceName)
        if (device == null) {
            val online = Hub.onlineTargetNames()
            return ToolExecutionResult(
                "找不到设备「$deviceName」。当前在线：${if (online.isEmpty()) "无" else online.joinToString(", ")}",
                false,
            )
        }
        if (!device.online) {
            return ToolExecutionResult("设备「${device.name}」不在线。", false)
        }
        if (!ACTIONS.contains(action)) {
            return ToolExecutionResult("不支持的动作：$action", false)
        }

        // [T-hub-receipt] A notify may carry a machine-readable handover.
        // It is checked here, before anything goes on the wire, so the model
        // learns that the object was wrong instead of the other side storing
        // a half-valid receipt.
        if (action == "notify") {
            val problem = receiptProblem(actionArgs)
            if (problem != null) {
                return ToolExecutionResult("receipt 不合法：$problem", false)
            }
        }

        val result = Hub.call(device.id, action, actionArgs, sessionId, toolCallId)
        val prefix = PROVENANCE_PREFIX.format(device.name)
        if (!result.ok) {
            val why = when (result.error) {
                "not_allowed" -> "对方拒绝了这次操作（${result.message.orEmpty()}）"
                "device_offline" -> "对方不在线"
                "not_controllable" -> "对方没有开放远程控制"
                "timeout" -> "对方没有在期限内回应"
                "rate_limited" -> "对方暂时不接受更多请求，稍后再试"
                else -> result.message?.takeIf { it.isNotBlank() } ?: result.error ?: "未知错误"
            }
            return ToolExecutionResult(prefix + "失败：$why", false)
        }
        val rendered = render(result.body, context, sessionId)
        // A screenshot is a picture: hand it to the model as an image, not
        // as a path it has to go and read.
        val image = imageBytes(result.body)
        return ToolExecutionResult(prefix + rendered, true, imageData = image)
    }

    /** The body of a `shell` result reads like a terminal; the rest is JSON. */
    private fun render(body: JSONObject?, context: Context, sessionId: String): String {
        if (body == null) return "（空结果）"
        if (body.has("stdout") || body.has("exit_code")) {
            val sb = StringBuilder()
            sb.append("exit_code=").append(body.optInt("exit_code", 0))
            body.optString("duration_ms").takeIf { it.isNotBlank() }?.let { sb.append(" ").append(it).append("ms") }
            if (body.optBoolean("truncated", false)) sb.append(" (输出被截断)")
            sb.append("\n")
            val out = body.optString("stdout", "")
            if (out.isNotEmpty()) sb.append(out.trimEnd()).append("\n")
            val err = body.optString("stderr", "")
            if (err.isNotBlank()) sb.append("--- stderr ---\n").append(err.trimEnd()).append("\n")
            return sb.toString().trimEnd()
        }
        val slim = JSONObject()
        for (k in body.keys()) {
            if (k == "data") {
                // P2.5: the bytes are the point of the action. Write them
                // where the model can actually reach them and say where —
                // a character count made `screen` and `file.get` useless.
                val landed = land(context, sessionId, body.optString(k), body.optString("mime", "application/octet-stream"))
                if (landed == null) {
                    slim.put(k, "<${body.optString(k).length} base64 字符，未能落盘>")
                } else {
                    slim.put(k, landed)
                }
            } else {
                slim.put(k, body.opt(k))
            }
        }
        return slim.toString(2)
    }

    /**
     * [T-hub-receipt] Why a notify's receipt is refused, or null when it is
     * fine.
     *
     * The check happens before anything goes on the wire, so the model learns
     * that the object was wrong instead of the other side storing a
     * half-valid receipt.
     */
    internal fun receiptProblem(args: JSONObject): String? {
        if (!args.has("receipt") || args.isNull("receipt")) return null
        val problems = HubReceipt.problems(args.optJSONObject("receipt"))
        if (problems.isEmpty()) return null
        return problems.joinToString("；")
    }

    /**
     * "args" is an object in the schema, but a model may hand it over as a
     * JSON string. Accept both: an empty args made the PC answer
     * "shell needs args.command" instead of running anything.
     */
    internal fun actionArgs(args: JSONObject): JSONObject = when (val raw = args.opt("args")) {
        is JSONObject -> raw
        is String -> try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
        else -> JSONObject()
    }

    /**
     * Write the payload where the sandbox can actually read it.
     *
     * The bytes land in the chat session's own offloads directory
     * (`filesDir/minis-sessions/<sessionId>/offloads`), which PRoot
     * bind-mounts at `/var/minis/offloads` for that session (see
     * [com.openminis.app.sandbox.SessionMounts]) — so the model can read
     * the file back from inside the sandbox at the path we report.
     *
     * The external-files-dir copy the old code used had no such mount:
     * the guest never saw the `hub` subdirectory, so `file.get` handed
     * the model a "已落盘" message and nothing it could open.
     */
    private fun land(context: Context, sessionId: String, data: String, mime: String): String? {
        if (data.isEmpty()) return null
        return try {
            val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
            val out = java.io.File(context.filesDir, "minis-sessions/$sessionId/offloads").also { it.mkdirs() }
            val ext = when {
                mime.contains("png") -> "png"
                mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
                mime.contains("json") -> "json"
                mime.contains("text") -> "txt"
                else -> "bin"
            }
            val file = java.io.File(out, "remote-${System.currentTimeMillis()}.$ext")
            file.writeBytes(bytes)
            "已落盘 ${file.length()} 字节：/var/minis/offloads/${file.name}"
        } catch (e: Exception) {
            null
        }
    }

    /** The decoded picture of a `screen` result, for the model to look at. */
    private fun imageBytes(body: JSONObject?): ByteArray? {
        val data = body?.optString("data") ?: return null
        if (!body.optString("mime", "").startsWith("image/")) return null
        if (data.isEmpty()) return null
        return try {
            android.util.Base64.decode(data, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            null
        }
    }
}
