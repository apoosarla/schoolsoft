"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  BellScheduleDto,
  bellScheduleForSection,
  createTimetableSlot,
  deleteTimetableSlot,
  getSession,
  hasScreen,
  listSections,
  listStaff,
  listSubjects,
  SectionDayDto,
  sectionDay,
  SectionDto,
  Session,
  StaffDto,
  SubjectDto,
  timetableForSection,
  timetablePublishWarnings,
  TimetableSlotDto,
} from "@/lib/api";

const DAY_NAMES = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

const emptyForm = {
  subjectId: "",
  teacherStaffId: "",
  dayOfWeek: "1",
  periodId: "",
  periodNo: "1",
  startsAt: "09:00",
  endsAt: "09:45",
  room: "",
  effectiveFrom: todayIso(),
  effectiveTo: "",
};

export default function TimetablePage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [sections, setSections] = useState<SectionDto[] | null>(null);
  const [subjects, setSubjects] = useState<SubjectDto[] | null>(null);
  const [staff, setStaff] = useState<StaffDto[] | null>(null);
  const [sectionId, setSectionId] = useState("");
  const [slots, setSlots] = useState<TimetableSlotDto[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState(emptyForm);
  const [creating, setCreating] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [dayDate, setDayDate] = useState(todayIso());
  const [day, setDay] = useState<SectionDayDto | null>(null);
  const [bell, setBell] = useState<BellScheduleDto | null>(null);
  const [warnings, setWarnings] = useState<string[] | null>(null);

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    if (!hasScreen(s, "timetable")) {
      router.replace("/dashboard");
      return;
    }
    setSessionState(s);
    Promise.all([listSections(s.schoolId), listSubjects(s.schoolId), listStaff(s.schoolId)])
      .then(([secs, subs, stf]) => {
        setSections(secs);
        setSubjects(subs);
        setStaff(stf);
        if (secs.length > 0) setSectionId(secs[0].id);
        setForm((f) => ({ ...f, subjectId: subs[0]?.id ?? "", teacherStaffId: stf[0]?.id ?? "" }));
      })
      .catch((err) => setError(describeError(err)));
  }, [router]);

  function refresh(id: string) {
    setLoading(true);
    setError(null);
    timetableForSection(id)
      .then(setSlots)
      .catch((err) => setError(describeError(err)))
      .finally(() => setLoading(false));
    // Room clashes and teachers over their weekly load: advisory at publish
    // time, so they are shown beside the grid rather than blocking an edit.
    timetablePublishWarnings(id)
      .then((w) => setWarnings(w.warnings))
      .catch(() => setWarnings(null));
  }

  useEffect(() => {
    if (!sectionId) return;
    refresh(sectionId);
  }, [sectionId]);

  useEffect(() => {
    if (!sectionId) return;
    setDay(null);
    sectionDay(sectionId, dayDate)
      .then(setDay)
      .catch((err) => setError(describeError(err)));
  }, [sectionId, dayDate]);

  // The section's bell schedule, through its grade. When it has one, a slot
  // names a period instead of carrying its own times, so moving the bell moves
  // every lesson hanging off it.
  useEffect(() => {
    if (!sectionId) return;
    setBell(null);
    bellScheduleForSection(sectionId)
      .then((b) => {
        setBell(b ?? null);
        setForm((f) => ({ ...f, periodId: "" }));
      })
      .catch(() => setBell(null));
  }, [sectionId]);

  const teachingPeriods = bell?.periods.filter((p) => !p.isBreak) ?? [];

  async function onCreate() {
    setCreating(true);
    setError(null);
    try {
      const period = teachingPeriods.find((p) => p.id === form.periodId);
      await createTimetableSlot({
        sectionId,
        subjectId: form.subjectId,
        teacherStaffId: form.teacherStaffId,
        dayOfWeek: Number(form.dayOfWeek),
        periodNo: period ? period.periodNo : Number(form.periodNo),
        periodId: period?.id,
        startsAt: period ? undefined : form.startsAt,
        endsAt: period ? undefined : form.endsAt,
        room: form.room || undefined,
        effectiveFrom: form.effectiveFrom,
        effectiveTo: form.effectiveTo || undefined,
      });
      setShowForm(false);
      refresh(sectionId);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setCreating(false);
    }
  }

  async function onDelete(id: string) {
    setDeletingId(id);
    setError(null);
    try {
      await deleteTimetableSlot(id);
      refresh(sectionId);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setDeletingId(null);
    }
  }

  if (!session) return null;

  return (
    <main className="shell">
      <div className="panel">
        <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
          <h2>Timetable</h2>
          <button type="button" onClick={() => setShowForm((v) => !v)} disabled={!subjects || subjects.length === 0}>
            {showForm ? "Cancel" : "Add slot"}
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

        {showForm && (
          <div className="form-row" style={{ flexWrap: "wrap" }}>
            <select value={form.subjectId} onChange={(e) => setForm((f) => ({ ...f, subjectId: e.target.value }))}>
              {subjects?.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
            <select
              value={form.teacherStaffId}
              onChange={(e) => setForm((f) => ({ ...f, teacherStaffId: e.target.value }))}
            >
              {staff?.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.firstName} {s.lastName ?? ""}
                </option>
              ))}
            </select>
            <select value={form.dayOfWeek} onChange={(e) => setForm((f) => ({ ...f, dayOfWeek: e.target.value }))}>
              {DAY_NAMES.map((d, i) => (
                <option key={i} value={i}>
                  {d}
                </option>
              ))}
            </select>
            {teachingPeriods.length > 0 ? (
              <select value={form.periodId} onChange={(e) => setForm((f) => ({ ...f, periodId: e.target.value }))}>
                <option value="">Own times…</option>
                {teachingPeriods.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.label} ({p.startsAt.slice(0, 5)}–{p.endsAt.slice(0, 5)})
                  </option>
                ))}
              </select>
            ) : (
              <input
                type="number"
                min={1}
                placeholder="Period"
                value={form.periodNo}
                onChange={(e) => setForm((f) => ({ ...f, periodNo: e.target.value }))}
                style={{ maxWidth: 80 }}
              />
            )}
            {!form.periodId && (
              <>
                <input
                  type="time"
                  value={form.startsAt}
                  onChange={(e) => setForm((f) => ({ ...f, startsAt: e.target.value }))}
                />
                <input
                  type="time"
                  value={form.endsAt}
                  onChange={(e) => setForm((f) => ({ ...f, endsAt: e.target.value }))}
                />
              </>
            )}
            <input
              placeholder="Room"
              value={form.room}
              onChange={(e) => setForm((f) => ({ ...f, room: e.target.value }))}
              style={{ maxWidth: 100 }}
            />
            <input
              type="date"
              value={form.effectiveFrom}
              onChange={(e) => setForm((f) => ({ ...f, effectiveFrom: e.target.value }))}
            />
            <button
              type="button"
              onClick={onCreate}
              disabled={creating || !form.subjectId || !form.teacherStaffId || !sectionId}
            >
              {creating ? "Adding…" : "Add slot"}
            </button>
          </div>
        )}

        {bell && (
          <p className="hint">
            Periods come from bell schedule <strong>{bell.name}</strong>, so moving a bell moves every lesson
            on it. Breaks are not offered — one cannot hold a lesson.
          </p>
        )}

        {loading && <p className="hint">Loading…</p>}
        {error && <div className="error-banner">{error}</div>}
        {warnings && warnings.length > 0 && (
          <div className="warn-banner">
            <strong>Publish warnings</strong>
            <ul className="rejected-list" style={{ color: "inherit" }}>
              {warnings.map((w, i) => (
                <li key={i}>{w}</li>
              ))}
            </ul>
          </div>
        )}
        {slots && slots.length === 0 && <p className="hint">No timetable slots for this section yet.</p>}

        {slots && slots.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Day</th>
                <th>Period</th>
                <th>Time</th>
                <th>Subject</th>
                <th>Teacher</th>
                <th>Room</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {slots
                .slice()
                .sort((a, b) => a.dayOfWeek - b.dayOfWeek || a.periodNo - b.periodNo)
                .map((slot) => {
                  const teacher = staff?.find((s) => s.id === slot.teacherStaffId);
                  return (
                    <tr key={slot.id}>
                      <td>{DAY_NAMES[slot.dayOfWeek]}</td>
                      <td>{slot.periodNo}</td>
                      <td>
                        {slot.startsAt}–{slot.endsAt}
                      </td>
                      <td>{slot.subjectName}</td>
                      <td>{teacher ? `${teacher.firstName} ${teacher.lastName ?? ""}` : "—"}</td>
                      <td>{slot.room ?? "—"}</td>
                      <td>
                        <button type="button" onClick={() => onDelete(slot.id)} disabled={deletingId === slot.id}>
                          {deletingId === slot.id ? "…" : "Delete"}
                        </button>
                      </td>
                    </tr>
                  );
                })}
            </tbody>
          </table>
        )}
      </div>

      {/* ------------------------------------------------------ one date */}
      <div className="panel">
        <h2>What happens on a date</h2>
        <p className="hint">
          The week above is the plan. This is the day itself: the school calendar decides whether there is
          school at all, an exam week replaces the periods, and cover names who is actually taking the class.
        </p>
        <div className="form-row">
          <input type="date" value={dayDate} onChange={(e) => setDayDate(e.target.value)} />
        </div>

        {day && !day.working && (
          <p className="hint">School closed — {day.reason ?? "not a working day"}.</p>
        )}

        {day?.working && day.examDay && (
          <>
            <div className="warn-banner">
              Exam day: the regular timetable is suspended and these papers run instead.
            </div>
            <table>
              <thead>
                <tr>
                  <th>Time</th>
                  <th>Paper</th>
                  <th>Room</th>
                </tr>
              </thead>
              <tbody>
                {day.examSessions.map((s) => (
                  <tr key={s.id}>
                    <td>
                      {s.startsAt.slice(0, 5)}–{s.endsAt.slice(0, 5)}
                    </td>
                    <td>
                      {s.subjectCode} · {s.name}
                    </td>
                    <td>{s.room ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}

        {day?.working && !day.examDay && (
          <table>
            <thead>
              <tr>
                <th>Period</th>
                <th>Time</th>
                <th>Subject</th>
                <th>Taken by</th>
                <th>Room</th>
              </tr>
            </thead>
            <tbody>
              {day.slots.map((slot) => {
                const cover = day.covers.find((c) => c.slotId === slot.id && !c.cancelled);
                const teacher = staff?.find((s) => s.id === slot.teacherStaffId);
                return (
                  <tr key={slot.id}>
                    <td>{slot.periodNo}</td>
                    <td>
                      {slot.startsAt.slice(0, 5)}–{slot.endsAt.slice(0, 5)}
                    </td>
                    <td>{slot.subjectName}</td>
                    <td>
                      {cover
                        ? `${cover.substituteStaffName} (covering for ${cover.absentStaffName})`
                        : teacher
                          ? `${teacher.firstName} ${teacher.lastName ?? ""}`
                          : "—"}
                    </td>
                    <td>{slot.room ?? "—"}</td>
                  </tr>
                );
              })}
              {day.slots.length === 0 && (
                <tr>
                  <td colSpan={5} className="hint">
                    Nothing timetabled for this section on that date.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        )}
      </div>
    </main>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}
