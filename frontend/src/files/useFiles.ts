import { useCallback, useEffect, useState } from 'react';
import { api, errorMessage, HttpError } from '../api/client';
import type { FileInfo } from '../api/types';

export function useFiles(expire: () => void) {
  const [files, setFiles] = useState<FileInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(
    (signal?: AbortSignal) =>
      api
        .files(signal)
        .then(
          (result) => {
            if (!signal?.aborted) {
              setFiles(result);
              setError(null);
            }
          },
          (error_: unknown) => {
            if (signal?.aborted) return;
            if (error_ instanceof HttpError && error_.status === 401) expire();
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
  function refresh() {
    setLoading(true);
    void load();
  }
  function addFile(file: FileInfo) {
    setFiles((current) => [file, ...current.filter((item) => item.id !== file.id)]);
  }
  return { files, loading, error, setError, refresh, addFile };
}
