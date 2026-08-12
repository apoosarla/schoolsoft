/**
 * Transport shared by every Schoolsoft web/mobile app. The package holds zero
 * knowledge of how a given app stores its session — callers supply
 * `getAccessToken`, which may be async so native token stores (Capacitor
 * Preferences) can back it later.
 *
 * Access tokens are short-lived (15 minutes), so a session that outlives one
 * will hit a 401 mid-use. When the caller supplies `getRefreshToken`, the
 * client refreshes once and replays the original request, and only surfaces the
 * 401 if the refresh itself fails — that is the whole session-expiry contract,
 * implemented here rather than in each app.
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

export type RefreshedTokens = {
  accessToken: string;
  refreshToken: string;
};

export type ApiClientOptions = {
  baseUrl: string;
  getAccessToken: () => string | null | undefined | Promise<string | null | undefined>;
  /** Supply to enable transparent refresh. Without it a 401 is thrown as-is. */
  getRefreshToken?: () => string | null | undefined | Promise<string | null | undefined>;
  /** Persist the new tokens. Called before the original request is replayed. */
  onTokensRefreshed?: (tokens: RefreshedTokens) => void | Promise<void>;
  /** Refresh failed or there was no refresh token: the app should send the user to login. */
  onSessionExpired?: () => void | Promise<void>;
};

export type ApiClient = {
  baseUrl: string;
  apiFetch: <T>(path: string, init?: RequestInit) => Promise<T>;
};

export function createApiClient(options: ApiClientOptions): ApiClient {
  const baseUrl = options.baseUrl.replace(/\/$/, "");

  // One refresh in flight at a time: a screen that fires six requests on mount
  // must not spend six refresh tokens, and the last one to land would win.
  let refreshInFlight: Promise<string | null> | null = null;

  async function send(path: string, init: RequestInit | undefined, token: string | null | undefined) {
    return fetch(`${baseUrl}${path}`, {
      ...init,
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(init?.headers ?? {}),
      },
    });
  }

  async function refreshAccessToken(): Promise<string | null> {
    if (!options.getRefreshToken) return null;
    const refreshToken = await options.getRefreshToken();
    if (!refreshToken) return null;

    const res = await fetch(`${baseUrl}/v1/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
    });
    if (!res.ok) return null;

    const body = (await res.json()) as { accessToken?: string; refreshToken?: string };
    if (!body.accessToken) return null;
    await options.onTokensRefreshed?.({
      accessToken: body.accessToken,
      refreshToken: body.refreshToken ?? refreshToken,
    });
    return body.accessToken;
  }

  async function refreshOnce(): Promise<string | null> {
    if (!refreshInFlight) {
      refreshInFlight = refreshAccessToken().finally(() => {
        refreshInFlight = null;
      });
    }
    return refreshInFlight;
  }

  async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
    const token = await options.getAccessToken();
    let res = await send(path, init, token);

    if (res.status === 401 && options.getRefreshToken && !path.startsWith("/v1/auth/")) {
      const newToken = await refreshOnce();
      if (newToken) {
        res = await send(path, init, newToken);
      } else {
        await options.onSessionExpired?.();
      }
    }

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
