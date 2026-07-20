package com.bubble.shared.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Pendant iOS d'AndroidPairedIdentityStore. Range l'identité pairée (session + clé E2EE) dans
 * le **Keychain** avec l'accessibilité `WhenUnlockedThisDeviceOnly` : iOS chiffre l'entrée au
 * repos avec une clé de classe protégée par la **Secure Enclave** (AES-GCM matériel) — c'est
 * l'équivalent idiomatique de l'enveloppe AES-256-GCM faite via l'Android Keystore. La clé
 * n'est lisible qu'appareil déverrouillé, jamais exportée, non extractible hors de l'appareil.
 *
 * NOTE : compile uniquement sur macOS. Le pont CoreFoundation ↔ Kotlin/Native devra être
 * confirmé au premier passage CI/Mac.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
class IosPairedIdentityStore : PairedIdentityStore {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Persisted(val session: PairingSession, val sessionKeyB64: String)

    override suspend fun save(identity: PairedIdentity) {
        clear() // une seule identité active (verrou 1:1)
        val plain = json.encodeToString(
            Persisted.serializer(),
            Persisted(identity.session, Base64.encode(identity.sessionKey)),
        ).encodeToByteArray()

        val data = CFBridgingRetain(plain.toNSData())
        val add = cfDictionaryOf(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to CFBridgingRetain(SERVICE),
            kSecAttrAccount to CFBridgingRetain(ACCOUNT),
            kSecValueData to data,
            kSecAttrAccessible to kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        )
        SecItemAdd(add, null)
        CFBridgingRelease(data)
        CFBridgingRelease(add)
    }

    override suspend fun load(): PairedIdentity? = memScoped {
        val query = cfDictionaryOf(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to CFBridgingRetain(SERVICE),
            kSecAttrAccount to CFBridgingRetain(ACCOUNT),
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne,
        )
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFBridgingRelease(query)
        if (status != errSecSuccess) return@memScoped null

        val nsData = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
        runCatching {
            val persisted = json.decodeFromString(Persisted.serializer(), nsData.toByteArray().decodeToString())
            PairedIdentity(persisted.session, Base64.decode(persisted.sessionKeyB64))
        }.getOrNull()
    }

    override suspend fun clear() {
        val query = cfDictionaryOf(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to CFBridgingRetain(SERVICE),
            kSecAttrAccount to CFBridgingRetain(ACCOUNT),
        )
        SecItemDelete(query)
        CFBridgingRelease(query)
    }

    /** Construit un CFDictionary depuis des paires de CFTypeRef (clés/valeurs Security). */
    private fun cfDictionaryOf(vararg pairs: Pair<CFStringRef?, Any?>): CFDictionaryRef? = memScoped {
        val keys = allocArrayOf(pairs.map { it.first })
        val values = allocArrayOf(pairs.map { it.second as? platform.CoreFoundation.CFTypeRef })
        CFDictionaryCreate(
            null, keys, values, pairs.size.convert(),
            kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
        )
    }

    private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
        NSData.create(bytes = if (isEmpty()) null else pinned.addressOf(0), length = size.convert())
    }

    private fun NSData.toByteArray(): ByteArray {
        val size = length.toInt()
        val out = ByteArray(size)
        if (size > 0) out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
        return out
    }

    private companion object {
        const val SERVICE = "app.bubble.identity"
        const val ACCOUNT = "paired-identity"
    }
}
