# Codex Android

Native Android shell for the Codex UI. It packages the Codex web interface, an
embedded Codex app-server runtime, and an Android localhost bridge into one APK
so Codex can run from the Android launcher without Termux or a separate browser.

This project is based heavily on [friuns2/codexui](https://github.com/friuns2/codexui).
The original project provides the Vue UI, Node bridge, browser/server workflow,
and most of the desktop-grade Codex experience. This fork adds the Android
WebView shell, Android local server, embedded runtime packaging, Android account
bridge, Android project picker endpoints, mobile UI tuning, and shared Android
storage work.

## Status

Current Android build:

- Launches as a normal Android app.
- Serves the Codex UI inside an Android WebView.
- Starts an app-owned localhost server on `http://127.0.0.1:37645`.
- Proxies `/codex-api/rpc` calls to the embedded Codex app-server process.
- Supports chat streaming, thread history, model selection, reasoning effort,
  and Android access modes.
- Passes plugin, app, skill, MCP, and quota RPC calls through to Codex instead
  of returning empty Android placeholders.
- Uses shared Android app storage for Codex home and workspaces.
- Can import an existing `auth.json` from a Termux Codex login.

This is still a developer/debug APK, not a Play Store release.

## Android Storage

The APK uses these app-owned Android paths:

```text
/storage/emulated/0/Android/data/com.codexapp.mobile/files/codex-home
/storage/emulated/0/Android/data/com.codexapp.mobile/files/workspaces
```

`codex-home` contains Codex auth, sessions, plugins, skills, and config.
`workspaces` is the default project folder root.

The app migrates older internal app storage into this shared app storage path on
launch when possible.

## Build

From the repo root:

```bash
npm install
npm run stage:android:runtime
npm run build:android
cd android-app
gradle assembleDebug
```

On Termux, use the native `aapt2` override because Android Gradle Plugin's
downloaded desktop Linux `aapt2` cannot run in Termux:

```bash
gradle -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2 assembleDebug
```

The debug APK is written to:

```text
android-app/app/build/outputs/apk/debug/app-debug.apk
```

In this workspace, the latest copied APK is:

```text
/storage/emulated/0/Download/codexapp-debug.apk
```

## Sync With Localhost/Termux UI

The native APK works without Termux. Termux is only needed for building,
debugging, or running the browser-based localhost version.

To make the Termux localhost UI use the same chat history and project metadata
as the Android app, run it with the Android app's Codex home:

```bash
CODEX_HOME=/storage/emulated/0/Android/data/com.codexapp.mobile/files/codex-home npx codexapp@latest
```

Android may block Termux from creating another app's `Android/data` folder before
the APK has created it. Install and open the APK once first.

If direct file access is blocked by your Android version, use the app UI or a
future import endpoint instead of forcing writes into `Android/data`.

## Import Auth From Termux

If Codex login works in Termux and the Android app is open, import that auth into
the APK with:

```bash
curl -X POST http://127.0.0.1:37645/codex-api/accounts/import \
  -H 'Content-Type: application/json' \
  --data-binary @/data/data/com.termux/files/home/.codex/auth.json
```

Do not commit `auth.json`, tokens, API keys, or any other credentials.

## Repository Credits

Based on:

- [friuns2/codexui](https://github.com/friuns2/codexui)
- Original license: MIT
- Original authors listed in this repo's `LICENSE`

Android app work in this fork includes native shell/runtime integration,
Android-specific storage, WebView hosting, mobile UI adjustments, account import,
plugin catalog passthrough, and APK build support.

## Notes

- The embedded runtime is staged into ignored Android asset/native-library
  folders. Do not commit generated APKs, runtime binaries, local SDK files, or
  secrets.
- `android-app/local.properties` is intentionally ignored because it contains a
  machine-specific SDK path.
- The current APK is debug-signed.
