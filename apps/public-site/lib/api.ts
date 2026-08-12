/**
 * Thin client for the Schoolsoft API's public, unauthenticated endpoints
 * (apps/api/.../publicsite/api/PublicController.java) — no login anywhere in
 * this app. School and chain are fixed for this dev pass (see CHAIN_SLUG /
 * SCHOOL_SLUG below) since there's no directory-of-schools concept yet.
 */

import { createApiClient } from "@schoolsoft/api-client";

export { ApiError } from "@schoolsoft/api-client";

const API_BASE = process.env.NEXT_PUBLIC_SCHOOLSOFT_API_URL ?? "http://localhost:8080";

export const CHAIN_SLUG = "smoketest";
export const SCHOOL_SLUG = "oakridge-hyd";

/** Transport comes from @schoolsoft/api-client; this app never holds a token. */
const client = createApiClient({
  baseUrl: API_BASE,
  getAccessToken: () => null,
});

const apiFetch = client.apiFetch;

export type PublicSchoolDto = {
  id: string;
  slug: string;
  name: string;
  boardCode: string;
};

export function getSchool(): Promise<PublicSchoolDto> {
  return apiFetch<PublicSchoolDto>(`/v1/public/schools/${CHAIN_SLUG}/${SCHOOL_SLUG}`);
}

export type GradeDto = { id: string; code: string; name: string; sortOrder: number };

export function listGrades(): Promise<GradeDto[]> {
  return apiFetch<GradeDto[]>(`/v1/public/schools/${CHAIN_SLUG}/${SCHOOL_SLUG}/grades`);
}

export type ApplyRequest = {
  applicantFirstName: string;
  applicantLastName?: string;
  applicantDob?: string;
  applicantGender?: string;
  gradeId: string;
  guardianName: string;
  guardianPhone: string;
  guardianEmail?: string;
};

export function apply(req: ApplyRequest): Promise<{ applicationNo: string }> {
  return apiFetch<{ applicationNo: string }>(`/v1/public/schools/${CHAIN_SLUG}/${SCHOOL_SLUG}/admissions/apply`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export type ApplicationStatusDto = {
  id: string;
  applicationNo: string;
  applicantFirstName: string;
  applicantLastName: string | null;
  guardianName: string;
  state: string;
  testScore: number | null;
  offerExpiresOn: string | null;
  createdAt: string;
};

export function trackApplication(applicationNo: string, guardianPhone: string): Promise<ApplicationStatusDto> {
  const params = new URLSearchParams({ applicationNo, guardianPhone });
  return apiFetch<ApplicationStatusDto>(
    `/v1/public/schools/${CHAIN_SLUG}/${SCHOOL_SLUG}/admissions/track?${params.toString()}`
  );
}
