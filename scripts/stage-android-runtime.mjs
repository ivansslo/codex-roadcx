import { copyFile, mkdir, stat } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join, resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const defaultTermuxRuntimeDir = "/data/data/com.termux/files/usr/lib/node_modules/@mmmbuto/codex-cli-termux/bin";
const sourceDir = process.env.CODEX_ANDROID_RUNTIME_SOURCE || defaultTermuxRuntimeDir;
const targetDir = join(root, "android-app", "app", "src", "main", "assets", "codex-runtime");
const nativeTargetDir = join(root, "android-app", "app", "src", "main", "jniLibs", "arm64-v8a");

const files = ["codex.bin", "libc++_shared.so"];

await mkdir(targetDir, { recursive: true });
await mkdir(nativeTargetDir, { recursive: true });

for (const file of files) {
  const source = join(sourceDir, file);
  if (!existsSync(source)) {
    throw new Error(`Missing Android runtime file: ${source}`);
  }
  const target = join(targetDir, file);
  await copyFile(source, target);
  const info = await stat(target);
  console.log(`Staged ${file} (${Math.round(info.size / 1024 / 1024)} MB) -> ${target}`);
}

const nativeRuntime = join(nativeTargetDir, "libcodex_bin.so");
await copyFile(join(sourceDir, "codex.bin"), nativeRuntime);
console.log(`Staged executable native runtime -> ${nativeRuntime}`);

const nativeSharedLibrary = join(nativeTargetDir, "libc++_shared.so");
await copyFile(join(sourceDir, "libc++_shared.so"), nativeSharedLibrary);
console.log(`Staged native shared library -> ${nativeSharedLibrary}`);
