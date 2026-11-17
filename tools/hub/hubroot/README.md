# ego lite 常驻服务（PC 侧）

手机要一次浏览器任务（打开网页、取标题/快照、填表），PC 这边用
`ego_srv.py` 包一层 HTTP：手机 POST 一次 `/q`，PC 跑一次 ego 拿结果。

一次任务 4–7 秒——因为 ego 的 node 运行时不能常驻（实测 listen 之后本机
连不上），每个请求都要重新 spawn 一个 `ego-browser.exe` 进程。**常驻的是
这层包装，不是浏览器本身。**

## 跑起来

```cmd
call D:\minis-src\tools\hub\hubroot\ego_boot.cmd
```

装两个计划任务并把服务拉起来：

| 任务 | 触发 | 作用 |
|---|---|---|
| `MinisEgoSrv` | 登录时 | 常驻服务（`pythonw`，无窗口） |
| `MinisEgoWatch` | 每分钟 | 看门狗：端口没监听就拉起来（`pythonw`，无窗口） |

其它：`ego_srv_start.cmd` 启动、`ego_srv_restart.cmd` 重启、
`ego_clean.cmd` 清临时脚本。

> 看门狗为什么是 `.py` 而不是 `.cmd`：计划任务在交互式会话里跑 `.cmd`，
> 每分钟会闪一个控制台窗口在桌面上。`pythonw` 是 GUI 子系统，根本没有
> 控制台 —— 这是「不闪窗口」的正解，不是藏技巧。

## 手机怎么调

服务**默认只监听 127.0.0.1**，手机侧走 adb 反向隧道：

```cmd
C:\Users\zhrl5\platform-tools\adb.exe -s 192.168.2.32:5555 reverse tcp:8799 tcp:8799
```

然后在手机上：

```sh
curl -s -X POST http://127.0.0.1:8799/q \
  -H "Content-Type: application/json" \
  -H "X-Minis-Token: <token>" \
  -d '{"url":"https://example.com","js":"const o={title:await p.title()};console.log(JSON.stringify(o));"}'
```

token 在 `ego_srv.json`（**不入库**）里，首次启动自动生成；也可以用环境变量
`EGO_TOKEN` 覆盖。adb 断连后 reverse 规则失效，重连一次即可。

## 接口

| 请求 | 返回 |
|---|---|
| `GET /health` | `{"ok":true,"ego":"<exe>","version":"0.6.3.17","auth":"token","parallel":2}` |
| `POST /q` | `{"exit":0,"ms":4123,"out":"..."}` |
| `POST /q`（无/错 token） | `401 {"err":"unauthorized"}` |
| `POST /q`（并发满） | `429 {"err":"busy"}` |

`/q` 的请求体：`{"url": "", "js": "", "wait_ms": 0, "timeout": 120, "raw": false}`。
`js` 留空用默认的「取标题 + 快照」；`raw: true` 时 `js` 整段当模块执行。

## 为什么这样收着（2026-11-17 硬化）

之前这服务监听 `0.0.0.0:8799`、无鉴权、`js` 原样执行：局域网和 tailnet 上的
任何设备都能在你这台 PC 上跑任意 JS，还能操作带你登录态的 ego 浏览器。现在：

- 必须带 token（401），token 在本地文件/环境变量里，不入库；
- 只监听回环，别的机器连不上（真要 LAN 直连就用防火墙只放行手机 IP）；
- 并发上限 2，超了 429 —— 每个请求都拉一个 Chromium 进程，不设闸会堆满机器；
- 异常统一回 JSON，不 reset 连接；
- ego 路径不再写死版本号：配置 > 已安装版本里最新的那个 > 明确报错。

## 出问题先看哪儿

- `ego_srv.log`：每个请求一行（exit / ms / url），服务起不来看 `ego_srv.err`。
- 服务没起来：`netstat -ano | findstr 8799` 看端口；`schtasks /Query /TN MinisEgoWatch` 看看门狗。
- 手机连不上：多半是 adb reverse 掉了，重跑一次 `adb reverse`。
