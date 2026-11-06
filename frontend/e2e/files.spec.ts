import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';

async function noOverflow(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
}

test('Регистрация, вход, приватные файлы, скачивание и выход', async ({ page, browser }, testInfo) => {
  const username = `u${crypto.randomUUID().replaceAll('-', '')}`;
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
