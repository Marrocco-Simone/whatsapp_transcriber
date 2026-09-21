#include <jni.h>
#include <algorithm>
#include <string>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "whisper_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_dev_simone_watranscriber_whisper_Whisper_nativeInit(
        JNIEnv *env, jobject, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(model_path, path);
    LOGI("model loaded: %p", ctx);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_simone_watranscriber_whisper_Whisper_nativeFree(
        JNIEnv *, jobject, jlong handle) {
    if (handle != 0) {
        whisper_free(reinterpret_cast<whisper_context *>(handle));
    }
}

struct progress_state {
    JNIEnv *env;
    jobject listener;
    jmethodID method;
    int64_t total_cs;
    int last_percent;
};

/**
 * whisper calls this on the thread that called whisper_full, so the JNIEnv of that call
 * stays valid. Segment times are in centiseconds.
 */
static void on_new_segment(struct whisper_context *, struct whisper_state *state,
                           int, void *user_data) {
    auto *progress = static_cast<progress_state *>(user_data);
    const int n_segments = whisper_full_n_segments_from_state(state);
    if (n_segments <= 0 || progress->total_cs <= 0) {
        return;
    }
    const int64_t t1 = whisper_full_get_segment_t1_from_state(state, n_segments - 1);
    int percent = static_cast<int>(100 * t1 / progress->total_cs);
    if (percent > 100) {
        percent = 100;
    }
    if (percent <= progress->last_percent) {
        return;
    }
    progress->last_percent = percent;
    progress->env->CallVoidMethod(progress->listener, progress->method, percent);
    if (progress->env->ExceptionCheck()) {
        progress->env->ExceptionClear();
    }
}

/**
 * whisper encodes 30 s windows of 1500 positions of 20 ms. A window sized to the audio
 * skips the padding, which is faster and keeps the model from inventing text for the
 * silence. limit: 1.5x the audio length with a floor of 320, set from one 3 s note.
 * A tighter window cuts words at the end.
 */
static int audio_ctx_for(jsize n_samples) {
    const double seconds = static_cast<double>(n_samples) / WHISPER_SAMPLE_RATE;
    const int positions = static_cast<int>(seconds * 50 * 1.5);
    return std::min(1500, std::max(320, positions));
}

static jbyteArray to_byte_array(JNIEnv *env, const std::string &text) {
    const auto size = static_cast<jsize>(text.size());
    jbyteArray array = env->NewByteArray(size);
    if (array != nullptr && size > 0) {
        env->SetByteArrayRegion(array, 0, size, reinterpret_cast<const jbyte *>(text.data()));
    }
    return array;
}

/**
 * Returns UTF-8 bytes rather than a jstring, because NewStringUTF takes modified UTF-8 and
 * rejects the four byte sequences that whisper can emit.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_simone_watranscriber_whisper_Whisper_nativeTranscribe(
        JNIEnv *env, jobject thiz, jlong handle, jfloatArray pcm,
        jstring language, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) {
        return to_byte_array(env, "");
    }

    const jsize n_samples = env->GetArrayLength(pcm);
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    if (samples == nullptr) {
        LOGI("could not read the samples");
        return to_byte_array(env, "");
    }
    const char *lang = env->GetStringUTFChars(language, nullptr);
    if (lang == nullptr) {
        env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
        LOGI("could not read the language");
        return to_byte_array(env, "");
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = lang;
    params.audio_ctx = audio_ctx_for(n_samples);
    params.translate = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;

    progress_state progress {
        env, thiz, env->GetMethodID(env->GetObjectClass(thiz), "onProgress", "(I)V"),
        n_samples / (WHISPER_SAMPLE_RATE / 100), 0,
    };
    if (progress.method == nullptr) {
        env->ExceptionClear();
        LOGI("no progress method, reporting no progress");
    } else {
        params.new_segment_callback = on_new_segment;
        params.new_segment_callback_user_data = &progress;
    }

    std::string text;
    if (whisper_full(ctx, params, samples, n_samples) == 0) {
        const int n_segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < n_segments; i++) {
            text += whisper_full_get_segment_text(ctx, i);
        }
    } else {
        LOGI("whisper_full failed");
    }

    env->ReleaseStringUTFChars(language, lang);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    return to_byte_array(env, text);
}
