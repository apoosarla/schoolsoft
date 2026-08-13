"use client";

import Link from "next/link";
import { useRouter, usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { clearSession, getSession, hasScreen, Session, SCREEN_DEFS } from "@/lib/api";

const ICON: Record<string, JSX.Element> = {
  dashboard: (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none">
      <rect x="2" y="2" width="6.5" height="6.5" rx="1.4" stroke="currentColor" strokeWidth="1.4" />
      <rect x="9.5" y="2" width="6.5" height="4" rx="1.4" stroke="currentColor" strokeWidth="1.4" />
      <rect x="9.5" y="7.5" width="6.5" height="8.5" rx="1.4" stroke="currentColor" strokeWidth="1.4" />
      <rect x="2" y="10.5" width="6.5" height="5.5" rx="1.4" stroke="currentColor" strokeWidth="1.4" />
    </svg>
  ),
  students: (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none">
      <circle cx="9" cy="5.3" r="2.8" stroke="currentColor" strokeWidth="1.4" />
      <path d="M2.8 16C3.3 12.4 5.8 10.5 9 10.5C12.2 10.5 14.7 12.4 15.2 16" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
    </svg>
  ),
  admissions: (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none">
      <path d="M2.5 10.5V14.5C2.5 15.3 3.2 16 4 16H14C14.8 16 15.5 15.3 15.5 14.5V10.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      <path d="M9 2V11.5M9 11.5L5.8 8.3M9 11.5L12.2 8.3" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  ),
  attendance: (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none">
      <rect x="2.3" y="3" width="13.4" height="12.2" rx="1.6" stroke="currentColor" strokeWidth="1.4" />
      <path d="M2.3 6.6H15.7" stroke="currentColor" strokeWidth="1.4" />
      <path d="M6 10.6L8 12.4L12 8.4" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  ),
  fees: (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none">
      <circle cx="9" cy="9" r="6.8" stroke="currentColor" strokeWidth="1.4" />
      <path d="M9 5.5V12.5M11 6.9C11 6 10.1 5.5 9 5.5C7.9 5.5 7 6 7 6.9C7 8.6 11 7.7 11 9.9C11 10.9 10.1 11.5 9 11.5C7.9 11.5 7 10.9 7 10" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" />
    </svg>
  ),
  timetable: (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none">
      <rect x="2.3" y="3" width="13.4" height="12.2" rx="1.6" stroke="currentColor" strokeWidth="1.4" />
      <path d="M2.3 7.3H15.7" stroke="currentColor" strokeWidth="1.4" />
      <path d="M6 1.5V4.3M12 1.5V4.3" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
    </svg>
  ),
  assessment: (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none">
      <rect x="4" y="2.6" width="10" height="13.4" rx="1.5" stroke="currentColor" strokeWidth="1.4" />
      <path d="M7 2V3.6H11V2" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      <path d="M6.5 8.2L8 9.7L11.5 6.3" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M6.5 12.4H11.5" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
    </svg>
  ),
  calendar: (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none">
      <rect x="2.3" y="3.2" width="13.4" height="12" rx="1.6" stroke="currentColor" strokeWidth="1.4" />
      <path d="M2.3 7H15.7" stroke="currentColor" strokeWidth="1.4" />
      <path d="M5.8 1.8V4.4M12.2 1.8V4.4" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      <circle cx="6.2" cy="10.2" r="1.1" fill="currentColor" />
      <path d="M9.4 10.2H13" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" />
      <path d="M5.2 13.2H12.8" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" />
    </svg>
  ),
  exams: (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none">
      <path d="M3.2 3.4C3.2 2.9 3.6 2.5 4.1 2.5H12L15 5.5V15C15 15.5 14.6 15.9 14.1 15.9H4.1C3.6 15.9 3.2 15.5 3.2 15V3.4Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
      <path d="M11.8 2.6V5.6H14.8" stroke="currentColor" strokeWidth="1.3" strokeLinejoin="round" />
      <path d="M5.9 9.2H12.1M5.9 12.2H10" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
    </svg>
  ),
  lms: (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none">
      <path d="M9 4.3C7.8 3.3 5.9 2.9 3 3V13.4C5.9 13.3 7.8 13.7 9 14.7C10.2 13.7 12.1 13.3 15 13.4V3C12.1 2.9 10.2 3.3 9 4.3Z" stroke="currentColor" strokeWidth="1.3" strokeLinejoin="round" />
      <path d="M9 4.3V14.7" stroke="currentColor" strokeWidth="1.3" />
    </svg>
  ),
  comms: (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none">
      <path d="M2.5 4.8C2.5 3.8 3.3 3 4.3 3H13.7C14.7 3 15.5 3.8 15.5 4.8V10.4C15.5 11.4 14.7 12.2 13.7 12.2H7.3L4 15V12.2H4.3C3.3 12.2 2.5 11.4 2.5 10.4V4.8Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
    </svg>
  ),
  library: (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none">
      <path d="M3 2.8H7.2V15.2H3V2.8Z" stroke="currentColor" strokeWidth="1.3" strokeLinejoin="round" />
      <path d="M8.6 2.8H11.4V15.2H8.6V2.8Z" stroke="currentColor" strokeWidth="1.3" strokeLinejoin="round" />
      <path d="M13 3.6L15.6 4.4L12.8 15L10.2 14.2L13 3.6Z" stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round" />
    </svg>
  ),
  transport: (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none">
      <path d="M2.5 11.5V6.3C2.5 5.4 3.2 4.7 4.1 4.7H12.4C13.6 4.7 14.6 5.5 14.9 6.6L15.5 8.7V11.5" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
      <path d="M2.5 11.5H15.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      <circle cx="5.3" cy="13.3" r="1.6" stroke="currentColor" strokeWidth="1.3" />
      <circle cx="12.7" cy="13.3" r="1.6" stroke="currentColor" strokeWidth="1.3" />
      <path d="M2.5 8.7H15.2" stroke="currentColor" strokeWidth="1.3" />
    </svg>
  ),
  admin: (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none">
      <path d="M9 2L15 4.3V8.4C15 12 12.4 14.8 9 16C5.6 14.8 3 12 3 8.4V4.3L9 2Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
      <path d="M6.5 9.2L8.2 10.9L11.5 7.3" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  ),
};

function humanize(code: string): string {
  return code
    .split("_")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
}

export default function AppShell({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const [session, setSessionState] = useState<Session | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);

  useEffect(() => {
    setSessionState(getSession());
    setDrawerOpen(false);
  }, [pathname]);

  if (!session || pathname === "/login") return <>{children}</>;

  const visible = SCREEN_DEFS.filter((s) => hasScreen(session, s.key));
  const current = SCREEN_DEFS.find((s) => s.path === pathname);
  const roleLabel = session.roleCodes.length > 0 ? session.roleCodes.map(humanize).join(" · ") : humanize(session.subjectType);

  function signOut() {
    clearSession();
    router.replace("/login");
  }

  return (
    <div className="app">
      <div className={"scrim" + (drawerOpen ? " open" : "")} onClick={() => setDrawerOpen(false)} />

      <aside className={"sidebar" + (drawerOpen ? " open" : "")}>
        <div className="brand">
          <svg className="crest" width="28" height="28" viewBox="0 0 30 30" fill="none">
            <path
              d="M15 2 L27 7 V15 C27 22 21.5 26.5 15 28 C8.5 26.5 3 22 3 15 V7 Z"
              fill="var(--accent-wash-strong)"
              stroke="var(--accent)"
              strokeWidth="1.4"
            />
            <text x="15" y="19.5" textAnchor="middle" fontFamily="Georgia, serif" fontSize="11.5" fill="var(--accent-strong)" fontWeight="700">
              S
            </text>
          </svg>
          <div className="brand-text">
            <div className="brand-name">Schoolsoft</div>
            <div className="brand-sub">School Admin</div>
          </div>
        </div>

        <nav className="nav">
          {visible.map((s) => (
            <Link key={s.key} href={s.path} className={"nav-link" + (pathname === s.path ? " active" : "")}>
              {ICON[s.key]}
              <span className="nav-label">{s.label}</span>
            </Link>
          ))}
        </nav>

        <div className="sidebar-footer">
          <div className="profile">
            <div className="avatar">
              <svg width="15" height="15" viewBox="0 0 18 18" fill="none">
                <circle cx="9" cy="5.6" r="3" stroke="currentColor" strokeWidth="1.4" />
                <path d="M2.8 16C3.4 12.1 6 10 9 10C12 10 14.6 12.1 15.2 16" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
              </svg>
            </div>
            <div className="profile-text">
              <div className="profile-role">{roleLabel}</div>
              <div className="profile-sub">{session.chainSchema}</div>
            </div>
          </div>
          <button type="button" className="signout-btn" onClick={signOut}>
            Sign out
          </button>
        </div>
      </aside>

      <div>
        <header className="topbar">
          <button type="button" className="hamburger" aria-label="Open menu" onClick={() => setDrawerOpen(true)}>
            <svg width="16" height="16" viewBox="0 0 18 18" fill="none">
              <path d="M2 4.5H16M2 9H16M2 13.5H16" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
            </svg>
          </button>
          <h1>{current?.label ?? "Schoolsoft"}</h1>
        </header>
        {children}
      </div>
    </div>
  );
}
