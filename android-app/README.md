# Android App

This module builds the native Android shell for Codex Android.

The app starts a small Java HTTP server, loads the Vue Codex UI into WebView,
and bridges browser RPC calls to an embedded Codex `app-server` process over
stdin/stdout.

## Runtime Layout

At runtime the app uses:

```text
http://127.0.0.1:37645
```

Important Android storage paths:

```text
<external app files>/codex-home
<external app files>/workspaces
<internal app files>/codex-runtime
<nativeLibraryDir>/libcodex_bin.so
```

On a typical device, external app files resolve to:

```text
/storage/emulated/0/Android/data/com.codexapp.mobile/files
```

`codex-home` contains auth, sessions, plugins, skills, and Codex config.
`workspaces` is the default project root.

## Build From Termux

Required Termux packages used in this workspace:

```bash
pkg install nodejs openjdk-17 gradle aapt aapt2 apksigner
```

Build from the repo root:

```bash
npm run stage:android:runtime
npm run build:android
cd android-app
gradle -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2 assembleDebug
```

Copy the APK to Downloads:

```bash
cp app/build/outputs/apk/debug/app-debug.apk /storage/emulated/0/Download/codexapp-debug.apk
apksigner verify --verbose /storage/emulated/0/Download/codexapp-debug.apk
```

## Current Android Bridge Endpoints

- `/android/health`
- `/android/runtime`
- `/android/runtime-config`
- `/codex-api/rpc`
- `/codex-api/events`
- `/codex-api/accounts/*`
- `/codex-api/workspace-roots-state`
- `/codex-api/project-root`
- `/codex-api/local-directory`
- `/codex-api/projectless-thread-cwd`
- `/codex-local-image`
- `/codex-local-file`
- `/codex-local-directories`

Plugin, app, skill, MCP, model, quota, thread, and turn operations are forwarded
to the embedded Codex app-server.
