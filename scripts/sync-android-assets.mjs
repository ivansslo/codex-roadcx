import { cp, mkdir, rm } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join, resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const webDist = join(root, "dist-android-web");
const assetTarget = join(root, "android-app", "app", "src", "main", "assets", "codexui");

if (!existsSync(webDist)) {
  throw new Error("dist-android-web does not exist. Run `npm run build:android:web` first.");
}

await rm(assetTarget, { recursive: true, force: true });
await mkdir(assetTarget, { recursive: true });
await cp(webDist, assetTarget, { recursive: true });

console.log(`Synced Android WebView assets to ${assetTarget}`);
