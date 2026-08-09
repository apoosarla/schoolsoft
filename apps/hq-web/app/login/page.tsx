"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { ApiError, startPlatformOtp, verifyPlatformOtp } from "@/lib/api";

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
          Platform-admin OTP login. Dev builds accept code <code>000000</code> for any email — see{" "}
          <code>OtpStore</code>&apos;s dev bypass.
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
                style={{ minWidth: 280 }}
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
              <button type="button" onClick={() => setStep("identify")} disabled={submitting}>
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
    if (err.status === 404) return "No platform-admin account found for that email.";
    return `${err.code ?? "error"}: ${err.message}`;
  }
  return err instanceof Error ? err.message : "Unknown error";
}
