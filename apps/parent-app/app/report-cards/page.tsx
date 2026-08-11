"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  AssessmentDto,
  assessmentsForSection,
  componentsForAssessment,
  getSession,
  marksForComponent,
  ReportCardDto,
  reportCardsForStudent,
  Session,
  StudentDto,
  studentsOfGuardian,
} from "@/lib/api";

type GradeRow = { assessment: AssessmentDto; marks: number | null; maxMarks: number | null; isAbsent: boolean };

export default function ReportCardsPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [children, setChildren] = useState<StudentDto[] | null>(null);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [reportCards, setReportCards] = useState<ReportCardDto[] | null>(null);
  const [grades, setGrades] = useState<GradeRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);

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

  useEffect(() => {
    if (!active) return;
    setReportCards(null);
    setGrades(null);
    reportCardsForStudent(active.id).then(setReportCards).catch((err) => setError(describeError(err)));

    if (!active.currentSectionId) return;
    (async () => {
      try {
        const assessments = await assessmentsForSection(active.currentSectionId!);
        const rows: GradeRow[] = [];
        for (const a of assessments) {
          const comps = await componentsForAssessment(a.id);
          const comp = comps[0];
          if (!comp) continue;
          const marks = await marksForComponent(comp.id);
          const m = marks.find((mk) => mk.studentId === active.id);
          if (!m) continue;
          rows.push({ assessment: a, marks: m.rawMarks, maxMarks: comp.maxMarks, isAbsent: m.isAbsent });
        }
        setGrades(rows);
      } catch (err) {
        setError(describeError(err));
      }
    })();
  }, [active]);

  if (!session) return null;

  if (!session.subjectId) {
    return (
      <main className="shell">
        <div className="panel">
          <h2>Report cards</h2>
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

      <div className="grid-2">
        <div className="panel">
          <h2>Grades this year</h2>
          {!grades && <p className="hint">Loading…</p>}
          {grades && grades.length === 0 && <p className="empty-note">No marks entered yet.</p>}
          {grades && grades.length > 0 && (
            <table>
              <thead>
                <tr>
                  <th>Assessment</th>
                  <th>Score</th>
                </tr>
              </thead>
              <tbody>
                {grades.map((g) => (
                  <tr key={g.assessment.id}>
                    <td>
                      {g.assessment.name}
                      <div className="list-row-sub">{g.assessment.assessmentType.replace("_", " ")}</div>
                    </td>
                    <td>{g.isAbsent ? "Absent" : `${g.marks ?? "—"} / ${g.maxMarks ?? "—"}`}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        <div className="panel">
          <h2>Report cards</h2>
          {!reportCards && <p className="hint">Loading…</p>}
          {reportCards && reportCards.length === 0 && <p className="empty-note">No report card generated yet.</p>}
          {reportCards && reportCards.length > 0 && (
            <div className="timeline" style={{ marginTop: 12 }}>
              {reportCards.map((rc) => (
                <div className="tl-item" key={rc.id}>
                  <div className="tl-time">{rc.generatedAt.slice(0, 10)}</div>
                  <div className="tl-title">{rc.templateCode}</div>
                  <div className="tl-sub">
                    <span className={`badge ${rc.isLocked ? "badge-active" : ""}`}>{rc.isLocked ? "final" : "draft"}</span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </main>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}
