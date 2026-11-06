import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { FilesPage } from '../src/files/FilesPage';
import { UploadDialog } from '../src/files/UploadDialog';
import { api, HttpError } from '../src/api/client';
const expire = vi.fn();
const logout = vi.fn();
vi.mock('../src/auth/AuthContext', () => ({
  useAuth: () => ({ user: { username: 'alice' }, expire, logout }),
}));
vi.mock('../src/api/client', async (original) => ({
  ...(await original<typeof import('../src/api/client')>()),
  api: { files: vi.fn(), upload: vi.fn(), download: vi.fn() },
}));
const files = [
  { id: '1', name: 'Проект.pdf', sizeBytes: 2048, sha256: '', createdAt: '2026-11-04T12:00:00Z' },
  {
    id: '2',
    name: '<img src=x onerror=alert(1)>.txt',
    sizeBytes: 512,
    sha256: '',
    createdAt: '2026-11-05T12:00:00Z',
  },
];
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.files).mockResolvedValue(files);
});
describe('Пространство файлов', () => {
  it('показывает реальные файлы, экранирует имя, ищет и сортирует без запросов', async () => {
    const user = userEvent.setup();
    const { container } = render(
      <MemoryRouter>
        <FilesPage />
      </MemoryRouter>,
    );
    await screen.findByText('Проект.pdf');
    expect(screen.getByText(files[1].name)).toBeInTheDocument();
    expect(container.querySelector('img')).toBeNull();
    await user.selectOptions(screen.getByLabelText('Сортировка файлов'), 'name');
    const list = screen.getByRole('list');
    expect(within(list).getAllByRole('listitem')).toHaveLength(2);
    await user.type(screen.getByRole('searchbox'), 'ПРОЕКТ');
    expect(within(list).getAllByRole('listitem')).toHaveLength(1);
    await user.clear(screen.getByRole('searchbox'));
    await user.type(screen.getByRole('searchbox'), 'нет-файла');
    expect(screen.getByText('Ничего не нашлось')).toBeInTheDocument();
    expect(api.files).toHaveBeenCalledTimes(1);
  });
  it('сетевая ошибка не превращается в пустое успешное состояние или logout', async () => {
    vi.mocked(api.files).mockRejectedValueOnce(new TypeError('offline'));
    render(
      <MemoryRouter>
        <FilesPage />
      </MemoryRouter>,
    );
    await screen.findByText('Не удалось получить файлы');
    expect(expire).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Обновить список' }));
    await screen.findByText('Проект.pdf');
  });
  it('401 завершает клиентскую сессию', async () => {
    vi.mocked(api.files).mockRejectedValueOnce(new HttpError(401, 'UNAUTHENTICATED', 'Требуется вход'));
    render(
      <MemoryRouter>
        <FilesPage />
      </MemoryRouter>,
    );
    await waitFor(() => expect(expire).toHaveBeenCalledOnce());
  });
  it('диалог валидирует файл и закрывается только после подтверждённого upload', async () => {
    const user = userEvent.setup();
    const close = vi.fn();
    const uploaded = vi.fn();
    vi.mocked(api.upload).mockResolvedValue(files[0]);
    render(<UploadDialog onClose={close} onUploaded={uploaded} />);
    await user.upload(screen.getByLabelText('Выберите файл'), new File([], 'empty.txt'));
    expect(screen.getByText('Выберите непустой файл размером до 10 МБ.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Загрузить' })).toBeDisabled();
    const file = new File(['data'], 'Проект.pdf');
    await user.upload(screen.getByLabelText('Выберите файл'), file);
    await user.click(screen.getByRole('button', { name: 'Загрузить' }));
    await waitFor(() => expect(uploaded).toHaveBeenCalledWith(files[0]));
    expect(close).toHaveBeenCalledOnce();
  });
  it('не закрывает диалог при неясном результате upload и не отправляет его повторно', async () => {
    vi.mocked(api.upload).mockRejectedValueOnce(new TypeError('offline'));
    const close = vi.fn();
    render(<UploadDialog onClose={close} onUploaded={vi.fn()} />);
    await userEvent.upload(screen.getByLabelText('Выберите файл'), new File(['data'], 'report.txt'));
    await userEvent.click(screen.getByRole('button', { name: 'Загрузить' }));
    await screen.findByText(/Перед повторной загрузкой проверьте список/);
    expect(api.upload).toHaveBeenCalledOnce();
    expect(close).not.toHaveBeenCalled();
  });
});
