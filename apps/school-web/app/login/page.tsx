"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { CodeStep } from "@schoolsoft/ui";
import {
  ApiError,
  TenantResolution,
  resolveTenant,
  startOtp,
  verifyOtp,
} from "@/lib/api";

const IS_DEV = process.env.NODE_ENV !== "production";

/**
 * Staff sign-in.
 *
 * The school comes from the address the browser arrived on, never from a
 * picker: a list of schools is a list of customers, and a searchable one hands
 * every competitor the roster. `GET /v1/public/tenant` resolves one host to one
 * tenant or answers 404, and there is no endpoint that lists them.
 *
 * An address nobody has claimed is an ordinary state, not an error — it is
 * every dev machine, and every mistyped domain. The page then asks for the
 * school's address rather than for a chain slug, because a slug is an internal
 * identifier no teacher has ever been told. Dev builds keep a slug field, since
 * localhost resolves to nothing and the flow still has to be workable.
 */
export default function LoginPage() {
  const router = useRouter();
  const [tenant, setTenant] = useState<TenantResolution | null>(null);
  const [resolving, setResolving] = useState(true);
  const [step, setStep] = useState<"identify" | "verify">("identify");
  const [identifier, setIdentifier] = useState("");
  const [chainSlug, setChainSlug] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    resolveTenant(window.location.hostname)
      .then((t) => {
        if (!live) return;
        setTenant(t);
        if (t?.chainSlug) setChainSlug(t.chainSlug);
      })
      .catch(() => {
        // The resolver being down must not lock anybody out: fall through to
        // the unresolved layout, which can still take an address by hand.
      })
      .finally(() => live && setResolving(false));
    return () => {
      live = false;
    };
  }, []);

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

  async function onVerify(code: string) {
    setError(null);
    setSubmitting(true);
    try {
      await verifyOtp(identifier.trim(), chainSlug.trim(), code);
      router.replace("/dashboard");
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSubmitting(false);
    }
  }

  const schoolName = tenant?.schoolName ?? null;
  const appName = tenant?.appName ?? "Schoolsoft";
  const initials = schoolName ? monogram(schoolName) : "S";

  return (
    <main className="login">
      <aside className="login-brand">
        {tenant?.vendorBranded !== false && (
          <div className="login-vendor">
            <span className="login-vendor-mark">S</span>
            <span className="login-vendor-name">Schoolsoft</span>
            <span className="login-vendor-rule" />
            <span className="login-vendor-tag">School</span>
          </div>
        )}

        <div className="login-identity">
          <div className="login-crest">{initials}</div>
          <h1>{schoolName ?? "Staff sign-in"}</h1>
          {tenant?.chainName && <p className="login-chain">{tenant.chainName}</p>}
        </div>

        <div className="login-address">
          {!resolving && (
            <code>{typeof window === "undefined" ? "" : window.location.hostname}</code>
          )}
          <p>
            {tenant
              ? "The school is read from this address. Nobody picks it from a list, and nobody types a chain slug."
              : "No school answers on this address yet. Tell us which one you are looking for and we will take you to its door."}
          </p>
        </div>

        <div className="login-aside-foot">
          <p>Parents and guardians use the {appName} app.</p>
        </div>
      </aside>

      <section className="login-form">
        <div className="login-form-inner">
          <h2>Staff sign-in</h2>
          <p className="login-lede">
            Use the email address or mobile number the school office has on record for you.
          </p>

          {step === "identify" ? (
            <form onSubmit={onStart}>
              {tenant?.schoolName ? (
                <div className="login-tenant">
                  <div>
                    <span className="login-tenant-label">Signing in to</span>
                    <span className="login-tenant-name">{tenant.schoolName}</span>
                  </div>
                  <Link href="/login/find-school">Not your school?</Link>
                </div>
              ) : (
                <div className="login-unresolved">
                  <label htmlFor="school-address">Your school&apos;s web address</label>
                  <div className="login-row">
                    <input
                      id="school-address"
                      inputMode="url"
                      placeholder="staff.yourschool.edu.in"
                      disabled={submitting}
                      onChange={(e) => setChainSlug(hostToSlug(e.target.value))}
                    />
                    <Link className="login-link-button" href="/login/find-school">
                      I don&apos;t have it
                    </Link>
                  </div>
                  {IS_DEV && (
                    <div className="login-devfield">
                      <label htmlFor="chain-slug">Chain slug (dev)</label>
                      <input
                        id="chain-slug"
                        value={chainSlug}
                        placeholder="smoketest"
                        disabled={submitting}
                        onChange={(e) => setChainSlug(e.target.value)}
                      />
                    </div>
                  )}
                </div>
              )}

              <div className="login-field">
                <label htmlFor="identifier">Email or mobile</label>
                <input
                  id="identifier"
                  autoComplete="username"
                  value={identifier}
                  onChange={(e) => setIdentifier(e.target.value)}
                  required
                  disabled={submitting}
                />
              </div>

              <button className="login-primary" type="submit" disabled={submitting}>
                {submitting ? "Sending…" : "Send code"}
              </button>

              <div className="login-foot">
                <span>A 6-digit code, good for 5 minutes.</span>
                <Link href="/login/find-school">Trouble signing in?</Link>
              </div>
            </form>
          ) : (
            <CodeStep
              sentTo={identifier}
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
              <code>DEV BUILD · any identifier accepts 000000</code>
              <span>Never rendered in a production build.</span>
            </div>
          )}
        </div>
      </section>
    </main>
  );
}

/** Two letters off a school's name, for the crest we do not have a logo for yet. */
function monogram(name: string): string {
  const words = name.replace(/[^\p{L}\s]/gu, " ").split(/\s+/).filter(Boolean);
  if (words.length === 0) return "S";
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase();
  return (words[0][0] + words[1][0]).toUpperCase();
}

/**
 * `stvincent.ridgeview.schoolsoft.app` names the chain in its second label.
 * A typed address is a hint, not a resolution — the API still decides.
 */
function hostToSlug(input: string): string {
  const host = input.trim().toLowerCase().replace(/^https?:\/\//, "").split("/")[0];
  const labels = host.split(".").filter(Boolean);
  return labels.length >= 2 ? labels[1] : labels[0] ?? "";
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 401) return "That code is not right. Check the digits and try again.";
    if (err.status === 404) {
      return "The office has no record of that email or mobile. They can add it in a minute — it is not something you can fix here.";
    }
    if (err.status === 403) return err.message;
    return `${err.code ?? "error"}: ${err.message}`;
  }
  return err instanceof Error ? err.message : "Unknown error";
}
