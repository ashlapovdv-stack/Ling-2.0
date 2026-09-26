package com.ling20.translator

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class InstalledModel(
    val displayName: String,
    val file: File,
    val sizeBytes: Long,
)

class ModelRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val modelDirectory = File(context.filesDir, "models")

    fun currentModel(): InstalledModel? {
        val path = preferences.getString(KEY_MODEL_PATH, null) ?: return null
        val file = File(path)
        if (!file.isFile || file.length() == 0L) return null
        return InstalledModel(
            displayName = preferences.getString(KEY_MODEL_NAME, file.name) ?: file.name,
            file = file,
            sizeBytes = file.length(),
        )
    }

    suspend fun importModel(uri: Uri): InstalledModel = withContext(Dispatchers.IO) {
        modelDirectory.mkdirs()
        val displayName = queryDisplayName(uri) ?: "model.gguf"
        require(displayName.endsWith(".gguf", ignoreCase = true)) {
            "Выберите файл модели в формате .gguf"
        }

        val temp = File(modelDirectory, "importing.gguf")
        val destination = File(modelDirectory, "model.gguf")

        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Не удалось открыть выбранный файл" }
                temp.outputStream().buffered().use { output ->
                    val magic = ByteArray(4)
                    val read = input.read(magic)
                    require(read == 4 && magic.contentEquals(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))) {
                        "Файл не является GGUF моделью"
                    }
                    output.write(magic)
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                }
            }

            require(temp.length() > 1024 * 1024) { "Файл модели слишком мал" }

            if (destination.exists() && !destination.delete()) {
                error("Не удалось заменить старую модель")
            }
            require(temp.renameTo(destination)) { "Не удалось сохранить модель" }

            preferences.edit()
                .putString(KEY_MODEL_PATH, destination.absolutePath)
                .putString(KEY_MODEL_NAME, displayName)
                .apply()

            InstalledModel(displayName, destination, destination.length())
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    fun removeModel() {
        currentModel()?.file?.delete()
        File(modelDirectory, "importing.gguf").delete()
        preferences.edit()
            .remove(KEY_MODEL_PATH)
            .remove(KEY_MODEL_NAME)
            .apply()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }

    private companion object {
        const val PREFS_NAME = "ling_model_preferences"
        const val KEY_MODEL_PATH = "model_path"
        const val KEY_MODEL_NAME = "model_name"
    }
}
