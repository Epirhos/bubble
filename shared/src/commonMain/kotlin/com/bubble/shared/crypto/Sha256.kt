package com.bubble.shared.crypto

/**
 * SHA-256 pur Kotlin (FIPS 180-4). En commonMain pour être identique sur les deux OS et
 * testable sur JVM avec les vecteurs NIST — pas de dépendance à java.security / CommonCrypto.
 */
object Sha256 {
    private val K = intArrayOf(
        0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
        -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
        -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
    )

    private const val BLOCK = 64

    fun hash(message: ByteArray): ByteArray {
        val h = intArrayOf(
            0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6,
            0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19,
        )
        val padded = pad(message)
        val w = IntArray(64)
        var offset = 0
        while (offset < padded.size) {
            for (i in 0 until 16) {
                w[i] = (padded[offset + i * 4].toInt() and 0xff shl 24) or
                    (padded[offset + i * 4 + 1].toInt() and 0xff shl 16) or
                    (padded[offset + i * 4 + 2].toInt() and 0xff shl 8) or
                    (padded[offset + i * 4 + 3].toInt() and 0xff)
            }
            for (i in 16 until 64) {
                val s0 = rotr(w[i - 15], 7) xor rotr(w[i - 15], 18) xor (w[i - 15] ushr 3)
                val s1 = rotr(w[i - 2], 17) xor rotr(w[i - 2], 19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (i in 0 until 64) {
                val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[i] + w[i]
                val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d
            h[4] += e; h[5] += f; h[6] += g; h[7] += hh
            offset += BLOCK
        }
        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[i * 4] = (h[i] ushr 24).toByte()
            out[i * 4 + 1] = (h[i] ushr 16).toByte()
            out[i * 4 + 2] = (h[i] ushr 8).toByte()
            out[i * 4 + 3] = h[i].toByte()
        }
        return out
    }

    private fun pad(message: ByteArray): ByteArray {
        val bitLen = message.size.toLong() * 8
        val padLen = ((56 - (message.size + 1) % BLOCK) + BLOCK) % BLOCK
        val result = ByteArray(message.size + 1 + padLen + 8)
        message.copyInto(result)
        result[message.size] = 0x80.toByte()
        for (i in 0 until 8) {
            result[result.size - 1 - i] = (bitLen ushr (8 * i)).toByte()
        }
        return result
    }

    private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))
}

/** HMAC-SHA256 (RFC 2104) et HKDF-SHA256 (RFC 5869), au-dessus de [Sha256]. */
object Hkdf {
    private const val BLOCK = 64
    private const val HASH_LEN = 32

    fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val k = if (key.size > BLOCK) Sha256.hash(key) else key
        val keyBlock = ByteArray(BLOCK)
        k.copyInto(keyBlock)
        val inner = ByteArray(BLOCK) { (keyBlock[it].toInt() xor 0x36).toByte() }
        val outer = ByteArray(BLOCK) { (keyBlock[it].toInt() xor 0x5c).toByte() }
        val innerHash = Sha256.hash(inner + message)
        return Sha256.hash(outer + innerHash)
    }

    /** Extrait-puis-étend : dérive [length] octets de clé du secret [ikm]. */
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, ikm)
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var generated = 0
        var counter = 1
        while (generated < length) {
            previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            val toCopy = minOf(previous.size, length - generated)
            previous.copyInto(output, generated, 0, toCopy)
            generated += toCopy
            counter++
        }
        return output
    }
}
