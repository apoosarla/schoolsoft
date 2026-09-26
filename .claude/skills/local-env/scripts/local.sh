#!/usr/bin/env bash
# Start, stop and inspect the Schoolsoft stack on this machine.
#
#   local.sh start  [target...]   default target: api school
#   local.sh stop   [target...]   default: everything this script started
#   local.sh status
#   local.sh logs   <target> [lines]
#
# Targets: api school platform public parent teacher driver infra
#          web (= school platform public)   all (= api + the six apps)
#
# Processes run detached, each in its own process group, with a pid file and
# a log under .run/ at the repo root. stop only kills what a pid file names;
# a port held by something else is reported, never killed, unless --force is
# given and the holder is a node, next-server or java process (never ssh — this machine
# tunnels 8080, 3000 and 9000 over ssh).

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
RUN="$ROOT/.run"
mkdir -p "$RUN"

FORCE=0
ARGS=()
for a in "$@"; do
  if [ "$a" = "--force" ]; then FORCE=1; else ARGS+=("$a"); fi
done
set -- ${ARGS[@]+"${ARGS[@]}"}

# ---------------------------------------------------------------- targets

port_of() {
  case "$1" in
    api) api_port ;;
    school) echo 3001 ;; platform) echo 3002 ;; teacher) echo 3003 ;;
    parent) echo 3004 ;; driver) echo 3005 ;; public) echo 3006 ;;
    *) echo "" ;;
  esac
}

workspace_of() {
  case "$1" in
    school) echo @schoolsoft/school-web ;; platform) echo @schoolsoft/platform-web ;;
    public) echo @schoolsoft/public-site ;; parent) echo @schoolsoft/parent-app ;;
    teacher) echo @schoolsoft/teacher-app ;; driver) echo @schoolsoft/driver-app ;;
  esac
}

APPS="school platform public parent teacher driver"

expand() {
  for t in "$@"; do
    case "$t" in
      web) echo school platform public ;;
      all) echo api $APPS ;;
      api|infra|school|platform|public|parent|teacher|driver) echo "$t" ;;
      *) echo "unknown target: $t (api school platform public parent teacher driver infra web all)" >&2; exit 2 ;;
    esac
  done
}

# ----------------------------------------------------------------- ports

listener_pid() { lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null | head -1; }
proc_name()    { ps -o comm= -p "$1" 2>/dev/null | xargs basename 2>/dev/null; }

# The API's port: whatever this script chose last time, else 8080 when it is
# free, else 8090. SCHOOLSOFT_API_PORT overrides both.
api_port() {
  if [ -n "${SCHOOLSOFT_API_PORT:-}" ]; then echo "$SCHOOLSOFT_API_PORT"; return; fi
  if [ -f "$RUN/api.port" ]; then cat "$RUN/api.port"; return; fi
  local holder; holder=$(listener_pid 8080)
  if [ -z "$holder" ] || [ "$(proc_name "$holder")" = "java" ]; then echo 8080; else echo 8090; fi
}

wait_for_port() { # port seconds name
  local i=0
  while [ $i -lt "$2" ]; do
    [ -n "$(listener_pid "$1")" ] && return 0
    if [ -f "$RUN/$3.pid" ] && ! kill -0 "$(cat "$RUN/$3.pid")" 2>/dev/null; then return 1; fi
    sleep 1; i=$((i + 1))
  done
  return 1
}

# ----------------------------------------------------------------- start

launch() { # name, then the command
  local name="$1"; shift
  : > "$RUN/$name.log"
  # set -m gives the background job its own process group, so stop can take
  # down mvnw's java child or next's workers along with the parent.
  ( set -m; nohup "$@" >> "$RUN/$name.log" 2>&1 < /dev/null & echo $! > "$RUN/$name.pid" )
}

start_one() {
  local t="$1" port holder
  if [ "$t" = infra ]; then
    if [ -n "$(listener_pid 9000)" ] && [ "$(proc_name "$(listener_pid 9000)")" = ssh ]; then
      echo "infra: port 9000 is an ssh tunnel here, so MinIO cannot bind it — starting redis, opensearch, emqx only"
      (cd "$ROOT" && docker compose up -d redis opensearch emqx)
    else
      (cd "$ROOT" && docker compose up -d)
    fi
    return
  fi

  port=$(port_of "$t")
  holder=$(listener_pid "$port")
  if [ -n "$holder" ]; then
    if [ -f "$RUN/$t.pid" ]; then
      echo "$t: already running on :$port (pid $(cat "$RUN/$t.pid"))"
    else
      echo "$t: :$port already held by $(proc_name "$holder") pid $holder, not started by this script — leaving it"
    fi
    return
  fi

  if [ "$t" = api ]; then
    if ! pg_isready -h localhost -p 5432 -q 2>/dev/null; then
      echo "api: Postgres is not answering on localhost:5432 — it runs on the host, not in compose. Start it first." >&2
      return 1
    fi
    echo "$port" > "$RUN/api.port"
    (cd "$ROOT/apps/api" && export SERVER_PORT="$port" && launch api ./mvnw -q spring-boot:run)
    echo -n "api: starting on :$port "
    if wait_for_port "$port" 240 api; then echo "— up"; else echo "— FAILED"; tail -25 "$RUN/api.log"; return 1; fi
  else
    (cd "$ROOT" && export NEXT_PUBLIC_SCHOOLSOFT_API_URL="http://localhost:$(api_port)" \
      && launch "$t" npm -w "$(workspace_of "$t")" run dev)
    echo -n "$t: starting on :$port "
    if wait_for_port "$port" 90 "$t"; then echo "— http://localhost:$port"; else echo "— FAILED"; tail -25 "$RUN/$t.log"; return 1; fi
  fi
}

# ------------------------------------------------------------------ stop

stop_one() {
  local t="$1" pid port holder name
  if [ "$t" = infra ]; then (cd "$ROOT" && docker compose down); return; fi
  port=$(port_of "$t")

  if [ -f "$RUN/$t.pid" ]; then
    pid=$(cat "$RUN/$t.pid")
    kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      [ -z "$(listener_pid "$port")" ] && break
      sleep 1
    done
    [ -n "$(listener_pid "$port")" ] && kill -KILL -- "-$pid" 2>/dev/null
    rm -f "$RUN/$t.pid"
    [ "$t" = api ] && rm -f "$RUN/api.port"
    echo "$t: stopped"
  fi

  holder=$(listener_pid "$port")
  [ -z "$holder" ] && return
  name=$(proc_name "$holder")
  if [ $FORCE -eq 1 ] && case "$name" in node|next-server|java) true ;; *) false ;; esac; then
    kill -TERM "$holder" && echo "$t: killed $name pid $holder on :$port (--force)"
  else
    echo "$t: :$port still held by $name pid $holder, not started by this script — left alone (--force kills node/next-server/java)"
  fi
}

# ---------------------------------------------------------------- status

status() {
  local t port holder state
  printf "%-9s %-6s %-9s %s\n" TARGET PORT STATE DETAIL
  for t in api $APPS; do
    port=$(port_of "$t"); holder=$(listener_pid "$port")
    if [ -f "$RUN/$t.pid" ] && [ -n "$holder" ]; then state=up; detail="managed, log .run/$t.log"
    elif [ -f "$RUN/$t.pid" ]; then state=starting; detail="pid $(cat "$RUN/$t.pid") not listening yet (or died — see logs)"
    elif [ -n "$holder" ]; then state=external; detail="$(proc_name "$holder") pid $holder, not started by this script"
    else state=down; detail=""; fi
    printf "%-9s %-6s %-9s %s\n" "$t" "$port" "$state" "$detail"
  done
  if pg_isready -h localhost -p 5432 -q 2>/dev/null; then echo "postgres  5432   up        host install"; else echo "postgres  5432   down"; fi
  if docker info >/dev/null 2>&1; then
    echo "infra:"; (cd "$ROOT" && docker compose ps --format '  {{.Service}} {{.State}}' 2>/dev/null) || true
  else
    echo "infra: docker daemon not running"
  fi
}

# ------------------------------------------------------------------ main

cmd="${1:-status}"; shift || true
case "$cmd" in
  start)
    [ $# -eq 0 ] && set -- api school
    targets=$(expand "$@") || exit 2
    # api first, so the apps are pointed at the port it actually took.
    for t in $targets; do [ "$t" = api ] && { start_one api || exit 1; }; done
    for t in $targets; do [ "$t" != api ] && start_one "$t"; done
    ;;
  stop)
    if [ $# -eq 0 ]; then
      targets=""
      for f in "$RUN"/*.pid; do [ -e "$f" ] && targets="$targets $(basename "$f" .pid)"; done
      [ -z "$targets" ] && { echo "nothing started by this script is running"; exit 0; }
    else
      targets=$(expand "$@") || exit 2
    fi
    for t in $targets; do stop_one "$t"; done
    ;;
  status) status ;;
  logs)
    [ $# -ge 1 ] || { echo "usage: local.sh logs <target> [lines]" >&2; exit 2; }
    tail -n "${2:-60}" "$RUN/$1.log"
    ;;
  *) echo "usage: local.sh start|stop|status|logs [target...] [--force]" >&2; exit 2 ;;
esac
