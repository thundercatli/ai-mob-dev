#include <jni.h>

#include <memory>
#include <mutex>
#include <string>

#include "aidevmob/frpc_core.h"

namespace {

JavaVM* javaVm = nullptr;

class JniCallback {
public:
    JniCallback(JNIEnv* env, jobject listener) : listener_(env->NewGlobalRef(listener)) {
        jclass listenerClass = env->GetObjectClass(listener);
        stateMethod_ = env->GetMethodID(listenerClass, "onNativeStateChanged", "(ILjava/lang/String;)V");
        logMethod_ = env->GetMethodID(listenerClass, "onNativeLog", "(Ljava/lang/String;)V");
        transportMethod_ = env->GetMethodID(
            listenerClass,
            "openNativeTransport",
            "(Ljava/lang/String;IZI)I"
        );
        env->DeleteLocalRef(listenerClass);
    }

    ~JniCallback() {
        withEnv([this](JNIEnv* env) { env->DeleteGlobalRef(listener_); });
    }

    void state(aidevmob_frpc_state state, const char* detail) {
        withEnv([this, state, detail](JNIEnv* env) {
            jstring message = detail == nullptr ? nullptr : env->NewStringUTF(detail);
            env->CallVoidMethod(listener_, stateMethod_, static_cast<jint>(state), message);
            if (message != nullptr) env->DeleteLocalRef(message);
        });
    }

    void log(const char* line) {
        withEnv([this, line](JNIEnv* env) {
            jstring message = env->NewStringUTF(line == nullptr ? "" : line);
            env->CallVoidMethod(listener_, logMethod_, message);
            env->DeleteLocalRef(message);
        });
    }

    int openTransport(const char* host, uint16_t port, bool useTls, int timeoutMs) {
        int socket = -1;
        withEnv([this, host, port, useTls, timeoutMs, &socket](JNIEnv* env) {
            jstring javaHost = env->NewStringUTF(host == nullptr ? "" : host);
            socket = env->CallIntMethod(
                listener_,
                transportMethod_,
                javaHost,
                static_cast<jint>(port),
                static_cast<jboolean>(useTls),
                static_cast<jint>(timeoutMs)
            );
            env->DeleteLocalRef(javaHost);
        });
        return socket;
    }

private:
    template <typename Callback>
    void withEnv(Callback callback) {
        JNIEnv* env = nullptr;
        bool attached = false;
        if (javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
            attached = true;
        }
        callback(env);
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (attached) javaVm->DetachCurrentThread();
    }

    jobject listener_;
    jmethodID stateMethod_;
    jmethodID logMethod_;
    jmethodID transportMethod_;
};

struct NativeSession {
    std::unique_ptr<JniCallback> callback;
    aidevmob_frpc_core* core = nullptr;

    ~NativeSession() {
        aidevmob_frpc_core_stop(core);
        aidevmob_frpc_core_destroy(core);
    }
};

std::string toString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

void onState(void* context, aidevmob_frpc_state state, const char* detail) {
    static_cast<JniCallback*>(context)->state(state, detail);
}

void onLog(void* context, const char* line) {
    static_cast<JniCallback*>(context)->log(line);
}

int openTransport(void* context, const char* host, uint16_t port, int useTls, int timeoutMs) {
    return static_cast<JniCallback*>(context)->openTransport(host, port, useTls != 0, timeoutMs);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_devhc_aidevmob_frp_nativecore_NativeFrpcBridge_nativeStart(
    JNIEnv* env,
    jobject,
    jstring serverHost,
    jint serverPort,
    jstring serverName,
    jstring secretKey,
    jstring authToken,
    jstring user,
    jstring serverUser,
    jboolean useTls,
    jboolean tcpMux,
    jboolean useEncryption,
    jboolean useCompression,
    jint bindPort,
    jobject listener
) {
    auto session = std::make_unique<NativeSession>();
    session->callback = std::make_unique<JniCallback>(env, listener);
    const std::string host = toString(env, serverHost);
    const std::string proxy = toString(env, serverName);
    const std::string secret = toString(env, secretKey);
    const std::string token = toString(env, authToken);
    const std::string clientUser = toString(env, user);
    const std::string proxyUser = toString(env, serverUser);
    aidevmob_frpc_stcp_config config{
        host.c_str(),
        static_cast<uint16_t>(serverPort),
        proxy.c_str(),
        secret.c_str(),
        "127.0.0.1",
        static_cast<uint16_t>(bindPort),
        10000,
        token.c_str(),
        clientUser.c_str(),
        proxyUser.c_str(),
        useTls ? 1 : 0,
        tcpMux ? 1 : 0,
        useEncryption ? 1 : 0,
        useCompression ? 1 : 0,
    };
    aidevmob_frpc_callbacks callbacks{session->callback.get(), onState, onLog, openTransport};
    session->core = aidevmob_frpc_core_create(&config, &callbacks);
    if (session->core == nullptr || aidevmob_frpc_core_start(session->core) != 0) return 0;
    return reinterpret_cast<jlong>(session.release());
}

extern "C" JNIEXPORT void JNICALL
Java_com_devhc_aidevmob_frp_nativecore_NativeFrpcBridge_nativeStop(
    JNIEnv*,
    jobject,
    jlong handle
) {
    delete reinterpret_cast<NativeSession*>(handle);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    javaVm = vm;
    return JNI_VERSION_1_6;
}
