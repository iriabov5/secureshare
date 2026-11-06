import { useEffect, useRef, useState } from 'react';
import { FileUp, FileText, X } from 'lucide-react';
import { api, errorMessage, HttpError } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import type { FileInfo } from '../api/types';
import { Button } from '../ui/Button';
import { Message } from '../ui/Feedback';
import { formatBytes } from './format';

export function UploadDialog({
  onClose,
  onUploaded,
}: Readonly<{ onClose(): void; onUploaded(file: FileInfo): void }>) {
  const { expire } = useAuth();
  const dialog = useRef<HTMLDialogElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  useEffect(() => {
    const element = dialog.current!;
    element.showModal();
    return () => element.close();
  }, []);
  function select(candidate?: File) {
    setError(null);
    setFile(null);
    if (!candidate) return;
    if (candidate.size === 0 || candidate.size > 10 * 1024 ** 2) {
      setError('Выберите непустой файл размером до 10 МБ.');
      return;
    }
    setFile(candidate);
  }
  async function upload() {
    if (!file || busy) return;
    setBusy(true);
    setError(null);
    try {
      const saved = await api.upload(file);
      onUploaded(saved);
      onClose();
    } catch (error_) {
      if (error_ instanceof HttpError && error_.status === 401) expire();
      else setError(errorMessage(error_) + ' Перед повторной загрузкой проверьте список файлов.');
    } finally {
      setBusy(false);
    }
  }
  return (
    <dialog
      ref={dialog}
      className="upload-dialog"
      aria-labelledby="upload-title"
      onCancel={(event) => {
        event.preventDefault();
        if (!busy) onClose();
      }}
    >
      <div className="dialog-heading">
        <div className="dialog-icon">
          <FileUp size={25} />
        </div>
        <Button variant="ghost" aria-label="Закрыть загрузку" disabled={busy} onClick={onClose}>
          <X size={20} />
        </Button>
      </div>
      <h2 id="upload-title">Загрузить файл</h2>
      <p className="muted">Добавьте документ в своё пространство.</p>
      <input
        ref={input}
        type="file"
        className="visually-hidden"
        tabIndex={-1}
        aria-label="Выберите файл"
        disabled={busy}
        onChange={(event) => select(event.target.files?.[0])}
      />
      <button
        type="button"
        className={`dropzone ${dragging ? 'dropzone--active' : ''}`}
        disabled={busy}
        onClick={() => input.current?.click()}
        onDragOver={(event) => {
          event.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(event) => {
          event.preventDefault();
          setDragging(false);
          if (!busy) {
            if (event.dataTransfer.files.length > 1) {
              setFile(null);
              setError('Загружайте по одному файлу.');
            } else select(event.dataTransfer.files[0]);
          }
        }}
      >
        <span className="dropzone-icon">
          <FileUp size={27} />
        </span>
        <strong>Выберите файл или перетащите сюда</strong>
        <span>Любой формат · до 10 МБ</span>
      </button>
      {file && (
        <div className="selected-file">
          <FileText size={24} />
          <span>
            <strong>{file.name}</strong>
            <small>{formatBytes(file.size)}</small>
          </span>
        </div>
      )}
      {error && <Message>{error}</Message>}
      {busy && <output className="upload-progress">Файл загружается. Не закрывайте страницу…</output>}
      <div className="dialog-actions">
        <Button variant="secondary" onClick={onClose} disabled={busy}>
          Отмена
        </Button>
        <Button
          disabled={!file}
          busy={busy}
          onClick={() => {
            void upload();
          }}
        >
          Загрузить
        </Button>
      </div>
    </dialog>
  );
}
