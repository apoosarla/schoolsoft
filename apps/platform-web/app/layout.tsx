import type { Metadata } from "next";
import "./globals.css";
import TopBar from "./topbar";

export const metadata: Metadata = {
  title: "Schoolsoft — Platform Console",
  description: "Schoolsoft's own operators: chain provisioning and the schools inside each chain.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <TopBar />
        {children}
      </body>
    </html>
  );
}
