package com.ling20.translator

interface TranslationEngine {
    val isReady: Boolean

    fun translate(
        text: String,
        source: Language,
        target: Language,
    ): String
}

/**
 * Temporary engine used until the llama.cpp JNI layer and a GGUF model are wired in.
 * It deliberately does not fake translations.
 */
object ModelNotLoadedEngine : TranslationEngine {
    override val isReady: Boolean = false

    override fun translate(
        text: String,
        source: Language,
        target: Language,
    ): String {
        error("Offline neural model is not loaded yet")
    }
}

object TranslationPrompt {
    fun build(text: String, source: Language, target: Language): String = """
        Translate the text from ${source.promptName} to ${target.promptName}.
        Return only the translation, without explanations, notes, quotes, or alternatives.
        Preserve meaning, tone, names, numbers, punctuation, and line breaks where reasonable.

        Text:
        $text
    """.trimIndent()
}
