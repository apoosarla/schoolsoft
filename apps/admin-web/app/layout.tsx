import type { Metadata } from "next";
import "./globals.css";
import Nav from "./nav";

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
          <Nav />
        </div>
        {children}
      </body>
    </html>
  );
}
