/**
 * Thin client for the Schoolsoft API's chain-scoped endpoints
 * (apps/api/.../iam/api/AuthController.java, dashboard/api/DashboardController.java).
 *
 * Unlike hq-web (platform-admin, no login flow yet — see BACKLOG.md), this
 * app authenticates against a real endpoint: POST /v1/auth/otp/start +
 * /v1/auth/otp/verify resolve identities inside a chain schema
 * (staff/guardian/student via `user_account`). Dev builds accept the literal
 * OTP code "000000" (OtpStore's dev bypass).
 */

const API_BASE = process.env.NEXT_PUBLIC_SCHOOLSOFT_API_URL ?? "http://localhost:8080";
const SESSION_KEY = "schoolsoft_admin_session";

export type Session = {
  accessToken: string;
  refreshToken: string;
  userAccountId: string;
  subjectType: string;
  schoolId: string;
  chainSchema: string;
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

export async function verifyOtp(identifier: string, chainSlug: string, code: string): Promise<Session> {
  const res = await apiFetch<AuthResponse>("/v1/auth/otp/verify", {
    method: "POST",
    body: JSON.stringify({ identifier, chainSlug, code }),
  });
  const session: Session = {
    accessToken: res.accessToken,
    refreshToken: res.refreshToken,
    userAccountId: res.profile.userAccountId,
    subjectType: res.profile.subjectType,
    schoolId: res.profile.schoolId,
    chainSchema: res.profile.chainSchema,
  };
  setSession(session);
  return session;
}

export type SchoolOverviewDto = {
  activeEnrolments: number;
  presentToday: number;
  attendanceTodayPct: number | null;
  feeInvoicedMtd: number;
  feeCollectedMtd: number;
  feeCollectionMtdPct: number | null;
  admissionsFunnel: Record<string, number>;
  announcementsPublished30d: number;
  announcementReads30d: number;
};

export function getSchoolOverview(schoolId: string): Promise<SchoolOverviewDto> {
  return apiFetch<SchoolOverviewDto>(`/v1/dashboards/schools/${schoolId}/overview`);
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

export function listStudents(schoolId: string, q?: string, sectionId?: string): Promise<StudentDto[]> {
  const params = new URLSearchParams({ schoolId });
  if (q) params.set("q", q);
  if (sectionId) params.set("sectionId", sectionId);
  return apiFetch<StudentDto[]>(`/v1/people/students?${params.toString()}`);
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

export type AcademicYearDto = {
  id: string;
  code: string;
  startsOn: string;
  endsOn: string;
  isCurrent: boolean;
};

export function listAcademicYears(schoolId: string): Promise<AcademicYearDto[]> {
  return apiFetch<AcademicYearDto[]>(`/v1/tenancy/schools/${schoolId}/academic-years`);
}

export type GradeDto = { id: string; code: string; name: string; sortOrder: number };

export function listGrades(schoolId: string): Promise<GradeDto[]> {
  return apiFetch<GradeDto[]>(`/v1/tenancy/schools/${schoolId}/grades`);
}

export const ADMISSION_STATES = [
  "lead",
  "application_started",
  "document_pending",
  "fee_pending",
  "review",
  "test_scheduled",
  "test_done",
  "offered",
  "accepted",
  "waitlist",
  "rejected",
  "enrolled",
  "lapsed",
] as const;

export type AdmissionApplicationDto = {
  id: string;
  schoolId: string;
  academicYearId: string;
  gradeId: string;
  applicationNo: string;
  applicantFirstName: string;
  applicantLastName: string | null;
  applicantDob: string | null;
  applicantGender: string | null;
  guardianName: string;
  guardianPhone: string;
  guardianEmail: string | null;
  source: string | null;
  state: string;
  testScore: number | null;
  interviewNotes: string | null;
  offerExpiresOn: string | null;
  convertedStudentId: string | null;
  createdAt: string;
};

export function listAdmissionApplications(schoolId: string, state?: string): Promise<AdmissionApplicationDto[]> {
  const params = new URLSearchParams({ schoolId });
  if (state) params.set("state", state);
  return apiFetch<AdmissionApplicationDto[]>(`/v1/admissions/applications?${params.toString()}`);
}

export type CreateAdmissionApplicationRequest = {
  schoolId: string;
  academicYearId: string;
  gradeId: string;
  applicationNo: string;
  applicantFirstName: string;
  applicantLastName?: string;
  applicantDob?: string;
  applicantGender?: string;
  guardianName: string;
  guardianPhone: string;
  guardianEmail?: string;
  source?: string;
};

export function createAdmissionApplication(
  req: CreateAdmissionApplicationRequest
): Promise<AdmissionApplicationDto> {
  return apiFetch<AdmissionApplicationDto>("/v1/admissions/applications", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function transitionAdmissionApplication(id: string, toState: string): Promise<AdmissionApplicationDto> {
  return apiFetch<AdmissionApplicationDto>(`/v1/admissions/applications/${id}/transition`, {
    method: "POST",
    body: JSON.stringify({ toState }),
  });
}

export function enrolAdmissionApplication(
  id: string,
  sectionId: string,
  rollNo?: string
): Promise<{ studentId: string }> {
  return apiFetch<{ studentId: string }>(`/v1/admissions/applications/${id}/enrol`, {
    method: "POST",
    body: JSON.stringify({ sectionId, rollNo }),
  });
}

export type FeeHeadDto = {
  id: string;
  schoolId: string;
  code: string;
  name: string;
  isRecurring: boolean;
  gstRatePct: number;
  hsnSac: string | null;
};

export function listFeeHeads(schoolId: string): Promise<FeeHeadDto[]> {
  return apiFetch<FeeHeadDto[]>(`/v1/fees/heads?schoolId=${schoolId}`);
}

export function createFeeHead(
  schoolId: string,
  req: { code: string; name: string; isRecurring: boolean; gstRatePct: number; hsnSac?: string }
): Promise<FeeHeadDto> {
  return apiFetch<FeeHeadDto>(`/v1/fees/heads?schoolId=${schoolId}`, {
    method: "POST",
    body: JSON.stringify(req),
  });
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

export type InvoiceLineInput = {
  feeHeadId: string;
  description?: string;
  amount: number;
  discount: number;
  gst: number;
};

export function createInvoice(req: {
  schoolId: string;
  studentId: string;
  invoiceNo: string;
  cycleLabel: string;
  dueOn: string;
  lines: InvoiceLineInput[];
}): Promise<FeeInvoiceDto> {
  return apiFetch<FeeInvoiceDto>("/v1/fees/invoices", {
    method: "POST",
    body: JSON.stringify(req),
  });
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

export function recordPayment(req: {
  schoolId: string;
  feeInvoiceId: string;
  amount: number;
  gateway: string;
  method?: string;
  idempotencyKey: string;
}): Promise<PaymentDto> {
  return apiFetch<PaymentDto>("/v1/fees/payments", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export type SubjectDto = { id: string; schoolId: string; code: string; name: string; boardCode: string | null };

export function listSubjects(schoolId: string): Promise<SubjectDto[]> {
  return apiFetch<SubjectDto[]>(`/v1/tenancy/schools/${schoolId}/subjects`);
}

export type StaffDto = {
  id: string;
  schoolId: string;
  employeeNo: string;
  firstName: string;
  lastName: string | null;
  email: string | null;
  phone: string | null;
  employmentType: string | null;
  joinedOn: string | null;
  isActive: boolean;
};

export function listStaff(schoolId: string, q?: string): Promise<StaffDto[]> {
  const params = new URLSearchParams({ schoolId });
  if (q) params.set("q", q);
  return apiFetch<StaffDto[]>(`/v1/people/staff?${params.toString()}`);
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

export function timetableForSection(sectionId: string): Promise<TimetableSlotDto[]> {
  return apiFetch<TimetableSlotDto[]>(`/v1/timetable/sections/${sectionId}`);
}

export function createTimetableSlot(req: {
  sectionId: string;
  subjectId: string;
  teacherStaffId: string;
  dayOfWeek: number;
  periodNo: number;
  startsAt: string;
  endsAt: string;
  room?: string;
  effectiveFrom: string;
  effectiveTo?: string;
}): Promise<TimetableSlotDto> {
  return apiFetch<TimetableSlotDto>("/v1/timetable/slots", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function deleteTimetableSlot(id: string): Promise<void> {
  return apiFetch<void>(`/v1/timetable/slots/${id}`, { method: "DELETE" });
}

export const ASSESSMENT_STATUSES = [
  "draft",
  "scheduled",
  "in_progress",
  "marking",
  "locked",
  "published",
] as const;

export const ASSESSMENT_TYPES = ["PT", "HY", "Annual", "CoScholastic", "Component", "Coursework"];

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

export function listAssessmentsForSection(sectionId: string): Promise<AssessmentDto[]> {
  return apiFetch<AssessmentDto[]>(`/v1/assessment?sectionId=${sectionId}`);
}

export function createAssessment(req: {
  schoolId: string;
  sectionId: string;
  subjectId: string;
  termId?: string;
  strategyCode: string;
  name: string;
  assessmentType: string;
  maxMarks?: number;
  weightPct?: number;
  scheduledOn?: string;
}): Promise<AssessmentDto> {
  return apiFetch<AssessmentDto>("/v1/assessment", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function setAssessmentStatus(id: string, status: string): Promise<AssessmentDto> {
  return apiFetch<AssessmentDto>(`/v1/assessment/${id}/status`, {
    method: "POST",
    body: JSON.stringify({ status }),
  });
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

export function listAssessmentComponents(assessmentId: string): Promise<AssessmentComponentDto[]> {
  return apiFetch<AssessmentComponentDto[]>(`/v1/assessment/${assessmentId}/components`);
}

export function addAssessmentComponent(
  assessmentId: string,
  req: { code: string; name: string; maxMarks: number; weightPct?: number; sortOrder: number }
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

export function listMarksForComponent(componentId: string): Promise<MarkDto[]> {
  return apiFetch<MarkDto[]>(`/v1/assessment/components/${componentId}/marks`);
}

export function enterMark(
  componentId: string,
  req: {
    schoolId: string;
    studentId: string;
    rawMarks?: number;
    gradeLetter?: string;
    remarks?: string;
    isAbsent: boolean;
    enteredByStaffId?: string;
  }
): Promise<MarkDto> {
  return apiFetch<MarkDto>(`/v1/assessment/components/${componentId}/marks`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

// -------------------------- Library --------------------------

export type LibraryTitleDto = {
  id: string;
  schoolId: string;
  isbn: string | null;
  title: string;
  author: string | null;
  publisher: string | null;
  year: number | null;
  subjectTags: string[] | null;
};

export function listLibraryTitles(schoolId: string, q?: string): Promise<LibraryTitleDto[]> {
  const params = new URLSearchParams({ schoolId });
  if (q) params.set("q", q);
  return apiFetch<LibraryTitleDto[]>(`/v1/library/titles?${params.toString()}`);
}

export function createLibraryTitle(
  schoolId: string,
  req: { isbn?: string; title: string; author?: string; publisher?: string; year?: number }
): Promise<LibraryTitleDto> {
  return apiFetch<LibraryTitleDto>(`/v1/library/titles?schoolId=${schoolId}`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export type LibraryCopyDto = { id: string; titleId: string; barcode: string; status: string };

export function listLibraryCopies(titleId: string): Promise<LibraryCopyDto[]> {
  return apiFetch<LibraryCopyDto[]>(`/v1/library/titles/${titleId}/copies`);
}

export function addLibraryCopy(titleId: string, barcode: string): Promise<LibraryCopyDto> {
  return apiFetch<LibraryCopyDto>(`/v1/library/titles/${titleId}/copies`, {
    method: "POST",
    body: JSON.stringify({ barcode }),
  });
}

export type LibraryIssueDto = {
  id: string;
  copyId: string;
  memberType: string;
  memberId: string;
  issuedOn: string;
  dueOn: string;
  returnedOn: string | null;
  fine: number;
  finePaid: boolean;
};

export function issueLibraryCopy(req: {
  schoolId: string;
  copyId: string;
  memberType: string;
  memberId: string;
  dueOn: string;
}): Promise<LibraryIssueDto> {
  return apiFetch<LibraryIssueDto>("/v1/library/issues", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function returnLibraryCopy(issueId: string): Promise<LibraryIssueDto> {
  return apiFetch<LibraryIssueDto>(`/v1/library/issues/${issueId}/return`, { method: "POST" });
}

export function activeLibraryIssuesForMember(memberType: string, memberId: string): Promise<LibraryIssueDto[]> {
  return apiFetch<LibraryIssueDto[]>(`/v1/library/issues/active?memberType=${memberType}&memberId=${memberId}`);
}

// -------------------------- Comms --------------------------

export const ANNOUNCEMENT_SCOPES = ["school", "grade", "section", "custom"] as const;

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

export function createAnnouncement(req: {
  schoolId: string;
  scopeType: string;
  scopeIds?: string[];
  title: string;
  body: string;
  channels?: string[];
  createdByUserId?: string;
}): Promise<AnnouncementDto> {
  return apiFetch<AnnouncementDto>("/v1/comms/announcements", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function publishAnnouncement(id: string): Promise<AnnouncementDto> {
  return apiFetch<AnnouncementDto>(`/v1/comms/announcements/${id}/publish`, { method: "POST" });
}

export type MessageThreadDto = {
  id: string;
  schoolId: string;
  subjectStudentId: string | null;
  participants: string[];
  lastMessageAt: string | null;
};

export function listThreads(userAccountId: string): Promise<MessageThreadDto[]> {
  return apiFetch<MessageThreadDto[]>(`/v1/comms/threads?userAccountId=${userAccountId}`);
}

export function createThread(req: {
  schoolId: string;
  subjectStudentId?: string;
  participants: string[];
}): Promise<MessageThreadDto> {
  return apiFetch<MessageThreadDto>("/v1/comms/threads", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export type UserDirectoryEntryDto = {
  userAccountId: string;
  subjectType: string;
  subjectId: string | null;
  displayName: string;
  email: string | null;
  phone: string | null;
};

export function listDirectory(schoolId: string, q?: string, subjectType?: string): Promise<UserDirectoryEntryDto[]> {
  const params = new URLSearchParams({ schoolId });
  if (q) params.set("q", q);
  if (subjectType) params.set("subjectType", subjectType);
  return apiFetch<UserDirectoryEntryDto[]>(`/v1/people/directory?${params.toString()}`);
}

export type MessageDto = { id: string; threadId: string; senderUserId: string; body: string; sentAt: string };

export function listMessages(threadId: string): Promise<MessageDto[]> {
  return apiFetch<MessageDto[]>(`/v1/comms/threads/${threadId}/messages`);
}

export function sendMessage(threadId: string, senderUserId: string, body: string): Promise<MessageDto> {
  return apiFetch<MessageDto>(`/v1/comms/threads/${threadId}/messages`, {
    method: "POST",
    body: JSON.stringify({ senderUserId, body }),
  });
}

// -------------------------- LMS --------------------------

export const LESSON_PLAN_STATUSES = ["draft", "approved", "delivered", "archived"] as const;
export const ASSIGNMENT_SUBMISSION_TYPES = ["file", "text", "quiz", "offline", "lti"];

export type LessonPlanDto = {
  id: string;
  schoolId: string;
  sectionId: string;
  subjectId: string;
  curriculumNodeId: string | null;
  title: string;
  plannedFor: string | null;
  durationMinutes: number | null;
  status: string;
  createdByStaffId: string | null;
};

export function listLessonPlans(sectionId: string): Promise<LessonPlanDto[]> {
  return apiFetch<LessonPlanDto[]>(`/v1/lms/lesson-plans?sectionId=${sectionId}`);
}

export function createLessonPlan(req: {
  schoolId: string;
  sectionId: string;
  subjectId: string;
  title: string;
  plannedFor?: string;
  durationMinutes?: number;
}): Promise<LessonPlanDto> {
  return apiFetch<LessonPlanDto>("/v1/lms/lesson-plans", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function setLessonPlanStatus(id: string, status: string): Promise<LessonPlanDto> {
  return apiFetch<LessonPlanDto>(`/v1/lms/lesson-plans/${id}/status`, {
    method: "POST",
    body: JSON.stringify({ status }),
  });
}

export type AssignmentDto = {
  id: string;
  schoolId: string;
  sectionId: string;
  subjectId: string;
  title: string;
  instructions: string | null;
  submissionType: string;
  dueAt: string | null;
  maxMarks: number | null;
  status: string;
  createdByStaffId: string | null;
};

export function listAssignments(sectionId: string): Promise<AssignmentDto[]> {
  return apiFetch<AssignmentDto[]>(`/v1/lms/assignments?sectionId=${sectionId}`);
}

export function createAssignment(req: {
  schoolId: string;
  sectionId: string;
  subjectId: string;
  title: string;
  instructions?: string;
  submissionType: string;
  dueAt?: string;
  maxMarks?: number;
}): Promise<AssignmentDto> {
  return apiFetch<AssignmentDto>("/v1/lms/assignments", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export type AssignmentSubmissionDto = {
  id: string;
  assignmentId: string;
  studentId: string;
  body: string | null;
  submittedAt: string;
  marks: number | null;
  feedback: string | null;
  gradedAt: string | null;
};

export function listSubmissions(assignmentId: string): Promise<AssignmentSubmissionDto[]> {
  return apiFetch<AssignmentSubmissionDto[]>(`/v1/lms/assignments/${assignmentId}/submissions`);
}

export function gradeSubmission(id: string, marks: number, feedback?: string): Promise<AssignmentSubmissionDto> {
  return apiFetch<AssignmentSubmissionDto>(`/v1/lms/submissions/${id}/grade`, {
    method: "POST",
    body: JSON.stringify({ marks, feedback }),
  });
}
