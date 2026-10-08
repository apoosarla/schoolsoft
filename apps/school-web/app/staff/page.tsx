"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  CampusDto,
  createStaff,
  exitStaff,
  getSession,
  hasScreen,
  listCampuses,
  listRoles,
  listStaff,
  listStaffRoles,
  RoleDto,
  Session,
  StaffDto,
  staffDuties,
  StaffDutyDto,
  StaffWithRolesDto,
  updateStaff,
} from "@/lib/api";
import { formatDate, humanize, todayIso } from "@/lib/format";

const EMPLOYMENT_TYPES = ["permanent", "contract", "temp", "visiting"];

const emptyForm = {
  firstName: "",
  lastName: "",
  email: "",
  phone: "",
  employeeNo: "",
  employmentType: "permanent",
  joinedOn: "",
  campusId: "",
  roleCodes: [] as string[],
};

type ExitDraft = { lastWorkingDate: string; reason: string; successorStaffId: string };

export default function StaffPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [staff, setStaff] = useState<StaffDto[] | null>(null);
  const [grants, setGrants] = useState<StaffWithRolesDto[]>([]);
  const [roles, setRoles] = useState<RoleDto[]>([]);
  const [campuses, setCampuses] = useState<CampusDto[]>([]);
  const [showLeft, setShowLeft] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState(emptyForm);
  const [creating, setCreating] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // One row is open at a time, either to edit or to file an exit; the result
  // of either shows inside that row, beside the control that asked for it.
  const [editing, setEditing] = useState<{ id: string; draft: typeof emptyForm } | null>(null);
  const [exiting, setExiting] = useState<{ id: string; draft: ExitDraft } | null>(null);
  const [duties, setDuties] = useState<StaffDutyDto[] | null>(null);
  const [rowBusy, setRowBusy] = useState(false);
  const [rowError, setRowError] = useState<string | null>(null);

  const today = todayIso();

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    if (!hasScreen(s, "staff")) {
      router.replace("/dashboard");
      return;
    }
    setSessionState(s);
    refresh(s.schoolId);
    listRoles().then(setRoles).catch(() => setRoles([]));
    listCampuses(s.schoolId).then(setCampuses).catch(() => setCampuses([]));
  }, [router]);

  function refresh(schoolId: string) {
    Promise.all([listStaff(schoolId), listStaffRoles(schoolId)])
      .then(([people, withRoles]) => {
        setStaff(people);
        setGrants(withRoles);
        setLoadError(null);
      })
      .catch((err) => setLoadError(describeError(err)));
  }

  const rolesOf = useMemo(() => {
    const byStaff: Record<string, string[]> = {};
    for (const g of grants) byStaff[g.staffId] = g.roleCodes;
    return byStaff;
  }, [grants]);

  const roleName = (code: string) => roles.find((r) => r.code === code)?.name ?? humanize(code);
  const hasLeft = (m: StaffDto) => !!m.leftOn && m.leftOn < today;
  const visible = staff?.filter((m) => showLeft || !hasLeft(m)) ?? [];
  const leftCount = staff?.filter(hasLeft).length ?? 0;

  // What is being handed over is teaching, so whoever takes it has to still be
  // here the day after — the server checks that they hold a teaching role.
  function successorsFor(leaver: StaffDto, lastWorkingDate: string) {
    return (staff ?? []).filter((m) => m.id !== leaver.id && (!m.leftOn || m.leftOn > lastWorkingDate));
  }

  async function onCreate() {
    if (!session) return;
    setCreating(true);
    setFormError(null);
    setNotice(null);
    try {
      const created = await createStaff({
        schoolId: session.schoolId,
        firstName: form.firstName.trim(),
        lastName: form.lastName.trim() || undefined,
        email: form.email.trim() || undefined,
        phone: form.phone.trim() || undefined,
        employeeNo: form.employeeNo.trim() || undefined,
        employmentType: form.employmentType,
        joinedOn: form.joinedOn || undefined,
        campusId: form.campusId || undefined,
        roleCodes: form.roleCodes,
      });
      setNotice(
        `${created.firstName} ${created.lastName ?? ""} added as ${created.employeeNo}. ` +
          `They sign in with ${created.email ?? created.phone}.`,
      );
      setForm(emptyForm);
      setShowForm(false);
      refresh(session.schoolId);
    } catch (err) {
      setFormError(describeError(err));
    } finally {
      setCreating(false);
    }
  }

  function startEdit(m: StaffDto) {
    setExiting(null);
    setRowError(null);
    setEditing({
      id: m.id,
      draft: {
        ...emptyForm,
        firstName: m.firstName,
        lastName: m.lastName ?? "",
        email: m.email ?? "",
        phone: m.phone ?? "",
        employmentType: m.employmentType ?? "permanent",
        joinedOn: m.joinedOn ?? "",
        campusId: m.campusId ?? "",
      },
    });
  }

  async function onSaveEdit(m: StaffDto) {
    if (!session || !editing) return;
    setRowBusy(true);
    setRowError(null);
    try {
      await updateStaff(m.id, {
        firstName: editing.draft.firstName.trim(),
        lastName: editing.draft.lastName.trim() || undefined,
        email: editing.draft.email.trim() || undefined,
        phone: editing.draft.phone.trim() || undefined,
        employmentType: editing.draft.employmentType,
        joinedOn: editing.draft.joinedOn || undefined,
        campusId: editing.draft.campusId || undefined,
        version: m.version,
      });
      setEditing(null);
      refresh(session.schoolId);
    } catch (err) {
      setRowError(describeError(err));
    } finally {
      setRowBusy(false);
    }
  }

  function startExit(m: StaffDto) {
    setEditing(null);
    setRowError(null);
    setExiting({ id: m.id, draft: { lastWorkingDate: today, reason: "", successorStaffId: "" } });
    loadDuties(m.id, today);
  }

  function loadDuties(id: string, lastWorkingDate: string) {
    setDuties(null);
    if (!lastWorkingDate) return;
    staffDuties(id, lastWorkingDate)
      .then(setDuties)
      .catch((err) => setRowError(describeError(err)));
  }

  async function onExit(m: StaffDto) {
    if (!session || !exiting) return;
    setRowBusy(true);
    setRowError(null);
    try {
      const left = await exitStaff(m.id, {
        lastWorkingDate: exiting.draft.lastWorkingDate,
        reason: exiting.draft.reason.trim(),
        successorStaffId: exiting.draft.successorStaffId || undefined,
        version: m.version,
      });
      setNotice(
        `${left.firstName}'s last working day is ${formatDate(left.leftOn)}. ` +
          `They keep their access until the end of that day.`,
      );
      setExiting(null);
      refresh(session.schoolId);
    } catch (err) {
      setRowError(describeError(err));
    } finally {
      setRowBusy(false);
    }
  }

  if (!session) return null;

  const canCreate = form.firstName.trim() && (form.email.trim() || form.phone.trim());

  return (
    <main>
      <div className="panel">
        <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
          <h2 style={{ margin: 0 }}>Staff</h2>
          <button type="button" onClick={() => { setShowForm((v) => !v); setFormError(null); }}>
            {showForm ? "Cancel" : "Add staff"}
          </button>
        </div>

        {notice && <p className="hint" role="status">{notice}</p>}

        {showForm && (
          <div style={{ marginTop: 12 }}>
            <div className="form-row" style={{ flexWrap: "wrap" }}>
              <input
                placeholder="First name *"
                aria-label="First name"
                value={form.firstName}
                onChange={(e) => setForm({ ...form, firstName: e.target.value })}
              />
              <input
                placeholder="Last name"
                aria-label="Last name"
                value={form.lastName}
                onChange={(e) => setForm({ ...form, lastName: e.target.value })}
              />
              <input
                type="email"
                placeholder="Email"
                aria-label="Email"
                value={form.email}
                onChange={(e) => setForm({ ...form, email: e.target.value })}
              />
              <input
                type="tel"
                placeholder="Mobile number"
                aria-label="Mobile number"
                value={form.phone}
                onChange={(e) => setForm({ ...form, phone: e.target.value })}
              />
            </div>
            <p className="hint">An email address or a mobile number is required — it is how they sign in.</p>
            <div className="form-row" style={{ flexWrap: "wrap" }}>
              <input
                placeholder="Employee no. (issued if blank)"
                aria-label="Employee number"
                value={form.employeeNo}
                onChange={(e) => setForm({ ...form, employeeNo: e.target.value })}
              />
              <select
                aria-label="Employment type"
                value={form.employmentType}
                onChange={(e) => setForm({ ...form, employmentType: e.target.value })}
              >
                {EMPLOYMENT_TYPES.map((t) => (
                  <option key={t} value={t}>{humanize(t)}</option>
                ))}
              </select>
              <label className="form-row" style={{ alignItems: "center", gap: 6, marginBottom: 0 }}>
                Joined
                <input
                  type="date"
                  value={form.joinedOn}
                  onChange={(e) => setForm({ ...form, joinedOn: e.target.value })}
                />
              </label>
              {campuses.length > 1 && (
                <select
                  aria-label="Campus"
                  value={form.campusId}
                  onChange={(e) => setForm({ ...form, campusId: e.target.value })}
                >
                  <option value="">Main campus</option>
                  {campuses.map((c) => (
                    <option key={c.id} value={c.id}>{c.name}</option>
                  ))}
                </select>
              )}
            </div>
            <div className="form-row" style={{ flexWrap: "wrap" }}>
              {roles.map((r) => (
                <label key={r.code} className="form-row" style={{ alignItems: "center", gap: 4, marginBottom: 0 }}>
                  <input
                    type="checkbox"
                    checked={form.roleCodes.includes(r.code)}
                    onChange={() =>
                      setForm((f) => ({
                        ...f,
                        roleCodes: f.roleCodes.includes(r.code)
                          ? f.roleCodes.filter((c) => c !== r.code)
                          : [...f.roleCodes, r.code],
                      }))
                    }
                  />
                  {r.name}
                </label>
              ))}
            </div>
            {form.roleCodes.length === 0 && (
              <p className="hint">With no role they can sign in and see nothing. Roles can be granted later on Roles &amp; Users.</p>
            )}
            {formError && <div className="error-banner">{formError}</div>}
            <div className="form-row">
              <button type="button" onClick={onCreate} disabled={creating || !canCreate}>
                {creating ? "Adding…" : "Add to staff"}
              </button>
            </div>
          </div>
        )}

        {loadError && <div className="error-banner">{loadError}</div>}
        {staff && visible.length === 0 && <p className="hint">Nobody is on the staff list yet.</p>}

        {visible.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Signs in with</th>
                <th>Roles</th>
                <th>Status</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {visible.map((m) => {
                const isEditing = editing?.id === m.id;
                const isExiting = exiting?.id === m.id;
                return (
                  <tr key={m.id}>
                    <td style={{ whiteSpace: "normal" }}>
                      {isEditing ? (
                        <div className="form-row" style={{ flexWrap: "wrap", marginBottom: 0 }}>
                          <input
                            aria-label="First name"
                            value={editing.draft.firstName}
                            onChange={(e) => setEditing({ id: m.id, draft: { ...editing.draft, firstName: e.target.value } })}
                          />
                          <input
                            aria-label="Last name"
                            placeholder="Last name"
                            value={editing.draft.lastName}
                            onChange={(e) => setEditing({ id: m.id, draft: { ...editing.draft, lastName: e.target.value } })}
                          />
                          <select
                            aria-label="Employment type"
                            value={editing.draft.employmentType}
                            onChange={(e) => setEditing({ id: m.id, draft: { ...editing.draft, employmentType: e.target.value } })}
                          >
                            {EMPLOYMENT_TYPES.map((t) => (
                              <option key={t} value={t}>{humanize(t)}</option>
                            ))}
                          </select>
                        </div>
                      ) : (
                        <>
                          {m.firstName} {m.lastName ?? ""}
                          <br />
                          <span className="hint">
                            {m.employeeNo} · {humanize(m.employmentType)}
                            {m.joinedOn ? ` · joined ${formatDate(m.joinedOn)}` : ""}
                          </span>
                        </>
                      )}
                    </td>
                    <td style={{ whiteSpace: "normal" }}>
                      {isEditing ? (
                        <div className="form-row" style={{ flexWrap: "wrap", marginBottom: 0 }}>
                          <input
                            type="email"
                            aria-label="Email"
                            placeholder="Email"
                            value={editing.draft.email}
                            onChange={(e) => setEditing({ id: m.id, draft: { ...editing.draft, email: e.target.value } })}
                          />
                          <input
                            type="tel"
                            aria-label="Mobile number"
                            placeholder="Mobile number"
                            value={editing.draft.phone}
                            onChange={(e) => setEditing({ id: m.id, draft: { ...editing.draft, phone: e.target.value } })}
                          />
                        </div>
                      ) : (
                        <>
                          {m.email ?? m.phone ?? <span className="hint">no way to sign in</span>}
                          {m.email && m.phone && (
                            <>
                              <br />
                              <span className="hint">{m.phone}</span>
                            </>
                          )}
                        </>
                      )}
                    </td>
                    <td>
                      <div style={{ display: "flex", flexWrap: "wrap", gap: 4, whiteSpace: "normal" }}>
                        {(rolesOf[m.id] ?? []).length === 0 && <span className="hint">No role</span>}
                        {(rolesOf[m.id] ?? []).map((code) => (
                          <span key={code} className="badge">{roleName(code)}</span>
                        ))}
                      </div>
                    </td>
                    <td style={{ whiteSpace: "normal" }}>
                      {!m.leftOn && <span className="badge badge-active" style={{ whiteSpace: "nowrap" }}>On staff</span>}
                      {m.leftOn && !hasLeft(m) && <span className="badge" style={{ whiteSpace: "nowrap" }}>Leaving {formatDate(m.leftOn)}</span>}
                      {hasLeft(m) && <span className="badge badge-suspended" style={{ whiteSpace: "nowrap" }}>Left {formatDate(m.leftOn)}</span>}
                      {m.leftOn && m.exitReason && (
                        <>
                          <br />
                          <span className="hint">{m.exitReason}</span>
                        </>
                      )}
                    </td>
                    <td style={{ whiteSpace: "normal", minWidth: 260 }}>
                      {!isEditing && !isExiting && !hasLeft(m) && (
                        <div className="form-row" style={{ gap: 4, marginBottom: 0 }}>
                          <button type="button" onClick={() => startEdit(m)}>Edit</button>
                          {!m.leftOn && <button type="button" onClick={() => startExit(m)}>Record exit</button>}
                        </div>
                      )}
                      {isEditing && (
                        <>
                          <div className="form-row" style={{ gap: 4, marginBottom: 0 }}>
                            <button
                              type="button"
                              onClick={() => onSaveEdit(m)}
                              disabled={rowBusy || !editing.draft.firstName.trim() ||
                                !(editing.draft.email.trim() || editing.draft.phone.trim())}
                            >
                              {rowBusy ? "Saving…" : "Save"}
                            </button>
                            <button type="button" onClick={() => setEditing(null)} disabled={rowBusy}>Cancel</button>
                          </div>
                          {rowError && <div className="error-banner" style={{ marginTop: 8 }}>{rowError}</div>}
                        </>
                      )}
                      {isExiting && (
                        <div>
                          <label className="form-row" style={{ alignItems: "center", gap: 6 }}>
                            Last working day
                            <input
                              type="date"
                              min={m.joinedOn ?? undefined}
                              value={exiting.draft.lastWorkingDate}
                              onChange={(e) => {
                                setExiting({ id: m.id, draft: { ...exiting.draft, lastWorkingDate: e.target.value, successorStaffId: "" } });
                                loadDuties(m.id, e.target.value);
                              }}
                            />
                          </label>
                          {duties === null && exiting.draft.lastWorkingDate && <p className="hint">Checking what they hold…</p>}
                          {duties && duties.length === 0 && (
                            <p className="hint">They hold no sections or timetabled periods after that day.</p>
                          )}
                          {duties && duties.length > 0 && (
                            <>
                              <p className="hint" style={{ marginBottom: 4 }}>
                                Still theirs after that day — somebody has to take these over:
                              </p>
                              <ul className="hint" style={{ margin: "0 0 8px 18px", padding: 0 }}>
                                {duties.map((d, i) => (
                                  <li key={i}>{d.description}</li>
                                ))}
                              </ul>
                              <select
                                aria-label="Who takes over"
                                value={exiting.draft.successorStaffId}
                                onChange={(e) => setExiting({ id: m.id, draft: { ...exiting.draft, successorStaffId: e.target.value } })}
                              >
                                <option value="">Who takes over…</option>
                                {successorsFor(m, exiting.draft.lastWorkingDate).map((c) => (
                                  <option key={c.id} value={c.id}>
                                    {c.firstName} {c.lastName ?? ""}
                                  </option>
                                ))}
                              </select>
                            </>
                          )}
                          <input
                            placeholder="Why are they leaving?"
                            aria-label="Why are they leaving?"
                            style={{ display: "block", width: "100%", marginTop: 8 }}
                            value={exiting.draft.reason}
                            onChange={(e) => setExiting({ id: m.id, draft: { ...exiting.draft, reason: e.target.value } })}
                          />
                          {rowError && <div className="error-banner" style={{ marginTop: 8 }}>{rowError}</div>}
                          <div className="form-row" style={{ gap: 4, marginTop: 8, marginBottom: 0 }}>
                            <button
                              type="button"
                              onClick={() => onExit(m)}
                              disabled={
                                rowBusy || !exiting.draft.lastWorkingDate || !exiting.draft.reason.trim() ||
                                duties === null || (duties.length > 0 && !exiting.draft.successorStaffId)
                              }
                            >
                              {rowBusy ? "Recording…" : "Record exit"}
                            </button>
                            <button type="button" onClick={() => setExiting(null)} disabled={rowBusy}>Cancel</button>
                          </div>
                          <p className="hint">This cannot be undone here. Access ends after the last working day.</p>
                        </div>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}

        {leftCount > 0 && (
          <label className="form-row" style={{ alignItems: "center", gap: 6, marginTop: 12 }}>
            <input type="checkbox" checked={showLeft} onChange={(e) => setShowLeft(e.target.checked)} />
            Show {leftCount} who {leftCount === 1 ? "has" : "have"} left
          </label>
        )}
      </div>
    </main>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return err.userMessage;
  return err instanceof Error ? err.message : "Unknown error";
}
