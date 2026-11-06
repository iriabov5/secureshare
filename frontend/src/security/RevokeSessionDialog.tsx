import { useEffect, useRef } from 'react';
import { ShieldAlert } from 'lucide-react';
import { Button } from '../ui/Button';
import { Message } from '../ui/Feedback';

export function RevokeSessionDialog({
  busy,
  error,
  onClose,
  onConfirm,
}: Readonly<{
  busy: boolean;
  error: string | null;
  onClose(): void;
  onConfirm(): void;
}>) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const element = dialog.current!;
    element.showModal();
    return () => element.close();
  }, []);
  return (
    <dialog
      ref={dialog}
      className="upload-dialog session-dialog"
      aria-labelledby="revoke-title"
      onCancel={(event) => {
        event.preventDefault();
        if (!busy) onClose();
      }}
    >
      <ShieldAlert size={30} className="session-emblem" />
      <h2 id="revoke-title">Завершить сеанс?</h2>
      <p>Для доступа к файлам в этом сеансе потребуется войти снова. Текущий сеанс продолжит работать.</p>
      {error && <Message>{error}</Message>}
      <div className="session-dialog-actions">
        <Button variant="secondary" disabled={busy} onClick={onClose}>
          Отмена
        </Button>
        <Button busy={busy} onClick={onConfirm}>
          Завершить сеанс
        </Button>
      </div>
    </dialog>
  );
}
