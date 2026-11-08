import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { FilesPage } from '../src/files/FilesPage';
import { UploadDialog } from '../src/files/UploadDialog';
import type { FileInfo, FileIntegrityResult } from '../src/api/types';
import { api, HttpError } from '../src/api/client';
const expire = vi.fn();
const logout = vi.fn();
vi.mock('../src/auth/AuthContext', () => ({
  useAuth: () => ({ user: { username: 'alice' }, expire, logout }),
}));
vi.mock('../src/api/client', async (original) => ({
  ...(await original<typeof import('../src/api/client')>()),
  api: { files: vi.fn(), upload: vi.fn(), download: vi.fn(), checkIntegrity: vi.fn(), encryptFile: vi.fn() },
}));
const files: FileInfo[] = [
  {
    id: '1',
    name: 'Проект.pdf',
    sizeBytes: 2048,
    sha256: '',
    createdAt: '2026-11-04T12:00:00Z',
    storageStatus: 'ENCRYPTED',
    legacyCleanupPending: false,
  },
  {
    id: '2',
    name: '<img src=x onerror=alert(1)>.txt',
    sizeBytes: 512,
    sha256: '',
    storageStatus: 'PENDING_MIGRATION',
    legacyCleanupPending: false,
    createdAt: '2026-11-05T12:00:00Z',
  },
];
const verified: FileIntegrityResult = { status: 'VERIFIED', checkedAt: '2026-11-08T09:15:00Z' };

function openFiles() {
  return render(
    <MemoryRouter>
      <FilesPage />
    </MemoryRouter>,
  );
}

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
    expect(api.checkIntegrity).not.toHaveBeenCalled();
  });
  it('различает статус хранения и ручную проверку, сбрасывает успех при повторе и показывает отказ', async () => {
    const user = userEvent.setup();
    openFiles();
    expect(await screen.findByText('Зашифрован')).toBeVisible();
    expect(screen.getByText('Ожидает переноса')).toBeVisible();
    const button = screen.getByRole('button', { name: 'Проверить файл Проект.pdf' });
    let finish!: (result: FileIntegrityResult) => void;
    vi.mocked(api.checkIntegrity).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );

    await user.dblClick(button);
    expect(api.checkIntegrity).toHaveBeenCalledOnce();
    expect(button).toBeDisabled();
    expect(screen.queryByText('Целостность подтверждена')).not.toBeInTheDocument();
    await act(async () => finish(verified));
    expect(await screen.findByText('Целостность подтверждена')).toBeVisible();
    expect(document.querySelector(`time[datetime="${verified.checkedAt}"]`)).toBeVisible();
    await user.type(screen.getByRole('searchbox'), 'нет-файла');
    await user.clear(screen.getByRole('searchbox'));
    expect(screen.getByText('Целостность подтверждена')).toBeVisible();
    expect(api.checkIntegrity).toHaveBeenCalledOnce();

    vi.mocked(api.checkIntegrity).mockRejectedValueOnce(
      new HttpError(503, 'FILE_SERVICE_UNAVAILABLE', 'Недоступно'),
    );
    await user.click(screen.getByRole('button', { name: 'Проверить файл Проект.pdf' }));
    expect(await screen.findByText('Не удалось проверить файл')).toBeVisible();
    expect(screen.queryByText('Целостность подтверждена')).not.toBeInTheDocument();
  });

  it('отбрасывает поздний ответ после обновления формата или ухода со страницы', async () => {
    const user = userEvent.setup();
    const { unmount } = openFiles();
    await screen.findByText('Ожидает переноса');
    const legacyButton = () => screen.getByRole('button', { name: `Проверить файл ${files[1].name}` });
    let finish!: (result: FileIntegrityResult) => void;
    vi.mocked(api.checkIntegrity).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    await user.click(legacyButton());

    const migrated: FileInfo[] = [files[0], { ...files[1], storageStatus: 'ENCRYPTED' }];
    // Ошибка скачивания открывает существующее действие обновления списка.
    vi.mocked(api.download).mockRejectedValueOnce(new TypeError('offline'));
    await user.click(screen.getByRole('button', { name: 'Скачать Проект.pdf' }));
    vi.mocked(api.files).mockResolvedValueOnce(migrated);
    await user.click(await screen.findByRole('button', { name: 'Обновить список' }));
    expect(await screen.findAllByText('Зашифрован')).toHaveLength(2);
    expect(vi.mocked(api.checkIntegrity).mock.calls[0][1].aborted).toBe(true);
    await act(async () => finish(verified));
    expect(screen.queryByText('Целостность подтверждена')).not.toBeInTheDocument();

    vi.mocked(api.checkIntegrity).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    await user.click(legacyButton());
    unmount();
    expect(vi.mocked(api.checkIntegrity).mock.calls[1][1].aborted).toBe(true);
    openFiles();
    await act(async () => finish(verified));
    await screen.findByText('Проект.pdf');
    expect(screen.queryByText('Целостность подтверждена')).not.toBeInTheDocument();
  });

  it('при истёкшем сеансе запускает повторный вход', async () => {
    vi.mocked(api.checkIntegrity).mockRejectedValueOnce(
      new HttpError(401, 'SESSION_EXPIRED', 'Сеанс завершён'),
    );
    openFiles();
    await userEvent.click(await screen.findByRole('button', { name: 'Проверить файл Проект.pdf' }));
    expect(expire).toHaveBeenCalledWith('SESSION_EXPIRED');
    expect(screen.queryByText('Целостность подтверждена')).not.toBeInTheDocument();
  });

  it('шифрует одну строку и убирает результат проверки старого формата', async () => {
    const user = userEvent.setup();
    const legacy = files[1];
    const encrypted: FileInfo = { ...legacy, storageStatus: 'ENCRYPTED' };
    let resolve!: (file: FileInfo) => void;
    const promise = new Promise<FileInfo>((done) => {
      resolve = done;
    });
    vi.mocked(api.encryptFile).mockReturnValueOnce(promise);
    vi.mocked(api.checkIntegrity).mockResolvedValueOnce(verified);
    openFiles();
    await user.click(await screen.findByRole('button', { name: `Проверить файл ${legacy.name}` }));
    expect(await screen.findByText('Целостность подтверждена')).toBeVisible();
    const button = screen.getByRole('button', { name: `Зашифровать файл ${legacy.name}` });

    await user.dblClick(button);
    expect(button).toBeDisabled();
    expect(api.encryptFile).toHaveBeenCalledOnce();
    await act(async () => resolve(encrypted));

    expect(await screen.findAllByText('Зашифрован')).toHaveLength(2);
    expect(screen.queryByText('Ожидает переноса')).not.toBeInTheDocument();
    expect(screen.queryByText('Целостность подтверждена')).not.toBeInTheDocument();
    expect(api.files).toHaveBeenCalledOnce();
  });

  it('после сбоя показывает оставшуюся копию, а после потери ответа сверяет результат со списком', async () => {
    const user = userEvent.setup();
    const legacy = files[1];
    const encrypted: FileInfo = { ...legacy, storageStatus: 'ENCRYPTED' };
    const pending: FileInfo = { ...encrypted, legacyCleanupPending: true };
    openFiles();
    const button = await screen.findByRole('button', { name: `Зашифровать файл ${legacy.name}` });
    vi.mocked(api.encryptFile).mockRejectedValueOnce(
      new HttpError(503, 'FILE_SERVICE_UNAVAILABLE', 'Недоступно'),
    );
    vi.mocked(api.files).mockResolvedValueOnce([files[0], pending]);

    await user.click(button);
    expect(await screen.findByText('Старая копия ожидает удаления')).toBeVisible();
    expect(screen.getByText('Не удалось завершить операцию')).toBeVisible();

    vi.mocked(api.encryptFile).mockRejectedValueOnce(new TypeError('network'));
    vi.mocked(api.files).mockResolvedValueOnce([files[0], encrypted]);
    await user.click(screen.getByRole('button', { name: `Повторить очистку ${legacy.name}` }));

    await waitFor(() => expect(screen.queryByText('Старая копия ожидает удаления')).not.toBeInTheDocument());
    expect(screen.queryByText('Не удалось завершить операцию')).not.toBeInTheDocument();
    expect(screen.getAllByText('Зашифрован')).toHaveLength(2);
  });

  it('игнорирует ответ шифрования после ухода и при 401 отправляет к повторному входу', async () => {
    const user = userEvent.setup();
    const legacy = files[1];
    let reject!: (reason: HttpError) => void;
    const promise = new Promise<FileInfo>((_, fail) => {
      reject = fail;
    });
    vi.mocked(api.encryptFile).mockReturnValueOnce(promise);
    const { unmount } = openFiles();
    const button = () => screen.findByRole('button', { name: `Зашифровать файл ${legacy.name}` });

    await user.click(await button());
    unmount();
    expect(vi.mocked(api.encryptFile).mock.calls[0][1].aborted).toBe(true);
    await act(async () => reject(new HttpError(401, 'SESSION_EXPIRED', 'Сеанс завершён')));
    expect(expire).not.toHaveBeenCalled();

    vi.mocked(api.encryptFile).mockRejectedValueOnce(new HttpError(401, 'SESSION_EXPIRED', 'Сеанс завершён'));
    openFiles();
    await user.click(await button());
    expect(expire).toHaveBeenCalledWith('SESSION_EXPIRED');
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
