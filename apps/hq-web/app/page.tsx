import Link from "next/link";

export default function DashboardPage() {
  return (
    <main className="shell">
      <div className="panel">
        <h2>Chain HQ Console</h2>
        <p className="hint">
          Cross-school KPIs (enrolment, fees, attendance trends) land here per
          schoolsoft-design.md §15 — Phase 2. For now, this console covers tenant
          onboarding.
        </p>
        <p>
          <Link href="/chains">Go to Chains →</Link>
        </p>
      </div>
    </main>
  );
}
