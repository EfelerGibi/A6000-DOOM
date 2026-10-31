// Platform-independent half of the camera port: everything between Doom's
// I_FinishUpdate() and a pixel buffer. No JNI, no Android headers, so the
// same code can be built and checked on a desktop (see tools/hosttest/).
//
// Frame flow:
//   Doom thread    DG_DrawFrame() copies the 8-bit frame and the current
//                  palette into the "back" slot, then swaps it with "ready".
//   Present thread dc_take_frame() swaps "ready" with "front";
//                  dc_render_front() palette-expands "front" (2x) straight
//                  into the destination (window buffer or Bitmap).
// Three slots, so neither thread ever waits for the other to finish
// touching pixels -- only for the swap itself.

#ifndef DC_CORE_H
#define DC_CORE_H

#include <stdint.h>

#define DC_W DOOMGENERIC_RESX
#define DC_H DOOMGENERIC_RESY

// Destination pixel layouts dc_render_front() understands.
enum {
    DC_FMT_565  = 1,    // 16-bit RGB565 (the camera's native surface format)
    DC_FMT_8888 = 2     // 32-bit, bytes R,G,B,A in memory (Android RGBA/RGBX_8888)
};

// Counters since the last dc_get_stats(..., reset=1).
typedef struct {
    uint32_t ticks;         // doomgeneric_Tick() calls
    uint32_t produced;      // frames Doom finished (DG_DrawFrame calls)
    uint32_t dropped;       // mailbox mode: a ready frame replaced before it was shown
    uint32_t presented;     // presenter iterations that showed a new frame
    uint32_t repeats;       // presenter iterations that re-showed the old frame
    uint64_t us_tick;       // wall time inside doomgeneric_Tick
    uint64_t us_tick_cpu;   // CPU time of the Doom thread inside doomgeneric_Tick
    uint64_t us_convert;    // Doom thread: copy frame + palette into its slot
    uint64_t us_handoff;    // Doom thread: blocked handing the frame over (fifo)
    uint64_t us_lock;       // presenter: waiting for a surface buffer
    uint64_t us_wait;       // presenter: waiting for Doom to produce a frame
    uint64_t us_copy;       // presenter: expanding into the surface / bitmap
    uint64_t us_post;       // presenter: unlockAndPost
} dc_stats_t;

void     dc_init(void);
void     dc_set_mailbox(int on);    // 1: newest frame wins (default); 0: fifo, Doom waits
void     dc_stop(void);             // wake every waiter; used on shutdown

// Presenter side. Returns 1 if a new frame was moved into "front", 0 if
// none arrived within timeout_ms (front keeps the previous frame).
int      dc_take_frame(int timeout_ms);
int      dc_have_front(void);

// Draw "front" into dst (w x h pixels, stride in pixels), integer-scaled as
// large as fits and centred. clear_borders paints the letterbox black.
void     dc_render_front(void *dst, int fmt, int w, int h, int stride, int clear_borders);
void     dc_clear(void *dst, int fmt, int w, int h, int stride);

void     dc_tick(void);             // wait for the next tic, then doomgeneric_Tick()
// repeat: 0 = new frame shown, 1 = old frame re-shown, -1 = only add the
// timings (a second report for an iteration already counted).
void     dc_stats_present(uint64_t lock, uint64_t wait, uint64_t copy,
                          uint64_t post, int repeat);
void     dc_get_stats(dc_stats_t *out, int reset);

void     dc_push_key(int pressed, int doomkey);   // one producer thread only
uint64_t dc_now_us(void);

#endif
