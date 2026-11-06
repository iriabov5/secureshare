import { ArrowDownToLine, FileText, Files, Plus, Search } from 'lucide-react';
import type { FileInfo } from '../api/types';
import { Button } from '../ui/Button';
import { formatBytes, formatDate } from './format';

type Props = Readonly<{
  loading: boolean;
  files: readonly FileInfo[];
  search: string;
  error: string | null;
  downloading: string | null;
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
export function FileList({ loading, files, search, error, downloading, onDownload, onUpload }: Props) {
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
        </li>
      ))}
    </ul>
  );
}
