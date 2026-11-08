import { useMemo, useState } from 'react';
import { Check, Files, HardDrive, Plus, Search, ShieldCheck, UploadCloud } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { WorkspaceLayout } from '../ui/WorkspaceLayout';
import { Button } from '../ui/Button';
import { Message } from '../ui/Feedback';
import { UploadDialog } from './UploadDialog';
import { useFiles } from './useFiles';
import { useFileIntegrity } from './useFileIntegrity';
import { useFileEncryption } from './useFileEncryption';
import { formatBytes } from './format';
import { useFileActions } from './useFileActions';
import { FileList } from './FileList';
import { fileComparator } from './sort';

export function FilesPage() {
  const { expire } = useAuth();
  const { checks, check, reconcile, discard } = useFileIntegrity(expire);
  const { files, loading, error, setError, refresh, addFile, replaceFile } = useFiles(expire, reconcile);
  const { encryptions, encrypt } = useFileEncryption(
    expire,
    (file, updated) => {
      if (file.storageStatus !== updated.storageStatus) discard(file.id);
      replaceFile(updated);
    },
    () => refresh(false),
  );
  const [notice, setNotice] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [sort, setSort] = useState('newest');
  const [uploadOpen, setUploadOpen] = useState(false);
  const { downloading, leaving, download, signOut } = useFileActions(setError);
  const visible = useMemo(
    () =>
      files
        .filter((file) => file.name.toLocaleLowerCase('ru').includes(search.toLocaleLowerCase('ru')))
        .sort(fileComparator(sort)),
    [files, search, sort],
  );
  return (
    <WorkspaceLayout
      title="Файлы"
      filesCount={files.length}
      leaving={leaving}
      onSignOut={() => void signOut()}
    >
      <main id="main-content" className="files-main">
        <div className="page-heading">
          <div>
            <span className="eyebrow">ВСЁ В ОДНОМ МЕСТЕ</span>
            <h1>
              Мои файлы<span className="heading-dot">.</span>
            </h1>
            <p>Загружайте, находите и скачивайте документы.</p>
          </div>
          <Button
            onClick={() => {
              setNotice(null);
              setUploadOpen(true);
            }}
          >
            <Plus size={19} />
            Загрузить файл
          </Button>
        </div>
        <section className="stats" aria-label="Сводка файлов">
          <article className="stat">
            <span className="stat-icon stat-icon--blue">
              <Files size={22} />
            </span>
            <div>
              <span>Всего файлов</span>
              <strong>{loading || (error && files.length === 0) ? '—' : files.length}</strong>
            </div>
          </article>
          <article className="stat">
            <span className="stat-icon stat-icon--purple">
              <HardDrive size={22} />
            </span>
            <div>
              <span>Объём файлов</span>
              <strong>
                {loading || (error && files.length === 0)
                  ? '—'
                  : formatBytes(files.reduce((sum, file) => sum + file.sizeBytes, 0))}
              </strong>
            </div>
          </article>
          <article className="stat stat--limit">
            <span className="stat-icon stat-icon--green">
              <UploadCloud size={22} />
            </span>
            <div>
              <span>Максимум на файл</span>
              <strong>10 МБ</strong>
            </div>
          </article>
        </section>
        {notice && <Message tone="success">{notice}</Message>}
        {error && (
          <Message>
            {error}{' '}
            <button
              type="button"
              className="text-button"
              onClick={() => {
                refresh();
              }}
            >
              Обновить список
            </button>
          </Message>
        )}
        <section className="files-panel" aria-labelledby="file-list-title">
          <div className="panel-heading">
            <div>
              <h2 id="file-list-title">
                Все файлы <span>{files.length}</span>
              </h2>
              <p>Доступны только вам</p>
            </div>
            <div className="list-controls">
              <label className="search">
                <Search size={18} aria-hidden="true" />
                <input
                  type="search"
                  aria-label="Найти файл"
                  placeholder="Найти файл…"
                  value={search}
                  onChange={(event) => setSearch(event.target.value)}
                />
              </label>
              <select
                aria-label="Сортировка файлов"
                value={sort}
                onChange={(event) => setSort(event.target.value)}
              >
                <option value="newest">Сначала новые</option>
                <option value="name">По названию</option>
                <option value="size">По размеру</option>
              </select>
            </div>
          </div>
          <div className="file-table">
            <div className="file-table-heading" aria-hidden="true">
              <span>Название</span>
              <span>Размер</span>
              <span>Дата загрузки</span>
              <span />
            </div>
            <FileList
              loading={loading}
              files={visible}
              search={search}
              error={error}
              checks={checks}
              onCheck={check}
              encryptions={encryptions}
              onEncrypt={encrypt}
              downloading={downloading}
              onDownload={download}
              onUpload={() => setUploadOpen(true)}
            />
          </div>
          <footer className="panel-footer">
            <span>{loading ? 'Загружаем…' : `Показано ${visible.length} из ${files.length}`}</span>
            <span>
              <Check size={14} />
              Только ваши файлы
            </span>
          </footer>
        </section>
        <p className="workspace-note">
          <ShieldCheck size={15} />
          Файлы доступны только вашему аккаунту.
        </p>
      </main>
      {uploadOpen && (
        <UploadDialog
          onClose={() => setUploadOpen(false)}
          onUploaded={(file) => {
            addFile(file);
            setNotice(`Файл «${file.name}» загружен.`);
          }}
        />
      )}
    </WorkspaceLayout>
  );
}
