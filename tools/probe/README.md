# tools/probe/

Harness² 风格的 probe runner：从信箱生成任务集，为后续 A/B 对照做准备。

## 现状（第一步已完成）

`build_tasks.py` 读取 `D:/minis-src/.claude/bridge/minis-to-claude.jsonl`，
把每条 `from=minis` 的消息的 `ask` 字段（真实任务原文）写入 `tasks.jsonl`。

```bash
python tools/probe/build_tasks.py
# wrote 32 tasks -> tools/probe/tasks.jsonl
```

每行 JSON 字段：
| 字段 | 来源 | 说明 |
|---|---|---|
| `task_id` | `msg.id` | 信箱消息 id，`m-YYYYMMDD-NNN` |
| `source` | 固定 `"mailbox"` | 后续可加其他来源 |
| `ts` | `msg.ts` | 时间戳 |
| `severity` | `msg.severity` | `info` / `warn` / `blocker` |
| `summary` | `msg.summary` | 一行中文摘要 |
| `tags` | 关键词自动分类 | `hub/android/ios/voice/build/go/memory/tailscale/approval` |
| `prompt` | `msg.ask` | **原样任务指令，probe-runner 直接送进 session** |

## 已知噪声（build 阶段不过滤，runner 阶段处理）

11 / 32 条 `prompt` 以 `[FROM: MINIS]` 开头，内容是信箱协议约定
（"把回执投递到 xxx session"、"正文首尾加 [FROM: CLAUDE]" 等），
不是工程任务。runner 加载时应跳过这些行，判据：
```python
if task["prompt"].startswith("[FROM: MINIS]") and "chat.prompt" in task["prompt"]:
    continue
```

## 当前任务分布

| severity | 数量 |
|---|---|
| blocker | 2 |
| warn | 10 |
| info | 20 |

| tag | 数量 |
|---|---|
| hub | 20 |
| build | 19 |
| android | 8 |
| tailscale | 7 |
| approval | 7 |
| voice | 3 |

## 第二步（已实现）：probe-runner

`run.py` 对每条任务跑 1..N 个 harness 变体，比较工具调用轨迹（Harness²
方法论：不靠 ground truth，靠行为差异）。实际执行走 PC 上的
`claude -p --output-format stream-json` headless，而不是真机
`minis-sessions-cli` —— 后者是 iOS/Android 侧桥，PC 上无法直接调。

```bash
# 单变体基线（默认只跑非 hub、非 protocol 任务）
python tools/probe/run.py --model workbuddy/deepseek-v4.1-flash \
  --limit 1 --variants baseline

# 双变体 A/B（单任务）
python tools/probe/run.py --model workbuddy/deepseek-v4.1-flash \
  --only-task m-20261120-001 \
  --variants tools/probe/variants/baseline.json,tools/probe/variants/verbose_tools.json

# 指定模型是必须的：会话内的模型组名（group/smart-split[1m]）headless 不认
```

产物在 `tools/probe/runs/<run-id>/`：
- `<task>__<variant>.json` — 原始 stream-json 事件
- `<task>__<variant>.tools.jsonl` — 归一化的工具调用（tool 名 + 参数 hash + 输入）
- `<task>__diff.json` — 两变体对比（仅 A/B 时生成）
- `report.md` — 人读的汇总
- `manifest.json` — 配置 + 输入 sha256（论文要求 judge 与 refinement
  使用同一 bench/subset/seed，靠 manifest 冻结）

安全边界（2026-11-20 实测修订）：
- scratch 默认放 **系统临时目录**（`%TEMP%\minis-probe\<run-id>`），不在仓库内
  —— 否则子进程会向上发现仓库 `.claude/settings.local.json`、AGENTS.md、hooks。
- 默认 **`--strict-mcp-config`**：不加载任何 MCP/插件工具（claude-mem 的搜索
  工具会泄漏宿主记忆库）。
- 默认 deny：adb / curl / ssh / scp / gradle / git push / git commit / git
  checkout / echo / sed / tee，以及 PowerShell 的写文件、网络、设备类命令。
- 默认 **禁写**：Edit / Write / NotebookEdit 全部 deny。子进程与宿主共享文件
  系统且 Read 是全局白名单，一个「改代码」任务可能直接编辑真实仓库。只有
  `--allow-writes`（仅限一次性 VM/账号）才放行。
- **已知泄漏面**：用户全局 settings 的只读白名单（Read/Glob/Grep + 各类只读
  PowerShell）仍生效，子进程能翻家目录、`~/.claude`。目前接受——A/B 两变体
  面对同样的泄漏面，且写路径已封。需要更严格隔离时用一次性账号或 VM。
- `--include-hub` 才能包含 hub 任务；即便包含，scratch 也连不上生产 relay。
  未来跑 hub 交互先用 `python tools/probe/start_relay.py`（端口 8790 桩 relay）。
- 子进程模型用 `--model` 显式钉住（继承会话的 `group/smart-split[1m]` 这类
  模型组名 headless 不认，会 `unrecognized_model` 卡死）。

## 冒烟 + A/B 实测状态（2026-11-20）

已打通：任务 → 变体 → `claude -p --output-format stream-json` → 工具轨迹 →
diff 报告。实测记录：

- `smoke3`：19 次工具调用，31 个 assistant turn 全部由
  `workbuddy/deepseek-v4.1-flash` 生成（`--model` 生效）。
- `smoke4-isolated`：隔离改造后同任务重跑，无 MCP 泄漏、无 adb/curl，
  scratch 落在 `%TEMP%`。
- `ab-voice`（m-20261120-001，baseline vs verbose_tools）：14 vs 15 次调用，
  工具构成相同（PowerShell×10、Read 3/4、Grep×1）。**verbose_tools 未产生
  可测偏移**——但两次都在 120s 截止时仍处探索阶段，属截断轨迹，只能算弱证据。
  要下结论需调大 `--timeout-s` 或缩窄任务。

## 第三步（已实现）：真机探针 run_device.py

`run_device.py` 不再是代理——它直接驱动**手机上的真实 App**，走 debug JSON-RPC：

```
chat.prompt          → 新建会话、起一轮（可钉 modelEntryId / thinkingLevel）
chat.session.status  → 轮询直到 agent 空闲
chat.messages.list   → 取回 transcript（含 toolCalls: name/input/status）
```

```bash
adb -s MDX0220A21035172 forward tcp:5321 tcp:5321
python tools/probe/run_device.py --run-id identity1 --label identity \
  --model-entry 380b0b7c-0275-40c8-9294-681cbcc9b68d/workbuddy/deepseek-v4.1-flash \
  --prompt "只读回答：1) 你运行在哪个应用里？应用叫什么名字？..."
```

轨迹格式与 PC 版一致（`tool` / `args_hash` / `input` / `status`），可直接用
`run.py` 的 `diff_trajectories` 做对比。

注意：
- `isRunning` 有竞态（agent 还没启动就显示空闲），所以用 `wait=true` 阻塞等待，
  兜底轮询也要求「见过 running」或「已有 assistant 消息」才算结束。
- 手机上的 agent 有真 shell（`shell_execute`），Minis **没有**关掉它的开关
  （`AgentToolSwitch` 只覆盖 browser/agents/user_ask/hub）——真机探针只能挑
  不改动手机的任务来跑。
- 包名仍是 `com.openminis.app.dev`，应用显示名已是 MiniPilot。

### 提示词级注入（T-probe-overlay，2026-11-20 加入）

`chat.prompt` 新增 `systemPromptAppend` 参数：一段候选 harness 文本会追加到
该会话 VM 的**真实系统提示词末尾**（`buildSystemPrompt` 尾部）。VM 按会话缓存，
所以叠加文本随探针会话存亡、不写 DB、普通对话永远为 null。

```bash
python tools/probe/run_device.py --run-id ov1 --label who \
  --model-entry <entry-id> --system-overlay "回答任何问题先输出 [OVERLAY-OK]。" \
  --prompt "你是谁？"
```

这意味着：**改一条规则不再需要重编译**。候选措辞 → 真机 A/B → 赢家才写进
`ChatViewModel.kt` 编译装机。

### 任务集分拣与 device_tasks.jsonl

`build_tasks.py` 现在给每条任务打两个字段：
- `kind`: `protocol` / `verification` / `engineering`
- `device_safe`: 能否无人值守跑在真机上

信箱 32 条的实测结果：**12 protocol + 13 verification + 7 engineering，
device_safe 为 0**——信箱里的任务要么是协议、要么是验收报告，要么要求
编译/装机/走审批。所以真机探针另用一份手工校准的只读行为探针集
`device_tasks.jsonl`（10 条，每条对着一类 `[T-xxx]` 规则：品牌一致性、
执行纪律、设置深链、记忆开关、tool_title、审批边界……）。

`run_device.py` 批量跑时默认只取 `device_safe=true`；显式 `--only-task`
可越过该过滤；`--include-unsafe` 放开全量（自担风险）。

### iOS 侧 parity（2026-11-20）

iOS 的 App 本身仍叫 **Minis**（`PRODUCT_NAME=$(TARGET_NAME)`、Share 扩展
"Share to Minis"），所以 iOS 提示词中的 Minis **没有**改成 MiniPilot——
改了反而会让 agent 自称 MiniPilot 而 App 叫 Minis。iOS 只补探针注入口：

- `AIChatViewModel.debugSystemPromptAppend` 字段
- `baseSystemPrompt` 尾部追加（与 Android 同位置语义）
- `DebugRPCProviderChat` 从 `chat.prompt` 参数读取并赋值（每次调用都赋值，
  省略或空串即清除，避免复用 VM 残留）
- `DebugMethodRegistry` 声明参数

Windows 上没有 Xcode，iOS 侧只做了源码级实现与人工核对，未编译验证。

### 真任务语料 device_real_tasks.jsonl（2026-11-20）

信箱任务需要 PC 仓库+审批，历史会话已被用户清空，所以真机语料改为**直接派真任务**：
10 条可在手机上真实执行的工程任务（shell/文件/计算/错误处理），写入一律落在
`/var/minis/workspace/probe/`，全部 `device_safe=true`：

- 文件+计算：建 CSV、求和求均值（rt-001）、随机温度统计（rt-002）
- 脚本：词频 top3（rt-003）、故意缺依赖的失败诊断（rt-010）
- 文件：两文件 diff（rt-005）、目录巡检（rt-006）、读不存在的文件（rt-007）
- 设备只读：电量与存储（rt-004）、列出定时任务（rt-008）
- 多步一致性：写→算→读回（rt-009）

这批任务的轨迹里有真实 `shell_execute` / `file_write` / `file_read` 调用，
可直接当 Harness² 的行为基线；后续候选规则用 `--system-overlay` 或
`--tasks device_real_tasks.jsonl` + `compare_runs.py` 做 A/B。

历史收割脚本 `harvest_tasks.py` 保留：会话重新积累后可以再跑
（输出 `real_tasks.jsonl` 已 gitignore，含个人会话内容，永不提交）。

### 跨平台 endpoint

`run_device.py --endpoint host:port` 跳过 adb forward，直连任何可达的
debug server（例如 iOS 设备经 SSH 隧道转发的 5321）。

### 首个 A/B 结果（2026-11-20，Android 真机）

同一批 10 条探针，baseline vs 候选规则「回答 ≤120 汉字，先结论后要点」
（经 `systemPromptAppend` 注入，零重编译）：

| task | chars A | chars B | len ratio | similarity |
|---|---|---|---|---|
| dev-identity | 665 | 149 | 0.22 | 0.297 |
| dev-selfref | 727 | 157 | 0.22 | 0.192 |
| dev-discipline | 765 | 174 | 0.23 | 0.173 |
| dev-watch | 1276 | 177 | 0.14 | 0.146 |
| dev-tools | 1677 | 210 | 0.13 | 0.038 |
| dev-links | 488 | 174 | 0.36 | 0.438 |
| dev-memory | 1137 | 272 | 0.24 | 0.085 |
| dev-tooltitle | 551 | 189 | 0.34 | 0.189 |
| dev-approval | 1404 | 266 | 0.19 | 0.099 |
| dev-scheduled | 755 | 185 | 0.25 | 0.196 |

结论：规则生效且可量化（长度全部收敛到 0.13–0.36，要点保留）。
这正是 Harness² 的用法——不需要标准答案，同一批任务上的行为差异本身
就是判据。`compare_runs.py` 生成同样格式的报告。

### 首个真机结论（2026-11-20）

任务「你运行在哪个应用里？应用叫什么名字？」→ 模型答「**Minis**」；
但应用启动器里叫 **MiniPilot**，模型自己的名字也是 MiniPilot。
根因：`ChatViewModel.kt` base prompt 第 17598 / 17603 / 17616 行仍写「Minis」，
改名 commit 5d244b4 只改了品牌层（app_name / 文案），没进提示词。
**这类缺陷正是 Harness² 方法要找的**：人 review 提示词看不出来，探针一句话验得出。

**已修复并真机验证（build 57, 15:52）**：三处改为 MiniPilot（`minis-*` 命令名与
`minis://` scheme 不动），重编译装机后同一探针作答「MiniPilot」。
`systemPromptAppend` 同样通过真机验证：注入「第一行必须输出 [OVERLAY-OK]」后，
回答首行确实出现 `[OVERLAY-OK]`——真系统提示词级注入、零重编译。

## 自我改进 Phase 1：端侧问题单采集

`self-improve-SKILL.md` 是安装在真机上的每日自我诊断 skill
（`/var/minis/skills/self-improve/SKILL.md`）。每天 23:30 由
`minis-scheduled` 触发，审计当天真实会话的工具轨迹，把失败信号写成
结构化问题单到 `/var/minis/shared/self-improve/`（必须是全局 shared，
**不能写 session 私有的 `/var/minis/workspace/`**）。

设计、调度记录与首次真机验证见 `docs/specs/self-improve-loop.md`。

## 自我改进 Phase 2：PC 侧消费者

四步流水线，全部在 PC 上跑，卡片与档案**一律落在仓库外**（含用户会话
原文，不进版本库）：

```bash
# 1) 把设备上的问题单取到 PC（tar|base64 走 debug.shellExecute）
python tools/probe/pull_cards.py --date 2026-11-20
# 2) 校验 + 归并 + 排序 → dossier.md / clusters.json / worksheets/
python tools/probe/improve.py
# 3) headless claude 为每组写候选规则 + 探针任务
python tools/probe/improve_propose.py --only C01,C07
# 4) 真机 A/B（baseline vs systemPromptAppend）+ 随机顺序裁判
python tools/probe/improve_ab.py --only C01,C07 \
    --model-entry <entry-id>
```

判决只有三种：`adopt` / `no-diff` / `regress`；只有 `adopt` 才允许写进
rules.md。首次完整跑完 9 张卡 → 9 组 → 3 条候选规则 → **全部 no-diff**
（规则层对这些失败类型已经饱和），结论与产品修复清单见
`docs/specs/self-improve-phase2.md`。

## 待办

- 任务集人工分拣：32 条里 11 条协议噪声 + 若干「验收/报告」类，真正可做
  的工程任务约 5–7 条。建议在 tasks.jsonl 加 `kind` 字段人工标注。
- 轨迹完整性：agent 需要能读到仓库代码才有真实行为；当前靠全局 Read 白名单
  偶然可达。可选方案：`--add-dir D:/minis-src` 只读挂载 + 写仍 deny。
- Phase 3：把问题单指向的 7 项产品/文档修复排成一批落地（android-speak
  完成回执、scheduled_task 全角标点、搜索工具报错语义、browser_use 参数
  校验、会话落库、技能文档命令写法、早安脚本前置检查）。
