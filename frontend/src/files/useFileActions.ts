import { useState } from 'react';
import { api, errorMessage, HttpError } from '../api/client';
import type { FileInfo } from '../api/types';
import { useAuth } from '../auth/AuthContext';

export function useFileActions(setError: (message: string | null) => void) {
  const { logout, expire } = useAuth();
  const [downloading, setDownloading] = useState<string | null>(null);
  const [leaving, setLeaving] = useState(false);
  function rejected(error_: unknown) {
    if (error_ instanceof HttpError && error_.status === 401) expire(error_.code);
    else setError(errorMessage(error_));
  }
  async function download(file: FileInfo) {
    if (downloading) return;
    setDownloading(file.id);
    setError(null);
    try {
      const blob = await api.download(file.id);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = file.name;
      document.body.append(anchor);
      anchor.click();
      anchor.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error_) {
      rejected(error_);
    } finally {
      setDownloading(null);
    }
  }
  async function signOut() {
    setLeaving(true);
    setError(null);
    try {
      await logout();
    } catch (error_) {
      rejected(error_);
    } finally {
      setLeaving(false);
    }
  }
  return { downloading, leaving, download, signOut };
}
