# Codex Android App

This is the native Android shell for the Codex UI. It packages the Vue app into
Android WebView assets so the first screen is the same UI without opening a
separate browser.

Current milestone:

- Native Android launcher app.
- WebView host with JavaScript, DOM storage, microphone permission, and back navigation.
- `CodexAndroid.getRuntimeInfo()` JavaScript bridge for Android runtime detection.
- Static Vue asset packaging from `dist-android-web`.

Next runtime milestone:

- Add an Android background service.
- Start an app-private Codex bridge/runtime from that service.
- Point the WebView UI at the service endpoints instead of the desktop/Termux bridge.
- Store auth and workspace state under app-private Android storage.

Build flow from a host with Android SDK and Java installed:

```bash
npm run build:android
cd android-app
gradle assembleDebug
```

This Termux workspace currently does not include Java, Gradle, or the Android SDK,
so APK builds need to run on a machine/container that has those tools installed.
