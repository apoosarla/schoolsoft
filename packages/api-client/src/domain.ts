/**
 * Chain-scoped endpoint wrappers, grouped by the API module that owns them.
 * Each factory binds an ApiClient so apps import only the modules they use.
 */

import type { ApiClient } from "./http";
import type {
  AnnouncementDto,
  AssessmentComponentDto,
  AssessmentDto,
  AssignmentDto,
  AssignmentSubmissionDto,
  AttendanceRecordDto,
  ExamScheduleDto,
  ExamSessionDto,
  FeeInvoiceDto,
  FeeInvoiceLineDto,
  HallTicketDto,
  MarkDto,
  MessageDto,
  MessageThreadDto,
  PaymentDto,
  ReportCardDetailDto,
  ReportCardDto,
  SectionTeacherDto,
  StudentDto,
  UserDirectoryEntryDto,
} from "./types";

export function createPeopleApi(client: ApiClient) {
  return {
    studentsOfGuardian(guardianId: string): Promise<StudentDto[]> {
      return client.apiFetch<StudentDto[]>(`/v1/people/guardians/${guardianId}/students`);
    },
    directory(schoolId: string, subjectType: string): Promise<UserDirectoryEntryDto[]> {
      const params = new URLSearchParams({ schoolId, subjectType });
      return client.apiFetch<UserDirectoryEntryDto[]>(`/v1/people/directory?${params.toString()}`);
    },
  };
}

export function createTenancyApi(client: ApiClient) {
  return {
    teachersForSection(sectionId: string): Promise<SectionTeacherDto[]> {
      return client.apiFetch<SectionTeacherDto[]>(`/v1/tenancy/sections/${sectionId}/teachers`);
    },
  };
}

export function createCommsApi(client: ApiClient) {
  return {
    listAnnouncements(schoolId: string): Promise<AnnouncementDto[]> {
      return client.apiFetch<AnnouncementDto[]>(`/v1/comms/announcements?schoolId=${schoolId}`);
    },
    threadsForUser(userAccountId: string): Promise<MessageThreadDto[]> {
      return client.apiFetch<MessageThreadDto[]>(`/v1/comms/threads?userAccountId=${userAccountId}`);
    },
    createThread(req: {
      schoolId: string;
      subjectStudentId?: string;
      participants: string[];
    }): Promise<MessageThreadDto> {
      return client.apiFetch<MessageThreadDto>("/v1/comms/threads", {
        method: "POST",
        body: JSON.stringify(req),
      });
    },
    messagesForThread(threadId: string): Promise<MessageDto[]> {
      return client.apiFetch<MessageDto[]>(`/v1/comms/threads/${threadId}/messages`);
    },
    sendMessage(threadId: string, senderUserId: string, body: string): Promise<MessageDto> {
      return client.apiFetch<MessageDto>(`/v1/comms/threads/${threadId}/messages`, {
        method: "POST",
        body: JSON.stringify({ senderUserId, body }),
      });
    },
  };
}

export function createAttendanceApi(client: ApiClient) {
  return {
    attendanceForSectionOnDate(sectionId: string, onDate: string): Promise<AttendanceRecordDto[]> {
      const params = new URLSearchParams({ sectionId, onDate });
      return client.apiFetch<AttendanceRecordDto[]>(`/v1/attendance?${params.toString()}`);
    },
    attendanceHistoryForStudent(studentId: string, from: string, to: string): Promise<AttendanceRecordDto[]> {
      const params = new URLSearchParams({ from, to });
      return client.apiFetch<AttendanceRecordDto[]>(`/v1/attendance/students/${studentId}?${params.toString()}`);
    },
  };
}

export function createFeesApi(client: ApiClient) {
  return {
    listInvoicesForStudent(studentId: string): Promise<FeeInvoiceDto[]> {
      return client.apiFetch<FeeInvoiceDto[]>(`/v1/fees/invoices?studentId=${studentId}`);
    },
    listInvoiceLines(invoiceId: string): Promise<FeeInvoiceLineDto[]> {
      return client.apiFetch<FeeInvoiceLineDto[]>(`/v1/fees/invoices/${invoiceId}/lines`);
    },
    listPaymentsForInvoice(invoiceId: string): Promise<PaymentDto[]> {
      return client.apiFetch<PaymentDto[]>(`/v1/fees/invoices/${invoiceId}/payments`);
    },
  };
}

export function createAssessmentApi(client: ApiClient) {
  return {
    reportCardsForStudent(studentId: string): Promise<ReportCardDto[]> {
      return client.apiFetch<ReportCardDto[]>(`/v1/assessment/report-cards/students/${studentId}`);
    },
    assessmentsForSection(sectionId: string): Promise<AssessmentDto[]> {
      return client.apiFetch<AssessmentDto[]>(`/v1/assessment?sectionId=${sectionId}`);
    },
    componentsForAssessment(assessmentId: string): Promise<AssessmentComponentDto[]> {
      return client.apiFetch<AssessmentComponentDto[]>(`/v1/assessment/${assessmentId}/components`);
    },
    marksForComponent(componentId: string): Promise<MarkDto[]> {
      return client.apiFetch<MarkDto[]>(`/v1/assessment/components/${componentId}/marks`);
    },
    reportCard(id: string): Promise<ReportCardDetailDto> {
      return client.apiFetch<ReportCardDetailDto>(`/v1/assessment/report-cards/${id}`);
    },
    examSchedules(schoolId: string, academicYearId?: string): Promise<ExamScheduleDto[]> {
      const year = academicYearId ? `&academicYearId=${academicYearId}` : "";
      return client.apiFetch<ExamScheduleDto[]>(`/v1/exams/schedules?schoolId=${schoolId}${year}`);
    },
    examSessions(examScheduleId: string): Promise<ExamSessionDto[]> {
      return client.apiFetch<ExamSessionDto[]>(`/v1/exams/schedules/${examScheduleId}/sessions`);
    },
    hallTicket(examScheduleId: string, studentId: string): Promise<HallTicketDto> {
      return client.apiFetch<HallTicketDto>(
        `/v1/exams/schedules/${examScheduleId}/hall-tickets/${studentId}`,
      );
    },
  };
}

export function createLmsApi(client: ApiClient) {
  return {
    assignmentsForSection(sectionId: string): Promise<AssignmentDto[]> {
      return client.apiFetch<AssignmentDto[]>(`/v1/lms/assignments?sectionId=${sectionId}`);
    },
    submissionsForAssignment(assignmentId: string): Promise<AssignmentSubmissionDto[]> {
      return client.apiFetch<AssignmentSubmissionDto[]>(`/v1/lms/assignments/${assignmentId}/submissions`);
    },
    submitAssignment(assignmentId: string, studentId: string, body: string): Promise<AssignmentSubmissionDto> {
      return client.apiFetch<AssignmentSubmissionDto>(`/v1/lms/assignments/${assignmentId}/submissions`, {
        method: "POST",
        body: JSON.stringify({ studentId, body }),
      });
    },
  };
}
