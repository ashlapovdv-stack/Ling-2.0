# Offline model implementation

## Initial target

Target for the first device tests: `Qwen3-0.6B` GGUF using a Q4_K_M quantization.

The architecture does not hard-code Qwen. Any compatible causal GGUF with a usable llama.cpp tokenizer/chat template can be selected from Android storage.

## Runtime

Ling 2.0 now uses llama.cpp compiled for Android `arm64-v8a` through NDK/CMake.

The layers are intentionally separated:

```text
Compose UI
  ↓
TranslationEngine
  ↓
LlamaTranslationEngine
  ↓
LlamaNative JNI
  ↓
llama.cpp
```

The first implementation is CPU-only. This provides a stable baseline before adding optional GPU/Vulkan acceleration.

## Model installation

The APK does not contain a GGUF model and the repository does not store model binaries.

The MVP flow is:

1. User opens **Настройки → Локальная модель**.
2. Android's document picker selects a `.gguf` file.
3. `ModelRepository` verifies the `GGUF` magic bytes.
4. The file is copied to app-private `files/models/model.gguf`.
5. The native engine memory-maps the local model.
6. The selected display name/path are persisted for automatic loading on the next app start.

Removing the model unloads native state and deletes the private model copy.

## Translation generation

The translation prompt requests only the translated text and includes `/no_think` for models such as Qwen3 that support a non-thinking mode.

Current generation baseline:

- context: 4096 tokens;
- batch: 512 tokens;
- max output: 128–768 tokens based on input length;
- top-k: 20;
- top-p: 0.8;
- temperature: 0.7;
- CPU threads: up to 4;
- no GPU layers.

These are starting values, not final performance settings. They should be benchmarked on representative Android devices.

## Offline/privacy rule

The app manifest intentionally has no `INTERNET` permission. Model import uses Android local document access only. Translation does not have a network fallback.

## Remaining phase-1 validation

- make the Android CI build green;
- install the generated APK on a physical arm64 Android device;
- load the chosen Qwen3-0.6B Q4_K_M GGUF;
- test all six translation directions;
- measure first-token latency, tokens/sec, peak memory and thermals;
- tune context/thread/sampler settings if needed.
