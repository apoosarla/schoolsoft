/**
 * Device Gateway (per design doc §7 Layer 5 + §11). Registers biometric
 * readers, RFID gate readers, and GPS units against a school; resolves
 * inbound hardware events to a student/staff attendance record. The MQTT
 * broker, local biometric-SDK agent, and geofencing engine from §11 are not
 * implemented — this exposes the HTTP-side event-ingestion contract those
 * pieces would call (a local agent bridges the device SDK to this endpoint).
 */
package com.schoolsoft.device;
