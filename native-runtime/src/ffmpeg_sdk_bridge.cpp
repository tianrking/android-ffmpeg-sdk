#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>

#include <atomic>
#include <cerrno>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavcodec/jni.h>
#include <libavfilter/avfilter.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/error.h>
#include <libavutil/mathematics.h>
}

namespace {

using ExecuteFunction = int (*)(int, char **);
using CancelFunction = void (*)();

JavaVM *java_vm = nullptr;
std::mutex context_mutex;
jobject android_context = nullptr;
std::mutex execution_mutex;
std::mutex active_runner_mutex;
std::mutex callback_mutex;
std::atomic<jlong> active_session{0};
std::atomic<CancelFunction> active_cancel{nullptr};
std::atomic<jobject> active_callback{nullptr};
std::atomic<jmethodID> active_log_method{nullptr};
struct ProbeInterruptState {
    std::atomic<bool> cancelled{false};
};
std::mutex active_probe_mutex;
jlong active_probe_session = 0;
ProbeInterruptState *active_probe = nullptr;

class AttachedEnvironment {
public:
    AttachedEnvironment() {
        if (java_vm == nullptr) return;
        const jint result = java_vm->GetEnv(reinterpret_cast<void **>(&environment_), JNI_VERSION_1_6);
        if (result == JNI_EDETACHED &&
            java_vm->AttachCurrentThread(&environment_, nullptr) == JNI_OK) {
            attached_ = true;
        }
    }

    ~AttachedEnvironment() {
        if (attached_) java_vm->DetachCurrentThread();
    }

    JNIEnv *get() const { return environment_; }

private:
    JNIEnv *environment_ = nullptr;
    bool attached_ = false;
};

void append_utf8(std::string &output, std::uint32_t code_point) {
    if (code_point <= 0x7f) {
        output.push_back(static_cast<char>(code_point));
    } else if (code_point <= 0x7ff) {
        output.push_back(static_cast<char>(0xc0 | (code_point >> 6)));
        output.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
    } else if (code_point <= 0xffff) {
        output.push_back(static_cast<char>(0xe0 | (code_point >> 12)));
        output.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
    } else {
        output.push_back(static_cast<char>(0xf0 | (code_point >> 18)));
        output.push_back(static_cast<char>(0x80 | ((code_point >> 12) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
    }
}

std::string java_string_to_utf8(JNIEnv *environment, jstring value) {
    if (value == nullptr) return "";
    const jsize length = environment->GetStringLength(value);
    const jchar *characters = environment->GetStringChars(value, nullptr);
    if (characters == nullptr) return "";

    std::string output;
    output.reserve(static_cast<std::size_t>(length));
    for (jsize index = 0; index < length; ++index) {
        std::uint32_t code_point = characters[index];
        if (code_point >= 0xd800 && code_point <= 0xdbff) {
            if (index + 1 < length && characters[index + 1] >= 0xdc00 &&
                characters[index + 1] <= 0xdfff) {
                code_point = 0x10000 + ((code_point - 0xd800) << 10) +
                    (characters[++index] - 0xdc00);
            } else {
                code_point = 0xfffd;
            }
        } else if (code_point >= 0xdc00 && code_point <= 0xdfff) {
            code_point = 0xfffd;
        }
        append_utf8(output, code_point);
    }
    environment->ReleaseStringChars(value, characters);
    return output;
}

jstring utf8_to_java_string(JNIEnv *environment, const char *value) {
    if (value == nullptr) value = "";
    const auto *bytes = reinterpret_cast<const unsigned char *>(value);
    const std::size_t length = std::strlen(value);
    std::vector<jchar> output;
    output.reserve(length);

    std::size_t index = 0;
    while (index < length) {
        const unsigned char first = bytes[index];
        std::uint32_t code_point = 0xfffd;
        std::size_t continuation_count = 0;
        std::uint32_t minimum = 0;
        if (first <= 0x7f) {
            code_point = first;
        } else if (first >= 0xc2 && first <= 0xdf) {
            code_point = first & 0x1f;
            continuation_count = 1;
            minimum = 0x80;
        } else if (first >= 0xe0 && first <= 0xef) {
            code_point = first & 0x0f;
            continuation_count = 2;
            minimum = 0x800;
        } else if (first >= 0xf0 && first <= 0xf4) {
            code_point = first & 0x07;
            continuation_count = 3;
            minimum = 0x10000;
        }

        bool valid = continuation_count == 0 ? first <= 0x7f : index + continuation_count < length;
        if (valid && continuation_count > 0) {
            for (std::size_t offset = 1; offset <= continuation_count; ++offset) {
                const unsigned char next = bytes[index + offset];
                if ((next & 0xc0) != 0x80) {
                    valid = false;
                    break;
                }
                code_point = (code_point << 6) | (next & 0x3f);
            }
            if (valid && (code_point < minimum || code_point > 0x10ffff ||
                          (code_point >= 0xd800 && code_point <= 0xdfff))) {
                code_point = 0xfffd;
            }
        }

        if (!valid) {
            code_point = 0xfffd;
            continuation_count = 0;
        }
        index += continuation_count + 1;
        if (code_point <= 0xffff) {
            output.push_back(static_cast<jchar>(code_point));
        } else {
            code_point -= 0x10000;
            output.push_back(static_cast<jchar>(0xd800 + (code_point >> 10)));
            output.push_back(static_cast<jchar>(0xdc00 + (code_point & 0x3ff)));
        }
    }
    return environment->NewString(
        output.empty() ? nullptr : output.data(),
        static_cast<jsize>(output.size()));
}

std::string json_escape(const char *value) {
    if (value == nullptr) return "";
    std::ostringstream result;
    for (const unsigned char character : std::string(value)) {
        switch (character) {
            case '\"': result << "\\\""; break;
            case '\\': result << "\\\\"; break;
            case '\b': result << "\\b"; break;
            case '\f': result << "\\f"; break;
            case '\n': result << "\\n"; break;
            case '\r': result << "\\r"; break;
            case '\t': result << "\\t"; break;
            default:
                if (character < 0x20) {
                    char escaped[7];
                    std::snprintf(escaped, sizeof(escaped), "\\u%04x", character);
                    result << escaped;
                } else {
                    result << static_cast<char>(character);
                }
        }
    }
    return result.str();
}

void emit_log(int level, const char *message) {
    std::lock_guard<std::mutex> callback_lock(callback_mutex);
    jobject callback = active_callback.load(std::memory_order_acquire);
    jmethodID method = active_log_method.load(std::memory_order_acquire);
    if (callback == nullptr || method == nullptr || message == nullptr || *message == '\0') return;

    AttachedEnvironment attached;
    JNIEnv *environment = attached.get();
    if (environment == nullptr) return;
    jstring text = utf8_to_java_string(environment, message);
    if (text == nullptr) {
        environment->ExceptionClear();
        return;
    }
    environment->CallVoidMethod(callback, method, static_cast<jint>(level), text);
    environment->DeleteLocalRef(text);
    if (environment->ExceptionCheck()) {
        environment->ExceptionDescribe();
        environment->ExceptionClear();
    }
}

void sdk_log_callback(void *context, int level, const char *format, va_list arguments) {
    if (level > av_log_get_level()) return;
    char line[4096];
    static thread_local int print_prefix = 1;
    va_list copy;
    va_copy(copy, arguments);
    av_log_format_line2(context, level, format, copy, line, sizeof(line), &print_prefix);
    va_end(copy);
    emit_log(level, line);
}

jstring new_string(JNIEnv *environment, const std::string &value) {
    return utf8_to_java_string(environment, value.c_str());
}

std::string error_string(int code) {
    char text[AV_ERROR_MAX_STRING_SIZE];
    av_strerror(code, text, sizeof(text));
    return text;
}

std::string component_names(int kind) {
    std::ostringstream output;
    bool first = true;
    auto append = [&](const char *name) {
        if (name == nullptr || *name == '\0') return;
        if (!first) output << '\n';
        output << name;
        first = false;
    };

    if (kind == 0 || kind == 1) {
        void *state = nullptr;
        const AVCodec *codec = nullptr;
        while ((codec = av_codec_iterate(&state)) != nullptr) {
            if ((kind == 0 && av_codec_is_encoder(codec)) ||
                (kind == 1 && av_codec_is_decoder(codec))) {
                append(codec->name);
            }
        }
    } else if (kind == 2) {
        void *state = nullptr;
        const AVFilter *filter = nullptr;
        while ((filter = av_filter_iterate(&state)) != nullptr) append(filter->name);
    } else if (kind == 3) {
        void *state = nullptr;
        const AVOutputFormat *format = nullptr;
        while ((format = av_muxer_iterate(&state)) != nullptr) append(format->name);
    } else if (kind == 4) {
        void *state = nullptr;
        const AVInputFormat *format = nullptr;
        while ((format = av_demuxer_iterate(&state)) != nullptr) append(format->name);
    }
    return output.str();
}

int probe_interrupt_callback(void *opaque) {
    const auto *state = static_cast<ProbeInterruptState *>(opaque);
    return state->cancelled.load(std::memory_order_acquire) ? 1 : 0;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    java_vm = vm;
    if (av_jni_set_java_vm(vm, nullptr) < 0) return JNI_ERR;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_tianrking_ffmpegsdk_engine_nativeffmpeg_NativeBindings_nativeInitialize(
    JNIEnv *environment,
    jobject,
    jobject context) {
    std::lock_guard<std::mutex> execution_lock(execution_mutex);
    std::lock_guard<std::mutex> context_lock(context_mutex);
    jobject replacement = environment->NewGlobalRef(context);
    if (replacement == nullptr) return JNI_FALSE;
    if (av_jni_set_android_app_ctx(replacement, nullptr) < 0) {
        environment->DeleteGlobalRef(replacement);
        return JNI_FALSE;
    }
    if (android_context != nullptr) environment->DeleteGlobalRef(android_context);
    android_context = replacement;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_tianrking_ffmpegsdk_engine_nativeffmpeg_NativeBindings_nativeVersion(
    JNIEnv *environment,
    jobject) {
    return new_string(environment, av_version_info());
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_tianrking_ffmpegsdk_engine_nativeffmpeg_NativeBindings_nativeConfiguration(
    JNIEnv *environment,
    jobject) {
    return new_string(environment, avcodec_configuration());
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_tianrking_ffmpegsdk_engine_nativeffmpeg_NativeBindings_nativeLicense(
    JNIEnv *environment,
    jobject) {
    return new_string(environment, avcodec_license());
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_tianrking_ffmpegsdk_engine_nativeffmpeg_NativeBindings_nativeComponents(
    JNIEnv *environment,
    jobject,
    jint kind) {
    return new_string(environment, component_names(kind));
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_tianrking_ffmpegsdk_engine_nativeffmpeg_NativeBindings_nativeProbe(
    JNIEnv *environment,
    jobject,
    jlong session_id,
    jstring input) {
    std::lock_guard<std::mutex> execution_lock(execution_mutex);
    const std::string input_value = java_string_to_utf8(environment, input);
    if (environment->ExceptionCheck()) return nullptr;

    ProbeInterruptState interrupt;
    AVFormatContext *format = avformat_alloc_context();
    int result = format == nullptr ? AVERROR(ENOMEM) : 0;
    if (format != nullptr) {
        format->interrupt_callback.callback = probe_interrupt_callback;
        format->interrupt_callback.opaque = &interrupt;
        {
            std::lock_guard<std::mutex> probe_lock(active_probe_mutex);
            active_probe_session = session_id;
            active_probe = &interrupt;
        }
        result = avformat_open_input(&format, input_value.c_str(), nullptr, nullptr);
    }
    if (result >= 0) result = avformat_find_stream_info(format, nullptr);
    {
        std::lock_guard<std::mutex> probe_lock(active_probe_mutex);
        if (active_probe == &interrupt) {
            active_probe = nullptr;
            active_probe_session = 0;
        }
    }
    if (result < 0) {
        if (format != nullptr && format->iformat != nullptr) avformat_close_input(&format);
        else if (format != nullptr) avformat_free_context(format);
        const std::string error = "{\"error\":\"" + json_escape(error_string(result).c_str()) + "\"}";
        return new_string(environment, error);
    }

    std::ostringstream json;
    json << "{\"durationMs\":";
    if (format->duration == AV_NOPTS_VALUE) json << "null";
    else json << av_rescale(format->duration, 1000, AV_TIME_BASE);
    json << ",\"formatNames\":[";
    bool first_name = true;
    std::string names = format->iformat != nullptr && format->iformat->name != nullptr
        ? format->iformat->name : "";
    std::size_t start = 0;
    while (start <= names.size()) {
        const std::size_t comma = names.find(',', start);
        const std::string name = names.substr(start, comma == std::string::npos ? std::string::npos : comma - start);
        if (!name.empty()) {
            if (!first_name) json << ',';
            json << '\"' << json_escape(name.c_str()) << '\"';
            first_name = false;
        }
        if (comma == std::string::npos) break;
        start = comma + 1;
    }
    json << "],\"bitrate\":";
    if (format->bit_rate <= 0) json << "null";
    else json << format->bit_rate;
    json << ",\"streams\":[";

    for (unsigned int index = 0; index < format->nb_streams; ++index) {
        const AVStream *stream = format->streams[index];
        const AVCodecParameters *parameters = stream->codecpar;
        if (index > 0) json << ',';
        json << "{\"index\":" << stream->index
             << ",\"type\":\"" << json_escape(av_get_media_type_string(parameters->codec_type)) << "\""
             << ",\"codec\":\"" << json_escape(avcodec_get_name(parameters->codec_id)) << "\"";
        if (parameters->codec_type == AVMEDIA_TYPE_VIDEO) {
            json << ",\"width\":" << parameters->width
                 << ",\"height\":" << parameters->height;
        } else if (parameters->codec_type == AVMEDIA_TYPE_AUDIO) {
            json << ",\"sampleRate\":" << parameters->sample_rate
                 << ",\"channels\":" << parameters->ch_layout.nb_channels;
        }
        json << '}';
    }
    json << "]}";
    avformat_close_input(&format);
    return new_string(environment, json.str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_tianrking_ffmpegsdk_engine_nativeffmpeg_NativeBindings_nativeCancelProbe(
    JNIEnv *,
    jobject,
    jlong session_id) {
    std::lock_guard<std::mutex> probe_lock(active_probe_mutex);
    if (active_probe == nullptr || active_probe_session != session_id) return JNI_FALSE;
    active_probe->cancelled.store(true, std::memory_order_release);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_tianrking_ffmpegsdk_engine_nativeffmpeg_NativeBindings_nativeExecute(
    JNIEnv *environment,
    jobject,
    jlong session_id,
    jobjectArray arguments,
    jobject callback) {
    std::lock_guard<std::mutex> execution_lock(execution_mutex);

    const jsize argument_count = environment->GetArrayLength(arguments);
    std::vector<std::string> values;
    values.reserve(static_cast<std::size_t>(argument_count) + 1);
    values.emplace_back("ffmpeg");
    for (jsize index = 0; index < argument_count; ++index) {
        auto value = static_cast<jstring>(environment->GetObjectArrayElement(arguments, index));
        values.emplace_back(java_string_to_utf8(environment, value));
        if (value != nullptr) environment->DeleteLocalRef(value);
        if (environment->ExceptionCheck()) return -124;
    }
    std::vector<char *> argv;
    argv.reserve(values.size());
    for (std::string &value : values) argv.push_back(value.data());

    void *runner = dlopen("libffmpeg_sdk_cli.so", RTLD_NOW | RTLD_LOCAL);
    if (runner == nullptr) {
        emit_log(AV_LOG_ERROR, dlerror());
        return -127;
    }
    auto execute = reinterpret_cast<ExecuteFunction>(dlsym(runner, "ffmpeg_sdk_execute"));
    auto cancel = reinterpret_cast<CancelFunction>(dlsym(runner, "ffmpeg_sdk_cancel"));
    if (execute == nullptr || cancel == nullptr) {
        dlclose(runner);
        return -126;
    }

    jobject callback_reference = environment->NewGlobalRef(callback);
    jclass callback_class = environment->GetObjectClass(callback);
    jmethodID log_method = callback_class == nullptr ? nullptr :
        environment->GetMethodID(callback_class, "onLog", "(ILjava/lang/String;)V");
    if (callback_class != nullptr) environment->DeleteLocalRef(callback_class);
    if (callback_reference == nullptr || log_method == nullptr) {
        if (callback_reference != nullptr) environment->DeleteGlobalRef(callback_reference);
        dlclose(runner);
        return -125;
    }

    {
        std::lock_guard<std::mutex> callback_lock(callback_mutex);
        active_callback.store(callback_reference, std::memory_order_release);
        active_log_method.store(log_method, std::memory_order_release);
    }
    {
        std::lock_guard<std::mutex> runner_lock(active_runner_mutex);
        active_cancel.store(cancel, std::memory_order_release);
        active_session.store(session_id, std::memory_order_release);
    }
    const int previous_log_level = av_log_get_level();
    const int previous_log_flags = av_log_get_flags();
    av_log_set_callback(sdk_log_callback);

    const int result = execute(static_cast<int>(argv.size()), argv.data());

    {
        std::lock_guard<std::mutex> runner_lock(active_runner_mutex);
        active_session.store(0, std::memory_order_release);
        active_cancel.store(nullptr, std::memory_order_release);
    }
    av_log_set_callback(av_log_default_callback);
    av_log_set_level(previous_log_level);
    av_log_set_flags(previous_log_flags);
    {
        std::lock_guard<std::mutex> callback_lock(callback_mutex);
        active_log_method.store(nullptr, std::memory_order_release);
        active_callback.store(nullptr, std::memory_order_release);
        environment->DeleteGlobalRef(callback_reference);
    }
    dlclose(runner);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_tianrking_ffmpegsdk_engine_nativeffmpeg_NativeBindings_nativeCancel(
    JNIEnv *,
    jobject,
    jlong session_id) {
    // The lock keeps the runner loaded until the cancellation function returns.
    std::lock_guard<std::mutex> runner_lock(active_runner_mutex);
    CancelFunction cancel = active_cancel.load(std::memory_order_acquire);
    if (cancel == nullptr || active_session.load(std::memory_order_acquire) != session_id) {
        return JNI_FALSE;
    }
    cancel();
    return JNI_TRUE;
}
