import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  base: "/mnote-transcript/",
  plugins: [react()],
  build: { outDir: "dist", emptyOutDir: true },
});
