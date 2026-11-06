import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { SecurityPage } from '../src/security/SecurityPage';
import { api, HttpError } from '../src/api/client';
const expire = vi.fn();
const logout = vi.fn();
vi.mock('../src/auth/AuthContext', () => ({
  useAuth: () => ({ user: { username: 'alice' }, expire, logout }),
}));
vi.mock('../src/api/client', async (original) => ({
  ...(await original<typeof import('../src/api/client')>()),
  api: { sessions: vi.fn(), revokeSession: vi.fn() },
}));
const data = {
  maxSessions: 3,
  sessions: [
    { id: 'current', current: true, createdAt: '2026-11-06T06:00:00Z', lastSeenAt: '2026-11-06T07:00:00Z' },
    { id: 'other', current: false, createdAt: '2026-11-05T06:00:00Z', lastSeenAt: '2026-11-06T05:00:00Z' },
  ],
};
const one = { ...data, sessions: data.sessions.slice(0, 1) };
function page() {
  return render(
    <MemoryRouter initialEntries={['/security']}>
      <SecurityPage />
    </MemoryRouter>,
  );
}
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.sessions).mockResolvedValue(data);
});
describe('Управление сеансами', () => {
  it('показывает реальные сеансы и current, предлагает отзыв только другого, отменяет диалог', async () => {
    page();
    expect(screen.getByText('Обновляем сеансы…')).toBeVisible();
    expect(await screen.findByText('2 из 3 доступных')).toBeVisible();
    expect(screen.getByText('Это вы')).toBeVisible();
    expect(screen.getAllByRole('button', { name: 'Завершить' })).toHaveLength(1);
    expect(screen.getByRole('link', { name: 'Безопасность' })).toHaveAttribute('aria-current', 'page');
    await userEvent.click(screen.getByRole('button', { name: 'Завершить' }));
    expect(screen.getByRole('dialog')).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: 'Отмена' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(api.revokeSession).not.toHaveBeenCalled();
  });
  it('подтверждает отзыв и обновляет список после ответа, не повторяет отправку в busy', async () => {
    let finish!: () => void;
    vi.mocked(api.revokeSession).mockReturnValue(
      new Promise<void>((resolve) => {
        finish = resolve;
      }),
    );
    page();
    await userEvent.click(await screen.findByRole('button', { name: 'Завершить' }));
    const confirm = within(screen.getByRole('dialog')).getByRole('button', { name: 'Завершить сеанс' });
    await userEvent.dblClick(confirm);
    expect(confirm).toBeDisabled();
    expect(api.revokeSession).toHaveBeenCalledExactlyOnceWith('other');
    expect(screen.queryByText('Сеанс завершён.')).not.toBeInTheDocument();
    vi.mocked(api.sessions).mockResolvedValue(one);
    await act(async () => finish());
    expect(await screen.findByText('Сеанс завершён.')).toBeVisible();
    expect(await screen.findByText('1 из 3 доступных')).toBeVisible();
  });
  it('ошибка загрузки допускает ручной retry, 404 отзыва обновляет список', async () => {
    vi.mocked(api.sessions).mockRejectedValueOnce(new TypeError('offline'));
    page();
    expect(await screen.findByText('Нет соединения с сервером. Попробуйте ещё раз.')).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: 'Обновить' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Завершить' }));
    vi.mocked(api.revokeSession).mockRejectedValueOnce(new HttpError(404, 'SESSION_NOT_FOUND', 'Нет сеанса'));
    vi.mocked(api.sessions).mockResolvedValue(one);
    await userEvent.click(screen.getByRole('button', { name: 'Завершить сеанс' }));
    expect(await screen.findByText('Этот сеанс уже завершён.')).toBeVisible();
    expect(await screen.findByText('1 из 3 доступных')).toBeVisible();
  });
  it('сетевой сбой отзыва не показывает успех и не вызывает автоматический повтор', async () => {
    vi.mocked(api.revokeSession).mockRejectedValue(new TypeError('offline'));
    page();
    await userEvent.click(await screen.findByRole('button', { name: 'Завершить' }));
    await userEvent.click(screen.getByRole('button', { name: 'Завершить сеанс' }));
    expect(await screen.findByText(/Обновите список перед повторной попыткой/)).toBeVisible();
    expect(api.revokeSession).toHaveBeenCalledOnce();
    expect(screen.queryByText('Сеанс завершён.')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Отмена' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
  it('401 списка и отзыва передаёт причину завершения session provider', async () => {
    vi.mocked(api.sessions).mockRejectedValueOnce(new HttpError(401, 'SESSION_EXPIRED', 'Сеанс завершён'));
    const first = page();
    await waitFor(() => expect(expire).toHaveBeenCalledWith('SESSION_EXPIRED'));
    first.unmount();
    expire.mockClear();
    vi.mocked(api.revokeSession).mockRejectedValueOnce(
      new HttpError(401, 'SESSION_EXPIRED', 'Сеанс завершён'),
    );
    page();
    await userEvent.click(await screen.findByRole('button', { name: 'Завершить' }));
    await userEvent.click(screen.getByRole('button', { name: 'Завершить сеанс' }));
    await waitFor(() => expect(expire).toHaveBeenCalledWith('SESSION_EXPIRED'));
  });
  it('не применяет результат загрузки после размонтирования', async () => {
    let finish!: (value: typeof data) => void;
    vi.mocked(api.sessions).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    const rendered = page();
    const signal = vi.mocked(api.sessions).mock.calls[0][0];
    rendered.unmount();
    await act(async () => finish(data));
    expect(signal?.aborted).toBe(true);
    expect(expire).not.toHaveBeenCalled();
  });
});
