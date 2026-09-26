"use client";

import { useState } from "react";
import { ChainAdminDto } from "@/lib/api";

/**
 * Who runs this chain, and the form that hands it to them.
 *
 * <p>The vendor's half of onboarding ends here. Schoolsoft provisions the
 * chain and can open schools in it, but a chain with no accounts has no door:
 * appointing a school's first keyholder belongs to the chain's own HQ, so
 * until this panel has been used, every school in the chain is stuck in draft
 * however complete the rest of its setup is.</p>
 *
 * Once, per chain — the same rule as a school's first administrator, one level
 * up. Anyone else at the customer's HQ is the customer's to add, and the form
 * disappears rather than pretending otherwise.
 */
export default function HandoverPanel({
  admins,
  loading,
  error,
  onAppoint,
}: {
  admins: ChainAdminDto[] | null;
  loading: boolean;
  error: string | null;
  onAppoint: (email: string) => Promise<ChainAdminDto>;
}) {
  const [email, setEmail] = useState("");
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setFormError(null);
    setBusy(true);
    try {
      await onAppoint(email.trim());
      setEmail("");
    } catch (err) {
      setFormError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="panel">
      <h2>Who runs this chain</h2>
      {loading && <p className="hint">Loading&hellip;</p>}
      {error && <div className="error-banner">{error}</div>}

      {admins && admins.length > 0 && (
        <p className="hint" style={{ marginBottom: 0 }}>
          Handed over. <code>{admins[0].signsInWith}</code> signs in at the chain&apos;s own
          address and opens its schools from there; they appoint each school&apos;s first
          administrator, and the schools hire everybody else. Another HQ account is theirs
          to add, not ours.
        </p>
      )}

      {admins && admins.length === 0 && (
        <>
          <p className="hint">
            Nobody yet. This chain is provisioned but not handed over: its schools can be
            opened from here, and none of them can leave <code>draft</code>, because the
            person who appoints a school&apos;s first administrator is the chain&apos;s own
            HQ. Creating that account is the one person Schoolsoft puts inside a customer&apos;s
            chain, and it is recorded in their audit log.
          </p>
          <form onSubmit={submit}>
            <div className="form-row">
              <input
                type="email"
                placeholder="Email their HQ signs in with"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                disabled={busy}
                required
                style={{ minWidth: 280 }}
              />
              <button type="submit" disabled={busy}>
                {busy ? "Handing over…" : "Hand the chain over"}
              </button>
            </div>
          </form>
          {formError && <div className="error-banner">{formError}</div>}
        </>
      )}
    </div>
  );
}
