package com.bubble.app.play

import android.content.Context
import com.bubble.shared.play.CustomPromptStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Prompts personnalisés du couple, persistés localement (zéro serveur, contrainte étape 10).
 * Un prompt par ligne dans un fichier interne — les amorces sont de simples phrases, pas
 * besoin de JSON. Effacé comme le reste à la désaffiliation.
 */
class FilePromptStore(context: Context) : CustomPromptStore {
    private val file = File(context.filesDir, "custom_prompts.txt")

    override suspend fun all(): List<String> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    override suspend fun add(text: String) = withContext(Dispatchers.IO) {
        val trimmed = text.trim().replace("\n", " ")
        if (trimmed.isEmpty()) return@withContext
        val current = all().toMutableList()
        if (trimmed !in current) {
            current.add(trimmed)
            file.writeText(current.joinToString("\n"))
        }
    }

    override suspend fun remove(text: String) = withContext(Dispatchers.IO) {
        val current = all().toMutableList()
        if (current.remove(text)) file.writeText(current.joinToString("\n"))
    }
}
