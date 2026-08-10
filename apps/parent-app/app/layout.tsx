import type { Metadata, Viewport } from "next";
import "./globals.css";
import AppShell from "./app-shell";

export const metadata: Metadata = {
  title: "Schoolsoft — Parent",
  description: "Your child's attendance, fees, and school updates.",
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  maximumScale: 1,
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
