import type { Metadata } from "next";
import "./globals.css";
import Nav from "./nav";

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
          <Nav />
        </div>
        {children}
      </body>
    </html>
  );
}
