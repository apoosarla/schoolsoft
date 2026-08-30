"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  assignStaffRole,
  createRole,
  deleteRole,
  getSession,
  hasScreen,
  listRoles,
  listStaffRoles,
  RoleDto,
  SCREEN_DEFS,
  Session,
  StaffWithRolesDto,
  unassignStaffRole,
  updateRole,
} from "@/lib/api";

const emptyForm = { code: "", name: "", description: "", screenKeys: [] as string[] };

export default function RolesPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [roles, setRoles] = useState<RoleDto[] | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState(emptyForm);
  const [creating, setCreating] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editScreens, setEditScreens] = useState<string[]>([]);
  const [savingId, setSavingId] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  const [staff, setStaff] = useState<StaffWithRolesDto[] | null>(null);
  const [assignRoleCode, setAssignRoleCode] = useState<Record<string, string>>({});
  const [assignReason, setAssignReason] = useState<Record<string, string>>({});
  const [assigningId, setAssigningId] = useState<string | null>(null);

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    if (!hasScreen(s, "admin")) {
      router.replace("/dashboard");
      return;
    }
    setSessionState(s);
    refreshRoles();
    refreshStaff(s.schoolId);
  }, [router]);

  function refreshRoles() {
    listRoles()
      .then(setRoles)
      .catch((err) => setError(describeError(err)));
  }

  function refreshStaff(schoolId: string) {
    listStaffRoles(schoolId)
      .then(setStaff)
      .catch((err) => setError(describeError(err)));
  }

  function toggleFormScreen(key: string) {
    setForm((f) => ({
      ...f,
      screenKeys: f.screenKeys.includes(key) ? f.screenKeys.filter((k) => k !== key) : [...f.screenKeys, key],
    }));
  }

  async function onCreate() {
    setCreating(true);
    setError(null);
    try {
      await createRole({
        code: form.code,
        name: form.name,
        description: form.description || undefined,
        screenKeys: form.screenKeys,
      });
      setForm(emptyForm);
      setShowForm(false);
      refreshRoles();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setCreating(false);
    }
  }

  function startEdit(role: RoleDto) {
    setEditingId(role.id);
    setEditScreens(role.screenKeys);
  }

  function toggleEditScreen(key: string) {
    setEditScreens((s) => (s.includes(key) ? s.filter((k) => k !== key) : [...s, key]));
  }

  async function onSaveEdit(role: RoleDto) {
    setSavingId(role.id);
    setError(null);
    try {
      await updateRole(role.id, {
        name: role.name,
        description: role.description ?? undefined,
        screenKeys: editScreens,
        // The version this row was loaded with. If somebody else saved the
        // role since, the server refuses rather than letting this overwrite
        // their change, and the 409 lands in `error` below.
        expectedVersion: role.version,
      });
      setEditingId(null);
      refreshRoles();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSavingId(null);
    }
  }

  async function onDelete(role: RoleDto) {
    setDeletingId(role.id);
    setError(null);
    try {
      await deleteRole(role.id);
      refreshRoles();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setDeletingId(null);
    }
  }

  async function onAssign(member: StaffWithRolesDto) {
    if (!session) return;
    const roleCode = assignRoleCode[member.staffId];
    const reason = (assignReason[member.staffId] ?? "").trim();
    if (!roleCode || !reason) return;
    setAssigningId(member.staffId);
    setError(null);
    try {
      await assignStaffRole(member.staffId, session.schoolId, roleCode, reason);
      setAssignRoleCode((m) => ({ ...m, [member.staffId]: "" }));
      setAssignReason((m) => ({ ...m, [member.staffId]: "" }));
      refreshStaff(session.schoolId);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setAssigningId(null);
    }
  }

  async function onUnassign(member: StaffWithRolesDto, roleCode: string) {
    if (!session) return;
    const reason = window.prompt("Why is this role being revoked?")?.trim();
    if (!reason) return;
    setAssigningId(member.staffId);
    setError(null);
    try {
      await unassignStaffRole(member.staffId, session.schoolId, roleCode, reason);
      refreshStaff(session.schoolId);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setAssigningId(null);
    }
  }

  if (!session) return null;

  const roleName = (code: string) => roles?.find((r) => r.code === code)?.name ?? code;

  return (
    <main className="shell">
      <div className="panel">
        <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center" }}>
          <h2>Roles</h2>
          <button type="button" onClick={() => setShowForm((v) => !v)}>
            {showForm ? "Cancel" : "New custom role"}
          </button>
        </div>

        {showForm && (
          <div>
            <div className="form-row" style={{ flexWrap: "wrap" }}>
              <input
                placeholder="Code (e.g. sports_coordinator)"
                value={form.code}
                onChange={(e) => setForm((f) => ({ ...f, code: e.target.value }))}
                style={{ minWidth: 220 }}
              />
              <input
                placeholder="Name"
                value={form.name}
                onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))}
                style={{ minWidth: 200 }}
              />
              <input
                placeholder="Description"
                value={form.description}
                onChange={(e) => setForm((f) => ({ ...f, description: e.target.value }))}
                style={{ minWidth: 260 }}
              />
            </div>
            <div className="form-row" style={{ flexWrap: "wrap" }}>
              {SCREEN_DEFS.map((s) => (
                <label key={s.key} className="form-row" style={{ alignItems: "center", gap: 4, marginBottom: 0 }}>
                  <input
                    type="checkbox"
                    checked={form.screenKeys.includes(s.key)}
                    onChange={() => toggleFormScreen(s.key)}
                  />
                  {s.label}
                </label>
              ))}
            </div>
            <div className="form-row">
              <button type="button" onClick={onCreate} disabled={creating || !form.code || !form.name}>
                {creating ? "Creating…" : "Create role"}
              </button>
            </div>
          </div>
        )}

        {error && <div className="error-banner">{error}</div>}

        {roles && (
          <table>
            <thead>
              <tr>
                <th>Role</th>
                <th>Screens</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {roles.map((r) => (
                <tr key={r.id}>
                  <td>
                    {r.name} <span className="hint">({r.code})</span>
                    {r.isSystem && <span className="badge" style={{ marginLeft: 6 }}>system</span>}
                    {r.description && (
                      <>
                        <br />
                        <span className="hint">{r.description}</span>
                      </>
                    )}
                  </td>
                  <td>
                    {editingId === r.id ? (
                      <div className="form-row" style={{ flexWrap: "wrap", marginBottom: 0 }}>
                        {SCREEN_DEFS.map((s) => (
                          <label key={s.key} className="form-row" style={{ alignItems: "center", gap: 4, marginBottom: 0 }}>
                            <input
                              type="checkbox"
                              checked={editScreens.includes(s.key)}
                              onChange={() => toggleEditScreen(s.key)}
                            />
                            {s.label}
                          </label>
                        ))}
                      </div>
                    ) : (
                      r.screenKeys.map((k) => (
                        <span key={k} className="badge" style={{ marginRight: 4 }}>
                          {SCREEN_DEFS.find((s) => s.key === k)?.label ?? k}
                        </span>
                      ))
                    )}
                  </td>
                  <td>
                    {editingId === r.id ? (
                      <div className="form-row" style={{ gap: 4 }}>
                        <button type="button" onClick={() => onSaveEdit(r)} disabled={savingId === r.id}>
                          {savingId === r.id ? "…" : "Save"}
                        </button>
                        <button type="button" onClick={() => setEditingId(null)}>
                          Cancel
                        </button>
                      </div>
                    ) : (
                      <div className="form-row" style={{ gap: 4 }}>
                        <button type="button" onClick={() => startEdit(r)}>
                          Edit
                        </button>
                        {!r.isSystem && (
                          <button type="button" onClick={() => onDelete(r)} disabled={deletingId === r.id}>
                            {deletingId === r.id ? "…" : "Delete"}
                          </button>
                        )}
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="panel">
        <h2>Staff & role assignments</h2>
        {staff && staff.length === 0 && <p className="hint">No staff records for this school yet.</p>}
        {staff && staff.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Staff</th>
                <th>Roles</th>
                <th>Assign</th>
              </tr>
            </thead>
            <tbody>
              {staff.map((m) => (
                <tr key={m.staffId}>
                  <td>
                    {m.firstName} {m.lastName ?? ""}
                    <br />
                    <span className="hint">{m.email ?? "no login account"}</span>
                  </td>
                  <td>
                    {m.roleCodes.length === 0 && <span className="hint">No roles assigned</span>}
                    {m.roleCodes.map((code) => (
                      <span key={code} className="badge" style={{ marginRight: 4 }}>
                        {roleName(code)}{" "}
                        <button
                          type="button"
                          onClick={() => onUnassign(m, code)}
                          disabled={assigningId === m.staffId}
                          style={{
                            background: "none",
                            border: "none",
                            color: "inherit",
                            padding: 0,
                            marginLeft: 4,
                            cursor: "pointer",
                            fontSize: 12,
                          }}
                        >
                          ×
                        </button>
                      </span>
                    ))}
                  </td>
                  <td>
                    <div className="form-row" style={{ gap: 4 }}>
                      <select
                        value={assignRoleCode[m.staffId] ?? ""}
                        onChange={(e) => setAssignRoleCode((s) => ({ ...s, [m.staffId]: e.target.value }))}
                      >
                        <option value="">Role…</option>
                        {roles
                          ?.filter((r) => !m.roleCodes.includes(r.code))
                          .map((r) => (
                            <option key={r.code} value={r.code}>
                              {r.name}
                            </option>
                          ))}
                      </select>
                      <input
                        placeholder="Reason"
                        value={assignReason[m.staffId] ?? ""}
                        onChange={(e) =>
                          setAssignReason((s) => ({ ...s, [m.staffId]: e.target.value }))
                        }
                      />
                      <button
                        type="button"
                        onClick={() => onAssign(m)}
                        disabled={
                          assigningId === m.staffId ||
                          !assignRoleCode[m.staffId] ||
                          !(assignReason[m.staffId] ?? "").trim()
                        }
                      >
                        Assign
                      </button>
                    </div>
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
