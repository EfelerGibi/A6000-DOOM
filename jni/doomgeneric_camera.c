// doomgeneric platform layer for Sony PMCA cameras (Android 2.3.7 / ARMv7).
//
// Design: the Java render thread drives everything.
//   nativeCreate()  -> doomgeneric_Create()  (D_DoomMain, returns after init)
//   nativeTick()    -> doomgeneric_Tick()    (exactly one frame)
//   nativeBlit(bmp) -> copy DG_ScreenBuffer into an ARGB_8888 Bitmap
//   nativeKey()     -> push into the key queue DG_GetKey() drains
// Nothing here touches camera APIs; it is a plain Android NDK library.

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <time.h>
#include <unistd.h>

#include <fcntl.h>

#include "doomgeneric/doomgeneric.h"

#define TAG "doomcam"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define KEYQ 64
static unsigned short s_q[KEYQ];
static void on_doom_exit(void);
static volatile unsigned int s_qr = 0, s_qw = 0;

// ---------------------------------------------------------------- DG_* hooks

void DG_Init(void) { LOGI("DG_Init %dx%d", DOOMGENERIC_RESX, DOOMGENERIC_RESY); }

/* Doom's I_Error path ends in exit(-1); make that visible in logcat too. */
static void on_doom_exit(void)
{
    LOGE("doom called exit() -- see DOOMOUT.TXT on the card for I_Error text");
    fflush(NULL);
}

// No-op: Java pulls the frame after each tick via nativeBlit().
void DG_DrawFrame(void) { }

void DG_SetWindowTitle(const char *title) { (void)title; }

void DG_SleepMs(uint32_t ms) { usleep(ms * 1000); }

uint32_t DG_GetTicksMs(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)(ts.tv_sec * 1000u + ts.tv_nsec / 1000000u);
}

int DG_GetKey(int *pressed, unsigned char *key)
{
    if (s_qr == s_qw) return 0;
    unsigned short d = s_q[s_qr % KEYQ];
    s_qr++;
    *pressed = (d >> 8) & 1;
    *key     = (unsigned char)(d & 0xff);
    return 1;
}

// ---------------------------------------------------------------------- JNI

#define JNIFN(name) Java_com_doomcam_DoomActivity_##name

JNIEXPORT void JNICALL JNIFN(nativeCreate)(JNIEnv *env, jobject thiz,
                                           jstring jIwad, jstring jWork)
{
    const char *iwad = (*env)->GetStringUTFChars(env, jIwad, NULL);
    const char *work = (*env)->GetStringUTFChars(env, jWork, NULL);
    char path[512];

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
    doomgeneric_Tick();
}

JNIEXPORT void JNICALL JNIFN(nativeKey)(JNIEnv *env, jobject thiz,
                                        jint pressed, jint key)
{
    s_q[s_qw % KEYQ] = (unsigned short)(((pressed & 1) << 8) | (key & 0xff));
    s_qw++;
}

// Convert Doom's 0xAARRGGBB buffer into the Bitmap, expanding 2x on the way.
// Doing the scale here is nearly free; letting Canvas do it costs ~99 ms/frame
// because it converts and dithers into the surface's RGB_565 format.
// Supports a 565 destination (fast path, matches the camera's surface) and an
// 8888 destination (fallback; note Android's 8888 is byte order R,G,B,A, so
// R and B swap relative to Doom's word layout, and alpha must be forced).
JNIEXPORT void JNICALL JNIFN(nativeBlit)(JNIEnv *env, jobject thiz, jobject bmp)
{
    AndroidBitmapInfo info;
    void *pixels = NULL;
    const uint32_t *src;
    int x, y;

    if (AndroidBitmap_getInfo(env, bmp, &info) < 0) return;
    if (AndroidBitmap_lockPixels(env, bmp, &pixels) < 0) return;

    src = (const uint32_t *)DG_ScreenBuffer;
    if (src == NULL) { AndroidBitmap_unlockPixels(env, bmp); return; }

    if (info.format == ANDROID_BITMAP_FORMAT_RGB_565) {
        for (y = 0; y < DOOMGENERIC_RESY; y++) {
            const uint32_t *sp = src + y * DOOMGENERIC_RESX;
            uint16_t *r0 = (uint16_t *)((char *)pixels + (size_t)(2 * y)     * info.stride);
            uint16_t *r1 = (uint16_t *)((char *)pixels + (size_t)(2 * y + 1) * info.stride);
            for (x = 0; x < DOOMGENERIC_RESX; x++) {
                uint32_t p = sp[x];
                uint16_t c = (uint16_t)(((p >> 8) & 0xf800)    /* R 5 */
                                      | ((p >> 5) & 0x07e0)    /* G 6 */
                                      | ((p >> 3) & 0x001f));  /* B 5 */
                r0[2 * x] = c; r0[2 * x + 1] = c;
            }
            memcpy(r1, r0, (size_t)DOOMGENERIC_RESX * 2 * sizeof(uint16_t));
        }
    } else if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
        for (y = 0; y < DOOMGENERIC_RESY; y++) {
            const uint32_t *sp = src + y * DOOMGENERIC_RESX;
            uint32_t *r0 = (uint32_t *)((char *)pixels + (size_t)(2 * y)     * info.stride);
            uint32_t *r1 = (uint32_t *)((char *)pixels + (size_t)(2 * y + 1) * info.stride);
            for (x = 0; x < DOOMGENERIC_RESX; x++) {
                uint32_t p = sp[x];
                uint32_t c = 0xff000000u
                           | ((p & 0x00ff0000u) >> 16)
                           |  (p & 0x0000ff00u)
                           | ((p & 0x000000ffu) << 16);
                r0[2 * x] = c; r0[2 * x + 1] = c;
            }
            memcpy(r1, r0, (size_t)DOOMGENERIC_RESX * 2 * sizeof(uint32_t));
        }
    }
    AndroidBitmap_unlockPixels(env, bmp);
}
