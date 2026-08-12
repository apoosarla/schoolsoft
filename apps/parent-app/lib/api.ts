/**
 * Parent-app's binding of @schoolsoft/api-client: the session (localStorage
 * shape, subjectId = guardian.id resolved via /v1/iam/me) is app-specific and
 * lives here; transport, DTOs, and endpoint wrappers come from the package.
 * Page components import everything from "@/lib/api", so this file re-exports
 * the shared surface under the names they already use.
 */

import {
  createApiClient,
  createAssessmentApi,
  createAttendanceApi,
  createAuthApi,
  createCommsApi,
  createFeesApi,
  createLmsApi,
  createPeopleApi,
  createTenancyApi,
  type LeaveApplicationDto,
} from "@schoolsoft/api-client";

export { ApiError } from "@schoolsoft/api-client";
export type * from "@schoolsoft/api-client";

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

const auth = createAuthApi(client);

export const startOtp = auth.startOtp;

export async function verifyOtp(identifier: string, chainSlug: string, code: string): Promise<Session> {
  const res = await auth.verifyOtpTokens(identifier, chainSlug, code);
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
  const me = await auth.fetchMe();
  session = { ...session, subjectId: me.subjectId || null };
  setSession(session);
  return session;
}

const people = createPeopleApi(client);
export const studentsOfGuardian = people.studentsOfGuardian;
export function staffDirectory(schoolId: string) {
  return people.directory(schoolId, "staff");
}

const tenancy = createTenancyApi(client);
export const teachersForSection = tenancy.teachersForSection;

const comms = createCommsApi(client);
export const listAnnouncements = comms.listAnnouncements;
export const threadsForUser = comms.threadsForUser;
export const createThread = comms.createThread;
export const messagesForThread = comms.messagesForThread;
export const sendMessage = comms.sendMessage;

const attendance = createAttendanceApi(client);
export const attendanceForSectionOnDate = attendance.attendanceForSectionOnDate;
export const attendanceHistoryForStudent = attendance.attendanceHistoryForStudent;

const fees = createFeesApi(client);
export const listInvoicesForStudent = fees.listInvoicesForStudent;
export const listInvoiceLines = fees.listInvoiceLines;
export const listPaymentsForInvoice = fees.listPaymentsForInvoice;

const assessment = createAssessmentApi(client);
export const reportCardsForStudent = assessment.reportCardsForStudent;
export const assessmentsForSection = assessment.assessmentsForSection;
export const componentsForAssessment = assessment.componentsForAssessment;
export const marksForComponent = assessment.marksForComponent;

const lms = createLmsApi(client);
export const assignmentsForSection = lms.assignmentsForSection;
export const submissionsForAssignment = lms.submissionsForAssignment;
export const submitAssignment = lms.submitAssignment;

/** Parent-app only applies leave on behalf of a student — subjectType is fixed, so this stays local. */
export function applyLeave(req: {
  schoolId: string;
  subjectId: string;
  fromDate: string;
  toDate: string;
  reason?: string;
}): Promise<LeaveApplicationDto> {
  return client.apiFetch<LeaveApplicationDto>("/v1/attendance/leave", {
    method: "POST",
    body: JSON.stringify({ ...req, subjectType: "student" }),
  });
}
