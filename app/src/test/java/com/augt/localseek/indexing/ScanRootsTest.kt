package com.augt.localseek.indexing

import com.augt.localseek.core.IdentityUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ScanRootsTest {

    private lateinit var base: File
    private lateinit var storage: File   // plays /storage/emulated/0
    private lateinit var alias: File     // plays /sdcard (a symlink to storage)

    @Before fun setUp() {
        base = Files.createTempDirectory("scanroots").toFile().canonicalFile
        storage = File(base, "storage").apply { mkdirs() }
        File(storage, "Download").mkdirs()
        File(storage, "Documents").mkdirs()
        alias = File(base, "sdcard")
        Files.createSymbolicLink(alias.toPath(), storage.toPath())
    }

    @After fun tearDown() { base.deleteRecursively() }

    private fun write(path: File, text: String = "x") { path.parentFile.mkdirs(); path.writeText(text) }
    private val txt: (File) -> Boolean = { it.extension == "txt" }

    @Test fun `same folder through a symlink is one root`() {
        val roots = ScanRoots.canonicalRoots(listOf(File(storage, "Download"), File(alias, "Download"), File(storage, "Documents")))
        assertEquals(listOf(File(storage, "Download").path, File(storage, "Documents").path), roots.map { it.path })
    }

    @Test fun `a root inside another root is skipped`() {
        val roots = ScanRoots.canonicalRoots(listOf(File(storage, "Documents"), File(storage, "Documents/Notes").apply { mkdirs() }))
        assertEquals(listOf(File(storage, "Documents").path), roots.map { it.path })
    }

    @Test fun `missing roots are dropped`() {
        assertTrue(ScanRoots.canonicalRoots(listOf(File(storage, "Nope"))).isEmpty())
    }

    @Test fun `a file reached through two roots is listed once with its canonical path`() {
        write(File(storage, "Download/a.txt"))
        val roots = ScanRoots.canonicalRoots(listOf(File(storage, "Download"), File(alias, "Download")))
        val files = ScanRoots.listFiles(roots, txt)
        assertEquals(1, files.size)
        assertEquals(File(storage, "Download/a.txt").path, files.single().path)
    }

    @Test fun `visited set also dedupes when roots were not canonicalised`() {
        write(File(storage, "Download/a.txt"))
        val files = ScanRoots.listFiles(listOf(File(storage, "Download"), File(alias, "Download")), txt)
        assertEquals(1, files.size)
    }

    @Test fun `a genuine second copy in a different folder is still indexed`() {
        write(File(storage, "Download/a.txt"), "same")
        write(File(storage, "Documents/a.txt"), "same")
        val roots = ScanRoots.canonicalRoots(listOf(File(storage, "Download"), File(alias, "Download"), File(storage, "Documents")))
        val files = ScanRoots.listFiles(roots, txt)
        assertEquals(2, files.size)
        assertEquals(2, files.map { it.path }.toSet().size)
    }

    @Test fun `a symlinked file inside a root resolves to its target and is not listed twice`() {
        write(File(storage, "Documents/real.txt"))
        Files.createSymbolicLink(File(storage, "Documents/link.txt").toPath(), File(storage, "Documents/real.txt").toPath())
        val files = ScanRoots.listFiles(ScanRoots.canonicalRoots(listOf(File(storage, "Documents"))), txt)
        assertEquals(1, files.size)
    }

    @Test fun `a symlink loop does not hang or duplicate`() {
        write(File(storage, "Documents/a.txt"))
        Files.createSymbolicLink(File(storage, "Documents/loop").toPath(), File(storage, "Documents").toPath())
        assertEquals(1, ScanRoots.listFiles(ScanRoots.canonicalRoots(listOf(File(storage, "Documents"))), txt).size)
    }

    @Test fun `excluded and hidden directories are skipped`() {
        write(File(storage, "Documents/node_modules/x.txt"))
        write(File(storage, "Documents/.hidden/x.txt"))
        write(File(storage, "Documents/ok/x.txt"))
        assertEquals(1, ScanRoots.listFiles(ScanRoots.canonicalRoots(listOf(File(storage, "Documents"))), txt).size)
    }

    @Test fun `stable key of an already canonical path is unchanged`() {
        write(File(storage, "Documents/k.txt"))
        val original = File(storage, "Documents/k.txt")
        val listed = ScanRoots.listFiles(ScanRoots.canonicalRoots(listOf(File(storage, "Documents"))), txt).single()
        assertEquals(IdentityUtils.fileStableKey(original.absolutePath), IdentityUtils.fileStableKey(listed.absolutePath))
    }

    @Test fun `the alias spelling and the canonical spelling get one key after canonicalisation`() {
        write(File(storage, "Download/k.txt"))
        val viaAlias = ScanRoots.listFiles(listOf(File(alias, "Download")), txt).single()
        val direct = ScanRoots.listFiles(listOf(File(storage, "Download")), txt).single()
        assertEquals(IdentityUtils.fileStableKey(direct.absolutePath), IdentityUtils.fileStableKey(viaAlias.absolutePath))
    }
}
