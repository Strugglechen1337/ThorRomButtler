package dev.thor.rombutler.domain.detection

/**
 * Minimal CHD-v5 header parser for system detection.
 *
 * Layout (big endian): magic "MComprHD" @0, length u32 @8, version u32 @12,
 * four compressor fourCCs @16..31, logicalBytes u64 @32.
 *
 * Heuristics:
 * - CD-type compressors (`cdlz`, `cdzl`, `cdfl`) => CD image
 *   (PS1 / Saturn / Dreamcast / PCE — PS1 is by far the most common).
 * - non-CD compressors with a DVD-sized payload => PS2 DVD image.
 */
object ChdHeader {

    private val MAGIC = "MComprHD".toByteArray(Charsets.US_ASCII)
    private const val MIN_HEADER = 40
    private val CD_COMPRESSORS = setOf("cdlz", "cdzl", "cdfl", "cdav")
    private const val DVD_MIN_BYTES = 1_500L * 1024 * 1024 // > CD capacity

    fun isChd(header: ByteArray): Boolean {
        if (header.size < MIN_HEADER) return false
        for (i in MAGIC.indices) {
            if (header[i] != MAGIC[i]) return false
        }
        return true
    }

    /** True for CHDs compressed with CD-specific codecs. */
    fun isCdChd(header: ByteArray): Boolean =
        isChd(header) && compressors(header).any { it in CD_COMPRESSORS }

    /** True for CHDs that look like DVD images (PS2). */
    fun isDvdChd(header: ByteArray): Boolean =
        isChd(header) && !isCdChd(header) && logicalBytes(header) >= DVD_MIN_BYTES

    private fun compressors(header: ByteArray): List<String> =
        (0 until 4).map { i ->
            String(header, 16 + i * 4, 4, Charsets.US_ASCII).lowercase()
        }

    private fun logicalBytes(header: ByteArray): Long {
        var value = 0L
        for (i in 32 until 40) {
            value = (value shl 8) or (header[i].toLong() and 0xFF)
        }
        return value
    }
}
