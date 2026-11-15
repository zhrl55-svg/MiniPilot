# ChatViewModel 工具调度拆分评估报告

- 日期：2026-11-15
- 对象：`src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt`
- 结论：**不建议一次性大改**。建议按 6 个阶段做"绞杀式"拆分，每一阶段都能独立构建、安装、回归与回滚。详见第 5、6 节。

---

## 1. 现状量化

| 指标 | 数值（2026-11-15，提交 `858ee94` 之后） |
|---|---|
| 文件行数 | 21,431 |
| import 数 | 103 |
| 顶层成员声明（4 空格缩进） | 约 587 |
| Kotlin 函数声明 | 约 350 |
| `MutableStateFlow` / `mutableStateOf` | 53 |
| `private var` / `@Volatile` 字段 | 57 |
| 单文件内最长函数 | `runAgentLoopBody`，约 3,168 行（11792–14959） |
| 工具分发函数 | `executeTool`，约 230 行（15014–15243） |
| 分发分支 | 15 个命名分支 + `else`（15076–15243） |
| 风险判定位置 | `executeTool` 开头 15020–15058，先 `Guard.classify`，随后按 `enforceEnabled` / `guardInteractiveThisRun` 决定放行、拒绝或弹确认卡 |
| 其他巨型函数 | `loadSession` 620 行、`compactAllImpl` 547 行、`sendMessage` 474 行、`executeDelegateTask` 427 行、`generateSessionTitleIfNeeded` 408 行、`runLocalAssistantFastPath` 326 行、`offloadContextIfNeeded` 310 行 |
| 文件内被 `[T-*]` 标记的行为约束 | 69 处 |
| 直接引用该文件的 UI/服务文件 | `ChatScreen.kt`(27)、`ChatViewModelStore.kt`(18)、`ChatViewModelUiStateExt.kt`(11) 等 |
| 直接构造 ChatViewModel 的单元测试 | 0（现有测试只覆盖可独立实例化的纯逻辑/存储层） |

`executeTool` 本身只有 227 行，真正的问题是它把三类职责压在同一个入口里：

1. **风险判定与审批**：`Guard.classify` → 审计 → 交互式询问 → 拒绝；
2. **工具路由**：15 个分支，覆盖内置工具、子代理、user_ask、hub_call；
3. **执行上下文耦合**：分支体直接读 `context`、`activeSessionId`、`fsSessionId`、`userAskExposedThisRun`、`userAskDeferredThisRun`、`skillRepository`，并直接写 UI 工具块（`toolBlocks` / `assistantId` / `currentText`）。

更大的问题在调用方 `runAgentLoopBody`：一次 run 的流式输出、provider 回退、工具批次并发执行、工具结果持久化、上下文压缩、子代理回调、user_ask 恢复、SkillStore 记录全部在同一条 3,163 行函数里，任何拆分都必须先保证这些顺序语义不被打乱。

---

## 2. 为什么现在不能"一次性大改"

1. **行为只存在于运行时，类型系统兜不住。**
   工具执行的正确性依赖顺序：`Guard` 必须在任何分支前执行；`ToolResultWrap.historyMessage` 必须与 tool_use 顺序一致；工具结果既写 UI 又写 DB，还要回填 `agentHistory` 的 `dbMessageId`。这些约束没有编译期检查，只能靠设备回归。

2. **并发语义是隐式的。**
   同一 turn 的多个工具调用并发执行（`[T-android-concurrent-tools]`），结果按 `tool_use` 顺序而非完成顺序合并（14696–14701）。拆成多个类时，只要有一个 `async` 的调度上下文变了，就会出现"工具结果串位"这类只在真机上偶发的问题。

3. **prompt cache 对字节级改动敏感。**
   系统提示的拼接顺序（skills → global memory → daily memory → skill hint → MCP）被显式保持（17542–17589）。把 `buildSystemPrompt` 拆走时，任何空格、分隔符或空片段处理的变化都会让 provider 端缓存失效，表现为"功能没坏但费用和首字延迟变差"。

4. **没有 ChatViewModel 级回归网。**
   当前 754 个单元测试里没有一处直接构造 `ChatViewModel`；`ChatScreen` 的 27 处引用说明它同时是 UI 状态源。一次性重写的爆炸半径覆盖发送、重试、压缩、子代理、定时任务、语音、分享等所有入口。

5. **失败模式无法在 PC 上复现。**
   EMUI 会丢 logcat，很多问题只能在设备上看 AppLogger 落盘文件；一次大改后如果启动即崩或会话打不开，定位成本远高于分阶段迁移。

6. **本轮范围纪律。**
   需求说明书 P2-3 明确要求"只评估、不动代码"。本报告的所有拆分建议都应以独立任务、独立提交、独立真机验收执行。

---

## 3. 目标结构（边界）

保持 `ChatViewModel` 作为 Android `ViewModel` 与 UI 状态拥有者，把"对话循环"和"工具执行"下沉为可测试的普通类。建议的边界：

```
ui/chat/ChatViewModel.kt                 // 仅：UI 状态、意图入口、协程生命周期
  └─ agent/loop/ChatTurnRunner.kt        // 一条 run 的状态机（流式、回退、turn 上限）
       ├─ agent/loop/ToolBatchExecutor.kt// 单 turn 多工具并发、顺序合并、失败标记
       │    └─ agent/tools/ToolRegistry.kt
       │         ├─ ToolHandler (interface): name, risk, execute(ToolCallContext)
       │         └─ handlers/: ShellToolHandler, FileToolHandler, BrowserToolHandler,
       │                        MemoryToolHandler, DelegateToolHandler, UserAskToolHandler …
       ├─ agent/loop/TurnPersistence.kt  // assistant/tool-result 落库与 history 回填
       └─ agent/loop/ContextCompactor.kt // compact 标记、offload、上下文预算
  └─ data/session/SessionLoader.kt       // loadSession / 恢复 / 标题生成
```

关键契约（拆分前先定义，避免边拆边改语义）：

- `ToolCallContext`：只携带执行所需的只读依赖 + 明确的回调（`onToolBlockUpdate`、`onApprovalNeeded`），不再把整个 ViewModel 传进去。
- `ToolHandler.execute(ctx, args): ToolExecutionResult`：同步/挂起可选，但**不得直接读写 ViewModel 的 StateFlow**；UI 更新通过回调。
- `ToolRegistry.resolve(name)`：取代 `when (name)`；`Guard.classify` 仍由 `ToolBatchExecutor` 在调用 handler 之前统一执行，风险策略不分散到各 handler。
- `ToolBatchExecutor.execute(turnCalls): List<ToolResultPart>`：对外只暴露"按输入顺序返回结果"的契约，内部并发策略可替换。
- `TurnPersistence.persistAssistantTurn / persistToolResultMessage`：把 14732–14751 的落库顺序封装成一个事务式调用。

---

## 4. 拆分边界明细

### 4.1 风险门（最小、最先）
- 输入：`toolName`、`argsJson`、`sessionId`、`isInteractive`、`isHeadless`。
- 输出：`GuardDecision(allow | deny(reason) | ask(verdict))`。
- 现状代码：15022–15059。
- 边界理由：这是唯一必须在所有工具分支之前执行的逻辑，先抽成 `ToolGuardGate` 可以独立单测（不需要 Context 的分支用假实现；需要 Context 的审计走接口）。

### 4.2 工具路由与 handler
- 按域拆，不按工具逐个拆：
  - `ShellToolHandler`（含 `executeShellCommand`，16467 起，249 行）
  - `FileToolHandler`（read/write/edit/image）
  - `BrowserToolHandler`（`executeBrowserUseTool` + 工具开关）
  - `MemoryToolHandler`（`executeMemoryWriteTool` / `executeMemoryGetTool`）
  - `DelegateToolHandler`（`executeDelegateTask` 427 行 + `executeAgentStatus` 175 行 + resume）
  - `UserAskToolHandler`（含 deferred / recovery 分支）
  - `HubToolHandler`、`WebSearchToolHandler`（Amap/News/Stock）
- 边界理由：这些分支之间没有共享可变状态，只有 `ToolCallContext` 和返回类型；按域拆可以在 6~7 个 PR 内完成，每个 PR 只搬一个 `when` 分支。

### 4.3 单 turn 工具批次
- 输入：`List<ToolCall>`、并发上限、取消信号。
- 输出：按 `tool_use` 顺序排列的 `List<AgentContentPart.ToolResult>`。
- 现状代码：14267–14716（并发执行、截断参数修复、结果合并、SkillStore 折叠）。
- 边界理由：这是并发语义最集中的 450 行；抽成独立类后可以用纯 JVM 测试验证"完成顺序 ≠ 输出顺序"。

### 4.4 落库与 history
- 输入：assistant 文本、工具块、usage、reasoning、DB session id。
- 输出：`assistantDbId` / `toolResultDbId`，并回填 `agentHistory`。
- 现状代码：14729–14751。
- 边界理由：落库顺序（assistant 先、tool result 后）与 `[T-android-session-last-message-live-tool-call]` 的 live preview 是两条不同路径，必须先有契约再搬。

### 4.5 run 状态机
- `runAgentLoopBody` 的 3,163 行按 turn 生命周期切：
  1. 准备（skill hint、memory probe、参数校验）
  2. 请求与流式（provider 回退、错误分类、context overflow）
  3. 工具批次（4.3）
  4. 持久化（4.4）
  5. 循环退出判定（空 turn、turn cap、stop、steer）
- 边界理由：这五段之间的状态传递可以显式化为一个 `TurnState` 数据类；在完成 4.1–4.4 之前不要动它。

### 4.6 会话生命周期
- `sendMessage`（474 行）、`loadSession`（620 行）、`generateSessionTitleIfNeeded`（408 行）、`compactAllImpl`（547 行）属于另一条独立的拆分线，建议在工具链拆分稳定后再做。

---

## 5. 迁移顺序（每阶段可独立交付）

| 阶段 | 内容 | 前置 | 验收 | 预估 |
|---|---|---|---|---|
| P0 | 补特征测试：用现有纯逻辑断言 `ToolRegistry` 的 name→handler 映射、`ToolBatchExecutor` 的顺序合并、`GuardDecision` 三态；记录一份真实会话的 tool_use/tool_result 顺序作为 golden | 无 | 单测通过；golden 与当前设备输出一致 | 2–3 人日 |
| P1 | 抽出 `ToolGuardGate`（4.1），`executeTool` 开头改为调用它 | P0 | 单测覆盖 allow/deny/ask/headless；真机跑一次高风险工具确认审计行不变 | 1–2 人日 |
| P2 | 建立 `ToolRegistry` + `ToolHandler` 接口；先迁 3 个无状态工具（Amap/News/Stock），`executeTool` 保留 fallback `when` | P1 | 三个工具真机各跑一次；未迁移分支行为不变 | 2 人日 |
| P3 | 按域迁移文件/浏览器/记忆 handler；每次只搬一个分支，旧分支删除前保留一周 | P2 | 每个 handler 独立单测 + 真机 smoke；`Unknown tool` 文案不变 | 4–5 人日 |
| P4 | 迁移 `user_ask` / `delegate_task` / `hub_call` 三个有状态 handler；引入 `ToolCallContext` 回调 | P3 | 确认卡、子代理完成回调、hub 审批三种卡片均真机通过；无人在应答场景不阻塞 | 4–6 人日 |
| P5 | 抽出 `ToolBatchExecutor`（4.3），替换 14267–14716；保留旧实现直到对比通过 | P4 | 并发 3 工具时结果顺序与 golden 一致；截断参数修复路径仍触发 | 4–6 人日 |
| P6 | 抽出 `TurnPersistence`（4.4）与 `TurnState`，把 `runAgentLoopBody` 缩到 <800 行 | P5 | 会话重开、压缩标记、usage、live preview 全量回归 | 6–8 人日 |
| P7 | 拆 `sendMessage` / `loadSession` / `compactAllImpl`（4.6） | P6 | 发送、重试、切换会话、compact 真机回归 | 8–10 人日 |

合计约 **31–42 人日**（不含评审与返工）。若只做到 P5（工具调度与对话循环分离、`executeTool` 变成注册表），约 **17–24 人日**，已经能消除"风险判定塞在分发函数开头"这一主要痛点。

每个阶段的回滚方式相同：保留旧函数与新入口的开关（编译期常量或 `BuildConfig` 字段），真机验收失败即切回旧路径，不 `revert` 整个提交。

---

## 6. 风险点

| 风险 | 触发方式 | 缓解 |
|---|---|---|
| 工具结果串位 | 并发执行改为串行/线程池变化 | P5 的 golden 顺序测试；`ToolBatchExecutor` 只承诺顺序输出 |
| 风险判定被绕过 | handler 内部直接执行，忘记先过 `ToolGuardGate` | Registry 的 `execute` 是唯一入口；P1 加静态检查/单测遍历所有注册项 |
| 确认卡在无人在应答的运行中卡死 | 把 `guardInteractiveThisRun` 判定搬进 handler | 该判定留在 gate；P4 验收覆盖 headless/scheduled |
| prompt cache 失效 | 拆分 `buildSystemPrompt` 时改变拼接字节 | P6 前后对同一会话抓 `[Skill] hint probe` 与请求体指纹对比 |
| 落库顺序变化 | 先写 tool result 再写 assistant | P6 的 golden DB 断言；保留 `persistAssistantTurn` 返回 id 的时序 |
| 子代理/定时任务回归 | 共享 `activeSessionId` 或回调闭包被搬到错误层级 | handler 只拿显式 `sessionId`，不读 `activeSessionId` |
| SkillStore 记录质量下降 | 工具批次重构后 `skillSteps` 收集点丢失 | P5 验收中检查 `[Skill] recorded` 日志与 steps/chain 比例 |
| 编译期无保护 | Kotlin 协程/StateFlow 类型宽松 | 每阶段独立单测 + 真机冒烟；不做跨阶段合并提交 |

---

## 7. 工作量估计（汇总）

- 只拆工具调度（P0–P5）：**17–24 人日**，风险中等，收益最大（分发函数 15 分支 → 注册表，风险门独立可测）。
- 连对话循环一起拆（P6）：再加 **6–8 人日**，收益是把 3,163 行的 `runAgentLoopBody` 收敛到可读状态。
- 会话生命周期（P7）：再加 **8–10 人日**，收益偏向可维护性，紧迫性低于工具链。
- 完整目标结构：**31–42 人日**；建议分 3 个迭代交付，每个迭代结束时设备可正常安装使用。

---

## 8. 明确不做

- 本轮不修改 `ChatViewModel.kt` 的结构（本报告是唯一交付物）。
- 不调整 Guard 默认值、不改变"无人在应答时记拒绝并放行"的语义。
- 不改变任何工具的名称、参数 schema、返回文案或确认卡行为。
- 不在拆分过程中顺手升级依赖或替换 JSON/协程库。
