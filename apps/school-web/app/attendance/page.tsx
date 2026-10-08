"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { ReasonField, SyncConflict, SyncConflicts } from "@schoolsoft/ui";
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
  GateDisagreementDto,
  getMe,
  getSession,
  hasScreen,
  LeaveApplicationDto,
  listAmendments,
  listGateDisagreements,
  listLeave,
  listSections,
  listStudents,
  requestAmendment,
  rosterForSection,
  SectionDto,
  Session,
  StudentDto,
  syncAttendance,
} from "@/lib/api";

const STATUSES = ["present", "absent", "late", "leave", "excused", "half_day"];

type Tab = "register" | "gate" | "amendments" | "leave" | "cover";

/** A mark that was sent and did not land, with enough to send it again. */
type PendingConflict = SyncConflict & { sectionId: string; onDate: string; studentId: string };

function daysAgoIso(days: number): string {
  const d = new Date(Date.now() - days * 86400000);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

const TABS: { key: Tab; label: string }[] = [
  { key: "register", label: "Register" },
  { key: "gate", label: "Gate checks" },
  { key: "amendments", label: "Amendments" },
  { key: "leave", label: "Leave" },
  { key: "cover", label: "Cover" },
];

function todayIso(): string {
  // The local calendar date. toISOString() is UTC, which in India is still
  // yesterday until 05:30 — and a register opened early landed on the wrong day.
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
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
  const [confirmWithdraw, setConfirmWithdraw] = useState<string | null>(null);
  const [sections, setSections] = useState<SectionDto[] | null>(null);
  const [sectionId, setSectionId] = useState("");
  const [onDate, setOnDate] = useState(todayIso());
  const [roster, setRoster] = useState<EnrolmentDto[] | null>(null);
  // Which device reported each student at the gate that day, if one did.
  const [gate, setGate] = useState<Record<string, string>>({});
  const [students, setStudents] = useState<StudentDto[] | null>(null);
  const [statuses, setStatuses] = useState<Record<string, string>>({});
  // The markedAt read for each student when the register was loaded — what a
  // save from this screen is sent against, so a mark somebody else made in the
  // meantime is surfaced instead of overwritten.
  const [seen, setSeen] = useState<Record<string, string>>({});
  const [conflicts, setConflicts] = useState<PendingConflict[]>([]);
  const [gateFrom, setGateFrom] = useState(daysAgoIso(7));
  const [gateTo, setGateTo] = useState(todayIso());
  const [gateSectionId, setGateSectionId] = useState("");
  const [gateRows, setGateRows] = useState<GateDisagreementDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);

  const [amendments, setAmendments] = useState<AttendanceAmendmentDto[] | null>(null);
  /** The amendment being decided, and which way — its reason is asked in the row. */
  const [deciding, setDeciding] = useState<{ id: string; status: "approved" | "rejected" } | null>(null);
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
    // A date input is empty for a moment while it is being typed into; asking
    // for that register was a 500 that then outlived the real answer.
    if (!sectionId || !onDate || tab !== "register") return;
    let current = true;
    setLoading(true);
    setError(null);
    setNotice(null);
    Promise.all([rosterForSection(sectionId), attendanceForSectionOnDate(sectionId, onDate)])
      .then(([r, existing]) => {
        if (!current) return;
        setRoster(r);
        const initial: Record<string, string> = {};
        const seen: Record<string, string> = {};
        const read: Record<string, string> = {};
        for (const enr of r) {
          // Jackson's non_null inclusion omits periodNo from the JSON entirely when it's
          // null, rather than serializing `null` — so it arrives here as `undefined`, not
          // `null`. Loose equality catches both.
          const match = existing.find((e) => e.studentId === enr.studentId && e.periodNo == null);
          initial[enr.studentId] = match?.status ?? "present";
          if (match?.gateSeenAt) seen[enr.studentId] = match.gateSource ?? "biometric";
          if (match) read[enr.studentId] = match.markedAt;
        }
        setStatuses(initial);
        setGate(seen);
        setSeen(read);
        setConflicts([]);
      })
      .catch((err) => {
        if (current) setError(describeError(err));
      })
      .finally(() => {
        if (current) setLoading(false);
      });
    // A slower answer for a date already moved past must not land on the new one.
    return () => {
      current = false;
    };
  }, [sectionId, onDate, tab]);

  // A banner belongs to the tab that raised it.
  useEffect(() => {
    setError(null);
    setNotice(null);
  }, [tab]);

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
    if (!session || !onDate) return;
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
      const result = await syncAttendance(
        session.schoolId,
        sectionId,
        onDate,
        roster.map((r) => ({
          studentId: r.studentId,
          status: statuses[r.studentId] ?? "present",
          seenMarkedAt: seen[r.studentId],
        }))
      );
      setSeen((prev) => {
        const next = { ...prev };
        result.applied.forEach((r) => (next[r.studentId] = r.markedAt));
        return next;
      });
      setConflicts(
        result.conflicts.map((c) => ({
          key: `${sectionId}:${onDate}:${c.studentId}`,
          who: studentLabel(students, c.studentId),
          kind: c.kind,
          yours: c.yours,
          theirs: c.theirs && { status: c.theirs.status, source: c.theirs.source, markedAt: c.theirs.markedAt },
          message: c.message,
          sectionId,
          onDate,
          studentId: c.studentId,
        }))
      );
      setNotice(
        result.conflicts.length > 0
          ? `Saved ${result.applied.length} of ${roster.length}. ${result.conflicts.length} changed while this register was open — decide below.`
          : `Saved attendance for ${roster.length} student(s).`
      );
    });
  }

  /** Overrules what is on the register with the mark that was sent, now that both have been seen. */
  async function onUseMine(c: SyncConflict) {
    const conflict = conflicts.find((p) => p.key === c.key);
    if (!session || !conflict) return;
    await run(async () => {
      const result = await syncAttendance(session.schoolId, conflict.sectionId, conflict.onDate, [
        { studentId: conflict.studentId, status: conflict.yours, seenMarkedAt: conflict.theirs?.markedAt },
      ]);
      if (result.conflicts.length > 0) {
        // It moved again while the choice was being made. Show the newer value.
        const again = result.conflicts[0];
        setConflicts((prev) =>
          prev.map((p) =>
            p.key === c.key
              ? {
                  ...p,
                  kind: again.kind,
                  message: again.message,
                  theirs: again.theirs && {
                    status: again.theirs.status,
                    source: again.theirs.source,
                    markedAt: again.theirs.markedAt,
                  },
                }
              : p
          )
        );
        return;
      }
      setConflicts((prev) => prev.filter((p) => p.key !== c.key));
      setSeen((prev) => ({ ...prev, [conflict.studentId]: result.applied[0]?.markedAt ?? prev[conflict.studentId] }));
      setNotice(`${conflict.who}: saved as ${conflict.yours.replace("_", " ")}.`);
    });
  }

  /** Accepts what is on the register and drops the mark that was sent. */
  function onKeepTheirs(c: SyncConflict) {
    const conflict = conflicts.find((p) => p.key === c.key);
    setConflicts((prev) => prev.filter((p) => p.key !== c.key));
    const theirs = conflict?.theirs;
    if (!conflict || !theirs) return;
    setStatuses((prev) => ({ ...prev, [conflict.studentId]: theirs.status }));
    setSeen((prev) => ({ ...prev, [conflict.studentId]: theirs.markedAt }));
  }

  async function onLoadGateChecks() {
    if (!session) return;
    await run(async () => {
      setGateRows(await listGateDisagreements(session.schoolId, gateFrom, gateTo, gateSectionId || undefined));
    });
  }

  /** From a disagreement to the register it is on, where it can be put right. */
  function openRegister(row: GateDisagreementDto) {
    setSectionId(row.sectionId);
    setOnDate(row.onDate);
    setTab("register");
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

          {conflicts.length > 0 && (
            <div className="panel" style={{ marginTop: 12 }}>
              <strong>
                {conflicts.length === 1 ? "One mark needs" : `${conflicts.length} marks need`} a decision
              </strong>
              <div className="hint">The rest of the register was saved.</div>
              <SyncConflicts conflicts={conflicts} busy={busy} onUseMine={onUseMine} onKeepTheirs={onKeepTheirs} />
            </div>
          )}

          {loading && <p className="hint">Loading roster…</p>}
          {roster && roster.length === 0 && <p className="hint">No active students in this section.</p>}

          {roster && roster.length > 0 && (
            <table>
              <thead>
                <tr>
                  <th>Roll no.</th>
                  <th>Name</th>
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
                      <td>{studentLabel(students, r.studentId)}</td>
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
                        <GateNote source={gate[r.studentId]} status={statuses[r.studentId] ?? "present"} />
                      </td>
                    </tr>
                  ))}
              </tbody>
            </table>
          )}
        </div>
      )}

      {/* ---------------------------------------------------- gate checks */}
      {tab === "gate" && (
        <div className="panel">
          <h2>Gate checks</h2>
          <p className="hint">
            Days where a gate device saw a child the register has down as absent, on leave or excused. A gate
            read never changes a mark somebody made, so this is where the two are compared. One of them is
            wrong: a card lent to a friend, a register filled in from memory — or a child who came through
            the gate and did not reach the classroom.
          </p>
          <div className="form-row">
            <label>
              From <input type="date" value={gateFrom} onChange={(e) => setGateFrom(e.target.value)} />
            </label>
            <label>
              to <input type="date" value={gateTo} min={gateFrom} onChange={(e) => setGateTo(e.target.value)} />
            </label>
            <select
              aria-label="Section"
              value={gateSectionId}
              onChange={(e) => setGateSectionId(e.target.value)}
              disabled={!sections}
            >
              <option value="">All sections</option>
              {sections?.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.gradeName}-{s.code}
                </option>
              ))}
            </select>
            <button type="button" onClick={onLoadGateChecks} disabled={busy || !gateFrom || !gateTo}>
              Show
            </button>
          </div>

          {gateRows && gateRows.length === 0 && (
            <p className="hint">The gate and the register agree for every day in this range.</p>
          )}
          {gateRows && gateRows.length > 0 && (
            <table>
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Student</th>
                  <th>Section</th>
                  <th>Register says</th>
                  <th>Gate</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {gateRows.map((row) => (
                  <tr key={row.recordId}>
                    <td>{row.onDate}</td>
                    <td>
                      {row.studentName}
                      <br />
                      <span className="hint">{row.admissionNo}</span>
                    </td>
                    <td>{row.sectionLabel}</td>
                    <td>
                      <span className="badge badge-suspended">{row.status}</span>
                    </td>
                    <td>
                      Seen at the {row.gateSource === "rfid" ? "card reader" : "biometric gate"}
                      <br />
                      <span className="hint">reported {new Date(row.gateSeenAt).toLocaleString()}</span>
                    </td>
                    <td>
                      <button type="button" className="tab" onClick={() => openRegister(row)}>
                        Open register
                      </button>
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
                      {a.status === "pending" && deciding?.id === a.id ? (
                        <ReasonField
                          placeholder={
                            deciding.status === "approved"
                              ? "Why is this correction allowed?"
                              : "Why is this correction refused?"
                          }
                          confirmLabel={deciding.status === "approved" ? "Approve" : "Reject"}
                          busy={busy}
                          onCancel={() => setDeciding(null)}
                          onConfirm={(reason) =>
                            run(async () => {
                              await decideAmendment(a.id, { status: deciding.status, reason });
                              setDeciding(null);
                              if (deciding.status === "approved") {
                                setNotice("Amendment approved — the register now shows the corrected value.");
                              }
                              refreshAmendments();
                            })
                          }
                        />
                      ) : (
                        a.status === "pending" && (
                          <div className="form-row inline">
                            <button
                              type="button"
                              disabled={busy}
                              onClick={() => setDeciding({ id: a.id, status: "approved" })}
                            >
                              Approve
                            </button>
                            <button
                              type="button"
                              className="secondary"
                              disabled={busy}
                              onClick={() => setDeciding({ id: a.id, status: "rejected" })}
                            >
                              Reject
                            </button>
                          </div>
                        )
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
                          onClick={() => {
                            // Withdrawing rewrites the register; it takes a second press.
                            if (confirmWithdraw !== l.id) {
                              setConfirmWithdraw(l.id);
                              return;
                            }
                            setConfirmWithdraw(null);
                            run(async () => {
                              await decideLeave(l.id, { status: "cancelled", approverStaffId: staffId });
                              setNotice("Approval withdrawn — the days it created have been removed.");
                              refreshLeave();
                            });
                          }}
                        >
                          {confirmWithdraw === l.id ? "Confirm withdraw?" : "Withdraw"}
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

/**
 * The gate and the register, side by side. A device never changes a mark a
 * person made, so when a card was read on a day the register says the child
 * was not there, this is the only place the two meet — and somebody should
 * look, because one of them is wrong and the child may be in the building.
 */
function GateNote({ source, status }: { source: string | undefined; status: string }) {
  if (!source) return null;
  const device = source === "rfid" ? "card reader" : "biometric gate";
  if (status === "absent" || status === "leave") {
    return (
      <div className="gate-disagrees" role="note">
        The {device} saw this child arrive. Check before saving them as {status === "leave" ? "on leave" : "absent"}.
      </div>
    );
  }
  return <div className="hint">Seen at the {device}</div>;
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return err.userMessage;
  return err instanceof Error ? err.message : "Unknown error";
}
