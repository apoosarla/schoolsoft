/**
 * Thin client for the two consoles this app serves.
 *
 * **Schoolsoft staff** (`platform_admin`) sign in against
 * `platform.platform_user` via POST /v1/auth/platform-admin/otp/{start,verify}
 * and work above every chain, through `/v1/platform-admin/*`.
 *
 * **A chain's own HQ admin** (`chain_admin`) signs in through the ordinary
 * chain OTP door with their chain's slug, and works inside their own chain
 * through `/v1/tenancy/*` — where they hold every unrestricted read and
 * exactly one write, `school.onboard`. They never see another chain, because
 * their token names theirs.
 *
 * The two share this app and nothing else: different doors, different
 * endpoints, and a session that says which kind it is so a screen cannot
 * quietly offer one of them the other's data. Dev builds accept the literal
 * code "000000" (OtpStore's dev bypass).
 */

import { createApiClient } from "@schoolsoft/api-client";

export { ApiError } from "@schoolsoft/api-client";

const API_BASE = process.env.NEXT_PUBLIC_SCHOOLSOFT_API_URL ?? "http://localhost:8080";
const TOKEN_KEY = "schoolsoft_hq_platform_admin_token";
const REFRESH_KEY = "schoolsoft_hq_platform_admin_refresh";
const KIND_KEY = "schoolsoft_hq_session_kind";

/**
 * Which console this session is. Kept beside the token rather than read out
 * of the JWT: the screens branch on it, and a screen that guesses wrong shows
 * somebody a door they cannot open.
 */
export type SessionKind = "platform" | "chain";

export function getSessionKind(): SessionKind | null {
  if (typeof window === "undefined") return null;
  const raw = window.localStorage.getItem(KIND_KEY);
  return raw === "platform" || raw === "chain" ? raw : null;
}

function setSessionKind(kind: SessionKind): void {
  window.localStorage.setItem(KIND_KEY, kind);
}

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
  window.localStorage.removeItem(KIND_KEY);
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
  setSessionKind("platform");
}

// ----------------------------------------------------- the chain's own admin

export async function startChainOtp(identifier: string, chainSlug: string): Promise<void> {
  await apiFetch<{ status: string }>("/v1/auth/otp/start", {
    method: "POST",
    body: JSON.stringify({ identifier, chainSlug }),
  });
}

/**
 * Refuses anything but a `chain_admin`: a principal has an account in this
 * same door, and letting one in here would land them on a console built
 * around a chain rather than their school. The token is dropped rather than
 * stored so a refused sign-in leaves nothing behind.
 */
export async function verifyChainOtp(identifier: string, chainSlug: string, code: string): Promise<void> {
  const res = await apiFetch<{
    accessToken: string;
    refreshToken: string;
    profile: { subjectType?: string };
  }>("/v1/auth/otp/verify", {
    method: "POST",
    body: JSON.stringify({ identifier, chainSlug, code }),
  });
  if (res.profile?.subjectType !== "chain_admin") {
    throw new Error(
      "That account is not a chain HQ admin. Staff of a school sign in to the school's own admin app."
    );
  }
  setToken(res.accessToken);
  setRefreshToken(res.refreshToken);
  setSessionKind("chain");
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

// ------------------------------------------------- the schools inside a chain

/**
 * A school as the platform console sees it. The checklist is not here: it is
 * ten counting queries per school, so the console asks for one school's
 * readiness when somebody opens that row.
 */
export type ChainSchoolDto = {
  id: string;
  slug: string;
  name: string;
  boardCode: string;
  /** draft = never opened · live = open · suspended = closed by the operator. */
  lifecycle: "draft" | "live" | "suspended";
  wentLiveAt?: string | null;
  activeEnrolments: number;
};

export type OnboardingStepDto = {
  key: string;
  label: string;
  why: string;
  blocking: boolean;
  done: boolean;
  count: number;
  unit: string;
  skipped: boolean;
  skipReason: string | null;
};

export type SchoolReadinessDto = {
  schoolId: string;
  lifecycle: "draft" | "live" | "suspended";
  wentLiveAt?: string | null;
  canGoLive: boolean;
  steps: OnboardingStepDto[];
};

export type CreateSchoolRequest = {
  slug: string;
  name: string;
  boardCode: string;
  gstin?: string;
  stateCode?: string;
};

export function listChainSchools(chainId: string): Promise<ChainSchoolDto[]> {
  return apiFetch<ChainSchoolDto[]>(`/v1/platform-admin/chains/${chainId}/schools`);
}

/** Opens a school on the chain's behalf. It starts in draft, like any other. */
export function createChainSchool(chainId: string, req: CreateSchoolRequest): Promise<ChainSchoolDto> {
  return apiFetch<ChainSchoolDto>(`/v1/platform-admin/chains/${chainId}/schools`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function getChainSchoolReadiness(chainId: string, schoolId: string): Promise<SchoolReadinessDto> {
  return apiFetch<SchoolReadinessDto>(`/v1/platform-admin/chains/${chainId}/schools/${schoolId}/readiness`);
}

/**
 * A school as its own chain's HQ admin sees it, through `/v1/tenancy/schools`.
 * No headcount: that read is `/v1/platform-admin/chains/{id}/stats`, which is
 * the operator's, and cross-school numbers for a chain are the KPI work the
 * design doc puts in Phase 2.
 */
export type SchoolDto = {
  id: string;
  slug: string;
  name: string;
  boardCode: string;
  gstin: string | null;
  stateCode: string | null;
  isActive: boolean;
  lifecycle: "draft" | "live" | "suspended";
  wentLiveAt?: string | null;
};

export function listMySchools(): Promise<SchoolDto[]> {
  return apiFetch<SchoolDto[]>("/v1/tenancy/schools");
}

/** The chain admin's one write. The school starts in draft, like any other. */
export function createMySchool(req: CreateSchoolRequest): Promise<SchoolDto> {
  return apiFetch<SchoolDto>("/v1/tenancy/schools", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function getMySchoolReadiness(schoolId: string): Promise<SchoolReadinessDto> {
  return apiFetch<SchoolReadinessDto>(`/v1/tenancy/schools/${schoolId}/readiness`);
}
