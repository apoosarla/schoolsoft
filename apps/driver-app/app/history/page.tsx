"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ApiError,
  DriverDto,
  getSession,
  myDriverProfile,
  Session,
  TripDto,
  tripsForDriver,
} from "@/lib/api";

function duration(startedAt: string, endedAt: string | null): string {
  const start = new Date(startedAt).getTime();
  const end = endedAt ? new Date(endedAt).getTime() : Date.now();
  const mins = Math.round((end - start) / 60000);
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

function manifestSummary(trip: TripDto): string {
  const entries = Object.values(trip.manifest);
  if (entries.length === 0) return "no check-ins";
  const boarded = entries.filter((e) => e.status === "boarded" || e.status === "dropped").length;
  const absent = entries.filter((e) => e.status === "absent").length;
  return `${boarded} checked in${absent > 0 ? `, ${absent} absent` : ""}`;
}

export default function HistoryPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [trips, setTrips] = useState<TripDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    setSessionState(s);
    if (!s.subjectId) {
      setError("This account has no staff record on file — a driver profile can't be resolved.");
      return;
    }
    myDriverProfile(s.schoolId, s.subjectId)
      .then(async (drivers: DriverDto[]) => {
        if (drivers.length === 0) {
          setError("No driver profile is linked to this account yet.");
          return;
        }
        const t = await tripsForDriver(drivers[0].id, 30);
        setTrips(t);
      })
      .catch((err) => setError(describeError(err)));
  }, [router]);

  if (!session) return null;

  return (
    <main className="shell">
      {error && <div className="error-banner">{error}</div>}

      {!trips && !error && (
        <div className="panel">
          <p className="hint">Loading…</p>
        </div>
      )}

      {trips && trips.length === 0 && (
        <div className="panel">
          <p className="empty-note">No trips yet.</p>
        </div>
      )}

      {trips && trips.length > 0 && (
        <div className="panel">
          <div className="timeline" style={{ marginTop: 4 }}>
            {trips.map((t) => (
              <div className="tl-item" key={t.id}>
                <div className="tl-time">{new Date(t.startedAt).toLocaleString()}</div>
                <div className="tl-title">
                  {t.direction === "pickup" ? "Pickup" : "Drop"} · {duration(t.startedAt, t.endedAt)}
                  {!t.endedAt && (
                    <span className="badge badge-live" style={{ marginLeft: 8 }}>
                      live
                    </span>
                  )}
                </div>
                <div className="tl-sub">{manifestSummary(t)}</div>
              </div>
            ))}
          </div>
        </div>
      )}
    </main>
  );
}

function describeError(err: unknown): string {
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}
