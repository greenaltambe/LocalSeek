package com.augt.localseek.indexing

import android.os.Environment
import java.io.File
import java.io.IOException

/**
 * The single place that decides which folders are scanned for documents and which files they yield.
 *
 * Before this existed the roots included both `/storage/emulated/0/Download` and the literal `/sdcard/Download`
 * (the same folder through a symlink), so nearly every Download file was indexed twice under two paths.
 * Now every root is canonicalised, roots that resolve to the same folder are dropped, a root inside another root is
 * skipped, and a canonical file path is returned at most once per walk. The returned files carry their canonical
 * path, which is what the stored path and stableKey are derived from. A path that is already canonical (for
 * example `/storage/emulated/0/Documents/x`) is unchanged, so keys of files that were never duplicated stay the same.
 */
object ScanRoots {

    val EXCLUDED_DIR_NAMES = setOf(
        "node_modules", "build", "target", "out", "dist", "bin", "obj",
        "Android", "lost+found", ".git", ".cache", ".idea", ".github", ".gradle"
    )

    /** Android: the folders documents are searched in (Documents and Download, by their usual spellings). */
    fun defaultCandidates(): List<File> = listOf(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        File(Environment.getExternalStorageDirectory(), "Download"),
        File(Environment.getExternalStorageDirectory(), "Documents"),
        File("/sdcard/Download")
    )

    fun defaultRoots(): List<File> = canonicalRoots(defaultCandidates())

    /** Existing directories, canonical, de-duplicated, with roots nested inside another root removed. */
    fun canonicalRoots(candidates: List<File>): List<File> {
        val canonical = candidates
            .filter { it.exists() && it.isDirectory }
            .mapNotNull { canonicalOrNull(it) }
            .distinctBy { it.path }
        return canonical.filter { root ->
            canonical.none { other -> other.path != root.path && root.path.startsWith(other.path + File.separator) }
        }
    }

    /**
     * Walks [roots] (already canonical) and returns every file accepted by [accept] exactly once, as a canonical
     * [File]. Excluded and hidden directories are skipped; a directory reached twice (symlink loop or alias) is
     * entered once.
     */
    fun listFiles(roots: List<File>, accept: (File) -> Boolean): List<File> {
        val visitedDirs = HashSet<String>()
        val visitedFiles = HashSet<String>()
        val result = ArrayList<File>()
        for (root in roots) {
            root.walkTopDown()
                .onEnter { dir ->
                    val name = dir.name
                    if (name.startsWith(".") || EXCLUDED_DIR_NAMES.any { name.equals(it, ignoreCase = true) }) false
                    else canonicalOrNull(dir)?.let { visitedDirs.add(it.path) } ?: false
                }
                .filter { it.isFile && accept(it) }
                .forEach { file ->
                    val canonical = canonicalOrNull(file) ?: file
                    if (visitedFiles.add(canonical.path)) result.add(canonical)
                }
        }
        return result
    }

    private fun canonicalOrNull(file: File): File? = try {
        File(file.canonicalPath)
    } catch (_: IOException) {
        null
    }
}
