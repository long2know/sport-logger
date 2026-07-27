#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
SYNC_SCRIPT="$SCRIPT_DIR/sync-mesh.sh"
WORK_DIR="$SCRIPT_DIR/.sync-mesh-test-$$-$RANDOM"

cleanup() {
  rm -rf -- "$WORK_DIR"
}
trap cleanup EXIT

fail() {
  echo "not ok - $*" >&2
  exit 1
}

make_mesh() {
  local case_dir="$1"
  local target="$2"
  local auth="${3:-none}"

  cat > "$case_dir/mesh.json" <<EOF
{
  "squads": {
    "remote-api": {
      "zone": "remote-opaque",
      "source": "https://example.invalid/SUMMARY.md",
      "sync_to": "$target",
      "auth": "$auth"
    }
  }
}
EOF
}

run_sync() {
  local case_dir="$1"
  (
    cd "$case_dir"
    unset MESH_ROOT
    PATH="$WORK_DIR/bin:$PATH" \
      MOCK_CURL_ARGS="$case_dir/curl-args" \
      MOCK_CURL_STDIN="$case_dir/curl-stdin" \
      bash "$SYNC_SCRIPT" mesh.json > stdout 2> stderr
  )
}

run_init() {
  local case_dir="$1"
  (
    cd "$case_dir"
    PATH="$WORK_DIR/bin:$PATH" \
      bash "$SYNC_SCRIPT" --init mesh.json > stdout 2> stderr
  )
}

assert_no_success_message() {
  local output_file="$1"
  local success_message="$2"

  if grep -Fq -- "$success_message" "$output_file"; then
    fail "failure path claimed success: $success_message"
  fi
}

assert_no_jq_temp_files() {
  local case_dir="$1"
  local matches=()

  shopt -s nullglob
  matches=("$case_dir"/.sync-mesh-jq.*)
  shopt -u nullglob
  [ "${#matches[@]}" -eq 0 ] || fail "jq temporary files were not cleaned up"
}

assert_rejected_target() {
  local name="$1"
  local target="$2"
  local case_dir="$WORK_DIR/$name"

  mkdir -p -- "$case_dir"
  make_mesh "$case_dir" "$target"
  run_sync "$case_dir"
  [ ! -e "$case_dir/curl-args" ] || fail "$target reached curl"
  echo "ok - rejects $target"
}

assert_rejected_default_root_symlink() {
  local name="$1"
  local symlink_component="$2"
  local case_dir="$WORK_DIR/$name"
  local outside_dir="$WORK_DIR/$name-outside"

  mkdir -p -- "$case_dir" "$outside_dir"
  if [ "$symlink_component" = ".mesh" ]; then
    ln -s "$outside_dir" "$case_dir/.mesh"
  else
    mkdir -p -- "$case_dir/.mesh"
    ln -s "$outside_dir" "$case_dir/.mesh/remotes"
  fi

  make_mesh "$case_dir" ".mesh/remotes/remote-api"
  if run_sync "$case_dir"; then
    fail "default root with symlinked $symlink_component was accepted"
  fi
  [ ! -e "$case_dir/curl-args" ] || fail "default root symlink reached curl"
  [ -z "$(find "$outside_dir" -mindepth 1 -print -quit)" ] ||
    fail "default root symlink wrote outside the invocation root"
  echo "ok - rejects symlinked default root component $symlink_component"
}

mkdir -p -- "$WORK_DIR/bin"
cat > "$WORK_DIR/bin/jq" <<'EOF'
#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const args = process.argv.slice(2);
const variables = {};
let filter = '';
let file = '';

for (let i = 0; i < args.length; i += 1) {
  if (args[i] === '-e' || args[i] === '-j' || args[i] === '-r') continue;
  if (args[i] === '--arg') {
    variables[args[i + 1]] = args[i + 2];
    i += 2;
    continue;
  }
  if (!filter) {
    filter = args[i];
  } else {
    file = args[i];
  }
}

let config;
try {
  config = JSON.parse(fs.readFileSync(file, 'utf8'));
} catch (error) {
  process.stderr.write(`${error.message}\n`);
  process.exit(4);
}
const squads = config.squads || {};

if (filter.includes('type == "object"') && filter.includes('.squads')) {
  const valid = config !== null &&
    typeof config === 'object' &&
    !Array.isArray(config) &&
    config.squads !== null &&
    typeof config.squads === 'object' &&
    !Array.isArray(config.squads);
  process.exit(valid ? 0 : 1);
}

let keys = Object.keys(squads).sort();
if (filter.includes('to_entries[]')) {
  const zone = filter.match(/value\.zone == "([^"]+)"/)?.[1];
  if (zone !== undefined) {
    keys = keys.filter((key) => squads[key]?.zone === zone);
  }
}

function stringValue(value, fallback = '') {
  const selected = value === undefined || value === null || value === false
    ? fallback
    : value;
  return typeof selected === 'string' ? selected : '';
}

function writeRecord(values) {
  for (const value of values) process.stdout.write(`${value}\0`);
}

for (const key of keys) {
  const squad = squads[key] || {};
  if (filter.includes('remote-trusted')) {
    writeRecord([
      key,
      stringValue(squad.source),
      stringValue(squad.ref, 'main'),
      stringValue(squad.sync_to),
    ]);
  } else if (filter.includes('remote-opaque')) {
    writeRecord([
      key,
      stringValue(squad.source),
      stringValue(squad.sync_to),
      stringValue(squad.auth),
    ]);
  } else {
    writeRecord([key, stringValue(squad.zone)]);
  }
}
EOF
chmod +x "$WORK_DIR/bin/jq"

cat > "$WORK_DIR/bin/curl" <<'EOF'
#!/bin/bash
set -euo pipefail

printf '%s\n' "$@" > "$MOCK_CURL_ARGS"
cat > "$MOCK_CURL_STDIN"

output=""
args=("$@")
for ((i = 0; i < ${#args[@]}; i += 1)); do
  if [ "${args[$i]}" = "--output" ]; then
    output="${args[$((i + 1))]}"
    break
  fi
done

[ -n "$output" ]
printf '# mocked summary\n' > "$output"
EOF
chmod +x "$WORK_DIR/bin/curl"

invalid_sync_case="$WORK_DIR/invalid-json-sync"
mkdir -p -- "$invalid_sync_case"
printf '{"squads": {' > "$invalid_sync_case/mesh.json"
if run_sync "$invalid_sync_case"; then
  fail "normal sync accepted invalid JSON"
fi
assert_no_success_message "$invalid_sync_case/stdout" "Mesh sync complete"
[ ! -e "$invalid_sync_case/.mesh" ] || fail "invalid JSON caused a normal-sync state write"
[ ! -e "$invalid_sync_case/curl-args" ] || fail "invalid JSON reached curl"
assert_no_jq_temp_files "$invalid_sync_case"
echo "ok - normal sync rejects invalid JSON without partial state"

invalid_init_case="$WORK_DIR/invalid-json-init"
mkdir -p -- "$invalid_init_case"
printf '{"squads": {' > "$invalid_init_case/mesh.json"
if run_init "$invalid_init_case"; then
  fail "init accepted invalid JSON"
fi
assert_no_success_message "$invalid_init_case/stdout" "Mesh state repository initialized"
[ ! -e "$invalid_init_case/README.md" ] || fail "invalid JSON created init README.md"
assert_no_jq_temp_files "$invalid_init_case"
echo "ok - init rejects invalid JSON without partial state"

invalid_shape_sync_case="$WORK_DIR/invalid-squads-shape-sync"
mkdir -p -- "$invalid_shape_sync_case"
printf '{"squads":[]}\n' > "$invalid_shape_sync_case/mesh.json"
if run_sync "$invalid_shape_sync_case"; then
  fail "normal sync accepted a non-object .squads value"
fi
assert_no_success_message "$invalid_shape_sync_case/stdout" "Mesh sync complete"
[ ! -e "$invalid_shape_sync_case/.mesh" ] ||
  fail "non-object .squads caused a normal-sync state write"
assert_no_jq_temp_files "$invalid_shape_sync_case"
echo "ok - normal sync requires a .squads object"

invalid_shape_init_case="$WORK_DIR/invalid-squads-shape-init"
mkdir -p -- "$invalid_shape_init_case"
printf '{"squads":[]}\n' > "$invalid_shape_init_case/mesh.json"
if run_init "$invalid_shape_init_case"; then
  fail "init accepted a non-object .squads value"
fi
assert_no_success_message "$invalid_shape_init_case/stdout" "Mesh state repository initialized"
[ ! -e "$invalid_shape_init_case/README.md" ] ||
  fail "non-object .squads created init README.md"
assert_no_jq_temp_files "$invalid_shape_init_case"
echo "ok - init requires a .squads object"

missing_jq_sync_case="$WORK_DIR/missing-jq-sync"
mkdir -p -- "$missing_jq_sync_case/empty-bin"
make_mesh "$missing_jq_sync_case" ".mesh/remotes/remote-api"
if (
  cd "$missing_jq_sync_case"
  PATH="$missing_jq_sync_case/empty-bin" \
    /bin/bash "$SYNC_SCRIPT" mesh.json > stdout 2> stderr
); then
  fail "normal sync succeeded without jq"
fi
assert_no_success_message "$missing_jq_sync_case/stdout" "Mesh sync complete"
[ ! -e "$missing_jq_sync_case/.mesh" ] || fail "missing jq caused a normal-sync state write"
echo "ok - normal sync fails closed when jq is missing"

missing_jq_init_case="$WORK_DIR/missing-jq-init"
mkdir -p -- "$missing_jq_init_case/empty-bin"
make_mesh "$missing_jq_init_case" ".mesh/remotes/remote-api"
if (
  cd "$missing_jq_init_case"
  PATH="$missing_jq_init_case/empty-bin" \
    /bin/bash "$SYNC_SCRIPT" --init mesh.json > stdout 2> stderr
); then
  fail "init succeeded without jq"
fi
assert_no_success_message "$missing_jq_init_case/stdout" "Mesh state repository initialized"
[ ! -e "$missing_jq_init_case/README.md" ] || fail "missing jq created init README.md"
echo "ok - init fails closed when jq is missing"

failing_jq_bin="$WORK_DIR/failing-jq-bin"
mkdir -p -- "$failing_jq_bin"
cat > "$failing_jq_bin/jq" <<'EOF'
#!/bin/bash
set -euo pipefail

for argument in "$@"; do
  if [[ "$argument" == *"$FAIL_JQ_FILTER"* ]]; then
    printf 'partial\0'
    exit 42
  fi
done

exec "$BASE_JQ" "$@"
EOF
chmod +x "$failing_jq_bin/jq"

failing_keys_sync_case="$WORK_DIR/failing-keys-sync"
mkdir -p -- "$failing_keys_sync_case"
cat > "$failing_keys_sync_case/mesh.json" <<'EOF'
{
  "squads": {
    "trusted-api": {
      "zone": "remote-trusted",
      "source": "https://example.invalid/trusted.git",
      "sync_to": ".mesh/remotes/trusted-api"
    },
    "opaque-api": {
      "zone": "remote-opaque",
      "source": "https://example.invalid/SUMMARY.md",
      "sync_to": ".mesh/remotes/opaque-api"
    }
  }
}
EOF
if (
  cd "$failing_keys_sync_case"
  unset MESH_ROOT
  PATH="$failing_jq_bin:$WORK_DIR/bin:$PATH" \
    BASE_JQ="$WORK_DIR/bin/jq" \
    FAIL_JQ_FILTER='remote-opaque' \
    MOCK_CURL_ARGS="$failing_keys_sync_case/curl-args" \
    MOCK_CURL_STDIN="$failing_keys_sync_case/curl-stdin" \
    bash "$SYNC_SCRIPT" mesh.json > stdout 2> stderr
); then
  fail "normal sync ignored a jq key-list failure"
fi
assert_no_success_message "$failing_keys_sync_case/stdout" "Mesh sync complete"
[ ! -e "$failing_keys_sync_case/.mesh" ] ||
  fail "jq key-list failure caused a partial normal-sync state write"
[ ! -e "$failing_keys_sync_case/curl-args" ] || fail "jq key-list failure reached curl"
assert_no_jq_temp_files "$failing_keys_sync_case"
echo "ok - normal sync propagates jq key-list failure before writes"

failing_keys_init_case="$WORK_DIR/failing-keys-init"
mkdir -p -- "$failing_keys_init_case"
cat > "$failing_keys_init_case/mesh.json" <<'EOF'
{
  "squads": {
    "safe-squad": { "zone": "remote-trusted" }
  }
}
EOF
if (
  cd "$failing_keys_init_case"
  PATH="$failing_jq_bin:$WORK_DIR/bin:$PATH" \
    BASE_JQ="$WORK_DIR/bin/jq" \
    FAIL_JQ_FILTER='to_entries[]' \
    bash "$SYNC_SCRIPT" --init mesh.json > stdout 2> stderr
); then
  fail "init ignored a jq key-list failure"
fi
assert_no_success_message "$failing_keys_init_case/stdout" "Mesh state repository initialized"
[ ! -e "$failing_keys_init_case/safe-squad" ] ||
  fail "jq key-list failure created a partial init squad directory"
[ ! -e "$failing_keys_init_case/README.md" ] ||
  fail "jq key-list failure created a partial init README.md"
assert_no_jq_temp_files "$failing_keys_init_case"
echo "ok - init propagates jq key-list failure before writes"

assert_rejected_target "relative-traversal" "foo/.."
assert_rejected_target "absolute-traversal" "/etc/.."
assert_rejected_target "mesh-root" ".mesh/remotes"
assert_rejected_target "current-directory" "."
assert_rejected_target "parent-directory" ".."

assert_rejected_default_root_symlink "default-mesh-symlink" ".mesh"
assert_rejected_default_root_symlink "default-remotes-symlink" ".mesh/remotes"

explicit_root_case="$WORK_DIR/explicit-symlink-root"
explicit_root_outside="$WORK_DIR/explicit-symlink-root-outside"
mkdir -p -- "$explicit_root_case" "$explicit_root_outside"
ln -s "$explicit_root_outside" "$explicit_root_case/.mesh"
make_mesh "$explicit_root_case" ".mesh/remotes/remote-api"
(
  cd "$explicit_root_case"
  PATH="$WORK_DIR/bin:$PATH" \
    MOCK_CURL_ARGS="$explicit_root_case/curl-args" \
    MOCK_CURL_STDIN="$explicit_root_case/curl-stdin" \
    MESH_ROOT=".mesh/remotes" \
    bash "$SYNC_SCRIPT" mesh.json > stdout 2> stderr
)
[ -f "$explicit_root_outside/remotes/remote-api/SUMMARY.md" ] ||
  fail "explicit symlinked MESH_ROOT did not honor the caller's trust decision"
[ -f "$explicit_root_case/curl-args" ] || fail "explicit symlinked MESH_ROOT did not reach curl"
echo "ok - explicit MESH_ROOT may trust a symlinked external root"

symlink_case="$WORK_DIR/symlink-escape"
mkdir -p -- "$symlink_case/.mesh/remotes" "$WORK_DIR/outside"
ln -s "$WORK_DIR/outside" "$symlink_case/.mesh/remotes/escape"
make_mesh "$symlink_case" ".mesh/remotes/escape/child"
run_sync "$symlink_case"
[ ! -e "$symlink_case/curl-args" ] || fail "symlink escape reached curl"
[ ! -e "$WORK_DIR/outside/child" ] || fail "symlink escape wrote outside mesh root"
echo "ok - rejects symlink ancestor escape"

success_case="$WORK_DIR/success"
mkdir -p -- "$success_case"
make_mesh "$success_case" ".mesh/remotes/remote-api" "bearer"
token='value;$(touch '"$success_case"'/token-executed); "quoted" & | < > * ?'
(
  cd "$success_case"
  unset MESH_ROOT
  PATH="$WORK_DIR/bin:$PATH" \
    MOCK_CURL_ARGS="$success_case/curl-args" \
    MOCK_CURL_STDIN="$success_case/curl-stdin" \
    REMOTE_API_TOKEN="$token" \
    bash "$SYNC_SCRIPT" mesh.json > stdout 2> stderr
)

[ -f "$success_case/.mesh/remotes/remote-api/SUMMARY.md" ] ||
  fail "safe .mesh/remotes target did not sync"
[ ! -e "$success_case/token-executed" ] || fail "bearer token was evaluated by the shell"
if grep -Fq -- "$token" "$success_case/curl-args"; then
  fail "bearer token appeared in curl argv"
fi
printf 'Authorization: Bearer %s\n' "$token" > "$success_case/expected-header"
cmp -s "$success_case/expected-header" "$success_case/curl-stdin" ||
  fail "bearer token was not passed through curl stdin"
echo "ok - safe target syncs and bearer token stays off argv"

init_case="$WORK_DIR/init"
mkdir -p -- "$init_case" "$WORK_DIR/init-outside"
ln -s "$WORK_DIR/init-outside" "$init_case/linked-squad"
cat > "$init_case/mesh.json" <<'EOF'
{
  "squads": {
    "safe-squad": { "zone": "remote-trusted" },
    "..": { "zone": "remote-trusted" },
    "nested/name": { "zone": "remote-trusted" },
    "linked-squad": { "zone": "remote-trusted" }
  }
}
EOF
(
  cd "$init_case"
  PATH="$WORK_DIR/bin:$PATH" bash "$SYNC_SCRIPT" --init mesh.json > stdout 2> stderr
)
[ -f "$init_case/safe-squad/SUMMARY.md" ] || fail "safe init squad key was not created"
[ ! -e "$init_case/nested" ] || fail "nested init squad key created directories"
[ ! -e "$WORK_DIR/SUMMARY.md" ] || fail "parent init squad key escaped state root"
[ ! -e "$WORK_DIR/init-outside/SUMMARY.md" ] || fail "init squad symlink escaped state root"
echo "ok - init accepts only safe single-segment squad keys"
