# 升级契约：设备攒问题，Claude 来改

**2026-11-20 与用户约定。** 这份文档定义 MiniPilot「自我改进」链条上
谁做什么、边界在哪。

## 角色分工

| 角色 | 做什么 | 不做什么 |
|---|---|---|
| **MiniPilot（端侧）** | 每天 23:30 自动复盘当天会话，写问题单到 `/var/minis/shared/self-improve/`；问题攒够时**上报告警** | 绝不改 rules.md / skill / 源码 / 设置；绝不自己编译或装机 |
| **Claude（PC 侧）** | 收到告警后拉卡 → 归并 → 提案 → 真机 A/B → 改码 / 编译 / 装机 → 提交推送 | 不在 A/B 判决为 `no-diff` 时往规则文件里加规则 |

用户原话：**「收集到问题清单到一定程度，他会找你，我已经约定修改源码编译安装必须找你。」**

## 升级触发条件（端侧自动判断）

问题单未上报数 ≥ 10，**或** 出现 `severity=blocker`，**或** 同一
`surface+tool` 组合累计 ≥ 3 张 → 写一条告警到
`/var/minis/shared/self-improve/alerts.jsonl`（UTF-8，一行一个 JSON 对象，
只追加）。上报后把本批最大问题单 id 写入 `.last_alert_id` 去重，
避免每天重复上报同一批。

告警只带「有多少张 + 路径 + 每行摘要 + 要 Claude 做什么」，**不带**正文，
更不带「你自己去改」的指示。

## 通道（踩过坑）

第一版设计是让设备往 PC 仓库挂载目录
`/var/minis/mounts/minis-share/minis-src/minis-inbox/alerts.jsonl` 写告警。
实测**不成立**：该挂载是 PC→手机的单向快照（设备上 `SYNC_INFO.txt` 记录
`source: PC D:\minis-src`、`synced_at: 11:32`），设备写入的文件不会回到 PC
（手机 `touch` 成功，PC 侧目录始终没有新文件）。

最终通道：**设备写本地全局目录 → PC 侧 watcher 通过 debug RPC 主动拉取**。

```
设备  /var/minis/shared/self-improve/alerts.jsonl   （skill 写入）
   ↓  debug.shellExecute: cat …
PC   .claude/bridge/watch.py 每 30s 拉一次
   ↓  镜像到 minis-inbox/alerts.jsonl 并输出（带 "_channel":"alert"）
Claude 会话
```

`.claude/bridge/watch.py` 同时保留原来的本地信箱通道
（`minis-to-claude.jsonl`，`_channel":"mailbox"`），两个游标独立。

## 验证记录（2026-11-20）

- 设备写告警行 → `debug.shellExecute` 读到 → watcher 一轮拉取输出
  `{"id":"a-test-001", ..., "_channel":"alert"}` → 本地
  `minis-inbox/alerts.jsonl` 出现镜像行。测试行与游标已清空。
- 手机对挂载目录的写权限已确认（root，770），但**方向**不通，所以不用它。

## 相关文档

- `self-improve-loop.md` — Phase 1：端侧问题单采集与调度
- `self-improve-phase2.md` — Phase 2：PC 侧归并、提案、A/B 闸门
- `self-improve-phase3.md` — Phase 3：问题单指向的 7 项产品/文档修复
