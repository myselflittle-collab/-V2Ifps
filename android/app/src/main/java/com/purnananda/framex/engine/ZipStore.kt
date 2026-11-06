package com.purnananda.framex.engine

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Calendar
import java.util.zip.CRC32

/**
 * Minimal ZIP writer/verifier for already-compressed images.
 *
 * Entries are STORED (no recompression), so the bytes inside the archive are exactly the
 * extracted image files. CRC-32 and sizes are known up front, so the archive streams to any
 * OutputStream (e.g. a MediaStore download) without seeking. ZIP64 records are added
 * automatically for archives over 4 GB or with more than 65,535 frames.
 */
object ZipStore {
    data class Entry(val name: String, val file: File, val crc32: Long, val size: Long)

    private const val LOCAL = 0x04034b50
    private const val CENTRAL = 0x02014b50
    private const val EOCD = 0x06054b50
    private const val EOCD64 = 0x06064b50
    private const val LOCATOR64 = 0x07064b50
    private const val U32 = 0xFFFFFFFFL
    private const val FLAG_UTF8 = 0x0800

    fun write(entries: List<Entry>, out: OutputStream, onEntry: (Int) -> Unit = {}, isCancelled: () -> Boolean = { false }) {
        val os = Counting(BufferedOutputStream(out, 1 shl 16))
        val (time, date) = dosNow()
        val offsets = LongArray(entries.size)
        val copyBuf = ByteArray(1 shl 16)

        entries.forEachIndexed { i, e ->
            if (isCancelled()) throw CancelledException()
            require(e.file.length() == e.size) { "${e.name} changed on disk" }
            val name = e.name.toByteArray(Charsets.UTF_8)
            val big = e.size >= U32
            offsets[i] = os.count
            val h = le(30 + name.size + if (big) 20 else 0)
            h.putInt(LOCAL).putShort(if (big) 45 else 20).putShort(FLAG_UTF8).putShort(0)
                .putShort(time).putShort(date).putInt(e.crc32.toInt())
                .putInt(if (big) -1 else e.size.toInt()).putInt(if (big) -1 else e.size.toInt())
                .putShort(name.size.toShort()).putShort(if (big) 20 else 0).put(name)
            if (big) h.putShort(1).putShort(16).putLong(e.size).putLong(e.size)
            os.write(h.array())
            FileInputStream(e.file).use { inp ->
                while (true) {
                    val n = inp.read(copyBuf)
                    if (n < 0) break
                    os.write(copyBuf, 0, n)
                }
            }
            onEntry(i + 1)
        }

        val cdStart = os.count
        entries.forEachIndexed { i, e ->
            val name = e.name.toByteArray(Charsets.UTF_8)
            val bigSize = e.size >= U32
            val bigOff = offsets[i] >= U32
            val extraLen = if (bigSize || bigOff) 4 + (if (bigSize) 16 else 0) + (if (bigOff) 8 else 0) else 0
            val zip64 = extraLen > 0
            val c = le(46 + name.size + extraLen)
            c.putInt(CENTRAL).putShort(if (zip64) 45 else 20).putShort(if (zip64) 45 else 20)
                .putShort(FLAG_UTF8).putShort(0).putShort(time).putShort(date).putInt(e.crc32.toInt())
                .putInt(if (bigSize) -1 else e.size.toInt()).putInt(if (bigSize) -1 else e.size.toInt())
                .putShort(name.size.toShort()).putShort(extraLen.toShort()).putShort(0)
                .putShort(0).putShort(0).putInt(0)
                .putInt(if (bigOff) -1 else offsets[i].toInt()).put(name)
            if (zip64) {
                c.putShort(1).putShort((extraLen - 4).toShort())
                if (bigSize) c.putLong(e.size).putLong(e.size)
                if (bigOff) c.putLong(offsets[i])
            }
            os.write(c.array())
        }
        val cdSize = os.count - cdStart
        val n = entries.size.toLong()
        val need64 = n >= 0xFFFF || cdStart >= U32 || cdSize >= U32
        if (need64) {
            val z = os.count
            os.write(le(56).putInt(EOCD64).putLong(44).putShort(45).putShort(45).putInt(0).putInt(0)
                .putLong(n).putLong(n).putLong(cdSize).putLong(cdStart).array())
            os.write(le(20).putInt(LOCATOR64).putInt(0).putLong(z).putInt(1).array())
        }
        val n16 = if (need64) 0xFFFF else n.toInt()
        os.write(le(22).putInt(EOCD).putShort(0).putShort(0).putShort(n16.toShort()).putShort(n16.toShort())
            .putInt(if (need64) -1 else cdSize.toInt()).putInt(if (need64) -1 else cdStart.toInt()).putShort(0).array())
        os.flush()
    }

    /**
     * Re-reads a written archive and checks it entry by entry: names and order, sizes, and the
     * CRC-32 of every byte of every file. Throws if anything is wrong; returns the entry count.
     */
    fun verify(channel: FileChannel, expectedNames: List<String>, onEntry: (Int) -> Unit = {}): Int {
        val len = channel.size()
        val tailLen = minOf(len, 22L + 65535L).toInt()
        val tail = read(channel, len - tailLen, tailLen)
        var eocd = -1
        for (p in tailLen - 22 downTo 0) if (tail.getInt(p) == EOCD) { eocd = p; break }
        check(eocd >= 0) { "ZIP end record not found" }
        var count = tail.getShort(eocd + 10).toInt() and 0xFFFF
        var cdSize = tail.getInt(eocd + 12).toLong() and U32
        var cdStart = tail.getInt(eocd + 16).toLong() and U32
        if (count == 0xFFFF || cdSize == U32 || cdStart == U32) {
            val locPos = len - tailLen + eocd - 20
            val loc = read(channel, locPos, 20)
            check(loc.getInt(0) == LOCATOR64) { "ZIP64 locator missing" }
            val rec = read(channel, loc.getLong(8), 56)
            check(rec.getInt(0) == EOCD64) { "ZIP64 end record missing" }
            count = rec.getLong(32).toInt(); cdSize = rec.getLong(40); cdStart = rec.getLong(48)
        }
        check(count == expectedNames.size) { "ZIP has $count entries, expected ${expectedNames.size}" }

        val cd = read(channel, cdStart, cdSize.toInt())
        var p = 0
        val crc = CRC32()
        val buf = ByteBuffer.allocate(1 shl 16)
        for (i in 0 until count) {
            check(cd.getInt(p) == CENTRAL) { "Bad central directory at entry ${i + 1}" }
            val method = cd.getShort(p + 10).toInt()
            val wantCrc = cd.getInt(p + 16).toLong() and U32
            var size = cd.getInt(p + 24).toLong() and U32
            val nameLen = cd.getShort(p + 28).toInt() and 0xFFFF
            val extraLen = cd.getShort(p + 30).toInt() and 0xFFFF
            val commentLen = cd.getShort(p + 32).toInt() and 0xFFFF
            var offset = cd.getInt(p + 42).toLong() and U32
            val nameBytes = ByteArray(nameLen).also { cd.position(p + 46); cd.get(it) }
            val name = String(nameBytes, Charsets.UTF_8)
            if (size == U32 || offset == U32) {
                var q = p + 46 + nameLen
                val endExtra = q + extraLen
                while (q + 4 <= endExtra) {
                    val id = cd.getShort(q).toInt() and 0xFFFF
                    val sz = cd.getShort(q + 2).toInt() and 0xFFFF
                    if (id == 1) {
                        var r = q + 4
                        if (size == U32) { size = cd.getLong(r); r += 16 }
                        if (offset == U32) { offset = cd.getLong(r) }
                    }
                    q += 4 + sz
                }
            }
            check(name == expectedNames[i]) { "Entry ${i + 1} is $name, expected ${expectedNames[i]}" }
            check(method == 0) { "$name is compressed; expected stored" }

            val lh = read(channel, offset, 30)
            check(lh.getInt(0) == LOCAL) { "Bad local header for $name" }
            val dataStart = offset + 30 + (lh.getShort(26).toInt() and 0xFFFF) + (lh.getShort(28).toInt() and 0xFFFF)
            crc.reset()
            var pos = dataStart
            var left = size
            while (left > 0) {
                buf.clear()
                buf.limit(minOf(buf.capacity().toLong(), left).toInt())
                val n = channel.read(buf, pos)
                check(n > 0) { "$name is truncated" }
                crc.update(buf.array(), 0, n)
                pos += n; left -= n
            }
            check(crc.value == wantCrc) { "CRC mismatch in $name" }
            onEntry(i + 1)
            p += 46 + nameLen + extraLen + commentLen
        }
        return count
    }

    private fun read(ch: FileChannel, pos: Long, n: Int): ByteBuffer {
        val b = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        var off = pos
        while (b.hasRemaining()) {
            val r = ch.read(b, off)
            check(r > 0) { "Unexpected end of ZIP" }
            off += r
        }
        b.flip()
        return b
    }

    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun ByteBuffer.putShort(v: Int): ByteBuffer = putShort(v.toShort())

    private fun dosNow(): Pair<Int, Int> {
        val c = Calendar.getInstance()
        val time = (c.get(Calendar.HOUR_OF_DAY) shl 11) or (c.get(Calendar.MINUTE) shl 5) or (c.get(Calendar.SECOND) / 2)
        val date = ((c.get(Calendar.YEAR) - 1980) shl 9) or ((c.get(Calendar.MONTH) + 1) shl 5) or c.get(Calendar.DAY_OF_MONTH)
        return time to date
    }

    private class Counting(private val out: OutputStream) : OutputStream() {
        var count = 0L
            private set
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }
}
