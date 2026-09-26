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

        // A single generation previously capped long translations and could stop
        // before the end of the input. Translate bounded chunks instead. Splitting
        // by lines first keeps the user's line breaks intact.
        return text
            .split('\n')
            .joinToString("\n") { line ->
                if (line.isBlank()) {
                    ""
                } else {
                    splitLongLine(line).joinToString(" ") { chunk ->
                        translateChunk(chunk, source, target)
                    }
                }
            }
            .trimEnd()
    }

    private fun translateChunk(text: String, source: Language, target: Language): String {
        val prompt = TranslationPrompt.build(text, source, target)
        val maxTokens = outputTokenBudget(text, target)
        val raw = LlamaNative.generate(prompt, maxTokens)
        val cleaned = cleanModelOutput(raw)
        check(cleaned.isNotBlank()) { "Модель вернула пустой перевод" }
        return cleaned
    }

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

    private fun splitLongLine(line: String): List<String> {
        if (line.length <= CHUNK_CHAR_LIMIT) return listOf(line.trim())

        val chunks = mutableListOf<String>()
        var remaining = line.trim()

        while (remaining.length > CHUNK_CHAR_LIMIT) {
            val minPreferredBreak = CHUNK_CHAR_LIMIT * 2 / 3
            var breakAt = -1

            // Prefer ending a chunk at sentence punctuation or whitespace near
            // the limit so the model gets natural translation units.
            for (index in CHUNK_CHAR_LIMIT downTo minPreferredBreak) {
                val previous = remaining[index - 1]
                val current = remaining[index]
                if (previous in SENTENCE_ENDINGS || current.isWhitespace()) {
                    breakAt = index
                    break
                }
            }

            if (breakAt < 0) breakAt = CHUNK_CHAR_LIMIT

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
        const val CHUNK_CHAR_LIMIT = 1000
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
