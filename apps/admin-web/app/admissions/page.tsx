"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ADMISSION_STATES,
  AdmissionApplicationDto,
  ApiError,
  AcademicYearDto,
  createAdmissionApplication,
  enrolAdmissionApplication,
  GradeDto,
  getSession,
  listAcademicYears,
  listAdmissionApplications,
  listGrades,
  listSections,
  SectionDto,
  Session,
  transitionAdmissionApplication,
} from "@/lib/api";

const SOURCES = ["website", "walkin", "referral", "ad"];

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
  const [apps, setApps] = useState<AdmissionApplicationDto[] | null>(null);
  const [stateFilter, setStateFilter] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState(emptyForm);
  const [creating, setCreating] = useState(false);
  const [rowState, setRowState] = useState<Record<string, string>>({});
  const [rowSection, setRowSection] = useState<Record<string, string>>({});
  const [busyId, setBusyId] = useState<string | null>(null);

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    setSessionState(s);
    Promise.all([listAcademicYears(s.schoolId), listGrades(s.schoolId), listSections(s.schoolId)])
      .then(([y, g, sec]) => {
        setYears(y);
        setGrades(g);
        setSections(sec);
        const current = y.find((yr) => yr.isCurrent) ?? y[0];
        setForm((f) => ({
          ...f,
          academicYearId: current?.id ?? "",
          gradeId: g[0]?.id ?? "",
        }));
      })
      .catch((err) => setError(describeError(err)));
  }, [router]);

  function refresh(schoolId: string, state: string) {
    setLoading(true);
    setError(null);
    listAdmissionApplications(schoolId, state || undefined)
      .then(setApps)
      .catch((err) => setError(describeError(err)))
      .finally(() => setLoading(false));
  }

  useEffect(() => {
    if (!session) return;
    refresh(session.schoolId, stateFilter);
  }, [session, stateFilter]);

  const gradeName = useMemo(() => {
    const map: Record<string, string> = {};
    grades?.forEach((g) => (map[g.id] = g.name));
    return map;
  }, [grades]);

  async function onCreate() {
    if (!session) return;
    setCreating(true);
    setError(null);
    try {
      await createAdmissionApplication({
        schoolId: session.schoolId,
        academicYearId: form.academicYearId,
        gradeId: form.gradeId,
        applicationNo: form.applicationNo,
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
      refresh(session.schoolId, stateFilter);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setCreating(false);
    }
  }

  async function onTransition(app: AdmissionApplicationDto) {
    const toState = rowState[app.id] ?? app.state;
    if (toState === app.state) return;
    setBusyId(app.id);
    setError(null);
    try {
      await transitionAdmissionApplication(app.id, toState);
      if (session) refresh(session.schoolId, stateFilter);
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
      if (session) refresh(session.schoolId, stateFilter);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusyId(null);
    }
  }

  if (!session) return null;

  return (
    <main className="shell">
      <div className="panel">
        <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
          <h2>Admissions</h2>
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
              placeholder="Application no."
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
                !form.applicationNo ||
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
        <div className="form-row">
          <select value={stateFilter} onChange={(e) => setStateFilter(e.target.value)}>
            <option value="">All states</option>
            {ADMISSION_STATES.map((st) => (
              <option key={st} value={st}>
                {st}
              </option>
            ))}
          </select>
        </div>

        {loading && <p className="hint">Loading…</p>}
        {error && <div className="error-banner">{error}</div>}
        {apps && apps.length === 0 && <p className="hint">No applications found.</p>}

        {apps && apps.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Application no.</th>
                <th>Applicant</th>
                <th>Grade</th>
                <th>Guardian</th>
                <th>State</th>
                <th>Move to</th>
                <th>Enrol</th>
              </tr>
            </thead>
            <tbody>
              {apps.map((a) => (
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
                  <td>
                    <span className="badge">{a.state}</span>
                  </td>
                  <td>
                    <div className="form-row" style={{ gap: 4 }}>
                      <select
                        value={rowState[a.id] ?? a.state}
                        onChange={(e) => setRowState((s) => ({ ...s, [a.id]: e.target.value }))}
                        disabled={a.state === "enrolled"}
                      >
                        {ADMISSION_STATES.map((st) => (
                          <option key={st} value={st}>
                            {st}
                          </option>
                        ))}
                      </select>
                      <button
                        type="button"
                        onClick={() => onTransition(a)}
                        disabled={busyId === a.id || a.state === "enrolled" || (rowState[a.id] ?? a.state) === a.state}
                      >
                        Move
                      </button>
                    </div>
                  </td>
                  <td>
                    {a.state === "enrolled" ? (
                      <span className="hint">Enrolled</span>
                    ) : (
                      <div className="form-row" style={{ gap: 4 }}>
                        <select
                          value={rowSection[a.id] ?? ""}
                          onChange={(e) => setRowSection((s) => ({ ...s, [a.id]: e.target.value }))}
                        >
                          <option value="">Section…</option>
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
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </main>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}
