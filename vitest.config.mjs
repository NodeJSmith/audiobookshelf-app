import { fileURLToPath } from 'node:url'
import vue from '@vitejs/plugin-vue2'
import { defineConfig } from 'vitest/config'

const root = fileURLToPath(new URL('.', import.meta.url))

export default defineConfig({
  plugins: [vue()],
  resolve: {
    // Nuxt maps both @ and ~ to the project root, and the app's modules use both
    alias: { '@': root, '~': root }
  },
  test: {
    // Vitest's default discovery (*.test.* and *.spec.*), limited to the JS test tree
    dir: 'test/js'
  }
})
