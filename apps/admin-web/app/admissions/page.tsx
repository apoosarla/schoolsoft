"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import {
  AcademicYearDto,
  AdmissionApplicationDto,
  AdmissionFunnelSummaryDto,
  AdmissionPolicyDto,
  admissionMovesByState,
  admissionMovesForState,
  AdmissionSearchResultDto,
  ApiError,
  createAdmissionApplication,
  enrolAdmissionApplication,
  getAdmissionPolicy,
  getAdmissionSummary,
  getSession,
  GradeDto,
  hasScreen,
  listAcademicYears,
  listAdmissionApplications,
  listGrades,
  listSections,
  searchAdmissionApplications,
  SectionDto,
  Session,
  transitionAdmissionApplication,
} from "@/lib/api";

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
    if (session) refreshSummary(session.schoolId);
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
      })
      .catch((err) => setError(describeError(err)))
      .finally(() => setLoadingRows(false));
  }, []);

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
    if (selected) loadStage(session.schoolId, selected, offset);
    if (results) runSearch(searchOffset);
  }

  async function onCreate() {
    if (!session) return;
    setCreating(true);
    setError(null);
    try {
      await createAdmissionApplication({
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
      afterChange();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setCreating(false);
    }
  }

  async function onMove(app: AdmissionApplicationDto, toState: string) {
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
      await enrolAdmissionApplication(app.id, sectionId);
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
              placeholder="Applicant first name"
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
              placeholder="Guardian name"
              value={form.guardianName}
              onChange={(e) => setForm((f) => ({ ...f, guardianName: e.target.value }))}
            />
            <input
              placeholder="Guardian phone"
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
                                  {STATE_LABEL[to] ?? to}
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
                                {STATE_LABEL[to] ?? to}
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

/** An offer already past its date is a seat being held for nobody. */
function expiryClass(on: string | null): string {
  if (!on) return "";
  const today = new Date().toISOString().slice(0, 10);
  if (on < today) return "expiry-past";
  const week = new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 10);
  return on <= week ? "expiry-soon" : "";
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}
