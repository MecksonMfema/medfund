import { defineConfig, devices } from '@playwright/test';

const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:4200';

export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  // Retry on CI only — one retry catches flake without masking real regressions.
  retries: process.env.CI ? 1 : 0,
  // Two workers locally keeps the laptop responsive; CI overrides via --workers.
  workers: process.env.CI ? 4 : 2,
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
    ['junit', { outputFile: 'test-results/junit.xml' }],
  ],
  use: {
    baseURL: BASE_URL,
    // Trace on first retry — keeps successful runs fast, gives diagnostics on failure.
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    // Video disabled — Playwright's bundled ffmpeg is not available on every
    // host OS. Trace + screenshot already give good post-mortem coverage.
    // Override in CI with PLAYWRIGHT_VIDEO=retain-on-failure if needed.
    video: (process.env.PLAYWRIGHT_VIDEO as 'off' | 'on' | 'retain-on-failure') ?? 'off',
    // Long enough for Angular's first-paint + Keycloak iframe stub round-trip,
    // short enough that a hung test fails inside the per-job CI budget.
    actionTimeout: 10_000,
    navigationTimeout: 15_000,
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        // Use the system Google Chrome when Playwright's bundled browser is
        // unavailable for the current host OS (e.g. Ubuntu 26.04). Override
        // with `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=...` in CI if needed.
        channel: process.env.PLAYWRIGHT_CHANNEL ?? 'chrome',
      },
    },
  ],
  // Auto-start `ng serve` so contributors do not have to remember a second
  // terminal. Reuses an already-running server (handy during `--headed`
  // iteration). Disabled when E2E_BASE_URL points at an external dev server.
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: 'npm --prefix .. run start -- --port 4200',
        url: BASE_URL,
        reuseExistingServer: !process.env.CI,
        timeout: 120_000,
        stdout: 'pipe',
        stderr: 'pipe',
      },
});
