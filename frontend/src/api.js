// Every request goes to the PantryChef backend (/api is proxied to it in development). The access token is kept in
// memory only (D-008): it is never written to localStorage, and reloading the page means signing in again.

let token = null;
let onUnauthorized = () => {};

export function setToken(value) {
  token = value;
}

export function onSessionExpired(callback) {
  onUnauthorized = callback;
}

export class ApiError extends Error {
  constructor(status, error = {}) {
    super(error.message || 'Something went wrong. Try again.');
    this.status = status;
    this.code = error.code || 'ERROR';
    this.fieldErrors = error.fieldErrors || {};
    this.requestId = error.requestId;
  }
}

export async function api(path, { method = 'GET', body, headers = {} } = {}) {
  let response;
  try {
    response = await fetch(`/api/v1${path}`, {
      method,
      headers: {
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...headers,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, { code: 'NETWORK', message: 'PantryChef could not be reached. Check that it is running.' });
  }
  if (response.status === 204) {
    return null;
  }
  const data = await response.json().catch(() => null);
  if (!response.ok) {
    if (response.status === 401 && token) {
      token = null;
      onUnauthorized();
    }
    throw new ApiError(response.status, data?.error);
  }
  return data;
}

/** One key per user intent, so a retried submit is applied only once (backend Idempotency-Key). */
export function newRequestKey() {
  return crypto.randomUUID();
}
