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

import { createApiClient } from "@schoolsoft/api-client";

export { ApiError } from "@schoolsoft/api-client";

const API_BASE = process.env.NEXT_PUBLIC_SCHOOLSOFT_API_URL ?? "http://localhost:8080";
const SESSION_KEY = "schoolsoft_admin_session";

export type Session = {
  accessToken: string;
  refreshToken: string;
  userAccountId: string;
  subjectType: string;
  schoolId: string;
  chainSchema: string;
  /** Screen keys unlocked by the caller's role grants — see /v1/iam/me/screens. "dashboard" is always implicitly allowed. */
  screens: string[];
  roleCodes: string[];
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
    schoolId: res.profile.schoolId,
    chainSchema: res.profile.chainSchema,
    screens: [],
    roleCodes: [],
  };
  setSession(session);
  try {
    const my = await getMyScreens();
    session = { ...session, screens: my.screenKeys, roleCodes: my.roleCodes };
    setSession(session);
  } catch {
    // Non-staff principals (guardian/student) have no role grants — leave screens empty.
  }
  return session;
}

export function getMyScreens(): Promise<{ screenKeys: string[]; roleCodes: string[] }> {
  return apiFetch<{ screenKeys: string[]; roleCodes: string[] }>("/v1/iam/me/screens");
}

/**
 * The person behind the login. The JWT carries only `user_account.id`, and
 * several endpoints want the staff row itself — a leave decision records the
 * approver, a closure records who declared it — so screens that act *as* the
 * signed-in staff member resolve it once here.
 */
export type MeDto = {
  userAccountId: string;
  subjectType: string;
  /** The staff/guardian/student row id; empty string when the account has none. */
  subjectId: string;
  schoolId: string;
};

export function getMe(): Promise<MeDto> {
  return apiFetch<MeDto>("/v1/iam/me");
}

export function hasScreen(session: Session | null, screenKey: string): boolean {
  if (!session) return false;
  if (screenKey === "dashboard") return true;
  return (session.screens ?? []).includes(screenKey);
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
  campusId: string | null;
};

export function listSections(schoolId: string, academicYearId?: string): Promise<SectionDto[]> {
  const params = new URLSearchParams();
  if (academicYearId) params.set("academicYearId", academicYearId);
  const query = params.toString();
  return apiFetch<SectionDto[]>(
    `/v1/tenancy/schools/${schoolId}/sections${query ? `?${query}` : ""}`
  );
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

export function rosterForSection(sectionId: string, activeOnly = true): Promise<EnrolmentDto[]> {
  return apiFetch<EnrolmentDto[]>(`/v1/enrolment/sections/${sectionId}?activeOnly=${activeOnly}`);
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
  /** planning | active | closed — a closed year refuses writes to its records. */
  status: string;
  closedAt: string | null;
  reopenedAt: string | null;
  reopenReason: string | null;
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

/**
 * Give either `periodId` — the times then come from the grade's bell schedule
 * and move with it — or explicit `startsAt`/`endsAt`.
 */
export function createTimetableSlot(req: {
  sectionId: string;
  subjectId: string;
  teacherStaffId: string;
  dayOfWeek: number;
  periodNo: number;
  startsAt?: string;
  endsAt?: string;
  room?: string;
  effectiveFrom: string;
  effectiveTo?: string;
  periodId?: string;
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

// Reopening a locked or published assessment needs a reason; ordinary forward
// transitions do not.
export function setAssessmentStatus(
  id: string,
  status: string,
  reason?: string,
): Promise<AssessmentDto> {
  return apiFetch<AssessmentDto>(`/v1/assessment/${id}/status`, {
    method: "POST",
    body: JSON.stringify({ status, reason }),
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
  /** entered | pending | absent | medical_leave | exempt — a blank is not a zero. */
  status: string;
  isAbsent: boolean;
  revisionCount: number;
};

/**
 * The statuses a teacher picks between. `entered` is implied by typing a mark,
 * so the picker offers the four that mean "there will be no number, and here
 * is why".
 */
export const MARK_STATUSES = ["entered", "pending", "absent", "medical_leave", "exempt"] as const;

export function listMarksForComponent(componentId: string): Promise<MarkDto[]> {
  return apiFetch<MarkDto[]>(`/v1/assessment/components/${componentId}/marks`);
}

export function enterMark(
  componentId: string,
  req: {
    schoolId: string;
    studentId: string;
    rawMarks?: number;
    status?: string;
    gradeLetter?: string;
    remarks?: string;
    isAbsent?: boolean;
    reason?: string;
    enteredByStaffId?: string;
  }
): Promise<MarkDto> {
  return apiFetch<MarkDto>(`/v1/assessment/components/${componentId}/marks`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export type BulkMarkEntry = {
  studentId: string;
  rawMarks?: number;
  status?: string;
  gradeLetter?: string;
  remarks?: string;
};

/**
 * What the bulk endpoint did and what it refused. A mark above the component
 * maximum comes back in `rejected` with the reason; the rest are stored, so a
 * single typo does not throw away a whole section's entry.
 */
export type BulkMarkResult = {
  componentId: string;
  accepted: number;
  marks: MarkDto[];
  rejected: { studentId: string; reason: string }[];
};

export function enterMarksInBulk(req: {
  schoolId: string;
  componentId: string;
  entries: BulkMarkEntry[];
  enteredByStaffId?: string;
  reason?: string;
}): Promise<BulkMarkResult> {
  return apiFetch<BulkMarkResult>("/v1/assessment/marks/bulk", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export type AssessmentValidationDto = {
  assessmentId: string;
  valid: boolean;
  issues: string[];
};

export function validateAssessment(assessmentId: string): Promise<AssessmentValidationDto> {
  return apiFetch<AssessmentValidationDto>(`/v1/assessment/${assessmentId}/validation`);
}

export type MarkRevisionDto = {
  id: string;
  markId: string;
  revisionNo: number;
  kind: string;
  oldRawMarks: number | null;
  oldStatus: string;
  newRawMarks: number | null;
  newStatus: string;
  reason: string;
  changedAt: string;
};

export function listMarkRevisions(markId: string): Promise<MarkRevisionDto[]> {
  return apiFetch<MarkRevisionDto[]>(`/v1/assessment/marks/${markId}/revisions`);
}

export type MarkReevaluationDto = {
  id: string;
  markId: string;
  studentId: string;
  reason: string;
  requestedAt: string;
  status: string;
  decidedAt: string | null;
  decisionNote: string | null;
};

export function listReevaluations(studentId: string): Promise<MarkReevaluationDto[]> {
  return apiFetch<MarkReevaluationDto[]>(`/v1/assessment/re-evaluations?studentId=${studentId}`);
}

export function decideReevaluation(
  id: string,
  req: { outcome: string; newRawMarks?: number; reason: string }
): Promise<MarkReevaluationDto> {
  return apiFetch<MarkReevaluationDto>(`/v1/assessment/re-evaluations/${id}/decide`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

// -------------------------- Report cards (Phase 5) --------------------------

export type ReportCardDto = {
  id: string;
  schoolId: string;
  studentId: string;
  sectionId: string | null;
  academicYearId: string;
  termId: string | null;
  strategyCode: string;
  templateCode: string;
  status: string;
  version: number;
  isLocked: boolean;
  gradeScaleCode: string | null;
  totalMarks: number | null;
  totalMaxMarks: number | null;
  overallPct: number | null;
  overallGrade: string | null;
  classRank: number | null;
  classSize: number | null;
  percentile: number | null;
  attendanceWorkingDays: number | null;
  attendancePresentDays: number | null;
  attendancePct: number | null;
  promotionDecision: string | null;
  teacherRemarks: string | null;
  principalRemarks: string | null;
  enrolledFrom: string | null;
  termsAttended: number | null;
  termsInYear: number | null;
  coverageNote: string | null;
  publishedAt: string | null;
  generatedAt: string;
};

export type ReportCardSubjectRow = {
  subjectId: string;
  subjectCode: string;
  subjectName: string;
  origin: string;
  marksObtained: number | null;
  maxMarks: number | null;
  percentage: number | null;
  gradeLetter: string | null;
  resultStatus: string;
  /** What the row prints — "AB" for an absence, never a zero. */
  display: string;
  passing: boolean | null;
  remarks: string | null;
  sortOrder: number;
};

export type ReportCardDetailDto = {
  card: ReportCardDto;
  subjects: ReportCardSubjectRow[];
  coScholastic: { areaCode: string; areaName: string; rating: string; remarks: string | null; sortOrder: number }[];
  payload: Record<string, unknown>;
};

export const PROMOTION_DECISIONS = ["promote", "detain", "graduate"] as const;

export function reportCardsForStudent(studentId: string): Promise<ReportCardDto[]> {
  return apiFetch<ReportCardDto[]>(`/v1/assessment/report-cards/students/${studentId}`);
}

export function reportCardDetail(id: string): Promise<ReportCardDetailDto> {
  return apiFetch<ReportCardDetailDto>(`/v1/assessment/report-cards/${id}`);
}

export function generateReportCard(req: {
  schoolId: string;
  studentId: string;
  academicYearId: string;
  termId?: string;
  strategyCode: string;
  templateCode: string;
  teacherRemarks?: string;
  principalRemarks?: string;
  promotionDecision?: string;
  coScholastic?: { areaCode: string; areaName: string; rating: string; remarks?: string; sortOrder: number }[];
}): Promise<ReportCardDto> {
  return apiFetch<ReportCardDto>("/v1/assessment/report-cards", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

/** Generates the whole section in one run, which is also what ranks it. */
export function generateReportCardsForSection(req: {
  schoolId: string;
  sectionId: string;
  academicYearId: string;
  termId?: string;
  strategyCode: string;
  templateCode: string;
}): Promise<ReportCardDto[]> {
  return apiFetch<ReportCardDto[]>("/v1/assessment/report-cards/sections", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function lockReportCard(id: string): Promise<ReportCardDto> {
  return apiFetch<ReportCardDto>(`/v1/assessment/report-cards/${id}/lock`, { method: "POST" });
}

export function unlockReportCard(id: string, reason: string): Promise<ReportCardDto> {
  return apiFetch<ReportCardDto>(`/v1/assessment/report-cards/${id}/unlock`, {
    method: "POST",
    body: JSON.stringify({ reason }),
  });
}

export function publishReportCard(id: string): Promise<ReportCardDto> {
  return apiFetch<ReportCardDto>(`/v1/assessment/report-cards/${id}/publish`, { method: "POST" });
}

export function setPromotionDecision(id: string, decision: string): Promise<ReportCardDto> {
  return apiFetch<ReportCardDto>(`/v1/assessment/report-cards/${id}/promotion`, {
    method: "POST",
    body: JSON.stringify({ decision }),
  });
}

export type AssessmentPolicyDto = {
  schoolId: string;
  duesBlockPolicy: string;
  duesBlockThreshold: number;
  weightTolerancePct: number;
};

export function getAssessmentPolicy(schoolId: string): Promise<AssessmentPolicyDto> {
  return apiFetch<AssessmentPolicyDto>(`/v1/assessment/policy?schoolId=${schoolId}`);
}

export function setAssessmentPolicy(req: {
  schoolId: string;
  duesBlockPolicy?: string;
  duesBlockThreshold?: number;
  weightTolerancePct?: number;
}): Promise<AssessmentPolicyDto> {
  return apiFetch<AssessmentPolicyDto>("/v1/assessment/policy", {
    method: "PUT",
    body: JSON.stringify(req),
  });
}

// -------------------------- Exams (Phase 5) --------------------------

export type ExamScheduleDto = {
  id: string;
  schoolId: string;
  academicYearId: string;
  termId: string | null;
  code: string;
  name: string;
  startsOn: string;
  endsOn: string;
  status: string;
  publishedAt: string | null;
  sessionCount: number;
};

export type ExamSessionDto = {
  id: string;
  examScheduleId: string;
  gradeId: string;
  subjectId: string;
  subjectCode: string;
  subjectName: string;
  paperCode: string;
  name: string;
  onDate: string;
  startsAt: string;
  endsAt: string;
  room: string | null;
  invigilatorStaffId: string | null;
  maxMarks: number | null;
};

export type ExamClashDto = {
  studentId: string;
  sessionAId: string;
  sessionBId: string;
  onDate: string;
  subjectA: string;
  subjectB: string;
};

export type HallTicketDto = {
  id: string;
  examScheduleId: string;
  studentId: string;
  studentName: string;
  admissionNo: string;
  ticketNo: string;
  seatNo: string | null;
  issuedAt: string;
  sessions: ExamSessionDto[];
};

export function listExamSchedules(schoolId: string, academicYearId?: string): Promise<ExamScheduleDto[]> {
  const params = new URLSearchParams({ schoolId });
  if (academicYearId) params.set("academicYearId", academicYearId);
  return apiFetch<ExamScheduleDto[]>(`/v1/exams/schedules?${params.toString()}`);
}

export function createExamSchedule(req: {
  schoolId: string;
  academicYearId: string;
  termId?: string;
  code: string;
  name: string;
  startsOn: string;
  endsOn: string;
}): Promise<ExamScheduleDto> {
  return apiFetch<ExamScheduleDto>("/v1/exams/schedules", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function listExamSessions(scheduleId: string): Promise<ExamSessionDto[]> {
  return apiFetch<ExamSessionDto[]>(`/v1/exams/schedules/${scheduleId}/sessions`);
}

export function addExamSession(
  scheduleId: string,
  req: {
    gradeId: string;
    subjectId: string;
    paperCode?: string;
    name: string;
    onDate: string;
    startsAt: string;
    endsAt: string;
    room?: string;
    invigilatorStaffId?: string;
    maxMarks?: number;
  }
): Promise<ExamSessionDto> {
  return apiFetch<ExamSessionDto>(`/v1/exams/schedules/${scheduleId}/sessions`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function deleteExamSession(sessionId: string): Promise<void> {
  return apiFetch<void>(`/v1/exams/sessions/${sessionId}`, { method: "DELETE" });
}

/** Per-student clashes; publication is refused while any stand. */
export function examClashes(scheduleId: string): Promise<{ clashCount: number; clashes: ExamClashDto[] }> {
  return apiFetch<{ clashCount: number; clashes: ExamClashDto[] }>(
    `/v1/exams/schedules/${scheduleId}/clashes`
  );
}

export function publishExamSchedule(scheduleId: string): Promise<ExamScheduleDto> {
  return apiFetch<ExamScheduleDto>(`/v1/exams/schedules/${scheduleId}/publish`, { method: "POST" });
}

export function unpublishExamSchedule(scheduleId: string): Promise<ExamScheduleDto> {
  return apiFetch<ExamScheduleDto>(`/v1/exams/schedules/${scheduleId}/unpublish`, { method: "POST" });
}

export function issueHallTickets(scheduleId: string): Promise<HallTicketDto[]> {
  return apiFetch<HallTicketDto[]>(`/v1/exams/schedules/${scheduleId}/hall-tickets`, { method: "POST" });
}

export function listHallTickets(scheduleId: string): Promise<HallTicketDto[]> {
  return apiFetch<HallTicketDto[]>(`/v1/exams/schedules/${scheduleId}/hall-tickets`);
}

// -------------------------- Calendar & year lifecycle (Phase 1) --------------------------

export type CalendarEntryDto = {
  id: string;
  schoolId: string;
  academicYearId: string | null;
  onDate: string;
  kind: string;
  title: string;
  description: string | null;
  gradeId: string | null;
  campusId: string | null;
  source: string;
  declaredAt: string;
};

export const CALENDAR_KINDS = ["holiday", "vacation", "working_saturday", "closure", "exam_day"] as const;

export type DayStatusDto = {
  date: string;
  working: boolean;
  reason: string | null;
  calendarKind: string | null;
};

export type WorkingDayPatternDto = {
  id: string;
  schoolId: string;
  campusId: string | null;
  effectiveFrom: string;
  effectiveTo: string | null;
  /** Mon..Sun, '1' for a teaching day. */
  weekdayMask: string;
  saturdayRule: string;
  notes: string | null;
};

export const SATURDAY_RULES = ["none", "odd", "even", "all"] as const;

export function listCalendarEntries(schoolId: string, from: string, to: string): Promise<CalendarEntryDto[]> {
  const params = new URLSearchParams({ schoolId, from, to });
  return apiFetch<CalendarEntryDto[]>(`/v1/calendar/entries?${params.toString()}`);
}

export function createCalendarEntry(req: {
  schoolId: string;
  academicYearId?: string;
  onDate: string;
  kind: string;
  title: string;
  description?: string;
  gradeId?: string;
  campusId?: string;
  declaredByStaffId?: string;
}): Promise<CalendarEntryDto> {
  return apiFetch<CalendarEntryDto>("/v1/calendar/entries", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function deleteCalendarEntry(id: string): Promise<void> {
  return apiFetch<void>(`/v1/calendar/entries/${id}`, { method: "DELETE" });
}

export function calendarDays(
  schoolId: string,
  from: string,
  to: string,
  gradeId?: string
): Promise<DayStatusDto[]> {
  const params = new URLSearchParams({ schoolId, from, to });
  if (gradeId) params.set("gradeId", gradeId);
  return apiFetch<DayStatusDto[]>(`/v1/calendar/days?${params.toString()}`);
}

export function listWorkingDayPatterns(schoolId: string): Promise<WorkingDayPatternDto[]> {
  return apiFetch<WorkingDayPatternDto[]>(`/v1/calendar/patterns?schoolId=${schoolId}`);
}

export function createWorkingDayPattern(req: {
  schoolId: string;
  campusId?: string;
  effectiveFrom: string;
  effectiveTo?: string;
  weekdayMask: string;
  saturdayRule: string;
  notes?: string;
}): Promise<WorkingDayPatternDto> {
  return apiFetch<WorkingDayPatternDto>("/v1/calendar/patterns", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

/**
 * A same-day closure does three things together: the day stops counting,
 * attendance already marked is voided (retained, not deleted), and the
 * affected guardians are told. The result reports all three.
 */
export type ClosureResultDto = {
  entry: CalendarEntryDto;
  voidedAttendanceRecords: number;
  guardiansNotified: number;
};

export function declareClosure(req: {
  schoolId: string;
  onDate: string;
  title: string;
  description?: string;
  gradeId?: string;
  campusId?: string;
  declaredByStaffId?: string;
}): Promise<ClosureResultDto> {
  return apiFetch<ClosureResultDto>("/v1/calendar/closures", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export type TermDto = {
  id: string;
  academicYearId: string;
  code: string;
  name: string;
  startsOn: string;
  endsOn: string;
};

export function listTerms(academicYearId: string): Promise<TermDto[]> {
  return apiFetch<TermDto[]>(`/v1/tenancy/academic-years/${academicYearId}/terms`);
}

export function createTerm(
  academicYearId: string,
  req: { code: string; name: string; startsOn: string; endsOn: string }
): Promise<TermDto> {
  return apiFetch<TermDto>(`/v1/tenancy/academic-years/${academicYearId}/terms`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function createAcademicYear(
  schoolId: string,
  req: { code: string; startsOn: string; endsOn: string; isCurrent?: boolean }
): Promise<AcademicYearDto> {
  return apiFetch<AcademicYearDto>(`/v1/tenancy/schools/${schoolId}/academic-years`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

/** Closing a year makes it read-only; reopening it needs a reason and is audited. */
export function setAcademicYearStatus(
  academicYearId: string,
  req: { status: string; actingStaffId?: string; reason?: string }
): Promise<AcademicYearDto> {
  return apiFetch<AcademicYearDto>(`/v1/tenancy/academic-years/${academicYearId}/status`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

// -------------------------- Amendments, leave & cover (Phase 3) --------------------------

export type AttendanceAmendmentDto = {
  id: string;
  attendanceRecordId: string;
  studentId: string;
  sectionId: string;
  onDate: string;
  periodNo: number | null;
  oldStatus: string;
  newStatus: string;
  reason: string;
  requestedAt: string;
  status: string;
  decidedAt: string | null;
  decisionNote: string | null;
};

export function listAmendments(schoolId: string, status?: string): Promise<AttendanceAmendmentDto[]> {
  const params = new URLSearchParams({ schoolId });
  if (status) params.set("status", status);
  return apiFetch<AttendanceAmendmentDto[]>(`/v1/attendance/amendments?${params.toString()}`);
}

export function requestAmendment(req: {
  schoolId: string;
  studentId: string;
  onDate: string;
  periodNo?: number;
  newStatus: string;
  reason: string;
}): Promise<AttendanceAmendmentDto> {
  return apiFetch<AttendanceAmendmentDto>("/v1/attendance/amendments", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function decideAmendment(
  id: string,
  req: { status: string; reason: string }
): Promise<AttendanceAmendmentDto> {
  return apiFetch<AttendanceAmendmentDto>(`/v1/attendance/amendments/${id}/decide`, {
    method: "POST",
    body: JSON.stringify(req),
  });
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
  decidedAt: string | null;
};

export function listLeave(schoolId: string, status?: string): Promise<LeaveApplicationDto[]> {
  const params = new URLSearchParams({ schoolId });
  if (status) params.set("status", status);
  return apiFetch<LeaveApplicationDto[]>(`/v1/attendance/leave?${params.toString()}`);
}

/** Approval materialises the covered working days; revoking one unwinds them. */
export function decideLeave(
  id: string,
  req: { status: string; approverStaffId: string }
): Promise<LeaveApplicationDto> {
  return apiFetch<LeaveApplicationDto>(`/v1/attendance/leave/${id}/decide`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export type CoverDto = {
  id: string;
  slotId: string;
  sectionId: string;
  sectionLabel: string;
  subjectName: string;
  onDate: string;
  periodNo: number;
  startsAt: string;
  endsAt: string;
  room: string | null;
  absentStaffId: string;
  absentStaffName: string;
  substituteStaffId: string;
  substituteStaffName: string;
  reason: string | null;
  cancelled: boolean;
};

export type CoverNeedDto = {
  slotId: string;
  sectionId: string;
  sectionLabel: string;
  subjectName: string;
  onDate: string;
  periodNo: number;
  startsAt: string;
  endsAt: string;
  room: string | null;
  absentStaffId: string;
  absentStaffName: string;
  cover: CoverDto | null;
  candidates: { staffId: string; name: string; periodsThatDay: number }[];
};

/**
 * One section, one date. The school calendar decides whether there is school
 * at all; a published exam schedule replaces the periods on the days it
 * covers; cover names who is actually walking through the door.
 */
export type SectionDayDto = {
  date: string;
  working: boolean;
  reason: string | null;
  calendarKind: string | null;
  slots: TimetableSlotDto[];
  covers: CoverDto[];
  examDay: boolean;
  examSessions: ExamSessionDto[];
};

export function sectionDay(sectionId: string, date: string): Promise<SectionDayDto> {
  return apiFetch<SectionDayDto>(`/v1/timetable/sections/${sectionId}/day?date=${date}`);
}

export function coverNeeds(schoolId: string, date: string): Promise<CoverNeedDto[]> {
  const params = new URLSearchParams({ schoolId, date });
  return apiFetch<CoverNeedDto[]>(`/v1/timetable/cover/needs?${params.toString()}`);
}

export function coverForDay(schoolId: string, date: string): Promise<CoverDto[]> {
  const params = new URLSearchParams({ schoolId, date });
  return apiFetch<CoverDto[]>(`/v1/timetable/cover?${params.toString()}`);
}

export function assignCover(req: {
  slotId: string;
  onDate: string;
  substituteStaffId: string;
  reason?: string;
}): Promise<CoverDto> {
  return apiFetch<CoverDto>("/v1/timetable/cover", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function cancelCover(id: string): Promise<CoverDto> {
  return apiFetch<CoverDto>(`/v1/timetable/cover/${id}`, { method: "DELETE" });
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

// -------------------------- Transport --------------------------

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

export function createVehicle(
  schoolId: string,
  req: { registrationNo: string; model?: string; capacity?: number }
): Promise<VehicleDto> {
  return apiFetch<VehicleDto>(`/v1/transport/vehicles?schoolId=${schoolId}`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export type DriverDto = {
  id: string;
  schoolId: string;
  staffId: string | null;
  name: string;
  phone: string | null;
  licenseNo: string | null;
  isActive: boolean;
};

export function listDrivers(schoolId: string): Promise<DriverDto[]> {
  return apiFetch<DriverDto[]>(`/v1/transport/drivers?schoolId=${schoolId}`);
}

export function createDriver(
  schoolId: string,
  req: { name: string; phone?: string; licenseNo?: string; staffId?: string }
): Promise<DriverDto> {
  return apiFetch<DriverDto>(`/v1/transport/drivers?schoolId=${schoolId}`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export type TransportRouteDto = {
  id: string;
  schoolId: string;
  code: string;
  name: string;
  direction: string;
  isActive: boolean;
};

export function listTransportRoutes(schoolId: string): Promise<TransportRouteDto[]> {
  return apiFetch<TransportRouteDto[]>(`/v1/transport/routes?schoolId=${schoolId}`);
}

export function createTransportRoute(
  schoolId: string,
  req: { code: string; name: string; direction?: string }
): Promise<TransportRouteDto> {
  return apiFetch<TransportRouteDto>(`/v1/transport/routes?schoolId=${schoolId}`, {
    method: "POST",
    body: JSON.stringify(req),
  });
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

export function listTransportStops(routeId: string): Promise<TransportStopDto[]> {
  return apiFetch<TransportStopDto[]>(`/v1/transport/routes/${routeId}/stops`);
}

export function addTransportStop(
  routeId: string,
  req: { name: string; sortOrder: number; lat?: number; lng?: number; fee?: number }
): Promise<TransportStopDto> {
  return apiFetch<TransportStopDto>(`/v1/transport/routes/${routeId}/stops`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export type StudentTransportDto = {
  id: string;
  studentId: string;
  routeId: string;
  stopId: string;
  startsOn: string;
  endsOn: string | null;
};

export function studentsOnRoute(routeId: string): Promise<StudentTransportDto[]> {
  return apiFetch<StudentTransportDto[]>(`/v1/transport/routes/${routeId}/students`);
}

export function assignStudentTransport(req: {
  schoolId: string;
  studentId: string;
  routeId: string;
  stopId: string;
  startsOn: string;
}): Promise<StudentTransportDto> {
  return apiFetch<StudentTransportDto>("/v1/transport/student-assignments", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export type TransportManifestEntry = { status: string; at: string };

export type TripDto = {
  id: string;
  schoolId: string;
  routeId: string;
  vehicleId: string;
  driverId: string;
  direction: string;
  startedAt: string;
  endedAt: string | null;
  manifest: Record<string, TransportManifestEntry>;
};

export function listTripsForSchool(schoolId: string, limit = 30): Promise<TripDto[]> {
  const params = new URLSearchParams({ schoolId, limit: String(limit) });
  return apiFetch<TripDto[]>(`/v1/transport/trips?${params.toString()}`);
}

export type GeofenceStatusDto = {
  vehicleId: string;
  stopId: string;
  insideGeofence: boolean;
  distanceMeters: number;
  geofenceRadiusM: number;
  asOf: string;
};

export function geofenceStatus(vehicleId: string, stopId: string): Promise<GeofenceStatusDto> {
  const params = new URLSearchParams({ vehicleId, stopId });
  return apiFetch<GeofenceStatusDto>(`/v1/transport/geofence-status?${params.toString()}`);
}

// -------------------------- Structure: subjects, electives, roll numbers, bells (Phase 2) ---

export function createSubject(
  schoolId: string,
  req: { code: string; name: string; boardCode?: string }
): Promise<SubjectDto> {
  return apiFetch<SubjectDto>(`/v1/tenancy/schools/${schoolId}/subjects`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

/**
 * What a section is taught and by whom. `isElective` is the flag that separates
 * a subject the whole section takes from an option only its electors take —
 * the distinction `SubjectSetResolver` reads.
 */
export type SectionTeacherDto = {
  id: string;
  sectionId: string;
  subjectId: string;
  subjectName: string;
  teacherStaffId: string;
  teacherName: string;
  isPrimary: boolean;
  isElective: boolean;
};

export function listSectionTeachers(sectionId: string): Promise<SectionTeacherDto[]> {
  return apiFetch<SectionTeacherDto[]>(`/v1/tenancy/sections/${sectionId}/teachers`);
}

export function assignSectionTeacher(
  sectionId: string,
  req: { subjectId: string; teacherStaffId: string; isPrimary: boolean; isElective: boolean }
): Promise<SectionTeacherDto> {
  return apiFetch<SectionTeacherDto>(`/v1/tenancy/sections/${sectionId}/teachers`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

/** An option block a grade offers — an IGCSE block, a Class 11 stream. */
export type ElectiveGroupDto = {
  id: string;
  schoolId: string;
  academicYearId: string;
  gradeId: string;
  code: string;
  name: string;
  minPicks: number;
  maxPicks: number;
  options: { subjectId: string; subjectCode: string; subjectName: string; capacity: number | null }[];
};

export function listElectiveGroups(
  schoolId: string,
  academicYearId?: string,
  gradeId?: string
): Promise<ElectiveGroupDto[]> {
  const params = new URLSearchParams();
  if (academicYearId) params.set("academicYearId", academicYearId);
  if (gradeId) params.set("gradeId", gradeId);
  const query = params.toString();
  return apiFetch<ElectiveGroupDto[]>(
    `/v1/tenancy/schools/${schoolId}/elective-groups${query ? `?${query}` : ""}`
  );
}

export function createElectiveGroup(
  schoolId: string,
  req: {
    academicYearId: string;
    gradeId: string;
    code: string;
    name: string;
    minPicks: number;
    maxPicks: number;
    subjectIds: string[];
  }
): Promise<ElectiveGroupDto> {
  return apiFetch<ElectiveGroupDto>(`/v1/tenancy/schools/${schoolId}/elective-groups`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

/**
 * One subject a student studies. `origin` is `compulsory` when the section
 * takes it as a whole and `elective` when the student chose it, so a screen can
 * show which rows are the child's own decision.
 */
export type StudentSubjectDto = {
  id: string;
  enrolmentId: string;
  studentId: string;
  subjectId: string;
  subjectCode: string;
  subjectName: string;
  origin: string;
  electiveGroupId: string | null;
  electiveGroupCode: string | null;
  status: string;
  effectiveFrom: string | null;
  effectiveTo: string | null;
};

/** The resolved set: the section's compulsory subjects plus this student's elections. */
export function subjectsForEnrolment(enrolmentId: string, onDate?: string): Promise<StudentSubjectDto[]> {
  const query = onDate ? `?onDate=${onDate}` : "";
  return apiFetch<StudentSubjectDto[]>(`/v1/enrolment/${enrolmentId}/subjects${query}`);
}

export function electionsForEnrolment(enrolmentId: string): Promise<StudentSubjectDto[]> {
  return apiFetch<StudentSubjectDto[]>(`/v1/enrolment/${enrolmentId}/elections`);
}

export function electSubject(
  enrolmentId: string,
  req: { subjectId: string; electiveGroupId?: string; effectiveFrom?: string }
): Promise<StudentSubjectDto> {
  return apiFetch<StudentSubjectDto>(`/v1/enrolment/${enrolmentId}/elections`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

/** Ends an election from a date; the marks earned under it stay. */
export function dropElection(
  enrolmentId: string,
  req: { subjectId: string; effectiveTo?: string }
): Promise<void> {
  return apiFetch<void>(`/v1/enrolment/${enrolmentId}/elections/drop`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

/**
 * `rollNo` left out, the section's number series issues the next one.
 * `overCapacityReason` is required only when the section is already full.
 */
export function enrolStudent(req: {
  schoolId: string;
  studentId: string;
  sectionId: string;
  academicYearId: string;
  startsOn: string;
  rollNo?: string;
  overCapacityReason?: string;
}): Promise<EnrolmentDto> {
  return apiFetch<EnrolmentDto>("/v1/enrolment", { method: "POST", body: JSON.stringify(req) });
}

export function transferEnrolment(
  id: string,
  req: { newSectionId: string; rollNo?: string; overCapacityReason?: string }
): Promise<EnrolmentDto> {
  return apiFetch<EnrolmentDto>(`/v1/enrolment/${id}/transfer`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

/**
 * Re-sequences a section's roll numbers from 1 in admission order. Deliberately
 * an explicit action: it changes what is written in every child's exercise book.
 */
export function renumberSection(sectionId: string): Promise<EnrolmentDto[]> {
  return apiFetch<EnrolmentDto[]>(`/v1/enrolment/sections/${sectionId}/renumber`, { method: "POST" });
}

/**
 * The school day for a grade band. A slot references a period rather than
 * repeating its times, so moving the bell moves every affected lesson at once.
 */
export type BellScheduleDto = {
  id: string;
  schoolId: string;
  campusId: string | null;
  code: string;
  name: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  gradeIds: string[];
  periods: {
    id: string;
    periodNo: number;
    label: string;
    startsAt: string;
    endsAt: string;
    isBreak: boolean;
  }[];
};

export function listBellSchedules(schoolId: string): Promise<BellScheduleDto[]> {
  return apiFetch<BellScheduleDto[]>(`/v1/timetable/bell-schedules?schoolId=${schoolId}`);
}

/** The schedule a section follows, through its grade; 204 when its grade has none. */
export function bellScheduleForSection(sectionId: string): Promise<BellScheduleDto | undefined> {
  return apiFetch<BellScheduleDto | undefined>(`/v1/timetable/sections/${sectionId}/bell-schedule`);
}

export function createBellSchedule(req: {
  schoolId: string;
  campusId?: string;
  code: string;
  name: string;
  effectiveFrom: string;
  effectiveTo?: string;
  periods: { periodNo: number; label: string; startsAt: string; endsAt: string; isBreak: boolean }[];
  gradeIds?: string[];
}): Promise<BellScheduleDto> {
  return apiFetch<BellScheduleDto>("/v1/timetable/bell-schedules", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

/** Room clashes and teachers over their weekly load — advisory, not a block. */
export function timetablePublishWarnings(
  sectionId: string
): Promise<{ sectionId: string; warnings: string[]; publishable: boolean }> {
  return apiFetch<{ sectionId: string; warnings: string[]; publishable: boolean }>(
    `/v1/timetable/sections/${sectionId}/publish-warnings`
  );
}

// -------------------------- Fee engine: structures, runs, dunning, reports (Phase 4) ---

export type FeeStructureDto = {
  id: string;
  schoolId: string;
  gradeId: string;
  academicYearId: string;
  name: string;
  schedule: Record<string, unknown> | null;
  lines: {
    id: string;
    feeHeadId: string;
    feeHeadCode: string;
    feeHeadName: string;
    amount: number;
    gstRatePct: number;
  }[];
  total: number;
};

export function listFeeStructures(
  schoolId: string,
  academicYearId?: string,
  gradeId?: string
): Promise<FeeStructureDto[]> {
  const params = new URLSearchParams({ schoolId });
  if (academicYearId) params.set("academicYearId", academicYearId);
  if (gradeId) params.set("gradeId", gradeId);
  return apiFetch<FeeStructureDto[]>(`/v1/fees/structures?${params.toString()}`);
}

export function createFeeStructure(req: {
  schoolId: string;
  gradeId: string;
  academicYearId: string;
  name: string;
  schedule?: Record<string, unknown>;
  lines: { feeHeadId: string; amount: number }[];
}): Promise<FeeStructureDto> {
  return apiFetch<FeeStructureDto>("/v1/fees/structures", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function replaceFeeStructureLines(
  id: string,
  lines: { feeHeadId: string; amount: number }[]
): Promise<FeeStructureDto> {
  return apiFetch<FeeStructureDto>(`/v1/fees/structures/${id}/lines`, {
    method: "PUT",
    body: JSON.stringify({ lines }),
  });
}

/** Next year's structure is a copy — editing it must not touch this year's. */
export function cloneFeeStructure(
  id: string,
  req: { targetAcademicYearId: string; name?: string }
): Promise<FeeStructureDto> {
  return apiFetch<FeeStructureDto>(`/v1/fees/structures/${id}/clone`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

/**
 * `alreadyRun` is the point of the run record: billing a cycle twice is a no-op
 * with the first run's numbers, not an error and not a second set of invoices.
 */
export type FeeRunResultDto = {
  runId: string;
  invoicesCreated: number;
  studentsSkipped: number;
  totalBilled: number;
  alreadyRun: boolean;
};

export function generateInvoices(req: {
  schoolId: string;
  academicYearId: string;
  gradeId?: string;
  cycleLabel: string;
  dueOn: string;
  runByStaffId?: string;
}): Promise<FeeRunResultDto> {
  return apiFetch<FeeRunResultDto>("/v1/fees/generate", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export type FeeScheduleRunDto = {
  id: string;
  schoolId: string;
  academicYearId: string;
  cycleLabel: string;
  gradeId: string | null;
  gradeCode: string | null;
  dueOn: string;
  state: string;
  invoicesCreated: number;
  studentsSkipped: number;
  totalBilled: number;
  runByStaffId: string | null;
  createdAt: string;
};

export function listFeeRuns(schoolId: string, academicYearId?: string): Promise<FeeScheduleRunDto[]> {
  const params = new URLSearchParams({ schoolId });
  if (academicYearId) params.set("academicYearId", academicYearId);
  return apiFetch<FeeScheduleRunDto[]>(`/v1/fees/runs?${params.toString()}`);
}

/**
 * Everything that changes a bill after it was issued. A bounced cheque is a
 * `reversal`, never a deleted payment — the school has to be able to show that
 * the money arrived and went away again.
 */
export type FeeAdjustmentDto = {
  id: string;
  schoolId: string;
  feeInvoiceId: string;
  paymentId: string | null;
  kind: string;
  amount: number;
  reason: string;
  approvedByStaffId: string | null;
  createdAt: string;
};

export const FEE_ADJUSTMENT_KINDS = [
  "credit_note",
  "waiver",
  "charge",
  "late_fee",
  "reversal",
  "refund",
] as const;

/** Which way each kind moves the bill, for the operator about to post one. */
export const FEE_ADJUSTMENT_EFFECT: Record<string, string> = {
  credit_note: "lowers what is owed",
  waiver: "lowers what is owed, as a waiver",
  charge: "raises what is owed",
  late_fee: "raises what is owed, as a late fee",
  reversal: "un-does a payment (a bounced cheque)",
  refund: "pays money back out",
};

export function listAdjustments(invoiceId: string): Promise<FeeAdjustmentDto[]> {
  return apiFetch<FeeAdjustmentDto[]>(`/v1/fees/invoices/${invoiceId}/adjustments`);
}

export function postAdjustment(
  invoiceId: string,
  req: {
    schoolId: string;
    kind: string;
    amount: number;
    reason: string;
    paymentId?: string;
    approvedByStaffId?: string;
    feeHeadId?: string;
  }
): Promise<FeeAdjustmentDto> {
  return apiFetch<FeeAdjustmentDto>(`/v1/fees/invoices/${invoiceId}/adjustments`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export type ConcessionDto = {
  id: string;
  kind: string;
  pct: number | null;
  flatAmount: number | null;
  appliesToHeadId: string | null;
  notes: string | null;
};

export function listConcessions(studentId: string, academicYearId?: string): Promise<ConcessionDto[]> {
  const params = new URLSearchParams({ studentId });
  if (academicYearId) params.set("academicYearId", academicYearId);
  return apiFetch<ConcessionDto[]>(`/v1/fees/concessions?${params.toString()}`);
}

/** A concession is a decision about one child's bill, so it records who approved it. */
export function grantConcession(req: {
  schoolId: string;
  studentId: string;
  academicYearId: string;
  kind: string;
  pct?: number;
  flatAmount?: number;
  appliesToHeadId?: string;
  notes?: string;
  approvedByStaffId?: string;
}): Promise<{ id: string }> {
  return apiFetch<{ id: string }>("/v1/fees/concessions", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export type SiblingPolicyDto = {
  id: string;
  schoolId: string;
  academicYearId: string;
  nthChild: number;
  pct: number;
  appliesToHeadId: string | null;
};

export function listSiblingPolicies(
  schoolId: string,
  academicYearId?: string
): Promise<SiblingPolicyDto[]> {
  const params = new URLSearchParams({ schoolId });
  if (academicYearId) params.set("academicYearId", academicYearId);
  return apiFetch<SiblingPolicyDto[]>(`/v1/fees/sibling-policies?${params.toString()}`);
}

export function upsertSiblingPolicy(req: {
  schoolId: string;
  academicYearId: string;
  nthChild: number;
  pct: number;
  appliesToHeadId?: string;
}): Promise<{ id: string }> {
  return apiFetch<{ id: string }>("/v1/fees/sibling-policies", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export type DunningPolicyDto = {
  id: string;
  schoolId: string;
  graceDays: number;
  reminderDays: number[];
  lateFeePct: number | null;
  lateFeeFlat: number | null;
  lateFeeHeadId: string | null;
  isActive: boolean;
};

/** Undefined when the school has none — dunning then leaves its invoices alone. */
export function getDunningPolicy(schoolId: string): Promise<DunningPolicyDto | undefined> {
  return apiFetch<DunningPolicyDto | undefined>(`/v1/fees/dunning-policy?schoolId=${schoolId}`);
}

export function saveDunningPolicy(req: {
  schoolId: string;
  graceDays: number;
  reminderDays: number[];
  lateFeePct?: number;
  lateFeeFlat?: number;
  lateFeeHeadId?: string;
}): Promise<{ id: string }> {
  return apiFetch<{ id: string }>("/v1/fees/dunning-policy", {
    method: "PUT",
    body: JSON.stringify(req),
  });
}

/**
 * One school's dunning pass for a chosen day. `dunning_event` is keyed on
 * (invoice, kind, day), so running it twice does not write to a family twice.
 */
export type DunningResultDto = {
  markedOverdue: number;
  remindersSent: number;
  lateFeesApplied: number;
};

export function runDunning(req: { schoolId: string; asOf?: string }): Promise<DunningResultDto> {
  return apiFetch<DunningResultDto>("/v1/fees/dunning/run", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

/**
 * What came in over a range. The collection total and the ledger's own bank
 * movement are computed separately and returned together, so a mismatch shows
 * up in the report rather than at year end.
 */
export type DayBookDto = {
  from: string;
  to: string;
  collected: number;
  refunded: number;
  net: number;
  ledgerNet: number;
  reconciles: boolean;
  byMethod: { method: string; count: number; amount: number }[];
};

export function feeDayBook(schoolId: string, from: string, to: string): Promise<DayBookDto> {
  const params = new URLSearchParams({ schoolId, from, to });
  return apiFetch<DayBookDto>(`/v1/fees/reports/day-book?${params.toString()}`);
}

export type OutstandingReportDto = {
  totalOutstanding: number;
  studentsWithDues: number;
  byGrade: { gradeCode: string; students: number; balance: number }[];
  students: {
    studentId: string;
    admissionNo: string;
    name: string;
    gradeCode: string | null;
    sectionCode: string | null;
    balance: number;
    invoices: number;
    oldestDueOn: string;
  }[];
};

export function feeOutstanding(
  schoolId: string,
  filters?: { academicYearId?: string; gradeId?: string; sectionId?: string }
): Promise<OutstandingReportDto> {
  const params = new URLSearchParams({ schoolId });
  if (filters?.academicYearId) params.set("academicYearId", filters.academicYearId);
  if (filters?.gradeId) params.set("gradeId", filters.gradeId);
  if (filters?.sectionId) params.set("sectionId", filters.sectionId);
  return apiFetch<OutstandingReportDto>(`/v1/fees/reports/outstanding?${params.toString()}`);
}

export function studentDues(
  studentId: string
): Promise<{ studentId: string; balance: number; hasDues: boolean }> {
  return apiFetch<{ studentId: string; balance: number; hasDues: boolean }>(
    `/v1/fees/students/${studentId}/dues`
  );
}

/** Groups a student with their siblings, by their shared guardian. */
export function linkFamily(req: { schoolId: string; studentId: string }): Promise<{ familyId: string }> {
  return apiFetch<{ familyId: string }>("/v1/fees/families/link", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function createCombinedFamilyInvoice(req: {
  schoolId: string;
  familyId: string;
  cycleLabel: string;
  dueOn: string;
}): Promise<FeeInvoiceDto> {
  return apiFetch<FeeInvoiceDto>("/v1/fees/families/combined-invoice", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

// -------------------------- Year closure & rollover (Phase 6) --------------------------

/**
 * What still stands between the school and closing its year. Each count is
 * also a list, because the office has to work through names, not totals.
 */
export type ReadinessReportDto = {
  schoolId: string;
  academicYearId: string;
  academicYearCode: string;
  ready: boolean;
  activeEnrolments: number;
  unpublishedAssessments: number;
  unlockedReportCards: number;
  missingPromotionDecisions: number;
  unmarkedAttendanceDays: number;
  studentsWithDues: number;
  outstandingTotal: number;
  items: { kind: string; detail: string; targetId: string | null }[];
};

export function rolloverReadiness(schoolId: string, academicYearId: string): Promise<ReadinessReportDto> {
  const params = new URLSearchParams({ schoolId, academicYearId });
  return apiFetch<ReadinessReportDto>(`/v1/rollover/readiness?${params.toString()}`);
}

/**
 * One attempt at moving the school into the next year. `state` is the whole
 * contract: nothing irreversible happens before `committed`, and even that is
 * undoable while the new year is still `planning`.
 */
export type RolloverRunDto = {
  id: string;
  schoolId: string;
  fromAcademicYearId: string;
  fromAcademicYearCode: string;
  toAcademicYearId: string;
  toAcademicYearCode: string;
  toAcademicYearStatus: string;
  toAcademicYearIsCurrent: boolean;
  runKey: string;
  /** draft | structure_cloned | allocated | committed | rolled_back */
  state: string;
  batchSize: number;
  batchesDone: number;
  stats: Record<string, number>;
  startedByStaffId: string | null;
  createdAt: string;
  committedAt: string | null;
  rolledBackAt: string | null;
};

export const ROLLOVER_STATES = [
  "draft",
  "structure_cloned",
  "allocated",
  "committed",
  "rolled_back",
] as const;

/**
 * Where one child is going. A row with no `toSectionId` is a question for the
 * school — the grade above is full, or nobody decided whether they passed.
 */
export type RolloverAllocationDto = {
  id: string;
  rolloverRunId: string;
  studentId: string;
  studentName: string;
  admissionNo: string;
  fromEnrolmentId: string;
  fromSectionId: string;
  fromSectionLabel: string;
  decision: string;
  toSectionId: string | null;
  toSectionLabel: string | null;
  rollNo: string | null;
  overCapacityReason: string | null;
  /** planned | applied | skipped */
  state: string;
  note: string | null;
  newEnrolmentId: string | null;
  batchNo: number;
  appliedAt: string | null;
};

export type RolloverCommitResultDto = {
  runId: string;
  state: string;
  applied: number;
  graduated: number;
  remaining: number;
  unplaced: number;
  withoutDecision: number;
  sourceYearClosed: boolean;
  arrearsCarried: number;
};

export function listRolloverRuns(schoolId: string): Promise<RolloverRunDto[]> {
  return apiFetch<RolloverRunDto[]>(`/v1/rollover/runs?schoolId=${schoolId}`);
}

export function getRolloverRun(id: string): Promise<RolloverRunDto> {
  return apiFetch<RolloverRunDto>(`/v1/rollover/runs/${id}`);
}

/** The same run key twice is the same run — a double-clicked button is one rollover. */
export function startRollover(req: {
  schoolId: string;
  fromAcademicYearId: string;
  toAcademicYearId: string;
  runKey?: string;
  batchSize?: number;
  startedByStaffId?: string;
}): Promise<RolloverRunDto> {
  return apiFetch<RolloverRunDto>("/v1/rollover/runs", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

/** Next year's sections and fee structures, as a planning-state copy. */
export function cloneRolloverStructure(runId: string): Promise<RolloverRunDto> {
  return apiFetch<RolloverRunDto>(`/v1/rollover/runs/${runId}/clone-structure`, { method: "POST" });
}

/** Turns each promotion decision into a seat. Inert until commit. */
export function allocateRollover(runId: string): Promise<RolloverRunDto> {
  return apiFetch<RolloverRunDto>(`/v1/rollover/runs/${runId}/allocate`, { method: "POST" });
}

export function rolloverAllocations(runId: string, state?: string): Promise<RolloverAllocationDto[]> {
  const query = state ? `?state=${state}` : "";
  return apiFetch<RolloverAllocationDto[]>(`/v1/rollover/runs/${runId}/allocations${query}`);
}

export function reallocateStudent(
  allocationId: string,
  req: { toSectionId?: string; rollNo?: string; overCapacityReason?: string; note?: string }
): Promise<RolloverAllocationDto> {
  return apiFetch<RolloverAllocationDto>(`/v1/rollover/allocations/${allocationId}`, {
    method: "PUT",
    body: JSON.stringify(req),
  });
}

/** Applies the plan in batches; call again to continue where it stopped. */
export function commitRollover(
  runId: string,
  req?: { maxBatches?: number; actingStaffId?: string }
): Promise<RolloverCommitResultDto> {
  return apiFetch<RolloverCommitResultDto>(`/v1/rollover/runs/${runId}/commit`, {
    method: "POST",
    body: JSON.stringify(req ?? {}),
  });
}

/** Undoes everything the run created — refused once the new year is live. */
export function rollbackRollover(
  runId: string,
  req: { reason: string; actingStaffId?: string }
): Promise<RolloverRunDto> {
  return apiFetch<RolloverRunDto>(`/v1/rollover/runs/${runId}/rollback`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

/** Makes the new year live. After this the rollover cannot be undone. */
export function activateRollover(
  runId: string,
  req?: { actingStaffId?: string }
): Promise<RolloverRunDto> {
  return apiFetch<RolloverRunDto>(`/v1/rollover/runs/${runId}/activate`, {
    method: "POST",
    body: JSON.stringify(req ?? {}),
  });
}

// -------------------------- Roles & access --------------------------

/** Every admin-web route that's gated by a role's screen_keys, plus "admin" for this Roles & Users screen itself. */
export const SCREEN_DEFS = [
  { key: "dashboard", label: "Dashboard", path: "/dashboard" },
  { key: "students", label: "Students", path: "/students" },
  { key: "admissions", label: "Admissions", path: "/admissions" },
  { key: "attendance", label: "Attendance", path: "/attendance" },
  { key: "fees", label: "Fees", path: "/fees" },
  { key: "calendar", label: "Calendar & Year", path: "/calendar" },
  { key: "academics", label: "Academics", path: "/academics" },
  { key: "rollover", label: "Year Rollover", path: "/rollover" },
  { key: "timetable", label: "Timetable", path: "/timetable" },
  { key: "assessment", label: "Assessment", path: "/assessment" },
  { key: "exams", label: "Exams", path: "/exams" },
  { key: "lms", label: "LMS", path: "/lms" },
  { key: "comms", label: "Comms", path: "/comms" },
  { key: "library", label: "Library", path: "/library" },
  { key: "transport", label: "Transport", path: "/transport" },
  { key: "admin", label: "Roles & Users", path: "/roles" },
] as const;

export type RoleDto = {
  id: string;
  code: string;
  name: string;
  description: string | null;
  screenKeys: string[];
  isSystem: boolean;
  /** Send this back on update. A save against a stale version is refused with a 409 rather than overwriting whoever saved in between. */
  version: number;
  createdAt: string;
};

export function listRoles(): Promise<RoleDto[]> {
  return apiFetch<RoleDto[]>("/v1/iam/roles");
}

export function createRole(req: {
  code: string;
  name: string;
  description?: string;
  screenKeys: string[];
}): Promise<RoleDto> {
  return apiFetch<RoleDto>("/v1/iam/roles", { method: "POST", body: JSON.stringify(req) });
}

export function updateRole(
  id: string,
  req: { name: string; description?: string; screenKeys: string[]; expectedVersion: number }
): Promise<RoleDto> {
  return apiFetch<RoleDto>(`/v1/iam/roles/${id}`, { method: "PUT", body: JSON.stringify(req) });
}

export function deleteRole(id: string): Promise<void> {
  return apiFetch<void>(`/v1/iam/roles/${id}`, { method: "DELETE" });
}

export type StaffWithRolesDto = {
  staffId: string;
  userAccountId: string | null;
  firstName: string;
  lastName: string | null;
  email: string | null;
  roleCodes: string[];
};

export function listStaffRoles(schoolId: string): Promise<StaffWithRolesDto[]> {
  return apiFetch<StaffWithRolesDto[]>(`/v1/iam/staff-roles?schoolId=${schoolId}`);
}

// A role grant hands somebody screens full of other people's children, so the
// API records why it was made; both calls carry the reason (SEC-08).
export function assignStaffRole(
  staffId: string,
  schoolId: string,
  roleCode: string,
  reason: string,
): Promise<void> {
  return apiFetch<void>("/v1/iam/staff-roles/assign", {
    method: "POST",
    body: JSON.stringify({ staffId, schoolId, roleCode, reason }),
  });
}

export function unassignStaffRole(
  staffId: string,
  schoolId: string,
  roleCode: string,
  reason: string,
): Promise<void> {
  return apiFetch<void>("/v1/iam/staff-roles/unassign", {
    method: "POST",
    body: JSON.stringify({ staffId, schoolId, roleCode, reason }),
  });
}
