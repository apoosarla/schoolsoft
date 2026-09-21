"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { getSession, homeFor } from "@/lib/api";

export default function HomePage() {
  const router = useRouter();

  useEffect(() => {
    router.replace(homeFor(getSession()));
  }, [router]);

  return (
    <main className="shell">
      <p className="hint">Redirecting…</p>
    </main>
  );
}
