package com.openminis.app

import java.io.File

/**
 * Locates production Kotlin sources from a unit test so a test can add a
 * SOURCE-GREP DRIFT GUARD next to a ported decision: when the decision logic
 * is entangled in ChatViewModel (Context + DB + provider to construct), the
 * test ports the algorithm verbatim and then asserts the production file still
 * carries the load-bearing lines. If production changes without the port, the
 * guard fails instead of the port silently drifting.
 *
 * Gradle runs `:app:testDebugUnitTest` with the module directory as the
 * working directory, so `src/main/java/...` resolves directly; the fallback
 * walk covers an IDE runner started from the repository root or a subdir.
 *
 * Only `src/main` is consulted — never `build/`, which can hold stale
 * generated copies of deleted classes.
 */
object ProductionSources {

    private const val MAIN_ROOT = "src/main/java/com/openminis/app"

    /**
     * [perf 2026-11-19] The Room layer and its models moved to the :data
     * module, so production sources live under two roots now. A path is
     * relative to com/openminis/app in BOTH, so "data/model/LLMModel.kt"
     * keeps working for the guards that read it.
     */
    private const val DATA_ROOT = "data/src/main/java/com/openminis/app"

    /** The `src/main/java/com/openminis/app` directory, or null if not found. */
    fun mainRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val direct = File(dir, MAIN_ROOT)
            if (direct.isDirectory) return direct
            val viaModule = File(dir, "src/android/app/$MAIN_ROOT")
            if (viaModule.isDirectory) return viaModule
            val viaAndroid = File(dir, "app/$MAIN_ROOT")
            if (viaAndroid.isDirectory) return viaAndroid
            dir = dir.parentFile
        }
        return null
    }

    /**
     * Read one production file by its path relative to `com/openminis/app`.
     *
     * Line endings are normalized to LF: the guards below match multi-line
     * snippets written with `\n`, while a Windows checkout (core.autocrlf)
     * stores CRLF. The repository's canonical form is LF, so this compares
     * against the same text CI sees.
     *
     * [perf 2026-11-19] Two roots are searched: the app module's, and the
     * :data module's (the Room layer moved there). A path is relative to
     * `com/openminis/app` in BOTH, so `data/model/LLMModel.kt` keeps working
     * for the guards that read it.
     */
    fun read(relPath: String): String {
        for (root in listOfNotNull(mainRoot(), dataMainRoot())) {
            val f = File(root, relPath)
            if (f.isFile) return f.readText().replace("\r\n", "\n")
        }
        error("production source missing: $relPath (searched ${mainRoot()} and ${dataMainRoot()})")
    }

    /**
     * The :data module's main root (`data/src/main/java/com/openminis/app`),
     * or null if not found.
     */
    fun dataMainRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (candidate in listOf(DATA_ROOT, "src/android/$DATA_ROOT")) {
                val f = File(dir, candidate)
                if (f.isDirectory) return f
            }
            dir = dir.parentFile
        }
        return null
    }

    /** Every `.kt` file under `src/main`, for whole-tree symbol scans. */
    fun allKotlinFiles(): List<File> =
        listOfNotNull(mainRoot(), dataMainRoot()).flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
}
