#include <jni.h>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>
#include "needle.h"

static void throw_error(JNIEnv *env, const std::string &message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message.c_str());
}

static std::string last_error() {
    const char *error = needle_last_error();
    return error != nullptr ? error : "unknown needle error";
}

/** needle_load has no counterpart that frees a model, so the bytes stay for the process. */
static std::vector<unsigned char> weights;

extern "C" JNIEXPORT void JNICALL
Java_dev_simone_watranscriber_whisper_Whistle_nativeLoad(
        JNIEnv *env, jobject, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    std::ifstream file(path, std::ios::binary);
    env->ReleaseStringUTFChars(model_path, path);
    if (!file) {
        throw_error(env, "could not open the Whistle model");
        return;
    }
    weights.assign(std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>());
    if (needle_load(weights.data(), weights.size()) < 0) {
        throw_error(env, last_error());
    }
}

/**
 * Returns the JSON as UTF-8 bytes rather than a jstring, because NewStringUTF takes
 * modified UTF-8 and rejects four byte sequences.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_simone_watranscriber_whisper_Whistle_nativeTranscribe(
        JNIEnv *env, jobject, jfloatArray pcm, jstring language) {
    const jsize n_samples = env->GetArrayLength(pcm);
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    if (samples == nullptr) {
        return nullptr;
    }
    const char *lang = language != nullptr ? env->GetStringUTFChars(language, nullptr) : nullptr;
    if (language != nullptr && lang == nullptr) {
        env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
        return nullptr;
    }

    std::vector<char> out(1 << 18);
    const int code = needle_transcribe(samples, n_samples, lang, nullptr, 0, out.data(),
                                       static_cast<int>(out.size()));

    if (lang != nullptr) {
        env->ReleaseStringUTFChars(language, lang);
    }
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    if (code < 0) {
        throw_error(env, last_error());
        return nullptr;
    }

    const auto size = static_cast<jsize>(strnlen(out.data(), out.size()));
    jbyteArray array = env->NewByteArray(size);
    if (array != nullptr && size > 0) {
        env->SetByteArrayRegion(array, 0, size, reinterpret_cast<const jbyte *>(out.data()));
    }
    return array;
}
