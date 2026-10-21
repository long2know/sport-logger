import unittest

from pair_emulators import Configuration, Pairing, PairingError, parse_status


PHONE = "emulator-5592"
WATCH = "emulator-5594"


class FakeAdb:
    def __init__(self, *, installed=True, legacy=False, never_connect=False, existing=False, reverse_fails=False):
        self.installed = installed
        self.legacy = legacy
        self.never_connect = never_connect
        self.forward = existing
        self.reverse = existing
        self.reverse_fails = reverse_fails
        self.started = False
        self.calls = []

    def __call__(self, args):
        self.calls.append(args)
        if args == ["forward", "--list"]:
            return f"{PHONE} tcp:5602 tcp:5601" if self.forward else ""
        serial, tail = args[1], args[2:]
        if tail == ["shell", "getprop", "ro.kernel.qemu"]:
            return "1"
        if tail[:4] == ["shell", "settings", "get", "secure"]:
            return "com.google.android.wearable.app" if self.legacy else "com.google.android.apps.wear.companion"
        if tail[:3] == ["shell", "pm", "path"]:
            return "package:/test/companion.apk" if self.installed else ""
        if tail == ["shell", "getprop", "ro.product.cpu.abilist"]:
            return "x86_64"
        if tail[-1:] == ["get-pairing-status"]:
            node, peer = ("phone", "watch") if serial == PHONE else ("watch", "phone")
            connected = "true" if self.started and not self.never_connect else "false"
            return f"Local:[{node}]\nPeer:[{peer},true,{connected}]"
        if tail == ["reverse", "--list"]:
            return f"{WATCH} tcp:5601 tcp:5602" if self.reverse else ""
        if tail[:2] == ["forward", "--no-rebind"]:
            self.forward = True
            return ""
        if tail[:2] == ["reverse", "--no-rebind"]:
            if self.reverse_fails:
                raise PairingError("reverse failed")
            self.reverse = True
            return ""
        if tail[:2] == ["reverse", "--remove"]:
            self.reverse = False
            return ""
        if tail[:2] == ["forward", "--remove"]:
            self.forward = False
            return ""
        if tail[-1:] == ["refresh-emulator-connection"]:
            return "Broadcast completed"
        if tail[:4] == ["shell", "am", "start", "-n"]:
            self.started = True
            return "Starting: Intent"
        raise AssertionError(args)


class FakeClock:
    def __init__(self):
        self.value = 0.0

    def now(self):
        return self.value

    def sleep(self, seconds):
        self.value += seconds


class PairingTests(unittest.TestCase):
    def controller(self, adb, **kwargs):
        clock = FakeClock()
        config = Configuration(PHONE, WATCH, timeout=3, watch_avd="owned-watch", **kwargs)
        return Pairing(config, adb, clock.now, clock.sleep)

    def test_rejects_physical_or_ambiguous_devices(self):
        for phone, watch in (("physical-serial", WATCH), (PHONE, PHONE), ("emulator-5592;bad", WATCH)):
            with self.subTest(phone=phone, watch=watch), self.assertRaises(PairingError):
                Configuration(phone, watch).validate()

    def test_missing_companion_does_not_create_a_bridge(self):
        adb = FakeAdb(installed=False)
        with self.assertRaisesRegex(PairingError, "official first-party"):
            self.controller(adb).pair()
        self.assertFalse(adb.forward)
        self.assertFalse(adb.reverse)

    def test_legacy_companion_rejects_64_bit_only_phone(self):
        with self.assertRaisesRegex(PairingError, "64-bit-only"):
            self.controller(FakeAdb(legacy=True)).preflight()

    def test_success_requires_actual_bidirectional_peer_status(self):
        adb = FakeAdb()
        result = self.controller(adb).pair()
        self.assertTrue(result["paired"])
        self.assertTrue(adb.forward)
        self.assertTrue(adb.reverse)
        commands = " ".join(" ".join(call) for call in adb.calls)
        self.assertNotIn("force-stop", commands)
        self.assertNotIn("pm clear", commands)
        self.assertNotIn("kill-server", commands)
        self.assertIn(".EmulatorActivity", commands)

    def test_timeout_removes_only_mappings_this_run_created(self):
        adb = FakeAdb(never_connect=True)
        with self.assertRaisesRegex(PairingError, "No paired success"):
            self.controller(adb).pair()
        self.assertFalse(adb.forward)
        self.assertFalse(adb.reverse)

    def test_timeout_keeps_preexisting_matching_bridge(self):
        adb = FakeAdb(existing=True, never_connect=True)
        with self.assertRaises(PairingError):
            self.controller(adb).pair()
        self.assertTrue(adb.forward)
        self.assertTrue(adb.reverse)
        self.assertFalse(any("--remove" in call for call in adb.calls))

    def test_partial_bridge_failure_cleans_forward(self):
        adb = FakeAdb(reverse_fails=True)
        with self.assertRaisesRegex(PairingError, "reverse failed"):
            self.controller(adb).pair()
        self.assertFalse(adb.forward)

    def test_disconnect_does_not_claim_cloud_is_offline(self):
        adb = FakeAdb(existing=True)
        result = self.controller(adb).disconnect()
        self.assertFalse(adb.forward)
        self.assertFalse(adb.reverse)
        self.assertFalse(result["cloud_disconnection_proven"])

    def test_unrelated_forward_is_never_removed(self):
        adb = FakeAdb()
        original = adb.__call__

        def conflicting(args):
            if args == ["forward", "--list"]:
                return "emulator-5580 tcp:5602 tcp:9000"
            return original(args)

        with self.assertRaisesRegex(PairingError, "another ADB"):
            self.controller(conflicting).pair()
        self.assertFalse(any("--remove" in call for call in adb.calls))

    def test_disconnect_validates_both_mappings_before_mutating(self):
        adb = FakeAdb(existing=True)
        original = adb.__call__

        def conflicting(args):
            if args == ["forward", "--list"]:
                return "emulator-5580 tcp:5602 tcp:5601"
            return original(args)

        with self.assertRaises(PairingError):
            self.controller(conflicting).disconnect()
        self.assertTrue(adb.reverse)
        self.assertFalse(any("--remove" in call for call in adb.calls))

    def test_cleanup_does_not_remove_a_reassigned_forward(self):
        adb = FakeAdb()
        original = adb.__call__

        def reassigned(args):
            if args == ["forward", "--list"]:
                return "emulator-5580 tcp:5602 tcp:5601"
            return original(args)

        controller = self.controller(reassigned)
        controller.created_forward = True
        with self.assertRaisesRegex(PairingError, "cleanup failed"):
            controller.remove_created()
        self.assertFalse(any("--remove" in call for call in adb.calls))

    def test_interrupt_cleans_only_new_bridge_mappings(self):
        adb = FakeAdb()
        original = adb.__call__

        def interrupted(args):
            if args[2:6] == ["shell", "am", "start", "-n"]:
                raise KeyboardInterrupt
            return original(args)

        with self.assertRaises(KeyboardInterrupt):
            self.controller(interrupted).pair()
        self.assertFalse(adb.forward)
        self.assertFalse(adb.reverse)

    def test_status_requires_matching_enabled_connected_peer(self):
        status = parse_status("Local:[watch]\nPeer:[phone,false,true]")
        self.assertFalse(status.connected_to(parse_status("Local:[phone]")))
        with self.assertRaises(PairingError):
            parse_status("Broadcast completed: result=0")
        with self.assertRaisesRegex(PairingError, "conflicting"):
            parse_status("Local:[watch]\nPeer:[phone,true,true]\nPeer:[phone,false,false]")


if __name__ == "__main__":
    unittest.main()
