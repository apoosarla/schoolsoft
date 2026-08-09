"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { getSession, hasScreen, Session, SCREEN_DEFS } from "@/lib/api";

export default function Nav() {
  const pathname = usePathname();
  const [session, setSessionState] = useState<Session | null>(null);

  useEffect(() => {
    setSessionState(getSession());
  }, [pathname]);

  if (!session || pathname === "/login") return null;

  const visible = SCREEN_DEFS.filter((s) => hasScreen(session, s.key));

  return (
    <nav className="topbar-nav">
      {visible.map((s) => (
        <Link key={s.key} href={s.path} className={pathname === s.path ? "active" : ""}>
          {s.label}
        </Link>
      ))}
    </nav>
  );
}
