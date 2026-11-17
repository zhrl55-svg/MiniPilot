[FROM: CLAUDE]
轮次：2026-11-17 批次（F-001/F-002/F-003 + H-001..H-004 + H-006）

逐条状态：
- F-001 已修（32f403f + 7cfe830）。file_write / file_edit 抽不出 path 槽就退回云端
  （decision=cloud reason=no-args），默认路由加语境 exclude，SEED_VERSION 升到 13 并用
  迁移块合并进已有安装。设备端 assistant_fastpath.log 实测：
  [21:43:40] route: … 「这句话里写入了很多细节」 decision=cloud reason=no-args
  [21:43:45] route: … 「前面另一个会话记了要修改的东西，你一并整理成一个文档放桌面」 decision=cloud reason=no-args
  [21:43:49] route: … 「把 hello 写入 /tmp/a.txt」 decision=local file_write/write score=50（真命令仍走本地）
  补充（同一条日志暴露的同类问题）：20:17:46 「…你一并整理成一个文档放桌面…」decision=local
  file_edit/edit → 端侧模型把路径猜成 /etc/hosts。file_edit 一并加了同样的 path 守卫。
- F-002 已修（32f403f）。路由入口识别 [FROM: / [END 信封：
  [21:43:36] route: … 「[FROM: CLAUDE] 这条交接里写入了很多细节」 decision=cloud reason=internal-message
- F-003 已由 9a80cb2 满足（.tmp/、.workbuddy-ai/、tools/hub/.tmp_off* 已进 .gitignore，
  git status 干净）。物理文件有意保留：休眠计划任务 minis-context-build / minis-context-test
  仍指向 .tmp\*.vbs，删掉会留下死引用；.workbuddy-ai/ 是另一个工具的记忆目录。
- H-001 已修（7a26d88）。shell 增加 detach：{"pid": 30060, "detached": true, "duration_ms": 3}
  —— 3ms 返回 pid，子进程在调用结束后继续存活（实测写出标记文件）。前台跑常驻服务不再撞超时。
- H-002 已修（7a26d88，线上单文件执行器同步修好）。device.roots 白名单，device.root 始终允许：
  成功：D:/minis-src/.claude/bridge/… → {"path":"…","size":9,"sha256":"8ad41917…"}
  拒绝：C:/Windows/hubtest-e2e.txt → not_allowed: path is outside this executor's root:
        C:\Windows\hubtest-e2e.txt (allowed: …\hubroot, D:/minis-src)
- H-003 已修（7a26d88）。Windows 探测 Git Bash（hermes / ProgramFiles / scoop），
  不信 where bash（它先命中 WindowsApps 的 WSL 别名）；info 回显实际 shell：
  shell_program = C:\Users\zhrl5\AppData\Local\hermes\git\bin\bash.exe
  `echo "a b" | tr a-z A-Z` → A B
- H-004 已定位（7a26d88）：三段 repr 对比完全一致，手机 Hub.call「hub call command:
  "echo \"a b\""」→ 帧「hub frame command: 同」→ PC journal「cmd_repr: 'echo \"a b\"'」，
  端到端 stdout = a b、exit_code=0、326ms。结论：引号没有在传输层被吃掉，该症状在当前
  链路上不成立，旧记录应降级。两端日志已常驻，下次再出现可直接定位到哪一跳。
- H-006 已做（532ff6f）。① token：X-Minis-Token，首次启动生成到 ego_srv.json（不入库），
  缺失/错误 401；② 只监听 127.0.0.1：PC 上 LAN IP 与 tailnet IP 都连不上（000），手机经
  adb reverse tcp:8799 tcp:8799 能拿到 /health；③ 并发 2：三条并发里第三条 1.6ms 回 429 busy；
  ④ 异常统一 JSON：ego 缺失时 /health → ego:""，/q →「ego-browser.exe not found under …」，
  不 reset；⑤ 保活：MinisEgoSrv（登录）+ MinisEgoWatch（每分钟看门狗），实测杀掉后 30s 自愈；
  ⑥ 版本解耦：不再写死 0.6.3.17；⑦ 源码入库 + hubroot/README.md 运行手册。
  另修两处真问题：Windows 的 SO_REUSEADDR 会让第二个实例抢同一个端口（一度起了 5 个实例），
  现在用 pid 文件 + 端口探测拒绝多起；子进程 stdout 被重定向进日志文件会让别的进程写不进去，
  且缓冲不刷就看不到记录。

单测：全量 testGithubPerfUnitTest 按用户指示本批**不再跑**（上一版基线：6561 用例 / 71 失败 /
24 个失败类，失败类清单见文末）。本批改动涉及的聚焦单测已跑通：
  · com.openminis.app.assistant.routing.* —— BUILD SUCCESSFUL（含新增的语料 5 例、
    no-args / internal-message 专项、v13 迁移合并）
  · com.openminis.app.hub.* —— BUILD SUCCESSFUL（含新增的引号往返断言）
  · tools/hub/test_executor.py —— 9 项全过（路径根、Git Bash 探测、detach 3ms 返回且
    子进程存活、info 回显）
基线失败类清单（未复跑，供下次对照）：
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

产物：APK 绝对路径 D:\minis-src\src\android\app\build\outputs\apk\perf\app-perf.apk
      sha256 61b98757c6aedd8f69a229f14c116432952433289ae20817a277ce387e5c07f8
      字节数 75022966
      apksigner 证书 SHA-256 d3c5d4c293492430f0ab6c7dfe8b44f09e183b5d28e3645320c4d729b1ce20d0（与基准一致）
      包名 com.openminis.app.dev / versionCode 55 / versionName 1.21-dev
装机：com.openminis.app.dev，versionCode 55，lastUpdateTime 2026-11-17 21:43:12

提交：32f403f F 系列路由修复 / 7a26d88 hub 通信层 / 7cfe830 file-edit 路径守卫 /
      532ff6f ego 服务硬化 / 2960658 需求文档同步（含 H-006）

未完成项及原因：
- H-005（持久 shell 会话）：需求列为可选、优先级最低，本批未做；当前仍是「无状态执行器」。
- F-003 物理清理：有意保留，理由见上。
- 手机侧带 token 的 /q 调用未由手机发起：设备上没有 curl/wget（已用浏览器经 reverse 隧道
  验证 /health 可达），且令牌不应进模型上下文，故未走 chat.prompt。
- 线上跑的是 C:\Users\zhrl5\hub\hub_executor.py（单文件版，与仓库的 executor.py 是两份
  实现）。已同步修复并纳管进 tools/hub/deploy/，README 记了这一节。
[END CLAUDE]
