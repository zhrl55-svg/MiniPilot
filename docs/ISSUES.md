# Minis 源码待修清单（下次派活用）

> **范围**：只收录**能改 Minis 源码 / 仓库文件**修掉的问题。发现即登记，攒够一批再一次性派给 PC 上的 Claude Code（走信箱 `D:\minis-src\.claude\bridge\minis-to-claude.jsonl`）。
> **纪律**：每条必须有可复现证据（贴日志/源码行），不写"感觉有点怪"。修完由我独立验收，状态推进 `待修 → 已报 → 已修待验 → 已关闭`。
> 非源码类问题（magpie 上游、PC 环境、我的误判等）不进本文件，见 daily log 与 GLOBAL。

---

## 派活范围一览

| 编号 | 问题 | 涉及文件 | 影响面 | 状态 |
|---|---|---|---|---|
| F-004 | `life.contacts/dial` 英文关键词 `call ` 命中 `hub_call` → **可能真的拨号** | `DefaultAssistantRoutes.kt:569-572` | 前台弹拨号确认卡 | 已关闭（14935f5） |
| F-007 | HubClient 断线后**不重试首选 relay**，粘在兜底 relay 上 → 端点重启后手机不回流直连 | `hub/HubClient.kt` | 端点重启需人工干预才能恢复直连 | **已关闭**（ba2d9bc + 9383520） |
| F-006 | 持久 shell 会话（无状态执行器仍无法 cd / 保持 cwd） | `tools/hub/executor.py` | 连续操作不便 | 待修（可选，优先级最低） |

### 已关闭（2026-11-17 批次验收通过）

| 编号 | 问题 | 关闭依据 |
|---|---|---|
| F-005 | hub 路径白名单 `device.roots` 代码已实现但线上 config 未配置 | **2026-11-17 22:0x 已启用**：PC `tools/hub/config.listen.json` 的 `device.roots` 加 `["D:/minis-src"]`（备份 `config.listen.json.bak.20261117T141000Z`），重启执行器后实测 `file.get D:/minis-src/.claude/bridge/minis-to-claude.jsonl` → 成功（size=44068）；`file.get C:/Windows/win.ini` → 仍被拒（allowed: hubroot, D:/minis-src）。**用户已授权放宽** |
| F-001 | `tool.file-write` 被"提到写入"误触发 | 日志 `[21:43:40]`「这句话里写入了很多细节」→ `decision=cloud reason=no-args`；`[21:43:45]`「…整理成一个文档放桌面」→ `cloud reason=no-args`（修复前 `[21:38:44]` 同句为 `local file_edit/edit`，且端侧模型把路径猜成 `/etc/hosts`）；`[21:43:49]`「把 hello 写入 /tmp/a.txt」→ 仍 `local file_write/write`，真命令不误伤。提交 `32f403f` + `7cfe830` |
| F-002 | `[FROM: …]` 内部消息被端侧路由打分 | 日志 `[21:43:36]`「[FROM: CLAUDE] 这条交接里写入了很多细节」→ `decision=cloud reason=internal-message`。提交 `32f403f` |
| F-003 | 仓库工作树遗留临时文件 | `git status` 干净；`.gitignore:204 .tmp/`、`:205 .workbuddy-ai/`、`:207 tools/hub/.tmp_off*.txt` 三个 `check-ignore` 命中。物理文件有意保留（休眠计划任务 `minis-context-build/test` 仍指向 `.tmp\*.vbs`）。提交 `9a80cb2` |

---

## F-007 HubClient 断线后不回流首选 relay —— 已关闭（2026-11-17 23:2x）
- **修法**：`ba2d9bc`（首选 relay 优先 + 定期巡检）、`9383520`（巡检循环常驻，仅 stopped 才退出 —— 修 F-007b 的一次性巡检缺陷）。
- **现象**：2026-11-17 22:00:58 我重启了 PC 侧 hub 执行器（改 `config.listen.json` 加 roots）。**执行器在 22:00:58 正常起来，但手机 7 分钟内没有重连**，`hub_call` 一直报「找不到设备「AUSA」。当前在线：ASUS-PC」。
- **证据**：
  - PC `executor.log` 22:00:58 之后只有我自己的 curl 探针（`22:05:36 connection rejected (426 Upgrade Required)`），**没有任何 `phone attached`**。
  - buntu 侧 `ss -tnp | grep 8787` 显示手机 `100.88.79.70:22462` **一直挂在兜底 relay**（`ws://100.95.180.77:8787`）上；PC 侧直连端点从 22:00:58 起没有手机连接。
  - 端点本身健康：从沙箱（经 app 的 tsnet）`curl -k https://asus.saola-mintaka.ts.net:8443/v1/hub` → **426 Upgrade Required**（即 WS 端点活着）。
  - 恢复办法（验证用）：把手机 `hub.json` 临时改成只剩 `wss://…:8443`，再 `systemctl --user restart minis-hub-relay.service`（buntu，user 级单元）→ 手机在 **22:07:22** 立刻 `phone attached` 直连端点；随后把 `hub.json` 恢复成两条（备份 `hub.json.bak.20261117`）。
- **判断**：**首选 relay 断掉后，客户端不会回头重试它，而是粘在兜底 relay 上**。今天 12:58 那次 18 秒重连说明它偶尔会重试首选，行为不稳定。
- **建议修法**：`HubClient` 在连接断开/心跳超时时，回到 relay 列表**首项**重试，并加一个定时巡检（首选 relay 每 N 分钟探一次），而不是"哪个先成用哪个、之后一直用"。
- **验收标准**：PC 端重启执行器 → 手机应在 60 秒内重新出现在 `executor.log` 的 `phone attached`，且 buntu 8787 上不再有手机的连接。
- **验收证据**（executor.log）：第 1 轮 23:26:37 listening → 23:26:44 `connection rejected (426 Upgrade Required)` → 23:26:45 connection open；第 2 轮 23:27:21 → 23:27:44 426 → 23:27:45 connection open（第二轮证明巡检常驻）。buntu `ss -tnp | grep 8787` 已无手机连接；装机 sha256 `05c60740…d34eaaa`、versionCode 55、lastUpdateTime 23:26:10。
- **注意**：Claude 重启 executor 时我的 `hub_call` 会断，请他提前打招呼。

---

## F-004 `life.contacts/dial` 误触发 —— 已关闭（2026-11-17 22:2x）
- **原症状**：消息里出现英文 `hub_call ` → 命中 `life.contacts/dial`，前台弹**拨号确认卡**（真执行就是真打电话）。
- **根因**：`DefaultAssistantRoutes.kt:569-572` 的 keywords 含 `"call "`，pattern 无词边界，命中 `hub_call` 内部。
- **修法**（提交 `14935f5`）：匹配器把 `_` 计入 ASCII 词内字符；pattern 加 `(?<![A-Za-z0-9_])call\b`；exclude 补 `hub_call`/`shell`/`command`/工具；`SEED_VERSION` 升 14，迁移时替换旧内置 pattern（保留用户自建）。
- **验收证据**（设备端日志）：`[22:25:55]「用 hub_call 工具在电脑上执行 shell…」→ decision=cloud reason=no-args`；`[22:26:00]「call Alice Wang」→ decision=local contacts/dial score=70`（正例仍命中）。

## F-005 hub 路径白名单 —— 已关闭（2026-11-17 22:19）
- **原症状**：executor.py 实现了 `device.roots`，但线上 `config.listen.json` 没有该字段 → `D:/minis-src` 被拒。
- **修法**（提交 `2162211`）：`device.roots = ["D:/minis-src"]`，端点重启重载配置。
- **验收证据**：`file.get D:/minis-src/.claude/bridge/minis-to-claude.jsonl` → 成功（size=44068）；`file.get C:/Windows/win.ini` → 仍被拒（allowed: hubroot, D:/minis-src）。
- **附带**（提交 `b316369`）：`approval_timeout_s` 120 → **600**（审批横幅需要人看见的时间）；副作用：无人应答会占住执行通道 10 分钟（`max_concurrent_calls=4`）。

## F-006 持久 shell 会话 —— 待修（可选）
- **状态**：Claude 列为可选、优先级最低，本批未做；线上仍是**无状态执行器**（每次调用独立进程、cwd 固定 `hubroot`）。
- **影响**：连续操作（cd、export、进入同一 shell 上下文）无法保持；每次都要自带绝对路径。
- **建议**：等用户明确要做再做，不主动派。

---

## 附录：全量单测基线（2026-11-17 批次**未跑**，以下供下轮对照）

上一版基线：**6561 用例 / 71 失败 / 24 个失败类**。失败类清单：

```
com.openminis.app.backup.BackupHistoryStorageTest
com.openminis.app.ctx.CtxFixJWireShaTest
com.openminis.app.ctx.CtxInvarianceTest
com.openminis.app.data.session.DirectorySizeSymlinkTest
com.openminis.app.data.session.OrphanedSessionFilesTest
com.openminis.app.data.session.Review0923SessionStorageSymlinkTest
com.openminis.app.data.session.SessionStorageTest
com.openminis.app.imagerepro.ImageReproGateTest
com.openminis.app.imagerepro.ImageReproHistoryFactsTest
com.openminis.app.mcp.oauth.McpOAuthGuardsTest
com.openminis.app.mcp.oauth.McpOAuthSignInTest
com.openminis.app.network.NetworkClientsTest
com.openminis.app.network.NetworkCoverageTest
com.openminis.app.sandbox.FreshRunnerScriptTest
com.openminis.app.sandbox.NativeOffloadHardeningTest
com.openminis.app.sandbox.SessionIsolationTest
com.openminis.app.sandbox.ShellParityFixesTest
com.openminis.app.sandbox.SpawnSignalMaskTest
com.openminis.app.sandbox.StderrDrainTest
com.openminis.app.speech.VoiceAsrRetryTest
com.openminis.app.tools.FileToolSizeGuardTest
com.openminis.app.ui.chat.ScheduledPrefillWiringTest
com.openminis.app.ui.sandbox.FileBrowserDeleteSymlinkTest
com.openminis.app.usage.UsageVectorsTest
```

> 2026-11-17 22:0x 实测（build 55 装机后）：设备端 `versionCode=55` / `lastUpdateTime=2026-11-17 21:43:12`；装机包 sha256 `61b98757c6aedd8f69a229f14c116432952433289ae20817a277ce387e5c07f8`，与 PC 端 `outputs/apk/perf/app-perf.apk` 及 `outputs/apk/github/perf/app-github-perf.apk` 三者一致。

---

## 下次派活模板（照抄改）

```
[FROM: MINIS]
本轮修 /var/minis/shared/changelog/ISSUES.md 里的 F-007（必须，HubClient 重连策略）+ F-006（可选，持久 shell 会话）。F-004/F-005 已关闭。
逐条要求：
1. 先复述每条的复现方式与验收标准，确认理解一致；
2. 改源码 + 补单测（任务名 testGithubPerfUnitTest），跑**全量**并报"用例数 / 失败数 / 失败类清单"，
   与基线 6561/71/24 对照，说明是否引入新失败（上批未跑全量，这批必须跑）；
3. 流程固定为：先提交 → 再编译（assembleGithubPerf）→ 报 APK 绝对路径、sha256、字节数、
   apksigner 证书 SHA-256 → 再装机到 192.168.2.32:5555；
4. 装机后回执写明设备端 versionCode / lastUpdateTime，以及各条的真机验收结果（贴 fastpath 日志原文）；
5. 有任何一条没做完或受阻，立刻写回执说明卡在哪一步，不要自行跳过。
[END MINIS]
```
