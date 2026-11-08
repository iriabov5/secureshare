import type { ActiveSessionList, Credentials, CsrfToken, FileInfo, FileIntegrityResult, User } from './types';

export class HttpError extends Error {
  readonly status: number;
  readonly code: string;
  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = 'HttpError';
    this.status = status;
    this.code = code;
  }
}

let csrfPromise: Promise<CsrfToken> | null = null;
export function resetCsrf(): void {
  csrfPromise = null;
}

async function errorFrom(response: Response): Promise<HttpError> {
  const body: unknown = await response.json().catch(() => null);
  if (
    typeof body === 'object' &&
    body !== null &&
    'code' in body &&
    'message' in body &&
    typeof body.code === 'string' &&
    typeof body.message === 'string'
  ) {
    return new HttpError(response.status, body.code, body.message);
  }
  return new HttpError(response.status, 'REQUEST_FAILED', 'Не удалось выполнить запрос. Попробуйте ещё раз.');
}

async function jsonResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    if (response.status === 403) resetCsrf();
    throw await errorFrom(response);
  }
  if (response.status === 204) return undefined as T;
  if (!response.headers.get('content-type')?.includes('application/json')) {
    throw new HttpError(502, 'INVALID_RESPONSE', 'Сервер вернул неожиданный ответ. Обновите страницу.');
  }
  return response.json() as Promise<T>;
}

export function csrf(): Promise<CsrfToken> {
  csrfPromise ??= fetch('/api/v1/auth/csrf', { credentials: 'include' })
    .then(jsonResponse<CsrfToken>)
    .catch((error: unknown) => {
      resetCsrf();
      throw error;
    });
  return csrfPromise;
}

async function post<T>(
  path: string,
  { body, contentType, signal }: { body?: BodyInit; contentType?: string; signal?: AbortSignal } = {},
): Promise<T> {
  const token = await csrf();
  const headers = new Headers({ [token.headerName]: token.token });
  if (contentType) headers.set('Content-Type', contentType);
  const response = await fetch(path, { method: 'POST', credentials: 'include', headers, body, signal });
  return jsonResponse<T>(response);
}

async function refreshCsrf(): Promise<void> {
  resetCsrf();
  // Login/logout уже подтверждены сервером: сбой refresh не отменяет их результат.
  await csrf().then(
    () => undefined,
    () => undefined,
  );
}

export const api = {
  async sessions(signal?: AbortSignal): Promise<ActiveSessionList> {
    return jsonResponse<ActiveSessionList>(
      await fetch('/api/v1/me/sessions', { credentials: 'include', signal }),
    );
  },
  async revokeSession(id: string): Promise<void> {
    const token = await csrf();
    return jsonResponse<void>(
      await fetch(`/api/v1/me/sessions/${encodeURIComponent(id)}`, {
        method: 'DELETE',
        credentials: 'include',
        headers: { [token.headerName]: token.token },
      }),
    );
  },
  async me(signal?: AbortSignal): Promise<User> {
    return jsonResponse<User>(await fetch('/api/v1/me', { credentials: 'include', signal }));
  },
  async register(credentials: Credentials): Promise<void> {
    await post('/api/v1/auth/register', {
      body: JSON.stringify(credentials),
      contentType: 'application/json',
    });
  },
  async login(credentials: Credentials): Promise<User> {
    const user = await post<User>('/api/v1/auth/login', {
      body: new URLSearchParams({ ...credentials }),
      contentType: 'application/x-www-form-urlencoded',
    });
    await refreshCsrf();
    return user;
  },
  async logout(): Promise<void> {
    await post<void>('/api/v1/auth/logout');
    await refreshCsrf();
  },
  async files(signal?: AbortSignal): Promise<FileInfo[]> {
    return jsonResponse<FileInfo[]>(await fetch('/api/v1/files', { credentials: 'include', signal }));
  },
  async upload(file: File): Promise<FileInfo> {
    const body = new FormData();
    body.append('file', file);
    return post<FileInfo>('/api/v1/files', { body });
  },
  async encryptFile(id: string, signal: AbortSignal): Promise<FileInfo> {
    const file = await post<FileInfo>(`/api/v1/files/${encodeURIComponent(id)}/encrypt`, { signal });
    if (file?.id !== id || file.storageStatus !== 'ENCRYPTED' || file.legacyCleanupPending !== false) {
      throw new HttpError(502, 'INVALID_RESPONSE', 'Сервер вернул неожиданный ответ. Обновите страницу.');
    }
    return file;
  },
  async checkIntegrity(id: string, signal: AbortSignal): Promise<FileIntegrityResult> {
    const result = await post<FileIntegrityResult>(
      `/api/v1/files/${encodeURIComponent(id)}/integrity-check`,
      { signal },
    );
    if (
      result?.status !== 'VERIFIED' ||
      typeof result.checkedAt !== 'string' ||
      !Number.isFinite(Date.parse(result.checkedAt))
    ) {
      throw new HttpError(502, 'INVALID_RESPONSE', 'Сервер вернул неожиданный ответ. Обновите страницу.');
    }
    return result;
  },
  async download(id: string): Promise<Blob> {
    const response = await fetch(`/api/v1/files/${encodeURIComponent(id)}/content`, {
      credentials: 'include',
    });
    if (!response.ok) throw await errorFrom(response);
    if (!response.headers.get('content-type')?.startsWith('application/octet-stream')) {
      throw new HttpError(502, 'INVALID_RESPONSE', 'Файл пока недоступен. Повторите попытку позже.');
    }
    return response.blob();
  },
};

export function errorMessage(
  error: unknown,
  fallback = 'Нет соединения с сервером. Попробуйте ещё раз.',
): string {
  return error instanceof HttpError ? error.message : fallback;
}
