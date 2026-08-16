import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import path from "node:path";

export default defineConfig({
  // The interface is mounted at the ROOT, so emitted URLs carry no prefix. This was "/app/" while the
  // server-rendered tier held the root; the Java tier serves the bundle at /assets/... to match.
  base: "/",
  plugins: [react(), tailwindcss()],
  resolve: { alias: { "@": path.resolve(import.meta.dirname, "src") } },
  build: {
    // Straight into the Java module's resources. The interface is served by the same origin as the
    // API it calls, which is what lets the session cookie stay SameSite=Strict.
    outDir: path.resolve(import.meta.dirname, "../app/src/main/resources/aspm/app/webui"),
    emptyOutDir: true,
    // Hashed filenames, because the Java tier serves them with a long max-age. A stable name plus a
    // long cache is how a deploy ships an interface that half the users never receive.
    assetsDir: "assets",
  },
  // The dev server owns the root now, so it cannot blanket-proxy it. Named prefixes only: the API,
  // and the pages the Java tier still renders itself (authentication, the guide, the policy screen,
  // the component list, and attachment upload and delivery).
  server: {
    proxy: Object.fromEntries(
      ["/api", "/sign-in", "/mfa", "/mfa-enrol", "/forgot-password", "/sign-out", "/change-password",
       "/step-up", "/guide", "/security-policy", "/components", "/attachments", "/board", "/style.css",
       "/app.js", "/brand"].map((p) => [p, "http://127.0.0.1:8099"]),
    ),
  },
});
