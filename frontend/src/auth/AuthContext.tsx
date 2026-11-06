import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { api, csrf, errorMessage, HttpError, resetCsrf } from '../api/client';
import type { Credentials, User } from '../api/types';

type Session = { user: User | null; status: 'loading' | 'ready' | 'error'; error: string | null };
interface AuthValue extends Session {
  login(credentials: Credentials): Promise<void>;
  logout(): Promise<void>;
  expire(reason?: string): void;
  expired: boolean;
  retry(): void;
}
const AuthContext = createContext<AuthValue | null>(null);

export function AuthProvider({ children }: Readonly<{ children: ReactNode }>) {
  const [expired, setExpired] = useState(false);
  const [session, setSession] = useState<Session>({ user: null, status: 'loading', error: null });
  const bootstrap = useCallback(
    (signal?: AbortSignal) =>
      api.me(signal).then(
        (user) => {
          if (!signal?.aborted) setSession({ user, status: 'ready', error: null });
        },
        (error: unknown) => {
          if (signal?.aborted) return;
          if (error instanceof HttpError && error.status === 401) {
            resetCsrf();
            setExpired(error.code === 'SESSION_EXPIRED');
            setSession({ user: null, status: 'ready', error: null });
          } else setSession({ user: null, status: 'error', error: errorMessage(error) });
        },
      ),
    [],
  );
  useEffect(() => {
    const controller = new AbortController();
    void bootstrap(controller.signal);
    void csrf().catch(() => undefined);
    return () => controller.abort();
  }, [bootstrap]);
  const login = useCallback(async (credentials: Credentials) => {
    const user = await api.login(credentials);
    setExpired(false);
    setSession({ user, status: 'ready', error: null });
  }, []);
  const logout = useCallback(async () => {
    await api.logout();
    setExpired(false);
    setSession({ user: null, status: 'ready', error: null });
  }, []);
  const expire = useCallback((reason?: string) => {
    resetCsrf();
    setExpired(reason === 'SESSION_EXPIRED');
    setSession({ user: null, status: 'ready', error: null });
  }, []);
  const retry = useCallback(() => {
    setSession({ user: null, status: 'loading', error: null });
    void bootstrap();
  }, [bootstrap]);
  const value = useMemo(
    () => ({ ...session, expired, login, logout, expire, retry }),
    [session, expired, login, logout, expire, retry],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
export function useAuth(): AuthValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error('AuthProvider is required');
  return value;
}
