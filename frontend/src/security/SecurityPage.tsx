import { RefreshCw, ShieldCheck } from 'lucide-react';
import { Button } from '../ui/Button';
import { Message } from '../ui/Feedback';
import { WorkspaceLayout } from '../ui/WorkspaceLayout';
import { useFileActions } from '../files/useFileActions';
import { useSessions } from './useSessions';
import { RevokeSessionDialog } from './RevokeSessionDialog';

const date = (value: string) =>
  new Intl.DateTimeFormat('ru', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));

export function SecurityPage() {
  const sessions = useSessions();
  const { leaving, signOut } = useFileActions(sessions.setError);
  return (
    <WorkspaceLayout title="Безопасность" leaving={leaving} onSignOut={() => void signOut()}>
      <main id="main-content" className="files-main security-main">
        <div className="page-heading">
          <div>
            <span className="eyebrow">КОНТРОЛЬ ДОСТУПА</span>
            <h1>
              Безопасность<span className="heading-dot">.</span>
            </h1>
            <p>Управляйте активными сеансами своего аккаунта.</p>
          </div>
          <Button variant="secondary" busy={sessions.loading} onClick={sessions.refresh}>
            <RefreshCw size={18} />
            Обновить
          </Button>
        </div>
        <section className="session-summary">
          <span className="stat-icon stat-icon--blue">
            <ShieldCheck size={24} />
          </span>
          <div>
            <h2>Активные сеансы</h2>
            <p>
              {sessions.data
                ? `${sessions.data.sessions.length} из ${sessions.data.maxSessions} доступных`
                : 'Загружаем данные…'}
            </p>
          </div>
        </section>
        <p className="session-explanation">
          При новом входе сверх лимита завершается сеанс с самой давней активностью. Вкладки одного браузера
          могут использовать общий сеанс.
        </p>
        {sessions.error && <Message>{sessions.error}</Message>}
        {sessions.notice && <Message tone="success">{sessions.notice}</Message>}
        <section className="files-panel" aria-label="Активные сеансы" aria-busy={sessions.loading}>
          {sessions.loading && (
            <output className="session-loading" aria-live="polite">
              Обновляем сеансы…
            </output>
          )}
          <ul className="session-list">
            {sessions.data?.sessions.map((session) => (
              <li key={session.id} className="session-row">
                <span className="session-icon">
                  <ShieldCheck size={22} />
                </span>
                <div className="session-details">
                  <h3>
                    {session.current ? 'Текущий сеанс' : 'Другой сеанс'}
                    {session.current && <span className="session-badge">Это вы</span>}
                  </h3>
                  <p>
                    Вход: <time dateTime={session.createdAt}>{date(session.createdAt)}</time>
                  </p>
                  <p>
                    Активность: <time dateTime={session.lastSeenAt}>{date(session.lastSeenAt)}</time>
                  </p>
                </div>
                {!session.current && (
                  <Button
                    variant="secondary"
                    disabled={sessions.loading || sessions.busy}
                    onClick={() => sessions.choose(session.id)}
                  >
                    Завершить
                  </Button>
                )}
              </li>
            ))}
          </ul>
        </section>
        <p className="workspace-note">
          <ShieldCheck size={15} />
          Для завершения текущего сеанса используйте выход из аккаунта.
        </p>
      </main>
      {sessions.target && (
        <RevokeSessionDialog
          busy={sessions.busy}
          error={sessions.dialogError}
          onClose={sessions.close}
          onConfirm={() => void sessions.revoke()}
        />
      )}
    </WorkspaceLayout>
  );
}
