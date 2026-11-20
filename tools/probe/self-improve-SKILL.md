---
name: self-improve
description: 每日自我诊断——从当天会话工具轨迹中找失败信号，写结构化问题单到 /var/minis/shared/self-improve/，供 Claude 侧改进器消费。只有当用户明确要求、或定时任务触发时才运行；不在普通对话中自动触发。
---

# Self-Improve · 每日失败复盘

你是 MiniPilot 的「证据采集器」。你的唯一产出是结构化问题单，**绝不修改**
rules.md、skill、源码、设置或任何产品文件。写文件只能落在
`/var/minis/shared/self-improve/`。

**不要写 `/var/minis/workspace/`**：那是**本会话私有**目录，定时任务写进去
的文件在其他会话和外部读取端（PC 侧改进器）里都看不到。2026-11-20 首次
触发时 10 张问题单就丢在了私有 workspace 里。`shared` 才是所有会话共享的
全局目录。

## 触发条件

- 定时任务（每天 23:30）的 prompt 里写「读取并执行 self-improve skill」
- 或用户明确说「做一次自我诊断」

普通对话里不要主动运行。

## 步骤

### 1. 列候选会话

```sh
minis-sessions-cli list --start $(date +%F) --limit 100 -q
```

解析 JSON，跳过 `source=debug` 的会话（那是我们自己的探针）。只保留
`updatedAt` 在今天的。若某会话 last_active 显示今天但消息体查不到当天内容，
在结果里记一条 `info` 级「轨迹盲区」问题单，不要假装没问题。

### 2. 拉工具轨迹

对每个候选会话：

```sh
minis-sessions-cli messages --id <session_id> --tools -q
```

需要完整文本时加 `--full`（上限 50000 字符/字段）。消息很多时用
`--offset N` 分页，避免截断。输出包含 `tool_calls[{name,input}]` 与
`tool_results[{success,output}]`。

### 3. 找四类失败信号

| 信号 | 判据 |
|---|---|
| 循环 | 同一工具 + 相同/近似参数连续 ≥3 次，或 ≥5 轮工具调用无进展 |
| 工具失败 | `tool_results.success=false`，或 output 含 `error` / `Invalid` / `denied` / `not found` |
| 用户纠正 | 用户消息含「不对/错了/不是这个/你刚才说/没有听到/没生效」等 |
| 空头承诺 | 助手最后一条以「我会稍后…/稍后回来看/明天再…」等结尾（不等待即结束） |

**不要编造**。没有信号就写一条 count=0 的摘要。区分「真错误」和
「环境不可用」（后者标 info 而非 warn）。

### 4. 写问题单

每条形如：

```json
{
  "id": "2026-11-20-<session8>-<seq>",
  "ts": "2026-11-20T23:30:00+08:00",
  "symptom": "一句话描述失败",
  "evidence": {
    "session": "<session_id>",
    "message_index": 12,
    "tool": "browser_use",
    "excerpt": "原始输出/用户原话（≤300字）"
  },
  "surface": "system_prompt | tool_desc | threshold | skill | guardrail",
  "repro": "下次怎么复现或用哪条历史会话可查",
  "severity": "warn | blocker | info"
}
```

文件名：`/var/minis/shared/self-improve/<date>-<session8>-<seq>.json`
（开始前先 `mkdir -p /var/minis/shared/self-improve`）

当天无问题时写 `/var/minis/shared/self-improve/<date>-summary.json`：
`{"date":"YYYY-MM-DD","count":0,"note":"..."}`

### 5. 汇总回答

列出写出的问题单路径 + 每条一行摘要 + 按危害排序的前 3 条。
指出根因属于哪一类（行为规则 / 工具描述 / 阈值 / 技能 / 护栏），但不要改。

## 边界

- 不读密钥、环境变量值、API key、OAuth token
- 不改产品任何文件；只写 self-improve 目录
- 不把 debug 探针会话当真实用户问题
- 不确定时标 info，不要把猜测写成 blocker
