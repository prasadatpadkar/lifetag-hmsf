import { defineConfig } from 'vite';
import { resolve } from 'path';
import { readdirSync } from 'fs';

// This is a plain multi-page HTML/CSS/JS site (no React/build step needed) —
// Vite is used here purely as a fast static dev server with live reload.
// No entries need to be declared for `npm run dev`; the `build.rollupOptions.input`
// block below only matters if you ever run `npm run build` to bundle for production
// (the Docker image instead just serves the files directly via nginx — see ../Dockerfile).

const pagesDir = resolve(__dirname, 'pages');
const pageEntries = Object.fromEntries(
  readdirSync(pagesDir)
    .filter((f) => f.endsWith('.html'))
    .map((f) => [`pages/${f.replace('.html', '')}`, resolve(pagesDir, f)])
);

export default defineConfig({
  root: __dirname,
  server: {
    port: 5500,
    open: true,      // auto-opens the browser on `npm run dev`
    strictPort: false // if 5500 is taken, Vite just picks the next free port and prints it
  },
  build: {
    outDir: 'dist',
    target: 'esnext', // project-overview.html uses top-level await (Mermaid diagram render)
    rollupOptions: {
      input: {
        main: resolve(__dirname, 'index.html'),
        overview: resolve(__dirname, 'project-overview.html'),
        ...pageEntries
      }
    }
  }
});
