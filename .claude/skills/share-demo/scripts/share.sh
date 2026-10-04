#!/usr/bin/env bash
# Put the local stack on a public URL through Cloudflare quick tunnels.
#
#   share.sh up      [app...]    default: what is already shared, else school
#   share.sh status              every hop, local and public; exit 1 if any is not ok
#   share.sh restart [target...] bounce api and/or apps; tunnels and URLs survive
#   share.sh down                tunnels, the shared apps and the API
#
# Apps: school parent teacher driver public. platform is refused — see SKILL.md.
#
# The API and the apps are started through local-env's local.sh, so they share
# its pid files and logs under .run/ and show up in its status. What is this
# script's own lives under .run/share/: the tunnels, the JWT secret, and a note
# of which API URL each app was built against.
#
# An app is served as a production build, because NEXT_PUBLIC_SCHOOLSOFT_API_URL
# is inlined at build time and has to be the API's public URL. That takes the
# app's port and its .next/ — a dev server this stack started is stopped first,
# and one it did not start is left alone and the app is refused.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
RUN="$ROOT/.run"
SH="$RUN/share"
LOCAL="$ROOT/.claude/skills/local-env/scripts/local.sh"
mkdir -p "$SH"

APPS="school parent teacher driver public"
# Asks Cloudflare's resolver rather than the machine's: a quick tunnel's name
# is minutes old, and macOS caches the NXDOMAIN from asking too early.
DOH="https://cloudflare-dns.com/dns-query"

port_of() {
  case "$1" in
    api) cat "$RUN/api.port" 2>/dev/null ;;
    school) echo 3001 ;; teacher) echo 3003 ;; parent) echo 3004 ;;
    driver) echo 3005 ;; public) echo 3006 ;;
  esac
}
workspace_of() {
  case "$1" in
    school) echo @schoolsoft/school-web ;; public) echo @schoolsoft/public-site ;;
    parent) echo @schoolsoft/parent-app ;; teacher) echo @schoolsoft/teacher-app ;;
    driver) echo @schoolsoft/driver-app ;;
  esac
}
dir_of() { local w; w=$(workspace_of "$1"); echo "$ROOT/apps/${w#@schoolsoft/}"; }
# The path that answers 200 without a session when the target is healthy.
probe_of() { case "$1" in api) echo /actuator/info ;; public) echo / ;; *) echo /login ;; esac; }

check_apps() {
  for t in "$@"; do
    case "$t" in
      school|parent|teacher|driver|public) ;;
      platform) echo "platform: refused. Sign-in accepts 000000 for any account, and platform-web is the operator console for every chain." >&2; exit 2 ;;
      *) echo "unknown app: $t ($APPS)" >&2; exit 2 ;;
    esac
  done
}

listener_pid() { [ -n "$1" ] && lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null | head -1; }
alive()        { [ -f "$1" ] && kill -0 "$(cat "$1")" 2>/dev/null; }
read_file()    { cat "$1" 2>/dev/null || true; }
local_code()   { curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://localhost:$1$2"; }
public_code()  { curl -s -o /dev/null -w '%{http_code}' --max-time 10 --doh-url "$DOH" "$1"; }

wait_public() { # url seconds
  local i=0
  while [ $i -lt "$2" ]; do
    [ "$(public_code "$1")" = 200 ] && return 0
    sleep 3; i=$((i + 3))
  done
  return 1
}

shared_apps() {
  for t in $APPS; do [ -f "$SH/$t.started" ] && echo "$t"; done
}

# ------------------------------------------------------------------- api

# A dev API is open in four ways that do not matter on localhost and do on a
# public URL, so the shared one is started differently:
#   - the JWT secret is private. The default is in git. One secret is kept for
#     the life of .run/share so a restart does not sign everybody out.
#   - the sign-in code is random, not 000000, and new for each share. It is
#     what the user hands out along with the URL.
#   - that code does not open a platform admin. The API serves that door too,
#     whether or not platform-web is shared.
#   - CORS names the shared apps' origins and no others.
# Forwarded headers are honoured (from loopback only, which is where
# cloudflared connects from) so the per-address rate limits see the visitor
# and not 127.0.0.1 for everybody.
ensure_api() { # origins, comma-separated
  local origins="$1"
  [ -f "$SH/jwt.secret" ] || (umask 077; openssl rand -hex 32 > "$SH/jwt.secret")
  [ -f "$SH/otp.code" ] || (umask 077; printf '%06d\n' $(( $(od -An -N4 -tu4 /dev/urandom) % 1000000 )) > "$SH/otp.code")

  if alive "$RUN/api.pid" && [ "$(read_file "$RUN/api.pid")" != "$(read_file "$SH/api.started")" ]; then
    echo "api: running as a dev API — restarting it locked down for sharing"
    "$LOCAL" stop api
  elif alive "$RUN/api.pid" && [ "$(read_file "$SH/api.origins")" != "$origins" ]; then
    echo "api: the set of shared apps changed — restarting it to allow their origins"
    "$LOCAL" stop api
  fi
  echo "$origins" > "$SH/api.origins"
  export SCHOOLSOFT_DEV_OTP_CODE="$(cat "$SH/otp.code")" SCHOOLSOFT_DEV_OTP_PLATFORM=false
  export SCHOOLSOFT_CORS_ORIGINS="$origins" SERVER_FORWARD_HEADERS_STRATEGY=native
  # local.sh's exit status is not the API's — "start api" alone ends on a false
  # test — so the pid file is what says whether it came up.
  SCHOOLSOFT_JWT_SECRET="$(cat "$SH/jwt.secret")" "$LOCAL" start api
  if ! alive "$RUN/api.pid"; then
    echo "api: an API this stack did not start is holding the port, so its JWT secret is unknown. Stop it and run up again." >&2
    return 1
  fi
  cp "$RUN/api.pid" "$SH/api.started"
}

# ------------------------------------------------------------------ apps

ensure_app() {
  local t="$1" port ws api_url
  port=$(port_of "$t"); ws=$(workspace_of "$t"); api_url=$(read_file "$SH/api.url")

  if [ -n "$(listener_pid "$port")" ]; then
    if ! alive "$RUN/$t.pid"; then
      echo "$t: :$port is held by a server this stack did not start — stop it and run up again" >&2
      return 1
    fi
    if [ "$(read_file "$SH/$t.started")" = "$(cat "$RUN/$t.pid")" ] && [ "$(read_file "$SH/$t.baked")" = "$api_url" ]; then
      echo "$t: already serving a build for $api_url"
      return 0
    fi
    echo "$t: stopping the running server to rebuild against $api_url"
    "$LOCAL" stop "$t"
  fi

  if [ "${REUSE_BUILD:-0}" = 1 ] && [ "$(read_file "$SH/$t.baked")" = "$api_url" ] && [ -f "$(dir_of "$t")/.next/BUILD_ID" ]; then
    echo "$t: reusing the build for $api_url"
  else
    rm -f "$SH/$t.baked"
    echo -n "$t: building against $api_url "
    if (cd "$ROOT" && NEXT_PUBLIC_SCHOOLSOFT_API_URL="$api_url" npm -w "$ws" run build) > "$SH/$t.build.log" 2>&1; then
      echo "— built"
    else
      echo "— BUILD FAILED"; tail -25 "$SH/$t.build.log"; return 1
    fi
    echo "$api_url" > "$SH/$t.baked"
  fi

  : > "$RUN/$t.log"
  ( cd "$ROOT" && set -m; nohup npm -w "$ws" run start >> "$RUN/$t.log" 2>&1 < /dev/null & echo $! > "$RUN/$t.pid" )
  local i=0
  while [ $i -lt 60 ] && [ -z "$(listener_pid "$port")" ]; do sleep 1; i=$((i + 1)); done
  if [ -z "$(listener_pid "$port")" ]; then
    echo "$t: did not come up on :$port"; tail -25 "$RUN/$t.log"; return 1
  fi
  cp "$RUN/$t.pid" "$SH/$t.started"
}

# --------------------------------------------------------------- tunnels

ensure_tunnel() { # target
  local t="$1" port url i=0
  port=$(port_of "$t")
  if alive "$SH/$t.tunnel.pid" && [ -s "$SH/$t.url" ]; then return 0; fi
  command -v cloudflared >/dev/null || { echo "cloudflared is not on PATH" >&2; return 1; }

  rm -f "$SH/$t.url"; : > "$SH/$t.tunnel.log"
  ( set -m; nohup cloudflared tunnel --no-autoupdate --url "http://localhost:$port" \
      >> "$SH/$t.tunnel.log" 2>&1 < /dev/null & echo $! > "$SH/$t.tunnel.pid" )
  while [ $i -lt 40 ]; do
    url=$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$SH/$t.tunnel.log" | grep -v '//api\.' | head -1)
    [ -n "$url" ] && break
    alive "$SH/$t.tunnel.pid" || break
    sleep 1; i=$((i + 1))
  done
  if [ -z "${url:-}" ]; then
    echo "$t: cloudflared gave no URL"; tail -15 "$SH/$t.tunnel.log"; return 1
  fi
  echo "$url" > "$SH/$t.url"
  # Keep the machine awake for as long as the API's tunnel lives.
  [ "$t" = api ] && (nohup caffeinate -i -w "$(cat "$SH/$t.tunnel.pid")" > /dev/null 2>&1 < /dev/null &)
  return 0
}

stop_tunnel() {
  local t="$1" pid
  if [ -f "$SH/$t.tunnel.pid" ]; then
    pid=$(cat "$SH/$t.tunnel.pid")
    kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null
    echo "$t: tunnel closed"
  fi
  rm -f "$SH/$t.tunnel.pid" "$SH/$t.url"
}

expose() { # target — tunnel it and wait until the public URL answers
  local t="$1" url
  ensure_tunnel "$t" || return 1
  url=$(cat "$SH/$t.url")
  echo -n "$t: waiting for $url "
  if wait_public "$url$(probe_of "$t")" 90; then echo "— reachable"; else
    echo "— NOT REACHABLE (public $(public_code "$url$(probe_of "$t")"), local $(local_code "$(port_of "$t")" "$(probe_of "$t")"))"
    tail -8 "$SH/$t.tunnel.log"; return 1
  fi
}

# ------------------------------------------------------------- commands

up() {
  local apps failed=0 origins=""
  check_apps "$@"
  apps=$(for t in "$@" $(shared_apps); do echo "$t"; done | sort -u | xargs)
  [ -z "$apps" ] && apps=school

  # The apps' tunnels first: their URLs are the origins the API has to allow,
  # and cloudflared does not mind that nothing is listening yet.
  for t in $apps; do
    ensure_tunnel "$t" || exit 1
    origins="${origins:+$origins,}$(cat "$SH/$t.url")"
  done
  ensure_api "$origins" || exit 1
  expose api || exit 1
  for t in $apps; do
    { ensure_app "$t" && expose "$t"; } || failed=1
  done

  echo
  for t in $apps; do
    [ -s "$SH/$t.url" ] && echo "  $t  $(cat "$SH/$t.url")$(probe_of "$t")"
  done
  echo "  api  $(cat "$SH/api.url")"
  echo
  echo "  sign-in code  $(cat "$SH/otp.code")   (chain slug: smoketest)"
  echo
  echo "That code opens every school account, so it is the password: send it only to the"
  echo "people trying the app. It does not open a platform admin. Demo data only."
  echo "URLs change if a tunnel is restarted; the code changes after down."
  return $failed
}

status() {
  local t port url lc pc tun bad=0 targets
  if [ ! -f "$SH/api.tunnel.pid" ] && [ -z "$(shared_apps)" ]; then echo "nothing is shared — run up"; return 1; fi
  targets="api $(shared_apps | xargs)"
  printf "%-8s %-6s %-6s %-7s %-7s %s\n" TARGET PORT LOCAL TUNNEL PUBLIC URL
  for t in $targets; do
    port=$(port_of "$t"); url=$(read_file "$SH/$t.url"); lc=-; pc=-; tun=down
    [ -n "$(listener_pid "$port")" ] && lc=$(local_code "$port" "$(probe_of "$t")")
    alive "$SH/$t.tunnel.pid" && tun=up
    [ -n "$url" ] && pc=$(public_code "$url$(probe_of "$t")")
    { [ "$lc" = 200 ] && [ "$tun" = up ] && [ "$pc" = 200 ]; } || bad=1
    printf "%-8s %-6s %-6s %-7s %-7s %s\n" "$t" "${port:--}" "$lc" "$tun" "$pc" "${url:--}"
  done
  for t in $(shared_apps); do
    [ "$(read_file "$SH/$t.baked")" = "$(read_file "$SH/api.url")" ] || { echo "$t: built against a different API URL than the live one — run up"; bad=1; }
  done
  alive "$RUN/api.pid" && [ "$(read_file "$RUN/api.pid")" != "$(read_file "$SH/api.started")" ] && { echo "api: not started by share.sh, so it is a dev API: 000000 signs anyone in, platform admins included — run up"; bad=1; }
  if [ $bad -eq 0 ]; then echo "all reachable"; else echo "NOT all reachable — 'up' repairs whatever is down"; fi
  return $bad
}

restart() {
  local targets="$*"
  [ -z "$targets" ] && targets="api $(shared_apps | xargs)"
  for t in $targets; do
    [ "$t" = api ] || check_apps "$t"
    "$LOCAL" stop "$t"
  done
  REUSE_BUILD=1 up $(for t in $targets $(shared_apps); do [ "$t" != api ] && echo "$t"; done | sort -u)
}

down() {
  for f in "$SH"/*.tunnel.pid; do [ -e "$f" ] && stop_tunnel "$(basename "$f" .tunnel.pid)"; done
  for t in $(shared_apps); do "$LOCAL" stop "$t"; rm -f "$SH/$t.started"; done
  if [ -f "$SH/api.started" ]; then
    [ "$(read_file "$RUN/api.pid")" = "$(cat "$SH/api.started")" ] && "$LOCAL" stop api
    rm -f "$SH/api.started"
  fi
  rm -f "$SH/otp.code" "$SH/api.origins"
}

cmd="${1:-status}"; shift || true
case "$cmd" in
  up)      up "$@" ;;
  status)  status ;;
  restart) restart "$@" ;;
  down)    down ;;
  *) echo "usage: share.sh up|status|restart|down [target...]" >&2; exit 2 ;;
esac
