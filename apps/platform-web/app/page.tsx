"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { SessionKind, getSessionKind, isLoggedIn } from "@/lib/api";

/**
 * The front door of an app that serves two consoles: it sends each kind of
 * session to its own and shows nothing of the other.
 */
export default function LandingPage() {
  const router = useRouter();
  const [kind, setKind] = useState<SessionKind | null>(null);

  useEffect(() => {
    if (!isLoggedIn()) {
      router.replace("/login");
      return;
    }
    const k = getSessionKind();
    if (k === "chain") {
      router.replace("/my-chain");
      return;
    }
    setKind(k);
  }, [router]);

  if (kind !== "platform") return null;

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
