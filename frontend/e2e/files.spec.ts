import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';

async function noOverflow(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
}

test('Регистрация, вход, приватные файлы, скачивание и выход', async ({ page, browser }, testInfo) => {
  const username = `demo_${testInfo.project.name}`;
  const password = 'Browser correct passphrase';
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error' && message.text().includes('Content Security Policy'))
      errors.push(message.text());
  });
  await page.goto('/register');
  await expect(page.getByRole('heading', { name: 'Создайте своё пространство' })).toBeVisible();
  await noOverflow(page);
  await page.screenshot({
    path: resolve('../docs/screenshots', `${testInfo.project.name}-register.png`),
    fullPage: true,
  });
  await page.getByLabel('Логин', { exact: true }).fill(username);
  await page.getByLabel('Пароль', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Создать аккаунт' }).click();
  await expect(page.getByText('Аккаунт создан. Теперь можно войти.')).toBeVisible();
  await expect(page.getByLabel('Пароль', { exact: true })).toHaveValue('');
  await page.screenshot({
    path: resolve('../docs/screenshots', `${testInfo.project.name}-login.png`),
    fullPage: true,
  });
  await page.getByLabel('Пароль', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Войти', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Мои файлы.' })).toBeVisible();
  await expect(page.getByText('Место для важных вещей')).toBeVisible();
  const samples = [
    { name: 'План проекта.txt', content: 'План проекта SecureShare — приватный файл.' },
    { name: 'Заметки по встрече.md', content: '# Заметки\nСогласовать документы.' },
    { name: 'requirements.json', content: '{"status":"ready"}' },
  ];
  for (const sample of samples) {
    await page.getByRole('button', { name: 'Загрузить файл', exact: true }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await page.getByLabel('Выберите файл', { exact: true }).setInputFiles({
      name: sample.name,
      mimeType: 'application/octet-stream',
      buffer: Buffer.from(sample.content),
    });
    await dialog.getByRole('button', { name: 'Загрузить', exact: true }).click();
    await expect(dialog).not.toBeVisible();
    await expect(page.getByText(sample.name, { exact: true })).toBeVisible();
  }
  await page.getByRole('searchbox').fill('ПЛАН');
  await expect(page.getByRole('listitem')).toHaveCount(1);
  await page.getByRole('searchbox').clear();
  await page.getByLabel('Сортировка файлов').selectOption('name');
  await expect(page.getByRole('listitem')).toHaveCount(3);
  await expect(page.getByText('Зашифрован', { exact: true })).toHaveCount(3);
  const fileRow = page.getByRole('listitem').filter({ hasText: samples[0].name });
  const integrityResponse = page.waitForResponse(
    (response) => response.request().method() === 'POST' && response.url().endsWith('/integrity-check'),
  );
  const unexpectedDownloads: string[] = [];
  page.on('download', (download) => unexpectedDownloads.push(download.suggestedFilename()));
  await fileRow.getByRole('button', { name: `Проверить файл ${samples[0].name}` }).click();
  const integrity = await integrityResponse;
  expect(integrity.status()).toBe(200);
  expect(integrity.headers()['cache-control']).toBe('no-store');
  expect(await integrity.json()).toEqual({ status: 'VERIFIED', checkedAt: expect.any(String) });
  await expect(fileRow.getByText('Целостность подтверждена')).toBeVisible();
  expect(unexpectedDownloads).toEqual([]);
  await noOverflow(page);
  await page.screenshot({
    path: resolve('../docs/screenshots', `${testInfo.project.name}-files.png`),
    fullPage: true,
  });
  await page.getByRole('button', { name: 'Загрузить файл', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.keyboard.press('Tab');
  expect(await page.evaluate(() => !!document.activeElement?.closest('dialog'))).toBe(true);
  await noOverflow(page);
  await page.screenshot({
    path: resolve('../docs/screenshots', `${testInfo.project.name}-upload.png`),
    fullPage: true,
  });
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).not.toBeVisible();
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: `Скачать ${samples[0].name}`, exact: true }).click();
  const downloaded = await downloadPromise;
  expect(downloaded.suggestedFilename()).toBe(samples[0].name);
  expect(await readFile((await downloaded.path())!, 'utf8')).toBe(samples[0].content);
  const metadata = (await (await page.request.get('/api/v1/files')).json()) as { id: string }[];
  const other = await browser.newContext();
  try {
    const token = (await (
      await other.request.get(`${testInfo.project.use.baseURL ?? process.env.E2E_BASE_URL}/api/v1/auth/csrf`)
    ).json()) as { token: string; headerName: string };
    const base = process.env.E2E_BASE_URL!;
    const credentials = { username: `${username}x`, password };
    expect(
      (
        await other.request.post(`${base}/api/v1/auth/register`, {
          data: credentials,
          headers: { [token.headerName]: token.token },
        })
      ).status(),
    ).toBe(201);
    expect(
      (
        await other.request.post(`${base}/api/v1/auth/login`, {
          form: credentials,
          headers: { [token.headerName]: token.token },
        })
      ).status(),
    ).toBe(200);
    expect((await other.request.get(`${base}/api/v1/files/${metadata[0].id}/content`)).status()).toBe(403);
    expect(await (await other.request.get(`${base}/api/v1/files`)).json()).toEqual([]);
  } finally {
    await other.close();
  }
  await page.reload();
  await expect(page.getByRole('listitem')).toHaveCount(3);
  await page.getByRole('button', { name: 'Выйти', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'С возвращением' })).toBeVisible();
  expect((await page.request.get('/api/v1/files')).status()).toBe(401);
  expect(await page.evaluate(() => Object.keys(localStorage))).toEqual([]);
  expect(errors).toEqual([]);
});

test('Управление сеансами и отзыв доступа в другом браузере', async ({ page, browser }, testInfo) => {
  const base = process.env.E2E_BASE_URL!;
  const username = `security_${testInfo.project.name}`;
  const password = 'Session browser passphrase';
  const csrfResponse = await page.request.get('/api/v1/auth/csrf');
  const token = (await csrfResponse.json()) as { token: string; headerName: string };
  const credentials = { username, password };
  expect(
    (
      await page.request.post('/api/v1/auth/register', {
        data: credentials,
        headers: { [token.headerName]: token.token },
      })
    ).status(),
  ).toBe(201);
  expect(
    (
      await page.request.post('/api/v1/auth/login', {
        form: credentials,
        headers: { [token.headerName]: token.token },
      })
    ).status(),
  ).toBe(200);
  const other = await browser.newContext({ baseURL: base });
  try {
    const otherPage = await other.newPage();
    await otherPage.goto('/login');
    await otherPage.getByLabel('Логин', { exact: true }).fill(username);
    await otherPage.getByLabel('Пароль', { exact: true }).fill(password);
    await otherPage.getByRole('button', { name: 'Войти', exact: true }).click();
    await expect(otherPage.getByRole('heading', { name: 'Мои файлы.' })).toBeVisible();
    await otherPage.getByRole('link', { name: 'Безопасность', exact: true }).click();
    await expect(otherPage.getByText('2 из 3 доступных')).toBeVisible();
    await page.goto('/security');
    await expect(page.getByText('2 из 3 доступных')).toBeVisible();
    await noOverflow(page);
    await page.screenshot({
      path: resolve('../docs/screenshots', `${testInfo.project.name}-security.png`),
      fullPage: true,
    });
    await page.getByRole('button', { name: 'Завершить', exact: true }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.keyboard.press('Tab');
    expect(await page.evaluate(() => !!document.activeElement?.closest('dialog'))).toBe(true);
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).not.toBeVisible();
    await page.getByRole('button', { name: 'Завершить', exact: true }).click();
    await page.getByRole('button', { name: 'Завершить сеанс', exact: true }).click();
    await expect(page.getByText('1 из 3 доступных')).toBeVisible();
    await otherPage.getByRole('button', { name: 'Обновить', exact: true }).click();
    await expect(otherPage.getByRole('heading', { name: 'С возвращением' })).toBeVisible();
    await expect(otherPage.getByText('Сеанс завершён. Войдите снова.')).toBeVisible();
    expect((await other.request.get('/api/v1/files')).status()).toBe(401);
    expect((await page.request.get('/api/v1/me')).status()).toBe(200);
    await otherPage.getByLabel('Логин', { exact: true }).fill(username);
    await otherPage.getByLabel('Пароль', { exact: true }).fill(password);
    await otherPage.getByRole('button', { name: 'Войти', exact: true }).click();
    await expect(otherPage.getByRole('heading', { name: 'Мои файлы.' })).toBeVisible();
    await expect(otherPage.getByText('Сеанс завершён. Войдите снова.')).not.toBeVisible();
  } finally {
    await other.close();
  }
});

test(
  'Владелец шифрует старый файл и скачивает те же байты',
  { tag: '@legacy-fixture' },
  async ({ page }, testInfo) => {
    await page.goto('/login');
    await page.getByLabel('Логин', { exact: true }).fill(`legacy_${testInfo.project.name}`);
    await page.getByLabel('Пароль', { exact: true }).fill('Legacy browser passphrase');
    await page.getByRole('button', { name: 'Войти', exact: true }).click();

    const row = page.getByRole('listitem').filter({ hasText: 'legacy.txt' });
    await expect(row.getByText('Ожидает переноса')).toBeVisible();
    await noOverflow(page);
    await page.screenshot({
      path: resolve('../docs/screenshots', `${testInfo.project.name}-legacy-file.png`),
      fullPage: true,
    });
    await row.getByRole('button', { name: 'Проверить файл legacy.txt' }).click();
    await expect(row.getByText('Целостность подтверждена')).toBeVisible();
    await noOverflow(page);
    const encryption = page.waitForResponse(
      (response) => response.request().method() === 'POST' && response.url().endsWith('/encrypt'),
    );
    await row.getByRole('button', { name: 'Зашифровать файл legacy.txt' }).click();

    const response = await encryption;
    expect(response.status()).toBe(200);
    expect(await response.json()).toMatchObject({ storageStatus: 'ENCRYPTED', legacyCleanupPending: false });
    await expect(row.getByText('Зашифрован', { exact: true })).toBeVisible();
    await expect(row.getByText('Целостность подтверждена')).toHaveCount(0);
    await expect(row.getByRole('button', { name: 'Зашифровать файл legacy.txt' })).toHaveCount(0);
    await page.mouse.move(0, 0);
    await noOverflow(page);
    await page.screenshot({
      path: resolve('../docs/screenshots', `${testInfo.project.name}-legacy-encrypted.png`),
      fullPage: true,
    });
    const download = page.waitForEvent('download');
    await row.getByRole('button', { name: 'Скачать legacy.txt' }).click();
    expect(await readFile((await (await download).path())!, 'utf8')).toBe('Legacy browser file');
    await noOverflow(page);
  },
);
