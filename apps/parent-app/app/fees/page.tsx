"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  adjustmentsForInvoice,
  ApiError,
  duesForStudent,
  FeeAdjustmentDto,
  FeeInvoiceDto,
  FeeInvoiceLineDto,
  getSession,
  listInvoiceLines,
  listInvoicesForStudent,
  listPaymentsForInvoice,
  PaymentDto,
  Session,
  StudentDto,
  studentsOfGuardian,
} from "@/lib/api";

/** What each kind means to the family, in their words rather than the ledger's. */
const ADJUSTMENT_WORDING: Record<string, string> = {
  credit_note: "Credit — reduces what is owed",
  waiver: "Waived by the school",
  charge: "Added charge",
  late_fee: "Late fee",
  reversal: "Payment reversed (a cheque did not clear)",
  refund: "Refunded to you",
};

function inr(n: number): string {
  return n.toLocaleString(undefined, { style: "currency", currency: "INR", maximumFractionDigits: 2 });
}

export default function FeesPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [children, setChildren] = useState<StudentDto[] | null>(null);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [invoices, setInvoices] = useState<FeeInvoiceDto[] | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [lines, setLines] = useState<FeeInvoiceLineDto[] | null>(null);
  const [payments, setPayments] = useState<PaymentDto[] | null>(null);
  const [adjustments, setAdjustments] = useState<FeeAdjustmentDto[] | null>(null);
  const [dues, setDues] = useState<{ balance: number; hasDues: boolean } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    setSessionState(s);
    if (!s.subjectId) {
      setLoading(false);
      return;
    }
    studentsOfGuardian(s.subjectId)
      .then((kids) => {
        setChildren(kids);
        if (kids.length > 0) setActiveId(kids[0].id);
      })
      .catch((err) => setError(describeError(err)))
      .finally(() => setLoading(false));
  }, [router]);

  useEffect(() => {
    if (!activeId) return;
    setExpandedId(null);
    setDues(null);
    listInvoicesForStudent(activeId)
      .then(setInvoices)
      .catch((err) => setError(describeError(err)));
    duesForStudent(activeId)
      .then(setDues)
      .catch((err) => setError(describeError(err)));
  }, [activeId]);

  async function toggleExpand(inv: FeeInvoiceDto) {
    if (expandedId === inv.id) {
      setExpandedId(null);
      return;
    }
    setExpandedId(inv.id);
    setError(null);
    try {
      const [il, p, adj] = await Promise.all([
        listInvoiceLines(inv.id),
        listPaymentsForInvoice(inv.id),
        adjustmentsForInvoice(inv.id),
      ]);
      setLines(il);
      setPayments(p);
      setAdjustments(adj);
    } catch (err) {
      setError(describeError(err));
    }
  }

  const expanded = invoices?.find((i) => i.id === expandedId) ?? null;

  if (!session) return null;

  if (!session.subjectId) {
    return (
      <main className="shell">
        <div className="panel">
          <h2>Fees</h2>
          <p className="hint">This account isn&apos;t linked to a guardian record.</p>
        </div>
      </main>
    );
  }

  return (
    <main className="shell">
      {loading && (
        <div className="panel">
          <p className="hint">Loading…</p>
        </div>
      )}
      {error && <div className="error-banner">{error}</div>}

      {children && children.length > 1 && (
        <div className="chip-row">
          {children.map((c) => (
            <button
              key={c.id}
              type="button"
              className={"chip-btn" + (c.id === activeId ? " active" : "")}
              onClick={() => setActiveId(c.id)}
            >
              {c.firstName}
            </button>
          ))}
        </div>
      )}

      {children && children.length === 0 && (
        <div className="panel">
          <p className="empty-note">No children linked to this account yet.</p>
        </div>
      )}

      {dues && (
        <div className="panel">
          <h2>{dues.hasDues ? `Outstanding ${inr(dues.balance)}` : "Nothing outstanding"}</h2>
          <p className="hint">
            {dues.hasDues
              ? "Across every unpaid invoice below, after any credit or waiver the school has applied."
              : "Every invoice is settled."}
          </p>
        </div>
      )}

      <div className="pane-split">
        <div className="pane-list">
          {invoices && invoices.length === 0 && (
            <div className="panel">
              <p className="empty-note">No invoices yet.</p>
            </div>
          )}

          {invoices?.map((inv) => (
            <button
              key={inv.id}
              type="button"
              className={"panel-btn" + (expandedId === inv.id ? " active" : "")}
              onClick={() => toggleExpand(inv)}
            >
              <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
                <div>
                  <div>{inv.invoiceNo}</div>
                  <div className="list-row-sub">
                    {inv.cycleLabel} · due {inv.dueOn}
                  </div>
                </div>
                <div style={{ textAlign: "right" }}>
                  <div>{inr(inv.total)}</div>
                  <span className={`badge ${inv.status === "paid" ? "badge-active" : ""}`}>{inv.status}</span>
                </div>
              </div>
            </button>
          ))}
        </div>

        <div className="pane-detail">
          {expanded && (
            <div className="panel">
              <h2>{expanded.invoiceNo}</h2>
              <p className="hint">
                {expanded.cycleLabel} · due {expanded.dueOn} · {inr(expanded.total)}
              </p>

              <h2 style={{ marginTop: 16 }}>Lines</h2>
              {lines && lines.length > 0 ? (
                <table>
                  <thead>
                    <tr>
                      <th>Description</th>
                      <th>Amount</th>
                      <th>GST</th>
                    </tr>
                  </thead>
                  <tbody>
                    {lines.map((l) => (
                      <tr key={l.id}>
                        <td>{l.description ?? "—"}</td>
                        <td>{inr(l.amount)}</td>
                        <td>{inr(l.gst)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              ) : (
                <p className="hint">No lines.</p>
              )}

              <h2 style={{ marginTop: 16 }}>Payments</h2>
              {payments && payments.length > 0 ? (
                <table>
                  <thead>
                    <tr>
                      <th>Amount</th>
                      <th>Method</th>
                      <th>Date</th>
                    </tr>
                  </thead>
                  <tbody>
                    {payments.map((p) => (
                      <tr key={p.id}>
                        <td>{inr(p.amount)}</td>
                        <td>{p.method ?? "—"}</td>
                        <td>{p.capturedAt?.slice(0, 10) ?? "—"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              ) : (
                <p className="hint">No payments yet.</p>
              )}

              {adjustments && adjustments.length > 0 && (
                <>
                  <h2 style={{ marginTop: 16 }}>Changes since this bill was issued</h2>
                  <p className="hint">
                    Why the amount moved. Nothing here is deleted — a cheque that did not clear shows as a
                    reversal, so the payment and its undoing are both on the record.
                  </p>
                  <table>
                    <thead>
                      <tr>
                        <th>What</th>
                        <th>Amount</th>
                        <th>Reason</th>
                        <th>Date</th>
                      </tr>
                    </thead>
                    <tbody>
                      {adjustments.map((a) => (
                        <tr key={a.id}>
                          <td>{ADJUSTMENT_WORDING[a.kind] ?? a.kind}</td>
                          <td>{inr(a.amount)}</td>
                          <td>{a.reason}</td>
                          <td>{a.createdAt.slice(0, 10)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </>
              )}
            </div>
          )}
        </div>
      </div>
    </main>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}
