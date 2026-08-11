# Google Play "Data safety" form — transcription sheet

**Schoolsoft Parent** (`com.schoolsoft.parent`)

This document is written to be transcribed **section by section, in order** into Play
Console → App content → Data safety. Every answer is grounded in the app's actual code
as of this draft; the "Grounded in" column cites the file that establishes it. Where a
question has no code-derived answer, it is marked **NEEDS CONFIRMATION** rather than
guessed — do not submit those without checking.

Read alongside `privacy-policy.md`, which must stay consistent with these answers.
Google cross-checks the two, and an inconsistency is a common cause of rejection.

---

## The one distinction that determines most answers

Play defines **"collected"** as *transmitting user data from the app off the user's
device*. It does not mean "the app can see it."

This app is mostly a **reader** of records the school already holds. Your child's
name, attendance, marks, and fee invoices travel **server → device** so the app can
display them. The school already had that data; the app is not collecting it.

Only these things travel **device → server**, and only these drive a "Yes":

1. the email or phone you sign in with, and the identifiers used to scope requests
2. the reason text on a leave application
3. the answer text on a homework submission
4. the body of a message you send to a teacher
5. *(not yet shipped)* a push notification token

Everything else in the app is display-only. Each such case is marked **Display-only —
not "collected"** below, with the reasoning, so a reviewer or a future maintainer can
audit the judgment instead of taking it on faith.

> Read the whole app-lifecycle picture too: the school's *own* privacy notice covers
> its holding of student records. This form covers only what **the app** transmits.

---

## Section 1 — Data collection and security (top-level questions)

| Play question | Answer | Grounded in |
|---|---|---|
| Does your app collect or share any of the required user data types? | **Yes** | Sign-in identifier, message bodies, leave reasons, and homework answers are all POSTed off-device. |
| Is all of the user data collected by your app encrypted in transit? | **Yes — but VERIFY FIRST** | The transport sets no scheme of its own; it uses whatever base URL the build is given (`packages/api-client/src/http.ts`). That URL is `NEXT_PUBLIC_SCHOOLSOFT_API_URL`, which **defaults to `http://localhost:8080`** (`apps/parent-app/lib/api.ts:25`). Answer "Yes" **only** after confirming the release build is configured with an `https://` endpoint. If it is not, fix the build — do not answer "No". |
| Do you provide a way for users to request that their data be deleted? | **Yes — via a contact-based process that you must first stand up** | See Section 4. There is no deletion endpoint in the API. |

**At-rest encryption is not asked by this form** — but if a reviewer or your own
security questionnaire raises it: it **cannot be confirmed from this repository**. The
database reserves an `encrypted_payload` column for sensitive fields
(`db/migration/chain/V002__people.sql:17-19`) but **no application code writes to it**
— field-level encryption is designed, not implemented. Any at-rest protection comes
from the school's own database and backup configuration, which is outside this repo.
Do not assert it in the listing or the policy.

---

## Section 2 — Data types

Work down Play's category list in the order below; it matches the Console. For each
"Collected: Yes" row, Play then asks four follow-ups, answered in the detail tables.

### 2.1 Location

| Data type | Collected | Notes |
|---|---|---|
| Approximate location | **No** | |
| Precise location | **No** | |

The app declares **no location permission** — the manifest requests only
`android.permission.INTERNET`
(`apps/parent-app/android/app/src/main/AndroidManifest.xml`). Nothing in the push-token
flow is location-adjacent either: registration sends a token string and the literal
platform name, constrained to `android|ios|web`
(`apps/api/.../notification/api/PushDeviceController.java`). Unambiguously not collected.

### 2.2 Personal info

| Data type | Collected | Shared | Ephemeral | Required/Optional | Purposes |
|---|---|---|---|---|---|
| **Email address** | **Yes** | No | No | Required | App functionality; Account management |
| **Phone number** | **Yes** | No | No | Required | App functionality; Account management |
| **User IDs** | **Yes** | No | No | Required | App functionality; Account management |
| Name | **No** — display-only | — | — | — | — |
| Address | **No** | — | — | — | — |
| Race and ethnicity | **No** | — | — | — | — |
| Political or religious beliefs | **No** | — | — | — | — |
| Sexual orientation | **No** | — | — | — | — |
| Other info | **No** — display-only | — | — | — | — |

**Email address / Phone number.** One field on the sign-in screen accepts either
("Email or phone", `apps/parent-app/app/login/page.tsx`), and it is POSTed to
`/v1/auth/otp/start` and `/v1/auth/otp/verify` (`packages/api-client/src/auth.ts`).
Because a parent may use either, **declare both**. Marked *Required* — you cannot use
the app without signing in. Also persisted on the device inside the saved session
(`apps/parent-app/lib/api.ts:26-37`), which is why *Ephemeral* is **No**.

**User IDs.** The account, guardian, and student identifiers returned at sign-in are
sent back off-device on subsequent requests — as path or query parameters in, for
example, `/v1/people/guardians/{guardianId}/students`, `/v1/comms/threads?userAccountId=…`,
and the `studentId` in a homework submission body (`packages/api-client/src/domain.ts`).
They originate server-side, but they *are* transmitted from the device, so this is a Yes.

**Name — display-only.** Student names arrive in `StudentDto`
(`packages/api-client/src/types.ts:3-16`) and are rendered on Home and as each screen's
heading. The app never sends a name to the server. The guardian's own name is never
collected at all — sign-in is by identifier only.

**Other info — display-only.** `StudentDto` also carries `dob` and `gender`. The app
does not display them, and never transmits them, but they do reach the device in the
student payload. Not "collected" under Play's definition. Disclosed in
`privacy-policy.md` §3.2 for accuracy — keep both documents saying the same thing.

### 2.3 Financial info

| Data type | Collected | Notes |
|---|---|---|
| User payment info | **No** | |
| Purchase history | **No** — display-only | |
| Credit score | **No** | |
| Other financial info | **No** — display-only | |

**This app takes no payments.** It has no billing library, no payment gateway SDK, and
no endpoint that initiates one — the fees module exposes exactly three **read**
operations: list invoices, list invoice lines, list payments
(`packages/api-client/src/domain.ts:89-101`). It never asks for a card number, UPI ID,
or bank detail.

The Fees screen *displays* invoices and the school's record of past payments —
amounts, GST, dues, and the gateway/method of payments the school already captured
(`FeeInvoiceDto`, `PaymentDto`, `packages/api-client/src/types.ts:56-91`). That data
travels server → device only, so it is not "collected," and it is not "Purchase
history" in Play's sense (there are no in-app purchases). **Answer No** — but be ready
to explain it if asked, since a fees screen invites the question.

### 2.4 Health and fitness

| Data type | Collected | Notes |
|---|---|---|
| Health info | **No** | The student record schema has a `blood_group` field, but the app never requests, displays, or transmits it — it is not in `StudentDto`. |
| Fitness info | **No** | |

### 2.5 Messages

| Data type | Collected | Shared | Ephemeral | Required/Optional | Purposes |
|---|---|---|---|---|---|
| **Other in-app messages** | **Yes** | No | No | Optional | App functionality |
| Emails | **No** | — | — | — | — |
| SMS or MMS | **No** | — | — | — | — |

The Messages screen lets a parent compose free text and POST it to a thread with school
staff — `sendMessage(threadId, senderUserId, body)`
(`packages/api-client/src/domain.ts:67-72`; composer at
`apps/parent-app/app/messages/page.tsx`). Message bodies are stored server-side and
visible to the staff participants in that thread. Marked *Optional*: the rest of the
app works without ever opening Messages.

### 2.6 Photos and videos

| Data type | Collected | Notes |
|---|---|---|
| Photos | **No** | |
| Videos | **No** | |

There is **no file, photo, or video upload anywhere in the app**. Both user-submission
paths take a single text string: `submitAssignment(assignmentId, studentId, body)` and
`sendMessage(threadId, senderUserId, body)` (`packages/api-client/src/domain.ts`). No
camera or media permission is declared.

### 2.7 Audio files

| Data type | Collected | Notes |
|---|---|---|
| Voice or sound recordings | **No** | No microphone permission. |
| Music files | **No** | |
| Other audio files | **No** | |

### 2.8 Files and docs

| Data type | Collected | Notes |
|---|---|---|
| Files and docs | **No** | No upload path; no storage permission. |

### 2.9 Calendar

| Data type | Collected | Notes |
|---|---|---|
| Calendar events | **No** | The app reads school dates (due dates, terms) from the school's API; it never touches the device calendar. |

### 2.10 Contacts

| Data type | Collected | Notes |
|---|---|---|
| Contacts | **No** | |

The Messages screen shows a **school staff directory** — teacher names and, where the
school publishes them, emails and phone numbers (`UserDirectoryEntryDto`,
`packages/api-client/src/types.ts:175-182`). That is the school's own directory sent to
the device for display; it is neither the device's contact list nor data collected from
the user. Not collected.

### 2.11 App activity

| Data type | Collected | Shared | Ephemeral | Required/Optional | Purposes |
|---|---|---|---|---|---|
| **Other user-generated content** | **Yes** | No | No | Optional | App functionality |
| App interactions | **No** | — | — | — | — |
| In-app search history | **No** | — | — | — | — |
| Installed apps | **No** | — | — | — | — |
| Other actions | **No** | — | — | — | — |

**Other user-generated content** covers the two remaining things a parent types and
sends:

- the free-text **reason on a leave application**, with the dates
  (`apps/parent-app/lib/api.ts:123-134`; form at `apps/parent-app/app/attendance/page.tsx`)
- the free-text **homework answer** submitted on the child's behalf
  (`apps/parent-app/app/homework/page.tsx` → `submitAssignment`)

Marked *Optional* — both are features a parent may never use.

*App interactions* is **No** because the app bundles **no analytics SDK at all**: its
only runtime dependencies are Capacitor, Next.js, React, and the in-house API client
(`apps/parent-app/package.json`). Nothing tracks screen views or taps.

### 2.12 Web browsing history

| Data type | Collected | Notes |
|---|---|---|
| Web browsing history | **No** | The app is a Capacitor WebView rendering its own bundled pages; it is not a browser and records no browsing. |

### 2.13 App info and performance

| Data type | Collected | Notes |
|---|---|---|
| Crash logs | **No** | |
| Diagnostics | **No** | |
| Other app performance data | **No** | |

No crash-reporting or performance SDK is bundled (no Crashlytics, no Sentry — see
`apps/parent-app/package.json`). The backend design mentions Sentry for **server-side**
error tracking, which is not app data collection and is not declared here. Crash data
that **Google Play itself** gathers from Play-distributed apps is collected by Google,
not by you, and is out of scope for this form.

### 2.14 Device or other IDs

| Data type | Collected | Notes |
|---|---|---|
| Device or other IDs | **No today — see below. MUST BE REVISITED BEFORE PUSH SHIPS.** | |

**Today: No.** The parent app does not register a push token. It has no push plugin
(`apps/parent-app/package.json` lists no `@capacitor/push-notifications`) and no client
code calls the registration endpoint. The *server* side is built — the endpoint
`POST /v1/notifications/devices` and the `notification_device` table storing token and
platform (`apps/api/.../PushDeviceController.java`,
`db/migration/chain/V015__notification_device.sql`) — but a server capability the app
never invokes is not app data collection.

**When push ships, this row becomes Yes,** and three answers change together:

| Follow-up | Answer once push ships |
|---|---|
| Collected | **Yes** — the FCM registration token and platform string, bound to the account from the bearer token |
| Shared | **No** — Firebase Cloud Messaging is Google acting as a *service provider* delivering on the school's behalf, which Play's definition of "sharing" excludes. Collection: yes; sharing: no. |
| Ephemeral | **No** — the token is persisted in `notification_device` |
| Required/Optional | **Optional** — a parent can decline the notification permission and keep using the app |
| Purposes | App functionality (and Developer communications, if the school sends broadcast-style notices) |

Also revisit at that point: adding the Firebase client SDK means Google Play services
on the device obtain the token from Google, and Android 13+ requires the
`POST_NOTIFICATIONS` runtime permission in the manifest. Update `privacy-policy.md`
§3.4 and §5 in the same change.

---

## Section 3 — Privacy policy URL

Play requires a publicly reachable privacy policy URL in the listing.

`privacy-policy.md` in this directory is a **draft template with unfilled placeholders
and unresolved engineering items** — it is not publishable as-is. Complete its "Before
you publish this" checklist, host the result at a public URL, and enter that URL in
Play Console. **NEEDS CONFIRMATION: the hosting URL.**

---

## Section 4 — Account deletion

Play requires apps that support account creation to offer users a way to request
account and data deletion, including a **publicly reachable deletion-request URL**
declared in Play Console — reachable without signing in.

**Verified against the codebase:** there is **no account-deletion endpoint**. A sweep of
`@DeleteMapping` across the API turns up only three, none of which delete an account —
push device, IAM role, and timetable slot. The design document anticipates a
data-subject-request workflow covering erasure (`schoolsoft-design.md` §16), but it is
not built. Chain-level offboarding drops the whole schema, which is not a
per-parent mechanism.

**So declare the contact-based process, and stand it up before submitting:**

| Play question | Answer |
|---|---|
| Can users request account deletion? | **Yes** |
| Does the app offer in-app account deletion? | **No** |
| Deletion request URL | **NEEDS CONFIRMATION — a page the school must publish.** It must be publicly reachable, explain that deletion is requested by contacting the school, give the address, and state which records the school is legally required to retain. |

This is the single most likely cause of a submission being blocked. Treat the URL as a
prerequisite, not a formality, and make sure someone at the school actually monitors
and actions the requests.

---

## At a glance — every "Yes"

Four data types, one purpose, nothing shared:

| Data type | What it actually is | Required? |
|---|---|---|
| Personal info → Email address | Sign-in identifier | Required |
| Personal info → Phone number | Sign-in identifier | Required |
| Personal info → User IDs | Account/guardian/student IDs sent on requests | Required |
| Messages → Other in-app messages | Messages a parent writes to school staff | Optional |
| App activity → Other user-generated content | Leave reasons; homework answers | Optional |

**Shared with third parties: nothing.** No analytics, advertising, attribution, or
crash-reporting SDK is present in the app. The only third party that ever enters the
picture is Firebase Cloud Messaging, and only once push ships and only where the school
has configured credentials — and that is a service-provider relationship, which Play
does not count as sharing.

---

## Before submitting — checklist

**Verify (do not answer from this document alone)**
1. The release build's `NEXT_PUBLIC_SCHOOLSOFT_API_URL` is an `https://` endpoint —
   this is what makes the "encrypted in transit: Yes" answer true (Section 1).
2. Push notifications are still absent from the shipping build. If they are not,
   redo Section 2.14 **and** `privacy-policy.md` §3.4/§5.

**Publish first (both are hard prerequisites)**
3. The completed privacy policy, at a public URL (Section 3).
4. The account-deletion request page, at a public URL (Section 4).

**Keep in sync**
5. `privacy-policy.md` and this form must agree. Google compares them; a
   contradiction is a common rejection reason. If the app's data use changes,
   change both in the same commit.
