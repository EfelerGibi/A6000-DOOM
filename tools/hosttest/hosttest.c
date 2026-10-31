// Desktop harness for the frame path in jni/dc_core.c. Not part of the APK.
//
//   frames  <iwad> <n> <out.raw>
//       Play DEMO1 with -singletics and append each 640x400 RGB565 frame to
//       out.raw, prefixed by its 4-byte gametic. Built twice by run.sh: once
//       with -DCMAP256 + dc_core.c (the new path) and once without, using
//       the previous release's 32-bit doomgeneric output + nativeBlit loop.
//       Frames with the same gametic must match. (Comparing by frame number
//       doesn't work: Doom occasionally returns from a tick without running
//       a tic, based on wall-clock time, so the two runs drift by a frame.)
//
//   threads <iwad> <seconds> <mailbox|fifo> <display_hz>   (CMAP256 build only)
//       Doom thread + presenter thread in real time, with the presenter paced
//       like a display at display_hz. Checks the handoff for deadlocks and
//       races (build with -fsanitize=thread) and prints the frame counters.

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "doomgeneric.h"
#ifdef CMAP256
#include "dc_core.h"
#endif

extern int gametic;

#define OW (DOOMGENERIC_RESX * 2)
#define OH (DOOMGENERIC_RESY * 2)
static uint16_t s_out[OW * OH];
static double s_present_us;

static double now_us(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1e6 + ts.tv_nsec / 1e3;
}

void DG_Init(void) { }

#ifndef CMAP256
// ---- reference: previous release (32-bit doomgeneric output + nativeBlit)

void DG_DrawFrame(void) { }
void DG_SleepMs(uint32_t ms) { usleep(ms * 1000); }
uint32_t DG_GetTicksMs(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)(ts.tv_sec * 1000u + ts.tv_nsec / 1000000u);
}
int DG_GetKey(int *pressed, unsigned char *key) { return 0; }
void DG_SetWindowTitle(const char *title) { }

static void tick(void) { doomgeneric_Tick(); }

static void present(void)   // nativeBlit's RGB_565 branch, verbatim logic
{
    const uint32_t *src = (const uint32_t *)DG_ScreenBuffer;
    int x, y;
    double t0 = now_us();
    for (y = 0; y < DOOMGENERIC_RESY; y++) {
        const uint32_t *sp = src + y * DOOMGENERIC_RESX;
        uint16_t *r0 = s_out + (size_t)(2 * y) * OW;
        uint16_t *r1 = s_out + (size_t)(2 * y + 1) * OW;
        for (x = 0; x < DOOMGENERIC_RESX; x++) {
            uint32_t p = sp[x];
            uint16_t c = (uint16_t)(((p >> 8) & 0xf800)
                                  | ((p >> 5) & 0x07e0)
                                  | ((p >> 3) & 0x001f));
            r0[2 * x] = c; r0[2 * x + 1] = c;
        }
        memcpy(r1, r0, (size_t)DOOMGENERIC_RESX * 2 * sizeof(uint16_t));
    }
    s_present_us += now_us() - t0;
}

#else
// ---- new path

// frames mode calls doomgeneric_Tick() directly, like the reference build:
// dc_tick() would pace -singletics to real time for no benefit.
static void tick(void) { doomgeneric_Tick(); }

static void present(void)
{
    double t0 = now_us();
    dc_take_frame(0);
    if (dc_have_front()) dc_render_front(s_out, DC_FMT_565, OW, OH, OW, 1);
    s_present_us += now_us() - t0;
}

static int s_run = 1;              // __atomic_* access only
static int s_hz = 30;

static void *present_thread(void *arg)
{
    double period = 1e6 / s_hz, next = now_us() + period;
    (void)arg;
    while (__atomic_load_n(&s_run, __ATOMIC_ACQUIRE)) {
        double t;
        // "lock": wait for the next display slot, like a freed surface buffer
        while ((t = now_us()) < next) usleep((useconds_t)(next - t));
        next += period;
        if (next < now_us()) next = now_us() + period;   // fell behind: resync
        int got = dc_take_frame(100);
        if (dc_have_front()) dc_render_front(s_out, DC_FMT_565, OW, OH, OW, 1);
        dc_stats_present(0, 0, 0, 0, !got);
    }
    return NULL;
}

static int run_threads(char *iwad, int seconds, int mailbox, int hz)
{
    static char *argv[] = { "doom", "-iwad", NULL, "-nomusic", NULL };
    pthread_t pt;
    dc_stats_t st;
    double t0, secs;

    argv[2] = iwad;
    s_hz = hz;
    dc_init();
    dc_set_mailbox(mailbox);
    doomgeneric_Create(4, argv);
    dc_get_stats(&st, 1);

    pthread_create(&pt, NULL, present_thread, NULL);
    t0 = now_us();
    while (now_us() - t0 < seconds * 1e6) dc_tick();
    __atomic_store_n(&s_run, 0, __ATOMIC_RELEASE);
    dc_stop();
    pthread_join(pt, NULL);
    // Not `seconds`: the last tick can run long (a screen melt is ~1 s).
    secs = (now_us() - t0) / 1e6;

    dc_get_stats(&st, 0);
    printf("%s @%dHz over %.1fs: doom %.1f fps, shown %.1f fps, repeats %.1f/s, dropped %.1f/s, "
           "tick cpu %.2f ms, handoff wait %.2f ms/frame\n",
           mailbox ? "mailbox" : "fifo", hz, secs,
           st.produced / secs, st.presented / secs,
           st.repeats / secs, st.dropped / secs,
           st.ticks ? st.us_tick_cpu / 1000.0 / st.ticks : 0.0,
           st.produced ? st.us_handoff / 1000.0 / st.produced : 0.0);
    return st.presented > 0 ? 0 : 1;
}
#endif

static int run_frames(char *iwad, int n, const char *outpath)
{
    static char *argv[] = { "doom", "-iwad", NULL, "-nomusic",
                            "-playdemo", "demo1", "-singletics", NULL };
    FILE *f = fopen(outpath, "wb");
    int i;
    if (!f) { perror(outpath); return 1; }
    argv[2] = iwad;
#ifdef CMAP256
    dc_init();
#endif
    doomgeneric_Create(7, argv);
    for (i = 0; i < n; i++) {
        int32_t gt;
        tick();
        present();
        gt = gametic;
        fwrite(&gt, sizeof(gt), 1, f);
        fwrite(s_out, sizeof(s_out), 1, f);
    }
    fclose(f);
    fprintf(stderr, "%d frames, present/blit avg %.1f us\n", n, s_present_us / n);
    return 0;
}

int main(int argc, char **argv)
{
    if (argc >= 5 && !strcmp(argv[1], "frames"))
        return run_frames(argv[2], atoi(argv[3]), argv[4]);
#ifdef CMAP256
    if (argc >= 6 && !strcmp(argv[1], "threads"))
        return run_threads(argv[2], atoi(argv[3]), strcmp(argv[4], "fifo") != 0, atoi(argv[5]));
#endif
    fprintf(stderr, "usage: %s frames <iwad> <n> <out.raw>\n"
                    "       %s threads <iwad> <seconds> <mailbox|fifo> <display_hz>\n",
            argv[0], argv[0]);
    return 2;
}
