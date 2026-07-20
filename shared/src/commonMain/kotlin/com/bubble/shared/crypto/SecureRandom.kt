package com.bubble.shared.crypto

/**
 * Octets aléatoires cryptographiquement sûrs, fournis par la plateforme
 * (SecureRandom sur JVM/Android, SecRandomCopyBytes sur iOS). Jamais un PRNG maison.
 */
expect fun secureRandomBytes(size: Int): ByteArray
