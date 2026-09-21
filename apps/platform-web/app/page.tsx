"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { isLoggedIn } from "@/lib/api";

/** The front door of the platform console. */
export default function LandingPage() {
  const router = useRouter();
  const [ready, setReady] = useState(false);

  useEffect(() => {
    if (!isLoggedIn()) {
      router.replace("/login");
      return;
    }
    setReady(true);
  }, [router]);

  if (!ready) return null;

  return (
    <main className="shell">
      <div className="panel">
        <h2>Platform console</h2>
        <p className="hint">
          Cross-school KPIs (enrolment, fees, attendance trends) land here per
          schoolsoft-design.md §15 — Phase 2. For now, this console covers tenant
          onboarding and the schools inside each chain.
        </p>
        <p>
          <Link href="/chains">Go to Chains &rarr;</Link>
        </p>
      </div>
    </main>
  );
}
