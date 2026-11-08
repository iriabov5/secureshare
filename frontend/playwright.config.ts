import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './e2e',
  // browser-check.py создаёт старые объекты в тестовых PostgreSQL и S3.
  grepInvert: process.env.E2E_LEGACY_FIXTURE ? undefined : /@legacy-fixture/,
  fullyParallel: false,
  workers: 1,
  timeout: 60_000,
  reporter: 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://127.0.0.1:8080',
    launchOptions: { executablePath: process.env.CHROME_PATH },
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'desktop', use: { viewport: { width: 1440, height: 1000 } } },
    { name: 'mobile', use: { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true } },
  ],
});
