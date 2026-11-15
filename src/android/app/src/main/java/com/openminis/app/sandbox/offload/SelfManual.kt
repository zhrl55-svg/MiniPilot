package com.openminis.app.sandbox.offload

/**
 * [T-self-manual] Static reference text for `minis-self`.
 *
 * These blocks used to live in the system prompt. They are reference material,
 * not standing instructions: keeping them here (and one pointer line in the
 * prompt) lets the model read them on demand instead of paying for them on
 * every request. Tools and settings are rendered live from their registries
 * (AgentTools / ConfigBridge); this file holds the hand-written manuals.
 */
internal object SelfManual {

    const val HELP_TEXT =
        "minis-self - this build's own manual (tools, settings, features, architecture)\n" +
            "\n" +
            "Generated from the live registries at call time. Prefer it over re-discovery\n" +
            "(minis-config topic-help, source scans, filesystem searches) when the question\n" +
            "is about Minis itself.\n" +
            "\n" +
            "USAGE:\n" +
            "  minis-self <subcommand> [args]\n" +
            "\n" +
            "SUBCOMMANDS:\n" +
            "  tools [--filter <kw>] [--full]\n" +
            "                             Agent tools: one line each; --full adds params and purpose.\n" +
            "  cli [name]                 android-* / minis-* command manual. No name = index.\n" +
            "  settings [topic]           All configurable settings: path, type, description.\n" +
            "                             No topic = topic index. --filter <kw> narrows fields.\n" +
            "  links                      minis://settings deep-link paths.\n" +
            "  features [--filter <kw>]   Feature -> where to configure it -> code module.\n" +
            "  arch [--filter <kw>]       Module map and key entry points.\n" +
            "\n" +
            "EXAMPLES:\n" +
            "  minis-self tools --filter memory\n" +
            "  minis-self cli minis-config\n" +
            "  minis-self settings memory\n" +
            "  minis-self features 语音\n" +
            "  minis-self arch\n"

    /** The CLI manual moved out of the system prompt. */
    val CLI_MANUAL: String = """
Android framework CLIs (android-*, on PATH; each supports --help):
- android-alarm — schedule alarms/timers in the system Clock app (`schedule <HH:MM> --label <L> [--repeat ONCE|DAILY|WEEKDAYS]`, `timer <seconds> --label <L>`, `open`). Alarms/timers are saved into the user's Android Clock — list/cancel are not supported (no system query API); tell the user to manage them from the Clock app's Alarms/Timers tabs (or `android-alarm open` / minis://views/alarm).
- android-calendar — read/write the device calendar (`list --start YYYY-MM-DD [--end ...] [--max N]`; `create --title <T> --start <ISO> [--end <ISO>] [--description <D>] [--location <L>] [--all-day]`).
- android-clipboard — `get | set <text> [--label L] | clear`.
- android-contacts — `list [--max N] | search <query> [--max N] | get <id> | delete <id>`. Requires READ_CONTACTS (delete also needs WRITE_CONTACTS).
- android-device — `[all|info|battery|storage]` — model, OS version, battery, storage (JSON).
- android-location — `current` for device location with reverse-geocoded address; `geocode <lat> <lon>` for reverse, `forward --address "<addr>"` for forward geocoding.
- android-notification — `send --title <T> [--body <B>] | clear | list [--max N]`. `send` triggers the system permission prompt on Android 13+ if POST_NOTIFICATIONS isn't granted. `list` reads active status-bar notifications and requires Notification Access (one-time setup; the first `list` call opens that page automatically).
- android-open <url> — open a URL via the system handler (http/https, tel:, mailto:, geo:, market:, intent:, etc.). To offer a tappable link instead, write a standard Markdown link — the app handles system URL schemes natively.
- android-photos — `list [--max N] | stats | near <lat> <lon> [--radius KM] [--max N]` — query the device photo library via MediaStore.
- android-player — audio playback sessions (`play <session> <path>`, `pause/resume/seek/stop/status <session>`, `list`).
- android-speak — device TTS (`<text> [--rate F] [--pitch F] [--volume F]`; `--stop | --status`).
- android-speech — speech transcription (`transcribe [--engine system|provider|auto] [--source mic|<file>] [--language BCP47] [--duration SEC, max 60] [--max N] [--on-device]`; `listen` is an alias). `--engine provider` uses the Voice Input model instead; `--engine auto` falls back when the system recognizer cannot be reached and says `audio_sent_to`; default `--engine system`. `--on-device` prefers the on-device recognizer; either falls back once and the result names `recognizer` / `fallback_used`. `languages` lists what the recognizer reports; `status [--probe]` says whether recognition can work and why not. JSON envelope {ok, data|error{code,message}}. Requires RECORD_AUDIO.
- android-weather <latitude> <longitude> — Open-Meteo forecast (current + hourly + daily). No API key needed.
- android-shizuku-cli — privileged Android system APIs via Shizuku when granted. Curated subcommands return structured JSON; anything else via `exec <shell command>` (same surface as `adb shell`). Run with no args for the subcommand list.
- android-a11y-cli — drive system UI (read screen, tap, type, swipe, scroll) via AccessibilityService when enabled. Run with no args for the subcommand list.

Minis CLIs:
- minis-open <url-or-path> — open a resource inside Minis without leaving the chat (http/https → built-in WebKit preview; chat-resource paths under /var/minis/** → built-in preview by extension). Prefer this over android-open for anything previewable in-app.
- minis-sessions-cli — read chat history: `list` recent/by date, `search --keywords` cross-session, `messages --id` (add `--tools` for each tool call's exact input/output).
- minis-model-use — invoke other LLM models pre-configured by the user: `list`, `search <query>`, `run --model <id_or_name>` with an OpenAI-compatible messages request (--input <json_file> or stdin; output to stdout or --output <path>). Standard params are auto-converted per provider; escape hatches `extra_body`, `endpoint`, `passthrough`. Read `warnings`/`applied_extras` to self-correct. Run --help before multimodal use.
- minis-config — read or change Minis settings programmatically: `list-topics`, `topic-help <topic>`, `get <path> [--filter] [--page] [--page-size]`, `set <path> <value-json|--file>`, `add <topic> <json>`, `set-batch`, `audit-list/get/revert`. Every write triggers an in-app confirmation sheet and a revertable audit; after a successful change relay the response's `user_message` (how to review/revert via Settings → Logs → Config Changes). `get` never echoes API keys / OAuth tokens / env var values (permission_denied by design). You can add providers and write `apiKey` (literal or an env-var reference); OAuth tokens and env-var values are not settable — point the user at the env-var deep link. Network routing and live Tailscale status: `minis-config get network.tailscale.status`. Use `minis-self settings` for discovery, `minis-config` to write.
- minis-scheduled — create/manage scheduled prompts: `create --prompt "..."` + one trigger (`--after 30m`, `--interval 10m [--count N]`, `--time HH:MM [--repeat once|daily|weekdays|custom] [--start/--end]`, `--trigger on-completion --of <id>`), `list`, `delete --id`, `enable/disable --id`, `run --id`. `--target follow-up|new|rerun --session <id> --message <id>`; `--model`, `--thinking`. `create` prints a `delivery` line — check it matches the user's intent. `--command` / `--tool shell_execute --tool-args` pre-runs a command at each fire and hands you its output. Fires through a system alarm, so it runs even when the app is backgrounded.
""".trimIndent()

    /** Feature -> settings -> implementation. Filterable by keyword. */
    val FEATURES: List<String> = listOf(
        "记忆 GLOBAL/USER/每日日志 → Settings → 记忆 (minis://settings/memory) → data/repository/MemoryRepository + memory/ 索引；GLOBAL 注入为章节索引，正文用 memory_get/file_read",
        "模型与供应商 → minis://settings/providers, minis://settings/model-groups → provider/, data/repository/ProviderRepository；Agent Loop 模型组在 model-groups",
        "Agent 工具开关（浏览器/子代理/询问用户）→ 设置 → Agent Tools（路由 agent_tools；无 settings deep link）→ tools/AgentTools + AgentToolSwitch；关掉后 schema 与提示同时移除",
        "技能 → minis://settings/skills → agent/skill/* + SkillRepository；SKILL.md 按会话启用",
        "SOUL 人格 → 设置 → Soul（无 settings deep link）→ agent/SoulStore + SystemPromptBuilder",
        "语音唤醒/识别/播报 → 设置 → 语音（无 settings deep link）→ speech/wake, SpeechRecognitionManager, TextToSpeechManager",
        "音乐播放器 → 设置 → 音乐（网易云扫码登录/音源地址）→ media/music + ui/media/music；聊天页悬浮迷你播放条",
        "端侧助理（MNN 0.3B 路由）→ 设置 → Agent Runtime 的助理模式开关 + 路由页 → llm/mnn, assistant/*",
        "沙箱/Rootfs → minis://settings/rootfs → sandbox/PRootKernel, ExecutionCoordinator, RootfsManager",
        "网络路由/Tailscale/代理/DNS → minis://settings/network, /tailscale, /proxy, /dns → sandbox/net, ContainerDns",
        "定时任务 → minis://settings/scheduled → 调度器 + minis-scheduled CLI（系统闹钟触发）",
        "备份/恢复 → 设置 → 备份（无 settings deep link）→ backup/BackupExporter, BackupImporter",
        "日志 → minis://settings/logs → logging/, debug/logs.*",
        "权限 → minis://settings/permissions → PermissionManager + agent/Settings 开关",
        "环境变量 → minis://settings/environments → data/repository/EnvVarRepository",
        "存储 → minis://settings/storage → 会话/附件清理",
        "外部挂载 → minis://settings/mount-external → sandbox/MountedFolderCoordinator",
        "共享文件夹 → minis://settings/shared-folders → filesDir/minis-global/shared",
        "外观/后台/关于 → minis://settings/appearance, /background, /about → ui/settings/*",
        "调试 RPC（仅 dev/perf）→ 端口 5321，rpc.discover → debug/DebugServer + DebugMethodRegistry",
    )

    /** Canonical minis://settings paths (see DeepLinkHandler). */
    val SETTINGS_LINKS: List<String> = listOf(
        "minis://settings — Settings home",
        "minis://settings/providers[/<instanceId>] — provider list / detail",
        "minis://settings/model-groups[/<groupId>] — model groups (incl. Agent Loop)",
        "minis://settings/usage — token usage",
        "minis://settings/skills — skills management",
        "minis://settings/memory — memory management",
        "minis://settings/storage — storage management",
        "minis://settings/shared-folders — shared folders",
        "minis://settings/mount-external — mounted external folders",
        "minis://settings/logs — logs",
        "minis://settings/appearance — appearance",
        "minis://settings/background — background settings",
        "minis://settings/about — about / updates",
        "minis://settings/permissions — permissions",
        "minis://settings/environments[?create_key=K&create_value=V&create_note=N] — env vars",
        "minis://settings/rootfs — rootfs / mirrors",
        "minis://settings/network — network routing",
        "minis://settings/tailscale — tailscale",
        "minis://settings/proxy — upstream proxies",
        "minis://settings/dns — shell container DNS",
        "minis://settings/scheduled — scheduled tasks",
        "minis://settings/agents — agent runtime / sub agents",
        "minis://views/alarm — alarm list",
        "minis://session/<id> — open a session",
    )

    /** Module map; the on-device app has no source tree, so this is curated. */
    val ARCHITECTURE: String = """
minis-android — single :app module (Kotlin, Compose, Room)

- ui/chat/        ChatViewModel: agent loop, system-prompt build, tool dispatch; ChatScreen: Compose chat UI; ChatSplitScaffold: list/detail navigation
- agent/          SystemPromptBuilder (identity + SOUL), jobs/HelperRunner (sub agents), skill/SkillStore (run traces), userask/ (ask-the-user cards)
- tools/          AgentTools (central tool registry) + one definition/executor per tool
- data/repository/ ChatRepository, ProviderRepository, MemoryRepository (+ memory/ index), SkillRepository, MCPRepository
- sandbox/        PRootKernel, ExecutionCoordinator, NativeOffloadServer; sandbox/offload/ = CLI handlers (android-*, minis-*)
- config/         ConfigRegistry + ConfigBuiltins (every setting), ConfigBridge (CLI read/write + audit), readable renderers
- assistant/      on-device routing and fast path (AssistantRouter, LocalCapabilityExecutor, DefaultAssistantRoutes)
- speech/         wake word, ASR, TTS, voice-output arbitration
- llm/            cloud providers (OpenAI/Anthropic/…), MNN on-device model plumbing
- debug/          DebugServer (JSON-RPC :5321, DEV_TOOLS only) + DebugMethodRegistry
- persistence     Room DBs; user data under filesDir/minis-global → /var/minis (memory, shared, skills, mounts)
- builds          flavors github/store; types debug (debuggable), perf (DEV_TOOLS, AOT-friendly, .dev), release (minified)
""".trimIndent()
}
