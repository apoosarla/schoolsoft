"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { ApiError, clearSession, getSchoolOverview, getSession, Session, SchoolOverviewDto } from "@/lib/api";

export default function DashboardPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [overview, setOverview] = useState<SchoolOverviewDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    setSessionState(s);
    getSchoolOverview(s.schoolId)
      .then(setOverview)
      .catch((err) => setError(err instanceof ApiError ? `${err.code}: ${err.message}` : "Failed to load"))
      .finally(() => setLoading(false));
  }, [router]);

  function signOut() {
    clearSession();
    router.replace("/login");
  }

  if (!session) return null;

  return (
    <main className="shell">
      <div className="panel">
        <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
          <div>
            <h2 style={{ marginBottom: 4 }}>Overview</h2>
            <p className="hint">
              Signed in as <code>{session.subjectType}</code> · chain{" "}
              <code>{session.chainSchema}</code>
            </p>
          </div>
          <button type="button" onClick={signOut}>
            Sign out
          </button>
        </div>
      </div>

      {loading && (
        <div className="panel">
          <p className="hint">Loading…</p>
        </div>
      )}

      {error && <div className="error-banner">{error}</div>}

      {overview && (
        <>
          <div className="panel">
            <h2>Today</h2>
            <div className="stat-grid">
              <Stat label="Active enrolments" value={overview.activeEnrolments} />
              <Stat label="Present today" value={overview.presentToday} />
              <Stat
                label="Attendance %"
                value={overview.attendanceTodayPct == null ? "—" : `${overview.attendanceTodayPct.toFixed(0)}%`}
              />
            </div>
          </div>

          <div className="panel">
            <h2>Fees — month to date</h2>
            <div className="stat-grid">
              <Stat label="Invoiced" value={formatInr(overview.feeInvoicedMtd)} />
              <Stat label="Collected" value={formatInr(overview.feeCollectedMtd)} />
              <Stat
                label="Collection %"
                value={overview.feeCollectionMtdPct == null ? "—" : `${overview.feeCollectionMtdPct.toFixed(0)}%`}
              />
            </div>
          </div>

          <div className="panel">
            <h2>Admissions funnel</h2>
            {Object.keys(overview.admissionsFunnel).length === 0 ? (
              <p className="hint">No applications yet.</p>
            ) : (
              <div className="stat-grid">
                {Object.entries(overview.admissionsFunnel).map(([state, count]) => (
                  <Stat key={state} label={state} value={count} />
                ))}
              </div>
            )}
          </div>

          <div className="panel">
            <h2>Comms reach (30 days)</h2>
            <div className="stat-grid">
              <Stat label="Announcements published" value={overview.announcementsPublished30d} />
              <Stat label="Read receipts" value={overview.announcementReads30d} />
            </div>
          </div>
        </>
      )}
    </main>
  );
}

function Stat({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="stat-tile">
      <div className="value">{value}</div>
      <div className="label">{label}</div>
    </div>
  );
}

function formatInr(n: number): string {
  return n.toLocaleString(undefined, { style: "currency", currency: "INR", maximumFractionDigits: 0 });
}
