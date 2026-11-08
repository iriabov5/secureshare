import {
  ArrowDownToLine,
  CircleAlert,
  FileText,
  Files,
  LockKeyhole,
  Plus,
  Search,
  ShieldCheck,
} from 'lucide-react';
import type { FileInfo } from '../api/types';
import { Button } from '../ui/Button';
import { formatBytes, formatDate } from './format';
import type { FileCheck } from './useFileIntegrity';
import type { FileEncryptionState } from './useFileEncryption';

type Props = Readonly<{
  loading: boolean;
  files: readonly FileInfo[];
  search: string;
  error: string | null;
  downloading: string | null;
  checks: ReadonlyMap<string, FileCheck>;
  onCheck(file: FileInfo): Promise<void>;
  encryptions: ReadonlyMap<string, FileEncryptionState>;
  onEncrypt(file: FileInfo): Promise<void>;
  onDownload(file: FileInfo): Promise<void>;
  onUpload(): void;
}>;
function emptyCopy(search: string, error: string | null) {
  if (search) return { title: 'Ничего не нашлось', description: 'Попробуйте другое название файла.' };
  if (error)
    return {
      title: 'Не удалось получить файлы',
      description: 'Повторите запрос, когда соединение восстановится.',
    };
  return { title: 'Место для важных вещей', description: 'Загрузите первый файл — и он появится здесь.' };
}
function extension(name: string): string {
  if (!name.includes('.')) return 'ФАЙЛ';
  return name
    .slice(name.lastIndexOf('.') + 1)
    .toUpperCase()
    .slice(0, 12);
}
const checkTime = new Intl.DateTimeFormat('ru', { dateStyle: 'short', timeStyle: 'medium' });

function FileIntegrity({
  file,
  check,
  onCheck,
  encryption,
  onEncrypt,
}: Readonly<{
  file: FileInfo;
  check: FileCheck | undefined;
  onCheck(file: FileInfo): Promise<void>;
  encryption: FileEncryptionState | undefined;
  onEncrypt(file: FileInfo): Promise<void>;
}>) {
  const encrypted = file.storageStatus === 'ENCRYPTED';
  const canEncrypt = !encrypted || file.legacyCleanupPending;
  const encryptionLabel = file.legacyCleanupPending ? 'Повторить очистку' : 'Зашифровать файл';
  const pendingLabel = file.legacyCleanupPending ? 'Очищаем…' : 'Шифруем…';
  return (
    <div className="file-integrity">
      <span
        className={`storage-badge ${encrypted ? 'storage-badge--encrypted' : ''}`}
        title={encrypted ? undefined : 'Файл можно зашифровать на месте'}
      >
        <LockKeyhole size={13} aria-hidden="true" />
        {encrypted ? 'Зашифрован' : 'Ожидает переноса'}
      </span>
      <Button
        variant="secondary"
        aria-label={`Проверить файл ${file.name}`}
        busy={check?.status === 'pending'}
        onClick={() => void onCheck(file)}
      >
        {check?.status === 'pending' ? 'Проверяем…' : 'Проверить файл'}
      </Button>
      {canEncrypt && (
        <Button
          variant="secondary"
          title={file.legacyCleanupPending ? undefined : 'Файл останется в списке под тем же именем'}
          aria-label={`${encryptionLabel} ${file.name}`}
          busy={encryption === 'pending'}
          onClick={() => void onEncrypt(file)}
        >
          {encryption === 'pending' ? pendingLabel : encryptionLabel}
        </Button>
      )}
      {canEncrypt && (file.legacyCleanupPending || encryption === 'failed' || encryption === 'busy') && (
        <output className="file-encryption-notice">
          {file.legacyCleanupPending && <span>Старая копия ожидает удаления</span>}
          {encryption === 'failed' && <span>Не удалось завершить операцию</span>}
          {encryption === 'busy' && <span>Файл уже обрабатывается</span>}
        </output>
      )}
      <output className="file-check-result">
        {check?.status === 'VERIFIED' && (
          <span className="file-check-result--success">
            <ShieldCheck size={15} aria-hidden="true" />
            Целостность подтверждена
            <time dateTime={check.checkedAt}>{checkTime.format(new Date(check.checkedAt))}</time>
          </span>
        )}
        {check?.status === 'failed' && (
          <span className="file-check-result--error">
            <CircleAlert size={15} aria-hidden="true" />
            Не удалось проверить файл
          </span>
        )}
      </output>
    </div>
  );
}

export function FileList({
  loading,
  files,
  search,
  error,
  downloading,
  checks,
  onCheck,
  encryptions,
  onEncrypt,
  onDownload,
  onUpload,
}: Props) {
  if (loading)
    return (
      <div className="file-skeleton" aria-label="Загружаем файлы">
        <i />
        <i />
        <i />
      </div>
    );
  if (files.length === 0) {
    const copy = emptyCopy(search, error);
    return (
      <div className="empty-state">
        <span className="empty-icon">{search ? <Search size={32} /> : <Files size={34} />}</span>
        <h3>{copy.title}</h3>
        <p>{copy.description}</p>
        {!search && !error && (
          <Button variant="secondary" onClick={onUpload}>
            <Plus size={17} />
            Добавить первый файл
          </Button>
        )}
      </div>
    );
  }
  return (
    <ul className="file-list">
      {files.map((file) => (
        <li className="file-row" key={file.id}>
          <div className="file-name">
            <span className="file-icon">
              <FileText size={23} aria-hidden="true" />
            </span>
            <div>
              <strong title={file.name}>{file.name}</strong>
              <small>{extension(file.name)}</small>
            </div>
          </div>
          <span className="file-size">{formatBytes(file.sizeBytes)}</span>
          <time dateTime={file.createdAt}>{formatDate(file.createdAt)}</time>
          <Button
            variant="ghost"
            aria-label={`Скачать ${file.name}`}
            title="Скачать"
            busy={downloading === file.id}
            disabled={downloading !== null}
            onClick={() => {
              void onDownload(file);
            }}
          >
            {downloading !== file.id && <ArrowDownToLine size={19} />}
          </Button>
          <FileIntegrity
            file={file}
            check={checks.get(file.id)}
            onCheck={onCheck}
            encryption={encryptions.get(file.id)}
            onEncrypt={onEncrypt}
          />
        </li>
      ))}
    </ul>
  );
}
