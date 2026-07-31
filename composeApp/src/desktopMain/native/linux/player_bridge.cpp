/*
 * Linux mpv player bridge for NuvioDesktop.
 * Dynamically loads libmpv.so.2 and renders video into an AWT Canvas via X11 wid embedding.
 */

/* Enable verbose debug logging: set env NUVIO_MPV_DEBUG=1 */
static int g_debug = -1; /* -1 = uninitialized */
#define DBG(fmt, ...) do { \
    if (g_debug == -1) { \
        const char *e = getenv("NUVIO_MPV_DEBUG"); \
        g_debug = (e && e[0] == '1') ? 1 : 0; \
    } \
    if (g_debug) { fprintf(stderr, "[nuvio-mpv] " fmt "\n", ##__VA_ARGS__); fflush(stderr); } \
} while(0)
#define LOG(fmt, ...) do { fprintf(stderr, "[nuvio-mpv] " fmt "\n", ##__VA_ARGS__); fflush(stderr); } while(0)

#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <string>
#include <vector>
#include <map>
#include <mutex>
#include <atomic>
#include <thread>
#include <cstring>
#include <sstream>
#include <cstdio>
#include <cinttypes>
#include <clocale>
#include <future>
#include <sys/types.h>

/* ------------------------------------------------------------------ */
/*  mpv function pointer typedefs                                      */
/* ------------------------------------------------------------------ */

typedef struct mpv_handle mpv_handle;

typedef long long mpv_node_val_int64;
typedef double   mpv_node_val_double;
typedef struct mpv_node mpv_node;

struct mpv_event {
    int event_id;
    int error;
    void *data;
};

struct mpv_event_property {
    const char *name;
    int format;
    void *data;
};

typedef enum {
    MPV_FORMAT_NONE     = 0,
    MPV_FORMAT_STRING   = 1,
    MPV_FORMAT_FLAG     = 2,
    MPV_FORMAT_INT64    = 3,
    MPV_FORMAT_DOUBLE   = 4,
    MPV_FORMAT_NODE     = 5,
    MPV_FORMAT_NODE_ARRAY = 6,
    MPV_FORMAT_NODE_MAP   = 7,
    MPV_FORMAT_BYTE_ARRAY = 8,
} mpv_format;

typedef enum {
    MPV_EVENT_NONE              = 0,
    MPV_EVENT_SHUTDOWN          = 1,
    MPV_EVENT_LOG_MESSAGE       = 2,
    MPV_EVENT_GET_PROPERTY_REPLY = 3,
    MPV_EVENT_SET_PROPERTY_REPLY = 4,
    MPV_EVENT_COMMAND_REPLY     = 5,
    MPV_EVENT_START_FILE        = 6,
    MPV_EVENT_END_FILE          = 7,
    MPV_EVENT_FILE_LOADED       = 8,
    MPV_EVENT_IDLE              = 11,
    MPV_EVENT_TICK              = 14,
    MPV_EVENT_CLIENT_MESSAGE    = 16,
    MPV_EVENT_VIDEO_RECONFIG    = 17,
    MPV_EVENT_AUDIO_RECONFIG    = 18,
    MPV_EVENT_HOOK              = 19,
    MPV_EVENT_PROPERTY_CHANGE   = 23,
    MPV_EVENT_QUEUE_OVERFLOW    = 24,
} mpv_event_id;

typedef enum {
    MPV_END_FILE_REASON_EOF     = 0,
    MPV_END_FILE_REASON_STOP    = 1,
    MPV_END_FILE_REASON_QUIT    = 2,
    MPV_END_FILE_REASON_ERROR   = 3,
    MPV_END_FILE_REASON_REDIRECT = 4,
} mpv_end_file_reason;

typedef struct mpv_event_end_file {
    int reason;
    int error;
} mpv_event_end_file;

struct mpv_node_list {
    int num;
    mpv_node *values;
    char **keys;
};

struct mpv_byte_list {
    int size;
    char *data;
};

struct mpv_node {
    union {
        char *string;
        int flag;
        long long int64;
        double double_;
        struct mpv_node_list *list;
        struct mpv_byte_list *ba;
    } u;
    mpv_format format;
};

/* Function pointer types */
typedef mpv_handle*  (*mpv_create_t)(void);
typedef int          (*mpv_initialize_t)(mpv_handle*);
typedef void         (*mpv_terminate_destroy_t)(mpv_handle*);
typedef int          (*mpv_set_option_string_t)(mpv_handle*, const char*, const char*);
typedef int          (*mpv_set_option_t)(mpv_handle*, const char*, mpv_format, void*);
typedef int          (*mpv_set_property_t)(mpv_handle*, const char*, mpv_format, void*);
typedef int          (*mpv_set_property_string_t)(mpv_handle*, const char*, const char*);
typedef char*        (*mpv_get_property_string_t)(mpv_handle*, const char*);
typedef int          (*mpv_get_property_t)(mpv_handle*, const char*, mpv_format, void*);
typedef void         (*mpv_free_t)(void*);
typedef mpv_event*   (*mpv_wait_event_t)(mpv_handle*, double);
typedef void         (*mpv_wakeup_t)(mpv_handle*);
typedef int          (*mpv_observe_property_t)(mpv_handle*, uint64_t, const char*, mpv_format);
typedef int          (*mpv_unobserve_property_t)(mpv_handle*, uint64_t);
typedef int          (*mpv_request_event_t)(mpv_handle*, mpv_event_id, int);
typedef int          (*mpv_command_t)(mpv_handle*, const char**);
typedef int          (*mpv_command_string_t)(mpv_handle*, const char*);
typedef int64_t      (*mpv_get_time_us_t)(mpv_handle*);
typedef int          (*mpv_get_property_osd_string_t)(mpv_handle*, const char*);

/* ------------------------------------------------------------------ */
/*  Dynamic libmpv loader                                              */
/* ------------------------------------------------------------------ */

static void* gMpvLib = nullptr;

static mpv_create_t                  p_mpv_create                  = nullptr;
static mpv_initialize_t             p_mpv_initialize              = nullptr;
static mpv_terminate_destroy_t      p_mpv_terminate_destroy       = nullptr;
static mpv_set_option_string_t      p_mpv_set_option_string       = nullptr;
static mpv_set_option_t             p_mpv_set_option              = nullptr;
static mpv_set_property_t           p_mpv_set_property            = nullptr;
static mpv_set_property_string_t    p_mpv_set_property_string     = nullptr;
static mpv_get_property_string_t    p_mpv_get_property_string     = nullptr;
static mpv_get_property_t           p_mpv_get_property            = nullptr;
static mpv_free_t                   p_mpv_free                    = nullptr;
static mpv_wait_event_t             p_mpv_wait_event              = nullptr;
static mpv_wakeup_t                 p_mpv_wakeup                  = nullptr;
static mpv_observe_property_t       p_mpv_observe_property        = nullptr;
static mpv_unobserve_property_t     p_mpv_unobserve_property      = nullptr;
static mpv_request_event_t          p_mpv_request_event           = nullptr;
static mpv_command_t                p_mpv_command                 = nullptr;
static mpv_command_string_t         p_mpv_command_string          = nullptr;
static mpv_get_time_us_t            p_mpv_get_time_us             = nullptr;

static int load_libmpv() {
    if (gMpvLib) return 1;

    const char* lib_names[] = {
        "libmpv.so.2",
        "libmpv.so",
        nullptr
    };

    for (int i = 0; lib_names[i]; i++) {
        gMpvLib = dlopen(lib_names[i], RTLD_NOW | RTLD_GLOBAL);
        if (gMpvLib) break;
    }

    if (!gMpvLib) {
        fprintf(stderr, "[nuvio-mpv] Failed to load libmpv: %s\n", dlerror());
        return 0;
    }

#define LOAD_SYM(name) \
    p_##name = (name##_t)dlsym(gMpvLib, #name); \
    if (!p_##name) { fprintf(stderr, "[nuvio-mpv] Missing symbol: " #name "\n"); dlclose(gMpvLib); gMpvLib = nullptr; return 0; }

    LOAD_SYM(mpv_create);
    LOAD_SYM(mpv_initialize);
    LOAD_SYM(mpv_terminate_destroy);
    LOAD_SYM(mpv_set_option_string);
    LOAD_SYM(mpv_set_option);
    LOAD_SYM(mpv_set_property);
    LOAD_SYM(mpv_set_property_string);
    LOAD_SYM(mpv_get_property_string);
    LOAD_SYM(mpv_get_property);
    LOAD_SYM(mpv_free);
    LOAD_SYM(mpv_wait_event);
    LOAD_SYM(mpv_wakeup);
    LOAD_SYM(mpv_observe_property);
    LOAD_SYM(mpv_unobserve_property);
    LOAD_SYM(mpv_request_event);
    LOAD_SYM(mpv_command);
    LOAD_SYM(mpv_command_string);
    LOAD_SYM(mpv_get_time_us);

#undef LOAD_SYM

    fprintf(stderr, "[nuvio-mpv] libmpv loaded successfully\n");
    return 1;
}

/* ------------------------------------------------------------------ */
/*  Player instance                                                    */
/* ------------------------------------------------------------------ */

struct MpvPlayer {
    mpv_handle   *mpv;
    JavaVM       *jvm;
    jobject       eventSink;
    std::thread   eventThread;
    std::atomic<bool>  running;
    std::mutex    mutex;
    int64_t       wid;

    /* Cache for snapshot polling */
    std::atomic<double>  cachedDuration;
    std::atomic<double>  cachedPosition;
    std::atomic<double>  cachedBufferedPosition;
    std::atomic<int>     cachedPaused;
    std::atomic<int>     cachedEnded;

    MpvPlayer() : mpv(nullptr), jvm(nullptr), eventSink(nullptr), running(false),
                  wid(0), cachedDuration(0), cachedPosition(0), cachedBufferedPosition(0),
                  cachedPaused(1), cachedEnded(0) {}
    ~MpvPlayer() { destroy(); }

    void destroy() {
        bool expected = true;
        if (running.compare_exchange_strong(expected, false)) {
            if (mpv) {
                p_mpv_wakeup(mpv);
            }
            if (eventThread.joinable()) {
                eventThread.join();
            }
        }
        if (mpv) {
            std::lock_guard<std::mutex> lock(mutex);
            p_mpv_terminate_destroy(mpv);
            mpv = nullptr;
        }
        if (eventSink) {
            JNIEnv *env = nullptr;
            if (jvm && jvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) {
                env->DeleteGlobalRef(eventSink);
            }
            eventSink = nullptr;
        }
    }

    int initialize(JNIEnv *env, int64_t windowId, const char *sourceUrl,
                   const char * const *headers, int numHeaders,
                   int playWhenReady, int64_t initialPositionMs,
                   int decoderPriority, jobject sink)
    {
        LOG("initialize: windowId=0x%llx url=%s headers=%d playWhenReady=%d initialPos=%lld decoderPrio=%d",
            (unsigned long long)windowId, sourceUrl, numHeaders, playWhenReady,
            (long long)initialPositionMs, decoderPriority);

        /* mpv requires LC_NUMERIC=C for proper float parsing */
        setlocale(LC_NUMERIC, "C");

        if (!load_libmpv()) {
            LOG("libmpv not available");
            return -1;
        }

        mpv = p_mpv_create();
        if (!mpv) {
            LOG("mpv_create failed");
            return -1;
        }
        DBG("mpv_create OK");

        wid = windowId;

        /* Store JVM and event sink */
        env->GetJavaVM(&jvm);
        eventSink = env->NewGlobalRef(sink);
        DBG("JVM/eventSink stored");

        /* Configure mpv */
        p_mpv_set_option_string(mpv, "vo", "gpu-next");
        p_mpv_set_option_string(mpv, "gpu-context", "x11");
        p_mpv_set_option_string(mpv, "hwdec", decoderPriority > 0 ? "auto-safe" : "no");
        p_mpv_set_option_string(mpv, "cache", "yes");
        p_mpv_set_option_string(mpv, "cache-secs", "300");
        p_mpv_set_option_string(mpv, "demuxer-max-bytes", "500M");
        p_mpv_set_option_string(mpv, "demuxer-max-back-bytes", "100M");
        p_mpv_set_option_string(mpv, "keep-open", "no");
        p_mpv_set_option_string(mpv, "audio-file-auto", "no");
        p_mpv_set_option_string(mpv, "sub-auto", "no");
        p_mpv_set_option_string(mpv, "osd-level", "0");
        p_mpv_set_option_string(mpv, "input-default-bindings", "no");
        p_mpv_set_option_string(mpv, "input-vo-keyboard", "no");
        p_mpv_set_option_string(mpv, "terminal", "no");
        p_mpv_set_option_string(mpv, "msg-level", "all=error");
        p_mpv_set_option_string(mpv, "video-sync", "display-resample");
        p_mpv_set_option_string(mpv, "video-sync-max-video-change", "5");

        /* Set the X11 window handle via string (more compatible across mpv versions) */
        char widStr[32];
        snprintf(widStr, sizeof(widStr), "0x%llx", (unsigned long long)windowId);
        int wid_ret = p_mpv_set_option_string(mpv, "wid", widStr);
        DBG("mpv_set_option_string(wid=%s) = %d", widStr, wid_ret);
        if (wid_ret < 0) {
            wid_ret = p_mpv_set_option(mpv, "wid", MPV_FORMAT_INT64, &windowId);
            DBG("mpv_set_option(wid=INT64) = %d", wid_ret);
        }

        /* Apply custom headers if any */
        if (numHeaders > 0) {
            std::string headerStr;
            for (int i = 0; i < numHeaders; i++) {
                if (i > 0) headerStr += "\n";
                headerStr += headers[i];
            }
            p_mpv_set_option_string(mpv, "http-header-fields", headerStr.c_str());
            DBG("set %d headers", numHeaders);
        }

        /* Initialize mpv */
        DBG("calling mpv_initialize...");
        int ret = p_mpv_initialize(mpv);
        DBG("mpv_initialize returned: %d", ret);
        if (ret < 0) {
            LOG("mpv_initialize failed: %d", ret);
            p_mpv_terminate_destroy(mpv);
            mpv = nullptr;
            return -1;
        }

        /* Request events */
        p_mpv_request_event(mpv, MPV_EVENT_PROPERTY_CHANGE, 1);

        /* Observe properties */
        p_mpv_observe_property(mpv, 0, "time-pos", MPV_FORMAT_DOUBLE);
        p_mpv_observe_property(mpv, 0, "duration", MPV_FORMAT_DOUBLE);
        p_mpv_observe_property(mpv, 0, "pause", MPV_FORMAT_FLAG);
        p_mpv_observe_property(mpv, 0, "eof-reached", MPV_FORMAT_FLAG);
        p_mpv_observe_property(mpv, 0, "track-list", MPV_FORMAT_NODE);

        /* Load the file */
        const char *cmd[] = {"loadfile", sourceUrl, nullptr};
        p_mpv_command(mpv, cmd);

        if (playWhenReady) {
            p_mpv_set_property_string(mpv, "pause", "no");
        } else {
            p_mpv_set_property_string(mpv, "pause", "yes");
        }

        if (initialPositionMs > 0) {
            char seekStr[32];
            snprintf(seekStr, sizeof(seekStr), "%" PRId64, initialPositionMs / 1000);
            const char *seekCmd[] = {"seek", seekStr, "absolute", nullptr};
            p_mpv_command(mpv, seekCmd);
        }

        /* Start event thread */
        running = true;
        eventThread = std::thread(&MpvPlayer::eventLoop, this);

        return 0;
    }

    void eventLoop() {
        JNIEnv *env = nullptr;
        jint attachResult = jvm->AttachCurrentThread((void**)&env, nullptr);
        if (attachResult != JNI_OK) {
            LOG("Failed to attach event thread to JVM");
            return;
        }

        jclass sinkClass = (jclass)env->NewGlobalRef(
            env->FindClass("com/nuvio/app/features/player/desktop/NativePlayerEventSink"));
        jmethodID onEventMethod = env->GetMethodID(
            sinkClass, "onPlayerEvent", "(Ljava/lang/String;D)V");

        while (running) {
            mpv_event *event = p_mpv_wait_event(mpv, 0.25);
            if (!event) continue;
            if (!running) break;

            int evId = event->event_id;
            void *evData = event->data;

            std::lock_guard<std::mutex> lock(mutex);

            if (evId == MPV_EVENT_SHUTDOWN) {
                running = false;
                break;
            }

            if (evId == MPV_EVENT_END_FILE && evData) {
                mpv_event_end_file *ef = (mpv_event_end_file*)evData;
                cachedEnded = (ef->reason == MPV_END_FILE_REASON_EOF) ? 1 : 0;
            }

            if (evId == MPV_EVENT_FILE_LOADED) {
                cachedEnded = 0;
            }

            if (evId == MPV_EVENT_PROPERTY_CHANGE && evData) {
                mpv_event_property *prop = (mpv_event_property*)evData;
                if (!prop->name || !prop->data) continue;
                const char *pname = prop->name;
                void *pdata = prop->data;
                if (strcmp(pname, "time-pos") == 0 && prop->format == MPV_FORMAT_DOUBLE)
                    cachedPosition = *(double*)pdata;
                else if (strcmp(pname, "duration") == 0 && prop->format == MPV_FORMAT_DOUBLE)
                    cachedDuration = *(double*)pdata;
                else if (strcmp(pname, "pause") == 0 && prop->format == MPV_FORMAT_FLAG)
                    cachedPaused = *(int*)pdata;
                else if (strcmp(pname, "eof-reached") == 0 && prop->format == MPV_FORMAT_FLAG)
                    cachedEnded = *(int*)pdata;
            }
        }

        env->DeleteGlobalRef(sinkClass);
        jvm->DetachCurrentThread();
    }
};

/* ------------------------------------------------------------------ */
/*  JNI Helpers                                                        */
/* ------------------------------------------------------------------ */

static JavaVM *gGlobalJvm = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    gGlobalJvm = vm;
    return JNI_VERSION_1_6;
}

static MpvPlayer* get_player(jlong handle) {
    return reinterpret_cast<MpvPlayer*>(static_cast<uintptr_t>(handle));
}

static void throw_jni_error(JNIEnv *env, const char *msg) {
    jclass exClass = env->FindClass("java/lang/RuntimeException");
    if (exClass) {
        env->ThrowNew(exClass, msg);
    }
}

static std::string get_track_list_json(mpv_handle *mpv) {
    mpv_node node;
    int ret = p_mpv_get_property(mpv, "track-list", MPV_FORMAT_NODE, &node);
    if (ret < 0) return "[]";

    std::ostringstream json;
    json << "[";

    if (node.format == MPV_FORMAT_NODE_ARRAY && node.u.list) {
        for (int i = 0; i < node.u.list->num; i++) {
            if (i > 0) json << ",";

            mpv_node *entry = &node.u.list->values[i];
            json << "{";

            if (entry->format == MPV_FORMAT_NODE_MAP && entry->u.list) {
                bool first = true;
                for (int j = 0; j < entry->u.list->num; j++) {
                    if (!first) json << ",";
                    first = false;

                    const char *key = entry->u.list->keys[j];
                    mpv_node *val = &entry->u.list->values[j];

                    json << "\"" << key << "\":";

                    switch (val->format) {
                        case MPV_FORMAT_STRING:
                            json << "\"" << val->u.string << "\"";
                            break;
                        case MPV_FORMAT_FLAG:
                            json << (val->u.flag ? "true" : "false");
                            break;
                        case MPV_FORMAT_INT64:
                            json << val->u.int64;
                            break;
                        case MPV_FORMAT_DOUBLE:
                            json << val->u.double_;
                            break;
                        default:
                            json << "null";
                            break;
                    }
                }
            }

            json << "}";
        }
    }

    json << "]";

    p_mpv_free(&node);
    return json.str();
}

/* ------------------------------------------------------------------ */
/*  JNI Functions                                                      */
/* ------------------------------------------------------------------ */

#define JNI_CLASS "com/nuvio/app/features/player/desktop/NativePlayerBridge"

extern "C" {

JNIEXPORT jlong JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_create(
    JNIEnv *env, jclass clazz,
    jlong hostViewPtr,
    jstring sourceUrl,
    jobjectArray headerLines,
    jboolean playWhenReady,
    jlong initialPositionMs,
    jstring controlsPageUrl,
    jint decoderPriority,
    jboolean nvidiaRtxSuperResolutionEnabled,
    jobject eventSink)
{
    LOG("create: hostViewPtr=0x%llx", (unsigned long long)hostViewPtr);

    if (!load_libmpv()) {
        LOG("libmpv not available, aborting create");
        throw_jni_error(env, "libmpv is not installed. Install mpv via your package manager.");
        return 0;
    }

    const char *urlChars = env->GetStringUTFChars(sourceUrl, nullptr);
    if (!urlChars) {
        LOG("sourceUrl is null");
        return 0;
    }
    LOG("create: url=%s", urlChars);

    /* Collect headers */
    std::vector<const char*> headers;
    jsize numHeaders = headerLines ? env->GetArrayLength(headerLines) : 0;
    for (jsize i = 0; i < numHeaders; i++) {
        jstring hs = (jstring)env->GetObjectArrayElement(headerLines, i);
        if (hs) {
            headers.push_back(env->GetStringUTFChars(hs, nullptr));
        }
    }

    MpvPlayer *player = new MpvPlayer();
    LOG("create: calling player->initialize...");
    int ret = player->initialize(env, static_cast<int64_t>(hostViewPtr),
                                  urlChars,
                                  headers.data(), (int)headers.size(),
                                  playWhenReady, static_cast<int64_t>(initialPositionMs),
                                  decoderPriority, eventSink);
    LOG("create: player->initialize returned %d", ret);

    env->ReleaseStringUTFChars(sourceUrl, urlChars);
    for (size_t i = 0; i < headers.size(); i++) {
        if (headers[i]) {
            jstring hs = (jstring)env->GetObjectArrayElement(headerLines, i);
            if (hs) env->ReleaseStringUTFChars(hs, headers[i]);
        }
    }

    if (ret < 0) {
        LOG("create: initialization failed, deleting player");
        delete player;
        return 0;
    }

    jlong handle = static_cast<jlong>(reinterpret_cast<uintptr_t>(player));
    LOG("create: success, handle=0x%llx", (unsigned long long)handle);
    return handle;
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_dispose(
    JNIEnv *env, jclass clazz, jlong handle)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return;
    player->destroy();
    delete player;
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_updateControls(
    JNIEnv *env, jclass clazz, jlong handle, jstring controlsJson)
{
    /* Controls overlay requires WebKitGTK; not implemented yet.
     * Compose-based controls work independently. */
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_requestFocus(
    JNIEnv *env, jclass clazz, jlong handle)
{
    /* AWT focus handling is sufficient. */
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setPaused(
    JNIEnv *env, jclass clazz, jlong handle, jboolean paused)
{
    MpvPlayer *player = get_player(handle);
    if (!player || !player->mpv) return;
    std::lock_guard<std::mutex> _l(player->mutex);
    p_mpv_set_property_string(player->mpv, "pause", paused ? "yes" : "no");
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_seekTo(
    JNIEnv *env, jclass clazz, jlong handle, jlong positionMs)
{
    MpvPlayer *player = get_player(handle);
    if (!player || !player->mpv) return;
    std::lock_guard<std::mutex> _l(player->mutex);
    char seekStr[32];
    snprintf(seekStr, sizeof(seekStr), "%" PRId64, positionMs / 1000);
    const char *cmd[] = {"seek", seekStr, "absolute", nullptr};
    p_mpv_command(player->mpv, cmd);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_seekBy(
    JNIEnv *env, jclass clazz, jlong handle, jlong offsetMs)
{
    MpvPlayer *player = get_player(handle);
    if (!player || !player->mpv) return;
    std::lock_guard<std::mutex> _l(player->mutex);
    char seekStr[32];
    snprintf(seekStr, sizeof(seekStr), "%" PRId64, offsetMs / 1000);
    const char *cmd[] = {"seek", seekStr, "relative", nullptr};
    p_mpv_command(player->mpv, cmd);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setSpeed(
    JNIEnv *env, jclass clazz, jlong handle, jfloat speed)
{
    MpvPlayer *player = get_player(handle);
    if (!player || !player->mpv) return;
    std::lock_guard<std::mutex> _l(player->mutex);
    char speedStr[16];
    snprintf(speedStr, sizeof(speedStr), "%.2f", (double)speed);
    p_mpv_set_property_string(player->mpv, "speed", speedStr);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_adjustVolume(
    JNIEnv *env, jclass clazz, jlong handle, jfloat delta)
{
    MpvPlayer *player = get_player(handle);
    if (!player || !player->mpv) return;
    std::lock_guard<std::mutex> _l(player->mutex);
    double vol = 100.0;
    p_mpv_get_property(player->mpv, "volume", MPV_FORMAT_DOUBLE, &vol);
    vol += delta;
    if (vol < 0) vol = 0;
    if (vol > 200) vol = 200;
    char volStr[16];
    snprintf(volStr, sizeof(volStr), "%.0f", vol);
    p_mpv_set_property_string(player->mpv, "volume", volStr);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setVolume(
    JNIEnv *env, jclass clazz, jlong handle, jfloat level)
{
    MpvPlayer *player = get_player(handle);
    if (!player || !player->mpv) return;
    std::lock_guard<std::mutex> _l(player->mutex);
    char volStr[16];
    snprintf(volStr, sizeof(volStr), "%.0f", (double)(level * 100.0f));
    p_mpv_set_property_string(player->mpv, "volume", volStr);
}

JNIEXPORT jfloat JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_volume(
    JNIEnv *env, jclass clazz, jlong handle)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return 0.0f;
    double vol = 100.0;
    std::lock_guard<std::mutex> _l(player->mutex);
    p_mpv_get_property(player->mpv, "volume", MPV_FORMAT_DOUBLE, &vol);
    return (jfloat)(vol / 100.0);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setResizeMode(
    JNIEnv *env, jclass clazz, jlong handle, jint mode)
{
    MpvPlayer *player = get_player(handle);
    if (!player || !player->mpv) return;
    std::lock_guard<std::mutex> _l(player->mutex);
    switch (mode) {
        case 0:
            p_mpv_set_property_string(player->mpv, "panscan", "0");
            p_mpv_set_property_string(player->mpv, "video-zoom", "0");
            p_mpv_set_property_string(player->mpv, "video-fit", "contain");
            break;
        case 1:
            p_mpv_set_property_string(player->mpv, "panscan", "0");
            p_mpv_set_property_string(player->mpv, "video-zoom", "0");
            p_mpv_set_property_string(player->mpv, "video-fit", "fill");
            break;
        case 2:
            p_mpv_set_property_string(player->mpv, "panscan", "0");
            p_mpv_set_property_string(player->mpv, "video-zoom", "0.5");
            p_mpv_set_property_string(player->mpv, "video-fit", "contain");
            break;
        case 3:
            p_mpv_set_property_string(player->mpv, "panscan", "0");
            p_mpv_set_property_string(player->mpv, "video-zoom", "0");
            p_mpv_set_property_string(player->mpv, "video-fit", "stretch");
            break;
    }
}

/* Getters use atomic cache — no mutex needed */
JNIEXPORT jlong JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_durationMs(
    JNIEnv *env, jclass clazz, jlong handle)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return 0;
    return (jlong)(player->cachedDuration * 1000.0);
}

JNIEXPORT jlong JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_positionMs(
    JNIEnv *env, jclass clazz, jlong handle)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return 0;
    double pos = player->cachedPosition;
    return pos < 0 ? 0 : (jlong)(pos * 1000.0);
}

JNIEXPORT jlong JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_bufferedPositionMs(
    JNIEnv *env, jclass clazz, jlong handle)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return 0;
    return (jlong)(player->cachedBufferedPosition * 1000.0);
}

JNIEXPORT jboolean JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_isLoading(
    JNIEnv *env, jclass clazz, jlong handle)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return JNI_FALSE;
    /* Check cache-busy with mutex since it's not cached */
    int busy = 0;
    { std::lock_guard<std::mutex> _l(player->mutex);
    p_mpv_get_property(player->mpv, "cache-busy", MPV_FORMAT_FLAG, &busy); }
    return busy ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_isEnded(
    JNIEnv *env, jclass clazz, jlong handle)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return JNI_FALSE;
    return player->cachedEnded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_isPaused(
    JNIEnv *env, jclass clazz, jlong handle)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return JNI_TRUE;
    return player->cachedPaused ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_speed(
    JNIEnv *env, jclass clazz, jlong handle)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return 1.0f;
    double speed = 1.0;
    { std::lock_guard<std::mutex> _l(player->mutex); p_mpv_get_property(player->mpv, "speed", MPV_FORMAT_DOUBLE, &speed); }
    return (jfloat)speed;
}

static std::string buildTrackListJson(mpv_handle *mpv, const char *trackType) {
    mpv_node node;
    int ret = p_mpv_get_property(mpv, "track-list", MPV_FORMAT_NODE, &node);
    if (ret < 0) return "[]";

    std::string json;
    json = "[";
    if (node.format == MPV_FORMAT_NODE_ARRAY && node.u.list) {
        int idx = 0;
        for (int i = 0; i < node.u.list->num; i++) {
            mpv_node *entry = &node.u.list->values[i];
            if (entry->format != MPV_FORMAT_NODE_MAP || !entry->u.list) continue;

            const char *type = nullptr;
            for (int j = 0; j < entry->u.list->num; j++) {
                if (strcmp(entry->u.list->keys[j], "type") == 0 &&
                    entry->u.list->values[j].format == MPV_FORMAT_STRING) {
                    type = entry->u.list->values[j].u.string;
                    break;
                }
            }
            if (!type || strcmp(type, trackType) != 0) continue;

            if (idx > 0) json += ",";
            json += "{";

            int id = idx;
            const char *lang = "";
            const char *label = "";
            int selected = 0;
            int forced = 0;

            for (int j = 0; j < entry->u.list->num; j++) {
                const char *key = entry->u.list->keys[j];
                mpv_node *val = &entry->u.list->values[j];

                if (strcmp(key, "id") == 0 && val->format == MPV_FORMAT_INT64)
                    id = (int)val->u.int64;
                else if (strcmp(key, "lang") == 0 && val->format == MPV_FORMAT_STRING)
                    lang = val->u.string ? val->u.string : "";
                else if (strcmp(key, "title") == 0 && val->format == MPV_FORMAT_STRING)
                    label = val->u.string ? val->u.string : "";
                else if (strcmp(key, "selected") == 0 && val->format == MPV_FORMAT_FLAG)
                    selected = val->u.flag;
                else if (strcmp(key, "forced") == 0 && val->format == MPV_FORMAT_FLAG)
                    forced = val->u.flag;
            }

            char idStr[16], idxStr[16];
            snprintf(idStr, sizeof(idStr), "%d", id);
            snprintf(idxStr, sizeof(idxStr), "%d", idx);

            json += "\"index\":"; json += idxStr;
            json += ",\"id\":\""; json += idStr; json += "\"";
            json += ",\"label\":\""; json += label; json += "\"";
            json += ",\"language\":\""; json += lang; json += "\"";
            json += selected ? ",\"selected\":true" : ",\"selected\":false";
            json += ",\"forced\":";
            json += forced ? "true" : "false";
            json += "}";

            idx++;
        }
    }
    json += "]";

    p_mpv_free(&node);
    return json;
}

JNIEXPORT jstring JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_audioTracksJson(
    JNIEnv *env, jclass clazz, jlong handle)
{
    MpvPlayer *player = get_player(handle);
    if (!player || !player->mpv) return env->NewStringUTF("[]");
    std::lock_guard<std::mutex> _l(player->mutex);
    std::string json = buildTrackListJson(player->mpv, "audio");
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT jstring JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_subtitleTracksJson(
    JNIEnv *env, jclass clazz, jlong handle)
{
    MpvPlayer *player = get_player(handle);
    if (!player || !player->mpv) return env->NewStringUTF("[]");
    std::lock_guard<std::mutex> _l(player->mutex);
    std::string json = buildTrackListJson(player->mpv, "sub");
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_selectAudioTrack(
    JNIEnv *env, jclass clazz, jlong handle, jint trackId)
{
    MpvPlayer *player = get_player(handle);
    if (!player || !player->mpv) return;
    std::lock_guard<std::mutex> _l(player->mutex);
    char idStr[16];
    snprintf(idStr, sizeof(idStr), "%d", trackId);
    p_mpv_set_property_string(player->mpv, "aid", idStr);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_selectSubtitleTrack(
    JNIEnv *env, jclass clazz, jlong handle, jint trackId)
{
    MpvPlayer *player = get_player(handle);
    if (!player || !player->mpv) return;
    std::lock_guard<std::mutex> _l(player->mutex);
    char idStr[16];
    snprintf(idStr, sizeof(idStr), "%d", trackId);
    if (trackId < 0) {
        p_mpv_set_property_string(player->mpv, "sub-visibility", "no");
    } else {
        p_mpv_set_property_string(player->mpv, "sid", idStr);
        p_mpv_set_property_string(player->mpv, "sub-visibility", "yes");
    }
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_addSubtitleUrl(
    JNIEnv *env, jclass clazz, jlong handle, jstring url)
{
    MpvPlayer *player = get_player(handle);
    if (!player || !player->mpv) return;
    const char *urlChars = env->GetStringUTFChars(url, nullptr);
    if (!urlChars) return;
    std::lock_guard<std::mutex> _l(player->mutex);
    const char *cmd[] = {"sub-add", urlChars, "auto", nullptr};
    p_mpv_command(player->mpv, cmd);
    env->ReleaseStringUTFChars(url, urlChars);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_clearExternalSubtitles(
    JNIEnv *env, jclass clazz, jlong handle)
{
    MpvPlayer *player = get_player(handle);
    if (!player || !player->mpv) return;
    std::lock_guard<std::mutex> _l(player->mutex);
    const char *cmd[] = {"sub-remove", nullptr};
    p_mpv_command(player->mpv, cmd);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_clearExternalSubtitlesAndSelect(
    JNIEnv *env, jclass clazz, jlong handle, jint trackId)
{
    MpvPlayer *player = get_player(handle);
    if (!player || !player->mpv) return;
    std::lock_guard<std::mutex> _l(player->mutex);
    const char *cmd[] = {"sub-remove", nullptr};
    p_mpv_command(player->mpv, cmd);
    char idStr[16];
    snprintf(idStr, sizeof(idStr), "%d", trackId);
    if (trackId < 0) {
        p_mpv_set_property_string(player->mpv, "sub-visibility", "no");
    } else {
        p_mpv_set_property_string(player->mpv, "sid", idStr);
        p_mpv_set_property_string(player->mpv, "sub-visibility", "yes");
    }
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setSubtitleDelayMs(
    JNIEnv *env, jclass clazz, jlong handle, jint delayMs)
{
    MpvPlayer *player = get_player(handle);
    if (!player || !player->mpv) return;
    std::lock_guard<std::mutex> _l(player->mutex);
    char delayStr[16];
    snprintf(delayStr, sizeof(delayStr), "%.3f", (double)delayMs / 1000.0);
    p_mpv_set_property_string(player->mpv, "sub-delay", delayStr);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_applySubtitleStyle(
    JNIEnv *env, jclass clazz, jlong handle,
    jstring textColor, jstring backgroundColor, jstring outlineColor,
    jfloat outlineSize, jboolean bold, jfloat fontSize, jint subPos)
{
    MpvPlayer *player = get_player(handle);
    if (!player || !player->mpv) return;
    const char *c_textColor = env->GetStringUTFChars(textColor, nullptr);
    const char *c_bgColor = env->GetStringUTFChars(backgroundColor, nullptr);
    const char *c_outlineColor = env->GetStringUTFChars(outlineColor, nullptr);

    {
        std::lock_guard<std::mutex> _l(player->mutex);
        if (c_textColor) p_mpv_set_property_string(player->mpv, "sub-color", c_textColor);
        if (c_bgColor) p_mpv_set_property_string(player->mpv, "sub-back-color", c_bgColor);
        if (c_outlineColor) p_mpv_set_property_string(player->mpv, "sub-border-color", c_outlineColor);
        char floatStr[16];
        snprintf(floatStr, sizeof(floatStr), "%.1f", (double)outlineSize);
        p_mpv_set_property_string(player->mpv, "sub-border-size", floatStr);
        p_mpv_set_property_string(player->mpv, "sub-bold", bold ? "yes" : "no");
        snprintf(floatStr, sizeof(floatStr), "%.0f", (double)fontSize);
        p_mpv_set_property_string(player->mpv, "sub-font-size", floatStr);
        char posStr[16];
        snprintf(posStr, sizeof(posStr), "%d", subPos);
        p_mpv_set_property_string(player->mpv, "sub-pos", posStr);
    }

    if (c_textColor) env->ReleaseStringUTFChars(textColor, c_textColor);
    if (c_bgColor) env->ReleaseStringUTFChars(backgroundColor, c_bgColor);
    if (c_outlineColor) env->ReleaseStringUTFChars(outlineColor, c_outlineColor);
}

} /* extern "C" */
