"use client";

import { Fragment, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  createFeeHead,
  createInvoice,
  FeeHeadDto,
  FeeInvoiceDto,
  FeeInvoiceLineDto,
  getSession,
  InvoiceLineInput,
  listFeeHeads,
  listInvoiceLines,
  listInvoicesForStudent,
  listPaymentsForInvoice,
  listStudents,
  PaymentDto,
  recordPayment,
  Session,
  StudentDto,
} from "@/lib/api";

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

function inr(n: number): string {
  return n.toLocaleString(undefined, { style: "currency", currency: "INR", maximumFractionDigits: 2 });
}

const emptyLine: InvoiceLineInput = { feeHeadId: "", description: "", amount: 0, discount: 0, gst: 0 };

export default function FeesPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [heads, setHeads] = useState<FeeHeadDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [q, setQ] = useState("");
  const [students, setStudents] = useState<StudentDto[] | null>(null);
  const [student, setStudent] = useState<StudentDto | null>(null);

  const [invoices, setInvoices] = useState<FeeInvoiceDto[] | null>(null);
  const [loadingInvoices, setLoadingInvoices] = useState(false);

  const [showHeadForm, setShowHeadForm] = useState(false);
  const [headForm, setHeadForm] = useState({ code: "", name: "", isRecurring: false, gstRatePct: 0, hsnSac: "" });
  const [creatingHead, setCreatingHead] = useState(false);

  const [showInvoiceForm, setShowInvoiceForm] = useState(false);
  const [invoiceNo, setInvoiceNo] = useState("");
  const [cycleLabel, setCycleLabel] = useState("");
  const [dueOn, setDueOn] = useState(todayIso());
  const [lines, setLines] = useState<InvoiceLineInput[]>([{ ...emptyLine }]);
  const [creatingInvoice, setCreatingInvoice] = useState(false);

  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [invoiceLines, setInvoiceLines] = useState<FeeInvoiceLineDto[] | null>(null);
  const [payments, setPayments] = useState<PaymentDto[] | null>(null);
  const [payAmount, setPayAmount] = useState("");
  const [payMethod, setPayMethod] = useState("cash");
  const [payingId, setPayingId] = useState<string | null>(null);

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    setSessionState(s);
    listFeeHeads(s.schoolId)
      .then(setHeads)
      .catch((err) => setError(describeError(err)));
  }, [router]);

  useEffect(() => {
    if (!session) return;
    if (!q) {
      setStudents(null);
      return;
    }
    listStudents(session.schoolId, q)
      .then(setStudents)
      .catch((err) => setError(describeError(err)));
  }, [session, q]);

  function selectStudent(s: StudentDto) {
    setStudent(s);
    setStudents(null);
    setQ("");
    setExpandedId(null);
    refreshInvoices(s.id);
  }

  function refreshInvoices(studentId: string) {
    setLoadingInvoices(true);
    setError(null);
    listInvoicesForStudent(studentId)
      .then(setInvoices)
      .catch((err) => setError(describeError(err)))
      .finally(() => setLoadingInvoices(false));
  }

  async function onCreateHead() {
    if (!session) return;
    setCreatingHead(true);
    setError(null);
    try {
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
    } catch (err) {
      setError(describeError(err));
    } finally {
      setCreatingHead(false);
    }
  }

  function updateLine(i: number, patch: Partial<InvoiceLineInput>) {
    setLines((ls) => ls.map((l, idx) => (idx === i ? { ...l, ...patch } : l)));
  }

  function addLine() {
    setLines((ls) => [...ls, { ...emptyLine }]);
  }

  function removeLine(i: number) {
    setLines((ls) => ls.filter((_, idx) => idx !== i));
  }

  const invoiceTotals = useMemo(() => {
    const subtotal = lines.reduce((sum, l) => sum + (l.amount - l.discount), 0);
    const gst = lines.reduce((sum, l) => sum + l.gst, 0);
    return { subtotal, gst, total: subtotal + gst };
  }, [lines]);

  async function onCreateInvoice() {
    if (!session || !student) return;
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
      refreshInvoices(student.id);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setCreatingInvoice(false);
    }
  }

  async function toggleExpand(inv: FeeInvoiceDto) {
    if (expandedId === inv.id) {
      setExpandedId(null);
      return;
    }
    setExpandedId(inv.id);
    setPayAmount("");
    setError(null);
    try {
      const [il, p] = await Promise.all([listInvoiceLines(inv.id), listPaymentsForInvoice(inv.id)]);
      setInvoiceLines(il);
      setPayments(p);
    } catch (err) {
      setError(describeError(err));
    }
  }

  async function onRecordPayment(inv: FeeInvoiceDto) {
    if (!session) return;
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
      const [il, p] = await Promise.all([listInvoiceLines(inv.id), listPaymentsForInvoice(inv.id)]);
      setInvoiceLines(il);
      setPayments(p);
      if (student) refreshInvoices(student.id);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setPayingId(null);
    }
  }

  if (!session) return null;

  return (
    <main className="shell">
      <div className="panel">
        <h2>Fees</h2>
        <div className="form-row">
          <input
            placeholder="Search student by name or admission no."
            value={q}
            onChange={(e) => setQ(e.target.value)}
            style={{ minWidth: 280 }}
          />
        </div>
        {students && students.length > 0 && (
          <table>
            <tbody>
              {students.map((s) => (
                <tr key={s.id} style={{ cursor: "pointer" }} onClick={() => selectStudent(s)}>
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
        {error && <div className="error-banner">{error}</div>}
      </div>

      <div className="panel">
        <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
          <h2>Fee heads</h2>
          <button type="button" onClick={() => setShowHeadForm((v) => !v)}>
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
            <label className="form-row" style={{ alignItems: "center", gap: 4 }}>
              <input
                type="checkbox"
                checked={headForm.isRecurring}
                onChange={(e) => setHeadForm((f) => ({ ...f, isRecurring: e.target.checked }))}
              />
              Recurring
            </label>
            <button type="button" onClick={onCreateHead} disabled={creatingHead || !headForm.code || !headForm.name}>
              {creatingHead ? "Creating…" : "Create head"}
            </button>
          </div>
        )}
        {heads && heads.length === 0 && <p className="hint">No fee heads yet — create one before raising invoices.</p>}
        {heads && heads.length > 0 && (
          <p className="hint">{heads.map((h) => `${h.code} (${h.name})`).join(", ")}</p>
        )}
      </div>

      {student && (
        <div className="panel">
          <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
            <h2>
              Invoices — {student.firstName} {student.lastName ?? ""}{" "}
              <span className="hint">({student.admissionNo})</span>
            </h2>
            <button type="button" onClick={() => setShowInvoiceForm((v) => !v)} disabled={!heads || heads.length === 0}>
              {showInvoiceForm ? "Cancel" : "New invoice"}
            </button>
          </div>

          {showInvoiceForm && (
            <div>
              <div className="form-row" style={{ flexWrap: "wrap" }}>
                <input
                  placeholder="Invoice no."
                  value={invoiceNo}
                  onChange={(e) => setInvoiceNo(e.target.value)}
                />
                <input
                  placeholder="Cycle (e.g. Q1 2026-27)"
                  value={cycleLabel}
                  onChange={(e) => setCycleLabel(e.target.value)}
                />
                <input type="date" value={dueOn} onChange={(e) => setDueOn(e.target.value)} />
              </div>
              {lines.map((line, i) => (
                <div className="form-row" key={i} style={{ flexWrap: "wrap" }}>
                  <select value={line.feeHeadId} onChange={(e) => updateLine(i, { feeHeadId: e.target.value })}>
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
                    onChange={(e) => updateLine(i, { description: e.target.value })}
                  />
                  <input
                    type="number"
                    placeholder="Amount"
                    value={line.amount}
                    onChange={(e) => updateLine(i, { amount: Number(e.target.value) })}
                    style={{ maxWidth: 110 }}
                  />
                  <input
                    type="number"
                    placeholder="Discount"
                    value={line.discount}
                    onChange={(e) => updateLine(i, { discount: Number(e.target.value) })}
                    style={{ maxWidth: 110 }}
                  />
                  <input
                    type="number"
                    placeholder="GST"
                    value={line.gst}
                    onChange={(e) => updateLine(i, { gst: Number(e.target.value) })}
                    style={{ maxWidth: 100 }}
                  />
                  <button type="button" onClick={() => removeLine(i)} disabled={lines.length === 1}>
                    Remove
                  </button>
                </div>
              ))}
              <div className="form-row">
                <button type="button" onClick={addLine}>
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
                  onClick={onCreateInvoice}
                  disabled={creatingInvoice || !invoiceNo || !cycleLabel || !lines.some((l) => l.feeHeadId)}
                >
                  {creatingInvoice ? "Creating…" : "Create invoice"}
                </button>
              </div>
            </div>
          )}

          {loadingInvoices && <p className="hint">Loading invoices…</p>}
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
                        <button type="button" onClick={() => toggleExpand(inv)}>
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
                                  onClick={() => onRecordPayment(inv)}
                                  disabled={payingId === inv.id}
                                >
                                  {payingId === inv.id ? "Recording…" : "Record payment"}
                                </button>
                              </div>
                            )}
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
      )}
    </main>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}
