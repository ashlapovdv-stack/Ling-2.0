# Ling 2.0

Offline neural translator for Android.

## MVP goals

- Android only
- Fully offline after a model is installed on the device
- Russian, English and Chinese (Simplified)
- Local neural-network inference
- No server/API dependency for translation
- Model layer isolated from UI so the model can be upgraded later

## Planned stack

- Kotlin
- Jetpack Compose + Material 3
- Local GGUF inference through llama.cpp / Android NDK
- Initial model target: Qwen3-0.6B Instruct, quantized GGUF

## Translation directions

- Russian ↔ English
- Russian ↔ Chinese
- English ↔ Chinese

## Architecture

```text
UI (Compose)
  ↓
TranslatorViewModel / state
  ↓
TranslationEngine
  ↓
LlamaCppTranslationEngine (JNI)
  ↓
GGUF model stored locally on device
```

## Current status

Project bootstrap. The first UI and the model-independent translation interface are being added. Neural inference is the next implementation step.

## Model policy

Large model files must not be committed to Git. The app will load a GGUF model from app storage / user-selected local storage.
