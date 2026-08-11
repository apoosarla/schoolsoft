/**
 * Transport shared by every Schoolsoft web/mobile app. The package holds zero
 * knowledge of how a given app stores its session — callers supply
 * `getAccessToken`, which may be async so native token stores (Capacitor
 * Preferences) can back it later.
 */

export class ApiError extends Error {
  status: number;
  code?: string;
  constructor(status: number, message: string, code?: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export type ApiClientOptions = {
  baseUrl: string;
  getAccessToken: () => string | null | undefined | Promise<string | null | undefined>;
};

export type ApiClient = {
  baseUrl: string;
  apiFetch: <T>(path: string, init?: RequestInit) => Promise<T>;
};

export function createApiClient(options: ApiClientOptions): ApiClient {
  const baseUrl = options.baseUrl.replace(/\/$/, "");

  async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
    const token = await options.getAccessToken();
    const res = await fetch(`${baseUrl}${path}`, {
      ...init,
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(init?.headers ?? {}),
      },
    });

    if (!res.ok) {
      let message = res.statusText;
      let code: string | undefined;
      try {
        const body = await res.json();
        message = body.message ?? message;
        code = body.code;
      } catch {
        // response wasn't JSON — fall back to statusText
      }
      throw new ApiError(res.status, message, code);
    }

    if (res.status === 204) return undefined as T;
    return (await res.json()) as T;
  }

  return { baseUrl, apiFetch };
}
