"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  addTransportStop,
  ApiError,
  assignStudentTransport,
  createDriver,
  createTransportRoute,
  createVehicle,
  DriverDto,
  geofenceStatus,
  GeofenceStatusDto,
  getSession,
  hasScreen,
  listDirectory,
  listDrivers,
  listStudents,
  listTransportRoutes,
  listTransportStops,
  listTripsForSchool,
  listVehicles,
  Session,
  StudentDto,
  studentsOnRoute,
  StudentTransportDto,
  TransportRouteDto,
  TransportStopDto,
  TripDto,
  UserDirectoryEntryDto,
  VehicleDto,
} from "@/lib/api";

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
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

  const [routes, setRoutes] = useState<TransportRouteDto[] | null>(null);
  const [routeForm, setRouteForm] = useState({ code: "", name: "", direction: "pickup" });
  const [creatingRoute, setCreatingRoute] = useState(false);
  const [selectedRouteId, setSelectedRouteId] = useState("");
  const [stops, setStops] = useState<TransportStopDto[] | null>(null);
  const [stopForm, setStopForm] = useState({ name: "", lat: "", lng: "", fee: "" });
  const [addingStop, setAddingStop] = useState(false);
  const [routeStudents, setRouteStudents] = useState<StudentTransportDto[] | null>(null);

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

  function refreshAll(schoolId: string) {
    listVehicles(schoolId).then(setVehicles).catch((err) => setError(describeError(err)));
    listDrivers(schoolId).then(setDrivers).catch((err) => setError(describeError(err)));
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
                    <th>Stop</th>
                    <th>Since</th>
                  </tr>
                </thead>
                <tbody>
                  {routeStudents.map((rs) => (
                    <tr key={rs.id}>
                      <td>{rs.studentId.slice(0, 8)}</td>
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
  if (err instanceof ApiError) return `${err.code ?? "error"}: ${err.message}`;
  return err instanceof Error ? err.message : "Unknown error";
}
