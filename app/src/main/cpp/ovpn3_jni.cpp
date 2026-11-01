// JNI bridge between io.github.meshkati.ovpnvless.OpenVpn3 (Kotlin) and
// chain::Client. One session at a time.
#include <jni.h>

#include <android/log.h>

#include <mutex>
#include <string>

#include "chain_client.hpp"

namespace {

JavaVM *g_vm = nullptr;
jclass g_string_class = nullptr;
jmethodID g_string_ctor = nullptr;  // String(byte[], String charset)

std::mutex g_mutex;
chain::Client *g_client = nullptr;  // running session, guarded by g_mutex

// Detaches threads we attached ourselves when they exit.
struct ThreadDetacher {
    bool attached = false;
    ~ThreadDetacher() {
        if (attached)
            g_vm->DetachCurrentThread();
    }
};
thread_local ThreadDetacher t_detacher;

JNIEnv *env_for_thread() {
    JNIEnv *env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK)
        return env;
#ifdef __ANDROID__
    const jint rc = g_vm->AttachCurrentThread(&env, nullptr);
#else  // the JDK's jni.h declares void** (host compile check)
    const jint rc = g_vm->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr);
#endif
    if (rc != JNI_OK)
        return nullptr;
    t_detacher.attached = true;
    return env;
}

// NewStringUTF wants modified UTF-8 and aborts on anything else; log lines
// can carry arbitrary bytes, so decode through String(byte[], "UTF-8").
jstring to_jstring(JNIEnv *env, const std::string &s) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(s.size()),
                            reinterpret_cast<const jbyte *>(s.data()));
    jstring charset = env->NewStringUTF("UTF-8");
    auto str = static_cast<jstring>(env->NewObject(g_string_class, g_string_ctor, bytes, charset));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    return str;
}

std::string from_jstring(JNIEnv *env, jstring s) {
    if (!s)
        return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jobjectArray to_jarray(JNIEnv *env, const std::vector<std::string> &v) {
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(v.size()), g_string_class, nullptr);
    for (size_t i = 0; i < v.size(); i++) {
        jstring s = to_jstring(env, v[i]);
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

void clear_exception(JNIEnv *env) {
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

// Forwards chain::Listener calls to the Kotlin OpenVpn3.Callbacks object.
class JniListener : public chain::Listener {
public:
    JniListener(JNIEnv *env, jobject callbacks) : callbacks_(env->NewGlobalRef(callbacks)) {
        jclass cls = env->GetObjectClass(callbacks);
        on_log_ = env->GetMethodID(cls, "onLog", "(Ljava/lang/String;)V");
        on_event_ = env->GetMethodID(cls, "onEvent", "(Ljava/lang/String;Ljava/lang/String;ZZ)V");
        on_tun_ = env->GetMethodID(cls, "onTun", "(I[Ljava/lang/String;[Ljava/lang/String;I)Z");
        on_device_tun_ = env->GetMethodID(
            cls, "onDeviceTun",
            "([Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;I)I");
        env->DeleteLocalRef(cls);
    }

    ~JniListener() override {
        if (JNIEnv *env = env_for_thread())
            env->DeleteGlobalRef(callbacks_);
    }

    void on_log(const std::string &line) override {
        __android_log_write(ANDROID_LOG_INFO, "ovpn3", line.c_str());
        JNIEnv *env = env_for_thread();
        if (!env)
            return;
        jstring s = to_jstring(env, line);
        env->CallVoidMethod(callbacks_, on_log_, s);
        env->DeleteLocalRef(s);
        clear_exception(env);
    }

    void on_event(const openvpn::ClientAPI::Event &ev) override {
        JNIEnv *env = env_for_thread();
        if (!env)
            return;
        jstring name = to_jstring(env, ev.name);
        jstring info = to_jstring(env, ev.info);
        env->CallVoidMethod(callbacks_, on_event_, name, info, ev.error, ev.fatal);
        env->DeleteLocalRef(name);
        env->DeleteLocalRef(info);
        clear_exception(env);
    }

    bool on_tun(int fd, const chain::TunConfig &cfg) override {
        JNIEnv *env = env_for_thread();
        if (!env) {
            close(fd);
            return false;
        }
        jobjectArray addrs = to_jarray(env, cfg.addresses);
        jobjectArray dns = to_jarray(env, cfg.dns);
        jboolean ok = env->CallBooleanMethod(callbacks_, on_tun_, fd, addrs, dns, cfg.mtu);
        env->DeleteLocalRef(addrs);
        env->DeleteLocalRef(dns);
        if (env->ExceptionCheck()) {
            clear_exception(env);
            ok = JNI_FALSE;
        }
        // On success Kotlin passed fd on to the Go engine, which closes it.
        if (!ok)
            close(fd);
        return ok;
    }

    int on_device_tun(const chain::TunConfig &cfg) override {
        JNIEnv *env = env_for_thread();
        if (!env)
            return -1;
        jobjectArray addrs = to_jarray(env, cfg.addresses);
        jobjectArray routes = to_jarray(env, cfg.routes);
        jobjectArray dns = to_jarray(env, cfg.dns);
        jobjectArray domains = to_jarray(env, cfg.search_domains);
        jint fd = env->CallIntMethod(callbacks_, on_device_tun_, addrs, routes, dns, domains, cfg.mtu);
        env->DeleteLocalRef(addrs);
        env->DeleteLocalRef(routes);
        env->DeleteLocalRef(dns);
        env->DeleteLocalRef(domains);
        if (env->ExceptionCheck()) {
            clear_exception(env);
            return -1;
        }
        return fd;  // Kotlin detached it from its ParcelFileDescriptor
    }

private:
    jobject callbacks_;
    jmethodID on_log_;
    jmethodID on_event_;
    jmethodID on_tun_;
    jmethodID on_device_tun_;
};

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;
    jclass cls = env->FindClass("java/lang/String");
    g_string_class = static_cast<jclass>(env->NewGlobalRef(cls));
    g_string_ctor = env->GetMethodID(g_string_class, "<init>", "([BLjava/lang/String;)V");
    env->DeleteLocalRef(cls);

    // Process-wide OpenVPN 3 initialisation; lives as long as the library.
    static openvpn::ClientAPI::OpenVPNClientHelper helper;
    return JNI_VERSION_1_6;
}

// fun run(profile: String, username: String, password: String, response: String,
//         deviceTun: Boolean, callbacks: Callbacks): String?
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_meshkati_ovpnvless_OpenVpn3_run(JNIEnv *env, jobject, jstring profile,
                                               jstring username, jstring password,
                                               jstring response, jboolean device_tun,
                                               jobject callbacks) {
    JniListener listener(env, callbacks);
    chain::Client client(listener);
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_client)
            return to_jstring(env, "an OpenVPN session is already running");
        g_client = &client;
    }

    std::string err;
    try {
        err = client.run(from_jstring(env, profile), from_jstring(env, username),
                         from_jstring(env, password), from_jstring(env, response),
                         device_tun ? chain::Mode::Device : chain::Mode::Chain);
    } catch (const std::exception &e) {
        err = e.what();
    }

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_client = nullptr;
    }
    return err.empty() ? nullptr : to_jstring(env, err);
}

// fun stop()
extern "C" JNIEXPORT void JNICALL
Java_io_github_meshkati_ovpnvless_OpenVpn3_stop(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_client)
        g_client->stop();
}

// fun coreVersion(): String
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_meshkati_ovpnvless_OpenVpn3_coreVersion(JNIEnv *env, jobject) {
    return to_jstring(env, std::string("OpenVPN 3 core ") + OPENVPN_CORE_GIT_VERSION + ", " +
                               openvpn::ClientAPI::OpenVPNClientHelper::platform());
}
