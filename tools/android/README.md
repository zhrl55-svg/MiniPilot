# tools/android — 设备端 Android 调试工具

这里放的是**跑在设备上**、需要 adb 通道的辅助脚本。每个工具一个目录，
自带安装脚本和注意事项。

## vd.sh — 虚拟屏（VirtualDisplay）控制

在手机上用 `app_process` 起一个 shell uid 的虚拟显示，用来在**第二块屏**上
启动 App、截图、点击，做 UI 自动化验证，不干扰正在用的主屏。

### 安装

```bash
tools/android/install-vd.sh          # 推到 /data/local/tmp/vd/
```

设备上得到：

| 文件 | 作用 |
|---|---|
| `vd.sh` | 控制脚本（建屏/销毁/截图/点击/启动 App） |
| `classes.dex` | 建屏用的 helper：通过 `DisplayManager.createVirtualDisplay` 建 VIRTUAL display |
| `vd_watchdog.sh` | `vd.sh start` 时自动生成的空闲回收看门狗 |

### 用法

```bash
adb shell /data/local/tmp/vd/vd.sh start 720 1600 320   # 建屏，打印 displayId
adb shell /data/local/tmp/vd/vd.sh launch com.x/.MainActivity
adb shell /data/local/tmp/vd/vd.sh shot /data/local/tmp/vd/x.png
adb shell /data/local/tmp/vd/vd.sh do "tap 100 200" "sleep 0.5" "key 4"
adb shell /data/local/tmp/vd/vd.sh status
adb shell /data/local/tmp/vd/vd.sh stop                   # 销毁
```

### ⚠️ 用完必须 stop

建屏进程是 **shell uid 的 `app_process`**，并持有一个 `owner=com.android.shell`
的 VIRTUAL display。银行/支付类 App（实测建设银行 9.8.0）会把它识别成
「录屏/屏幕共享软件 Shell」并**直接拒绝启动**，提示：

> 系统检测到您正在录屏/屏幕共享……请退出录屏或共享软件"Shell"后再使用手机银行服务

所以：

- 用完立刻 `vd.sh stop`；
- 弹窗已经出现时，进程里缓存了检测结果，需要
  `adb shell am force-stop com.chinamworld.main` 后重开才会消失；
- 为了防止会话意外结束留下残余进程，`vd.sh` 自带**空闲看门狗**：
  超过 `VD_IDLE_SECONDS`（默认 900 秒）没有任何操作就自动 kill 掉建屏进程。
  测试用可以调小：`VD_IDLE_SECONDS=45 adb shell .../vd.sh start`；
  设成 `0` 关闭自动回收（此时必须自己记得 stop）。

2026-11-18 的事故：11-15 留下的建屏进程没被 stop，建行连续两天拒绝启动，
排查后 `vd.sh stop` + `force-stop` 建行即恢复。

### 限制

- 需要 USB 调试（adb shell）通道；
- 依赖 `/data/local/tmp/vd/classes.dex`，本仓库只保存编译产物，**源码不在仓库里**；
- 虚拟屏对 App 可见（`DisplayManager.getDisplays()` 能看到 displayId 707 之类），
  这是银行 App 拦截的直接原因，不要在银行/支付 App 使用期间建屏。
