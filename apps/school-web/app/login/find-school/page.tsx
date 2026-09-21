"use client";

import { useState } from "react";
import Link from "next/link";
import { ApiError, resolveTenant } from "@/lib/api";

/**
 * The page behind "Not your school?".
 *
 * It takes something the school gave you — an address, a link, a code — and
 * opens one door. It does not list schools, it does not search them, and it
 * does not confirm which school a person belongs to: that answer is the
 * school's to give. Everything here is typed, never chosen.
 */
export default function FindSchoolPage() {
  const [address, setAddress] = useState("");
  const [code, setCode] = useState("");
  const [checking, setChecking] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onAddress(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setChecking(true);
    try {
      const host = normaliseHost(address);
      const tenant = await resolveTenant(host);
      if (!tenant) {
        setError("No school answers on that address. Check it against your school's letterhead.");
        return;
      }
      window.location.href = `https://${host}/login`;
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not reach Schoolsoft. Try again.");
    } finally {
      setChecking(false);
    }
  }

  return (
    <main className="login-find">
      <div className="login-find-inner">
        <Link className="login-back" href="/login">
          ← Back to sign-in
        </Link>

        <h1>Find your school</h1>
        <p className="login-lede">
          There is no list of schools to browse, and no search. You arrive with something your
          school gave you — an address, a link, or a code — and it opens one door.
        </p>

        <ol className="login-routes">
          <li>
            <span className="login-step">1</span>
            <div>
              <label htmlFor="address">Your school&apos;s web address</label>
              <span className="hint">
                On letterhead, in the welcome email, on the school&apos;s own website.
              </span>
              <form className="login-row" onSubmit={onAddress}>
                <input
                  id="address"
                  inputMode="url"
                  placeholder="staff.yourschool.edu.in"
                  value={address}
                  onChange={(e) => setAddress(e.target.value)}
                  disabled={checking}
                  required
                />
                <button type="submit" disabled={checking}>
                  {checking ? "Checking…" : "Go"}
                </button>
              </form>
            </div>
          </li>

          <li>
            <span className="login-step">2</span>
            <div>
              <strong>The link the office sent you</strong>
              <span className="hint">
                Every welcome email and admission letter carries a link that opens the right door on
                its own. Search your inbox for the school&apos;s name.
              </span>
            </div>
          </li>

          <li>
            <span className="login-step">3</span>
            <div>
              <label htmlFor="access-code">An access code from the office</label>
              <span className="hint">
                Issued to you, not derived from the school&apos;s name. Expires 14 days after it is
                printed.
              </span>
              <div className="login-row">
                <input
                  id="access-code"
                  className="login-code-input"
                  placeholder="K7QP-2M4X-9T"
                  value={code}
                  onChange={(e) => setCode(e.target.value.toUpperCase())}
                  disabled
                />
                <button type="button" disabled title="Access codes are not issued yet">
                  Open
                </button>
              </div>
              <span className="hint">Not issued yet — see BACKLOG.md.</span>
            </div>
          </li>
        </ol>

        {error && <div className="error-banner">{error}</div>}

        <p className="login-find-foot">
          Still stuck? Your school office can resend your invite in a minute. Support cannot look up
          which school a person belongs to — that answer is the school&apos;s to give, not ours.
        </p>
      </div>
    </main>
  );
}

/** Accepts a pasted URL as readily as a bare hostname; the API decides the rest. */
function normaliseHost(input: string): string {
  return input.trim().toLowerCase().replace(/^https?:\/\//, "").split("/")[0];
}
