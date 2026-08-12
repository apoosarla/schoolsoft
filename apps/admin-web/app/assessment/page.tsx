"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  addAssessmentComponent,
  ApiError,
  ASSESSMENT_STATUSES,
  ASSESSMENT_TYPES,
  AssessmentComponentDto,
  AssessmentDto,
  createAssessment,
  enterMark,
  getSession,
  hasScreen,
  listAssessmentComponents,
  listAssessmentsForSection,
  listMarksForComponent,
  listSections,
  listStudents,
  listSubjects,
  MarkDto,
  SectionDto,
  Session,
  setAssessmentStatus,
  StudentDto,
  SubjectDto,
} from "@/lib/api";

const emptyAssessmentForm = {
  subjectId: "",
  name: "",
  assessmentType: ASSESSMENT_TYPES[0],
  maxMarks: "",
  weightPct: "",
  scheduledOn: "",
};

const emptyComponentForm = { code: "", name: "", maxMarks: "", weightPct: "", sortOrder: "1" };

export default function AssessmentPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [sections, setSections] = useState<SectionDto[] | null>(null);
  const [subjects, setSubjects] = useState<SubjectDto[] | null>(null);
  const [sectionId, setSectionId] = useState("");
  const [error, setError] = useState<string | null>(null);

  const [assessments, setAssessments] = useState<AssessmentDto[] | null>(null);
  const [showAssessmentForm, setShowAssessmentForm] = useState(false);
  const [assessmentForm, setAssessmentForm] = useState(emptyAssessmentForm);
  const [creatingAssessment, setCreatingAssessment] = useState(false);

  const [selectedAssessment, setSelectedAssessment] = useState<AssessmentDto | null>(null);
  const [components, setComponents] = useState<AssessmentComponentDto[] | null>(null);
  const [showComponentForm, setShowComponentForm] = useState(false);
  const [componentForm, setComponentForm] = useState(emptyComponentForm);
  const [creatingComponent, setCreatingComponent] = useState(false);
  const [statusSaving, setStatusSaving] = useState(false);

  const [selectedComponent, setSelectedComponent] = useState<AssessmentComponentDto | null>(null);
  const [roster, setRoster] = useState<StudentDto[] | null>(null);
  const [marks, setMarks] = useState<Record<string, { rawMarks: string; isAbsent: boolean }>>({});
  const [savingAll, setSavingAll] = useState(false);

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    if (!hasScreen(s, "assessment")) {
      router.replace("/dashboard");
      return;
    }
    setSessionState(s);
    Promise.all([listSections(s.schoolId), listSubjects(s.schoolId)])
      .then(([secs, subs]) => {
        setSections(secs);
        setSubjects(subs);
        if (secs.length > 0) setSectionId(secs[0].id);
        setAssessmentForm((f) => ({ ...f, subjectId: subs[0]?.id ?? "" }));
      })
      .catch((err) => setError(describeError(err)));
  }, [router]);

  function refreshAssessments(id: string) {
    setError(null);
    listAssessmentsForSection(id)
      .then(setAssessments)
      .catch((err) => setError(describeError(err)));
  }

  useEffect(() => {
    if (!sectionId) return;
    setSelectedAssessment(null);
    setSelectedComponent(null);
    refreshAssessments(sectionId);
  }, [sectionId]);

  async function onCreateAssessment() {
    if (!session) return;
    const section = sections?.find((s) => s.id === sectionId);
    setCreatingAssessment(true);
    setError(null);
    try {
      await createAssessment({
        schoolId: session.schoolId,
        sectionId,
        subjectId: assessmentForm.subjectId,
        strategyCode: section?.strategyCode ?? "default",
        name: assessmentForm.name,
        assessmentType: assessmentForm.assessmentType,
        maxMarks: assessmentForm.maxMarks ? Number(assessmentForm.maxMarks) : undefined,
        weightPct: assessmentForm.weightPct ? Number(assessmentForm.weightPct) : undefined,
        scheduledOn: assessmentForm.scheduledOn || undefined,
      });
      setAssessmentForm((f) => ({ ...emptyAssessmentForm, subjectId: f.subjectId }));
      setShowAssessmentForm(false);
      refreshAssessments(sectionId);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setCreatingAssessment(false);
    }
  }

  async function selectAssessment(a: AssessmentDto) {
    setSelectedAssessment(a);
    setSelectedComponent(null);
    setError(null);
    try {
      setComponents(await listAssessmentComponents(a.id));
    } catch (err) {
      setError(describeError(err));
    }
  }

  async function onStatusChange(status: string) {
    if (!selectedAssessment) return;
    // Reopening marks a family has already seen is a decision somebody owns.
    const reopening =
      ["locked", "published"].includes(selectedAssessment.status) &&
      !["locked", "published"].includes(status);
    let reason: string | undefined;
    if (reopening) {
      reason = window.prompt("Why is this assessment being reopened?")?.trim();
      if (!reason) return;
    }
    setStatusSaving(true);
    setError(null);
    try {
      const updated = await setAssessmentStatus(selectedAssessment.id, status, reason);
      setSelectedAssessment(updated);
      refreshAssessments(sectionId);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setStatusSaving(false);
    }
  }

  async function onCreateComponent() {
    if (!selectedAssessment) return;
    setCreatingComponent(true);
    setError(null);
    try {
      await addAssessmentComponent(selectedAssessment.id, {
        code: componentForm.code,
        name: componentForm.name,
        maxMarks: Number(componentForm.maxMarks),
        weightPct: componentForm.weightPct ? Number(componentForm.weightPct) : undefined,
        sortOrder: Number(componentForm.sortOrder),
      });
      setComponentForm(emptyComponentForm);
      setShowComponentForm(false);
      setComponents(await listAssessmentComponents(selectedAssessment.id));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setCreatingComponent(false);
    }
  }

  async function selectComponent(c: AssessmentComponentDto) {
    if (!session || !selectedAssessment) return;
    setSelectedComponent(c);
    setError(null);
    try {
      const [students, existing] = await Promise.all([
        listStudents(session.schoolId, undefined, selectedAssessment.sectionId),
        listMarksForComponent(c.id),
      ]);
      setRoster(students);
      const initial: Record<string, { rawMarks: string; isAbsent: boolean }> = {};
      for (const s of students) {
        const m = existing.find((e) => e.studentId === s.id);
        initial[s.id] = { rawMarks: m?.rawMarks?.toString() ?? "", isAbsent: m?.isAbsent ?? false };
      }
      setMarks(initial);
    } catch (err) {
      setError(describeError(err));
    }
  }

  async function onSaveAllMarks() {
    if (!session || !selectedComponent || !roster) return;
    setSavingAll(true);
    setError(null);
    try {
      for (const s of roster) {
        const m = marks[s.id];
        await enterMark(selectedComponent.id, {
          schoolId: session.schoolId,
          studentId: s.id,
          rawMarks: m.isAbsent || m.rawMarks === "" ? undefined : Number(m.rawMarks),
          isAbsent: m.isAbsent,
        });
      }
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSavingAll(false);
    }
  }

  if (!session) return null;

  return (
    <main className="shell">
      <div className="panel">
        <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
          <h2>Assessment</h2>
          <button type="button" onClick={() => setShowAssessmentForm((v) => !v)} disabled={!subjects || subjects.length === 0}>
            {showAssessmentForm ? "Cancel" : "New assessment"}
          </button>
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

        {showAssessmentForm && (
          <div className="form-row" style={{ flexWrap: "wrap" }}>
            <select
              value={assessmentForm.subjectId}
              onChange={(e) => setAssessmentForm((f) => ({ ...f, subjectId: e.target.value }))}
            >
              {subjects?.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
            <input
              placeholder="Name (e.g. Periodic Test 1)"
              value={assessmentForm.name}
              onChange={(e) => setAssessmentForm((f) => ({ ...f, name: e.target.value }))}
            />
            <select
              value={assessmentForm.assessmentType}
              onChange={(e) => setAssessmentForm((f) => ({ ...f, assessmentType: e.target.value }))}
            >
              {ASSESSMENT_TYPES.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
            <input
              type="number"
              placeholder="Max marks"
              value={assessmentForm.maxMarks}
              onChange={(e) => setAssessmentForm((f) => ({ ...f, maxMarks: e.target.value }))}
              style={{ maxWidth: 110 }}
            />
            <input
              type="number"
              placeholder="Weight %"
              value={assessmentForm.weightPct}
              onChange={(e) => setAssessmentForm((f) => ({ ...f, weightPct: e.target.value }))}
              style={{ maxWidth: 110 }}
            />
            <input
              type="date"
              value={assessmentForm.scheduledOn}
              onChange={(e) => setAssessmentForm((f) => ({ ...f, scheduledOn: e.target.value }))}
            />
            <button
              type="button"
              onClick={onCreateAssessment}
              disabled={creatingAssessment || !assessmentForm.subjectId || !assessmentForm.name}
            >
              {creatingAssessment ? "Creating…" : "Create assessment"}
            </button>
          </div>
        )}

        {error && <div className="error-banner">{error}</div>}
        {assessments && assessments.length === 0 && <p className="hint">No assessments for this section yet.</p>}

        {assessments && assessments.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Subject</th>
                <th>Type</th>
                <th>Max marks</th>
                <th>Scheduled</th>
                <th>Status</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {assessments.map((a) => (
                <tr key={a.id} style={{ cursor: "pointer" }} onClick={() => selectAssessment(a)}>
                  <td>{a.name}</td>
                  <td>{subjects?.find((s) => s.id === a.subjectId)?.name ?? "—"}</td>
                  <td>{a.assessmentType}</td>
                  <td>{a.maxMarks ?? "—"}</td>
                  <td>{a.scheduledOn ?? "—"}</td>
                  <td>
                    <span className="badge">{a.status}</span>
                  </td>
                  <td>
                    <button type="button" onClick={() => selectAssessment(a)}>
                      {selectedAssessment?.id === a.id ? "Selected" : "Open"}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {selectedAssessment && (
        <div className="panel">
          <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
            <h2>Components — {selectedAssessment.name}</h2>
            <div className="form-row" style={{ gap: 4 }}>
              <select
                value={selectedAssessment.status}
                onChange={(e) => onStatusChange(e.target.value)}
                disabled={statusSaving}
              >
                {ASSESSMENT_STATUSES.map((st) => (
                  <option key={st} value={st}>
                    {st}
                  </option>
                ))}
              </select>
              <button type="button" onClick={() => setShowComponentForm((v) => !v)}>
                {showComponentForm ? "Cancel" : "Add component"}
              </button>
            </div>
          </div>

          {showComponentForm && (
            <div className="form-row" style={{ flexWrap: "wrap" }}>
              <input
                placeholder="Code (e.g. Q1)"
                value={componentForm.code}
                onChange={(e) => setComponentForm((f) => ({ ...f, code: e.target.value }))}
                style={{ maxWidth: 100 }}
              />
              <input
                placeholder="Name"
                value={componentForm.name}
                onChange={(e) => setComponentForm((f) => ({ ...f, name: e.target.value }))}
              />
              <input
                type="number"
                placeholder="Max marks"
                value={componentForm.maxMarks}
                onChange={(e) => setComponentForm((f) => ({ ...f, maxMarks: e.target.value }))}
                style={{ maxWidth: 110 }}
              />
              <input
                type="number"
                placeholder="Weight %"
                value={componentForm.weightPct}
                onChange={(e) => setComponentForm((f) => ({ ...f, weightPct: e.target.value }))}
                style={{ maxWidth: 110 }}
              />
              <input
                type="number"
                placeholder="Sort order"
                value={componentForm.sortOrder}
                onChange={(e) => setComponentForm((f) => ({ ...f, sortOrder: e.target.value }))}
                style={{ maxWidth: 100 }}
              />
              <button
                type="button"
                onClick={onCreateComponent}
                disabled={creatingComponent || !componentForm.code || !componentForm.name || !componentForm.maxMarks}
              >
                {creatingComponent ? "Adding…" : "Add"}
              </button>
            </div>
          )}

          {components && components.length === 0 && <p className="hint">No components yet.</p>}
          {components && components.length > 0 && (
            <table>
              <thead>
                <tr>
                  <th>Code</th>
                  <th>Name</th>
                  <th>Max marks</th>
                  <th>Weight %</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {components
                  .slice()
                  .sort((a, b) => a.sortOrder - b.sortOrder)
                  .map((c) => (
                    <tr key={c.id} style={{ cursor: "pointer" }} onClick={() => selectComponent(c)}>
                      <td>{c.code}</td>
                      <td>{c.name}</td>
                      <td>{c.maxMarks}</td>
                      <td>{c.weightPct ?? "—"}</td>
                      <td>
                        <button type="button" onClick={() => selectComponent(c)}>
                          {selectedComponent?.id === c.id ? "Selected" : "Enter marks"}
                        </button>
                      </td>
                    </tr>
                  ))}
              </tbody>
            </table>
          )}
        </div>
      )}

      {selectedComponent && roster && (
        <div className="panel">
          <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
            <h2>
              Marks — {selectedComponent.name} <span className="hint">(max {selectedComponent.maxMarks})</span>
            </h2>
            <button type="button" onClick={onSaveAllMarks} disabled={savingAll || roster.length === 0}>
              {savingAll ? "Saving…" : "Save all"}
            </button>
          </div>
          {roster.length === 0 && <p className="hint">No students in this section.</p>}
          {roster.length > 0 && (
            <table>
              <thead>
                <tr>
                  <th>Roll no.</th>
                  <th>Name</th>
                  <th>Marks</th>
                  <th>Absent</th>
                </tr>
              </thead>
              <tbody>
                {roster
                  .slice()
                  .sort((a, b) => (a.rollNo ?? "").localeCompare(b.rollNo ?? ""))
                  .map((s) => (
                    <tr key={s.id}>
                      <td>{s.rollNo ?? "—"}</td>
                      <td>
                        {s.firstName} {s.lastName ?? ""}
                      </td>
                      <td>
                        <input
                          type="number"
                          max={selectedComponent.maxMarks}
                          value={marks[s.id]?.rawMarks ?? ""}
                          disabled={marks[s.id]?.isAbsent}
                          onChange={(e) =>
                            setMarks((m) => ({ ...m, [s.id]: { ...m[s.id], rawMarks: e.target.value } }))
                          }
                          style={{ maxWidth: 90 }}
                        />
                      </td>
                      <td>
                        <input
                          type="checkbox"
                          checked={marks[s.id]?.isAbsent ?? false}
                          onChange={(e) =>
                            setMarks((m) => ({ ...m, [s.id]: { ...m[s.id], isAbsent: e.target.checked } }))
                          }
                        />
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
