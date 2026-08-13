"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  assignCover,
  attendanceForSectionOnDate,
  AttendanceAmendmentDto,
  cancelCover,
  CoverDto,
  CoverNeedDto,
  coverForDay,
  coverNeeds,
  decideAmendment,
  decideLeave,
  EnrolmentDto,
  getMe,
  getSession,
  hasScreen,
  LeaveApplicationDto,
  listAmendments,
  listLeave,
  listSections,
  listStudents,
  markAttendanceBulk,
  requestAmendment,
  rosterForSection,
  SectionDto,
  Session,
  StudentDto,
} from "@/lib/api";

const STATUSES = ["present", "absent", "late", "leave", "excused", "half_day"];

type Tab = "register" | "amendments" | "leave" | "cover";

const TABS: { key: Tab; label: string }[] = [
  { key: "register", label: "Register" },
  { key: "amendments", label: "Amendments" },
  { key: "leave", label: "Leave" },
  { key: "cover", label: "Cover" },
];

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * Phase 3's daily operations, all four in one place because they are one
 * working day: the register, the corrections that come after it closes, the
 * leave that fills it in, and the periods an absent teacher leaves behind.
 */
export default function AttendancePage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [staffId, setStaffId] = useState("");
  const [tab, setTab] = useState<Tab>("register");
  const [sections, setSections] = useState<SectionDto[] | null>(null);
  const [sectionId, setSectionId] = useState("");
  const [onDate, setOnDate] = useState(todayIso());
  const [roster, setRoster] = useState<EnrolmentDto[] | null>(null);
  const [students, setStudents] = useState<StudentDto[] | null>(null);
  const [statuses, setStatuses] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);

  const [amendments, setAmendments] = useState<AttendanceAmendmentDto[] | null>(null);
  const [amendmentFilter, setAmendmentFilter] = useState("pending");
  const [amendForm, setAmendForm] = useState({ studentId: "", newStatus: "present", reason: "" });

  const [leave, setLeave] = useState<LeaveApplicationDto[] | null>(null);
  const [leaveFilter, setLeaveFilter] = useState("pending");

  const [needs, setNeeds] = useState<CoverNeedDto[] | null>(null);
  const [covers, setCovers] = useState<CoverDto[] | null>(null);
  const [substitute, setSubstitute] = useState<Record<string, string>>({});

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    if (!hasScreen(s, "attendance")) {
      router.replace("/dashboard");
      return;
    }
    setSessionState(s);
    getMe()
      .then((me) => setStaffId(me.subjectId))
      .catch(() => setStaffId(""));
    Promise.all([listSections(s.schoolId), listStudents(s.schoolId)])
      .then(([secs, studs]) => {
        setSections(secs);
        setStudents(studs);
        if (secs.length > 0) setSectionId(secs[0].id);
      })
      .catch((err) => setError(describeError(err)));
  }, [router]);

  useEffect(() => {
    if (!sectionId || tab !== "register") return;
    setLoading(true);
    setError(null);
    setNotice(null);
    Promise.all([rosterForSection(sectionId), attendanceForSectionOnDate(sectionId, onDate)])
      .then(([r, existing]) => {
        setRoster(r);
        const initial: Record<string, string> = {};
        for (const enr of r) {
          // Jackson's non_null inclusion omits periodNo from the JSON entirely when it's
          // null, rather than serializing `null` — so it arrives here as `undefined`, not
          // `null`. Loose equality catches both.
          const match = existing.find((e) => e.studentId === enr.studentId && e.periodNo == null);
          initial[enr.studentId] = match?.status ?? "present";
        }
        setStatuses(initial);
      })
      .catch((err) => setError(describeError(err)))
      .finally(() => setLoading(false));
  }, [sectionId, onDate, tab]);

  const refreshAmendments = useCallback(() => {
    if (!session) return;
    listAmendments(session.schoolId, amendmentFilter || undefined)
      .then(setAmendments)
      .catch((err) => setError(describeError(err)));
  }, [session, amendmentFilter]);

  const refreshLeave = useCallback(() => {
    if (!session) return;
    listLeave(session.schoolId, leaveFilter || undefined)
      .then(setLeave)
      .catch((err) => setError(describeError(err)));
  }, [session, leaveFilter]);

  const refreshCover = useCallback(() => {
    if (!session) return;
    Promise.all([coverNeeds(session.schoolId, onDate), coverForDay(session.schoolId, onDate)])
      .then(([n, c]) => {
        setNeeds(n);
        setCovers(c);
      })
      .catch((err) => setError(describeError(err)));
  }, [session, onDate]);

  useEffect(() => {
    if (tab === "amendments") refreshAmendments();
    if (tab === "leave") refreshLeave();
    if (tab === "cover") refreshCover();
  }, [tab, refreshAmendments, refreshLeave, refreshCover]);

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

  async function onSave() {
    if (!session || !roster) return;
    await run(async () => {
      await markAttendanceBulk(
        session.schoolId,
        sectionId,
        onDate,
        roster.map((r) => ({ studentId: r.studentId, status: statuses[r.studentId] ?? "present" }))
      );
      setNotice(`Saved attendance for ${roster.length} student(s).`);
    });
  }

  if (!session) return null;

  return (
    <main className="shell">
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

      {error && <div className="error-banner">{error}</div>}
      {notice && <div className="notice-banner">{notice}</div>}

      {/* ------------------------------------------------------- register */}
      {tab === "register" && (
        <div className="panel">
          <h2>Mark attendance</h2>
          <div className="form-row">
            <select value={sectionId} onChange={(e) => setSectionId(e.target.value)} disabled={!sections}>
              {sections?.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.gradeName}-{s.code}
                </option>
              ))}
            </select>
            <input type="date" value={onDate} onChange={(e) => setOnDate(e.target.value)} />
            <button type="button" onClick={onSave} disabled={busy || loading || !roster || roster.length === 0}>
              {busy ? "Saving…" : "Save attendance"}
            </button>
          </div>

          <p className="hint">
            Inside the school&apos;s marking window this saves as a correction. Once the window has closed the
            register refuses a silent overwrite — raise an amendment instead, and somebody senior decides it.
          </p>

          {loading && <p className="hint">Loading roster…</p>}
          {roster && roster.length === 0 && <p className="hint">No active students in this section.</p>}

          {roster && roster.length > 0 && (
            <table>
              <thead>
                <tr>
                  <th>Roll no.</th>
                  <th>Status</th>
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
                        <select
                          value={statuses[r.studentId] ?? "present"}
                          onChange={(e) => setStatuses((s) => ({ ...s, [r.studentId]: e.target.value }))}
                        >
                          {STATUSES.map((st) => (
                            <option key={st} value={st}>
                              {st.replace("_", " ")}
                            </option>
                          ))}
                        </select>
                      </td>
                    </tr>
                  ))}
              </tbody>
            </table>
          )}
        </div>
      )}

      {/* ----------------------------------------------------- amendments */}
      {tab === "amendments" && (
        <>
          <div className="panel">
            <h2>Amendment requests</h2>
            <p className="hint">
              A correction to a register that has already closed. The record keeps the current truth; the
              amendment keeps what it used to be, who asked, who allowed it, and why.
            </p>
            <div className="form-row">
              <select value={amendmentFilter} onChange={(e) => setAmendmentFilter(e.target.value)}>
                <option value="pending">Pending</option>
                <option value="approved">Approved</option>
                <option value="rejected">Rejected</option>
                <option value="">All</option>
              </select>
            </div>

            <table>
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Student</th>
                  <th>Change</th>
                  <th>Reason</th>
                  <th>Status</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {amendments?.map((a) => (
                  <tr key={a.id}>
                    <td>
                      {a.onDate}
                      {a.periodNo != null ? ` · period ${a.periodNo}` : ""}
                    </td>
                    <td>{studentLabel(students, a.studentId)}</td>
                    <td>
                      {a.oldStatus} → {a.newStatus}
                    </td>
                    <td style={{ whiteSpace: "normal" }}>{a.reason}</td>
                    <td>
                      <span className={"badge " + (a.status === "approved" ? "badge-active" : "")}>{a.status}</span>
                    </td>
                    <td>
                      {a.status === "pending" && (
                        <div className="form-row inline">
                          <button
                            type="button"
                            disabled={busy}
                            onClick={() =>
                              run(async () => {
                                const reason = window.prompt("Why is this correction allowed?")?.trim();
                                if (!reason) return;
                                await decideAmendment(a.id, { status: "approved", reason });
                                setNotice("Amendment approved — the register now shows the corrected value.");
                                refreshAmendments();
                              })
                            }
                          >
                            Approve
                          </button>
                          <button
                            type="button"
                            className="secondary"
                            disabled={busy}
                            onClick={() =>
                              run(async () => {
                                const reason = window.prompt("Why is this correction refused?")?.trim();
                                if (!reason) return;
                                await decideAmendment(a.id, { status: "rejected", reason });
                                refreshAmendments();
                              })
                            }
                          >
                            Reject
                          </button>
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
                {amendments?.length === 0 && (
                  <tr>
                    <td colSpan={6} className="hint">
                      Nothing waiting.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          <div className="panel">
            <h2>Raise an amendment</h2>
            <div className="form-row">
              <input type="date" value={onDate} onChange={(e) => setOnDate(e.target.value)} />
              <select
                value={amendForm.studentId}
                onChange={(e) => setAmendForm({ ...amendForm, studentId: e.target.value })}
              >
                <option value="">Choose a student</option>
                {students?.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.firstName} {s.lastName ?? ""} ({s.currentSectionLabel ?? "—"})
                  </option>
                ))}
              </select>
              <select
                value={amendForm.newStatus}
                onChange={(e) => setAmendForm({ ...amendForm, newStatus: e.target.value })}
              >
                {STATUSES.map((st) => (
                  <option key={st} value={st}>
                    {st.replace("_", " ")}
                  </option>
                ))}
              </select>
              <input
                placeholder="Reason"
                value={amendForm.reason}
                onChange={(e) => setAmendForm({ ...amendForm, reason: e.target.value })}
              />
              <button
                type="button"
                disabled={busy || !amendForm.studentId || !amendForm.reason}
                onClick={() =>
                  run(async () => {
                    await requestAmendment({
                      schoolId: session.schoolId,
                      studentId: amendForm.studentId,
                      onDate,
                      newStatus: amendForm.newStatus,
                      reason: amendForm.reason,
                    });
                    setAmendForm({ studentId: "", newStatus: "present", reason: "" });
                    setNotice("Amendment raised — it needs a decision before the register changes.");
                    setAmendmentFilter("pending");
                    refreshAmendments();
                  })
                }
              >
                Request
              </button>
            </div>
          </div>
        </>
      )}

      {/* ---------------------------------------------------------- leave */}
      {tab === "leave" && (
        <div className="panel">
          <h2>Leave applications</h2>
          <p className="hint">
            Approving one writes <em>leave</em> across the working days it covers — the school calendar
            decides which those are — and revoking the approval takes exactly those days back out again.
          </p>
          <div className="form-row">
            <select value={leaveFilter} onChange={(e) => setLeaveFilter(e.target.value)}>
              <option value="pending">Pending</option>
              <option value="approved">Approved</option>
              <option value="rejected">Rejected</option>
              <option value="">All</option>
            </select>
          </div>

          <table>
            <thead>
              <tr>
                <th>Applicant</th>
                <th>Dates</th>
                <th>Reason</th>
                <th>Status</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {leave?.map((l) => (
                <tr key={l.id}>
                  <td>
                    {l.subjectType === "student" ? studentLabel(students, l.subjectId) : "Staff"}{" "}
                    <span className="hint">({l.subjectType})</span>
                  </td>
                  <td>
                    {l.fromDate} → {l.toDate}
                  </td>
                  <td style={{ whiteSpace: "normal" }}>{l.reason ?? "—"}</td>
                  <td>
                    <span className={"badge " + (l.status === "approved" ? "badge-active" : "")}>{l.status}</span>
                  </td>
                  <td>
                    <div className="form-row inline">
                      {l.status === "pending" && (
                        <>
                          <button
                            type="button"
                            disabled={busy || !staffId}
                            onClick={() =>
                              run(async () => {
                                await decideLeave(l.id, { status: "approved", approverStaffId: staffId });
                                setNotice("Approved — the covered working days are now marked as leave.");
                                refreshLeave();
                              })
                            }
                          >
                            Approve
                          </button>
                          <button
                            type="button"
                            className="secondary"
                            disabled={busy || !staffId}
                            onClick={() =>
                              run(async () => {
                                await decideLeave(l.id, { status: "rejected", approverStaffId: staffId });
                                refreshLeave();
                              })
                            }
                          >
                            Reject
                          </button>
                        </>
                      )}
                      {l.status === "approved" && (
                        <button
                          type="button"
                          className="secondary"
                          disabled={busy || !staffId}
                          onClick={() =>
                            run(async () => {
                              await decideLeave(l.id, { status: "cancelled", approverStaffId: staffId });
                              setNotice("Approval withdrawn — the days it created have been removed.");
                              refreshLeave();
                            })
                          }
                        >
                          Withdraw
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
              {leave?.length === 0 && (
                <tr>
                  <td colSpan={5} className="hint">
                    Nothing to decide.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}

      {/* ---------------------------------------------------------- cover */}
      {tab === "cover" && (
        <>
          <div className="panel">
            <h2>Cover needed</h2>
            <p className="hint">
              Periods whose teacher is on approved leave, each with the colleagues genuinely free in that
              period. Assigning cover tells the substitute and the section, and is what authorises the
              substitute to mark that period&apos;s register.
            </p>
            <div className="form-row">
              <input type="date" value={onDate} onChange={(e) => setOnDate(e.target.value)} />
              <button type="button" className="secondary" disabled={busy} onClick={() => run(async () => refreshCover())}>
                Refresh
              </button>
            </div>

            <table>
              <thead>
                <tr>
                  <th>Period</th>
                  <th>Section</th>
                  <th>Subject</th>
                  <th>Away</th>
                  <th>Substitute</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {needs?.map((n) => (
                  <tr key={n.slotId}>
                    <td>
                      {n.periodNo} · {n.startsAt.slice(0, 5)}
                    </td>
                    <td>{n.sectionLabel}</td>
                    <td>{n.subjectName}</td>
                    <td>{n.absentStaffName}</td>
                    <td>
                      {n.cover ? (
                        n.cover.substituteStaffName
                      ) : (
                        <select
                          value={substitute[n.slotId] ?? ""}
                          onChange={(e) => setSubstitute((s) => ({ ...s, [n.slotId]: e.target.value }))}
                        >
                          <option value="">Choose a free teacher</option>
                          {n.candidates.map((c) => (
                            <option key={c.staffId} value={c.staffId}>
                              {c.name} ({c.periodsThatDay} periods today)
                            </option>
                          ))}
                        </select>
                      )}
                    </td>
                    <td>
                      {n.cover ? (
                        <button
                          type="button"
                          className="secondary"
                          disabled={busy}
                          onClick={() =>
                            run(async () => {
                              await cancelCover(n.cover!.id);
                              refreshCover();
                            })
                          }
                        >
                          Cancel
                        </button>
                      ) : (
                        <button
                          type="button"
                          disabled={busy || !substitute[n.slotId]}
                          onClick={() =>
                            run(async () => {
                              await assignCover({
                                slotId: n.slotId,
                                onDate,
                                substituteStaffId: substitute[n.slotId],
                                reason: `Covering for ${n.absentStaffName}`,
                              });
                              setNotice("Cover assigned — the substitute and the section have been told.");
                              refreshCover();
                            })
                          }
                        >
                          Assign
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
                {needs?.length === 0 && (
                  <tr>
                    <td colSpan={6} className="hint">
                      Nobody is on leave with periods to cover that day.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          {covers && covers.length > 0 && (
            <div className="panel">
              <h2>Cover assigned on {onDate}</h2>
              <table>
                <thead>
                  <tr>
                    <th>Period</th>
                    <th>Section</th>
                    <th>Away</th>
                    <th>Taken by</th>
                    <th>Reason</th>
                  </tr>
                </thead>
                <tbody>
                  {covers.map((c) => (
                    <tr key={c.id}>
                      <td>
                        {c.periodNo} · {c.startsAt.slice(0, 5)}
                      </td>
                      <td>{c.sectionLabel}</td>
                      <td>{c.absentStaffName}</td>
                      <td>{c.substituteStaffName}</td>
                      <td style={{ whiteSpace: "normal" }}>{c.reason ?? "—"}</td>
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

function studentLabel(students: StudentDto[] | null, studentId: string): string {
  const student = students?.find((s) => s.id === studentId);
  return student ? `${student.firstName} ${student.lastName ?? ""}`.trim() : studentId.slice(0, 8);
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}
