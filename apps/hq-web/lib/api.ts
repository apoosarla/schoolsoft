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

import { createApiClient } from "@schoolsoft/api-client";

export { ApiError } from "@schoolsoft/api-client";

const API_BASE = process.env.NEXT_PUBLIC_SCHOOLSOFT_API_URL ?? "http://localhost:8080";
const TOKEN_KEY = "schoolsoft_hq_platform_admin_token";
const REFRESH_KEY = "schoolsoft_hq_platform_admin_refresh";

export function getToken(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  window.localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
  window.localStorage.removeItem(TOKEN_KEY);
  window.localStorage.removeItem(REFRESH_KEY);
}

export function getRefreshToken(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(REFRESH_KEY);
}

export function setRefreshToken(token: string): void {
  window.localStorage.setItem(REFRESH_KEY, token);
}

export function isLoggedIn(): boolean {
  return !!getToken();
}

/**
 * Transport comes from @schoolsoft/api-client, so the platform console gets the
 * same 401 → refresh → replay behaviour as the chain-scoped apps rather than
 * dropping the operator at a dead screen when a 15-minute access token lapses.
 */
const client = createApiClient({
  baseUrl: API_BASE,
  getAccessToken: () => getToken(),
  getRefreshToken: () => getRefreshToken(),
  onTokensRefreshed: ({ accessToken, refreshToken }) => {
    setToken(accessToken);
    setRefreshToken(refreshToken);
  },
  onSessionExpired: () => {
    clearToken();
    if (typeof window !== "undefined") window.location.href = "/login";
  },
});

const apiFetch = client.apiFetch;

export async function startPlatformOtp(email: string): Promise<void> {
  await apiFetch<{ status: string }>("/v1/auth/platform-admin/otp/start", {
    method: "POST",
    body: JSON.stringify({ email }),
  });
}

export async function verifyPlatformOtp(email: string, code: string): Promise<void> {
  const res = await apiFetch<{ accessToken: string; refreshToken: string }>(
    "/v1/auth/platform-admin/otp/verify",
    {
      method: "POST",
      body: JSON.stringify({ email, code }),
    }
  );
  setToken(res.accessToken);
  setRefreshToken(res.refreshToken);
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
