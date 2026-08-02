import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";

export const metadata: Metadata = {
  title: "Schoolsoft — Chain HQ Console",
  description: "Tenant and school onboarding for the Schoolsoft platform.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <div className="topbar">
          <div className="topbar-brand">Schoolsoft · Chain HQ</div>
          <nav className="topbar-nav">
            <Link href="/">Dashboard</Link>
            <Link href="/chains">Chains</Link>
          </nav>
        </div>
        {children}
      </body>
    </html>
  );
}
