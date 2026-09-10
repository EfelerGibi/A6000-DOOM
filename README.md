# DoomCam

Doom running on a Sony α6000, as a PlayMemories Camera App.

Renders at Doom's native 320×200, scaled 2× onto the camera's 640×480 panel with
a 40 px letterbox. Around 26 fps, which is display-paced rather than CPU-bound.
The controls are mapped to the camera body: wheel to turn, d-pad to strafe,
shutter to fire.

Tested only on the α6000. Other PlayMemories cameras will probably run it, but
the screen size and button scan codes differ, so expect to adjust both.

---

## Install (no building required)

Grab `DoomCam.apk` from [Releases](../../releases). You need three things: the
APK, an IWAD, and a way to push apps to the camera.

### 1. Get the installer

Sony cameras don't take APKs directly — installation goes through
[Sony-PMCA-RE](https://github.com/ma1co/Sony-PMCA-RE), the community tool that
speaks Sony's app-install protocol.

- **Windows:** download `pmca-gui.exe` from that project's releases. Nothing else needed.
- **Linux / macOS:** clone it and run from source:

  ```bash
  git clone https://github.com/ma1co/Sony-PMCA-RE.git
  cd Sony-PMCA-RE
  python3 -m venv venv
  venv/bin/pip install pyusb pycryptodomex tlslite-ng pyyaml asn1crypto certifi axmlparserpy==0.0.4
  ```

### 2. Put the camera in Mass Storage mode

On the camera: `MENU → Setup → USB Connection → Mass Storage`. Insert a memory
card, connect USB, and wait until the screen shows *USB Mode*.

### 3. Install the app

**Windows:** run `pmca-gui.exe`, open the *Install app* tab, choose *Select apk*,
pick `DoomCam.apk`, and click *Install*.

**Linux / macOS:**

```bash
sudo venv/bin/python pmca-console.py install -f /path/to/DoomCam.apk
```

`sudo` is needed so libusb can claim the device. If it reports no devices found,
your desktop auto-mounted the camera — unmount it in your file manager first.

The camera will flicker and change modes on its own for a minute or so. Wait for
*Task completed successfully*.

### 4. Add a WAD

With the camera still in Mass Storage mode, copy an IWAD onto the memory card —
the root, or any folder one level down. Case doesn't matter; anything over 1 MB
ending in `.wad` is found automatically.

Where to get one:

- **Freedoom** — free and open, no purchase needed. Grab `freedoom1.wad` from
  [freedoom.github.io](https://freedoom.github.io/) or `dnf install freedoom`.
- **Shareware `DOOM1.WAD`** — id's freely redistributable episode one.
- **Your own `DOOM.WAD` or `DOOM2.WAD`** from a Doom you already own.

Eject the card properly, then disconnect.

### 5. Play

On the camera: `MENU → Application → Application List → Doom`.

Leave it a moment on the title screen and the demo will start playing, which is
how you know the WAD loaded. Press a button to get to the menu.

If you get a black screen or an error message on the LCD, check `DOOMLOG.TXT` in
the root of the memory card — it says which card path was used and which WAD was
found.

---

## Controls

| Camera button | Doom |
| --- | --- |
| Control wheel (rotate) | Turn left / right |
| D-pad left / right | Strafe |
| D-pad up / down | Forward / back |
| Shutter | Fire |
| AEL | Use / confirm |
| Top dial (rotate) | Cycle weapon |
| C1 | Run |
| Fn | Automap |
| Trash | Doom menu |
| MENU | Quit to camera |

The centre button is mapped but generally unusable — see *Known issues*. Use AEL
to confirm.

---

## Building from source

Everything except a shell lives under `toolchain/`; nothing is installed
system-wide. On Fedora:

```bash
sudo dnf install unzip git python3
```

`setup.sh` fetches the rest: NDK r16b, SDK build-tools 30.0.3, an API 28
`android.jar`, doomgeneric, Sony-PMCA-RE and its Python venv. Roughly 1 GB
downloaded, ~4 GB on disk — the NDK dominates both.

A JDK 17 is also needed. Fedora 44 no longer packages one, so unpack Temurin into
`toolchain/`:

```bash
mkdir -p toolchain
curl -fL -o toolchain/jdk17.tar.gz \
  https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.16+8/OpenJDK17U-jdk_x64_linux_hotspot_17.0.16_8.tar.gz
tar xzf toolchain/jdk17.tar.gz -C toolchain && rm toolchain/jdk17.tar.gz
```

`build.sh` finds it automatically and falls back to a system JDK if you have a
suitable one.

```bash
./setup.sh      # once
./build.sh      # produces DoomCam.apk
./install.sh    # pushes it to the camera
```

Six stages: `ndk-build` → `aapt` → `javac` → `d8` → repackage → `zipalign` +
`apksigner`.

### Debugging

`<card>/DOOMLOG.TXT` holds the app's log — card path, WAD found, per-frame
timings, stack traces. `<card>/doomcam/DOOMOUT.TXT` holds Doom's own stdout and
stderr, including any `I_Error` before it exits. Both survive the app dying,
which matters because adb goes away when you leave Tweak.

For live logs, install [OpenMemories: Tweak](https://github.com/ma1co/OpenMemories-Tweak),
enable Wi-Fi and adb there, note the IP, then:

```bash
adb connect <camera-ip>:5555
adb logcat -s doomcam
```

---

## Known issues

**The centre button opens the camera UI.** The key reaches the app
(`scanCode 232`) and is consumed, but Sony's system layer acts on it in parallel.
`dispatchKeyEvent` routes directly to the app's handlers instead of through
`super`, which fixes every other key; the centre button still leaks.
`notifyAppInfo()` registers the app with `DAConnectionManagerService`, which
doesn't help either — the `pkey` / `pullingback_key` / `resume_key` extras are
left commented in the source for anyone who wants to experiment. Use AEL.

**Freedoom aborts with `W_GetNumForName: M_EPI4 not found!`** if you build
without the patch. Freedoom Phase 1 has four episodes, so Doom identifies it as
*The Ultimate DOOM* and tries to draw an episode-4 menu graphic Freedoom doesn't
ship — doomgeneric's own comment concedes it "should crash if missing".
`setup.sh` applies this, but if you re-clone `jni/doomgeneric/` by hand:

```bash
sed -i 's|    if (gameversion < exe_ultimate)|    if (gameversion < exe_ultimate \|\| W_CheckNumForName("M_EPI4") < 0)|' \
    jni/doomgeneric/m_menu.c
```

**No sound.** doomgeneric ships no audio backend and none was added. Whether
`AudioTrack` reaches the camera speaker is untested.

**The camera can reboot.** Seen occasionally during development, most often with
Wi-Fi left on. Turn Wi-Fi off in Tweak before powering down.

---

## Performance notes

Worth writing down, because the fix wasn't obvious. The first working build ran
at 8 fps, and dropping the render resolution from 640×400 to 320×200 changed
nothing — the cost wasn't pixel count.

Per-frame instrumentation put 99 ms in `Canvas.drawBitmap`. The camera's surface
is RGB_565, and drawing an ARGB_8888 bitmap into it makes Canvas convert *and
dither* every pixel in software. Three changes took `draw` from 99 ms to 2 ms:

- `holder.setFormat(PixelFormat.RGB_565)` and an `RGB_565` bitmap
- a `Paint` with dither, filter and anti-alias off
- the 2× scale moved into the JNI blit, so `drawBitmap` is a 1:1 copy

The profile is now `tick=11 blit=4 lock=17 draw=2`. `lock` is `lockCanvas`
waiting on the display, so the panel sets the ceiling at roughly 30 fps.

Other things that cost time:

- NDK r16b is required — last with the GCC toolchain — and its floor of
  `android-14` still produces binaries that run on Android 2.3.7.
- Sign **v1 only** with `--min-sdk-version 9`. Android 2.3 can't verify SHA-256
  JAR signatures, and apksigner only picks SHA-1 when told the min SDK. Get this
  wrong and the camera refuses the app with no useful message.
- The rear control wheel reports `DIAL_2` (528/529), not `DIAL_KURU` (522/523).
- The app's private `/data` area is only a few hundred KB, far too small for an
  IWAD, which is why it's read in place from the card.

---

## Credits

This exists because other people did the hard parts first and published them.

**[ma1co](https://github.com/ma1co)** — the foundation of the entire Sony camera
hacking scene. [Sony-PMCA-RE](https://github.com/ma1co/Sony-PMCA-RE) is what
makes installing anything possible at all;
[OpenMemories-Framework](https://github.com/ma1co/OpenMemories-Framework) carries
the `ScalarInput` scan-code table that made the controls work;
[PMCADemo](https://github.com/ma1co/PMCADemo) showed how to stop the camera UI
eating key events, which is the single thing this project would have died on;
and [OpenMemories: Tweak](https://github.com/ma1co/OpenMemories-Tweak) provided
adb access for debugging. Nothing here would exist without that body of work.

**[ozkl](https://github.com/ozkl)** — [doomgeneric](https://github.com/ozkl/doomgeneric),
a Doom port that reduces the platform layer to six functions. Porting Doom to a
camera took an afternoon instead of a month because of it.

**[voxivoid](https://github.com/voxivoid)** — [recipe-lab-sony-a6000](https://github.com/voxivoid/recipe-lab-sony-a6000),
whose developer notes documented the exact toolchain (NDK r16b, build-tools
30.0.3, JDK 17, v1 signing) and whose key-dispatch approach this project copies
directly. That README saved days of trial and error.

**id Software** — for Doom, and for releasing the source in 1997. Thirty years
on it still ports to anything with a framebuffer.

**The [Freedoom](https://freedoom.github.io/) project** — a free IWAD, so this is
playable without owning anything.

---

## Licence

doomgeneric derives from the Doom source release under the GPL, so this project
is **GPL-2.0** as well. No IWAD is included; supply your own.
