# Ling 2.0

Offline neural translator for Android.

## Product scope

Ling 2.0 is an Android-only translator designed to work locally on the device.

Supported languages:

- Russian
- English
- Simplified Chinese

Planned translation directions:

- Russian ↔ English
- Russian ↔ Chinese
- English ↔ Chinese

## Main modes

The app has four bottom navigation tiles:

1. **Перевод** — text translation (phase 1)
2. **Камера** — translation from a camera photo or gallery image (later phase)
3. **Диалог** — two-way voice conversation translation (later phase)
4. **Настройки** — model information, app information and **Настройки → История**

History is intentionally not a separate bottom navigation item.

## Phase 1

Implemented foundation:

- Kotlin + Jetpack Compose + Material 3 UI
- Russian / English / Chinese language selection
- source/target language swap
- text input up to 5000 characters
- model-independent `TranslationEngine`
- offline status/model state in the UI
- translation result card with copy and clear actions
- local translation history storage (up to 200 successful translations)
- Settings → History screen with clear-history action
- placeholders for Camera and Dialog so the navigation architecture does not need to be redesigned later
- no server translation fallback

Still required to complete phase 1:

- llama.cpp Android/JNI integration
- local GGUF loading
- real on-device neural translation
- model lifecycle/error handling and performance tuning

## Planned inference stack

- Local GGUF inference through llama.cpp / Android NDK
- Initial model target: Qwen3-0.6B-class multilingual GGUF, subject to device performance testing
- Large model files must not be committed to Git

## Architecture

```text
Compose UI
  ↓
App state / navigation
  ↓
TranslationEngine
  ↓
LlamaCppTranslationEngine (JNI)  ← next implementation slice
  ↓
GGUF model stored locally on device

Settings
  ↓
History
  ↓
Local SharedPreferences storage
```

## Privacy / offline principle

The Android manifest does not request Internet access. Translation, history and future speech/OCR functionality are intended to run locally on the device.
