import { defineConfig } from 'vite'
import { resolve } from 'path'

export default defineConfig({
  root: './src',
  build: {
    outDir: '../src-tauri/src/../../../src',
    rollupOptions: {
      input: {
        main: resolve(__dirname, 'src/index.html'),
        settings: resolve(__dirname, 'src/settings.html'),
        overlay: resolve(__dirname, 'src/overlay.html'),
        assistant: resolve(__dirname, 'src/assistant.html'),
        project_board: resolve(__dirname, 'src/project_board.html')
      }
    }
  },
  server: {
    port: 5173,
    host: '127.0.0.1'
  }
})
