package com.ling20.translator

enum class Language(
    val code: String,
    val displayName: String,
    val promptName: String,
) {
    RUSSIAN("ru", "Русский", "Russian"),
    ENGLISH("en", "English", "English"),
    CHINESE("zh", "中文", "Simplified Chinese"),
}
