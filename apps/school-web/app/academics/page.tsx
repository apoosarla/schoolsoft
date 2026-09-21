"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import {
  AcademicYearDto,
  ApiError,
  assignSectionTeacher,
  BellScheduleDto,
  createBellSchedule,
  createElectiveGroup,
  createSubject,
  dropElection,
  electSubject,
  electionsForEnrolment,
  ElectiveGroupDto,
  EnrolmentDto,
  enrolStudent,
  getSession,
  GradeDto,
  hasScreen,
  listAcademicYears,
  listBellSchedules,
  listElectiveGroups,
  listGrades,
  listSections,
  listSectionTeachers,
  listStaff,
  listStudents,
  listSubjects,
  renumberSection,
  rosterForSection,
  SectionDto,
  SectionTeacherDto,
  Session,
  StaffDto,
  StudentDto,
  StudentSubjectDto,
  SubjectDto,
  subjectsForEnrolment,
  transferEnrolment,
} from "@/lib/api";

type Tab = "teaching" | "electives" | "students" | "bells";

const TABS: { key: Tab; label: string }[] = [
  { key: "teaching", label: "Subjects & teaching" },
  { key: "electives", label: "Elective groups" },
  { key: "students", label: "Roll numbers & subjects" },
  { key: "bells", label: "Bell schedules" },
];

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

const emptyPeriod = { periodNo: 1, label: "", startsAt: "08:30", endsAt: "09:15", isBreak: false };

/**
 * Phase 2's screen: the structure every other module resolves against — who
 * studies what, what they are called, how many fit in a room, and when the
 * day's periods run.
 *
 * The four tabs are one shape change seen from four sides, so they share a
 * section selector: a subject is taught to a section, an option block is picked
 * by that section's students, a roll number is unique within it, and the bell
 * schedule is what its periods mean.
 */
export default function AcademicsPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [tab, setTab] = useState<Tab>("teaching");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [years, setYears] = useState<AcademicYearDto[] | null>(null);
  const [yearId, setYearId] = useState("");
  const [grades, setGrades] = useState<GradeDto[] | null>(null);
  const [sections, setSections] = useState<SectionDto[] | null>(null);
  const [sectionId, setSectionId] = useState("");
  const [subjects, setSubjects] = useState<SubjectDto[] | null>(null);
  const [staff, setStaff] = useState<StaffDto[] | null>(null);

  // Subjects & teaching
  const [sectionTeachers, setSectionTeachers] = useState<SectionTeacherDto[] | null>(null);
  const [subjectForm, setSubjectForm] = useState({ code: "", name: "", boardCode: "" });
  const [assignForm, setAssignForm] = useState({
    subjectId: "",
    teacherStaffId: "",
    isPrimary: false,
    isElective: false,
  });

  // Elective groups
  const [groups, setGroups] = useState<ElectiveGroupDto[] | null>(null);
  const [groupGradeId, setGroupGradeId] = useState("");
  const [groupForm, setGroupForm] = useState({
    code: "",
    name: "",
    minPicks: 1,
    maxPicks: 1,
    subjectIds: [] as string[],
  });

  // Roll numbers, capacity, student subject sets
  const [roster, setRoster] = useState<EnrolmentDto[] | null>(null);
  const [students, setStudents] = useState<StudentDto[] | null>(null);
  const [openEnrolmentId, setOpenEnrolmentId] = useState<string | null>(null);
  const [studentSubjects, setStudentSubjects] = useState<StudentSubjectDto[] | null>(null);
  const [elections, setElections] = useState<StudentSubjectDto[] | null>(null);
  const [asOf, setAsOf] = useState(todayIso());
  const [electForm, setElectForm] = useState({ subjectId: "", electiveGroupId: "", effectiveFrom: todayIso() });
  const [transferForm, setTransferForm] = useState({ newSectionId: "", rollNo: "", overCapacityReason: "" });
  const [enrolQuery, setEnrolQuery] = useState("");
  const [enrolCandidates, setEnrolCandidates] = useState<StudentDto[] | null>(null);
  const [enrolForm, setEnrolForm] = useState({
    studentId: "",
    startsOn: todayIso(),
    rollNo: "",
    overCapacityReason: "",
  });

  // Bell schedules
  const [bells, setBells] = useState<BellScheduleDto[] | null>(null);
  const [bellForm, setBellForm] = useState({
    code: "",
    name: "",
    effectiveFrom: todayIso(),
    effectiveTo: "",
    gradeIds: [] as string[],
  });
  const [periods, setPeriods] = useState([{ ...emptyPeriod }]);

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    if (!hasScreen(s, "academics")) {
      router.replace("/dashboard");
      return;
    }
    setSessionState(s);
    Promise.all([
      listAcademicYears(s.schoolId),
      listGrades(s.schoolId),
      listSections(s.schoolId),
      listSubjects(s.schoolId),
      listStaff(s.schoolId),
      listBellSchedules(s.schoolId),
    ])
      .then(([ays, gs, secs, subs, stf, bs]) => {
        setYears(ays);
        setGrades(gs);
        setSections(secs);
        setSubjects(subs);
        setStaff(stf);
        setBells(bs);
        const current = ays.find((y) => y.isCurrent) ?? ays[0];
        if (current) setYearId(current.id);
        if (secs.length > 0) setSectionId(secs[0].id);
        if (gs.length > 0) setGroupGradeId(gs[0].id);
        setAssignForm((f) => ({
          ...f,
          subjectId: subs[0]?.id ?? "",
          teacherStaffId: stf[0]?.id ?? "",
        }));
      })
      .catch((err) => setError(describeError(err)));
  }, [router]);

  const section = sections?.find((s) => s.id === sectionId) ?? null;

  const refreshTeaching = useCallback(() => {
    if (!sectionId) return;
    listSectionTeachers(sectionId).then(setSectionTeachers).catch((err) => setError(describeError(err)));
  }, [sectionId]);

  const refreshRoster = useCallback(() => {
    if (!sectionId) return;
    rosterForSection(sectionId).then(setRoster).catch((err) => setError(describeError(err)));
  }, [sectionId]);

  const refreshGroups = useCallback(() => {
    if (!session || !yearId) return;
    listElectiveGroups(session.schoolId, yearId, groupGradeId || undefined)
      .then(setGroups)
      .catch((err) => setError(describeError(err)));
  }, [session, yearId, groupGradeId]);

  useEffect(() => {
    refreshTeaching();
    refreshRoster();
    setOpenEnrolmentId(null);
  }, [refreshTeaching, refreshRoster]);

  useEffect(() => {
    refreshGroups();
  }, [refreshGroups]);

  useEffect(() => {
    if (!session || !roster || roster.length === 0) {
      setStudents(null);
      return;
    }
    listStudents(session.schoolId, undefined, sectionId)
      .then(setStudents)
      .catch((err) => setError(describeError(err)));
  }, [session, roster, sectionId]);

  useEffect(() => {
    if (!session || enrolQuery.trim().length < 2) {
      setEnrolCandidates(null);
      return;
    }
    listStudents(session.schoolId, enrolQuery)
      .then(setEnrolCandidates)
      .catch((err) => setError(describeError(err)));
  }, [session, enrolQuery]);

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await action();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  function studentName(studentId: string): string {
    const s = students?.find((x) => x.id === studentId);
    return s ? `${s.firstName} ${s.lastName ?? ""}`.trim() : studentId.slice(0, 8);
  }

  function openStudent(enrolment: EnrolmentDto) {
    if (openEnrolmentId === enrolment.id) {
      setOpenEnrolmentId(null);
      return;
    }
    setOpenEnrolmentId(enrolment.id);
    setStudentSubjects(null);
    setElections(null);
    setTransferForm({ newSectionId: "", rollNo: "", overCapacityReason: "" });
  }

  const reloadOpenStudent = useCallback(async () => {
    if (!openEnrolmentId) return;
    const [subs, els] = await Promise.all([
      subjectsForEnrolment(openEnrolmentId, asOf),
      electionsForEnrolment(openEnrolmentId),
    ]);
    setStudentSubjects(subs);
    setElections(els);
  }, [openEnrolmentId, asOf]);

  // The set is resolved as of a date, so moving the date re-resolves it — a
  // mid-year joiner's subjects start on their first day, not today's.
  useEffect(() => {
    reloadOpenStudent().catch((err) => setError(describeError(err)));
  }, [reloadOpenStudent]);

  const occupancy = useMemo(() => {
    if (!section || !roster) return null;
    return { taken: roster.length, capacity: section.capacity };
  }, [section, roster]);

  const groupSubjects = subjects ?? [];

  if (!session) return null;

  return (
    <main className="shell">
      {error && <div className="error-banner">{error}</div>}
      {notice && <div className="notice-banner">{notice}</div>}

      <div className="panel">
        <div className="tabs">
          {TABS.map((t) => (
            <button
              key={t.key}
              type="button"
              className={"tab" + (tab === t.key ? " active" : "")}
              onClick={() => setTab(t.key)}
            >
              {t.label}
            </button>
          ))}
        </div>
        <div className="form-row inline">
          <select value={yearId} onChange={(e) => setYearId(e.target.value)}>
            {years?.map((y) => (
              <option key={y.id} value={y.id}>
                {y.code}
                {y.status === "closed" ? " (closed)" : ""}
              </option>
            ))}
          </select>
          {tab !== "bells" && tab !== "electives" && (
            <select value={sectionId} onChange={(e) => setSectionId(e.target.value)}>
              {sections?.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.gradeName}-{s.code}
                </option>
              ))}
            </select>
          )}
          {occupancy && tab === "students" && (
            <span className="hint">
              {occupancy.taken} enrolled
              {occupancy.capacity != null
                ? ` of ${occupancy.capacity} seats${occupancy.taken > occupancy.capacity ? " — over capacity" : ""}`
                : " · no capacity set"}
            </span>
          )}
        </div>
      </div>

      {/* ------------------------------------------------ subjects & teaching */}
      {tab === "teaching" && (
        <>
          <div className="panel">
            <h2>Subjects</h2>
            <p className="hint">
              The school&apos;s subject catalogue. A section is then taught a subset of it, and a student
              studies the section&apos;s compulsory subjects plus their own elections.
            </p>
            <div className="form-row">
              <input
                placeholder="Code"
                value={subjectForm.code}
                onChange={(e) => setSubjectForm((f) => ({ ...f, code: e.target.value }))}
                style={{ maxWidth: 120 }}
              />
              <input
                placeholder="Name"
                value={subjectForm.name}
                onChange={(e) => setSubjectForm((f) => ({ ...f, name: e.target.value }))}
              />
              <input
                placeholder="Board code (optional)"
                value={subjectForm.boardCode}
                onChange={(e) => setSubjectForm((f) => ({ ...f, boardCode: e.target.value }))}
                style={{ maxWidth: 170 }}
              />
              <button
                type="button"
                disabled={busy || !subjectForm.code || !subjectForm.name}
                onClick={() =>
                  run(async () => {
                    await createSubject(session.schoolId, {
                      code: subjectForm.code,
                      name: subjectForm.name,
                      boardCode: subjectForm.boardCode || undefined,
                    });
                    setSubjectForm({ code: "", name: "", boardCode: "" });
                    setSubjects(await listSubjects(session.schoolId));
                    setNotice("Subject added.");
                  })
                }
              >
                Add subject
              </button>
            </div>
            {subjects && subjects.length > 0 && (
              <p className="hint">{subjects.map((s) => `${s.code} (${s.name})`).join(", ")}</p>
            )}
          </div>

          <div className="panel">
            <h2>Taught in {section ? `${section.gradeName}-${section.code}` : "this section"}</h2>
            <p className="hint">
              Mark a row <strong>elective</strong> when the section is taught it only for the students who
              elected it. Everything else is compulsory for the whole section, and that is the line marks
              entry, the student timetable and the report card all read.
            </p>
            <div className="form-row">
              <select
                value={assignForm.subjectId}
                onChange={(e) => setAssignForm((f) => ({ ...f, subjectId: e.target.value }))}
              >
                {subjects?.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </select>
              <select
                value={assignForm.teacherStaffId}
                onChange={(e) => setAssignForm((f) => ({ ...f, teacherStaffId: e.target.value }))}
              >
                {staff?.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.firstName} {s.lastName ?? ""}
                  </option>
                ))}
              </select>
              <label className="check">
                <input
                  type="checkbox"
                  checked={assignForm.isPrimary}
                  onChange={(e) => setAssignForm((f) => ({ ...f, isPrimary: e.target.checked }))}
                />
                Class teacher
              </label>
              <label className="check">
                <input
                  type="checkbox"
                  checked={assignForm.isElective}
                  onChange={(e) => setAssignForm((f) => ({ ...f, isElective: e.target.checked }))}
                />
                Elective
              </label>
              <button
                type="button"
                disabled={busy || !sectionId || !assignForm.subjectId || !assignForm.teacherStaffId}
                onClick={() =>
                  run(async () => {
                    await assignSectionTeacher(sectionId, assignForm);
                    refreshTeaching();
                    setNotice("Teacher assigned.");
                  })
                }
              >
                Assign
              </button>
            </div>
            <table>
              <thead>
                <tr>
                  <th>Subject</th>
                  <th>Teacher</th>
                  <th>Taken by</th>
                  <th>Class teacher</th>
                </tr>
              </thead>
              <tbody>
                {sectionTeachers?.map((t) => (
                  <tr key={t.id}>
                    <td>{t.subjectName}</td>
                    <td>{t.teacherName}</td>
                    <td>
                      {t.isElective ? (
                        <span className="badge">elective — electors only</span>
                      ) : (
                        <span className="badge badge-active">whole section</span>
                      )}
                    </td>
                    <td>{t.isPrimary ? "Yes" : "—"}</td>
                  </tr>
                ))}
                {sectionTeachers?.length === 0 && (
                  <tr>
                    <td colSpan={4} className="hint">
                      Nothing taught to this section yet.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </>
      )}

      {/* ----------------------------------------------------- elective groups */}
      {tab === "electives" && (
        <>
          <div className="panel">
            <h2>Option blocks</h2>
            <p className="hint">
              A block holds the subjects a grade offers as alternatives, and how many of them a student
              takes. Picking outside the count is refused at election time, not discovered on a report card.
            </p>
            <div className="form-row">
              <select value={groupGradeId} onChange={(e) => setGroupGradeId(e.target.value)}>
                <option value="">All grades</option>
                {grades?.map((g) => (
                  <option key={g.id} value={g.id}>
                    {g.name}
                  </option>
                ))}
              </select>
            </div>
            <table>
              <thead>
                <tr>
                  <th>Block</th>
                  <th>Grade</th>
                  <th>Picks</th>
                  <th>Options</th>
                </tr>
              </thead>
              <tbody>
                {groups?.map((g) => (
                  <tr key={g.id}>
                    <td>
                      {g.code} · {g.name}
                    </td>
                    <td>{grades?.find((x) => x.id === g.gradeId)?.name ?? "—"}</td>
                    <td>
                      {g.minPicks === g.maxPicks ? g.minPicks : `${g.minPicks}–${g.maxPicks}`}
                    </td>
                    <td>{g.options.map((o) => o.subjectName).join(", ") || "—"}</td>
                  </tr>
                ))}
                {groups?.length === 0 && (
                  <tr>
                    <td colSpan={4} className="hint">
                      No option blocks for this year.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          <div className="panel">
            <h2>New option block</h2>
            <div className="form-row">
              <input
                placeholder="Code"
                value={groupForm.code}
                onChange={(e) => setGroupForm((f) => ({ ...f, code: e.target.value }))}
                style={{ maxWidth: 120 }}
              />
              <input
                placeholder="Name"
                value={groupForm.name}
                onChange={(e) => setGroupForm((f) => ({ ...f, name: e.target.value }))}
              />
              <select
                value={groupGradeId}
                onChange={(e) => setGroupGradeId(e.target.value)}
                title="Grade the block belongs to"
              >
                {grades?.map((g) => (
                  <option key={g.id} value={g.id}>
                    {g.name}
                  </option>
                ))}
              </select>
              <input
                type="number"
                min={1}
                value={groupForm.minPicks}
                onChange={(e) => setGroupForm((f) => ({ ...f, minPicks: Number(e.target.value) }))}
                style={{ maxWidth: 90 }}
                title="Minimum picks"
              />
              <input
                type="number"
                min={1}
                value={groupForm.maxPicks}
                onChange={(e) => setGroupForm((f) => ({ ...f, maxPicks: Number(e.target.value) }))}
                style={{ maxWidth: 90 }}
                title="Maximum picks"
              />
            </div>
            <div className="form-row" style={{ gap: 14 }}>
              {groupSubjects.map((s) => (
                <label key={s.id} className="check">
                  <input
                    type="checkbox"
                    checked={groupForm.subjectIds.includes(s.id)}
                    onChange={(e) =>
                      setGroupForm((f) => ({
                        ...f,
                        subjectIds: e.target.checked
                          ? [...f.subjectIds, s.id]
                          : f.subjectIds.filter((x) => x !== s.id),
                      }))
                    }
                  />
                  {s.name}
                </label>
              ))}
            </div>
            <div className="form-row">
              <button
                type="button"
                disabled={
                  busy ||
                  !yearId ||
                  !groupGradeId ||
                  !groupForm.code ||
                  !groupForm.name ||
                  groupForm.subjectIds.length === 0
                }
                onClick={() =>
                  run(async () => {
                    await createElectiveGroup(session.schoolId, {
                      academicYearId: yearId,
                      gradeId: groupGradeId,
                      code: groupForm.code,
                      name: groupForm.name,
                      minPicks: groupForm.minPicks,
                      maxPicks: groupForm.maxPicks,
                      subjectIds: groupForm.subjectIds,
                    });
                    setGroupForm({ code: "", name: "", minPicks: 1, maxPicks: 1, subjectIds: [] });
                    refreshGroups();
                    setNotice("Option block created.");
                  })
                }
              >
                Create block
              </button>
            </div>
          </div>
        </>
      )}

      {/* ------------------------------------- roll numbers, capacity, subjects */}
      {tab === "students" && (
        <div className="panel">
          <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
            <h2>{section ? `${section.gradeName}-${section.code}` : "Section"} roll</h2>
            <button
              type="button"
              className="secondary"
              disabled={busy || !sectionId}
              onClick={() =>
                run(async () => {
                  const rows = await renumberSection(sectionId);
                  setRoster(rows);
                  setNotice(
                    `Renumbered ${rows.length} children from 1 in admission order — tell the class before their books are marked.`
                  );
                })
              }
            >
              Renumber from 1
            </button>
          </div>
          <p className="hint">
            Roll numbers are unique among a section&apos;s active enrolments and issued by the school&apos;s
            number series. Renumbering is explicit because it changes what is written in every child&apos;s
            exercise book.
          </p>
          <div className="form-row">
            <label className="check">
              Subjects as of
              <input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} />
            </label>
          </div>

          <table>
            <thead>
              <tr>
                <th>Roll</th>
                <th>Student</th>
                <th>From</th>
                <th>Status</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {roster?.map((e) => (
                <tr key={e.id} className={openEnrolmentId === e.id ? "row-selected" : undefined}>
                  <td>{e.rollNo ?? "—"}</td>
                  <td>{studentName(e.studentId)}</td>
                  <td>{e.startsOn}</td>
                  <td>
                    <span className={"badge " + (e.status === "active" ? "badge-active" : "")}>{e.status}</span>
                  </td>
                  <td>
                    <button type="button" className="secondary" onClick={() => openStudent(e)}>
                      {openEnrolmentId === e.id ? "Hide" : "Subjects"}
                    </button>
                  </td>
                </tr>
              ))}
              {roster?.length === 0 && (
                <tr>
                  <td colSpan={5} className="hint">
                    Nobody enrolled in this section.
                  </td>
                </tr>
              )}
            </tbody>
          </table>

          <h2 style={{ marginTop: 18 }}>Add a child to this section</h2>
          <p className="hint">
            The section refuses a seat it does not have. Filling in a reason is the deliberate override, and
            it is stored on the enrolment rather than lost in a conversation.
          </p>
          <div className="form-row">
            <input
              placeholder="Search student by name or admission no."
              value={enrolQuery}
              onChange={(e) => setEnrolQuery(e.target.value)}
              style={{ minWidth: 260 }}
            />
            <select
              value={enrolForm.studentId}
              onChange={(e) => setEnrolForm((f) => ({ ...f, studentId: e.target.value }))}
            >
              <option value="">Student…</option>
              {enrolCandidates?.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.admissionNo} · {s.firstName} {s.lastName ?? ""}
                </option>
              ))}
            </select>
            <input
              type="date"
              value={enrolForm.startsOn}
              onChange={(e) => setEnrolForm((f) => ({ ...f, startsOn: e.target.value }))}
            />
            <input
              placeholder="Roll no. (blank = next in series)"
              value={enrolForm.rollNo}
              onChange={(e) => setEnrolForm((f) => ({ ...f, rollNo: e.target.value }))}
              style={{ maxWidth: 220 }}
            />
            <input
              placeholder="Reason, if over capacity"
              value={enrolForm.overCapacityReason}
              onChange={(e) => setEnrolForm((f) => ({ ...f, overCapacityReason: e.target.value }))}
            />
            <button
              type="button"
              disabled={busy || !enrolForm.studentId || !yearId || !sectionId}
              onClick={() =>
                run(async () => {
                  await enrolStudent({
                    schoolId: session.schoolId,
                    studentId: enrolForm.studentId,
                    sectionId,
                    academicYearId: yearId,
                    startsOn: enrolForm.startsOn,
                    rollNo: enrolForm.rollNo || undefined,
                    overCapacityReason: enrolForm.overCapacityReason || undefined,
                  });
                  setEnrolForm({
                    studentId: "",
                    startsOn: todayIso(),
                    rollNo: "",
                    overCapacityReason: "",
                  });
                  setEnrolQuery("");
                  refreshRoster();
                  setNotice("Enrolled.");
                })
              }
            >
              Enrol
            </button>
          </div>

          {openEnrolmentId && (
            <div style={{ marginTop: 16 }}>
              <h2>What this child studies</h2>
              <p className="hint">
                The resolved set on {asOf}: the section&apos;s compulsory subjects plus their own elections.
                An election is effective-dated, so ending one leaves the marks earned under it alone.
              </p>
              <table>
                <thead>
                  <tr>
                    <th>Subject</th>
                    <th>Origin</th>
                    <th>Block</th>
                    <th>From</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {studentSubjects?.map((s) => {
                    const election = elections?.find((e) => e.subjectId === s.subjectId && !e.effectiveTo);
                    return (
                      <tr key={s.id}>
                        <td>
                          {s.subjectCode} · {s.subjectName}
                        </td>
                        <td>
                          <span className={"badge " + (s.origin === "elective" ? "" : "badge-active")}>
                            {s.origin}
                          </span>
                        </td>
                        <td>{s.electiveGroupCode ?? "—"}</td>
                        <td>{s.effectiveFrom ?? "—"}</td>
                        <td>
                          {election && (
                            <button
                              type="button"
                              className="secondary"
                              disabled={busy}
                              onClick={() =>
                                run(async () => {
                                  await dropElection(openEnrolmentId, {
                                    subjectId: s.subjectId,
                                    effectiveTo: asOf,
                                  });
                                  await reloadOpenStudent();
                                  setNotice(
                                    `${s.subjectName} ends on ${asOf}. The marks earned under it stay.`
                                  );
                                })
                              }
                            >
                              End election
                            </button>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                  {studentSubjects?.length === 0 && (
                    <tr>
                      <td colSpan={5} className="hint">
                        No subjects resolve on this date — check the enrolment covers it.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>

              <div className="form-row" style={{ marginTop: 10 }}>
                <select
                  value={electForm.subjectId}
                  onChange={(e) => setElectForm((f) => ({ ...f, subjectId: e.target.value }))}
                >
                  <option value="">Elect a subject…</option>
                  {subjects?.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
                    </option>
                  ))}
                </select>
                <select
                  value={electForm.electiveGroupId}
                  onChange={(e) => setElectForm((f) => ({ ...f, electiveGroupId: e.target.value }))}
                >
                  <option value="">No block</option>
                  {groups?.map((g) => (
                    <option key={g.id} value={g.id}>
                      {g.code}
                    </option>
                  ))}
                </select>
                <input
                  type="date"
                  value={electForm.effectiveFrom}
                  onChange={(e) => setElectForm((f) => ({ ...f, effectiveFrom: e.target.value }))}
                />
                <button
                  type="button"
                  disabled={busy || !electForm.subjectId}
                  onClick={() =>
                    run(async () => {
                      await electSubject(openEnrolmentId, {
                        subjectId: electForm.subjectId,
                        electiveGroupId: electForm.electiveGroupId || undefined,
                        effectiveFrom: electForm.effectiveFrom,
                      });
                      setElectForm((f) => ({ ...f, subjectId: "", electiveGroupId: "" }));
                      await reloadOpenStudent();
                      setNotice("Election recorded.");
                    })
                  }
                >
                  Elect
                </button>
              </div>

              <h2 style={{ marginTop: 18 }}>Move to another section</h2>
              <p className="hint">
                A full section refuses the seat unless the transfer carries a reason, which is stored on the
                enrolment.
              </p>
              <div className="form-row">
                <select
                  value={transferForm.newSectionId}
                  onChange={(e) => setTransferForm((f) => ({ ...f, newSectionId: e.target.value }))}
                >
                  <option value="">Section…</option>
                  {sections
                    ?.filter((s) => s.id !== sectionId)
                    .map((s) => (
                      <option key={s.id} value={s.id}>
                        {s.gradeName}-{s.code}
                      </option>
                    ))}
                </select>
                <input
                  placeholder="Roll no. (blank = next in series)"
                  value={transferForm.rollNo}
                  onChange={(e) => setTransferForm((f) => ({ ...f, rollNo: e.target.value }))}
                  style={{ maxWidth: 220 }}
                />
                <input
                  placeholder="Reason, if over capacity"
                  value={transferForm.overCapacityReason}
                  onChange={(e) => setTransferForm((f) => ({ ...f, overCapacityReason: e.target.value }))}
                />
                <button
                  type="button"
                  disabled={busy || !transferForm.newSectionId}
                  onClick={() =>
                    run(async () => {
                      await transferEnrolment(openEnrolmentId, {
                        newSectionId: transferForm.newSectionId,
                        rollNo: transferForm.rollNo || undefined,
                        overCapacityReason: transferForm.overCapacityReason || undefined,
                      });
                      setOpenEnrolmentId(null);
                      refreshRoster();
                      setNotice("Moved. The old section's roll numbers are unchanged.");
                    })
                  }
                >
                  Move
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* ------------------------------------------------------ bell schedules */}
      {tab === "bells" && (
        <>
          <div className="panel">
            <h2>Bell schedules</h2>
            <p className="hint">
              A timetable slot points at a period rather than repeating its times, so moving the bell moves
              every lesson that hangs off it. A break refuses to hold a lesson.
            </p>
            {bells?.map((b) => (
              <div key={b.id} style={{ marginBottom: 14 }}>
                <strong>
                  {b.code} · {b.name}
                </strong>{" "}
                <span className="hint">
                  from {b.effectiveFrom}
                  {b.effectiveTo ? ` to ${b.effectiveTo}` : ""} ·{" "}
                  {b.gradeIds.length > 0
                    ? b.gradeIds
                        .map((id) => grades?.find((g) => g.id === id)?.name ?? "?")
                        .join(", ")
                    : "no grade bound to it yet"}
                </span>
                <table>
                  <thead>
                    <tr>
                      <th>#</th>
                      <th>Label</th>
                      <th>Runs</th>
                      <th>Kind</th>
                    </tr>
                  </thead>
                  <tbody>
                    {b.periods.map((p) => (
                      <tr key={p.id}>
                        <td>{p.periodNo}</td>
                        <td>{p.label}</td>
                        <td>
                          {p.startsAt.slice(0, 5)}–{p.endsAt.slice(0, 5)}
                        </td>
                        <td>{p.isBreak ? "Break" : "Lesson"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ))}
            {bells?.length === 0 && <p className="hint">No bell schedule yet — slots carry their own times.</p>}
          </div>

          <div className="panel">
            <h2>New bell schedule</h2>
            <div className="form-row">
              <input
                placeholder="Code"
                value={bellForm.code}
                onChange={(e) => setBellForm((f) => ({ ...f, code: e.target.value }))}
                style={{ maxWidth: 120 }}
              />
              <input
                placeholder="Name"
                value={bellForm.name}
                onChange={(e) => setBellForm((f) => ({ ...f, name: e.target.value }))}
              />
              <input
                type="date"
                value={bellForm.effectiveFrom}
                onChange={(e) => setBellForm((f) => ({ ...f, effectiveFrom: e.target.value }))}
              />
              <input
                type="date"
                value={bellForm.effectiveTo}
                onChange={(e) => setBellForm((f) => ({ ...f, effectiveTo: e.target.value }))}
                title="Effective to (optional)"
              />
            </div>
            <div className="form-row" style={{ gap: 14 }}>
              {grades?.map((g) => (
                <label key={g.id} className="check">
                  <input
                    type="checkbox"
                    checked={bellForm.gradeIds.includes(g.id)}
                    onChange={(e) =>
                      setBellForm((f) => ({
                        ...f,
                        gradeIds: e.target.checked
                          ? [...f.gradeIds, g.id]
                          : f.gradeIds.filter((x) => x !== g.id),
                      }))
                    }
                  />
                  {g.name}
                </label>
              ))}
            </div>
            {periods.map((p, i) => (
              <div className="form-row" key={i}>
                <input
                  type="number"
                  min={1}
                  value={p.periodNo}
                  onChange={(e) =>
                    setPeriods((ps) =>
                      ps.map((x, idx) => (idx === i ? { ...x, periodNo: Number(e.target.value) } : x))
                    )
                  }
                  style={{ maxWidth: 80 }}
                  title="Period number"
                />
                <input
                  placeholder="Label (e.g. Period 1, Lunch)"
                  value={p.label}
                  onChange={(e) =>
                    setPeriods((ps) => ps.map((x, idx) => (idx === i ? { ...x, label: e.target.value } : x)))
                  }
                />
                <input
                  type="time"
                  value={p.startsAt}
                  onChange={(e) =>
                    setPeriods((ps) => ps.map((x, idx) => (idx === i ? { ...x, startsAt: e.target.value } : x)))
                  }
                />
                <input
                  type="time"
                  value={p.endsAt}
                  onChange={(e) =>
                    setPeriods((ps) => ps.map((x, idx) => (idx === i ? { ...x, endsAt: e.target.value } : x)))
                  }
                />
                <label className="check">
                  <input
                    type="checkbox"
                    checked={p.isBreak}
                    onChange={(e) =>
                      setPeriods((ps) =>
                        ps.map((x, idx) => (idx === i ? { ...x, isBreak: e.target.checked } : x))
                      )
                    }
                  />
                  Break
                </label>
                <button
                  type="button"
                  className="secondary"
                  onClick={() => setPeriods((ps) => ps.filter((_, idx) => idx !== i))}
                  disabled={periods.length === 1}
                >
                  Remove
                </button>
              </div>
            ))}
            <div className="form-row">
              <button
                type="button"
                className="secondary"
                onClick={() =>
                  setPeriods((ps) => [
                    ...ps,
                    { ...emptyPeriod, periodNo: (ps[ps.length - 1]?.periodNo ?? 0) + 1 },
                  ])
                }
              >
                + Add period
              </button>
              <button
                type="button"
                disabled={busy || !bellForm.code || !bellForm.name || periods.some((p) => !p.label)}
                onClick={() =>
                  run(async () => {
                    await createBellSchedule({
                      schoolId: session.schoolId,
                      code: bellForm.code,
                      name: bellForm.name,
                      effectiveFrom: bellForm.effectiveFrom,
                      effectiveTo: bellForm.effectiveTo || undefined,
                      periods,
                      gradeIds: bellForm.gradeIds,
                    });
                    setBellForm({
                      code: "",
                      name: "",
                      effectiveFrom: todayIso(),
                      effectiveTo: "",
                      gradeIds: [],
                    });
                    setPeriods([{ ...emptyPeriod }]);
                    setBells(await listBellSchedules(session.schoolId));
                    setNotice("Bell schedule created.");
                  })
                }
              >
                Create schedule
              </button>
              <span className="hint">Overlapping periods are refused — a day where period 3 starts before period 2 ends is a typo.</span>
            </div>
          </div>
        </>
      )}
    </main>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}
