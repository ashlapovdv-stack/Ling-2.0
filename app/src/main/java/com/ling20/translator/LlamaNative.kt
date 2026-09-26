package com.ling20.translator

internal object LlamaNative {
    init {
        System.loadLibrary("ling_llama")
    }

    fun loadModel(path: String) = nativeLoadModel(path)

    fun unloadModel() = nativeUnloadModel()

    fun generate(prompt: String, maxTokens: Int): String = nativeGenerate(prompt, maxTokens)

    private external fun nativeLoadModel(modelPath: String)
    private external fun nativeUnloadModel()
    private external fun nativeGenerate(prompt: String, maxTokens: Int): String
}
