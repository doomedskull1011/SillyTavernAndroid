# SillyTavernAndroid

SillyTavernAndroid packages [SillyTavern](https://github.com/SillyTavern/SillyTavern) 1.18.0 as a self-contained Android app. It runs a real Node.js server inside the app and shows the SillyTavern web UI in a full-screen WebView.

The current release is built as:

- Package: `com.sillytavern.app`
- Version: `1.1.0-st1.18.0` (`versionCode 2`)
- APK: `app-release.apk` / release asset `SillyTavernAndroid-v1.1.0-st1.18.0.apk`
- Size: about 167 MB
- Android support: Android 7.0+ / API 24+
- CPU support: arm64-v8a only

## Download

Get the latest signed release APK here:

https://github.com/doomedskull1011/SillyTavernAndroid/releases/latest

Direct asset for the current release:

https://github.com/doomedskull1011/SillyTavernAndroid/releases/download/v1.1.0-st1.18.0/SillyTavernAndroid-v1.1.0-st1.18.0.apk

## What is included

This is not a thin web wrapper around a remote server. The app bundles the runtime pieces needed to run SillyTavern locally on the phone:

- SillyTavern server and web UI from the `payload/sillytavern` tree
- Pre-installed npm dependencies for the bundled SillyTavern version
- A Node.js runtime packaged as native Android libraries
- A git client runtime so SillyTavern extension install/update flows can work on-device
- Android WebView integration for the UI
- Android download/import bridges for files

## Features

- Runs SillyTavern locally on `127.0.0.1:8000`
- Full-screen WebView UI once the local server is ready
- First-launch payload extraction with progress display
- Foreground service notification while the server is running
- Stop action in the notification
- Android system file picker support for SillyTavern file inputs
- Downloads/exports are saved to `Downloads/SillyTavern/`
- User data is kept in app-private storage and preserved across payload upgrades
- Bundled git support for extension install/update where possible
- Automatic fallback to SillyTavern's pure-JS git path if the bundled git runtime is unavailable
- Node heap sizing based on device RAM to reduce out-of-memory kills

## Requirements

### To install the app

- Android 7.0+ device
- arm64-v8a CPU
- Enough free storage for the APK plus extracted payload/runtime data
- Ability to install an APK from outside the Play Store

### To build from source

- Windows PowerShell or another shell capable of running the Gradle wrapper
- JDK 17
- Android SDK with platform/API 34
- Git
- Git LFS, because large binary/runtime files are tracked with LFS
- The Android SDK location configured in `local.properties` or through the usual Android Studio/Gradle environment

## Install on Android

1. Download the release APK to the phone.
2. Open it from a file manager or browser download.
3. Allow installation from unknown apps if Android asks.
4. Launch **SillyTavern**.

## First launch

The first launch is slower than later launches.

1. The app extracts the bundled SillyTavern payload into app-private storage.
2. The app prepares the bundled git runtime if available.
3. The app starts the local Node.js server.
4. SillyTavern may compile/cache frontend assets on first run.
5. When the server responds, the loading screen is replaced by the SillyTavern UI.

Later launches are much faster because extraction is skipped and caches are reused.

## Data and privacy

- The server binds to localhost only.
- Chats, characters, settings, and other SillyTavern data live under the app's private storage.
- Uninstalling the app deletes the app-private SillyTavern data.
- Use SillyTavern's export/backup features if you want copies outside the app.
- Exported files are written to `Downloads/SillyTavern/`.

## Permissions

The app requests only the permissions needed for its local server and Android integration:

- `INTERNET` for SillyTavern to reach configured AI/API backends
- `FOREGROUND_SERVICE` and related service permissions to keep the local server alive
- `POST_NOTIFICATIONS` on Android 13+ for the persistent server notification
- `WAKE_LOCK` to keep the server process running reliably

## How it works

The Android wrapper has four main parts:

- `MainActivity.java`
  - Builds the WebView UI
  - Shows extraction/startup progress
  - Opens Android file pickers for web file inputs
  - Routes normal HTTP(S) downloads into Android storage
  - Waits for `http://127.0.0.1:8000` to respond before showing the UI

- `ServerService.java`
  - Runs the bundled Node.js executable as a foreground service
  - Sets environment variables such as `HOME`, `TMPDIR`, `LD_LIBRARY_PATH`, and git-related paths
  - Mirrors server output to a log file for startup status/debugging
  - Provides a persistent notification with a stop action

- `PayloadExtractor.java`
  - Extracts `payload.zip` and `git.zip` from app assets
  - Uses parallel ZIP extraction for faster first launch
  - Tracks payload/git versions with marker files
  - Preserves the SillyTavern `data/` directory across app updates

- `DownloadBridge.java`
  - Bridges browser downloads that use `blob:` or `data:` URLs
  - Streams downloaded content into `Downloads/SillyTavern/`
  - Handles filename sanitizing and chunked writes

## Repository layout

```text
app/                    Android app module
  src/main/java/...     WebView host, server service, extractor, download bridge
  src/main/assets/      Bundled payload.zip and git.zip (Git LFS)
  src/main/jniLibs/     Native runtime libraries (Git LFS)
payload/sillytavern/    Bundled SillyTavern source and default content
runtime/                Scripts used to stage/patch runtime dependencies
gradle/                 Gradle wrapper files
build.gradle            Root Gradle build file
settings.gradle         Gradle settings
app/build.gradle        Android app build configuration
```

## Large files and Git LFS

This repository uses Git LFS for large binary/runtime files.

Tracked by LFS:

- `*.zip`
- `*.so`

Before cloning or building locally, install Git LFS:

```powershell
git lfs install
git clone https://github.com/doomedskull1011/SillyTavernAndroid.git
```

If you already cloned without LFS, run:

```powershell
git lfs install
git lfs pull
```

## Build from source

From the repository root on Windows:

```powershell
.\gradlew.bat assembleRelease
```

The release APK is written to:

```text
app\build\outputs\apk\release\app-release.apk
```

The build expects signing configuration to be available locally through:

```text
keystore.properties
release.keystore
```

Those files are intentionally not committed to the repository.

## Local files that are not committed

The repo is configured to keep local/private/generated files out of Git, including:

- `local.properties`
- `keystore.properties`
- `release.keystore`
- `.gradle/`
- `app/build/`
- `build/`
- `payload/sillytavern/node_modules/`
- generated runtime staging/extraction folders under `runtime/`

If you fork or rebuild this project, keep your own signing keys and local SDK paths private.

## Debugging

To watch the bundled server log on a connected device:

```powershell
adb logcat -s ServerService
```

The app also writes server output to the app-private `server.log` file used by the loading screen status view.

## Notes and limitations

- Remote AI backends are configured inside SillyTavern as usual.
- Local on-device model inference is not included.
- Local embeddings through WASM may work but can be slow on phones.
- Only arm64 Android devices are supported.
- The bundled git runtime is intended to improve extension install/update support; if it cannot run on a device, SillyTavern can fall back to its JavaScript git implementation.

## Upstream project

This repository wraps SillyTavern for Android. For the upstream project, docs, and community resources:

- SillyTavern GitHub: https://github.com/SillyTavern/SillyTavern
- SillyTavern docs: https://docs.sillytavern.app/
- SillyTavern Discord: https://discord.gg/sillytavern

## License

SillyTavern itself is licensed under AGPL-3.0. See the upstream SillyTavern repository and the files under `payload/sillytavern/` for upstream license details.
