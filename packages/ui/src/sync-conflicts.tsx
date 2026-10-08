"use client";

import styles from "./sync-conflicts.module.css";

export type SyncConflict = {
  /** Unique across registers: a phone may hold conflicts for more than one. */
  key: string;
  /** Who it is about, as this screen names them: a name, or a roll number. */
  who: string;
  /** Which register, when the list can span several: "Grade 5-B · 24 Aug". */
  where?: string;
  /** `changed`: somebody recorded something else meanwhile. `refused`: it cannot be written at all. */
  kind: "changed" | "refused";
  yours: string;
  /** The record as it stands, absent when there is none. */
  theirs?: { status: string; source: string; markedAt: string };
  /** Why, for a refusal. */
  message?: string;
};

export type SyncConflictsProps = {
  conflicts: SyncConflict[];
  busy?: boolean;
  /** Overrule what is there with the mark that was sent. Only offered for `changed`. */
  onUseMine: (conflict: SyncConflict) => void;
  /** Accept what is there and drop the mark that was sent. */
  onKeepTheirs: (conflict: SyncConflict) => void;
};

function by(source: string, status: string): string {
  if (status === "leave") return "an approved leave";
  if (source === "biometric" || source === "rfid") return "the gate";
  return "somebody else";
}

function at(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "" : d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

/**
 * Marks that were sent and did not land, because the register had moved since
 * the sender last read it.
 *
 * A register marked on a phone with no signal is sent later, and by then the
 * office may have recorded a leave for one of the children. Neither value is
 * simply right — the teacher saw the room, the office saw the letter — so the
 * choice goes to a person, with both in front of them, instead of to whichever
 * request arrived last.
 */
export function SyncConflicts({ conflicts, busy, onUseMine, onKeepTheirs }: SyncConflictsProps) {
  if (conflicts.length === 0) return null;
  return (
    <ul className={styles.list} aria-label="Marks that need a decision">
      {conflicts.map((c) => (
        <li key={c.key} className={styles.item}>
          <div className={styles.who}>
            {c.who} {c.where && <span className={styles.where}>· {c.where}</span>}
          </div>
          {c.kind === "changed" ? (
            <div className={styles.values}>
              You marked <strong>{c.yours.replace("_", " ")}</strong>.{" "}
              {c.theirs ? (
                <>
                  Since you last looked, {by(c.theirs.source, c.theirs.status)} recorded{" "}
                  <strong>{c.theirs.status.replace("_", " ")}</strong>
                  {at(c.theirs.markedAt) && ` at ${at(c.theirs.markedAt)}`}.
                </>
              ) : (
                <>The record has changed since you last looked.</>
              )}
            </div>
          ) : (
            <div className={styles.values}>
              Your <strong>{c.yours.replace("_", " ")}</strong> was not saved. {c.message}
            </div>
          )}
          <div className={styles.actions}>
            {c.kind === "changed" && (
              <button type="button" disabled={busy} onClick={() => onUseMine(c)}>
                Use mine: {c.yours.replace("_", " ")}
              </button>
            )}
            <button type="button" className={styles.secondary} disabled={busy} onClick={() => onKeepTheirs(c)}>
              {c.kind === "changed" && c.theirs ? `Keep ${c.theirs.status.replace("_", " ")}` : "Dismiss"}
            </button>
          </div>
        </li>
      ))}
    </ul>
  );
}
