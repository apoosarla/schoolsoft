import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";

export const metadata: Metadata = {
  title: "Schoolsoft — School Admin",
  description: "School admin console for Schoolsoft.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <div className="topbar">
          <div className="topbar-brand">Schoolsoft · School Admin</div>
          <nav className="topbar-nav">
            <Link href="/dashboard">Dashboard</Link>
            <Link href="/students">Students</Link>
            <Link href="/admissions">Admissions</Link>
            <Link href="/attendance">Attendance</Link>
            <Link href="/fees">Fees</Link>
          </nav>
        </div>
        {children}
      </body>
    </html>
  );
}
