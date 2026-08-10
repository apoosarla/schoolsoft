/**
 * Thin client for the Schoolsoft API's public, unauthenticated endpoints
 * (apps/api/.../publicsite/api/PublicController.java) — no login anywhere in
 * this app. School and chain are fixed for this dev pass (see CHAIN_SLUG /
 * SCHOOL_SLUG below) since there's no directory-of-schools concept yet.
 */

const API_BASE = process.env.NEXT_PUBLIC_SCHOOLSOFT_API_URL ?? "http://localhost:8080";

export const CHAIN_SLUG = "smoketest";
export const SCHOOL_SLUG = "oakridge-hyd";

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
  const res = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
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

  return (await res.json()) as T;
}

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
