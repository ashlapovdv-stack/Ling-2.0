#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include "llama.h"

namespace {

constexpr const char * LOG_TAG = "LingLlama";
constexpr uint32_t CONTEXT_SIZE = 4096;
constexpr uint32_t BATCH_SIZE = 512;
constexpr int MAX_GENERATION_TOKENS = 1792;

std::mutex g_mutex;
llama_model * g_model = nullptr;
const llama_vocab * g_vocab = nullptr;
bool g_backend_initialized = false;

void log_error(const std::string & message) {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", message.c_str());
}

void throw_java(JNIEnv * env, const char * class_name, const std::string & message) {
    jclass clazz = env->FindClass(class_name);
    if (clazz != nullptr) {
        env->ThrowNew(clazz, message.c_str());
    }
}

std::string from_jstring(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void free_model_locked() {
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
        g_vocab = nullptr;
    }
}

std::string token_piece(llama_token token) {
    char small[256];
    int size = llama_token_to_piece(g_vocab, token, small, sizeof(small), 0, true);
    if (size >= 0) {
        return std::string(small, size);
    }

    std::vector<char> buffer(static_cast<size_t>(-size));
    size = llama_token_to_piece(g_vocab, token, buffer.data(), buffer.size(), 0, true);
    if (size < 0) return {};
    return std::string(buffer.data(), size);
}

std::string apply_chat_template(const std::string & user_prompt) {
    const char * model_template = llama_model_chat_template(g_model, nullptr);
    if (model_template == nullptr || model_template[0] == '\0') {
        return user_prompt;
    }

    const llama_chat_message message = {"user", user_prompt.c_str()};
    const int32_t required = llama_chat_apply_template(
        model_template,
        &message,
        1,
        true,
        nullptr,
        0
    );
    if (required <= 0) return user_prompt;

    std::vector<char> buffer(static_cast<size_t>(required) + 1, '\0');
    const int32_t written = llama_chat_apply_template(
        model_template,
        &message,
        1,
        true,
        buffer.data(),
        static_cast<int32_t>(buffer.size())
    );
    if (written <= 0) return user_prompt;
    return std::string(buffer.data(), static_cast<size_t>(written));
}

std::vector<llama_token> tokenize(const std::string & text) {
    int count = llama_tokenize(
        g_vocab,
        text.c_str(),
        static_cast<int32_t>(text.size()),
        nullptr,
        0,
        true,
        true
    );
    if (count >= 0) return {};

    count = -count;
    std::vector<llama_token> tokens(static_cast<size_t>(count));
    const int written = llama_tokenize(
        g_vocab,
        text.c_str(),
        static_cast<int32_t>(text.size()),
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        true,
        true
    );
    if (written < 0) return {};
    tokens.resize(static_cast<size_t>(written));
    return tokens;
}

std::string generate_locked(const std::string & raw_prompt, int max_tokens) {
    if (g_model == nullptr || g_vocab == nullptr) throw std::runtime_error("Модель не загружена");
    if (raw_prompt.empty()) throw std::runtime_error("Пустой запрос");

    max_tokens = std::clamp(max_tokens, 16, MAX_GENERATION_TOKENS);
    const std::string prompt = apply_chat_template(raw_prompt);
    std::vector<llama_token> prompt_tokens = tokenize(prompt);

    if (prompt_tokens.empty()) throw std::runtime_error("Не удалось токенизировать запрос");
    if (prompt_tokens.size() + static_cast<size_t>(max_tokens) + 8 > CONTEXT_SIZE) {
        throw std::runtime_error("Фрагмент текста слишком длинный для контекста 4096 токенов");
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = CONTEXT_SIZE;
    ctx_params.n_batch = BATCH_SIZE;
    const unsigned hw_threads = std::max(2u, std::thread::hardware_concurrency());
    const unsigned threads = std::min(4u, hw_threads);
    ctx_params.n_threads = static_cast<int32_t>(threads);
    ctx_params.n_threads_batch = static_cast<int32_t>(threads);
    ctx_params.no_perf = true;

    llama_context * ctx = llama_init_from_model(g_model, ctx_params);
    if (ctx == nullptr) throw std::runtime_error("Не удалось создать контекст llama.cpp");

    llama_sampler * sampler = nullptr;
    try {
        if (llama_model_has_encoder(g_model)) {
            throw std::runtime_error("Encoder-decoder модели пока не поддерживаются");
        }

        llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
        sampler_params.no_perf = true;
        sampler = llama_sampler_chain_init(sampler_params);
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

        size_t offset = 0;
        while (offset < prompt_tokens.size()) {
            const size_t count = std::min<size_t>(BATCH_SIZE, prompt_tokens.size() - offset);
            llama_batch batch = llama_batch_get_one(prompt_tokens.data() + offset, static_cast<int32_t>(count));
            if (llama_decode(ctx, batch) != 0) {
                throw std::runtime_error("Ошибка обработки входного текста");
            }
            offset += count;
        }

        std::string output;
        output.reserve(static_cast<size_t>(max_tokens) * 4);

        for (int generated = 0; generated < max_tokens; ++generated) {
            const llama_token token = llama_sampler_sample(sampler, ctx, -1);
            if (llama_vocab_is_eog(g_vocab, token)) break;

            output += token_piece(token);

            llama_token mutable_token = token;
            llama_batch batch = llama_batch_get_one(&mutable_token, 1);
            if (llama_decode(ctx, batch) != 0) {
                throw std::runtime_error("Ошибка генерации перевода");
            }
        }

        llama_sampler_free(sampler);
        llama_free(ctx);
        return output;
    } catch (...) {
        if (sampler != nullptr) llama_sampler_free(sampler);
        llama_free(ctx);
        throw;
    }
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_ling20_translator_LlamaNative_nativeLoadModel(JNIEnv * env, jobject, jstring model_path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    try {
        const std::string path = from_jstring(env, model_path);
        if (path.empty()) throw std::runtime_error("Путь к модели пуст");

        if (!g_backend_initialized) {
            ggml_backend_load_all();
            llama_backend_init();
            g_backend_initialized = true;
        }

        free_model_locked();

        llama_model_params params = llama_model_default_params();
        params.n_gpu_layers = 0;
        params.load_mode = LLAMA_LOAD_MODE_MMAP;

        g_model = llama_model_load_from_file(path.c_str(), params);
        if (g_model == nullptr) throw std::runtime_error("Не удалось открыть GGUF модель");

        g_vocab = llama_model_get_vocab(g_model);
        if (g_vocab == nullptr) {
            free_model_locked();
            throw std::runtime_error("В модели отсутствует словарь");
        }
    } catch (const std::exception & error) {
        log_error(error.what());
        free_model_locked();
        throw_java(env, "java/lang/IllegalStateException", error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_ling20_translator_LlamaNative_nativeUnloadModel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    free_model_locked();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ling20_translator_LlamaNative_nativeGenerate(
    JNIEnv * env,
    jobject,
    jstring prompt,
    jint max_tokens
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    try {
        const std::string result = generate_locked(from_jstring(env, prompt), max_tokens);
        return env->NewStringUTF(result.c_str());
    } catch (const std::exception & error) {
        log_error(error.what());
        throw_java(env, "java/lang/IllegalStateException", error.what());
        return nullptr;
    }
}
