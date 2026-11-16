#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <memory>
#include <new>
#include <mutex>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>
#include "jni_utf.h"
#include "whisper_metric_utils.h"
#include "whisper.h"

#ifndef NDEBUG
#define TV_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "TransVibeNative", __VA_ARGS__)
#else
#define TV_LOGE(...) ((void)0)
#endif

namespace {

constexpr jint MAX_THREADS = 8;
constexpr jsize MAX_AUDIO_SAMPLES = 70 * 16000;
constexpr int MAX_SEGMENTS = 10000;

class NativeFailure final : public std::runtime_error {
public:
    NativeFailure(std::string code, std::string message)
        : std::runtime_error(std::move(message)), code_(std::move(code)) {}
    const std::string& code() const noexcept { return code_; }
private:
    std::string code_;
};

[[noreturn]] void fail(const char* code, const char* message) {
    throw NativeFailure(code ? code : "UNKNOWN", message ? message : "Native failure");
}

struct WhisperDeleter {
    void operator()(whisper_context* p) const noexcept { if (p) whisper_free(p); }
};

struct NativeContext final {
    explicit NativeContext(whisper_context* p) : ctx(p) {
        if (!p) fail("INIT_FAILED", "Whisper context is null");
    }
    std::unique_ptr<whisper_context, WhisperDeleter> ctx;
    std::atomic_bool cancelled{false};
    std::mutex inferenceMutex;
    // Reused while holding inferenceMutex. Avoids Java-side FloatArray slices and repeated native allocations.
    std::vector<float> audioScratch;
};

class ScopedStandardUtf8 final {
public:
    ScopedStandardUtf8(JNIEnv* env, jstring value, bool required) {
        if (!env) fail("INVALID_ARGUMENT", "JNI environment is null");
        if (!value) {
            if (required) fail("INVALID_ARGUMENT", "Required string is null");
            return;
        }
        const jsize length = env->GetStringLength(value);
        if (env->ExceptionCheck()) fail("INVALID_ARGUMENT", "Cannot read Java string length");
        const jchar* chars = env->GetStringChars(value, nullptr);
        if (!chars) fail("ALLOCATION_FAILED", "Cannot access Java UTF-16 string");
        try {
            std::u16string_view view(reinterpret_cast<const char16_t*>(chars), static_cast<size_t>(length));
            value_ = transvibe::unicode::utf16ToUtf8(view);
        } catch (...) {
            env->ReleaseStringChars(value, chars);
            throw;
        }
        env->ReleaseStringChars(value, chars);
    }
    const char* get() const noexcept { return value_.empty() ? nullptr : value_.c_str(); }
    const std::string& str() const noexcept { return value_; }
private:
    std::string value_;
};



std::mutex registryMutex;
std::unordered_map<jlong, std::shared_ptr<NativeContext>> registry;
std::atomic<jlong> nextId{1};

void throwNative(JNIEnv* env, const char* code, const char* message) noexcept {
    if (!env || env->ExceptionCheck()) return;
    jclass ex = env->FindClass("java/lang/IllegalStateException");
    if (!ex) return;
    std::string payload = "TVE|";
    payload += code ? code : "UNKNOWN";
    payload += '|';
    payload += message ? message : "Native failure";
    env->ThrowNew(ex, payload.c_str());
}

void validateModelPath(const char* rawPath) {
    if (!rawPath || *rawPath == '\0') fail("MODEL_INVALID", "Model path is empty");
    const std::filesystem::path path(rawPath);
    if (!path.is_absolute()) fail("MODEL_INVALID", "Model path must be absolute");
    std::error_code ec;
    if (!std::filesystem::exists(path, ec) || ec) fail("MODEL_NOT_FOUND", "Whisper model file does not exist");
    if (!std::filesystem::is_regular_file(path, ec) || ec) fail("MODEL_INVALID", "Whisper model path is not a regular file");
    const auto size = std::filesystem::file_size(path, ec);
    if (ec || size < 1024ULL * 1024ULL) fail("MODEL_INVALID", "Whisper model file is invalid or too small");
    std::ifstream input(path, std::ios::binary);
    if (!input.good()) fail("MODEL_NOT_READABLE", "Whisper model file is not readable");
}

std::shared_ptr<NativeContext> getContext(jlong id) {
    if (id <= 0) fail("INVALID_HANDLE", "Native handle is zero or invalid");
    std::lock_guard<std::mutex> lock(registryMutex);
    const auto it = registry.find(id);
    if (it == registry.end() || !it->second || !it->second->ctx) fail("INVALID_HANDLE", "Whisper context does not exist or was released");
    return it->second;
}

bool abortCallback(void* user) noexcept {
    const auto* context = static_cast<NativeContext*>(user);
    return context && context->cancelled.load(std::memory_order_relaxed);
}

void validateThreads(jint threads) {
    if (threads < 1 || threads > MAX_THREADS) fail("INVALID_ARGUMENT", "Whisper thread count is outside the supported range");
}

void validateLanguage(const char* language) {
    if (!language) return;
    const std::string value(language);
    if (value.empty() || value.size() > 31) fail("INVALID_ARGUMENT", "Language code is invalid");
}

whisper_full_params makeParams(NativeContext* context, int threads, const char* language, bool translate, const char* initialPrompt = nullptr) {
    if (!context || !context->ctx) fail("INVALID_HANDLE", "Whisper context is released");
    validateThreads(threads);
    validateLanguage(language);
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.translate = translate;
    params.language = language;
    params.detect_language = language == nullptr || std::string(language) == "auto";
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.token_timestamps = false;
    // Keep Whisper's internal rolling context disabled. Cross-chunk context is supplied only
    // through the bounded, application-owned finalized-text prompt.
    params.no_context = true;
    params.n_max_text_ctx = 64;
    params.initial_prompt = (initialPrompt && *initialPrompt) ? initialPrompt : nullptr;
    params.carry_initial_prompt = false;
    params.abort_callback = abortCallback;
    params.abort_callback_user_data = context;
    return params;
}

jstring newJavaStringFromUtf8(JNIEnv* env, const std::string& value) {
    if (!env) fail("INVALID_ARGUMENT", "JNI environment is null");
    std::u16string utf16;
    try {
        utf16 = transvibe::unicode::utf8ToUtf16(value);
    } catch (const std::exception&) {
        fail("INVALID_RESULT", "Native result contains invalid UTF-8");
    }
    static const jchar EMPTY = 0;
    const jchar* data = utf16.empty() ? &EMPTY : reinterpret_cast<const jchar*>(utf16.data());
    jstring result = env->NewString(data, static_cast<jsize>(utf16.size()));
    if (!result) fail("ALLOCATION_FAILED", "Cannot allocate Java result string");
    return result;
}

jobject boxedFloat(JNIEnv* env, const std::optional<float>& value) {
    if (!value.has_value()) return nullptr;
    jclass cls = env->FindClass("java/lang/Float");
    if (!cls) fail("ALLOCATION_FAILED", "Cannot resolve java.lang.Float");
    jmethodID valueOf = env->GetStaticMethodID(cls, "valueOf", "(F)Ljava/lang/Float;");
    if (!valueOf) { env->DeleteLocalRef(cls); fail("ALLOCATION_FAILED", "Cannot resolve Float.valueOf"); }
    jobject boxed = env->CallStaticObjectMethod(cls, valueOf, static_cast<jfloat>(*value));
    env->DeleteLocalRef(cls);
    if (env->ExceptionCheck() || !boxed) fail("ALLOCATION_FAILED", "Cannot box Whisper metric");
    return boxed;
}

void reportException(JNIEnv* env, const char* entry, const NativeFailure& e) noexcept {
    TV_LOGE("%s failed [%s]: %s", entry, e.code().c_str(), e.what());
    throwNative(env, e.code().c_str(), e.what());
}

void reportUnknown(JNIEnv* env, const char* entry) noexcept {
    TV_LOGE("%s failed with unknown native exception", entry);
    throwNative(env, "UNKNOWN", "Unknown native failure");
}

} // namespace

static jstring nativeBackendForContext(JNIEnv* env, jobject, jlong handle) {
    try {
        auto context = getContext(handle);
        std::lock_guard<std::mutex> lock(context->inferenceMutex);
        const char * active = whisper_transvibe_active_backend(context->ctx.get());
        return newJavaStringFromUtf8(env, active ? active : "UNVERIFIED");
    } catch (const NativeFailure& e) { reportException(env, "nativeBackendForContext", e); }
      catch (const std::exception& e) { TV_LOGE("nativeBackendForContext: %s", e.what()); throwNative(env, "UNKNOWN", "Backend inspection failed"); }
      catch (...) { reportUnknown(env, "nativeBackendForContext"); }
    return nullptr;
}

static jlong nativeCreateContext(JNIEnv* env, jobject, jstring path_) {
    try {
        ScopedStandardUtf8 path(env, path_, true);
        validateModelPath(path.get());
        auto params = whisper_context_default_params();
        params.use_gpu = false;
        whisper_context* raw = whisper_init_from_file_with_params(path.get(), params);
        if (!raw) fail("INIT_FAILED", "Failed to initialize Whisper model");
        auto context = std::make_shared<NativeContext>(raw);
        const jlong id = nextId.fetch_add(1, std::memory_order_relaxed);
        if (id <= 0) fail("ALLOCATION_FAILED", "Native context identifier overflow");
        std::lock_guard<std::mutex> lock(registryMutex);
        if (!registry.emplace(id, std::move(context)).second) fail("ALLOCATION_FAILED", "Native context identifier collision");
        return id;
    } catch (const NativeFailure& e) { reportException(env, "nativeCreateContext", e); }
      catch (const std::bad_alloc&) { throwNative(env, "ALLOCATION_FAILED", "Native allocation failed while loading Whisper"); }
      catch (const std::exception& e) { TV_LOGE("nativeCreateContext exception: %s", e.what()); throwNative(env, "UNKNOWN", "Native initialization failed"); }
      catch (...) { reportUnknown(env, "nativeCreateContext"); }
    return 0;
}



static std::pair<jsize, jsize> checkedAudioRange(JNIEnv* env, jfloatArray audio, jint offset, jint sampleCount, jsize minimumCount) {
    if (!audio) fail("INVALID_AUDIO", "Audio buffer is null");
    const jsize total = env->GetArrayLength(audio);
    if (env->ExceptionCheck()) fail("INVALID_AUDIO", "Cannot read audio buffer length");
    if (offset < 0 || sampleCount < 0 || offset > total - sampleCount) {
        fail("INVALID_ARGUMENT", "Audio range is outside the source buffer");
    }
    if (sampleCount < minimumCount) fail("INVALID_AUDIO", "Audio buffer is too short");
    if (sampleCount > MAX_AUDIO_SAMPLES) fail("INVALID_AUDIO", "Audio buffer exceeds bounded chunk limit");
    return {static_cast<jsize>(offset), static_cast<jsize>(sampleCount)};
}


static const float* loadAudioRangeIntoScratch(
    JNIEnv* env,
    NativeContext& context,
    jfloatArray audio,
    jsize offset,
    jsize count
) {
    context.audioScratch.resize(static_cast<size_t>(count));
    env->GetFloatArrayRegion(audio, offset, count, context.audioScratch.data());
    if (env->ExceptionCheck()) fail("INVALID_AUDIO", "Cannot copy requested audio range");
    return context.audioScratch.data();
}

static jboolean nativeIsMultilingual(JNIEnv* env, jobject, jlong id) {
    try {
        auto context = getContext(id);
        return whisper_is_multilingual(context->ctx.get()) ? JNI_TRUE : JNI_FALSE;
    } catch (const NativeFailure& e) { reportException(env, "nativeIsMultilingual", e); }
      catch (...) { reportUnknown(env, "nativeIsMultilingual"); }
    return JNI_FALSE;
}

static jstring nativeModelTypeReadable(JNIEnv* env, jobject, jlong id) {
    try {
        auto context = getContext(id);
        const char* type = whisper_model_type_readable(context->ctx.get());
        if (!type || *type == '\0') fail("INVALID_RESULT", "Whisper returned an empty model type");
        return newJavaStringFromUtf8(env, type);
    } catch (const NativeFailure& e) { reportException(env, "nativeModelTypeReadable", e); }
      catch (...) { reportUnknown(env, "nativeModelTypeReadable"); }
    return nullptr;
}
static jobject nativeDetectLanguageDetailed(JNIEnv* env, jobject, jlong id, jfloatArray audio_, jint offset, jint sampleCount, jint threads) {
    try {
        auto context = getContext(id);
        validateThreads(threads);
        const auto [audioOffset, count] = checkedAudioRange(env, audio_, offset, sampleCount, 1600);
        std::lock_guard<std::mutex> inference(context->inferenceMutex);
        if (context->cancelled.load(std::memory_order_relaxed)) fail("CANCELLED", "Processing was cancelled");
        const float* audio = loadAudioRangeIntoScratch(env, *context, audio_, audioOffset, count);
        if (whisper_pcm_to_mel(context->ctx.get(), audio, count, threads) != 0) {
            fail(context->cancelled.load() ? "CANCELLED" : "INFERENCE_FAILED", "Language detection mel conversion failed");
        }
        std::vector<float> probabilities(static_cast<size_t>(whisper_lang_max_id() + 1), 0.0f);
        const int langId = whisper_lang_auto_detect(context->ctx.get(), 0, threads, probabilities.data());
        if (langId < 0 || langId >= static_cast<int>(probabilities.size())) {
            fail("INVALID_RESULT", "Whisper returned an invalid language identifier");
        }
        const char* code = whisper_lang_str(langId);
        if (!code || *code == '\0') fail("INVALID_RESULT", "Whisper returned an empty language code");
        const std::optional<float> confidence = transvibe::metrics::validProbability(probabilities[static_cast<size_t>(langId)]);

        jclass resultClass = env->FindClass("com/transvibe/app/nativebridge/whisper/WhisperLanguageDetectionResult");
        if (!resultClass) fail("INVALID_RESULT", "Cannot resolve WhisperLanguageDetectionResult JNI class");
        jmethodID ctor = env->GetMethodID(resultClass, "<init>", "(Ljava/lang/String;Ljava/lang/Float;)V");
        if (!ctor) { env->DeleteLocalRef(resultClass); fail("INVALID_RESULT", "Cannot resolve WhisperLanguageDetectionResult constructor"); }
        jstring language = newJavaStringFromUtf8(env, code);
        jobject confidenceBox = boxedFloat(env, confidence);
        jobject result = env->NewObject(resultClass, ctor, language, confidenceBox);
        env->DeleteLocalRef(language);
        if (confidenceBox) env->DeleteLocalRef(confidenceBox);
        env->DeleteLocalRef(resultClass);
        if (env->ExceptionCheck() || !result) fail("ALLOCATION_FAILED", "Cannot construct language detection result");
        return result;
    } catch (const NativeFailure& e) { reportException(env, "nativeDetectLanguageDetailed", e); }
      catch (const std::bad_alloc&) { throwNative(env, "ALLOCATION_FAILED", "Native allocation failed during language detection"); }
      catch (const std::exception& e) { TV_LOGE("nativeDetectLanguageDetailed exception: %s", e.what()); throwNative(env, "UNKNOWN", "Native language detection failed"); }
      catch (...) { reportUnknown(env, "nativeDetectLanguageDetailed"); }
    return nullptr;
}

static jobjectArray transcribeStructured(JNIEnv* env, jlong id, jfloatArray audio_, jint offset, jint sampleCount, jstring lang_, jboolean translate, jint threads, jstring prompt_) {
    auto context = getContext(id);
    validateThreads(threads);
    const auto [audioOffset, count] = checkedAudioRange(env, audio_, offset, sampleCount, 1);
    ScopedStandardUtf8 language(env, lang_, false);
    ScopedStandardUtf8 prompt(env, prompt_, false);
    validateLanguage(language.get());
    if (prompt.get() && prompt.str().size() > 512) fail("INVALID_ARGUMENT", "Initial prompt exceeds bounded UTF-8 limit");
    std::lock_guard<std::mutex> inference(context->inferenceMutex);
    if (context->cancelled.load(std::memory_order_relaxed)) fail("CANCELLED", "Processing was cancelled");
    const float* audio = loadAudioRangeIntoScratch(env, *context, audio_, audioOffset, count);
    auto params = makeParams(context.get(), threads, language.get(), translate == JNI_TRUE, prompt.get());
    const int rc = whisper_full(context->ctx.get(), params, audio, count);
    if (rc != 0) fail(context->cancelled.load() ? "CANCELLED" : "INFERENCE_FAILED", "Whisper inference failed");

    const int segmentCount = whisper_full_n_segments(context->ctx.get());
    if (segmentCount < 0 || segmentCount > MAX_SEGMENTS) fail("INVALID_RESULT", "Whisper returned an invalid segment count");

    jclass resultClass = env->FindClass("com/transvibe/app/nativebridge/whisper/WhisperSegmentResult");
    if (!resultClass) fail("INVALID_RESULT", "Cannot resolve WhisperSegmentResult JNI class");
    jmethodID ctor = env->GetMethodID(resultClass, "<init>", "(JJLjava/lang/String;Ljava/lang/Float;Ljava/lang/Float;Ljava/lang/Float;I)V");
    if (!ctor) { env->DeleteLocalRef(resultClass); fail("INVALID_RESULT", "Cannot resolve WhisperSegmentResult constructor"); }
    jobjectArray results = env->NewObjectArray(segmentCount, resultClass, nullptr);
    if (!results) { env->DeleteLocalRef(resultClass); fail("ALLOCATION_FAILED", "Cannot allocate Whisper result array"); }

    for (int i = 0; i < segmentCount; ++i) {
        const int64_t t0 = whisper_full_get_segment_t0(context->ctx.get(), i);
        const int64_t t1 = whisper_full_get_segment_t1(context->ctx.get(), i);
        if (t0 < 0 || t1 < t0) { env->DeleteLocalRef(resultClass); fail("INVALID_RESULT", "Whisper returned invalid segment timestamps"); }
        const char* rawText = whisper_full_get_segment_text(context->ctx.get(), i);
        if (!rawText) { env->DeleteLocalRef(resultClass); fail("INVALID_RESULT", "Whisper returned null segment text"); }
        jstring text = newJavaStringFromUtf8(env, std::string(rawText));

        const int tokenCount = whisper_full_n_tokens(context->ctx.get(), i);
        std::vector<float> probabilities;
        if (tokenCount > 0) probabilities.reserve(static_cast<size_t>(tokenCount));
        for (int token = 0; token < tokenCount; ++token) {
            probabilities.push_back(whisper_full_get_token_p(context->ctx.get(), i, token));
        }
        const std::optional<float> confidence = transvibe::metrics::meanValidTokenProbability(probabilities);
        const std::optional<float> noSpeech = transvibe::metrics::validProbability(
            whisper_full_get_segment_no_speech_prob(context->ctx.get(), i));
        // avg_logprobs exists internally in whisper.cpp but is not exposed by this checkout's public C API.
        // Do not fabricate or reconstruct it under that name.
        const std::optional<float> avgLogProb = std::nullopt;

        jobject confidenceBox = boxedFloat(env, confidence);
        jobject avgLogProbBox = boxedFloat(env, avgLogProb);
        jobject noSpeechBox = boxedFloat(env, noSpeech);
        jobject result = env->NewObject(
            resultClass, ctor,
            static_cast<jlong>(t0 * 10),
            static_cast<jlong>(t1 * 10),
            text,
            confidenceBox,
            avgLogProbBox,
            noSpeechBox,
            static_cast<jint>(tokenCount));
        if (env->ExceptionCheck() || !result) {
            if (text) env->DeleteLocalRef(text);
            if (confidenceBox) env->DeleteLocalRef(confidenceBox);
            if (avgLogProbBox) env->DeleteLocalRef(avgLogProbBox);
            if (noSpeechBox) env->DeleteLocalRef(noSpeechBox);
            env->DeleteLocalRef(resultClass);
            fail("ALLOCATION_FAILED", "Cannot construct structured Whisper result");
        }
        env->SetObjectArrayElement(results, i, result);
        env->DeleteLocalRef(result);
        env->DeleteLocalRef(text);
        if (confidenceBox) env->DeleteLocalRef(confidenceBox);
        if (avgLogProbBox) env->DeleteLocalRef(avgLogProbBox);
        if (noSpeechBox) env->DeleteLocalRef(noSpeechBox);
        if (env->ExceptionCheck()) { env->DeleteLocalRef(resultClass); fail("ALLOCATION_FAILED", "Cannot store structured Whisper result"); }
    }
    env->DeleteLocalRef(resultClass);
    return results;
}

static jobjectArray nativeTranscribeStructured(JNIEnv* env, jobject, jlong id, jfloatArray audio_, jint offset, jint sampleCount, jstring lang_, jboolean translate, jint threads, jstring prompt_) {
    try { return transcribeStructured(env, id, audio_, offset, sampleCount, lang_, translate, threads, prompt_); }
    catch (const NativeFailure& e) { reportException(env, "nativeTranscribeStructured", e); }
    catch (const std::bad_alloc&) { throwNative(env, "ALLOCATION_FAILED", "Native allocation failed during Whisper inference"); }
    catch (const std::exception& e) { TV_LOGE("nativeTranscribeStructured exception: %s", e.what()); throwNative(env, "UNKNOWN", "Native Whisper inference failed"); }
    catch (...) { reportUnknown(env, "nativeTranscribeStructured"); }
    return nullptr;
}

static jobject nativeBridgeSelfTest(JNIEnv* env, jobject, jstring text_) {
    try {
        ScopedStandardUtf8 input(env, text_, true);
        jclass resultClass = env->FindClass("com/transvibe/app/nativebridge/whisper/WhisperSegmentResult");
        if (!resultClass) fail("INVALID_RESULT", "Cannot resolve WhisperSegmentResult JNI class");
        jmethodID ctor = env->GetMethodID(resultClass, "<init>", "(JJLjava/lang/String;Ljava/lang/Float;Ljava/lang/Float;Ljava/lang/Float;I)V");
        if (!ctor) { env->DeleteLocalRef(resultClass); fail("INVALID_RESULT", "Cannot resolve WhisperSegmentResult constructor"); }
        jstring text = newJavaStringFromUtf8(env, input.str());
        jobject confidence = boxedFloat(env, std::optional<float>(0.75f));
        jobject noSpeech = boxedFloat(env, std::optional<float>(0.20f));
        jobject result = env->NewObject(resultClass, ctor, static_cast<jlong>(10), static_cast<jlong>(20), text, confidence, nullptr, noSpeech, static_cast<jint>(3));
        env->DeleteLocalRef(text);
        if (confidence) env->DeleteLocalRef(confidence);
        if (noSpeech) env->DeleteLocalRef(noSpeech);
        env->DeleteLocalRef(resultClass);
        if (env->ExceptionCheck() || !result) fail("ALLOCATION_FAILED", "Cannot construct JNI bridge self-test result");
        return result;
    } catch (const NativeFailure& e) { reportException(env, "nativeBridgeSelfTest", e); }
      catch (const std::bad_alloc&) { throwNative(env, "ALLOCATION_FAILED", "Native allocation failed during bridge self-test"); }
      catch (const std::exception& e) { TV_LOGE("nativeBridgeSelfTest exception: %s", e.what()); throwNative(env, "UNKNOWN", "Native bridge self-test failed"); }
      catch (...) { reportUnknown(env, "nativeBridgeSelfTest"); }
    return nullptr;
}

static void nativeCancel(JNIEnv* env, jobject, jlong id) {
    try {
        auto context = getContext(id);
        context->cancelled.store(true, std::memory_order_relaxed);
    } catch (const NativeFailure& e) { reportException(env, "nativeCancel", e); }
      catch (const std::exception& e) { TV_LOGE("nativeCancel exception: %s", e.what()); throwNative(env, "UNKNOWN", "Native cancellation failed"); }
      catch (...) { reportUnknown(env, "nativeCancel"); }
}

static void nativeReleaseContext(JNIEnv* env, jobject, jlong id) {
    try {
        if (id <= 0) fail("INVALID_HANDLE", "Native handle is zero or invalid");
        std::shared_ptr<NativeContext> removed;
        {
            std::lock_guard<std::mutex> lock(registryMutex);
            const auto it = registry.find(id);
            if (it == registry.end() || !it->second) fail("INVALID_HANDLE", "Whisper context does not exist or was already released");
            removed = std::move(it->second);
            registry.erase(it);
        }
        removed->cancelled.store(true, std::memory_order_relaxed);
    } catch (const NativeFailure& e) { reportException(env, "nativeReleaseContext", e); }
      catch (const std::exception& e) { TV_LOGE("nativeReleaseContext exception: %s", e.what()); throwNative(env, "UNKNOWN", "Native release failed"); }
      catch (...) { reportUnknown(env, "nativeReleaseContext"); }
}


extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    if (!vm) return JNI_ERR;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || !env) return JNI_ERR;
    jclass clazz = env->FindClass("com/transvibe/app/nativebridge/whisper/WhisperNative");
    if (!clazz || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_ERR;
    }
    static const JNINativeMethod methods[] = {
        {const_cast<char*>("nativeCreateContext"), const_cast<char*>("(Ljava/lang/String;)J"), reinterpret_cast<void*>(nativeCreateContext)},
        {const_cast<char*>("nativeBackendForContext"), const_cast<char*>("(J)Ljava/lang/String;"), reinterpret_cast<void*>(nativeBackendForContext)},
        {const_cast<char*>("nativeDetectLanguageDetailed"), const_cast<char*>("(J[FIII)Lcom/transvibe/app/nativebridge/whisper/WhisperLanguageDetectionResult;"), reinterpret_cast<void*>(nativeDetectLanguageDetailed)},
        {const_cast<char*>("nativeIsMultilingual"), const_cast<char*>("(J)Z"), reinterpret_cast<void*>(nativeIsMultilingual)},
        {const_cast<char*>("nativeModelTypeReadable"), const_cast<char*>("(J)Ljava/lang/String;"), reinterpret_cast<void*>(nativeModelTypeReadable)},
        {const_cast<char*>("nativeTranscribeStructured"), const_cast<char*>("(J[FIILjava/lang/String;ZILjava/lang/String;)[Lcom/transvibe/app/nativebridge/whisper/WhisperSegmentResult;"), reinterpret_cast<void*>(nativeTranscribeStructured)},
        {const_cast<char*>("nativeBridgeSelfTest"), const_cast<char*>("(Ljava/lang/String;)Lcom/transvibe/app/nativebridge/whisper/WhisperSegmentResult;"), reinterpret_cast<void*>(nativeBridgeSelfTest)},
        {const_cast<char*>("nativeCancel"), const_cast<char*>("(J)V"), reinterpret_cast<void*>(nativeCancel)},
        {const_cast<char*>("nativeReleaseContext"), const_cast<char*>("(J)V"), reinterpret_cast<void*>(nativeReleaseContext)},
    };
    if (env->RegisterNatives(clazz, methods, static_cast<jint>(sizeof(methods) / sizeof(methods[0]))) != JNI_OK || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(clazz);
        return JNI_ERR;
    }
    env->DeleteLocalRef(clazz);
    return JNI_VERSION_1_6;
}
