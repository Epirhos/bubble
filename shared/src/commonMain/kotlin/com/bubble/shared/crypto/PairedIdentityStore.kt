package com.bubble.shared.crypto

/** Identité pairée persistée : la session publique + la clé E2EE secrète. */
class PairedIdentity(val session: PairingSession, val sessionKey: ByteArray)

/**
 * Range l'identité pairée de façon chiffrée au repos (Keystore/Keychain), pour la restaurer
 * au redémarrage sans refaire la cérémonie. La clé E2EE ne doit jamais toucher le disque en
 * clair : l'implémentation la protège par une clé matérielle non exportable.
 */
interface PairedIdentityStore {
    suspend fun save(identity: PairedIdentity)

    suspend fun load(): PairedIdentity?

    suspend fun clear()
}
