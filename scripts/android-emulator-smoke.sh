#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <adb-serial> <apk-path>" >&2
  exit 2
fi

serial=$1
apk_path=$2
adb_bin=${ADB:-adb}
package_name=com.long2know.sportlogger
activity_name=.MainActivity

if [[ ! -f "$apk_path" ]]; then
  echo "APK not found: $apk_path" >&2
  exit 2
fi

"$adb_bin" -s "$serial" wait-for-device
"$adb_bin" -s "$serial" install -r -d "$apk_path"
"$adb_bin" -s "$serial" logcat -c

launch_output=$(
  "$adb_bin" -s "$serial" shell am start -W -S \
    -n "$package_name/$activity_name"
)
printf '%s\n' "$launch_output"

if ! grep -q '^Status: ok$' <<<"$launch_output"; then
  echo "Activity launch did not report Status: ok" >&2
  exit 1
fi

sleep 5
pid=$("$adb_bin" -s "$serial" shell pidof "$package_name" | tr -d '\r')
if [[ -z "$pid" ]]; then
  echo "App process exited after launch" >&2
  exit 1
fi

process_log=$("$adb_bin" -s "$serial" logcat --pid="$pid" -d -v brief)
if grep -E 'FATAL EXCEPTION|Process: com\.long2know\.sportlogger.*has died' <<<"$process_log"; then
  echo "Fatal app log detected after launch" >&2
  exit 1
fi

echo "Launch smoke passed for $apk_path on $serial (pid $pid)."
