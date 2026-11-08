package com.proscenic.m7.protocol

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Pure-Kotlin implementation of raw LZ4 block decompression & compression
 * matching the Proscenic M7 Pro firmware's `LZ4_decompress_safe` / `LZ4_compress_default`
 * (`MAP.md` & `FUNC_MAP.md` §2.3).
 */
object Lz4Codec {

    /**
     * Decompresses a raw LZ4 block into a byte array of at most [maxOutputSize] bytes.
     * Matches `LZ4_decompress_safe(src, dst, srcLen, width * height)`.
     */
    fun decompressBlock(src: ByteArray, maxOutputSize: Int): ByteArray {
        val out = ByteArray(maxOutputSize)
        var sIdx = 0
        var dIdx = 0
        val sEnd = src.size

        while (sIdx < sEnd) {
            val token = src[sIdx++].toInt() and 0xFF

            // 1. Literal length
            var literalLength = token ushr 4
            if (literalLength == 15) {
                while (sIdx < sEnd) {
                    val extra = src[sIdx++].toInt() and 0xFF
                    literalLength += extra
                    if (extra != 255) break
                }
            }

            if (sIdx + literalLength > sEnd) {
                throw IllegalArgumentException("LZ4 literal length overflows input buffer")
            }
            if (dIdx + literalLength > maxOutputSize) {
                throw IllegalArgumentException("LZ4 literal length overflows output buffer ($maxOutputSize)")
            }

            System.arraycopy(src, sIdx, out, dIdx, literalLength)
            sIdx += literalLength
            dIdx += literalLength

            // The last sequence ends right after literals
            if (sIdx >= sEnd) break

            // 2. Match offset (16-bit little-endian)
            if (sIdx + 2 > sEnd) {
                throw IllegalArgumentException("LZ4 truncated match offset")
            }
            val offset = (src[sIdx].toInt() and 0xFF) or ((src[sIdx + 1].toInt() and 0xFF) shl 8)
            sIdx += 2
            if (offset <= 0 || offset > dIdx) {
                throw IllegalArgumentException("LZ4 invalid match offset: $offset at dst=$dIdx")
            }

            // 3. Match length (min match = 4)
            var matchLength = (token and 0x0F) + 4
            if (matchLength == 19) {
                while (sIdx < sEnd) {
                    val extra = src[sIdx++].toInt() and 0xFF
                    matchLength += extra
                    if (extra != 255) break
                }
            }

            if (dIdx + matchLength > maxOutputSize) {
                throw IllegalArgumentException("LZ4 match length overflows output buffer ($maxOutputSize)")
            }

            // Byte-by-byte copy to properly handle overlapping matches (RLE-style repeats)
            var matchSrc = dIdx - offset
            for (i in 0 until matchLength) {
                out[dIdx++] = out[matchSrc++]
            }
        }

        return if (dIdx == maxOutputSize) out else out.copyOf(dIdx)
    }

    /**
     * Compresses [input] into a valid raw LZ4 block (used by the built-in floorplan simulator
     * and round-trip unit tests). Uses run-length match encoding + literal blocks.
     */
    fun compressBlock(input: ByteArray): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val bos = ByteArrayOutputStream(input.size + 32)
        var pos = 0
        val end = input.size

        // Simple RLE/match LZ4 encoder (efficient for occupancy grids with long runs of 0x7F/0xFF/room IDs)
        var anchor = 0
        while (pos + 4 < end - 5) {
            // Check for a 4-byte match at offset 1 (run of identical bytes)
            val b = input[pos]
            if (pos > anchor && input[pos - 1] == b &&
                input[pos + 1] == b && input[pos + 2] == b && input[pos + 3] == b
            ) {
                val litLen = pos - anchor
                var matchLen = 4
                while (pos + matchLen < end - 5 && input[pos + matchLen] == b) {
                    matchLen++
                }
                writeSequence(bos, input, anchor, litLen, offset = 1, matchLen = matchLen)
                pos += matchLen
                anchor = pos
            } else {
                pos++
            }
        }

        // Trailing literals (LZ4 spec requires last 5 bytes to be literals)
        val remaining = end - anchor
        val litTok = if (remaining >= 15) 15 else remaining
        bos.write(litTok shl 4)
        if (remaining >= 15) {
            writeLength(bos, remaining - 15)
        }
        bos.write(input, anchor, remaining)
        return bos.toByteArray()
    }

    private fun writeSequence(
        bos: ByteArrayOutputStream,
        src: ByteArray,
        litStart: Int,
        litLen: Int,
        offset: Int,
        matchLen: Int
    ) {
        val litNib = if (litLen >= 15) 15 else litLen
        val matchMinus4 = matchLen - 4
        val matchNib = if (matchMinus4 >= 15) 15 else matchMinus4
        bos.write((litNib shl 4) or matchNib)
        if (litLen >= 15) writeLength(bos, litLen - 15)
        bos.write(src, litStart, litLen)
        bos.write(offset and 0xFF)
        bos.write((offset ushr 8) and 0xFF)
        if (matchMinus4 >= 15) writeLength(bos, matchMinus4 - 15)
    }

    private fun writeLength(bos: ByteArrayOutputStream, length: Int) {
        var rem = length
        while (rem >= 255) {
            bos.write(255)
            rem -= 255
        }
        bos.write(rem)
    }

    /**
     * Decodes Base64 string from `infoType 20002` `data.map` (handling URL-decoded spaces as `+`)
     * and decompresses the LZ4 block into `width * height` cells.
     */
    fun decodeMapBase64Lz4(base64Map: String, width: Int, height: Int): ByteArray {
        val sanitized = base64Map.trim().replace(' ', '+').replace("\n", "").replace("\r", "")
        val compressed = Base64.getDecoder().decode(sanitized)
        return decompressBlock(compressed, width * height)
    }

    fun encodeMapBase64Lz4(rawGrid: ByteArray): Pair<String, Int> {
        val lz4Bytes = compressBlock(rawGrid)
        val b64 = Base64.getEncoder().encodeToString(lz4Bytes)
        return b64 to lz4Bytes.size
    }
}
