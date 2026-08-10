"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import {
  DriverDto,
  endTrip,
  getSession,
  listRoutes,
  listVehicles,
  myDriverProfile,
  recordGpsPing,
  Session,
  startTrip,
  TransportRouteDto,
  TripDto,
  VehicleDto,
} from "@/lib/api";

const PING_INTERVAL_MS = 20000;

export default function HomePage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [driver, setDriver] = useState<DriverDto | null>(null);
  const [routes, setRoutes] = useState<TransportRouteDto[] | null>(null);
  const [vehicles, setVehicles] = useState<VehicleDto[] | null>(null);
  const [routeId, setRouteId] = useState("");
  const [vehicleId, setVehicleId] = useState("");
  const [direction, setDirection] = useState<"pickup" | "drop">("pickup");
  const [error, setError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [ending, setEnding] = useState(false);

  const [trip, setTrip] = useState<TripDto | null>(null);
  const [tripStartedAt, setTripStartedAt] = useState<number | null>(null);
  const [elapsed, setElapsed] = useState("00:00");
  const [lastPing, setLastPing] = useState<{ lat: number; lng: number; at: string } | null>(null);
  const [pingError, setPingError] = useState<string | null>(null);

  const pingTimer = useRef<ReturnType<typeof setInterval> | null>(null);
  const clockTimer = useRef<ReturnType<typeof setInterval> | null>(null);

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
    Promise.all([myDriverProfile(s.schoolId, s.subjectId), listRoutes(s.schoolId), listVehicles(s.schoolId)])
      .then(([drivers, rts, vhs]) => {
        setRoutes(rts);
        setVehicles(vhs);
        if (rts.length > 0) setRouteId(rts[0].id);
        if (vhs.length > 0) setVehicleId(vhs[0].id);
        if (drivers.length === 0) {
          setError("No driver profile is linked to this account yet.");
          return;
        }
        setDriver(drivers[0]);
      })
      .catch((err) => setError(describeError(err)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [router]);

  useEffect(() => {
    return () => {
      if (pingTimer.current) clearInterval(pingTimer.current);
      if (clockTimer.current) clearInterval(clockTimer.current);
    };
  }, []);

  function pingOnce(vId: string) {
    if (!navigator.geolocation) {
      setPingError("Geolocation isn't available in this browser.");
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        const { latitude, longitude, speed, heading } = pos.coords;
        setPingError(null);
        recordGpsPing({
          vehicleId: vId,
          occurredAt: new Date().toISOString(),
          lat: latitude,
          lng: longitude,
          speedKmh: speed != null ? speed * 3.6 : undefined,
          heading: heading ?? undefined,
        })
          .then(() => setLastPing({ lat: latitude, lng: longitude, at: new Date().toLocaleTimeString() }))
          .catch((err) => setPingError(describeError(err)));
      },
      (err) => setPingError(`Location error: ${err.message}`),
      { enableHighAccuracy: true, timeout: 10000 }
    );
  }

  async function onStart() {
    if (!session || !driver || !routeId || !vehicleId) return;
    setStarting(true);
    setError(null);
    try {
      const t = await startTrip({
        schoolId: session.schoolId,
        routeId,
        vehicleId,
        driverId: driver.id,
        direction,
      });
      setTrip(t);
      setTripStartedAt(Date.now());
      pingOnce(vehicleId);
      pingTimer.current = setInterval(() => pingOnce(vehicleId), PING_INTERVAL_MS);
      clockTimer.current = setInterval(() => {
        setTripStartedAt((started) => started);
      }, 1000);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setStarting(false);
    }
  }

  useEffect(() => {
    if (!tripStartedAt) return;
    const id = setInterval(() => {
      const secs = Math.floor((Date.now() - tripStartedAt) / 1000);
      const m = String(Math.floor(secs / 60)).padStart(2, "0");
      const s = String(secs % 60).padStart(2, "0");
      setElapsed(`${m}:${s}`);
    }, 1000);
    return () => clearInterval(id);
  }, [tripStartedAt]);

  async function onEnd() {
    if (!trip) return;
    setEnding(true);
    setError(null);
    try {
      await endTrip(trip.id);
      if (pingTimer.current) clearInterval(pingTimer.current);
      if (clockTimer.current) clearInterval(clockTimer.current);
      setTrip(null);
      setTripStartedAt(null);
      setLastPing(null);
      setElapsed("00:00");
    } catch (err) {
      setError(describeError(err));
    } finally {
      setEnding(false);
    }
  }

  if (!session) return null;

  return (
    <main className="shell">
      {error && <div className="error-banner">{error}</div>}

      {!trip && (
        <div className="panel">
          <h2>Start a trip</h2>
          <div className="form-row" style={{ flexDirection: "column" }}>
            <select value={routeId} onChange={(e) => setRouteId(e.target.value)} disabled={!routes || starting}>
              {routes?.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.code} — {r.name}
                </option>
              ))}
            </select>
            <select value={vehicleId} onChange={(e) => setVehicleId(e.target.value)} disabled={!vehicles || starting}>
              {vehicles?.map((v) => (
                <option key={v.id} value={v.id}>
                  {v.registrationNo}
                  {v.model ? ` — ${v.model}` : ""}
                </option>
              ))}
            </select>
            <select value={direction} onChange={(e) => setDirection(e.target.value as "pickup" | "drop")} disabled={starting}>
              <option value="pickup">Pickup</option>
              <option value="drop">Drop</option>
            </select>
            <button
              type="button"
              className="btn-block btn-large"
              onClick={onStart}
              disabled={starting || !driver || !routeId || !vehicleId}
            >
              {starting ? "Starting…" : "Start trip"}
            </button>
          </div>
          {!routes || routes.length === 0 ? <p className="hint">No routes set up for this school yet.</p> : null}
        </div>
      )}

      {trip && (
        <div className="panel">
          <div className="form-row" style={{ justifyContent: "space-between", alignItems: "center", marginBottom: 4 }}>
            <h2>Trip in progress</h2>
            <span className="badge badge-live">tracking</span>
          </div>
          <div className="stat-row">
            <div className="stat-chip">
              <span className="n">{elapsed}</span>
              <span className="l">Elapsed</span>
            </div>
            <div className="stat-chip">
              <span className="n">{direction === "pickup" ? "Pickup" : "Drop"}</span>
              <span className="l">Direction</span>
            </div>
          </div>
          <p className="hint" style={{ marginTop: 12 }}>
            {lastPing
              ? `Last ping ${lastPing.at} — ${lastPing.lat.toFixed(5)}, ${lastPing.lng.toFixed(5)}`
              : "Waiting for first GPS fix…"}
          </p>
          {pingError && <p className="hint" style={{ color: "var(--critical)" }}>{pingError}</p>}
          <button type="button" className="btn-block btn-large btn-danger" onClick={onEnd} disabled={ending} style={{ marginTop: 12 }}>
            {ending ? "Ending…" : "End trip"}
          </button>
        </div>
      )}
    </main>
  );
}

function describeError(err: unknown): string {
  if (err && typeof err === "object" && "message" in err) return String((err as Error).message);
  return "Unknown error";
}
