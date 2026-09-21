"use client";

import { CSSProperties, Fragment, useEffect, useState } from "react";
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
  retireTimetableSlot,
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

/** Which view the user last chose. Per browser, not per school. */
const VIEW_KEY = "schoolsoft.timetable.view";

type ViewMode = "table" | "grid";

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

  // The table lists the week; the grid lays it out. Same slots, same date
  // question — the toggle is a preference, so it is remembered per browser.
  const [view, setView] = useState<ViewMode>("table");
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState(emptyForm);
  const [creating, setCreating] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  // The week is a question about a date: a revision supersedes rather than
  // overwrites, so "the timetable" without one is not a thing the school has.
  const [asOf, setAsOf] = useState(todayIso());
  const [retiringId, setRetiringId] = useState<string | null>(null);
  const [retireDate, setRetireDate] = useState(todayIso());
  const [retiring, setRetiring] = useState(false);
  const [dayDate, setDayDate] = useState(todayIso());
  const [day, setDay] = useState<SectionDayDto | null>(null);
  const [bell, setBell] = useState<BellScheduleDto | null>(null);
  const [warnings, setWarnings] = useState<string[] | null>(null);

  useEffect(() => {
    const stored = window.localStorage.getItem(VIEW_KEY);
    if (stored === "grid" || stored === "table") setView(stored);
  }, []);

  function chooseView(next: ViewMode) {
    setView(next);
    try {
      window.localStorage.setItem(VIEW_KEY, next);
    } catch {
      // A browser refusing storage is not worth an error banner.
    }
  }

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

  function refresh(id: string, onDate: string) {
    setLoading(true);
    setError(null);
    timetableForSection(id, onDate)
      .then(setSlots)
      .catch((err) => setError(describeError(err)))
      .finally(() => setLoading(false));
    // Room clashes and teachers over their weekly load: advisory at publish
    // time, so they are shown beside the grid rather than blocking an edit.
    // Both are about the load carried on the date being published.
    timetablePublishWarnings(id, onDate)
      .then((w) => setWarnings(w.warnings))
      .catch(() => setWarnings(null));
  }

  useEffect(() => {
    if (!sectionId) return;
    refresh(sectionId, asOf);
  }, [sectionId, asOf]);

  useEffect(() => {
    if (selectedId && slots && !slots.some((s) => s.id === selectedId)) setSelectedId(null);
  }, [slots, selectedId]);

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
  const selectedSlot = slots?.find((s) => s.id === selectedId) ?? null;
  const selectedTeacher = staff?.find((s) => s.id === selectedSlot?.teacherStaffId);

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
      refresh(sectionId, asOf);
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
      refresh(sectionId, asOf);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setDeletingId(null);
    }
  }

  async function onRetire(id: string) {
    setRetiring(true);
    setError(null);
    try {
      await retireTimetableSlot(id, retireDate);
      setRetiringId(null);
      refresh(sectionId, asOf);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setRetiring(false);
    }
  }

  if (!session) return null;

  return (
    <main className="shell">
      <div className="panel">
        <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
          <h2>Timetable</h2>
          <div className="form-row inline" style={{ alignItems: "center", marginBottom: 0 }}>
            <div className="tabs" style={{ marginBottom: 0 }}>
              <button
                type="button"
                className={`tab${view === "table" ? " active" : ""}`}
                aria-pressed={view === "table"}
                onClick={() => chooseView("table")}
              >
                Table
              </button>
              <button
                type="button"
                className={`tab${view === "grid" ? " active" : ""}`}
                aria-pressed={view === "grid"}
                onClick={() => chooseView("grid")}
              >
                Grid
              </button>
            </div>
            <button type="button" onClick={() => setShowForm((v) => !v)} disabled={!subjects || subjects.length === 0}>
              {showForm ? "Cancel" : "Add slot"}
            </button>
          </div>
        </div>
        <div className="form-row">
          <select value={sectionId} onChange={(e) => setSectionId(e.target.value)} disabled={!sections}>
            {sections?.map((s) => (
              <option key={s.id} value={s.id}>
                {s.gradeName}-{s.code}
              </option>
            ))}
          </select>
          <label className="hint" htmlFor="as-of">
            in force on
          </label>
          <input id="as-of" type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} />
        </div>
        <p className="hint">
          A revision supersedes rather than overwrites: a slot is retired from a last day and its
          replacement starts the next, so last term&rsquo;s grid still reads back at last term&rsquo;s
          dates. Move this date to see the week that was — or the one that will be.
        </p>

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
        {slots && slots.length === 0 && (
          <p className="hint">Nothing was on this section&rsquo;s timetable on {asOf}.</p>
        )}

        {view === "table" && slots && slots.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Day</th>
                <th>Period</th>
                <th>Time</th>
                <th>Subject</th>
                <th>Teacher</th>
                <th>Room</th>
                <th>In force</th>
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
                        {slot.effectiveFrom}
                        {slot.effectiveTo ? ` – ${slot.effectiveTo}` : " –"}
                      </td>
                      <td>
                        {retiringId === slot.id ? (
                          <span className="form-row" style={{ margin: 0 }}>
                            <input
                              type="date"
                              value={retireDate}
                              min={slot.effectiveFrom}
                              onChange={(e) => setRetireDate(e.target.value)}
                            />
                            <button type="button" onClick={() => onRetire(slot.id)} disabled={retiring}>
                              {retiring ? "…" : "Last day"}
                            </button>
                            <button type="button" onClick={() => setRetiringId(null)} disabled={retiring}>
                              Cancel
                            </button>
                          </span>
                        ) : (
                          <>
                            <button
                              type="button"
                              onClick={() => {
                                setRetiringId(slot.id);
                                setRetireDate(slot.effectiveTo ?? todayIso());
                              }}
                            >
                              Retire
                            </button>{" "}
                            <button type="button" onClick={() => onDelete(slot.id)} disabled={deletingId === slot.id}>
                              {deletingId === slot.id ? "…" : "Delete"}
                            </button>
                          </>
                        )}
                      </td>
                    </tr>
                  );
                })}
            </tbody>
          </table>
        )}

        {view === "grid" && slots && (
          <WeekGrid
            slots={slots}
            staff={staff}
            bell={bell}
            selectedId={selectedId}
            onPick={(id) => {
              setSelectedId(id);
              setRetiringId(null);
            }}
            onAddAt={(row, dayOfWeek) => {
              // A free cell is the add form with the day and the period
              // already answered: the two fields nobody should retype.
              setForm((f) => ({
                ...f,
                dayOfWeek: String(dayOfWeek),
                periodId: row.periodId ?? "",
                periodNo: String(row.periodNo),
                startsAt: hhmm(row.startsAt),
                endsAt: hhmm(row.endsAt),
                effectiveFrom: asOf,
              }));
              setSelectedId(null);
              setShowForm(true);
            }}
          />
        )}

        {view === "grid" && selectedSlot && (
          <div className="tt-selected">
            <div className="head">
              <span>{selectedSlot.subjectName}</span>
              <span className="badge">
                {DAY_NAMES[selectedSlot.dayOfWeek]} &middot; P{selectedSlot.periodNo}
              </span>
            </div>
            <dl>
              <div className="pair">
                <dt>Teacher</dt>
                <dd>{selectedTeacher ? `${selectedTeacher.firstName} ${selectedTeacher.lastName ?? ""}` : "\u2014"}</dd>
              </div>
              <div className="pair">
                <dt>Room</dt>
                <dd>{selectedSlot.room ?? "\u2014"}</dd>
              </div>
              <div className="pair">
                <dt>Time</dt>
                <dd>
                  {hhmm(selectedSlot.startsAt)}&ndash;{hhmm(selectedSlot.endsAt)}
                </dd>
              </div>
              <div className="pair">
                <dt>In force</dt>
                <dd>
                  {selectedSlot.effectiveFrom}
                  {selectedSlot.effectiveTo ? ` \u2013 ${selectedSlot.effectiveTo}` : " \u2013"}
                </dd>
              </div>
            </dl>
            <div className="form-row inline" style={{ marginTop: 10, alignItems: "center" }}>
              {retiringId === selectedSlot.id ? (
                <>
                  <label className="hint" htmlFor="retire-on">
                    last day
                  </label>
                  <input
                    id="retire-on"
                    type="date"
                    value={retireDate}
                    min={selectedSlot.effectiveFrom}
                    onChange={(e) => setRetireDate(e.target.value)}
                  />
                  <button type="button" onClick={() => onRetire(selectedSlot.id)} disabled={retiring}>
                    {retiring ? "\u2026" : "Retire"}
                  </button>
                  <button type="button" className="secondary" onClick={() => setRetiringId(null)} disabled={retiring}>
                    Cancel
                  </button>
                </>
              ) : (
                <>
                  <button
                    type="button"
                    className="secondary"
                    onClick={() => {
                      setRetiringId(selectedSlot.id);
                      setRetireDate(selectedSlot.effectiveTo ?? todayIso());
                    }}
                  >
                    Retire
                  </button>
                  <button
                    type="button"
                    className="danger"
                    onClick={() => onDelete(selectedSlot.id)}
                    disabled={deletingId === selectedSlot.id}
                  >
                    {deletingId === selectedSlot.id ? "\u2026" : "Delete"}
                  </button>
                  <button type="button" className="secondary" onClick={() => setSelectedId(null)}>
                    Close
                  </button>
                </>
              )}
            </div>
            <p className="hint">
              Retire ends the window from a last day and leaves the slot resolvable, so the attendance and
              lesson plans hung off it still read back. Delete is for a slot authored by mistake.
            </p>
          </div>
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

/** A bell schedule's times carry seconds; a grid has no room for them. */
function hhmm(t: string): string {
  return t.slice(0, 5);
}

/**
 * A subject keeps its colour between sections and across a reload, because the
 * hue is a hash of its id rather than its position in the week.
 */
function hueOf(subjectId: string): string {
  let h = 0;
  for (let i = 0; i < subjectId.length; i++) h = (h * 31 + subjectId.charCodeAt(i)) >>> 0;
  return `var(--tt-h${h % 8})`;
}

type GridRow = {
  kind: "period" | "break";
  key: string;
  label: string;
  periodNo: number;
  startsAt: string;
  endsAt: string;
  /** Set only for a teaching period of a bell schedule. */
  periodId?: string;
};

/**
 * The rows are the bell schedule's periods when the grade has one — breaks
 * included, since a break is a band across the week rather than six cells
 * nobody may teach in. Without a bell schedule the rows are whatever period
 * numbers the slots themselves carry, timed by the first slot sitting on each.
 */
function gridRows(bell: BellScheduleDto | null, slots: TimetableSlotDto[]): GridRow[] {
  if (bell && bell.periods.length > 0) {
    return bell.periods
      .slice()
      .sort((a, b) => a.periodNo - b.periodNo)
      .map((p) => ({
        kind: p.isBreak ? ("break" as const) : ("period" as const),
        key: p.id,
        label: p.label,
        periodNo: p.periodNo,
        startsAt: p.startsAt,
        endsAt: p.endsAt,
        periodId: p.isBreak ? undefined : p.id,
      }));
  }
  const byNo = new Map<number, TimetableSlotDto>();
  for (const s of slots) if (!byNo.has(s.periodNo)) byNo.set(s.periodNo, s);
  return [...byNo.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([no, s]) => ({
      kind: "period" as const,
      key: `p${no}`,
      label: `P${no}`,
      periodNo: no,
      startsAt: s.startsAt,
      endsAt: s.endsAt,
    }));
}

/** Monday to Saturday, plus any other day something is actually timetabled on. */
function gridDays(slots: TimetableSlotDto[]): number[] {
  const days = new Set([1, 2, 3, 4, 5, 6]);
  for (const s of slots) days.add(s.dayOfWeek);
  return [...days].sort((a, b) => a - b);
}

function WeekGrid({
  slots,
  staff,
  bell,
  selectedId,
  onPick,
  onAddAt,
}: {
  slots: TimetableSlotDto[];
  staff: StaffDto[] | null;
  bell: BellScheduleDto | null;
  selectedId: string | null;
  onPick: (id: string) => void;
  onAddAt: (row: GridRow, dayOfWeek: number) => void;
}) {
  const rows = gridRows(bell, slots);
  const days = gridDays(slots);

  const byCell = new Map<string, TimetableSlotDto[]>();
  const perDay = new Map<number, number>();
  for (const s of slots) {
    const key = `${s.dayOfWeek}:${s.periodNo}`;
    const at = byCell.get(key);
    if (at) at.push(s);
    else byCell.set(key, [s]);
    perDay.set(s.dayOfWeek, (perDay.get(s.dayOfWeek) ?? 0) + 1);
  }

  // One legend entry per subject on this week's grid, in the order they read.
  const subjects = new Map<string, string>();
  for (const s of slots) if (!subjects.has(s.subjectId)) subjects.set(s.subjectId, s.subjectName);

  if (rows.length === 0) {
    return (
      <p className="hint">
        No bell schedule for this grade and nothing timetabled, so there are no rows to draw. Add a slot
        with its own times, or give the grade a bell schedule.
      </p>
    );
  }

  return (
    <>
      <div
        className="tt-grid"
        style={{ gridTemplateColumns: `104px repeat(${days.length}, minmax(0, 1fr))` }}
      >
        <div />
        {days.map((d) => (
          <div key={d} className="tt-day-head">
            <div className="name">{DAY_NAMES[d]}</div>
            <div className="count">
              {perDay.get(d) ?? 0} period{(perDay.get(d) ?? 0) === 1 ? "" : "s"}
            </div>
          </div>
        ))}

        {rows.map((row) =>
          row.kind === "break" ? (
            <div key={row.key} className="tt-break">
              <span className="label">{row.label}</span>
              <span className="time">
                {hhmm(row.startsAt)}&ndash;{hhmm(row.endsAt)}
              </span>
            </div>
          ) : (
            <Fragment key={row.key}>
              <div className="tt-period">
                <div className="no">{row.label}</div>
                <div className="time">
                  {hhmm(row.startsAt)}&ndash;{hhmm(row.endsAt)}
                </div>
              </div>
              {days.map((d) => {
                const at = byCell.get(`${d}:${row.periodNo}`) ?? [];
                if (at.length === 0) {
                  return (
                    <button
                      key={d}
                      type="button"
                      className="tt-cell free"
                      onClick={() => onAddAt(row, d)}
                      aria-label={`Add a slot on ${DAY_NAMES[d]}, ${row.label}`}
                    >
                      + Add slot
                    </button>
                  );
                }
                return (
                  <div key={d} style={{ display: "flex", flexDirection: "column", gap: 4 }}>
                    {at.map((slot) => {
                      const teacher = staff?.find((t) => t.id === slot.teacherStaffId);
                      // The two warnings the grid can answer for itself: a slot
                      // with no room is one of the publish warnings above, and a
                      // window with a last day is a revision already filed.
                      const roomless = !slot.room;
                      const ends = Boolean(slot.effectiveTo);
                      // A slot with its own times sits on the row its period
                      // number names, but it does not run when that row runs:
                      // say so rather than let the row's clock speak for it.
                      const offRow = hhmm(slot.startsAt) !== hhmm(row.startsAt);
                      const selected = slot.id === selectedId;
                      return (
                        <button
                          key={slot.id}
                          type="button"
                          className={`tt-cell${roomless ? " warn" : ""}${ends ? " ends" : ""}${
                            selected ? " selected" : ""
                          }`}
                          style={{ "--dot": hueOf(slot.subjectId) } as CSSProperties}
                          aria-pressed={selected}
                          onClick={() => onPick(slot.id)}
                        >
                          <span className="subject">{slot.subjectName}</span>
                          <span className="who">
                            {teacher ? `${teacher.firstName} ${teacher.lastName ?? ""}`.trim() : "\u2014"}
                            {slot.room ? ` \u00b7 ${slot.room}` : ""}
                          </span>
                          {offRow && (
                            <span className="mark time">
                              {hhmm(slot.startsAt)}&ndash;{hhmm(slot.endsAt)}
                            </span>
                          )}
                          {roomless && <span className="mark warn">no room</span>}
                          {ends && <span className="mark ends">last day {slot.effectiveTo}</span>}
                        </button>
                      );
                    })}
                  </div>
                );
              })}
            </Fragment>
          )
        )}
      </div>

      <div className="tt-legend">
        {[...subjects.entries()].map(([id, name]) => (
          <span key={id}>
            <span className="swatch dot" style={{ "--dot": hueOf(id) } as CSSProperties} />
            {name}
          </span>
        ))}
        <span>
          <span className="swatch warn" />
          no room assigned
        </span>
        <span>
          <span className="swatch ends" />
          window has a last day
        </span>
        <span>
          <span className="swatch free" />
          free &mdash; click to add
        </span>
      </div>
    </>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}
