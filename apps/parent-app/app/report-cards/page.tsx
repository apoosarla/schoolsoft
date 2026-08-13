"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  AssessmentDto,
  assessmentsForSection,
  componentsForAssessment,
  getSession,
  MarkReevaluationDto,
  marksForComponent,
  reevaluationsForStudent,
  reportCard,
  ReportCardDetailDto,
  ReportCardDto,
  reportCardsForStudent,
  requestReevaluation,
  Session,
  StudentDto,
  studentsOfGuardian,
} from "@/lib/api";

type GradeRow = {
  assessment: AssessmentDto;
  markId: string;
  marks: number | null;
  maxMarks: number | null;
  status: string;
};

/** How a mark reads when there is no number behind it. */
function scoreLabel(row: GradeRow): string {
  switch (row.status) {
    case "absent":
      return "Absent";
    case "medical_leave":
      return "Absent (medical)";
    case "exempt":
      return "Exempt";
    case "pending":
      return "Not marked yet";
    default:
      return `${row.marks ?? "—"} / ${row.maxMarks ?? "—"}`;
  }
}

export default function ReportCardsPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [children, setChildren] = useState<StudentDto[] | null>(null);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [reportCards, setReportCards] = useState<ReportCardDto[] | null>(null);
  const [openCard, setOpenCard] = useState<ReportCardDetailDto | null>(null);
  const [grades, setGrades] = useState<GradeRow[] | null>(null);
  const [reevaluations, setReevaluations] = useState<MarkReevaluationDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

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
    setOpenCard(null);
    setNotice(null);
    reportCardsForStudent(active.id).then(setReportCards).catch((err) => setError(describeError(err)));
    reevaluationsForStudent(active.id).then(setReevaluations).catch(() => setReevaluations([]));

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
          rows.push({
            assessment: a,
            markId: m.id,
            marks: m.rawMarks,
            maxMarks: comp.maxMarks,
            status: m.status,
          });
        }
        setGrades(rows);
      } catch (err) {
        setError(describeError(err));
      }
    })();
  }, [active]);

  async function askForReevaluation(row: GradeRow) {
    if (!active) return;
    const reason = window.prompt(`Why should ${row.assessment.name} be looked at again?`)?.trim();
    if (!reason) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await requestReevaluation(row.markId, reason);
      setNotice("Request sent. The school will review the paper and you will see the outcome here.");
      setReevaluations(await reevaluationsForStudent(active.id));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

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
      {notice && <p className="hint">{notice}</p>}

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
                  <th />
                </tr>
              </thead>
              <tbody>
                {grades.map((g) => {
                  const pending = reevaluations?.find((r) => r.markId === g.markId && r.status === "pending");
                  const decided = reevaluations?.find((r) => r.markId === g.markId && r.status !== "pending");
                  return (
                    <tr key={g.assessment.id}>
                      <td>
                        {g.assessment.name}
                        <div className="list-row-sub">{g.assessment.assessmentType.replace("_", " ")}</div>
                      </td>
                      <td>{scoreLabel(g)}</td>
                      <td>
                        {pending ? (
                          <span className="badge">under review</span>
                        ) : decided ? (
                          <span className="badge">{decided.status}</span>
                        ) : (
                          g.status === "entered" && (
                            <button
                              type="button"
                              className="chip-btn"
                              disabled={busy}
                              onClick={() => askForReevaluation(g)}
                            >
                              Ask for a re-check
                            </button>
                          )
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>

        <div className="panel">
          <h2>Report cards</h2>
          {!reportCards && <p className="hint">Loading…</p>}
          {reportCards && reportCards.length === 0 && (
            <p className="empty-note">Nothing published yet. Cards appear here once the school releases them.</p>
          )}
          {reportCards && reportCards.length > 0 && (
            <div className="timeline" style={{ marginTop: 12 }}>
              {reportCards.map((rc) => (
                <button
                  type="button"
                  className="tl-item panel-btn"
                  key={rc.id}
                  style={{ textAlign: "left", width: "100%" }}
                  onClick={async () => {
                    try {
                      setOpenCard(await reportCard(rc.id));
                    } catch (err) {
                      setError(describeError(err));
                    }
                  }}
                >
                  <div className="tl-time">{(rc.publishedAt ?? rc.generatedAt).slice(0, 10)}</div>
                  <div className="tl-title">{rc.templateCode}</div>
                  <div className="tl-sub">
                    {rc.overallGrade ? `Grade ${rc.overallGrade}` : "Published"}
                    {rc.overallPct != null ? ` · ${rc.overallPct}%` : ""}
                    {rc.classRank ? ` · rank ${rc.classRank} of ${rc.classSize}` : ""}
                  </div>
                </button>
              ))}
            </div>
          )}
        </div>
      </div>

      {openCard && (
        <div className="panel">
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
            <h2>{openCard.card.templateCode}</h2>
            <button type="button" className="chip-btn" onClick={() => setOpenCard(null)}>
              Close
            </button>
          </div>

          {openCard.card.coverageNote && <p className="hint">{openCard.card.coverageNote}</p>}

          <table>
            <thead>
              <tr>
                <th>Subject</th>
                <th>Marks</th>
                <th>Grade</th>
              </tr>
            </thead>
            <tbody>
              {openCard.subjects.map((row) => (
                <tr key={row.subjectId}>
                  <td>
                    {row.subjectName}
                    <div className="list-row-sub">{row.subjectCode}</div>
                  </td>
                  {/* "AB" for an absence — the school did not record a zero. */}
                  <td>{row.display}</td>
                  <td>{row.gradeLetter ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>

          {openCard.coScholastic.length > 0 && (
            <table style={{ marginTop: 14 }}>
              <thead>
                <tr>
                  <th>Beyond the classroom</th>
                  <th>Rating</th>
                </tr>
              </thead>
              <tbody>
                {openCard.coScholastic.map((area) => (
                  <tr key={area.areaCode}>
                    <td>{area.areaName}</td>
                    <td>{area.rating}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          <div className="list-row-sub" style={{ marginTop: 14 }}>
            {openCard.card.attendancePct != null && (
              <div>
                Attendance {openCard.card.attendancePct}% ({openCard.card.attendancePresentDays ?? "—"} of{" "}
                {openCard.card.attendanceWorkingDays ?? "—"} school days)
              </div>
            )}
            {openCard.card.promotionDecision && <div>Result: {openCard.card.promotionDecision}</div>}
            {openCard.card.teacherRemarks && <div>Class teacher: {openCard.card.teacherRemarks}</div>}
            {openCard.card.principalRemarks && <div>Principal: {openCard.card.principalRemarks}</div>}
          </div>
        </div>
      )}
    </main>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}
