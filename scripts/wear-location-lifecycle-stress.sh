#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "Usage: $0 <adb-serial> <wear-apk-path> [cycles]" >&2
  exit 2
fi

serial=$1
apk_path=$2
cycles=${3:-20}
adb_bin=${ADB:-adb}
package_name=com.long2know.sportlogger
activity_name=.MainActivity

if [[ ! -f "$apk_path" ]]; then
  echo "APK not found: $apk_path" >&2
  exit 2
fi
if ! [[ "$cycles" =~ ^[1-9][0-9]*$ ]]; then
  echo "Cycles must be a positive integer: $cycles" >&2
  exit 2
fi

set_location_enabled() {
  "$adb_bin" -s "$serial" shell cmd location set-location-enabled "$1"
}

count_app_location_listeners() {
  "$adb_bin" -s "$serial" shell dumpsys location |
    awk '/^[[:space:]]*[0-9]+\/com\.long2know\.sportlogger\/.*Request\[/{count++} END{print count+0}'
}

cleanup() {
  set_location_enabled true >/dev/null 2>&1 || true
}
trap cleanup EXIT

"$adb_bin" -s "$serial" wait-for-device
"$adb_bin" -s "$serial" install -r -d "$apk_path"
"$adb_bin" -s "$serial" shell pm clear "$package_name"

permissions=(
  android.permission.ACCESS_COARSE_LOCATION
  android.permission.ACCESS_FINE_LOCATION
  android.permission.ACTIVITY_RECOGNITION
  android.permission.BODY_SENSORS
  android.permission.BODY_SENSORS_BACKGROUND
  android.permission.health.READ_HEART_RATE
  android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND
  android.permission.POST_NOTIFICATIONS
)
for permission in "${permissions[@]}"; do
  "$adb_bin" -s "$serial" shell pm grant "$package_name" "$permission"
done

"$adb_bin" -s "$serial" shell input keyevent KEYCODE_WAKEUP
"$adb_bin" -s "$serial" shell wm dismiss-keyguard
set_location_enabled true
"$adb_bin" -s "$serial" shell am start -W \
  -n "$package_name/$activity_name" >/dev/null
sleep 1
"$adb_bin" -s "$serial" logcat -c

initial_pid=$("$adb_bin" -s "$serial" shell pidof "$package_name" | tr -d '\r')
if [[ -z "$initial_pid" ]]; then
  echo "Wear app process did not start" >&2
  exit 1
fi

minimum_listeners=999
maximum_listeners=0
for cycle in $(seq 1 "$cycles"); do
  "$adb_bin" -s "$serial" shell am start -W \
    -n "$package_name/$activity_name" >/dev/null
  sleep 0.4

  listener_count=$(count_app_location_listeners)
  (( listener_count < minimum_listeners )) && minimum_listeners=$listener_count
  (( listener_count > maximum_listeners )) && maximum_listeners=$listener_count
  if (( listener_count != 1 )); then
    echo "Expected one active location listener at cycle $cycle; found $listener_count" >&2
    exit 1
  fi

  set_location_enabled false
  set_location_enabled true
  set_location_enabled false
  sleep 0.05
  "$adb_bin" -s "$serial" shell input keyevent KEYCODE_BACK
  sleep 0.4
  set_location_enabled true
done

"$adb_bin" -s "$serial" shell am start -W \
  -n "$package_name/$activity_name" >/dev/null
sleep 0.5

final_pid=$("$adb_bin" -s "$serial" shell pidof "$package_name" | tr -d '\r')
process_log=$("$adb_bin" -s "$serial" logcat -d -v brief)
stopped_count=$(grep -c 'Location route listener stopped' <<<"$process_log" || true)

if [[ "$initial_pid" != "$final_pid" ]]; then
  echo "Wear app process restarted: $initial_pid -> $final_pid" >&2
  exit 1
fi
if (( stopped_count < cycles )); then
  echo "Expected at least $cycles completed listener teardowns; found $stopped_count" >&2
  exit 1
fi
if grep -E \
  'Receiver not registered|IntentReceiverLeaked|Service not registered|FATAL EXCEPTION|Process: com\.long2know\.sportlogger.*has died' \
  <<<"$process_log"; then
  echo "Lifecycle failure found in Wear stress log" >&2
  exit 1
fi

echo "Wear lifecycle stress passed: pid $initial_pid, cycles $cycles, listeners $minimum_listeners/$maximum_listeners, teardowns $stopped_count."
