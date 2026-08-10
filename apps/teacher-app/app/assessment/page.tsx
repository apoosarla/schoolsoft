"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import {
  addAssessmentComponent,
  AssessmentComponentDto,
  AssessmentDto,
  assessmentsForSection,
  ApiError,
  componentsForAssessment,
  createAssessment,
  EnrolmentDto,
  enterMark,
  getSession,
  listSections,
  MarkDto,
  marksForComponent,
  rosterForSection,
  SectionDto,
  Session,
  timetableForTeacher,
  TimetableSlotDto,
} from "@/lib/api";

const TYPES = ["unit_test", "quiz", "assignment", "midterm", "final_exam", "project"];

export default function AssessmentPage() {
  return (
    <Suspense fallback={null}>
      <AssessmentInner />
    </Suspense>
  );
}

function AssessmentInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [session, setSessionState] = useState<Session | null>(null);
  const [mySections, setMySections] = useState<SectionDto[] | null>(null);
  const [subjectBySection, setSubjectBySection] = useState<Record<string, string>>({});
  const [sectionId, setSectionId] = useState("");
  const [assessments, setAssessments] = useState<AssessmentDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  const [name, setName] = useState("");
  const [assessmentType, setAssessmentType] = useState(TYPES[0]);
  const [maxMarks, setMaxMarks] = useState("100");

  const [openId, setOpenId] = useState<string | null>(null);

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
    assessmentsForSection(sectionId).then(setAssessments).catch((err) => setError(describeError(err)));
  }, [sectionId]);

  async function onCreate(e: React.FormEvent) {
    e.preventDefault();
    if (!session || !sectionId) return;
    const subjectId = subjectBySection[sectionId];
    const section = mySections?.find((s) => s.id === sectionId);
    if (!subjectId || !section) return;
    setCreating(true);
    setError(null);
    try {
      const assessment = await createAssessment({
        schoolId: session.schoolId,
        sectionId,
        subjectId,
        strategyCode: section.strategyCode,
        name: name.trim(),
        assessmentType,
        maxMarks: Number(maxMarks) || undefined,
      });
      await addAssessmentComponent(assessment.id, {
        code: "overall",
        name: "Overall",
        maxMarks: Number(maxMarks) || 100,
        sortOrder: 0,
      });
      setName("");
      const refreshed = await assessmentsForSection(sectionId);
      setAssessments(refreshed);
      setOpenId(assessment.id);
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
          <h2>Assessment</h2>
          <p className="hint">This account isn&apos;t linked to a staff record.</p>
        </div>
      </main>
    );
  }

  return (
    <main className="shell">
      <div className="panel">
        <h2>Assessments</h2>
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
            <input
              placeholder="Assessment name (e.g. Unit Test 1)"
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
              disabled={creating}
            />
            <div className="form-row">
              <select value={assessmentType} onChange={(e) => setAssessmentType(e.target.value)} disabled={creating} style={{ flex: 1 }}>
                {TYPES.map((t) => (
                  <option key={t} value={t}>
                    {t.replace("_", " ")}
                  </option>
                ))}
              </select>
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
              {creating ? "Creating…" : "New assessment"}
            </button>
          </div>
        </form>
      </div>

      {assessments && assessments.length === 0 && (
        <div className="panel">
          <p className="empty-note">No assessments for this section yet.</p>
        </div>
      )}

      {assessments?.map((a) => (
        <div key={a.id} className="panel">
          <button
            type="button"
            className="panel-btn"
            style={{ padding: 0 }}
            onClick={() => setOpenId(openId === a.id ? null : a.id)}
          >
            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
              <div>
                <div>{a.name}</div>
                <div className="list-row-sub">
                  {a.assessmentType.replace("_", " ")} · max {a.maxMarks ?? "—"}
                </div>
              </div>
              <span className="badge">{a.status}</span>
            </div>
          </button>
          {openId === a.id && <MarksEditor assessment={a} schoolId={session.schoolId} sectionId={sectionId} />}
        </div>
      ))}
    </main>
  );
}

function MarksEditor({ assessment, schoolId, sectionId }: { assessment: AssessmentDto; schoolId: string; sectionId: string }) {
  const [component, setComponent] = useState<AssessmentComponentDto | null>(null);
  const [roster, setRoster] = useState<EnrolmentDto[] | null>(null);
  const [marksIn, setMarksIn] = useState<Record<string, string>>({});
  const [absent, setAbsent] = useState<Record<string, boolean>>({});
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveMessage, setSaveMessage] = useState<string | null>(null);

  useEffect(() => {
    setError(null);
    setSaveMessage(null);
    Promise.all([componentsForAssessment(assessment.id), rosterForSection(sectionId)])
      .then(async ([comps, r]) => {
        setRoster(r);
        const comp = comps[0] ?? null;
        setComponent(comp);
        if (!comp) return;
        const marks = await marksForComponent(comp.id);
        const initIn: Record<string, string> = {};
        const initAbs: Record<string, boolean> = {};
        for (const enr of r) {
          const m = marks.find((mk) => mk.studentId === enr.studentId);
          initIn[enr.studentId] = m?.rawMarks != null ? String(m.rawMarks) : "";
          initAbs[enr.studentId] = m?.isAbsent ?? false;
        }
        setMarksIn(initIn);
        setAbsent(initAbs);
      })
      .catch((err) => setError(describeError(err)));
  }, [assessment.id, sectionId]);

  async function onSave() {
    if (!component || !roster) return;
    setSaving(true);
    setError(null);
    setSaveMessage(null);
    try {
      await Promise.all(
        roster.map((r) =>
          enterMark(component.id, {
            schoolId,
            studentId: r.studentId,
            rawMarks: marksIn[r.studentId] ? Number(marksIn[r.studentId]) : undefined,
            isAbsent: absent[r.studentId] ?? false,
          })
        )
      );
      setSaveMessage(`Saved marks for ${roster.length} student(s).`);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div style={{ marginTop: 14 }}>
      {error && <div className="error-banner">{error}</div>}
      {saveMessage && <p className="hint">{saveMessage}</p>}
      {!roster && <p className="hint">Loading roster…</p>}
      {roster && roster.length === 0 && <p className="empty-note">No active students in this section.</p>}
      {roster && roster.length > 0 && component && (
        <>
          <table>
            <thead>
              <tr>
                <th>Roll</th>
                <th>Marks / {component.maxMarks}</th>
                <th>Absent</th>
              </tr>
            </thead>
            <tbody>
              {roster
                .slice()
                .sort((a, b) => (a.rollNo ?? "").localeCompare(b.rollNo ?? ""))
                .map((r) => (
                  <tr key={r.studentId}>
                    <td>{r.rollNo ?? "—"}</td>
                    <td>
                      <input
                        type="number"
                        value={marksIn[r.studentId] ?? ""}
                        onChange={(e) => setMarksIn((m) => ({ ...m, [r.studentId]: e.target.value }))}
                        disabled={absent[r.studentId]}
                        style={{ width: 90, minHeight: 36 }}
                      />
                    </td>
                    <td>
                      <input
                        type="checkbox"
                        checked={absent[r.studentId] ?? false}
                        onChange={(e) => setAbsent((a) => ({ ...a, [r.studentId]: e.target.checked }))}
                      />
                    </td>
                  </tr>
                ))}
            </tbody>
          </table>
          <div style={{ marginTop: 14 }}>
            <button type="button" onClick={onSave} disabled={saving} style={{ width: "100%" }}>
              {saving ? "Saving…" : "Save marks"}
            </button>
          </div>
        </>
      )}
    </div>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}
