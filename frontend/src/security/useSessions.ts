import { useCallback, useEffect, useState } from 'react';
import { api, errorMessage, HttpError } from '../api/client';
import type { ActiveSessionList } from '../api/types';
import { useAuth } from '../auth/AuthContext';

export function useSessions() {
  const { expire } = useAuth();
  const [data, setData] = useState<ActiveSessionList | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [target, setTarget] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [dialogError, setDialogError] = useState<string | null>(null);
  const load = useCallback(
    (signal?: AbortSignal) =>
      api
        .sessions(signal)
        .then(
          (result) => {
            if (!signal?.aborted) {
              setData(result);
              setError(null);
            }
          },
          (error_: unknown) => {
            if (signal?.aborted) return;
            if (error_ instanceof HttpError && error_.status === 401) expire(error_.code);
            else setError(errorMessage(error_));
          },
        )
        .finally(() => {
          if (!signal?.aborted) setLoading(false);
        }),
    [expire],
  );
  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);
  async function revoke() {
    if (!target || busy) return;
    setBusy(true);
    setDialogError(null);
    try {
      await api.revokeSession(target);
      setTarget(null);
      setNotice('Сеанс завершён.');
      setLoading(true);
      await load();
    } catch (error_) {
      if (error_ instanceof HttpError && error_.status === 401) expire(error_.code);
      else if (error_ instanceof HttpError && error_.status === 404) {
        setTarget(null);
        setNotice('Этот сеанс уже завершён.');
        setLoading(true);
        await load();
      } else setDialogError(errorMessage(error_) + ' Обновите список перед повторной попыткой.');
    } finally {
      setBusy(false);
    }
  }
  function refresh() {
    setLoading(true);
    void load();
  }
  function choose(id: string) {
    setDialogError(null);
    setNotice(null);
    setTarget(id);
  }
  return {
    data,
    loading,
    error,
    setError,
    notice,
    target,
    busy,
    dialogError,
    load,
    refresh,
    revoke,
    choose,
    close: () => setTarget(null),
  };
}
