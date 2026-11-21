# tools/hub — PC 侧执行器

契约 ② 的电脑端：手机（Minis）发起 `call`，这里执行，结果回传。
协议是 nanoMuse Hub 0.31，见 [`docs/specs/hub-contract.md`](../../docs/specs/hub-contract.md)。

## 三个文件的分工

| 文件 | 角色 | 状态 |
|---|---|---|
| `executor.py` | **PC 执行器**（本次交付物） | 可用 |
| `dev_relay.py` | 本地中继，只为联调 | 开发用，单进程内存态 |
| `fake_phone.py` | 假手机端，只为验收 | 测试用 |

`executor.py` 不依赖另外两个：它只认 `wss://…/v1/hub` 和 `Bearer nm_…`，换成真中继不改一行代码。

## 跑起来

```sh
cd tools/hub
pip install -r requirements.txt

# 三个终端，或三个后台进程
python dev_relay.py --port 8787 --key 3rbszEjiUibMJh4g6au1ATnO85CYzg5Xk5xLEmZQaRE --key 9kL9VY4tMQee4jJnEj9snRghcO3uWb2VSTwOg5A37k0
python executor.py --config config.dev.json
python fake_phone.py --target pc-ausa --action info
```

`fake_phone.py` 的 `--target` 可以写设备 id（`pc-ausa`）也可以写设备名（`AUSA`）。

## 开机自启（H-006，2026-11-18）

**规则（强制）**：Startup 里只放 **`.lnk` 快捷方式**（或把绝对路径写死在脚本里），
**禁止**把依赖同目录文件的启动 vbs **复制**进 Startup。
`run_executor.vbs` / `run_bridge.vbs` 用 `GetParentFolderName(WScript.ScriptFullName)`
找同目录的 `.cmd`，复制到 Startup 后它只在 Startup 里找，**必然找不到**：
executor 从未启动 → `127.0.0.1:8790` 无监听 → `tailscale serve` 8443 没有后端 →
手机侧 hub 全断，且极容易被误判成「Tailscale/网络问题」。（2026-11-18 H-006 实测复现。）

**安装**（新机器 / 仓库挪位置后重跑一次即可，幂等）：

```bat
tools\hub\install_startup.cmd
rem 或：cscript //nologo tools\hub\install_startup.vbs
```

脚本做三件事：

1. 把 Startup 里旧的 `MinisPCHubExecutor.vbs*` / `MinisPCHubBridge.vbs*`
   **移出** Startup，归档到 `tools\hub\backup\startup-legacy\`（连同历史
   `*.bak.*` / `*.removed.*` 残留一并清扫；只动这两个本项目前缀，其它启动项不碰）。
   不能像早期版本那样原地改名成 `*.removed.<时间戳>`：Startup 会对每个文件执行
   ShellExecute，未知扩展名（如 `.vbs.bak.20261118`）会在每次登录时弹
   「选择程序打开」。2026-11-18 重启实测复现；
2. 创建两个 `.lnk`：`MinisPCHubExecutor.lnk` → `tools\hub\run_executor.vbs`、
   `MinisPCHubBridge.lnk` → `tools\pchub\run_bridge.vbs`。
   快捷方式指向**仓库里的源脚本**，所以脚本自己的同目录查找继续有效；
3. 自检 `127.0.0.1:8790`：未监听就立即拉起一次执行器，仍未监听则把一条明确错误
   追加到 `tools\hub\executor.log`（无控制台窗口也能事后排障）。

**验收**：执行安装脚本 → 重启 PC → 无任何弹窗（Startup 里与 hub 相关的只应有
`MinisPCHubExecutor.lnk` / `MinisPCHubBridge.lnk`，打开“选择程序”弹窗即说明还有
未知扩展名的残留文件），`netstat -ano | findstr 127.0.0.1:8790` 显示 LISTENING，
`tailscale serve status` 仍指向 8790，手机端 `hub_call` 往返正常。

## 配置

`config.dev.json` 逐项说明：

| 键 | 作用 |
|---|---|
| `relay` / `api_key` | 中继地址与凭据。一把 key 只能认领一个 `device.id`，多设备要多把 key |
| `device.id` | 必须持久化，`kind` 恒为 `computer`。改 id = 换设备身份 |
| `device.root` | **文件与命令的工作根**。所有 `files` / `file.*` / `shell.cwd` 都被限制在这个目录内 |
| `device.roots` | 额外的允许目录（如 `["D:/minis-src"]`）。命中**任一**根即可；不配就仍然只有 `device.root`。线上已开：`config.listen.json` 里是 `["D:/minis-src"]` |
| `device.remote_control` | 本机总开关。设为 `false` 时 `hello` 声明空 `actions`，中继会直接拒投递（`not_controllable`） |
| `actions[]` | 对外声明的能力。**声明了就必须实现**，否则等于给自己挖一个必超时的洞 |
| `policy.default` | 默认档位：`bypass` / `ask` / `deny` |
| `policy.by_sender` | 按**发起方 device id** 放行，例如 `{"phone-xxxx": "bypass"}` |
| `policy.allow_read_only_shell` | 只读命令免审批（白名单见 `policy.py`） |
| `limits.approval_timeout_s` | 审批卡等人多久，超时按拒绝处理。**线上是 600s**：手机端的审批会弹系统横幅（user-ask 通知），横幅需要人看见的时间，120 秒基本来不及点 |
| `limits.max_concurrent_calls` | 同时在跑的 call 上限（线上 4）。**等待审批的 call 也占坑** —— 无人应答时一条待批就占 600 秒，批量审批时注意别把通道占满 |
| `shell.program` / `shell.args` | 留空则 Windows 上用 Git Bash，其次 `cmd /c`；类 Unix 用 `/bin/sh -lc` |
| `shell.notify_command` | `notify` 动作的落地命令，会被拼上 `title` 和 `body` 两个参数。`config.listen.json` 里指向 `notify.py`（Windows Toast/气泡，不阻塞执行器） |
| `journal` | 每个 call 的审计记录（谁发起、什么动作、结果、耗时） |

`file.get` 的返回体里 `size` 是**整个文件**的大小，`data` 才是这次取的片段
（`offset`/`length` 生效）。别把 `size` 当成片段长度。

## 已实测通过

在本机（Windows，AUSA）跑通的场景，均带真实耗时：

| 场景 | 结果 |
|---|---|
| 只读 shell `ls -la` | 免审批直通，**43ms** 返回 stdout |
| 写 shell `rm -rf trash` | 弹审批卡 `risk=high` → 允许 → 执行，目录确实被删 |
| 同上但拒绝 | 返回 `not_allowed: denied by a person`，目标文件未被触碰 |
| 路径越界 `files {"path":"../../.."}` | `not_allowed: path is outside this executor's root` |
| `file.get` / `file.put` | 正常；`file.put` 走审批，落盘后回 `sha256` |
| `screen` | 审批后截屏，降采样到 **1600×900 / JPEG q80**，约 190KB，远低于帧上限 |
| 本机关闭远程控制 | 中继回 **`not_controllable`**，call 根本到不了执行器 |
| 中继重启 | 执行器按 1s→2s 退避自动重连，无需人工介入 |

审计样例（`journal.jsonl`）：

```json
{"call":"phone-fake:1:f552","sender":"phone-fake","action":"shell","status":"ok","exit_code":0,"duration_ms":35,"note":"shell is read-only (read-only)"}
```

## 路径基准

**配置里的相对路径一律以「配置文件所在目录」为基准，与进程 cwd 无关。**

`device.root`、`journal` 以及 `file_max` 约束下的一切路径都相对配置文件目录展开；
绝对路径原样使用。执行器的日志文件也放在同一个目录（启动器把 stdout 重定向到
配置文件旁边），于是**日志 / journal / 文件根三者同处一地**，不会再出现
"本该写在这里却写到了那里"。

例：配置 `D:\hubtmp\config.ts.json` 且 `device.root="hubroot"`、`journal="journal.jsonl"`
→ 根目录 `D:\hubtmp\hubroot`、审计日志 `D:\hubtmp\journal.jsonl`、
执行器日志 `D:\hubtmp\executor.log`，无论从哪个 cwd 启动。

## 与契约的偏差（都是有意为之）

| 契约条款 | 本实现 | 原因 |
|---|---|---|
| C4.3 账号级 push（`event{to}`） | **未实现**，只做挂 call 的 `event{id}` | ② 场景下每次审批都有 call 顶着；push 留给「PC 主动发起」的后续版本 |
| C6.1 `stop{call}` | 返回 `{stopped:false}`，绝不强杀已在跑的命令 | 强杀一个写了一半的 `rm` 比让它跑完更危险 |
| C7.3 断线重放 | 执行器内存里去重（`_done` 保留 500 条） | 中继重启后能挡住「重连又重发」，跨进程重启挡不住 |
| C3.2 `open` | 未实现 | 它会拉起 GUI，语义依赖桌面环境，先放着 |
| `notify` | 需要 `shell.notify_command`，否则回 `{delivered:false}` | Windows 侧已适配（`notify.py` + `notify_popup.ps1`），其他平台仍需自己配 `notify_command` |

## 已知限制

- `dev_relay.py` 是**内存单进程**：没有持久化、没有 TLS、重启丢 in-flight。生产要么跑在 Tailscale 上（地址本身就是信任边界），要么用 nanoMuse 的 `cloud/nanomuse_cloud/hub.py`。
- `fake_phone.py` 的 call id 序号不持久化（`phone-fake:1:…`），真客户端必须按 C7.1 落盘单调递增。
- `screen` 依赖 Pillow + 可用的桌面会话；无头环境下会回 `not_allowed`。
- 只读命令白名单刻意保守：`awk` / `sed` / `find` / `git` / `python` 一律不算只读（它们都有写入或执行路径）。宁多弹一次卡，不放行一次写。

## 线上跑的是哪一份（2026-11-17）

手机上的 `hub_call` 打到的是 **`deploy/hub_executor.py`**（单文件版，跑在
`C:\Users\zhrl5\hub\hub_executor.py`，计划任务 `MinisHubExec` 以 `pythonw` 启动，
连 `ws://192.168.2.102:8787/v1/hub`）。它和本目录的 `executor.py` 是两份实现：

| | `executor.py`（契约实现） | `deploy/hub_executor.py`（线上单文件版） |
|---|---|---|
| 审批 | `policy.Gate` + 审批卡 | 无（手机侧直接执行） |
| 配置 | `config.listen.json`（嵌套 `device.root`） | `hub_device.json`（扁平 `root`） |
| 路径 | 只允许 `device.root` + `device.roots[]` | 同上（`roots[]`） |
| 审计 | `journal` 逐条记录 | 只有 `hub.log` |

改线上那份要同步改 `deploy/` 里的副本，否则下次部署会把它覆盖回去。

## 新增能力（2026-11-17 批次）

- **`shell` 的 `detach`**：`{"command": "...", "detach": true}` 立刻返回 `{"pid": …, "detached": true}`。
  前台跑常驻服务时子进程会继承管道，`communicate()` 等一个永远不来的 EOF，调用必然超时——
  这就是「服务起来了但工具说超时」的原因。detached 子进程没有管道，不等它。
- **`device.roots`**：除 `device.root` 之外再开几个允许落盘的目录（`"roots": ["D:/minis-src"]`）。
  没配就仍然只有 `device.root` 一个根，越界路径回 `not_allowed: path is outside this executor's root`。
- **`info` 回显 `shell_program`**：Windows 上探测 Git Bash（`%LOCALAPPDATA%\hermes\git\…`、
  `%ProgramFiles%\Git\…` …）。**不信 `where bash`**：它先命中 WindowsApps 的 WSL 别名，
  那个 shell 的引号和路径规则跟我们要的不是一回事。
- **`cmd_repr` 审计**：每条 `shell` call 的 journal 里多一个 `cmd_repr` 字段（原命令的 repr），
  手机侧 `Hub.call` / `HubClient.send` 各记一条同样的 repr —— 引号在哪一跳被吃掉，两边一对就知道。

## 下一步

1. 换真中继：把 `relay` 指向 Tailscale 上的实例，`api_key` 换成 `nm_…`，`executor.py` 不动。
2. Minis 侧：`HubClient` + `Hub` + `HubService`，把 `call` 包成工具的 `hub_call`（设备名动态列进工具描述，结果回灌必须带来源标记）。
3. 补 `event{to}` push，才能覆盖「电脑自己发起的方向」。
