package com.doomcam;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

public class DoomActivity extends Activity implements SurfaceHolder.Callback, Runnable {

    private static final String TAG = "doomcam";

    // Must match DOOMGENERIC_RESX/RESY in Android.mk. Doom renders at its
    // native 320x200; Canvas scales 2x into 640x400 on the 640x480 panel,
    // leaving a 40 px letterbox top and bottom.
    private static final int W = 320, H = 200;
    private static final int DRAW_W = 640, DRAW_H = 400;

    // The app's private /data area on PMCA cameras is tiny, so the IWAD is
    // never copied -- it is read in place from the card, and Doom's config and
    // savegames go to <card>/doomcam/.
    private static final String[] CARD_CANDIDATES = {
        "/sdcard", "/mnt/sdcard", "/mnt/sdcard0", "/mnt/sdcard1",
        "/mnt/ext_card", "/mnt/card", "/storage/sdcard0", "/mnt/media_rw/sdcard"
    };

    static { System.loadLibrary("doomcam"); }

    private native void nativeCreate(String iwadPath, String workDir);
    private native void nativeTick();
    private native void nativeBlit(Bitmap bmp);
    private native void nativeKey(int pressed, int doomKey);

    // ---- Doom key codes (doomkeys.h)
    private static final int K_RIGHT = 0xae, K_LEFT = 0xac, K_UP = 0xad, K_DOWN = 0xaf;
    private static final int K_STRAFE_L = 0xa0, K_STRAFE_R = 0xa1;
    private static final int K_USE = 0xa2, K_FIRE = 0xa3;
    private static final int K_ESCAPE = 27, K_ENTER = 13, K_TAB = 9;
    private static final int K_RSHIFT = 0x80 + 0x36;

    // ---- Camera scan codes, from ScalarInput in OpenMemories-Framework
    private static final int SC_UP = 103, SC_DOWN = 108, SC_LEFT = 105, SC_RIGHT = 106;
    private static final int SC_ENTER = 232;
    private static final int SC_S1 = 516, SC_S1_2 = 517, SC_S2 = 518;   // shutter half / full
    private static final int SC_MENU = 514, SC_SK1 = 229;
    private static final int SC_TRASH = 595, SC_SK2 = 513;
    private static final int SC_FN = 520, SC_AEL = 532, SC_C1 = 622;
    private static final int SC_PLAY = 207, SC_MOVIE = 515;
    // Rear control wheel reports DIAL_2 on this body: 528 = clockwise,
    // 529 = counter-clockwise. DIAL_KURU (522/523) handled the same way.
    private static final int SC_WHEEL_CW = 528, SC_WHEEL_CCW = 529;
    private static final int SC_KURU_CW = 522, SC_KURU_CCW = 523;
    private static final int SC_DIAL_L = 525, SC_DIAL_R = 526;     // top dial

    private SurfaceView view;
    private SurfaceHolder holder;
    private Bitmap frame;
    private Thread loop;
    private volatile boolean running;
    private final Rect src = new Rect(0, 0, DRAW_W, DRAW_H);
    private final Paint paint = new Paint();
    private final Rect dst = new Rect();

    private final Handler handler = new Handler();
    private final SparseArray<Runnable> pendingRelease = new SparseArray<Runnable>();
    private int weapon = 1;

    private File logFile;
    private boolean exiting = false;
    private volatile int clearFrames = 8;   // SurfaceView rotates 2-3 buffers

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                           | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new SurfaceView(this);
        holder = view.getHolder();
        holder.addCallback(this);
        // Match the camera's native surface format. Drawing an ARGB_8888
        // bitmap into a 565 surface makes Canvas convert AND dither every
        // pixel in software -- that alone cost ~99 ms per frame.
        holder.setFormat(PixelFormat.RGB_565);
        setContentView(view);
        // Doom renders 320x200; the C blit expands 2x into this bitmap so
        // Canvas has nothing to scale.
        frame = Bitmap.createBitmap(DRAW_W, DRAW_H, Bitmap.Config.RGB_565);
        paint.setDither(false);
        paint.setFilterBitmap(false);
        paint.setAntiAlias(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        notifyAppInfo();            // <-- without this, the camera UI steals ENTER
        setAutoPowerOffMode(false);
    }

    /**
     * Sony's DAConnectionManagerService intercepts some keys (notably the
     * centre/enter button) and hands them to the camera UI unless the running
     * app registers itself. ma1co's BaseActivity does this on every resume.
     * If enter is still swallowed, try adding the commented-out extras below.
     */
    private void notifyAppInfo() {
        try {
            Intent i = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
            i.putExtra("package_name", getComponentName().getPackageName());
            i.putExtra("class_name", getComponentName().getClassName());
            // i.putExtra("pkey", new String[] {});
            // i.putExtra("pullingback_key", new String[] {});
            // i.putExtra("resume_key", new String[] {});
            sendBroadcast(i);
        } catch (Throwable t) { Log.e(TAG, "notifyAppInfo failed", t); }
    }

    /** Stop the camera powering itself off mid-game. */
    private void setAutoPowerOffMode(boolean enable) {
        try {
            Intent i = new Intent("com.android.server.DAConnectionManagerService.apo");
            i.putExtra("apo_info", enable ? "APO/NORMAL" : "APO/NO");
            sendBroadcast(i);
        } catch (Throwable t) { Log.e(TAG, "apo failed", t); }
    }

    // -------------------------------------------------------------- logging
    // adb goes away when you leave OpenMemories: Tweak, so everything also
    // lands in DOOMLOG.TXT on the card. Read it in Mass Storage mode.

    private void log(String msg) {
        Log.i(TAG, msg);
        if (logFile == null) return;
        try {
            FileWriter w = new FileWriter(logFile, true);
            w.write(msg);
            w.write("\n");
            w.close();
        } catch (Throwable ignored) { }
    }

    private void log(String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        Log.e(TAG, msg, t);
        log(msg + "\n" + sw.toString());
    }

    // ------------------------------------------------------------ lifecycle

    public void surfaceCreated(SurfaceHolder h) {
        if (loop == null) { running = true; loop = new Thread(this, "doom"); loop.start(); }
    }

    public void surfaceChanged(SurfaceHolder h, int fmt, int w, int hh) {
        int x = (w - DRAW_W) / 2, y = (hh - DRAW_H) / 2;
        dst.set(Math.max(0, x), Math.max(0, y), Math.max(0, x) + DRAW_W, Math.max(0, y) + DRAW_H);
        clearFrames = 8;
        Log.i(TAG, "surface " + w + "x" + hh + " fmt=" + fmt + " -> dst " + dst);
    }

    public void surfaceDestroyed(SurfaceHolder h) { }

    @Override
    protected void onDestroy() {
        running = false;
        super.onDestroy();
        // Doom has no clean shutdown and cannot be re-initialised in the same
        // process, so on a real exit we let the process go.
        if (exiting) android.os.Process.killProcess(android.os.Process.myPid());
    }

    // -------------------------------------------------------------- storage

    private List<File> cardCandidates() {
        List<File> out = new ArrayList<File>();
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) out.add(ext);
        } catch (Throwable ignored) { }
        for (int i = 0; i < CARD_CANDIDATES.length; i++) {
            File f = new File(CARD_CANDIDATES[i]);
            boolean dup = false;
            for (int j = 0; j < out.size(); j++)
                if (out.get(j).getAbsolutePath().equals(f.getAbsolutePath())) dup = true;
            if (!dup) out.add(f);
        }
        return out;
    }

    private File findCard() {
        List<File> cands = cardCandidates();
        File best = null;
        for (int i = 0; i < cands.size(); i++) {
            File f = cands.get(i);
            long free = -1;
            try { free = f.getFreeSpace(); } catch (Throwable ignored) { }
            Log.i(TAG, "card? " + f + " exists=" + f.exists()
                     + " dir=" + f.isDirectory() + " write=" + f.canWrite() + " free=" + free);
            if (best == null && f.isDirectory() && f.canWrite() && free > 1024L * 1024L) best = f;
        }
        return best;
    }

    /** Any *.wad on the card: root, or any folder one level down. Case-insensitive. */
    private File findWad(File card) {
        File hit = scanDir(card);
        if (hit != null) return hit;
        File[] subs = card.listFiles();
        if (subs != null) {
            for (int i = 0; i < subs.length; i++) {
                if (!subs[i].isDirectory()) continue;
                String n = subs[i].getName().toUpperCase();
                // Skip Sony's own folders -- they can hold thousands of files.
                if (n.equals("DCIM") || n.equals("PRIVATE") || n.equals("MP_ROOT")
                    || n.equals("AVF_INFO") || n.equals("MISC")) continue;
                hit = scanDir(subs[i]);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private File scanDir(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (int i = 0; i < fs.length; i++) {
            if (!fs[i].isFile()) continue;
            if (fs[i].getName().toLowerCase().endsWith(".wad")
                && fs[i].length() > 1024L * 1024L) return fs[i];
        }
        return null;
    }

    // ---------------------------------------------------------- render loop

    public void run() {
        try {
            File card = findCard();
            if (card == null) {
                fail("No writable memory card found.\nSee logcat for the paths tried.");
                return;
            }
            logFile = new File(card, "DOOMLOG.TXT");
            try { if (logFile.exists()) logFile.delete(); } catch (Throwable ignored) { }
            log("=== doomcam start ===");
            log("card: " + card.getAbsolutePath() + " free=" + card.getFreeSpace());

            File wad = findWad(card);
            if (wad == null) {
                log("no .wad found under " + card);
                fail("No .wad on the card.\nCopy DOOM1.WAD to the card root\nand restart the app.");
                return;
            }
            log("iwad: " + wad.getAbsolutePath() + " (" + wad.length() + " bytes)");

            File work = new File(card, "doomcam");
            if (!work.exists() && !work.mkdirs()) log("WARN: could not create " + work);
            log("workdir: " + work.getAbsolutePath());

            nativeCreate(wad.getAbsolutePath(), work.getAbsolutePath());
            log("doom initialised, entering loop");

            long frames = 0, t0 = System.currentTimeMillis();
            long tTick = 0, tBlit = 0, tLock = 0, tDraw = 0, tPost = 0;
            while (running) {
                long a = System.currentTimeMillis();
                nativeTick();
                long b = System.currentTimeMillis();
                nativeBlit(frame);
                long c0 = System.currentTimeMillis();
                Canvas c = holder.lockCanvas();
                long d = System.currentTimeMillis();
                if (c != null) {
                    // Clear enough frames to cover every buffer in the
                    // rotation, or stale garbage shows in the letterbox bands.
                    if (clearFrames > 0) { c.drawColor(Color.BLACK); clearFrames--; }
                    c.drawBitmap(frame, src, dst, paint);
                    long e = System.currentTimeMillis();
                    holder.unlockCanvasAndPost(c);
                    tDraw += e - d;
                    tPost += System.currentTimeMillis() - e;
                }
                tTick += b - a;
                tBlit += c0 - b;
                tLock += d - c0;
                if (++frames % 300 == 0) {
                    long now = System.currentTimeMillis();
                    log("fps ~" + (300000L / Math.max(1, now - t0))
                        + "  per-frame ms: tick=" + (tTick / 300)
                        + " blit=" + (tBlit / 300)
                        + " lock=" + (tLock / 300)
                        + " draw=" + (tDraw / 300)
                        + " post=" + (tPost / 300));
                    t0 = now;
                    tTick = tBlit = tLock = tDraw = tPost = 0;
                }
            }
        } catch (Throwable t) {
            log("loop died", t);
            try { fail("Crashed:\n" + t); } catch (Throwable ignored) { }
        }
    }

    /** Readable message on the camera screen instead of a black rectangle. */
    private void fail(String msg) {
        Canvas c = holder.lockCanvas();
        if (c == null) return;
        Paint p = new Paint();
        p.setColor(Color.WHITE);
        p.setTextSize(20);
        p.setAntiAlias(true);
        c.drawColor(Color.BLACK);
        String[] lines = msg.split("\n");
        for (int i = 0; i < lines.length; i++) c.drawText(lines[i], 20, 60 + i * 26, p);
        holder.unlockCanvasAndPost(c);
    }

    // ---------------------------------------------------------------- input

    // Route key events straight to our own handlers. super.dispatchKeyEvent()
    // walks the window/view hierarchy, and on this camera something in that
    // chain consumes the centre (ENTER) button before Activity.onKeyDown sees
    // it -- which is why pressing it opened the camera UI instead. This is the
    // same approach Recipe Lab uses on the same body.
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) return onKeyDown(e.getKeyCode(), e);
        if (e.getAction() == KeyEvent.ACTION_UP)   return onKeyUp(e.getKeyCode(), e);
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (e.getRepeatCount() > 0) return true;
        int sc = e.getScanCode();

        if (sc == SC_MENU || sc == SC_SK1 || keyCode == KeyEvent.KEYCODE_MENU) { exiting = true; finish(); return true; }
        if (sc == SC_S1 || sc == SC_S1_2) return true;   // eat shutter half-press (AF)

        // Wheel = turn. Clockwise turns right.
        if (sc == SC_WHEEL_CW  || sc == SC_KURU_CW)  { pulse(K_RIGHT); return true; }
        if (sc == SC_WHEEL_CCW || sc == SC_KURU_CCW) { pulse(K_LEFT);  return true; }
        if (sc == SC_DIAL_L)  { cycleWeapon(-1);   return true; }
        if (sc == SC_DIAL_R)  { cycleWeapon(+1);   return true; }

        int k = map(keyCode, sc);
        if (k == 0) {
            Log.i(TAG, "unmapped down kc=" + keyCode + " sc=" + sc);
            return super.onKeyDown(keyCode, e);
        }
        nativeKey(1, k);
        if (k == K_USE) nativeKey(1, K_ENTER);
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        int sc = e.getScanCode();
        if (sc == SC_WHEEL_CW || sc == SC_WHEEL_CCW || sc == SC_KURU_CW || sc == SC_KURU_CCW
            || sc == SC_DIAL_L || sc == SC_DIAL_R) return true;
        if (sc == SC_S1 || sc == SC_S1_2) return true;
        int k = map(keyCode, sc);
        if (k == 0) return super.onKeyUp(keyCode, e);
        nativeKey(0, k);
        if (k == K_USE) nativeKey(0, K_ENTER);
        return true;
    }

    private int map(int keyCode, int sc) {
        switch (sc) {
            case SC_UP:     return K_UP;
            case SC_DOWN:   return K_DOWN;
            case SC_LEFT:   return K_STRAFE_L;   // d-pad strafes; wheel turns
            case SC_RIGHT:  return K_STRAFE_R;
            case SC_ENTER:  return K_USE;
            case SC_S2:     return K_FIRE;     // shutter = trigger
            case SC_AEL:    return K_USE;      // AEL = use / confirm (also sends ENTER)
            case SC_C1:     return K_RSHIFT;   // run
            case SC_FN:     return K_TAB;      // automap
            case SC_TRASH:  return K_ESCAPE;
            case SC_SK2:    return K_ESCAPE;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:     return K_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:   return K_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:   return K_STRAFE_L;
            case KeyEvent.KEYCODE_DPAD_RIGHT:  return K_STRAFE_R;
            case KeyEvent.KEYCODE_DPAD_CENTER: return K_USE;
            case KeyEvent.KEYCODE_ENTER:       return K_ENTER;
            case KeyEvent.KEYCODE_BACK:        return K_ESCAPE;
        }
        return 0;
    }

    private void pulse(final int doomKey) {
        Runnable old = pendingRelease.get(doomKey);
        if (old != null) handler.removeCallbacks(old);
        else nativeKey(1, doomKey);
        Runnable rel = new Runnable() {
            public void run() { nativeKey(0, doomKey); pendingRelease.remove(doomKey); }
        };
        pendingRelease.put(doomKey, rel);
        handler.postDelayed(rel, 120);
    }

    private void cycleWeapon(int dir) {
        weapon += dir;
        if (weapon < 1) weapon = 7;
        if (weapon > 7) weapon = 1;
        final int k = '0' + weapon;
        nativeKey(1, k);
        handler.postDelayed(new Runnable() { public void run() { nativeKey(0, k); } }, 60);
    }
}
