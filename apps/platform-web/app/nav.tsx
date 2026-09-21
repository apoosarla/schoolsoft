"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { isLoggedIn } from "@/lib/api";

/**
 * One console, one menu. There were two until the chain's own HQ moved to
 * school-web, and with it the branch that decided which of them a session was
 * allowed to see.
 */
const LINKS = [
  { href: "/", label: "Dashboard" },
  { href: "/chains", label: "Chains" },
];

export default function Nav() {
  const pathname = usePathname();
  const [signedIn, setSignedIn] = useState(false);

  // localStorage is not readable while rendering on the server, and the token
  // changes on sign-in and sign-out, both of which change the path.
  useEffect(() => setSignedIn(isLoggedIn()), [pathname]);

  if (!signedIn) return <nav className="topbar-nav" />;

  return (
    <nav className="topbar-nav">
      {LINKS.map((l) => (
        <Link key={l.href} href={l.href} className={pathname === l.href ? "active" : ""}>
          {l.label}
        </Link>
      ))}
    </nav>
  );
}
