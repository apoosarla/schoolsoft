---
name: local-env
description: Start, stop, restart or check the Schoolsoft stack on this machine — the Spring Boot API and any of the six Next.js apps (school-web, platform-web, public-site, parent-app, teacher-app, driver-app), plus the compose infra. Use when the user asks to run, start, boot, bring up, stop, kill, restart or check the status of the app, the API, a frontend, or the local environment.
---

# Local environment

One script does the work: `.claude/skills/local-env/scripts/local.sh`, run from
the repo root. It starts each piece detached, in its own process group, with a
pid file and a log under `.run/` (gitignored), and waits until the port is
listening before it reports success.

```sh
S=.claude/skills/local-env/scripts/local.sh
$S status                      # what is up, what is external, what is down
$S start                       # api + school-web (the usual pair)
$S start api web               # api + school, platform, public
$S start all                   # api + all six apps
$S start parent teacher        # just those apps, against the running API
$S stop                        # everything this script started
$S stop api                    # one target
$S logs api 100                # tail a target's log
```

Targets: `api` `school` `platform` `public` `parent` `teacher` `driver`
`infra`, and the groups `web` (school, platform, public) and `all` (api + six
apps).

| Target | Port | |
|---|---|---|
| api | 8080, or 8090 if 8080 is taken | `SCHOOLSOFT_API_PORT` overrides |
| school | 3001 | office + chain HQ |
| platform | 3002 | Schoolsoft operators |
| teacher | 3003 | |
| parent | 3004 | |
| driver | 3005 | |
| public | 3006 | |

## What to know before running it

- **Port 8080 is usually an ssh tunnel on this machine**, so the API lands on
  8090. The script records the port it chose in `.run/api.port` and starts
  every app with `NEXT_PUBLIC_SCHOOLSOFT_API_URL` pointing at it — so start
  the API first, or in the same command. An app started while the API is down
  still points at the port the API will take.
- **Postgres is not containerised.** It runs on the host at `localhost:5432`.
  `start api` refuses with a clear message if it is not answering; the script
  does not start Postgres.
- **The API does not need `infra`** (redis, opensearch, emqx, minio) to boot.
  Start it only when the work touches search, messaging or files. Port 9000 is
  also an ssh tunnel here, so `start infra` skips MinIO and says so.
- **The script never kills what it did not start.** A port already held by
  another process is reported as `external` and left alone on both start and
  stop. `stop <target> --force` kills the holder only if it is `node`,
  `next-server` or `java` — never `ssh`. Ask before using `--force`: an
  external dev server is often one the user started themselves in a terminal.
- **Never `next build` an app whose dev server is running** — it overwrites
  `.next/` under the live server. Type-check with `npx tsc --noEmit -p .`
  instead.
- A start that fails prints the last 25 lines of that target's log. For the
  API, a failure right after editing a chain migration is usually a stale
  schema, not a bug (see CLAUDE.md).

## Signing in once it is up

Dev chain slug `smoketest`, OTP `000000`. Seeded accounts are in the
`dev-browser-login` memory: `priya.menon@oakridge-hyd.test` (principal),
`hq@smoketest.test` (chain admin, lands on `/chain`), `admin@schoolsoft.dev`
on platform-web.

## Reporting back

After start or stop, run `status` and give the user the URLs that are up and
anything `external` or `FAILED`. Stop what you started when the task is done,
unless the user wants it left running.
