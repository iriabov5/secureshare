import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../src/App';
import { AuthProvider, useAuth } from '../src/auth/AuthContext';
import { api, csrf, HttpError } from '../src/api/client';
import type { User } from '../src/api/types';

vi.mock('../src/api/client', async (original) => ({
  ...(await original<typeof import('../src/api/client')>()),
  api: { me: vi.fn(), login: vi.fn(), logout: vi.fn(), files: vi.fn(), register: vi.fn() },
  csrf: vi.fn(),
}));
const user: User = { id: 'server-account-id', username: 'alice', roles: ['USER'] };
function application(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(csrf).mockResolvedValue({ token: 'test-token', headerName: 'X-CSRF-TOKEN' });
  vi.mocked(api.files).mockResolvedValue([]);
});
describe('Сессия и маршруты приложения', () => {
  it('показывает loading, проводит реальный UI login/logout через session provider и защищает files', async () => {
    vi.mocked(api.me).mockRejectedValueOnce(new HttpError(401, 'UNAUTHENTICATED', 'Требуется вход'));
    vi.mocked(api.login).mockResolvedValueOnce(user);
    vi.mocked(api.logout).mockResolvedValueOnce();
    const browser = userEvent.setup();
    application('/files');
    expect(screen.getByText('Проверяем сессию…')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'С возвращением' })).toBeVisible();
    await browser.type(screen.getByLabelText('Логин'), 'alice');
    await browser.type(screen.getByLabelText('Пароль'), 'correct passphrase');
    await browser.click(screen.getByRole('button', { name: 'Войти' }));
    expect(await screen.findByRole('heading', { name: 'Мои файлы.' })).toBeVisible();
    expect(api.login).toHaveBeenCalledWith({ username: 'alice', password: 'correct passphrase' });
    await browser.click(screen.getByRole('button', { name: 'Выйти' }));
    expect(await screen.findByRole('heading', { name: 'С возвращением' })).toBeVisible();
    expect(api.logout).toHaveBeenCalledOnce();
  });
  it('сбой bootstrap допускает retry и не выглядит как неверный пароль', async () => {
    vi.mocked(api.me).mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(user);
    vi.mocked(csrf).mockRejectedValueOnce(new TypeError('offline'));
    application('/login');
    await screen.findByText('Нет соединения с сервером. Попробуйте ещё раз.');
    expect(screen.queryByLabelText('Пароль')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Попробовать снова' }));
    expect(await screen.findByRole('heading', { name: 'Мои файлы.' })).toBeVisible();
  });
  it('401 файлов отзывает клиентскую сессию и возвращает на вход', async () => {
    vi.mocked(api.me).mockResolvedValueOnce(user);
    vi.mocked(api.files).mockRejectedValueOnce(new HttpError(401, 'UNAUTHENTICATED', 'Требуется вход'));
    application('/app');
    expect(await screen.findByRole('heading', { name: 'С возвращением' })).toBeVisible();
  });
  it('анонимному доступна регистрация, неизвестный клиентский маршрут имеет понятный fallback', async () => {
    vi.mocked(api.me).mockRejectedValue(new HttpError(401, 'UNAUTHENTICATED', 'Требуется вход'));
    const first = application('/register');
    expect(await screen.findByRole('heading', { name: 'Создайте своё пространство' })).toBeVisible();
    first.unmount();
    application('/unknown');
    expect(await screen.findByRole('heading', { name: 'Страница не найдена' })).toBeVisible();
  });
  it('вошедший пользователь перенаправляется с register и корня к файлам', async () => {
    vi.mocked(api.me).mockResolvedValue(user);
    const first = application('/register');
    expect(await screen.findByRole('heading', { name: 'Мои файлы.' })).toBeVisible();
    first.unmount();
    application('/');
    expect(await screen.findByRole('heading', { name: 'Мои файлы.' })).toBeVisible();
  });
  it('ответы отменённого bootstrap не обновляют размонтированную сессию', async () => {
    let finish!: (user: User) => void;
    vi.mocked(api.me).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    const view = application('/files');
    view.unmount();
    await act(async () => finish(user));
    expect(api.files).not.toHaveBeenCalled();
    let reject!: (error: unknown) => void;
    vi.mocked(api.me).mockReturnValueOnce(
      new Promise((_, fail) => {
        reject = fail;
      }),
    );
    const next = application('/files');
    next.unmount();
    await act(async () => reject(new Error('late failure')));
    expect(api.files).not.toHaveBeenCalled();
  });
  it('useAuth требует provider, чтобы компоненты не обходили lifecycle сессии', () => {
    function OutsideProvider() {
      useAuth();
      return null;
    }
    expect(() => render(<OutsideProvider />)).toThrow('AuthProvider is required');
  });
});
