# 2026-11-22 自我改进问题单归并（8 张 → 7 个根因）

来源：设备告警 `a-20261122-001`，问题单取自
`/var/minis/shared/self-improve/2026-11-22-*.json`（原文含用户对话，未入库；
本地副本在 `%TEMP%\minis-self-improve\cards-20261122`）。

归并原则：按**根因**而非按会话合并；同一根因下注明卡片与危害等级。
涉及源码的条目按约定由 Claude（PC 侧）修改、编译、装机；规则类先过 A/B 闸门。

## 总览

| 根因 | 卡片 | 严重度 | 杠杆 | 处置 |
|---|---|---|---|---|
| R1 流式工具参数静默降级为 `{}` | 91f2a642-1、3998a307-1 | **blocker** | 源码 | 显式失败 + 按 index 归并 + 日志 |
| R2 循环护栏只告警不阻断 | 91f2a642-1（下游） | blocker 关联 | 源码/阈值 | 评估 BLOCKED 阈值与同错计数 |
| R3 环境能力与 RPC 契约不可发现 | 6443d25e-1、5ae83322-1 | warn / info | 工具描述 + 技能文档 | 补 `rpc.discover`、参数签名、沙箱能力边界 |
| R4 派活越界给改法 | 91f2a642-3 | warn | 规则 | rules.md 候选，走 A/B |
| R5 检索工具整句当 query | 0c91b94d-1 | warn | 工具描述/规则 | query 构造约束，走 A/B |
| R6 语音短指令话题粘连 | 57cc96fd-1 | warn | 待定位 | 先取证：路由层 or 模型层 |
| R7 worktree 出包缺 jniLibs | 91f2a642-2 | warn | 构建流程 | 补齐 `.so` 获取步骤/跟踪规则 |

## R1 流式工具参数静默降级为 `{}`（blocker）

**证据**：hub_call 连续 14 次同一失败（`shell needs args.command`），其中
多次调用参数为空对象；并行调用的第二路出现工具名损坏（`shell_executable`、
`write`）与参数丢失，产品只回 `Unknown tool`。

**已核实的代码路径**：

- `src/android/app/src/main/java/com/openminis/app/provider/anthropic/AnthropicProvider.kt`
  - 只用**单个** `currentToolId / currentToolName / toolInputBuffer`，完全忽略
    `content_block_start/stop` 的 `index`；任何乱序或未收到 stop 的块都会覆盖/清空缓冲。
  - 参数 JSON 解析失败时静默 `JSONObject()`（第 284-288 行），调用方拿到空参数照常执行。
- `src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt`
  - 并行调用按 `optInt("index", 0)` 归并；**index 缺失时全部落到 0**，
    两个并行调用的 name 互相覆盖、arguments 粘连成非法 JSON → 解析失败 → `{}`。
  - 同样静默降级：`JSONObject(acc.args.toString())` 失败即 `JSONObject()`（第 1805、1818 行）。
  - 流中途断开时会把半截参数「冲刷」成 ToolCallComplete（T248 注释），参数仍是 `{}`。

**修法方向**（待实现）：

1. 解析失败不再静默：产生显式失败块（例如「工具参数不完整：JSON 截断」），让模型
   重新构造参数，而不是把 `{}` 交给工具层。
2. Anthropic 按 content block index 维护缓冲；OpenAI 在 index 缺失时用 `id`
   或到达顺序兜底，禁止无条件回落 0。
3. 保留参数长度与解析结果的诊断日志，便于真机取证。

## R2 循环护栏只告警不阻断

同一 blocker 的下游：`[LOOP WARNING]` 出现在第 10 次，之后仍重试到 14 次。
`ToolLoopDetector` 有 `[LOOP BLOCKED]` 分支，但阈值/计数口径（同名 + 同参 vs
同名 + 同错）需要复核，确保「重复同一失败」能升级为阻断。与 R1 一起验证：
参数修复后，若仍重复同一错误，阻断应生效。

## R3 环境能力与 RPC 契约不可发现

- debug RPC：`debug.methods` 不存在，正确入口是 `rpc.discover`；`logs.read`
  参数是 `offset/limit` 且文件名为 `minis-YYYY-MM-DD.log`。
- 沙箱能力：无 `logcat/dumpsys/getprop`；`run-as` 不可用（非 debuggable）；
  非 tty 命令（如 kaggle）会卡在交互确认，必须带确认参数或 `yes |`。
- 处置：把这些写进工具描述/技能文档，减少反复试探；info 级卡片不单独开修复。

## R4 派活越界给改法（规则候选）

根因是行为规则：把任务交给 Claude 时，只陈述问题与目标，不指定改哪个文件、
怎么改。用户已两次纠正并要求写入全局记忆。
规则候选（走 A/B 闸门）：

> 把问题交给外部执行者时，只描述现象、影响与目标，不指定文件、函数或改法。

## R5 检索工具整句当 query（规则/描述候选）

news_search 把「搜索一下今天的新闻」整句作为 query，结果退化为泛化关键词。
规则/描述候选：检索类工具先提取主体与限定词，去掉口语引导语。

## R6 语音短指令话题粘连（待取证）

「八秒内无声纹就停止聆听」被理解成「停止播放音乐」。属意图路由还是上下文
粘连需实证：对照语音路由层与模型前导文本。取证前不预设修法。

## R7 worktree 出包缺 jniLibs

`.gitignore` 只跟踪 `libproot-loader*.so`；`libproot.so`、`libMNN.so`、
`libmnnllmapp.so` 未跟踪。当前 v58 产物含全部 4 个 `.so`（已 `unzip -l` 核实），
但**新建 worktree/干净克隆出包会缺**，导致沙箱全线不可用。
处置：明确 `.so` 获取/复制步骤或调整跟踪规则，并加入出包后自检
（`unzip -l app-perf.apk | grep -E 'libproot|libMNN'`）。

## 建议顺序

1. R1（blocker，源码 + 单测）→ R2 一并验证
2. R7（构建流程，防止再次出坏包）
3. R3（文档/描述，低风险）
4. R4 / R5（规则候选，走 `improve_propose.py` + `improve_ab.py` 真机 A/B）
5. R6 先取证再定

## 进展（2026-11-23）

- **R1 + R2 + R7 已实现**：分支 `harness-r1-toolcall-args`，提交 `f640706`，已推 `minipilot`。
  - 新增 `provider/ToolCallArgs.kt`（`parseToolArgs` + 按 index 分桶的 `ToolCallBuffers`）
  - Anthropic 按 `content_block` index 累积；流结束未收完的参数带原因冲刷
  - OpenAI 并行调用缺 `index` 时按 id/到达顺序分配；两条 API 路径解析失败都带原因
  - `ToolCallComplete.argsInvalidReason` + ChatViewModel 执行前拒绝并合成 tool_result
  - 非 poll 同参同错达 20 次升级为 `LOOP BLOCKED`
  - `verifyJniLibs` 预检：缺 `.so` 直接构建失败
  - 单测：ToolCallArgsTest 8、ToolLoopDetectorNonPollTest 3、ToolLoopDetectorTest 22、AnthropicProviderTest 32、OpenAIProviderTest 25、ChatCompletionsToolPairingTest 19 全绿
  - v58 已装机（10:02:08），沙箱与一次真实工具调用（shell_execute → TOOLCALL_OK）冒烟通过
- **R3 已实现并验证**：`shell_execute` 描述写清沙箱边界（无 logcat/dumpsys/getprop/am/pm/run-as）、`minis-debug --help` + `discover` 列全方法/参数名、交互式提示会挂住；提交 `02ab2fc` 已推送并装机（10:06:05）。真机验证：模型能逐条复述新描述。
- **R4 规则候选：A/B 三轮均 no-diff → 不写入 rules.md。** 探针分别试了子 agent 派活、
  强制贴出派活原文、写派活单到临时文件；baseline 与 overlay 都只给「问题/需求/目标/验收」，
  不指定文件与改法——现有 `pc-claude-dev` 技能已经把这条行为固化，规则没有增量。
- **R5 已修复（源码，提交 61a1ebd + 2b06ad7 + 8b7d0c4，已推送并装机）**：
  真机复现发现根因不在模型——本地快路径 `SlotExtractor` 把整句塞进 query，
  `normalizeQuery` 剥词后残留「的」；另外 Google News 标题后缀与 `<source>` 并存导致
  来源/摘要各显示两次（语音读两遍）。修复：助词清理+空查询回落「最新新闻」；
  标题后缀与来源一致时剥离；摘要重复时丢弃；标题头不再二次拼「新闻」。
  真机复测：`搜索一下今天的新闻` → 「最新新闻，范围：最近24小时」，来源与摘要各一次。
- 待办：R6 取证
