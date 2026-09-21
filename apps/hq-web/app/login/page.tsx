"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  startChainOtp,
  startPlatformOtp,
  verifyChainOtp,
  verifyPlatformOtp,
} from "@/lib/api";

type Door = "chain" | "platform";

/**
 * Two doors into one app.
 *
 * A chain's HQ admin signs in with their chain's slug and lands on their own
 * chain. Schoolsoft's own staff sign in against the platform account list and
 * land above every chain. They are different accounts in different tables
 * answering to different endpoints, so this is a choice made before the email
 * is typed rather than something guessed from it afterwards.
 *
 * Neither door is the school's: a principal or a registrar signs in to the
 * school's own admin app, and this one tells them so rather than admitting
 * them to a console built around a chain.
 */
export default function LoginPage() {
  const router = useRouter();
  const [door, setDoor] = useState<Door>("chain");
  const [step, setStep] = useState<"identify" | "verify">("identify");
  const [identifier, setIdentifier] = useState("");
  const [chainSlug, setChainSlug] = useState("");
  const [code, setCode] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function switchDoor(next: Door) {
    setDoor(next);
    setStep("identify");
    setError(null);
    setCode("");
  }

  async function onStart(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      if (door === "platform") await startPlatformOtp(identifier.trim());
      else await startChainOtp(identifier.trim(), chainSlug.trim());
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
      if (door === "platform") {
        await verifyPlatformOtp(identifier.trim(), code.trim());
        router.replace("/chains");
      } else {
        await verifyChainOtp(identifier.trim(), chainSlug.trim(), code.trim());
        router.replace("/my-chain");
      }
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

        <div className="form-row" style={{ gap: 8 }}>
          <button
            type="button"
            className={door === "chain" ? "" : "secondary"}
            onClick={() => switchDoor("chain")}
            disabled={submitting}
          >
            Chain HQ
          </button>
          <button
            type="button"
            className={door === "platform" ? "" : "secondary"}
            onClick={() => switchDoor("platform")}
            disabled={submitting}
          >
            Schoolsoft staff
          </button>
        </div>

        <p className="hint">
          {door === "chain" ? (
            <>
              For the admin of a chain, with the chain&apos;s slug. Staff of a school sign in to the
              school&apos;s own admin app instead.
            </>
          ) : (
            <>Platform-admin login, for Schoolsoft&apos;s own operators — every chain, not one.</>
          )}{" "}
          Dev builds accept code <code>000000</code> — see <code>OtpStore</code>&apos;s dev bypass.
        </p>

        {step === "identify" && (
          <form onSubmit={onStart}>
            <div className="form-row">
              <input
                type={door === "platform" ? "email" : "text"}
                placeholder={door === "platform" ? "Email" : "Email or phone"}
                value={identifier}
                onChange={(e) => setIdentifier(e.target.value)}
                required
                disabled={submitting}
                style={{ minWidth: 260 }}
              />
              {door === "chain" && (
                <input
                  placeholder="Chain slug (e.g. smoketest)"
                  value={chainSlug}
                  onChange={(e) => setChainSlug(e.target.value)}
                  required
                  disabled={submitting}
                />
              )}
              <button type="submit" disabled={submitting}>
                {submitting ? "Sending…" : "Send code"}
              </button>
            </div>
          </form>
        )}

        {step === "verify" && (
          <form onSubmit={onVerify}>
            <p className="hint">
              Code sent to <strong>{identifier}</strong>
              {door === "chain" && (
                <>
                  {" "}
                  (<code>{chainSlug}</code>)
                </>
              )}
              .
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
    if (err.status === 404) return "No account found for that identifier in this chain.";
    if (err.status === 403) return err.message;
    return `${err.code ?? "error"}: ${err.message}`;
  }
  return err instanceof Error ? err.message : "Unknown error";
}
