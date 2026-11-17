# Minis 待修需求（2026-11-17 批次）

- 执行方：PC(ASUS-PC) 上的 Claude Code
- 仓库：`D:\minis-src`，设备：HUAWEI ELS-AN00 / EMUI / Android 12（`192.168.2.32:5555`）
- 本文档 = 两批合并：**F 系列**（端侧路由，来自 `changelog/ISSUES.md`）+ **H 系列**（hub 通信层 + ego 服务，2026-11-17 晚实测定位）
- **20:30 复核修订**：F-001 改为双修（①+②）；H-003 锁定 Git Bash（排除 WSL bash）；H-004 改为三段 repr 定位、不再预设编码层有 bug；**新增 H-006**（ego_srv 安全与运维硬化）。
- 每条都有可复现证据与验收标准。**只做本文列出的项**，范围外的重构只出报告不动手。

---

## 0. 前置约定（违反即返工）

1. **先 `git status` 再动手**。工作树有未提交改动，`git checkout -- .` / `git clean` 会丢代码。
2. 构建链：JDK 17 + 仓库自带 wrapper(8.11.1)，任务 `assembleGithubPerf`。**不要**用 JDK 21 或外部 gradle。
3. 流程固定：**先提交 → 再编译 → 报 APK 绝对路径 / sha256 / 字节数 / apksigner 证书 SHA-256 → 再装机**。
4. 装机后必须校验：包名 `com.openminis.app.dev`、versionCode、lastUpdateTime。
5. 单测任务 `testGithubPerfUnitTest`，报告「用例数 / 失败数 / 失败类清单」，并与上一版基线对比。
6. 完成后按交棒协议回报到会话 `142217d5-6840-44be-a72c-4d3ae4a23dfb`（本会话），不要只在终端打印。
7. 任何一条做不完或受阻，立刻写回执说明卡在哪一步，**不要自行跳过**。

---

## F 系列（端侧路由，来自 changelog/ISSUES.md）

### F-001 `tool.file-write` 路由被"提到写入"误触发 —— 已报

- **复现**：普通对话里说一句含「写入」但不写文件的句子（例如交接类长文）。
- **证据**（设备端 `assistant_fastpath.log`）：
  ```
  [19:10:05] route: enabled=true text=「[FROM: CLAUDE]…写入经 ConfigBridge 确认卡…」 decision=local file_write/write score=50
  [19:10:05] skip: headless-needs-confirmation file_write/write
  ```
  源码 `src/android/app/src/main/java/com/openminis/app/assistant/routing/DefaultAssistantRoutes.kt:70-72`：
  entry `tool.file-write`，keywords = `写入 / 写文件 / 保存到 / 存到 / 新建文件 / write file / save to`。
- **影响**：headless 下只是 skip；**前台普通对话会弹「写文件确认卡」**；路径槽被误抽时存在写错文件风险。
- **建议修法（已定：① 和 ② 都做）**：
  ① **强制 `path` 槽缺失即回退云端**（与 `AmapPlaceQuery.isPlaceKeyword()` 同一套路）；
  ② 给该 entry 加 `exclude`（`写入经 / 配置 / 代码 / 日志 / 文档` 等语境词）；
  注意 exclude 只裁语境、不裁真命令：`写入 /tmp/a.txt`、`把 hello 写入文件` 这类必须仍能命中本地。
- **验收**：说含「写入」的普通句子 → 日志出现 `decision=cloud reason=no-args`，不弹卡、不产生文件。补单测（可放 `AssistantRouteCorpusTest`）。

### F-002 `[FROM: …]` 内部消息被端侧路由打分 —— 已报

- **复现**：把带 `[FROM: X] … [END X]` 前缀的交接/回执消息投进聊天。
- **证据**：与 F-001 同一条 19:10:05 消息，同分钟还命中过 amap 路由（`decision=local amap_search/search`）。
- **影响**：内部内容被当用户指令执行（可能触发工具调用/弹卡），也是 F-001 的触发源之一。
- **建议修法**：路由匹配入口做前缀识别（`[FROM:` / `[END `），命中则整体跳过端侧路由。
- **验收**：投一条 `[FROM: X] 写入测试` → 日志中**不出现任何 `decision=local`**。

### F-003 仓库工作树遗留临时文件 —— 待修

- **证据**（`git status --porcelain`，2026-11-17 19:0x）：
  ```
  ?? .tmp/
  ?? .workbuddy-ai/
  ?? tools/hub/.tmp_off.txt
  ?? tools/hub/.tmp_off2.txt
  ?? tools/hub/.tmp_off3.txt
  ```
- **建议修法**：确认归属后删除，并把 `.tmp/`、`.workbuddy-ai/`、`tools/hub/.tmp*` 收进 `.gitignore`。
- **验收**：`git status --porcelain` 除约定的未提交改动外干净。

---

## H 系列（hub 通信层 + ego 服务，2026-11-17 晚源码级定位）

> 背景：手机 ↔ PC 的 hub 通道目前只能"绕"不能"用"——带引号的命令必失败、后台服务必然挂住、写文件只能写 hubroot。
> 这次全部走 ssh 才把 ego 服务部署起来，正是这些缺陷的直接后果。

### H-001 `_do_shell` 等 pipe EOF，后台进程导致命令永不返回

- **位置**：`tools/hub/executor.py:466-520`（`_do_shell`），关键行
  ```python
  proc = await asyncio.create_subprocess_exec(*argv, cwd=cwd,
      stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE, creationflags=flags)
  out, err = await asyncio.wait_for(proc.communicate(), timeout=timeout_s)
  ```
- **复现**：`hub_call(shell, {"command":"start \"\" /b python D:\\...\\ego_srv.py"})`。
  实测：工具返回 `Tool execution was interrupted`（撞超时），但**服务确实已启动**——随后 `curl 127.0.0.1:8799/health` 返回 `{"ok":true}`。两次复现同样现象。
- **原因**：`start /b` 的子进程继承了 stdout/stderr 管道，`communicate()` 等 EOF，只要子进程活着就永远等不到。
- **影响**：无法通过 hub 启动任何常驻服务（服务、守护、长任务都必须绕 ssh/schtasks）。
- **建议修法**：`shell` 增加可选 `args.detach`；为真时用
  `stdout=stderr=subprocess.DEVNULL` + `creationflags = CREATE_NEW_PROCESS_GROUP | DETACHED_PROCESS`，
  不 `communicate()`，立即返回 `{"pid": <pid>, "detached": true}`。
- **验收**：`detach` 调用 **300ms 内**返回并带 pid；2 秒后目标端口/健康检查可访问；不影响普通调用的输出采集。

### H-002 `file.put` / `file.get` 被锁死在 `device.root`

- **位置**：`executor.py:437-441` `_path()` → `policy.path_is_inside(self.cfg.root, candidate)`；
  `config.listen.json` 里 `"device": {"root": "hubroot"}`。
- **复现**：`file.put` 到 `D:/minis-src/.claude/bridge/x.jsonl` 或桌面路径 → `not_allowed: path is outside this executor's root`。
- **影响**：写信箱、写桌面、投需求文档全部走不通，只能绕 ssh/scp。
- **建议修法**：`device` 支持 `roots: []` 白名单（保留 `root` 作为默认/兼容），`_path` 改为"命中任一 root 即可"；
  或退一步，给 `file.put`/`file.get` 单独配 `allow_paths`。默认配置保持现状，不扩大默认攻击面。
- **验收**：配置里加入 `D:/minis-src` 后，写入 `D:/minis-src/.claude/bridge/x.jsonl` 成功；
  写 `C:/Windows/x.txt` 仍被拒并给出明确原因。

### H-003 `shell.program` 为空 → 全部回落 cmd.exe

- **位置**：`executor.py:73-86` `_shell_argv()` 只在 Windows 上探测
  `C:\Program Files\Git\bin\bash.exe` 与 `C:\Program Files\Git\usr\bin\bash.exe`，都没有则 `["cmd.exe","/c",command]`。
- **现状**：本机 Git Bash 不在上面两条路径（用户在 `%USERPROFILE%\hermes\git` 一带），所以**所有命令按 cmd 解析**：
  `&` `|` `%VAR%` 都会被解释，`%xx` 形式的 URL 编码被当年份变量展开，引号规则也与 bash 不同。
- **建议修法**：① 探测顺序改为：`config.shell.program`（最高）→ 标准 Git 路径 →
  `%LOCALAPPDATA%\Programs\Git` → `%USERPROFILE%\hermes\git`；**不要直接信 `where bash`**——
  它可能先命中 WSL 的 `C:\Windows\System32\bash.exe`，路径语义完全不同。只有确认是 Git Bash 才采用；
  ② 在 `info` 动作里回显"当前实际使用的 shell"（已解析的全路径）便于排障。
- **验收**：`hub_call(action=info)` 返回里有 `shell_program` 字段且值正确；`echo "a b" | tr a-z A-Z` 返回 `A B`。

### H-004 引号在传输中退化成字面 `\"`（最高优先级，是 H-001..H-003 之外最痛的一条）

- **复现**：`hub_call(shell, {"command":"echo \"a b\""})` → PC 实际执行后回显 `\"a b\"`（反斜杠被当成命令内容）。
  同类：`cmd /c dir "C:\Program Files\..."` → `文件名、目录名或卷标语法不正确`；`^"` 转义同样无效。
- **已排除**：executor 侧 `_on_frame` 只做 `json.loads`，`_decode()` 也正常；cmd 本身不会给引号加反斜杠。
  故嫌疑集中在**手机端发送前的编码**（`HubCallTool.actionArgs` / `HubClient` 组帧）或 **wire 层字符串二次转义**。
  `HubCallTool.actionArgs`（`src/android/app/src/main/java/com/openminis/app/hub/HubCallTool.kt:167-176`）只做 `JSONObject` 解析，未见额外转义，需沿 `Hub.call → HubClient.send` 往下查。
- **影响**：所有含引号的命令 100% 失败，只能改用 base64 上传脚本绕行，是最影响可用性的缺陷。
- **归因修正（2026-11-17 20:30 复核）**：两端源码链路（`HubCallTool.actionArgs → HubFrames.call →
  JSONObject.toString() → okhttp 文本帧 → PC json.loads`）是**标准 JSON 往返**，常规不会产生字面 `\"`。
  首要嫌疑改为 **模型生成 tool args 时已过度转义**（`\\\"` 解一层后剩 `\"`），其次是 H-003 的 cmd.exe
  放大症状。**先三段定位、后按跳修正**，不要预设编码层有 bug。
- **建议修法**：
  ① 手机侧：`Hub.call` 收到 `args` 后、`HubClient.send` 组帧前，各打一条 `command` 的 repr 日志；
  ② PC 侧：`_do_shell` 入口把 `command` 的 repr 写进 journal（新增 `cmd_repr` 字段）；
  ③ 发一条 `{"command":"echo \"a b\""}`，比对三段 repr，**哪一跳第一次出现反斜杠就在哪一层修**；
  ④ 若定位在模型层（tool args 过度转义），在 `HubCallTool.actionArgs` 入口做保守归一
  （仅当 `command` 含 `\"` 且能在一次解码后消除时才改），并补单测锁死。
- **验收**：`hub_call(shell, {"command":"echo \"a b\""})` 返回 `a b`（不是 `\"a b\"`）；
  手机侧两条 repr + journal `cmd_repr` 三条记录都存在且能定位到首个出错跳；
  另跑一条带引号路径的 cmd 用例（如 `dir "C:\Program Files"`）不回归。

### H-005 无持久 shell 会话（可选，优先级最低）

- **现状**：每条命令一个独立进程，`cd`、`set`、`export` 都不保留；hub 无法像手机端 a11y 那样"连续操作"。
- **建议修法**：可选地维持一个常驻 `bash --noprofile --norc`（或 `cmd /k`），按 call_id 串行投递，超时丢弃；
  或明确不做，改为在文档里写清"hub 是无状态执行器"。
- **验收**：连续两次 `cd D:\minis-src` 后 `cd` 仍在该目录（若实现）。

### H-006 `ego_srv.py` 安全与运维硬化（复核新增，本批必做）

> **现状（2026-11-17 20:20 实测确认）**：`0.0.0.0:8799` LISTENING（pythonw PID 32056），`/q` 无鉴权、
> `js` 原样拼进脚本执行；PC 在 5 节点 tailnet 上（asus/buntu/minipilot…）。等于局域网/尾网内**任意设备**
> 可在带登录态的 ego 浏览器里执行任意 JS，且完全绕过 hub 的审批、journal 与 ACL。
> **约束：不改 `/q` 的请求体/响应体形状与 4–7 秒行为指标，只加门禁与运维能力**（新增必填请求头除外）。

- **位置**：`tools/hub/hubroot/ego_srv.py`、`ego_boot.cmd`、`ego_srv_start.cmd`、`ego_srv_restart.cmd`
  （目前全部 untracked，本项要求一并入库）。
- **修法**：
  ① **鉴权**：请求头带共享 token（如 `X-Minis-Token`），值从本地配置文件或环境变量读取，**不写进仓库**；
  缺失/错误 → `401 {"err":"unauthorized"}`。手机侧调用 curl 一并加头。
  ② **收窄监听（二选一，默认前者）**：
  - 默认：服务只监听 `127.0.0.1`，手机侧走 `adb reverse tcp:8799 tcp:8799` 访问 `127.0.0.1:8799`
  （与 DebugServer 5321 同一套路）。adb 断连后 reverse 规则失效，README 写清重执行步骤；
  - 备选：保留 LAN 监听，但 Windows 防火墙只放行手机 IP（`192.168.2.32`），并检查/移除
  pythonw 的宽放行规则（默认拒绝策略下，单独一条限定 RemoteAddress 的入站规则即可）。
  ③ **并发上限**：`threading.Semaphore(2)`，超限立即回 `429 {"err":"busy"}`，不排队、不堆 Chromium 进程。
  ④ **异常统一**：`run_ego` 与 handler 全程 try/except，统一回 `{"err":"...","ms":n}` JSON；
  ego 路径不存在时 `/health` 与 POST 都给明确 JSON，不 reset 连接。
  ⑤ **保活与日志**：计划任务改 `onlogon` 触发 + 周期重复看门狗（或 NSSM）；
  每次请求一行日志进 `ego_srv.log`（时间、exit、耗时、并发占用）；进程崩溃 traceback 追加 `ego_srv.err`。
  ⑥ **版本解耦**：ego 路径不再硬编码 `0.6.3.17`，配置 > 已安装版本目录最新 > 明确报错；`/health` 回显解析到的路径与版本。
  ⑦ **入库**：`ego_*.py` / `ego_*.cmd` 提交进 `tools/hub/`；token 只留在本地忽略文件；
  `tools/hub/README.md` 补运行手册（启动、重启、排查、手机侧调用示例）。
- **验收**：
  - 无 token / 错 token → 401；正确 token + 正常任务 → 原 `/q` 响应形状不变，耗时仍 4–7 秒量级；
  - 从 tailnet 其他节点（如 buntu）连 8799 → 拒绝/超时；手机（经 adb reverse）→ 正常；
  - 并发 3 条 → 2 条执行、1 条 429；
  - `taskkill` 杀掉 pythonw → ≤ 2 分钟自愈，`/health` 恢复 `{"ok":true}`；
  - 把 ego 版本目录改名模拟升级 → `/health` 与 POST 回清晰 JSON 错误，不 reset；
  - `ego_srv.log` 有逐请求日志；`git status` 中 ego 源码不再 untracked。
- **后续（不属于本批，不要做）**：H 系列修好后，把 ego 收敛为 hub 的 `ego` action
  （`executor.py` 代理到 `127.0.0.1:8799`），手机不再直连端口，审批/journal 自动适用。

---

## 已具备、勿重复实现（2026-11-17 完成）

- **ego lite 常驻服务已建成**：PC 上 `D:\minis-src\tools\hub\hubroot\ego_srv.py`（Python 标准库 HTTP，`0.0.0.0:8799`），
  由计划任务 `MinisEgoSrv` 以 `pythonw` 启动；手机一次 `curl -X POST /q` 拿结果，4–7 秒；
  内置 `finally p.close()` 用完即关标签，spawn 已带 `CREATE_NO_WINDOW`（不再闪黑框）。
  **功能不需要重做**；安全与运维硬化按 **H-006** 执行，`/q` 响应形状与 4–7 秒行为指标保持不变。
- **ego 的 node 运行时不可常驻**（实测）：它是 Chromium 的 `ego.mojom.NodeService` 沙箱子进程，
  `listen(0.0.0.0:8801)` 后 netstat 显示 LISTENING，但手机连超时、PC 本机 127.0.0.1 直接 refused。
  别再去尝试"把 JS 运行时做成服务"。

---

## 回报模板

```
[FROM: CLAUDE]
轮次：2026-11-17 批次（F-001/F-002/F-003 + H-001..H-004 + H-006）
逐条状态：
- F-001 已修 / 卡在哪 / 验收结果（贴 fastpath 日志原文）
- F-002 …
- F-003 …
- H-001 …（detach 实测耗时、返回体）
- H-002 …（成功写入路径 + 拒绝路径各一条）
- H-003 …（info 回的 shell_program、`tr` 用例结果）
- H-004 …（三段 repr 对比：手机 args / 手机帧 / journal cmd_repr；`echo "a b"` 实际回显）
- H-006 …（401/429 各一条、tailnet 节点拒绝一条、自愈耗时、/health 回显、日志与 git 状态）
单测：用例数 / 失败数 / 失败类清单 / 与基线对比
产物：APK 绝对路径、sha256、字节数、apksigner 证书 SHA-256
装机：versionCode / lastUpdateTime
未完成项及原因：
[END CLAUDE]
```
