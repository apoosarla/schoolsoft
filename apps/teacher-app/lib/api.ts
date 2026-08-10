/**
 * Thin client for the Schoolsoft API's chain-scoped endpoints, teacher-app's
 * slice of it: OTP login (same flow as admin-web — POST /v1/auth/otp/{start,verify},
 * dev bypass code "000000"), /v1/iam/me to resolve the caller's staff.id (the
 * JWT only carries user_account.id), timetable, roster, and attendance.
 */

const API_BASE = process.env.NEXT_PUBLIC_SCHOOLSOFT_API_URL ?? "http://localhost:8080";
const SESSION_KEY = "schoolsoft_teacher_session";

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
  const session = getSession();
  const res = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(session ? { Authorization: `Bearer ${session.accessToken}` } : {}),
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

export type SectionDto = {
  id: string;
  schoolId: string;
  gradeId: string;
  gradeName: string;
  academicYearId: string;
  code: string;
  name: string;
  curriculumId: string | null;
  strategyCode: string;
  capacity: number | null;
};

export function listSections(schoolId: string): Promise<SectionDto[]> {
  return apiFetch<SectionDto[]>(`/v1/tenancy/schools/${schoolId}/sections`);
}

export type TimetableSlotDto = {
  id: string;
  sectionId: string;
  subjectId: string;
  subjectName: string;
  teacherStaffId: string;
  dayOfWeek: number;
  periodNo: number;
  startsAt: string;
  endsAt: string;
  room: string | null;
  effectiveFrom: string;
  effectiveTo: string | null;
};

export function timetableForTeacher(teacherStaffId: string): Promise<TimetableSlotDto[]> {
  return apiFetch<TimetableSlotDto[]>(`/v1/timetable/teachers/${teacherStaffId}`);
}

export type EnrolmentDto = {
  id: string;
  schoolId: string;
  studentId: string;
  sectionId: string;
  sectionLabel: string;
  academicYearId: string;
  startsOn: string;
  endsOn: string | null;
  status: string;
  rollNo: string | null;
};

export function rosterForSection(sectionId: string): Promise<EnrolmentDto[]> {
  return apiFetch<EnrolmentDto[]>(`/v1/enrolment/sections/${sectionId}`);
}

export type AttendanceRecordDto = {
  id: string;
  schoolId: string;
  studentId: string;
  sectionId: string;
  onDate: string;
  periodNo: number | null;
  status: string;
  source: string;
  notes: string | null;
};

export function attendanceForSectionOnDate(sectionId: string, onDate: string): Promise<AttendanceRecordDto[]> {
  const params = new URLSearchParams({ sectionId, onDate });
  return apiFetch<AttendanceRecordDto[]>(`/v1/attendance?${params.toString()}`);
}

export type MarkAttendanceEntry = { studentId: string; status: string; notes?: string };

export function markAttendanceBulk(
  schoolId: string,
  sectionId: string,
  onDate: string,
  entries: MarkAttendanceEntry[]
): Promise<AttendanceRecordDto[]> {
  return apiFetch<AttendanceRecordDto[]>("/v1/attendance/mark/bulk", {
    method: "POST",
    body: JSON.stringify({ schoolId, sectionId, onDate, source: "manual", entries }),
  });
}

// -------------------------- Assessment --------------------------

export type AssessmentDto = {
  id: string;
  schoolId: string;
  sectionId: string;
  subjectId: string;
  termId: string | null;
  strategyCode: string;
  name: string;
  assessmentType: string;
  maxMarks: number | null;
  weightPct: number | null;
  scheduledOn: string | null;
  status: string;
};

export function assessmentsForSection(sectionId: string): Promise<AssessmentDto[]> {
  return apiFetch<AssessmentDto[]>(`/v1/assessment?sectionId=${sectionId}`);
}

export function createAssessment(req: {
  schoolId: string;
  sectionId: string;
  subjectId: string;
  strategyCode: string;
  name: string;
  assessmentType: string;
  maxMarks?: number;
}): Promise<AssessmentDto> {
  return apiFetch<AssessmentDto>("/v1/assessment", { method: "POST", body: JSON.stringify(req) });
}

export type AssessmentComponentDto = {
  id: string;
  assessmentId: string;
  code: string;
  name: string;
  maxMarks: number;
  weightPct: number | null;
  sortOrder: number;
};

export function componentsForAssessment(assessmentId: string): Promise<AssessmentComponentDto[]> {
  return apiFetch<AssessmentComponentDto[]>(`/v1/assessment/${assessmentId}/components`);
}

export function addAssessmentComponent(
  assessmentId: string,
  req: { code: string; name: string; maxMarks: number; sortOrder: number }
): Promise<AssessmentComponentDto> {
  return apiFetch<AssessmentComponentDto>(`/v1/assessment/${assessmentId}/components`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export type MarkDto = {
  id: string;
  assessmentComponentId: string;
  studentId: string;
  rawMarks: number | null;
  gradeLetter: string | null;
  remarks: string | null;
  isAbsent: boolean;
};

export function marksForComponent(componentId: string): Promise<MarkDto[]> {
  return apiFetch<MarkDto[]>(`/v1/assessment/components/${componentId}/marks`);
}

export function enterMark(
  componentId: string,
  req: { schoolId: string; studentId: string; rawMarks?: number; isAbsent: boolean }
): Promise<MarkDto> {
  return apiFetch<MarkDto>(`/v1/assessment/components/${componentId}/marks`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

// -------------------------- LMS: assignments --------------------------

export type AssignmentDto = {
  id: string;
  schoolId: string;
  sectionId: string;
  subjectId: string;
  title: string;
  instructions: string | null;
  submissionType: string | null;
  dueAt: string | null;
  maxMarks: number | null;
  status: string;
  createdByStaffId: string | null;
};

export function assignmentsForSection(sectionId: string): Promise<AssignmentDto[]> {
  return apiFetch<AssignmentDto[]>(`/v1/lms/assignments?sectionId=${sectionId}`);
}

export function createAssignment(req: {
  schoolId: string;
  sectionId: string;
  subjectId: string;
  title: string;
  instructions?: string;
  dueAt?: string;
  maxMarks?: number;
  createdByStaffId?: string;
}): Promise<AssignmentDto> {
  return apiFetch<AssignmentDto>("/v1/lms/assignments", { method: "POST", body: JSON.stringify(req) });
}

export type AssignmentSubmissionDto = {
  id: string;
  assignmentId: string;
  studentId: string;
  body: string | null;
  submittedAt: string | null;
  marks: number | null;
  feedback: string | null;
  gradedAt: string | null;
};

export function submissionsForAssignment(assignmentId: string): Promise<AssignmentSubmissionDto[]> {
  return apiFetch<AssignmentSubmissionDto[]>(`/v1/lms/assignments/${assignmentId}/submissions`);
}

export function gradeSubmission(
  submissionId: string,
  req: { marks: number; feedback?: string; gradedByStaffId?: string }
): Promise<AssignmentSubmissionDto> {
  return apiFetch<AssignmentSubmissionDto>(`/v1/lms/submissions/${submissionId}/grade`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

// -------------------------- Comms: announcements --------------------------

export type AnnouncementDto = {
  id: string;
  schoolId: string;
  scopeType: string;
  scopeIds: string[] | null;
  title: string;
  body: string;
  channels: string[];
  publishedAt: string | null;
  expiresAt: string | null;
  createdByUserId: string | null;
  createdAt: string;
};

export function announcementsForSchool(schoolId: string): Promise<AnnouncementDto[]> {
  return apiFetch<AnnouncementDto[]>(`/v1/comms/announcements?schoolId=${schoolId}`);
}

export function createAnnouncement(req: {
  schoolId: string;
  scopeType: string;
  scopeIds?: string[];
  title: string;
  body: string;
  channels?: string[];
  createdByUserId?: string;
}): Promise<AnnouncementDto> {
  return apiFetch<AnnouncementDto>("/v1/comms/announcements", { method: "POST", body: JSON.stringify(req) });
}

export function publishAnnouncement(id: string): Promise<AnnouncementDto> {
  return apiFetch<AnnouncementDto>(`/v1/comms/announcements/${id}/publish`, { method: "POST" });
}
