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

`npm run stage:android:runtime` copies `codex.bin` and `libc++_shared.so` from the
Termux-compatible Codex package into ignored Android assets. Override the source
with `CODEX_ANDROID_RUNTIME_SOURCE=/path/to/runtime/bin` when staging from a
different runtime build.

This Termux workspace currently does not include Java, Gradle, or the Android SDK,
so APK builds need to run on a machine/container that has those tools installed.
