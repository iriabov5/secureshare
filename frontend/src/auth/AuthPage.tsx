import { useState } from 'react';
import type { SubmitEvent } from 'react';
import { ArrowRight, Check, Files, LockKeyhole, ShieldCheck } from 'lucide-react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { api, errorMessage } from '../api/client';
import { useAuth } from './AuthContext';
import { Brand } from '../ui/Brand';
import { Button } from '../ui/Button';
import { Field } from '../ui/Field';
import { Message } from '../ui/Feedback';

export function AuthPage({ mode }: Readonly<{ mode: 'login' | 'register' }>) {
  const registration = mode === 'register';
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const initial = location.state as { registered?: boolean; username?: string } | null;
  const [username, setUsername] = useState(initial?.username ?? '');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      if (registration) {
        await api.register({ username, password });
        navigate('/login', { replace: true, state: { registered: true, username } });
      } else {
        await login({ username, password });
        navigate('/files', { replace: true });
      }
    } catch (error_) {
      setError(errorMessage(error_));
    } finally {
      setBusy(false);
    }
  }
  return (
    <main className="auth-layout">
      <section className="auth-story">
        <Brand />
        <div className="auth-story__content">
          <span className="eyebrow eyebrow--light">ВАШЕ ЛИЧНОЕ ПРОСТРАНСТВО</span>
          <h1>
            Важные файлы.
            <br />
            <span>
              Под вашим
              <br />
              контролем.
            </span>
          </h1>
          <p>
            Соберите документы в одном месте.
            <br />
            Загружайте, находите и скачивайте —<br />
            доступ остаётся у вас.
          </p>
          <div className="auth-story__visual" aria-hidden="true">
            <div className="floating-file">
              <Files size={32} />
              <div>
                <i />
                <i />
              </div>
              <span>
                <Check size={14} />
              </span>
            </div>
            <div className="floating-shield">
              <ShieldCheck size={34} />
            </div>
          </div>
          <div className="auth-story__benefit">
            <LockKeyhole size={18} aria-hidden="true" />
            Приватный доступ к вашим файлам
          </div>
        </div>
        <span className="auth-story__footer">Простота снаружи. Защита внутри.</span>
      </section>
      <section className="auth-form-area">
        <div className="auth-mobile-brand">
          <Brand />
        </div>
        <div className="auth-card">
          <div className="auth-card__icon">
            <ShieldCheck size={25} aria-hidden="true" />
          </div>
          <span className="eyebrow">SECURESHARE</span>
          <h2>{registration ? 'Создайте своё пространство' : 'С возвращением'}</h2>
          <p className="auth-card__description">
            {registration
              ? 'Один аккаунт для ваших важных файлов.'
              : 'Войдите, чтобы продолжить работу с файлами.'}
          </p>
          {initial?.registered && !registration && (
            <Message tone="success">Аккаунт создан. Теперь можно войти.</Message>
          )}
          {error && <Message>{error}</Message>}
          <form
            onSubmit={(event) => {
              void submit(event);
            }}
            className="auth-form"
          >
            <Field
              id={`${mode}-username`}
              label="Логин"
              name="username"
              type="text"
              autoComplete="username"
              required
              minLength={3}
              maxLength={64}
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              placeholder="Ваш логин"
              hint={registration ? '3–64 латинских буквы, цифры или знак _' : undefined}
              disabled={busy}
            />
            <Field
              id={`${mode}-password`}
              label="Пароль"
              name="password"
              type="password"
              autoComplete={registration ? 'new-password' : 'current-password'}
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              placeholder={registration ? 'Придумайте надёжный пароль' : 'Введите пароль'}
              hint={registration ? '12–64 символа, не более 72 байт UTF-8' : undefined}
              disabled={busy}
            />
            <Button type="submit" busy={busy} className="auth-submit">
              {registration ? 'Создать аккаунт' : 'Войти'}
              <ArrowRight size={18} aria-hidden="true" />
            </Button>
          </form>
          <p className="auth-switch">
            {registration ? 'Уже есть аккаунт?' : 'Пока нет аккаунта?'}{' '}
            <Link to={registration ? '/login' : '/register'}>
              {registration ? 'Войти' : 'Зарегистрироваться'}
            </Link>
          </p>
          <div className="auth-note">
            <LockKeyhole size={14} aria-hidden="true" />
            {registration ? 'Файлы доступны только вашему аккаунту.' : 'Файлы доступны только после входа.'}
          </div>
        </div>
      </section>
    </main>
  );
}
