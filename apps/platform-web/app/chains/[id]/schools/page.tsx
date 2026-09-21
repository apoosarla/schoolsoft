"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import {
  ApiError,
  ChainDto,
  ChainSchoolDto,
  clearToken,
  createChainSchool,
  getChainSchoolReadiness,
  isLoggedIn,
  listChainSchools,
  listChains,
} from "@/lib/api";
import SchoolsPanel from "../../../schools-panel";

/**
 * The operator's view of one chain's schools. The chain's own admin sees the
 * same act in school-web, at `/chain`, through their own endpoints — this
 * page reaches into a chain the caller does not belong to, which is why every
 * read behind it is platform-admin only.
 */
export default function ChainSchoolsPage() {
  const router = useRouter();
  const params = useParams<{ id: string }>();
  const chainId = params.id;

  const [ready, setReady] = useState(false);
  const [chain, setChain] = useState<ChainDto | null>(null);
  const [schools, setSchools] = useState<ChainSchoolDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

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
    setReady(true);
  }, [router]);

  useEffect(() => {
    if (ready) refresh();
  }, [ready, refresh]);

  function signOut() {
    clearToken();
    router.replace("/login");
  }

  if (!ready) return null;

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

      <SchoolsPanel
        schools={schools}
        loading={loading}
        error={error}
        where={chain?.schemaName ?? "the chain's schema"}
        onCreate={async (req) => {
          const created = await createChainSchool(chainId, req);
          await refresh();
          return created.name;
        }}
        loadReadiness={(schoolId) => getChainSchoolReadiness(chainId, schoolId)}
      />
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
