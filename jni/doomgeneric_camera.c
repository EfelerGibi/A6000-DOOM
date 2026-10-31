// doomgeneric platform layer for Sony PMCA cameras (Android 2.3.7 / ARMv7):
// the JNI half. Frame handling, keys and timing live in dc_core.c.
//
// Threads (all created in Java):
//   doom     nativeCreate() once, then nativeTick() in a loop. Each finished
//            frame is published from DG_DrawFrame() without waiting for the
//            display.
//   present  nativePresentWindow() or nativePresentBitmap() in a loop: wait
//            for a free surface buffer, take the newest frame, expand it 2x
//            into the buffer, post.
// Overlapping the two is what takes the frame time from tick + present to
// max(tick, present).

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <android/native_window.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "doomgeneric/doomgeneric.h"
#include "dc_core.h"

#define TAG "doomcam"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define JNIFN(name) Java_com_doomcam_DoomActivity_##name

// ---------------------------------------------------------------- DG_* hooks

void DG_Init(void) { LOGI("DG_Init %dx%d (8-bit, CMAP256)", DOOMGENERIC_RESX, DOOMGENERIC_RESY); }

/* Doom's I_Error path ends in exit(-1); make that visible in logcat too. */
static void on_doom_exit(void)
{
    LOGE("doom called exit() -- see DOOMOUT.TXT on the card for I_Error text");
    fflush(NULL);
}

// ---------------------------------------------------------------------- JNI

JNIEXPORT void JNICALL JNIFN(nativeCreate)(JNIEnv *env, jobject thiz,
                                           jstring jIwad, jstring jWork)
{
    const char *iwad = (*env)->GetStringUTFChars(env, jIwad, NULL);
    const char *work = (*env)->GetStringUTFChars(env, jWork, NULL);
    char path[512];

    dc_init();

    // Doom writes default.cfg and savegames relative to $HOME / cwd.
    setenv("HOME", work, 1);
    if (chdir(work) != 0) LOGE("chdir(%s) failed", work);

    // Doom talks to stdout/stderr, which Android discards -- including the
    // I_Error message that precedes exit(-1). Point both at a file on the card
    // so failures leave a readable trace. Unbuffered, so nothing is lost when
    // Doom calls exit() from inside I_Error.
    snprintf(path, sizeof(path), "%s/DOOMOUT.TXT", work);
    if (freopen(path, "w", stdout) != NULL) {
        setvbuf(stdout, NULL, _IONBF, 0);
        dup2(fileno(stdout), 2);          /* stderr -> same file */
        LOGI("stdout/stderr -> %s", path);
    } else {
        LOGE("could not open %s", path);
    }
    atexit(on_doom_exit);

    // argv must outlive this call: doomgeneric keeps the pointers in myargv.
    static char *argv[5];
    argv[0] = strdup("doom");
    argv[1] = strdup("-iwad");
    argv[2] = strdup(iwad);
    argv[3] = strdup("-nomusic");   // harmless; there is no music backend anyway
    argv[4] = NULL;

    LOGI("doomgeneric_Create iwad=%s cwd=%s", iwad, work);
    doomgeneric_Create(4, argv);
    LOGI("doomgeneric_Create returned");

    (*env)->ReleaseStringUTFChars(env, jIwad, iwad);
    (*env)->ReleaseStringUTFChars(env, jWork, work);
}

JNIEXPORT void JNICALL JNIFN(nativeTick)(JNIEnv *env, jobject thiz)
{
    dc_tick();
}

JNIEXPORT void JNICALL JNIFN(nativeKey)(JNIEnv *env, jobject thiz,
                                        jint pressed, jint key)
{
    dc_push_key(pressed, key);
}

JNIEXPORT void JNICALL JNIFN(nativeSetMailbox)(JNIEnv *env, jobject thiz, jint on)
{
    dc_set_mailbox(on);
}

JNIEXPORT void JNICALL JNIFN(nativeStop)(JNIEnv *env, jobject thiz)
{
    dc_stop();
}

// out[] = ticks, produced, dropped, presented, repeats, us_tick, us_tick_cpu,
//         us_convert, us_handoff, us_lock, us_wait, us_copy, us_post
JNIEXPORT void JNICALL JNIFN(nativeGetStats)(JNIEnv *env, jobject thiz, jlongArray jout)
{
    dc_stats_t s;
    jlong v[13];
    dc_get_stats(&s, 1);
    v[0] = s.ticks;      v[1] = s.produced;    v[2] = s.dropped;
    v[3] = s.presented;  v[4] = s.repeats;     v[5] = (jlong)s.us_tick;
    v[6] = (jlong)s.us_tick_cpu;  v[7] = (jlong)s.us_convert;
    v[8] = (jlong)s.us_handoff;   v[9] = (jlong)s.us_lock;
    v[10] = (jlong)s.us_wait;     v[11] = (jlong)s.us_copy;
    v[12] = (jlong)s.us_post;
    (*env)->SetLongArrayRegion(env, jout, 0, 13, v);
}

// The Canvas path times lockCanvas / drawBitmap / unlockCanvasAndPost in
// Java and reports them here so both paths log the same way.
JNIEXPORT void JNICALL JNIFN(nativeAddPresentStats)(JNIEnv *env, jobject thiz,
                                                    jlong lockUs, jlong drawUs, jlong postUs)
{
    // Not a new iteration: nativePresentBitmap() already counted it.
    dc_stats_present((uint64_t)lockUs, 0, (uint64_t)drawUs, (uint64_t)postUs, -1);
}

// ------------------------------------------------- Canvas / Bitmap present

// Wait up to timeoutMs for a new frame and expand it into the Bitmap.
// Returns 1 = new frame, 0 = nothing new (Bitmap untouched), -1 = error.
JNIEXPORT jint JNICALL JNIFN(nativePresentBitmap)(JNIEnv *env, jobject thiz,
                                                  jobject bmp, jint timeoutMs)
{
    AndroidBitmapInfo info;
    void *pixels = NULL;
    uint64_t t0, t1, t2;
    int got, fmt, bpp;

    if (AndroidBitmap_getInfo(env, bmp, &info) < 0) return -1;
    if (info.format == ANDROID_BITMAP_FORMAT_RGB_565)        { fmt = DC_FMT_565;  bpp = 2; }
    else if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) { fmt = DC_FMT_8888; bpp = 4; }
    else return -1;

    t0 = dc_now_us();
    got = dc_take_frame(timeoutMs);
    t1 = dc_now_us();
    if (got) {
        if (AndroidBitmap_lockPixels(env, bmp, &pixels) < 0) return -1;
        dc_render_front(pixels, fmt, info.width, info.height, info.stride / bpp, 1);
        AndroidBitmap_unlockPixels(env, bmp);
    }
    t2 = dc_now_us();
    dc_stats_present(0, t1 - t0, t2 - t1, 0, !got);
    return got;
}

// ------------------------------------------------- ANativeWindow present
// Writes straight into the SurfaceView's buffer: no intermediate Bitmap,
// no Canvas. libandroid.so is opened at run time rather than linked, so a
// firmware without it still loads this library and falls back to Canvas.

typedef ANativeWindow *(*fromSurface_t)(JNIEnv *, jobject);
typedef void           (*release_t)(ANativeWindow *);
typedef int32_t        (*lock_t)(ANativeWindow *, ANativeWindow_Buffer *, ARect *);
typedef int32_t        (*unlockAndPost_t)(ANativeWindow *);

static void           *s_libandroid;
static fromSurface_t   p_fromSurface;
static release_t       p_release;
static lock_t          p_lock;
static unlockAndPost_t p_unlockAndPost;

static pthread_mutex_t s_win_mx = PTHREAD_MUTEX_INITIALIZER;
static ANativeWindow  *s_win;
static int             s_win_geom[4];   // width, height, stride, format at last lock

static int load_libandroid(void)
{
    if (p_fromSurface && p_release && p_lock && p_unlockAndPost) return 1;
    if (!s_libandroid) s_libandroid = dlopen("libandroid.so", RTLD_NOW);
    if (!s_libandroid) { LOGE("dlopen libandroid.so: %s", dlerror()); return 0; }
    p_fromSurface   = (fromSurface_t)  dlsym(s_libandroid, "ANativeWindow_fromSurface");
    p_release       = (release_t)      dlsym(s_libandroid, "ANativeWindow_release");
    p_lock          = (lock_t)         dlsym(s_libandroid, "ANativeWindow_lock");
    p_unlockAndPost = (unlockAndPost_t)dlsym(s_libandroid, "ANativeWindow_unlockAndPost");
    if (!(p_fromSurface && p_release && p_lock && p_unlockAndPost)) {
        LOGE("libandroid.so lacks ANativeWindow_* symbols");
        return 0;
    }
    return 1;
}

static void detach_locked(void)
{
    if (s_win) { p_release(s_win); s_win = NULL; }
}

// Returns 1 if the native path is ready for this Surface, 0 to use Canvas.
JNIEXPORT jint JNICALL JNIFN(nativeAttachSurface)(JNIEnv *env, jobject thiz, jobject surface)
{
    int ok = 0;
    pthread_mutex_lock(&s_win_mx);
    detach_locked();
    if (surface && load_libandroid()) {
        s_win = p_fromSurface(env, surface);
        ok = s_win != NULL;
        if (!ok) LOGE("ANativeWindow_fromSurface returned NULL");
    }
    pthread_mutex_unlock(&s_win_mx);
    return ok;
}

JNIEXPORT void JNICALL JNIFN(nativeDetachSurface)(JNIEnv *env, jobject thiz)
{
    pthread_mutex_lock(&s_win_mx);
    detach_locked();
    pthread_mutex_unlock(&s_win_mx);
}

// One present: lock a buffer (blocks until SurfaceFlinger frees one), take
// the newest frame -- waiting up to timeoutMs if Doom hasn't produced one
// yet -- expand it into the buffer and post. Locking *before* taking the
// frame means the frame shown is the freshest one at the moment a buffer
// becomes available.
// Returns 1 = new frame shown, 0 = previous frame re-shown, -1 = error.
JNIEXPORT jint JNICALL JNIFN(nativePresentWindow)(JNIEnv *env, jobject thiz, jint timeoutMs)
{
    ANativeWindow_Buffer b;
    uint64_t t0, t1, t2, t3, t4;
    int got, fmt, rc = -1;

    pthread_mutex_lock(&s_win_mx);
    if (!s_win) goto out;

    t0 = dc_now_us();
    if (p_lock(s_win, &b, NULL) != 0) goto out;
    t1 = dc_now_us();

    if (b.format == WINDOW_FORMAT_RGB_565) fmt = DC_FMT_565;
    else if (b.format == WINDOW_FORMAT_RGBA_8888 || b.format == WINDOW_FORMAT_RGBX_8888) fmt = DC_FMT_8888;
    else fmt = 0;

    s_win_geom[0] = b.width;  s_win_geom[1] = b.height;
    s_win_geom[2] = b.stride; s_win_geom[3] = b.format;

    got = dc_take_frame(timeoutMs);
    t2 = dc_now_us();
    if (fmt) {
        if (dc_have_front())
            dc_render_front(b.bits, fmt, b.width, b.height, b.stride, 1);
        else
            dc_clear(b.bits, fmt, b.width, b.height, b.stride);
    }
    t3 = dc_now_us();
    p_unlockAndPost(s_win);
    t4 = dc_now_us();

    dc_stats_present(t1 - t0, t2 - t1, t3 - t2, t4 - t3, !got);
    rc = fmt ? got : -1;
out:
    pthread_mutex_unlock(&s_win_mx);
    return rc;
}

// Buffer geometry seen by the last lock, for DOOMLOG.TXT.
JNIEXPORT jstring JNICALL JNIFN(nativeWindowInfo)(JNIEnv *env, jobject thiz)
{
    char tmp[96];
    pthread_mutex_lock(&s_win_mx);
    if (s_win_geom[0] > 0)
        snprintf(tmp, sizeof(tmp), "%dx%d stride=%d format=%d (4=RGB565)",
                 s_win_geom[0], s_win_geom[1], s_win_geom[2], s_win_geom[3]);
    else
        snprintf(tmp, sizeof(tmp), "not locked yet");
    pthread_mutex_unlock(&s_win_mx);
    return (*env)->NewStringUTF(env, tmp);
}
