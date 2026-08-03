/**
 * Transport (per design doc §7 Layer 4.6-4.7). Routes/stops/vehicles/drivers,
 * student-stop assignment, and the driver-app trip lifecycle (start/end
 * triggers live tracking). GPS ingestion here is a plain insert of
 * {@code gps_ping} rows — the MQTT/EMQX ingestion pipeline and geofencing
 * engine from §11 are not implemented; this exposes the HTTP-side data model
 * only (a device gateway would call the same ping-insert path).
 */
package com.schoolsoft.transport;
