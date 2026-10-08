package com.bitchat.android.model

import com.bitchat.android.util.AppConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BitchatFilePacket: TLV-encoded file transfer payload for BLE mesh.
 * TLVs:
 *  - 0x01: filename (UTF-8)
 *  - 0x02: file size (8 bytes, UInt64)
 *  - 0x03: mime type (UTF-8)
 *  - 0x04: content (bytes) — may appear multiple times for large files
 *
 * Length field for TLV is 2 bytes (UInt16, big-endian) for all TLVs.
 * For large files, CONTENT is chunked into multiple TLVs of up to 65535 bytes each.
 *
 * Unknown TLV types are SKIPPED, not rejected: the tag list above is a floor,
 * not a ceiling, and a decoder that bails on the first tag it does not know
 * makes the format unextendable — the whole file is lost over a field that,
 * by construction, the sender considered optional. The iOS client has always
 * done this (`case nil: continue` in its own decoder), so rejecting here also
 * meant the two implementations disagreed about what a valid packet is.
 *
 * Note: The outer BitchatPacket uses version 2 (4-byte payload length), so this
 * TLV payload can exceed 64 KiB even though each TLV value is limited to 65535 bytes.
 * Transport-level fragmentation then splits the final packet for BLE MTU.
 */
data class BitchatFilePacket(
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val content: ByteArray
) {
    private enum class TLVType(val v: UByte) {
        FILE_NAME(0x01u), FILE_SIZE(0x02u), MIME_TYPE(0x03u), CONTENT(0x04u);
        companion object {
            fun from(value: UByte): TLVType? = when (value) {
                FILE_NAME.v -> FILE_NAME
                FILE_SIZE.v -> FILE_SIZE
                MIME_TYPE.v -> MIME_TYPE
                CONTENT.v -> CONTENT
                else -> null
            }
        }
    }

    fun encode(): ByteArray? {
        try {
            android.util.Log.d("BitchatFilePacket", "🔄 Encoding: name=$fileName, size=$fileSize, mime=$mimeType")
        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val mimeBytes = mimeType.toByteArray(Charsets.UTF_8)
        // Validate bounds for 2-byte TLV lengths (per-TLV). CONTENT may exceed 65535 and will be chunked.
        if (nameBytes.size > 0xFFFF || mimeBytes.size > 0xFFFF) {
                android.util.Log.e("BitchatFilePacket", "❌ TLV field too large: name=${nameBytes.size}, mime=${mimeBytes.size} (max: 65535)")
                return null
            }
            if (content.size > 0xFFFF) {
                android.util.Log.d("BitchatFilePacket", "📦 Content exceeds 65535 bytes (${content.size}); will be split into multiple CONTENT TLVs")
            } else {
                android.util.Log.d("BitchatFilePacket", "📏 TLV sizes OK: name=${nameBytes.size}, mime=${mimeBytes.size}, content=${content.size}")
            }
        val sizeFieldLen = 4 // UInt32 for FILE_SIZE (changed from 8 bytes)
        val contentLenFieldLen = 4 // UInt32 for CONTENT TLV as requested

        // Compute capacity: header TLVs + single CONTENT TLV with 4-byte length
        val contentTLVBytes = 1 + contentLenFieldLen + content.size
        val capacity = (1 + 2 + nameBytes.size) + (1 + 2 + sizeFieldLen) + (1 + 2 + mimeBytes.size) + contentTLVBytes
        val buf = ByteBuffer.allocate(capacity).order(ByteOrder.BIG_ENDIAN)

        // FILE_NAME
        buf.put(TLVType.FILE_NAME.v.toByte())
        buf.putShort(nameBytes.size.toShort())
        buf.put(nameBytes)

        // FILE_SIZE (4 bytes)
        buf.put(TLVType.FILE_SIZE.v.toByte())
        buf.putShort(sizeFieldLen.toShort())
        buf.putInt(fileSize.toInt())

        // MIME_TYPE
        buf.put(TLVType.MIME_TYPE.v.toByte())
        buf.putShort(mimeBytes.size.toShort())
        buf.put(mimeBytes)

        // CONTENT (single TLV with 4-byte length)
        buf.put(TLVType.CONTENT.v.toByte())
        buf.putInt(content.size)
        buf.put(content)

        val result = buf.array()
            android.util.Log.d("BitchatFilePacket", "✅ Encoded successfully: ${result.size} bytes total")
            return result
        } catch (e: Exception) {
            android.util.Log.e("BitchatFilePacket", "❌ Encoding failed: ${e.message}", e)
            return null
        }
    }

    companion object {
        private const val TAG = "BitchatFilePacket"
        private const val MAX_TLV2_LENGTH = 0xFFFF

        // encode() emits one CONTENT TLV; other encoders may chunk content
        // into 65535-byte TLVs. Allow exactly enough chunks for the largest
        // file we accept, so a packet cannot carry an unbounded number of
        // (possibly zero-length) CONTENT TLVs.
        private val MAX_CONTENT_TLVS: Int =
            ((AppConstants.Media.MAX_FILE_SIZE_BYTES + MAX_TLV2_LENGTH - 1) / MAX_TLV2_LENGTH).toInt()

        private inline fun forEachTLV(
            data: ByteArray,
            visit: (type: TLVType?, offset: Int, length: Int) -> Boolean
        ): Boolean {
            var off = 0
            while (off < data.size) {
                // Every TLV needs at least a type and a 2-byte length.
                // Reject a truncated trailing header instead of silently
                // accepting it, matching the iOS decoder.
                if (data.size - off < 3) return false
                // A null type is an unknown tag: read its length like any
                // other 2-byte TLV and skip its value, matching iOS.
                val t = TLVType.from(data[off].toUByte())
                off += 1
                // CONTENT uses 4-byte length; others use 2-byte length
                val len: Int
                if (t == TLVType.CONTENT) {
                    if (off + 4 > data.size) return false
                    len = ((data[off].toInt() and 0xFF) shl 24) or ((data[off + 1].toInt() and 0xFF) shl 16) or ((data[off + 2].toInt() and 0xFF) shl 8) or (data[off + 3].toInt() and 0xFF)
                    off += 4
                } else {
                    len = ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)
                    off += 2
                }
                if (len < 0 || len > data.size - off) return false
                if (!visit(t, off, len)) return false
                off += len
            }
            return true
        }

        fun decode(data: ByteArray): BitchatFilePacket? {
            try {
                // Pass 1: validate the framing and bound CONTENT before
                // allocating anything. Per-TLV work here is O(1) with no
                // copies or logging, because the TLV count is attacker-scaled
                // (a zero-length unknown TLV is 3 bytes).
                var contentTLVs = 0
                var contentTotal = 0L
                var skippedUnknownTLVs = 0
                val wellFormed = forEachTLV(data) { t, _, len ->
                    when (t) {
                        null -> { skippedUnknownTLVs += 1; true }
                        TLVType.FILE_SIZE -> len == 4
                        TLVType.CONTENT -> {
                            contentTLVs += 1
                            contentTotal += len
                            contentTLVs <= MAX_CONTENT_TLVS &&
                                contentTotal <= AppConstants.Media.MAX_FILE_SIZE_BYTES
                        }
                        else -> true
                    }
                }
                if (!wellFormed || contentTLVs == 0) return null

                // Pass 2: copy every CONTENT value once into a buffer sized
                // to the total, so decoding stays linear in the input size.
                val content = ByteArray(contentTotal.toInt())
                var contentOff = 0
                var name: String? = null
                var size: Long? = null
                var mime: String? = null
                forEachTLV(data) { t, off, len ->
                    when (t) {
                        TLVType.FILE_NAME -> name = String(data, off, len, Charsets.UTF_8)
                        TLVType.FILE_SIZE -> size = ByteBuffer.wrap(data, off, 4).order(ByteOrder.BIG_ENDIAN).int.toLong()
                        TLVType.MIME_TYPE -> mime = String(data, off, len, Charsets.UTF_8)
                        TLVType.CONTENT -> {
                            System.arraycopy(data, off, content, contentOff, len)
                            contentOff += len
                        }
                        null -> Unit
                    }
                    true
                }
                if (skippedUnknownTLVs > 0) {
                    android.util.Log.d(TAG, "⏭️ Skipped $skippedUnknownTLVs unknown TLV(s)")
                }
                val n = name ?: return null
                val s = size ?: content.size.toLong()
                val m = mime ?: "application/octet-stream"
                android.util.Log.d(TAG, "✅ Decoded: name=$n, size=$s, mime=$m, content=${content.size} bytes")
                return BitchatFilePacket(n, s, m, content)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "❌ Decoding failed: ${e.message}", e)
                return null
            }
        }
    }
}
