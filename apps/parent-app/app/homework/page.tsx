"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  AssignmentDto,
  AssignmentSubmissionDto,
  assignmentsForSection,
  getSession,
  Session,
  StudentDto,
  studentsOfGuardian,
  submissionsForAssignment,
  submitAssignment,
} from "@/lib/api";

type Row = { assignment: AssignmentDto; submission: AssignmentSubmissionDto | null };

export default function HomeworkPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [children, setChildren] = useState<StudentDto[] | null>(null);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [rows, setRows] = useState<Row[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [submittingId, setSubmittingId] = useState<string | null>(null);

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    setSessionState(s);
    if (!s.subjectId) return;
    studentsOfGuardian(s.subjectId)
      .then((kids) => {
        setChildren(kids);
        if (kids.length > 0) setActiveId(kids[0].id);
      })
      .catch((err) => setError(describeError(err)));
  }, [router]);

  const active = children?.find((c) => c.id === activeId) ?? null;

  async function load() {
    if (!active?.currentSectionId) {
      setRows(null);
      return;
    }
    try {
      const assignments = await assignmentsForSection(active.currentSectionId);
      const withSubs = await Promise.all(
        assignments.map(async (a) => {
          const subs = await submissionsForAssignment(a.id);
          const mine = subs.find((s) => s.studentId === active.id) ?? null;
          return { assignment: a, submission: mine };
        })
      );
      withSubs.sort((a, b) => (b.assignment.dueAt ?? "").localeCompare(a.assignment.dueAt ?? ""));
      setRows(withSubs);
    } catch (err) {
      setError(describeError(err));
    }
  }

  useEffect(() => {
    setRows(null);
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active]);

  async function onSubmit(assignmentId: string) {
    if (!active) return;
    setSubmittingId(assignmentId);
    setError(null);
    try {
      await submitAssignment(assignmentId, active.id, answers[assignmentId] ?? "");
      await load();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSubmittingId(null);
    }
  }

  if (!session) return null;

  if (!session.subjectId) {
    return (
      <main className="shell">
        <div className="panel">
          <h2>Homework</h2>
          <p className="hint">This account isn&apos;t linked to a guardian record.</p>
        </div>
      </main>
    );
  }

  return (
    <main className="shell">
      {error && <div className="error-banner">{error}</div>}

      {children && children.length > 1 && (
        <div className="chip-row">
          {children.map((c) => (
            <button
              key={c.id}
              type="button"
              className={"chip-btn" + (c.id === activeId ? " active" : "")}
              onClick={() => setActiveId(c.id)}
            >
              {c.firstName}
            </button>
          ))}
        </div>
      )}

      {children && children.length === 0 && (
        <div className="panel">
          <p className="empty-note">No children linked to this account yet.</p>
        </div>
      )}

      {active && !active.currentSectionId && (
        <div className="panel">
          <p className="empty-note">Not enrolled in a section yet.</p>
        </div>
      )}

      {!rows && active?.currentSectionId && (
        <div className="panel">
          <p className="hint">Loading…</p>
        </div>
      )}

      {rows && rows.length === 0 && (
        <div className="panel">
          <p className="empty-note">No homework assigned yet.</p>
        </div>
      )}

      <div className="grid-2">
        {rows?.map(({ assignment, submission }) => (
          <div key={assignment.id} className="panel">
            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 10 }}>
              <div>
                <div>{assignment.title}</div>
                <div className="list-row-sub">
                  due {assignment.dueAt ? assignment.dueAt.slice(0, 16).replace("T", " ") : "—"} · max {assignment.maxMarks ?? "—"}
                </div>
              </div>
              <span className={`badge ${submission ? "badge-active" : ""}`}>{submission ? "submitted" : "pending"}</span>
            </div>
            {assignment.instructions && <p className="hint" style={{ marginTop: 8 }}>{assignment.instructions}</p>}

            {submission?.gradedAt && (
              <p className="hint" style={{ marginTop: 8 }}>
                Graded: {submission.marks ?? "—"} / {assignment.maxMarks ?? "—"}
                {submission.feedback ? ` — ${submission.feedback}` : ""}
              </p>
            )}

            {!submission && (
              <div className="form-row" style={{ marginTop: 10 }}>
                <input
                  placeholder="Answer / notes"
                  value={answers[assignment.id] ?? ""}
                  onChange={(e) => setAnswers((a) => ({ ...a, [assignment.id]: e.target.value }))}
                  style={{ flex: 1 }}
                />
                <button type="button" onClick={() => onSubmit(assignment.id)} disabled={submittingId === assignment.id}>
                  {submittingId === assignment.id ? "Submitting…" : "Submit"}
                </button>
              </div>
            )}
          </div>
        ))}
      </div>
    </main>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}
