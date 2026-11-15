# Guard 确认流程复核（只复核，未改代码）

- 日期：2026-11-15
- 设备：HUAWEI ELS-AN00 / Android 12
- 构建：`com.openminis.app.dev`，versionCode 37，buildDate `2026-11-15 13:19:38`，安装于 `14:20:14`
- 审计文件：`/storage/emulated/0/Android/data/com.openminis.app.dev/files/agent/audit.jsonl`
- 复核对象：`Guard` 拦截开关打开后，确认卡在前台是否可见；无人应答的运行（定时任务 / 后台 / 无界面）是否会被卡死
- 复核范围：只读代码 + 真机触发，**未修改任何行为**

## 结论摘要

| 问题 | 结论 |
|---|---|
| 开启拦截后，确认卡在前台是否有正常渲染位？ | **没有。这是一个真问题。** 前台时确认卡不会出现在界面上，工具持续显示“运行中”，直到 5 分钟超时被判拒绝。 |
| 无人在应答的运行会不会卡死？ | **不会永久卡死。** 无界面运行不弹卡，直接记 `denied` 并放行；有界面但没人回答时，最长等待 5 分钟（默认），超时转 `asked_timeout`，该次调用失败，整轮对话继续。 |

## 一、代码路径

1. 风险分类与审计入口在 `ChatViewModel.executeTool()`（`src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:15022`）：
   - 达到 `RiskLevel.EXTERNAL` 且 `guard_enforce=true` 时：
     - 本条运行无人可答（`guardInteractiveThisRun=false`）→ 记 `denied`，**继续执行**（`:15035`）；
     - 否则调用 `Guard.requestApproval()` 并等待（`:15040`）。
2. “有没有人能答”在每轮开始时决定一次（`ChatViewModel.kt:993`）：
   ```kotlin
   val foreground = appInForeground()
   guardInteractiveThisRun = interactive && !isHelper && foreground
   ```
   `interactive=false` 覆盖定时任务、CLI/RPC、子代理回投等无界面入口；`foreground` 只取**运行开始那一刻**的应用可见性。
3. `Guard.requestApproval()`（`agent/guard/Guard.kt:212`）通过 `UserAskCenter` 建卡，`ownedByToolCall=false`，`toolCallId` 设为这次工具调用的 id（`:239`），等待结果最多 `UserAskSettings.timeoutMinutes()` 分钟（默认 5 分钟，`agent/userask/UserAskCore.kt:673`）。
4. 渲染端：聊天只在**工具块本身的工具名是 `user_ask`**（或 `hub_call`）时才走卡片组件：
   - `ui/chat/ChatScreen.kt:5304`：`if (item.block.toolName == UserAskPromptText.TOOL_NAME) UserAskBlock(...)`
   - `ui/chat/UserAskCard.kt:200`：`UserAskBlock` 会用 live ask 的 `toolCallId` 匹配块 id，并渲染 `UserAskPendingCard`。
   - 其他所有工具（`shell_execute` / `file_write` / `browser_use`…）走 `ToolCallPill`，**没有任何地方检查挂在这些块上的 live ask**。
5. 通知兜底：`UserAskNotifier`（`notification/UserAskNotifier.kt:80`）只在**应用不在前台**时发通知；前台会主动清除通知（“卡片在屏幕上”）。Guard 的 ask 没有 `user_ask` 工具块，因此前台既没有卡片，也没有通知。

## 二、真机证据

### 1. 前台无渲染位（真问题）

- 会话 `c06feeff-…`（界面停留在该会话，应用前台），15:35:33 让模型执行
  `curl -X POST http://127.0.0.1:9/guard-test -d test`：
  - 工具胶囊一直显示运行中（截图核对两次，间隔约 1 分钟），**界面没有出现任何“安全确认 / 允许一次 / 拒绝”卡片**；
  - 15:41:07 审计落盘：`sessionId=c06feeff… tool=shell_execute risk=EXTERNAL rule=curl -X POST decision=asked_timeout`；
  - 随后工具以“用户未批准高风险操作”失败，模型把失败原因转述给用户。
- 会话 `57991304-…`：同一条命令，工具胶囊时长 **301 s**，审计 `15:39:04 … asked_timeout`。
- 会话 `ca3c7747-…`（另一个在跑 ssh 的会话）：`15:39:03 … shell_execute EXTERNAL ssh asked_timeout`，界面上的工具胶囊同样显示 **301 s**。
- 结论：`asked_timeout` 不是“用户没点”，而是“用户根本看不到卡”。默认 5 分钟盲等后才失败。

### 2. 无人应答不卡死

- 无界面入口：`chat.prompt`（headless）新建会话 `3fe91117-…`，同样命令：
  - 没有建卡，审计 `15:43:13 … risk=EXTERNAL rule=curl -X POST decision=denied`；
  - 命令**照常执行**（curl exit 7，连接被拒），模型正常汇报结果；
  - 全程约 35 秒结束，没有被审批阻塞。
- 子代理 / 定时任务走同一个 `interactive=false` 分支（代码确认；本轮未单独构造子代理用例）。
- 超时有界：`UserAskSettingsSpec.DEFAULT_TIMEOUT_MINUTES = 5`，`Guard` 还做 `coerceAtLeast(1)`；到点转 `asked_timeout`，该次调用失败、轮次继续，不会永久挂起。

## 三、影响

1. **功能不可用**：`guard_enforce=true` 时，前台用户实际上无法批准任何高风险调用——卡不出现，所有 EXTERNAL 以上调用都会在 5 分钟后被当作拒绝。
2. **体验伤害**：每遇到一次高风险调用，工具和整轮对话被阻塞 5 分钟；用户只看到“运行中”，不知道在等什么。
3. **审计可追溯**，`asked_timeout` 与 `denied` 能区分“没人看见”和“无人可问”，这一点符合预期。

## 四、建议（本轮未实施）

按优先级：

1. **给 Guard 的 ask 一个前台渲染位**（最小改动）：在 `ChatScreen` 的 `AssistantToolUse` 分支，把条件从“工具名是 `user_ask`”扩展为“工具名是 `user_ask`，或该块 id 存在 live pending ask”；或让 `ToolCallPill` 在存在 live ask 时改为渲染 `UserAskBlock`。Guard 已经把 `toolCallId` 设为块 id，匹配条件现成。
2. **会话不在屏幕上时也要有出口**：当前 `UserAskNotifier` 只处理“应用不在前台”。可在“应用在前台但发起会话不是当前可见会话”时也发通知，避免盲等。
3. **可观测性**：`Guard.requestApproval` 等待期间在工具胶囊上显示“等待安全确认”，至少让用户知道在等什么。
4. 不建议缩短默认 5 分钟超时来掩盖问题；超时只是兜底，不是交互。

## 五、未验证项

- 后台通知的 Guard 确认（`UserAskNotifier` 的 RemoteInput / 按钮回调）未在真机走通一次完整批准流程，只确认了代码路径与门控条件。
- 子代理（`isHelper`）分支未单独构造用例；与 headless 共用 `interactive=false` 判定。
- 本轮复核未修改 `guard_enforce` 默认值（保持 `false`），测试后已恢复。
