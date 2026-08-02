/**
 * Thin client for the Schoolsoft API's platform-admin endpoints
 * (apps/api/.../tenancy/api/ChainAdminController.java).
 *
 * Auth note: there is no platform-admin login flow wired up yet — see
 * BACKLOG.md. `AuthController`/`UserLookupService` only resolve identities
 * that live inside a chain schema (staff/guardian/student), never
 * `platform.platform_user`. Until that exists, the HQ Console asks for a
 * bearer token pasted in directly (e.g. minted by hand against the platform
 * schema, or via a future `/v1/auth/platform-admin/*` flow) and stores it in
 * localStorage. Swap `getToken`/`setToken` for a real session once that
 * login flow lands — nothing else here should need to change.
 */

const API_BASE = process.env.NEXT_PUBLIC_SCHOOLSOFT_API_URL ?? "http://localhost:8080";
const TOKEN_KEY = "schoolsoft_hq_platform_admin_token";

export function getToken(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  window.localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
  window.localStorage.removeItem(TOKEN_KEY);
}

export class ApiError extends Error {
  status: number;
  code?: string;
  constructor(status: number, message: string, code?: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const token = getToken();
  const res = await fetch(`${API_BASE}${path}`, {
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
      // response wasn't JSON (e.g. network-level error page) — fall back to statusText
    }
    throw new ApiError(res.status, message, code);
  }

  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export type ChainDto = {
  id: string;
  slug: string;
  name: string;
  schemaName: string;
  planCode: string;
  region: string;
  status: string;
  schemaVersion: number;
  createdAt: string;
};

export type ProvisionChainRequest = {
  slug: string;
  name: string;
  planCode?: string;
};

export type ProvisionChainResponse = {
  chainId: string;
  schemaName: string;
  created: boolean;
};

export function listChains(): Promise<ChainDto[]> {
  return apiFetch<ChainDto[]>("/v1/platform-admin/chains");
}

export function provisionChain(req: ProvisionChainRequest): Promise<ProvisionChainResponse> {
  return apiFetch<ProvisionChainResponse>("/v1/platform-admin/chains", {
    method: "POST",
    body: JSON.stringify(req),
  });
}
