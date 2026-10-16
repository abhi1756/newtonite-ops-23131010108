/** Thin fetch wrapper: adds the token and Idempotency-Key, and turns error responses into ApiError. */

const TOKEN_KEY = 'newtonite.token';

export const tokenStore = {
  get() {
    try {
      return localStorage.getItem(TOKEN_KEY);
    } catch {
      return null;
    }
  },
  set(token) {
    try {
      localStorage.setItem(TOKEN_KEY, token);
    } catch {
      /* storage unavailable: the session lasts until reload */
    }
  },
  clear() {
    try {
      localStorage.removeItem(TOKEN_KEY);
    } catch {
      /* ignore */
    }
  },
};

/** A response the server rejected. `code` is the machine-readable reason from the problem+json body. */
export class ApiError extends Error {
  constructor(status, code, message, body) {
    super(message);
    this.status = status;
    this.code = code;
    this.body = body;
  }
}

/** fetch() throws TypeError when the request never got a response (offline, connection reset). */
export function isNetworkError(error) {
  return error instanceof TypeError;
}

export function newIdempotencyKey() {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
}

/**
 * @param {string} path API path after /api, e.g. "/items/5"
 * @param {{ method?: string, body?: unknown, idempotencyKey?: string, signal?: AbortSignal }} [options]
 */
export async function api(path, options = {}) {
  const headers = { Accept: 'application/json' };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  const token = tokenStore.get();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey;

  const response = await fetch(`/api${path}`, {
    method: options.method ?? 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: options.signal,
  });

  if (response.status === 401 && path !== '/auth/login') {
    tokenStore.clear();
    window.dispatchEvent(new Event('auth:logout'));
  }

  const text = await response.text();
  let data;
  try {
    data = text ? JSON.parse(text) : undefined;
  } catch {
    data = undefined;
  }
  if (!response.ok) {
    const problem = data ?? {};
    throw new ApiError(
      response.status,
      problem.code ?? `HTTP_${response.status}`,
      problem.detail ?? response.statusText ?? 'Request failed',
      problem,
    );
  }
  return data;
}

export function errorMessage(error) {
  if (error instanceof ApiError) return error.message;
  if (isNetworkError(error)) return 'Cannot reach the server. Check your connection and try again.';
  return error instanceof Error ? error.message : 'Something went wrong';
}
