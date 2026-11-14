# DocScan

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
  The page above shows the result live: paper shape, where the picture sits and the number. PDF numbers are real text in Times-Roman;
  pictures (JPG/PNG) use the bundled Liberation Serif (Times New Roman look, same widths, SIL OFL licence) from `assets/fonts`.

## Build the APK on GitHub (no Android Studio needed)
1. Create a repository, upload everything in this folder (keep the `keystore` folder), commit to `main`.
2. Open **Actions → Build Android APK**. It runs by itself on every push (or press *Run workflow*). It takes about 10-15 minutes.
3. Open the finished run: download **DocScan-apk**, or open **Releases** on your phone and download `DocScan.apk`.
4. Install it (allow "install unknown apps" for your browser once).

Each build number is higher than the one before, so a new APK installs over the old one and keeps the settings.

## Auto update
Works like Image Fixer's updater, with the same rules:
- The GitHub build publishes every build as a Release `build-<number>` with `DocScan.apk`, and writes the build number and the
  repository name into the app (`BuildConfig.BUILD`, `BuildConfig.REPO`) while building. Nothing to configure.
- The app asks `https://api.github.com/repos/<repo>/releases/latest` a few seconds after it starts, then every 10 minutes while it is open.
  A newer build shows a card on the Home screen and a question ("Update now?") with the commit message as "what is new".
- Update = download, size + SHA-256 check, Android's installer opens, tap **Install**. Settings stay (same package, same signing key).
  The first time, Android asks once to allow this app to install apps.
- Settings → Updates: switch auto update off, see the build number, or press "Check for updates".
- The repository must be **public** (the app asks GitHub without a login), and this repository should publish only DocScan releases.
- The very first install is by hand; automatic updates start from the build that contains this feature.
- A copy built on your own computer has no build number: it never offers updates.

## Notes
- Android 10 or newer, 64-bit phones (arm64-v8a).
- The APK is large (about 80-100 MB) because Python, numpy and OpenCV are inside it.
- `keystore/docscan.jks` signs the APK. It is a personal sideload key (password inside `app/build.gradle.kts`), so updates always install over each other.
- Language: Bangla or English (Home screen or Settings).
