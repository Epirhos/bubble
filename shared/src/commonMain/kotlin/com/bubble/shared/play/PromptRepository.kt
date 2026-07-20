package com.bubble.shared.play

import kotlin.random.Random

/** Persistance locale (zéro serveur) des prompts ajoutés par l'utilisateur. */
interface CustomPromptStore {
    suspend fun all(): List<String>

    suspend fun add(text: String)

    suspend fun remove(text: String)
}

/** Store en mémoire — défaut et base des tests ; l'app fournit une version persistée (fichier). */
class InMemoryPromptStore(initial: List<String> = emptyList()) : CustomPromptStore {
    private val items = initial.toMutableList()

    override suspend fun all(): List<String> = items.toList()

    override suspend fun add(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty() && trimmed !in items) items.add(trimmed)
    }

    override suspend fun remove(text: String) {
        items.remove(text)
    }
}

/**
 * Pioche des amorces de jeu (dilemmes, "Et si…", "Qui de nous deux…"). Combine une liste
 * embarquée (aucun réseau) et les prompts ajoutés localement par le couple.
 */
class PromptRepository(
    private val customStore: CustomPromptStore,
    private val random: Random = Random.Default,
) {
    suspend fun randomPrompt(): String {
        val pool = DEFAULT_PROMPTS + customStore.all()
        return pool[random.nextInt(pool.size)]
    }

    suspend fun randomCoupon(): String = DEFAULT_COUPONS[random.nextInt(DEFAULT_COUPONS.size)]

    suspend fun addCustomPrompt(text: String) = customStore.add(text)

    suspend fun customPrompts(): List<String> = customStore.all()

    companion object {
        /** Amorces embarquées — douces, ouvertes, jamais clivantes (règle Zéro Perdant). */
        val DEFAULT_PROMPTS: List<String> = listOf(
            "Si on devait échanger de corps pour 24h…",
            "Le souvenir de notre premier fou rire…",
            "Et si on partait demain, sans rien prévoir…",
            "Qui de nous deux ose le plus…",
            "La chose que je n'ose jamais te dire à voix haute…",
            "Notre prochaine petite folie à deux…",
            "Un endroit où je rêve de me réveiller avec toi…",
            "Ce que je préfère quand tu ne me regardes pas…",
        )

        val DEFAULT_COUPONS: List<String> = listOf(
            "Bon pour un café préparé demain matin",
            "Bon pour un slow dans la cuisine",
            "Bon pour choisir le film ce soir",
            "Bon pour un vrai câlin de 30 secondes",
            "Bon pour une question à laquelle je répondrai franchement",
        )
    }
}
