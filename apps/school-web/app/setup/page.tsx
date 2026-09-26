"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { ReasonField } from "@schoolsoft/ui";
import {
  AcademicYearDto,
  ApiError,
  GradeDto,
  OnboardingStepDto,
  SchoolReadinessDto,
  Session,
  createCampus,
  createGrade,
  createSection,
  getSchoolReadiness,
  getSession,
  goLive,
  hasScreen,
  listAcademicYears,
  listGrades,
  listStrategyCodes,
  skipSetupStep,
  unskipSetupStep,
} from "@/lib/api";

/**
 * Opening a school. The list is not a wizard: schools do these out of order —
 * subjects before sections is normal — so every step is open at once and each
 * one says why it matters rather than only that it is missing.
 *
 * Where a screen already builds something, this links to it rather than
 * growing a second form for the same rows. Where nothing else can build it —
 * campuses, grades, sections — the form is here, because a school cannot reach
 * its first section through any other door.
 */
export default function SetupPage() {
  const router = useRouter();
  const [session, setSession] = useState<Session | null>(null);
  const [readiness, setReadiness] = useState<SchoolReadinessDto | null>(null);
  const [grades, setGrades] = useState<GradeDto[]>([]);
  const [years, setYears] = useState<AcademicYearDto[]>([]);
  const [strategies, setStrategies] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  /** The step whose skip reason is being typed. Asked inline rather than in a
   *  modal: the reason is written to the audit log and read months later by
   *  somebody asking why a school never set its fees up. */
  const [skipping, setSkipping] = useState<string | null>(null);

  const load = useCallback(async (schoolId: string) => {
    const [next, gradeList, yearList] = await Promise.all([
      getSchoolReadiness(schoolId),
      listGrades(schoolId),
      listAcademicYears(schoolId),
    ]);
    setReadiness(next);
    setGrades(gradeList);
    setYears(yearList);
  }, []);

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    if (!hasScreen(s, "setup")) {
      router.replace("/dashboard");
      return;
    }
    setSession(s);
    load(s.schoolId).catch((err) => setError(describeError(err)));
    listStrategyCodes()
      .then(setStrategies)
      .catch(() => setStrategies([]));
  }, [router, load]);

  /** Every action ends the same way: say what happened, then re-ask the server what is left. */
  async function run(what: string, action: () => Promise<unknown>) {
    if (!session) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await action();
      await load(session.schoolId);
      setNotice(what);
    } catch (err) {
      setError(describeError(err));
      // The checklist is derived, so a failed write may still have changed
      // something else. Re-read rather than trust the screen.
      await load(session.schoolId).catch(() => undefined);
    } finally {
      setBusy(false);
    }
  }

  if (!session || !readiness) {
    return (
      <main className="shell">
        <div className="panel">
          <p className="hint">{error ?? "Loading…"}</p>
        </div>
      </main>
    );
  }

  const blocking = readiness.steps.filter((s) => s.blocking);
  // Two steps have a real order behind them, enforced by the database rather
  // than by this screen: a section needs a year to hang off, and a staff row
  // needs a campus to land on.
  const campusDone = readiness.steps.some((s) => s.key === "campus" && s.done);
  const optional = readiness.steps.filter((s) => !s.blocking);
  const done = blocking.filter((s) => s.done).length;
  const currentYear = years.find((y) => y.isCurrent) ?? null;

  return (
    <main className="shell">
      <div className="panel">
        <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center", marginBottom: 8 }}>
          <h2 style={{ margin: 0 }}>School setup</h2>
          <span className={"badge " + (readiness.lifecycle === "live" ? "badge-active" : "")}>
            {readiness.lifecycle === "live" ? "open" : readiness.lifecycle}
          </span>
        </div>
        <p className="hint" style={{ margin: 0 }}>
          {readiness.lifecycle === "draft"
            ? "This school is not open yet. Work through what is left, then open it."
            : `This school opened${readiness.wentLiveAt ? " on " + new Date(readiness.wentLiveAt).toLocaleDateString() : ""}. The list stays here, because a school keeps being set up after it opens.`}
        </p>

        <div style={{ marginTop: 14 }}>
          <div
            style={{
              height: 8,
              borderRadius: 999,
              background: "var(--surface-2)",
              border: "1px solid var(--border)",
              overflow: "hidden",
            }}
            role="progressbar"
            aria-valuenow={done}
            aria-valuemin={0}
            aria-valuemax={blocking.length}
          >
            <div
              style={{
                width: `${blocking.length === 0 ? 100 : (done / blocking.length) * 100}%`,
                height: "100%",
                background: done === blocking.length ? "var(--success)" : "var(--accent)",
              }}
            />
          </div>
          <p className="hint" style={{ marginTop: 6 }}>
            {done} of {blocking.length} steps a school cannot open without.
          </p>
        </div>
      </div>

      {error && <div className="error-banner">{error}</div>}
      {notice && <div className="notice-banner">{notice}</div>}

      <div className="panel">
        <h3 style={{ marginTop: 0 }}>Before this school can open</h3>
        <div className="setting-list">
          {blocking.map((step) => (
            <Step key={step.key} step={step}>
              {control(step)}
            </Step>
          ))}
        </div>
      </div>

      <div className="panel">
        <h3 style={{ marginTop: 0 }}>Can wait until after it opens</h3>
        <div className="setting-list">
          {optional.map((step) => (
            <Step key={step.key} step={step}>
              <div className="form-row" style={{ gap: 8, alignItems: "center", margin: 0 }}>
                {control(step)}
                {step.skipped ? (
                  <button type="button" className="secondary" disabled={busy} onClick={() => unskip(step)}>
                    Put back
                  </button>
                ) : skipping === step.key ? (
                  <ReasonField
                    placeholder="Why it will not be done"
                    confirmLabel="Skip it"
                    busy={busy}
                    onCancel={() => setSkipping(null)}
                    onConfirm={(reason) => skip(step, reason)}
                  />
                ) : (
                  <button
                    type="button"
                    className="secondary"
                    disabled={busy || step.done}
                    onClick={() => setSkipping(step.key)}
                  >
                    Skip
                  </button>
                )}
              </div>
            </Step>
          ))}
        </div>
      </div>

      <div className="panel">
        <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center", margin: 0 }}>
          <div style={{ maxWidth: "62ch" }}>
            <strong>{readiness.lifecycle === "live" ? "This school is open" : "Open this school"}</strong>
            <p className="setting-why" style={{ marginTop: 3 }}>
              {readiness.lifecycle === "live"
                ? "Staff and families with an account here can sign in."
                : "From then, staff and families with an account here can sign in. Setup stays reachable and nothing on it is locked."}
            </p>
          </div>
          <button
            type="button"
            disabled={busy || readiness.lifecycle === "live"}
            onClick={() =>
              run("This school is open.", () => goLive(session.schoolId))
            }
          >
            {readiness.lifecycle === "live"
              ? readiness.wentLiveAt
                ? `Opened ${new Date(readiness.wentLiveAt).toLocaleDateString()}`
                : "Open"
              : "Open the school"}
          </button>
        </div>
      </div>
    </main>
  );

  // ------------------------------------------------------------------ actions

  function skip(step: OnboardingStepDto, reason: string) {
    setSkipping(null);
    run(`"${step.label}" will not be done.`, () => skipSetupStep(session!.schoolId, step.key, reason));
  }

  function unskip(step: OnboardingStepDto) {
    run(`"${step.label}" is back on the list.`, () => unskipSetupStep(session!.schoolId, step.key));
  }

  // ----------------------------------------------------------------- controls

  /**
   * What a step offers: a form where this is the only screen that can build
   * the rows, and the screen that already does otherwise.
   */
  function control(step: OnboardingStepDto) {
    if (step.done) return <DoneCount step={step} />;
    switch (step.key) {
      case "campus":
        return <CampusForm busy={busy} onAdd={(name) => run(`Campus "${name}" added.`, () => createCampus(session!.schoolId, { name, isPrimary: true }))} />;
      case "grades":
        return <GradeForm busy={busy} count={grades.length} onAdd={(code, name) => run(`Grade "${name}" added.`, () => createGrade(session!.schoolId, { code, name, sortOrder: grades.length + 1 }))} />;
      case "sections":
        return (
          <SectionForm
            busy={busy}
            grades={grades}
            strategies={strategies}
            year={currentYear}
            onAdd={(req) => run(`Section "${req.name}" added.`, () => createSection(session!.schoolId, req))}
          />
        );
      case "academic_year":
      case "terms":
      case "working_week":
        return <GoTo href="/calendar" label="Calendar &amp; Year" />;
      case "subjects":
        return <GoTo href="/academics" label="Academics" />;
      case "admin_account":
        // V018's trigger puts a new staff member on the school's primary
        // campus and refuses when there is none, so this step genuinely
        // cannot be done first. Say so here rather than let somebody find
        // out as a failed save on the Roles screen.
        return campusDone ? (
          <GoTo href="/roles" label="Roles &amp; Users" />
        ) : (
          <Needs what="a campus" />
        );
      case "fee_structure":
        return <GoTo href="/fees" label="Fees" />;
      case "theme":
        return <span className="hint">Set by the chain until this school picks its own.</span>;
      default:
        return null;
    }
  }
}

// ------------------------------------------------------------------- pieces

function Step({ step, children }: { step: OnboardingStepDto; children: React.ReactNode }) {
  return (
    <div className="setting">
      <div className="setting-text">
        <span className="setting-name">
          <Marker step={step} /> {step.label}
        </span>
        <p className="setting-why">{step.why}</p>
        {step.skipped && step.skipReason && (
          <p className="setting-why" style={{ marginTop: 4, fontStyle: "italic" }}>
            Skipped — &ldquo;{step.skipReason}&rdquo;
          </p>
        )}
      </div>
      <div className="setting-control">{children}</div>
    </div>
  );
}

function Marker({ step }: { step: OnboardingStepDto }) {
  if (step.done) return <span style={{ color: "var(--success)" }}>&#10003;</span>;
  if (step.skipped) return <span style={{ color: "var(--text-faint)" }}>&#8856;</span>;
  return <span style={{ color: step.blocking ? "var(--warning)" : "var(--text-faint)" }}>&#9675;</span>;
}

/** What is there, counted — a screen that says "12 grades" is easier to trust than a tick. */
function DoneCount({ step }: { step: OnboardingStepDto }) {
  return (
    <span className="hint" style={{ whiteSpace: "nowrap" }}>
      {step.count} {step.unit}
    </span>
  );
}

/** A step that cannot be done yet, and what it is waiting for. */
function Needs({ what }: { what: string }) {
  return <span className="hint" style={{ whiteSpace: "nowrap" }}>Needs {what} first.</span>;
}

/** The screen that already builds these rows. No second form for the same table. */
function GoTo({ href, label }: { href: string; label: string }) {
  return (
    <Link href={href} style={{ whiteSpace: "nowrap", fontSize: 13, fontWeight: 600 }}>
      Open {label} &rarr;
    </Link>
  );
}

function CampusForm({ busy, onAdd }: { busy: boolean; onAdd: (name: string) => void }) {
  const [name, setName] = useState("Main Campus");
  return (
    <div className="form-row" style={{ gap: 6, margin: 0 }}>
      <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Campus name" style={{ width: 170 }} />
      <button type="button" disabled={busy || !name.trim()} onClick={() => onAdd(name.trim())}>
        Add
      </button>
    </div>
  );
}

function GradeForm({ busy, count, onAdd }: { busy: boolean; count: number; onAdd: (code: string, name: string) => void }) {
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  return (
    <div className="form-row" style={{ gap: 6, margin: 0, flexWrap: "wrap", justifyContent: "flex-end" }}>
      <input value={code} onChange={(e) => setCode(e.target.value)} placeholder="Code" style={{ width: 78 }} />
      <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Grade name" style={{ width: 150 }} />
      <button type="button" disabled={busy || !code.trim() || !name.trim()} onClick={() => onAdd(code.trim(), name.trim())}>
        Add
      </button>
      {count > 0 && <span className="hint">{count} so far</span>}
    </div>
  );
}

function SectionForm({
  busy,
  grades,
  strategies,
  year,
  onAdd,
}: {
  busy: boolean;
  grades: GradeDto[];
  strategies: string[];
  year: AcademicYearDto | null;
  onAdd: (req: { gradeId: string; academicYearId: string; code: string; name: string; strategyCode: string; capacity: number | null }) => void;
}) {
  const [gradeId, setGradeId] = useState("");
  const [code, setCode] = useState("A");
  const [capacity, setCapacity] = useState("40");
  const [strategyCode, setStrategyCode] = useState("");

  // A section needs a grade and a year to hang off, so the form says which one
  // is missing rather than failing on submit.
  if (!year) return <Needs what="a current academic year" />;
  if (grades.length === 0) return <Needs what="a grade" />;

  const grade = grades.find((g) => g.id === gradeId) ?? grades[0];
  const strategy = strategyCode || strategies[0] || "";

  return (
    <div className="form-row" style={{ gap: 6, margin: 0, flexWrap: "wrap", justifyContent: "flex-end" }}>
      <select value={grade.id} onChange={(e) => setGradeId(e.target.value)}>
        {grades.map((g) => (
          <option key={g.id} value={g.id}>
            {g.name}
          </option>
        ))}
      </select>
      <input value={code} onChange={(e) => setCode(e.target.value)} placeholder="A" style={{ width: 56 }} />
      <input
        value={capacity}
        onChange={(e) => setCapacity(e.target.value)}
        type="number"
        min={1}
        style={{ width: 74 }}
        aria-label="Seats"
      />
      <select value={strategy} onChange={(e) => setStrategyCode(e.target.value)}>
        {strategies.map((s) => (
          <option key={s} value={s}>
            {s}
          </option>
        ))}
      </select>
      <button
        type="button"
        disabled={busy || !code.trim() || !strategy}
        onClick={() =>
          onAdd({
            gradeId: grade.id,
            academicYearId: year.id,
            code: code.trim(),
            name: `${grade.name}-${code.trim()}`,
            strategyCode: strategy,
            capacity: capacity.trim() === "" ? null : Number(capacity),
          })
        }
      >
        Add
      </button>
    </div>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 403) {
      return "You can read this checklist but not act on it — that needs school.onboard.";
    }
    // A 409 from go-live already names the steps that are open, and a
    // constraint violation names the rule it broke. Both say more than
    // anything this screen could invent.
    return err.message;
  }
  return err instanceof Error ? err.message : "Unknown error";
}
