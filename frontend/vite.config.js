
import { defineConfig } from 'vite';
import { resolve } from 'path';
import { readdirSync, cpSync, mkdirSync } from 'fs';

const pagesDir = resolve(__dirname, 'pages');

const pageEntries = Object.fromEntries(
  readdirSync(pagesDir)
    .filter((f) => f.endsWith('.html'))
    .map((f) => [
      `pages/${f.replace('.html', '')}`,
      resolve(pagesDir, f)
    ])
);

export default defineConfig({
  root: __dirname,

  server: {
    port: 5500,
    open: true,
    strictPort: false
  },

  plugins: [
    {
      name: 'copy-static-js',
      closeBundle() {
        const source = resolve(__dirname, 'js');
        const destination = resolve(__dirname, 'dist/js');

        mkdirSync(destination, { recursive: true });
        cpSync(source, destination, { recursive: true });
      }
    }
  ],

  build: {
    outDir: 'dist',
    target: 'esnext',
    rollupOptions: {
      input: {
        main: resolve(__dirname, 'index.html'),
        overview: resolve(__dirname, 'project-overview.html'),
        signup: resolve(__dirname, 'signup.html'),
        forgotPassword: resolve(__dirname, 'forgot-password.html'),
        ...pageEntries
      }
    }
  }
});
