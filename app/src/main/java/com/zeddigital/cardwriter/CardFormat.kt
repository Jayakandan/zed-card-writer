package com.zeddigital.cardwriter

/**
 * Layout of sector 1 on the Mifare Classic card:
 *   block 4 = balance (4-byte magic tag + 4-byte big-endian cents)
 *   block 5 = cardholder name (ASCII, zero-padded, max 16 chars)
 *
 * Unchanged from the original app - only MainActivity's UI and flow changed.
 */
object CardFormat {

    const val SECTOR = 1
    const val BALANCE_BLOCK = 4
    const val NAME_BLOCK = 5
    const val NAME_MAX_LEN = 16

    val MAGIC = byteArrayOf(66, 65, 76, 49) // "BAL1"

    /** Common default/test Mifare Classic keys, tried in order until one authenticates. */
    val CANDIDATE_KEYS: List<ByteArray> = listOf(
        byteArrayOf(-1, -1, -1, -1, -1, -1),
        byteArrayOf(0, 0, 0, 0, 0, 0),
        byteArrayOf(-96, -95, -94, -93, -92, -91),
        byteArrayOf(-45, -9, -45, -9, -45, -9),
        byteArrayOf(-80, -79, -78, -77, -76, -75),
        byteArrayOf(77, 58, -103, -61, 81, -35),
        byteArrayOf(26, -104, 44, 126, 69, -102),
        byteArrayOf(-86, -69, -52, -35, -18, -1),
        byteArrayOf(113, 76, 92, -120, 110, -105),
        byteArrayOf(88, 126, -27, -7, 53, 15)
    )

    fun encodeBalance(cents: Long): ByteArray {
        val b = ByteArray(16)
        System.arraycopy(MAGIC, 0, b, 0, 4)
        b[4] = ((cents shr 24) and 0xFF).toByte()
        b[5] = ((cents shr 16) and 0xFF).toByte()
        b[6] = ((cents shr 8) and 0xFF).toByte()
        b[7] = (cents and 0xFF).toByte()
        return b
    }

    fun decodeBalance(block: ByteArray?): Long? {
        if (block == null || block.size < 8) return null
        for (i in 0 until 4) {
            if (block[i] != MAGIC[i]) return null
        }
        return (((block[4].toLong() and 0xFF) shl 24) or
                ((block[5].toLong() and 0xFF) shl 16) or
                ((block[6].toLong() and 0xFF) shl 8) or
                (block[7].toLong() and 0xFF))
    }

    fun encodeName(name: String): ByteArray {
        val b = ByteArray(16)
        val src = name.take(NAME_MAX_LEN).toByteArray(Charsets.US_ASCII)
        System.arraycopy(src, 0, b, 0, src.size)
        return b
    }

    fun decodeName(block: ByteArray?): String? {
        if (block == null || block.isEmpty() || block[0] == 0.toByte()) return null
        val sb = StringBuilder()
        for (i in 0 until minOf(NAME_MAX_LEN, block.size)) {
            val c = block[i].toInt() and 0xFF
            if (c == 0) break
            if (c in 32..126) sb.append(c.toChar())
        }
        return sb.toString().ifEmpty { null }
    }

    /** Parses "500", "12.50", "$12.5" etc. into whole cents. Returns null if not valid. */
    fun dollarsToCents(text: String): Long? {
        val t = text.trim().removePrefix("$").trim()
        if (t.isEmpty() || !Regex("^\\d+(\\.\\d{0,2})?$").matches(t)) return null
        val parts = t.split(".")
        val whole = parts[0].toLongOrNull() ?: return null
        var frac = 0L
        if (parts.size > 1 && parts[1].isNotEmpty()) {
            frac = parts[1].padEnd(2, '0').toLongOrNull() ?: return null
        }
        return whole * 100 + frac
    }

    fun centsToDollars(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val a = kotlin.math.abs(cents)
        return "%s%d.%02d".format(sign, a / 100, a % 100)
    }

    fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02X".format(it) }
}
