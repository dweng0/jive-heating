import { defineConfig, devices } from '@playwright/test'

const port = Number(process.env.E2E_PORT ?? 18099)

// Browser tests against a real service + Mosquitto (see e2e/start-service.sh). Needs Docker.
// The tests share one fresh database and run in order: setup creates the admin the rest use.
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  globalTeardown: './e2e/teardown.ts',
  use: {
    baseURL: `http://localhost:${port}`,
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: {
    command: 'bash e2e/start-service.sh',
    url: `http://localhost:${port}/health`,
    timeout: 240_000,
    reuseExistingServer: false,
    stdout: 'ignore',
    stderr: 'pipe',
  },
})
