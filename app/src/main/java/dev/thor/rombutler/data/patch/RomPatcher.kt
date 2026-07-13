package dev.thor.rombutler.data.patch

import java.util.zip.CRC32

/** Patch could not be applied; [message] is user-facing (German). */
class PatchException(message: String) : Exception(message)

/** Supported patch container formats, detected via magic bytes. */
enum class PatchFormat(val extension: String) {
    IPS("ips"),
    UPS("ups"),
    BPS("bps"),
}

/**
 * Pure-Kotlin ROM patcher for the three formats used by the romhacking
 * and fan-translation scene. All work happens in memory: patches target
 * cartridge-era ROMs, and [MAX_INPUT_BYTES] guards against absurd inputs.
 *
 * UPS and BPS carry CRC32 checksums of source, target and patch — these
 * are verified so a wrong base ROM is reported clearly instead of
 * producing a silently broken game. IPS has no checksums (format
 * limitation); it is applied as-is.
 */
object RomPatcher {

    const val MAX_INPUT_BYTES = 128L * 1024 * 1024

    val PATCH_EXTENSIONS = PatchFormat.entries.map { it.extension }.toSet()

    /** Format by magic bytes, or null when the content is no known patch. */
    fun formatOf(patch: ByteArray): PatchFormat? = when {
        patch.startsWith("PATCH") -> PatchFormat.IPS
        patch.startsWith("UPS1") -> PatchFormat.UPS
        patch.startsWith("BPS1") -> PatchFormat.BPS
        else -> null
    }

    /** Applies [patch] to [source] and returns the patched ROM. */
    fun apply(patch: ByteArray, source: ByteArray): ByteArray {
        val format = formatOf(patch)
            ?: throw PatchException("Unbekanntes Patch-Format (kein IPS/UPS/BPS-Header)")
        return when (format) {
            PatchFormat.IPS -> applyIps(patch, source)
            PatchFormat.UPS -> applyUps(patch, source)
            PatchFormat.BPS -> applyBps(patch, source)
        }
    }

    // ---------------------------------------------------------------- IPS

    private fun applyIps(patch: ByteArray, source: ByteArray): ByteArray {
        var output = source.copyOf()
        var length = source.size
        var pos = 5
        while (true) {
            if (pos + 3 > patch.size) throw PatchException("IPS-Patch ist abgeschnitten")
            // "EOF" marker (0x45 0x4F 0x46) ends the record list
            if (patch[pos] == EOF_E && patch[pos + 1] == EOF_O && patch[pos + 2] == EOF_F) {
                pos += 3
                break
            }
            val offset = readUInt24(patch, pos)
            pos += 3
            if (pos + 2 > patch.size) throw PatchException("IPS-Patch ist abgeschnitten")
            val size = readUInt16(patch, pos)
            pos += 2

            val writeLength = if (size > 0) size else 0
            val (runLength, runValue) = if (size == 0) {
                // RLE record: 2-byte repeat count + 1-byte fill value
                if (pos + 3 > patch.size) throw PatchException("IPS-Patch ist abgeschnitten")
                val count = readUInt16(patch, pos)
                val value = patch[pos + 2]
                pos += 3
                count to value
            } else {
                if (pos + size > patch.size) throw PatchException("IPS-Patch ist abgeschnitten")
                0 to 0.toByte()
            }

            val end = offset + if (size > 0) writeLength else runLength
            if (end > MAX_INPUT_BYTES) throw PatchException("IPS-Patch schreibt außerhalb des Limits")
            if (end > output.size) output = output.copyOf(end)
            if (end > length) length = end

            if (size > 0) {
                System.arraycopy(patch, pos, output, offset, size)
                pos += size
            } else {
                output.fill(runValue, offset, offset + runLength)
            }
        }
        // Optional 3-byte truncate extension after EOF
        if (pos + 3 <= patch.size) {
            val truncated = readUInt24(patch, pos)
            if (truncated in 1 until length) length = truncated
        }
        return if (length == output.size) output else output.copyOf(length)
    }

    // ---------------------------------------------------------------- UPS

    private fun applyUps(patch: ByteArray, source: ByteArray): ByteArray {
        if (patch.size < 4 + 12) throw PatchException("UPS-Patch ist abgeschnitten")
        val storedSourceCrc = readLeUInt32(patch, patch.size - 12)
        val storedTargetCrc = readLeUInt32(patch, patch.size - 8)
        val storedPatchCrc = readLeUInt32(patch, patch.size - 4)
        if (crc32(patch, 0, patch.size - 4) != storedPatchCrc) {
            throw PatchException("UPS-Patch ist beschädigt (Prüfsummen-Fehler)")
        }

        val reader = NumberReader(patch, 4)
        val sourceSize = reader.next()
        val targetSize = reader.next()
        if (sourceSize > MAX_INPUT_BYTES || targetSize > MAX_INPUT_BYTES) {
            throw PatchException("UPS-Patch überschreitet das Größenlimit")
        }

        // UPS is symmetric: the same patch also reverts. Accept the ROM in
        // either direction, but never a ROM matching neither checksum.
        val sourceCrc = crc32(source, 0, source.size)
        val (outputSize, expectedOutputCrc) = when (sourceCrc) {
            storedSourceCrc -> targetSize.toInt() to storedTargetCrc
            storedTargetCrc -> sourceSize.toInt() to storedSourceCrc
            else -> throw PatchException(
                "ROM passt nicht zum Patch (andere Version oder falscher Dump?)",
            )
        }

        val output = ByteArray(outputSize)
        System.arraycopy(source, 0, output, 0, minOf(source.size, outputSize))

        var outPos = 0
        val end = patch.size - 12
        while (reader.position < end) {
            outPos += reader.next().toIntOrThrow()
            while (reader.position < end) {
                val value = patch[reader.position]
                reader.position++
                if (outPos < outputSize) {
                    val original = if (outPos < source.size) source[outPos] else 0
                    output[outPos] = (original.toInt() xor value.toInt()).toByte()
                }
                outPos++
                if (value == 0.toByte()) break
            }
        }

        if (crc32(output, 0, output.size) != expectedOutputCrc) {
            throw PatchException("Ergebnis-Prüfsumme falsch – Patch passt nicht zu dieser ROM")
        }
        return output
    }

    // ---------------------------------------------------------------- BPS

    private fun applyBps(patch: ByteArray, source: ByteArray): ByteArray {
        if (patch.size < 4 + 12) throw PatchException("BPS-Patch ist abgeschnitten")
        val storedSourceCrc = readLeUInt32(patch, patch.size - 12)
        val storedTargetCrc = readLeUInt32(patch, patch.size - 8)
        val storedPatchCrc = readLeUInt32(patch, patch.size - 4)
        if (crc32(patch, 0, patch.size - 4) != storedPatchCrc) {
            throw PatchException("BPS-Patch ist beschädigt (Prüfsummen-Fehler)")
        }
        if (crc32(source, 0, source.size) != storedSourceCrc) {
            throw PatchException("ROM passt nicht zum Patch (andere Version oder falscher Dump?)")
        }

        val reader = NumberReader(patch, 4)
        val sourceSize = reader.next()
        val targetSize = reader.next()
        if (targetSize > MAX_INPUT_BYTES || sourceSize > MAX_INPUT_BYTES) {
            throw PatchException("BPS-Patch überschreitet das Größenlimit")
        }
        if (sourceSize.toInt() != source.size) {
            throw PatchException("ROM-Größe passt nicht zum Patch")
        }
        val metadataSize = reader.next().toIntOrThrow()
        reader.position += metadataSize

        val target = ByteArray(targetSize.toInt())
        var outputOffset = 0
        var sourceRelativeOffset = 0
        var targetRelativeOffset = 0
        val end = patch.size - 12

        while (reader.position < end) {
            val data = reader.next()
            val command = (data and 3L).toInt()
            val length = ((data shr 2) + 1).toIntOrThrow()
            if (outputOffset + length > target.size) {
                throw PatchException("BPS-Patch schreibt außerhalb der Zieldatei")
            }
            when (command) {
                0 -> { // SourceRead
                    if (outputOffset + length > source.size) {
                        throw PatchException("BPS-Patch liest außerhalb der Quelldatei")
                    }
                    System.arraycopy(source, outputOffset, target, outputOffset, length)
                    outputOffset += length
                }

                1 -> { // TargetRead
                    if (reader.position + length > end) {
                        throw PatchException("BPS-Patch ist abgeschnitten")
                    }
                    System.arraycopy(patch, reader.position, target, outputOffset, length)
                    reader.position += length
                    outputOffset += length
                }

                2 -> { // SourceCopy
                    sourceRelativeOffset += reader.nextSignedOffset()
                    if (sourceRelativeOffset < 0 || sourceRelativeOffset + length > source.size) {
                        throw PatchException("BPS-Patch liest außerhalb der Quelldatei")
                    }
                    System.arraycopy(source, sourceRelativeOffset, target, outputOffset, length)
                    sourceRelativeOffset += length
                    outputOffset += length
                }

                else -> { // TargetCopy — byte-by-byte on purpose: ranges overlap
                    targetRelativeOffset += reader.nextSignedOffset()
                    if (targetRelativeOffset < 0 || targetRelativeOffset >= target.size) {
                        throw PatchException("BPS-Patch liest außerhalb der Zieldatei")
                    }
                    repeat(length) {
                        target[outputOffset++] = target[targetRelativeOffset++]
                    }
                }
            }
        }

        if (crc32(target, 0, target.size) != storedTargetCrc) {
            throw PatchException("Ergebnis-Prüfsumme falsch – Patch passt nicht zu dieser ROM")
        }
        return target
    }

    // ------------------------------------------------------------- helpers

    /** Variable-length number as used by both UPS and BPS ("beat" encoding). */
    private class NumberReader(private val bytes: ByteArray, var position: Int) {
        fun next(): Long {
            var data = 0L
            var shift = 1L
            while (true) {
                if (position >= bytes.size) throw PatchException("Patch ist abgeschnitten")
                val x = bytes[position].toInt() and 0xff
                position++
                data += (x and 0x7f).toLong() * shift
                if (x and 0x80 != 0) return data
                shift = shift shl 7
                data += shift
                if (data > MAX_INPUT_BYTES * 2) throw PatchException("Ungültige Zahl im Patch")
            }
        }

        /** BPS relative offsets: bit 0 = sign, remaining bits = distance. */
        fun nextSignedOffset(): Int {
            val raw = next()
            val distance = (raw shr 1).toIntOrThrow()
            return if (raw and 1L == 1L) -distance else distance
        }
    }

    private fun Long.toIntOrThrow(): Int {
        if (this < 0 || this > Int.MAX_VALUE) throw PatchException("Ungültige Zahl im Patch")
        return toInt()
    }

    private fun ByteArray.startsWith(magic: String): Boolean {
        if (size < magic.length) return false
        for (index in magic.indices) {
            if (this[index] != magic[index].code.toByte()) return false
        }
        return true
    }

    private fun readUInt24(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 16) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            (bytes[offset + 2].toInt() and 0xff)

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun readLeUInt32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun crc32(bytes: ByteArray, offset: Int, end: Int): Long {
        val crc = CRC32()
        crc.update(bytes, offset, end - offset)
        return crc.value
    }

    private const val EOF_E = 'E'.code.toByte()
    private const val EOF_O = 'O'.code.toByte()
    private const val EOF_F = 'F'.code.toByte()
}
