"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  AcademicYearDto,
  AdmissionFeeDto,
  AdmissionPolicyDto,
  ApiError,
  getAdmissionPolicy,
  getSession,
  GradeDto,
  hasScreen,
  listAcademicYears,
  listAdmissionFees,
  listGrades,
  saveAdmissionFee,
  saveAdmissionPolicy,
  Session,
} from "@/lib/api";

/**
 * School settings — the facts about how this school runs, as opposed to the
 * day's work. Everything here applies to the whole school and changes what the
 * screens elsewhere will let people do, so each setting says so in its own
 * words rather than leaving somebody to find out by being refused.
 */
export default function SettingsPage() {
  const router = useRouter();
  const [session, setSession] = useState<Session | null>(null);
  const [policy, setPolicy] = useState<AdmissionPolicyDto | null>(null);
  const [days, setDays] = useState("");
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [years, setYears] = useState<AcademicYearDto[]>([]);
  const [grades, setGrades] = useState<GradeDto[]>([]);
  const [feeYear, setFeeYear] = useState("");
  // What the server holds, and what is typed in each box until it is saved.
  const [fees, setFees] = useState<AdmissionFeeDto[]>([]);
  const [feeDraft, setFeeDraft] = useState<Record<string, string>>({});

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    if (!hasScreen(s, "settings")) {
      router.replace("/dashboard");
      return;
    }
    setSession(s);
    getAdmissionPolicy(s.schoolId)
      .then((p) => {
        setPolicy(p);
        setDays(String(p.offerValidityDays));
      })
      .catch((err) => setError(describeError(err)));
    Promise.all([listAcademicYears(s.schoolId), listGrades(s.schoolId)])
      .then(([ys, gs]) => {
        setYears(ys);
        setGrades([...gs].sort((a, b) => a.sortOrder - b.sortOrder));
        setFeeYear((ys.find((y) => y.isCurrent) ?? ys[0])?.id ?? "");
      })
      .catch((err) => setError(describeError(err)));
  }, [router]);

  useEffect(() => {
    if (!session || !feeYear) return;
    setFeeDraft({});
    listAdmissionFees(session.schoolId, feeYear)
      .then(setFees)
      .catch((err) => setError(describeError(err)));
  }, [session, feeYear]);

  function feeOf(gradeId: string): number {
    return fees.find((f) => f.gradeId === gradeId)?.amount ?? 0;
  }

  async function saveFee(grade: GradeDto) {
    if (!session) return;
    const typed = feeDraft[grade.id];
    if (typed === undefined) return;
    const amount = typed.trim() === "" ? 0 : Number(typed);
    const discard = () =>
      setFeeDraft((d) => {
        const { [grade.id]: _typed, ...rest } = d;
        return rest;
      });
    if (!Number.isFinite(amount) || amount < 0) {
      discard();
      setError("An admission fee is an amount of zero or more.");
      return;
    }
    if (amount === feeOf(grade.id)) {
      discard();
      return;
    }
    setSaving(true);
    setError(null);
    setSaved(null);
    try {
      setFees(await saveAdmissionFee({ schoolId: session.schoolId, academicYearId: feeYear, gradeId: grade.id, amount }));
      setSaved(amount === 0 ? `${grade.name} no longer charges an admission fee.` : `Admission fee for ${grade.name} saved.`);
    } catch (err) {
      setError(describeError(err));
    } finally {
      // Either way the box goes back to showing what the server holds.
      discard();
      setSaving(false);
    }
  }

  async function save(next: AdmissionPolicyDto, what: string) {
    setSaving(true);
    setError(null);
    setSaved(null);
    try {
      const updated = await saveAdmissionPolicy(next);
      setPolicy(updated);
      setDays(String(updated.offerValidityDays));
      setSaved(what);
    } catch (err) {
      // Put the control back to what the server still holds, so the screen
      // never shows a setting that did not take.
      if (policy) setDays(String(policy.offerValidityDays));
      setError(describeError(err));
    } finally {
      setSaving(false);
    }
  }

  function saveDays() {
    if (!policy) return;
    const parsed = Number(days);
    if (!Number.isInteger(parsed) || parsed < 1) {
      setDays(String(policy.offerValidityDays));
      setError("An offer has to stand for at least a whole day.");
      return;
    }
    if (parsed === policy.offerValidityDays) return;
    save({ ...policy, offerValidityDays: parsed }, "Offer validity saved.");
  }

  if (!session) return null;

  return (
    <main className="shell">
      <div className="panel">
        <h2>School settings</h2>
        <p className="hint" style={{ margin: 0 }}>
          How this school runs. Each setting applies to the whole school unless it says otherwise.
        </p>
      </div>

      {error && <div className="error-banner">{error}</div>}
      {saved && <div className="notice-banner">{saved}</div>}

      <div className="panel">
        <h3 style={{ marginTop: 0 }}>Admissions</h3>

        {!policy && !error && <p className="hint">Loading&hellip;</p>}

        {policy && (
          <div className="setting-list">
            <div className="setting">
              <div className="setting-text">
                <label htmlFor="entrance-test-required" className="setting-name">
                  Entrance test
                </label>
                <p className="setting-why">
                  {policy.entranceTestRequired
                    ? "Applicants sit a test before an offer. The funnel runs review → test → offer, and an offer cannot be made straight from review."
                    : "No test is held. An offer is made straight from review, and the funnel has no test to schedule."}
                </p>
              </div>
              <div className="setting-control">
                <label className="switch-row" htmlFor="entrance-test-required">
                  <input
                    id="entrance-test-required"
                    type="checkbox"
                    checked={policy.entranceTestRequired}
                    disabled={saving}
                    onChange={(e) =>
                      save(
                        { ...policy, entranceTestRequired: e.target.checked },
                        e.target.checked
                          ? "Entrance test turned on for the school."
                          : "Entrance test turned off for the school."
                      )
                    }
                  />
                  <span>{policy.entranceTestRequired ? "Held" : "Not held"}</span>
                </label>
              </div>
            </div>

            <div className="setting">
              <div className="setting-text">
                <label htmlFor="offer-validity-days" className="setting-name">
                  Offer validity
                </label>
                <p className="setting-why">
                  How long an offer stands. The date lands on the application when the offer is made, so the
                  family can see it and the office can chase it. One family can still be given longer.
                </p>
              </div>
              <div className="setting-control">
                <div className="form-row" style={{ gap: 6, alignItems: "center", margin: 0 }}>
                  <input
                    id="offer-validity-days"
                    type="number"
                    min={1}
                    value={days}
                    disabled={saving}
                    style={{ width: 78 }}
                    onChange={(e) => setDays(e.target.value)}
                    onBlur={saveDays}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") e.currentTarget.blur();
                    }}
                  />
                  <span className="hint">days</span>
                </div>
              </div>
            </div>
          </div>
        )}
      </div>

      <div className="panel">
        <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
          <h3 style={{ margin: 0 }}>Admission fee</h3>
          <select aria-label="Academic year" value={feeYear} onChange={(e) => setFeeYear(e.target.value)}>
            {years.map((y) => (
              <option key={y.id} value={y.id}>
                {y.code}
              </option>
            ))}
          </select>
        </div>
        <p className="hint">
          Charged when an application reaches the fee stage, for the grade and year it applies to. The
          application cannot move on to review until it is paid in full, and the payment goes onto the
          child&apos;s account when the seat is confirmed. Leave a grade empty to charge nothing. A change
          applies to applications that reach the fee stage from now on.
        </p>
        <table>
          <thead>
            <tr>
              <th>Grade</th>
              <th>Fee (₹)</th>
            </tr>
          </thead>
          <tbody>
            {grades.map((g) => (
              <tr key={g.id}>
                <td>
                  <label htmlFor={`admission-fee-${g.id}`}>{g.name}</label>
                </td>
                <td>
                  <input
                    id={`admission-fee-${g.id}`}
                    type="number"
                    min={0}
                    step="0.01"
                    placeholder="None"
                    disabled={saving}
                    style={{ width: 120 }}
                    value={feeDraft[g.id] ?? (feeOf(g.id) === 0 ? "" : String(feeOf(g.id)))}
                    onChange={(e) => setFeeDraft((d) => ({ ...d, [g.id]: e.target.value }))}
                    onBlur={() => saveFee(g)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") e.currentTarget.blur();
                    }}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </main>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 403) {
      return "You can see these settings but not change them — that needs admission.policy.manage.";
    }
    return err.userMessage;
  }
  return err instanceof Error ? err.message : "Unknown error";
}
