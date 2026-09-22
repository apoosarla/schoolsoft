"use client";

import { Fragment, useState } from "react";
import {
  CreateSchoolRequest,
  FirstAdminRequest,
  SchoolDto,
  SchoolHandoverDto,
  SchoolReadinessDto,
} from "@/lib/api";

const BOARD_CODES = ["CBSE", "CIE", "ICSE", "IB", "STATE"];

/**
 * The schools in one chain, and the form that opens another.
 *
 * Schoolsoft's operators have a table of their own in platform-web, over the
 * same act done on a chain's behalf. This one is deliberately not shared with
 * it: that copy carries a headcount per school and names the chain's schema
 * in its copy, both of which are the vendor's view of a customer, and keeping
 * one component honest about two audiences was already costing two props that
 * meant "not for you".
 *
 * What this cannot do is finish a school's setup — that is built on screens
 * belonging to the school, by the people who work there, and the checklist
 * here is read-only for that reason. With one exception, and it is the
 * exception that makes the rest possible: the first person who can run the
 * school has to be appointed from outside it, because until they exist there
 * is nobody inside to do it.
 */
export default function SchoolsPanel({
  schools,
  loading,
  error,
  onCreate,
  loadReadiness,
  onAppointAdmin,
}: {
  schools: SchoolDto[] | null;
  loading: boolean;
  error: string | null;
  onCreate: (req: CreateSchoolRequest) => Promise<string>;
  loadReadiness: (schoolId: string) => Promise<SchoolReadinessDto>;
  onAppointAdmin: (schoolId: string, req: FirstAdminRequest) => Promise<SchoolHandoverDto>;
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
        `"${created}" created in your chain. It is in draft — nobody outside its office can sign in ` +
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

  return (
    <>
      <div className="panel">
        <h2>Open a school in this chain</h2>
        <p className="hint">
          Creates the school inside your chain in <code>draft</code>. Its campuses, year,
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
                      <td>
                        <button type="button" onClick={() => toggleReadiness(s.id)}>
                          {expanded === s.id ? "Hide setup" : "Setup"}
                        </button>
                      </td>
                    </tr>
                    {expanded === s.id && (
                      <tr>
                        <td colSpan={5}>
                          {state === "loading" && <span className="hint">Loading the checklist&hellip;</span>}
                          {state === "error" && (
                            <span className="hint">Could not read this school&apos;s checklist.</span>
                          )}
                          {state && state !== "loading" && state !== "error" && (
                            <Checklist
                              readiness={state}
                              onAppoint={(req) => onAppointAdmin(s.id, req)}
                              onAppointed={(next) =>
                                setReadiness((r) => ({ ...r, [s.id]: next }))
                              }
                            />
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
    </>
  );
}

function Lifecycle({ school }: { school: SchoolDto }) {
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
 * What is left. Read-only but for one step: until the school has somebody who
 * can run it, there is nobody there to do any of the rest, so the form that
 * appoints that person lives here. Once it is done the form is gone, and
 * every later hire happens on the school's own screens.
 */
function Checklist({
  readiness,
  onAppoint,
  onAppointed,
}: {
  readiness: SchoolReadinessDto;
  onAppoint: (req: FirstAdminRequest) => Promise<SchoolHandoverDto>;
  onAppointed: (readiness: SchoolReadinessDto) => void;
}) {
  const admin = readiness.steps.find((s) => s.key === "admin_account");
  const campus = readiness.steps.find((s) => s.key === "campus");
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
      {admin && !admin.done && (
        <FirstAdminForm
          onAppoint={onAppoint}
          onAppointed={onAppointed}
          why={admin.why}
          needsCampus={!campus?.done}
        />
      )}
    </div>
  );
}

/** The three built-in roles that carry `structure.manage`. A chain that built
 *  its own role can type its code instead — the server checks the grant, not
 *  the name, and says so if the role cannot set a school up. */
const RUNS_THE_SCHOOL_ROLES = ["principal", "vice_principal", "it_admin"];

function FirstAdminForm({
  onAppoint,
  onAppointed,
  why,
  needsCampus,
}: {
  onAppoint: (req: FirstAdminRequest) => Promise<SchoolHandoverDto>;
  onAppointed: (readiness: SchoolReadinessDto) => void;
  why: string;
  /** A staff row hangs off a campus, so a school with none gets its first one
   *  here. Asked rather than assumed: the school has a name for it, and it is
   *  the name every section and timetable will carry afterwards. */
  needsCampus: boolean;
}) {
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [email, setEmail] = useState("");
  const [roleCode, setRoleCode] = useState(RUNS_THE_SCHOOL_ROLES[0]);
  const [campusName, setCampusName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<SchoolHandoverDto | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const handover = await onAppoint({
        firstName: firstName.trim(),
        lastName: lastName.trim() || undefined,
        email: email.trim(),
        roleCode,
        campusName: needsCampus ? campusName.trim() || undefined : undefined,
      });
      setDone(handover);
      onAppointed(handover.readiness);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <p className="hint" style={{ marginBottom: 0 }}>
        {done.staff.firstName} {done.staff.lastName ?? ""} can sign in as{" "}
        <code>{done.signsInWith}</code> and set the school up
        {done.campusCreated ? ", from the campus created with them" : ""}. Everybody else there is
        added by them, on the school&apos;s own screens.
      </p>
    );
  }

  return (
    <form onSubmit={submit} style={{ marginTop: 14 }}>
      <p className="hint" style={{ marginTop: 0 }}>
        Hand the school over. {why}
        {needsCampus
          ? " They need a campus to work at, so this school's first one is created with them."
          : ""}
      </p>
      <div className="form-row">
        <input
          placeholder="First name"
          value={firstName}
          onChange={(e) => setFirstName(e.target.value)}
          disabled={busy}
          required
        />
        <input
          placeholder="Last name"
          value={lastName}
          onChange={(e) => setLastName(e.target.value)}
          disabled={busy}
        />
        <input
          type="email"
          placeholder="Email they sign in with"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          disabled={busy}
          required
          style={{ minWidth: 240 }}
        />
        {needsCampus && (
          <input
            placeholder="Campus name (Main Campus)"
            value={campusName}
            onChange={(e) => setCampusName(e.target.value)}
            disabled={busy}
            style={{ minWidth: 200 }}
          />
        )}
        <select value={roleCode} onChange={(e) => setRoleCode(e.target.value)} disabled={busy}>
          {RUNS_THE_SCHOOL_ROLES.map((r) => (
            <option key={r} value={r}>
              {r}
            </option>
          ))}
        </select>
        <button type="submit" disabled={busy}>
          {busy ? "Appointing…" : "Appoint"}
        </button>
      </div>
      {error && <div className="error-banner">{error}</div>}
    </form>
  );
}
