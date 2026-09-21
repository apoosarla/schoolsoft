"use client";

import Link from "next/link";
import { Fragment, useCallback, useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import {
  ApiError,
  ChainDto,
  ChainSchoolDto,
  SchoolReadinessDto,
  clearToken,
  createChainSchool,
  getChainSchoolReadiness,
  isLoggedIn,
  listChainSchools,
  listChains,
} from "@/lib/api";

const BOARD_CODES = ["CBSE", "CIE", "ICSE", "IB", "STATE"];

/**
 * The schools inside one chain, and the form that opens another.
 *
 * A school starts in draft and stays there until somebody inside it says its
 * setup is done — this console can see how far along that is, and cannot
 * finish it: the steps are built on screens that belong to the school, by the
 * people who work there.
 */
export default function ChainSchoolsPage() {
  const router = useRouter();
  const params = useParams<{ id: string }>();
  const chainId = params.id;

  const [hasToken, setHasToken] = useState(false);
  const [chain, setChain] = useState<ChainDto | null>(null);
  const [schools, setSchools] = useState<ChainSchoolDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const [slug, setSlug] = useState("");
  const [name, setName] = useState("");
  const [boardCode, setBoardCode] = useState(BOARD_CODES[0]);
  const [stateCode, setStateCode] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [lastResult, setLastResult] = useState<string | null>(null);

  const [expanded, setExpanded] = useState<string | null>(null);
  const [readiness, setReadiness] = useState<Record<string, SchoolReadinessDto | "loading" | "error">>({});

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [chains, list] = await Promise.all([listChains(), listChainSchools(chainId)]);
      setChain(chains.find((c) => c.id === chainId) ?? null);
      setSchools(list);
    } catch (err) {
      setSchools(null);
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }, [chainId]);

  useEffect(() => {
    if (!isLoggedIn()) {
      router.replace("/login");
      return;
    }
    setHasToken(true);
  }, [router]);

  useEffect(() => {
    if (hasToken) refresh();
  }, [hasToken, refresh]);

  /** The checklist is ten queries per school, so it is asked for one school at a time. */
  async function toggleReadiness(schoolId: string) {
    if (expanded === schoolId) {
      setExpanded(null);
      return;
    }
    setExpanded(schoolId);
    setReadiness((r) => ({ ...r, [schoolId]: "loading" }));
    try {
      const answer = await getChainSchoolReadiness(chainId, schoolId);
      setReadiness((r) => ({ ...r, [schoolId]: answer }));
    } catch {
      setReadiness((r) => ({ ...r, [schoolId]: "error" }));
    }
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFormError(null);
    setLastResult(null);
    setSubmitting(true);
    try {
      const created = await createChainSchool(chainId, {
        slug: slug.trim(),
        name: name.trim(),
        boardCode,
        stateCode: stateCode.trim() || undefined,
      });
      setLastResult(
        `"${created.name}" created in ${chain?.schemaName ?? "this chain"}. It is in draft — ` +
          "nobody outside its office can sign in until its setup is finished and somebody there opens it."
      );
      setSlug("");
      setName("");
      setStateCode("");
      await refresh();
    } catch (err) {
      setFormError(describeError(err));
    } finally {
      setSubmitting(false);
    }
  }

  function signOut() {
    clearToken();
    router.replace("/login");
  }

  if (!hasToken) return null;

  return (
    <main className="shell">
      <div className="panel">
        <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
          <div>
            <h2>{chain ? chain.name : "Chain"}</h2>
            <p className="hint">
              {chain ? (
                <>
                  <code>{chain.schemaName}</code> · {chain.planCode} · schema v{chain.schemaVersion}
                </>
              ) : (
                "Loading…"
              )}
            </p>
          </div>
          <div className="form-row" style={{ alignItems: "center", marginBottom: 0 }}>
            <Link href="/chains">&larr; All chains</Link>
            <button onClick={signOut} type="button">
              Sign out
            </button>
          </div>
        </div>
      </div>

      <div className="panel">
        <h2>Open a school in this chain</h2>
        <p className="hint">
          Creates the school inside <code>{chain?.schemaName ?? "the chain's schema"}</code> in{" "}
          <code>draft</code>. Its campuses, year, grades and sections are built by the school itself,
          on its own Setup screen.
        </p>
        <form onSubmit={onSubmit}>
          <div className="form-row">
            <input
              placeholder="slug (e.g. oakridge-blr)"
              value={slug}
              onChange={(e) => setSlug(e.target.value)}
              disabled={submitting}
              required
            />
            <input
              placeholder="School name (e.g. Oakridge Bengaluru)"
              value={name}
              onChange={(e) => setName(e.target.value)}
              disabled={submitting}
              required
              style={{ minWidth: 240 }}
            />
            <select value={boardCode} onChange={(e) => setBoardCode(e.target.value)} disabled={submitting}>
              {BOARD_CODES.map((b) => (
                <option key={b} value={b}>
                  {b}
                </option>
              ))}
            </select>
            <input
              placeholder="State (e.g. KA)"
              value={stateCode}
              onChange={(e) => setStateCode(e.target.value)}
              disabled={submitting}
              style={{ width: 120 }}
            />
            <button type="submit" disabled={submitting}>
              {submitting ? "Creating…" : "Create school"}
            </button>
          </div>
        </form>
        {formError && <div className="error-banner">{formError}</div>}
        {lastResult && <p className="hint">{lastResult}</p>}
      </div>

      <div className="panel">
        <h2>Schools</h2>
        {loading && <p className="hint">Loading&hellip;</p>}
        {error && <div className="error-banner">{error}</div>}
        {schools && schools.length === 0 && <p className="hint">This chain has no schools yet.</p>}
        {schools && schools.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Slug</th>
                <th>Board</th>
                <th>State</th>
                <th>On the register</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {schools.map((s) => {
                const state = readiness[s.id];
                return (
                  <Fragment key={s.id}>
                    <tr>
                      <td>{s.name}</td>
                      <td>{s.slug}</td>
                      <td>{s.boardCode}</td>
                      <td>
                        <Lifecycle school={s} />
                      </td>
                      <td>{s.activeEnrolments.toLocaleString()}</td>
                      <td>
                        <button type="button" onClick={() => toggleReadiness(s.id)}>
                          {expanded === s.id ? "Hide setup" : "Setup"}
                        </button>
                      </td>
                    </tr>
                    {expanded === s.id && (
                      <tr>
                        <td colSpan={6}>
                          {state === "loading" && <span className="hint">Loading the checklist&hellip;</span>}
                          {state === "error" && (
                            <span className="hint">Could not read this school's checklist.</span>
                          )}
                          {state && state !== "loading" && state !== "error" && <Checklist readiness={state} />}
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        )}
      </div>
    </main>
  );
}

function Lifecycle({ school }: { school: ChainSchoolDto }) {
  if (school.lifecycle === "live") {
    return (
      <span className="badge badge-active" title={school.wentLiveAt ? `Opened ${new Date(school.wentLiveAt).toLocaleDateString()}` : undefined}>
        open
      </span>
    );
  }
  if (school.lifecycle === "suspended") return <span className="badge badge-suspended">suspended</span>;
  return <span className="badge">draft</span>;
}

/**
 * What is left, read-only. The operator can see that a school is waiting on
 * its sections; building them is the school's own work, on its own screens.
 */
function Checklist({ readiness }: { readiness: SchoolReadinessDto }) {
  const blocking = readiness.steps.filter((s) => s.blocking);
  const open = blocking.filter((s) => !s.done);
  return (
    <div>
      <p className="hint" style={{ marginTop: 0 }}>
        {readiness.lifecycle === "live"
          ? "Open."
          : open.length === 0
            ? "Everything it needs is in place — somebody at the school has to open it."
            : `${blocking.length - open.length} of ${blocking.length} steps done. Waiting on: ${open
                .map((s) => s.label)
                .join(", ")}.`}
      </p>
      <div className="form-row" style={{ gap: 14, flexWrap: "wrap", marginBottom: 0 }}>
        {readiness.steps.map((s) => (
          <span key={s.key} className="hint" title={s.why}>
            {s.done ? "✓" : s.skipped ? "⊘" : "○"} {s.label}
            {s.done ? ` (${s.count} ${s.unit})` : ""}
          </span>
        ))}
      </div>
    </div>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 403) return "Forbidden — this token isn't a platform_admin account.";
    if (err.status === 401) return "Unauthorized — token missing, expired, or invalid.";
    return `${err.code ?? "error"}: ${err.message}`;
  }
  return err instanceof Error ? err.message : "Unknown error";
}
