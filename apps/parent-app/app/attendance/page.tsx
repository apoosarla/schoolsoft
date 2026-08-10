"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  applyLeave,
  AttendanceRecordDto,
  attendanceHistoryForStudent,
  getSession,
  Session,
  StudentDto,
  studentsOfGuardian,
} from "@/lib/api";

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

function daysAgoIso(n: number): string {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return d.toISOString().slice(0, 10);
}

function statusBadgeClass(status: string): string {
  return status === "present" ? "badge-active" : status === "absent" ? "badge-suspended" : "";
}

export default function AttendanceHistoryPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [children, setChildren] = useState<StudentDto[] | null>(null);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [history, setHistory] = useState<AttendanceRecordDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [fromDate, setFromDate] = useState(daysAgoIso(30));
  const [toDate, setToDate] = useState(daysAgoIso(0));
  const [leaveTo, setLeaveTo] = useState(todayIso());
  const [reason, setReason] = useState("");
  const [applying, setApplying] = useState(false);
  const [leaveMessage, setLeaveMessage] = useState<string | null>(null);

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    setSessionState(s);
    if (!s.subjectId) return;
    studentsOfGuardian(s.subjectId)
      .then((kids) => {
        setChildren(kids);
        if (kids.length > 0) setActiveId(kids[0].id);
      })
      .catch((err) => setError(describeError(err)));
  }, [router]);

  useEffect(() => {
    if (!activeId) return;
    attendanceHistoryForStudent(activeId, fromDate, toDate)
      .then((h) => setHistory(h.filter((r) => r.periodNo == null)))
      .catch((err) => setError(describeError(err)));
  }, [activeId, fromDate, toDate]);

  async function onApplyLeave(e: React.FormEvent) {
    e.preventDefault();
    if (!session || !activeId) return;
    setApplying(true);
    setError(null);
    setLeaveMessage(null);
    try {
      await applyLeave({
        schoolId: session.schoolId,
        subjectId: activeId,
        fromDate: todayIso(),
        toDate: leaveTo,
        reason: reason.trim() || undefined,
      });
      setLeaveMessage("Leave application submitted.");
      setReason("");
    } catch (err) {
      setError(describeError(err));
    } finally {
      setApplying(false);
    }
  }

  if (!session) return null;

  if (!session.subjectId) {
    return (
      <main className="shell">
        <div className="panel">
          <h2>Attendance</h2>
          <p className="hint">This account isn&apos;t linked to a guardian record.</p>
        </div>
      </main>
    );
  }

  return (
    <main className="shell">
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

      <div className="panel">
        <h2>History</h2>
        <div className="form-row">
          <input type="date" value={fromDate} onChange={(e) => setFromDate(e.target.value)} style={{ flex: 1 }} />
          <input type="date" value={toDate} onChange={(e) => setToDate(e.target.value)} style={{ flex: 1 }} />
        </div>

        {!history && <p className="hint">Loading…</p>}
        {history && history.length === 0 && <p className="empty-note">No attendance recorded in this range.</p>}
        {history && history.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Date</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {history
                .slice()
                .sort((a, b) => b.onDate.localeCompare(a.onDate))
                .map((r) => (
                  <tr key={r.id}>
                    <td>{r.onDate}</td>
                    <td>
                      <span className={`badge ${statusBadgeClass(r.status)}`}>{r.status.replace("_", " ")}</span>
                    </td>
                  </tr>
                ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="panel">
        <h2>Apply for leave</h2>
        <form onSubmit={onApplyLeave}>
          <div className="form-row" style={{ flexDirection: "column" }}>
            <div className="form-row">
              <input value={todayIso()} disabled style={{ flex: 1 }} />
              <input type="date" value={leaveTo} min={todayIso()} onChange={(e) => setLeaveTo(e.target.value)} style={{ flex: 1 }} />
            </div>
            <input placeholder="Reason (optional)" value={reason} onChange={(e) => setReason(e.target.value)} disabled={applying} />
            <button type="submit" disabled={applying || !activeId}>
              {applying ? "Submitting…" : "Submit leave request"}
            </button>
          </div>
        </form>
        {leaveMessage && <p className="hint">{leaveMessage}</p>}
      </div>
    </main>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}
