"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  AcademicYearDto,
  addExamSession,
  ApiError,
  createExamSchedule,
  deleteExamSession,
  ExamClashDto,
  examClashes,
  ExamScheduleDto,
  ExamSessionDto,
  getSession,
  GradeDto,
  HallTicketDto,
  hasScreen,
  issueHallTickets,
  listAcademicYears,
  listExamSchedules,
  listExamSessions,
  listGrades,
  listHallTickets,
  listStaff,
  listSubjects,
  publishExamSchedule,
  Session,
  StaffDto,
  SubjectDto,
  TermDto,
  listTerms,
  unpublishExamSchedule,
} from "@/lib/api";

const emptyScheduleForm = { code: "", name: "", termId: "", startsOn: "", endsOn: "" };
const emptySessionForm = {
  gradeId: "",
  subjectId: "",
  paperCode: "P1",
  name: "",
  onDate: "",
  startsAt: "09:30",
  endsAt: "11:30",
  room: "",
  invigilatorStaffId: "",
  maxMarks: "",
};

/**
 * Phase 5's exam operations.
 *
 * The clash list is the point of the screen. A grade's papers can look clean on
 * a section timetable while one candidate with a different option block is
 * booked into two rooms at the same hour — so publication is blocked until the
 * per-student check comes back empty, and from publication onwards this is what
 * the class timetable shows on those dates.
 */
export default function ExamsPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [years, setYears] = useState<AcademicYearDto[] | null>(null);
  const [yearId, setYearId] = useState("");
  const [terms, setTerms] = useState<TermDto[] | null>(null);
  const [grades, setGrades] = useState<GradeDto[] | null>(null);
  const [subjects, setSubjects] = useState<SubjectDto[] | null>(null);
  const [staff, setStaff] = useState<StaffDto[] | null>(null);

  const [schedules, setSchedules] = useState<ExamScheduleDto[] | null>(null);
  const [selectedId, setSelectedId] = useState("");
  const [sessions, setSessions] = useState<ExamSessionDto[] | null>(null);
  const [clashes, setClashes] = useState<ExamClashDto[] | null>(null);
  const [tickets, setTickets] = useState<HallTicketDto[] | null>(null);

  const [scheduleForm, setScheduleForm] = useState(emptyScheduleForm);
  const [sessionForm, setSessionForm] = useState(emptySessionForm);

  const selected = schedules?.find((s) => s.id === selectedId) ?? null;

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    if (!hasScreen(s, "exams")) {
      router.replace("/dashboard");
      return;
    }
    setSessionState(s);
    Promise.all([listAcademicYears(s.schoolId), listGrades(s.schoolId), listSubjects(s.schoolId), listStaff(s.schoolId)])
      .then(([ays, gs, subs, st]) => {
        setYears(ays);
        setGrades(gs);
        setSubjects(subs);
        setStaff(st);
        const current = ays.find((y) => y.isCurrent) ?? ays[0];
        if (current) setYearId(current.id);
        setSessionForm((f) => ({ ...f, gradeId: gs[0]?.id ?? "", subjectId: subs[0]?.id ?? "" }));
      })
      .catch((err) => setError(describeError(err)));
  }, [router]);

  const refreshSchedules = useCallback(() => {
    if (!session || !yearId) return;
    listExamSchedules(session.schoolId, yearId)
      .then((list) => {
        setSchedules(list);
        setSelectedId((current) => (list.some((s) => s.id === current) ? current : list[0]?.id ?? ""));
      })
      .catch((err) => setError(describeError(err)));
  }, [session, yearId]);

  useEffect(() => {
    refreshSchedules();
    if (yearId) listTerms(yearId).then(setTerms).catch(() => setTerms([]));
  }, [refreshSchedules, yearId]);

  const refreshSelected = useCallback(() => {
    if (!selectedId) {
      setSessions(null);
      setClashes(null);
      setTickets(null);
      return;
    }
    Promise.all([listExamSessions(selectedId), examClashes(selectedId), listHallTickets(selectedId)])
      .then(([ss, cl, ts]) => {
        setSessions(ss);
        setClashes(cl.clashes);
        setTickets(ts);
      })
      .catch((err) => setError(describeError(err)));
  }, [selectedId]);

  useEffect(() => {
    refreshSelected();
  }, [refreshSelected]);

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

  if (!session) return null;

  return (
    <main className="shell">
      {error && <div className="error-banner">{error}</div>}
      {notice && <div className="notice-banner">{notice}</div>}

      {/* -------------------------------------------------------- schedules */}
      <div className="panel">
        <h2>Exam schedules</h2>
        <div className="form-row">
          <select value={yearId} onChange={(e) => setYearId(e.target.value)}>
            {years?.map((y) => (
              <option key={y.id} value={y.id}>
                {y.code}
                {y.isCurrent ? " (current)" : ""}
              </option>
            ))}
          </select>
        </div>

        <table>
          <thead>
            <tr>
              <th>Code</th>
              <th>Name</th>
              <th>Window</th>
              <th>Papers</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {schedules?.map((s) => (
              <tr
                key={s.id}
                role="button"
                onClick={() => setSelectedId(s.id)}
                className={s.id === selectedId ? "row-selected" : undefined}
              >
                <td>{s.code}</td>
                <td>{s.name}</td>
                <td>
                  {s.startsOn} → {s.endsOn}
                </td>
                <td>{s.sessionCount}</td>
                <td>
                  <span className={"badge " + (s.status === "published" ? "badge-active" : "")}>{s.status}</span>
                </td>
              </tr>
            ))}
            {schedules?.length === 0 && (
              <tr>
                <td colSpan={5} className="hint">
                  No exam weeks in this year yet.
                </td>
              </tr>
            )}
          </tbody>
        </table>

        <div className="form-row" style={{ marginTop: 14 }}>
          <input
            placeholder="Code (HY-2026)"
            value={scheduleForm.code}
            onChange={(e) => setScheduleForm({ ...scheduleForm, code: e.target.value })}
          />
          <input
            placeholder="Name"
            value={scheduleForm.name}
            onChange={(e) => setScheduleForm({ ...scheduleForm, name: e.target.value })}
          />
          <select value={scheduleForm.termId} onChange={(e) => setScheduleForm({ ...scheduleForm, termId: e.target.value })}>
            <option value="">No term</option>
            {terms?.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
              </option>
            ))}
          </select>
          <input
            type="date"
            value={scheduleForm.startsOn}
            onChange={(e) => setScheduleForm({ ...scheduleForm, startsOn: e.target.value })}
          />
          <input
            type="date"
            value={scheduleForm.endsOn}
            onChange={(e) => setScheduleForm({ ...scheduleForm, endsOn: e.target.value })}
          />
          <button
            type="button"
            disabled={busy || !scheduleForm.code || !scheduleForm.startsOn || !scheduleForm.endsOn}
            onClick={() =>
              run(async () => {
                const created = await createExamSchedule({
                  schoolId: session.schoolId,
                  academicYearId: yearId,
                  termId: scheduleForm.termId || undefined,
                  code: scheduleForm.code,
                  name: scheduleForm.name || scheduleForm.code,
                  startsOn: scheduleForm.startsOn,
                  endsOn: scheduleForm.endsOn,
                });
                setScheduleForm(emptyScheduleForm);
                setSessionForm((f) => ({ ...f, onDate: created.startsOn }));
                refreshSchedules();
                setSelectedId(created.id);
              })
            }
          >
            Add exam week
          </button>
        </div>
      </div>

      {selected && (
        <>
          {/* ---------------------------------------------------- clash gate */}
          <div className="panel">
            <h2>
              {selected.code} — papers{" "}
              <span className={"badge " + (selected.status === "published" ? "badge-active" : "")}>
                {selected.status}
              </span>
            </h2>

            {clashes && clashes.length > 0 && (
              <div className="warn-banner">
                {clashes.length} student paper clash(es). Publication is blocked until every one is cleared —
                move a paper to another slot or day.
                <ul className="rejected-list">
                  {clashes.slice(0, 6).map((c, i) => (
                    <li key={i}>
                      {c.subjectA} and {c.subjectB} on {c.onDate} — student {c.studentId.slice(0, 8)}
                    </li>
                  ))}
                  {clashes.length > 6 && <li>…and {clashes.length - 6} more.</li>}
                </ul>
              </div>
            )}
            {clashes && clashes.length === 0 && sessions && sessions.length > 0 && (
              <p className="hint">No student sits two of these papers at once.</p>
            )}

            <table>
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Time</th>
                  <th>Grade</th>
                  <th>Paper</th>
                  <th>Room</th>
                  <th>Invigilator</th>
                  <th>Max</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {sessions?.map((s) => (
                  <tr key={s.id}>
                    <td>{s.onDate}</td>
                    <td>
                      {s.startsAt.slice(0, 5)}–{s.endsAt.slice(0, 5)}
                    </td>
                    <td>{gradeLabel(grades, s.gradeId)}</td>
                    <td>
                      {s.subjectCode} · {s.name} ({s.paperCode})
                    </td>
                    <td>{s.room ?? "—"}</td>
                    <td>{staffLabel(staff, s.invigilatorStaffId)}</td>
                    <td>{s.maxMarks ?? "—"}</td>
                    <td>
                      <button
                        type="button"
                        className="secondary"
                        disabled={busy || selected.status === "published"}
                        onClick={() =>
                          run(async () => {
                            await deleteExamSession(s.id);
                            refreshSelected();
                            refreshSchedules();
                          })
                        }
                      >
                        Remove
                      </button>
                    </td>
                  </tr>
                ))}
                {sessions?.length === 0 && (
                  <tr>
                    <td colSpan={8} className="hint">
                      No papers yet.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>

            {selected.status !== "published" && (
              <div className="form-row" style={{ marginTop: 14 }}>
                <select
                  value={sessionForm.gradeId}
                  onChange={(e) => setSessionForm({ ...sessionForm, gradeId: e.target.value })}
                >
                  {grades?.map((g) => (
                    <option key={g.id} value={g.id}>
                      Grade {g.code}
                    </option>
                  ))}
                </select>
                <select
                  value={sessionForm.subjectId}
                  onChange={(e) => setSessionForm({ ...sessionForm, subjectId: e.target.value })}
                >
                  {subjects?.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.code} — {s.name}
                    </option>
                  ))}
                </select>
                <input
                  placeholder="Paper name"
                  value={sessionForm.name}
                  onChange={(e) => setSessionForm({ ...sessionForm, name: e.target.value })}
                />
                <input
                  type="date"
                  value={sessionForm.onDate || selected.startsOn}
                  onChange={(e) => setSessionForm({ ...sessionForm, onDate: e.target.value })}
                />
                <input
                  type="time"
                  value={sessionForm.startsAt}
                  onChange={(e) => setSessionForm({ ...sessionForm, startsAt: e.target.value })}
                />
                <input
                  type="time"
                  value={sessionForm.endsAt}
                  onChange={(e) => setSessionForm({ ...sessionForm, endsAt: e.target.value })}
                />
                <input
                  placeholder="Room"
                  value={sessionForm.room}
                  onChange={(e) => setSessionForm({ ...sessionForm, room: e.target.value })}
                />
                <select
                  value={sessionForm.invigilatorStaffId}
                  onChange={(e) => setSessionForm({ ...sessionForm, invigilatorStaffId: e.target.value })}
                >
                  <option value="">No invigilator</option>
                  {staff?.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.firstName} {s.lastName ?? ""}
                    </option>
                  ))}
                </select>
                <input
                  placeholder="Max marks"
                  value={sessionForm.maxMarks}
                  onChange={(e) => setSessionForm({ ...sessionForm, maxMarks: e.target.value })}
                />
                <button
                  type="button"
                  disabled={busy || !sessionForm.name || !sessionForm.gradeId || !sessionForm.subjectId}
                  onClick={() =>
                    run(async () => {
                      await addExamSession(selected.id, {
                        gradeId: sessionForm.gradeId,
                        subjectId: sessionForm.subjectId,
                        paperCode: sessionForm.paperCode || "P1",
                        name: sessionForm.name,
                        onDate: sessionForm.onDate || selected.startsOn,
                        startsAt: `${sessionForm.startsAt}:00`,
                        endsAt: `${sessionForm.endsAt}:00`,
                        room: sessionForm.room || undefined,
                        invigilatorStaffId: sessionForm.invigilatorStaffId || undefined,
                        maxMarks: sessionForm.maxMarks ? Number(sessionForm.maxMarks) : undefined,
                      });
                      setSessionForm({ ...sessionForm, name: "", room: "" });
                      refreshSelected();
                      refreshSchedules();
                    })
                  }
                >
                  Add paper
                </button>
              </div>
            )}

            <div className="form-row" style={{ marginTop: 8 }}>
              {selected.status !== "published" ? (
                <button
                  type="button"
                  disabled={busy || !sessions || sessions.length === 0}
                  onClick={() =>
                    run(async () => {
                      await publishExamSchedule(selected.id);
                      setNotice(
                        `${selected.code} published. Its dates now show the exam timetable instead of the ` +
                          `regular one, and hall tickets can be issued.`
                      );
                      refreshSchedules();
                      refreshSelected();
                    })
                  }
                >
                  Publish
                </button>
              ) : (
                <button
                  type="button"
                  className="secondary"
                  disabled={busy}
                  onClick={() =>
                    run(async () => {
                      await unpublishExamSchedule(selected.id);
                      setNotice(`${selected.code} back to draft — the regular timetable applies again.`);
                      refreshSchedules();
                      refreshSelected();
                    })
                  }
                >
                  Unpublish
                </button>
              )}
              <button
                type="button"
                className="secondary"
                disabled={busy || selected.status !== "published"}
                onClick={() =>
                  run(async () => {
                    const issued = await issueHallTickets(selected.id);
                    setNotice(`${issued.length} hall ticket(s) issued. Re-issuing keeps the numbers already given out.`);
                    refreshSelected();
                  })
                }
              >
                Issue hall tickets
              </button>
            </div>
          </div>

          {/* ------------------------------------------------- hall tickets */}
          {tickets && tickets.length > 0 && (
            <div className="panel">
              <h2>Hall tickets</h2>
              <p className="hint">
                Each ticket lists the papers that candidate sits, resolved from their own subject set — two
                students in one section with different options carry different tickets.
              </p>
              <table>
                <thead>
                  <tr>
                    <th>Ticket</th>
                    <th>Seat</th>
                    <th>Candidate</th>
                    <th>Admission no.</th>
                    <th>Papers</th>
                  </tr>
                </thead>
                <tbody>
                  {tickets.map((t) => (
                    <tr key={t.id}>
                      <td>{t.ticketNo}</td>
                      <td>{t.seatNo ?? "—"}</td>
                      <td>{t.studentName}</td>
                      <td>{t.admissionNo}</td>
                      <td style={{ whiteSpace: "normal" }}>
                        {t.sessions.map((s) => `${s.subjectCode} ${s.onDate} ${s.startsAt.slice(0, 5)}`).join(" · ")}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </main>
  );
}

function gradeLabel(grades: GradeDto[] | null, gradeId: string): string {
  const grade = grades?.find((g) => g.id === gradeId);
  return grade ? `Grade ${grade.code}` : "—";
}

function staffLabel(staff: StaffDto[] | null, staffId: string | null): string {
  if (!staffId) return "—";
  const member = staff?.find((s) => s.id === staffId);
  return member ? `${member.firstName} ${member.lastName ?? ""}`.trim() : "—";
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}
