import { act, renderHook } from '@testing-library/react';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { useFileActions } from '../src/files/useFileActions';
import { api, HttpError } from '../src/api/client';
import { fileComparator } from '../src/files/sort';
import { formatBytes } from '../src/files/format';
import type { FileInfo } from '../src/api/types';
const expire = vi.fn();
const logout = vi.fn();
vi.mock('../src/auth/AuthContext', () => ({ useAuth: () => ({ expire, logout }) }));
vi.mock('../src/api/client', async (original) => ({
  ...(await original<typeof import('../src/api/client')>()),
  api: { download: vi.fn() },
}));
const file: FileInfo = {
  id: '1',
  name: 'report.txt',
  sizeBytes: 1024,
  createdAt: '2026-11-05T12:00:00Z',
  sha256: '',
};
beforeEach(() => vi.resetAllMocks());
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
describe('Действия с файлами', () => {
  it('скачивает полученный blob и освобождает Object URL, не повторяя отправку при двойном действии', async () => {
    vi.useFakeTimers();
    const create = vi.fn().mockReturnValue('blob:test');
    const revoke = vi.fn();
    vi.stubGlobal('URL', { createObjectURL: create, revokeObjectURL: revoke });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    let finish!: (value: Blob) => void;
    vi.mocked(api.download).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    const { result } = renderHook(() => useFileActions(vi.fn()));
    let pending!: Promise<void>;
    act(() => {
      pending = result.current.download(file);
    });
    expect(result.current.downloading).toBe(file.id);
    await act(async () => result.current.download(file));
    expect(api.download).toHaveBeenCalledOnce();
    await act(async () => {
      finish(new Blob(['data']));
      await pending;
    });
    expect(click).toHaveBeenCalledOnce();
    expect(create).toHaveBeenCalledOnce();
    expect(document.querySelector('a[download]')).toBeNull();
    expect(result.current.downloading).toBeNull();
    act(() => vi.runAllTimers());
    expect(revoke).toHaveBeenCalledWith('blob:test');
  });
  it('отличает истёкшую сессию от сетевого сбоя и снова разрешает ручное действие', async () => {
    const error = vi.fn();
    const { result } = renderHook(() => useFileActions(error));
    vi.mocked(api.download).mockRejectedValueOnce(new HttpError(401, 'UNAUTHENTICATED', 'Требуется вход'));
    await act(async () => result.current.download(file));
    expect(expire).toHaveBeenCalledOnce();
    vi.mocked(api.download).mockRejectedValueOnce(new TypeError('offline'));
    await act(async () => result.current.download(file));
    expect(error).toHaveBeenLastCalledWith('Нет соединения с сервером. Попробуйте ещё раз.');
    expect(result.current.downloading).toBeNull();
  });
  it('logout показывает ошибку без ложного успеха и обрабатывает 401 отдельно', async () => {
    const error = vi.fn();
    const { result } = renderHook(() => useFileActions(error));
    logout.mockRejectedValueOnce(new TypeError('offline'));
    await act(async () => result.current.signOut());
    expect(result.current.leaving).toBe(false);
    expect(error).toHaveBeenLastCalledWith('Нет соединения с сервером. Попробуйте ещё раз.');
    logout.mockRejectedValueOnce(new HttpError(401, 'UNAUTHENTICATED', 'Требуется вход'));
    await act(async () => result.current.signOut());
    expect(expire).toHaveBeenCalledOnce();
    logout.mockResolvedValueOnce(undefined);
    await act(async () => result.current.signOut());
    expect(logout).toHaveBeenCalledTimes(3);
  });
  it('сортирует по имени, размеру и времени, форматирует байты без выдуманного объёма', () => {
    const other = { ...file, id: '2', name: 'z.txt', sizeBytes: 2048, createdAt: '2026-11-06T12:00:00Z' };
    expect([other, file].sort(fileComparator('name'))[0].id).toBe('1');
    expect([file, other].sort(fileComparator('size'))[0].id).toBe('2');
    expect([file, other].sort(fileComparator('newest'))[0].id).toBe('2');
    expect(formatBytes(10 * 1024 ** 2)).toBe('10 МБ');
    expect(formatBytes(0)).toBe('0 Б');
  });
});
