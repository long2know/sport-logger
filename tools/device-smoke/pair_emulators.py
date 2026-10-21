#!/usr/bin/env python3
"""Pair caller-owned emulators using Android Studio's published Wear protocol."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from dataclasses import dataclass
from typing import Callable

SERIAL = re.compile(r"emulator-[0-9]{4,5}\Z")
NODE = r"[A-Za-z0-9_.-]{1,64}"
COMPANIONS = {
    "com.google.android.apps.wear.companion",
    "com.google.android.wearable.app",
}
LEGACY_COMPANION = "com.google.android.wearable.app"
BROADCAST = "com.google.android.gms.wearable.EMULATOR"


class PairingError(RuntimeError):
    pass


@dataclass(frozen=True)
class Configuration:
    phone: str
    watch: str
    host_port: int = 5602
    timeout: int = 60
    watch_avd: str = ""

    def validate(self) -> None:
        if not SERIAL.fullmatch(self.phone) or not SERIAL.fullmatch(self.watch):
            raise PairingError("Only explicit caller-owned emulator serials are supported")
        if self.phone == self.watch:
            raise PairingError("Phone and watch must be different emulators")
        if not 1024 <= self.host_port <= 65535:
            raise PairingError("Host port must be between 1024 and 65535")
        if not 1 <= self.timeout <= 180:
            raise PairingError("Pairing timeout must be between 1 and 180 seconds")
        if self.watch_avd and not re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", self.watch_avd):
            raise PairingError("Watch AVD name must contain only letters, digits, '.', '_' or '-'")


@dataclass(frozen=True)
class Status:
    local: str
    peers: dict[str, tuple[bool, bool]]

    def connected_to(self, other: "Status") -> bool:
        return self.peers.get(other.local.lower()) == (True, True)


def parse_status(text: str) -> Status:
    local = re.findall(r"Local:\[(" + NODE + r")\]", text)
    if len(local) != 1:
        raise PairingError("Wear pairing status is unavailable; check first-party GMS/companion setup")
    peers = {}
    for node, enabled, connected in re.findall(
        r"Peer:\[(" + NODE + r"),(true|false),(true|false)\]", text, re.IGNORECASE
    ):
        if node.lower() != "null":
            state = (enabled.lower() == "true", connected.lower() == "true")
            if node.lower() in peers and peers[node.lower()] != state:
                raise PairingError("Wear pairing status contains conflicting peer entries")
            peers[node.lower()] = state
    return Status(local[0], peers)


class Adb:
    def __init__(self, executable: str) -> None:
        self.executable = executable

    def __call__(self, arguments: list[str]) -> str:
        try:
            result = subprocess.run(
                [self.executable, *arguments],
                capture_output=True,
                text=True,
                timeout=20,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            raise PairingError("ADB could not execute the bounded device command") from error
        if result.returncode:
            raise PairingError("ADB command failed: " + result.stderr.strip()[:300])
        return result.stdout.strip()


class Pairing:
    def __init__(
        self,
        configuration: Configuration,
        adb: Callable[[list[str]], str],
        clock: Callable[[], float] = time.monotonic,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        configuration.validate()
        self.config = configuration
        self.adb = adb
        self.clock = clock
        self.sleep = sleep
        self.created_forward = False
        self.created_reverse = False

    def device(self, serial: str, *arguments: str) -> str:
        return self.adb(["-s", serial, *arguments])

    def status(self, serial: str) -> Status:
        return parse_status(
            self.device(
                serial, "shell", "am", "broadcast", "-a", BROADCAST,
                "--es", "operation", "get-pairing-status",
            )
        )

    def preflight(self) -> dict[str, str]:
        for serial in (self.config.phone, self.config.watch):
            if self.device(serial, "shell", "getprop", "ro.kernel.qemu") != "1":
                raise PairingError("The selected serial is not an Android emulator")
        watch = self.config.watch
        companion = self.device(
            watch, "shell", "settings", "get", "secure", "cw.oem_companion_package"
        )
        if companion in ("", "null"):
            companion = self.device(watch, "shell", "getprop", "ro.oem.companion_package")
        if companion in ("", "null"):
            companion = LEGACY_COMPANION
        if companion not in COMPANIONS:
            raise PairingError("The watch requires an unsupported companion package")
        installed = self.device(self.config.phone, "shell", "pm", "path", companion)
        if not installed.startswith("package:"):
            raise PairingError(
                "Install/configure the official first-party companion on the test phone: " + companion
            )
        abi = self.device(self.config.phone, "shell", "getprop", "ro.product.cpu.abilist")
        if companion == LEGACY_COMPANION and abi == "x86_64":
            raise PairingError("The legacy companion does not support a 64-bit-only x86 phone image")
        phone_status = self.status(self.config.phone)
        watch_status = self.status(watch)
        return {
            "companion": companion,
            "phone_node": phone_status.local,
            "watch_node": watch_status.local,
        }

    def forward_present(self) -> bool:
        wanted = f"tcp:{self.config.host_port}"
        for line in self.adb(["forward", "--list"]).splitlines():
            row = line.split()
            if len(row) == 3 and row[1] == wanted:
                if row != [self.config.phone, wanted, "tcp:5601"]:
                    raise PairingError("Host port belongs to another ADB forwarding mapping")
                return True
        return False

    def reverse_present(self) -> bool:
        for line in self.device(self.config.watch, "reverse", "--list").splitlines():
            row = line.split()
            if len(row) == 3 and row[1] == "tcp:5601":
                if row[2] != f"tcp:{self.config.host_port}":
                    raise PairingError("The watch reverse port belongs to another mapping")
                return True
        return False

    def refresh(self) -> None:
        self.device(
            self.config.watch, "shell", "am", "broadcast", "-a", BROADCAST,
            "--es", "operation", "refresh-emulator-connection",
        )

    def remove_created(self) -> None:
        failures = []
        for created, serial, operation, port in (
            (self.created_reverse, self.config.watch, "reverse", "tcp:5601"),
            (self.created_forward, self.config.phone, "forward", f"tcp:{self.config.host_port}"),
        ):
            if created:
                try:
                    present = self.reverse_present() if operation == "reverse" else self.forward_present()
                    if present:
                        self.device(serial, operation, "--remove", port)
                except PairingError as error:
                    failures.append(str(error))
        if failures:
            raise PairingError("Owned bridge cleanup failed: " + "; ".join(failures))

    def pair(self) -> dict[str, object]:
        if not self.config.watch_avd:
            raise PairingError("An explicit watch AVD name is required for pairing")
        setup = self.preflight()
        phone, watch = self.status(self.config.phone), self.status(self.config.watch)
        if phone.connected_to(watch) and watch.connected_to(phone):
            return {**setup, "paired": True, "bridge_created": False}
        try:
            if not self.forward_present():
                self.device(
                    self.config.phone, "forward", "--no-rebind",
                    f"tcp:{self.config.host_port}", "tcp:5601",
                )
                self.created_forward = True
            if not self.reverse_present():
                self.device(
                    self.config.watch, "reverse", "--no-rebind",
                    "tcp:5601", f"tcp:{self.config.host_port}",
                )
                self.created_reverse = True
            self.refresh()
            started = self.device(
                self.config.phone, "shell", "am", "start", "-n",
                setup["companion"] + "/.EmulatorActivity",
                "--es", "emulator-name", self.config.watch_avd,
                "--es", "emulator-id", setup["watch_node"],
            )
            if re.search(r"\bError[: ]|\bException\b", started):
                raise PairingError("The official companion EmulatorActivity could not start")
            deadline = self.clock() + self.config.timeout
            while self.clock() < deadline:
                phone, watch = self.status(self.config.phone), self.status(self.config.watch)
                if phone.connected_to(watch) and watch.connected_to(phone):
                    return {**setup, "paired": True, "bridge_created": self.created_forward or self.created_reverse}
                self.sleep(min(1.0, max(0.0, deadline - self.clock())))
            raise PairingError(
                "Pairing did not complete; check official companion consent/setup. No paired success is claimed."
            )
        except (PairingError, KeyboardInterrupt) as error:
            try:
                self.remove_created()
            except PairingError as cleanup:
                message = str(error) or "Pairing interrupted"
                raise PairingError(message + "; " + str(cleanup)) from error
            raise

    def disconnect(self) -> dict[str, bool]:
        forward_present = self.forward_present()
        reverse_present = self.reverse_present()
        if reverse_present:
            self.device(self.config.watch, "reverse", "--remove", "tcp:5601")
        if forward_present:
            self.device(
                self.config.phone, "forward", "--remove", f"tcp:{self.config.host_port}"
            )
        return {"bridge_removed": True, "cloud_disconnection_proven": False}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--phone", required=True)
    parser.add_argument("--watch", required=True)
    parser.add_argument("--watch-avd", default="")
    parser.add_argument("--host-port", type=int, default=5602)
    parser.add_argument("--timeout", type=int, default=60)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--disconnect", action="store_true")
    args = parser.parse_args()
    try:
        pairing = Pairing(
            Configuration(args.phone, args.watch, args.host_port, args.timeout, args.watch_avd),
            Adb(args.adb),
        )
        result = pairing.preflight() if args.check else pairing.disconnect() if args.disconnect else pairing.pair()
        print(json.dumps(result, sort_keys=True))
        return 0
    except PairingError as error:
        print("ERROR: " + str(error), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("ERROR: Pairing interrupted", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
