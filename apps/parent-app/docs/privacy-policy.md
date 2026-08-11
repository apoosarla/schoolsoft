# Privacy Policy — Schoolsoft Parent (Android)

**Status: DRAFT TEMPLATE — not yet publishable.**

This is a per-deployment template, not a finished policy. Schoolsoft is a multi-tenant
product: each school chain runs its own isolated instance (schema-per-chain, see
`schoolsoft-design.md` §5a), so the *data controller* is the school or chain that
operates the instance — not a single central company. Every `[SQUARE BRACKET]`
placeholder below must be filled in by the operating school before this is published
or linked from a Play Store listing. The unresolved items are collected in
"Before you publish this" at the end.

Everything in the "What we collect" section is derived from the app's actual API
surface (`apps/parent-app/lib/api.ts`, `packages/api-client/src/domain.ts`,
`packages/api-client/src/types.ts`) as of this draft, not from a generic template.
If the app's data use changes, this document must change with it.

- **App:** Schoolsoft Parent (`com.schoolsoft.parent`, per `apps/parent-app/capacitor.config.ts`)
- **Operated by:** `[SCHOOL / CHAIN LEGAL ENTITY NAME]`
- **Last updated:** `[DATE]`

---

## 1. Who this policy is for

This app is for **parents and legal guardians** of students enrolled at
`[SCHOOL NAME]`. You are the account holder. The app is not intended for use by
students themselves, and student accounts cannot sign in to it — sign-in resolves a
*guardian* record, and the app shows an explicit "This account isn't linked to a
guardian record" state for any account that isn't one.

Because it is an app about your child's schooling, it necessarily displays
**personal data about a minor**. See §6.

## 2. Who controls your data

`[SCHOOL / CHAIN LEGAL ENTITY]` is the data controller (under India's DPDP Act 2023,
the Data Fiduciary; under GDPR/UK DPA where applicable, the Controller). The school
already holds this data as part of running the school — the app is a window onto the
school's own records, not a new collection of them.

The Schoolsoft platform vendor, where the school uses a hosted deployment, acts as a
**data processor** on the school's instructions. Each chain's data lives in its own
isolated database schema and is never queried across chains by the application.

## 3. What the app collects, and why

Each item below maps to a real feature. Nothing here is collected for advertising,
profiling, or resale.

### 3.1 To sign you in

| Data | Why |
|---|---|
| Your **email address or mobile number** | You type it on the sign-in screen to request a one-time passcode (OTP). It is sent to the school's API to identify which guardian record you are and to deliver the code. |
| The **school chain identifier** ("chain slug") | Selects which school's instance to authenticate against. Not personal data. |
| The **one-time passcode** you enter | Verifies it was really you. Not retained by the app after sign-in. |
| A **session token and refresh token**, and your account/guardian/school identifiers | Keeps you signed in so you don't re-enter a code on every screen. |

**Stored on your device:** after sign-in, the app saves your session — including the
email or phone number you signed in with — in the app's local web storage
(`schoolsoft_parent_session`). It is removed when you sign out. See §8 for the
security caveats that apply to this.

### 3.2 To show you your child's information

The app requests the students linked to your guardian record, and then loads only
that child's data. Depending on which screen you open, this includes:

| Data | Which screen, and why |
|---|---|
| **Your child's identity** — name, admission number, class/section, roll number, enrolment status | Home screen, and as the heading on every other screen, so you know whose record you are looking at. Where you have more than one child, to let you switch between them. |
| **Date of birth and gender** | Returned by the school's student record API alongside the fields above. The app does not currently display these, but they are transmitted to and held in memory by the app as part of the student record. Disclosed here for accuracy. |
| **Attendance** — daily and per-period status, dates, and any teacher's note | Home screen ("today's attendance") and the Attendance screen's history range. |
| **Leave applications** — the dates you request and the free-text reason you type | You submit these from the Attendance screen on your child's behalf, for the school to approve. |
| **Assessment results** — report cards, assessment names, marks, grade letters, teacher remarks, and absence flags | The Report Cards screen, so you can see how your child is doing. |
| **Fee information** — invoices (number, billing cycle, issue and due dates, subtotal, GST, total, amount paid, status), individual invoice line items and discounts, and payment history (amount, method, gateway, status, capture date) | The Fees screen, so you can review and track your child's school fee status. **The app does not take payments.** It displays payments the school has already recorded; it never asks for and never handles card numbers, UPI IDs, bank details, or any other payment instrument. |
| **Homework** — assignment titles, instructions, due dates, and your child's submissions, marks, and teacher feedback | The Homework screen. |
| **Homework answers you submit** — the free text you type into the answer box | Submitted on your child's behalf to the teacher who set the assignment. |

### 3.3 To let you contact the school

| Data | Why |
|---|---|
| **Messages you write**, and the messages in your conversation threads | The Messages screen: direct conversations between you and school staff about your child. Message bodies are stored by the school and are visible to the staff participants in the thread. |
| **Staff contact details** — the names, and where the school has published them, the email addresses and phone numbers of your child's teachers and school staff | Shown to you so you can start a conversation with the right person. This is the school's own staff directory, displayed to you; the app does not collect it *from* you. |
| **School announcements** | Shown on the Home screen. Authored by the school, not by you. |

### 3.4 Notifications

`[DELETE THIS SUBSECTION IF PUSH NOTIFICATIONS ARE NOT ENABLED IN YOUR DEPLOYMENT.]`

When push notifications are enabled, the app registers a **push notification token**
and the **platform name** ("android") with the school's API, linked to your account,
so the school can send you alerts about your child. You can stop this by turning off
notifications for the app in Android settings; the registration can also be removed
from the server.

**Implementation note (remove before publishing):** as of this draft, the parent app
does **not** register a push token. The server endpoint exists
(`POST /v1/notifications/devices`, `apps/api/src/main/java/com/schoolsoft/notification/api/PushDeviceController.java`)
and the token/platform table exists (`V015__notification_device.sql`), but the app
has no push plugin and makes no such call. Publish this subsection only once the
client side actually ships — and note that adding the Firebase client SDK will mean
the Google Play services on the device obtain a token from Google, which changes the
sharing answer in §5 and in `play-data-safety.md`.

### 3.5 What the app does *not* collect

The app requests no Android permission other than internet access
(`apps/parent-app/android/app/src/main/AndroidManifest.xml`). It does **not** collect
or access:

- your location, in any form (precise or approximate)
- your contacts, calendar, call logs, or SMS messages
- your camera, microphone, photos, videos, or files — homework submissions and
  messages are **text only**; there is no file or photo upload anywhere in the app
- health or fitness data
- advertising identifiers, or any device identifier used for advertising
- app usage analytics, or crash/diagnostic reports — the app bundles no analytics,
  attribution, advertising, or crash-reporting SDK (see `apps/parent-app/package.json`)

## 4. How your data is transmitted

The app talks only to the school's own Schoolsoft API instance, at the address the
school configures for the build. Requests carry your session token as a bearer
credential.

**Deployment requirement:** the API address is a build-time setting
(`NEXT_PUBLIC_SCHOOLSOFT_API_URL`) and defaults to a local development address over
plain HTTP. **Any build distributed to parents must be configured with an `https://`
endpoint.** Do not publish this policy's claim of encrypted transit against a build
that is not. Confirm the configured value before submitting to the Play Store.

## 5. Who your data is shared with

**The app sends your data to no one but your school's own Schoolsoft instance.**
There is no third-party analytics, advertising, attribution, or crash-reporting
service in the app, and your data is never sold or used for advertising.

The one conditional exception is push notifications. If — and only if — your school
has configured Firebase Cloud Messaging credentials on its instance, notification
content is handed to **Google's Firebase Cloud Messaging** for delivery to your
device. Where those credentials are not configured, nothing is sent to Firebase and
no notification data leaves the school's own infrastructure. `[STATE WHICH APPLIES TO
YOUR DEPLOYMENT.]`

Your data may also be disclosed where the school is legally required to disclose it,
for example to an education regulator or in response to a lawful order.

## 6. Children's data

This app exists to show a guardian information about their child, so it necessarily
handles personal data about a minor: their name, admission number, class, attendance,
academic results, homework, and fee record.

- **The guardian is the account holder.** The child does not have an account here and
  cannot sign in to this app.
- The app shows a guardian only the children **linked to their own guardian record**
  by the school.
- The school is the source of that linkage and of all the underlying records. If you
  believe you are seeing a child who is not yours, or are not seeing a child who is,
  contact the school immediately — this is a school records issue, not an app setting.
- `[SCHOOL: state here how you obtain and record parental consent for processing your
  students' data, and how a guardian withdraws it. The platform design anticipates a
  per-data-subject consent registry (`schoolsoft-design.md` §16), but you must
  describe the process you actually operate today.]`

## 7. How long data is kept

Your child's school records are the **school's** records, and the school decides how
long to keep them — subject to its own retention policy and to obligations such as
board and regulatory record-retention rules. This app does not set a separate
retention period; it displays what the school holds.

When a school or chain leaves the platform, its entire isolated database schema is
dropped, which removes that chain's records in one irreversible operation.

`[SCHOOL: state your actual retention periods here — for example, how long you keep
attendance, assessment, fee, and message records after a student leaves.]`

## 8. Security, stated honestly

What we can state about this app as built:

- Requests carry a short-lived session token; the server derives your identity from
  that token rather than trusting identifiers sent by the app. Push device
  registration, for example, always binds to the account in the token, so a session
  can only register or remove its own device.
- Each chain's data is isolated in its own database schema, with row-level policies on
  school as a second line of defence, and the application never queries across chains.
- Transit encryption depends on the deployment being configured with an HTTPS
  endpoint — see §4.

Known limitations that should be resolved rather than papered over:

- **Session storage.** The session, including your sign-in email or phone number and
  your refresh token, is kept in the WebView's local storage rather than in Android's
  encrypted credential storage. On top of that, the Android manifest currently allows
  Android's automatic backup (`android:allowBackup="true"`), which can copy app data
  off the device. `[ENGINEERING: move the session to secure native storage and set
  `allowBackup="false"` (or exclude the session from backup) before a production
  release, or disclose this here.]`
- **Encryption at rest.** The database reserves a column for encrypted sensitive
  fields, but no application code currently writes to it — field-level encryption is
  designed, not implemented. Whatever at-rest protection exists comes from the
  school's own database and backup configuration. `[SCHOOL: state what your hosting
  actually provides; do not claim field-level encryption until it ships.]`

## 9. Your rights, and how to exercise them

You can ask `[SCHOOL NAME]` to give you a copy of, correct, or delete the personal
data it holds about you and your child, and to withdraw consent where processing
relies on it.

**How deletion works today: by contacting the school.** The app has **no in-app
"delete my account" control, and the API has no account-deletion endpoint** — this is
verified against the current codebase, not assumed. Until self-serve deletion ships,
account and data deletion is a **manual process handled by the school**: email
`[privacy@SCHOOL-DOMAIN]` and the school will action the request against its records.

Note that some records cannot simply be erased on request — a school is required to
retain certain student, attendance, and financial records for a defined period. The
school will tell you what it can delete and what it must retain, and why.

`[BEFORE PLAY SUBMISSION: Google Play requires apps with accounts to offer a route to
request account and data deletion, including a publicly reachable deletion-request URL
declared in the Play Console listing. A contact-based process can satisfy this, but
the URL and the process must exist and be honoured. See `play-data-safety.md`.]`

## 10. Changes to this policy

If the app starts collecting or sharing data differently, this policy will be updated
and the "Last updated" date changed before the new behaviour ships.

## 11. Contact

Questions, or to exercise any right in §9:

- **Email:** `[privacy@SCHOOL-DOMAIN]`
- **Postal address:** `[SCHOOL ADDRESS]`
- **Data Protection Officer / Grievance Officer:** `[NAME AND CONTACT — required
  under the DPDP Act for a Significant Data Fiduciary; check whether this applies to
  you.]`

---

## Before you publish this

Nothing here is optional; each item is either a placeholder to fill or a claim that
must be made true first.

**Fill in**
1. Legal entity, school name, and postal address (§2, §11).
2. Contact email and DPO/Grievance Officer (§11).
3. Retention periods (§7).
4. Parental consent process (§6).
5. "Last updated" date.

**Decide, then edit**
6. Whether push notifications ship in this build — keep or delete §3.4 and the
   Firebase paragraph in §5, and match `play-data-safety.md`.

**Verify before claiming**
7. The build points at an `https://` API endpoint (§4).
8. What at-rest protection the hosting actually provides (§8).

**Fix, or disclose**
9. Session in secure storage; `allowBackup` disabled (§8).
10. A working deletion-request route and a public URL for it (§9).

**Then**
11. Host this policy at a public URL and put that URL in the Play Console listing.
12. Have `[SCHOOL]`'s legal or DPO review it — this draft is engineering's account of
    what the app does, not legal advice.
