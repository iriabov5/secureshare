import { useEffect, useRef, useState } from 'react';
import { api, HttpError } from '../api/client';
import type { FileInfo } from '../api/types';

export type FileEncryptionState = 'pending' | 'failed' | 'busy';

export function useFileEncryption(
  expire: (reason?: string) => void,
  onEncrypted: (file: FileInfo, updated: FileInfo) => void,
  refresh: () => Promise<FileInfo[] | undefined>,
) {
  const [encryptions, setEncryptions] = useState(() => new Map<string, FileEncryptionState>());
  const requests = useRef(new Map<string, AbortController>());

  useEffect(() => {
    const active = requests.current;
    return () => {
      for (const controller of active.values()) controller.abort();
      active.clear();
    };
  }, []);

  function setResult(id: string, result?: FileEncryptionState) {
    setEncryptions((current) => {
      const next = new Map(current);
      if (result) next.set(id, result);
      else next.delete(id);
      return next;
    });
  }

  async function reconcileError(id: string, signal: AbortSignal, error_: unknown) {
    if (signal.aborted) return;
    if (error_ instanceof HttpError && error_.status === 401) {
      expire(error_.code);
      return;
    }
    const current = (await refresh())?.find((item) => item.id === id);
    if (signal.aborted) return;
    if (current?.storageStatus === 'ENCRYPTED' && !current.legacyCleanupPending) setResult(id);
    else setResult(id, error_ instanceof HttpError && error_.status === 409 ? 'busy' : 'failed');
  }

  async function encrypt(file: FileInfo) {
    const { id } = file;
    if (requests.current.has(id)) return;
    const controller = new AbortController();
    requests.current.set(id, controller);
    setResult(id, 'pending');

    try {
      const updated = await api.encryptFile(id, controller.signal);
      if (controller.signal.aborted) return;
      onEncrypted(file, updated);
      setResult(id);
    } catch (error_) {
      await reconcileError(id, controller.signal, error_);
    } finally {
      if (!controller.signal.aborted) requests.current.delete(id);
    }
  }

  return { encryptions, encrypt };
}
