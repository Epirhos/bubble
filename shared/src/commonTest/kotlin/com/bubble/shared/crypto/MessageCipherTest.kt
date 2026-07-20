package com.bubble.shared.crypto

import com.bubble.shared.signal.HapticGesture
import com.bubble.shared.signal.PeerMessage
import com.bubble.shared.signal.PeerWire
import com.bubble.shared.signal.SignalPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageCipherTest {

    @Test
    fun sealedPeerMessageRoundTripsUnderSharedKey() {
        // Deux appareils dérivant la même clé de session partagent le même cipher.
        val key = PairingCrypto.deriveSessionKey(secureRandomBytes(32), "couple-xyz")
        val alice = AeadMessageCipher(key)
        val bob = AeadMessageCipher(key)

        val message: PeerMessage = PeerMessage.Signal(SignalPayload.Haptic(42L, HapticGesture.KISS))
        val onWire = alice.seal(PeerWire.encode(message).encodeToByteArray())

        val opened = bob.open(onWire)
        assertTrue(opened != null)
        assertEquals(message, PeerWire.decode(opened.decodeToString()))
    }

    @Test
    fun differentSessionKeyCannotOpen() {
        val alice = AeadMessageCipher(ByteArray(32) { 1 })
        val eve = AeadMessageCipher(ByteArray(32) { 2 })
        val sealed = alice.seal("bisou".encodeToByteArray())
        assertNull(eve.open(sealed), "une clé différente ne peut pas déchiffrer")
    }

    @Test
    fun eachSealUsesFreshNonce() {
        val cipher = AeadMessageCipher(ByteArray(32) { 7 })
        val a = cipher.seal("x".encodeToByteArray())
        val b = cipher.seal("x".encodeToByteArray())
        // Même clair, sorties différentes (nonce aléatoire) → pas de fuite par répétition.
        assertTrue(!a.contentEquals(b))
    }
}
