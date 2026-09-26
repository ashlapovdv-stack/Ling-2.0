# Ling 2.0

Offline neural translator for Android.

## Product scope

Ling 2.0 is an Android-only translator designed to work locally on the device.

Supported languages:

- Russian
- English
- Simplified Chinese

Translation directions:

- Russian ↔ English
- Russian ↔ Chinese
- English ↔ Chinese

## Main modes

The app has four bottom navigation tiles:

1. **Перевод** — text translation
2. **Камера** — live camera / gallery capture, with offline OCR and translation being added incrementally
3. **Диалог** — two-way voice conversation translation (later phase)
4. **Настройки** — translation and camera defaults, local model, app information and **Настройки → История**

History is intentionally not a separate bottom navigation item.

## Current status

Implemented and CI-build verified:

- Kotlin + Jetpack Compose + Material 3 UI
- Russian / English / Chinese language selection
- automatic source-language detection for text translation
- source/target language swap
- text input up to 5000 characters
- offline on-device speech input where supported by Android
- offline Android TTS voices only
- local translation history (up to 200 successful translations)
- Settings → History with clear-history action
- Settings → Translation with default input/output languages
- Settings → Camera with independent default input/output languages
- Settings → Local model
- Android Storage Access Framework picker for `.gguf` models
- GGUF header validation and copy into app-private storage
- llama.cpp pinned as a Git submodule
- Android NDK/CMake JNI bridge
- CPU-only on-device model loading and token generation
- TranslateGemma-specific translation prompt support
- `LlamaTranslationEngine` connected to the Translate button
- inference runs off the UI thread
- no server/API translation fallback
- no Android `INTERNET` permission
- Camera stage 1: full-screen CameraX preview, runtime camera permission, photo capture, gallery image selection and front/back camera switching

Camera OCR/translation and Dialog mode are the next implementation phases.

## Local model

The model is not committed to Git and is not bundled into the APK.

1. copy a compatible `.gguf` model to the Android device;
2. open **Настройки → Локальная модель**;
3. tap **Выбрать GGUF модель**;
4. select the file;
5. Ling copies it into app-private storage and loads it through llama.cpp.

Current recommended translation model: **TranslateGemma 4B GGUF Q4_K_M**. Qwen GGUF models remain supported as alternatives.

The native build is CPU-only and `arm64-v8a`. GPU acceleration can be evaluated after the baseline translator is stable.

## Architecture

```text
Compose UI
  ↓
Ling app state
  ↓
TranslationEngine
  ↓
LlamaTranslationEngine
  ↓
LlamaNative (JNI)
  ↓
llama.cpp (Android NDK / CMake)
  ↓
GGUF model in app-private storage

Settings
  ├─ Translation defaults
  ├─ Camera defaults
  ├─ Local model
  └─ History
       ↓
     local SharedPreferences storage
```

## Native dependency

`llama.cpp` is pinned as a Git submodule under `third_party/llama.cpp`.

Clone with submodules:

```bash
git clone --recurse-submodules <repo-url>
```

For an existing clone:

```bash
git submodule update --init --recursive
```

## Build

The project builds for `arm64-v8a` using Java 17, stable Android API 36, Android NDK and CMake. GitHub Actions runs a debug APK build on pushes to `main` and checks out the llama.cpp submodule recursively.

## Privacy / offline principle

The Android manifest does not request Internet access. Translation, model loading, history, speech input/TTS and planned OCR are designed to work locally on the device.
