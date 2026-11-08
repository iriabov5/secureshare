import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, csrf, HttpError, resetCsrf } from '../src/api/client';
const token = { token: 'masked-test-token', headerName: 'X-CSRF-TOKEN' };
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const fetcher = vi.fn<typeof fetch>();
beforeEach(() => {
  resetCsrf();
  fetcher.mockReset();
  vi.stubGlobal('fetch', fetcher);
});
afterEach(() => vi.unstubAllGlobals());
describe('Сессионный API client', () => {
  it('объединяет параллельные CSRF запросы', async () => {
    fetcher.mockResolvedValueOnce(json(token));
    const [first, second] = await Promise.all([csrf(), csrf()]);
    expect(first).toEqual(token);
    expect(second).toEqual(token);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it('передаёт form login и CSRF header, обновляет токен после смены сессии', async () => {
    const user = { id: '1', username: 'alice', roles: ['USER'] };
    fetcher
      .mockResolvedValueOnce(json(token))
      .mockResolvedValueOnce(json(user))
      .mockResolvedValueOnce(json({ ...token, token: 'rotated' }));
    expect(await api.login({ username: 'alice', password: 'secret passphrase' })).toEqual(user);
    const [path, init] = fetcher.mock.calls[1];
    expect(path).toBe('/api/v1/auth/login');
    expect(init?.credentials).toBe('include');
    expect(new Headers(init?.headers).get(token.headerName)).toBe(token.token);
    expect(String(init?.body)).toBe('username=alice&password=secret+passphrase');
    expect(await csrf()).toEqual({ ...token, token: 'rotated' });
    expect(fetcher).toHaveBeenCalledTimes(3);
  });
  it('не повторяет изменяющий запрос при CSRF отказе, следующий запрос получает новый токен', async () => {
    fetcher
      .mockResolvedValueOnce(json(token))
      .mockResolvedValueOnce(json({ code: 'FORBIDDEN', message: 'Отказ' }, 403));
    await expect(api.register({ username: 'alice', password: 'passphrase' })).rejects.toBeInstanceOf(
      HttpError,
    );
    expect(fetcher).toHaveBeenCalledTimes(2);
    fetcher.mockResolvedValueOnce(json(token));
    await csrf();
    expect(fetcher).toHaveBeenCalledTimes(3);
  });
  it('сетевой сбой upload не вызывает повторную отправку', async () => {
    fetcher.mockResolvedValueOnce(json(token)).mockRejectedValueOnce(new TypeError('offline'));
    await expect(api.upload(new File(['data'], 'file.txt'))).rejects.toThrow('offline');
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it('не принимает HTML redirect как успешный JSON или файл', async () => {
    fetcher.mockResolvedValue(
      new Response('<html>login</html>', { headers: { 'content-type': 'text/html' } }),
    );
    await expect(api.me()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
    await expect(api.download('1')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });
  it('logout 204 сохраняет подтверждённый результат при сбое обновления CSRF', async () => {
    fetcher
      .mockResolvedValueOnce(json(token))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockRejectedValueOnce(new TypeError('offline'));
    await expect(api.logout()).resolves.toBeUndefined();
  });
});

it('получает список сеансов с cookies и отправляет отзыв с CSRF без тела', async () => {
  const data = { sessions: [], maxSessions: 3 };
  fetcher
    .mockResolvedValueOnce(json(data))
    .mockResolvedValueOnce(json(token))
    .mockResolvedValueOnce(new Response(null, { status: 204 }));
  expect(await api.sessions()).toEqual(data);
  await expect(api.revokeSession('session/id')).resolves.toBeUndefined();
  const [path, init] = fetcher.mock.calls[2];
  expect(path).toBe('/api/v1/me/sessions/session%2Fid');
  expect(init?.method).toBe('DELETE');
  expect(init?.credentials).toBe('include');
  expect(new Headers(init?.headers).get(token.headerName)).toBe(token.token);
  expect(init?.body).toBeUndefined();
});

it('проверяет файл с CSRF без тела и принимает только подтверждение с временем', async () => {
  const signal = new AbortController().signal;
  const verified = { status: 'VERIFIED', checkedAt: '2026-11-08T09:15:00Z' };
  fetcher
    .mockResolvedValueOnce(json(token))
    .mockResolvedValueOnce(json(verified))
    .mockResolvedValueOnce(json({ ...verified, checkedAt: 'invalid' }));

  expect(await api.checkIntegrity('file/id', signal)).toEqual(verified);
  const [path, init] = fetcher.mock.calls[1];
  expect(path).toBe('/api/v1/files/file%2Fid/integrity-check');
  expect(init).toMatchObject({ method: 'POST', credentials: 'include', signal });
  expect(new Headers(init?.headers).get(token.headerName)).toBe(token.token);
  expect(init?.body).toBeUndefined();
  await expect(api.checkIntegrity('file/id', signal)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
