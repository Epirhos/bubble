package com.bubble.shared.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/** Valide SHA-256, HKDF et X25519 contre les vecteurs officiels (NIST / RFC 5869 / RFC 7748). */
class CryptoPrimitivesTest {

    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun String.unhex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun sha256MatchesNistVectors() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hash("abc".encodeToByteArray()).hex(),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hash(ByteArray(0)).hex(),
        )
    }

    @Test
    fun hkdfMatchesRfc5869Case1() {
        val ikm = "0b".repeat(22).unhex()
        val salt = "000102030405060708090a0b0c".unhex()
        val info = "f0f1f2f3f4f5f6f7f8f9".unhex()
        val okm = Hkdf.derive(ikm, salt, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.hex(),
        )
    }

    @Test
    fun x25519MatchesRfc7748Vector() {
        // RFC 7748 §5.2 : scalaire + u → sortie attendue.
        val scalar = "a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4".unhex()
        val u = "e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c".unhex()
        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            X25519.scalarMult(scalar, u).hex(),
        )
    }

    @Test
    fun x25519DiffieHellmanAgrees() {
        // RFC 7748 §6.1 : Alice et Bob dérivent le même secret partagé.
        val alicePriv = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".unhex()
        val bobPriv = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb".unhex()
        val alicePub = X25519.scalarMultBase(alicePriv)
        val bobPub = X25519.scalarMultBase(bobPriv)
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", alicePub.hex())
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", bobPub.hex())

        val secretA = X25519.scalarMult(alicePriv, bobPub)
        val secretB = X25519.scalarMult(bobPriv, alicePub)
        assertEquals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742", secretA.hex())
        assertEquals(secretA.hex(), secretB.hex())
    }
}
