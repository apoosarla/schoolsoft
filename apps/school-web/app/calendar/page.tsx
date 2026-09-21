"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import {
  AcademicYearDto,
  ApiError,
  CALENDAR_KINDS,
  CalendarEntryDto,
  calendarDays,
  createAcademicYear,
  createCalendarEntry,
  createTerm,
  createWorkingDayPattern,
  DayStatusDto,
  declareClosure,
  deleteCalendarEntry,
  getMe,
  getSession,
  GradeDto,
  hasScreen,
  listAcademicYears,
  listCalendarEntries,
  listGrades,
  listTerms,
  listWorkingDayPatterns,
  SATURDAY_RULES,
  Session,
  setAcademicYearStatus,
  TermDto,
  WorkingDayPatternDto,
} from "@/lib/api";

const WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

function monthOf(iso: string): string {
  return iso.slice(0, 7);
}

function monthRange(month: string): { from: string; to: string } {
  const [year, m] = month.split("-").map(Number);
  const last = new Date(Date.UTC(year, m, 0)).getUTCDate();
  return { from: `${month}-01`, to: `${month}-${String(last).padStart(2, "0")}` };
}

const emptyEntryForm = { onDate: todayIso(), kind: "holiday", title: "", description: "", gradeId: "" };
const emptyClosureForm = { onDate: todayIso(), title: "", description: "", gradeId: "" };
const emptyYearForm = { code: "", startsOn: "", endsOn: "", isCurrent: false };
const emptyTermForm = { code: "", name: "", startsOn: "", endsOn: "" };

/**
 * Phase 1's screen: the calendar every attendance percentage and fee due date
 * is computed against, and the year lifecycle that makes last year read-only.
 *
 * The month grid is deliberately the resolved answer rather than the raw
 * entries — a holiday, a working Saturday and the weekday pattern can all bear
 * on one date, and the school needs to see which of them won.
 */
export default function CalendarPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [staffId, setStaffId] = useState<string>("");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [years, setYears] = useState<AcademicYearDto[] | null>(null);
  const [selectedYearId, setSelectedYearId] = useState("");
  const [terms, setTerms] = useState<TermDto[] | null>(null);
  const [grades, setGrades] = useState<GradeDto[] | null>(null);
  const [patterns, setPatterns] = useState<WorkingDayPatternDto[] | null>(null);

  const [month, setMonth] = useState(monthOf(todayIso()));
  const [entries, setEntries] = useState<CalendarEntryDto[] | null>(null);
  const [days, setDays] = useState<DayStatusDto[] | null>(null);
  const [dayGrade, setDayGrade] = useState("");

  const [yearForm, setYearForm] = useState(emptyYearForm);
  const [termForm, setTermForm] = useState(emptyTermForm);
  const [entryForm, setEntryForm] = useState(emptyEntryForm);
  const [closureForm, setClosureForm] = useState(emptyClosureForm);
  const [reopenReason, setReopenReason] = useState("");
  const [patternForm, setPatternForm] = useState({
    effectiveFrom: todayIso(),
    effectiveTo: "",
    weekdays: [true, true, true, true, true, false, false],
    saturdayRule: "none",
    notes: "",
  });
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    if (!hasScreen(s, "calendar")) {
      router.replace("/dashboard");
      return;
    }
    setSessionState(s);
    getMe()
      .then((me) => setStaffId(me.subjectId))
      .catch(() => setStaffId(""));
    Promise.all([listAcademicYears(s.schoolId), listGrades(s.schoolId), listWorkingDayPatterns(s.schoolId)])
      .then(([ays, gs, ps]) => {
        setYears(ays);
        setGrades(gs);
        setPatterns(ps);
        const current = ays.find((y) => y.isCurrent) ?? ays[0];
        if (current) setSelectedYearId(current.id);
      })
      .catch((err) => setError(describeError(err)));
  }, [router]);

  useEffect(() => {
    if (!selectedYearId) return;
    listTerms(selectedYearId).then(setTerms).catch((err) => setError(describeError(err)));
  }, [selectedYearId]);

  const refreshMonth = useCallback(() => {
    if (!session) return;
    const { from, to } = monthRange(month);
    Promise.all([
      listCalendarEntries(session.schoolId, from, to),
      calendarDays(session.schoolId, from, to, dayGrade || undefined),
    ])
      .then(([es, ds]) => {
        setEntries(es);
        setDays(ds);
      })
      .catch((err) => setError(describeError(err)));
  }, [session, month, dayGrade]);

  useEffect(() => {
    refreshMonth();
  }, [refreshMonth]);

  function refreshYears() {
    if (!session) return;
    listAcademicYears(session.schoolId).then(setYears).catch((err) => setError(describeError(err)));
  }

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

  const selectedYear = years?.find((y) => y.id === selectedYearId) ?? null;

  const workingCount = useMemo(() => days?.filter((d) => d.working).length ?? 0, [days]);

  if (!session) return null;

  return (
    <main className="shell">
      {error && <div className="error-banner">{error}</div>}
      {notice && <div className="notice-banner">{notice}</div>}

      {/* ---------------------------------------------------- academic years */}
      <div className="panel">
        <h2>Academic years</h2>
        <p className="hint">
          Closing a year makes its attendance, marks and invoices read-only. Reopening one needs a reason and
          is written to the audit log.
        </p>
        <table>
          <thead>
            <tr>
              <th>Year</th>
              <th>Runs</th>
              <th>Status</th>
              <th>Current</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {years?.map((y) => (
              <tr
                key={y.id}
                onClick={() => setSelectedYearId(y.id)}
                className={y.id === selectedYearId ? "row-selected" : undefined}
              >
                <td>{y.code}</td>
                <td>
                  {y.startsOn} → {y.endsOn}
                </td>
                <td>
                  <span className={"badge " + statusBadge(y.status)}>{y.status}</span>
                </td>
                <td>{y.isCurrent ? "Yes" : "—"}</td>
                <td>
                  {y.status !== "closed" ? (
                    <button
                      type="button"
                      className="secondary"
                      disabled={busy}
                      onClick={() =>
                        run(async () => {
                          await setAcademicYearStatus(y.id, {
                            status: "closed",
                            actingStaffId: staffId || undefined,
                          });
                          setNotice(`${y.code} closed — its records are now read-only.`);
                          refreshYears();
                        })
                      }
                    >
                      Close year
                    </button>
                  ) : (
                    <div className="form-row inline">
                      <input
                        placeholder="Reason for reopening"
                        value={reopenReason}
                        onChange={(e) => setReopenReason(e.target.value)}
                      />
                      <button
                        type="button"
                        disabled={busy || reopenReason.trim().length === 0}
                        onClick={() =>
                          run(async () => {
                            await setAcademicYearStatus(y.id, {
                              status: "active",
                              actingStaffId: staffId || undefined,
                              reason: reopenReason,
                            });
                            setNotice(`${y.code} reopened — the reason is on the audit trail.`);
                            setReopenReason("");
                            refreshYears();
                          })
                        }
                      >
                        Reopen
                      </button>
                    </div>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>

        <div className="form-row" style={{ marginTop: 14 }}>
          <input
            placeholder="Code (2027-28)"
            value={yearForm.code}
            onChange={(e) => setYearForm({ ...yearForm, code: e.target.value })}
          />
          <input
            type="date"
            value={yearForm.startsOn}
            onChange={(e) => setYearForm({ ...yearForm, startsOn: e.target.value })}
          />
          <input
            type="date"
            value={yearForm.endsOn}
            onChange={(e) => setYearForm({ ...yearForm, endsOn: e.target.value })}
          />
          <label className="check">
            <input
              type="checkbox"
              checked={yearForm.isCurrent}
              onChange={(e) => setYearForm({ ...yearForm, isCurrent: e.target.checked })}
            />
            Make current
          </label>
          <button
            type="button"
            disabled={busy || !yearForm.code || !yearForm.startsOn || !yearForm.endsOn}
            onClick={() =>
              run(async () => {
                await createAcademicYear(session.schoolId, {
                  code: yearForm.code,
                  startsOn: yearForm.startsOn,
                  endsOn: yearForm.endsOn,
                  isCurrent: yearForm.isCurrent,
                });
                setYearForm(emptyYearForm);
                refreshYears();
              })
            }
          >
            Add year
          </button>
        </div>
      </div>

      {/* ------------------------------------------------------------- terms */}
      {selectedYear && (
        <div className="panel">
          <h2>Terms in {selectedYear.code}</h2>
          <table>
            <thead>
              <tr>
                <th>Code</th>
                <th>Name</th>
                <th>Runs</th>
              </tr>
            </thead>
            <tbody>
              {terms?.map((t) => (
                <tr key={t.id}>
                  <td>{t.code}</td>
                  <td>{t.name}</td>
                  <td>
                    {t.startsOn} → {t.endsOn}
                  </td>
                </tr>
              ))}
              {terms?.length === 0 && (
                <tr>
                  <td colSpan={3} className="hint">
                    No terms yet. A term must sit inside its year&apos;s dates and may not overlap another.
                  </td>
                </tr>
              )}
            </tbody>
          </table>

          <div className="form-row" style={{ marginTop: 14 }}>
            <input
              placeholder="Code (T1)"
              value={termForm.code}
              onChange={(e) => setTermForm({ ...termForm, code: e.target.value })}
            />
            <input
              placeholder="Name"
              value={termForm.name}
              onChange={(e) => setTermForm({ ...termForm, name: e.target.value })}
            />
            <input
              type="date"
              value={termForm.startsOn}
              onChange={(e) => setTermForm({ ...termForm, startsOn: e.target.value })}
            />
            <input
              type="date"
              value={termForm.endsOn}
              onChange={(e) => setTermForm({ ...termForm, endsOn: e.target.value })}
            />
            <button
              type="button"
              disabled={busy || !termForm.code || !termForm.startsOn || !termForm.endsOn}
              onClick={() =>
                run(async () => {
                  await createTerm(selectedYear.id, {
                    code: termForm.code,
                    name: termForm.name || termForm.code,
                    startsOn: termForm.startsOn,
                    endsOn: termForm.endsOn,
                  });
                  setTermForm(emptyTermForm);
                  listTerms(selectedYear.id).then(setTerms);
                })
              }
            >
              Add term
            </button>
          </div>
        </div>
      )}

      {/* --------------------------------------------------- month day grid */}
      <div className="panel">
        <h2>Working days</h2>
        <div className="form-row">
          <input type="month" value={month} onChange={(e) => setMonth(e.target.value)} />
          <select value={dayGrade} onChange={(e) => setDayGrade(e.target.value)}>
            <option value="">Whole school</option>
            {grades?.map((g) => (
              <option key={g.id} value={g.id}>
                Grade {g.code}
              </option>
            ))}
          </select>
          <span className="hint" style={{ alignSelf: "center" }}>
            {workingCount} working day(s) this month{dayGrade ? " for this grade" : ""}
          </span>
        </div>

        <div className="day-grid">
          {WEEKDAYS.map((w) => (
            <div key={w} className="day-head">
              {w}
            </div>
          ))}
          {days &&
            days.length > 0 &&
            Array.from({ length: leadingBlanks(days[0].date) }).map((_, i) => (
              <div key={`blank-${i}`} className="day-cell blank" />
            ))}
          {days?.map((d) => (
            <div key={d.date} className={"day-cell" + (d.working ? "" : " off")} title={d.reason ?? ""}>
              <span className="day-num">{Number(d.date.slice(8))}</span>
              {d.calendarKind && <span className="day-kind">{d.calendarKind.replace("_", " ")}</span>}
              {!d.calendarKind && !d.working && <span className="day-kind">off</span>}
            </div>
          ))}
        </div>
      </div>

      {/* ------------------------------------------------- calendar entries */}
      <div className="panel">
        <h2>Calendar entries</h2>
        <table>
          <thead>
            <tr>
              <th>Date</th>
              <th>Kind</th>
              <th>Title</th>
              <th>Scope</th>
              <th>Source</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {entries?.map((e) => (
              <tr key={e.id}>
                <td>{e.onDate}</td>
                <td>{e.kind.replace("_", " ")}</td>
                <td>{e.title}</td>
                <td>{e.gradeId ? gradeLabel(grades, e.gradeId) : e.campusId ? "One campus" : "Whole school"}</td>
                <td>{e.source}</td>
                <td>
                  <button
                    type="button"
                    className="secondary"
                    disabled={busy}
                    onClick={() =>
                      run(async () => {
                        await deleteCalendarEntry(e.id);
                        refreshMonth();
                      })
                    }
                  >
                    Remove
                  </button>
                </td>
              </tr>
            ))}
            {entries?.length === 0 && (
              <tr>
                <td colSpan={6} className="hint">
                  Nothing declared this month — the working-day pattern decides every date.
                </td>
              </tr>
            )}
          </tbody>
        </table>

        <div className="form-row" style={{ marginTop: 14 }}>
          <input
            type="date"
            value={entryForm.onDate}
            onChange={(e) => setEntryForm({ ...entryForm, onDate: e.target.value })}
          />
          <select value={entryForm.kind} onChange={(e) => setEntryForm({ ...entryForm, kind: e.target.value })}>
            {CALENDAR_KINDS.map((k) => (
              <option key={k} value={k}>
                {k.replace("_", " ")}
              </option>
            ))}
          </select>
          <input
            placeholder="Title"
            value={entryForm.title}
            onChange={(e) => setEntryForm({ ...entryForm, title: e.target.value })}
          />
          <select value={entryForm.gradeId} onChange={(e) => setEntryForm({ ...entryForm, gradeId: e.target.value })}>
            <option value="">Whole school</option>
            {grades?.map((g) => (
              <option key={g.id} value={g.id}>
                Grade {g.code} only
              </option>
            ))}
          </select>
          <button
            type="button"
            disabled={busy || !entryForm.title}
            onClick={() =>
              run(async () => {
                await createCalendarEntry({
                  schoolId: session.schoolId,
                  academicYearId: selectedYearId || undefined,
                  onDate: entryForm.onDate,
                  kind: entryForm.kind,
                  title: entryForm.title,
                  description: entryForm.description || undefined,
                  gradeId: entryForm.gradeId || undefined,
                  declaredByStaffId: staffId || undefined,
                });
                setEntryForm({ ...emptyEntryForm, onDate: entryForm.onDate, kind: entryForm.kind });
                setMonth(monthOf(entryForm.onDate));
                refreshMonth();
              })
            }
          >
            Add entry
          </button>
        </div>
      </div>

      {/* ---------------------------------------------------- same-day closure */}
      <div className="panel">
        <h2>Declare an unplanned closure</h2>
        <p className="hint">
          Weather, a strike, an emergency. Three things happen together: the day stops counting as a working
          day, attendance already marked for it is voided (kept on record, not deleted), and the affected
          guardians are notified.
        </p>
        <div className="form-row">
          <input
            type="date"
            value={closureForm.onDate}
            onChange={(e) => setClosureForm({ ...closureForm, onDate: e.target.value })}
          />
          <input
            placeholder="Reason parents will see"
            value={closureForm.title}
            onChange={(e) => setClosureForm({ ...closureForm, title: e.target.value })}
          />
          <select
            value={closureForm.gradeId}
            onChange={(e) => setClosureForm({ ...closureForm, gradeId: e.target.value })}
          >
            <option value="">Whole school</option>
            {grades?.map((g) => (
              <option key={g.id} value={g.id}>
                Grade {g.code} only
              </option>
            ))}
          </select>
          <button
            type="button"
            disabled={busy || !closureForm.title}
            onClick={() =>
              run(async () => {
                const result = await declareClosure({
                  schoolId: session.schoolId,
                  onDate: closureForm.onDate,
                  title: closureForm.title,
                  gradeId: closureForm.gradeId || undefined,
                  declaredByStaffId: staffId || undefined,
                });
                setNotice(
                  `Closure declared for ${result.entry.onDate}: ${result.voidedAttendanceRecords} attendance ` +
                    `record(s) voided, ${result.guardiansNotified} guardian(s) notified.`
                );
                setClosureForm({ ...emptyClosureForm, onDate: closureForm.onDate });
                setMonth(monthOf(closureForm.onDate));
                refreshMonth();
              })
            }
          >
            Declare closure
          </button>
        </div>
      </div>

      {/* ------------------------------------------------- working-day pattern */}
      <div className="panel">
        <h2>Working-day pattern</h2>
        <p className="hint">
          The default week, and which Saturdays count. A calendar entry always beats the pattern — a declared
          closure closes a working Saturday too.
        </p>
        <table>
          <thead>
            <tr>
              <th>In force</th>
              <th>Week</th>
              <th>Saturdays</th>
              <th>Notes</th>
            </tr>
          </thead>
          <tbody>
            {patterns?.map((p) => (
              <tr key={p.id}>
                <td>
                  {p.effectiveFrom} → {p.effectiveTo ?? "open"}
                </td>
                <td>{maskLabel(p.weekdayMask)}</td>
                <td>{p.saturdayRule}</td>
                <td>{p.notes ?? "—"}</td>
              </tr>
            ))}
            {patterns?.length === 0 && (
              <tr>
                <td colSpan={4} className="hint">
                  None configured — dates fall back to a Monday–Friday week.
                </td>
              </tr>
            )}
          </tbody>
        </table>

        <div className="form-row" style={{ marginTop: 14 }}>
          <input
            type="date"
            value={patternForm.effectiveFrom}
            onChange={(e) => setPatternForm({ ...patternForm, effectiveFrom: e.target.value })}
          />
          {WEEKDAYS.map((w, i) => (
            <label key={w} className="check">
              <input
                type="checkbox"
                checked={patternForm.weekdays[i]}
                onChange={(e) => {
                  const next = [...patternForm.weekdays];
                  next[i] = e.target.checked;
                  setPatternForm({ ...patternForm, weekdays: next });
                }}
              />
              {w}
            </label>
          ))}
          <select
            value={patternForm.saturdayRule}
            onChange={(e) => setPatternForm({ ...patternForm, saturdayRule: e.target.value })}
          >
            {SATURDAY_RULES.map((r) => (
              <option key={r} value={r}>
                Saturdays: {r}
              </option>
            ))}
          </select>
          <button
            type="button"
            disabled={busy}
            onClick={() =>
              run(async () => {
                await createWorkingDayPattern({
                  schoolId: session.schoolId,
                  effectiveFrom: patternForm.effectiveFrom,
                  effectiveTo: patternForm.effectiveTo || undefined,
                  weekdayMask: patternForm.weekdays.map((d) => (d ? "1" : "0")).join(""),
                  saturdayRule: patternForm.saturdayRule,
                  notes: patternForm.notes || undefined,
                });
                listWorkingDayPatterns(session.schoolId).then(setPatterns);
                refreshMonth();
              })
            }
          >
            Save pattern
          </button>
        </div>
      </div>
    </main>
  );
}

/** Monday-first offset, so the 1st lands under the right column. */
function leadingBlanks(firstDate: string): number {
  const day = new Date(`${firstDate}T00:00:00Z`).getUTCDay(); // 0 = Sunday
  return (day + 6) % 7;
}

function maskLabel(mask: string): string {
  return WEEKDAYS.filter((_, i) => mask.charAt(i) === "1").join(" ");
}

function gradeLabel(grades: GradeDto[] | null, gradeId: string): string {
  const grade = grades?.find((g) => g.id === gradeId);
  return grade ? `Grade ${grade.code}` : "One grade";
}

function statusBadge(status: string): string {
  if (status === "active") return "badge-active";
  if (status === "closed") return "badge-suspended";
  return "";
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}
