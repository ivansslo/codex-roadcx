import { defineConfig, mergeConfig } from "vite";
import baseConfig from "./vite.config";

export default defineConfig((env) =>
  mergeConfig(
    typeof baseConfig === "function" ? baseConfig(env) : baseConfig,
    {
      base: "./",
      build: {
        outDir: "dist-android-web",
        emptyOutDir: true,
      },
    },
  ),
);
