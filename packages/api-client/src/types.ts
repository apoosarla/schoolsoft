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
  academicYearId: string;
  termId: string | null;
  strategyCode: string;
  templateCode: string;
  isLocked: boolean;
  generatedAt: string;
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
  isAbsent: boolean;
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
