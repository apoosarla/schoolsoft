/**
 * Thin client for the Schoolsoft API's chain-scoped endpoints, parent-app's
 * slice of it: OTP login (same flow as admin-web/teacher-app — POST
 * /v1/auth/otp/{start,verify}, dev bypass code "000000"), /v1/iam/me to
 * resolve the caller's guardian.id (the JWT only carries user_account.id),
 * children (reverse of guardiansOfStudent), announcements, and fees.
 */

const API_BASE = process.env.NEXT_PUBLIC_SCHOOLSOFT_API_URL ?? "http://localhost:8080";
const SESSION_KEY = "schoolsoft_parent_session";

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

export type StudentDto = {
  id: string;
  schoolId: string;
  admissionNo: string;
  firstName: string;
  middleName: string | null;
  lastName: string | null;
  dob: string | null;
  gender: string | null;
  status: string;
  currentSectionId: string | null;
  currentSectionLabel: string | null;
  rollNo: string | null;
};

export function studentsOfGuardian(guardianId: string): Promise<StudentDto[]> {
  return apiFetch<StudentDto[]>(`/v1/people/guardians/${guardianId}/students`);
}

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

export function listAnnouncements(schoolId: string): Promise<AnnouncementDto[]> {
  return apiFetch<AnnouncementDto[]>(`/v1/comms/announcements?schoolId=${schoolId}`);
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

export type FeeInvoiceDto = {
  id: string;
  schoolId: string;
  studentId: string;
  invoiceNo: string;
  cycleLabel: string;
  issuedOn: string;
  dueOn: string;
  subtotal: number;
  gst: number;
  total: number;
  paid: number;
  status: string;
};

export function listInvoicesForStudent(studentId: string): Promise<FeeInvoiceDto[]> {
  return apiFetch<FeeInvoiceDto[]>(`/v1/fees/invoices?studentId=${studentId}`);
}

export type FeeInvoiceLineDto = {
  id: string;
  feeInvoiceId: string;
  feeHeadId: string;
  description: string | null;
  amount: number;
  discount: number;
  gst: number;
};

export function listInvoiceLines(invoiceId: string): Promise<FeeInvoiceLineDto[]> {
  return apiFetch<FeeInvoiceLineDto[]>(`/v1/fees/invoices/${invoiceId}/lines`);
}

export type PaymentDto = {
  id: string;
  schoolId: string;
  feeInvoiceId: string;
  amount: number;
  gateway: string;
  method: string | null;
  status: string;
  idempotencyKey: string;
  capturedAt: string | null;
};

export function listPaymentsForInvoice(invoiceId: string): Promise<PaymentDto[]> {
  return apiFetch<PaymentDto[]>(`/v1/fees/invoices/${invoiceId}/payments`);
}

// -------------------------- Attendance history + leave --------------------------

export function attendanceHistoryForStudent(studentId: string, from: string, to: string): Promise<AttendanceRecordDto[]> {
  const params = new URLSearchParams({ from, to });
  return apiFetch<AttendanceRecordDto[]>(`/v1/attendance/students/${studentId}?${params.toString()}`);
}

export type LeaveApplicationDto = {
  id: string;
  schoolId: string;
  subjectType: string;
  subjectId: string;
  fromDate: string;
  toDate: string;
  reason: string | null;
  status: string;
  approverStaffId: string | null;
};

export function applyLeave(req: {
  schoolId: string;
  subjectId: string;
  fromDate: string;
  toDate: string;
  reason?: string;
}): Promise<LeaveApplicationDto> {
  return apiFetch<LeaveApplicationDto>("/v1/attendance/leave", {
    method: "POST",
    body: JSON.stringify({ ...req, subjectType: "student" }),
  });
}

// -------------------------- Report cards --------------------------

export type ReportCardDto = {
  id: string;
  schoolId: string;
  studentId: string;
  academicYearId: string;
  termId: string | null;
  strategyCode: string;
  templateCode: string;
  isLocked: boolean;
  generatedAt: string;
};

export function reportCardsForStudent(studentId: string): Promise<ReportCardDto[]> {
  return apiFetch<ReportCardDto[]>(`/v1/assessment/report-cards/students/${studentId}`);
}

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

// -------------------------- Homework (LMS) --------------------------

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

export function submitAssignment(assignmentId: string, studentId: string, body: string): Promise<AssignmentSubmissionDto> {
  return apiFetch<AssignmentSubmissionDto>(`/v1/lms/assignments/${assignmentId}/submissions`, {
    method: "POST",
    body: JSON.stringify({ studentId, body }),
  });
}

// -------------------------- Messaging --------------------------

export type SectionTeacherDto = {
  id: string;
  sectionId: string;
  subjectId: string;
  subjectName: string;
  teacherStaffId: string;
  teacherName: string;
  isPrimary: boolean;
};

export function teachersForSection(sectionId: string): Promise<SectionTeacherDto[]> {
  return apiFetch<SectionTeacherDto[]>(`/v1/tenancy/sections/${sectionId}/teachers`);
}

export type UserDirectoryEntryDto = {
  userAccountId: string;
  subjectType: string;
  subjectId: string;
  displayName: string;
  email: string | null;
  phone: string | null;
};

export function staffDirectory(schoolId: string): Promise<UserDirectoryEntryDto[]> {
  return apiFetch<UserDirectoryEntryDto[]>(`/v1/people/directory?schoolId=${schoolId}&subjectType=staff`);
}

export type MessageThreadDto = {
  id: string;
  schoolId: string;
  subjectStudentId: string | null;
  participants: string[];
  lastMessageAt: string | null;
};

export function threadsForUser(userAccountId: string): Promise<MessageThreadDto[]> {
  return apiFetch<MessageThreadDto[]>(`/v1/comms/threads?userAccountId=${userAccountId}`);
}

export function createThread(req: { schoolId: string; subjectStudentId?: string; participants: string[] }): Promise<MessageThreadDto> {
  return apiFetch<MessageThreadDto>("/v1/comms/threads", { method: "POST", body: JSON.stringify(req) });
}

export type MessageDto = { id: string; threadId: string; senderUserId: string; body: string; sentAt: string };

export function messagesForThread(threadId: string): Promise<MessageDto[]> {
  return apiFetch<MessageDto[]>(`/v1/comms/threads/${threadId}/messages`);
}

export function sendMessage(threadId: string, senderUserId: string, body: string): Promise<MessageDto> {
  return apiFetch<MessageDto>(`/v1/comms/threads/${threadId}/messages`, {
    method: "POST",
    body: JSON.stringify({ senderUserId, body }),
  });
}
