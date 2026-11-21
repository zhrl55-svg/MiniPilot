# tools/pchub — PC 端点

> 手机（MiniPilot）指挥电脑（这台 PC）干活，**电脑直接当端点**，中继退休。

## 为什么是 PC 当端点

中继（relay）当初存在的两个理由，在新的传输下都不成立了：

| relay 的职责 | 原因 | 现在 |
|---|---|---|
| NAT 穿透（两端都出站） | 谁都可能没有公网可达地址 | 第 3 层的 tailnet 解决了 |
| 设备目录（谁在线、能做什么） | 多设备路由 | 一台 PC 时，一张静态列表就够 |

所以 `dev_relay.py` 里真正不可替代的只剩约 120 行（鉴权、首帧期限、帧上限、
ping/pong、welcome 结构）——这些全部搬进 `link` 即可，剩下的都是路由。

**手机端不需要改一行代码**：帧协议完全一致。

## 传输层：已经建好了

MiniPilot 内嵌了 tsnet（Go/gomobile）+ nethub 本地入口，**不占手机 VPN 槽**：

| 组件 | 位置 |
|---|---|
| tsnet 内嵌节点 | `deps/rclone-mobile/tsnet`、`network/TailscaleNode.kt` |
| nethub SOCKS5 / HTTP 入口 | `network/NetworkHub.kt` |
| 四个独立路由 scope | `NetworkSettings.kt:79` — SHELL / BROWSER / MCP / BACKUP |
| MCP 专用代理 | `minis-mcp-cli/transport/http.py:41` — `MINIS_MCP_PROXY` |
| OkHttp 统一路由 | `network/NetworkHttp.kt:44` |
| 沙箱透明转发 | `network/ShellTransparentNet.kt` |

本机实测（2026-11-17）：

```
$ tailscale status
100.88.138.66   asus             windows
100.91.216.109  minis-android-1  android  idle, tx 73592 rx 83920   ← 手机内嵌节点

$ tailscale ping minis-android-1
pong from minis-android-1 (100.91.216.109) via 192.168.2.32:52069 in 7ms
```

---

## Ego Bridge（第 0 天已交付）

`ego_mcp.py` —— 一个 stdlib-only 的 MCP 服务器，把本机已装的 ego lite 包成
**浏览器工具**。手机通过 tailnet 调它，浏览器用的是你自己的登录态。

### 跑起来

```bash
cd tools/pchub
python ego_mcp.py                 # 127.0.0.1:8788，token 自动写进 token.txt
python ego_mcp.py --self-test     # 真实调一次 ego（open example.com）
python smoke.py                   # 完整 MCP 握手 + 三个工具调用 + 延迟
```

### 暴露到 tailnet（仍然只对 tailnet 可见）

```bash
tailscale serve --bg --http=8788 http://127.0.0.1:8788
tailscale serve status
```

`tailscale serve` 而不是绑 `0.0.0.0`：ego lite 只接受 loopback 连接（运行时里
明写 `Ego only accepts loopback connections`），bridge 保持 loopback，由
tailscaled 代为出网——**ego 一行不用改**。

### 手机端添加

Settings → MCP Integrations，新增：

| 字段 | 值 |
|---|---|
| URL | `http://asus.<tailnet>.ts.net:8788/mcp` |
| Header | `Authorization: Bearer <token.txt 里的 token>` |
| Transport | HTTP / SSE |

Settings → Network 把 **MCP** scope 的路由设成 `tailscale`（或 default = tailscale）。

> 也可以用命令行注册（与 UI 写的是同一份 servers.json，实测无需改 App）：
>
> ```bash
> minis-mcp-cli add --name pchub \
>   --url http://asus.saola-mintaka.ts.net:8788/mcp \
>   --header "Authorization: Bearer <token.txt 里的 token>" \
>   --note "PC endpoint tools/pchub"
> ```

### 手机端实测（2026-11-17，ELS-AN00 / 1.20-dev build 51）

| 步骤 | 结果 |
|---|---|
| 沙箱内 `curl /health` | HTTP 200，**86 ms** |
| `minis-mcp-cli tools pchub` | 11 个工具，1.8 s（含 daemon 冷启动） |
| `minis-mcp-cli call pchub browser_open` | 真实 a11y 快照，2.4 s |
| 连续 5 次 tools/list（经 tailscale serve） | 全 200 |

### 常驻

`run_bridge.cmd` + `run_bridge.vbs` 是隐藏窗口的重启循环（崩了 5 秒后重来，
发现 8788 已有实例就等待，不会抢端口）。

登录自启已配置在**用户启动文件夹**（无需管理员）：

```
%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\MinisPCHubBridge.vbs
```

（`schtasks /SC ONLOGON` 在非管理员下会报“拒绝访问”，所以用启动文件夹等价实现。）

`tailscale serve` 的配置由 tailscaled 持久化，重启后仍然有效。

### 工具集

| 工具 | 作用 |
|---|---|
| `browser_open` | 打开 URL，返回快照 |
| `browser_snapshot` | 读当前页面的无障碍快照（**给模型看的主要手段**） |
| `browser_click` / `browser_fill` / `browser_press` / `browser_select` | 交互 |
| `browser_eval` | 页内执行 JS |
| `browser_script` | **一次调用跑多步**，末尾给一份快照 |
| `browser_screenshot` | 存 PNG 并返回路径（给人看） |
| `browser_tabs` / `browser_finish` | 标签页管理 / 收工 |

快照给的是 `@N` ref 和 `loc=role:...` 选择器——和 ego 官方 agent 用的是同一套，
模型学一次就够。

### 已知实现细节

- ego 的内嵌 Node 运行时把 `console.log` 写到 **stderr**（0.6.3.17 实测），
  所以结果解析同时扫 stdout 和 stderr。
- 一个浏览器 space 跨调用保持（`.state.json` 记 `spaceId`），所以多步任务
  不必每次重开页面。space 没了会自动新建一次再重试。
- 调 ego 全程串行加锁：space 是有状态的。
- HTTP keep-alive 必须**先把请求体读干再返回**。401/404 分支如果直接返回，
  反代（`tailscale serve` 会复用后端连接）下一条请求会从上一个 body 里
  开始解析，表现为 `501 Unsupported method ('{...}POST')`。
- `tailscale serve` 按 **Host 头**路由：只能用 MagicDNS 名字
  （`http://asus.saola-mintaka.ts.net:8788`），用 `100.x.y.z:8788` 直连会
  得到 `404 page not found`。
- 本机自测时注意 shell 里的 `HTTP_PROXY`（Clash 等）会劫持 tailnet 域名并
  返回 400；`smoke.py` 已内置忽略环境代理。**websockets ≥15 的客户端默认读取
  `HTTPS_PROXY`**，会把 `wss://…ts.net` 送进代理并得到
  `SSLV3_ALERT_HANDSHAKE_FAILURE`——hub 的三个客户端（executor / fake_phone /
  test_link）都已显式 `proxy=None`。

---

## 路线图

| 阶段 | 内容 | 状态 |
|---|---|---|
| **P0** | Ego Bridge + `/mcp` + `tailscale serve` | ✅ `ego_mcp.py` |
| **P1** | `link.py`：把 relay 的服务端抽出来，executor 加 `--listen`，`--connect` 保留 | ✅ 2026-11-17 |
| **P2** | 手机 `HubClient` 走 `NetworkHttp`，地址用 MagicDNS；relay 退到配置第二位 | ✅ 2026-11-17 |
| **P3** | 跑稳后删 relay | 待做 |

### P1 已交付（`tools/hub/`）

`link.py` 落在 **`tools/hub/`** 而不是 `pchub/`——它和 executor、relay、
fake_phone 共用 `hubkit` 的帧工具，放在一起才不用反向引用。

```
tools/hub/
  link.py         ← Link 基类 + DirectLink（鉴权、hello/welcome、帧上限、
                    ping/pong、幂等、审批断线判 aborted）
  executor.py     ← 新增 --listen HOST:PORT；原有 --connect 不变
  dev_relay.py    ← 变成 Relay(Link)，只剩路由与事件转发
  test_link.py    ← 六条契约的回归测试（9 项，真实 WS 客户端）
  config.listen.json ← direct 模式配置（无 relay 字段）
```

跑起来（direct，目标形态）：

```bash
cd tools/hub
python test_link.py                                   # 9 项契约
python executor.py --config config.listen.json --listen 127.0.0.1:8790
tailscale serve --bg --https=8443 http://127.0.0.1:8790
python fake_phone.py \
  --relay wss://asus.saola-mintaka.ts.net:8443/v1/hub \
  --key bx7Wv1DJa0wxqknMPMn23sms5behlYOurFqkAVgdaF0 --target pc-ausa --action info
```

已实测：welcome `minis-pchub/0.1`、`devices=[(AUSA, pc-ausa, controllable)]`，
loopback 直连 24 ms，经 `tailscale serve` 的 wss 同样返回；审批卡往返
（`rm -f …` → APPROVAL → allow → 文件删除）在两种入口下都通过。

> 一个 key 绑定一个 device id（C1.3）。真机连着时手机那把 key 已被手机占用，
> 再跑 `fake_phone.py --key <同一把>` 会得到 `4002 bad_device`——这是契约，
> 不是故障；手工测试请换一个 key 的独立 executor 实例。

router（过渡形态，保留）：`python dev_relay.py --port 8787 --key 3rbszEjiUibMJh4g6au1ATnO85CYzg5Xk5xLEmZQaRE --key 9kL9VY4tMQee4jJnEj9snRghcO3uWb2VSTwOg5A37k0`。

### P2 已交付（手机直连 PC）

手机端只改了一处：`HubClient.defaultHttp` 不再自己拼代理，而是

```kotlin
NetworkHttp.following(NetworkScope.MCP.tag, base)   // hub.json 里显式 proxy 仍然优先
```

即走 **MCP scope** 的路由（该 scope 本来就为 pchub 桥接指向 Tailscale），
于是 MagicDNS 名字在 tsnet 内解析、`wss://` 经本地入口 CONNECT 隧道出去，
不占手机 VPN 槽。手机 `hub.json`（`/sdcard/Android/data/com.openminis.app.dev/files/hub.json`）：

```json
{
  "relay": [
    "wss://asus.saola-mintaka.ts.net:8443/v1/hub",
    "ws://100.95.180.77:8787/v1/hub"
  ],
  "key": "bx7Wv1DJa0wxqknMPMn23sms5behlYOurFqkAVgdaF0"
}
```

PC 端 `config.listen.json` 的 `api_key` 改用手机 hub.json 里已有的同一把 key（见上），PC 只监听
`127.0.0.1:8790`，由 `tailscale serve --https=8443` 对外——**tailnet 以外不可见**。

实测（2026-11-17 12:45，ELS-AN00）：手机启动后 40s 内连上 PC，
`phone attached: ELS-AN00 (phone-f7eca9c3)`；连续 `shell` 调用 + 审批卡往返
一次 3-4.5s，连接无重连。relay 保持在线作为第二地址，P3 再删。

常驻：`tools/hub/run_executor.cmd`（端口已占用就等，退出后 5 秒重启）+
`run_executor.vbs`（无窗口），启动文件夹里是 `MinisPCHubExecutor.vbs`。
`tailscale serve` 的 8443 规则由 tailscaled 持久化。

### 审批不再每一步一张卡

手机连续调 `shell` 时，原来每一步都弹一张审批卡——人会开始不读就点「允许」，
审批就失去意义了。现在 `policy.py` 有三层：

1. **只读命令直接放行**（`ls`、`cat`、`grep`、`tail`…；带 `>`、`|`、
   `&&`、`$(` 等的一律不算）。
2. **一次批准覆盖同类操作**：同一设备允许 `shell` 后，`approval_grant_s`
   （默认 600 秒）内同类调用不再询问，审批卡上会写「允许后 10 分钟内同类操作
   不再询问」。卡的粒度是 **设备 + 动作**，换了动作或换了设备照样问。
3. **销毁类命令永远每次问**：`rm`、`del`、`format`、`dd`、`shutdown` 等
   即使有 grant 也重新弹卡。grant 是省噪音的，不是扩权的。

`by_sender` 里显式信任的设备仍跳过一切（包括销毁检查）——那是「信任」的
含义。`approval_grant_s: 0` 可关掉 grant。回归测试：`python test_policy.py`
（33 项）。

### 审批模式（像 Claude Code 的权限模式）

运行时有三档，存在 `tools/hub/mode.txt`（与 journal 同目录），**热生效**，
不用重启 executor：

| 模式 | 行为 |
|---|---|
| `ask` | 每一步都问，只读命令也问；只有 `policy.actions` 里显式 `bypass` 的（info/files/file.get/notify）直接放行 |
| `smart`（默认） | 只读 shell 直放；允许一次管同类 10 分钟；销毁命令每次都问 |
| `auto` | 除销毁命令（rm/del/format/dd/shutdown…）外全部直放 |

切换方式：

```bash
python hub_mode.py            # 看当前模式
python hub_mode.py ask|smart|auto
```

或在手机上让 agent 调 `hub_call(action="mode.set", args={"mode":"auto"})`：
**这一调用永远弹审批卡**（即使有 grant、即使在 auto 模式），卡上写清目标
模式的含义。模型不能给自己放权；完全信任只写在 PC 配置的 `by_sender` 里，
是人类改配置的动作。`auto` 模式也不覆盖显式 `deny` 条目。
`info` 的返回里带 `mode` 和可选 `modes`，调试时一眼能看到现在是什么状态。

### P1 的形状（关键：不要揉成一个文件）

```
tools/hub/
  link.py       ← 服务端：鉴权、hello/welcome、帧上限、心跳、幂等
  executor.py   ← 策略、审批、调度（现有 _dispatch / policy / _ask 不动）
  actions/      ← shell / files / screen / browser
  pchub/mcp.py  ← /mcp：把 ego 包成工具
```

`link.py` 有两种模式，**同一份实现**：

- **direct**（目标形态）：executor 直接当 WS 服务端
- **router**（过渡形态）：现在的 `dev_relay.py`，N 条连接 + 路由

手机端不用改。P1 结束时 direct 和 router 同时在线（手机先连 PC 直连地址，
连不上再落到 relay），跑两周没毛病再删 relay。

### 从 relay 搬过来、不能丢的六件事

| # | 行为 | 依据 |
|---|---|---|
| 1 | 鉴权 + 失败关 `4001` | `HubClient.kt:227-232` 按 4001/4002 走 60s 退避 |
| 2 | 首帧 15s 内必须是 hello，否则 `4000` | C1.2 |
| 3 | 帧上限，超限丢弃而不是崩 | `HubClient.kt:188` |
| 4 | 坏帧回 `error bad_frame` 但不断开 | C2.1 |
| 5 | 重复 call id → `duplicate_call` | C7 幂等 |
| 6 | 应用层 ping → pong（手机每 15s 发，50s 静默就断） | `HubClient.kt:68-71` |

另外：**`welcome` 必须带 `devices` 数组**（哪怕只有自己），否则 `hub_call`
的 device 枚举是空的，模型直接没法调（`Hub.kt:242-247`）。

### 新冒出来、relay 替你挡过的四件事

1. **配对**：PC 当服务端后要有绑定流程（PC 显示二维码 → 手机扫一次）。
2. **威胁模型**：executor 会 `shell`、会写文件，现在开始监听。只绑 loopback +
   `tailscale serve` + tailnet ACL + token，四层收口。
3. **可达性转移到 PC**：PC 关机/睡眠 = 没有端点（中继也救不了）。需要禁休眠 +
   开机自启 + 崩溃重启；同局域网加 Wake-on-LAN。
4. **断线时的审批**：手机断开就立刻判 aborted，别挂一个没人能答的卡。

---

## 单一执行端的前提

现在手机侧是单例 `Hub`，一条连接。PC 当服务端后，「多执行端」从 relay 的路由
变成了 App 里要维护的连接列表。**先接受单 PC**；真要接第二台，`welcome.devices`
这个结构还在，加个列表不动协议。
