import type {
  ApiErrorBody,
  CheckResult,
  CreateMonitorPayload,
  FieldError,
  Incident,
  LoginResponse,
  Monitor,
  PageResponse,
  RegisterResponse,
  UptimeResponse,
  UserProfile,
} from './types';

const TOKEN_KEY = 'pulseops_token';

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY);
}

export class ApiRequestError extends Error {
  readonly status: number;
  readonly fieldErrors: FieldError[];

  constructor(status: number, message: string, fieldErrors: FieldError[] = []) {
    super(message);
    this.status = status;
    this.fieldErrors = fieldErrors;
  }
}

async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = getToken();
  const headers = new Headers(init.headers);
  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }

  const res = await fetch(path, { ...init, headers });

  if (res.status === 204) {
    return undefined as T;
  }

  const text = await res.text();
  const data = text ? JSON.parse(text) : null;

  if (!res.ok) {
    const errBody = data as ApiErrorBody | null;
    if (res.status === 401 && token) {
      clearToken();
    }
    throw new ApiRequestError(
      res.status,
      errBody?.message || `Request failed (${res.status})`,
      errBody?.fieldErrors ?? []
    );
  }

  return data as T;
}

export const authApi = {
  register: (email: string, password: string) =>
    apiFetch<RegisterResponse>('/api/v1/auth/register', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    }),

  login: (email: string, password: string) =>
    apiFetch<LoginResponse>('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    }),

  me: () => apiFetch<UserProfile>('/api/v1/users/me'),
};

export const monitorsApi = {
  list: (page = 0, size = 20) =>
    apiFetch<PageResponse<Monitor>>(`/api/v1/monitors?page=${page}&size=${size}`),

  create: (payload: CreateMonitorPayload) =>
    apiFetch<Monitor>('/api/v1/monitors', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),

  pause: (id: number) =>
    apiFetch<Monitor>(`/api/v1/monitors/${id}/pause`, { method: 'POST' }),

  resume: (id: number) =>
    apiFetch<Monitor>(`/api/v1/monitors/${id}/resume`, { method: 'POST' }),

  remove: (id: number) =>
    apiFetch<void>(`/api/v1/monitors/${id}`, { method: 'DELETE' }),

  results: (id: number, page = 0, size = 20) =>
    apiFetch<PageResponse<CheckResult>>(
      `/api/v1/monitors/${id}/results?page=${page}&size=${size}`
    ),

  uptime: (id: number) =>
    apiFetch<UptimeResponse>(`/api/v1/monitors/${id}/uptime`),
};

export const incidentsApi = {
  listAll: (page = 0, size = 20) =>
    apiFetch<PageResponse<Incident>>(`/api/v1/incidents?page=${page}&size=${size}`),

  listForMonitor: (id: number, page = 0, size = 20) =>
    apiFetch<PageResponse<Incident>>(
      `/api/v1/monitors/${id}/incidents?page=${page}&size=${size}`
    ),
};
