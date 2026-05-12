# Codex Android App

This is the native Android shell for the Codex UI. It packages the Vue app into
Android WebView assets so the first screen is the same UI without opening a
separate browser.

Current milestone:

- Native Android launcher app.
- WebView host with JavaScript, DOM storage, microphone permission, and back navigation.
- `CodexAndroid.getRuntimeInfo()` JavaScript bridge for Android runtime detection.
- App-owned localhost server at `http://127.0.0.1:37645`.
- `/codex-api/rpc` proxy that translates browser HTTP requests to Codex app-server JSON-RPC over stdin/stdout.
- App-private runtime location: `<app files>/codex-runtime/codex.bin`.
- App-private native library location: `<app files>/codex-runtime/libc++_shared.so`.
- Executable packaged runtime location: `<nativeLibraryDir>/libcodex_bin.so`.
- App-private Codex home: `<app files>/codex-home`.
- Runtime asset extraction from `assets/codex-runtime/` into app-private storage on first launch.
- Static Vue asset packaging from `dist-android-web`, served through the local Android service.

Next runtime milestone:

- Extend the Android server endpoints that are still implemented by the Node bridge on desktop.
- Store auth and workspace state under app-private Android storage.

Build flow from a host with Android SDK and Java installed:

```bash
npm run stage:android:runtime
npm run build:android
cd android-app
gradle assembleDebug
```

On Termux, AGP's downloaded Linux `aapt2` binary cannot run because it targets
desktop Linux. Use Termux's native `aapt2` override:

```bash
gradle -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2 assembleDebug
```

The debug APK is written to:

```text
android-app/app/build/outputs/apk/debug/app-debug.apk
```

`npm run stage:android:runtime` copies `codex.bin` and `libc++_shared.so` from the
Termux-compatible Codex package into ignored Android assets. Override the source
with `CODEX_ANDROID_RUNTIME_SOURCE=/path/to/runtime/bin` when staging from a
different runtime build.

This Termux workspace has been tested with `openjdk-17`, `gradle`, `aapt`,
`aapt2`, and `apksigner` from Termux packages plus Android SDK command-line
tools installed under a local SDK directory.
