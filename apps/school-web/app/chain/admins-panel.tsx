"use client";

import { useCallback, useEffect, useState } from "react";
import {
  ApiError,
  ChainAdminDto,
  addChainAdmin,
  deactivateChainAdmin,
  listChainAdmins,
} from "@/lib/api";

/**
 * Who signs in as this chain's HQ, and the form that adds another.
 *
 * Schoolsoft hands a chain over once, to one address. Everyone after that is
 * the chain's own to add — and to remove, when somebody leaves — so a chain is
 * never one departure away from asking its vendor to run SQL.
 *
 * The last active account cannot be deactivated: the button is not offered,
 * and the API refuses it anyway. Removing someone asks for a reason inline
 * rather than in a modal, because it goes into the audit log and is the one
 * thing an auditor will want to read.
 */
export default function AdminsPanel({ selfAccountId }: { selfAccountId: string }) {
  const [admins, setAdmins] = useState<ChainAdminDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [address, setAddress] = useState("");
  const [removing, setRemoving] = useState<string | null>(null);
  const [reason, setReason] = useState("");

  const refresh = useCallback(async () => {
    try {
      setAdmins(await listChainAdmins());
    } catch (err) {
      setError(describe(err));
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const activeCount = (admins ?? []).filter((a) => a.active).length;

  async function run(done: string, act: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await act();
      setNotice(done);
      await refresh();
    } catch (err) {
      setError(describe(err));
    } finally {
      setBusy(false);
    }
  }

  function add(e: React.FormEvent) {
    e.preventDefault();
    const value = address.trim();
    if (!value) return;
    // One field, either kind of address: the sign-in screen accepts both.
    const req = value.includes("@") ? { email: value } : { phone: value };
    run(`${value} can now sign in as the chain's HQ.`, async () => {
      await addChainAdmin(req);
      setAddress("");
    });
  }

  function remove(admin: ChainAdminDto) {
    const why = reason.trim();
    if (!why) {
      setError("Removing someone needs a reason — it is what the audit log will say.");
      return;
    }
    setRemoving(null);
    setReason("");
    run(`${admin.signsInWith} can no longer sign in.`, () => deactivateChainAdmin(admin.accountId, why));
  }

  return (
    <div className="panel">
      <h2>Who signs in as HQ</h2>
      <p className="hint">
        Everyone here sees every school in the chain. Add someone before the only person with
        access leaves — the chain cannot be left with nobody.
      </p>
      {error && <div className="error-banner">{error}</div>}
      {notice && <p className="hint">{notice}</p>}

      {admins === null ? (
        <p className="hint">Loading&hellip;</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Signs in with</th>
              <th>Added</th>
              <th>Status</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {admins.map((a) => (
              <tr key={a.accountId}>
                <td>
                  <code>{a.signsInWith}</code>
                  {a.accountId === selfAccountId && <span className="hint"> (you)</span>}
                </td>
                <td>{new Date(a.createdAt).toLocaleDateString()}</td>
                <td>{a.active ? "Active" : "Deactivated"}</td>
                <td>
                  {!a.active || activeCount <= 1 ? null : removing === a.accountId ? (
                    <div className="form-row inline">
                      <input
                        autoFocus
                        placeholder="Why they no longer need access"
                        value={reason}
                        onChange={(e) => setReason(e.target.value)}
                        onKeyDown={(e) => {
                          if (e.key === "Enter" && reason.trim()) remove(a);
                          if (e.key === "Escape") {
                            setRemoving(null);
                            setReason("");
                          }
                        }}
                        style={{ minWidth: 240 }}
                      />
                      <button type="button" disabled={busy || !reason.trim()} onClick={() => remove(a)}>
                        Deactivate
                      </button>
                      <button
                        type="button"
                        className="secondary"
                        disabled={busy}
                        onClick={() => {
                          setRemoving(null);
                          setReason("");
                        }}
                      >
                        Cancel
                      </button>
                    </div>
                  ) : (
                    <button
                      type="button"
                      className="secondary"
                      disabled={busy}
                      onClick={() => {
                        setReason("");
                        setRemoving(a.accountId);
                      }}
                    >
                      {a.accountId === selfAccountId ? "Remove myself" : "Deactivate"}
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <form onSubmit={add} style={{ marginTop: 16 }}>
        <div className="form-row">
          <input
            placeholder="Email or mobile number they will sign in with"
            value={address}
            onChange={(e) => setAddress(e.target.value)}
            disabled={busy}
            required
            style={{ minWidth: 320 }}
          />
          <button type="submit" disabled={busy || !address.trim()}>
            {busy ? "Saving…" : "Add HQ account"}
          </button>
        </div>
      </form>
    </div>
  );
}

function describe(err: unknown): string {
  if (err instanceof ApiError) return err.message;
  return err instanceof Error ? err.message : "Unknown error";
}
