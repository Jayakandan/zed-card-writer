package com.zeddigital.cardwriter

/**
 * The on-card byte format. This file is the contract between this app and the
 * Teensy/VP3300 terminal - if you change anything here you must change the
 * matching constants in the Arduino sketch, or the terminal will stop reading
 * cards this app writes.
 *
 * Mifare Classic 1K layout:
 *   16 sectors x 4 blocks. Absolute block = sector * 4 + blockInSector.
 *   Block 3 of every sector is the SECTOR TRAILER (keys + access bits).
 *   Writing a trailer with data bricks that sector permanently - never do it.
 *
 * Sector 1 is used here:
 *   absolute block 4 (sector 1, block 0) = BALANCE
 *   absolute block 5 (sector 1, block 1) = NAME
 *   absolute block 6 (sector 1, block 2) = free
 *   absolute block 7 (sector 1, block 3) = TRAILER - never written
 *
 * BALANCE BLOCK (16 bytes)
 *   [0..3]   ASCII "BAL1"  - marks the block as one we initialised, so a
 *                            factory-blank block of zeros is never mistaken
 *                            for a balance of zero
 *   [4..7]   uint32 big-endian, the balance in CENTS (not dollars)
 *   [8..15]  zero
 *
 * Cents, not a float. $1.50 is stored as 150. Money is never held as a
 * floating point value on the card, because repeated debits would accumulate
 * rounding error over the card's life.
 *
 * NAME BLOCK (16 bytes)
 *   ASCII, NUL-padded, truncated to 16 characters.
 */
object CardFormat {

    const val SECTOR = 1
    const val BALANCE_BLOCK_IN_SECTOR = 0
    const val NAME_BLOCK_IN_SECTOR = 1

    const val BALANCE_BLOCK = SECTOR * 4 + BALANCE_BLOCK_IN_SECTOR   // 4
    const val NAME_BLOCK = SECTOR * 4 + NAME_BLOCK_IN_SECTOR         // 5

    const val NAME_MAX_LEN = 16

    val MAGIC = byteArrayOf('B'.code.toByte(), 'A'.code.toByte(), 'L'.code.toByte(), '1'.code.toByte())

    /**
     * Mifare Classic keys to try, in order. Same list as the Arduino sketch.
     * These are publicly documented vendor/transport defaults, not an attack -
     * a factory-blank card answers to the first one.
     */
    val CANDIDATE_KEYS: List<ByteArray> = listOf(
        byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
        byteArrayOf(0xA0.toByte(), 0xA1.toByte(), 0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte()),
        byteArrayOf(0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte()),
        byteArrayOf(0xB0.toByte(), 0xB1.toByte(), 0xB2.toByte(), 0xB3.toByte(), 0xB4.toByte(), 0xB5.toByte()),
        byteArrayOf(0x4D, 0x3A, 0x99.toByte(), 0xC3.toByte(), 0x51, 0xDD.toByte()),
        byteArrayOf(0x1A, 0x98.toByte(), 0x2C, 0x7E, 0x45, 0x9A.toByte()),
        byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte()),
        byteArrayOf(0x71, 0x4C, 0x5C, 0x88.toByte(), 0x6E, 0x97.toByte()),
        byteArrayOf(0x58, 0x7E, 0xE5.toByte(), 0xF9.toByte(), 0x35, 0x0F)
    )

    // ---- balance block ----------------------------------------------------

    fun encodeBalance(cents: Long): ByteArray {
        val b = ByteArray(16)
        System.arraycopy(MAGIC, 0, b, 0, 4)
        b[4] = ((cents shr 24) and 0xFF).toByte()
        b[5] = ((cents shr 16) and 0xFF).toByte()
        b[6] = ((cents shr 8) and 0xFF).toByte()
        b[7] = (cents and 0xFF).toByte()
        return b
    }

    /** Returns the balance in cents, or null if this block holds no balance we wrote. */
    fun decodeBalance(block: ByteArray?): Long? {
        if (block == null || block.size < 8) return null
        for (i in 0..3) if (block[i] != MAGIC[i]) return null
        return ((block[4].toLong() and 0xFF) shl 24) or
               ((block[5].toLong() and 0xFF) shl 16) or
               ((block[6].toLong() and 0xFF) shl 8) or
               (block[7].toLong() and 0xFF)
    }

    // ---- name block -------------------------------------------------------

    fun encodeName(name: String): ByteArray {
        val b = ByteArray(16)
        val src = name.take(NAME_MAX_LEN).toByteArray(Charsets.US_ASCII)
        System.arraycopy(src, 0, b, 0, src.size)
        return b
    }

    /** Returns the stored name, or null if the block looks blank. */
    fun decodeName(block: ByteArray?): String? {
        if (block == null || block.isEmpty()) return null
        if (block[0].toInt() == 0) return null
        val sb = StringBuilder()
        for (i in 0 until minOf(NAME_MAX_LEN, block.size)) {
            val c = block[i].toInt() and 0xFF
            if (c == 0) break
            if (c < 32 || c > 126) continue
            sb.append(c.toChar())
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    // ---- money helpers ----------------------------------------------------

    /**
     * Parses a dollar string ("12", "12.5", "12.50") into exact cents without
     * going through a float, so nothing rounds on the way in.
     * Returns null if the text is not a valid non-negative amount.
     */
    fun dollarsToCents(text: String): Long? {
        val t = text.trim().removePrefix("$").trim()
        if (t.isEmpty()) return null
        if (!Regex("^\\d+(\\.\\d{0,2})?$").matches(t)) return null
        val parts = t.split(".")
        val whole = parts[0].toLongOrNull() ?: return null
        var frac = 0L
        if (parts.size > 1 && parts[1].isNotEmpty()) {
            val f = parts[1].padEnd(2, '0')
            frac = f.toLongOrNull() ?: return null
        }
        return whole * 100 + frac
    }

    fun centsToDollars(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val a = kotlin.math.abs(cents)
        return String.format("%s%d.%02d", sign, a / 100, a % 100)
    }

    fun bytesToHex(b: ByteArray): String {
        val sb = StringBuilder()
        for (x in b) sb.append(String.format("%02X", x))
        return sb.toString()
    }
}
