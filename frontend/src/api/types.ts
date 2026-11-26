export interface FieldError {
  field: string;
  message: string;
}

export interface ApiErrorBody {
  status: number;
  error: string;
  message: string;
  path: string;
  timestamp: string;
  fieldErrors?: FieldError[];
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface LoginResponse {
  token: string;
}

export interface RegisterResponse {
  id: number;
  email: string;
  createdAt: string;
}

export interface UserProfile {
  id: number;
  email: string;
  createdAt: string;
}

export type LastStatus = 'UP' | 'DOWN' | null;

export interface Monitor {
  id: number;
  userId: number;
  name: string;
  url: string;
  intervalMinutes: number;
  timeoutSeconds: number;
  failureThreshold: number;
  enabled: boolean;
  lastStatus: LastStatus;
  lastCheckedAt: string | null;
  lastResponseMs: number | null;
  targetIp: string | null;
  geoCountry: string | null;
  geoCity: string | null;
  geoLat: number | null;
  geoLon: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateMonitorPayload {
  name: string;
  url: string;
  intervalMinutes?: number;
  timeoutSeconds?: number;
  failureThreshold?: number;
}

export interface CheckResult {
  id: number;
  monitorId: number;
  success: boolean;
  statusCode: number | null;
  latencyMs: number | null;
  errorDetail: string | null;
  checkedAt: string;
}

export interface WindowStats {
  totalChecks: number;
  successfulChecks: number;
  uptimePercentage: number | null;
}

export interface UptimeResponse {
  monitorId: number;
  last24Hours: WindowStats;
  last7Days: WindowStats;
  last30Days: WindowStats;
}

export interface Incident {
  id: number;
  monitorId: number;
  status: 'OPEN' | 'RESOLVED';
  openedAt: string;
  resolvedAt: string | null;
  failureCountAtOpen: number;
  lastError: string | null;
}
