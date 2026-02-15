import { build } from "esbuild";

await build({
  entryPoints: ["src/index.ts"],
  bundle: true,
  platform: "node",
  target: "node18",
  format: "cjs",
  outfile: "dist/index.js",
  banner: { js: "#!/usr/bin/env node" },
  external: ["posthog-node"],
  sourcemap: false,
});
