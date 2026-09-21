"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import {
  AcademicYearDto,
  activateRollover,
  allocateRollover,
  ApiError,
  cloneRolloverStructure,
  commitRollover,
  createAcademicYear,
  getMe,
  getRolloverRun,
  getSession,
  hasScreen,
  listAcademicYears,
  listRolloverRuns,
  listSections,
  reallocateStudent,
  ReadinessReportDto,
  rollbackRollover,
  RolloverAllocationDto,
  rolloverAllocations,
  rolloverReadiness,
  RolloverCommitResultDto,
  RolloverRunDto,
  SectionDto,
  Session,
  startRollover,
} from "@/lib/api";

const STEPS = [
  { key: "readiness", label: "Readiness" },
  { key: "structure", label: "Next year's structure" },
  { key: "allocation", label: "Where each child goes" },
  { key: "commit", label: "Move the school" },
  { key: "activate", label: "Start the year" },
] as const;

const BLOCKER_LABELS: Record<string, string> = {
  unpublished_assessment: "Assessments not published",
  unlocked_report_card: "Report cards not sealed",
  missing_promotion_decision: "No promotion decision",
  unmarked_attendance_day: "Registers not marked",
  outstanding_dues: "Fees outstanding",
};

function inr(n: number): string {
  return n.toLocaleString(undefined, { style: "currency", currency: "INR", maximumFractionDigits: 2 });
}

/** Which step a run's state has reached, so the wizard opens where the work is. */
function stepOf(run: RolloverRunDto | null): number {
  if (!run) return 0;
  switch (run.state) {
    case "draft":
      return 1;
    case "structure_cloned":
      return 2;
    case "allocated":
      return 3;
    case "committed":
      return 4;
    default:
      return 0;
  }
}

/**
 * Phase 6's screen: the year-end wizard.
 *
 * It is deliberately five separate steps with the state of the run between
 * them, rather than one button that "does the rollover". Every step is either
 * inspectable before it is taken (the readiness list, the allocation table) or
 * reversible after it (commit, until the new year is started) — because the
 * thing being moved is every child in the school, and the school needs to see
 * what is about to happen to them.
 */
export default function RolloverPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [staffId, setStaffId] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [years, setYears] = useState<AcademicYearDto[] | null>(null);
  const [fromYearId, setFromYearId] = useState("");
  const [toYearId, setToYearId] = useState("");
  const [newYear, setNewYear] = useState({ code: "", startsOn: "", endsOn: "" });

  const [runs, setRuns] = useState<RolloverRunDto[] | null>(null);
  const [run, setRun] = useState<RolloverRunDto | null>(null);
  const [readiness, setReadiness] = useState<ReadinessReportDto | null>(null);
  const [allocations, setAllocations] = useState<RolloverAllocationDto[] | null>(null);
  const [allocationFilter, setAllocationFilter] = useState<"all" | "unplaced" | "skipped" | "applied">("all");
  const [targetSections, setTargetSections] = useState<SectionDto[] | null>(null);
  const [edits, setEdits] = useState<Record<string, { toSectionId: string; reason: string }>>({});
  const [commitResult, setCommitResult] = useState<RolloverCommitResultDto | null>(null);
  const [rollbackReason, setRollbackReason] = useState("");

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    if (!hasScreen(s, "rollover")) {
      router.replace("/dashboard");
      return;
    }
    setSessionState(s);
    getMe()
      .then((me) => setStaffId(me.subjectId))
      .catch(() => setStaffId(""));
    Promise.all([listAcademicYears(s.schoolId), listRolloverRuns(s.schoolId)])
      .then(([ays, rs]) => {
        setYears(ays);
        setRuns(rs);
        const current = ays.find((y) => y.isCurrent) ?? ays[0];
        if (current) setFromYearId(current.id);
        const planned = ays.find((y) => y.status === "planning");
        if (planned) setToYearId(planned.id);
        const live = rs.find((r) => r.state !== "rolled_back" && r.state !== "committed") ?? rs[0] ?? null;
        if (live) setRun(live);
      })
      .catch((err) => setError(describeError(err)));
  }, [router]);

  const refreshYears = useCallback(() => {
    if (!session) return;
    listAcademicYears(session.schoolId).then(setYears).catch((err) => setError(describeError(err)));
  }, [session]);

  const refreshAllocations = useCallback(() => {
    if (!run) return;
    rolloverAllocations(run.id).then(setAllocations).catch((err) => setError(describeError(err)));
  }, [run]);

  useEffect(() => {
    if (!run || !session) return;
    if (run.state === "allocated" || run.state === "committed") refreshAllocations();
    listSections(session.schoolId, run.toAcademicYearId)
      .then(setTargetSections)
      .catch(() => setTargetSections(null));
  }, [run, session, refreshAllocations]);

  async function run_(action: () => Promise<void>) {
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

  const step = stepOf(run);
  const fromYear = years?.find((y) => y.id === fromYearId) ?? null;

  const visibleAllocations = useMemo(() => {
    if (!allocations) return null;
    switch (allocationFilter) {
      case "unplaced":
        return allocations.filter((a) => !a.toSectionId && a.decision !== "graduate" && a.state !== "applied");
      case "skipped":
        return allocations.filter((a) => a.state === "skipped");
      case "applied":
        return allocations.filter((a) => a.state === "applied");
      default:
        return allocations;
    }
  }, [allocations, allocationFilter]);

  const allocationCounts = useMemo(() => {
    if (!allocations) return null;
    return {
      total: allocations.length,
      promote: allocations.filter((a) => a.decision === "promote").length,
      detain: allocations.filter((a) => a.decision === "detain").length,
      graduate: allocations.filter((a) => a.decision === "graduate").length,
      unplaced: allocations.filter(
        (a) => !a.toSectionId && a.decision !== "graduate" && a.state !== "applied"
      ).length,
      skipped: allocations.filter((a) => a.state === "skipped").length,
      applied: allocations.filter((a) => a.state === "applied").length,
    };
  }, [allocations]);

  if (!session) return null;

  const blockersByKind = (readiness?.items ?? []).reduce<Record<string, string[]>>((acc, item) => {
    (acc[item.kind] ??= []).push(item.detail);
    return acc;
  }, {});

  return (
    <main className="shell">
      {error && <div className="error-banner">{error}</div>}
      {notice && <div className="notice-banner">{notice}</div>}

      {/* ------------------------------------------------------------ header */}
      <div className="panel">
        <h2>Year rollover</h2>
        <p className="hint">
          Moves every child into next year, carries what follows them, and closes the year behind them.
          Nothing is written until you commit, and a commit can be undone until the new year is started.
        </p>

        <div className="stepper">
          {STEPS.map((s, i) => (
            <div
              key={s.key}
              className={"step" + (i < step ? " done" : "") + (i === step ? " active" : "")}
            >
              <span className="step-no">{i + 1}</span>
              <span>{s.label}</span>
            </div>
          ))}
        </div>

        {run ? (
          <p className="hint">
            <strong>
              {run.fromAcademicYearCode} → {run.toAcademicYearCode}
            </strong>{" "}
            · <span className={"badge " + stateBadge(run.state)}>{run.state.replace("_", " ")}</span> ·{" "}
            {run.batchesDone} {run.batchesDone === 1 ? "batch" : "batches"} applied · target year is{" "}
            {run.toAcademicYearStatus}
            {runs && runs.length > 1 && (
              <>
                {" · "}
                <select
                  value={run.id}
                  onChange={(e) => {
                    const next = runs.find((r) => r.id === e.target.value) ?? null;
                    setRun(next);
                    setCommitResult(null);
                  }}
                >
                  {runs.map((r) => (
                    <option key={r.id} value={r.id}>
                      {r.fromAcademicYearCode} → {r.toAcademicYearCode} ({r.state})
                    </option>
                  ))}
                </select>
              </>
            )}
          </p>
        ) : (
          <div className="form-row">
            <select value={fromYearId} onChange={(e) => setFromYearId(e.target.value)}>
              {years?.map((y) => (
                <option key={y.id} value={y.id}>
                  Closing {y.code}
                </option>
              ))}
            </select>
            <select value={toYearId} onChange={(e) => setToYearId(e.target.value)}>
              <option value="">Into…</option>
              {years
                ?.filter((y) => y.id !== fromYearId && y.status !== "closed")
                .map((y) => (
                  <option key={y.id} value={y.id}>
                    {y.code} ({y.status})
                  </option>
                ))}
            </select>
            <button
              type="button"
              disabled={busy || !fromYearId || !toYearId}
              onClick={() =>
                run_(async () => {
                  const started = await startRollover({
                    schoolId: session.schoolId,
                    fromAcademicYearId: fromYearId,
                    toAcademicYearId: toYearId,
                    startedByStaffId: staffId || undefined,
                  });
                  setRun(started);
                  setRuns(await listRolloverRuns(session.schoolId));
                  setNotice("Rollover started. Nothing has moved yet.");
                })
              }
            >
              Start rollover
            </button>
          </div>
        )}

        {!run && (
          <>
            <p className="hint">
              No year to roll into? Create it here — it starts in <strong>planning</strong>, which is what
              keeps it editable and the rollover reversible.
            </p>
            <div className="form-row">
              <input
                placeholder="Code (e.g. 2027-28)"
                value={newYear.code}
                onChange={(e) => setNewYear((f) => ({ ...f, code: e.target.value }))}
                style={{ maxWidth: 160 }}
              />
              <input
                type="date"
                value={newYear.startsOn}
                onChange={(e) => setNewYear((f) => ({ ...f, startsOn: e.target.value }))}
              />
              <input
                type="date"
                value={newYear.endsOn}
                onChange={(e) => setNewYear((f) => ({ ...f, endsOn: e.target.value }))}
              />
              <button
                type="button"
                className="secondary"
                disabled={busy || !newYear.code || !newYear.startsOn || !newYear.endsOn}
                onClick={() =>
                  run_(async () => {
                    const created = await createAcademicYear(session.schoolId, {
                      code: newYear.code,
                      startsOn: newYear.startsOn,
                      endsOn: newYear.endsOn,
                    });
                    setNewYear({ code: "", startsOn: "", endsOn: "" });
                    refreshYears();
                    setToYearId(created.id);
                    setNotice(`${created.code} created in planning.`);
                  })
                }
              >
                Create year
              </button>
            </div>
          </>
        )}
      </div>

      {/* --------------------------------------------------------- readiness */}
      <div className="panel">
        <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
          <h2>1 · Readiness</h2>
          <button
            type="button"
            className="secondary"
            disabled={busy || !fromYearId}
            onClick={() =>
              run_(async () => {
                setReadiness(
                  await rolloverReadiness(session.schoolId, run ? run.fromAcademicYearId : fromYearId)
                );
              })
            }
          >
            Check {fromYear ? fromYear.code : "the year"}
          </button>
        </div>
        <p className="hint">
          A year closed with these outstanding is discovered months later, when it is read-only and the fix
          needs an audited reopen.
        </p>

        {readiness && (
          <>
            {readiness.ready ? (
              <div className="notice-banner">
                {readiness.academicYearCode} is ready to close — {readiness.activeEnrolments} children, nothing
                outstanding.
              </div>
            ) : (
              <div className="warn-banner">
                {readiness.academicYearCode} is not ready. You may still roll over, but each of these is
                somebody&apos;s unfinished work.
              </div>
            )}
            <div className="stat-grid">
              <div className="stat-tile">
                <div className="value">{readiness.unpublishedAssessments}</div>
                <div className="label">Assessments unpublished</div>
              </div>
              <div className="stat-tile">
                <div className="value">{readiness.unlockedReportCards}</div>
                <div className="label">Report cards unsealed</div>
              </div>
              <div className="stat-tile">
                <div className="value">{readiness.missingPromotionDecisions}</div>
                <div className="label">Without a decision</div>
              </div>
              <div className="stat-tile">
                <div className="value">{readiness.unmarkedAttendanceDays}</div>
                <div className="label">Registers unmarked</div>
              </div>
              <div className="stat-tile">
                <div className="value">{readiness.studentsWithDues}</div>
                <div className="label">Families owing {inr(readiness.outstandingTotal)}</div>
              </div>
            </div>

            {Object.entries(blockersByKind).map(([kind, details]) => (
              <div key={kind} style={{ marginTop: 12 }}>
                <strong>{BLOCKER_LABELS[kind] ?? kind}</strong>
                <ul className="rejected-list">
                  {details.slice(0, 10).map((detail, i) => (
                    <li key={i}>{detail}</li>
                  ))}
                  {details.length > 10 && <li>…and {details.length - 10} more</li>}
                </ul>
              </div>
            ))}
          </>
        )}
      </div>

      {/* --------------------------------------------------------- structure */}
      {run && (
        <div className="panel">
          <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
            <h2>2 · Next year&apos;s structure</h2>
            <button
              type="button"
              disabled={busy || run.state === "committed" || run.state === "rolled_back"}
              onClick={() =>
                run_(async () => {
                  const updated = await cloneRolloverStructure(run.id);
                  setRun(updated);
                  setNotice(
                    `${updated.stats.sectionsCloned ?? 0} sections and ${updated.stats.feeStructuresCloned ?? 0} fee structures copied into ${updated.toAcademicYearCode}.`
                  );
                })
              }
            >
              Clone into {run.toAcademicYearCode}
            </button>
          </div>
          <p className="hint">
            Sections, capacities, curriculum bindings and fee structures are copied into the new year while it
            is still in planning, so you can rename, resize or re-price before anybody is in them. Teacher
            assignments deliberately do not come across — who teaches next year&apos;s classes is a decision,
            not a continuation. Bell schedules hang off the grade, so they already apply.
          </p>
          {run.stats.sectionsCloned != null && (
            <div className="stat-grid">
              <div className="stat-tile">
                <div className="value">{run.stats.sectionsCloned}</div>
                <div className="label">Sections copied</div>
              </div>
              <div className="stat-tile">
                <div className="value">{run.stats.sectionsAlreadyThere ?? 0}</div>
                <div className="label">Already there</div>
              </div>
              <div className="stat-tile">
                <div className="value">{run.stats.feeStructuresCloned ?? 0}</div>
                <div className="label">Fee structures copied</div>
              </div>
            </div>
          )}
        </div>
      )}

      {/* -------------------------------------------------------- allocation */}
      {run && (
        <div className="panel">
          <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
            <h2>3 · Where each child goes</h2>
            <button
              type="button"
              disabled={busy || run.state === "committed" || run.state === "rolled_back"}
              onClick={() =>
                run_(async () => {
                  const updated = await allocateRollover(run.id);
                  setRun(updated);
                  setAllocations(await rolloverAllocations(updated.id));
                  setNotice("Plan built. Nothing has moved — check it before committing.");
                })
              }
            >
              Build the plan
            </button>
          </div>
          <p className="hint">
            Each child is placed by the promotion decision on their report card: promoted into the next grade,
            detained into the same one, or graduated out. The class stays together where there is room,
            siblings stay in one section, and a full grade produces unplaced rows rather than an invented
            chair.
          </p>

          {allocationCounts && (
            <>
              <div className="stat-grid">
                <div className="stat-tile">
                  <div className="value">{allocationCounts.promote}</div>
                  <div className="label">Promoting</div>
                </div>
                <div className="stat-tile">
                  <div className="value">{allocationCounts.detain}</div>
                  <div className="label">Repeating</div>
                </div>
                <div className="stat-tile">
                  <div className="value">{allocationCounts.graduate}</div>
                  <div className="label">Graduating</div>
                </div>
                <div className="stat-tile">
                  <div className="value">{allocationCounts.unplaced}</div>
                  <div className="label">No seat yet</div>
                </div>
                <div className="stat-tile">
                  <div className="value">{allocationCounts.skipped}</div>
                  <div className="label">No decision</div>
                </div>
              </div>

              {(allocationCounts.unplaced > 0 || allocationCounts.skipped > 0) && (
                <div className="warn-banner">
                  The year will not close while a child has nowhere to go: give them a section, or record a
                  promotion decision, and commit again.
                </div>
              )}

              <div className="tabs" style={{ marginTop: 12 }}>
                {(["all", "unplaced", "skipped", "applied"] as const).map((f) => (
                  <button
                    key={f}
                    type="button"
                    className={"tab" + (allocationFilter === f ? " active" : "")}
                    onClick={() => setAllocationFilter(f)}
                  >
                    {f === "all" ? `All ${allocationCounts.total}` : f}
                  </button>
                ))}
              </div>
            </>
          )}

          {visibleAllocations && visibleAllocations.length > 0 && (
            <table>
              <thead>
                <tr>
                  <th>Admission no.</th>
                  <th>Child</th>
                  <th>From</th>
                  <th>Decision</th>
                  <th>Going to</th>
                  <th>State</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {visibleAllocations.slice(0, 200).map((a) => {
                  const edit = edits[a.id];
                  const editable = a.state !== "applied" && run.state !== "committed";
                  return (
                    <tr key={a.id}>
                      <td>{a.admissionNo}</td>
                      <td>{a.studentName}</td>
                      <td>{a.fromSectionLabel}</td>
                      <td>
                        <span className={"badge " + decisionBadge(a.decision)}>{a.decision}</span>
                      </td>
                      <td>
                        {editable ? (
                          <select
                            value={edit?.toSectionId ?? a.toSectionId ?? ""}
                            onChange={(e) =>
                              setEdits((m) => ({
                                ...m,
                                [a.id]: { toSectionId: e.target.value, reason: edit?.reason ?? "" },
                              }))
                            }
                          >
                            <option value="">{a.decision === "graduate" ? "— leaving —" : "No seat"}</option>
                            {targetSections?.map((s) => (
                              <option key={s.id} value={s.id}>
                                {s.gradeName}-{s.code}
                              </option>
                            ))}
                          </select>
                        ) : (
                          (a.toSectionLabel ?? "—")
                        )}
                      </td>
                      <td>
                        <span className={"badge " + (a.state === "applied" ? "badge-active" : "")}>
                          {a.state}
                        </span>
                        {a.note && <div className="hint">{a.note}</div>}
                      </td>
                      <td>
                        {editable && edit && (
                          <div className="form-row inline">
                            <input
                              placeholder="Reason, if over capacity"
                              value={edit.reason}
                              onChange={(e) =>
                                setEdits((m) => ({ ...m, [a.id]: { ...edit, reason: e.target.value } }))
                              }
                              style={{ maxWidth: 200 }}
                            />
                            <button
                              type="button"
                              className="secondary"
                              disabled={busy}
                              onClick={() =>
                                run_(async () => {
                                  await reallocateStudent(a.id, {
                                    toSectionId: edit.toSectionId || undefined,
                                    overCapacityReason: edit.reason || undefined,
                                  });
                                  setEdits((m) => {
                                    const next = { ...m };
                                    delete next[a.id];
                                    return next;
                                  });
                                  refreshAllocations();
                                })
                              }
                            >
                              Save
                            </button>
                          </div>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
          {visibleAllocations && visibleAllocations.length > 200 && (
            <p className="hint">Showing the first 200 of {visibleAllocations.length}.</p>
          )}
        </div>
      )}

      {/* ------------------------------------------------------------ commit */}
      {run && (
        <div className="panel">
          <h2>4 · Move the school</h2>
          <p className="hint">
            Applies the plan in batches of {run.batchSize}: old enrolments close as promoted, detained or
            graduated, new ones open in {run.toAcademicYearCode}, and arrears, bus seats and elective choices
            follow each child. An interrupted run resumes where it stopped. The year you are closing stays
            open until every child has somewhere to go.
          </p>
          <div className="form-row">
            <button
              type="button"
              className="secondary"
              disabled={busy || run.state === "rolled_back"}
              onClick={() =>
                run_(async () => {
                  const result = await commitRollover(run.id, {
                    maxBatches: 1,
                    actingStaffId: staffId || undefined,
                  });
                  setCommitResult(result);
                  setRun(await getRolloverRun(run.id));
                  refreshAllocations();
                  setNotice(`${result.applied} moved, ${result.remaining} to go.`);
                })
              }
            >
              Apply one batch
            </button>
            <button
              type="button"
              disabled={busy || run.state === "rolled_back"}
              onClick={() =>
                run_(async () => {
                  const result = await commitRollover(run.id, { actingStaffId: staffId || undefined });
                  setCommitResult(result);
                  setRun(await getRolloverRun(run.id));
                  refreshAllocations();
                  setNotice(
                    result.sourceYearClosed
                      ? `${run.fromAcademicYearCode} is closed. Its records are now read-only.`
                      : `${result.applied} moved; ${result.remaining} still to place.`
                  );
                })
              }
            >
              Apply the rest
            </button>
          </div>

          {commitResult && (
            <div className="stat-grid">
              <div className="stat-tile">
                <div className="value">{commitResult.applied}</div>
                <div className="label">Moved this run</div>
              </div>
              <div className="stat-tile">
                <div className="value">{commitResult.graduated}</div>
                <div className="label">Graduated</div>
              </div>
              <div className="stat-tile">
                <div className="value">{commitResult.remaining}</div>
                <div className="label">Still to move</div>
              </div>
              <div className="stat-tile">
                <div className="value">{inr(commitResult.arrearsCarried)}</div>
                <div className="label">Arrears carried</div>
              </div>
            </div>
          )}
        </div>
      )}

      {/* ---------------------------------------------------------- activate */}
      {run && (
        <div className="panel">
          <h2>5 · Start the year — or undo</h2>
          <p className="hint">
            Activation makes {run.toAcademicYearCode} the live year. It is the point of no return: after it,
            timetables, registers and invoices are being written against the new year, and unwinding would
            take that work with it.
          </p>
          <div className="form-row">
            <button
              type="button"
              disabled={busy || run.state !== "committed" || run.toAcademicYearIsCurrent}
              onClick={() =>
                run_(async () => {
                  const updated = await activateRollover(run.id, { actingStaffId: staffId || undefined });
                  setRun(updated);
                  refreshYears();
                  setNotice(`${updated.toAcademicYearCode} is now the current year.`);
                })
              }
            >
              {run.toAcademicYearIsCurrent ? `${run.toAcademicYearCode} is live` : `Start ${run.toAcademicYearCode}`}
            </button>
          </div>

          {!run.toAcademicYearIsCurrent && run.state !== "rolled_back" && (
            <>
              <p className="hint">
                Undoing removes every enrolment, opening balance and carried assignment this run created, puts
                the old enrolments back, and reopens {run.fromAcademicYearCode}. Anything a person did since —
                a payment against an opening balance, say — blocks it rather than being deleted.
              </p>
              <div className="form-row">
                <input
                  placeholder="Why is this being undone?"
                  value={rollbackReason}
                  onChange={(e) => setRollbackReason(e.target.value)}
                  style={{ minWidth: 280 }}
                />
                <button
                  type="button"
                  className="danger"
                  disabled={busy || !rollbackReason.trim()}
                  onClick={() =>
                    run_(async () => {
                      const updated = await rollbackRollover(run.id, {
                        reason: rollbackReason,
                        actingStaffId: staffId || undefined,
                      });
                      setRun(updated);
                      setRollbackReason("");
                      setCommitResult(null);
                      refreshYears();
                      refreshAllocations();
                      setNotice(`Rolled back. ${updated.fromAcademicYearCode} is open again.`);
                    })
                  }
                >
                  Undo this rollover
                </button>
              </div>
            </>
          )}
        </div>
      )}
    </main>
  );
}

function stateBadge(state: string): string {
  if (state === "committed") return "badge-active";
  if (state === "rolled_back") return "badge-suspended";
  return "";
}

function decisionBadge(decision: string): string {
  if (decision === "promote") return "badge-active";
  if (decision === "detain") return "badge-suspended";
  return "";
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}
