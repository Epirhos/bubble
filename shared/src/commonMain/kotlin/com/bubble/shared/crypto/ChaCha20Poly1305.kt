package com.bubble.shared.crypto

/**
 * AEAD ChaCha20-Poly1305 (RFC 8439) en Kotlin pur — chiffrement authentifié des payloads
 * E2EE. Identique sur les deux OS, testable sur JVM avec les vecteurs officiels RFC 8439.
 *
 * ChaCha20 (arithmétique 32 bits via Int) + Poly1305 (MAC, limbes 26 bits via Long).
 * Confidentialité + intégrité : un octet modifié ⇒ [open] retourne null (tag invalide).
 */
object ChaCha20Poly1305 {
    const val KEY_SIZE = 32
    const val NONCE_SIZE = 12
    const val TAG_SIZE = 16

    /** Chiffre puis authentifie : retourne `ciphertext || tag(16)`. */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(key.size == KEY_SIZE && nonce.size == NONCE_SIZE)
        val otk = ChaCha20.block(key, 0, nonce).copyOf(32)
        val ciphertext = ChaCha20.xor(key, 1, nonce, plaintext)
        val tag = Poly1305.mac(macData(aad, ciphertext), otk)
        return ciphertext + tag
    }

    /** Vérifie le tag puis déchiffre. Retourne null si l'authentification échoue. */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? {
        if (key.size != KEY_SIZE || nonce.size != NONCE_SIZE || sealed.size < TAG_SIZE) return null
        val ciphertext = sealed.copyOfRange(0, sealed.size - TAG_SIZE)
        val tag = sealed.copyOfRange(sealed.size - TAG_SIZE, sealed.size)
        val otk = ChaCha20.block(key, 0, nonce).copyOf(32)
        val expected = Poly1305.mac(macData(aad, ciphertext), otk)
        if (!constantTimeEquals(tag, expected)) return null
        return ChaCha20.xor(key, 1, nonce, ciphertext)
    }

    private fun macData(aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val out = ArrayList<Byte>(aad.size + ciphertext.size + 32)
        out.addAll(aad.toList()); pad16(out, aad.size)
        out.addAll(ciphertext.toList()); pad16(out, ciphertext.size)
        appendU64Le(out, aad.size.toLong())
        appendU64Le(out, ciphertext.size.toLong())
        return out.toByteArray()
    }

    private fun pad16(out: MutableList<Byte>, length: Int) {
        val rem = length % 16
        if (rem != 0) repeat(16 - rem) { out.add(0) }
    }

    private fun appendU64Le(out: MutableList<Byte>, value: Long) {
        for (i in 0 until 8) out.add((value ushr (8 * i)).toByte())
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}

private object ChaCha20 {
    private val CONSTANTS = intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574)

    /** Bloc de keystream de 64 octets pour un compteur donné. */
    fun block(key: ByteArray, counter: Int, nonce: ByteArray): ByteArray {
        val state = IntArray(16)
        CONSTANTS.copyInto(state)
        for (i in 0 until 8) state[4 + i] = leInt(key, i * 4)
        state[12] = counter
        for (i in 0 until 3) state[13 + i] = leInt(nonce, i * 4)

        val w = state.copyOf()
        repeat(10) {
            quarterRound(w, 0, 4, 8, 12); quarterRound(w, 1, 5, 9, 13)
            quarterRound(w, 2, 6, 10, 14); quarterRound(w, 3, 7, 11, 15)
            quarterRound(w, 0, 5, 10, 15); quarterRound(w, 1, 6, 11, 12)
            quarterRound(w, 2, 7, 8, 13); quarterRound(w, 3, 4, 9, 14)
        }
        val out = ByteArray(64)
        for (i in 0 until 16) {
            val v = w[i] + state[i]
            out[i * 4] = v.toByte()
            out[i * 4 + 1] = (v ushr 8).toByte()
            out[i * 4 + 2] = (v ushr 16).toByte()
            out[i * 4 + 3] = (v ushr 24).toByte()
        }
        return out
    }

    fun xor(key: ByteArray, counter: Int, nonce: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var blockCounter = counter
        var offset = 0
        while (offset < data.size) {
            val ks = block(key, blockCounter, nonce)
            val n = minOf(64, data.size - offset)
            for (i in 0 until n) out[offset + i] = (data[offset + i].toInt() xor ks[i].toInt()).toByte()
            offset += 64
            blockCounter++
        }
        return out
    }

    private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 7)
    }

    private fun rotl(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))

    private fun leInt(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xff) or
            ((b[i + 1].toInt() and 0xff) shl 8) or
            ((b[i + 2].toInt() and 0xff) shl 16) or
            ((b[i + 3].toInt() and 0xff) shl 24)
}

/** Poly1305 (RFC 8439 §2.5), port poly1305-donna 32 bits, limbes 26 bits sur Long. */
private object Poly1305 {
    fun mac(message: ByteArray, key: ByteArray): ByteArray {
        val r = LongArray(5)
        val h = LongArray(5)
        val pad = LongArray(4)
        r[0] = le32(key, 0) and 0x3ffffff
        r[1] = (le32(key, 3) ushr 2) and 0x3ffff03
        r[2] = (le32(key, 6) ushr 4) and 0x3ffc0ff
        r[3] = (le32(key, 9) ushr 6) and 0x3f03fff
        r[4] = (le32(key, 12) ushr 8) and 0x00fffff
        for (i in 0 until 4) pad[i] = le32(key, 16 + i * 4)

        val s1 = r[1] * 5; val s2 = r[2] * 5; val s3 = r[3] * 5; val s4 = r[4] * 5

        var offset = 0
        val full = message.size - (message.size % 16)
        while (offset < full) {
            absorb(h, r, s1, s2, s3, s4, message, offset, hibit = 1L shl 24)
            offset += 16
        }
        val rem = message.size % 16
        if (rem > 0) {
            val block = ByteArray(16)
            message.copyInto(block, 0, offset, message.size)
            block[rem] = 1
            absorb(h, r, s1, s2, s3, s4, block, 0, hibit = 0L)
        }

        var h0 = h[0]; var h1 = h[1]; var h2 = h[2]; var h3 = h[3]; var h4 = h[4]
        var c = h1 ushr 26; h1 = h1 and 0x3ffffff
        h2 += c; c = h2 ushr 26; h2 = h2 and 0x3ffffff
        h3 += c; c = h3 ushr 26; h3 = h3 and 0x3ffffff
        h4 += c; c = h4 ushr 26; h4 = h4 and 0x3ffffff
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and 0x3ffffff
        h1 += c

        var g0 = h0 + 5; c = g0 ushr 26; g0 = g0 and 0x3ffffff
        var g1 = h1 + c; c = g1 ushr 26; g1 = g1 and 0x3ffffff
        var g2 = h2 + c; c = g2 ushr 26; g2 = g2 and 0x3ffffff
        var g3 = h3 + c; c = g3 ushr 26; g3 = g3 and 0x3ffffff
        var g4 = h4 + c - (1L shl 26)

        val mask = if (g4 < 0) 0L else -1L
        g0 = g0 and mask; g1 = g1 and mask; g2 = g2 and mask; g3 = g3 and mask; g4 = g4 and mask
        val nmask = mask.inv()
        h0 = (h0 and nmask) or g0
        h1 = (h1 and nmask) or g1
        h2 = (h2 and nmask) or g2
        h3 = (h3 and nmask) or g3
        h4 = (h4 and nmask) or g4

        var f0 = ((h0) or (h1 shl 26)) and 0xffffffffL
        var f1 = ((h1 ushr 6) or (h2 shl 20)) and 0xffffffffL
        var f2 = ((h2 ushr 12) or (h3 shl 14)) and 0xffffffffL
        var f3 = ((h3 ushr 18) or (h4 shl 8)) and 0xffffffffL

        var f = f0 + pad[0]; f0 = f and 0xffffffffL
        f = f1 + pad[1] + (f ushr 32); f1 = f and 0xffffffffL
        f = f2 + pad[2] + (f ushr 32); f2 = f and 0xffffffffL
        f = f3 + pad[3] + (f ushr 32); f3 = f and 0xffffffffL

        val mac = ByteArray(16)
        writeLe32(mac, 0, f0); writeLe32(mac, 4, f1); writeLe32(mac, 8, f2); writeLe32(mac, 12, f3)
        return mac
    }

    private fun absorb(h: LongArray, r: LongArray, s1: Long, s2: Long, s3: Long, s4: Long, m: ByteArray, o: Int, hibit: Long) {
        var h0 = h[0] + (le32(m, o) and 0x3ffffff)
        var h1 = h[1] + ((le32(m, o + 3) ushr 2) and 0x3ffffff)
        var h2 = h[2] + ((le32(m, o + 6) ushr 4) and 0x3ffffff)
        var h3 = h[3] + ((le32(m, o + 9) ushr 6) and 0x3ffffff)
        var h4 = h[4] + ((le32(m, o + 12) ushr 8) or hibit)

        val d0 = h0 * r[0] + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
        var d1 = h0 * r[1] + h1 * r[0] + h2 * s4 + h3 * s3 + h4 * s2
        var d2 = h0 * r[2] + h1 * r[1] + h2 * r[0] + h3 * s4 + h4 * s3
        var d3 = h0 * r[3] + h1 * r[2] + h2 * r[1] + h3 * r[0] + h4 * s4
        var d4 = h0 * r[4] + h1 * r[3] + h2 * r[2] + h3 * r[1] + h4 * r[0]

        var c = d0 ushr 26; h0 = d0 and 0x3ffffff
        d1 += c; c = d1 ushr 26; h1 = d1 and 0x3ffffff
        d2 += c; c = d2 ushr 26; h2 = d2 and 0x3ffffff
        d3 += c; c = d3 ushr 26; h3 = d3 and 0x3ffffff
        d4 += c; c = d4 ushr 26; h4 = d4 and 0x3ffffff
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and 0x3ffffff
        h1 += c

        h[0] = h0; h[1] = h1; h[2] = h2; h[3] = h3; h[4] = h4
    }

    private fun le32(b: ByteArray, i: Int): Long =
        (b[i].toLong() and 0xff) or
            ((b[i + 1].toLong() and 0xff) shl 8) or
            ((b[i + 2].toLong() and 0xff) shl 16) or
            ((b[i + 3].toLong() and 0xff) shl 24)

    private fun writeLe32(b: ByteArray, i: Int, v: Long) {
        b[i] = v.toByte()
        b[i + 1] = (v ushr 8).toByte()
        b[i + 2] = (v ushr 16).toByte()
        b[i + 3] = (v ushr 24).toByte()
    }
}
