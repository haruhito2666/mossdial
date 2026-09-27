// Minimal JNI bridge for GGUF loading and text generation.
//
// This file is the only Mossdial-authored native code. It exposes the small set
// of llama.cpp entry points the app needs: one model, one context, one generation
// at a time, guarded by a per-session mutex. Conversation history is *not* kept
// in the KV cache between calls; the caller re-sends the full prompt.
//
// Known limitation: a token piece can end in the middle of a multi-byte UTF-8
// character, so the per-token stream callback may show a replacement character for
// that character. The string returned by nativeGenerate is always well formed.

#include <jni.h>

#include <android/log.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"

#define LOG_TAG "MossdialAi"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int32_t kMaxContext = 1 << 20;  // hard ceiling for a caller supplied n_ctx
constexpr int32_t kMaxBatch = 4096;
constexpr int32_t kMaxThreads = 64;
constexpr int32_t kMaxTokensPerCall = 4096;
constexpr size_t kMaxUtf16 = 1 << 20;

// The ggml logger is global. Every log line is mirrored into a thread local
// buffer so a failed call can report the reason that llama.cpp logged instead of
// returning a bare failure code.
std::string & log_buffer() {
    static thread_local std::string buffer;
    return buffer;
}

void capture_log(ggml_log_level level, const char * text, void * /*user_data*/) {
    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) {
        std::string & buffer = log_buffer();
        buffer += text;
        if (buffer.size() > 8192) {
            buffer.erase(0, buffer.size() - 8192);
        }
    }
}

std::string last_error(const char * fallback) {
    std::string & buffer = log_buffer();
    if (buffer.empty()) {
        return fallback;
    }
    while (!buffer.empty() && (buffer.back() == '\n' || buffer.back() == ' ')) {
        buffer.pop_back();
    }
    const size_t start = buffer.find_last_of('\n', buffer.size() - 1);
    std::string line = start == std::string::npos ? buffer : buffer.substr(start + 1);
    return line.empty() ? std::string(fallback) : line;
}

void log_clear() {
    log_buffer().clear();
}

// llama.cpp token pieces are UTF-8. NewStringUTF would mangle anything outside the
// BMP, so encode to UTF-16 and build the java string from that.
jstring to_jstring(JNIEnv * env, const std::string & utf8) {
    if (utf8.empty()) {
        return env->NewStringUTF("");
    }
    std::vector<jchar> utf16;
    utf16.reserve(utf8.size());
    const unsigned char * bytes = reinterpret_cast<const unsigned char *>(utf8.data());
    const size_t size = utf8.size();
    for (size_t i = 0; i < size;) {
        const unsigned char lead = bytes[i];
        uint32_t code_point;
        size_t extra;
        if (lead < 0x80) {
            code_point = lead;
            extra = 0;
        } else if ((lead & 0xE0) == 0xC0) {
            code_point = lead & 0x1Fu;
            extra = 1;
        } else if ((lead & 0xF0) == 0xE0) {
            code_point = lead & 0x0Fu;
            extra = 2;
        } else if ((lead & 0xF8) == 0xF0) {
            code_point = lead & 0x07u;
            extra = 3;
        } else {
            code_point = 0xFFFD;
            extra = 0;
        }
        if (i + extra >= size) {
            code_point = 0xFFFD;
            extra = 0;
        } else {
            for (size_t k = 1; k <= extra; ++k) {
                const unsigned char continuation = bytes[i + k];
                if ((continuation & 0xC0) != 0x80) {
                    code_point = 0xFFFD;
                    extra = 0;
                    break;
                }
                code_point = (code_point << 6) | (continuation & 0x3Fu);
            }
        }
        i += extra + 1;

        if (code_point >= 0x10000 && code_point <= 0x10FFFF) {
            code_point -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (code_point >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (code_point & 0x3FF)));
        } else {
            utf16.push_back(static_cast<jchar>(code_point));
        }
        if (utf16.size() >= kMaxUtf16) {
            break;
        }
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

std::string from_jstring(JNIEnv * env, jstring value) {
    if (value == nullptr) {
        return std::string();
    }
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return std::string();
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

class Session {
public:
    Session(llama_model * model, llama_context * ctx) : model_(model), ctx_(ctx) {}

    ~Session() {
        if (ctx_ != nullptr) {
            llama_free(ctx_);
        }
        if (model_ != nullptr) {
            llama_model_free(model_);
        }
    }

    Session(const Session &) = delete;
    Session & operator=(const Session &) = delete;

    llama_model * model() const { return model_; }
    llama_context * ctx() const { return ctx_; }
    std::mutex & mutex() { return mutex_; }

private:
    llama_model * model_;
    llama_context * ctx_;
    std::mutex mutex_;
};

std::mutex g_registry_mutex;
std::map<jlong, std::unique_ptr<Session>> g_sessions;
jlong g_next_handle = 1;
std::once_flag g_backend_once;

void init_backend_once() {
    std::call_once(g_backend_once, [] {
        llama_log_set(capture_log, nullptr);
        llama_backend_init();
        LOGI("llama.cpp %s backend initialised", llama_version());
    });
}

Session * session_for(JNIEnv * env, jlong handle) {
    std::lock_guard<std::mutex> guard(g_registry_mutex);
    auto it = g_sessions.find(handle);
    if (it == g_sessions.end()) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Model is not loaded");
        return nullptr;
    }
    return it->second.get();
}

void throw_runtime(JNIEnv * env, const std::string & message) {
    env->ThrowNew(env->FindClass("java/lang/RuntimeException"), message.c_str());
}

int32_t clamp_positive(int32_t value, int32_t min_value, int32_t max_value, int32_t fallback) {
    if (value <= 0) {
        return fallback;
    }
    if (value < min_value) {
        return min_value;
    }
    if (value > max_value) {
        return max_value;
    }
    return value;
}

std::string describe_model(const llama_model * model) {
    char desc[512] = {0};
    llama_model_desc(model, desc, sizeof(desc));
    const uint64_t bytes = llama_model_size(model);
    char summary[640];
    snprintf(summary, sizeof(summary), "%s|ctx_train=%d|vocab=%d|bytes=%llu",
             desc[0] != '\0' ? desc : "unknown model",
             static_cast<int>(llama_model_n_ctx_train(model)),
             static_cast<int>(llama_vocab_n_tokens(llama_model_get_vocab(model))),
             static_cast<unsigned long long>(bytes));
    return std::string(summary);
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_mossdial_ai_LlamaBridge_nativeInit(JNIEnv * /*env*/, jobject /*thiz*/) {
    init_backend_once();
}

JNIEXPORT jstring JNICALL
Java_com_mossdial_ai_LlamaBridge_nativeSystemInfo(JNIEnv * env, jobject /*thiz*/) {
    init_backend_once();
    std::string info = std::string("llama.cpp ") + llama_version();
    const size_t devices = ggml_backend_dev_count();
    for (size_t i = 0; i < devices; ++i) {
        ggml_backend_dev_t device = ggml_backend_dev_get(i);
        if (device == nullptr) {
            continue;
        }
        const char * name = ggml_backend_dev_name(device);
        const char * description = ggml_backend_dev_description(device);
        info += "; ";
        info += name != nullptr ? name : "unknown";
        if (description != nullptr && description[0] != '\0') {
            info += " (";
            info += description;
            info += ")";
        }
    }
    if (devices == 0) {
        info += "; no ggml backend device";
    }
    return to_jstring(env, info);
}

JNIEXPORT jlong JNICALL
Java_com_mossdial_ai_LlamaBridge_nativeLoadModel(
        JNIEnv * env, jobject /*thiz*/, jstring path, jint contextSize, jint threads, jint batchSize) {
    init_backend_once();
    const std::string file = from_jstring(env, path);
    if (file.empty()) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "No model path given");
        return 0;
    }

    const int32_t n_ctx = clamp_positive(contextSize, 128, kMaxContext, 2048);
    const int32_t n_threads = clamp_positive(threads, 1, kMaxThreads, 4);
    const int32_t n_batch = clamp_positive(batchSize, 32, kMaxBatch, 512);

    log_clear();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;

    llama_model * model = llama_model_load_from_file(file.c_str(), model_params);
    if (model == nullptr) {
        throw_runtime(env, "Could not load " + file + ": " + last_error("unknown llama.cpp error"));
        return 0;
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(std::min<int32_t>(n_ctx, llama_model_n_ctx_train(model)));
    context_params.n_batch = static_cast<uint32_t>(n_batch);
    context_params.n_ubatch = static_cast<uint32_t>(n_batch);
    context_params.n_threads = n_threads;
    context_params.n_threads_batch = n_threads;
    context_params.no_perf = true;

    llama_context * ctx = llama_init_from_model(model, context_params);
    if (ctx == nullptr) {
        llama_model_free(model);
        throw_runtime(env, "Could not create a context for " + file + ": " + last_error("context allocation failed"));
        return 0;
    }

    auto session = std::unique_ptr<Session>(new Session(model, ctx));
    std::lock_guard<std::mutex> guard(g_registry_mutex);
    const jlong handle = g_next_handle++;
    g_sessions.emplace(handle, std::move(session));
    LOGI("loaded %s handle=%lld", describe_model(model).c_str(), static_cast<long long>(handle));
    return handle;
}

JNIEXPORT jstring JNICALL
Java_com_mossdial_ai_LlamaBridge_nativeModelInfo(JNIEnv * env, jobject /*thiz*/, jlong handle) {
    init_backend_once();
    Session * session = session_for(env, handle);
    if (session == nullptr) {
        return nullptr;
    }
    std::lock_guard<std::mutex> guard(session->mutex());
    return to_jstring(env, describe_model(session->model()));
}

JNIEXPORT void JNICALL
Java_com_mossdial_ai_LlamaBridge_nativeUnloadModel(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    std::unique_ptr<Session> doomed;
    {
        std::lock_guard<std::mutex> guard(g_registry_mutex);
        auto it = g_sessions.find(handle);
        if (it == g_sessions.end()) {
            return;
        }
        doomed = std::move(it->second);
        g_sessions.erase(it);
    }
    if (doomed) {
        doomed.reset();
    }
}

JNIEXPORT jstring JNICALL
Java_com_mossdial_ai_LlamaBridge_nativeGenerate(
        JNIEnv *   env,
        jobject    /*thiz*/,
        jlong      handle,
        jstring    prompt,
        jint       maxTokens,
        jfloat     temperature,
        jint       topK,
        jfloat     topP,
        jint       seed,
        jobjectArray stopStrings,
        jobject    tokenSink) {
    init_backend_once();
    Session * session = session_for(env, handle);
    if (session == nullptr) {
        return nullptr;
    }

    const std::string text = from_jstring(env, prompt);
    const int32_t budget = clamp_positive(maxTokens, 1, kMaxTokensPerCall, 256);

    std::vector<std::string> stops;
    if (stopStrings != nullptr) {
        const jsize count = env->GetArrayLength(stopStrings);
        stops.reserve(static_cast<size_t>(count));
        for (jsize i = 0; i < count; ++i) {
            jstring element = static_cast<jstring>(env->GetObjectArrayElement(stopStrings, i));
            if (element != nullptr) {
                std::string stop = from_jstring(env, element);
                if (!stop.empty()) {
                    stops.push_back(stop);
                }
                env->DeleteLocalRef(element);
            }
        }
    }

    jmethodID on_token = nullptr;
    if (tokenSink != nullptr) {
        jclass sink_class = env->GetObjectClass(tokenSink);
        if (sink_class != nullptr) {
            on_token = env->GetMethodID(sink_class, "onToken", "(Ljava/lang/String;)V");
            env->DeleteLocalRef(sink_class);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                throw_runtime(env, "Token sink does not declare onToken(String)");
                return nullptr;
            }
        }
    }

    std::lock_guard<std::mutex> guard(session->mutex());
    log_clear();

    llama_context * ctx = session->ctx();
    const llama_vocab * vocab = llama_model_get_vocab(session->model());
    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(ctx));
    const int32_t n_batch = static_cast<int32_t>(llama_n_batch(ctx));

    llama_memory_clear(llama_get_memory(ctx), true);

    // The prompt and the reply share the context: cap the reply so the prompt keeps
    // at least half of it, then truncate a prompt that still does not fit.
    const int32_t reply_budget = std::max(1, std::min(budget, n_ctx / 2));
    const int32_t prompt_room = n_ctx - reply_budget - 1;
    if (prompt_room <= 0) {
        throw_runtime(env, "The context of " + std::to_string(n_ctx) + " tokens is too small for one reply");
        return nullptr;
    }

    std::vector<llama_token> tokens(static_cast<size_t>(prompt_room));
    const int32_t token_count = llama_tokenize(
            vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(), prompt_room,
            true, /* add_special = */ true);
    if (token_count < 0) {
        throw_runtime(env,
                "Could not tokenize a prompt of " + std::to_string(text.size()) + " characters into " +
                    std::to_string(prompt_room) + " tokens");
        return nullptr;
    }
    if (token_count == 0) {
        return to_jstring(env, std::string());
    }

    llama_batch batch = llama_batch_init(n_batch, 0, 1);
    const int32_t prompt_tokens = token_count;
    for (int32_t offset = 0; offset < prompt_tokens;) {
        const int32_t size = std::min(n_batch, prompt_tokens - offset);
        batch.n_tokens = size;
        for (int32_t i = 0; i < size; ++i) {
            batch.token[i] = tokens[static_cast<size_t>(offset + i)];
            batch.pos[i] = offset + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[0][i] = 0;
            batch.logits[i] = i == size - 1;
        }
        if (llama_decode(ctx, batch) != 0) {
            llama_batch_free(batch);
            throw_runtime(env, "Failed to decode the prompt: " + last_error("llama_decode returned an error"));
            return nullptr;
        }
        offset += size;
    }

    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    sampler_params.no_perf = true;
    llama_sampler * chain = llama_sampler_chain_init(sampler_params);
    if (chain == nullptr) {
        llama_batch_free(batch);
        throw_runtime(env, "Could not create a sampler");
        return nullptr;
    }
    if (topK > 0) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(topK));
    }
    if (topP > 0.0f && topP < 1.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(topP, 1));
    }
    if (temperature > 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
    }
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(
                chain, llama_sampler_init_dist(seed == 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed)));
    }

    std::string output;
    bool stopped = false;
    int32_t produced = 0;
    std::vector<char> piece(256);

    while (produced < reply_budget && !stopped) {
        const llama_token token = llama_sampler_sample(chain, ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }

        const int32_t length = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()),
                                                     0, /* special = */ false);
        if (length < 0) {
            piece.resize(static_cast<size_t>(-length));
            if (llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, false) < 0) {
                break;
            }
        } else {
            piece.resize(static_cast<size_t>(length));
        }

        const std::string fragment(piece.data(), piece.size());
        output += fragment;
        ++produced;
        llama_sampler_accept(chain, token);

        for (const std::string & stop : stops) {
            if (output.find(stop) != std::string::npos) {
                stopped = true;
                break;
            }
        }

        batch.n_tokens = 1;
        batch.token[0] = token;
        batch.pos[0] = prompt_tokens + produced - 1;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = true;
        if (llama_decode(ctx, batch) != 0) {
            llama_sampler_free(chain);
            llama_batch_free(batch);
            throw_runtime(env, "Generation stopped early: " + last_error("llama_decode returned an error"));
            return nullptr;
        }

        if (on_token != nullptr) {
            jstring chunk = to_jstring(env, fragment);
            env->CallVoidMethod(tokenSink, on_token, chunk);
            env->DeleteLocalRef(chunk);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                llama_sampler_free(chain);
                llama_batch_free(batch);
                throw_runtime(env, "The token sink threw an exception");
                return nullptr;
            }
        }
    }

    if (stopped) {
        for (const std::string & stop : stops) {
            const size_t at = output.find(stop);
            if (at != std::string::npos) {
                output.erase(at);
                break;
            }
        }
    }

    llama_sampler_free(chain);
    llama_batch_free(batch);
    return to_jstring(env, output);
}

}  // extern "C"
