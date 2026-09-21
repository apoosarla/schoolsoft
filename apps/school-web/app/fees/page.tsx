"use client";

import { Fragment, useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import {
  AcademicYearDto,
  ApiError,
  cloneFeeStructure,
  ConcessionDto,
  createCombinedFamilyInvoice,
  createFeeHead,
  createFeeStructure,
  createInvoice,
  DayBookDto,
  DunningPolicyDto,
  DunningResultDto,
  FEE_ADJUSTMENT_EFFECT,
  FEE_ADJUSTMENT_KINDS,
  FeeAdjustmentDto,
  feeDayBook,
  FeeHeadDto,
  FeeInvoiceDto,
  FeeInvoiceLineDto,
  feeOutstanding,
  FeeRunResultDto,
  FeeScheduleRunDto,
  FeeStructureDto,
  generateInvoices,
  getDunningPolicy,
  getMe,
  getSession,
  GradeDto,
  grantConcession,
  hasScreen,
  InvoiceLineInput,
  linkFamily,
  listAcademicYears,
  listAdjustments,
  listConcessions,
  listFeeHeads,
  listFeeRuns,
  listFeeStructures,
  listGrades,
  listInvoiceLines,
  listInvoicesForStudent,
  listPaymentsForInvoice,
  listSiblingPolicies,
  listStudents,
  OutstandingReportDto,
  PaymentDto,
  postAdjustment,
  recordPayment,
  replaceFeeStructureLines,
  runDunning,
  saveDunningPolicy,
  Session,
  SiblingPolicyDto,
  StudentDto,
  studentDues,
  upsertSiblingPolicy,
} from "@/lib/api";

type Tab = "collections" | "structures" | "generation" | "dunning" | "reports";

const TABS: { key: Tab; label: string }[] = [
  { key: "collections", label: "Collections" },
  { key: "structures", label: "Structures & concessions" },
  { key: "generation", label: "Generation" },
  { key: "dunning", label: "Dunning" },
  { key: "reports", label: "Reports" },
];

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

function monthStartIso(): string {
  return todayIso().slice(0, 8) + "01";
}

function inr(n: number): string {
  return n.toLocaleString(undefined, { style: "currency", currency: "INR", maximumFractionDigits: 2 });
}

const emptyLine: InvoiceLineInput = { feeHeadId: "", description: "", amount: 0, discount: 0, gst: 0 };

/**
 * Phase 4's screen. Collections was here already; the rest of the fee engine —
 * the structures a bill is assembled from, the generation run that is a no-op
 * the second time, adjustments, dunning, and the two reports an office actually
 * runs — had working endpoints and no surface.
 *
 * The tabs follow the money rather than the tables: what a grade is billed,
 * what was billed, what came back, who was chased, and what is still owed.
 */
export default function FeesPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [staffId, setStaffId] = useState("");
  const [tab, setTab] = useState<Tab>("collections");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [heads, setHeads] = useState<FeeHeadDto[] | null>(null);
  const [years, setYears] = useState<AcademicYearDto[] | null>(null);
  const [yearId, setYearId] = useState("");
  const [grades, setGrades] = useState<GradeDto[] | null>(null);

  // ---- collections
  const [q, setQ] = useState("");
  const [students, setStudents] = useState<StudentDto[] | null>(null);
  const [student, setStudent] = useState<StudentDto | null>(null);
  const [invoices, setInvoices] = useState<FeeInvoiceDto[] | null>(null);
  const [dues, setDues] = useState<{ balance: number; hasDues: boolean } | null>(null);
  const [concessions, setConcessions] = useState<ConcessionDto[] | null>(null);
  const [loadingInvoices, setLoadingInvoices] = useState(false);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [invoiceLines, setInvoiceLines] = useState<FeeInvoiceLineDto[] | null>(null);
  const [payments, setPayments] = useState<PaymentDto[] | null>(null);
  const [adjustments, setAdjustments] = useState<FeeAdjustmentDto[] | null>(null);
  const [payAmount, setPayAmount] = useState("");
  const [payMethod, setPayMethod] = useState("cash");
  const [payingId, setPayingId] = useState<string | null>(null);
  const [adjustForm, setAdjustForm] = useState({
    kind: "credit_note",
    amount: "",
    reason: "",
    paymentId: "",
    feeHeadId: "",
  });
  const [showInvoiceForm, setShowInvoiceForm] = useState(false);
  const [invoiceNo, setInvoiceNo] = useState("");
  const [cycleLabel, setCycleLabel] = useState("");
  const [dueOn, setDueOn] = useState(todayIso());
  const [lines, setLines] = useState<InvoiceLineInput[]>([{ ...emptyLine }]);
  const [creatingInvoice, setCreatingInvoice] = useState(false);
  const [familyCycle, setFamilyCycle] = useState("");

  // ---- structures & concessions
  const [structures, setStructures] = useState<FeeStructureDto[] | null>(null);
  const [showHeadForm, setShowHeadForm] = useState(false);
  const [headForm, setHeadForm] = useState({ code: "", name: "", isRecurring: false, gstRatePct: 0, hsnSac: "" });
  const [structureForm, setStructureForm] = useState({ gradeId: "", name: "" });
  const [structureLines, setStructureLines] = useState<{ feeHeadId: string; amount: number }[]>([
    { feeHeadId: "", amount: 0 },
  ]);
  const [editingStructureId, setEditingStructureId] = useState<string | null>(null);
  const [editLines, setEditLines] = useState<{ feeHeadId: string; amount: number }[]>([]);
  const [cloneTargetYearId, setCloneTargetYearId] = useState("");
  const [siblingPolicies, setSiblingPolicies] = useState<SiblingPolicyDto[] | null>(null);
  const [siblingForm, setSiblingForm] = useState({ nthChild: 2, pct: 10, appliesToHeadId: "" });
  const [concessionForm, setConcessionForm] = useState({
    kind: "staff_ward",
    pct: "",
    flatAmount: "",
    appliesToHeadId: "",
    notes: "",
  });

  // ---- generation
  const [runs, setRuns] = useState<FeeScheduleRunDto[] | null>(null);
  const [runForm, setRunForm] = useState({ gradeId: "", cycleLabel: "", dueOn: todayIso() });
  const [lastRun, setLastRun] = useState<FeeRunResultDto | null>(null);

  // ---- dunning
  const [policy, setPolicy] = useState<DunningPolicyDto | null>(null);
  const [policyForm, setPolicyForm] = useState({
    graceDays: 0,
    reminderDays: "1, 7, 15",
    lateFeePct: "",
    lateFeeFlat: "",
    lateFeeHeadId: "",
  });
  const [dunningDate, setDunningDate] = useState(todayIso());
  const [dunningResult, setDunningResult] = useState<DunningResultDto | null>(null);

  // ---- reports
  const [from, setFrom] = useState(monthStartIso());
  const [to, setTo] = useState(todayIso());
  const [dayBook, setDayBook] = useState<DayBookDto | null>(null);
  const [outstanding, setOutstanding] = useState<OutstandingReportDto | null>(null);
  const [outstandingGradeId, setOutstandingGradeId] = useState("");

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    if (!hasScreen(s, "fees")) {
      router.replace("/dashboard");
      return;
    }
    setSessionState(s);
    getMe()
      .then((me) => setStaffId(me.subjectId))
      .catch(() => setStaffId(""));
    Promise.all([listFeeHeads(s.schoolId), listAcademicYears(s.schoolId), listGrades(s.schoolId)])
      .then(([hs, ays, gs]) => {
        setHeads(hs);
        setYears(ays);
        setGrades(gs);
        const current = ays.find((y) => y.isCurrent) ?? ays[0];
        if (current) setYearId(current.id);
        if (gs.length > 0) setStructureForm((f) => ({ ...f, gradeId: gs[0].id }));
      })
      .catch((err) => setError(describeError(err)));
  }, [router]);

  useEffect(() => {
    if (!session || !q) {
      setStudents(null);
      return;
    }
    listStudents(session.schoolId, q)
      .then(setStudents)
      .catch((err) => setError(describeError(err)));
  }, [session, q]);

  const refreshStructures = useCallback(() => {
    if (!session || !yearId) return;
    Promise.all([listFeeStructures(session.schoolId, yearId), listSiblingPolicies(session.schoolId, yearId)])
      .then(([ss, sp]) => {
        setStructures(ss);
        setSiblingPolicies(sp);
      })
      .catch((err) => setError(describeError(err)));
  }, [session, yearId]);

  const refreshRuns = useCallback(() => {
    if (!session || !yearId) return;
    listFeeRuns(session.schoolId, yearId).then(setRuns).catch((err) => setError(describeError(err)));
  }, [session, yearId]);

  useEffect(() => {
    refreshStructures();
    refreshRuns();
  }, [refreshStructures, refreshRuns]);

  useEffect(() => {
    if (!session) return;
    getDunningPolicy(session.schoolId)
      .then((p) => {
        setPolicy(p ?? null);
        if (p) {
          setPolicyForm({
            graceDays: p.graceDays,
            reminderDays: p.reminderDays.join(", "),
            lateFeePct: p.lateFeePct == null ? "" : String(p.lateFeePct),
            lateFeeFlat: p.lateFeeFlat == null ? "" : String(p.lateFeeFlat),
            lateFeeHeadId: p.lateFeeHeadId ?? "",
          });
        }
      })
      .catch((err) => setError(describeError(err)));
  }, [session]);

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

  function selectStudent(s: StudentDto) {
    setStudent(s);
    setStudents(null);
    setQ("");
    setExpandedId(null);
    refreshStudent(s.id);
  }

  function refreshStudent(studentId: string) {
    setLoadingInvoices(true);
    setError(null);
    Promise.all([
      listInvoicesForStudent(studentId),
      studentDues(studentId),
      listConcessions(studentId, yearId || undefined),
    ])
      .then(([inv, d, cs]) => {
        setInvoices(inv);
        setDues(d);
        setConcessions(cs);
      })
      .catch((err) => setError(describeError(err)))
      .finally(() => setLoadingInvoices(false));
  }

  async function loadInvoiceDetail(invoiceId: string) {
    const [il, p, adj] = await Promise.all([
      listInvoiceLines(invoiceId),
      listPaymentsForInvoice(invoiceId),
      listAdjustments(invoiceId),
    ]);
    setInvoiceLines(il);
    setPayments(p);
    setAdjustments(adj);
  }

  async function toggleExpand(inv: FeeInvoiceDto) {
    if (expandedId === inv.id) {
      setExpandedId(null);
      return;
    }
    setExpandedId(inv.id);
    setPayAmount("");
    setAdjustForm({ kind: "credit_note", amount: "", reason: "", paymentId: "", feeHeadId: "" });
    setError(null);
    try {
      await loadInvoiceDetail(inv.id);
    } catch (err) {
      setError(describeError(err));
    }
  }

  const invoiceTotals = useMemo(() => {
    const subtotal = lines.reduce((sum, l) => sum + (l.amount - l.discount), 0);
    const gst = lines.reduce((sum, l) => sum + l.gst, 0);
    return { subtotal, gst, total: subtotal + gst };
  }, [lines]);

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
          {tab === "collections" && (
            <input
              placeholder="Search student by name or admission no."
              value={q}
              onChange={(e) => setQ(e.target.value)}
              style={{ minWidth: 280 }}
            />
          )}
        </div>
        {students && students.length > 0 && (
          <table>
            <tbody>
              {students.map((s) => (
                <tr key={s.id} role="button" onClick={() => selectStudent(s)}>
                  <td>{s.admissionNo}</td>
                  <td>
                    {s.firstName} {s.lastName ?? ""}
                  </td>
                  <td>{s.currentSectionLabel ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* ---------------------------------------------------------- collections */}
      {tab === "collections" && student && (
        <>
          <div className="panel">
            <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
              <h2>
                {student.firstName} {student.lastName ?? ""}{" "}
                <span className="hint">({student.admissionNo})</span>
              </h2>
              <button
                type="button"
                onClick={() => setShowInvoiceForm((v) => !v)}
                disabled={!heads || heads.length === 0}
              >
                {showInvoiceForm ? "Cancel" : "One-off invoice"}
              </button>
            </div>
            {dues && (
              <p className="hint">
                Outstanding {inr(dues.balance)}
                {dues.hasDues ? " — year-end clearance reads this number." : " — nothing owed."}
              </p>
            )}
            {concessions && concessions.length > 0 && (
              <p className="hint">
                Concessions:{" "}
                {concessions
                  .map((c) => `${c.kind} ${c.pct != null ? `${c.pct}%` : inr(c.flatAmount ?? 0)}`)
                  .join(", ")}
              </p>
            )}

            {showInvoiceForm && (
              <div>
                <div className="form-row" style={{ flexWrap: "wrap" }}>
                  <input placeholder="Invoice no." value={invoiceNo} onChange={(e) => setInvoiceNo(e.target.value)} />
                  <input
                    placeholder="Cycle (e.g. Q1 2026-27)"
                    value={cycleLabel}
                    onChange={(e) => setCycleLabel(e.target.value)}
                  />
                  <input type="date" value={dueOn} onChange={(e) => setDueOn(e.target.value)} />
                </div>
                {lines.map((line, i) => (
                  <div className="form-row" key={i} style={{ flexWrap: "wrap" }}>
                    <select
                      value={line.feeHeadId}
                      onChange={(e) =>
                        setLines((ls) => ls.map((l, idx) => (idx === i ? { ...l, feeHeadId: e.target.value } : l)))
                      }
                    >
                      <option value="">Fee head…</option>
                      {heads?.map((h) => (
                        <option key={h.id} value={h.id}>
                          {h.name}
                        </option>
                      ))}
                    </select>
                    <input
                      placeholder="Description"
                      value={line.description}
                      onChange={(e) =>
                        setLines((ls) => ls.map((l, idx) => (idx === i ? { ...l, description: e.target.value } : l)))
                      }
                    />
                    <input
                      type="number"
                      placeholder="Amount"
                      value={line.amount}
                      onChange={(e) =>
                        setLines((ls) =>
                          ls.map((l, idx) => (idx === i ? { ...l, amount: Number(e.target.value) } : l))
                        )
                      }
                      style={{ maxWidth: 110 }}
                    />
                    <input
                      type="number"
                      placeholder="Discount"
                      value={line.discount}
                      onChange={(e) =>
                        setLines((ls) =>
                          ls.map((l, idx) => (idx === i ? { ...l, discount: Number(e.target.value) } : l))
                        )
                      }
                      style={{ maxWidth: 110 }}
                    />
                    <input
                      type="number"
                      placeholder="GST"
                      value={line.gst}
                      onChange={(e) =>
                        setLines((ls) => ls.map((l, idx) => (idx === i ? { ...l, gst: Number(e.target.value) } : l)))
                      }
                      style={{ maxWidth: 100 }}
                    />
                    <button
                      type="button"
                      className="secondary"
                      onClick={() => setLines((ls) => ls.filter((_, idx) => idx !== i))}
                      disabled={lines.length === 1}
                    >
                      Remove
                    </button>
                  </div>
                ))}
                <div className="form-row">
                  <button type="button" className="secondary" onClick={() => setLines((ls) => [...ls, { ...emptyLine }])}>
                    + Add line
                  </button>
                  <span className="hint">
                    Subtotal {inr(invoiceTotals.subtotal)} · GST {inr(invoiceTotals.gst)} · Total{" "}
                    {inr(invoiceTotals.total)}
                  </span>
                </div>
                <div className="form-row">
                  <button
                    type="button"
                    disabled={creatingInvoice || !invoiceNo || !cycleLabel || !lines.some((l) => l.feeHeadId)}
                    onClick={async () => {
                      setCreatingInvoice(true);
                      setError(null);
                      try {
                        await createInvoice({
                          schoolId: session.schoolId,
                          studentId: student.id,
                          invoiceNo,
                          cycleLabel,
                          dueOn,
                          lines: lines.filter((l) => l.feeHeadId),
                        });
                        setInvoiceNo("");
                        setCycleLabel("");
                        setLines([{ ...emptyLine }]);
                        setShowInvoiceForm(false);
                        refreshStudent(student.id);
                      } catch (err) {
                        setError(describeError(err));
                      } finally {
                        setCreatingInvoice(false);
                      }
                    }}
                  >
                    {creatingInvoice ? "Creating…" : "Create invoice"}
                  </button>
                </div>
              </div>
            )}

            {loadingInvoices && <p className="hint">Loading…</p>}
            {invoices && invoices.length === 0 && <p className="hint">No invoices for this student yet.</p>}

            {invoices && invoices.length > 0 && (
              <table>
                <thead>
                  <tr>
                    <th>Invoice no.</th>
                    <th>Cycle</th>
                    <th>Due</th>
                    <th>Total</th>
                    <th>Paid</th>
                    <th>Status</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {invoices.map((inv) => (
                    <Fragment key={inv.id}>
                      <tr>
                        <td>{inv.invoiceNo}</td>
                        <td>{inv.cycleLabel}</td>
                        <td>{inv.dueOn}</td>
                        <td>{inr(inv.total)}</td>
                        <td>{inr(inv.paid)}</td>
                        <td>
                          <span className={`badge ${inv.status === "paid" ? "badge-active" : ""}`}>{inv.status}</span>
                        </td>
                        <td>
                          <button type="button" className="secondary" onClick={() => toggleExpand(inv)}>
                            {expandedId === inv.id ? "Hide" : "Details"}
                          </button>
                        </td>
                      </tr>
                      {expandedId === inv.id && (
                        <tr>
                          <td colSpan={7}>
                            <div style={{ padding: "8px 0" }}>
                              <strong>Lines</strong>
                              {invoiceLines && invoiceLines.length > 0 ? (
                                <table>
                                  <thead>
                                    <tr>
                                      <th>Description</th>
                                      <th>Amount</th>
                                      <th>Discount</th>
                                      <th>GST</th>
                                    </tr>
                                  </thead>
                                  <tbody>
                                    {invoiceLines.map((l) => (
                                      <tr key={l.id}>
                                        <td>{l.description ?? "—"}</td>
                                        <td>{inr(l.amount)}</td>
                                        <td>{inr(l.discount)}</td>
                                        <td>{inr(l.gst)}</td>
                                      </tr>
                                    ))}
                                  </tbody>
                                </table>
                              ) : (
                                <p className="hint">No lines.</p>
                              )}

                              <strong>Payments</strong>
                              {payments && payments.length > 0 ? (
                                <table>
                                  <thead>
                                    <tr>
                                      <th>Amount</th>
                                      <th>Method</th>
                                      <th>Status</th>
                                      <th>Captured</th>
                                    </tr>
                                  </thead>
                                  <tbody>
                                    {payments.map((p) => (
                                      <tr key={p.id}>
                                        <td>{inr(p.amount)}</td>
                                        <td>{p.method ?? "—"}</td>
                                        <td>{p.status}</td>
                                        <td>{p.capturedAt ?? "—"}</td>
                                      </tr>
                                    ))}
                                  </tbody>
                                </table>
                              ) : (
                                <p className="hint">No payments yet.</p>
                              )}

                              {inv.status !== "paid" && (
                                <div className="form-row">
                                  <input
                                    type="number"
                                    placeholder="Amount"
                                    value={payAmount}
                                    onChange={(e) => setPayAmount(e.target.value)}
                                    style={{ maxWidth: 120 }}
                                  />
                                  <select value={payMethod} onChange={(e) => setPayMethod(e.target.value)}>
                                    <option value="cash">cash</option>
                                    <option value="upi">upi</option>
                                    <option value="card">card</option>
                                    <option value="bank_transfer">bank_transfer</option>
                                    <option value="cheque">cheque</option>
                                  </select>
                                  <button
                                    type="button"
                                    disabled={payingId === inv.id}
                                    onClick={async () => {
                                      const amount = Number(payAmount);
                                      if (!amount || amount <= 0) {
                                        setError("Enter a valid payment amount.");
                                        return;
                                      }
                                      setPayingId(inv.id);
                                      setError(null);
                                      try {
                                        await recordPayment({
                                          schoolId: session.schoolId,
                                          feeInvoiceId: inv.id,
                                          amount,
                                          gateway: "manual",
                                          method: payMethod,
                                          idempotencyKey: crypto.randomUUID(),
                                        });
                                        setPayAmount("");
                                        await loadInvoiceDetail(inv.id);
                                        refreshStudent(student.id);
                                      } catch (err) {
                                        setError(describeError(err));
                                      } finally {
                                        setPayingId(null);
                                      }
                                    }}
                                  >
                                    {payingId === inv.id ? "Recording…" : "Record payment"}
                                  </button>
                                </div>
                              )}

                              <strong>Adjustments</strong>
                              <p className="hint">
                                A bounced cheque is a reversal, never a deleted payment: the school has to be
                                able to show that the money arrived and went away again. Every kind posts to
                                the ledger, so the accountant&apos;s view and the parent&apos;s move together.
                              </p>
                              {adjustments && adjustments.length > 0 ? (
                                <table>
                                  <thead>
                                    <tr>
                                      <th>Kind</th>
                                      <th>Amount</th>
                                      <th>Reason</th>
                                      <th>When</th>
                                    </tr>
                                  </thead>
                                  <tbody>
                                    {adjustments.map((a) => (
                                      <tr key={a.id}>
                                        <td>{a.kind}</td>
                                        <td>{inr(a.amount)}</td>
                                        <td style={{ whiteSpace: "normal" }}>{a.reason}</td>
                                        <td>{a.createdAt.slice(0, 10)}</td>
                                      </tr>
                                    ))}
                                  </tbody>
                                </table>
                              ) : (
                                <p className="hint">Nothing adjusted on this invoice.</p>
                              )}
                              <div className="form-row" style={{ flexWrap: "wrap" }}>
                                <select
                                  value={adjustForm.kind}
                                  onChange={(e) => setAdjustForm((f) => ({ ...f, kind: e.target.value }))}
                                >
                                  {FEE_ADJUSTMENT_KINDS.map((k) => (
                                    <option key={k} value={k}>
                                      {k}
                                    </option>
                                  ))}
                                </select>
                                <input
                                  type="number"
                                  placeholder="Amount"
                                  value={adjustForm.amount}
                                  onChange={(e) => setAdjustForm((f) => ({ ...f, amount: e.target.value }))}
                                  style={{ maxWidth: 120 }}
                                />
                                <input
                                  placeholder="Reason — it is somebody's decision"
                                  value={adjustForm.reason}
                                  onChange={(e) => setAdjustForm((f) => ({ ...f, reason: e.target.value }))}
                                  style={{ minWidth: 240 }}
                                />
                                {(adjustForm.kind === "charge" || adjustForm.kind === "late_fee") && (
                                  <select
                                    value={adjustForm.feeHeadId}
                                    onChange={(e) => setAdjustForm((f) => ({ ...f, feeHeadId: e.target.value }))}
                                  >
                                    <option value="">Fee head…</option>
                                    {heads?.map((h) => (
                                      <option key={h.id} value={h.id}>
                                        {h.name}
                                      </option>
                                    ))}
                                  </select>
                                )}
                                {(adjustForm.kind === "reversal" || adjustForm.kind === "refund") && (
                                  <select
                                    value={adjustForm.paymentId}
                                    onChange={(e) => setAdjustForm((f) => ({ ...f, paymentId: e.target.value }))}
                                  >
                                    <option value="">Against payment…</option>
                                    {payments?.map((p) => (
                                      <option key={p.id} value={p.id}>
                                        {inr(p.amount)} · {p.method ?? p.gateway} · {p.capturedAt?.slice(0, 10) ?? "—"}
                                      </option>
                                    ))}
                                  </select>
                                )}
                                <button
                                  type="button"
                                  disabled={busy || !adjustForm.amount || !adjustForm.reason.trim()}
                                  onClick={() =>
                                    run(async () => {
                                      await postAdjustment(inv.id, {
                                        schoolId: session.schoolId,
                                        kind: adjustForm.kind,
                                        amount: Number(adjustForm.amount),
                                        reason: adjustForm.reason,
                                        paymentId: adjustForm.paymentId || undefined,
                                        feeHeadId: adjustForm.feeHeadId || undefined,
                                        approvedByStaffId: staffId || undefined,
                                      });
                                      setAdjustForm({
                                        kind: "credit_note",
                                        amount: "",
                                        reason: "",
                                        paymentId: "",
                                        feeHeadId: "",
                                      });
                                      await loadInvoiceDetail(inv.id);
                                      refreshStudent(student.id);
                                      setNotice("Adjustment posted, with its ledger pair.");
                                    })
                                  }
                                >
                                  Post adjustment
                                </button>
                                <span className="hint">{FEE_ADJUSTMENT_EFFECT[adjustForm.kind]}</span>
                              </div>
                            </div>
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  ))}
                </tbody>
              </table>
            )}
          </div>

          <div className="panel">
            <h2>One bill for the household</h2>
            <p className="hint">
              Groups this child with their siblings by their shared guardian, then raises a single invoice
              for the family — which is also what a sibling concession is ranked on.
            </p>
            <div className="form-row">
              <input
                placeholder="Cycle (e.g. Q1 2026-27)"
                value={familyCycle}
                onChange={(e) => setFamilyCycle(e.target.value)}
              />
              <input type="date" value={dueOn} onChange={(e) => setDueOn(e.target.value)} />
              <button
                type="button"
                disabled={busy || !familyCycle}
                onClick={() =>
                  run(async () => {
                    const { familyId } = await linkFamily({
                      schoolId: session.schoolId,
                      studentId: student.id,
                    });
                    const invoice = await createCombinedFamilyInvoice({
                      schoolId: session.schoolId,
                      familyId,
                      cycleLabel: familyCycle,
                      dueOn,
                    });
                    setFamilyCycle("");
                    setNotice(`Combined invoice ${invoice.invoiceNo} raised for ${inr(invoice.total)}.`);
                  })
                }
              >
                Raise combined invoice
              </button>
            </div>
          </div>
        </>
      )}

      {tab === "collections" && !student && (
        <div className="panel">
          <p className="hint">Search for a child above to see their invoices, payments and adjustments.</p>
        </div>
      )}

      {/* ------------------------------------------------ structures & concessions */}
      {tab === "structures" && (
        <>
          <div className="panel">
            <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
              <h2>Fee heads</h2>
              <button type="button" className="secondary" onClick={() => setShowHeadForm((v) => !v)}>
                {showHeadForm ? "Cancel" : "New fee head"}
              </button>
            </div>
            {showHeadForm && (
              <div className="form-row" style={{ flexWrap: "wrap" }}>
                <input
                  placeholder="Code"
                  value={headForm.code}
                  onChange={(e) => setHeadForm((f) => ({ ...f, code: e.target.value }))}
                  style={{ maxWidth: 120 }}
                />
                <input
                  placeholder="Name"
                  value={headForm.name}
                  onChange={(e) => setHeadForm((f) => ({ ...f, name: e.target.value }))}
                />
                <input
                  type="number"
                  placeholder="GST %"
                  value={headForm.gstRatePct}
                  onChange={(e) => setHeadForm((f) => ({ ...f, gstRatePct: Number(e.target.value) }))}
                  style={{ maxWidth: 100 }}
                />
                <input
                  placeholder="HSN/SAC"
                  value={headForm.hsnSac}
                  onChange={(e) => setHeadForm((f) => ({ ...f, hsnSac: e.target.value }))}
                  style={{ maxWidth: 140 }}
                />
                <label className="check">
                  <input
                    type="checkbox"
                    checked={headForm.isRecurring}
                    onChange={(e) => setHeadForm((f) => ({ ...f, isRecurring: e.target.checked }))}
                  />
                  Recurring
                </label>
                <button
                  type="button"
                  disabled={busy || !headForm.code || !headForm.name}
                  onClick={() =>
                    run(async () => {
                      await createFeeHead(session.schoolId, {
                        code: headForm.code,
                        name: headForm.name,
                        isRecurring: headForm.isRecurring,
                        gstRatePct: headForm.gstRatePct,
                        hsnSac: headForm.hsnSac || undefined,
                      });
                      setHeadForm({ code: "", name: "", isRecurring: false, gstRatePct: 0, hsnSac: "" });
                      setShowHeadForm(false);
                      setHeads(await listFeeHeads(session.schoolId));
                    })
                  }
                >
                  Create head
                </button>
              </div>
            )}
            {heads && heads.length > 0 ? (
              <p className="hint">{heads.map((h) => `${h.code} (${h.name}, GST ${h.gstRatePct}%)`).join(", ")}</p>
            ) : (
              <p className="hint">No fee heads yet — create one before building a structure.</p>
            )}
          </div>

          <div className="panel">
            <h2>Structures</h2>
            <p className="hint">
              What a grade is billed, head by head. Next year&apos;s structure is a clone, never an edit —
              last year&apos;s invoices have to keep meaning what they said.
            </p>
            <table>
              <thead>
                <tr>
                  <th>Structure</th>
                  <th>Grade</th>
                  <th>Heads</th>
                  <th>Total</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {structures?.map((s) => (
                  <Fragment key={s.id}>
                    <tr>
                      <td>{s.name}</td>
                      <td>{grades?.find((g) => g.id === s.gradeId)?.name ?? "—"}</td>
                      <td>{s.lines.length}</td>
                      <td>{inr(s.total)}</td>
                      <td>
                        <button
                          type="button"
                          className="secondary"
                          onClick={() => {
                            if (editingStructureId === s.id) {
                              setEditingStructureId(null);
                              return;
                            }
                            setEditingStructureId(s.id);
                            setEditLines(s.lines.map((l) => ({ feeHeadId: l.feeHeadId, amount: l.amount })));
                            setCloneTargetYearId("");
                          }}
                        >
                          {editingStructureId === s.id ? "Hide" : "Open"}
                        </button>
                      </td>
                    </tr>
                    {editingStructureId === s.id && (
                      <tr>
                        <td colSpan={5}>
                          <div style={{ padding: "8px 0" }}>
                            {editLines.map((l, i) => (
                              <div className="form-row" key={i}>
                                <select
                                  value={l.feeHeadId}
                                  onChange={(e) =>
                                    setEditLines((ls) =>
                                      ls.map((x, idx) => (idx === i ? { ...x, feeHeadId: e.target.value } : x))
                                    )
                                  }
                                >
                                  <option value="">Fee head…</option>
                                  {heads?.map((h) => (
                                    <option key={h.id} value={h.id}>
                                      {h.name}
                                    </option>
                                  ))}
                                </select>
                                <input
                                  type="number"
                                  value={l.amount}
                                  onChange={(e) =>
                                    setEditLines((ls) =>
                                      ls.map((x, idx) => (idx === i ? { ...x, amount: Number(e.target.value) } : x))
                                    )
                                  }
                                  style={{ maxWidth: 130 }}
                                />
                                <button
                                  type="button"
                                  className="secondary"
                                  onClick={() => setEditLines((ls) => ls.filter((_, idx) => idx !== i))}
                                >
                                  Remove
                                </button>
                              </div>
                            ))}
                            <div className="form-row">
                              <button
                                type="button"
                                className="secondary"
                                onClick={() => setEditLines((ls) => [...ls, { feeHeadId: "", amount: 0 }])}
                              >
                                + Add line
                              </button>
                              <button
                                type="button"
                                disabled={busy || editLines.some((l) => !l.feeHeadId)}
                                onClick={() =>
                                  run(async () => {
                                    await replaceFeeStructureLines(s.id, editLines);
                                    refreshStructures();
                                    setNotice("Structure updated. Invoices already raised are unchanged.");
                                  })
                                }
                              >
                                Save lines
                              </button>
                            </div>
                            <div className="form-row">
                              <select
                                value={cloneTargetYearId}
                                onChange={(e) => setCloneTargetYearId(e.target.value)}
                              >
                                <option value="">Clone into year…</option>
                                {years
                                  ?.filter((y) => y.id !== s.academicYearId)
                                  .map((y) => (
                                    <option key={y.id} value={y.id}>
                                      {y.code}
                                    </option>
                                  ))}
                              </select>
                              <button
                                type="button"
                                disabled={busy || !cloneTargetYearId}
                                onClick={() =>
                                  run(async () => {
                                    const copy = await cloneFeeStructure(s.id, {
                                      targetAcademicYearId: cloneTargetYearId,
                                    });
                                    setCloneTargetYearId("");
                                    refreshStructures();
                                    setNotice(`Cloned as "${copy.name}" — editing it will not touch this one.`);
                                  })
                                }
                              >
                                Clone
                              </button>
                            </div>
                          </div>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
                {structures?.length === 0 && (
                  <tr>
                    <td colSpan={5} className="hint">
                      No structures for this year — generation has nothing to bill from.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>

            <h2 style={{ marginTop: 16 }}>New structure</h2>
            <div className="form-row">
              <select
                value={structureForm.gradeId}
                onChange={(e) => setStructureForm((f) => ({ ...f, gradeId: e.target.value }))}
              >
                {grades?.map((g) => (
                  <option key={g.id} value={g.id}>
                    {g.name}
                  </option>
                ))}
              </select>
              <input
                placeholder="Name (e.g. Grade 6 — 2026-27)"
                value={structureForm.name}
                onChange={(e) => setStructureForm((f) => ({ ...f, name: e.target.value }))}
              />
            </div>
            {structureLines.map((l, i) => (
              <div className="form-row" key={i}>
                <select
                  value={l.feeHeadId}
                  onChange={(e) =>
                    setStructureLines((ls) =>
                      ls.map((x, idx) => (idx === i ? { ...x, feeHeadId: e.target.value } : x))
                    )
                  }
                >
                  <option value="">Fee head…</option>
                  {heads?.map((h) => (
                    <option key={h.id} value={h.id}>
                      {h.name}
                    </option>
                  ))}
                </select>
                <input
                  type="number"
                  placeholder="Amount"
                  value={l.amount}
                  onChange={(e) =>
                    setStructureLines((ls) =>
                      ls.map((x, idx) => (idx === i ? { ...x, amount: Number(e.target.value) } : x))
                    )
                  }
                  style={{ maxWidth: 130 }}
                />
                <button
                  type="button"
                  className="secondary"
                  disabled={structureLines.length === 1}
                  onClick={() => setStructureLines((ls) => ls.filter((_, idx) => idx !== i))}
                >
                  Remove
                </button>
              </div>
            ))}
            <div className="form-row">
              <button
                type="button"
                className="secondary"
                onClick={() => setStructureLines((ls) => [...ls, { feeHeadId: "", amount: 0 }])}
              >
                + Add line
              </button>
              <button
                type="button"
                disabled={
                  busy ||
                  !yearId ||
                  !structureForm.gradeId ||
                  !structureForm.name ||
                  !structureLines.some((l) => l.feeHeadId)
                }
                onClick={() =>
                  run(async () => {
                    await createFeeStructure({
                      schoolId: session.schoolId,
                      gradeId: structureForm.gradeId,
                      academicYearId: yearId,
                      name: structureForm.name,
                      lines: structureLines.filter((l) => l.feeHeadId),
                    });
                    setStructureForm((f) => ({ ...f, name: "" }));
                    setStructureLines([{ feeHeadId: "", amount: 0 }]);
                    refreshStructures();
                    setNotice("Structure created.");
                  })
                }
              >
                Create structure
              </button>
            </div>
          </div>

          <div className="panel">
            <h2>Sibling policy</h2>
            <p className="hint">
              The nth child of a family pays a percentage less. Ranking is by admission date, so a younger
              sibling joining never re-prices the eldest.
            </p>
            <table>
              <thead>
                <tr>
                  <th>Child</th>
                  <th>Discount</th>
                  <th>Applies to</th>
                </tr>
              </thead>
              <tbody>
                {siblingPolicies?.map((p) => (
                  <tr key={p.id}>
                    <td>#{p.nthChild}</td>
                    <td>{p.pct}%</td>
                    <td>{heads?.find((h) => h.id === p.appliesToHeadId)?.name ?? "every head"}</td>
                  </tr>
                ))}
                {siblingPolicies?.length === 0 && (
                  <tr>
                    <td colSpan={3} className="hint">
                      No sibling discount this year.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
            <div className="form-row">
              <input
                type="number"
                min={2}
                value={siblingForm.nthChild}
                onChange={(e) => setSiblingForm((f) => ({ ...f, nthChild: Number(e.target.value) }))}
                style={{ maxWidth: 100 }}
                title="Nth child"
              />
              <input
                type="number"
                value={siblingForm.pct}
                onChange={(e) => setSiblingForm((f) => ({ ...f, pct: Number(e.target.value) }))}
                style={{ maxWidth: 100 }}
                title="Percent off"
              />
              <select
                value={siblingForm.appliesToHeadId}
                onChange={(e) => setSiblingForm((f) => ({ ...f, appliesToHeadId: e.target.value }))}
              >
                <option value="">Every head</option>
                {heads?.map((h) => (
                  <option key={h.id} value={h.id}>
                    {h.name}
                  </option>
                ))}
              </select>
              <button
                type="button"
                disabled={busy || !yearId}
                onClick={() =>
                  run(async () => {
                    await upsertSiblingPolicy({
                      schoolId: session.schoolId,
                      academicYearId: yearId,
                      nthChild: siblingForm.nthChild,
                      pct: siblingForm.pct,
                      appliesToHeadId: siblingForm.appliesToHeadId || undefined,
                    });
                    refreshStructures();
                    setNotice("Sibling policy saved — it applies from the next generation run.");
                  })
                }
              >
                Save policy
              </button>
            </div>
          </div>

          <div className="panel">
            <h2>Concession for one child</h2>
            {student ? (
              <>
                <p className="hint">
                  A concession is a decision about {student.firstName}&apos;s bill, so it is recorded against
                  them with who approved it — and it shows on the invoice as a discount line rather than a
                  quietly smaller number.
                </p>
                <div className="form-row" style={{ flexWrap: "wrap" }}>
                  <input
                    placeholder="Kind (e.g. staff_ward, scholarship)"
                    value={concessionForm.kind}
                    onChange={(e) => setConcessionForm((f) => ({ ...f, kind: e.target.value }))}
                  />
                  <input
                    type="number"
                    placeholder="%"
                    value={concessionForm.pct}
                    onChange={(e) => setConcessionForm((f) => ({ ...f, pct: e.target.value }))}
                    style={{ maxWidth: 100 }}
                  />
                  <input
                    type="number"
                    placeholder="Flat ₹"
                    value={concessionForm.flatAmount}
                    onChange={(e) => setConcessionForm((f) => ({ ...f, flatAmount: e.target.value }))}
                    style={{ maxWidth: 120 }}
                  />
                  <select
                    value={concessionForm.appliesToHeadId}
                    onChange={(e) => setConcessionForm((f) => ({ ...f, appliesToHeadId: e.target.value }))}
                  >
                    <option value="">Every head</option>
                    {heads?.map((h) => (
                      <option key={h.id} value={h.id}>
                        {h.name}
                      </option>
                    ))}
                  </select>
                  <input
                    placeholder="Notes"
                    value={concessionForm.notes}
                    onChange={(e) => setConcessionForm((f) => ({ ...f, notes: e.target.value }))}
                  />
                  <button
                    type="button"
                    disabled={busy || !yearId || (!concessionForm.pct && !concessionForm.flatAmount)}
                    onClick={() =>
                      run(async () => {
                        await grantConcession({
                          schoolId: session.schoolId,
                          studentId: student.id,
                          academicYearId: yearId,
                          kind: concessionForm.kind,
                          pct: concessionForm.pct ? Number(concessionForm.pct) : undefined,
                          flatAmount: concessionForm.flatAmount ? Number(concessionForm.flatAmount) : undefined,
                          appliesToHeadId: concessionForm.appliesToHeadId || undefined,
                          notes: concessionForm.notes || undefined,
                          approvedByStaffId: staffId || undefined,
                        });
                        setConcessionForm({
                          kind: "staff_ward",
                          pct: "",
                          flatAmount: "",
                          appliesToHeadId: "",
                          notes: "",
                        });
                        refreshStudent(student.id);
                        setNotice("Concession granted — it lands as a visible line on the next invoice.");
                      })
                    }
                  >
                    Grant
                  </button>
                </div>
              </>
            ) : (
              <p className="hint">Pick a child on the Collections tab first.</p>
            )}
          </div>
        </>
      )}

      {/* ---------------------------------------------------------- generation */}
      {tab === "generation" && (
        <>
          <div className="panel">
            <h2>Bill a cycle</h2>
            <p className="hint">
              Each invoice is assembled from the grade&apos;s structure, the child&apos;s transport
              assignment, their own concessions and their sibling rank — every discount a visible line.
              Running the same cycle twice is a no-op with the first run&apos;s numbers, so a retry after a
              crash is safe.
            </p>
            <div className="form-row">
              <select value={runForm.gradeId} onChange={(e) => setRunForm((f) => ({ ...f, gradeId: e.target.value }))}>
                <option value="">Whole school</option>
                {grades?.map((g) => (
                  <option key={g.id} value={g.id}>
                    {g.name}
                  </option>
                ))}
              </select>
              <input
                placeholder="Cycle (e.g. Q1 2026-27)"
                value={runForm.cycleLabel}
                onChange={(e) => setRunForm((f) => ({ ...f, cycleLabel: e.target.value }))}
              />
              <input
                type="date"
                value={runForm.dueOn}
                onChange={(e) => setRunForm((f) => ({ ...f, dueOn: e.target.value }))}
              />
              <button
                type="button"
                disabled={busy || !yearId || !runForm.cycleLabel}
                onClick={() =>
                  run(async () => {
                    const result = await generateInvoices({
                      schoolId: session.schoolId,
                      academicYearId: yearId,
                      gradeId: runForm.gradeId || undefined,
                      cycleLabel: runForm.cycleLabel,
                      dueOn: runForm.dueOn,
                      runByStaffId: staffId || undefined,
                    });
                    setLastRun(result);
                    refreshRuns();
                    setNotice(
                      result.alreadyRun
                        ? `${runForm.cycleLabel} was billed before — nothing written. ${result.invoicesCreated} invoices, ${inr(result.totalBilled)}.`
                        : `Billed ${result.invoicesCreated} invoices for ${inr(result.totalBilled)}; ${result.studentsSkipped} skipped.`
                    );
                  })
                }
              >
                Run generation
              </button>
            </div>
            {lastRun && (
              <p className="hint">
                Due dates land on the next working day, so a cycle due on a holiday does not turn a family
                overdue for the school being shut.
              </p>
            )}
          </div>

          <div className="panel">
            <h2>Runs this year</h2>
            <table>
              <thead>
                <tr>
                  <th>Cycle</th>
                  <th>Cohort</th>
                  <th>Due</th>
                  <th>Invoices</th>
                  <th>Skipped</th>
                  <th>Billed</th>
                  <th>State</th>
                  <th>Run on</th>
                </tr>
              </thead>
              <tbody>
                {runs?.map((r) => (
                  <tr key={r.id}>
                    <td>{r.cycleLabel}</td>
                    <td>{r.gradeCode ?? "whole school"}</td>
                    <td>{r.dueOn}</td>
                    <td>{r.invoicesCreated}</td>
                    <td>{r.studentsSkipped}</td>
                    <td>{inr(r.totalBilled)}</td>
                    <td>
                      <span className={"badge " + (r.state === "completed" ? "badge-active" : "")}>{r.state}</span>
                    </td>
                    <td>{r.createdAt.slice(0, 10)}</td>
                  </tr>
                ))}
                {runs?.length === 0 && (
                  <tr>
                    <td colSpan={8} className="hint">
                      Nothing billed for this year yet.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </>
      )}

      {/* ------------------------------------------------------------- dunning */}
      {tab === "dunning" && (
        <>
          <div className="panel">
            <h2>Cadence</h2>
            <p className="hint">
              How long after the due date an invoice turns overdue, when reminders go out, and what a late
              fee costs. Dunning writes to families, so this is a relationship decision, not a technical one.
            </p>
            {!policy && (
              <div className="warn-banner">
                No policy set — the nightly job leaves this school&apos;s invoices alone.
              </div>
            )}
            <div className="form-row" style={{ flexWrap: "wrap" }}>
              <input
                type="number"
                min={0}
                value={policyForm.graceDays}
                onChange={(e) => setPolicyForm((f) => ({ ...f, graceDays: Number(e.target.value) }))}
                style={{ maxWidth: 110 }}
                title="Grace days"
              />
              <input
                placeholder="Reminder days after due (1, 7, 15)"
                value={policyForm.reminderDays}
                onChange={(e) => setPolicyForm((f) => ({ ...f, reminderDays: e.target.value }))}
                style={{ minWidth: 240 }}
              />
              <input
                type="number"
                placeholder="Late fee %"
                value={policyForm.lateFeePct}
                onChange={(e) => setPolicyForm((f) => ({ ...f, lateFeePct: e.target.value }))}
                style={{ maxWidth: 120 }}
              />
              <input
                type="number"
                placeholder="Late fee ₹"
                value={policyForm.lateFeeFlat}
                onChange={(e) => setPolicyForm((f) => ({ ...f, lateFeeFlat: e.target.value }))}
                style={{ maxWidth: 120 }}
              />
              <select
                value={policyForm.lateFeeHeadId}
                onChange={(e) => setPolicyForm((f) => ({ ...f, lateFeeHeadId: e.target.value }))}
              >
                <option value="">Late fee head…</option>
                {heads?.map((h) => (
                  <option key={h.id} value={h.id}>
                    {h.name}
                  </option>
                ))}
              </select>
              <button
                type="button"
                disabled={busy}
                onClick={() =>
                  run(async () => {
                    const days = policyForm.reminderDays
                      .split(",")
                      .map((d) => Number(d.trim()))
                      .filter((d) => Number.isFinite(d) && d > 0);
                    await saveDunningPolicy({
                      schoolId: session.schoolId,
                      graceDays: policyForm.graceDays,
                      reminderDays: days,
                      lateFeePct: policyForm.lateFeePct ? Number(policyForm.lateFeePct) : undefined,
                      lateFeeFlat: policyForm.lateFeeFlat ? Number(policyForm.lateFeeFlat) : undefined,
                      lateFeeHeadId: policyForm.lateFeeHeadId || undefined,
                    });
                    setPolicy((await getDunningPolicy(session.schoolId)) ?? null);
                    setNotice("Cadence saved.");
                  })
                }
              >
                Save cadence
              </button>
            </div>
          </div>

          <div className="panel">
            <h2>Run a pass</h2>
            <p className="hint">
              The nightly job does this at 6:30. Running it for a chosen date marks overdue invoices, sends
              the reminders that day&apos;s cadence calls for, and applies a late fee after grace. A family
              never gets the same reminder twice, whatever you do here.
            </p>
            <div className="form-row">
              <input type="date" value={dunningDate} onChange={(e) => setDunningDate(e.target.value)} />
              <button
                type="button"
                disabled={busy}
                onClick={() =>
                  run(async () => {
                    const result = await runDunning({ schoolId: session.schoolId, asOf: dunningDate });
                    setDunningResult(result);
                    setNotice(
                      `${result.markedOverdue} marked overdue, ${result.remindersSent} reminders sent, ${result.lateFeesApplied} late fees applied.`
                    );
                  })
                }
              >
                Run for this date
              </button>
            </div>
            {dunningResult && (
              <div className="stat-grid">
                <div className="stat-tile">
                  <div className="value">{dunningResult.markedOverdue}</div>
                  <div className="label">Marked overdue</div>
                </div>
                <div className="stat-tile">
                  <div className="value">{dunningResult.remindersSent}</div>
                  <div className="label">Reminders sent</div>
                </div>
                <div className="stat-tile">
                  <div className="value">{dunningResult.lateFeesApplied}</div>
                  <div className="label">Late fees applied</div>
                </div>
              </div>
            )}
          </div>
        </>
      )}

      {/* ------------------------------------------------------------- reports */}
      {tab === "reports" && (
        <>
          <div className="panel">
            <h2>Day book</h2>
            <p className="hint">
              What came in, counted by when the money arrived rather than by a payment&apos;s current status
              — a cheque that bounced next week was still banked today, and the reversal sits in its own
              column. The ledger&apos;s own bank movement is computed separately, so a mismatch shows here.
            </p>
            <div className="form-row">
              <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
              <input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
              <button
                type="button"
                disabled={busy}
                onClick={() =>
                  run(async () => {
                    setDayBook(await feeDayBook(session.schoolId, from, to));
                  })
                }
              >
                Run day book
              </button>
            </div>
            {dayBook && (
              <>
                {!dayBook.reconciles && (
                  <div className="warn-banner">
                    Collections {inr(dayBook.net)} do not match the ledger&apos;s bank movement{" "}
                    {inr(dayBook.ledgerNet)}. Something was posted outside the payment path.
                  </div>
                )}
                <div className="stat-grid">
                  <div className="stat-tile">
                    <div className="value">{inr(dayBook.collected)}</div>
                    <div className="label">Collected</div>
                  </div>
                  <div className="stat-tile">
                    <div className="value">{inr(dayBook.refunded)}</div>
                    <div className="label">Refunded / reversed</div>
                  </div>
                  <div className="stat-tile">
                    <div className="value">{inr(dayBook.net)}</div>
                    <div className="label">Net</div>
                  </div>
                  <div className="stat-tile">
                    <div className="value">{inr(dayBook.ledgerNet)}</div>
                    <div className="label">Ledger bank movement</div>
                  </div>
                </div>
                <table>
                  <thead>
                    <tr>
                      <th>Method</th>
                      <th>Receipts</th>
                      <th>Amount</th>
                    </tr>
                  </thead>
                  <tbody>
                    {dayBook.byMethod.map((m) => (
                      <tr key={m.method}>
                        <td>{m.method}</td>
                        <td>{m.count}</td>
                        <td>{inr(m.amount)}</td>
                      </tr>
                    ))}
                    {dayBook.byMethod.length === 0 && (
                      <tr>
                        <td colSpan={3} className="hint">
                          Nothing banked in this range.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </>
            )}
          </div>

          <div className="panel">
            <h2>Outstanding dues</h2>
            <p className="hint">
              The same numbers at two levels: a principal reads the grade rows, year-end clearance reads the
              student rows, so the two can never disagree.
            </p>
            <div className="form-row">
              <select value={outstandingGradeId} onChange={(e) => setOutstandingGradeId(e.target.value)}>
                <option value="">All grades</option>
                {grades?.map((g) => (
                  <option key={g.id} value={g.id}>
                    {g.name}
                  </option>
                ))}
              </select>
              <button
                type="button"
                disabled={busy}
                onClick={() =>
                  run(async () => {
                    setOutstanding(
                      await feeOutstanding(session.schoolId, {
                        academicYearId: yearId || undefined,
                        gradeId: outstandingGradeId || undefined,
                      })
                    );
                  })
                }
              >
                Run report
              </button>
            </div>
            {outstanding && (
              <>
                <div className="stat-grid">
                  <div className="stat-tile">
                    <div className="value">{inr(outstanding.totalOutstanding)}</div>
                    <div className="label">Total outstanding</div>
                  </div>
                  <div className="stat-tile">
                    <div className="value">{outstanding.studentsWithDues}</div>
                    <div className="label">Children with dues</div>
                  </div>
                </div>
                <table>
                  <thead>
                    <tr>
                      <th>Grade</th>
                      <th>Children</th>
                      <th>Balance</th>
                    </tr>
                  </thead>
                  <tbody>
                    {outstanding.byGrade.map((g) => (
                      <tr key={g.gradeCode}>
                        <td>{g.gradeCode}</td>
                        <td>{g.students}</td>
                        <td>{inr(g.balance)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <table>
                  <thead>
                    <tr>
                      <th>Admission no.</th>
                      <th>Child</th>
                      <th>Class</th>
                      <th>Invoices</th>
                      <th>Oldest due</th>
                      <th>Balance</th>
                    </tr>
                  </thead>
                  <tbody>
                    {outstanding.students.map((s) => (
                      <tr key={s.studentId}>
                        <td>{s.admissionNo}</td>
                        <td>{s.name}</td>
                        <td>
                          {s.gradeCode ?? "—"}
                          {s.sectionCode ? `-${s.sectionCode}` : ""}
                        </td>
                        <td>{s.invoices}</td>
                        <td>{s.oldestDueOn}</td>
                        <td>{inr(s.balance)}</td>
                      </tr>
                    ))}
                    {outstanding.students.length === 0 && (
                      <tr>
                        <td colSpan={6} className="hint">
                          Nobody owes anything here.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </>
            )}
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
