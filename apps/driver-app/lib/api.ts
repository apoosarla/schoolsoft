/**
 * Thin client for the Schoolsoft API's chain-scoped endpoints, driver-app's
 * slice of it: OTP login (same flow as admin-web/teacher-app — POST
 * /v1/auth/otp/{start,verify}, dev bypass code "000000"), /v1/iam/me to
 * resolve the caller's staff.id (the JWT only carries user_account.id), and
 * the transport module (drivers, routes, stops, vehicles, trips, GPS pings).
 */

import { createApiClient } from "@schoolsoft/api-client";

export { ApiError } from "@schoolsoft/api-client";

const API_BASE = process.env.NEXT_PUBLIC_SCHOOLSOFT_API_URL ?? "http://localhost:8080";
const SESSION_KEY = "schoolsoft_driver_session";

export type Session = {
  accessToken: string;
  refreshToken: string;
  userAccountId: string;
  subjectType: string;
  subjectId: string | null;
  schoolId: string;
  chainSchema: string;
  identifier: string;
};

export function getSession(): Session | null {
  if (typeof window === "undefined") return null;
  const raw = window.localStorage.getItem(SESSION_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as Session;
  } catch {
    return null;
  }
}

export function setSession(session: Session): void {
  window.localStorage.setItem(SESSION_KEY, JSON.stringify(session));
}

export function clearSession(): void {
  window.localStorage.removeItem(SESSION_KEY);
}

/**
 * Transport comes from @schoolsoft/api-client: one implementation of bearer
 * auth, error mapping, and the 401 → refresh → replay dance, shared by every
 * app instead of six copies that drift.
 */
const client = createApiClient({
  baseUrl: API_BASE,
  getAccessToken: () => getSession()?.accessToken ?? null,
  getRefreshToken: () => getSession()?.refreshToken ?? null,
  onTokensRefreshed: ({ accessToken, refreshToken }) => {
    const current = getSession();
    if (current) setSession({ ...current, accessToken, refreshToken });
  },
  onSessionExpired: () => {
    clearSession();
    if (typeof window !== "undefined") window.location.href = "/login";
  },
});

const apiFetch = client.apiFetch;

export async function startOtp(identifier: string, chainSlug: string): Promise<void> {
  await apiFetch<{ status: string }>("/v1/auth/otp/start", {
    method: "POST",
    body: JSON.stringify({ identifier, chainSlug }),
  });
}

type AuthResponse = {
  accessToken: string;
  refreshToken: string;
  profile: {
    userAccountId: string;
    subjectType: string;
    schoolId: string;
    chainSchema: string;
  };
};

type MeResponse = { userAccountId: string; subjectType: string; subjectId: string; schoolId: string };

export async function verifyOtp(identifier: string, chainSlug: string, code: string): Promise<Session> {
  const res = await apiFetch<AuthResponse>("/v1/auth/otp/verify", {
    method: "POST",
    body: JSON.stringify({ identifier, chainSlug, code }),
  });
  let session: Session = {
    accessToken: res.accessToken,
    refreshToken: res.refreshToken,
    userAccountId: res.profile.userAccountId,
    subjectType: res.profile.subjectType,
    subjectId: null,
    schoolId: res.profile.schoolId,
    chainSchema: res.profile.chainSchema,
    identifier,
  };
  setSession(session);
  const me = await apiFetch<MeResponse>("/v1/iam/me");
  session = { ...session, subjectId: me.subjectId || null };
  setSession(session);
  return session;
}

// -------------------------- Transport --------------------------

export type DriverDto = {
  id: string;
  schoolId: string;
  name: string;
  phone: string | null;
  licenseNo: string | null;
  isActive: boolean;
};

export function myDriverProfile(schoolId: string, staffId: string): Promise<DriverDto[]> {
  const params = new URLSearchParams({ schoolId, staffId });
  return apiFetch<DriverDto[]>(`/v1/transport/drivers?${params.toString()}`);
}

export type TransportRouteDto = {
  id: string;
  schoolId: string;
  code: string;
  name: string;
  direction: string;
  isActive: boolean;
};

export function listRoutes(schoolId: string): Promise<TransportRouteDto[]> {
  return apiFetch<TransportRouteDto[]>(`/v1/transport/routes?schoolId=${schoolId}`);
}

export type TransportStopDto = {
  id: string;
  routeId: string;
  name: string;
  sortOrder: number;
  lat: number | null;
  lng: number | null;
  fee: number | null;
};

export function listStops(routeId: string): Promise<TransportStopDto[]> {
  return apiFetch<TransportStopDto[]>(`/v1/transport/routes/${routeId}/stops`);
}

export type VehicleDto = {
  id: string;
  schoolId: string;
  registrationNo: string;
  model: string | null;
  capacity: number | null;
  isActive: boolean;
};

export function listVehicles(schoolId: string): Promise<VehicleDto[]> {
  return apiFetch<VehicleDto[]>(`/v1/transport/vehicles?schoolId=${schoolId}`);
}

export type ManifestEntry = { status: string; at: string };

export type TripDto = {
  id: string;
  schoolId: string;
  routeId: string;
  vehicleId: string;
  driverId: string;
  direction: string;
  startedAt: string;
  endedAt: string | null;
  manifest: Record<string, ManifestEntry>;
};

export function startTrip(req: {
  schoolId: string;
  routeId: string;
  vehicleId: string;
  driverId: string;
  direction: string;
}): Promise<TripDto> {
  return apiFetch<TripDto>("/v1/transport/trips/start", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function endTrip(tripId: string): Promise<TripDto> {
  return apiFetch<TripDto>(`/v1/transport/trips/${tripId}/end`, { method: "POST" });
}

export function recordGpsPing(req: {
  vehicleId: string;
  occurredAt: string;
  lat: number;
  lng: number;
  speedKmh?: number;
  heading?: number;
}): Promise<void> {
  return apiFetch<void>("/v1/transport/gps-pings", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function tripsForDriver(driverId: string, limit = 20): Promise<TripDto[]> {
  const params = new URLSearchParams({ driverId, limit: String(limit) });
  return apiFetch<TripDto[]>(`/v1/transport/trips?${params.toString()}`);
}

export function checkIn(tripId: string, studentId: string, status: string): Promise<TripDto> {
  return apiFetch<TripDto>(`/v1/transport/trips/${tripId}/checkin`, {
    method: "POST",
    body: JSON.stringify({ studentId, status }),
  });
}

/**
 * A rider as the roster returns them — named, so the check-in screen needs no
 * second call. The driver role holds no school-wide `student.view`: reading a
 * student out of /v1/people is refused, and the route roster is scoped to the
 * routes this driver is rostered to drive today.
 */
export type RouteRiderDto = {
  id: string;
  studentId: string;
  routeId: string;
  stopId: string;
  startsOn: string;
  endsOn: string | null;
  admissionNo: string;
  firstName: string;
  lastName: string | null;
  sectionLabel: string | null;
};

export function studentsOnRoute(routeId: string): Promise<RouteRiderDto[]> {
  return apiFetch<RouteRiderDto[]>(`/v1/transport/routes/${routeId}/students`);
}
