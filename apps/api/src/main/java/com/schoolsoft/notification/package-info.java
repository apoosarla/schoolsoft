/**
 * Notification module — single ingress for all outbound comms. Per design §10
 * (WhatsApp first): channel routing, template resolution, 24-hr session
 * window enforcement, opt-in checks, delivery receipts.
 *
 * In MVP the channel adapters are stubs that record the dispatch + log it.
 * Production replaces them with FCM, SES, and a WhatsApp BSP client.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Notification")
package com.schoolsoft.notification;
