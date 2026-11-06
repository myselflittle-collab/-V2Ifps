package com.purnananda.framex

import com.purnananda.framex.engine.ZipStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.random.Random

class ZipStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun entries(n: Int, size: (Int) -> Int): List<ZipStore.Entry> {
        val dir = tmp.newFolder()
        return (1..n).map { i ->
            val bytes = Random(i).nextBytes(size(i))
            val f = File(dir, "$i.png").apply { writeBytes(bytes) }
            ZipStore.Entry("$i.png", f, CRC32().apply { update(bytes) }.value, bytes.size.toLong())
        }
    }

    private fun writeZip(es: List<ZipStore.Entry>): File {
        val z = tmp.newFile("out.zip")
        z.outputStream().use { ZipStore.write(es, it) }
        return z
    }

    @Test fun storedEntriesReadByJavaZip() {
        val es = entries(300) { 1000 + it * 7 }
        val z = writeZip(es)
        ZipFile(z).use { zf ->
            val names = zf.entries().toList().map { it.name }
            assertEquals((1..300).map { "$it.png" }, names)
            es.forEach { e ->
                val ze = zf.getEntry(e.name)
                assertEquals(ZipEntry.STORED, ze.method)
                assertArrayEquals(e.file.readBytes(), zf.getInputStream(ze).readBytes())
            }
        }
        RandomAccessFile(z, "r").use { assertEquals(300, ZipStore.verify(it.channel, es.map { e -> e.name })) }
    }

    @Test fun zip64WhenMoreThan65535Entries() {
        val es = entries(70_000) { 3 }
        val z = writeZip(es)
        ZipFile(z).use { zf -> assertEquals(70_000, zf.size()) }
        RandomAccessFile(z, "r").use { assertEquals(70_000, ZipStore.verify(it.channel, es.map { e -> e.name })) }
    }

    @Test fun verifyDetectsCorruption() {
        val es = entries(10) { 500 }
        val z = writeZip(es)
        RandomAccessFile(z, "rw").use { raf ->
            raf.seek(40 + 30 + 6 + 100) // inside the data of the first or second entry
            val b = raf.read(); raf.seek(raf.filePointer - 1); raf.write(b xor 0xFF)
        }
        try {
            RandomAccessFile(z, "r").use { ZipStore.verify(it.channel, es.map { e -> e.name }) }
            fail("corruption not detected")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("CRC"))
        }
    }

    @Test fun verifyDetectsWrongOrder() {
        val es = entries(3) { 10 }
        val z = writeZip(es)
        try {
            RandomAccessFile(z, "r").use { ZipStore.verify(it.channel, listOf("1.png", "3.png", "2.png")) }
            fail("wrong order not detected")
        } catch (e: IllegalStateException) { /* expected */ }
    }
}
