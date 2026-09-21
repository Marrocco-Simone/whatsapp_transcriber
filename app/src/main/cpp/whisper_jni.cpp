#include <jni.h>
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
        JNIEnv *env, jobject, jlong handle, jfloatArray pcm,
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
    params.translate = false;
    params.no_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;

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
