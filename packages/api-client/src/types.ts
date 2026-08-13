/** DTOs as returned by the chain-scoped API. Pure type declarations, no runtime behaviour. */

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

export type FeeInvoiceLineDto = {
  id: string;
  feeInvoiceId: string;
  feeHeadId: string;
  description: string | null;
  amount: number;
  discount: number;
  gst: number;
};

/**
 * A change to what is owed or what was paid, made after the invoice was
 * issued. Kinds: credit_note | refund | waiver | late_fee | charge | reversal.
 * A bounced cheque is a reversal, so the money arriving and going away again
 * are both visible.
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
  /** marked | absent | medical_leave | exempt | not_assessed */
  resultStatus: string;
  /** What the row prints — "AB" for an absence, never a zero. */
  display: string;
  passing: boolean | null;
  remarks: string | null;
  sortOrder: number;
};

export type ReportCardCoScholasticRow = {
  areaCode: string;
  areaName: string;
  rating: string;
  remarks: string | null;
  sortOrder: number;
};

export type ReportCardDetailDto = {
  card: ReportCardDto;
  subjects: ReportCardSubjectRow[];
  coScholastic: ReportCardCoScholasticRow[];
  payload: Record<string, unknown>;
};

export type MarkReevaluationDto = {
  id: string;
  markId: string;
  studentId: string;
  reason: string;
  requestedAt: string;
  /** pending | upheld | revised | rejected — a request is kept whatever the outcome. */
  status: string;
  decidedAt: string | null;
  decisionNote: string | null;
};

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
  schoolId: string;
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
  assessmentId: string | null;
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

export type AssessmentComponentDto = {
  id: string;
  assessmentId: string;
  code: string;
  name: string;
  maxMarks: number;
  weightPct: number | null;
  sortOrder: number;
};

export type MarkDto = {
  id: string;
  assessmentComponentId: string;
  studentId: string;
  rawMarks: number | null;
  gradeLetter: string | null;
  remarks: string | null;
  /** entered | pending | absent | medical_leave | exempt — a blank is not a zero. */
  status: string;
  /** Derived from status, kept for callers written before it existed. */
  isAbsent: boolean;
  revisionCount: number;
};

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

export type SectionTeacherDto = {
  id: string;
  sectionId: string;
  subjectId: string;
  subjectName: string;
  teacherStaffId: string;
  teacherName: string;
  isPrimary: boolean;
};

export type UserDirectoryEntryDto = {
  userAccountId: string;
  subjectType: string;
  subjectId: string;
  displayName: string;
  email: string | null;
  phone: string | null;
};

export type MessageThreadDto = {
  id: string;
  schoolId: string;
  subjectStudentId: string | null;
  participants: string[];
  lastMessageAt: string | null;
};

export type MessageDto = {
  id: string;
  threadId: string;
  senderUserId: string;
  body: string;
  sentAt: string;
};
