package com.openminis.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * P0 storage acceptance, pure parts: the category rules that tell a model
 * from a cache from temp residue, and the whitelist that refuses everything
 * that is not clearly regenerable.
 */
class PrivateStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(f: File, bytes: Int) {
        f.parentFile!!.mkdirs()
        f.writeBytes(ByteArray(bytes))
    }

    @Test
    fun `classification separates business model cache temp and environment`() {
        assertEquals(PrivateStorage.CAT_MODEL, PrivateStorage.classify("files/models/mnn-llm-0.3b/llm.mnn"))
        assertEquals(PrivateStorage.CAT_MODEL, PrivateStorage.classify("files/asr-sensevoice/model.onnx"))
        assertEquals(PrivateStorage.CAT_CACHE, PrivateStorage.classify("cache/image-cache/a.bin"))
        assertEquals(PrivateStorage.CAT_CACHE, PrivateStorage.classify("files/alpine-rootfs/root/.npm/_cacache/x"))
        assertEquals(PrivateStorage.CAT_CACHE, PrivateStorage.classify("anything", PrivateStorage.RootKind.EXTERNAL_CACHE))
        assertEquals(PrivateStorage.CAT_TEMP, PrivateStorage.classify("files/alpine-rootfs/tmp/foo"))
        assertEquals(PrivateStorage.CAT_TEMP, PrivateStorage.classify("files/downloads/movie.mp4.part"))
        assertEquals(PrivateStorage.CAT_ENVIRONMENT, PrivateStorage.classify("files/alpine-rootfs/usr/lib/libc.so"))
        assertEquals(PrivateStorage.CAT_BUSINESS, PrivateStorage.classify("files/minis-sessions/s1/workspace/a.md"))
        assertEquals(PrivateStorage.CAT_BUSINESS, PrivateStorage.classify("databases/app.db"))
        assertEquals(PrivateStorage.CAT_BUSINESS, PrivateStorage.classify("shared_prefs/prefs.xml"))
    }

    @Test
    fun `size walk counts real files and ignores symlinks`() {
        val root = tmp.newFolder("root")
        write(File(root, "a.bin"), 100)
        write(File(root, "sub/b.bin"), 50)
        val outside = File(tmp.root, "outside.bin").also { it.writeBytes(ByteArray(999)) }
        runCatching { Files.createSymbolicLink(File(root, "link.bin").toPath(), outside.toPath()) }
        val (bytes, files, isDir) = PrivateStorage.sizeOf(root.toPath())
        assertTrue(isDir)
        assertEquals(150L, bytes)
        assertEquals(2L, files)
    }

    @Test
    fun `cleanup accepts cache and temp but refuses business, models, traversal and roots`() {
        val dataDir = tmp.newFolder("data")
        val cache = File(dataDir, "cache").also { it.mkdirs() }
        write(File(cache, "x.bin"), 10)
        write(File(dataDir, "databases/app.db"), 20)
        write(File(dataDir, "files/models/llm.mnn"), 30)
        write(File(dataDir, "files/minis-sessions/s1/workspace/notes.md"), 40)
        val sandboxTmp = File(dataDir, "files/alpine-rootfs/tmp").also { it.mkdirs() }
        write(File(sandboxTmp, "junk"), 50)
        val extCache = tmp.newFolder("extcache")
        write(File(extCache, "img"), 60)

        val roots = listOf(
            "internal" to dataDir.canonicalFile,
            "externalCache" to extCache.canonicalFile,
        )
        fun verdict(path: File) = PrivateStorage.evaluateAgainst(path.absolutePath, roots)

        assertTrue(verdict(cache).allowed)                       // whole cache root
        assertTrue(verdict(File(cache, "x.bin")).allowed)        // cache subtree
        assertTrue(verdict(sandboxTmp).allowed)                  // sandbox tmp dir
        assertTrue(verdict(File(sandboxTmp, "junk")).allowed)    // inside sandbox tmp
        assertTrue(verdict(extCache).allowed)                    // external cache root

        val db = verdict(File(dataDir, "databases/app.db"))
        assertFalse(db.allowed)
        assertTrue(db.reason, db.reason.contains("业务数据"))
        assertFalse(verdict(File(dataDir, "files/models/llm.mnn")).allowed)
        assertFalse(verdict(File(dataDir, "files/minis-sessions/s1/workspace/notes.md")).allowed)
        assertFalse(verdict(dataDir).allowed)                    // never the private root
        assertFalse(verdict(File(dataDir, "..")).allowed)        // explicit traversal
        assertFalse(verdict(File(tmp.root, "not-app.txt")).allowed)
    }

    @Test
    fun `fresh temp file is held back, old one is accepted`() {
        val dataDir = tmp.newFolder("data2")
        val root = dataDir.canonicalFile
        val fresh = File(dataDir, "files/download.part").also {
            it.parentFile?.mkdirs()
            it.writeBytes(ByteArray(5))
        }
        val old = File(dataDir, "files/old.tmp").also {
            it.parentFile?.mkdirs()
            it.writeBytes(ByteArray(5))
            it.setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000L)
        }
        val roots = listOf("internal" to root)
        assertFalse(PrivateStorage.evaluateAgainst(fresh.absolutePath, roots, 60 * 60 * 1000L).allowed)
        assertTrue(PrivateStorage.evaluateAgainst(old.absolutePath, roots, 60 * 60 * 1000L).allowed)
    }

    @Test
    fun `scan aggregates buckets and identifies temp and cache candidates`() {
        val data = tmp.newFolder("scan-data")
        write(File(data, "files/models/llm.mnn"), 1000)
        write(File(data, "cache/a.bin"), 200)
        write(File(data, "files/alpine-rootfs/tmp/junk"), 300)
        write(File(data, "databases/app.db"), 400)
        write(File(data, "files/minis-sessions/s/notes.md"), 500)

        val result = PrivateStorage.scanDirs(
            specs = listOf(PrivateStorage.RootSpec("internal", data, PrivateStorage.RootKind.INTERNAL)),
            maxDepth = 5,
            maxEntries = 50,
            minBytes = 0,
            includeFiles = true,
        )
        assertEquals(2400L, result.totalBytes)
        assertEquals(5L, result.totalFiles)
        val buckets = result.buckets.associate { it.category to it.bytes }
        assertEquals(1000L, buckets[PrivateStorage.CAT_MODEL])
        assertEquals(200L, buckets[PrivateStorage.CAT_CACHE])
        assertEquals(300L, buckets[PrivateStorage.CAT_TEMP])
        assertEquals(900L, buckets[PrivateStorage.CAT_BUSINESS])
        assertTrue(result.tempCandidates.any { it.path.replace('\\', '/').endsWith("/tmp") && it.bytes == 300L })
        assertTrue(result.tempCandidates.any { it.path.replace('\\', '/').endsWith("/cache") && it.bytes == 200L })
        assertTrue(result.top.any { it.path.replace('\\', '/').endsWith("/llm.mnn") })
    }

    @Test
    fun `human bytes formatting`() {
        assertEquals("512 B", PrivateStorage.humanBytes(512))
        assertEquals("1.0 KB", PrivateStorage.humanBytes(1024))
        assertEquals("1.5 MB", PrivateStorage.humanBytes(1024L * 1024 + 512 * 1024))
    }
}
