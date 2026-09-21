"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { SessionKind, getSessionKind } from "@/lib/api";

/**
 * The two consoles do not share a menu: an operator moves between chains, and
 * a chain admin has one chain and no business being offered the list of
 * everybody else's.
 */
const LINKS: Record<SessionKind, { href: string; label: string }[]> = {
  platform: [
    { href: "/", label: "Dashboard" },
    { href: "/chains", label: "Chains" },
  ],
  chain: [{ href: "/my-chain", label: "Your chain" }],
};

export default function Nav() {
  const pathname = usePathname();
  const [kind, setKind] = useState<SessionKind | null>(null);

  // localStorage is not readable while rendering on the server, and the kind
  // changes on sign-in and sign-out, both of which change the path.
  useEffect(() => setKind(getSessionKind()), [pathname]);

  if (!kind) return <nav className="topbar-nav" />;

  return (
    <nav className="topbar-nav">
      {LINKS[kind].map((l) => (
        <Link key={l.href} href={l.href} className={pathname === l.href ? "active" : ""}>
          {l.label}
        </Link>
      ))}
    </nav>
  );
}
