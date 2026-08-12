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
  {
    href: "/report-cards",
    label: "Grades",
    icon: (
      <svg width="20" height="20" viewBox="0 0 18 18" fill="none">
        <path d="M9 2.3L15.7 5.8V11.2C15.7 14.3 12.9 16.6 9 17.7C5.1 16.6 2.3 14.3 2.3 11.2V5.8L9 2.3Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
        <path d="M6.3 9.4L8.2 11.3L11.7 7.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    ),
  },
  {
    href: "/homework",
    label: "Homework",
    icon: (
      <svg width="20" height="20" viewBox="0 0 18 18" fill="none">
        <path d="M3 5.3C3 4.4 3.7 3.7 4.6 3.7H13.4C14.3 3.7 15 4.4 15 5.3V14.3L9 12L3 14.3V5.3Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
      </svg>
    ),
  },
  {
    href: "/messages",
    label: "Messages",
    icon: (
      <svg width="20" height="20" viewBox="0 0 18 18" fill="none">
        <path d="M2.5 4.8C2.5 3.8 3.3 3 4.3 3H13.7C14.7 3 15.5 3.8 15.5 4.8V10.4C15.5 11.4 14.7 12.2 13.7 12.2H7.3L4 15V12.2H4.3C3.3 12.2 2.5 11.4 2.5 10.4V4.8Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
      </svg>
    ),
  },
];

const TITLES: Record<string, string> = {
  "/": "Home",
  "/fees": "Fees",
  "/attendance": "Attendance",
  "/report-cards": "Grades",
  "/homework": "Homework",
  "/messages": "Messages",
};

/** next.config.mjs sets trailingSlash for the Capacitor static export, so usePathname() returns "/attendance/" rather than "/attendance" — normalize before any exact-path comparison. */
function normalize(pathname: string): string {
  return pathname !== "/" && pathname.endsWith("/") ? pathname.slice(0, -1) : pathname;
}

export default function AppShell({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const pathname = normalize(usePathname());
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
