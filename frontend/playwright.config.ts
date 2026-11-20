import { defineConfig } from '@playwright/test'

/**
 * End-to-end tests drive the real UI against the real backend (PostgreSQL, Kafka, Redis).
 *
 *  - Against a running stack (CI uses docker compose):   E2E_BASE_URL=http://localhost:3000 npx playwright test
 *  - Locally without Docker (starts the backend with embedded PostgreSQL/Kafka/Redis and the Vite dev server):
 *        E2E_CHANNEL=msedge npm run e2e           (or E2E_CHANNEL=chrome; unset to use Playwright's own Chromium)
 */
const external = process.env.E2E_BASE_URL
const baseURL = external ?? 'http://localhost:5173'

export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL,
    channel: process.env.E2E_CHANNEL || undefined,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    viewport: { width: 1280, height: 900 },
  },
  webServer: external
    ? undefined
    : [
        {
          command: 'node e2e/start-backend.mjs',
          url: 'http://localhost:8080/actuator/health',
          reuseExistingServer: true,
          timeout: 240_000,
          stdout: 'ignore',
        },
        {
          command: 'npm run dev -- --host 127.0.0.1',
          url: 'http://localhost:5173',
          reuseExistingServer: true,
          timeout: 60_000,
        },
      ],
})
