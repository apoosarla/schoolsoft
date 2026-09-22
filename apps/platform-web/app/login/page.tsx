"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { CodeStep } from "@schoolsoft/ui";
import { ApiError, startPlatformOtp, verifyPlatformOtp } from "@/lib/api";

const IS_DEV = process.env.NODE_ENV !== "production";

/** Where a customer's own people sign in. Dev runs school-web on :3001. */
const SCHOOL_APP_URL =
  process.env.NEXT_PUBLIC_SCHOOL_WEB_URL ?? (IS_DEV ? "http://localhost:3001/login" : "");

/**
 * The operators' door, and the only one.
 *
 * A chain's HQ admin used to sign in here too, on a second tab of this form —
 * which read as one login with a setting when they are two different account
 * lists answering two different endpoints. They sign in to school-web now,
 * alongside the schools they oversee, so this page is a door and not a choice.
 *
 * Deliberately not school-branded: nothing here resolves a tenant from the
 * host, because a platform account is not a tenant's account and this console
 * stands above every chain. What the page says instead of a crest is what an
 * operator should know before typing — the session is audited, and the list
 * these accounts live in is `platform.platform_user`, not any school's.
 */
export default function LoginPage() {
  const router = useRouter();
  const [step, setStep] = useState<"identify" | "verify">("identify");
  const [email, setEmail] = useState("");
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

  async function onVerify(code: string) {
    setError(null);
    setSubmitting(true);
    try {
      await verifyPlatformOtp(email.trim(), code);
      router.replace("/chains");
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="login">
      <aside className="login-brand">
        <div className="login-vendor">
          <span className="login-vendor-mark">S</span>
          <span className="login-vendor-name">Schoolsoft</span>
          <span className="login-vendor-rule" />
          <span className="login-vendor-tag">Platform</span>
        </div>

        <div className="login-said">
          <div className="login-identity">
            <h1>Operator sign-in</h1>
            <p className="login-chain">Schoolsoft staff, above every chain.</p>
          </div>

          <ul className="login-facts">
            <li>
              <strong>A separate list of accounts.</strong> Platform accounts are
              their own register. Holding one at a school, or at a chain&apos;s HQ,
              grants nothing here.
            </li>
            <li>
              <strong>Every session is audited.</strong> What an operator reads and
              changes inside a customer&apos;s chain is recorded against this
              account, and the customer can be shown it.
            </li>
          </ul>
        </div>

        <div className="login-aside-foot">
          <p>
            School staff and chain HQ sign in at the school&apos;s own address.
            {SCHOOL_APP_URL && (
              <>
                {" "}
                <a href={SCHOOL_APP_URL}>Take me there</a>.
              </>
            )}
          </p>
        </div>
      </aside>

      <section className="login-form">
        <div className="login-form-inner">
          <h2>Sign in</h2>
          <p className="login-lede">
            Use your Schoolsoft work email. A 6-digit code follows, good for five
            minutes.
          </p>

          {step === "identify" ? (
            <form onSubmit={onStart}>
              <div className="login-field">
                <label htmlFor="email">Work email</label>
                <input
                  id="email"
                  type="email"
                  autoComplete="username"
                  placeholder="you@schoolsoft.app"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  required
                  disabled={submitting}
                />
              </div>

              <button className="login-primary" type="submit" disabled={submitting}>
                {submitting ? "Sending…" : "Send code"}
              </button>
            </form>
          ) : (
            <CodeStep
              sentTo={email}
              submitting={submitting}
              onSubmit={onVerify}
              onBack={() => {
                setStep("identify");
                setError(null);
              }}
            />
          )}

          {error && <div className="error-banner">{error}</div>}

          {IS_DEV && (
            <div className="login-dev">
              <code>DEV BUILD · any operator email accepts 000000</code>
              <span>Never rendered in a production build.</span>
            </div>
          )}
        </div>
      </section>
    </main>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 401) return "That code is not right. Check the digits and try again.";
    // One sentence for "no such account" and for "deactivated": the resolver
    // filters on is_active and 404s both, and telling them apart would say
    // which addresses belong to Schoolsoft operators. Start never says
    // anything at all — it answers "sent" whatever it was handed.
    if (err.status === 404) return "That address cannot sign in here. Platform accounts are a separate list.";
    if (err.status === 403) return err.message;
    return `${err.code ?? "error"}: ${err.message}`;
  }
  return err instanceof Error ? err.message : "Unknown error";
}
