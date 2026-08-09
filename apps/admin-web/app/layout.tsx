import type { Metadata } from "next";
import "./globals.css";
import AppShell from "./app-shell";

export const metadata: Metadata = {
  title: "Schoolsoft — School Admin",
  description: "School admin console for Schoolsoft.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <AppShell>{children}</AppShell>
      </body>
    </html>
  );
}
