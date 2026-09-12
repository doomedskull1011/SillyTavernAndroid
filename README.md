# SillyTavernAndroid (ST-Manager)

SillyTavernAndroid packages [SillyTavern](https://github.com/SillyTavern/SillyTavern) as a self-contained Android app. It runs a real Node.js server inside the app and shows the SillyTavern web UI in a full-screen WebView.

Version 2.0 turns the app into **ST-Manager**: a manager for up to **6 SillyTavern instances** running side by side on different ports, each with its own launcher icon, private data, backup import and on-device update tooling. Because SillyTavern itself can now be updated on-device straight from GitHub, the app version no longer tracks the bundled SillyTavern version.

The current release is built as:

- Package: `com.sillytavern.app`
- Version: `2.0.0` (`versionCode 5`)
- APK: `SillyTavernAndroid-v2.0.0.apk`
- Size: about 171 MB
- Android support: Android 7.0+ / API 24+
- CPU support: arm64-v8a only

## Download

Get the latest signed release APK here:

https://github.com/doomedskull1011/SillyTavernAndroid/releases/latest

Direct asset for the current release:

https://github.com/doomedskull1011/SillyTavernAndroid/releases/download/v2.0.0/SillyTavernAndroid-v2.0.0.apk

## What is included

This is not a thin web wrapper around a remote server. The app bundles the runtime pieces needed to run SillyTavern locally on the phone:

- SillyTavern server and web UI from the `payload/sillytavern` tree
- Pre-installed npm dependencies for the bundled SillyTavern version
- A Node.js runtime packaged as native Android libraries
- A git client runtime so SillyTavern extension install/update flows can work on-device
- Android WebView integration for the UI
- Android download/import bridges for files

## Features

### ST-Manager (multi-instance)

- Up to 6 instances, each on its own port (`8000`-`8005`) with its own private data (characters, chats, settings)
- All instances share one extracted SillyTavern payload, so extra instances cost almost no storage
- Creating an instance adds a launcher icon (`ST-Inst2`, ...); instance 1 keeps the classic `SillyTavern` icon
- Per-instance RAM usage, polled live from `/proc/<pid>/status`
- Per-instance backup import from a SillyTavern `.zip` backup (system file picker)
- Header toolbar at the top, right beside the `ST-Manager` title, with **Repair**, **Packages** and **Update ST** actions that apply to the shared payload
- On-device **Update ST** from GitHub: choose **Latest** (stable release tag) or **Staging** (development branch); the payload becomes a shallow git checkout and dependencies are refreshed with a bundled npm CLI
- **Packages** runs `npm install` against the current payload; **Repair** restores the bundled version as a safety net

### Core

- Runs SillyTavern locally on `127.0.0.1`, localhost-only
- Full-screen WebView UI once the local server is ready
- First-launch payload extraction with progress display
- Foreground service notification summarizing all running instances, with a stop-all action
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

- Servers bind to localhost only.
- Chats, characters, settings, and other SillyTavern data live under the app's private storage, one `instances/instN/data` directory per instance.
- Uninstalling the app deletes the app-private SillyTavern data of all instances.
- Use SillyTavern's export/backup features if you want copies outside the app; ST-Manager can import those backup zips into any instance.
- Exported files are written to `Downloads/SillyTavern/`.

## Permissions

The app requests only the permissions needed for its local server and Android integration:

- `INTERNET` for SillyTavern to reach configured AI/API backends
- `FOREGROUND_SERVICE` and related service permissions to keep the local server alive
- `POST_NOTIFICATIONS` on Android 13+ for the persistent server notification
- `WAKE_LOCK` to keep the server process running reliably

## How it works

The Android wrapper has these main parts:

- `ManagerActivity.java` (ST-Manager)
  - Launcher entry point listing all instances with state and RAM usage
  - Header toolbar beside the title with the shared-payload actions: Repair, Packages and Update ST
  - Instance create/delete (launcher icons via manifest activity-aliases toggled at runtime)
  - Per-instance backup import (SAF zip picker)

- `InstanceActivity.java` + `Inst1Activity`..`Inst6Activity.java`
  - WebView host for one instance; the subclasses are the alias targets
  - Waits for the instance's port to respond before showing the UI
  - Opens Android file pickers for web file inputs and routes downloads
  - `MainActivity.java` is kept as a legacy redirect to instance 1 for old shortcuts

- `ServerService.java`
  - Runs one bundled Node.js process per started instance as a foreground service
  - Shared payload with per-instance `--port`, `--dataRoot`, `--configPath`
  - Mirrors each server's output to its own log file
  - Persistent notification summarizing running instances with a stop-all action

- `InstanceManager.java`
  - Instance registry (`files/instances.json`), per-instance directories/config
  - Migration from the pre-2.0 single-instance layout

- `PayloadExtractor.java`
  - Extracts `payload.zip` (shared code), `git.zip` and `npm.zip` from app assets
  - Uses parallel ZIP extraction for faster first launch
  - Tracks payload/git/npm versions with marker files

- `StUpdater.java` + `UpdateActivity.java`
  - On-device git fetch/checkout of upstream release tags or the staging branch
  - `npm install` through the bundled npm CLI; progress log UI

- `Env.java`
  - Shared environment setup (HOME/TMPDIR/LD_LIBRARY_PATH/git) for spawned processes

- `DownloadBridge.java`
  - Bridges browser downloads that use `blob:` or `data:` URLs
  - Streams downloaded content into `Downloads/SillyTavern/`
  - Handles filename sanitizing and chunked writes

## Repository layout

```text
app/                    Android app module
  src/main/java/...     WebView host, server service, extractor, download bridge
  src/main/assets/      Bundled payload.zip, git.zip and npm.zip (Git LFS)
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
