# Offline model plan

## Initial model

Target: `Qwen/Qwen3-0.6B-GGUF`, starting with a Q4_K_M quantization.

Why this model:

- small enough for an Android MVP compared with multi-billion-parameter models;
- multilingual instruction following and translation;
- supports Russian, English and Chinese;
- GGUF is supported by llama.cpp.

## Runtime

Use llama.cpp compiled for Android arm64-v8a through the Android NDK.

The Kotlin layer must talk to native code through a small JNI bridge. UI code must depend only on `TranslationEngine`, not directly on JNI.

## Model storage

Do not package the model in Git.

MVP options:

1. user chooses a `.gguf` file already present on the phone; or
2. model is copied into app-private storage during installation/testing.

The production app can later offer a separate model-install flow while keeping actual translation offline.

## Translation mode

For translation, disable long reasoning-style output and use a strict prompt:

- translate only;
- no explanations;
- preserve names, numbers and punctuation;
- return only the translated text.

Generation should be deterministic or near-deterministic (low temperature) and use a small context suitable for translation rather than chat.

## Next implementation steps

1. Add native `llama.cpp` module / CMake build.
2. Add JNI functions: load model, unload model, translate/generate, cancel.
3. Implement `LlamaCppTranslationEngine`.
4. Add local model picker and validation.
5. Move inference off the UI thread and stream progress/state.
6. Benchmark RU↔EN, RU↔ZH and EN↔ZH on physical Android devices.
