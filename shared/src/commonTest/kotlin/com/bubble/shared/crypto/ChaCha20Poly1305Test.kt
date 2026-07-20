package com.bubble.shared.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertContentEquals

class ChaCha20Poly1305Test {

    private fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun sealMatchesRfc8439Vector() {
        // RFC 8439 §2.8.2 — vecteur AEAD officiel.
        val key = "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f".unhex()
        val nonce = "070000004041424344454647".unhex()
        val aad = "50515253c0c1c2c3c4c5c6c7".unhex()
        val plaintext = ("4c616469657320616e642047656e746c656d656e206f662074686520636c6173" +
            "73206f66202739393a204966204920636f756c64206f6666657220796f75206f" +
            "6e6c79206f6e652074697020666f7220746865206675747572652c2073756e73" +
            "637265656e20776f756c642062652069742e").unhex()

        val sealed = ChaCha20Poly1305.seal(key, nonce, plaintext, aad)
        val expectedCt = ("d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
            "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36" +
            "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
            "3ff4def08e4b7a9de576d26586cec64b6116")
        val expectedTag = "1ae10b594f09e26a7e902ecbd0600691"
        assertEquals(expectedCt, sealed.copyOf(sealed.size - 16).hex())
        assertEquals(expectedTag, sealed.copyOfRange(sealed.size - 16, sealed.size).hex())
    }

    @Test
    fun openReversesSealWithAad() {
        val key = "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f".unhex()
        val nonce = "070000004041424344454647".unhex()
        val aad = "50515253c0c1c2c3c4c5c6c7".unhex()
        val plaintext = "hello bubble, this is an E2EE payload".encodeToByteArray()

        val sealed = ChaCha20Poly1305.seal(key, nonce, plaintext, aad)
        assertContentEquals(plaintext, ChaCha20Poly1305.open(key, nonce, sealed, aad))
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it * 7).toByte() }
        val sealed = ChaCha20Poly1305.seal(key, nonce, "secret".encodeToByteArray())

        sealed[0] = (sealed[0] + 1).toByte() // un octet modifié
        assertNull(ChaCha20Poly1305.open(key, nonce, sealed), "authentification doit échouer")
    }

    @Test
    fun wrongKeyIsRejected() {
        val nonce = ByteArray(12)
        val sealed = ChaCha20Poly1305.seal(ByteArray(32) { 1 }, nonce, "x".encodeToByteArray())
        assertNull(ChaCha20Poly1305.open(ByteArray(32) { 2 }, nonce, sealed))
    }
}
