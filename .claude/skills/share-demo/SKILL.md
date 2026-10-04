---
name: share-demo
description: Put the local Schoolsoft stack on a public URL through Cloudflare quick tunnels so somebody outside this machine can try it, and check or repair that link. Use when the user asks to share, expose, publish or demo the app to other people, wants a public or shareable URL, asks whether the shared link is still up, or wants the shared stack restarted or taken down.
---

# Share a demo

One script: `.claude/skills/share-demo/scripts/share.sh`, run from the repo
root. It needs `cloudflared` on PATH and Postgres up on the host. It drives
the `local-env` script for the API and the apps, so they keep its pid files and
logs and appear in its `status`.

```sh
S=.claude/skills/share-demo/scripts/share.sh
$S up                    # school-web + the API, each on a public URL
$S up school parent      # more than one app
$S status                # every hop, local and public; exit 1 if any is not 200
$S restart               # bounce the API and the shared apps; URLs survive
$S restart api           # one target
$S down                  # close the tunnels, stop the shared apps and the API
```

Apps: `school` `parent` `teacher` `driver` `public`.

## The loop

1. `up`. It returns only once each public URL answers 200, and prints them
   with the sign-in code for this share.
2. Hand the user the app URL (it ends in `/login`), the code and the sign-in
   below.
3. Asked "is it up?" — `status`. Its columns are the hops in order: `LOCAL`
   is the process on its port, `TUNNEL` is cloudflared, `PUBLIC` is the URL
   fetched from outside.
4. Something is not 200 — `up` again. It is idempotent: it starts only what is
   down and rebuilds only an app whose build points at a dead API URL. Use
   `restart` when a process is up but misbehaving.
5. `down` when the user is done. Do not leave it up unasked.

| What `status` shows | What it means |
|---|---|
| `LOCAL -` | the process is gone; `up` starts it |
| `LOCAL 200`, `PUBLIC 502` or `530` | the tunnel died or lost its origin; `up` |
| "built against a different API URL" | the API's tunnel was reopened; `up` rebuilds |
| "it is a dev API" | somebody restarted the API with `local-env`; `up` |

Logs: `.run/api.log`, `.run/<app>.log`, and under `.run/share/` each
`<target>.tunnel.log` and `<app>.build.log`.

## What to know before running it

- **The shared API is not the dev API.** `up` starts it with four things
  changed, and restarts one that `local-env` started without them (which signs
  local sessions out once):
  - a private JWT secret, kept in `.run/share/jwt.secret`. The default is in
    git, and a token forged with it would be honoured on a public URL.
  - a random six-digit sign-in code in place of `000000`, kept in
    `.run/share/otp.code` and new after every `down`. Nothing delivers real
    codes yet, so this one is the password to every school account in the dev
    database: it goes to the people trying the app and nowhere public.
  - that code does not open a platform admin. A platform admin signs in with
    the real code from `.run/api.log`.
  - CORS allows the shared apps' origins and no others, so adding an app
    later restarts the API.
- **`platform` is refused.** platform-web is the operator console for every
  chain. Do not work around it by tunnelling 3002 by hand.
- **Say this to the user before the first `up`: whoever holds the code is in**,
  as any account they can name, against the dev database. Demo data only.
  Five wrong codes lock an account for fifteen minutes and twenty lock an
  address, so the code cannot be guessed in useful time — but it can be
  forwarded.
- **Each app is a production build, and it takes the app's port.** The API's
  public URL is inlined at build time, so `up` stops a dev server this stack
  started, runs `next build`, and serves with `next start`. A server on that
  port that the stack did not start is left alone and the app is refused — ask
  the user to stop it. A first `up` takes a minute or two per app.
- **Running `local-env`'s `start <app>` afterwards needs a `stop <app>` first**;
  the dev server then overwrites `.next/` and the next `up` rebuilds.
- **Quick-tunnel URLs are random and do not survive the tunnel.** `restart`
  keeps them. `down`, a reboot, or cloudflared dying does not, and a new API
  URL costs every app a rebuild — `up` does it, but the link the user sent out
  is dead and they need the new one.
- **The machine has to stay awake.** `caffeinate` holds it while the API's
  tunnel lives; closing the lid still sleeps it.

## Signing in

Chain slug `smoketest`, the code `up` printed (also `cat .run/share/otp.code`)
— not `000000`, which the shared API refuses. `priya.menon@oakridge-hyd.test` is the
principal and sees every screen; the other seeded accounts are in the
`dev-browser-login` memory. The dev database is thin — one school — so a
screen may be empty until somebody makes a row.

## Reporting back

Give the app URL with `/login`, the chain slug, an account and the code, and
the one-line warning that the code is the only thing between the URL and the
data. After `status`, name the hop that
is down rather than pasting the table.
