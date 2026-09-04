package de.carstenkeller.videodownloader

/**
 * Reads an animated GIF's total playback length by summing every frame's delay time from its
 * Graphic Control Extension blocks (GIF89a spec, in 1/100s units) - the only place an actual
 * .gif file records how long it plays, since (unlike a video container) it has no separate
 * duration field. Needed because the minimum-length filter otherwise has nothing to check a
 * real .gif file against: BitmapFactory/ImageDecoder only ever expose the first frame, not the
 * animation's length. Pure parsing over already-downloaded bytes, no I/O.
 *
 * Returns null only when the length genuinely can't be determined - not a GIF, malformed, or
 * cut off before the trailer (the caller caps how much of a large file it downloads, so a very
 * large GIF can end up truncated). Returns 0 for a validly parsed GIF that has no delay data at
 * all (e.g. a single static frame) - a real, known answer, not "unknown".
 */
object GifDuration {

    fun parseMs(bytes: ByteArray): Long? {
        if (bytes.size < 13) return null
        val header = String(bytes, 0, 6, Charsets.US_ASCII)
        if (header != "GIF87a" && header != "GIF89a") return null

        // Logical Screen Descriptor: width(2) height(2) packed(1) bgColorIndex(1) pixelAspect(1)
        var pos = 13
        val packed = bytes[10].toInt() and 0xFF
        if (packed and 0x80 != 0) {
            pos += (2 shl (packed and 0x07)) * 3
        }

        var totalCentiseconds = 0L
        while (true) {
            if (pos >= bytes.size) return null // ran out before the trailer: truncated
            when (bytes[pos].toInt() and 0xFF) {
                0x21 -> { // Extension introducer
                    if (pos + 1 >= bytes.size) return null
                    val label = bytes[pos + 1].toInt() and 0xFF
                    pos += 2
                    if (label == 0xF9) {
                        // Graphic Control Extension: blockSize(1)=4 packed(1) delay(2 LE) transparentIndex(1) terminator(1)
                        if (pos + 3 >= bytes.size) return null
                        val delayLow = bytes[pos + 2].toInt() and 0xFF
                        val delayHigh = bytes[pos + 3].toInt() and 0xFF
                        totalCentiseconds += (delayHigh shl 8) or delayLow
                    }
                    pos = skipSubBlocks(bytes, pos) ?: return null
                }
                0x2C -> { // Image descriptor
                    if (pos + 9 >= bytes.size) return null
                    val imgPacked = bytes[pos + 9].toInt() and 0xFF
                    pos += 10
                    if (imgPacked and 0x80 != 0) {
                        pos += (2 shl (imgPacked and 0x07)) * 3
                    }
                    if (pos >= bytes.size) return null
                    pos += 1 // LZW minimum code size
                    pos = skipSubBlocks(bytes, pos) ?: return null
                }
                0x3B -> return totalCentiseconds * 10 // Trailer: fully parsed, answer is final
                else -> return null // Unexpected byte - stop rather than guess
            }
        }
    }

    /**
     * Skips a run of size-prefixed data sub-blocks, ending after the zero-length terminator.
     * Returns null if the blocks run past the end of [bytes] (truncated data).
     */
    private fun skipSubBlocks(bytes: ByteArray, start: Int): Int? {
        var pos = start
        while (true) {
            if (pos >= bytes.size) return null
            val blockSize = bytes[pos].toInt() and 0xFF
            pos += 1 + blockSize
            if (blockSize == 0) return pos
            if (pos > bytes.size) return null
        }
    }
}
