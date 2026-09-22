"use client";

import { usePathname } from "next/navigation";
import Nav from "./nav";

/**
 * The console's chrome, and where it stops.
 *
 * Sign-in is a full-bleed page of its own — a menu above somebody who has not
 * signed in yet offers them nothing, and the brand bar would compete with the
 * one the door already carries.
 */
export default function TopBar() {
  const pathname = usePathname();
  if (pathname.startsWith("/login")) return null;

  return (
    <div className="topbar">
      <div className="topbar-brand">Schoolsoft · Platform</div>
      <Nav />
    </div>
  );
}
