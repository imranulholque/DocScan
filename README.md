# DocScanner

*Made by **Imranul Hoque Sabbir** (ইমরানুল হক সাব্বির).*

Standalone Android app: scan one or many pages with the phone camera, crop (auto page finder + draggable corners and sides),
make the text clear with ONE slider, and save as PDF / JPG / PNG to any folder or share straight to WhatsApp and other apps.

The scanning engine is the **Image Fixer** code, unchanged (`core.py`, `scanner.py`), run on the phone with
[Chaquopy](https://chaquo.com/chaquopy/) (Python + numpy + Pillow + OpenCV). `engine.py` is the thin wrapper; the screens are Kotlin / Jetpack Compose.

- Auto crop = the same `scanner.detect_document` the PC program uses.
- "Text clarity" slider = the PC's shadow / paper-white / text-darkness / sharpness / clean-specks options merged into one 0-100 value
  (see `look()` in `app/src/main/python/engine.py` to change the mapping).
- Looks: Original / Color / **Vivid** / Gray / B&W, shown as small previews of your own page.
  - The text-clarity slider works on **every** look. On *Original* it keeps colours and paper exactly as photographed (0 = no change) and only makes the writing clearer.
  - **Vivid** is for colour pictures (photos, coloured pages): even light, richer colours, sharper picture. The scan looks (Color / Gray / B&W) turn paper white and are meant for black-on-white documents.
  - Changing the look or the slider shows a light sweep + label while it is being made, then a wipe reveals the new look. Auto crop shows a scanning light while it searches and the corners glide to the page.
- **Page & number** tab (Review screen): format, PDF size (Picture size / A4 / A5 / Letter / Legal) and page number (6 positions, sizes 10-24 pt).
  The page above shows the result live: paper shape, where the picture sits and the number. PDF numbers are real **bold** text in Times-Bold;
  pictures (JPG/PNG) use the bundled Liberation Serif Bold (Times New Roman Bold look, same widths, SIL OFL licence) from `assets/fonts`.
  One switch turns numbers on; then pick top/bottom + left/center/right and a size. The preview marks the number with a blue box.
- **Live camera**: while you aim, the page outline is drawn on the camera view (everything outside the page is darkened, 4 corner brackets, green when the shot is good)
  and short messages tell what to do (move back / closer / left / right / hold the phone flatter / hold still). After the shutter the crop screen opens at once,
  starts from that outline, and says "photo taken, you can move the phone away". "Auto cropping…" floats in the middle of the picture while the page is searched.
- **Review screen**: press and hold a thumbnail, then drag left / right to change the page order (a small line under the thumbnails says so).
  **Retake** replaces the current page with a new photo (camera or gallery) in exactly the same place, keeping its look.
- **Animations always play**, even when the phone's animations are off (Developer options, Battery saver): see `FullSpeed` / `atFullSpeed` / `animateFloatAlways` in `Common.kt`.
- Photos open the crop screen instantly: the picture is prepared natively (no Python), then the page finder runs with a visible scanning animation.

## Build the APK on GitHub (no Android Studio needed)
1. Create a repository, upload everything in this folder (keep the `keystore` folder), commit to `main`.
2. Open **Actions → Build Android APK**. It runs by itself on every push (or press *Run workflow*). It takes about 10-15 minutes.
3. Open the finished run: download **DocScanner-apk**, or open **Releases** on your phone and download `DocScanner.apk`.
4. Install it (allow "install unknown apps" for your browser once).

Each build number is higher than the one before, so a new APK installs over the old one and keeps the settings.

## Auto update
Works like Image Fixer's updater, with the same rules:
- The GitHub build publishes every build as a Release `build-<number>` with `DocScanner.apk` (plus an identical `DocScan.apk` so copies installed before the rename can still update), and writes the build number and the
  repository name into the app (`BuildConfig.BUILD`, `BuildConfig.REPO`) while building. Nothing to configure.
- The app asks `https://api.github.com/repos/<repo>/releases/latest` a few seconds after it starts, then every 10 minutes while it is open.
  A newer build shows a card on the Home screen and a question ("Update now?") with the commit message as "what is new".
- Update = download, size + SHA-256 check, Android's installer opens, tap **Install**. Settings stay (same package, same signing key).
  The first time, Android asks once to allow this app to install apps.
- Settings → Updates: switch auto update off, see the build number, or press "Check for updates".
- The repository must be **public** (the app asks GitHub without a login), and this repository should publish only DocScanner releases.
- The very first install is by hand; automatic updates start from the build that contains this feature.
- A copy built on your own computer has no build number: it never offers updates.

## Notes
- Android 10 or newer, 64-bit phones (arm64-v8a).
- The APK is large (about 80-100 MB) because Python, numpy and OpenCV are inside it.
- `keystore/docscan.jks` signs the APK. It is a personal sideload key (password inside `app/build.gradle.kts`), so updates always install over each other.
- Language: Bangla or English (Home screen or Settings).

## Looks (latest)
- Every new page starts as **Original** (no effect). Pick Color / Vivid / Gray / B&W and use the slider like Lightroom's amount: 0 = original, 60 = the look as designed (default), 100 = stronger.
- Camera takes about 8 MP photos in low-latency mode (fast shutter, text stays sharp).

## New in this version
- **Signature tab** (Review screen). Add a signature from the camera (own guide: paper outline, light checks) or from the gallery:
  1. *Crop*: a box jumps around the writing by itself (`engine.sig_guess`); fix it by dragging corners / sides, or draw a new box on the picture.
  2. *Clean*: Image Fixer's `core.remove_background` takes the paper away (strength, "uneven paper / shadow", darker ink, ink colour Original / Black / Blue).
     The result is shown on a checkerboard, press and hold to compare with the original.
  Signatures are kept in the app (transparent PNG) and can be put on any page: drag to move, pinch to resize + turn, or use the round corner handle.
  "All pages" copies one to every page. They are drawn into the saved PDF / JPG / PNG at full quality (`engine.sign_page`), under the page number.
- **Manual crop**: on the crop screen choose **Corners** (perspective, like auto crop: drag corners / sides) or **Rectangle** (draw a straight box,
  drag it, resize it). A round magnifier shows the exact spot under your finger.
- **Review screen** redesigned: compact controls panel (fold it down with the handle to give the page all the room), 4 tabs with a sliding pill
  (Effect / Signature / Page size / Page number), new sliders, Save and Share in the top bar, Reset all in the ⋮ menu.
- **Camera** gives better instructions: too dark / shadow on the paper / glare messages, auto focus when steady, and a **?** Tips button
  (shown automatically the first time). Signature photos have their own tips.
