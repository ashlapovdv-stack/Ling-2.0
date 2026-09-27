package com.ling20.translator

interface TranslationEngine {
    val isReady: Boolean
    val loadedModelName: String?

    fun translate(
        text: String,
        source: Language,
        target: Language,
    ): String

    fun translateBatch(
        texts: List<String>,
        source: Language,
        target: Language,
    ): List<String> = texts.map { text -> translate(text, source, target) }
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

    override fun translateBatch(
        texts: List<String>,
        source: Language,
        target: Language,
    ): List<String> {
        check(ready) { "Локальная модель не загружена" }
        require(source != target) { "Исходный язык и язык перевода должны отличаться" }
        if (texts.isEmpty()) return emptyList()
        if (texts.size == 1) return listOf(translate(texts.first(), source, target))

        val output = mutableListOf<String>()
        val batch = mutableListOf<String>()
        var batchCharacters = 0

        fun flushBatch() {
            if (batch.isEmpty()) return
            output += if (batch.size == 1) {
                listOf(translate(batch.first(), source, target))
            } else {
                translateBatchChunk(batch.toList(), source, target)
            }
            batch.clear()
            batchCharacters = 0
        }

        texts.forEach { raw ->
            val text = raw.trim()
            require(text.isNotBlank()) { "Введите текст для перевода" }
            val wouldOverflow = batch.isNotEmpty() &&
                (batch.size >= BATCH_MAX_ITEMS || batchCharacters + text.length > BATCH_MAX_CHARACTERS)
            if (wouldOverflow) flushBatch()
            batch += text
            batchCharacters += text.length
        }
        flushBatch()
        return output
    }

    private fun translateBatchChunk(
        texts: List<String>,
        source: Language,
        target: Language,
    ): List<String> {
        val prompt = buildBatchPrompt(texts, source, target)
        val sourcePayload = texts.joinToString(" ")
        val maxTokens = batchOutputTokenBudget(sourcePayload, target)
        val raw = LlamaNative.generate(prompt, maxTokens)
        val cleaned = cleanModelOutput(raw)

        parseBatchOutput(cleaned, texts.size)?.let { return it }
        parseLooseBatchOutput(cleaned, texts)?.let { return it }

        // Keep unchanged items here; CameraTranslationOverlay performs a compact
        // grouped fallback for unresolved OCR items instead of exploding into one
        // native generation per line.
        return texts
    }

    private fun buildBatchPrompt(
        texts: List<String>,
        source: Language,
        target: Language,
    ): String {
        val payload = texts.mapIndexed { index, text ->
            "[[LING_$index]] $text"
        }.joinToString("\n")

        return if (usesTranslateGemmaPrompt()) {
            """
                You are a professional ${source.promptName} (${source.code}) to ${target.promptName} (${target.code}) translator.
                Translate every labeled item below into ${target.promptName}.
                Keep every [[LING_n]] marker exactly unchanged and in the same order.
                Return exactly one translated item for every marker. Do not merge, omit, reorder, explain, or add commentary.
                Format each result as: [[LING_n]] translated text

                $payload
            """.trimIndent()
        } else {
            """
                You are an offline translation engine.
                Translate every labeled item from ${source.promptName} to ${target.promptName}.
                Keep every [[LING_n]] marker exactly unchanged and in the same order.
                Return exactly one translated item for every marker. Do not merge, omit, reorder, explain, summarize, or add alternatives.
                Preserve names, numbers and punctuation where reasonable.
                /no_think

                $payload
            """.trimIndent()
        }
    }

    private fun parseBatchOutput(raw: String, expectedCount: Int): List<String>? {
        val matches = BATCH_MARKER_REGEX.findAll(raw).toList()
        if (matches.isEmpty()) return null

        val parsed = mutableMapOf<Int, String>()
        matches.forEach { match ->
            val index = match.groupValues[1].toIntOrNull() ?: return@forEach
            val value = match.groupValues[2].trim()
            if (index in 0 until expectedCount && value.isNotBlank()) {
                parsed[index] = value
            }
        }
        if (parsed.size != expectedCount) return null
        return (0 until expectedCount).map { index -> parsed[index] ?: return null }
    }

    private fun parseLooseBatchOutput(raw: String, originals: List<String>): List<String>? {
        val matches = BATCH_MARKER_REGEX.findAll(raw).toList()
        if (matches.isNotEmpty()) {
            val result = originals.toMutableList()
            var accepted = 0
            matches.forEach { match ->
                val index = match.groupValues[1].toIntOrNull() ?: return@forEach
                val value = match.groupValues[2].trim()
                if (index in result.indices && value.isNotBlank()) {
                    result[index] = value
                    accepted++
                }
            }
            if (accepted > 0) return result
        }

        val lines = raw
            .lines()
            .map(String::trim)
            .filter(String::isNotBlank)
        if (lines.size != originals.size) return null

        return lines.map { line ->
            val stripped = line.replace(BATCH_LINE_PREFIX_REGEX, "").trim()
            stripped.ifBlank { line }
        }
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
        val estimated = when (target) {
            Language.RUSSIAN -> text.length * 3 / 2 + 256
            Language.ENGLISH -> text.length * 5 / 4 + 224
            Language.CHINESE -> text.length + 224
        }
        return estimated.coerceIn(256, MAX_OUTPUT_TOKENS)
    }

    private fun batchOutputTokenBudget(text: String, target: Language): Int {
        val estimated = when (target) {
            Language.RUSSIAN -> text.length * 3 / 2 + 128
            Language.ENGLISH -> text.length * 5 / 4 + 112
            Language.CHINESE -> text.length + 112
        }
        return estimated.coerceIn(BATCH_MIN_OUTPUT_TOKENS, BATCH_MAX_OUTPUT_TOKENS)
    }

    private fun splitLongLine(line: String, chunkLimit: Int): List<String> {
        if (line.length <= chunkLimit) return listOf(line.trim())

        val chunks = mutableListOf<String>()
        var remaining = line.trim()

        while (remaining.length > chunkLimit) {
            val minPreferredBreak = chunkLimit * 2 / 3
            var breakAt = -1

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
        const val TRANSLATEGEMMA_CHUNK_CHAR_LIMIT = 700
        const val MAX_OUTPUT_TOKENS = 1792
        const val BATCH_MAX_ITEMS = 16
        const val BATCH_MAX_CHARACTERS = 760
        const val BATCH_MIN_OUTPUT_TOKENS = 160
        const val BATCH_MAX_OUTPUT_TOKENS = 1024
        val SENTENCE_ENDINGS = charArrayOf('.', '!', '?', '。', '！', '？', ';', ':')
        val BATCH_MARKER_REGEX = Regex(
            "(?s)\\[\\[LING_(\\d+)]]\\s*(.*?)(?=(?:\\r?\\n)?\\s*\\[\\[LING_\\d+]]|\\z)",
        )
        val BATCH_LINE_PREFIX_REGEX = Regex(
            "^\\s*(?:\\[\\[LING_\\d+]]\\s*|(?:LING_)?\\d+\\s*[:.)-]\\s*)",
            RegexOption.IGNORE_CASE,
        )
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
    fun build(text: String, source: Language, target: Language): String = """
        You are a professional ${source.promptName} (${source.code}) to ${target.promptName} (${target.code}) translator. Your goal is to accurately convey the meaning and nuances of the original ${source.promptName} text while adhering to ${target.promptName} grammar, vocabulary, and cultural sensitivities.
        Produce only the ${target.promptName} translation, without any additional explanations or commentary. Please translate the following ${source.promptName} text into ${target.promptName}:


        $text
    """.trimIndent()
}
