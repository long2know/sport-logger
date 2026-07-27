#!/bin/bash
# sync-mesh.sh — Materialize remote squad state locally
#
# Reads mesh.json, fetches remote squads into local directories.
# Run before agent reads. No daemon. No service.
#
# Usage: ./sync-mesh.sh [path-to-mesh.json]
#        ./sync-mesh.sh --init [path-to-mesh.json]
# Requires: jq (https://github.com/jqlang/jq), git, curl

set -euo pipefail

INVOCATION_ROOT="$(pwd -P)"
RESOLVED_TARGET=""
DEFAULT_MESH_ROOT=".mesh/remotes"
TEMP_FILES=()

cleanup_temp_files() {
  local temp_file

  for temp_file in "${TEMP_FILES[@]}"; do
    rm -f -- "$temp_file"
  done
}
trap cleanup_temp_files EXIT

is_blank() {
  local value="${1//[[:space:]]/}"
  [ -z "$value" ] || [ "$value" = "null" ]
}

validate_source() {
  local squad="$1"
  local source="$2"

  if is_blank "$source"; then
    echo "⚠ $squad: source is required (skipped)" >&2
    return 1
  fi
}

canonicalize_path() {
  local input="$1"
  local pending
  local resolved="/"
  local component
  local candidate
  local link_target
  local link_count=0

  if [[ "$input" == *$'\n'* || "$input" == *$'\r'* ]]; then
    return 1
  fi

  if [[ "$input" == /* ]]; then
    pending="$input"
  else
    pending="$INVOCATION_ROOT/$input"
  fi

  while [ -n "$pending" ]; do
    while [[ "$pending" == /* ]]; do
      pending="${pending#/}"
    done
    [ -n "$pending" ] || break

    if [[ "$pending" == */* ]]; then
      component="${pending%%/*}"
      pending="${pending#*/}"
    else
      component="$pending"
      pending=""
    fi

    case "$component" in
      ""|.)
        continue
        ;;
      ..)
        if [ "$resolved" != "/" ]; then
          resolved="${resolved%/*}"
          [ -n "$resolved" ] || resolved="/"
        fi
        continue
        ;;
    esac

    candidate="${resolved%/}/$component"
    if [ -L "$candidate" ]; then
      link_count=$((link_count + 1))
      [ "$link_count" -le 40 ] || return 1
      link_target="$(readlink "$candidate")" || return 1

      if [[ "$link_target" == /* ]]; then
        resolved="/"
      fi
      if [ -n "$pending" ]; then
        pending="${link_target%/}/$pending"
      else
        pending="$link_target"
      fi
    else
      resolved="$candidate"
    fi
  done

  printf '%s\n' "$resolved"
}

validate_default_mesh_root() {
  local input="$1"
  local pending="$input"
  local current="$INVOCATION_ROOT"
  local component
  local candidate

  if [[ "$input" == /* || "$input" == *\\* || "$input" == *$'\n'* || "$input" == *$'\r'* ]]; then
    echo "❌ Default MESH_ROOT must be a relative path inside the invocation root" >&2
    return 1
  fi

  while [ -n "$pending" ]; do
    if [[ "$pending" == */* ]]; then
      component="${pending%%/*}"
      pending="${pending#*/}"
    else
      component="$pending"
      pending=""
    fi

    case "$component" in
      ""|.)
        continue
        ;;
      ..)
        echo "❌ Default MESH_ROOT must not contain parent traversal" >&2
        return 1
        ;;
    esac

    candidate="${current%/}/$component"
    if [ -L "$candidate" ]; then
      echo "❌ Default MESH_ROOT must not use symlinked path components: $candidate" >&2
      return 1
    fi
    if [ -e "$candidate" ] && [ ! -d "$candidate" ]; then
      echo "❌ Default MESH_ROOT path components must be real directories: $candidate" >&2
      return 1
    fi
    current="$candidate"
  done

  case "$current" in
    "$INVOCATION_ROOT"/*)
      ;;
    *)
      echo "❌ Default MESH_ROOT must remain inside the invocation root" >&2
      return 1
      ;;
  esac
}

has_dot_path_segment() {
  local padded="/$1/"
  [[ "$padded" == *"/./"* || "$padded" == *"/../"* ]]
}

validate_target() {
  local squad="$1"
  local target="$2"
  local canonical_target

  if is_blank "$target" || has_dot_path_segment "$target" || [[ "$target" == *\\* ]]; then
    echo "⚠ $squad: sync_to must be a strict descendant of $MESH_ROOT_INPUT without traversal (skipped)" >&2
    return 1
  fi

  canonical_target="$(canonicalize_path "$target")" || {
    echo "⚠ $squad: sync_to could not be canonicalized safely (skipped)" >&2
    return 1
  }

  case "$canonical_target" in
    "$MESH_ROOT_RESOLVED"/*)
      RESOLVED_TARGET="$canonical_target"
      ;;
    *)
      echo "⚠ $squad: sync_to escapes mesh root $MESH_ROOT_INPUT (skipped)" >&2
      return 1
      ;;
  esac
}

validate_squad_key() {
  local squad="$1"

  if is_blank "$squad" ||
    [ "$squad" = "." ] ||
    [ "$squad" = ".." ] ||
    [[ "$squad" == */* ]] ||
    [[ "$squad" == *\\* ]] ||
    [[ "$squad" == *[[:cntrl:]]* ]]; then
    echo "⚠ $squad: squad key must be one safe relative path segment (skipped)" >&2
    return 1
  fi
}

validate_state_directory() {
  local squad="$1"

  validate_squad_key "$squad" || return 1
  if [ -L "$squad" ] || { [ -e "$squad" ] && [ ! -d "$squad" ]; }; then
    echo "⚠ $squad: state path must be a real directory in this repository (skipped)" >&2
    return 1
  fi
}

validate_mesh_config() {
  if ! command -v jq >/dev/null 2>&1; then
    echo "❌ jq is required but was not found in PATH" >&2
    return 1
  fi

  if [ ! -f "$MESH_JSON" ]; then
    echo "❌ $MESH_JSON not found" >&2
    return 1
  fi

  if ! jq -e 'type == "object" and (.squads | type == "object")' "$MESH_JSON" >/dev/null 2>&1; then
    echo "❌ $MESH_JSON must be valid JSON with a .squads object" >&2
    return 1
  fi
}

create_secure_temp_file() {
  local result_variable="$1"
  local temp_file

  if ! temp_file="$(mktemp "$INVOCATION_ROOT/.sync-mesh-jq.XXXXXX")"; then
    echo "❌ Unable to create a secure jq output file in $INVOCATION_ROOT" >&2
    return 1
  fi

  TEMP_FILES+=("$temp_file")
  printf -v "$result_variable" '%s' "$temp_file"
}

materialize_jq_records() {
  local destination="$1"
  local description="$2"
  local filter="$3"

  if ! jq -j "$filter" "$MESH_JSON" > "$destination"; then
    echo "❌ Failed to enumerate $description from $MESH_JSON" >&2
    return 1
  fi
}

# Handle --init mode
if [ "${1:-}" = "--init" ]; then
  MESH_JSON="${2:-mesh.json}"
  validate_mesh_config || exit 1

  INIT_RECORDS=""
  create_secure_temp_file INIT_RECORDS || exit 1
  materialize_jq_records \
    "$INIT_RECORDS" \
    "mesh squad keys" \
    '.squads
      | to_entries[]
      | .key, "\u0000",
        ((.value.zone // "") | if type == "string" then . else "" end), "\u0000"' \
    || exit 1

  echo "🚀 Initializing mesh state repository..."

  # Create squad directories with placeholder SUMMARY.md
  while IFS= read -r -d '' squad; do
    if ! IFS= read -r -d '' zone; then
      echo "❌ jq returned an incomplete mesh squad record" >&2
      exit 1
    fi
    if ! validate_state_directory "$squad"; then
      continue
    fi

    if [ ! -d "$squad" ]; then
      mkdir -p -- "$squad"
      echo "  ✓ Created $squad/"
    else
      echo "  • $squad/ exists (skipped)"
    fi

    if [ ! -f "$squad/SUMMARY.md" ]; then
      printf '# %s\n\n_No state published yet._\n' "$squad" > "$squad/SUMMARY.md"
      echo "  ✓ Created $squad/SUMMARY.md"
    else
      echo "  • $squad/SUMMARY.md exists (skipped)"
    fi
  done < "$INIT_RECORDS"

  # Generate root README.md
  if [ ! -f "README.md" ]; then
    {
      echo "# Squad Mesh State Repository"
      echo ""
      echo "This repository tracks published state from participating squads."
      echo ""
      echo "## Participating Squads"
      echo ""
      while IFS= read -r -d '' squad; do
        if ! IFS= read -r -d '' zone; then
          echo "❌ jq returned an incomplete mesh squad record" >&2
          exit 1
        fi
        if ! validate_state_directory "$squad"; then
          continue
        fi
        printf -- '- **%s** (Zone: %s)\n' "$squad" "$zone"
      done < "$INIT_RECORDS"
      echo ""
      echo "Each squad directory contains a \`SUMMARY.md\` with their latest published state."
      echo "State is synchronized using \`sync-mesh.sh\` or \`sync-mesh.ps1\`."
    } > README.md
    echo "  ✓ Created README.md"
  else
    echo "  • README.md exists (skipped)"
  fi

  echo ""
  echo "✅ Mesh state repository initialized"
  exit 0
fi

MESH_JSON="${1:-mesh.json}"
validate_mesh_config || exit 1

MESH_ROOT_EXPLICIT=0
if [ "${MESH_ROOT+x}" = "x" ]; then
  MESH_ROOT_INPUT="$MESH_ROOT"
  MESH_ROOT_EXPLICIT=1
else
  MESH_ROOT_INPUT="$DEFAULT_MESH_ROOT"
fi
if is_blank "$MESH_ROOT_INPUT"; then
  echo "❌ MESH_ROOT must not be blank" >&2
  exit 1
fi

if [ "$MESH_ROOT_EXPLICIT" -eq 0 ]; then
  validate_default_mesh_root "$MESH_ROOT_INPUT" || exit 1
fi

MESH_ROOT_RESOLVED="$(canonicalize_path "$MESH_ROOT_INPUT")" || {
  echo "❌ MESH_ROOT could not be canonicalized safely" >&2
  exit 1
}
if [ "$MESH_ROOT_RESOLVED" = "/" ]; then
  echo "❌ MESH_ROOT must not resolve to the filesystem root" >&2
  exit 1
fi
if [ "$MESH_ROOT_EXPLICIT" -eq 0 ]; then
  case "$MESH_ROOT_RESOLVED" in
    "$INVOCATION_ROOT"/*)
      ;;
    *)
      echo "❌ Default MESH_ROOT must resolve inside the invocation root" >&2
      exit 1
      ;;
  esac
fi

TRUSTED_RECORDS=""
OPAQUE_RECORDS=""
create_secure_temp_file TRUSTED_RECORDS || exit 1
create_secure_temp_file OPAQUE_RECORDS || exit 1
materialize_jq_records \
  "$TRUSTED_RECORDS" \
  "remote-trusted squad keys" \
  '.squads
    | to_entries[]
    | select(.value.zone == "remote-trusted")
    | .key, "\u0000",
      ((.value.source // "") | if type == "string" then . else "" end), "\u0000",
      ((.value.ref // "main") | if type == "string" then . else "" end), "\u0000",
      ((.value.sync_to // "") | if type == "string" then . else "" end), "\u0000"' \
  || exit 1
materialize_jq_records \
  "$OPAQUE_RECORDS" \
  "remote-opaque squad keys" \
  '.squads
    | to_entries[]
    | select(.value.zone == "remote-opaque")
    | .key, "\u0000",
      ((.value.source // "") | if type == "string" then . else "" end), "\u0000",
      ((.value.sync_to // "") | if type == "string" then . else "" end), "\u0000",
      ((.value.auth // "") | if type == "string" then . else "" end), "\u0000"' \
  || exit 1

# Zone 2: Remote-trusted — git clone/pull
while IFS= read -r -d '' squad; do
  if ! IFS= read -r -d '' source ||
    ! IFS= read -r -d '' ref ||
    ! IFS= read -r -d '' target; then
    echo "❌ jq returned an incomplete remote-trusted squad record" >&2
    exit 1
  fi

  if ! validate_source "$squad" "$source" || ! validate_target "$squad" "$target"; then
    continue
  fi
  target="$RESOLVED_TARGET"
  if is_blank "$ref"; then
    ref="main"
  fi

  if [ -d "$target/.git" ]; then
    git -C "$target" pull --rebase --quiet 2>/dev/null \
      || echo "⚠ $squad: pull failed (using stale)"
  else
    mkdir -p -- "$(dirname -- "$target")"
    git clone --quiet --depth 1 --branch "$ref" -- "$source" "$target" 2>/dev/null \
      || echo "⚠ $squad: clone failed (unavailable)"
  fi
done < "$TRUSTED_RECORDS"

# Zone 3: Remote-opaque — fetch published contracts
while IFS= read -r -d '' squad; do
  if ! IFS= read -r -d '' source ||
    ! IFS= read -r -d '' target ||
    ! IFS= read -r -d '' auth; then
    echo "❌ jq returned an incomplete remote-opaque squad record" >&2
    exit 1
  fi

  if ! validate_source "$squad" "$source" || ! validate_target "$squad" "$target"; then
    continue
  fi
  target="$RESOLVED_TARGET"

  mkdir -p -- "$target"
  summary_path="$target/SUMMARY.md"
  curl_args=(curl --silent --fail --output "$summary_path")
  token=""

  if [ "$auth" = "bearer" ]; then
    token_var="$(printf '%s' "$squad" | tr '[:lower:]-' '[:upper:]_')_TOKEN"
    if [[ "$token_var" =~ ^[A-Z_][A-Z0-9_]*$ ]]; then
      token="$(printenv "$token_var" 2>/dev/null || true)"
    fi
  fi

  if [[ "$token" == *$'\r'* || "$token" == *$'\n'* ]]; then
    echo "⚠ $squad: bearer token contains invalid characters (skipped)" >&2
    printf '# %s — unavailable (%s)\n' "$squad" "$(date)" > "$summary_path"
    continue
  fi

  if [ -n "$token" ]; then
    curl_args+=(--header @-)
    if ! printf 'Authorization: Bearer %s\n' "$token" | "${curl_args[@]}" -- "$source" 2>/dev/null; then
      printf '# %s — unavailable (%s)\n' "$squad" "$(date)" > "$summary_path"
    fi
  elif ! "${curl_args[@]}" -- "$source" 2>/dev/null; then
    printf '# %s — unavailable (%s)\n' "$squad" "$(date)" > "$summary_path"
  fi
done < "$OPAQUE_RECORDS"

echo "✓ Mesh sync complete"
