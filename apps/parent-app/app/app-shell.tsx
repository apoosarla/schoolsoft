"use client";

import Link from "next/link";
import { useRouter, usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { clearSession, getSession, Session } from "@/lib/api";

const TABS = [
  {
    href: "/",
    label: "Home",
    icon: (
      <svg width="20" height="20" viewBox="0 0 18 18" fill="none">
        <path d="M2.5 8.2L9 2.5L15.5 8.2" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
        <path d="M4 7V15H14V7" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
        <path d="M7 15V11H11V15" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
      </svg>
    ),
  },
  {
    href: "/fees",
    label: "Fees",
    icon: (
      <svg width="20" height="20" viewBox="0 0 18 18" fill="none">
        <circle cx="9" cy="9" r="6.8" stroke="currentColor" strokeWidth="1.4" />
        <path d="M9 5.5V12.5M11 6.9C11 6 10.1 5.5 9 5.5C7.9 5.5 7 6 7 6.9C7 8.6 11 7.7 11 9.9C11 10.9 10.1 11.5 9 11.5C7.9 11.5 7 10.9 7 10" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" />
      </svg>
    ),
  },
];

const TITLES: Record<string, string> = {
  "/": "Home",
  "/fees": "Fees",
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
