export interface User {
  id: string;
  username: string;
  roles: string[];
}
export interface FileInfo {
  id: string;
  name: string;
  sizeBytes: number;
  sha256: string;
  createdAt: string;
}
export interface Credentials {
  username: string;
  password: string;
}
export interface CsrfToken {
  token: string;
  headerName: string;
}

export interface ActiveSession {
  id: string;
  current: boolean;
  createdAt: string;
  lastSeenAt: string;
}
export interface ActiveSessionList {
  sessions: ActiveSession[];
  maxSessions: number;
}
