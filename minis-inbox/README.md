# minis-inbox

设备 → Claude 的告警归档目录。

MiniPilot 把升级告警写到**设备本地**的
`/var/minis/shared/self-improve/alerts.jsonl`（全局目录，跨会话可读）。
PC 挂载的仓库是 PC→手机的单向快照，设备写进去的文件回不到 PC，
所以不能拿挂载目录当出口。

PC 侧 `.claude/bridge/watch.py` 每 30 秒通过 debug RPC (`debug.shellExecute`)
读取设备上的告警文件，把新行镜像到本目录的 `alerts.jsonl`，并以
`_channel: "alert"` 标记输出给监听会话。

收到告警后由 Claude 拉取 `/var/minis/shared/self-improve/` 的问题单，
走归并 → 提案 → A/B → 改码/编译/装机流程。设备端永远不自行修改规则、
skill 或源码。
