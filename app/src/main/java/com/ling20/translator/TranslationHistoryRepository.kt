package com.ling20.translator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TranslationHistoryItem(
    val id: Long,
    val source: Language,
    val target: Language,
    val input: String,
    val output: String,
    val createdAtMillis: Long,
)

class TranslationHistoryRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<TranslationHistoryItem> {
        val raw = preferences.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        TranslationHistoryItem(
                            id = item.getLong("id"),
                            source = Language.valueOf(item.getString("source")),
                            target = Language.valueOf(item.getString("target")),
                            input = item.getString("input"),
                            output = item.getString("output"),
                            createdAtMillis = item.getLong("createdAtMillis"),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun add(source: Language, target: Language, input: String, output: String) {
        val item = TranslationHistoryItem(
            id = System.currentTimeMillis(),
            source = source,
            target = target,
            input = input,
            output = output,
            createdAtMillis = System.currentTimeMillis(),
        )
        val updated = listOf(item) + load()
        save(updated.take(MAX_ITEMS))
    }

    fun clear() {
        preferences.edit().remove(KEY_HISTORY).apply()
    }

    private fun save(items: List<TranslationHistoryItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("source", item.source.name)
                    .put("target", item.target.name)
                    .put("input", item.input)
                    .put("output", item.output)
                    .put("createdAtMillis", item.createdAtMillis)
            )
        }
        preferences.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private companion object {
        const val PREFS_NAME = "ling_preferences"
        const val KEY_HISTORY = "translation_history"
        const val MAX_ITEMS = 200
    }
}
