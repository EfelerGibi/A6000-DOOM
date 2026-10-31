// See dc_core.h for the frame flow. Built with -DCMAP256, so Doom hands us
// 8-bit palette indices plus a palette, instead of converting every pixel
// to 32-bit itself (doomgeneric's cmap_to_fb) only for us to convert it
// again to 565.

#include <errno.h>
#include <pthread.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "doomgeneric/doomgeneric.h"
#include "doomgeneric/i_timer.h"     // I_GetTime
#include "doomgeneric/i_video.h"     // colors[], palette_changed (CMAP256)

#include "dc_core.h"

#ifndef CMAP256
#error "dc_core.c needs doomgeneric built with -DCMAP256"
#endif

// ------------------------------------------------------------------ frames

typedef struct {
    uint8_t  pix[DC_W * DC_H];  // palette indices, as Doom drew them
    uint32_t pal2[256];         // RGB565 pixel doubled into both halves
    uint32_t pal32[256];        // 0xAABBGGRR, i.e. bytes R,G,B,A
} dc_frame_t;

static dc_frame_t s_fr[3] __attribute__((aligned(16)));
static int s_back = 0, s_ready = 1, s_front = 2;
static int s_pending;           // "ready" holds a frame the presenter hasn't taken
static int s_have_front;        // "front" holds something worth showing
static int s_mailbox = 1;
static volatile int s_running = 1;

static pthread_mutex_t s_mx       = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  s_cv_ready = PTHREAD_COND_INITIALIZER;   // pending became 1
static pthread_cond_t  s_cv_taken = PTHREAD_COND_INITIALIZER;   // pending became 0

static uint32_t s_pal2[256], s_pal32[256];
static int s_pal_valid;

static dc_stats_t s_st;

uint64_t dc_now_us(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000u + (uint64_t)(ts.tv_nsec / 1000);
}

static uint64_t thread_cpu_us(void)
{
#ifdef CLOCK_THREAD_CPUTIME_ID
    struct timespec ts;
    if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts) == 0)
        return (uint64_t)ts.tv_sec * 1000000u + (uint64_t)(ts.tv_nsec / 1000);
#endif
    return 0;
}

void dc_init(void)
{
    pthread_mutex_lock(&s_mx);
    s_back = 0; s_ready = 1; s_front = 2;
    s_pending = 0; s_have_front = 0; s_pal_valid = 0;
    s_running = 1;
    memset(&s_st, 0, sizeof(s_st));
    pthread_mutex_unlock(&s_mx);
}

void dc_set_mailbox(int on)
{
    pthread_mutex_lock(&s_mx);
    s_mailbox = on ? 1 : 0;
    pthread_cond_broadcast(&s_cv_taken);
    pthread_mutex_unlock(&s_mx);
}

void dc_stop(void)
{
    pthread_mutex_lock(&s_mx);
    s_running = 0;
    pthread_cond_broadcast(&s_cv_ready);
    pthread_cond_broadcast(&s_cv_taken);
    pthread_mutex_unlock(&s_mx);
}

static void build_palette(void)
{
    int i;
    for (i = 0; i < 256; i++) {
        uint32_t r = colors[i].r, g = colors[i].g, b = colors[i].b;
        uint32_t p = ((r & 0xf8) << 8) | ((g & 0xfc) << 3) | (b >> 3);
        s_pal2[i]  = p | (p << 16);
        s_pal32[i] = 0xff000000u | (b << 16) | (g << 8) | r;
    }
}

// Called by I_FinishUpdate() once per finished frame -- including every
// step of the screen melt, which Doom draws in a loop inside D_Display().
void DG_DrawFrame(void)
{
    uint64_t t0 = dc_now_us(), t1, t2;
    dc_frame_t *f = &s_fr[s_back];

    if (palette_changed || !s_pal_valid) {
        build_palette();
        palette_changed = false;
        s_pal_valid = 1;
    }
    memcpy(f->pix, DG_ScreenBuffer, sizeof(f->pix));
    memcpy(f->pal2, s_pal2, sizeof(f->pal2));
    memcpy(f->pal32, s_pal32, sizeof(f->pal32));
    t1 = dc_now_us();

    pthread_mutex_lock(&s_mx);
    while (!s_mailbox && s_pending && s_running)
        pthread_cond_wait(&s_cv_taken, &s_mx);
    t2 = dc_now_us();
    if (s_pending) s_st.dropped++;
    { int t = s_back; s_back = s_ready; s_ready = t; }
    s_pending = 1;
    s_st.produced++;
    s_st.us_convert += t1 - t0;
    s_st.us_handoff += t2 - t1;
    pthread_cond_signal(&s_cv_ready);
    pthread_mutex_unlock(&s_mx);
}

int dc_take_frame(int timeout_ms)
{
    int got;
    pthread_mutex_lock(&s_mx);
    if (!s_pending && timeout_ms > 0 && s_running) {
        struct timespec dl;
        clock_gettime(CLOCK_REALTIME, &dl);
        dl.tv_sec  += timeout_ms / 1000;
        dl.tv_nsec += (long)(timeout_ms % 1000) * 1000000L;
        if (dl.tv_nsec >= 1000000000L) { dl.tv_sec++; dl.tv_nsec -= 1000000000L; }
        while (!s_pending && s_running)
            if (pthread_cond_timedwait(&s_cv_ready, &s_mx, &dl) == ETIMEDOUT) break;
    }
    got = s_pending;
    if (got) {
        int t = s_ready; s_ready = s_front; s_front = t;
        s_pending = 0;
        s_have_front = 1;
        pthread_cond_signal(&s_cv_taken);
    }
    pthread_mutex_unlock(&s_mx);
    return got;
}

int dc_have_front(void)
{
    int v;
    pthread_mutex_lock(&s_mx);
    v = s_have_front;
    pthread_mutex_unlock(&s_mx);
    return v;
}

// ------------------------------------------------------------------ output
// Only the presenter thread reads s_fr[s_front]; Doom never writes it.

static void fill_rect(void *dst, int fmt, int stride, int x, int y, int w, int h)
{
    int r, i;
    if (w <= 0 || h <= 0) return;
    for (r = y; r < y + h; r++) {
        if (fmt == DC_FMT_565) {
            memset((uint16_t *)dst + (size_t)r * stride + x, 0, (size_t)w * 2);
        } else {
            uint32_t *p = (uint32_t *)dst + (size_t)r * stride + x;
            for (i = 0; i < w; i++) p[i] = 0xff000000u;
        }
    }
}

void dc_clear(void *dst, int fmt, int w, int h, int stride)
{
    fill_rect(dst, fmt, stride, 0, 0, w, h);
}

// 565 at 2x: one 32-bit load gives four source pixels, each becomes one
// 32-bit store (two identical 565 pixels) on each of the two output rows.
// Destination rows are only ever written, never read back: window buffers
// can be uncached, and reading them is slow.
static void blit565_2x_aligned(const dc_frame_t *f, uint16_t *dst, int stride)
{
    const uint32_t *pal = f->pal2;
    int x, y;
    for (y = 0; y < DC_H; y++) {
        const uint32_t *sp = (const uint32_t *)(f->pix + y * DC_W);
        uint32_t *r0 = (uint32_t *)(dst + (size_t)(2 * y) * stride);
        uint32_t *r1 = (uint32_t *)(dst + (size_t)(2 * y + 1) * stride);
        for (x = 0; x < DC_W / 4; x++) {
            uint32_t q = sp[x];
            uint32_t a = pal[q & 0xff];
            uint32_t b = pal[(q >> 8) & 0xff];
            uint32_t c = pal[(q >> 16) & 0xff];
            uint32_t d = pal[q >> 24];
            r0[0] = a; r0[1] = b; r0[2] = c; r0[3] = d;
            r1[0] = a; r1[1] = b; r1[2] = c; r1[3] = d;
            r0 += 4; r1 += 4;
        }
    }
}

// Any scale, any alignment, either format. Slow path; not used on the a6000.
static void blit_generic(const dc_frame_t *f, void *dst, int fmt, int stride,
                         int ox, int oy, int sc, int cw, int ch)
{
    int x, y, k;
    for (y = 0; y < ch; y++) {
        const uint8_t *sp = f->pix + (y / sc) * DC_W;
        if (fmt == DC_FMT_565) {
            uint16_t *d = (uint16_t *)dst + (size_t)(oy + y) * stride + ox;
            for (x = 0; x < cw; x += sc)
                for (k = 0; k < sc && x + k < cw; k++)
                    d[x + k] = (uint16_t)f->pal2[sp[x / sc]];
        } else {
            uint32_t *d = (uint32_t *)dst + (size_t)(oy + y) * stride + ox;
            for (x = 0; x < cw; x += sc)
                for (k = 0; k < sc && x + k < cw; k++)
                    d[x + k] = f->pal32[sp[x / sc]];
        }
    }
}

void dc_render_front(void *dst, int fmt, int w, int h, int stride, int clear_borders)
{
    const dc_frame_t *f = &s_fr[s_front];
    int sc, ox, oy, cw, ch;

    sc = w / DC_W;
    if (h / DC_H < sc) sc = h / DC_H;
    if (sc < 1) sc = 1;
    cw = DC_W * sc; ch = DC_H * sc;
    if (cw > w) cw = w;
    if (ch > h) ch = h;
    ox = (w - cw) / 2;
    oy = (h - ch) / 2;

    if (clear_borders && (cw < w || ch < h)) {
        fill_rect(dst, fmt, stride, 0, 0, w, oy);                     // top
        fill_rect(dst, fmt, stride, 0, oy + ch, w, h - oy - ch);      // bottom
        fill_rect(dst, fmt, stride, 0, oy, ox, ch);                   // left
        fill_rect(dst, fmt, stride, ox + cw, oy, w - ox - cw, ch);    // right
    }

    if (fmt == DC_FMT_565 && sc == 2 && cw == 2 * DC_W && ch == 2 * DC_H) {
        uint16_t *base = (uint16_t *)dst + (size_t)oy * stride + ox;
        if (((uintptr_t)base & 3) == 0 && (stride & 1) == 0) {
            blit565_2x_aligned(f, base, stride);
            return;
        }
    }
    blit_generic(f, dst, fmt, stride, ox, oy, sc, cw, ch);
}

// ------------------------------------------------------------------- stats

static int s_last_tictime = -1;

// Doom's world only changes once per 35 Hz tic. Entering doomgeneric_Tick()
// before the next tic is due makes TryRunTics() sleep until the tic arrives
// and then return *without running it* (its "don't stay in this loop
// forever" exit fires on the same clock edge), after which D_Display()
// renders a frame identical to the last one. Once ticks are faster than
// 28.6 ms that doubles the render work for nothing -- measured on desktop:
// 62 renders/s for 35 tics/s. Waiting for the tic here avoids it.
void dc_tick(void)
{
    uint64_t w0, c0, w1, c1;
    int t;
    while ((t = I_GetTime()) == s_last_tictime && s_running)
        DG_SleepMs(1);
    s_last_tictime = t;

    w0 = dc_now_us(); c0 = thread_cpu_us();
    doomgeneric_Tick();
    w1 = dc_now_us(); c1 = thread_cpu_us();
    pthread_mutex_lock(&s_mx);
    s_st.ticks++;
    s_st.us_tick += w1 - w0;
    s_st.us_tick_cpu += c1 - c0;
    pthread_mutex_unlock(&s_mx);
}

void dc_stats_present(uint64_t lock, uint64_t wait, uint64_t copy, uint64_t post, int repeat)
{
    pthread_mutex_lock(&s_mx);
    if (repeat > 0) s_st.repeats++;
    else if (repeat == 0) s_st.presented++;     // < 0: extra timings, same iteration
    s_st.us_lock += lock;
    s_st.us_wait += wait;
    s_st.us_copy += copy;
    s_st.us_post += post;
    pthread_mutex_unlock(&s_mx);
}

void dc_get_stats(dc_stats_t *out, int reset)
{
    pthread_mutex_lock(&s_mx);
    *out = s_st;
    if (reset) memset(&s_st, 0, sizeof(s_st));
    pthread_mutex_unlock(&s_mx);
}

// ----------------------------------------------------------- DG_* platform

#define KEYQ 64
static unsigned short s_q[KEYQ];
static volatile unsigned int s_qr = 0, s_qw = 0;

// Single producer (the UI thread), single consumer (Doom). The barriers
// keep the slot write visible before the index bump on multi-core ARM.
void dc_push_key(int pressed, int doomkey)
{
    unsigned int w = s_qw;
    if (w - s_qr >= KEYQ) return;   // full: drop rather than overwrite
    s_q[w % KEYQ] = (unsigned short)(((pressed & 1) << 8) | (doomkey & 0xff));
    __sync_synchronize();
    s_qw = w + 1;
}

int DG_GetKey(int *pressed, unsigned char *key)
{
    unsigned int r = s_qr;
    unsigned short d;
    if (r == s_qw) return 0;
    __sync_synchronize();
    d = s_q[r % KEYQ];
    __sync_synchronize();
    s_qr = r + 1;
    *pressed = (d >> 8) & 1;
    *key     = (unsigned char)(d & 0xff);
    return 1;
}

void DG_SleepMs(uint32_t ms) { usleep(ms * 1000); }

uint32_t DG_GetTicksMs(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)(ts.tv_sec * 1000u + ts.tv_nsec / 1000000u);
}

void DG_SetWindowTitle(const char *title) { (void)title; }
