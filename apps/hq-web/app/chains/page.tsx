"use client";

import { Fragment, useEffect, useState } from "react";
import {
  ApiError,
  ChainDto,
  ChainStatsDto,
  clearToken,
  getChainStats,
  getToken,
  listChains,
  provisionChain,
  setToken,
} from "@/lib/api";

const PLAN_CODES = ["starter", "growth", "enterprise"];

export default function ChainsPage() {
  const [tokenInput, setTokenInput] = useState("");
  const [hasToken, setHasToken] = useState(false);

  const [chains, setChains] = useState<ChainDto[] | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const [slug, setSlug] = useState("");
  const [name, setName] = useState("");
  const [planCode, setPlanCode] = useState("starter");
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [lastResult, setLastResult] = useState<string | null>(null);

  const [expandedChainId, setExpandedChainId] = useState<string | null>(null);
  const [stats, setStats] = useState<Record<string, ChainStatsDto | "loading" | "error">>({});

  async function toggleStats(chainId: string) {
    if (expandedChainId === chainId) {
      setExpandedChainId(null);
      return;
    }
    setExpandedChainId(chainId);
    if (stats[chainId] && stats[chainId] !== "error") return;
    setStats((s) => ({ ...s, [chainId]: "loading" }));
    try {
      const result = await getChainStats(chainId);
      setStats((s) => ({ ...s, [chainId]: result }));
    } catch {
      setStats((s) => ({ ...s, [chainId]: "error" }));
    }
  }

  useEffect(() => {
    setHasToken(!!getToken());
  }, []);

  async function refresh() {
    setLoading(true);
    setListError(null);
    try {
      const result = await listChains();
      setChains(result);
    } catch (err) {
      setChains(null);
      setListError(describeError(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (hasToken) refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hasToken]);

  function saveToken() {
    if (!tokenInput.trim()) return;
    setToken(tokenInput.trim());
    setTokenInput("");
    setHasToken(true);
  }

  function signOut() {
    clearToken();
    setHasToken(false);
    setChains(null);
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFormError(null);
    setLastResult(null);
    setSubmitting(true);
    try {
      const res = await provisionChain({ slug: slug.trim(), name: name.trim(), planCode });
      setLastResult(
        res.created
          ? `Provisioned "${res.schemaName}" (chain ${res.chainId}).`
          : `Chain already existed — migrations re-applied for "${res.schemaName}".`
      );
      setSlug("");
      setName("");
      await refresh();
    } catch (err) {
      setFormError(describeError(err));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="shell">
      <div className="panel">
        <h2>Platform-admin session</h2>
        {hasToken ? (
          <div className="form-row" style={{ alignItems: "center" }}>
            <span className="badge badge-active">token set</span>
            <button onClick={signOut} type="button">
              Clear token
            </button>
          </div>
        ) : (
          <>
            <p className="hint">
              No platform-admin login flow exists yet (see BACKLOG.md) —
              paste a bearer token for a <code>platform_admin</code> account
              to use this console.
            </p>
            <div className="form-row">
              <input
                type="password"
                placeholder="Bearer token"
                value={tokenInput}
                onChange={(e) => setTokenInput(e.target.value)}
                style={{ minWidth: 360 }}
              />
              <button onClick={saveToken} type="button">
                Save token
              </button>
            </div>
          </>
        )}
      </div>

      <div className="panel">
        <h2>Onboard a new chain</h2>
        <p className="hint">
          Creates the chain's <code>chain_&lt;slug&gt;</code> schema and runs
          migrations against it (POST /v1/platform-admin/chains). Idempotent
          on slug — safe to retry.
        </p>
        <form onSubmit={onSubmit}>
          <div className="form-row">
            <input
              placeholder="slug (e.g. oakridge)"
              value={slug}
              onChange={(e) => setSlug(e.target.value)}
              disabled={!hasToken || submitting}
              required
            />
            <input
              placeholder="Chain name (e.g. Oakridge International)"
              value={name}
              onChange={(e) => setName(e.target.value)}
              disabled={!hasToken || submitting}
              required
              style={{ minWidth: 260 }}
            />
            <select
              value={planCode}
              onChange={(e) => setPlanCode(e.target.value)}
              disabled={!hasToken || submitting}
            >
              {PLAN_CODES.map((p) => (
                <option key={p} value={p}>
                  {p}
                </option>
              ))}
            </select>
            <button type="submit" disabled={!hasToken || submitting}>
              {submitting ? "Provisioning…" : "Provision chain"}
            </button>
          </div>
          <p className="hint">
            Slug must be lower_snake, 3–40 chars — matches{" "}
            <code>ChainProvisioningService</code>'s validation.
          </p>
        </form>
        {formError && <div className="error-banner">{formError}</div>}
        {lastResult && <p className="hint">{lastResult}</p>}
      </div>

      <div className="panel">
        <h2>Chains</h2>
        {!hasToken && <p className="hint">Set a token above to load chains.</p>}
        {hasToken && loading && <p className="hint">Loading…</p>}
        {listError && <div className="error-banner">{listError}</div>}
        {chains && chains.length === 0 && <p className="hint">No chains yet.</p>}
        {chains && chains.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Slug</th>
                <th>Schema</th>
                <th>Plan</th>
                <th>Region</th>
                <th>Status</th>
                <th>Schema v.</th>
                <th>Created</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {chains.map((c) => {
                const rowStats = stats[c.id];
                return (
                  <Fragment key={c.id}>
                    <tr>
                      <td>{c.name}</td>
                      <td>{c.slug}</td>
                      <td>{c.schemaName}</td>
                      <td>{c.planCode}</td>
                      <td>{c.region}</td>
                      <td>
                        <span className={`badge ${c.status === "active" ? "badge-active" : "badge-suspended"}`}>
                          {c.status}
                        </span>
                      </td>
                      <td>{c.schemaVersion}</td>
                      <td>{new Date(c.createdAt).toLocaleDateString()}</td>
                      <td>
                        <button type="button" onClick={() => toggleStats(c.id)}>
                          {expandedChainId === c.id ? "Hide stats" : "Stats"}
                        </button>
                      </td>
                    </tr>
                    {expandedChainId === c.id && (
                      <tr>
                        <td colSpan={9}>
                          {rowStats === "loading" && <span className="hint">Loading stats…</span>}
                          {rowStats === "error" && (
                            <span className="hint">Failed to load stats for this chain.</span>
                          )}
                          {rowStats && rowStats !== "loading" && rowStats !== "error" && (
                            <div className="form-row" style={{ gap: "2rem" }}>
                              <span>
                                <strong>{rowStats.schoolCount}</strong> schools
                              </span>
                              <span>
                                <strong>{rowStats.activeEnrolments}</strong> active enrolments
                              </span>
                              <span>
                                <strong>{rowStats.staffCount}</strong> active staff
                              </span>
                              <span>
                                <strong>
                                  {rowStats.feeCollectedTotal.toLocaleString(undefined, {
                                    style: "currency",
                                    currency: "INR",
                                    maximumFractionDigits: 0,
                                  })}
                                </strong>{" "}
                                fees collected
                              </span>
                            </div>
                          )}
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

function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 403) return "Forbidden — this token isn't a platform_admin account.";
    if (err.status === 401) return "Unauthorized — token missing, expired, or invalid.";
    return `${err.code ?? "error"}: ${err.message}`;
  }
  return err instanceof Error ? err.message : "Unknown error";
}
