/*
 * Linux mpv player bridge for NuvioDesktop.
 * Dynamically loads libmpv.so.2 and renders video via the libmpv render API
 * (software mode) into memory buffers consumed by the Compose UI.
 * Display-agnostic: works on both X11 and Wayland sessions.
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
#include <functional>
#include <chrono>
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
    uint64_t reply_userdata;
    void *data;
};

struct mpv_event_property {
    const char *name;
    int format;
    void *data;
};

struct mpv_event_log_message {
    const char *prefix;
    const char *level;
    const char *text;
    int log_level;
};

typedef enum {
    MPV_FORMAT_NONE     = 0,
    MPV_FORMAT_STRING   = 1,
    MPV_FORMAT_OSD_STRING = 2,
    MPV_FORMAT_FLAG     = 3,
    MPV_FORMAT_INT64    = 4,
    MPV_FORMAT_DOUBLE   = 5,
    MPV_FORMAT_NODE     = 6,
    MPV_FORMAT_NODE_ARRAY = 7,
    MPV_FORMAT_NODE_MAP   = 8,
    MPV_FORMAT_BYTE_ARRAY = 9,
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
    MPV_EVENT_SEEK              = 20,
    MPV_EVENT_PLAYBACK_RESTART  = 21,
    MPV_EVENT_PROPERTY_CHANGE   = 22,
    MPV_EVENT_QUEUE_OVERFLOW    = 24,
    MPV_EVENT_HOOK              = 25,
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
typedef int          (*mpv_load_config_file_t)(mpv_handle*, const char*);

/* Render API (software mode) — structs/constants mirror mpv/render.h ABI */
typedef struct mpv_render_context mpv_render_context;

typedef struct mpv_render_param {
    int   type;
    void *data;
} mpv_render_param;

enum {
    MPV_RENDER_PARAM_INVALID    = 0,
    MPV_RENDER_PARAM_API_TYPE   = 1,
    MPV_RENDER_PARAM_OPENGL_INIT_PARAMS = 2,
    MPV_RENDER_PARAM_OPENGL_FBO = 3,
    MPV_RENDER_PARAM_FLIP_Y     = 4,
    MPV_RENDER_PARAM_SW_SIZE    = 17,
    MPV_RENDER_PARAM_SW_FORMAT  = 18,
    MPV_RENDER_PARAM_SW_STRIDE  = 19,
    MPV_RENDER_PARAM_SW_POINTER = 20,
};

#define MPV_RENDER_API_TYPE_SW  "sw"
#define MPV_RENDER_UPDATE_FRAME (1 << 0)

typedef int  (*mpv_render_context_create_t)(mpv_render_context**, mpv_handle*, mpv_render_param*);
typedef void (*mpv_render_context_free_t)(mpv_render_context*);
typedef void (*mpv_render_context_set_update_callback_t)(mpv_render_context*, void (*)(void*), void*);
typedef int  (*mpv_render_context_update_t)(mpv_render_context*);
typedef int  (*mpv_render_context_render_t)(mpv_render_context*, mpv_render_param*);

/* OpenGL render API structs (mirror mpv/render_gl.h ABI) */
typedef struct mpv_opengl_init_params {
    void *(*get_proc_address)(void *ctx, const char *name);
    void *get_proc_address_ctx;
} mpv_opengl_init_params;

typedef struct mpv_opengl_fbo {
    int fbo;
    int w;
    int h;
    int internal_format;
} mpv_opengl_fbo;

#define MPV_RENDER_API_TYPE_OPENGL "opengl"

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
static mpv_load_config_file_t       p_mpv_load_config_file        = nullptr;
static mpv_render_context_create_t  p_mpv_render_context_create   = nullptr;
static mpv_render_context_free_t    p_mpv_render_context_free     = nullptr;
static mpv_render_context_set_update_callback_t p_mpv_render_context_set_update_callback = nullptr;
static mpv_render_context_update_t  p_mpv_render_context_update   = nullptr;
static mpv_render_context_render_t  p_mpv_render_context_render   = nullptr;

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
    LOAD_SYM(mpv_load_config_file);
    LOAD_SYM(mpv_render_context_create);
    LOAD_SYM(mpv_render_context_free);
    LOAD_SYM(mpv_render_context_set_update_callback);
    LOAD_SYM(mpv_render_context_update);
    LOAD_SYM(mpv_render_context_render);

#undef LOAD_SYM

    fprintf(stderr, "[nuvio-mpv] libmpv loaded successfully\n");
    return 1;
}

/* ------------------------------------------------------------------ */
/*  Offscreen EGL renderer (OpenGL render API)                         */
/*  Renders frames into an FBO on the GPU, then glReadPixels into the  */
/*  Kotlin-provided buffer. Falls back to the SW renderer if EGL/GL    */
/*  initialization fails.                                              */
/* ------------------------------------------------------------------ */

#define EGL_PLATFORM_SURFACELESS_MESA 0x31DD
#define EGL_CONTEXT_MAJOR_VERSION     0x3098
#define EGL_CONTEXT_MINOR_VERSION     0x30FB
#define EGL_CONTEXT_OPENGL_PROFILE_MASK 0x30FD
#define EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT 0x00000001
#define EGL_NONE                      0x3038
#define EGL_DEFAULT_DISPLAY           ((void*)0)
#define EGL_NO_CONTEXT                ((void*)0)
#define EGL_NO_SURFACE                ((void*)0)
#define EGL_RENDERABLE_TYPE           0x3040
#define EGL_OPENGL_BIT                0x0020
#define EGL_OPENGL_ES2_BIT            0x0004
#define EGL_SURFACE_TYPE              0x3033
#define EGL_PBUFFER_BIT               0x0001
#define EGL_RED_SIZE                  0x3024
#define EGL_GREEN_SIZE                0x3023
#define EGL_BLUE_SIZE                 0x3022
#define EGL_ALPHA_SIZE                0x3021
#define EGL_PLATFORM_X11_EXT          0x31D5
#define EGL_PLATFORM_WAYLAND_EXT      0x31D8

#define GL_FRAMEBUFFER                0x8D40
#define GL_FRAMEBUFFER_COMPLETE       0x8CD5
#define GL_COLOR_ATTACHMENT0          0x8CE0
#define GL_TEXTURE_2D                 0x0DE1
#define GL_RGBA                       0x1908
#define GL_RGBA8                      0x8058
#define GL_UNSIGNED_BYTE              0x1401
#define GL_LINEAR                     0x2601
#define GL_TEXTURE_MIN_FILTER         0x2801
#define GL_TEXTURE_MAG_FILTER         0x2800
#define GL_CLAMP_TO_EDGE              0x812F
#define GL_TEXTURE_WRAP_S             0x2802
#define GL_TEXTURE_WRAP_T             0x2803
#define GL_COLOR_BUFFER_BIT           0x4000

typedef void* (*egl_get_proc_address_t)(const char*);
typedef void* (*egl_get_platform_display_t)(unsigned int, void*, const int*);
typedef void* (*egl_get_display_t)(void*);
typedef int   (*egl_initialize_t)(void*, int*, int*);
typedef int   (*egl_choose_config_t)(void*, const int*, void*, int, int*);
typedef void* (*egl_create_context_t)(void*, void*, void*, const int*);
typedef int   (*egl_make_current_t)(void*, void*, void*, void*);
typedef int   (*egl_destroy_context_t)(void*, void*);
typedef int   (*egl_terminate_t)(void*);
typedef int   (*egl_get_error_t)(void);

typedef void (*gl_gen_framebuffers_t)(int, unsigned int*);
typedef void (*gl_delete_framebuffers_t)(int, const unsigned int*);
typedef void (*gl_bind_framebuffer_t)(unsigned int, unsigned int);
typedef void (*gl_framebuffer_texture2d_t)(unsigned int, unsigned int, unsigned int, unsigned int, int);
typedef void (*gl_gen_textures_t)(int, unsigned int*);
typedef void (*gl_delete_textures_t)(int, const unsigned int*);
typedef void (*gl_bind_texture_t)(unsigned int, unsigned int);
typedef void (*gl_tex_image2d_t)(unsigned int, int, int, int, int, int, unsigned int, unsigned int, const void*);
typedef void (*gl_tex_parameteri_t)(unsigned int, unsigned int, int);
typedef int  (*gl_check_framebuffer_t)(unsigned int);
typedef void (*gl_read_pixels_t)(int, int, int, int, unsigned int, unsigned int, void*);
typedef void (*gl_clear_color_t)(float, float, float, float);
typedef void (*gl_clear_t)(unsigned int);

struct GlRenderer {
    void *eglLib = nullptr;
    void *display = nullptr;
    void *context = nullptr;
    unsigned int fbo = 0;
    unsigned int texture = 0;
    int width = 0;
    int height = 0;
    bool ready = false;

    egl_get_proc_address_t eglGetProcAddress = nullptr;
    egl_make_current_t eglMakeCurrent = nullptr;
    egl_destroy_context_t eglDestroyContext = nullptr;
    egl_terminate_t eglTerminate = nullptr;
    egl_get_error_t eglGetError = nullptr;
    void *(*eglGetCurrentContext)(void) = nullptr;

    gl_gen_framebuffers_t glGenFramebuffers = nullptr;
    gl_delete_framebuffers_t glDeleteFramebuffers = nullptr;
    gl_bind_framebuffer_t glBindFramebuffer = nullptr;
    gl_framebuffer_texture2d_t glFramebufferTexture2D = nullptr;
    gl_gen_textures_t glGenTextures = nullptr;
    gl_delete_textures_t glDeleteTextures = nullptr;
    gl_bind_texture_t glBindTexture = nullptr;
    gl_tex_image2d_t glTexImage2D = nullptr;
    gl_tex_parameteri_t glTexParameteri = nullptr;
    gl_check_framebuffer_t glCheckFramebufferStatus = nullptr;
    gl_read_pixels_t glReadPixels = nullptr;
    gl_clear_color_t glClearColor = nullptr;
    gl_clear_t glClear = nullptr;
};

static void *gl_resolve(GlRenderer *gl, const char *name) {
    void *p = nullptr;
    if (gl->eglGetProcAddress) {
        p = gl->eglGetProcAddress(name);
    }
    if (!p) {
        p = dlsym(RTLD_DEFAULT, name);
    }
    return p;
}

static void *gl_get_proc_address_cb(void *ctx, const char *name) {
    return gl_resolve(static_cast<GlRenderer*>(ctx), name);
}

static void gl_destroy(GlRenderer *gl) {
    if (gl->display && gl->context) {
        gl->eglMakeCurrent(gl->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        gl->eglDestroyContext(gl->display, gl->context);
        gl->context = nullptr;
    }
    if (gl->display) {
        gl->eglTerminate(gl->display);
        gl->display = nullptr;
    }
    if (gl->eglLib) {
        dlclose(gl->eglLib);
        gl->eglLib = nullptr;
    }
    gl->ready = false;
}

static bool gl_init(GlRenderer *gl) {
    gl->eglLib = dlopen("libEGL.so.1", RTLD_NOW | RTLD_GLOBAL);
    if (!gl->eglLib) {
        LOG("GL renderer unavailable: libEGL.so.1 not found, falling back to SW renderer");
        return false;
    }
    gl->eglGetProcAddress = (egl_get_proc_address_t)dlsym(gl->eglLib, "eglGetProcAddress");
    gl->eglGetError = (egl_get_error_t)dlsym(gl->eglLib, "eglGetError");
    gl->eglGetCurrentContext = (void *(*)(void))dlsym(gl->eglLib, "eglGetCurrentContext");
    auto eglGetPlatformDisplay = (egl_get_platform_display_t)dlsym(gl->eglLib, "eglGetPlatformDisplay");
    auto eglGetDisplay = (egl_get_display_t)dlsym(gl->eglLib, "eglGetDisplay");
    auto eglInitialize = (egl_initialize_t)dlsym(gl->eglLib, "eglInitialize");
    auto eglChooseConfig = (egl_choose_config_t)dlsym(gl->eglLib, "eglChooseConfig");
    auto eglCreateContext = (egl_create_context_t)dlsym(gl->eglLib, "eglCreateContext");
    gl->eglMakeCurrent = (egl_make_current_t)dlsym(gl->eglLib, "eglMakeCurrent");
    gl->eglDestroyContext = (egl_destroy_context_t)dlsym(gl->eglLib, "eglDestroyContext");
    gl->eglTerminate = (egl_terminate_t)dlsym(gl->eglLib, "eglTerminate");
    if (!gl->eglGetProcAddress || !eglGetPlatformDisplay || !eglInitialize || !eglChooseConfig ||
        !eglCreateContext || !gl->eglMakeCurrent || !gl->eglDestroyContext || !gl->eglTerminate) {
        LOG("GL renderer unavailable: missing EGL symbols, falling back to SW renderer");
        dlclose(gl->eglLib);
        gl->eglLib = nullptr;
        return false;
    }

    /* Surfaceless EGL (Mesa) works on both X11 and Wayland with no window. */
    gl->display = eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, nullptr);
    if (!gl->display) {
        gl->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    }
    if (!gl->display) {
        LOG("GL renderer unavailable: no EGL display, falling back to SW renderer");
        dlclose(gl->eglLib);
        gl->eglLib = nullptr;
        return false;
    }
    int major = 0, minor = 0;
    if (!eglInitialize(gl->display, &major, &minor)) {
        LOG("GL renderer unavailable: eglInitialize failed, falling back to SW renderer");
        dlclose(gl->eglLib);
        gl->eglLib = nullptr;
        return false;
    }

    int configAttrsA[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE,
    };
    int configAttrsB[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
        EGL_NONE,
    };
    int configAttrsC[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE,
    };
    int configAttrsD[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE,
    };
    const int *configAttrsList[] = { configAttrsA, configAttrsB, configAttrsC, configAttrsD };
    void *config = nullptr;
    int numConfigs = 0;
    for (const int *attrs : configAttrsList) {
        if (eglChooseConfig(gl->display, attrs, &config, 1, &numConfigs) && numConfigs >= 1) {
            break;
        }
        config = nullptr;
        numConfigs = 0;
    }
    if (!config || numConfigs < 1) {
        LOG("GL renderer unavailable: no EGL config, falling back to SW renderer");
        gl_destroy(gl);
        return false;
    }

    int contextAttrs[] = {
        EGL_CONTEXT_MAJOR_VERSION, 3,
        EGL_CONTEXT_MINOR_VERSION, 3,
        EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT,
        EGL_NONE,
    };
    gl->context = eglCreateContext(gl->display, config, EGL_NO_CONTEXT, contextAttrs);
    if (!gl->context) {
        int legacyAttrs[] = { EGL_CONTEXT_MAJOR_VERSION, 2, EGL_NONE };
        gl->context = eglCreateContext(gl->display, config, EGL_NO_CONTEXT, legacyAttrs);
    }
    if (!gl->context) {
        int es2Attrs[] = { EGL_CONTEXT_MAJOR_VERSION, 2, EGL_NONE };
        gl->context = eglCreateContext(gl->display, config, EGL_NO_CONTEXT, es2Attrs);
    }
    if (!gl->context) {
        LOG("GL renderer unavailable: eglCreateContext failed, falling back to SW renderer");
        gl_destroy(gl);
        return false;
    }
    if (!gl->eglMakeCurrent(gl->display, EGL_NO_SURFACE, EGL_NO_SURFACE, gl->context)) {
        LOG("GL renderer unavailable: eglMakeCurrent failed, falling back to SW renderer");
        gl_destroy(gl);
        return false;
    }

    gl->glGenFramebuffers = (gl_gen_framebuffers_t)gl_resolve(gl, "glGenFramebuffers");
    gl->glDeleteFramebuffers = (gl_delete_framebuffers_t)gl_resolve(gl, "glDeleteFramebuffers");
    gl->glBindFramebuffer = (gl_bind_framebuffer_t)gl_resolve(gl, "glBindFramebuffer");
    gl->glFramebufferTexture2D = (gl_framebuffer_texture2d_t)gl_resolve(gl, "glFramebufferTexture2D");
    gl->glGenTextures = (gl_gen_textures_t)gl_resolve(gl, "glGenTextures");
    gl->glDeleteTextures = (gl_delete_textures_t)gl_resolve(gl, "glDeleteTextures");
    gl->glBindTexture = (gl_bind_texture_t)gl_resolve(gl, "glBindTexture");
    gl->glTexImage2D = (gl_tex_image2d_t)gl_resolve(gl, "glTexImage2D");
    gl->glTexParameteri = (gl_tex_parameteri_t)gl_resolve(gl, "glTexParameteri");
    gl->glCheckFramebufferStatus = (gl_check_framebuffer_t)gl_resolve(gl, "glCheckFramebufferStatus");
    gl->glReadPixels = (gl_read_pixels_t)gl_resolve(gl, "glReadPixels");
    gl->glClearColor = (gl_clear_color_t)gl_resolve(gl, "glClearColor");
    gl->glClear = (gl_clear_t)gl_resolve(gl, "glClear");
    if (!gl->glGenFramebuffers || !gl->glBindFramebuffer || !gl->glFramebufferTexture2D ||
        !gl->glGenTextures || !gl->glBindTexture || !gl->glTexImage2D || !gl->glTexParameteri ||
        !gl->glCheckFramebufferStatus || !gl->glReadPixels || !gl->glClear || !gl->glClearColor) {
        LOG("GL renderer unavailable: missing GL entry points, falling back to SW renderer");
        gl_destroy(gl);
        return false;
    }

    gl->glGenFramebuffers(1, &gl->fbo);
    gl->glBindFramebuffer(GL_FRAMEBUFFER, gl->fbo);
    gl->glGenTextures(1, &gl->texture);
    gl->glBindTexture(GL_TEXTURE_2D, gl->texture);
    gl->glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    gl->glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    gl->glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    gl->glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    gl->glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    gl->glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, gl->texture, 0);
    if (gl->glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        LOG("GL renderer unavailable: FBO incomplete, falling back to SW renderer");
        gl_destroy(gl);
        return false;
    }

    gl->ready = true;
    /* NOTE: keep the context current on this thread — mpv_render_context_create
     * (OPENGL) requires a current context, and the render thread keeps it
     * current for its whole lifetime (it is never switched between threads). */
    LOG("GL renderer initialized (EGL %d.%d, surfaceless)", major, minor);
    return true;
}

/* Resizes the FBO backing texture; call with the GL context current. */
static bool gl_ensure_size(GlRenderer *gl, int width, int height) {
    if (width == gl->width && height == gl->height) return true;
    gl->glBindTexture(GL_TEXTURE_2D, gl->texture);
    gl->glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    gl->width = width;
    gl->height = height;
    DBG("GL FBO resized to %dx%d", width, height);
    return true;
}

static void gl_destroy_textures(GlRenderer *gl) {
    if (gl->glDeleteFramebuffers && gl->fbo) {
        gl->glDeleteFramebuffers(1, &gl->fbo);
        gl->fbo = 0;
    }
    if (gl->glDeleteTextures && gl->texture) {
        gl->glDeleteTextures(1, &gl->texture);
        gl->texture = 0;
    }
}


/* Locates the user's mpv.conf (mpv's own search order) and loads it if it
 * exists. Returns true when a config was loaded. Everything in the config is
 * applied as-is; mpv silently ignores anything it cannot use with the render
 * API (e.g. vo/wid/gpu-context options). */
static bool loadUserConfig(mpv_handle *mpv) {
    if (!p_mpv_load_config_file) return false;

    std::vector<std::string> candidates;
    const char *mpvHome = getenv("MPV_HOME");
    if (mpvHome && mpvHome[0]) {
        candidates.push_back(std::string(mpvHome) + "/mpv.conf");
    }
    const char *xdgConfig = getenv("XDG_CONFIG_HOME");
    if (xdgConfig && xdgConfig[0]) {
        candidates.push_back(std::string(xdgConfig) + "/mpv/mpv.conf");
    } else {
        const char *home = getenv("HOME");
        if (home && home[0]) {
            candidates.push_back(std::string(home) + "/.config/mpv/mpv.conf");
        }
    }
    const char *home = getenv("HOME");
    if (home && home[0]) {
        candidates.push_back(std::string(home) + "/.mpv/mpv.conf");
    }

    for (const std::string &candidate : candidates) {
        FILE *f = fopen(candidate.c_str(), "r");
        if (f) {
            fclose(f);
            int ret = p_mpv_load_config_file(mpv, candidate.c_str());
            DBG("user config loaded: %s (ret=%d)", candidate.c_str(), ret);
            return ret >= 0;
        }
    }
    DBG("no user mpv.conf found, using built-in defaults");
    return false;
}

static std::string json_escape(const char *input) {
    if (!input) return "";
    std::string out;
    for (const char *c = input; *c; c++) {
        switch (*c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out += *c; break;
        }
    }
    return out;
}

static std::string buildTrackListJsonFromNode(const mpv_node *root, const char *trackType) {
    if (!root || root->format != MPV_FORMAT_NODE_ARRAY || !root->u.list) return "[]";

    std::string json = "[";
    int idx = 0;
    for (int i = 0; i < root->u.list->num; i++) {
        mpv_node *entry = &root->u.list->values[i];
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
        json += ",\"label\":\""; json += json_escape(label); json += "\"";
        json += ",\"language\":\""; json += json_escape(lang); json += "\"";
        json += selected ? ",\"selected\":true" : ",\"selected\":false";
        json += ",\"forced\":";
        json += forced ? "true" : "false";
        json += "}";

        idx++;
    }
    json += "]";
    return json;
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
    mpv_render_context *renderCtx;
    std::atomic<bool>  framePending;
    GlRenderer    gl;
    bool          useGl = false;

    /* Dedicated render thread: owns the GL context (never switched between
     * threads) and all mpv_render_* calls. Kotlin requests frames via the
     * renderFrame JNI, which enqueues a request and waits for the result. */
    std::thread   renderThread;
    std::mutex    renderMutex;
    std::condition_variable renderCv;
    bool          renderRequestPending = false;
    bool          renderDone = false;
    bool          renderResult = false;
    int           renderWidth = 0;
    int           renderHeight = 0;
    void         *renderBuffer = nullptr;
    std::mutex    renderInitMutex;
    std::condition_variable renderInitCv;
    bool          renderInitDone = false;
    bool          renderInitOk = false;

    /* All mpv API access (other than render) happens on the event thread.
     * Commands are queued here and drained by the event loop. */
    std::mutex    cmdMutex;
    std::vector<std::function<void()>> pendingCommands;

    /* Cache for snapshot polling */
    std::atomic<double>  cachedDuration;
    std::atomic<double>  cachedPosition;
    std::atomic<double>  cachedBufferedPosition;
    std::atomic<int>     cachedPaused;
    std::atomic<int>     cachedEnded;
    std::atomic<int>     cachedPausedForCache;
    std::atomic<double>  cachedSpeed;
    std::atomic<double>  cachedVolume;

    /* Track lists rebuilt by the event thread on track-list changes. */
    std::string cachedAudioTracksJson;
    std::string cachedSubtitleTracksJson;

    MpvPlayer() : mpv(nullptr), jvm(nullptr), eventSink(nullptr), running(false),
                  renderCtx(nullptr), framePending(false),
                  cachedDuration(0), cachedPosition(0), cachedBufferedPosition(0),
                  cachedPaused(1), cachedEnded(0), cachedPausedForCache(0),
                  cachedSpeed(1.0), cachedVolume(100.0) {}
    ~MpvPlayer() { destroy(); }

    void enqueueCommand(std::function<void()> command) {
        std::lock_guard<std::mutex> lock(cmdMutex);
        pendingCommands.push_back(std::move(command));
    }

    void drainCommands() {
        std::vector<std::function<void()>> commands;
        {
            std::lock_guard<std::mutex> lock(cmdMutex);
            commands.swap(pendingCommands);
        }
        for (auto &command : commands) {
            if (mpv) command();
        }
    }

    static void render_update_cb(void *ctx) {
        /* Called on an mpv internal thread; only signal, never call mpv here. */
        static_cast<MpvPlayer*>(ctx)->framePending.store(true);
    }

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
        /* Stop the render thread; it frees the render context and GL state
         * itself (the GL context is current on that thread only). */
        {
            std::lock_guard<std::mutex> lock(renderMutex);
            renderRequestPending = true;
            renderDone = false;
        }
        renderCv.notify_all();
        if (renderThread.joinable()) {
            renderThread.join();
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

    /* Runs on the dedicated render thread. Creates the render context (GL with
     * the EGL context current on this thread, else SW), then serves frame
     * requests until destroy() signals shutdown. */
    void renderLoop() {
        /* Try the OpenGL render API first (GPU conversion/scaling/subtitles);
         * fall back to the software renderer if GL is unusable. */
        bool ok = false;
        if (gl_init(&gl)) {
            mpv_opengl_init_params initParams;
            initParams.get_proc_address = gl_get_proc_address_cb;
            initParams.get_proc_address_ctx = &gl;
            mpv_render_param createParams[] = {
                { MPV_RENDER_PARAM_API_TYPE, (void*)MPV_RENDER_API_TYPE_OPENGL },
                { MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &initParams },
                { MPV_RENDER_PARAM_INVALID, nullptr }
            };
            int ret = p_mpv_render_context_create(&renderCtx, mpv, createParams);
            DBG("mpv_render_context_create(OPENGL) returned: %d", ret);
            if (ret < 0 || !renderCtx) {
                LOG("OpenGL render context failed (%d), falling back to SW renderer", ret);
                gl_destroy_textures(&gl);
                gl_destroy(&gl);
                renderCtx = nullptr;
            } else {
                useGl = true;
            }
        }
        if (!useGl) {
            mpv_render_param createParams[] = {
                { MPV_RENDER_PARAM_API_TYPE, (void*)MPV_RENDER_API_TYPE_SW },
                { MPV_RENDER_PARAM_INVALID, nullptr }
            };
            int ret = p_mpv_render_context_create(&renderCtx, mpv, createParams);
            DBG("mpv_render_context_create(SW) returned: %d", ret);
            ok = ret >= 0 && renderCtx;
        } else {
            ok = true;
        }
        if (ok) {
            p_mpv_render_context_set_update_callback(renderCtx, MpvPlayer::render_update_cb, this);
            DBG("render context created (mode=%s)", useGl ? "opengl" : "sw");
        } else {
            LOG("mpv_render_context_create failed");
        }
        {
            std::lock_guard<std::mutex> lock(renderInitMutex);
            renderInitOk = ok;
            renderInitDone = true;
        }
        renderInitCv.notify_all();
        if (!ok) return;

        while (true) {
            std::unique_lock<std::mutex> lock(renderMutex);
            renderCv.wait(lock, [&] { return renderRequestPending || !running; });
            if (!running) break;
            renderRequestPending = false;
            int w = renderWidth;
            int h = renderHeight;
            void *pixels = renderBuffer;
            lock.unlock();



            bool result = false;
            if (useGl) {
                /* Context is already current on this thread (bound in gl_init
                 * and never released or switched). */
                if (gl_ensure_size(&gl, w, h)) {
                    gl.glBindFramebuffer(GL_FRAMEBUFFER, gl.fbo);
                    gl.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                    gl.glClear(GL_COLOR_BUFFER_BIT);
                    mpv_opengl_fbo fbo = { (int)gl.fbo, w, h, 0 };
                    /* flip_y=0: with an FBO (not the default framebuffer), the
                     * readback via glReadPixels is top-row-first, matching the
                     * Kotlin/Skia buffer convention. (Empirically verified:
                     * flip_y=1 yields a vertically flipped image.) */
                    int flipY = 0;
                    mpv_render_param renderParams[] = {
                        { MPV_RENDER_PARAM_OPENGL_FBO, &fbo },
                        { MPV_RENDER_PARAM_FLIP_Y, &flipY },
                        { MPV_RENDER_PARAM_INVALID, nullptr }
                    };
                    int ret = p_mpv_render_context_render(renderCtx, renderParams);
                    if (ret >= 0) {
                        /* mpv restores the framebuffer binding to 0 after
                         * rendering (its GL state-restore contract), so re-bind
                         * our FBO before reading the pixels back. */
                        gl.glBindFramebuffer(GL_FRAMEBUFFER, gl.fbo);
                        gl.glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                        result = true;
                    } else {
                        DBG("mpv_render_context_render(GL) failed: %d", ret);
                    }
                }
            } else {
                int size[2] = { w, h };
                size_t stride = (size_t)w * 4;
                mpv_render_param renderParams[] = {
                    { MPV_RENDER_PARAM_SW_SIZE,    &size },
                    { MPV_RENDER_PARAM_SW_FORMAT,  (void*)"rgb0" },
                    { MPV_RENDER_PARAM_SW_STRIDE,  &stride },
                    { MPV_RENDER_PARAM_SW_POINTER, pixels },
                    { MPV_RENDER_PARAM_INVALID, nullptr }
                };
                int ret = p_mpv_render_context_render(renderCtx, renderParams);
                if (ret >= 0) {
                    result = true;
                } else {
                    DBG("mpv_render_context_render failed: %d", ret);
                }
            }

            lock.lock();
            renderResult = result;
            renderDone = true;
            renderCv.notify_all();
        }

        /* Teardown on this thread: render context first, then GL state. */
        if (renderCtx) {
            p_mpv_render_context_free(renderCtx);
            renderCtx = nullptr;
        }
        if (gl.ready) {
            gl_destroy_textures(&gl);
            gl_destroy(&gl);
        }
    }

    /* Spawns the render thread and blocks until its render context is ready. */
    bool startRenderThread() {
        renderThread = std::thread(&MpvPlayer::renderLoop, this);
        std::unique_lock<std::mutex> lock(renderInitMutex);
        renderInitCv.wait(lock, [&] { return renderInitDone; });
        return renderInitOk;
    }

    int initialize(JNIEnv *env, int64_t windowId, const char *sourceUrl,
                   const char * const *headers, int numHeaders,
                   int playWhenReady, int64_t initialPositionMs,
                   int decoderPriority, jobject sink)
    {
        LOG("initialize: url=%s headers=%d playWhenReady=%d initialPos=%lld decoderPrio=%d",
            sourceUrl, numHeaders, playWhenReady,
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

        /* Store JVM and event sink */
        env->GetJavaVM(&jvm);
        eventSink = env->NewGlobalRef(sink);
        DBG("JVM/eventSink stored");

        /* Configure mpv. If the user has an mpv.conf, load it wholesale —
         * mpv itself ignores whatever it cannot use with the render API
         * (window options are VO-level, scripts/input.conf never load via
         * libmpv). Without a config, apply our built-in defaults. */
        if (!loadUserConfig(mpv)) {
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
            p_mpv_set_option_string(mpv, "msg-level", "all=error:vd=info");
            p_mpv_set_option_string(mpv, "video-sync", "display-resample");
            p_mpv_set_option_string(mpv, "video-sync-max-video-change", "5");
        }

        /* Embedding-critical options: applied AFTER any user config so they
         * always win. A user vo=gpu-next would otherwise make mpv open its
         * own window and render there instead of into our FBO. hwdec is app-
         * controlled too: direct interop (e.g. vaapi) only works with a
         * windowed gpu VO, and silently falls back to software decode under
         * vo=libmpv — auto-copy keeps hardware decode (vulkan-copy on AMD). */
        p_mpv_set_option_string(mpv, "vo", "libmpv");
        p_mpv_set_option_string(mpv, "force-window", "no");
        const char *hwdecOpt = decoderPriority >= 2 ? "no" : "auto-copy";
        p_mpv_set_option_string(mpv, "hwdec", hwdecOpt);
        DBG("hwdec option = %s", hwdecOpt);

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

        /* Create the render context before any playback starts. The render
         * thread creates it (GL with a dedicated offscreen EGL context, else
         * the SW renderer) and owns all mpv_render_* calls from then on.
         * running must be set first or the render loop exits immediately. */
        running = true;
        if (!startRenderThread()) {
            LOG("render thread init failed");
            p_mpv_terminate_destroy(mpv);
            mpv = nullptr;
            return -1;
        }
        /* Request events */
        p_mpv_request_event(mpv, MPV_EVENT_PROPERTY_CHANGE, 1);
        p_mpv_request_event(mpv, MPV_EVENT_LOG_MESSAGE, 1);

        /* Observe properties */
        p_mpv_observe_property(mpv, 0, "time-pos", MPV_FORMAT_DOUBLE);
        p_mpv_observe_property(mpv, 0, "duration", MPV_FORMAT_DOUBLE);
        p_mpv_observe_property(mpv, 0, "pause", MPV_FORMAT_FLAG);
        p_mpv_observe_property(mpv, 0, "eof-reached", MPV_FORMAT_FLAG);
        p_mpv_observe_property(mpv, 0, "demuxer-cache-time", MPV_FORMAT_DOUBLE);
        p_mpv_observe_property(mpv, 0, "paused-for-cache", MPV_FORMAT_FLAG);
        p_mpv_observe_property(mpv, 0, "speed", MPV_FORMAT_DOUBLE);
        p_mpv_observe_property(mpv, 0, "volume", MPV_FORMAT_DOUBLE);
        p_mpv_observe_property(mpv, 0, "track-list", MPV_FORMAT_NODE);
        p_mpv_observe_property(mpv, 0, "hwdec-current", MPV_FORMAT_STRING);
        p_mpv_observe_property(mpv, 0, "hwdec-active", MPV_FORMAT_FLAG);

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
            drainCommands();

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

            if (evId == MPV_EVENT_LOG_MESSAGE && evData) {
                /* Surface mpv's own diagnostics (e.g. "Using hardware decoding
                 * (vaapi-copy)" / "Falling back to software decoding"). */
                mpv_event_log_message *msg = (mpv_event_log_message*)evData;
                if (msg->text) {
                    DBG("[mpv/%s] %s", msg->prefix ? msg->prefix : "?", msg->text);
                }
            }

            if (evId == MPV_EVENT_PROPERTY_CHANGE && evData) {
                mpv_event_property *prop = (mpv_event_property*)evData;
                if (!prop->name || !prop->data) continue;
                const char *pname = prop->name;
                void *pdata = prop->data;
                static int propLogCount = 0;
                if (propLogCount < 40) {
                    DBG("property change: name=%s format=%d", pname, (int)prop->format);
                    propLogCount++;
                }
                if (strcmp(pname, "time-pos") == 0 && prop->format == MPV_FORMAT_DOUBLE)
                    cachedPosition = *(double*)pdata;
                else if (strcmp(pname, "duration") == 0 && prop->format == MPV_FORMAT_DOUBLE)
                    cachedDuration = *(double*)pdata;
                else if (strcmp(pname, "pause") == 0 && prop->format == MPV_FORMAT_FLAG)
                    cachedPaused = *(int*)pdata;
                else if (strcmp(pname, "eof-reached") == 0 && prop->format == MPV_FORMAT_FLAG)
                    cachedEnded = *(int*)pdata;
                else if (strcmp(pname, "demuxer-cache-time") == 0 && prop->format == MPV_FORMAT_DOUBLE)
                    cachedBufferedPosition = cachedPosition.load() + *(double*)pdata;
                else if (strcmp(pname, "paused-for-cache") == 0 && prop->format == MPV_FORMAT_FLAG)
                    cachedPausedForCache = *(int*)pdata;
                else if (strcmp(pname, "speed") == 0 && prop->format == MPV_FORMAT_DOUBLE)
                    cachedSpeed = *(double*)pdata;
                else if (strcmp(pname, "volume") == 0 && prop->format == MPV_FORMAT_DOUBLE)
                    cachedVolume = *(double*)pdata;
                else if (strcmp(pname, "track-list") == 0 && prop->format == MPV_FORMAT_NODE) {
                    mpv_node *node = (mpv_node*)pdata;
                    cachedAudioTracksJson = buildTrackListJsonFromNode(node, "audio");
                    cachedSubtitleTracksJson = buildTrackListJsonFromNode(node, "sub");
                }
                else if (strcmp(pname, "hwdec-current") == 0 && prop->format == MPV_FORMAT_STRING) {
                    const char *hwdec = pdata ? *(const char**)pdata : nullptr;
                    DBG("hwdec-current = %s", hwdec ? hwdec : "(null)");
                }
                else if (strcmp(pname, "hwdec-active") == 0 && prop->format == MPV_FORMAT_FLAG) {
                    DBG("hwdec-active = %d", *(int*)pdata);
                }
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

/*
 * Requests the latest video frame into the caller-provided direct ByteBuffer.
 * The render thread renders it (OpenGL FBO + glReadPixels, or SW into the
 * buffer) and this call blocks until the frame is ready. Returns true when a
 * new frame was rendered; false when no new frame was available (caller keeps
 * the last one).
 */
JNIEXPORT jboolean JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_renderFrame(
    JNIEnv *env, jclass clazz, jlong handle, jint width, jint height, jobject buffer)
{
    MpvPlayer *player = get_player(handle);
    if (!player || !player->renderCtx) return JNI_FALSE;
    if (width <= 0 || height <= 0) return JNI_FALSE;



    void *pixels = env->GetDirectBufferAddress(buffer);
    if (!pixels) {
        DBG("renderFrame: buffer is not a direct ByteBuffer");
        return JNI_FALSE;
    }

    if (!player->framePending.exchange(false)) return JNI_FALSE;



    {
        std::unique_lock<std::mutex> lock(player->renderMutex);
        player->renderWidth = width;
        player->renderHeight = height;
        player->renderBuffer = pixels;
        player->renderRequestPending = true;
        player->renderDone = false;
    }
    player->renderCv.notify_all();

    std::unique_lock<std::mutex> lock(player->renderMutex);
    player->renderCv.wait_for(lock, std::chrono::seconds(2), [&] { return player->renderDone; });
    if (!player->renderDone) {
        DBG("renderFrame: timed out waiting for render thread");
        return JNI_FALSE;
    }
    return player->renderResult ? JNI_TRUE : JNI_FALSE;
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
    if (!player) return;
    player->enqueueCommand([player, paused]() {
        if (player->mpv) {
            p_mpv_set_property_string(player->mpv, "pause", paused ? "yes" : "no");
        }
    });
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_seekTo(
    JNIEnv *env, jclass clazz, jlong handle, jlong positionMs)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return;
    player->enqueueCommand([player, positionMs]() {
        if (!player->mpv) return;
        char seekStr[32];
        snprintf(seekStr, sizeof(seekStr), "%" PRId64, positionMs / 1000);
        const char *cmd[] = {"seek", seekStr, "absolute", nullptr};
        p_mpv_command(player->mpv, cmd);
    });
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_seekBy(
    JNIEnv *env, jclass clazz, jlong handle, jlong offsetMs)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return;
    player->enqueueCommand([player, offsetMs]() {
        if (!player->mpv) return;
        char seekStr[32];
        snprintf(seekStr, sizeof(seekStr), "%" PRId64, offsetMs / 1000);
        const char *cmd[] = {"seek", seekStr, "relative", nullptr};
        p_mpv_command(player->mpv, cmd);
    });
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setSpeed(
    JNIEnv *env, jclass clazz, jlong handle, jfloat speed)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return;
    player->cachedSpeed = speed;
    player->enqueueCommand([player, speed]() {
        if (!player->mpv) return;
        char speedStr[16];
        snprintf(speedStr, sizeof(speedStr), "%.2f", (double)speed);
        p_mpv_set_property_string(player->mpv, "speed", speedStr);
    });
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_adjustVolume(
    JNIEnv *env, jclass clazz, jlong handle, jfloat delta)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return;
    player->enqueueCommand([player, delta]() {
        if (!player->mpv) return;
        double vol = player->cachedVolume.load();
        vol += delta;
        if (vol < 0) vol = 0;
        if (vol > 200) vol = 200;
        char volStr[16];
        snprintf(volStr, sizeof(volStr), "%.0f", vol);
        p_mpv_set_property_string(player->mpv, "volume", volStr);
    });
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setVolume(
    JNIEnv *env, jclass clazz, jlong handle, jfloat level)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return;
    player->cachedVolume = level * 100.0f;
    player->enqueueCommand([player, level]() {
        if (!player->mpv) return;
        char volStr[16];
        snprintf(volStr, sizeof(volStr), "%.0f", (double)(level * 100.0f));
        p_mpv_set_property_string(player->mpv, "volume", volStr);
    });
}

JNIEXPORT jfloat JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_volume(
    JNIEnv *env, jclass clazz, jlong handle)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return 0.0f;
    return (jfloat)(player->cachedVolume.load() / 100.0);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setResizeMode(
    JNIEnv *env, jclass clazz, jlong handle, jint mode)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return;
    player->enqueueCommand([player, mode]() {
        if (!player->mpv) return;
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
    });
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
    return player->cachedPausedForCache ? JNI_TRUE : JNI_FALSE;
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
    return (jfloat)player->cachedSpeed.load();
}

JNIEXPORT jstring JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_audioTracksJson(
    JNIEnv *env, jclass clazz, jlong handle)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return env->NewStringUTF("[]");
    std::lock_guard<std::mutex> _l(player->mutex);
    return env->NewStringUTF(player->cachedAudioTracksJson.c_str());
}

JNIEXPORT jstring JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_subtitleTracksJson(
    JNIEnv *env, jclass clazz, jlong handle)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return env->NewStringUTF("[]");
    std::lock_guard<std::mutex> _l(player->mutex);
    return env->NewStringUTF(player->cachedSubtitleTracksJson.c_str());
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_selectAudioTrack(
    JNIEnv *env, jclass clazz, jlong handle, jint trackId)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return;
    player->enqueueCommand([player, trackId]() {
        if (!player->mpv) return;
        char idStr[16];
        snprintf(idStr, sizeof(idStr), "%d", trackId);
        p_mpv_set_property_string(player->mpv, "aid", idStr);
    });
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_selectSubtitleTrack(
    JNIEnv *env, jclass clazz, jlong handle, jint trackId)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return;
    player->enqueueCommand([player, trackId]() {
        if (!player->mpv) return;
        char idStr[16];
        snprintf(idStr, sizeof(idStr), "%d", trackId);
        if (trackId < 0) {
            p_mpv_set_property_string(player->mpv, "sub-visibility", "no");
        } else {
            p_mpv_set_property_string(player->mpv, "sid", idStr);
            p_mpv_set_property_string(player->mpv, "sub-visibility", "yes");
        }
    });
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_addSubtitleUrl(
    JNIEnv *env, jclass clazz, jlong handle, jstring url)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return;
    const char *urlChars = env->GetStringUTFChars(url, nullptr);
    if (!urlChars) return;
    std::string urlCopy(urlChars);
    env->ReleaseStringUTFChars(url, urlChars);
    player->enqueueCommand([player, urlCopy]() {
        if (!player->mpv) return;
        const char *cmd[] = {"sub-add", urlCopy.c_str(), "auto", nullptr};
        p_mpv_command(player->mpv, cmd);
    });
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_clearExternalSubtitles(
    JNIEnv *env, jclass clazz, jlong handle)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return;
    player->enqueueCommand([player]() {
        if (!player->mpv) return;
        const char *cmd[] = {"sub-remove", nullptr};
        p_mpv_command(player->mpv, cmd);
    });
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_clearExternalSubtitlesAndSelect(
    JNIEnv *env, jclass clazz, jlong handle, jint trackId)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return;
    player->enqueueCommand([player, trackId]() {
        if (!player->mpv) return;
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
    });
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setSubtitleDelayMs(
    JNIEnv *env, jclass clazz, jlong handle, jint delayMs)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return;
    player->enqueueCommand([player, delayMs]() {
        if (!player->mpv) return;
        char delayStr[16];
        snprintf(delayStr, sizeof(delayStr), "%.3f", (double)delayMs / 1000.0);
        p_mpv_set_property_string(player->mpv, "sub-delay", delayStr);
    });
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_applySubtitleStyle(
    JNIEnv *env, jclass clazz, jlong handle,
    jstring textColor, jstring backgroundColor, jstring outlineColor,
    jfloat outlineSize, jboolean bold, jfloat fontSize, jint subPos)
{
    MpvPlayer *player = get_player(handle);
    if (!player) return;
    const char *c_textColor = env->GetStringUTFChars(textColor, nullptr);
    const char *c_bgColor = env->GetStringUTFChars(backgroundColor, nullptr);
    const char *c_outlineColor = env->GetStringUTFChars(outlineColor, nullptr);
    std::string textColorCopy = c_textColor ? c_textColor : "";
    std::string bgColorCopy = c_bgColor ? c_bgColor : "";
    std::string outlineColorCopy = c_outlineColor ? c_outlineColor : "";
    if (c_textColor) env->ReleaseStringUTFChars(textColor, c_textColor);
    if (c_bgColor) env->ReleaseStringUTFChars(backgroundColor, c_bgColor);
    if (c_outlineColor) env->ReleaseStringUTFChars(outlineColor, c_outlineColor);

    player->enqueueCommand([player, textColorCopy, bgColorCopy, outlineColorCopy,
                            outlineSize, bold, fontSize, subPos]() {
        if (!player->mpv) return;
        if (!textColorCopy.empty()) p_mpv_set_property_string(player->mpv, "sub-color", textColorCopy.c_str());
        if (!bgColorCopy.empty()) p_mpv_set_property_string(player->mpv, "sub-back-color", bgColorCopy.c_str());
        if (!outlineColorCopy.empty()) p_mpv_set_property_string(player->mpv, "sub-border-color", outlineColorCopy.c_str());
        char floatStr[16];
        snprintf(floatStr, sizeof(floatStr), "%.1f", (double)outlineSize);
        p_mpv_set_property_string(player->mpv, "sub-border-size", floatStr);
        p_mpv_set_property_string(player->mpv, "sub-bold", bold ? "yes" : "no");
        snprintf(floatStr, sizeof(floatStr), "%.0f", (double)fontSize);
        p_mpv_set_property_string(player->mpv, "sub-font-size", floatStr);
        char posStr[16];
        snprintf(posStr, sizeof(posStr), "%d", subPos);
        p_mpv_set_property_string(player->mpv, "sub-pos", posStr);
    });
}

} /* extern "C" */
