"use client";

import { Fragment, useState } from "react";
import { CreateSchoolRequest, SchoolReadinessDto } from "@/lib/api";

const BOARD_CODES = ["CBSE", "CIE", "ICSE", "IB", "STATE"];

/**
 * One school as either console shows it. The operator's list carries a
 * headcount and the chain's does not, so it is optional rather than two
 * tables that drift.
 */
export type SchoolRow = {
  id: string;
  slug: string;
  name: string;
  boardCode: string;
  lifecycle: "draft" | "live" | "suspended";
  wentLiveAt?: string | null;
  activeEnrolments?: number | null;
};

/**
 * The schools in one chain, and the form that opens another.
 *
 * Shared by the operator's console and the chain's own, because it is the
 * same act from two doors: Schoolsoft staff doing it on a chain's behalf,
 * and the chain doing it itself. What neither can do from here is finish a
 * school's setup — that is built on screens belonging to the school, by the
 * people who work there, and the checklist here is read-only for that reason.
 */
export default function SchoolsPanel({
  schools,
  loading,
  error,
  where,
  whereIsSchema = true,
  onCreate,
  loadReadiness,
}: {
  schools: SchoolRow[] | null;
  loading: boolean;
  error: string | null;
  /**
   * Where the school lands, in the copy, so nobody wonders. The operator
   * names the schema and reads it as code; a chain admin has only one and
   * calls it theirs.
   */
  where: string;
  whereIsSchema?: boolean;
  onCreate: (req: CreateSchoolRequest) => Promise<string>;
  loadReadiness: (schoolId: string) => Promise<SchoolReadinessDto>;
}) {
  const [slug, setSlug] = useState("");
  const [name, setName] = useState("");
  const [boardCode, setBoardCode] = useState(BOARD_CODES[0]);
  const [stateCode, setStateCode] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [lastResult, setLastResult] = useState<string | null>(null);

  const [expanded, setExpanded] = useState<string | null>(null);
  const [readiness, setReadiness] = useState<Record<string, SchoolReadinessDto | "loading" | "error">>({});

  /** The checklist is ten counting queries per school, so it is asked for one school at a time. */
  async function toggleReadiness(schoolId: string) {
    if (expanded === schoolId) {
      setExpanded(null);
      return;
    }
    setExpanded(schoolId);
    setReadiness((r) => ({ ...r, [schoolId]: "loading" }));
    try {
      const answer = await loadReadiness(schoolId);
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
      const created = await onCreate({
        slug: slug.trim(),
        name: name.trim(),
        boardCode,
        stateCode: stateCode.trim() || undefined,
      });
      setLastResult(
        `"${created}" created in ${where}. It is in draft — nobody outside its office can sign in ` +
          "until its setup is finished and somebody there opens it."
      );
      setSlug("");
      setName("");
      setStateCode("");
    } catch (err) {
      setFormError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setSubmitting(false);
    }
  }

  const showsHeadcount = (schools ?? []).some((s) => s.activeEnrolments != null);

  return (
    <>
      <div className="panel">
        <h2>Open a school in this chain</h2>
        <p className="hint">
          Creates the school inside {whereIsSchema ? <code>{where}</code> : where} in{" "}
          <code>draft</code>. Its campuses, year,
          grades and sections are built by the school itself, on its own Setup screen.
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
                {showsHeadcount && <th>On the register</th>}
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
                      {showsHeadcount && <td>{(s.activeEnrolments ?? 0).toLocaleString()}</td>}
                      <td>
                        <button type="button" onClick={() => toggleReadiness(s.id)}>
                          {expanded === s.id ? "Hide setup" : "Setup"}
                        </button>
                      </td>
                    </tr>
                    {expanded === s.id && (
                      <tr>
                        <td colSpan={showsHeadcount ? 6 : 5}>
                          {state === "loading" && <span className="hint">Loading the checklist&hellip;</span>}
                          {state === "error" && (
                            <span className="hint">Could not read this school&apos;s checklist.</span>
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
    </>
  );
}

function Lifecycle({ school }: { school: SchoolRow }) {
  if (school.lifecycle === "live") {
    return (
      <span
        className="badge badge-active"
        title={school.wentLiveAt ? `Opened ${new Date(school.wentLiveAt).toLocaleDateString()}` : undefined}
      >
        open
      </span>
    );
  }
  if (school.lifecycle === "suspended") return <span className="badge badge-suspended">suspended</span>;
  return <span className="badge">draft</span>;
}

/**
 * What is left, read-only. Seeing that a school is waiting on its sections is
 * oversight; building them is the school's own work.
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
