#include <android/log.h>
#include <jni.h>
#include <iomanip>
#include <math.h>
#include <string>
#include <unistd.h>
#include <vector>

#include "llama.h"
#include "common.h"

#define TAG "llama-android.cpp"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

jclass la_int_var;
jmethodID la_int_var_value;
jmethodID la_int_var_inc;

std::string cached_token_chars;

static bool is_valid_utf8(const char *string) {
    if (!string) return true;
    const unsigned char *bytes = reinterpret_cast<const unsigned char *>(string);
    int num;
    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            num = 4;
        } else {
            return false;
        }
        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) return false;
            bytes += 1;
        }
    }
    return true;
}

static void log_callback(ggml_log_level level, const char *fmt, void *data) {
    if (level == GGML_LOG_LEVEL_ERROR) __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, data);
    else if (level == GGML_LOG_LEVEL_INFO) __android_log_print(ANDROID_LOG_INFO, TAG, fmt, data);
    else if (level == GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, fmt, data);
    else __android_log_print(ANDROID_LOG_DEBUG, TAG, fmt, data);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_android_llama_cpp_LLamaAndroid_load_1model(JNIEnv *env, jobject, jstring filename) {
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;

    const char *path_to_model = env->GetStringUTFChars(filename, nullptr);
    LOGi("Loading model from %s", path_to_model);

    llama_model *model = llama_model_load_from_file(path_to_model, model_params);
    env->ReleaseStringUTFChars(filename, path_to_model);

    if (!model) {
        LOGe("load_model() failed");
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "load_model() failed");
        return 0;
    }

    return reinterpret_cast<jlong>(model);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_free_1model(JNIEnv *, jobject, jlong model_ptr) {
    if (model_ptr != 0) {
        llama_model_free(reinterpret_cast<llama_model *>(model_ptr));
    }
}

extern "C"
JNIEXPORT jlong JNICALL
Java_android_llama_cpp_LLamaAndroid_new_1context(JNIEnv *env, jobject, jlong jmodel) {
    auto *model = reinterpret_cast<llama_model *>(jmodel);
    if (!model) {
        LOGe("new_context(): model cannot be null");
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Model cannot be null");
        return 0;
    }

    int n_threads = std::max(1, std::min(8, static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN)) - 2));
    LOGi("Using %d threads for llama context", n_threads);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 4096;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;

    llama_context *context = llama_init_from_model(model, ctx_params);
    if (!context) {
        LOGe("llama_init_from_model() returned null");
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "llama_init_from_model() failed");
        return 0;
    }

    return reinterpret_cast<jlong>(context);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_free_1context(JNIEnv *, jobject, jlong context_ptr) {
    if (context_ptr != 0) {
        llama_free(reinterpret_cast<llama_context *>(context_ptr));
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_backend_1init(JNIEnv *, jobject, jboolean numa) {
    llama_backend_init();
    if (numa) {
        llama_numa_init(GGML_NUMA_STRATEGY_DISTRIBUTE);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_backend_1free(JNIEnv *, jobject) {
    llama_backend_free();
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_log_1to_1android(JNIEnv *, jobject) {
    llama_log_set(log_callback, nullptr);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_android_llama_cpp_LLamaAndroid_new_1batch(JNIEnv *, jobject, jint n_tokens, jint embd, jint n_seq_max) {
    llama_batch *batch = new llama_batch();
    *batch = llama_batch_init(n_tokens, embd, n_seq_max);
    return reinterpret_cast<jlong>(batch);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_free_1batch(JNIEnv *, jobject, jlong batch_pointer) {
    if (batch_pointer != 0) {
        llama_batch *batch = reinterpret_cast<llama_batch *>(batch_pointer);
        llama_batch_free(*batch);
        delete batch;
    }
}

extern "C"
JNIEXPORT jlong JNICALL
Java_android_llama_cpp_LLamaAndroid_new_1sampler(JNIEnv *, jobject) {
    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler *smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, 1.12f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.92f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.75f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return reinterpret_cast<jlong>(smpl);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_free_1sampler(JNIEnv *, jobject, jlong sampler_pointer) {
    if (sampler_pointer != 0) {
        llama_sampler_free(reinterpret_cast<llama_sampler *>(sampler_pointer));
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_kv_1cache_1clear(JNIEnv *, jobject, jlong context) {
    if (context != 0) {
        llama_kv_self_clear(reinterpret_cast<llama_context *>(context));
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_android_llama_cpp_LLamaAndroid_completion_1init(
        JNIEnv *env,
        jobject,
        jlong context_pointer,
        jlong batch_pointer,
        jstring jtext,
        jint n_len
) {
    cached_token_chars.clear();

    const char *text = env->GetStringUTFChars(jtext, nullptr);
    const auto *context = reinterpret_cast<llama_context *>(context_pointer);
    auto *batch = reinterpret_cast<llama_batch *>(batch_pointer);
    const llama_model *model = llama_get_model(context);
    const llama_vocab *vocab = llama_model_get_vocab(model);

    const auto tokens_list = common_tokenize(vocab, text, true, true);
    env->ReleaseStringUTFChars(jtext, text);

    auto n_ctx = llama_n_ctx(context);
    auto n_kv_req = static_cast<int>(tokens_list.size()) + n_len;

    if (n_kv_req > n_ctx) {
        LOGe("error: n_kv_req (%d) > n_ctx (%d)", n_kv_req, n_ctx);
    }

    common_batch_clear(*batch);
    for (size_t i = 0; i < tokens_list.size(); ++i) {
        common_batch_add(*batch, tokens_list[i], static_cast<llama_pos>(i), {0}, false);
    }
    if (batch->n_tokens > 0) {
        batch->logits[batch->n_tokens - 1] = true;
    }

    if (llama_decode(const_cast<llama_context *>(context), *batch) != 0) {
        LOGe("llama_decode() failed during prompt prefill");
    }

    return batch->n_tokens;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_android_llama_cpp_LLamaAndroid_completion_1loop(
        JNIEnv *env,
        jobject,
        jlong context_pointer,
        jlong batch_pointer,
        jlong sampler_pointer,
        jint n_len,
        jobject intvar_ncur
) {
    const auto *context = reinterpret_cast<llama_context *>(context_pointer);
    auto *batch = reinterpret_cast<llama_batch *>(batch_pointer);
    auto *sampler = reinterpret_cast<llama_sampler *>(sampler_pointer);
    const llama_model *model = llama_get_model(context);
    const llama_vocab *vocab = llama_model_get_vocab(model);

    if (!la_int_var) la_int_var = env->GetObjectClass(intvar_ncur);
    if (!la_int_var_value) la_int_var_value = env->GetMethodID(la_int_var, "getValue", "()I");
    if (!la_int_var_inc) la_int_var_inc = env->GetMethodID(la_int_var, "inc", "()V");

    const auto new_token_id = llama_sampler_sample(sampler, const_cast<llama_context *>(context), -1);
    const auto n_cur = env->CallIntMethod(intvar_ncur, la_int_var_value);

    if (llama_vocab_is_eog(vocab, new_token_id) || n_cur == n_len) {
        return nullptr;
    }

    auto new_token_chars = common_token_to_piece(const_cast<llama_context *>(context), new_token_id);
    cached_token_chars += new_token_chars;

    jstring new_token = nullptr;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        new_token = env->NewStringUTF(cached_token_chars.c_str());
        cached_token_chars.clear();
    } else {
        new_token = env->NewStringUTF("");
    }

    common_batch_clear(*batch);
    common_batch_add(*batch, new_token_id, n_cur, {0}, true);
    env->CallVoidMethod(intvar_ncur, la_int_var_inc);

    if (llama_decode(const_cast<llama_context *>(context), *batch) != 0) {
        LOGe("llama_decode() failed in completion_loop");
    }

    return new_token;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_android_llama_cpp_LLamaAndroid_system_1info(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}
