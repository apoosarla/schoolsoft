# Cambridge-School Stack Survey — India

> Companion to `schoolsoft-design.md` §2 (Reference Landscape).
> Question this answers: *What SIS / LMS / ERP do top Cambridge-affiliated schools in India actually run, and what does that tell us about where MCB has to win?*
> Method: public-source only — school websites, parent-portal subdomains, vendor case studies, vendor customer pages. Mappings flagged as **Confirmed** (school or vendor publicly states it) or **Inferred** (subdomain pattern, login page branding, or strong circumstantial signal). No private/insider data.
> Date: May 2026.

---

## 1. Why this matters for MCB

§2 of the design doc compares MCB against *platforms*. This survey compares against the *deployments* — which platforms are actually running inside the schools we want to win. Two things become clear once you map school → stack:

1. **No incumbent owns the full Cambridge-in-India job.** Every top school stitches together 2–4 systems (a curriculum/LMS tool, a fees/admin tool, often a separate admissions tool, and Google Workspace). The seam between "IB/Cambridge curriculum tool" and "Indian fees + transport + GST" is where money leaks and parents complain.
2. **The clusters split by school origin, not by curriculum.** Schools with UK/IB lineage cluster on ManageBac or iSAMS. Schools with Indian-chain lineage (CBSE + bolted-on Cambridge) cluster on MyClassboard or similar Indian ERPs. Captive solutions (Pathways' Wizemen) exist where a chain decided no vendor fit.

MCB's wedge — first-class Cambridge curriculum **plus** real Indian compliance + ops in one stack — directly attacks both seams.

---

## 2. The schools and their stacks

| # | School | City / Chain | Curricula | Curriculum / LMS | SIS / Ops / Fees | Admissions | Evidence |
|---|---|---|---|---|---|---|---|
| 1 | **Oakridge International** | Hyd / Blr / Vizag / Mohali (Nord Anglia) | IB PYP + IGCSE + IB DP + CBSE | **Schoolpost.net** (parent portal), OASIS (apps.oakridge.in) — Nord Anglia globally standardising on **iSAMS** | In-house / Schoolpost | In-house / NA admissions | Confirmed: schoolpost.net login; Nord Anglia New York runs `parents.isams.cloud` — strong signal NA India is on or migrating to iSAMS |
| 2 | **The International School Bangalore (TISB)** | Bangalore | IGCSE + IBDP | **iSAMS** + iParent app | iSAMS | iSAMS | Confirmed: parent handbook references "iSAMS Parent Portal" and "iParent app" |
| 3 | **Pathways World School** (Aravali / Gurgaon / Noida) | Delhi NCR | IB PYP/MYP/DP + IGCSE | **Wizemen** (captive — Wizemen Technologies LLP is registered at Gate 1, Pathways School Gurgaon) | Wizemen | Wizemen | Confirmed: pwsscholarship.wizemen.net, student-resources page |
| 4 | **Indus International School** | Blr / Hyd / Pune | IGCSE + IBDP + CBSE | Google Classroom (informal) | **MyClassboard** (`indus.myclassboard.com`) | MyClassboard | Confirmed: parent portal subdomain |
| 5 | **École Mondiale World School** | Mumbai (Juhu) | IB PYP/MYP/DP (IGCSE optional) | **ManageBac+** (curriculum + comms + reports) | Separate fees tool | OpenApply (Faria) | Confirmed: Faria/ManageBac case study published by vendor |
| 6 | **Stonehill International School** | Bangalore | IB PYP/MYP/DP/CP | **ManageBac** (`stonehill.managebac.com`) | Separate | **OpenApply** (`stonehill.openapply.com`) | Confirmed: both subdomains live |
| 7 | **Jamnabai Narsee International / JNS** | Mumbai | IGCSE + IBDP + ICSE | **ManageBac** (`jamnabai.managebac.com`) | **Edusprint** (`jns.edusprint.in`) for fees / admin | Edusprint | Confirmed: both subdomains live |
| 8 | **Aditya Birla World Academy (ABWA)** | Mumbai | IGCSE + IBDP + ICSE | Likely ManageBac (IB) — not publicly confirmed | Unknown | **Skolaro** (admissions portal) | Confirmed for Skolaro on admissions; SIS unknown publicly |
| 9 | **Lancers International School** | Gurgaon | IB PYP/MYP/DP + IGCSE | **ManageBac** (listed on Digital Platforms page) | Separate | Separate | Confirmed: school's own "Digital Platforms" page names ManageBac |
| 10 | **Canadian International School (CIS) Bangalore** | Bangalore | IB PYP/MYP/DP + IGCSE | **ManageBac** + Google Classroom | Separate | Separate | Confirmed via school parent handbook |
| 11 | **Shiv Nadar School** | Noida / Gurgaon / Faridabad | IGCSE + CBSE | **ManageBac** | Separate | Separate | Inferred: school + vendor co-references; common pattern |
| 12 | **Dhirubhai Ambani International School (DAIS)** | Mumbai (BKC) | ICSE + IGCSE + IBDP | **In-house / custom** parent portal on dais.edu.in | In-house | `onlineadmission.dais.edu.in` (custom) | Inferred: no third-party SIS subdomain publicly visible; admissions on dais.edu.in subdomain |
| 13 | **Rustomjee Cambridge International School** | Mumbai (Dahisar / Thane / Virar) | Cambridge Primary / Lower-Sec / IGCSE / A-Levels | **Google Workspace** + custom `ris.rustomjee.com` | Custom + online fee portal | Custom | Inferred: Google sign-in on ris.rustomjee.com, custom fee portal |
| 14 | **Inventure Academy** | Bangalore | IGCSE + A/AS | Not publicly disclosed | Not publicly disclosed | Custom | Could not confirm publicly |
| 15 | **Mallya Aditi International** | Bangalore | IGCSE + ICSE | Not publicly disclosed | Not publicly disclosed | Custom | Could not confirm publicly |
| 16 | **Greenwood High International** | Bangalore | IB + IGCSE + ICSE/ISC | Not publicly disclosed | Not publicly disclosed | Custom | Could not confirm publicly |

Where a cell says "not publicly disclosed", the school keeps its stack behind login walls without vendor branding — usually a strong signal of in-house tooling or a low-profile Indian ERP that doesn't co-brand.

---

## 3. Per-platform analysis

### 3.1 ManageBac (Faria Education Group)

**Where it shows up:** École Mondiale, Stonehill, Jamnabai Narsee, Lancers, CIS Bangalore, Shiv Nadar, likely ABWA. By far the most common single platform in this cohort.

**Why these schools picked it.** ManageBac is the de-facto IB platform — curriculum planner, gradebook, MYP/DP-aligned reports, IB CAS/EE tracking. Its **British Curriculum mode** (launched as ManageBac+) added native Cambridge schemes-of-work mapping and IGCSE/A-Level grade scales. For an IB-first school that also runs IGCSE in the senior years, ManageBac is the one tool teachers don't argue about.

**What it does badly enough that schools bolt on other things.**
- **No real fees.** Schools pair it with Edusprint (JNS), in-house portals (DAIS-style), or a separate Indian ERP. ManageBac has Faria-Pay but it's not GST-IRN-aware and has no Tally/Zoho sync.
- **No transport / library / inventory / HR.** Anything operational is somebody else's system.
- **Admissions is a separate Faria product (OpenApply)** that schools have to license and integrate.
- **Closed ecosystem.** OneRoster/LTI 1.3 support exists but is partial; integrations with Google Classroom and MS Teams Edu are usable, deeper interop is bespoke.
- **WhatsApp parent comms** is not native — schools layer on TeachMint, Sangraha, or self-built notifiers.

**Implication for MCB.** ManageBac is the curriculum tool MCB has to match feature-for-feature in Cambridge mode (schemes of work, syllabus codes, A*–E and 9–1 scales, paper-component gradebook). MCB wins on the four things ManageBac concedes: GST-aware fees, transport/library/HR, WhatsApp-first parent comms, and open interop (LTI 1.3 + OneRoster 1.2 done properly so the school doesn't need OpenApply *and* ManageBac *and* an Indian ERP).

### 3.2 iSAMS

**Where it shows up:** TISB; Nord Anglia (Oakridge globally migrating).

**Why these schools picked it.** iSAMS is the mature UK independent-school MIS. It has 1,700+ schools globally, deep SIS data model, gradebook, attendance, boarding modules. For day-and-boarding schools with strong UK lineage, it's the safest enterprise choice and there's a thriving consultant ecosystem.

**What it does badly enough that schools work around it.**
- **UK-centric.** No CBSE templates, no UDISE+, no CIE Direct integration as a first-class module (Cambridge codes exist as data, the integration doesn't).
- **No GST IRN, no Tally, no Indian fee plans.** Schools either run iSAMS Finance disconnected from their statutory books or shadow-ledger it in Tally manually.
- **Pricey for chains.** Per-pupil licensing scales poorly past 5k pupils.
- **Mobile UX is dated** versus Toddle/ManageBac.

**Implication for MCB.** iSAMS is the benchmark for SIS depth and audit trail. MCB's data model and audit log should aim at parity (think: enrolment history, every-change-tracked attendance, boarding house assignments). MCB wins on Indian compliance, GST, WhatsApp, and a modern mobile-first UX — none of which iSAMS will retrofit credibly.

### 3.3 MyClassboard

**Where it shows up:** Indus International, and across many India-chain Cambridge schools (Indus serves as the canonical example here; the platform has 1,200+ schools but most are CBSE-first).

**Why these schools picked it.** Indian SaaS, India support hours, fee structures that understand Indian quirks (term-wise + transport-band + sibling discount + GST), Tally / Zoho Books export, Telugu/Hindi/regional language support, transport with biometric + RFID + GPS. For chains, multi-school admin in one console.

**What it does badly enough that Cambridge schools complain.**
- **No first-class Cambridge curriculum.** It can hold marks and produce reports but doesn't know what an IGCSE syllabus code is, can't push to CIE Direct, doesn't understand component-based assessment.
- **LMS is thin.** Lesson planning, scheme-of-work, content authoring are weak — schools that take teaching seriously layer on Google Classroom or Khan Academy alongside.
- **UX is dated.** Teacher and parent apps look like 2017.
- **Limited interop.** OneRoster / LTI 1.3 are not first-class.

**Implication for MCB.** MyClassboard's strength is the *India-ops* surface area. MCB should not lose to it on fees, GST, transport, or library — that's table stakes for an Indian sale. MCB wins on Cambridge depth, LMS, modern UX, and open interop.

### 3.4 Toddle

**Where it shows up:** Bangalore-based, 2,000+ schools globally, strong India footprint at IB/Cambridge schools — though none of the 16 schools above publicly confirm Toddle as their primary stack. Toddle tends to be the *curriculum-planner-and-assessment* tool alongside something else (or in place of ManageBac at newer schools).

**Why schools pick it.** Best-in-class UX for unit planning, conceptual learning, assessment design. IB-and-Cambridge-native. Mobile-first for teachers and parents.

**What it does badly.** Same gap as ManageBac, sharper: no fees, no transport, no HR, no admissions. Toddle has consistently positioned as "we do teaching and learning brilliantly, the school runs its ops elsewhere."

**Implication for MCB.** Toddle is the UX benchmark for the curriculum surface. The design doc already names Toddle's curriculum + UX as something to borrow (§2). The wedge is identical: Toddle won't grow downward into ops because it's a deliberate strategic choice; MCB can grow upward into curriculum because it must.

### 3.5 Wizemen (Pathways' captive platform)

**Where it shows up:** Pathways group only.

**Why Pathways built it.** When the chain launched, neither ManageBac nor an Indian ERP did the IB-continuum-plus-India-ops job. So Pathways built their own and incorporated Wizemen Technologies LLP at their Gurgaon campus. It's IB-native, has assessment / e-assessment modules, syncs with most ERPs.

**Implication for MCB.** Wizemen's existence is itself the proof-of-thesis for MCB. A top Indian school chain hit the same gap MCB targets and decided to build rather than buy — that gap has not closed in the years since. The risk this surfaces: a chain that decides MCB is mission-critical may want source escrow, on-prem options, or co-development rights. Worth pricing into enterprise contracts.

### 3.6 In-house / Google Workspace patchworks

**Where it shows up:** DAIS (custom portal on dais.edu.in), Rustomjee Cambridge (Google Workspace + ris.rustomjee.com), and almost certainly several of the "not publicly disclosed" schools in the table.

**Why schools do this.** Either they're large/wealthy enough to staff an IT team (DAIS) or they're early on the digital curve and Google Classroom + spreadsheets + a custom parent portal is "good enough." Both groups are unhappy customers waiting to be displaced — the wealthy ones because their IT cost is creeping, the others because audit and compliance keep biting them.

**Implication for MCB.** These are the highest-LTV target accounts. The custom-portal schools have already proven willingness-to-pay (they spent on building it). The Google-Workspace schools are the easiest demos because the gaps are obvious to anyone walking through the building.

### 3.7 Other tools that show up at the seams

- **OpenApply (Faria)** — admissions for ManageBac shops. Stonehill confirmed.
- **Skolaro** — admissions for ABWA. Indian.
- **Edusprint** — fees/admin alongside ManageBac at JNS.
- **Google Classroom / MS Teams Edu** — universal as the content layer. MCB's LTI 1.3 + OneRoster 1.2 bet means we plug in alongside these without trying to replace them.
- **TeachMint / Sangraha** — sometimes layered for WhatsApp comms when the SIS doesn't do it.

The pattern: every school in the cohort runs **at least two systems**. The most-stitched-together cases run four. That's MCB's TAM in a sentence.

---

## 4. What this changes in the MCB design

Nothing structural — the §2 "Net design stance" still holds. But it sharpens the *order-of-priority* for the MVP and the GTM message:

1. **Cambridge curriculum + CIE Direct integration is non-negotiable for the demo.** Every IB/Cambridge school in this cohort is on ManageBac or iSAMS or Wizemen. If MCB walks into a TISB or École Mondiale procurement without component-based assessment, syllabus codes, A*–E + 9–1 scales, and CIE Direct candidate registration working in the demo, the conversation ends at slide 3. (Already locked as MVP — re-confirms the priority.)
2. **GST e-invoice + Tally / Zoho sync is the credibility test for the CFO.** The ManageBac shops are running fees in something else, often badly. The MyClassboard shops have working fees but weak Cambridge. MCB needs both to land at one Cambridge school that today runs two systems.
3. **WhatsApp Business comms is the parent-engagement wedge.** Not one platform in this survey ships native WhatsApp Business at first-class quality. This is a feature parents notice within a week.
4. **OneRoster 1.2 + LTI 1.3 is the "we don't fight Google Classroom" message.** Schools will keep Classroom; MCB has to plug in cleanly so the school replaces ManageBac without losing Classroom integration.
5. **Admissions as a real module (not a separate product).** Stonehill / École Mondiale / many others license OpenApply *separately* from ManageBac. MCB shipping admissions inside the platform is a real bundle pitch — eliminates a vendor, eliminates an integration.

---

## 5. Confidence and caveats

- Public-source-only research. Mappings are observed (subdomain, login page, vendor case study) or inferred (subdomain pattern, school's listed tooling page). Treat **Inferred** rows as hypotheses to confirm before any sales conversation.
- A school can run multiple LMS tools per division (Primary on Toddle, Senior on ManageBac, etc.). The table captures the *primary* tool where evidence pointed to one.
- Vendor relationships change. iSAMS bought competitors recently; Faria's product line is consolidating under "ManageBac+"; Toddle is expanding into ops modules cautiously. Re-validate before each annual planning cycle.
- Three Bangalore schools (Inventure, Mallya Aditi, Greenwood High) couldn't be mapped publicly. Worth a direct visit / channel-partner conversation — these are exactly the profile MCB targets.

---

## 6. Sources

Captured during the May 2026 research pass:

- Indus International parent portal: https://indus.myclassboard.com/
- MyClassboard company: https://www.myclassboard.com/
- TISB parent portal references: https://www.tisb.org/secure/parent-portal
- iSAMS company: https://www.isams.com/
- Pathways World School student resources (Wizemen): https://www.pathways.in/aravali/students
- Wizemen: https://www.wizemen.net/
- École Mondiale ManageBac case study: https://www.managebac.com/case-studies/ecole-mondiale-world-school
- Stonehill ManageBac login: https://stonehill.managebac.com/login
- Stonehill OpenApply: https://stonehill.openapply.com/parents/sign_in
- Jamnabai Narsee ManageBac: https://jamnabai.managebac.com/login
- Jamnabai Narsee Edusprint: https://jns.edusprint.in/
- Aditya Birla World Academy: https://www.adityabirlaworldacademy.com/
- Lancers Digital Platforms: https://www.lis.ac.in/about-us/digital-platforms/
- Canadian International School Bangalore: http://www.cisb.org.in/
- Nord Anglia / Oakridge: https://www.oakridge.in/, https://www.nordangliaeducation.com/our-schools/asia/india
- DAIS: https://www.dais.edu.in/
- Rustomjee Cambridge: https://rcis.rustomjee.com/, https://ris.rustomjee.com/
- Toddle Cambridge: https://www.toddleapp.com/cambridge-curriculum/
- ManageBac: https://www.managebac.com/
