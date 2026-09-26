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

        val chunkLimit = if (usesTranslateGemmaPrompt()) {
            TRANSLATEGEMMA_CHUNK_CHAR_LIMIT
        } else {
            DEFAULT_CHUNK_CHAR_LIMIT
        }

        // Long text is translated in natural chunks so one generation cannot stop
        // before the end of the original input. Existing line breaks are preserved.
        return text
            .split('\n')
            .joinToString("\n") { line ->
                if (line.isBlank()) {
                    ""
                } else {
                    splitLongLine(line, chunkLimit).joinToString(" ") { chunk ->
                        translateChunk(chunk, source, target)
                    }
                }
            }
            .trimEnd()
    }

    private fun translateChunk(text: String, source: Language, target: Language): String {
        val prompt = if (usesTranslateGemmaPrompt()) {
            TranslateGemmaPrompt.build(text, source, target)
        } else {
            TranslationPrompt.build(text, source, target)
        }
        val maxTokens = outputTokenBudget(text, target)
        val raw = LlamaNative.generate(prompt, maxTokens)
        val cleaned = cleanModelOutput(raw)
        check(cleaned.isNotBlank()) { "Модель вернула пустой перевод" }
        return cleaned
    }

    private fun usesTranslateGemmaPrompt(): Boolean =
        modelName?.contains("translategemma", ignoreCase = true) == true

    private fun outputTokenBudget(text: String, target: Language): Int {
        // Character count intentionally overestimates the required token budget.
        // This is especially important for Chinese -> Russian/English, where the
        // translated text can expand significantly in characters.
        val estimated = when (target) {
            Language.RUSSIAN -> text.length * 3 / 2 + 256
            Language.ENGLISH -> text.length * 5 / 4 + 224
            Language.CHINESE -> text.length + 224
        }
        return estimated.coerceIn(256, MAX_OUTPUT_TOKENS)
    }

    private fun splitLongLine(line: String, chunkLimit: Int): List<String> {
        if (line.length <= chunkLimit) return listOf(line.trim())

        val chunks = mutableListOf<String>()
        var remaining = line.trim()

        while (remaining.length > chunkLimit) {
            val minPreferredBreak = chunkLimit * 2 / 3
            var breakAt = -1

            // Prefer ending a chunk at sentence punctuation or whitespace near
            // the limit so each request remains a natural translation unit.
            for (index in chunkLimit downTo minPreferredBreak) {
                val previous = remaining[index - 1]
                val current = remaining[index]
                if (previous in SENTENCE_ENDINGS || current.isWhitespace()) {
                    breakAt = index
                    break
                }
            }

            if (breakAt < 0) breakAt = chunkLimit

            remaining.substring(0, breakAt)
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let(chunks::add)

            remaining = remaining.substring(breakAt).trimStart()
        }

        if (remaining.isNotEmpty()) chunks += remaining
        return chunks
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

    private companion object {
        const val DEFAULT_CHUNK_CHAR_LIMIT = 1000
        // TranslateGemma's documented input context is 2K tokens. The smaller
        // chunk leaves comfortable room for its required translation instruction.
        const val TRANSLATEGEMMA_CHUNK_CHAR_LIMIT = 700
        const val MAX_OUTPUT_TOKENS = 1792
        val SENTENCE_ENDINGS = charArrayOf('.', '!', '?', '。', '！', '？', ';', ':')
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
        Translate the entire supplied text from the first character to the last; do not stop early.
        Return only the translation. Do not explain, comment, summarize, or provide alternatives.
        Preserve names, numbers, punctuation, tone, and line breaks where reasonable.
        Do not answer the text as a question; translate it literally and naturally.
        /no_think

        Text to translate:
        $text
    """.trimIndent()
}

object TranslateGemmaPrompt {
    /**
     * TranslateGemma was trained with this dedicated translation instruction.
     * The model-specific prompt is intentionally separate from the generic Qwen
     * prompt and includes both human-readable language names and ISO codes.
     */
    fun build(text: String, source: Language, target: Language): String = """
        You are a professional ${source.promptName} (${source.code}) to ${target.promptName} (${target.code}) translator. Your goal is to accurately convey the meaning and nuances of the original ${source.promptName} text while adhering to ${target.promptName} grammar, vocabulary, and cultural sensitivities.
        Produce only the ${target.promptName} translation, without any additional explanations or commentary. Please translate the following ${source.promptName} text into ${target.promptName}:


        $text
    """.trimIndent()
}
