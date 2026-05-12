# Codex Android App

This is the native Android shell for the Codex UI. It packages the Vue app into
Android WebView assets so the first screen is the same UI without opening a
separate browser.

Current milestone:

- Native Android launcher app.
- WebView host with JavaScript, DOM storage, microphone permission, and back navigation.
- `CodexAndroid.getRuntimeInfo()` JavaScript bridge for Android runtime detection.
- App-owned localhost server at `http://127.0.0.1:37645`.
- Static Vue asset packaging from `dist-android-web`, served through the local Android service.

Next runtime milestone:

- Start an app-private Codex bridge/runtime from the Android service.
- Proxy `/codex-api/*` from the local Android server to the embedded runtime.
- Store auth and workspace state under app-private Android storage.

Build flow from a host with Android SDK and Java installed:

```bash
npm run build:android
cd android-app
gradle assembleDebug
```

This Termux workspace currently does not include Java, Gradle, or the Android SDK,
so APK builds need to run on a machine/container that has those tools installed.
