"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { ApiError, startOtp, verifyOtp } from "@/lib/api";

export default function LoginPage() {
  const router = useRouter();
  const [step, setStep] = useState<"identify" | "verify">("identify");
  const [identifier, setIdentifier] = useState("");
  const [chainSlug, setChainSlug] = useState("");
  const [code, setCode] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onStart(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await startOtp(identifier.trim(), chainSlug.trim());
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
      await verifyOtp(identifier.trim(), chainSlug.trim(), code.trim());
      router.replace("/");
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
          Driver OTP login. Dev builds accept code <code>000000</code> for any identifier.
        </p>

        {step === "identify" && (
          <form onSubmit={onStart}>
            <div className="form-row" style={{ flexDirection: "column" }}>
              <input
                placeholder="Email or phone"
                value={identifier}
                onChange={(e) => setIdentifier(e.target.value)}
                required
                disabled={submitting}
              />
              <input
                placeholder="Chain slug (e.g. smoketest)"
                value={chainSlug}
                onChange={(e) => setChainSlug(e.target.value)}
                required
                disabled={submitting}
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
              Code sent to <strong>{identifier}</strong> ({chainSlug}).
            </p>
            <div className="form-row" style={{ flexDirection: "column" }}>
              <input
                placeholder="6-digit code"
                value={code}
                onChange={(e) => setCode(e.target.value)}
                required
                disabled={submitting}
                maxLength={6}
                inputMode="numeric"
              />
              <button type="submit" disabled={submitting}>
                {submitting ? "Verifying…" : "Verify"}
              </button>
              <button
                type="button"
                onClick={() => setStep("identify")}
                disabled={submitting}
                style={{ background: "none", color: "var(--text-dim)", boxShadow: "none", border: "1px solid var(--border-strong)" }}
              >
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
    if (err.status === 404) return "No account found for that identifier in that chain.";
    return `${err.code ?? "error"}: ${err.message}`;
  }
  return err instanceof Error ? err.message : "Unknown error";
}
