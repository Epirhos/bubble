package com.bubble.shared.crypto

/**
 * X25519 (RFC 7748) — échange de clés Diffie-Hellman sur Curve25519.
 * Port de l'implémentation de référence TweetNaCl (crypto_scalarmult) en Kotlin pur :
 * arithmétique de champ en radix 2^16 sur des `LongArray(16)`. Identique sur les deux OS,
 * testable sur JVM avec les vecteurs RFC 7748.
 *
 * Usage : [scalarMultBase] dérive la clé publique d'une clé privée ; [scalarMult] calcule
 * le secret partagé (privée locale × publique du pair).
 */
object X25519 {
    const val KEY_SIZE = 32

    private val _121665 = longArrayOf(0xDB41, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    fun scalarMultBase(scalar: ByteArray): ByteArray {
        val base = ByteArray(KEY_SIZE)
        base[0] = 9
        return scalarMult(scalar, base)
    }

    fun scalarMult(scalar: ByteArray, point: ByteArray): ByteArray {
        val z = ByteArray(32)
        for (i in 0 until 31) z[i] = scalar[i]
        z[31] = ((scalar[31].toInt() and 127) or 64).toByte()
        z[0] = (z[0].toInt() and 248).toByte()

        val x = unpack25519(point)
        val a = LongArray(16); val b = LongArray(16); val c = LongArray(16)
        val d = LongArray(16); val e = LongArray(16); val f = LongArray(16)
        for (i in 0 until 16) b[i] = x[i]
        a[0] = 1; d[0] = 1

        for (i in 254 downTo 0) {
            val r = ((z[i ushr 3].toInt() ushr (i and 7)) and 1).toLong()
            sel25519(a, b, r); sel25519(c, d, r)
            add(e, a, c); sub(a, a, c); add(c, b, d); sub(b, b, d)
            square(d, e); square(f, a); mul(a, c, a); mul(c, b, e)
            add(e, a, c); sub(a, a, c); square(b, a); sub(c, d, f)
            mul(a, c, _121665); add(a, a, d); mul(c, c, a); mul(a, d, f)
            mul(d, b, x); square(b, e)
            sel25519(a, b, r); sel25519(c, d, r)
        }
        val cc = c.copyOf()
        inv25519(cc, cc)
        mul(a, a, cc)
        return pack25519(a)
    }

    private fun unpack25519(n: ByteArray): LongArray {
        val o = LongArray(16)
        for (i in 0 until 16) o[i] = (n[2 * i].toLong() and 0xff) + ((n[2 * i + 1].toLong() and 0xff) shl 8)
        o[15] = o[15] and 0x7fff
        return o
    }

    private fun car25519(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += (1L shl 16)
            val c = o[i] shr 16
            o[(i + 1) * (if (i < 15) 1 else 0)] += c - 1 + 37 * (c - 1) * (if (i == 15) 1 else 0)
            o[i] -= c shl 16
        }
    }

    private fun sel25519(p: LongArray, q: LongArray, b: Long) {
        val c = (b - 1).inv()
        for (i in 0 until 16) {
            val t = c and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    private fun pack25519(n: LongArray): ByteArray {
        val t = n.copyOf()
        car25519(t); car25519(t); car25519(t)
        val m = LongArray(16)
        for (j in 0 until 2) {
            m[0] = t[0] - 0xffed
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
                m[i - 1] = m[i - 1] and 0xffff
            }
            m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
            val b = (m[15] shr 16) and 1
            m[14] = m[14] and 0xffff
            sel25519(t, m, 1 - b)
        }
        val o = ByteArray(32)
        for (i in 0 until 16) {
            o[2 * i] = (t[i] and 0xff).toByte()
            o[2 * i + 1] = (t[i] shr 8).toByte()
        }
        return o
    }

    private fun add(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until 16) o[i] = a[i] + b[i]
    }

    private fun sub(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until 16) o[i] = a[i] - b[i]
    }

    private fun mul(o: LongArray, a: LongArray, b: LongArray) {
        val t = LongArray(31)
        for (i in 0 until 16) for (j in 0 until 16) t[i + j] += a[i] * b[j]
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        for (i in 0 until 16) o[i] = t[i]
        car25519(o); car25519(o)
    }

    private fun square(o: LongArray, a: LongArray) = mul(o, a, a)

    private fun inv25519(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 253 downTo 0) {
            square(c, c)
            if (a != 2 && a != 4) mul(c, c, i)
        }
        for (a in 0 until 16) o[a] = c[a]
    }
}
