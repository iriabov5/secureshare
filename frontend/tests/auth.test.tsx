import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { AuthPage } from '../src/auth/AuthPage';
import { api, HttpError } from '../src/api/client';
const login = vi.fn();
vi.mock('../src/auth/AuthContext', () => ({ useAuth: () => ({ login }) }));
vi.mock('../src/api/client', async (original) => ({
  ...(await original<typeof import('../src/api/client')>()),
  api: { register: vi.fn() },
}));
beforeEach(() => vi.clearAllMocks());
describe('Формы аккаунта', () => {
  it('после регистрации открывает вход без переноса пароля', async () => {
    vi.mocked(api.register).mockResolvedValueOnce();
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/register']}>
        <Routes>
          <Route path="/register" element={<AuthPage key="register" mode="register" />} />
          <Route path="/login" element={<AuthPage key="login" mode="login" />} />
        </Routes>
      </MemoryRouter>,
    );
    await user.type(screen.getByLabelText('Логин'), 'alice');
    await user.type(screen.getByLabelText('Пароль'), 'secret passphrase');
    await user.click(screen.getByRole('button', { name: 'Создать аккаунт' }));
    await screen.findByText('Аккаунт создан. Теперь можно войти.');
    expect(screen.getByLabelText('Пароль')).toHaveValue('');
    expect(screen.getByLabelText('Логин')).toHaveValue('alice');
  });
  it('показывает отказ входа и сохраняет доступность формы для повторного действия', async () => {
    login.mockRejectedValueOnce(new HttpError(401, 'INVALID_CREDENTIALS', 'Неверный логин или пароль'));
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <AuthPage mode="login" />
      </MemoryRouter>,
    );
    expect(screen.getByLabelText('Пароль')).toHaveAttribute('autocomplete', 'current-password');
    await user.type(screen.getByLabelText('Логин'), 'alice');
    await user.type(screen.getByLabelText('Пароль'), 'wrong password');
    await user.click(screen.getByRole('button', { name: 'Войти' }));
    await screen.findByText('Неверный логин или пароль');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Войти' })).toBeEnabled());
    expect(login).toHaveBeenCalledOnce();
  });
});
