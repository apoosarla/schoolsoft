"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  AdmissionPolicyDto,
  ApiError,
  getAdmissionPolicy,
  getSession,
  hasScreen,
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
  }, [router]);

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
          These apply to the whole school, not to one academic year or one grade.
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
    </main>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 403) {
      return "You can see these settings but not change them — that needs admission.policy.manage.";
    }
    return `${err.code ?? "error"}: ${err.message}`;
  }
  return err instanceof Error ? err.message : "Unknown error";
}
