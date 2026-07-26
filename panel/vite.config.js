import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

/**
 * The panel is not deployed anywhere -- it is compiled straight into the plugin
 * jar, so the build writes into Maven's own output directory and `mvn package`
 * picks it up with everything else.
 *
 * Two settings are load bearing rather than preference:
 *
 * - `cssCodeSplit: false` and no inline anything. GodView serves the panel under
 *   a strict Content-Security-Policy with no 'unsafe-inline' for scripts, so a
 *   single inlined bootstrap script would produce a blank page that works
 *   perfectly in `vite dev` and fails only in production.
 * - `assetsInlineLimit: 0` for the same reason, kept honest: every asset is a
 *   real file the CSP's 'self' covers.
 */
export default defineConfig({
  plugins: [vue()],
  base: '/',
  build: {
    outDir: '../target/classes/web',
    emptyOutDir: true,
    cssCodeSplit: false,
    assetsInlineLimit: 0,
    modulePreload: { polyfill: false },
    rollupOptions: {
      output: {
        entryFileNames: 'assets/[name]-[hash].js',
        chunkFileNames: 'assets/[name]-[hash].js',
        assetFileNames: 'assets/[name]-[hash][extname]',
      },
    },
  },
  server: {
    port: 5173,
    // `npm run dev` against a running server: the panel is developed against a
    // real Paper instance, since almost nothing here is meaningful without one.
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8899',
        changeOrigin: false,
      },
    },
  },
});
