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
  AssessmentValidationDto,
  BulkMarkResult,
  createAssessment,
  enterMarksInBulk,
  generateReportCardsForSection,
  getMe,
  getSession,
  hasScreen,
  listAssessmentComponents,
  listAssessmentsForSection,
  listMarksForComponent,
  listSections,
  listStudents,
  listSubjects,
  listTerms,
  lockReportCard,
  MARK_STATUSES,
  MarkDto,
  PROMOTION_DECISIONS,
  publishReportCard,
  reportCardDetail,
  ReportCardDetailDto,
  ReportCardDto,
  reportCardsForStudent,
  SectionDto,
  Session,
  setAssessmentStatus,
  setPromotionDecision,
  StudentDto,
  SubjectDto,
  TermDto,
  unlockReportCard,
  validateAssessment,
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

  const [validation, setValidation] = useState<AssessmentValidationDto | null>(null);

  const [selectedComponent, setSelectedComponent] = useState<AssessmentComponentDto | null>(null);
  const [roster, setRoster] = useState<StudentDto[] | null>(null);
  /** A mark is a number *or* a reason there is none — never a silent blank. */
  const [marks, setMarks] = useState<Record<string, { rawMarks: string; status: string; revisions: number }>>({});
  const [savingAll, setSavingAll] = useState(false);
  const [bulkResult, setBulkResult] = useState<BulkMarkResult | null>(null);

  const [staffId, setStaffId] = useState("");
  const [terms, setTerms] = useState<TermDto[] | null>(null);
  const [termId, setTermId] = useState("");
  const [templateCode, setTemplateCode] = useState("TERM-REPORT");
  const [cards, setCards] = useState<ReportCardDto[] | null>(null);
  const [cardDetail, setCardDetail] = useState<ReportCardDetailDto | null>(null);
  const [cardsBusy, setCardsBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

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
    getMe()
      .then((me) => setStaffId(me.subjectId))
      .catch(() => setStaffId(""));
    Promise.all([listSections(s.schoolId), listSubjects(s.schoolId)])
      .then(([secs, subs]) => {
        setSections(secs);
        setSubjects(subs);
        if (secs.length > 0) setSectionId(secs[0].id);
        setAssessmentForm((f) => ({ ...f, subjectId: subs[0]?.id ?? "" }));
      })
      .catch((err) => setError(describeError(err)));
  }, [router]);

  // Report cards are per term, so the section's year decides the choices.
  useEffect(() => {
    const section = sections?.find((s) => s.id === sectionId);
    if (!section) return;
    listTerms(section.academicYearId)
      .then((ts) => {
        setTerms(ts);
        setTermId((current) => (ts.some((t) => t.id === current) ? current : ts[0]?.id ?? ""));
      })
      .catch(() => setTerms([]));
  }, [sections, sectionId]);

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
    setBulkResult(null);
    setError(null);
    try {
      const [comps, check] = await Promise.all([listAssessmentComponents(a.id), validateAssessment(a.id)]);
      setComponents(comps);
      setValidation(check);
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
      setValidation(await validateAssessment(selectedAssessment.id));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setCreatingComponent(false);
    }
  }

  async function selectComponent(c: AssessmentComponentDto) {
    if (!session || !selectedAssessment) return;
    setSelectedComponent(c);
    setBulkResult(null);
    setError(null);
    try {
      const [students, existing] = await Promise.all([
        listStudents(session.schoolId, undefined, selectedAssessment.sectionId),
        listMarksForComponent(c.id),
      ]);
      setRoster(students);
      setMarks(markStateFrom(students, existing));
    } catch (err) {
      setError(describeError(err));
    }
  }

  /**
   * One bulk call rather than a request per child. The endpoint validates each
   * row on its own, so a mark above the maximum comes back named while the rest
   * are stored — which is the behaviour a teacher entering forty marks needs.
   */
  async function onSaveAllMarks() {
    if (!session || !selectedComponent || !roster) return;
    setSavingAll(true);
    setError(null);
    setBulkResult(null);
    try {
      const result = await enterMarksInBulk({
        schoolId: session.schoolId,
        componentId: selectedComponent.id,
        enteredByStaffId: staffId || undefined,
        entries: roster.map((s) => {
          const m = marks[s.id];
          const status = m?.status ?? "pending";
          return {
            studentId: s.id,
            status,
            rawMarks: status === "entered" && m?.rawMarks !== "" ? Number(m.rawMarks) : undefined,
          };
        }),
      });
      setBulkResult(result);
      const refreshed = await listMarksForComponent(selectedComponent.id);
      setMarks(markStateFrom(roster, refreshed));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSavingAll(false);
    }
  }

  // -------------------------------------------------------- report cards

  async function runCards(action: () => Promise<void>) {
    setCardsBusy(true);
    setError(null);
    setNotice(null);
    try {
      await action();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setCardsBusy(false);
    }
  }

  async function refreshCards() {
    if (!roster || roster.length === 0) return;
    const lists = await Promise.all(roster.map((s) => reportCardsForStudent(s.id).catch(() => [])));
    const wanted = lists
      .flat()
      .filter((c) => c.templateCode === templateCode && (termId ? c.termId === termId : true));
    setCards(wanted);
  }

  /** Loads the section roster even when no component is open — cards are per section. */
  async function ensureRoster(): Promise<StudentDto[]> {
    if (roster && roster.length > 0) return roster;
    if (!session) return [];
    const students = await listStudents(session.schoolId, undefined, sectionId);
    setRoster(students);
    return students;
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

          {validation && !validation.valid && (
            <div className="warn-banner">
              This assessment cannot open for marking yet: {validation.issues.join("; ")}.
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
          <p className="hint">
            Typing a mark records it as entered — zero included. Leave it blank and pick a reason instead:
            an unmarked paper is <em>pending</em>, and an absence is never a nought.
          </p>

          {bulkResult && (
            <>
              <p className="hint">{bulkResult.accepted} mark(s) saved.</p>
              {bulkResult.rejected.length > 0 && (
                <div className="warn-banner">
                  {bulkResult.rejected.length} row(s) refused and not stored:
                  <ul className="rejected-list">
                    {bulkResult.rejected.map((r) => (
                      <li key={r.studentId}>
                        {studentLabel(roster, r.studentId)} — {r.reason}
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </>
          )}

          {roster.length === 0 && <p className="hint">No students in this section.</p>}
          {roster.length > 0 && (
            <table>
              <thead>
                <tr>
                  <th>Roll no.</th>
                  <th>Name</th>
                  <th>Marks</th>
                  <th>Status</th>
                  <th>History</th>
                </tr>
              </thead>
              <tbody>
                {roster
                  .slice()
                  .sort((a, b) => (a.rollNo ?? "").localeCompare(b.rollNo ?? ""))
                  .map((s) => {
                    const mark = marks[s.id] ?? { rawMarks: "", status: "pending", revisions: 0 };
                    return (
                      <tr key={s.id}>
                        <td>{s.rollNo ?? "—"}</td>
                        <td>
                          {s.firstName} {s.lastName ?? ""}
                        </td>
                        <td>
                          <input
                            type="number"
                            max={selectedComponent.maxMarks}
                            value={mark.rawMarks}
                            disabled={mark.status !== "entered" && mark.status !== "pending"}
                            onChange={(e) =>
                              setMarks((m) => ({
                                ...m,
                                [s.id]: {
                                  ...mark,
                                  rawMarks: e.target.value,
                                  // Typing a number is what makes it an entered mark.
                                  status: e.target.value === "" ? "pending" : "entered",
                                },
                              }))
                            }
                            className="mark-cell"
                          />
                        </td>
                        <td>
                          <select
                            value={mark.status}
                            onChange={(e) =>
                              setMarks((m) => ({
                                ...m,
                                [s.id]: {
                                  ...mark,
                                  status: e.target.value,
                                  rawMarks: e.target.value === "entered" ? mark.rawMarks : "",
                                },
                              }))
                            }
                          >
                            {MARK_STATUSES.map((st) => (
                              <option key={st} value={st}>
                                {st.replace("_", " ")}
                              </option>
                            ))}
                          </select>
                        </td>
                        <td>{mark.revisions > 0 ? `${mark.revisions} revision(s)` : "—"}</td>
                      </tr>
                    );
                  })}
              </tbody>
            </table>
          )}
        </div>
      )}

      {/* ---------------------------------------------------- report cards */}
      <div className="panel">
        <h2>Report cards</h2>
        <p className="hint">
          Built from the marks entered above: each student&apos;s own subjects, absences shown as AB rather
          than nought, attendance over the school-calendar denominator, rank across the section, and the
          promotion decision the year-end rollover reads.
        </p>
        {notice && <div className="notice-banner">{notice}</div>}

        <div className="form-row">
          <select value={termId} onChange={(e) => setTermId(e.target.value)}>
            <option value="">Whole year</option>
            {terms?.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
              </option>
            ))}
          </select>
          <input
            placeholder="Template code"
            value={templateCode}
            onChange={(e) => setTemplateCode(e.target.value)}
          />
          <button
            type="button"
            disabled={cardsBusy || !sectionId}
            onClick={() =>
              runCards(async () => {
                const section = sections?.find((s) => s.id === sectionId);
                if (!session || !section) return;
                await ensureRoster();
                const generated = await generateReportCardsForSection({
                  schoolId: session.schoolId,
                  sectionId,
                  academicYearId: section.academicYearId,
                  termId: termId || undefined,
                  strategyCode: section.strategyCode,
                  templateCode,
                });
                setCards(generated);
                setNotice(`${generated.length} card(s) generated and ranked.`);
              })
            }
          >
            Generate for section
          </button>
          <button
            type="button"
            className="secondary"
            disabled={cardsBusy}
            onClick={() =>
              runCards(async () => {
                await ensureRoster();
                await refreshCards();
              })
            }
          >
            Refresh
          </button>
        </div>

        {cards && cards.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Student</th>
                <th>Marks</th>
                <th>%</th>
                <th>Grade</th>
                <th>Rank</th>
                <th>Attendance</th>
                <th>Promotion</th>
                <th>Status</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {cards.map((c) => (
                <tr key={c.id}>
                  <td>{studentLabel(roster, c.studentId)}</td>
                  <td>
                    {c.totalMarks ?? "—"}
                    {c.totalMaxMarks ? ` / ${c.totalMaxMarks}` : ""}
                  </td>
                  <td>{c.overallPct ?? "—"}</td>
                  <td>{c.overallGrade ?? "—"}</td>
                  <td>{c.classRank ? `${c.classRank} of ${c.classSize}` : "—"}</td>
                  <td>{c.attendancePct != null ? `${c.attendancePct}%` : "—"}</td>
                  <td>
                    <select
                      value={c.promotionDecision ?? ""}
                      disabled={cardsBusy || c.status === "published"}
                      onChange={(e) =>
                        runCards(async () => {
                          await setPromotionDecision(c.id, e.target.value);
                          await refreshCards();
                        })
                      }
                    >
                      <option value="">—</option>
                      {PROMOTION_DECISIONS.map((d) => (
                        <option key={d} value={d}>
                          {d}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td>
                    <span className={"badge " + (c.status === "published" ? "badge-active" : "")}>{c.status}</span>
                  </td>
                  <td>
                    <div className="form-row inline">
                      <button
                        type="button"
                        className="secondary"
                        disabled={cardsBusy}
                        onClick={() => runCards(async () => setCardDetail(await reportCardDetail(c.id)))}
                      >
                        View
                      </button>
                      {c.status === "draft" && (
                        <button
                          type="button"
                          disabled={cardsBusy}
                          onClick={() =>
                            runCards(async () => {
                              await lockReportCard(c.id);
                              await refreshCards();
                            })
                          }
                        >
                          Lock
                        </button>
                      )}
                      {c.status === "locked" && (
                        <button
                          type="button"
                          disabled={cardsBusy}
                          onClick={() =>
                            runCards(async () => {
                              await publishReportCard(c.id);
                              setNotice("Published — the family can now see it in the parent app.");
                              await refreshCards();
                            })
                          }
                        >
                          Publish
                        </button>
                      )}
                      {c.status !== "draft" && (
                        <button
                          type="button"
                          className="secondary"
                          disabled={cardsBusy}
                          onClick={() =>
                            runCards(async () => {
                              const reason = window.prompt("Why is this card being unlocked?")?.trim();
                              if (!reason) return;
                              await unlockReportCard(c.id, reason);
                              await refreshCards();
                            })
                          }
                        >
                          Unlock
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {cards && cards.length === 0 && (
          <p className="hint">No cards for this term and template yet.</p>
        )}
      </div>

      {cardDetail && (
        <div className="panel">
          <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
            <h2>
              {studentLabel(roster, cardDetail.card.studentId)} — version {cardDetail.card.version}
            </h2>
            <button type="button" className="secondary" onClick={() => setCardDetail(null)}>
              Close
            </button>
          </div>

          {cardDetail.card.coverageNote && <div className="warn-banner">{cardDetail.card.coverageNote}</div>}

          <div className="stat-grid" style={{ marginBottom: 14 }}>
            <div className="stat-tile">
              <div className="value">{cardDetail.card.overallGrade ?? "—"}</div>
              <div className="label">
                Overall{cardDetail.card.overallPct != null ? ` · ${cardDetail.card.overallPct}%` : ""}
              </div>
            </div>
            <div className="stat-tile">
              <div className="value">
                {cardDetail.card.classRank ? `#${cardDetail.card.classRank}` : "—"}
              </div>
              <div className="label">
                Rank of {cardDetail.card.classSize ?? "—"}
                {cardDetail.card.percentile != null ? ` · ${cardDetail.card.percentile} pct` : ""}
              </div>
            </div>
            <div className="stat-tile">
              <div className="value">
                {cardDetail.card.attendancePct != null ? `${cardDetail.card.attendancePct}%` : "—"}
              </div>
              <div className="label">
                Attendance · {cardDetail.card.attendancePresentDays ?? "—"} of{" "}
                {cardDetail.card.attendanceWorkingDays ?? "—"} days
              </div>
            </div>
            <div className="stat-tile">
              <div className="value">{cardDetail.card.promotionDecision ?? "—"}</div>
              <div className="label">
                Promotion · terms {cardDetail.card.termsAttended ?? "—"}/{cardDetail.card.termsInYear ?? "—"}
              </div>
            </div>
          </div>

          <table>
            <thead>
              <tr>
                <th>Subject</th>
                <th>Origin</th>
                <th>Marks</th>
                <th>%</th>
                <th>Grade</th>
                <th>Result</th>
              </tr>
            </thead>
            <tbody>
              {cardDetail.subjects.map((row) => (
                <tr key={row.subjectId}>
                  <td>
                    {row.subjectCode} — {row.subjectName}
                  </td>
                  <td>{row.origin}</td>
                  <td>{row.display}</td>
                  <td>{row.percentage ?? "—"}</td>
                  <td>{row.gradeLetter ?? "—"}</td>
                  <td>
                    {row.resultStatus === "marked"
                      ? row.passing
                        ? "pass"
                        : "below pass mark"
                      : row.resultStatus.replace("_", " ")}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {cardDetail.coScholastic.length > 0 && (
            <table style={{ marginTop: 14 }}>
              <thead>
                <tr>
                  <th>Co-scholastic area</th>
                  <th>Rating</th>
                </tr>
              </thead>
              <tbody>
                {cardDetail.coScholastic.map((area) => (
                  <tr key={area.areaCode}>
                    <td>{area.areaName}</td>
                    <td>{area.rating}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          {cardDetail.card.teacherRemarks && (
            <p className="hint" style={{ marginTop: 12 }}>
              Teacher: {cardDetail.card.teacherRemarks}
            </p>
          )}
        </div>
      )}
    </main>
  );
}

/** Existing marks, mapped into the editor's shape: a number, or a reason there is none. */
function markStateFrom(
  students: StudentDto[],
  existing: MarkDto[]
): Record<string, { rawMarks: string; status: string; revisions: number }> {
  const state: Record<string, { rawMarks: string; status: string; revisions: number }> = {};
  for (const s of students) {
    const m = existing.find((e) => e.studentId === s.id);
    state[s.id] = {
      rawMarks: m?.rawMarks?.toString() ?? "",
      status: m?.status ?? "pending",
      revisions: m?.revisionCount ?? 0,
    };
  }
  return state;
}

function studentLabel(roster: StudentDto[] | null, studentId: string): string {
  const student = roster?.find((s) => s.id === studentId);
  return student ? `${student.firstName} ${student.lastName ?? ""}`.trim() : studentId.slice(0, 8);
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}
