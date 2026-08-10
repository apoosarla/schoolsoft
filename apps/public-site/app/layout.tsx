import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";

export const metadata: Metadata = {
  title: "Oakridge Hyderabad — Admissions",
  description: "School information and admissions inquiry.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <header className="site-header">
          <Link href="/" className="brand">
            <svg width="26" height="26" viewBox="0 0 30 30" fill="none">
              <path
                d="M15 2 L27 7 V15 C27 22 21.5 26.5 15 28 C8.5 26.5 3 22 3 15 V7 Z"
                fill="var(--accent-wash-strong)"
                stroke="var(--accent)"
                strokeWidth="1.4"
              />
              <text x="15" y="19.5" textAnchor="middle" fontFamily="Georgia, serif" fontSize="11.5" fill="var(--accent-strong)" fontWeight="700">
                O
              </text>
            </svg>
            <span className="brand-name serif">Oakridge Hyderabad</span>
          </Link>
          <nav className="site-nav">
            <Link href="/track">Track application</Link>
            <Link href="/apply">Apply now</Link>
          </nav>
        </header>
        {children}
        <footer className="site-footer">Oakridge Hyderabad · Admissions enquiries only, not a payments or student-records portal.</footer>
      </body>
    </html>
  );
}
