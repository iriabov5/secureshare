import { useCallback, useEffect, useRef, useState } from 'react';
import { api, HttpError } from '../api/client';
import type { FileInfo, FileIntegrityResult, StorageStatus } from '../api/types';

export type FileCheck = ({ status: 'pending' | 'failed' } | FileIntegrityResult) & {
  storageStatus: StorageStatus;
};

type Request = { controller: AbortController; storageStatus: StorageStatus };

export function useFileIntegrity(expire: (reason?: string) => void) {
  const [checks, setChecks] = useState(() => new Map<string, FileCheck>());
  const requests = useRef(new Map<string, Request>());

  useEffect(() => {
    const active = requests.current;
    return () => {
      for (const { controller } of active.values()) controller.abort();
      active.clear();
    };
  }, []);

  const reconcile = useCallback((files: readonly FileInfo[]) => {
    const formats = new Map(files.map(({ id, storageStatus }) => [id, storageStatus]));
    for (const [id, request] of requests.current) {
      if (formats.get(id) === request.storageStatus) continue;
      request.controller.abort();
      requests.current.delete(id);
    }
    setChecks(
      (current) => new Map([...current].filter(([id, check]) => formats.get(id) === check.storageStatus)),
    );
  }, []);

  function discard(id: string) {
    requests.current.get(id)?.controller.abort();
    requests.current.delete(id);
    setChecks((current) => {
      const next = new Map(current);
      next.delete(id);
      return next;
    });
  }

  async function check({ id, storageStatus }: FileInfo) {
    if (requests.current.has(id)) return;
    const controller = new AbortController();
    requests.current.set(id, { controller, storageStatus });

    function setResult(result: FileCheck) {
      setChecks((current) => new Map(current).set(id, result));
    }

    setResult({ status: 'pending', storageStatus });
    try {
      const result = await api.checkIntegrity(id, controller.signal);
      if (!controller.signal.aborted) setResult({ ...result, storageStatus });
    } catch (error) {
      if (controller.signal.aborted) return;
      if (error instanceof HttpError && error.status === 401) expire(error.code);
      else setResult({ status: 'failed', storageStatus });
    } finally {
      if (!controller.signal.aborted) requests.current.delete(id);
    }
  }

  return { checks, check, reconcile, discard };
}
