# Kotlin 接入设计：记忆检索层（Phase A/B）

> 基于 2026-11-14 的 Python 原型评测。原型是只读的，不改 App；本文是把同一套结构接进
> Android 的设计。目标不是“让 0.3B 读记忆”，而是让 Markdown 记忆可被高效召回。

## 0. 结论

- **Phase A（先做）**：在 `MemoryRepository` 内加一个**内存索引**，升级 `memory_get`。
  系统提示不变；`memory_get` 从“全关键词 AND + 行窗口”变成“BM25 + bigram + 标签/小标题排序”。
  不改注入链路，风险最低。原型在 35 条查询上 recall@5 100%、MRR 0.890，top-5 约为固定注入的 18%。
- **Phase B（再评估）**：把 GLOBAL 拆成“核心规则常驻 + 参考事实按需检索”。
  `buildSystemPrompt()` **不接收当前用户消息**，且在主线程调用两次/每次发送；不要为它加 query-aware
  检索，否则会破坏前缀缓存并增加主线程开销。Phase B 只注入核心章节，参考事实靠 `memory_get` 按需取。
- **不做**：SQLite FTS/向量库（当前规模不需要）、用 Pocket-0.3B 做检索/排序、把 Markdown 改成 DB 独占。
- **0.3B 的位置**：只在写入路径做 `type/tags/entities` 抽取（96 token 输出），不参与读取。

## 1. 现状与接入点（源码实测）

| 位置 | 现状 | 影响 |
|---|---|---|
| `MemoryRepository.getMemory(keywords, scope)` | 空格分词、ALL 必须命中、±2 行窗口、文件顺序、无排序 | 慢且漏；同义/跨行/长条目检索差 |
| `MemoryRepository.loadGlobalMemoryFragment()` | GLOBAL 全量注入 | 26KB 固定成本 |
| `MemoryRepository.loadRecentDailyMemoryFragment()` | 最近 3 个日志各前 200 行 | 固定成本 + 主线程磁盘读 |
| `ChatViewModel.buildSystemPrompt()`（约 17446） | 无参数，主线程调用两次/发送 | 不能做 query-aware 注入 |
| `ChatViewModel.executeTool()`（约 15000） | `suspend`；`memory_get` 分派在 15176 | 可把索引构建切到 IO |
| `MemoryTools.executeMemoryGet` | 调 `repository.getMemory` | 工具契约可保持 |
| `MemoryRepository` 写入方法 | `writeMemory`/`saveGlobalMd`/`saveFile`/`deleteFile`/`revokeEntry`/`replaceEntryBody` | 每处都要使索引失效 |
| `MinisApp`（约 612） | `MemoryRepository(File(filesDir, "minis-global/memory"))` | 可后台 prewarm |
| `ConfigBuiltins`（约 362） | 已有 `memory.enabled` | Phase B 加开关放这里 |

补充事实：**USER.md 目前没有任何加载器**，`memory_get` 也只搜 GLOBAL + 日志。新索引必须包含
USER.md；这本身就是覆盖度增益（原型：基线 62.9% → 补 USER 覆盖 71.4%）。

## 2. 包结构

新建 `src/android/app/src/main/java/com/openminis/app/data/repository/memory/`：

```
MemoryEntry.kt          // 条目模型 + MemoryKind
MemoryEntryParser.kt    // GLOBAL/USER/日志解析，排除秘密/备份/SOUL
MemoryTokenizer.kt      // CJK bigram + Latin/digit words
MemoryQueryCleaner.kt   // 停用词、问句后缀、少量同义词扩展
MemoryTagger.kt         // 实体别名 → tags
MemoryIndex.kt          // BM25 + Jaccard + 标签/标题/小标题 + 时间/重要性
MemoryIndexRepository.kt// 持有索引、指纹、失效、预构建、搜索
MemorySearchFormatter.kt// top-k → 工具输出（截断/来源/分数）
MemoryIndexConfig.kt    // 权重、topK、字符上限
MemoryIndexStatus.kt    // 调试状态
```

保持 `MemoryRepository` 是门面；现有调用方不改签名（除 Phase B 的注入开关）。

## 3. 条目模型与解析

```kotlin
enum class MemoryKind { GLOBAL, USER, DAILY }

data class MemoryEntry(
    val id: String,              // "GLOBAL.md#3"
    val file: String,            // "GLOBAL.md" / "2026-11-14.md"
    val kind: MemoryKind,
    val title: String,
    val body: String,            // 不含标题的原文
    val date: String?,           // 日志日期 YYYY-MM-DD
    val headings: List<String>,  // ### 子标题
    val tokens: List<String>,
    val tokenSet: Set<String>,
    val tf: Map<String, Int>,
    val tags: Set<String>,
    val recency: Double,
    val importance: Double,
)
```

解析规则（与原型一致）：

- `GLOBAL.md` / `USER.md`：按 `^## ` 切节；首节前的内容作为“（前言）”条目。
- `YYYY-MM-DD.md`：按 `^<!-- YYYY-MM-DD HH:MM:SS -->$` 切块；块内第一行 `## ` 作标题。
- 忽略：`SOUL.md`（身份，常驻，不检索）、点文件、`*.bak.*`、非 `GLOBAL.md`/`USER.md`/日期 `.md`。
- **秘密排除**：任何以 `.` 开头的文件（含 `.workbuddy_phone`）永不入索引；加单测锁死。
- 单文件过大时截断（建议 256KB）并记日志；条目正文进索引前可截到 8KB，避免异常日志撑爆内存。
- 解析器不依赖 Android API，纯 Kotlin/JVM，便于单测。

## 4. 检索算法

与原型一致，权重放 `MemoryIndexConfig`，不要散落：

```kotlin
const val W_BM25 = 0.46
const val W_JACCARD = 0.18
const val W_ENTITY = 0.14
const val W_TITLE = 0.08
const val W_HEADING = 0.10
const val W_RECENCY = 0.02
const val W_IMPORTANCE = 0.02
const val BM25_K1 = 1.2
const val BM25_B = 0.75
const val MMR_DEDUP = 0.90
const val TOP_K = 5
const val MAX_RECALL_CHARS = 6_000
const val MAX_ENTRY_CHARS = 1_200
```

- **Tokenize**：CJK 连续段 → 相邻 bigram（单字保留单字）；Latin/digit 连续段 → 小写词；`snake_case` 再拆。
- **Query 清洗**：停用词（怎么/什么/哪些/为什么/多少/吗/呢/的/了/要/我的…）；粘连后缀
  （“上有哪些”“了什么”“多少行”“放在哪”）；少量同义词扩展（歌源→音乐源/网易云、小窗→freeform/浮窗、
  验证码→短信/登录、唤醒词→唤醒）。**不要**用单字停用词全局删除，避免破坏“上证”等词。
- **打分**：BM25（按当前候选集归一化）× 0.46 + bigram Jaccard × 0.18 + 实体标签命中率 × 0.14
  + 标题重合 × 0.08 + `###` 小标题重合 × 0.10 + 时间衰减 × 0.02 + 重要性 × 0.02。
- **去重**：top-k 前用 Jaccard > 0.90 去掉近重复条目（MMR 简化版）。
- **时间**：日志 `exp(-days/14)`；GLOBAL/USER 固定 0.9。重要性：GLOBAL 1.15 / USER 1.10 / DAILY 1.00。
- **Scope**：`daily` 只排日志；`all` 排 GLOBAL + USER + 日志。IDF 用全量索引计算，scope 只过滤候选（实现简单，偏差可接受）。

## 5. 索引生命周期与线程

`MemoryIndexRepository`：

```kotlin
class MemoryIndexRepository(private val memoryDir: File) {
    fun search(query: String, scope: MemoryScope, limit: Int = TOP_K): MemorySearchResult
    fun prewarm()                 // Dispatchers.IO 调用
    fun invalidate()              // 写路径调用；置 dirty
    fun status(): MemoryIndexStatus
}
```

- 索引是**派生数据**，不落盘、不进 `memory/` 目录、不参与备份；Markdown 永远是事实源。
- **失效**：
  - 显式：`MemoryRepository` 的 `writeMemory` / `saveGlobalMd` / `saveFile` / `deleteFile` /
    `revokeEntry` / `replaceEntryBody` 末尾调用 `invalidate()`。
  - 兜底：搜索前计算指纹 `(name, size, lastModified)`；变了就重建。覆盖 Settings/SAF/备份导入等外部写入。
  - 不做 FileObserver（省电；指纹已够）。
- **线程**：
  - `MemoryIndexRepository.search` 同步、加锁、双重检查；构建时读文件 + 分词，条目数小（当前 47）。
  - `executeMemoryGetTool` 改为 `suspend`，内部 `withContext(Dispatchers.IO)` 调 `getMemory`。
    `executeTool` 已是 `suspend`，改动小。
  - `MinisApp` 构造后可在 `Dispatchers.IO` 调一次 `prewarm()`；不阻塞启动。
  - **不要**在 `buildSystemPrompt()` 里构建索引（主线程，两次/发送）。
- **并发**：`@Volatile` 持有不可变 `MemoryIndex`；`synchronized` 重建；搜索只读快照，安全。

## 6. 门面与工具契约

### 6.1 `MemoryRepository.getMemory`

保持签名，内部改为：

```kotlin
fun getMemory(keywords: String, scope: String, limit: Int = 5): String {
    if (keywords.isBlank()) return legacyGetMemory("", scope)       // 空查询保持全量 dump
    val result = indexRepository.search(keywords, scope.toMemoryScope(), limit)
    if (result.hits.isEmpty()) return legacyGetMemory(keywords, scope) // 防回归
    return MemorySearchFormatter.format(result)
}
```

- `getMemory` 仍返回 `String`，`MemoryTools` 只透传；测试/旧调用方不受影响。
- 若索引构建抛异常：记 `AppLogger.warning("MemoryIndex", ...)`，回退 legacy，绝不失败工具调用。
- `scope=all` 现在包含 USER.md；更新工具描述。
- 空 `keywords`：保留当前“全量 dump（500 行/30KB）”行为。

### 6.2 工具 schema

`MemoryTools.memoryGetToolDefinition()`：

- `keywords` 描述从“ALL must appear”改为：
  “Space-separated keywords or a short natural-language query. Results are ranked and include
  GLOBAL, USER, and daily logs (scope=all) or daily logs only (scope=daily).”
- 新增可选 `limit`（int，1–10，默认 5）；`MemoryTools.executeMemoryGet` 读取并传给 repository。
- `scope` 枚举保持 `daily|all`，不新增值，避免工具选择复杂度。
- `tool_title` 不变。

### 6.3 输出格式

```
[Recalled 5 entries (of 47) for "freeform 小窗" — ~3.2k chars]
[GLOBAL.md › 屏幕操作 | score 0.84 | tags: freeform]
<entry body, max 1200 chars>
---
[GLOBAL.md › 已知的坑（速查） | score 0.41 | tags: crlf]
...
```

- 总长 ≤ 6,000 字符；单条 ≤ 1,200 字符；超出加 `…` 和“narrow the query”提示。
- 空 results：`No matches found for: ...`（保持现有风格）。
- 结果必须标注来源文件/章节，模型才能引用。

## 7. 注入策略

### Phase A（先做，零注入改动）

只升级 `memory_get`。`loadGlobalMemoryFragment` / `loadRecentDailyMemoryFragment` 不动。
模型在需要设备/服务/坑时主动调 `memory_get`；工具描述更新即可。**系统提示字节稳定，前缀缓存不受影响。**

### Phase B（token 缩减，单独开关）

新增：

```kotlin
fun loadCoreGlobalFragment(): String?   // 只取核心章节
fun loadUserCoreFragment(): String?     // 身份/核心偏好/协作约定/硬约束
```

核心章节前缀（常量，可配）：
`沟通`、`对话基调`、`默认授权`、`任务纪律`、`Minis 改造协作分工`、`排障次序`、`当前基线`。
其余（定时任务、屏幕操作、网络、基础设施、Minis 自身、能力面速查、工具与技能、能力缺口、已知的坑、维护）
作为参考事实，按需检索。

- `ChatViewModel.buildSystemPrompt()` 里：
  - `globalMemoryFragment` 换成 `loadCoreGlobalFragment()`（开关 `memory.layeredInjection`）。
  - 新增 `userCoreFragment = loadUserCoreFragment()`（开关 `memory.userCoreInjection`，可同开关）。
  - 在 `base` 的系统提示里加一句：
    “Reference memory (devices, services, screen/network pitfalls, app internals) is not injected
    in full. Call `memory_get` with a short query before answering questions that depend on those facts.
    The GLOBAL rules above remain authoritative.”
- **不要**做 query-aware 注入：`buildSystemPrompt()` 没有用户消息，7+ 个调用点，主线程两次/发送；
  把 query 传进去会让提示按消息变化，破坏前缀缓存，也把检索成本压到主线程。
- 回退：`memory.layeredInjection=false` 时行为与今天完全一致；核心章节解析失败（标题改了）时回退全量 GLOBAL 并记日志。
- 预期：GLOBAL 26KB → 核心约 8–10KB；日志部分不变。具体收益待 Phase B 实测。

## 8. 写入路径与失效

在 `MemoryRepository` 的写方法末尾统一调用：

```kotlin
private fun invalidateIndex() = indexRepository.invalidate()
```

覆盖：`writeMemory`、`saveGlobalMd`、`saveFile`、`deleteFile`、`revokeEntry`、`replaceEntryBody`。
其他类直接写 `memoryDir` 的情况（BackupImporter 等）由指纹兜底；建议在导入/恢复完成后也调一次
`memoryRepository.invalidateIndex()`，减少首次搜索的重建。

## 9. 配置与开关

在 `ConfigBuiltins`（`memory.enabled` 附近）新增：

| key | 类型 | 默认 | 说明 |
|---|---|---|---|
| `memory.indexRetrieval` | bool | true（dev） | `memory_get` 是否走索引；false 时纯 legacy |
| `memory.layeredInjection` | bool | false | Phase B 核心章节注入 |
| `memory.userCoreInjection` | bool | false | Phase B USER 核心注入 |
| `memory.recallLimit` | int | 5 | top-k（1–10） |
| `memory.recallMaxChars` | int | 6000 | 工具输出上限 |

`MemoryRepository` 不直接读 ConfigRegistry（构造简单）；由 `MinisApp` 把开关/配置作为构造参数或
`MemoryIndexConfig` 传入。Phase A 可先不加开关（纯改进 + 回退），Phase B 再加。

## 10. 调试与验收

新增 DebugServer 方法（参照 `agent.guard.*` / `agent.skills.*`）：

- `memory.index.status` → `{entryCount, globalCount, userCount, dailyCount, chars, buildMs, lastQueryMs, fingerprint}`
- `memory.index.rebuild` → 强制重建
- `memory.search {query, scope, limit}` → top-k `{file,title,score,bm25,jaccard,entity}`

验收：

1. 设备上跑原型同一套 35 条查询，`memory.search` 的 top-5 与 Python 结果一致或差异可解释；
   recall@5 ≥ 90%，MRR ≥ 0.85。
2. `memory_get` 原有测试全过；空 `keywords` 仍返回全量 dump。
3. `.workbuddy_phone`、`.bak.*`、`SOUL.md` 永不入索引（单测 + 设备抽查）。
4. 2,000 条合成条目：构建 < 500ms、单次搜索 < 50ms（JVM 目标）；设备上记录 `buildMs/queryMs`。
5. Phase B：系统提示中 GLOBAL 字符数下降 ≥ 50%，核心规则无缺失；前缀缓存稳定性用两次发送的
   prompt 前缀对比验证。

## 11. 测试清单

- `MemoryEntryParserTest`：GLOBAL/USER 分节、日志块、前言、空文件、点文件/备份/SOUL 排除。
- `MemoryTokenizerTest`：中文 bigram、英文/数字、混合、snake_case、空串。
- `MemoryQueryCleanerTest`：停用词、粘连后缀、同义词扩展；“上证”不被破坏。
- `MemoryIndexTest`：合成 fixture，10–15 条查询的 top-k/MRR；MMR 去重。
- `MemoryRepositoryIndexTest`：临时目录；写入 → 失效 → 搜索命中；legacy 回退；空查询 dump。
- `MemorySecretExclusionTest`：秘密文件永不索引。
- `MemoryIndexPerfTest`（可选，JVM）：2,000 条构建/查询预算。

## 12. 实施顺序与估算

1. **Phase A**（1–1.5 天）：纯 Kotlin 类 + 单测 → `MemoryRepository` 门面 + legacy 回退 →
   `executeMemoryGetTool` 切 IO → Debug RPC → 设备 eval。
2. **Phase B**（0.5–1 天）：核心章节解析 + 开关 + 提示句 + 字符数对比。
3. **Phase C（可选）**：USER 核心注入；条目级截断策略；真实 query 集扩到 100 条；
   只有在真实集 recall@5 < 90% 时才评估 bge-small-zh ONNX（不是 Pocket-0.3B）。

## 13. 风险与规避

| 风险 | 规避 |
|---|---|
| 索引过期 | 写路径显式 invalidate + 搜索前指纹兜底 |
| 主线程卡顿 | Phase A 不在 `buildSystemPrompt` 构建；`memory_get` 切 IO；启动 prewarm |
| 前缀缓存被破坏 | Phase B 只注入文件派生的稳定核心章节，不做 query-aware |
| 秘密进索引 | 点文件/备份/SOUL 硬排除 + 单测 |
| 打分过拟合 35 条 | 权重常量可调；先用真实 query 集验证，不继续手调 |
| 工具行为变化 | 空查询保持旧 dump；无命中/异常回退 legacy |
| 内存增长 | 单文件/单条目截断；记录 entryCount/chars；monitor |
| 备份兼容 | 索引不落 `memory/`，不改 Markdown/备份格式 |

## 14. 明确不做

- 不引入 SQLite FTS/向量库；等条目 > 10k 或真实集 recall@5 < 90% 再评估。
- 不让 Pocket-0.3B 读记忆、排序、改写事实；它只在写入路径抽标签/实体。
- 不把 Markdown 迁进数据库；索引永远可重建。
- 不在系统提示里注入检索到的条目原文（Phase B 只注入核心规则）。
- 不为了评测继续加特判规则；下一轮用真实会话 query 验证。
