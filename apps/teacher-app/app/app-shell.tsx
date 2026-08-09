"use client";

import Link from "next/link";
import { useRouter, usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { clearSession, getSession, Session } from "@/lib/api";

const TABS = [
  {
    href: "/",
    label: "Today",
    icon: (
      <svg width="20" height="20" viewBox="0 0 18 18" fill="none">
        <rect x="2.3" y="3" width="13.4" height="12.2" rx="1.6" stroke="currentColor" strokeWidth="1.4" />
        <path d="M2.3 7.3H15.7" stroke="currentColor" strokeWidth="1.4" />
        <path d="M6 1.5V4.3M12 1.5V4.3" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      </svg>
    ),
  },
  {
    href: "/attendance",
    label: "Attendance",
    icon: (
      <svg width="20" height="20" viewBox="0 0 18 18" fill="none">
        <rect x="2.3" y="3" width="13.4" height="12.2" rx="1.6" stroke="currentColor" strokeWidth="1.4" />
        <path d="M2.3 6.6H15.7" stroke="currentColor" strokeWidth="1.4" />
        <path d="M6 10.6L8 12.4L12 8.4" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    ),
  },
];

const TITLES: Record<string, string> = {
  "/": "Today",
  "/attendance": "Attendance",
};

export default function AppShell({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const [session, setSessionState] = useState<Session | null>(null);

  useEffect(() => {
    setSessionState(getSession());
  }, [pathname]);

  if (!session || pathname === "/login") return <>{children}</>;

  function signOut() {
    clearSession();
    router.replace("/login");
  }

  return (
    <div className="app">
      <header className="topbar">
        <div>
          <h1>{TITLES[pathname] ?? "Schoolsoft"}</h1>
          <div className="topbar-sub">{session!.identifier}</div>
        </div>
        <button type="button" className="icon-btn" aria-label="Sign out" onClick={signOut}>
          <svg width="18" height="18" viewBox="0 0 18 18" fill="none">
            <path d="M7 15.5H4.3C3.6 15.5 3 14.9 3 14.2V3.8C3 3.1 3.6 2.5 4.3 2.5H7" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
            <path d="M11.5 12.5L15 9L11.5 5.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
            <path d="M15 9H6.8" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
          </svg>
        </button>
      </header>

      {children}

      <nav className="tabbar">
        {TABS.map((t) => (
          <Link key={t.href} href={t.href} className={"tab-link" + (pathname === t.href ? " active" : "")}>
            {t.icon}
            <span>{t.label}</span>
          </Link>
        ))}
      </nav>
    </div>
  );
}
