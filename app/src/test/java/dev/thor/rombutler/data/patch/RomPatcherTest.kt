package dev.thor.rombutler.data.patch

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.zip.CRC32

class RomPatcherTest {

    // ---------------------------------------------------------------- IPS

    @Test
    fun `ips applies plain and rle records and grows the file`() {
        val source = ByteArray(8)
        val patch = ipsPatch {
            // plain record: offset 2, payload "AB"
            add(0, 0, 2, 0, 2, 'A'.code, 'B'.code)
            // RLE record: offset 6, four 0xFF bytes (grows file to 10)
            add(0, 0, 6, 0, 0, 0, 4, 0xFF)
        }

        val output = RomPatcher.apply(patch, source)

        assertThat(output.size).isEqualTo(10)
        assertThat(output[2]).isEqualTo('A'.code.toByte())
        assertThat(output[3]).isEqualTo('B'.code.toByte())
        assertThat(output.copyOfRange(6, 10)).isEqualTo(byteArrayOf(-1, -1, -1, -1))
    }

    @Test
    fun `ips truncate extension shortens the output`() {
        val source = ByteArray(8) { it.toByte() }
        val patch = ipsPatch(truncateTo = 3) {
            add(0, 0, 0, 0, 1, 0x42)
        }

        val output = RomPatcher.apply(patch, source)

        assertThat(output.size).isEqualTo(3)
        assertThat(output[0]).isEqualTo(0x42.toByte())
    }

    @Test
    fun `truncated ips is rejected`() {
        val patch = byteArrayOf(
            'P'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte(),
            'C'.code.toByte(), 'H'.code.toByte(), 0, 0,
        )
        assertThrows(PatchException::class.java) { RomPatcher.apply(patch, ByteArray(4)) }
    }

    // ---------------------------------------------------------------- UPS

    @Test
    fun `ups patches forward and backward via checksums`() {
        val source = byteArrayOf(1, 2, 3, 4)
        val target = byteArrayOf(1, 9, 3, 4, 7)
        val patch = upsPatch(source, target) {
            addNumber(1) // skip to offset 1
            addRaw(2 xor 9, 0) // xor run + terminator
            addNumber(1) // relative skip (pointer is at 3 after the run)
            addRaw(7, 0) // new byte at offset 4
        }

        assertThat(RomPatcher.apply(patch, source)).isEqualTo(target)
        // UPS is symmetric: the same patch reverts the patched ROM
        assertThat(RomPatcher.apply(patch, target)).isEqualTo(source)
    }

    @Test
    fun `ups rejects a rom matching neither checksum`() {
        val source = byteArrayOf(1, 2, 3, 4)
        val target = byteArrayOf(1, 9, 3, 4)
        val patch = upsPatch(source, target) {
            addNumber(1)
            addRaw(2 xor 9, 0)
        }

        val error = assertThrows(PatchException::class.java) {
            RomPatcher.apply(patch, byteArrayOf(9, 9, 9, 9))
        }
        assertThat(error.message).contains("passt nicht")
    }

    @Test
    fun `corrupted ups is rejected before any work`() {
        val source = byteArrayOf(1, 2, 3, 4)
        val patch = upsPatch(source, source) { addNumber(0); addRaw(0) }
        patch[6] = (patch[6].toInt() xor 0xFF).toByte() // flip a body byte

        assertThrows(PatchException::class.java) { RomPatcher.apply(patch, source) }
    }

    // ---------------------------------------------------------------- BPS

    @Test
    fun `bps applies source read, target read and overlapping target copy`() {
        val source = "ABCDEFGH".toByteArray()
        val target = "ABCDXYXYXYXY".toByteArray()
        val patch = bpsPatch(source, target) {
            addNumber(((4 - 1) shl 2 or 0).toLong()) // SourceRead 4
            addNumber(((2 - 1) shl 2 or 1).toLong()) // TargetRead 2
            addRaw('X'.code, 'Y'.code)
            addNumber(((6 - 1) shl 2 or 3).toLong()) // TargetCopy 6
            addNumber((4 shl 1).toLong()) // relative offset +4 (overlaps)
        }

        assertThat(RomPatcher.apply(patch, source)).isEqualTo(target)
    }

    @Test
    fun `bps source copy reads from arbitrary source offset`() {
        val source = "ABCDEFGH".toByteArray()
        val target = "CDEF".toByteArray()
        val patch = bpsPatch(source, target) {
            addNumber(((4 - 1) shl 2 or 2).toLong()) // SourceCopy 4
            addNumber((2 shl 1).toLong()) // source offset +2
        }

        assertThat(RomPatcher.apply(patch, source)).isEqualTo(target)
    }

    @Test
    fun `bps rejects the wrong base rom`() {
        val source = "ABCDEFGH".toByteArray()
        val target = "ABCD".toByteArray()
        val patch = bpsPatch(source, target) {
            addNumber(((4 - 1) shl 2 or 0).toLong())
        }

        val error = assertThrows(PatchException::class.java) {
            RomPatcher.apply(patch, "AACDEFGH".toByteArray())
        }
        assertThat(error.message).contains("passt nicht")
    }

    @Test
    fun `unknown format is rejected`() {
        assertThrows(PatchException::class.java) {
            RomPatcher.apply(byteArrayOf(1, 2, 3, 4, 5, 6), ByteArray(4))
        }
    }

    // ------------------------------------------------------------- helpers

    private class BodyBuilder {
        val bytes = mutableListOf<Byte>()

        fun addRaw(vararg values: Int) {
            values.forEach { bytes.add(it.toByte()) }
        }

        /** "beat" variable-length number used by UPS and BPS. */
        fun addNumber(value: Long) {
            var data = value
            while (true) {
                val x = (data and 0x7f).toInt()
                data = data shr 7
                if (data == 0L) {
                    bytes.add((0x80 or x).toByte())
                    break
                }
                bytes.add(x.toByte())
                data--
            }
        }
    }

    private fun ipsPatch(truncateTo: Int? = null, records: BodyBuilder.() -> Unit): ByteArray {
        val body = BodyBuilder().apply {
            addRaw('P'.code, 'A'.code, 'T'.code, 'C'.code, 'H'.code)
            records()
            addRaw('E'.code, 'O'.code, 'F'.code)
            truncateTo?.let { addRaw(it shr 16 and 0xff, it shr 8 and 0xff, it and 0xff) }
        }
        return body.bytes.toByteArray()
    }

    private fun BodyBuilder.add(vararg values: Int) = addRaw(*values)

    private fun upsPatch(source: ByteArray, target: ByteArray, body: BodyBuilder.() -> Unit): ByteArray =
        beatContainer("UPS1", source, target) {
            addNumber(source.size.toLong())
            addNumber(target.size.toLong())
            body()
        }

    private fun bpsPatch(source: ByteArray, target: ByteArray, body: BodyBuilder.() -> Unit): ByteArray =
        beatContainer("BPS1", source, target) {
            addNumber(source.size.toLong())
            addNumber(target.size.toLong())
            addNumber(0) // no metadata
            body()
        }

    private fun beatContainer(
        magic: String,
        source: ByteArray,
        target: ByteArray,
        body: BodyBuilder.() -> Unit,
    ): ByteArray {
        val builder = BodyBuilder()
        magic.forEach { builder.bytes.add(it.code.toByte()) }
        builder.body()
        appendLeCrc(builder, crc32(source))
        appendLeCrc(builder, crc32(target))
        appendLeCrc(builder, crc32(builder.bytes.toByteArray()))
        return builder.bytes.toByteArray()
    }

    private fun appendLeCrc(builder: BodyBuilder, crc: Long) {
        builder.addRaw(
            (crc and 0xff).toInt(),
            (crc shr 8 and 0xff).toInt(),
            (crc shr 16 and 0xff).toInt(),
            (crc shr 24 and 0xff).toInt(),
        )
    }

    private fun crc32(bytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }
}
