package com.openminis.app.storage

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Composition scan and controlled cleanup for the app's private directories.
 *
 * Why this exists: `getTotalSpace`/`filesDir` totals were the only storage
 * numbers the app could produce, because a non-root shell cannot read
 * `/data/user/0/<pkg>` and the PRoot sandbox does not mount it. Roughly 1.87 GB
 * of the 2026-11-15 install was unattributed. This walks the directories from
 * INSIDE the app process, classifies every byte, and exposes the result over
 * one debug RPC (`storage.breakdown`).
 *
 * Safety rules, all enforced here rather than in the RPC layer:
 *  - symlinks are never followed (sizing AND deletion), so a link cannot
 *    inflate a total or make a delete reach outside its subtree;
 *  - cleanup only accepts cache/temp paths that the whitelist in
 *    [PrivateStoragePolicy] calls regenerable; everything else is refused with
 *    a reason;
 *  - every preview and every delete is appended to an on-disk audit log.
 */
object PrivateStorage {
    private const val TAG = "PrivateStorage"

    const val CAT_BUSINESS = "business"
    const val CAT_MODEL = "model"
    const val CAT_CACHE = "cache"
    const val CAT_TEMP = "temp"
    const val CAT_ENVIRONMENT = "environment"

    private val TEMP_SEGMENTS = setOf("tmp", "temp", "temporary", ".tmp")
    private val CACHE_SEGMENTS = setOf(
        "cache", "caches", "code_cache", "__pycache__", ".cache", "_cacache",
        "npm-cache", "pip-cache", ".npm",
    )
    private val MODEL_SEGMENTS = setOf(
        "models", "model", "mnn", "mnn-llm-0.3b", "asr-sensevoice", "voiceprint", "sensevoice",
    )
    private val MODEL_EXTENSIONS = listOf(
        ".mnn", ".onnx", ".gguf", ".safetensors", ".tflite", ".pt", ".pth",
    )
    private val TEMP_FILE_SUFFIXES = listOf(
        ".tmp", ".temp", ".part", ".partial", ".crdownload", ".download",
    )

    // ── Scan model ──────────────────────────────────────────────────────────

    data class StorageNode(
        val name: String,
        val path: String,
        val category: String,
        var bytes: Long = 0L,
        var files: Long = 0L,
        val children: MutableList<StorageNode> = mutableListOf(),
    )

    data class FlatEntry(
        val path: String,
        val bytes: Long,
        val files: Long,
        val category: String,
        val isDir: Boolean,
    )

    data class Candidate(
        val path: String,
        val bytes: Long,
        val files: Long,
        val category: String,
        val reason: String,
        val isDir: Boolean,
    )

    data class Bucket(val category: String, val bytes: Long, val files: Long)

    data class ScanResult(
        val roots: List<StorageNode>,
        val buckets: List<Bucket>,
        val top: List<FlatEntry>,
        val tempCandidates: List<Candidate>,
        val totalBytes: Long,
        val totalFiles: Long,
        val elapsedMs: Long,
        val walkedFiles: Long,
        val truncated: Boolean,
    )

    data class Verdict(
        val path: String,
        val allowed: Boolean,
        val reason: String,
        val category: String,
        val bytes: Long,
        val files: Long,
        val isDir: Boolean,
    )

    data class CleanupReport(
        val auditId: String,
        val dryRun: Boolean,
        val verdicts: List<Verdict>,
        val deleted: List<DeletedEntry>,
        val totalBytes: Long,
        val totalFiles: Long,
        val freedBytes: Long,
        val auditPath: String,
    )

    data class DeletedEntry(
        val path: String,
        val bytes: Long,
        val files: Long,
        val category: String,
        val reason: String,
        val freedBytes: Long,
        val failed: Boolean,
    )

    enum class RootKind { INTERNAL, EXTERNAL_FILES, EXTERNAL_CACHE }

    internal data class RootSpec(val name: String, val dir: File, val kind: RootKind)

    // ── Scan ────────────────────────────────────────────────────────────────

    fun scan(
        context: Context,
        maxDepth: Int = 3,
        maxEntries: Int = 120,
        minBytes: Long = 1L shl 20,
        includeFiles: Boolean = true,
        maxFiles: Int = 400_000,
        deadlineMs: Long = 120_000,
    ): ScanResult = scanDirs(
        specs = rootSpecs(context),
        maxDepth = maxDepth,
        maxEntries = maxEntries,
        minBytes = minBytes,
        includeFiles = includeFiles,
        maxFiles = maxFiles,
        deadlineMs = deadlineMs,
    )

    /** Pure scan over explicit roots; used by tests and by [scan]. */
    internal fun scanDirs(
        specs: List<RootSpec>,
        maxDepth: Int = 3,
        maxEntries: Int = 120,
        minBytes: Long = 1L shl 20,
        includeFiles: Boolean = true,
        maxFiles: Int = 400_000,
        deadlineMs: Long = 120_000,
    ): ScanResult {
        val started = System.nanoTime()
        val roots = mutableListOf<StorageNode>()
        val buckets = linkedMapOf<String, LongArray>()
        val flat = mutableListOf<FlatEntry>()
        val candidates = mutableListOf<Candidate>()
        var walked = 0L
        var truncated = false
        var totalBytes = 0L
        var totalFiles = 0L

        for (spec in specs) {
            if (!spec.dir.isDirectory) continue
            val walk = walkRoot(
                spec = spec,
                maxDepth = maxDepth.coerceIn(1, 8),
                includeFiles = includeFiles,
                minBytes = minBytes.coerceAtLeast(0),
                maxFiles = maxFiles.coerceIn(1_000, 5_000_000),
                deadlineNanos = started + deadlineMs * 1_000_000,
            )
            totalBytes += walk.rootNode.bytes
            totalFiles += walk.rootNode.files
            walked += walk.walked
            truncated = truncated || walk.truncated
            roots.add(walk.rootNode)
            flat.addAll(walk.flat)
            candidates.addAll(walk.candidates)
            for ((category, value) in walk.buckets) {
                val acc = buckets.getOrPut(category) { LongArray(2) }
                acc[0] += value[0]
                acc[1] += value[1]
            }
        }

        // A cache/tmp candidate discovered under another candidate adds no
        // information and would double-count in a preview total.
        val deduped = dedupeCandidates(candidates)
        val top = flat
            .filter { it.bytes >= minBytes && it.bytes > 0 }
            .sortedByDescending { it.bytes }
            .take(maxEntries.coerceIn(1, 1000))
        val bucketList = buckets.entries
            .map { Bucket(it.key, it.value[0], it.value[1]) }
            .sortedByDescending { it.bytes }
        return ScanResult(
            roots = roots,
            buckets = bucketList,
            top = top,
            tempCandidates = deduped,
            totalBytes = totalBytes,
            totalFiles = totalFiles,
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
            walkedFiles = walked,
            truncated = truncated,
        )
    }

    /** The directories one breakdown call reports on. Never overlapping. */
    private fun rootSpecs(context: Context): List<RootSpec> {
        val out = mutableListOf<RootSpec>()
        val dataDir = runCatching { File(context.applicationInfo.dataDir) }.getOrNull()
        if (dataDir != null) out.add(RootSpec("internal", dataDir, RootKind.INTERNAL))
        val extFiles = runCatching { context.getExternalFilesDir(null) }.getOrNull()
        if (extFiles != null && extFiles.isDirectory) {
            out.add(RootSpec("externalFiles", extFiles, RootKind.EXTERNAL_FILES))
        }
        val extCache = runCatching { context.externalCacheDir }.getOrNull()
        if (extCache != null && extCache.isDirectory) {
            out.add(RootSpec("externalCache", extCache, RootKind.EXTERNAL_CACHE))
        }
        return out
    }

    private class RootWalk(
        val rootNode: StorageNode,
        val buckets: Map<String, LongArray>,
        val flat: List<FlatEntry>,
        val candidates: List<Candidate>,
        val walked: Long,
        val truncated: Boolean,
    )

    private class StopWalk : RuntimeException()

    private fun walkRoot(
        spec: RootSpec,
        maxDepth: Int,
        includeFiles: Boolean,
        minBytes: Long,
        maxFiles: Int,
        deadlineNanos: Long,
    ): RootWalk {
        val rootPath = spec.dir.toPath()
        val rootNode = StorageNode(
            name = spec.dir.name.ifBlank { spec.name },
            path = spec.dir.absolutePath,
            category = when (spec.kind) {
                RootKind.EXTERNAL_CACHE -> CAT_CACHE
                else -> CAT_BUSINESS
            },
        )
        val buckets = linkedMapOf<String, LongArray>()
        val flat = mutableListOf<FlatEntry>()
        val candidates = mutableListOf<Candidate>()

        class CandidateAcc(
            val path: String,
            val category: String,
            val reason: String,
            val isDir: Boolean,
        ) {
            var bytes = 0L
            var files = 0L
        }

        // Parallel stacks: one slot per open directory. A null node means the
        // directory is deeper than maxDepth; it still aggregates into the
        // shallower open nodes but has no tree entry of its own.
        val openNodes = ArrayList<StorageNode?>()
        val openCategories = ArrayList<String>()
        val openCandidate = ArrayList<CandidateAcc?>()
        var rootSlot: StorageNode? = null

        var walked = 0L
        var truncated = false

        fun classifyRelative(relative: String): String = classify(relative, spec.kind)

        try {
            Files.walkFileTree(
                rootPath,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        if (System.nanoTime() > deadlineNanos || walked > maxFiles) {
                            truncated = true
                            throw StopWalk()
                        }
                        val relative = rootPath.relativize(dir).toString().replace('\\', '/')
                        val category = if (relative.isEmpty()) rootNode.category
                        else classifyRelative(relative)
                        val depth = openNodes.size
                        val parentCategory = openCategories.lastOrNull() ?: ""
                        val node = if (depth <= maxDepth) {
                            StorageNode(
                                name = dir.fileName?.toString() ?: spec.dir.name,
                                path = dir.toFile().absolutePath,
                                category = category,
                            )
                        } else {
                            null
                        }
                        if (node != null) {
                            if (depth == 0) {
                                rootSlot = node
                            } else {
                                val parent = openNodes.lastOrNull { it != null }
                                parent?.children?.add(node)
                            }
                        }
                        // One accumulator for the shallowest temp/cache dir on
                        // this path; nested temp/cache dirs are inside it.
                        val candidate = if (
                            (category == CAT_TEMP || category == CAT_CACHE) &&
                            parentCategory != CAT_TEMP && parentCategory != CAT_CACHE
                        ) {
                            CandidateAcc(
                                path = dir.toFile().absolutePath,
                                category = category,
                                reason = if (category == CAT_TEMP) {
                                    "临时目录（可再生）"
                                } else {
                                    "缓存目录（可再生）"
                                },
                                isDir = true,
                            )
                        } else {
                            null
                        }
                        openNodes.add(node)
                        openCategories.add(category)
                        openCandidate.add(candidate)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        walked++
                        if (walked > maxFiles) {
                            truncated = true
                            throw StopWalk()
                        }
                        val relative = rootPath.relativize(file).toString().replace('\\', '/')
                        val category = classifyRelative(relative)
                        // A symbolic link is NOT followed and contributes no
                        // bytes: its target is either counted at its real path
                        // or outside this subtree. This is the same rule the
                        // storage screen uses (T-android-storage-symlink-inflation).
                        if (attrs.isSymbolicLink) return FileVisitResult.CONTINUE
                        val size = attrs.size().coerceAtLeast(0L)
                        for (node in openNodes) {
                            if (node != null) {
                                node.bytes += size
                                node.files += 1
                            }
                        }
                        for (acc in openCandidate) {
                            if (acc != null) {
                                acc.bytes += size
                                acc.files += 1
                            }
                        }
                        val bucket = buckets.getOrPut(category) { LongArray(2) }
                        bucket[0] += size
                        bucket[1] += 1
                        if (includeFiles && size >= minBytes) {
                            flat.add(
                                FlatEntry(
                                    path = file.toFile().absolutePath,
                                    bytes = size,
                                    files = 1,
                                    category = category,
                                    isDir = false,
                                ),
                            )
                        }
                        val name = file.fileName?.toString()?.lowercase(Locale.ROOT).orEmpty()
                        val noCoveringCandidate = openCandidate.none { it != null }
                        if (noCoveringCandidate && isTempFileName(name)) {
                            val age = System.currentTimeMillis() - attrs.lastModifiedTime().toMillis()
                            if (age >= TEMP_MIN_AGE_MS) {
                                candidates.add(
                                    Candidate(
                                        path = file.toFile().absolutePath,
                                        bytes = size,
                                        files = 1,
                                        category = CAT_TEMP,
                                        reason = "临时文件（可再生）",
                                        isDir = false,
                                    ),
                                )
                            }
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                        val node = openNodes.removeAt(openNodes.lastIndex)
                        openCategories.removeAt(openCategories.lastIndex)
                        val acc = openCandidate.removeAt(openCandidate.lastIndex)
                        if (acc != null && acc.files > 0) {
                            candidates.add(
                                Candidate(
                                    path = acc.path,
                                    bytes = acc.bytes,
                                    files = acc.files,
                                    category = acc.category,
                                    reason = acc.reason,
                                    isDir = acc.isDir,
                                ),
                            )
                        }
                        if (node != null && node.bytes >= minBytes) {
                            flat.add(
                                FlatEntry(
                                    path = node.path,
                                    bytes = node.bytes,
                                    files = node.files,
                                    category = node.category,
                                    isDir = true,
                                ),
                            )
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult =
                        FileVisitResult.CONTINUE
                },
            )
        } catch (_: StopWalk) {
            // Expected: maxFiles / deadline hit. Partial numbers are still
            // useful, but postVisitDirectory never ran for the open path, so
            // flush whatever the temp/cache accumulators collected.
            for (acc in openCandidate) {
                if (acc != null && acc.files > 0) {
                    candidates.add(
                        Candidate(
                            path = acc.path,
                            bytes = acc.bytes,
                            files = acc.files,
                            category = acc.category,
                            reason = acc.reason,
                            isDir = acc.isDir,
                        ),
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "scan failed under ${spec.dir}: ${t.javaClass.simpleName}")
        }

        // The visitor builds a node for the root directory itself; its bytes
        // and children are the authoritative totals (it keeps accumulating
        // even after a StopWalk, because the root slot is never popped then).
        rootSlot?.let { slot ->
            rootNode.bytes = slot.bytes
            rootNode.files = slot.files
            rootNode.children.clear()
            rootNode.children.addAll(slot.children)
        }

        return RootWalk(
            rootNode = rootNode,
            buckets = buckets,
            flat = flat,
            candidates = candidates,
            walked = walked,
            truncated = truncated,
        )
    }

    // ── Classification ──────────────────────────────────────────────────────

    /**
     * Classify a path relative to a scan root. TEMP wins over CACHE wins over
     * MODEL, because a cache file inside `tmp/` is still disposable and a
     * model-shaped filename inside a temp dir is still temp residue.
     */
    fun classify(relative: String, kind: RootKind = RootKind.INTERNAL): String {
        val normalized = relative.replace('\\', '/').lowercase(Locale.ROOT)
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) {
            return if (kind == RootKind.EXTERNAL_CACHE) CAT_CACHE else CAT_BUSINESS
        }
        if (segments.any { isTempSegment(it) }) return CAT_TEMP
        val name = segments.last()
        if (isTempFileName(name)) return CAT_TEMP
        if (kind == RootKind.EXTERNAL_CACHE) return CAT_CACHE
        if (segments.any { it in CACHE_SEGMENTS }) return CAT_CACHE
        if (segments.any { it in MODEL_SEGMENTS }) return CAT_MODEL
        if (MODEL_EXTENSIONS.any { name.endsWith(it) }) return CAT_MODEL
        if (segments.contains("alpine-rootfs") || segments.any { it.startsWith("rootfs") }) {
            return CAT_ENVIRONMENT
        }
        return CAT_BUSINESS
    }

    private fun isTempSegment(segment: String): Boolean =
        segment in TEMP_SEGMENTS || segment.startsWith(".tmp") || segment.endsWith(".tmp")

    private fun isTempFileName(name: String): Boolean =
        TEMP_FILE_SUFFIXES.any { name.endsWith(it) }

    private fun dedupeCandidates(candidates: List<Candidate>): List<Candidate> {
        val sorted = candidates.sortedWith(compareBy({ it.path.length }, { it.path }))
        val kept = mutableListOf<Candidate>()
        for (candidate in sorted) {
            val candidatePath = candidate.path.replace('\\', '/')
            val nested = kept.any { parent ->
                val parentPath = parent.path.replace('\\', '/')
                candidatePath == parentPath ||
                    candidatePath.startsWith(parentPath.trimEnd('/') + "/")
            }
            if (!nested) kept.add(candidate)
        }
        return kept.sortedByDescending { it.bytes }
    }

    // ── Whitelist policy ────────────────────────────────────────────────────

    /**
     * Decide whether [path] is explicitly regenerable. Everything else is
     * refused. The check is canonical-path based, rejects `..`, and refuses a
     * target that is itself a symlink (deleting it is harmless, but following
     * its parent could lie about containment; refusing is the conservative
     * choice).
     */
    fun evaluate(context: Context, path: String, minAgeMs: Long = TEMP_MIN_AGE_MS): Verdict =
        evaluateAgainst(path, canonicalRoots(context), minAgeMs)

    /** Pure form of [evaluate]; [roots] are (name, canonical root) pairs. */
    internal fun evaluateAgainst(
        path: String,
        roots: List<Pair<String, File>>,
        minAgeMs: Long = TEMP_MIN_AGE_MS,
    ): Verdict {
        val raw = path.trim()
        if (raw.isEmpty()) return refused(raw, "空路径", CAT_BUSINESS)
        if (!File(raw).isAbsolute) return refused(raw, "只接受绝对路径", CAT_BUSINESS)
        val segments = raw.replace('\\', '/').split('/')
        if (segments.any { it == ".." }) return refused(raw, "路径包含 '..'，拒绝", CAT_BUSINESS)

        val target = File(raw)
        val targetPath = runCatching { target.toPath() }.getOrNull()
            ?: return refused(raw, "非法路径", CAT_BUSINESS)
        if (!Files.exists(targetPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return refused(raw, "路径不存在", CAT_BUSINESS)
        }
        if (Files.isSymbolicLink(targetPath)) {
            return refused(raw, "目标是符号链接，拒绝清理（避免越过边界）", CAT_BUSINESS)
        }

        val canonical = runCatching { target.canonicalFile }.getOrNull()
            ?: return refused(raw, "无法解析规范化路径", CAT_BUSINESS)
        val rootMatch = roots.firstOrNull { (_, root) ->
            canonical == root || canonical.path.startsWith(root.path + File.separator)
        } ?: return refused(raw, "不在 App 私有目录内，拒绝", CAT_BUSINESS)
        val (rootName, root) = rootMatch
        val relative = runCatching {
            canonical.relativeTo(root).path.replace('\\', '/')
        }.getOrDefault("")
        val category = classify(relative, if (rootName == "externalCache") RootKind.EXTERNAL_CACHE else RootKind.INTERNAL)
        val (bytes, files, isDir) = sizeOf(canonical.toPath())

        val lowerRoot = root.name.lowercase(Locale.ROOT)
        val inCacheRoot = lowerRoot == "cache" || lowerRoot == "code_cache" ||
            rootName == "externalCache"
        if (inCacheRoot) {
            return Verdict(
                raw, true, "App 缓存目录（可再生）", category, bytes, files, isDir,
            )
        }
        if (relative.isEmpty()) {
            return refused(raw, "不允许删除私有根目录本身", category, bytes, files, isDir)
        }
        val relSegments = relative.lowercase(Locale.ROOT).split('/').filter { it.isNotEmpty() }
        if (relSegments.any { isTempSegment(it) }) {
            return Verdict(
                raw, true, "临时目录内的内容（可再生）", category, bytes, files, isDir,
            )
        }
        if (relSegments.any { it in CACHE_SEGMENTS }) {
            return Verdict(
                raw, true, "缓存目录内的内容（可再生）", category, bytes, files, isDir,
            )
        }
        val name = canonical.name.lowercase(Locale.ROOT)
        if (!isDir && isTempFileName(name)) {
            val age = System.currentTimeMillis() - canonical.lastModified()
            if (age >= minAgeMs) {
                return Verdict(
                    raw, true, "临时文件且已超过 ${minAgeMs / 60_000} 分钟（可再生）",
                    category, bytes, files, isDir,
                )
            }
            return refused(raw, "临时文件仍很新，可能正在使用，保留", category, bytes, files, isDir)
        }
        val why = when (category) {
            CAT_MODEL -> "模型文件不属于可再生缓存，拒绝清理"
            CAT_ENVIRONMENT -> "系统环境（PRoot rootfs）不属于可再生缓存，拒绝清理"
            CAT_BUSINESS -> "业务数据（会话/数据库/记忆/配置/技能等）不属于可再生内容，拒绝清理"
            CAT_CACHE -> "未命中白名单的缓存路径，拒绝清理"
            else -> "不在可再生白名单内，拒绝清理"
        }
        return refused(raw, why, category, bytes, files, isDir)
    }

    private fun refused(
        path: String,
        reason: String,
        category: String,
        bytes: Long = 0L,
        files: Long = 0L,
        isDir: Boolean = false,
    ) = Verdict(path, false, reason, category, bytes, files, isDir)

    private fun canonicalRoots(context: Context): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        runCatching { File(context.applicationInfo.dataDir).canonicalFile }
            .getOrNull()?.let { out.add("internal" to it) }
        runCatching { context.getExternalFilesDir(null)?.canonicalFile }
            .getOrNull()?.let { out.add("externalFiles" to it) }
        runCatching { context.externalCacheDir?.canonicalFile }
            .getOrNull()?.let { out.add("externalCache" to it) }
        return out
    }

    // ── Cleanup ─────────────────────────────────────────────────────────────

    /**
     * Preview or delete. [requested] may be null with [all]=true, in which case
     * the scan's temp/cache candidates are used. Deletion happens only when
     * [confirm] is true; the audit line is written either way.
     */
    fun cleanup(
        context: Context,
        requested: List<String>?,
        all: Boolean,
        confirm: Boolean,
        minAgeMs: Long = TEMP_MIN_AGE_MS,
    ): CleanupReport {
        val auditId = UUID.randomUUID().toString().take(8)
        val paths = when {
            requested != null && requested.isNotEmpty() -> requested
            all -> discoverCandidatePaths(context, minAgeMs)
            else -> emptyList()
        }
        val verdicts = paths.distinct().map { evaluate(context, it, minAgeMs) }
        val allowed = verdicts.filter { it.allowed }
        val dryRun = !confirm
        val deleted = mutableListOf<DeletedEntry>()
        var freed = 0L

        if (!dryRun) {
            for (verdict in allowed) {
                val target = File(verdict.path)
                val beforeBytes = verdict.bytes
                val beforeFiles = verdict.files
                deleteTreeNoFollow(target)
                val afterBytes = if (target.exists()) sizeOf(target.toPath()).first else 0L
                val thisFreed = (beforeBytes - afterBytes).coerceIn(0L, beforeBytes)
                freed += thisFreed
                deleted.add(
                    DeletedEntry(
                        path = verdict.path,
                        bytes = beforeBytes,
                        files = beforeFiles,
                        category = verdict.category,
                        reason = verdict.reason,
                        freedBytes = thisFreed,
                        failed = target.exists(),
                    ),
                )
                // Android expects these to exist; recreate the well-known
                // cache roots so the next write does not fail.
                if (!target.exists() && isKnownCacheRoot(context, target)) {
                    runCatching { target.mkdirs() }
                }
            }
            // A deleted cache dir may be recreated by the line above, which
            // is why `failed` uses existence of the exact target, not the
            // root's absence.
        }

        val report = CleanupReport(
            auditId = auditId,
            dryRun = dryRun,
            verdicts = verdicts,
            deleted = deleted,
            totalBytes = allowed.sumOf { it.bytes },
            totalFiles = allowed.sumOf { it.files },
            freedBytes = freed,
            auditPath = auditFile(context).absolutePath,
        )
        appendAudit(context, report, all)
        return report
    }

    private fun isKnownCacheRoot(context: Context, target: File): Boolean {
        val candidates = listOfNotNull(
            runCatching { context.cacheDir }.getOrNull(),
            runCatching { context.codeCacheDir }.getOrNull(),
            runCatching { context.externalCacheDir }.getOrNull(),
        )
        return candidates.any { runCatching { it.canonicalFile == target.canonicalFile }.getOrDefault(false) }
    }

    private fun discoverCandidatePaths(context: Context, minAgeMs: Long): List<String> {
        val result = scan(
            context = context,
            maxDepth = 8,
            maxEntries = 1,
            minBytes = 0,
            includeFiles = false,
            maxFiles = 1_000_000,
            deadlineMs = 60_000,
        )
        val out = mutableListOf<String>()
        // Well-known cache roots first: they are the safest and biggest win.
        listOfNotNull(
            runCatching { context.cacheDir }.getOrNull(),
            runCatching { context.codeCacheDir }.getOrNull(),
            runCatching { context.externalCacheDir }.getOrNull(),
        ).filter { it.isDirectory }.forEach { out.add(it.absolutePath) }
        result.tempCandidates.forEach { out.add(it.path) }
        return out.distinct().filter { evaluate(context, it, minAgeMs).allowed }
    }

    /** Symlink-safe (bytes, files, isDir). A link contributes nothing. */
    fun sizeOf(path: Path): Triple<Long, Long, Boolean> {
        val isDir = Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        if (!isDir) {
            val size = runCatching { Files.size(path) }.getOrDefault(0L)
            return Triple(size, 1L, false)
        }
        var bytes = 0L
        var files = 0L
        runCatching {
            Files.walkFileTree(
                path,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        if (!attrs.isSymbolicLink) {
                            bytes += attrs.size().coerceAtLeast(0L)
                            files++
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult =
                        FileVisitResult.CONTINUE
                },
            )
        }
        return Triple(bytes, files, true)
    }

    /** Delete without following symlinks; restores write bits like session delete. */
    private fun deleteTreeNoFollow(root: File): Boolean {
        val rootPath = root.toPath()
        if (!Files.exists(rootPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true
        runCatching {
            Files.walkFileTree(
                rootPath,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        val f = dir.toFile()
                        if (!f.canWrite()) runCatching { f.setWritable(true, true) }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        runCatching { Files.delete(file) }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                        runCatching { Files.delete(file) }
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                        runCatching { Files.delete(dir) }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
        return !Files.exists(rootPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
    }

    // ── Audit ───────────────────────────────────────────────────────────────

    fun auditFile(context: Context): File = File(context.filesDir, "agent/storage-cleanup-audit.jsonl")

    private fun appendAudit(context: Context, report: CleanupReport, all: Boolean) {
        runCatching {
            val file = auditFile(context)
            file.parentFile?.mkdirs()
            val line = JSONObject().apply {
                put("id", report.auditId)
                put("ts", System.currentTimeMillis())
                put("iso", isoNow())
                put("action", if (report.dryRun) "preview" else "cleanup")
                put("dryRun", report.dryRun)
                put("all", all)
                put("requested", JSONArray().also { arr -> report.verdicts.forEach { arr.put(it.path) } })
                put("allowed", JSONArray().also { arr ->
                    report.verdicts.filter { it.allowed }.forEach { v ->
                        arr.put(JSONObject().apply {
                            put("path", v.path); put("bytes", v.bytes); put("files", v.files)
                            put("category", v.category); put("reason", v.reason)
                        })
                    }
                })
                put("refused", JSONArray().also { arr ->
                    report.verdicts.filterNot { it.allowed }.forEach { v ->
                        arr.put(JSONObject().apply {
                            put("path", v.path); put("reason", v.reason); put("category", v.category)
                        })
                    }
                })
                put("deleted", JSONArray().also { arr ->
                    report.deleted.forEach { d ->
                        arr.put(JSONObject().apply {
                            put("path", d.path); put("bytes", d.bytes); put("files", d.files)
                            put("category", d.category); put("reason", d.reason)
                            put("freedBytes", d.freedBytes); put("failed", d.failed)
                        })
                    }
                })
                put("totalBytes", report.totalBytes)
                put("freedBytes", report.freedBytes)
            }
            file.appendText(line.toString() + "\n", Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "cleanup audit not written: ${it.javaClass.simpleName}") }
    }

    /** Last [limit] audit lines, oldest first; malformed lines are skipped. */
    fun auditTail(context: Context, limit: Int): List<JSONObject> {
        val file = auditFile(context)
        if (!file.exists()) return emptyList()
        val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
        return lines.takeLast(limit.coerceIn(1, 500)).mapNotNull { line ->
            runCatching { JSONObject(line) }.getOrNull()
        }
    }

    // ── JSON rendering ──────────────────────────────────────────────────────

    fun scanToJson(result: ScanResult): JSONObject = JSONObject().apply {
        put("generatedAt", System.currentTimeMillis())
        put("iso", isoNow())
        put("roots", JSONArray().also { arr -> result.roots.forEach { arr.put(nodeToJson(it)) } })
        put("buckets", JSONArray().also { arr ->
            result.buckets.forEach { b ->
                arr.put(JSONObject().apply {
                    put("category", b.category)
                    put("bytes", b.bytes)
                    put("human", humanBytes(b.bytes))
                    put("files", b.files)
                })
            }
        })
        put("top", JSONArray().also { arr ->
            result.top.forEach { e ->
                arr.put(JSONObject().apply {
                    put("path", e.path)
                    put("bytes", e.bytes)
                    put("human", humanBytes(e.bytes))
                    put("files", e.files)
                    put("category", e.category)
                    put("isDir", e.isDir)
                })
            }
        })
        put("tempCandidates", JSONArray().also { arr ->
            result.tempCandidates.forEach { c ->
                arr.put(JSONObject().apply {
                    put("path", c.path)
                    put("bytes", c.bytes)
                    put("human", humanBytes(c.bytes))
                    put("files", c.files)
                    put("category", c.category)
                    put("reason", c.reason)
                    put("isDir", c.isDir)
                })
            }
        })
        put("totals", JSONObject().apply {
            put("bytes", result.totalBytes)
            put("human", humanBytes(result.totalBytes))
            put("files", result.totalFiles)
        })
        put("scan", JSONObject().apply {
            put("ms", result.elapsedMs)
            put("walkedFiles", result.walkedFiles)
            put("truncated", result.truncated)
        })
    }

    private fun nodeToJson(node: StorageNode): JSONObject = JSONObject().apply {
        put("name", node.name)
        put("path", node.path)
        put("category", node.category)
        put("bytes", node.bytes)
        put("human", humanBytes(node.bytes))
        put("files", node.files)
        put("childCount", node.children.size)
        // The tree is for locating the big items, not for dumping a package
        // manager's whole directory list; 60 children per level is plenty and
        // keeps the RPC response bounded on a rootfs with thousands of dirs.
        put("children", JSONArray().also { arr ->
            node.children.sortedByDescending { it.bytes }.take(60).forEach { arr.put(nodeToJson(it)) }
        })
    }

    fun reportToJson(report: CleanupReport): JSONObject = JSONObject().apply {
        put("dryRun", report.dryRun)
        put("auditId", report.auditId)
        put("auditPath", report.auditPath)
        put("allowed", JSONArray().also { arr ->
            report.verdicts.filter { it.allowed }.forEach { v ->
                arr.put(JSONObject().apply {
                    put("path", v.path); put("bytes", v.bytes); put("human", humanBytes(v.bytes))
                    put("files", v.files); put("category", v.category); put("reason", v.reason)
                    put("isDir", v.isDir)
                })
            }
        })
        put("refused", JSONArray().also { arr ->
            report.verdicts.filterNot { it.allowed }.forEach { v ->
                arr.put(JSONObject().apply {
                    put("path", v.path); put("reason", v.reason)
                    put("category", v.category); put("bytes", v.bytes)
                    put("human", humanBytes(v.bytes))
                })
            }
        })
        put("deleted", JSONArray().also { arr ->
            report.deleted.forEach { d ->
                arr.put(JSONObject().apply {
                    put("path", d.path); put("bytes", d.bytes); put("human", humanBytes(d.bytes))
                    put("files", d.files); put("category", d.category); put("reason", d.reason)
                    put("freedBytes", d.freedBytes); put("freedHuman", humanBytes(d.freedBytes))
                    put("failed", d.failed)
                })
            }
        })
        put("totalBytes", report.totalBytes)
        put("totalHuman", humanBytes(report.totalBytes))
        put("totalFiles", report.totalFiles)
        put("freedBytes", report.freedBytes)
        put("freedHuman", humanBytes(report.freedBytes))
    }

    fun humanBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())

    /** Temp-suffixed files younger than this are never offered for cleanup. */
    const val TEMP_MIN_AGE_MS: Long = 60 * 60 * 1000L
}
