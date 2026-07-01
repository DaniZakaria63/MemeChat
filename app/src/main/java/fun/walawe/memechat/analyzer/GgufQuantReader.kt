package `fun`.walawe.memechat.analyzer

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object GgufQuantReader {

    private val FILE_TYPE_LABELS = mapOf(
        2  to "Q4_0",
        3  to "Q4_1",
        7  to "Q8_0",
        8  to "Q5_0",
        9  to "Q5_1",
        10 to "Q2_K",
        11 to "Q3_K_S",
        12 to "Q3_K_M",
        13 to "Q3_K_L",
        14 to "Q4_K_S",
        15 to "Q4_K_M",
        16 to "Q5_K_S",
        17 to "Q5_K_M",
        18 to "Q6_K",
        19 to "IQ2_XXS",
        20 to "IQ2_XS",
        21 to "Q2_K_S",
        22 to "IQ3_XS",
        23 to "IQ3_XXS",
        24 to "IQ1_S",
        25 to "IQ4_NL",
        26 to "IQ3_S",
        27 to "IQ3_M",
        28 to "IQ2_S",
        29 to "IQ2_M",
        30 to "IQ4_XS",
        31 to "IQ1_M",
        32 to "BF16",
    )

    fun readFileType(path: String?): String? {
        if (path == null) return null
        return try {
            val file = RandomAccessFile(path, "r")
            val channel = file.channel
            val buf = ByteBuffer.allocate(64 * 1024)
            buf.order(ByteOrder.LITTLE_ENDIAN)
            channel.read(buf)
            buf.flip()
            channel.close()
            file.close()
            parse(buf)
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(buf: ByteBuffer): String? {
        val magic = ByteArray(4)
        buf.get(magic)
        if (magic[0] != 0x47.toByte() || magic[1] != 0x47.toByte() ||
            magic[2] != 0x55.toByte() || magic[3] != 0x46.toByte()) {
            return null
        }

        buf.getInt()
        buf.getLong()
        val kvCount = buf.getLong()

        for (i in 0 until kvCount) {
            val keyLen = buf.getLong()
            if (keyLen > buf.remaining()) return null
            val keyBytes = ByteArray(keyLen.toInt())
            buf.get(keyBytes)
            val key = String(keyBytes, Charsets.UTF_8)

            val valueType = buf.getInt()

            if (key == "general.file_type") {
                if (valueType == 4) {
                    val code = buf.getInt()
                    return FILE_TYPE_LABELS[code] ?: "Unknown($code)"
                }
                return null
            }

            when (valueType) {
                0 -> { buf.get(); }
                1 -> { buf.get(); }
                2 -> { buf.getShort(); }
                3 -> { buf.getShort(); }
                4 -> { buf.getInt(); }
                5 -> { buf.getInt(); }
                6 -> { buf.getFloat(); }
                7 -> { buf.get(); }
                8 -> {
                    val strLen = buf.getLong()
                    if (strLen > buf.remaining()) return null
                    buf.position(buf.position() + strLen.toInt())
                }
                9 -> {
                    val arrType = buf.getInt()
                    val arrLen = buf.getLong()
                    for (j in 0 until arrLen) {
                        skipValue(buf, arrType)
                    }
                }
                10 -> { buf.getLong(); }
                11 -> { buf.getLong(); }
                12 -> { buf.getDouble(); }
                else -> return null
            }
        }
        return null
    }

    private fun skipValue(buf: ByteBuffer, type: Int) {
        when (type) {
            0, 1, 7 -> buf.get()
            2, 3 -> buf.getShort()
            4, 5 -> buf.getInt()
            6 -> buf.getFloat()
            8 -> {
                val len = buf.getLong()
                if (len <= buf.remaining()) buf.position(buf.position() + len.toInt())
            }
            9 -> {
                val arrType = buf.getInt()
                val arrLen = buf.getLong()
                for (j in 0 until arrLen) skipValue(buf, arrType)
            }
            10, 11 -> buf.getLong()
            12 -> buf.getDouble()
        }
    }
}