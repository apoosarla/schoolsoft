"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  SchoolDto,
  clearToken,
  createMySchool,
  getMySchoolReadiness,
  getSessionKind,
  isLoggedIn,
  listMySchools,
} from "@/lib/api";
import SchoolsPanel from "../schools-panel";

/**
 * A chain's own HQ, for the chain's own admin.
 *
 * Everything here goes through `/v1/tenancy/*` with their token, so the chain
 * they see is the one their token names — there is no chain id in this page
 * because there is no other chain they could ask about.
 *
 * They read every school in it and change one thing: they open a school. The
 * rest of a school's setup belongs to the school, and the checklist below
 * says how far along each one is without offering to do any of it.
 */
export default function MyChainPage() {
  const router = useRouter();
  const [ready, setReady] = useState(false);
  const [schools, setSchools] = useState<SchoolDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setSchools(await listMySchools());
    } catch (err) {
      setSchools(null);
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!isLoggedIn()) {
      router.replace("/login");
      return;
    }
    if (getSessionKind() === "platform") {
      router.replace("/chains");
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

  const draft = (schools ?? []).filter((s) => s.lifecycle !== "live").length;

  return (
    <main className="shell">
      <div className="panel">
        <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
          <div>
            <h2>Your chain</h2>
            <p className="hint">
              {schools === null
                ? "Loading…"
                : `${schools.length} ${schools.length === 1 ? "school" : "schools"}` +
                  (draft > 0 ? ` · ${draft} still being set up` : "")}
            </p>
          </div>
          <button onClick={signOut} type="button">
            Sign out
          </button>
        </div>
      </div>

      <SchoolsPanel
        schools={schools}
        loading={loading}
        error={error}
        where="your chain"
        whereIsSchema={false}
        onCreate={async (req) => {
          const created = await createMySchool(req);
          await refresh();
          return created.name;
        }}
        loadReadiness={getMySchoolReadiness}
      />
    </main>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 403) {
      return "Forbidden — this account is not a chain HQ admin, or cannot open schools.";
    }
    if (err.status === 401) return "Unauthorized — token missing, expired, or invalid.";
    return `${err.code ?? "error"}: ${err.message}`;
  }
  return err instanceof Error ? err.message : "Unknown error";
}
