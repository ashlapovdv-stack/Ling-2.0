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

1. **Перевод** — text translation (phase 1)
2. **Камера** — translation from a camera photo or gallery image (later phase)
3. **Диалог** — two-way voice conversation translation (later phase)
4. **Настройки** — local model, app information and **Настройки → История**

History is intentionally not a separate bottom navigation item.

## Phase 1 status

Implemented and CI-build verified:

- Kotlin + Jetpack Compose + Material 3 UI
- Russian / English / Chinese language selection
- source/target language swap
- text input up to 5000 characters
- local translation history (up to 200 successful translations)
- Settings → History with clear-history action
- Settings → Local model
- Android Storage Access Framework picker for `.gguf` models
- GGUF header validation and copy into app-private storage
- llama.cpp pinned as a Git submodule
- Android NDK/CMake JNI bridge
- CPU-only on-device model loading and token generation
- `LlamaTranslationEngine` connected to the Translate button
- inference runs off the UI thread
- deterministic translation decoding
- safe fallback for GGUF files without a supported chat template
- no server/API translation fallback
- no Android `INTERNET` permission
- debug APK successfully built in GitHub Actions with Kotlin + NDK + CMake + llama.cpp

Camera, dialog and voice input are intentionally left for later phases.

## Local model

The model is not committed to Git and is not bundled into the APK.

For the first MVP:

1. copy a compatible `.gguf` model to the Android device;
2. open **Настройки → Локальная модель**;
3. tap **Выбрать GGUF модель**;
4. select the file;
5. Ling copies it into app-private storage and loads it through llama.cpp.

Initial physical-device test target: **Qwen3-0.6B GGUF Q4_K_M**.

The first native build is CPU-only and `arm64-v8a`. GPU acceleration can be evaluated after the baseline translator is stable.

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

The project currently builds for `arm64-v8a` using Java 17, stable Android API 36, Android NDK and CMake. GitHub Actions runs a debug APK build on pushes to `main` and checks out the llama.cpp submodule recursively.

The remaining phase-1 validation is a physical Android test with a real GGUF model: load the model, run all six language directions, and measure speed/RAM/stability.

## Privacy / offline principle

The Android manifest does not request Internet access. Translation, model loading and history all work locally on the device. Future speech/OCR functionality is also intended to remain offline.
