import { useCallback, useEffect, useRef, useState } from 'react';
import { api, errorMessage, HttpError } from '../api/client';
import type { FileInfo } from '../api/types';

export function useFiles(expire: (reason?: string) => void, onLoad: (files: readonly FileInfo[]) => void) {
  const [files, setFiles] = useState<FileInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const request = useRef<AbortController | null>(null);

  const load = useCallback(() => {
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    return api
      .files(controller.signal)
      .then(
        (result) => {
          if (controller.signal.aborted) return;
          onLoad(result);
          setFiles(result);
          setError(null);
          return result;
        },
        (error_: unknown) => {
          if (controller.signal.aborted) return;
          if (error_ instanceof HttpError && error_.status === 401) expire(error_.code);
          else setError(errorMessage(error_));
          return undefined;
        },
      )
      .finally(() => {
        if (!controller.signal.aborted) {
          request.current = null;
          setLoading(false);
        }
      });
  }, [expire, onLoad]);

  useEffect(() => {
    void load();
    return () => request.current?.abort();
  }, [load]);

  function refresh(showLoading = true) {
    if (showLoading) setLoading(true);
    return load();
  }

  function addFile(file: FileInfo) {
    setFiles((current) => [file, ...current.filter((item) => item.id !== file.id)]);
    if (request.current) void load();
  }

  function replaceFile(file: FileInfo) {
    setFiles((current) => current.map((item) => (item.id === file.id ? file : item)));
    if (request.current) void load();
  }

  return { files, loading, error, setError, refresh, addFile, replaceFile };
}
