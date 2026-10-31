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
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DoomActivity extends Activity implements SurfaceHolder.Callback, Runnable {

    private static final String TAG = "doomcam";

    // Must match DOOMGENERIC_RESX/RESY in Android.mk. Doom renders at its
    // native 320x200; the native side scales it by the largest integer that
    // fits the display (2x -> 640x400 on the a6000's 640x480 panel).
    private static final int W = 320, H = 200;

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
    private native void nativeKey(int pressed, int doomKey);
    private native void nativeSetMailbox(int on);
    private native void nativeStop();
    private native void nativeGetStats(long[] out);
    private native void nativeAddPresentStats(long lockUs, long drawUs, long postUs);
    private native int  nativePresentBitmap(Bitmap bmp, int timeoutMs);
    private native int  nativeAttachSurface(Surface surface);
    private native void nativeDetachSurface();
    private native int  nativePresentWindow(int timeoutMs);
    private native String nativeWindowInfo();

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
    private Bitmap frame;               // Canvas path only
    private Thread loop, presenter;
    private volatile boolean running;
    private int drawW = 2 * W, drawH = 2 * H;
    private final Rect src = new Rect();
    private final Paint paint = new Paint();
    private final Rect dst = new Rect();

    // Everything that touches the Surface happens under surfaceLock, so the
    // UI thread never tears it down mid-present.
    private final Object surfaceLock = new Object();
    private boolean surfaceOk;          // guarded by surfaceLock
    private boolean needAttach;         // guarded by surfaceLock
    private boolean useNative;          // guarded by surfaceLock
    private int nativeFails;            // guarded by surfaceLock
    private boolean loggedWindow;       // guarded by surfaceLock
    // >0 while a SurfaceHolder callback wants surfaceLock. The presenter
    // re-takes the lock right after releasing it, so it backs off while this
    // is set instead of relying on the monitor being fair.
    private volatile int uiWaiting;     // written by the UI thread only

    // doomcam.ini (see writeDefaultConfig)
    private boolean cfgNative = true, cfgThreaded = true, cfgMailbox = true, cfgPriority = true;

    private final Handler handler = new Handler();
    private final SparseArray<Runnable> pendingRelease = new SparseArray<Runnable>();
    private int weapon = 1;

    private File logFile;
    private final Object logLock = new Object();
    private boolean exiting = false;
    private volatile int clearFrames = 8;   // SurfaceView rotates 2-3 buffers

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                           | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Size the SurfaceView to exactly the scaled frame (640x400 on the
        // a6000) instead of the whole panel: SurfaceFlinger then composites
        // 17% fewer pixels per frame and there is no letterbox to clear.
        Display d = getWindowManager().getDefaultDisplay();
        int sw = d.getWidth(), sh = d.getHeight();
        if (sw <= 0 || sh <= 0) { sw = 640; sh = 480; }
        int scale = Math.max(1, Math.min(sw / W, sh / H));
        drawW = W * scale;
        drawH = H * scale;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new SurfaceView(this);
        holder = view.getHolder();
        holder.addCallback(this);
        // Match the camera's native surface format. Drawing an ARGB_8888
        // bitmap into a 565 surface makes Canvas convert AND dither every
        // pixel in software -- that alone cost ~99 ms per frame.
        holder.setFormat(PixelFormat.RGB_565);
        root.addView(view, new FrameLayout.LayoutParams(drawW, drawH, Gravity.CENTER));
        setContentView(root);

        // Canvas fallback: the C side expands 2x into this bitmap so Canvas
        // has nothing to scale.
        frame = Bitmap.createBitmap(drawW, drawH, Bitmap.Config.RGB_565);
        src.set(0, 0, drawW, drawH);
        paint.setDither(false);
        paint.setFilterBitmap(false);
        paint.setAntiAlias(false);
        Log.i(TAG, "display " + sw + "x" + sh + " -> surface " + drawW + "x" + drawH);
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
        synchronized (logLock) {
            if (logFile == null) return;
            try {
                FileWriter w = new FileWriter(logFile, true);
                w.write(msg);
                w.write("\n");
                w.close();
            } catch (Throwable ignored) { }
        }
    }

    private void log(String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        Log.e(TAG, msg, t);
        log(msg + "\n" + sw.toString());
    }

    // ------------------------------------------------------------ lifecycle

    public void surfaceCreated(SurfaceHolder h) {
        uiWaiting++;
        synchronized (surfaceLock) {
            surfaceOk = true;
            needAttach = true;
            surfaceLock.notifyAll();
        }
        uiWaiting--;
        if (loop == null) { running = true; loop = new Thread(this, "doom"); loop.start(); }
    }

    public void surfaceChanged(SurfaceHolder h, int fmt, int w, int hh) {
        uiWaiting++;
        synchronized (surfaceLock) {
            int x = (w - drawW) / 2, y = (hh - drawH) / 2;
            dst.set(Math.max(0, x), Math.max(0, y), Math.max(0, x) + drawW, Math.max(0, y) + drawH);
            clearFrames = 8;
            needAttach = true;      // the native Surface behind it may have been replaced
        }
        uiWaiting--;
        Log.i(TAG, "surface " + w + "x" + hh + " fmt=" + fmt + " -> dst " + dst);
    }

    public void surfaceDestroyed(SurfaceHolder h) {
        // Blocks until the presenter is outside nativePresentWindow / lockCanvas.
        uiWaiting++;
        synchronized (surfaceLock) {
            surfaceOk = false;
            useNative = false;
            nativeDetachSurface();
        }
        uiWaiting--;
    }

    @Override
    protected void onDestroy() {
        running = false;
        nativeStop();
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

    // --------------------------------------------------------------- config
    // <card>/doomcam/doomcam.ini, so rendering modes can be compared on the
    // camera without rebuilding. Written with defaults on first run.

    private void loadConfig(File work) {
        File f = new File(work, "doomcam.ini");
        if (!f.exists()) {
            writeDefaultConfig(f);
        } else {
            try {
                BufferedReader r = new BufferedReader(new FileReader(f));
                String line;
                while ((line = r.readLine()) != null) {
                    int c = line.indexOf('#');
                    if (c >= 0) line = line.substring(0, c);
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    String k = line.substring(0, eq).trim().toLowerCase();
                    String v = line.substring(eq + 1).trim().toLowerCase();
                    if (k.equals("present"))       cfgNative   = !v.equals("canvas");
                    else if (k.equals("threads"))  cfgThreaded = !v.equals("1");
                    else if (k.equals("sync"))     cfgMailbox  = !v.equals("fifo");
                    else if (k.equals("priority")) cfgPriority = !v.equals("0");
                }
                r.close();
            } catch (Throwable t) { log("reading " + f + " failed", t); }
        }
        log("config: present=" + (cfgNative ? "native" : "canvas")
            + " threads=" + (cfgThreaded ? 2 : 1)
            + " sync=" + (cfgMailbox ? "mailbox" : "fifo")
            + " priority=" + (cfgPriority ? 1 : 0));
    }

    private void writeDefaultConfig(File f) {
        try {
            FileWriter w = new FileWriter(f);
            w.write("# DoomCam settings. Edit in Mass Storage mode; read when the app starts.\n"
                  + "#\n"
                  + "# present = native | canvas\n"
                  + "#   native: write frames straight into the screen buffer (fastest).\n"
                  + "#   canvas: go through a Bitmap and Canvas.drawBitmap. Used automatically\n"
                  + "#   if native is unavailable on this firmware.\n"
                  + "present = native\n"
                  + "#\n"
                  + "# threads = 2 | 1\n"
                  + "#   2: Doom renders the next frame while the screen waits for the last one.\n"
                  + "#   1: old single-threaded loop (render, then wait, then render ...).\n"
                  + "threads = 2\n"
                  + "#\n"
                  + "# sync = mailbox | fifo\n"
                  + "#   mailbox: the newest frame always wins (lowest input lag).\n"
                  + "#   fifo: Doom waits until the screen took its frame (less CPU).\n"
                  + "sync = mailbox\n"
                  + "#\n"
                  + "# priority = 1 | 0   raise the scheduling priority of the render threads\n"
                  + "priority = 1\n");
            w.close();
        } catch (Throwable t) { log("writing " + f + " failed", t); }
    }

    private void raisePriority(int prio, String who) {
        if (!cfgPriority) return;
        try {
            android.os.Process.setThreadPriority(prio);
        } catch (Throwable t) { log(who + ": setThreadPriority(" + prio + ") failed: " + t); }
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
            log("surface: " + drawW + "x" + drawH);

            loadConfig(work);
            // Single-threaded, fifo would deadlock: the screen melt publishes
            // several frames inside one tick, with nobody else to take them.
            if (!cfgThreaded && !cfgMailbox) { cfgMailbox = true; log("threads=1: using sync=mailbox"); }
            nativeSetMailbox(cfgMailbox ? 1 : 0);
            raisePriority(android.os.Process.THREAD_PRIORITY_DISPLAY, "doom");

            nativeCreate(wad.getAbsolutePath(), work.getAbsolutePath());
            log("doom initialised, entering loop");

            if (cfgThreaded) {
                presenter = new Thread(new Runnable() {
                    public void run() { presentLoop(); }
                }, "present");
                presenter.start();
                while (running) nativeTick();
            } else {
                long lastLog = SystemClock.uptimeMillis();
                while (running) {
                    nativeTick();
                    if (presentOnce(0) < 0) SystemClock.sleep(5);
                    lastLog = maybeLogStats(lastLog);
                }
            }
        } catch (Throwable t) {
            log("loop died", t);
            stopPresenter();
            try { fail("Crashed:\n" + t); } catch (Throwable ignored) { }
        }
    }

    private void presentLoop() {
        try {
            raisePriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY, "present");
            long lastLog = SystemClock.uptimeMillis();
            while (running) {
                // 100 ms: during level loads Doom produces nothing; re-show
                // the last frame now and then rather than hold a buffer forever.
                if (presentOnce(100) < 0) SystemClock.sleep(5);
                lastLog = maybeLogStats(lastLog);
            }
        } catch (Throwable t) {
            log("present loop died", t);
        }
    }

    private void stopPresenter() {
        running = false;
        nativeStop();
        Thread p = presenter;
        if (p != null) {
            try { p.join(1000); } catch (InterruptedException ignored) { }
        }
    }

    /** One frame to the screen. 1 = new frame, 0 = old frame / no surface, -1 = error. */
    private int presentOnce(int timeoutMs) {
        if (uiWaiting > 0) { SystemClock.sleep(2); return 0; }
        synchronized (surfaceLock) {
            if (!surfaceOk) {
                try { surfaceLock.wait(100); } catch (InterruptedException ignored) { }
                return 0;
            }
            if (needAttach) {
                needAttach = false;
                nativeFails = 0;
                useNative = cfgNative && nativeAttachSurface(holder.getSurface()) == 1;
                log("present path: " + (useNative ? "native (ANativeWindow)" : "canvas")
                    + (cfgNative && !useNative ? " -- native unavailable, see logcat" : ""));
            }
            if (useNative) {
                int r = nativePresentWindow(timeoutMs);
                if (r >= 0) {
                    nativeFails = 0;
                    if (!loggedWindow) { loggedWindow = true; log("window buffer: " + nativeWindowInfo()); }
                    return r;
                }
                if (++nativeFails >= 10) {
                    log("native present failed 10x in a row (" + nativeWindowInfo()
                        + "), falling back to canvas");
                    useNative = false;
                    nativeDetachSurface();
                }
                return -1;
            }
            return presentCanvas(timeoutMs);
        }
    }

    private int presentCanvas(int timeoutMs) {
        long t0 = System.nanoTime();
        Canvas c = holder.lockCanvas();
        long t1 = System.nanoTime();
        if (c == null) return -1;
        int got;
        long t2, t3;
        try {
            got = nativePresentBitmap(frame, timeoutMs);
            t2 = System.nanoTime();
            // Clear enough frames to cover every buffer in the rotation, or
            // stale garbage shows in any letterbox band.
            if (clearFrames > 0) { c.drawColor(Color.BLACK); clearFrames--; }
            c.drawBitmap(frame, src, dst, paint);
        } finally {
            t3 = System.nanoTime();
            holder.unlockCanvasAndPost(c);
        }
        long t4 = System.nanoTime();
        nativeAddPresentStats((t1 - t0) / 1000, (t3 - t2) / 1000, (t4 - t3) / 1000);
        return got < 0 ? -1 : got;
    }

    // ---------------------------------------------------------------- stats

    private final long[] st = new long[13];

    private static String ms(long us, long n) {
        if (n <= 0) return "-";
        return String.format(Locale.US, "%.1f", us / 1000.0 / n);
    }

    private static String rate(long n, long dtMs) {
        return String.format(Locale.US, "%.1f", n * 1000.0 / Math.max(1, dtMs));
    }

    /** Every 5 s: what each thread spent its time on, per frame. */
    private long maybeLogStats(long last) {
        long now = SystemClock.uptimeMillis();
        long dt = now - last;
        if (dt < 5000) return last;
        nativeGetStats(st);
        long ticks = st[0], produced = st[1], dropped = st[2], presented = st[3], repeats = st[4];
        long iters = presented + repeats;
        boolean nat;
        synchronized (surfaceLock) { nat = useNative; }
        log("fps " + rate(presented, dt)
            + " (doom " + rate(produced, dt) + ", dropped " + rate(dropped, dt)
            + ", repeats " + rate(repeats, dt) + ")"
            + " | doom ms/tick: wall=" + ms(st[5], ticks) + " cpu=" + ms(st[6], ticks)
            + " convert=" + ms(st[7], produced) + " handoff=" + ms(st[8], produced)
            + " | present ms: lock=" + ms(st[9], iters) + " wait=" + ms(st[10], iters)
            + " copy=" + ms(st[11], iters) + " post=" + ms(st[12], iters)
            + " | " + (nat ? "native" : "canvas") + " threads=" + (cfgThreaded ? 2 : 1)
            + " " + (cfgMailbox ? "mailbox" : "fifo"));
        return now;
    }

    /** Readable message on the camera screen instead of a black rectangle. */
    private void fail(String msg) {
        synchronized (surfaceLock) {
            if (!surfaceOk) return;
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
