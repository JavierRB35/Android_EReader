#!/usr/bin/env bash
# Physical concurrency of the private coordinator core: separate sessions racing
# on real locks, which the sequential contract tests cannot exercise.
# Synthetic data only. Run from the repository root against a throwaway database
# that already has 001_private_core.sql and 002_private_pairing.sql applied.
set -euo pipefail

# Session A signals readiness only after writing and before committing.
# B/C must start after that signal, never race for the initial gate.
GATE=1030099
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

psq() { psql -XqtA -v ON_ERROR_STOP=1 "$@"; }
val() { psq -c "$1"; }

LIB=$(val "select gen_random_uuid()"); A=$(val "select gen_random_uuid()"); B=$(val "select gen_random_uuid()")
OTHER=$(val "select gen_random_uuid()"); C=$(val "select gen_random_uuid()")
TA=$(val "select encode(extensions.gen_random_bytes(32),'hex')")
TB=$(val "select encode(extensions.gen_random_bytes(32),'hex')")
TC=$(val "select encode(extensions.gen_random_bytes(32),'hex')")

psq > /dev/null <<SQL
select bia_coord.create_library('$LIB','$A','$TA');
select bia_coord.join_library('$LIB', bia_coord.issue_pairing('$LIB','$A','$TA')->>'code', '$B', '$TB');
select bia_coord.create_library('$OTHER','$C','$TC');
SQL

# Session A writes one note and keeps its transaction open: until another session
# is blocked by it ("wait") or for a number of seconds.
session_a() {
  psq > "$tmp/a" 2>&1 <<SQL &
begin;
select pg_advisory_lock($GATE);
select set_config('bia.hold','$3',true);
select bia_coord.apply_authenticated('$LIB','$A','$TA',gen_random_uuid(),'note','n1',$1,'put','$2');
select pg_advisory_unlock($GATE);
do \$\$
declare hold text := current_setting('bia.hold'); blocked integer := 0;
        deadline timestamptz := clock_timestamp() + interval '20 seconds';
begin
  if hold <> 'wait' then perform pg_sleep(hold::numeric); return; end if;
  while clock_timestamp() < deadline loop
    -- Statistics snapshots are cached within a transaction; refresh each poll.
    perform pg_stat_clear_snapshot();
    select count(*) into blocked from pg_stat_activity
     where datname = current_database() and wait_event_type = 'Lock'
       and pg_backend_pid() = any (pg_blocking_pids(pid));
    exit when blocked > 0;
    perform pg_sleep(0.05);
  end loop;
  if blocked = 0 then raise exception 'second session did not wait on first'; end if;
end \$\$;
commit;
SQL
  A_PID=$!
  # Wait for A to have taken the row lock and completed its write.
  # psql output is flushed per query in noninteractive mode; pg_stat_activity
  # also confirms the transaction is still executing its hold block.
  local deadline=$((SECONDS + 20))
  until grep -q '"status"' "$tmp/a"; do
    kill -0 "$A_PID" 2>/dev/null || fail "session A exited before readiness"
    [ "$SECONDS" -lt "$deadline" ] || fail "session A readiness timeout"
    sleep 0.05
  done
}

# Session B (same library) and C (another library) wait for the gate, then write.
call_b() {
  psq 2>&1 <<SQL
select pg_advisory_xact_lock($GATE);
select bia_coord.apply_authenticated('$LIB','$B','$TB',gen_random_uuid(),'note','$3',$1,'put','$2');
SQL
}
call_c() {
  psq 2>&1 <<SQL
select pg_advisory_xact_lock($GATE);
select bia_coord.apply_authenticated('$OTHER','$C','$TC',gen_random_uuid(),'note','n1',$1,'put','$2');
SQL
}

race() {
  session_a "$1" "$2" wait
  call_b "${5:-$1}" "$3" "$4" > "$tmp/b" || fail "session B failed"
  wait "$A_PID" || fail "session A failed"
  RESULT_A=$(result a)
  RESULT_B=$(result b)
}
result() { grep '"status"' "$tmp/$1" | tr -d ' '; }
entity() { psq -c "select revision||'|'||(case when deleted then 't' else 'f' end)||'|'||payload from bia_coord.entities where library_id='$LIB' and kind='note' and entity_id='$1'"; }
receipts() { psq -c "select count(*) from bia_coord.operations where library_id='$LIB'"; }

fail() { echo "FAIL $1"; cat "$tmp/a" "$tmp/b" 2>/dev/null; exit 1; }
check() {  # label, actual, expected fragments
  local label=$1 actual=$2 fragment
  shift 2
  for fragment in "$@"; do
    case $actual in *"$fragment"*) ;; *) fail "$label: [$fragment] not in [$actual]";; esac
  done
  echo "ok   $label"
}

# 1. Both devices create the same note while A's write is still uncommitted.
race 0 '{"text":"A"}' '{"text":"B"}' n1
check "create race: A applied" "$RESULT_A" '"status":"applied"' '"revision":1'
check "create race: B gets a conflict" "$RESULT_B" '"status":"conflict"' '"revision":1'
check "create race: A's note is kept" "$(entity n1)" '1|f|{"text": "A"}'
check "create race: one receipt" "$(receipts)" '1'

# 2. Both devices edit that note from the same revision.
race 1 '{"text":"A2"}' '{"text":"B2"}' n1
check "edit race: A applied" "$RESULT_A" '"status":"applied"' '"revision":2'
check "edit race: B gets a conflict" "$RESULT_B" '"status":"conflict"' '"revision":2'
check "edit race: A's edit is kept" "$(entity n1)" '2|f|{"text": "A2"}'
check "edit race: two receipts" "$(receipts)" '2'

# 3. A different note is accepted once A commits; neither write is lost.
race 2 '{"text":"A3"}' '{"text":"B"}' n2 0
check "disjoint notes: A applied" "$RESULT_A" '"status":"applied"' '"revision":3'
check "disjoint notes: B applied" "$RESULT_B" '"status":"applied"' '"revision":4'
check "disjoint notes: A's edit is kept" "$(entity n1)" '3|f|{"text": "A3"}'
check "disjoint notes: B's note is stored" "$(entity n2)" '4|f|{"text": "B"}'
check "disjoint notes: four receipts" "$(receipts)" '4'

# 4. A write held open for five seconds does not block another library.
session_a 3 '{"text":"A4"}' 5
started=$SECONDS
call_c 0 '{"text":"C"}' > "$tmp/b" || fail "session C failed"
elapsed=$((SECONDS - started))
wait "$A_PID" || fail "session A failed"
check "other library: applied while A holds a write" "$(result b)" '"status":"applied"' '"revision":1'
[ "$elapsed" -lt 3 ] || fail "other library waited ${elapsed}s behind an unrelated write"
echo "ok   other library: not blocked (${elapsed}s)"

echo "concurrent sessions: 4 scenarios passed"
