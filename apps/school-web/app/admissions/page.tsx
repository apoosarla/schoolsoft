"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import {
  AcademicYearDto,
  AdmissionApplicationDto,
  AdmissionFeeOwingDto,
  AdmissionFeeStatusDto,
  AdmissionFunnelSummaryDto,
  AdmissionPolicyDto,
  admissionMovesByState,
  admissionMovesForState,
  AdmissionSearchResultDto,
  ApiError,
  createAdmissionApplication,
  enrolAdmissionApplication,
  getAdmissionFeeStatus,
  getAdmissionPolicy,
  getGuardianLogin,
  GuardianLoginDto,
  getAdmissionSummary,
  getSession,
  GradeDto,
  hasScreen,
  listAcademicYears,
  listAdmissionApplications,
  listAdmissionFeesOwing,
  listGrades,
  listSections,
  recordPayment,
  refundAdmissionFee,
  searchAdmissionApplications,
  setGuardianLogin,
  SectionDto,
  Session,
  transitionAdmissionApplication,
} from "@/lib/api";
import { todayIso } from "@/lib/format";
import { ReasonField } from "@schoolsoft/ui";

const SOURCES = ["website", "walkin", "referral", "ad"];

const PAGE_SIZE = 25;

/** What the advanced panel can narrow by, empty. */
const emptyAdvanced = {
  name: "",
  dob: "",
  guardianPhone: "",
  applicationNo: "",
  gradeId: "",
  state: "",
  source: "",
};

/**
 * The thirteen states, grouped the way an admissions office talks about them.
 * The exact state keeps its own tile: `document_pending` and `fee_pending` are
 * different phone calls, so collapsing them into one "Application" number would
 * hide the only thing that says which call to make.
 */
const LANES: { key: string; label: string; states: string[] }[] = [
  { key: "new", label: "New", states: ["lead"] },
  { key: "application", label: "Application", states: ["application_started", "document_pending", "fee_pending"] },
  { key: "review", label: "Review", states: ["review"] },
  { key: "assessment", label: "Assessment", states: ["test_scheduled", "test_done"] },
  { key: "offer", label: "Offer", states: ["offered", "accepted", "waitlist"] },
  { key: "closed", label: "Closed", states: ["enrolled", "rejected", "lapsed"] },
];

/** What a state is called on screen. The wire keeps the snake_case. */
/** Moves that close an application for good — each asks twice. */
const CLOSING = ["rejected", "lapsed", "withdrawn"];

const STATE_LABEL: Record<string, string> = {
  lead: "Lead",
  application_started: "Started",
  document_pending: "Documents",
  fee_pending: "Fee",
  review: "Review",
  test_scheduled: "Test set",
  test_done: "Tested",
  offered: "Offered",
  accepted: "Accepted",
  waitlist: "Waitlist",
  enrolled: "Enrolled",
  rejected: "Rejected",
  lapsed: "Lapsed",
};

/** Stages nothing follows out of — they read as a record, not as work. */
const CLOSING_STATES = new Set(["enrolled", "rejected", "lapsed"]);

const emptyForm = {
  academicYearId: "",
  gradeId: "",
  applicationNo: "",
  applicantFirstName: "",
  applicantLastName: "",
  applicantDob: "",
  applicantGender: "",
  guardianName: "",
  guardianPhone: "",
  guardianEmail: "",
  source: SOURCES[0],
};

export default function AdmissionsPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [years, setYears] = useState<AcademicYearDto[] | null>(null);
  const [grades, setGrades] = useState<GradeDto[] | null>(null);
  const [sections, setSections] = useState<SectionDto[] | null>(null);
  const [policy, setPolicy] = useState<AdmissionPolicyDto | null>(null);
  const [summary, setSummary] = useState<AdmissionFunnelSummaryDto | null>(null);

  // Nothing is selected on arrival: the page opens on counts and fetches rows
  // only once somebody names a stage.
  const [selected, setSelected] = useState<string | null>(null);
  const [rows, setRows] = useState<AdmissionApplicationDto[] | null>(null);
  const [offset, setOffset] = useState(0);
  const [moves, setMoves] = useState<string[]>([]);
  const [loadingRows, setLoadingRows] = useState(false);

  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pendingMove, setPendingMove] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState(emptyForm);
  const [creating, setCreating] = useState(false);
  const [rowSection, setRowSection] = useState<Record<string, string>>({});

  // Search is the other way into the same rows: the tiles are for browsing a
  // stage, this is for finding one child when a parent rings up.
  const [q, setQ] = useState("");
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [adv, setAdv] = useState(emptyAdvanced);
  const [results, setResults] = useState<AdmissionSearchResultDto | null>(null);
  const [searchOffset, setSearchOffset] = useState(0);
  const [searching, setSearching] = useState(false);
  const [movesByState, setMovesByState] = useState<Record<string, string[]>>({});
  const [busyId, setBusyId] = useState<string | null>(null);
  // The fee stage's own column: what each application on the page owes. Null
  // for an application its school charges nothing.
  const [feeByApp, setFeeByApp] = useState<Record<string, AdmissionFeeStatusDto | null>>({});
  const [payment, setPayment] = useState<Record<string, { amount: string; method: string }>>({});
  // Applications that left the fee stage and owe the fee anyway.
  const [owing, setOwing] = useState<AdmissionFeeOwingDto[]>([]);
  const [refunding, setRefunding] = useState<string | null>(null);
  // The enrolled stage's own column: can each family sign in.
  const [loginByApp, setLoginByApp] = useState<Record<string, GuardianLoginDto | null>>({});
  const [loginDraft, setLoginDraft] = useState<Record<string, string>>({});

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    if (!hasScreen(s, "admissions")) {
      router.replace("/dashboard");
      return;
    }
    setSessionState(s);
    Promise.all([
      listAcademicYears(s.schoolId),
      listGrades(s.schoolId),
      listSections(s.schoolId),
      getAdmissionPolicy(s.schoolId),
      admissionMovesByState(s.schoolId),
    ])
      .then(([y, g, sec, pol, allMoves]) => {
        setYears(y);
        setGrades(g);
        setSections(sec);
        setPolicy(pol);
        setMovesByState(allMoves);
        const current = y.find((yr) => yr.isCurrent) ?? y[0];
        setForm((f) => ({ ...f, academicYearId: current?.id ?? "", gradeId: g[0]?.id ?? "" }));
      })
      .catch((err) => setError(describeError(err)));
  }, [router]);

  const refreshSummary = useCallback((schoolId: string) => {
    return getAdmissionSummary(schoolId)
      .then(setSummary)
      .catch((err) => setError(describeError(err)));
  }, []);

  useEffect(() => {
    if (!session) return;
    refreshSummary(session.schoolId);
    listAdmissionFeesOwing(session.schoolId)
      .then(setOwing)
      .catch((err) => setError(describeError(err)));
  }, [session, refreshSummary]);

  const loadStage = useCallback((schoolId: string, state: string, nextOffset: number) => {
    setLoadingRows(true);
    setError(null);
    Promise.all([
      listAdmissionApplications(schoolId, state, { limit: PAGE_SIZE, offset: nextOffset }),
      admissionMovesForState(schoolId, state),
    ])
      .then(([page, allowed]) => {
        setRows(page);
        setMoves(allowed);
        // Every stage, not only the fee stage: a fee that came back unpaid
        // follows the application to wherever it now stands.
        loadFees(page);
        if (state === "enrolled") loadLogins(page);
      })
      .catch((err) => setError(describeError(err)))
      .finally(() => setLoadingRows(false));
  }, []);

  function loadFees(page: AdmissionApplicationDto[]) {
    Promise.all(page.map((a) => getAdmissionFeeStatus(a.id).then((fee) => [a.id, fee ?? null] as const)))
      .then((pairs) => setFeeByApp(Object.fromEntries(pairs)))
      .catch((err) => setError(describeError(err)));
  }

  function loadLogins(page: AdmissionApplicationDto[]) {
    Promise.all(page.map((a) => getGuardianLogin(a.id).then((login) => [a.id, login ?? null] as const)))
      .then((pairs) => setLoginByApp(Object.fromEntries(pairs)))
      .catch((err) => setError(describeError(err)));
  }

  function refreshOwing(schoolId: string) {
    listAdmissionFeesOwing(schoolId)
      .then(setOwing)
      .catch((err) => setError(describeError(err)));
  }

  async function onRefundFee(app: AdmissionApplicationDto, reason: string) {
    setBusyId(app.id);
    setError(null);
    setNotice(null);
    try {
      const fee = await refundAdmissionFee(app.id, reason);
      setRefunding(null);
      setFeeByApp((f) => ({ ...f, [app.id]: fee }));
      setNotice(`${inr(fee.refunded)} refunded to ${app.guardianName} for ${app.applicantFirstName}.`);
    } catch (err) {
      setError(describeRefundError(err));
    } finally {
      setBusyId(null);
    }
  }

  async function onGiveLogin(app: AdmissionApplicationDto) {
    const identifier = (loginDraft[app.id] ?? "").trim();
    if (!identifier) return;
    setBusyId(app.id);
    setError(null);
    setNotice(null);
    try {
      const login = await setGuardianLogin(app.id, identifier);
      setLoginByApp((l) => ({ ...l, [app.id]: login }));
      setLoginDraft((d) => {
        const { [app.id]: _typed, ...rest } = d;
        return rest;
      });
      setNotice(`${login.guardianName} can now sign in to the parent app with ${identifier}.`);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusyId(null);
    }
  }

  async function onRecordFee(app: AdmissionApplicationDto, fee: AdmissionFeeStatusDto) {
    if (!session) return;
    const entry = payment[app.id] ?? { amount: String(outstandingOf(fee)), method: "cash" };
    const amount = Number(entry.amount);
    if (!(amount > 0)) {
      setError("Enter the amount received.");
      return;
    }
    setBusyId(app.id);
    setError(null);
    setNotice(null);
    try {
      await recordPayment({
        schoolId: session.schoolId,
        feeInvoiceId: fee.invoiceId,
        amount,
        gateway: entry.method,
        method: entry.method,
        idempotencyKey: `adm-${fee.invoiceId}-${crypto.randomUUID()}`,
      });
      setPayment((p) => {
        const { [app.id]: _done, ...rest } = p;
        return rest;
      });
      setNotice(`${inr(amount)} recorded against ${fee.invoiceNo} for ${app.applicantFirstName}.`);
      if (rows) loadFees(rows);
      refreshOwing(session.schoolId);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusyId(null);
    }
  }

  function onPickStage(state: string) {
    if (!session) return;
    // Clicking the open stage again closes it, so the board can go back to
    // being only numbers.
    if (state === selected) {
      setSelected(null);
      setRows(null);
      return;
    }
    setResults(null);
    setSelected(state);
    setOffset(0);
    loadStage(session.schoolId, state, 0);
  }

  function onPage(nextOffset: number) {
    if (!session || !selected) return;
    setOffset(nextOffset);
    loadStage(session.schoolId, selected, nextOffset);
  }

  const hasCriteria =
    q.trim() !== "" || Object.values(adv).some((v) => v.trim() !== "");

  function runSearch(nextOffset: number) {
    if (!session || !hasCriteria) return;
    // Searching and browsing a stage are two answers to different questions;
    // showing both at once leaves nobody sure which list they are looking at.
    setSelected(null);
    setRows(null);
    setSearching(true);
    setError(null);
    setSearchOffset(nextOffset);
    searchAdmissionApplications(session.schoolId, {
      q: q.trim() || undefined,
      name: adv.name || undefined,
      dob: adv.dob || undefined,
      guardianPhone: adv.guardianPhone || undefined,
      applicationNo: adv.applicationNo || undefined,
      gradeId: adv.gradeId || undefined,
      state: adv.state || undefined,
      source: adv.source || undefined,
      limit: PAGE_SIZE,
      offset: nextOffset,
    })
      .then(setResults)
      .catch((err) => setError(describeError(err)))
      .finally(() => setSearching(false));
  }

  function clearSearch() {
    setQ("");
    setAdv(emptyAdvanced);
    setResults(null);
    setSearchOffset(0);
    setError(null);
  }

  /** After a move both the counts and the open page are stale. */
  function afterChange() {
    if (!session) return;
    refreshSummary(session.schoolId);
    refreshOwing(session.schoolId);
    if (selected) loadStage(session.schoolId, selected, offset);
    if (results) runSearch(searchOffset);
  }

  async function onCreate() {
    if (!session) return;
    setCreating(true);
    setError(null);
    try {
      const created = await createAdmissionApplication({
        schoolId: session.schoolId,
        academicYearId: form.academicYearId,
        gradeId: form.gradeId,
        applicationNo: form.applicationNo || undefined,
        applicantFirstName: form.applicantFirstName,
        applicantLastName: form.applicantLastName || undefined,
        applicantDob: form.applicantDob || undefined,
        applicantGender: form.applicantGender || undefined,
        guardianName: form.guardianName,
        guardianPhone: form.guardianPhone,
        guardianEmail: form.guardianEmail || undefined,
        source: form.source || undefined,
      });
      setForm((f) => ({ ...emptyForm, academicYearId: f.academicYearId, gradeId: f.gradeId }));
      setShowForm(false);
      setNotice(`Application ${created.applicationNo} created for ${created.applicantFirstName}.`);
      afterChange();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setCreating(false);
    }
  }

  async function onMove(app: AdmissionApplicationDto, toState: string) {
    // Closing an application is a letter to a family. It takes a second press
    // on the same button, so a mis-click on a crowded row is not a rejection.
    if (CLOSING.includes(toState) && pendingMove !== `${app.id}:${toState}`) {
      setPendingMove(`${app.id}:${toState}`);
      return;
    }
    setPendingMove(null);
    setNotice(null);
    setBusyId(app.id);
    setError(null);
    try {
      await transitionAdmissionApplication(app.id, toState);
      afterChange();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusyId(null);
    }
  }

  async function onEnrol(app: AdmissionApplicationDto) {
    const sectionId = rowSection[app.id];
    if (!sectionId) {
      setError("Pick a section before enrolling.");
      return;
    }
    setBusyId(app.id);
    setError(null);
    try {
      const enrolled = await enrolAdmissionApplication(app.id, sectionId);
      setNotice(
        enrolled.guardianHasLogin
          ? `${app.applicantFirstName} is enrolled, and ${app.guardianName} can sign in to the parent app.`
          : `${app.applicantFirstName} is enrolled, but ${app.guardianName} has no parent-app login yet: ` +
              `${app.guardianPhone} already signs somebody in — at another school in the chain, or as a member of ` +
              `staff. Open Enrolled to give them one on a different number or email.`
      );
      afterChange();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusyId(null);
    }
  }

  const gradeName = useMemo(() => {
    const map: Record<string, string> = {};
    grades?.forEach((g) => (map[g.id] = g.name));
    return map;
  }, [grades]);

  const countOf = useMemo(() => {
    const map: Record<string, number> = {};
    summary?.byState.forEach((s) => (map[s.state] = s.count));
    return map;
  }, [summary]);

  /**
   * Still read here, though it is no longer set here: the lane strip has to
   * match the funnel this school runs, and a school with no entrance test has
   * no assessment lane to draw. Changing it lives on School settings.
   */
  const lanes = useMemo(
    () => (policy?.entranceTestRequired === false ? LANES.filter((l) => l.key !== "assessment") : LANES),
    [policy]
  );

  // The fee stage and the two closed stages always say what became of the fee.
  // Any other stage grows the column only when somebody on the page owes.
  const showFeeColumn =
    selected !== null &&
    (FEE_STAGES.includes(selected) || (rows ?? []).some((a) => isOwing(feeByApp[a.id])));

  const selectedCount = selected ? countOf[selected] ?? 0 : 0;
  const pageFrom = selectedCount === 0 ? 0 : offset + 1;
  const pageTo = Math.min(offset + PAGE_SIZE, selectedCount);

  if (!session) return null;

  return (
    <main className="shell">
      <div className="panel">
        <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
          <div>
            <h2>Admissions pipeline</h2>
            <p className="hint" style={{ margin: 0 }}>
              {summary
                ? `${summary.total} application${summary.total === 1 ? "" : "s"} · pick a stage to see who is in it`
                : "Loading…"}
            </p>
          </div>
          <button type="button" onClick={() => setShowForm((v) => !v)}>
            {showForm ? "Cancel" : "New application"}
          </button>
        </div>

        {showForm && (
          <div className="form-row" style={{ flexWrap: "wrap" }}>
            <select
              value={form.academicYearId}
              onChange={(e) => setForm((f) => ({ ...f, academicYearId: e.target.value }))}
            >
              {years?.map((y) => (
                <option key={y.id} value={y.id}>
                  {y.code}
                </option>
              ))}
            </select>
            <select value={form.gradeId} onChange={(e) => setForm((f) => ({ ...f, gradeId: e.target.value }))}>
              {grades?.map((g) => (
                <option key={g.id} value={g.id}>
                  {g.name}
                </option>
              ))}
            </select>
            <input
              placeholder="Application no. (auto)"
              value={form.applicationNo}
              onChange={(e) => setForm((f) => ({ ...f, applicationNo: e.target.value }))}
            />
            <input
              placeholder="Applicant first name *"
              value={form.applicantFirstName}
              onChange={(e) => setForm((f) => ({ ...f, applicantFirstName: e.target.value }))}
            />
            <input
              placeholder="Applicant last name"
              value={form.applicantLastName}
              onChange={(e) => setForm((f) => ({ ...f, applicantLastName: e.target.value }))}
            />
            <input
              type="date"
              max={todayIso()}
              value={form.applicantDob}
              onChange={(e) => setForm((f) => ({ ...f, applicantDob: e.target.value }))}
            />
            <input
              placeholder="Gender"
              value={form.applicantGender}
              onChange={(e) => setForm((f) => ({ ...f, applicantGender: e.target.value }))}
              style={{ maxWidth: 100 }}
            />
            <input
              placeholder="Guardian name *"
              value={form.guardianName}
              onChange={(e) => setForm((f) => ({ ...f, guardianName: e.target.value }))}
            />
            <input
              placeholder="Guardian phone *"
              value={form.guardianPhone}
              onChange={(e) => setForm((f) => ({ ...f, guardianPhone: e.target.value }))}
            />
            <input
              placeholder="Guardian email"
              value={form.guardianEmail}
              onChange={(e) => setForm((f) => ({ ...f, guardianEmail: e.target.value }))}
            />
            <select value={form.source} onChange={(e) => setForm((f) => ({ ...f, source: e.target.value }))}>
              {SOURCES.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
            <button
              type="button"
              onClick={onCreate}
              disabled={
                creating ||
                !form.academicYearId ||
                !form.gradeId ||
                !form.applicantFirstName ||
                !form.guardianName ||
                !form.guardianPhone
              }
            >
              {creating ? "Creating…" : "Create application"}
            </button>
          </div>
        )}
      </div>

      <div className="panel">
        <div className="search-bar">
          <input
            id="admissions-search"
            className="search-input"
            placeholder="Find a child — name, application no., guardian or phone"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") runSearch(0);
            }}
          />
          <button type="button" onClick={() => runSearch(0)} disabled={!hasCriteria || searching}>
            {searching ? "Searching\u2026" : "Search"}
          </button>
          <button
            type="button"
            className={"tab" + (advancedOpen ? " active" : "")}
            onClick={() => setAdvancedOpen((v) => !v)}
          >
            More filters
          </button>
          {(results || hasCriteria) && (
            <button type="button" className="tab" onClick={clearSearch}>
              Clear
            </button>
          )}
        </div>

        {advancedOpen && (
          <div className="search-advanced">
            <label>
              <span>Applicant name</span>
              <input
                value={adv.name}
                onChange={(e) => setAdv((a) => ({ ...a, name: e.target.value }))}
                onKeyDown={(e) => e.key === "Enter" && runSearch(0)}
              />
            </label>
            <label>
              <span>Date of birth</span>
              <input
                type="date"
                max={todayIso()}
                value={adv.dob}
                onChange={(e) => setAdv((a) => ({ ...a, dob: e.target.value }))}
              />
            </label>
            <label>
              <span>Guardian phone</span>
              <input
                value={adv.guardianPhone}
                onChange={(e) => setAdv((a) => ({ ...a, guardianPhone: e.target.value }))}
                onKeyDown={(e) => e.key === "Enter" && runSearch(0)}
              />
            </label>
            <label>
              <span>Application no.</span>
              <input
                value={adv.applicationNo}
                onChange={(e) => setAdv((a) => ({ ...a, applicationNo: e.target.value }))}
                onKeyDown={(e) => e.key === "Enter" && runSearch(0)}
              />
            </label>
            <label>
              <span>Grade</span>
              <select value={adv.gradeId} onChange={(e) => setAdv((a) => ({ ...a, gradeId: e.target.value }))}>
                <option value="">Any</option>
                {grades?.map((g) => (
                  <option key={g.id} value={g.id}>
                    {g.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              <span>Stage</span>
              <select value={adv.state} onChange={(e) => setAdv((a) => ({ ...a, state: e.target.value }))}>
                <option value="">Any</option>
                {Object.keys(STATE_LABEL).map((st) => (
                  <option key={st} value={st}>
                    {STATE_LABEL[st]}
                  </option>
                ))}
              </select>
            </label>
            <label>
              <span>Source</span>
              <select value={adv.source} onChange={(e) => setAdv((a) => ({ ...a, source: e.target.value }))}>
                <option value="">Any</option>
                {SOURCES.map((src) => (
                  <option key={src} value={src}>
                    {src}
                  </option>
                ))}
              </select>
            </label>
            <div className="search-advanced-actions">
              <button type="button" onClick={() => runSearch(0)} disabled={!hasCriteria || searching}>
                Search
              </button>
            </div>
          </div>
        )}
      </div>

      {error && <div className="error-banner">{error}</div>}
      {notice && <div className="notice-banner">{notice}</div>}

      {summary && (summary.offersExpired > 0 || summary.offersExpiringSoon > 0) && (
        <div className={summary.offersExpired > 0 ? "warn-banner" : "notice-banner"}>
          {summary.offersExpired > 0 && (
            <>
              <strong>{summary.offersExpired}</strong> offer
              {summary.offersExpired === 1 ? " is" : "s are"} past their date, still holding a seat.{" "}
            </>
          )}
          {summary.offersExpiringSoon > 0 && (
            <>
              <strong>{summary.offersExpiringSoon}</strong> expiring within a week.{" "}
            </>
          )}
          <button type="button" className="tab" onClick={() => onPickStage("offered")}>
            Show offers
          </button>
        </div>
      )}

      {owing.length > 0 && (
        <div className="warn-banner">
          <strong>{owing.length}</strong> application{owing.length === 1 ? "" : "s"} past the fee stage{" "}
          {owing.length === 1 ? "owes its" : "owe their"} admission fee — a payment that was taken and came
          back. Nothing moves forward until it is settled.
          <ul style={{ margin: "6px 0 0", paddingLeft: 18 }}>
            {owing.map((o) => (
              <li key={o.applicationId}>
                {o.applicantName} <span className="hint">({o.applicationNo})</span> — {inr(o.outstanding)} on{" "}
                {o.invoiceNo}, now in{" "}
                <button type="button" className="tab" onClick={() => selected !== o.state && onPickStage(o.state)}>
                  {STATE_LABEL[o.state] ?? o.state}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="panel">
        <div className="funnel-lanes">
          {lanes.map((lane) => (
            <div className="funnel-lane" key={lane.key}>
              <div className="funnel-lane-head">
                <span>{lane.label}</span>
                <span className="funnel-lane-total">
                  {lane.states.reduce((n, st) => n + (countOf[st] ?? 0), 0)}
                </span>
              </div>
              <div className="funnel-tiles">
                {lane.states.map((state) => {
                  const count = countOf[state] ?? 0;
                  return (
                    <button
                      key={state}
                      type="button"
                      className={
                        "funnel-tile" +
                        (selected === state ? " selected" : "") +
                        (count === 0 ? " empty" : "") +
                        (CLOSING_STATES.has(state) ? " closing" : "")
                      }
                      aria-pressed={selected === state}
                      onClick={() => onPickStage(state)}
                    >
                      <span className="funnel-count">{summary ? count : "–"}</span>
                      <span className="funnel-state">{STATE_LABEL[state] ?? state}</span>
                    </button>
                  );
                })}
              </div>
            </div>
          ))}
        </div>
      </div>

      {results && (
        <div className="panel">
          <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
            <h3 style={{ margin: 0 }}>
              Search{" "}
              <span className="hint" style={{ fontWeight: 400 }}>
                {results.total === 0
                  ? "\u2014 nothing matched"
                  : `\u2014 showing ${searchOffset + 1}\u2013${Math.min(
                      searchOffset + PAGE_SIZE,
                      results.total
                    )} of ${results.total}`}
              </span>
            </h3>
            <button type="button" className="tab" onClick={clearSearch}>
              Clear
            </button>
          </div>

          {results.total === 0 && (
            <p className="hint">
              No application matches. A child who applied in an earlier year is still here — try the
              application number, or widen the stage filter to Any.
            </p>
          )}

          {results.rows.length > 0 && (
            <>
              <table>
                <thead>
                  <tr>
                    <th>Application no.</th>
                    <th>Applicant</th>
                    <th>Born</th>
                    <th>Grade</th>
                    <th>Guardian</th>
                    <th>Status</th>
                    <th>Move to</th>
                  </tr>
                </thead>
                <tbody>
                  {results.rows.map((a) => {
                    const allowed = movesByState[a.state] ?? [];
                    return (
                      <tr key={a.id}>
                        <td>{a.applicationNo}</td>
                        <td>
                          {a.applicantFirstName} {a.applicantLastName ?? ""}
                        </td>
                        <td>{a.applicantDob ?? <span className="hint">—</span>}</td>
                        <td>{gradeName[a.gradeId] ?? "\u2014"}</td>
                        <td>
                          {a.guardianName}
                          <br />
                          <span className="hint">{a.guardianPhone}</span>
                        </td>
                        <td>
                          <span className="badge">{STATE_LABEL[a.state] ?? a.state}</span>
                          {a.state === "offered" && a.offerExpiresOn && (
                            <>
                              <br />
                              <span className={"hint " + expiryClass(a.offerExpiresOn)}>
                                expires {a.offerExpiresOn}
                              </span>
                            </>
                          )}
                        </td>
                        <td>
                          {allowed.length === 0 ? (
                            <span className="hint">Nothing follows this stage.</span>
                          ) : (
                            <div className="move-actions">
                              {allowed.map((to) => (
                                <button
                                  key={to}
                                  type="button"
                                  className="tab"
                                  disabled={busyId === a.id}
                                  onClick={() => onMove(a, to)}
                                >
                                  {pendingMove === `${a.id}:${to}` ? `Confirm: ${STATE_LABEL[to] ?? to}?` : (STATE_LABEL[to] ?? to)}
                                </button>
                              ))}
                            </div>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>

              {results.total > PAGE_SIZE && (
                <div className="form-row" style={{ justifyContent: "flex-end", gap: 8, marginTop: 12 }}>
                  <button
                    type="button"
                    className="tab"
                    disabled={searchOffset === 0 || searching}
                    onClick={() => runSearch(Math.max(0, searchOffset - PAGE_SIZE))}
                  >
                    Previous
                  </button>
                  <button
                    type="button"
                    className="tab"
                    disabled={searchOffset + PAGE_SIZE >= results.total || searching}
                    onClick={() => runSearch(searchOffset + PAGE_SIZE)}
                  >
                    Next
                  </button>
                </div>
              )}
            </>
          )}
        </div>
      )}

      {selected && (
        <div className="panel">
          <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
            <h3 style={{ margin: 0 }}>
              {STATE_LABEL[selected] ?? selected}{" "}
              <span className="hint" style={{ fontWeight: 400 }}>
                {selectedCount === 0
                  ? "— nobody here"
                  : `— showing ${pageFrom}–${pageTo} of ${selectedCount}`}
              </span>
            </h3>
            <button type="button" className="tab" onClick={() => onPickStage(selected)}>
              Close
            </button>
          </div>

          {loadingRows && <p className="hint">Loading&hellip;</p>}

          {!loadingRows && rows && rows.length === 0 && <p className="hint">No applications in this stage.</p>}

          {!loadingRows && rows && rows.length > 0 && (
            <>
              <table>
                <thead>
                  <tr>
                    <th>Application no.</th>
                    <th>Applicant</th>
                    <th>Grade</th>
                    <th>Guardian</th>
                    {selected === "offered" && <th>Offer expires</th>}
                    {showFeeColumn && <th>Admission fee</th>}
                    {selected === "enrolled" && <th>Parent login</th>}
                    <th>Move to</th>
                    {selected === "accepted" && <th>Enrol</th>}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((a) => (
                    <tr key={a.id}>
                      <td>{a.applicationNo}</td>
                      <td>
                        {a.applicantFirstName} {a.applicantLastName ?? ""}
                      </td>
                      <td>{gradeName[a.gradeId] ?? "—"}</td>
                      <td>
                        {a.guardianName}
                        <br />
                        <span className="hint">{a.guardianPhone}</span>
                      </td>
                      {selected === "offered" && (
                        <td className={expiryClass(a.offerExpiresOn)}>
                          {a.offerExpiresOn ?? <span className="hint">not set</span>}
                        </td>
                      )}
                      {showFeeColumn && (
                        <td>
                          <FeeCell
                            fee={feeByApp[a.id]}
                            stage={selected}
                            entry={payment[a.id]}
                            busy={busyId === a.id}
                            refunding={refunding === a.id}
                            onEntry={(next) => setPayment((p) => ({ ...p, [a.id]: next }))}
                            onRecord={(fee) => onRecordFee(a, fee)}
                            onAskRefund={(asking) => setRefunding(asking ? a.id : null)}
                            onRefund={(reason) => onRefundFee(a, reason)}
                          />
                        </td>
                      )}
                      {selected === "enrolled" && (
                        <td>
                          <LoginCell
                            login={loginByApp[a.id]}
                            draft={loginDraft[a.id] ?? ""}
                            busy={busyId === a.id}
                            onDraft={(value) => setLoginDraft((d) => ({ ...d, [a.id]: value }))}
                            onGive={() => onGiveLogin(a)}
                          />
                        </td>
                      )}
                      <td>
                        {moves.length === 0 ? (
                          <span className="hint">Nothing follows this stage.</span>
                        ) : (
                          <div className="move-actions">
                            {moves.map((to) => (
                              <button
                                key={to}
                                type="button"
                                className="tab"
                                disabled={busyId === a.id}
                                onClick={() => onMove(a, to)}
                              >
                                {pendingMove === `${a.id}:${to}` ? `Confirm: ${STATE_LABEL[to] ?? to}?` : (STATE_LABEL[to] ?? to)}
                              </button>
                            ))}
                          </div>
                        )}
                      </td>
                      {selected === "accepted" && (
                        <td>
                          <div className="form-row" style={{ gap: 4, margin: 0 }}>
                            <select
                              value={rowSection[a.id] ?? ""}
                              onChange={(e) => setRowSection((s) => ({ ...s, [a.id]: e.target.value }))}
                            >
                              <option value="">Section&hellip;</option>
                              {sections
                                ?.filter((sec) => sec.gradeId === a.gradeId)
                                .map((sec) => (
                                  <option key={sec.id} value={sec.id}>
                                    {sec.gradeName}-{sec.code}
                                  </option>
                                ))}
                            </select>
                            <button type="button" onClick={() => onEnrol(a)} disabled={busyId === a.id}>
                              Enrol
                            </button>
                          </div>
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>

              {selectedCount > PAGE_SIZE && (
                <div className="form-row" style={{ justifyContent: "flex-end", gap: 8, marginTop: 12 }}>
                  <button
                    type="button"
                    className="tab"
                    disabled={offset === 0 || loadingRows}
                    onClick={() => onPage(Math.max(0, offset - PAGE_SIZE))}
                  >
                    Previous
                  </button>
                  <button
                    type="button"
                    className="tab"
                    disabled={pageTo >= selectedCount || loadingRows}
                    onClick={() => onPage(offset + PAGE_SIZE)}
                  >
                    Next
                  </button>
                </div>
              )}
            </>
          )}
        </div>
      )}
    </main>
  );
}

function inr(n: number): string {
  return n.toLocaleString(undefined, { style: "currency", currency: "INR", maximumFractionDigits: 2 });
}

function outstandingOf(fee: AdmissionFeeStatusDto): number {
  return Math.max(0, Math.round((fee.total - fee.paid) * 100) / 100);
}

const FEE_STAGES = ["fee_pending", "rejected", "lapsed"];
const CLOSED_STAGES = ["rejected", "lapsed"];

function isOwing(fee: AdmissionFeeStatusDto | null | undefined): boolean {
  return !!fee && fee.status !== "cancelled" && fee.status !== "refunded" && outstandingOf(fee) > 0;
}

/**
 * What became of one application's admission fee, and the counter to act on it
 * at. Open and owing: take the payment — forward moves are refused by the
 * server until it is settled, so the cell says so rather than leaving the
 * office to find out from an error. Closed and paid: the refund, which is a
 * decision and so asks for its reason.
 */
function FeeCell({
  fee,
  stage,
  entry,
  busy,
  refunding,
  onEntry,
  onRecord,
  onAskRefund,
  onRefund,
}: {
  fee: AdmissionFeeStatusDto | null | undefined;
  stage: string;
  entry: { amount: string; method: string } | undefined;
  busy: boolean;
  refunding: boolean;
  onEntry: (next: { amount: string; method: string }) => void;
  onRecord: (fee: AdmissionFeeStatusDto) => void;
  onAskRefund: (asking: boolean) => void;
  onRefund: (reason: string) => void;
}) {
  if (fee === undefined) return <span className="hint">&hellip;</span>;
  if (fee === null) return <span className="hint">Nothing to pay</span>;
  if (fee.status === "cancelled") {
    return <span className="hint">Bill {fee.invoiceNo} cancelled — nothing had been paid</span>;
  }
  if (fee.status === "refunded") {
    return (
      <>
        <span className="badge">Refunded</span>{" "}
        <span className="hint">
          {inr(fee.refunded)} · {fee.invoiceNo}
        </span>
      </>
    );
  }
  if (CLOSED_STAGES.includes(stage)) {
    if (fee.paid <= 0) return <span className="hint">Nothing was paid</span>;
    return (
      <>
        <div>
          {inr(fee.paid)} paid <span className="hint">· {fee.invoiceNo}</span>
        </div>
        {refunding ? (
          <ReasonField
            placeholder="Why it is being paid back"
            confirmLabel={`Refund ${inr(fee.paid)}`}
            busy={busy}
            onConfirm={onRefund}
            onCancel={() => onAskRefund(false)}
          />
        ) : (
          <>
            <button type="button" className="tab" disabled={busy} onClick={() => onAskRefund(true)}>
              Refund&hellip;
            </button>
            <div className="hint">Kept unless refunded. Refunding needs accounts.</div>
          </>
        )}
      </>
    );
  }
  const outstanding = outstandingOf(fee);
  if (outstanding === 0) {
    return (
      <>
        <span className="badge badge-active">Paid</span> <span className="hint">{inr(fee.total)} · {fee.invoiceNo}</span>
      </>
    );
  }
  const current = entry ?? { amount: String(outstanding), method: "cash" };
  return (
    <>
      <div>
        {inr(outstanding)} due{" "}
        <span className="hint">
          {fee.paid > 0 ? `of ${inr(fee.total)} · ` : "· "}
          {fee.invoiceNo}
        </span>
      </div>
      <div className="form-row" style={{ gap: 4, margin: "4px 0 0" }}>
        <input
          type="number"
          min={0}
          step="0.01"
          aria-label="Amount received"
          value={current.amount}
          style={{ width: 96 }}
          onChange={(e) => onEntry({ ...current, amount: e.target.value })}
        />
        <select
          aria-label="How it was paid"
          value={current.method}
          onChange={(e) => onEntry({ ...current, method: e.target.value })}
        >
          <option value="cash">Cash</option>
          <option value="cheque">Cheque</option>
          <option value="upi">UPI</option>
          <option value="card">Card</option>
        </select>
        <button type="button" disabled={busy} onClick={() => onRecord(fee)}>
          Record
        </button>
      </div>
      <span className="hint">
        {stage === "fee_pending"
          ? "Review opens once this is paid in full."
          : "A payment came back. Nothing moves forward until this is settled."}
      </span>
    </>
  );
}

/**
 * Whether an enrolled child's family can sign in, and the fix when they
 * cannot. The number on the application was already somebody's login — a
 * parent with a child at another of the chain's schools, or a member of staff
 * — so the office asks for another and types it here.
 */
function LoginCell({
  login,
  draft,
  busy,
  onDraft,
  onGive,
}: {
  login: GuardianLoginDto | null | undefined;
  draft: string;
  busy: boolean;
  onDraft: (value: string) => void;
  onGive: () => void;
}) {
  if (login === undefined) return <span className="hint">&hellip;</span>;
  // Marked enrolled without ever being enrolled into a section — the plain
  // move the server now refuses. There is no child, so nobody to give a login.
  if (login === null) return <span className="hint">No student record behind this application</span>;
  if (login.hasLogin) {
    return (
      <span className="hint">
        Signs in with {[login.signInPhone, login.signInEmail].filter(Boolean).join(" or ")}
      </span>
    );
  }
  return (
    <div style={{ maxWidth: 300, whiteSpace: "normal" }}>
      <div>
        <span className="badge badge-suspended">No login</span>
      </div>
      <span className="hint">
        {[login.contactPhone, login.contactEmail].filter(Boolean).join(" and ")} already sign
        {login.contactPhone && login.contactEmail ? "" : "s"} somebody in — at another school in the chain, or as
        staff.
      </span>
      <div className="form-row" style={{ gap: 4, margin: "4px 0 0" }}>
        <input
          aria-label={`Another email or phone for ${login.guardianName}`}
          placeholder="Another email or phone"
          value={draft}
          onChange={(e) => onDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") onGive();
          }}
        />
        <button type="button" disabled={busy || draft.trim() === ""} onClick={onGive}>
          Give login
        </button>
      </div>
    </div>
  );
}

/** An offer already past its date is a seat being held for nobody. */
function expiryClass(on: string | null): string {
  if (!on) return "";
  const today = new Date().toISOString().slice(0, 10);
  if (on < today) return "expiry-past";
  const week = new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 10);
  return on <= week ? "expiry-soon" : "";
}

/** A registrar may reject; paying money back is accounts'. Say whose job it is rather than "forbidden". */
function describeRefundError(err: unknown): string {
  if (err instanceof ApiError && err.status === 403) {
    return "Refunding a fee is for accounts — it needs fee.adjustment.manage. Ask the accountant to refund it from this screen.";
  }
  return describeError(err);
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return err.userMessage;
  return err instanceof Error ? err.message : "Unknown error";
}
