"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import {
  ApiError,
  AssignmentDto,
  AssignmentSubmissionDto,
  assignmentsForSection,
  createAssignment,
  getSession,
  gradeSubmission,
  listSections,
  SectionDto,
  Session,
  submissionsForAssignment,
  timetableForTeacher,
  TimetableSlotDto,
} from "@/lib/api";

export default function ClassworkPage() {
  return (
    <Suspense fallback={null}>
      <ClassworkInner />
    </Suspense>
  );
}

function ClassworkInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [session, setSessionState] = useState<Session | null>(null);
  const [mySections, setMySections] = useState<SectionDto[] | null>(null);
  const [subjectBySection, setSubjectBySection] = useState<Record<string, string>>({});
  const [sectionId, setSectionId] = useState("");
  const [assignments, setAssignments] = useState<AssignmentDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [openId, setOpenId] = useState<string | null>(null);

  const [title, setTitle] = useState("");
  const [instructions, setInstructions] = useState("");
  const [dueAt, setDueAt] = useState("");
  const [maxMarks, setMaxMarks] = useState("100");

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    setSessionState(s);
    if (!s.subjectId) return;

    Promise.all([timetableForTeacher(s.subjectId), listSections(s.schoolId)])
      .then(([tt, allSections]: [TimetableSlotDto[], SectionDto[]]) => {
        const bySection: Record<string, string> = {};
        for (const slot of tt) bySection[slot.sectionId] = slot.subjectId;
        const ids = Object.keys(bySection);
        const mine = allSections.filter((sec) => ids.includes(sec.id));
        setMySections(mine);
        setSubjectBySection(bySection);
        const preselect = searchParams.get("section");
        if (preselect && ids.includes(preselect)) setSectionId(preselect);
        else if (mine.length > 0) setSectionId(mine[0].id);
      })
      .catch((err) => setError(describeError(err)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [router]);

  useEffect(() => {
    if (!sectionId) return;
    setOpenId(null);
    assignmentsForSection(sectionId).then(setAssignments).catch((err) => setError(describeError(err)));
  }, [sectionId]);

  async function onCreate(e: React.FormEvent) {
    e.preventDefault();
    if (!session || !sectionId) return;
    const subjectId = subjectBySection[sectionId];
    if (!subjectId) return;
    setCreating(true);
    setError(null);
    try {
      await createAssignment({
        schoolId: session.schoolId,
        sectionId,
        subjectId,
        title: title.trim(),
        instructions: instructions.trim() || undefined,
        dueAt: dueAt ? new Date(dueAt).toISOString() : undefined,
        maxMarks: Number(maxMarks) || undefined,
        createdByStaffId: session.subjectId ?? undefined,
      });
      setTitle("");
      setInstructions("");
      setDueAt("");
      const refreshed = await assignmentsForSection(sectionId);
      setAssignments(refreshed);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setCreating(false);
    }
  }

  if (!session) return null;

  if (!session.subjectId) {
    return (
      <main className="shell">
        <div className="panel">
          <h2>Classwork</h2>
          <p className="hint">This account isn&apos;t linked to a staff record.</p>
        </div>
      </main>
    );
  }

  return (
    <main className="shell">
      <div className="panel">
        <h2>Classwork</h2>
        <div className="form-row" style={{ flexDirection: "column" }}>
          <select value={sectionId} onChange={(e) => setSectionId(e.target.value)} disabled={!mySections}>
            {mySections?.length === 0 && <option value="">No sections assigned</option>}
            {mySections?.map((s) => (
              <option key={s.id} value={s.id}>
                {s.gradeName}-{s.code}
              </option>
            ))}
          </select>
        </div>

        {error && <div className="error-banner">{error}</div>}

        <form onSubmit={onCreate} style={{ marginTop: 12 }}>
          <div className="form-row" style={{ flexDirection: "column" }}>
            <input placeholder="Title" value={title} onChange={(e) => setTitle(e.target.value)} required disabled={creating} />
            <textarea
              placeholder="Instructions (optional)"
              value={instructions}
              onChange={(e) => setInstructions(e.target.value)}
              disabled={creating}
              rows={2}
            />
            <div className="form-row">
              <input
                type="datetime-local"
                value={dueAt}
                onChange={(e) => setDueAt(e.target.value)}
                disabled={creating}
                style={{ flex: 1 }}
              />
              <input
                type="number"
                placeholder="Max marks"
                value={maxMarks}
                onChange={(e) => setMaxMarks(e.target.value)}
                style={{ width: 110 }}
                disabled={creating}
              />
            </div>
            <button type="submit" disabled={creating || !sectionId}>
              {creating ? "Assigning…" : "New assignment"}
            </button>
          </div>
        </form>
      </div>

      {assignments && assignments.length === 0 && (
        <div className="panel">
          <p className="empty-note">No assignments for this section yet.</p>
        </div>
      )}

      {assignments?.map((a) => (
        <div key={a.id} className="panel">
          <button
            type="button"
            className="panel-btn"
            style={{ padding: 0 }}
            onClick={() => setOpenId(openId === a.id ? null : a.id)}
          >
            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
              <div>
                <div>{a.title}</div>
                <div className="list-row-sub">
                  due {a.dueAt ? a.dueAt.slice(0, 16).replace("T", " ") : "—"} · max {a.maxMarks ?? "—"}
                </div>
              </div>
              <span className="badge">{a.status}</span>
            </div>
          </button>
          {openId === a.id && <Submissions assignment={a} />}
        </div>
      ))}
    </main>
  );
}

function Submissions({ assignment }: { assignment: AssignmentDto }) {
  const [subs, setSubs] = useState<AssignmentSubmissionDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [marksIn, setMarksIn] = useState<Record<string, string>>({});
  const [feedbackIn, setFeedbackIn] = useState<Record<string, string>>({});
  const [gradingId, setGradingId] = useState<string | null>(null);

  useEffect(() => {
    submissionsForAssignment(assignment.id)
      .then((s) => {
        setSubs(s);
        const m: Record<string, string> = {};
        const f: Record<string, string> = {};
        for (const sub of s) {
          m[sub.id] = sub.marks != null ? String(sub.marks) : "";
          f[sub.id] = sub.feedback ?? "";
        }
        setMarksIn(m);
        setFeedbackIn(f);
      })
      .catch((err) => setError(describeError(err)));
  }, [assignment.id]);

  async function onGrade(sub: AssignmentSubmissionDto) {
    setGradingId(sub.id);
    setError(null);
    try {
      const updated = await gradeSubmission(sub.id, {
        marks: Number(marksIn[sub.id]) || 0,
        feedback: feedbackIn[sub.id] || undefined,
      });
      setSubs((prev) => prev?.map((s) => (s.id === updated.id ? updated : s)) ?? null);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setGradingId(null);
    }
  }

  return (
    <div style={{ marginTop: 14 }}>
      {error && <div className="error-banner">{error}</div>}
      {!subs && <p className="hint">Loading submissions…</p>}
      {subs && subs.length === 0 && <p className="empty-note">No submissions yet.</p>}
      {subs?.map((s) => (
        <div key={s.id} style={{ borderTop: "1px solid var(--border)", paddingTop: 10, marginTop: 10 }}>
          <div className="list-row-sub">
            student {s.studentId.slice(0, 8)} · submitted {s.submittedAt ? s.submittedAt.slice(0, 16).replace("T", " ") : "—"}
          </div>
          {s.body && <p style={{ fontSize: 13.5, margin: "6px 0" }}>{s.body}</p>}
          <div className="form-row" style={{ marginTop: 6 }}>
            <input
              type="number"
              placeholder={`/ ${assignment.maxMarks ?? ""}`}
              value={marksIn[s.id] ?? ""}
              onChange={(e) => setMarksIn((m) => ({ ...m, [s.id]: e.target.value }))}
              style={{ width: 90 }}
            />
            <input
              placeholder="Feedback"
              value={feedbackIn[s.id] ?? ""}
              onChange={(e) => setFeedbackIn((f) => ({ ...f, [s.id]: e.target.value }))}
              style={{ flex: 1 }}
            />
            <button type="button" onClick={() => onGrade(s)} disabled={gradingId === s.id}>
              {s.gradedAt ? "Update" : "Grade"}
            </button>
          </div>
        </div>
      ))}
    </div>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}
