/**
 * Thin client for the Schoolsoft API's platform-admin endpoints
 * (apps/api/.../tenancy/api/ChainAdminController.java).
 *
 * Auth: OTP against `platform.platform_user` via
 * POST /v1/auth/platform-admin/otp/{start,verify} (AuthController). Dev
 * builds accept the literal code "000000" (OtpStore's dev bypass). The
 * resulting access token is stored in localStorage; `getToken`/`setToken`
 * below are unchanged from before this flow existed.
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

export function isLoggedIn(): boolean {
  return !!getToken();
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

export async function startPlatformOtp(email: string): Promise<void> {
  await apiFetch<{ status: string }>("/v1/auth/platform-admin/otp/start", {
    method: "POST",
    body: JSON.stringify({ email }),
  });
}

export async function verifyPlatformOtp(email: string, code: string): Promise<void> {
  const res = await apiFetch<{ accessToken: string }>("/v1/auth/platform-admin/otp/verify", {
    method: "POST",
    body: JSON.stringify({ email, code }),
  });
  setToken(res.accessToken);
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

export type ChainStatsDto = {
  chainId: string;
  schoolCount: number;
  activeEnrolments: number;
  staffCount: number;
  feeCollectedTotal: number;
};

export function getChainStats(chainId: string): Promise<ChainStatsDto> {
  return apiFetch<ChainStatsDto>(`/v1/platform-admin/chains/${chainId}/stats`);
}
