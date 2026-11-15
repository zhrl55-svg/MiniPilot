# 生活能力建议 — 基于 Operit2 盘点与 Minis 现状

日期：2026-11-15
状态：P0-1 上下文注入与 P0-3 提醒投递状态已实施；其余为建议稿
参考：同日对 AAswordman/Operit2 的生活类能力盘点（内置工具 / Android Host / 外部插件 / 工作流四层）

---

## 1. 结论

Minis 不需要照抄 Operit2 的广度。Minis 在三个关键维度上已经优于 Operit2：

1. 本地确定性路由，命中时完全不经过云端（`AssistantRouter.kt`）；
2. 破坏性分级确认，只有删除/覆盖/系统设置等 HIGH 风险才弹卡（`LocalCapabilityRegistry.kt`）；
3. 生活能力与云端 Agent schema 分离，本地能力默认不出设备（`LocalCapabilityRegistry` 文件头注释）。

因此建议方向是**把已有架构做深**：补上下文注入、通知历史、投递状态与能力台账，而不是扩插件数量。

## 2. 核查基线（2026-11-15）

- 设备：HUAWEI ELS-AN00 / Android 12，应用 `com.openminis.app.dev`
- 版本：1.15-dev build 37，构建时间 2026-11-15 18:19:41
- 日志检查：
  - 应用日志 `minis-2026-11-15.log`：无 `[ERROR]` 记录；最后一条为 11:46（文件日志开关当前关闭，当前会话未写文件）
  - crash buffer：无本应用崩溃记录
  - `dumpsys activity exit-info`：全部为 `USER REQUESTED`（installPackageLI / 应用内重启），无 CRASH / ANR
  - 当前进程 logcat：无 E 级异常，仅 HwApsManager / OpenGLRenderer / Choreographer 系统噪音
- 已修复 WARN（2026-11-15）：`[Soul] personality body is over the language-aware limit (OverLimitEnglish(words=1095, cap=1000))`，人格正文超限后整体回退为 identity-only 系统提示词。已将正文压缩至 983 词写回设备，备份为 `SOUL.md.bak.20261115T105214Z`（`SoulStore.kt` 语言感知上限不变：CJK > 30% 按 1600 字，否则按 1000 词）

## 3. P0：下一轮就做

### P0-1 云端回退与定时任务的环境上下文注入

**借鉴点**：Operit2 的 message_insert 可按项注入时间、电量、天气、位置、当前应用、使用时长、OCR、通知，是"陪伴感"的主要来源；但它默认全开，权限与隐私代价大。

**现状**：Minis 的天气、电量、日历、通知摘要都只能通过工具显式调用（`LocalCapabilityRegistry.kt`），云回退的轮次不带任何设备状态。

**做法**：

- 新增 `ContextAssembler`，只在三条走模型的路径上组装上下文块：本地未命中、`needsModelExtraction`、定时任务执行。
- 内容与默认值：
  - 当前时间：默认开
  - 电量：默认开
  - 天气：默认开，复用 `WeatherManager`（open-meteo），带 15–30 分钟 TTL 缓存
  - 下一个日程：默认关
  - 最近通知：默认关，开启时只给 app + 标题
  - 位置：默认关
- 形式：紧凑 markdown/JSON 块（目标 < 120 token），作为系统提示词附加段或独立 system 消息。
- 红线：不落盘、不写入聊天历史；通知正文默认不注入；验证码模式自动打码；单项开关可在设置页关闭。

**验收**：本地路由评分不变；上下文块不进历史消息；关闭全部开关时请求体与现在一致；单测覆盖开关矩阵。

### P0-2 通知摘要的可选持久化

**借鉴点**：Operit2 只能读 `activeNotifications`，"今天上午有什么通知"在通知栏清掉后不可用。

**现状**：`NotificationInbox.kt` 是进程内内存 50 条，去重、进程重启清空——隐私设计正确，但可用性受限。

**做法**：

- 默认关闭的"通知历史（仅本机）"选项，环形保留 6–24 小时。
- 只存 app 名、标题、时间；正文不落盘。
- 撤销通知监听权限、退出登录、手动点"清除通知历史"时立即清空。
- 助手回复中注明数据来自本机缓存。

**验收**：开启后进程重启仍能回答时间范围内的问题；默认关闭；清除按钮生效；无正文写入磁盘。

### P0-3 提醒/定时任务的投递状态

**借鉴点**：Operit2 的提醒是应用内轮询，没有系统级保障；Minis 已有 AlarmManager 精确闹钟 + BOOT_COMPLETED 重排（`AlarmOffloadManager.kt` / `AlarmReceiver.kt` / `ScheduledTaskStore.kt`），底座领先。

**缺口**：投递结果不可见，通知权限关闭时用户无感知。

**做法**：

- 持久化投递状态：`pending → fired → delivered / missed`。
- `notify` 成功后才记 `delivered`；开机或进程重启时把过期未投递的标记 `missed` 并按规则重排。
- 通知权限关闭、精确闹钟不可用时，在助手回复里明确提示"提醒可能不会响"。
- 设置页增加"提醒可靠性"诊断：通知权限、精确闹钟权限、电池优化白名单。

**验收**：调度后杀进程，状态一致；关闭通知权限时有明确提示；重启后周期任务恢复。

## 4. P1：下一批能力时做

### P1-1 生活能力按"12306 模板"扩展

把新增能力固化成 checklist：

1. 公开、结构化的接口（拿不到就走 web_search 兜底，不写脆弱网页抓取）
2. 只读优先
3. 槽位声明（`SlotSpec`）
4. 路由关键词 + 排除词 + 讨论词守卫（`DefaultAssistantRoutes.kt`）
5. `LocalSpokenSummary` / `LocalActionSpeech` 文案
6. `LocalResultFormatter` 中文摘要
7. `AssistantDiagnostics` 记录
8. 单测

候选排序（高频 + 有公开接口 + 可降级）：快递物流、航班动态、电影演出、限行/油价。

天气保持 open-meteo 结构化数据，不学 Operit2 用百度搜索结果当天气。

### P1-2 能力台账（用户可见）

用 `LocalCapabilityRegistry` 元数据生成"本机能力"页：每个动作的只读/破坏性属性、风险等级、所需权限、数据是否出设备。补上 Operit2 插件市场"默认开启什么、权限边界在哪"不透明的问题。

同时把 `AssistantDiagnostics` 的云端回退原因分布（`no-match` / `low-confidence` / `topic-mention` / `no-args` / `weak-write-match`）做成聚合视图，用数据决定下一条路由加什么。

### P1-3 语音闭环保持

新能力必须带 `LocalSpokenSummary` / `LocalActionSpeech`；TTS 保持系统能力，不引入多供应商。

## 5. 明确不建议做

- **UI 自动化**（Operit2 `automatic_ui_base`）：权限成本、版本兼容、误操作风险不划算；保留 `app_launch` + deep link。
- **通用工作流引擎**：`scheduled_task` + 本地能力组合已覆盖大部分场景，先观察真实需求。
- **动态插件加载 / 插件市场**：安全模型复杂；扩展走"声明式路由 + 编译进包"。
- **桌宠 / waifu / 角色卡**：与产品定位和资源不匹配。
- **健康数据**：`HealthManager.kt` 目前是 stub。要么真正接 Health Connect（只读步数/睡眠），要么删掉，不留"看起来有、实际没有"的接口。

## 6. 实施顺序建议

1. P0-3 提醒投递状态（独立、小步、直接补信任）
2. P0-1 ContextAssembler（收益最大，需要设置项与隐私评审）
3. P0-2 通知历史持久化（隐私面最大，放最后）
4. P1-2 能力台账 → P1-1 新能力 → P1-3 语音补齐

## 7. 实施记录（2026-11-15）

- **提醒投递状态（P0-3）**：`ScheduledNotificationStore` 增加
  `pending → delivering → delivered / failed` 与 `pending → cancelled`
  状态机；`ScheduledNotificationReceiver` 先写持久化 claim 再投递，失败写入
  `failed` 与真实错误；`ScheduledNotificationScheduler.rescheduleAll` 在
  `BOOT_COMPLETED`、`MY_PACKAGE_REPLACED` 和冷启动时重挂未来提醒、补投逾期
  提醒、把中断的 `delivering` 标记为 unknown-outcome 失败；新增
  `android-notification history`，本地 `reminder list` 改为历史视图。
- **上下文注入（P0-1）**：`ContextAssembler` 在系统提示词末尾附加
  `<device-context>`；时间/电量/天气默认开，位置/通知/当前应用/屏幕文本
  默认关。天气与位置走 `WeatherContextCache` 的 30 分钟内存缓存，请求路径
  不发网络请求；屏幕文本由无障碍服务后台节流缓存，并在注入前做不可信数据
  转义。设置页新增“消息上下文”分组。
- **未实施**：P0-2 通知历史持久化、能力台账、新能力扩展。
