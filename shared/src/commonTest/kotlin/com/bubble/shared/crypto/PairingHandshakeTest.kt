package com.bubble.shared.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingHandshakeTest {

    @Test
    fun fullCeremonyDerivesIdenticalSessionKey() {
        // A génère l'invitation (QR).
        val alice = PairingHandshake.initiator()
        val qr = alice.invite.encode()

        // B scanne le QR, calcule immédiatement sa session.
        val (bob, bobResult) = assertNotNull(PairingHandshake.responder(qr))

        // B renvoie sa clé publique à A (via signaling) ; A finalise.
        val aliceResult = alice.complete(bob.publicKey)

        // Les deux ont dérivé LA MÊME clé de session E2EE, sans jamais l'échanger.
        assertContentEquals(aliceResult.sessionKey, bobResult.sessionKey)
        assertEquals(32, aliceResult.sessionKey.size)

        // Même safety number des deux côtés → pas de MITM.
        assertEquals(aliceResult.safetyNumber, bobResult.safetyNumber)

        // Même couple, fingerprints croisés cohérents.
        assertEquals(aliceResult.session.coupleId, bobResult.session.coupleId)
        assertEquals(aliceResult.session.selfFingerprint, bobResult.session.partnerFingerprint)
        assertEquals(aliceResult.session.partnerFingerprint, bobResult.session.selfFingerprint)
    }

    @Test
    fun distinctCouplesDeriveDifferentKeys() {
        val a1 = PairingHandshake.initiator()
        val (b1, r1) = assertNotNull(PairingHandshake.responder(a1.invite.encode()))
        val ar1 = a1.complete(b1.publicKey)

        val a2 = PairingHandshake.initiator()
        val (b2, r2) = assertNotNull(PairingHandshake.responder(a2.invite.encode()))
        val ar2 = a2.complete(b2.publicKey)

        assertTrue(!ar1.sessionKey.contentEquals(ar2.sessionKey), "clés indépendantes par couple")
        assertEquals(r1.sessionKey.toList(), ar1.sessionKey.toList())
        assertEquals(r2.sessionKey.toList(), ar2.sessionKey.toList())
    }

    @Test
    fun inviteEncodesAndDecodesRoundTrip() {
        val invite = PairingHandshake.initiator().invite
        val decoded = assertNotNull(PairingInvite.decode(invite.encode()))
        assertEquals(invite.coupleId, decoded.coupleId)
        assertContentEquals(invite.publicKey, decoded.publicKey)
    }

    @Test
    fun malformedQrIsRejected() {
        assertNull(PairingInvite.decode("garbage"))
        assertNull(PairingInvite.decode("bubble1:only-two-parts"))
        assertNull(PairingInvite.decode("bubble1:couple:not-base64!!"))
        assertNull(PairingHandshake.responder("not a real invite"))
    }
}
