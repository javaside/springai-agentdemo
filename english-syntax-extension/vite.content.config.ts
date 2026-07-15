import { defineConfig } from "vite";

export default defineConfig({
  build: {
    target: "chrome120",
    sourcemap: true,
    outDir: "dist",
    emptyOutDir: false,
    lib: {
      entry: "src/content/content-script.ts",
      name: "EnglishSyntaxContentScript",
      formats: ["iife"],
      fileName: () => "content-script.js",
    },
  },
});
