"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  addTransportStop,
  ApiError,
  assignRoute,
  assignStudentTransport,
  createDriver,
  createTransportRoute,
  createVehicle,
  deleteRouteAssignment,
  DriverDto,
  endRouteAssignment,
  geofenceStatus,
  GeofenceStatusDto,
  getSession,
  hasScreen,
  listDirectory,
  listRouteAssignments,
  listRouteGaps,
  linkDriver,
  listDrivers,
  unlinkDriver,
  listStudents,
  listTransportRoutes,
  listTransportStops,
  listTripsForSchool,
  listVehicles,
  RouteAssignmentDto,
  RouteGapDto,
  RouteRiderDto,
  Session,
  StudentDto,
  studentsOnRoute,
  TransportRouteDto,
  TransportStopDto,
  TripDto,
  UserDirectoryEntryDto,
  VehicleDto,
} from "@/lib/api";

function todayIso(): string {
  // The local calendar date. toISOString() is UTC, which in India is still
  // yesterday until 05:30 — and a register opened early landed on the wrong day.
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

/** The ISO date after `iso`, in local calendar terms. */
function nextDay(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number);
  const n = new Date(y, m - 1, d + 1);
  return `${n.getFullYear()}-${String(n.getMonth() + 1).padStart(2, "0")}-${String(n.getDate()).padStart(2, "0")}`;
}

function describeGap(g: RouteGapDto, today: string): string {
  const from = g.uncoveredFrom === today ? "today" : g.uncoveredFrom;
  const until = g.coveredAgainOn ? ` until ${g.coveredAgainOn}` : "";
  return `${g.routeCode} (${g.routeName}) has no driver from ${from}${until}.`;
}

function duration(startedAt: string, endedAt: string | null): string {
  const start = new Date(startedAt).getTime();
  const end = endedAt ? new Date(endedAt).getTime() : Date.now();
  const mins = Math.round((end - start) / 60000);
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

export default function TransportPage() {
  const router = useRouter();
  const [session, setSessionState] = useState<Session | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [vehicles, setVehicles] = useState<VehicleDto[] | null>(null);
  const [vehicleForm, setVehicleForm] = useState({ registrationNo: "", model: "", capacity: "" });
  const [creatingVehicle, setCreatingVehicle] = useState(false);

  const [drivers, setDrivers] = useState<DriverDto[] | null>(null);
  const [driverForm, setDriverForm] = useState({ name: "", phone: "", licenseNo: "" });
  const [driverStaffQ, setDriverStaffQ] = useState("");
  const [driverStaffResults, setDriverStaffResults] = useState<UserDirectoryEntryDto[] | null>(null);
  const [driverStaff, setDriverStaff] = useState<UserDirectoryEntryDto | null>(null);
  const [creatingDriver, setCreatingDriver] = useState(false);
  // Unlinking takes the driver app away from somebody, so it asks twice.
  const [confirmUnlinkId, setConfirmUnlinkId] = useState<string | null>(null);
  const [linkPick, setLinkPick] = useState<Record<string, string>>({});
  const [busyDriverId, setBusyDriverId] = useState<string | null>(null);

  const [routes, setRoutes] = useState<TransportRouteDto[] | null>(null);
  const [routeForm, setRouteForm] = useState({ code: "", name: "", direction: "pickup" });
  const [creatingRoute, setCreatingRoute] = useState(false);
  const [selectedRouteId, setSelectedRouteId] = useState("");

  const [rosters, setRosters] = useState<RouteAssignmentDto[] | null>(null);
  const [gaps, setGaps] = useState<RouteGapDto[]>([]);
  const [rosterForm, setRosterForm] = useState({ routeId: "", vehicleId: "", driverId: "", effectiveFrom: todayIso() });
  const [savingRoster, setSavingRoster] = useState(false);
  // Ending or deleting an assignment takes a route away from somebody, so each asks twice.
  const [rosterAction, setRosterAction] = useState<{ id: string; kind: "end" | "delete"; lastDay: string } | null>(null);
  const [busyRosterId, setBusyRosterId] = useState<string | null>(null);
  const [stops, setStops] = useState<TransportStopDto[] | null>(null);
  const [stopForm, setStopForm] = useState({ name: "", lat: "", lng: "", fee: "" });
  const [addingStop, setAddingStop] = useState(false);
  const [routeStudents, setRouteStudents] = useState<RouteRiderDto[] | null>(null);

  const [studentQ, setStudentQ] = useState("");
  const [studentResults, setStudentResults] = useState<StudentDto[] | null>(null);
  const [assignStudentId, setAssignStudentId] = useState<string | null>(null);
  const [assignStopId, setAssignStopId] = useState("");
  const [assigning, setAssigning] = useState(false);

  const [trips, setTrips] = useState<TripDto[] | null>(null);

  const [gfVehicleId, setGfVehicleId] = useState("");
  const [gfStopId, setGfStopId] = useState("");
  const [gfResult, setGfResult] = useState<GeofenceStatusDto | null>(null);
  const [gfChecking, setGfChecking] = useState(false);
  const [gfError, setGfError] = useState<string | null>(null);

  useEffect(() => {
    const s = getSession();
    if (!s) {
      router.replace("/login");
      return;
    }
    if (!hasScreen(s, "transport")) {
      router.replace("/dashboard");
      return;
    }
    setSessionState(s);
    refreshAll(s.schoolId);
  }, [router]);

  function refreshRosters(schoolId: string) {
    listRouteAssignments(schoolId).then(setRosters).catch((err) => setError(describeError(err)));
    listRouteGaps(schoolId).then(setGaps).catch((err) => setError(describeError(err)));
  }

  function refreshAll(schoolId: string) {
    listVehicles(schoolId).then(setVehicles).catch((err) => setError(describeError(err)));
    listDrivers(schoolId).then(setDrivers).catch((err) => setError(describeError(err)));
    refreshRosters(schoolId);
    listTransportRoutes(schoolId)
      .then((rs) => {
        setRoutes(rs);
        if (rs.length > 0) setSelectedRouteId((cur) => cur || rs[0].id);
      })
      .catch((err) => setError(describeError(err)));
    listTripsForSchool(schoolId).then(setTrips).catch((err) => setError(describeError(err)));
  }

  useEffect(() => {
    if (!selectedRouteId) return;
    listTransportStops(selectedRouteId).then(setStops).catch((err) => setError(describeError(err)));
    studentsOnRoute(selectedRouteId).then(setRouteStudents).catch((err) => setError(describeError(err)));
  }, [selectedRouteId]);

  async function onCreateVehicle() {
    if (!session || !vehicleForm.registrationNo.trim()) return;
    setCreatingVehicle(true);
    setError(null);
    try {
      await createVehicle(session.schoolId, {
        registrationNo: vehicleForm.registrationNo.trim(),
        model: vehicleForm.model.trim() || undefined,
        capacity: vehicleForm.capacity ? Number(vehicleForm.capacity) : undefined,
      });
      setVehicleForm({ registrationNo: "", model: "", capacity: "" });
      setVehicles(await listVehicles(session.schoolId));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setCreatingVehicle(false);
    }
  }

  // Who each driver's app login belongs to. "linked" alone hid a driver
  // linked to the wrong person's account.
  const [staffAccounts, setStaffAccounts] = useState<UserDirectoryEntryDto[] | null>(null);
  useEffect(() => {
    if (!session) return;
    listDirectory(session.schoolId, undefined, "staff")
      .then(setStaffAccounts)
      .catch(() => setStaffAccounts([]));
  }, [session]);

  useEffect(() => {
    if (!session || !driverStaffQ) {
      setDriverStaffResults(null);
      return;
    }
    listDirectory(session.schoolId, driverStaffQ, "staff")
      .then(setDriverStaffResults)
      .catch((err) => setError(describeError(err)));
  }, [session, driverStaffQ]);

  async function onCreateDriver() {
    if (!session || !driverForm.name.trim()) return;
    setCreatingDriver(true);
    setError(null);
    try {
      await createDriver(session.schoolId, {
        name: driverForm.name.trim(),
        phone: driverForm.phone.trim() || undefined,
        licenseNo: driverForm.licenseNo.trim() || undefined,
        staffId: driverStaff?.subjectId ?? undefined,
      });
      setDriverForm({ name: "", phone: "", licenseNo: "" });
      setDriverStaff(null);
      setDriverStaffQ("");
      setDrivers(await listDrivers(session.schoolId));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setCreatingDriver(false);
    }
  }

  async function onUnlinkDriver(driverId: string) {
    if (!session) return;
    setBusyDriverId(driverId);
    setError(null);
    try {
      await unlinkDriver(session.schoolId, driverId);
      setConfirmUnlinkId(null);
      setDrivers(await listDrivers(session.schoolId));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusyDriverId(null);
    }
  }

  async function onLinkDriver(driverId: string) {
    const staffId = linkPick[driverId];
    if (!session || !staffId) return;
    setBusyDriverId(driverId);
    setError(null);
    try {
      await linkDriver(session.schoolId, driverId, staffId);
      setLinkPick((p) => ({ ...p, [driverId]: "" }));
      setDrivers(await listDrivers(session.schoolId));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusyDriverId(null);
    }
  }

  async function onAssignRoute() {
    if (!session || !rosterForm.routeId || !rosterForm.vehicleId || !rosterForm.driverId || !rosterForm.effectiveFrom) {
      return;
    }
    setSavingRoster(true);
    setError(null);
    try {
      await assignRoute({ schoolId: session.schoolId, ...rosterForm });
      setRosterForm((f) => ({ ...f, driverId: "" }));
      refreshRosters(session.schoolId);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSavingRoster(false);
    }
  }

  async function onConfirmRosterAction() {
    if (!session || !rosterAction) return;
    setBusyRosterId(rosterAction.id);
    setError(null);
    try {
      if (rosterAction.kind === "end") {
        await endRouteAssignment(session.schoolId, rosterAction.id, rosterAction.lastDay);
      } else {
        await deleteRouteAssignment(session.schoolId, rosterAction.id);
      }
      setRosterAction(null);
      refreshRosters(session.schoolId);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusyRosterId(null);
    }
  }

  async function onCreateRoute() {
    if (!session || !routeForm.code.trim() || !routeForm.name.trim()) return;
    setCreatingRoute(true);
    setError(null);
    try {
      const created = await createTransportRoute(session.schoolId, {
        code: routeForm.code.trim(),
        name: routeForm.name.trim(),
        direction: routeForm.direction,
      });
      setRouteForm({ code: "", name: "", direction: "pickup" });
      const rs = await listTransportRoutes(session.schoolId);
      refreshRosters(session.schoolId);
      setRoutes(rs);
      setSelectedRouteId(created.id);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setCreatingRoute(false);
    }
  }

  async function onAddStop() {
    if (!selectedRouteId || !stopForm.name.trim()) return;
    setAddingStop(true);
    setError(null);
    try {
      await addTransportStop(selectedRouteId, {
        name: stopForm.name.trim(),
        sortOrder: (stops?.length ?? 0) + 1,
        lat: stopForm.lat ? Number(stopForm.lat) : undefined,
        lng: stopForm.lng ? Number(stopForm.lng) : undefined,
        fee: stopForm.fee ? Number(stopForm.fee) : undefined,
      });
      setStopForm({ name: "", lat: "", lng: "", fee: "" });
      setStops(await listTransportStops(selectedRouteId));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setAddingStop(false);
    }
  }

  useEffect(() => {
    if (!session || !studentQ) {
      setStudentResults(null);
      return;
    }
    listStudents(session.schoolId, studentQ)
      .then(setStudentResults)
      .catch((err) => setError(describeError(err)));
  }, [session, studentQ]);

  async function onAssignStudent() {
    if (!session || !assignStudentId || !selectedRouteId || !assignStopId) return;
    setAssigning(true);
    setError(null);
    try {
      await assignStudentTransport({
        schoolId: session.schoolId,
        studentId: assignStudentId,
        routeId: selectedRouteId,
        stopId: assignStopId,
        startsOn: todayIso(),
      });
      setAssignStudentId(null);
      setStudentQ("");
      setStudentResults(null);
      setRouteStudents(await studentsOnRoute(selectedRouteId));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setAssigning(false);
    }
  }

  async function onCheckGeofence() {
    if (!gfVehicleId || !gfStopId) return;
    setGfChecking(true);
    setGfError(null);
    setGfResult(null);
    try {
      setGfResult(await geofenceStatus(gfVehicleId, gfStopId));
    } catch (err) {
      setGfError(describeError(err));
    } finally {
      setGfChecking(false);
    }
  }

  if (!session) return null;

  const routeName = (id: string) => routes?.find((r) => r.id === id)?.name ?? id.slice(0, 8);
  const vehicleReg = (id: string) => vehicles?.find((v) => v.id === id)?.registrationNo ?? id.slice(0, 8);
  const driverName = (id: string) => drivers?.find((d) => d.id === id)?.name ?? id.slice(0, 8);

  return (
    <main className="shell">
      {error && <div className="error-banner">{error}</div>}
      {gaps.length > 0 && (
        <div className="warn-banner">
          {gaps.map((g) => (
            <div key={g.routeId}>{describeGap(g, todayIso())}</div>
          ))}
          <div>Assign a driver under “Who drives each route”, or the bus has nobody to run it in the driver app.</div>
        </div>
      )}

      <div className="panel">
        <h2>Vehicles</h2>
        <div className="form-row">
          <input
            placeholder="Registration no."
            value={vehicleForm.registrationNo}
            onChange={(e) => setVehicleForm((f) => ({ ...f, registrationNo: e.target.value }))}
          />
          <input
            placeholder="Model"
            value={vehicleForm.model}
            onChange={(e) => setVehicleForm((f) => ({ ...f, model: e.target.value }))}
          />
          <input
            placeholder="Capacity"
            type="number"
            value={vehicleForm.capacity}
            onChange={(e) => setVehicleForm((f) => ({ ...f, capacity: e.target.value }))}
            style={{ width: 100 }}
          />
          <button type="button" onClick={onCreateVehicle} disabled={creatingVehicle || !vehicleForm.registrationNo.trim()}>
            {creatingVehicle ? "Adding…" : "Add vehicle"}
          </button>
        </div>
        {vehicles && vehicles.length === 0 && <p className="hint">No vehicles yet.</p>}
        {vehicles && vehicles.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Reg. no.</th>
                <th>Model</th>
                <th>Capacity</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {vehicles.map((v) => (
                <tr key={v.id}>
                  <td>{v.registrationNo}</td>
                  <td>{v.model ?? "—"}</td>
                  <td>{v.capacity ?? "—"}</td>
                  <td>
                    <span className={`badge ${v.isActive ? "badge-active" : ""}`}>{v.isActive ? "active" : "inactive"}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="panel">
        <h2>Drivers</h2>
        <div className="form-row" style={{ flexWrap: "wrap", alignItems: "flex-start" }}>
          <input placeholder="Name" value={driverForm.name} onChange={(e) => setDriverForm((f) => ({ ...f, name: e.target.value }))} />
          <input placeholder="Phone" value={driverForm.phone} onChange={(e) => setDriverForm((f) => ({ ...f, phone: e.target.value }))} />
          <input
            placeholder="License no."
            value={driverForm.licenseNo}
            onChange={(e) => setDriverForm((f) => ({ ...f, licenseNo: e.target.value }))}
          />
          <div>
            <input
              placeholder="Link staff account (search)"
              value={driverStaff ? driverStaff.displayName : driverStaffQ}
              onChange={(e) => {
                setDriverStaff(null);
                setDriverStaffQ(e.target.value);
              }}
              style={{ minWidth: 220 }}
            />
            {!driverStaff && driverStaffResults && driverStaffResults.length > 0 && (
              <table>
                <tbody>
                  {driverStaffResults.map((u) => (
                    <tr
                      key={u.userAccountId}
                      style={{ cursor: "pointer" }}
                      onClick={() => {
                        setDriverStaff(u);
                        setDriverStaffResults(null);
                      }}
                    >
                      <td>{u.displayName}</td>
                      <td className="hint">{u.email ?? u.phone}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
          <button type="button" onClick={onCreateDriver} disabled={creatingDriver || !driverForm.name.trim()}>
            {creatingDriver ? "Adding…" : "Add driver"}
          </button>
        </div>
        {drivers && drivers.length === 0 && <p className="hint">No drivers yet.</p>}
        {drivers && drivers.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Phone</th>
                <th>License</th>
                <th>App login</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {drivers.map((d) => (
                <tr key={d.id}>
                  <td>{d.name}</td>
                  <td>{d.phone ?? "—"}</td>
                  <td>{d.licenseNo ?? "—"}</td>
                  <td>
                    <span className={`badge ${d.staffId ? "badge-active" : ""}`}>{d.staffId ? "linked" : "not linked"}</span>
                    {d.staffId && (() => {
                      const account = staffAccounts?.find((a) => a.subjectId === d.staffId);
                      return (
                        <div className="hint">
                          {account ? `${account.displayName}${account.email ? ` · ${account.email}` : ""}` : "a staff record with no sign-in"}
                        </div>
                      );
                    })()}
                  </td>
                  <td>
                    {d.staffId && confirmUnlinkId !== d.id && (
                      <button type="button" className="secondary" onClick={() => setConfirmUnlinkId(d.id)}>
                        Unlink
                      </button>
                    )}
                    {d.staffId && confirmUnlinkId === d.id && (
                      <div className="form-row">
                        <span className="hint">Removes their driver app access.</span>
                        <button type="button" onClick={() => onUnlinkDriver(d.id)} disabled={busyDriverId === d.id}>
                          {busyDriverId === d.id ? "Unlinking…" : "Confirm unlink"}
                        </button>
                        <button type="button" className="secondary" onClick={() => setConfirmUnlinkId(null)}>
                          Cancel
                        </button>
                      </div>
                    )}
                    {!d.staffId && (
                      <div className="form-row">
                        <select
                          value={linkPick[d.id] ?? ""}
                          onChange={(e) => setLinkPick((p) => ({ ...p, [d.id]: e.target.value }))}
                        >
                          <option value="">Link staff account…</option>
                          {staffAccounts
                            ?.filter((a) => a.subjectId && !drivers.some((x) => x.staffId === a.subjectId))
                            .map((a) => (
                              <option key={a.userAccountId} value={a.subjectId!}>
                                {a.displayName}
                                {a.email ? ` · ${a.email}` : ""}
                              </option>
                            ))}
                        </select>
                        <button
                          type="button"
                          onClick={() => onLinkDriver(d.id)}
                          disabled={!linkPick[d.id] || busyDriverId === d.id}
                        >
                          {busyDriverId === d.id ? "Linking…" : "Link"}
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

      <div className="panel">
        <h2>Routes &amp; stops</h2>
        <div className="form-row">
          <input placeholder="Code" value={routeForm.code} onChange={(e) => setRouteForm((f) => ({ ...f, code: e.target.value }))} style={{ width: 90 }} />
          <input placeholder="Name" value={routeForm.name} onChange={(e) => setRouteForm((f) => ({ ...f, name: e.target.value }))} />
          <select value={routeForm.direction} onChange={(e) => setRouteForm((f) => ({ ...f, direction: e.target.value }))}>
            <option value="pickup">Pickup</option>
            <option value="drop">Drop</option>
          </select>
          <button type="button" onClick={onCreateRoute} disabled={creatingRoute || !routeForm.code.trim() || !routeForm.name.trim()}>
            {creatingRoute ? "Adding…" : "Add route"}
          </button>
        </div>

        {routes && routes.length === 0 && <p className="hint">No routes yet.</p>}
        {routes && routes.length > 0 && (
          <>
            <div className="form-row">
              <select value={selectedRouteId} onChange={(e) => setSelectedRouteId(e.target.value)}>
                {routes.map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.code} — {r.name} ({r.direction})
                  </option>
                ))}
              </select>
            </div>

            <div className="form-row" style={{ marginTop: 4 }}>
              <input placeholder="Stop name" value={stopForm.name} onChange={(e) => setStopForm((f) => ({ ...f, name: e.target.value }))} />
              <input placeholder="Lat" value={stopForm.lat} onChange={(e) => setStopForm((f) => ({ ...f, lat: e.target.value }))} style={{ width: 100 }} />
              <input placeholder="Lng" value={stopForm.lng} onChange={(e) => setStopForm((f) => ({ ...f, lng: e.target.value }))} style={{ width: 100 }} />
              <input placeholder="Fee" value={stopForm.fee} onChange={(e) => setStopForm((f) => ({ ...f, fee: e.target.value }))} style={{ width: 90 }} />
              <button type="button" onClick={onAddStop} disabled={addingStop || !stopForm.name.trim()}>
                {addingStop ? "Adding…" : "Add stop"}
              </button>
            </div>

            {stops && stops.length === 0 && <p className="hint">No stops on this route yet.</p>}
            {stops && stops.length > 0 && (
              <table>
                <thead>
                  <tr>
                    <th>#</th>
                    <th>Stop</th>
                    <th>Coordinates</th>
                    <th>Fee</th>
                  </tr>
                </thead>
                <tbody>
                  {stops.map((s) => (
                    <tr key={s.id}>
                      <td>{s.sortOrder}</td>
                      <td>{s.name}</td>
                      <td>{s.lat != null && s.lng != null ? `${s.lat.toFixed(4)}, ${s.lng.toFixed(4)}` : "—"}</td>
                      <td>{s.fee ?? "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            <h2 style={{ marginTop: 16 }}>Assign a student to this route</h2>
            <div className="form-row" style={{ alignItems: "flex-start" }}>
              <div>
                <input
                  placeholder="Search students"
                  value={studentQ}
                  onChange={(e) => {
                    setAssignStudentId(null);
                    setStudentQ(e.target.value);
                  }}
                  style={{ minWidth: 200 }}
                />
                {studentResults && studentResults.length > 0 && !assignStudentId && (
                  <table>
                    <tbody>
                      {studentResults.map((st) => (
                        <tr key={st.id} style={{ cursor: "pointer" }} onClick={() => setAssignStudentId(st.id)}>
                          <td>
                            {st.firstName} {st.lastName ?? ""}
                          </td>
                          <td className="hint">{st.admissionNo}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
              <select value={assignStopId} onChange={(e) => setAssignStopId(e.target.value)} disabled={!stops || stops.length === 0}>
                <option value="">Stop…</option>
                {stops?.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </select>
              <button type="button" onClick={onAssignStudent} disabled={assigning || !assignStudentId || !assignStopId}>
                {assigning ? "Assigning…" : "Assign"}
              </button>
            </div>

            <h2 style={{ marginTop: 16 }}>Students on this route</h2>
            {routeStudents && routeStudents.length === 0 && <p className="hint">No students assigned yet.</p>}
            {routeStudents && routeStudents.length > 0 && (
              <table>
                <thead>
                  <tr>
                    <th>Student</th>
                    <th>Class</th>
                    <th>Stop</th>
                    <th>Since</th>
                  </tr>
                </thead>
                <tbody>
                  {routeStudents.map((rs) => (
                    <tr key={rs.id}>
                      <td>
                        {`${rs.firstName} ${rs.lastName ?? ""}`.trim()}
                        <div className="list-row-sub">{rs.admissionNo}</div>
                      </td>
                      <td>{rs.sectionLabel ?? "—"}</td>
                      <td>{stops?.find((s) => s.id === rs.stopId)?.name ?? rs.stopId.slice(0, 8)}</td>
                      <td>{rs.startsOn}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </>
        )}
      </div>

      <div className="panel">
        <h2>Who drives each route</h2>
        <p className="hint">
          A driver sees a route in the driver app only on the days they are assigned to it. Assigning a route from a
          day replaces whoever drove it then; they stop the day before.
        </p>
        <div className="form-row">
          <select value={rosterForm.routeId} onChange={(e) => setRosterForm((f) => ({ ...f, routeId: e.target.value }))}>
            <option value="">Route…</option>
            {routes?.map((r) => (
              <option key={r.id} value={r.id}>
                {r.code} · {r.name}
              </option>
            ))}
          </select>
          <select value={rosterForm.driverId} onChange={(e) => setRosterForm((f) => ({ ...f, driverId: e.target.value }))}>
            <option value="">Driver…</option>
            {drivers
              ?.filter((d) => d.isActive)
              .map((d) => (
                <option key={d.id} value={d.id}>
                  {d.name}
                  {d.staffId ? "" : " (not linked — no app access)"}
                </option>
              ))}
          </select>
          <select value={rosterForm.vehicleId} onChange={(e) => setRosterForm((f) => ({ ...f, vehicleId: e.target.value }))}>
            <option value="">Vehicle…</option>
            {vehicles?.map((v) => (
              <option key={v.id} value={v.id}>
                {v.registrationNo}
              </option>
            ))}
          </select>
          <label className="hint">
            From{" "}
            <input
              type="date"
              value={rosterForm.effectiveFrom}
              onChange={(e) => setRosterForm((f) => ({ ...f, effectiveFrom: e.target.value }))}
            />
          </label>
          <button
            type="button"
            onClick={onAssignRoute}
            disabled={savingRoster || !rosterForm.routeId || !rosterForm.driverId || !rosterForm.vehicleId || !rosterForm.effectiveFrom}
          >
            {savingRoster ? "Assigning…" : "Assign"}
          </button>
        </div>
        {rosters && rosters.length === 0 && <p className="hint">No route has a driver yet.</p>}
        {rosters && rosters.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Route</th>
                <th>Driver</th>
                <th>Vehicle</th>
                <th>From</th>
                <th>To</th>
                <th></th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {rosters.map((a) => {
                const today = todayIso();
                const state =
                  a.effectiveFrom > today ? "upcoming" : a.effectiveTo != null && a.effectiveTo < today ? "ended" : "current";
                const acting = rosterAction?.id === a.id ? rosterAction : null;
                return (
                  <tr key={a.id}>
                    <td>{a.routeCode}</td>
                    <td>{a.driverName}</td>
                    <td>{a.registrationNo}</td>
                    <td>{a.effectiveFrom}</td>
                    <td>{a.effectiveTo ?? "—"}</td>
                    <td>
                      <span className={`badge ${state === "current" ? "badge-active" : ""}`}>{state}</span>
                    </td>
                    <td>
                      {!acting && state === "upcoming" && (
                        <button
                          type="button"
                          className="secondary"
                          onClick={() => setRosterAction({ id: a.id, kind: "delete", lastDay: "" })}
                        >
                          Delete
                        </button>
                      )}
                      {!acting && state === "current" && (
                        <button
                          type="button"
                          className="secondary"
                          onClick={() => setRosterAction({ id: a.id, kind: "end", lastDay: a.effectiveTo ?? today })}
                        >
                          End…
                        </button>
                      )}
                      {acting && (
                        <div className="form-row">
                          {acting.kind === "end" ? (
                            <>
                              <label className="hint">
                                Last day{" "}
                                <input
                                  type="date"
                                  min={a.effectiveFrom}
                                  max={a.effectiveTo ?? undefined}
                                  value={acting.lastDay}
                                  onChange={(e) => setRosterAction({ ...acting, lastDay: e.target.value })}
                                />
                              </label>
                              {acting.lastDay &&
                                (() => {
                                  const after = nextDay(acting.lastDay);
                                  const pickedUp = rosters.some(
                                    (b) =>
                                      b.id !== a.id &&
                                      b.routeId === a.routeId &&
                                      b.effectiveFrom <= after &&
                                      (b.effectiveTo == null || b.effectiveTo >= after)
                                  );
                                  return pickedUp ? null : (
                                    <span style={{ color: "var(--warning)" }}>
                                      {a.routeCode} will have no driver from {after}.
                                    </span>
                                  );
                                })()}
                            </>
                          ) : (
                            <span className="hint">Remove this assignment?</span>
                          )}
                          <button
                            type="button"
                            onClick={onConfirmRosterAction}
                            disabled={busyRosterId === a.id || (acting.kind === "end" && !acting.lastDay)}
                          >
                            {busyRosterId === a.id ? "Saving…" : acting.kind === "end" ? "Confirm end" : "Confirm delete"}
                          </button>
                          <button type="button" className="secondary" onClick={() => setRosterAction(null)}>
                            Cancel
                          </button>
                        </div>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>

      <div className="panel">
        <h2>Geofence check</h2>
        <div className="form-row">
          <select value={gfVehicleId} onChange={(e) => setGfVehicleId(e.target.value)}>
            <option value="">Vehicle…</option>
            {vehicles?.map((v) => (
              <option key={v.id} value={v.id}>
                {v.registrationNo}
              </option>
            ))}
          </select>
          <select value={gfStopId} onChange={(e) => setGfStopId(e.target.value)}>
            <option value="">Stop…</option>
            {stops?.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
          <button type="button" onClick={onCheckGeofence} disabled={gfChecking || !gfVehicleId || !gfStopId}>
            {gfChecking ? "Checking…" : "Check"}
          </button>
        </div>
        {gfError && <p className="hint">{gfError}</p>}
        {gfResult && (
          <p className="hint">
            {gfResult.insideGeofence ? "Inside" : "Outside"} geofence — {Math.round(gfResult.distanceMeters)}m from stop (radius{" "}
            {gfResult.geofenceRadiusM}m), as of {new Date(gfResult.asOf).toLocaleTimeString()}.
          </p>
        )}
      </div>

      <div className="panel">
        <h2>Recent trips</h2>
        {trips && trips.length === 0 && <p className="hint">No trips yet.</p>}
        {trips && trips.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Started</th>
                <th>Route</th>
                <th>Vehicle</th>
                <th>Driver</th>
                <th>Duration</th>
                <th>Check-ins</th>
              </tr>
            </thead>
            <tbody>
              {trips.map((t) => (
                <tr key={t.id}>
                  <td>{new Date(t.startedAt).toLocaleString()}</td>
                  <td>
                    {routeName(t.routeId)} <span className="hint">({t.direction})</span>
                  </td>
                  <td>{vehicleReg(t.vehicleId)}</td>
                  <td>{driverName(t.driverId)}</td>
                  <td>
                    {duration(t.startedAt, t.endedAt)}
                    {!t.endedAt && <span className="badge badge-active" style={{ marginLeft: 6 }}>live</span>}
                  </td>
                  <td>{Object.keys(t.manifest).length}</td>
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
  if (err instanceof ApiError) return err.userMessage;
  return err instanceof Error ? err.message : "Unknown error";
}
