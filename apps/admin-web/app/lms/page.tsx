"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  ASSIGNMENT_SUBMISSION_TYPES,
  AssignmentDto,
  AssignmentSubmissionDto,
  createAssignment,
  createLessonPlan,
  getSession,
  gradeSubmission,
  LESSON_PLAN_STATUSES,
  LessonPlanDto,
  listAssignments,
  listLessonPlans,
  listSections,
  listSubjects,
  listSubmissions,
  SectionDto,
  Session,
  setLessonPlanStatus,
  SubjectDto,
} from "@/lib/api";

const emptyLessonForm = { subjectId: "", title: "", plannedFor: "", durationMinutes: "" };
const emptyAssignmentForm = {
  subjectId: "",
  title: "",
  instructions: "",
  submissionType: ASSIGNMENT_SUBMISSION_TYPES[0],
  dueAt: "",
  maxMarks: "",
};

export default function LmsPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [sections, setSections] = useState<SectionDto[] | null>(null);
  const [subjects, setSubjects] = useState<SubjectDto[] | null>(null);
  const [sectionId, setSectionId] = useState("");
  const [error, setError] = useState<string | null>(null);

  const [lessonPlans, setLessonPlans] = useState<LessonPlanDto[] | null>(null);
  const [showLessonForm, setShowLessonForm] = useState(false);
  const [lessonForm, setLessonForm] = useState(emptyLessonForm);
  const [creatingLesson, setCreatingLesson] = useState(false);
  const [lessonStatusSaving, setLessonStatusSaving] = useState<string | null>(null);

  const [assignments, setAssignments] = useState<AssignmentDto[] | null>(null);
  const [showAssignmentForm, setShowAssignmentForm] = useState(false);
  const [assignmentForm, setAssignmentForm] = useState(emptyAssignmentForm);
  const [creatingAssignment, setCreatingAssignment] = useState(false);

  const [selectedAssignment, setSelectedAssignment] = useState<AssignmentDto | null>(null);
  const [submissions, setSubmissions] = useState<AssignmentSubmissionDto[] | null>(null);
  const [gradeInputs, setGradeInputs] = useState<Record<string, { marks: string; feedback: string }>>({});
  const [gradingId, setGradingId] = useState<string | null>(null);

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    setSessionState(s);
    Promise.all([listSections(s.schoolId), listSubjects(s.schoolId)])
      .then(([secs, subs]) => {
        setSections(secs);
        setSubjects(subs);
        if (secs.length > 0) setSectionId(secs[0].id);
        setLessonForm((f) => ({ ...f, subjectId: subs[0]?.id ?? "" }));
        setAssignmentForm((f) => ({ ...f, subjectId: subs[0]?.id ?? "" }));
      })
      .catch((err) => setError(describeError(err)));
  }, [router]);

  function refreshLessonPlans(id: string) {
    listLessonPlans(id)
      .then(setLessonPlans)
      .catch((err) => setError(describeError(err)));
  }

  function refreshAssignments(id: string) {
    listAssignments(id)
      .then(setAssignments)
      .catch((err) => setError(describeError(err)));
  }

  useEffect(() => {
    if (!sectionId) return;
    setSelectedAssignment(null);
    setSubmissions(null);
    refreshLessonPlans(sectionId);
    refreshAssignments(sectionId);
  }, [sectionId]);

  async function onCreateLesson() {
    if (!session) return;
    setCreatingLesson(true);
    setError(null);
    try {
      await createLessonPlan({
        schoolId: session.schoolId,
        sectionId,
        subjectId: lessonForm.subjectId,
        title: lessonForm.title,
        plannedFor: lessonForm.plannedFor || undefined,
        durationMinutes: lessonForm.durationMinutes ? Number(lessonForm.durationMinutes) : undefined,
      });
      setLessonForm((f) => ({ ...emptyLessonForm, subjectId: f.subjectId }));
      setShowLessonForm(false);
      refreshLessonPlans(sectionId);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setCreatingLesson(false);
    }
  }

  async function onLessonStatusChange(plan: LessonPlanDto, status: string) {
    setLessonStatusSaving(plan.id);
    setError(null);
    try {
      await setLessonPlanStatus(plan.id, status);
      refreshLessonPlans(sectionId);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLessonStatusSaving(null);
    }
  }

  async function onCreateAssignment() {
    if (!session) return;
    setCreatingAssignment(true);
    setError(null);
    try {
      await createAssignment({
        schoolId: session.schoolId,
        sectionId,
        subjectId: assignmentForm.subjectId,
        title: assignmentForm.title,
        instructions: assignmentForm.instructions || undefined,
        submissionType: assignmentForm.submissionType,
        dueAt: assignmentForm.dueAt ? new Date(assignmentForm.dueAt).toISOString() : undefined,
        maxMarks: assignmentForm.maxMarks ? Number(assignmentForm.maxMarks) : undefined,
      });
      setAssignmentForm((f) => ({ ...emptyAssignmentForm, subjectId: f.subjectId }));
      setShowAssignmentForm(false);
      refreshAssignments(sectionId);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setCreatingAssignment(false);
    }
  }

  async function selectAssignment(a: AssignmentDto) {
    setSelectedAssignment(a);
    setError(null);
    try {
      const subs = await listSubmissions(a.id);
      setSubmissions(subs);
      const initial: Record<string, { marks: string; feedback: string }> = {};
      for (const s of subs) {
        initial[s.id] = { marks: s.marks?.toString() ?? "", feedback: s.feedback ?? "" };
      }
      setGradeInputs(initial);
    } catch (err) {
      setError(describeError(err));
    }
  }

  async function onGrade(sub: AssignmentSubmissionDto) {
    const input = gradeInputs[sub.id];
    if (!input || input.marks === "") return;
    setGradingId(sub.id);
    setError(null);
    try {
      await gradeSubmission(sub.id, Number(input.marks), input.feedback || undefined);
      if (selectedAssignment) setSubmissions(await listSubmissions(selectedAssignment.id));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setGradingId(null);
    }
  }

  if (!session) return null;

  return (
    <main className="shell">
      <div className="panel">
        <div className="form-row">
          <h2>LMS</h2>
        </div>
        <div className="form-row">
          <select value={sectionId} onChange={(e) => setSectionId(e.target.value)} disabled={!sections}>
            {sections?.map((s) => (
              <option key={s.id} value={s.id}>
                {s.gradeName}-{s.code}
              </option>
            ))}
          </select>
        </div>
        {error && <div className="error-banner">{error}</div>}
      </div>

      <div className="panel">
        <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
          <h2>Lesson plans</h2>
          <button type="button" onClick={() => setShowLessonForm((v) => !v)} disabled={!subjects || subjects.length === 0}>
            {showLessonForm ? "Cancel" : "New lesson plan"}
          </button>
        </div>

        {showLessonForm && (
          <div className="form-row" style={{ flexWrap: "wrap" }}>
            <select value={lessonForm.subjectId} onChange={(e) => setLessonForm((f) => ({ ...f, subjectId: e.target.value }))}>
              {subjects?.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
            <input
              placeholder="Title"
              value={lessonForm.title}
              onChange={(e) => setLessonForm((f) => ({ ...f, title: e.target.value }))}
            />
            <input
              type="date"
              value={lessonForm.plannedFor}
              onChange={(e) => setLessonForm((f) => ({ ...f, plannedFor: e.target.value }))}
            />
            <input
              type="number"
              placeholder="Minutes"
              value={lessonForm.durationMinutes}
              onChange={(e) => setLessonForm((f) => ({ ...f, durationMinutes: e.target.value }))}
              style={{ maxWidth: 100 }}
            />
            <button type="button" onClick={onCreateLesson} disabled={creatingLesson || !lessonForm.subjectId || !lessonForm.title}>
              {creatingLesson ? "Creating…" : "Create"}
            </button>
          </div>
        )}

        {lessonPlans && lessonPlans.length === 0 && <p className="hint">No lesson plans yet.</p>}
        {lessonPlans && lessonPlans.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Title</th>
                <th>Subject</th>
                <th>Planned for</th>
                <th>Minutes</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {lessonPlans.map((p) => (
                <tr key={p.id}>
                  <td>{p.title}</td>
                  <td>{subjects?.find((s) => s.id === p.subjectId)?.name ?? "—"}</td>
                  <td>{p.plannedFor ?? "—"}</td>
                  <td>{p.durationMinutes ?? "—"}</td>
                  <td>
                    <select
                      value={p.status}
                      onChange={(e) => onLessonStatusChange(p, e.target.value)}
                      disabled={lessonStatusSaving === p.id}
                    >
                      {LESSON_PLAN_STATUSES.map((st) => (
                        <option key={st} value={st}>
                          {st}
                        </option>
                      ))}
                    </select>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="panel">
        <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
          <h2>Assignments</h2>
          <button
            type="button"
            onClick={() => setShowAssignmentForm((v) => !v)}
            disabled={!subjects || subjects.length === 0}
          >
            {showAssignmentForm ? "Cancel" : "New assignment"}
          </button>
        </div>

        {showAssignmentForm && (
          <div>
            <div className="form-row" style={{ flexWrap: "wrap" }}>
              <select
                value={assignmentForm.subjectId}
                onChange={(e) => setAssignmentForm((f) => ({ ...f, subjectId: e.target.value }))}
              >
                {subjects?.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </select>
              <input
                placeholder="Title"
                value={assignmentForm.title}
                onChange={(e) => setAssignmentForm((f) => ({ ...f, title: e.target.value }))}
              />
              <select
                value={assignmentForm.submissionType}
                onChange={(e) => setAssignmentForm((f) => ({ ...f, submissionType: e.target.value }))}
              >
                {ASSIGNMENT_SUBMISSION_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </select>
              <input
                type="datetime-local"
                value={assignmentForm.dueAt}
                onChange={(e) => setAssignmentForm((f) => ({ ...f, dueAt: e.target.value }))}
              />
              <input
                type="number"
                placeholder="Max marks"
                value={assignmentForm.maxMarks}
                onChange={(e) => setAssignmentForm((f) => ({ ...f, maxMarks: e.target.value }))}
                style={{ maxWidth: 110 }}
              />
            </div>
            <div className="form-row">
              <textarea
                placeholder="Instructions"
                value={assignmentForm.instructions}
                onChange={(e) => setAssignmentForm((f) => ({ ...f, instructions: e.target.value }))}
                style={{ minWidth: 400, minHeight: 60 }}
              />
            </div>
            <div className="form-row">
              <button
                type="button"
                onClick={onCreateAssignment}
                disabled={creatingAssignment || !assignmentForm.subjectId || !assignmentForm.title}
              >
                {creatingAssignment ? "Creating…" : "Create assignment"}
              </button>
            </div>
          </div>
        )}

        {assignments && assignments.length === 0 && <p className="hint">No assignments yet.</p>}
        {assignments && assignments.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Title</th>
                <th>Subject</th>
                <th>Type</th>
                <th>Due</th>
                <th>Max marks</th>
                <th>Status</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {assignments.map((a) => (
                <tr key={a.id} style={{ cursor: "pointer" }} onClick={() => selectAssignment(a)}>
                  <td>{a.title}</td>
                  <td>{subjects?.find((s) => s.id === a.subjectId)?.name ?? "—"}</td>
                  <td>{a.submissionType}</td>
                  <td>{a.dueAt ?? "—"}</td>
                  <td>{a.maxMarks ?? "—"}</td>
                  <td>
                    <span className="badge">{a.status}</span>
                  </td>
                  <td>
                    <button type="button" onClick={() => selectAssignment(a)}>
                      {selectedAssignment?.id === a.id ? "Selected" : "Open"}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {selectedAssignment && submissions && (
        <div className="panel">
          <h2>Submissions — {selectedAssignment.title}</h2>
          {submissions.length === 0 && <p className="hint">No submissions yet.</p>}
          {submissions.length > 0 && (
            <table>
              <thead>
                <tr>
                  <th>Student</th>
                  <th>Submitted</th>
                  <th>Marks</th>
                  <th>Feedback</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {submissions.map((s) => (
                  <tr key={s.id}>
                    <td>{s.studentId.slice(0, 8)}</td>
                    <td>{s.submittedAt}</td>
                    <td>
                      <input
                        type="number"
                        max={selectedAssignment.maxMarks ?? undefined}
                        value={gradeInputs[s.id]?.marks ?? ""}
                        onChange={(e) =>
                          setGradeInputs((g) => ({ ...g, [s.id]: { ...g[s.id], marks: e.target.value } }))
                        }
                        style={{ maxWidth: 90 }}
                      />
                    </td>
                    <td>
                      <input
                        value={gradeInputs[s.id]?.feedback ?? ""}
                        onChange={(e) =>
                          setGradeInputs((g) => ({ ...g, [s.id]: { ...g[s.id], feedback: e.target.value } }))
                        }
                      />
                    </td>
                    <td>
                      <button type="button" onClick={() => onGrade(s)} disabled={gradingId === s.id}>
                        {gradingId === s.id ? "…" : s.gradedAt ? "Re-grade" : "Grade"}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </main>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}
