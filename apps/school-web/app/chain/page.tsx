"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  SchoolDto,
  appointFirstAdmin,
  createMySchool,
  getSchoolReadiness,
  getSession,
  isChainAdmin,
  listMySchools,
} from "@/lib/api";
import SchoolsPanel from "./schools-panel";
import AdminsPanel from "./admins-panel";

/**
 * A chain's own HQ, inside the school's own app.
 *
 * It used to be a separate console, next to the one Schoolsoft's operators
 * use. That put a chain's admin in the vendor's building: the app they signed
 * in to was the one built for us, and the only thing it had in common with
 * their schools was that both had a login. What they actually do — open a
 * school, then watch it being set up — is the first page of the same story
 * the office finishes on the Setup screen, so it belongs in the same app.
 *
 * Everything here goes through `/v1/tenancy/*` with their token, so the chain
 * they see is the one their token names; there is no chain id on this page
 * because there is no other chain they could ask about.
 *
 * They read every school in it and open new ones, and they decide who else
 * signs in as HQ (the panel at the foot of the page). The
 * rest of a school's setup belongs to the school, and the checklist below
 * says how far along each one is without offering to do any of it.
 */
export default function ChainPage() {
  const router = useRouter();
  const [ready, setReady] = useState(false);
  const [selfAccountId, setSelfAccountId] = useState("");
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
    const session = getSession();
    if (!session) {
      router.replace("/login");
      return;
    }
    // The office has no business on this page, and the API would refuse them
    // anyway — `school.onboard` is the three heads' too, but the chain-wide
    // list is not: their token names a school, so it would answer with one.
    if (!isChainAdmin(session)) {
      router.replace("/dashboard");
      return;
    }
    setSelfAccountId(session.userAccountId);
    setReady(true);
  }, [router]);

  useEffect(() => {
    if (ready) refresh();
  }, [ready, refresh]);

  if (!ready) return null;

  const draft = (schools ?? []).filter((s) => s.lifecycle !== "live").length;

  return (
    <main className="shell">
      <div className="panel">
        {/* No heading: the shell's topbar already says "Your chain", and two
            of them one above the other read as a mistake. */}
        <p className="hint" style={{ margin: 0 }}>
          {schools === null
            ? "Loading…"
            : `${schools.length} ${schools.length === 1 ? "school" : "schools"}` +
              (draft > 0 ? ` · ${draft} still being set up` : "")}
        </p>
      </div>

      <SchoolsPanel
        schools={schools}
        loading={loading}
        error={error}
        onCreate={async (req) => {
          const created = await createMySchool(req);
          await refresh();
          return created.name;
        }}
        loadReadiness={getSchoolReadiness}
        onAppointAdmin={appointFirstAdmin}
      />

      <AdminsPanel selfAccountId={selfAccountId} />
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
