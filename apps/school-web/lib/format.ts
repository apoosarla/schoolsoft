/**
 * An API timestamp as a person reads it: "26 Sept 2026, 8:35 pm" in the
 * viewer's own zone. Screens were printing the ISO string, microseconds and
 * all, in UTC.
 */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" });
}

/** A bell time as "08:45" — the API sends "08:45:00". */
export function formatClock(time: string | null | undefined): string {
  if (!time) return "—";
  return time.length >= 5 ? time.slice(0, 5) : time;
}

/** A code the API speaks in ("credit_note", "application_started") as words. */
export function humanize(code: string | null | undefined): string {
  if (!code) return "—";
  const words = code.replace(/_/g, " ").toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}
