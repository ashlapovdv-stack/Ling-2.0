package com.ling20.translator

interface TranslationEngine {
    val isReady: Boolean
    val loadedModelName: String?

    fun translate(
        text: String,
        source: Language,
        target: Language,
    ): String
}

class LlamaTranslationEngine : TranslationEngine {
    @Volatile
    private var ready: Boolean = false

    @Volatile
    private var modelName: String? = null

    override val isReady: Boolean
        get() = ready

    override val loadedModelName: String?
        get() = modelName

    fun loadModel(model: InstalledModel) {
        ready = false
        modelName = null
        LlamaNative.loadModel(model.file.absolutePath)
        modelName = model.displayName
        ready = true
    }

    fun unloadModel() {
        LlamaNative.unloadModel()
        ready = false
        modelName = null
    }

    override fun translate(text: String, source: Language, target: Language): String {
        check(ready) { "Локальная модель не загружена" }
        require(text.isNotBlank()) { "Введите текст для перевода" }
        require(source != target) { "Исходный язык и язык перевода должны отличаться" }

        val prompt = TranslationPrompt.build(text, source, target)
        val maxTokens = (text.length / 2 + 128).coerceIn(128, 768)
        val raw = LlamaNative.generate(prompt, maxTokens)
        val cleaned = cleanModelOutput(raw)
        check(cleaned.isNotBlank()) { "Модель вернула пустой перевод" }
        return cleaned
    }

    private fun cleanModelOutput(raw: String): String {
        var result = raw.trim()
        if ("</think>" in result) {
            result = result.substringAfterLast("</think>").trim()
        }
        result = result
            .replace(Regex("(?s)<think>.*?</think>"), "")
            .trim()
        return result
    }
}

object ModelNotLoadedEngine : TranslationEngine {
    override val isReady: Boolean = false
    override val loadedModelName: String? = null

    override fun translate(text: String, source: Language, target: Language): String {
        error("Offline neural model is not loaded yet")
    }
}

object TranslationPrompt {
    fun build(text: String, source: Language, target: Language): String = """
        You are an offline translation engine.
        Translate the text from ${source.promptName} to ${target.promptName}.
        Return only the translation. Do not explain, comment, summarize, or provide alternatives.
        Preserve names, numbers, punctuation, tone, and line breaks where reasonable.
        Do not answer the text as a question; translate it literally and naturally.
        /no_think

        Text to translate:
        $text
    """.trimIndent()
}
