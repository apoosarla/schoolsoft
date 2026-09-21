"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { ApiError, startPlatformOtp, verifyPlatformOtp } from "@/lib/api";

/**
 * The operators' door, and the only one.
 *
 * A chain's HQ admin used to sign in here too, on a second tab of this form.
 * They sign in to school-web now, with their chain's slug, alongside the
 * schools they oversee — so this page no longer has to explain to a customer
 * that they are in the vendor's building, and no screen behind it has to
 * branch on which kind of account arrived.
 */
export default function LoginPage() {
  const router = useRouter();
  const [step, setStep] = useState<"identify" | "verify">("identify");
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onStart(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await startPlatformOtp(email.trim());
      setStep("verify");
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSubmitting(false);
    }
  }

  async function onVerify(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await verifyPlatformOtp(email.trim(), code.trim());
      router.replace("/chains");
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="shell">
      <div className="panel">
        <h2>Sign in</h2>

        <p className="hint">
          Platform-admin login, for Schoolsoft&apos;s own operators — every chain, not one. A
          chain&apos;s HQ admin and a school&apos;s staff both sign in to the school app instead.
          Dev builds accept code <code>000000</code> — see <code>OtpStore</code>&apos;s dev bypass.
        </p>

        {step === "identify" && (
          <form onSubmit={onStart}>
            <div className="form-row">
              <input
                type="email"
                placeholder="Email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                required
                disabled={submitting}
                style={{ minWidth: 260 }}
              />
              <button type="submit" disabled={submitting}>
                {submitting ? "Sending…" : "Send code"}
              </button>
            </div>
          </form>
        )}

        {step === "verify" && (
          <form onSubmit={onVerify}>
            <p className="hint">
              Code sent to <strong>{email}</strong>.
            </p>
            <div className="form-row">
              <input
                placeholder="6-digit code"
                value={code}
                onChange={(e) => setCode(e.target.value)}
                required
                disabled={submitting}
                maxLength={6}
              />
              <button type="submit" disabled={submitting}>
                {submitting ? "Verifying…" : "Verify"}
              </button>
              <button type="button" className="secondary" onClick={() => setStep("identify")} disabled={submitting}>
                Back
              </button>
            </div>
          </form>
        )}

        {error && <div className="error-banner">{error}</div>}
      </div>
    </main>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 401) return "Invalid or expired code.";
    if (err.status === 404) return "No platform-admin account for that email.";
    if (err.status === 403) return err.message;
    return `${err.code ?? "error"}: ${err.message}`;
  }
  return err instanceof Error ? err.message : "Unknown error";
}
