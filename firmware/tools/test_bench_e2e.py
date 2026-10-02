# Self-test of bench_e2e's verdict on sample serial lines: python tools/bench_e2e.py --self-test (no bench needed).
import unittest

import bench_e2e as bench

FIRST_DIAG_AT_S = 6
DIAG_PERIOD_S = 5
BANNER = "blindside fw 0.2.0 boot_id=9f3a12c4 reset=POWERON"
ACL_LINE = "ble: controller acl buffers=10"
FULL_BOOT = bench.boot_facts([BANNER, ACL_LINE])


def watch(sent):
    return ("watch", sent)


def phone(sent):
    return ("phone", sent)


def link_text(slot, stream):
    if stream is None:
        return f"link{slot}[-]"
    role, sent = stream
    return f"link{slot}[role={role} trusted=1 sub=1 mtu=255 itvl=36 lat=0 sup=400 sent={sent} dropped=0]"


def diag_text(slot0=None, slot1=None):
    links = " ".join(link_text(slot, stream) for slot, stream in enumerate((slot0, slot1)))
    return f"diag up=60s {links} bonds=1 pair=0 disc=531 acl=10 hstk=2900 tx[fail=0 dropped=0 skipped=0]"


def run_of(*lines):
    return [bench.parse_diag(diag_text(*links), FIRST_DIAG_AT_S + DIAG_PERIOD_S * index)
            for index, links in enumerate(lines)]


def verdict(diags, check, boot=bench.NO_BOOT, boot_expected=False, expect_fw=None):
    return bench.verdict(diags, boot, check, expect_fw, boot_expected)


STEADY_WATCH = run_of((watch(10),), (watch(65),), (watch(120),))


class WatchStreamTest(unittest.TestCase):
    def test_a_watch_streaming_the_whole_run_passes(self):
        self.assertEqual([], verdict(STEADY_WATCH, "watch"))
        self.assertEqual([], verdict(STEADY_WATCH, "watch-steady"))

    def test_fewer_than_three_diag_lines_fail(self):
        self.assertEqual(["fewer than 3 diag lines in 0.2.0 format"], verdict(STEADY_WATCH[:1], "watch"))

    def test_a_watch_that_drops_and_never_returns_fails(self):
        diags = run_of((watch(10),), (watch(65),), (watch(120),), (), ())
        self.assertIn("watch stopped streaming", verdict(diags, "watch"))
        self.assertIn("watch stopped streaming", verdict(diags, "watch-steady"))

    def test_a_watch_that_loses_its_subscription_fails(self):
        diags = run_of((watch(10),), (watch(65),), (watch(120),))
        diags[-1]["links"][0]["sub"] = False
        self.assertIn("watch stopped streaming", verdict(diags, "watch-steady"))

    def test_a_watch_that_reconnects_in_the_other_slot_fails(self):
        diags = run_of((watch(10),), (watch(65),), (None, watch(20)), (None, watch(75)))
        self.assertEqual(["watch stopped streaming"], verdict(diags, "watch-steady"))

    def test_a_watch_that_reconnects_in_the_same_slot_fails(self):
        diags = run_of((watch(10),), (watch(65),), (watch(30),), (watch(85),))
        self.assertEqual(["watch stopped streaming"], verdict(diags, "watch-steady"))

    def test_a_watch_that_connects_late_and_then_stays_passes(self):
        diags = run_of((), (), (watch(10),), (watch(65),), (watch(120),))
        self.assertEqual([], verdict(diags, "watch"))

    def test_a_watch_that_never_streams_fails_without_claiming_it_stopped(self):
        missing = verdict(run_of((), (), ()), "watch")
        self.assertIn("watch streaming within 30 s", missing)
        self.assertIn("watch streaming in 2+ diag lines", missing)
        self.assertNotIn("watch stopped streaming", missing)

    def test_a_watch_streaming_only_in_the_last_line_fails(self):
        self.assertEqual(["watch streaming in 2+ diag lines"], verdict(run_of((), (), (watch(10),)), "watch-steady"))


class BothLinksTest(unittest.TestCase):
    def test_watch_and_phone_streaming_the_whole_run_pass(self):
        diags = run_of((watch(10), phone(8)), (watch(65), phone(60)), (watch(120), phone(115)))
        self.assertEqual([], verdict(diags, "both"))

    def test_a_phone_that_drops_and_never_returns_fails(self):
        diags = run_of((watch(10), phone(8)), (watch(65), phone(60)), (watch(120),), (watch(175),))
        self.assertEqual(["phone stopped streaming"], verdict(diags, "both"))


class BootLinesTest(unittest.TestCase):
    def test_a_reset_run_with_both_boot_lines_passes(self):
        self.assertEqual([], verdict(STEADY_WATCH, "watch", FULL_BOOT, True, "0.2.0"))

    def test_a_reset_run_without_the_acl_boot_line_fails(self):
        boot = bench.boot_facts([BANNER])
        self.assertEqual(["missing boot line 'ble: controller acl buffers='"],
                         verdict(STEADY_WATCH, "watch", boot, True, "0.2.0"))

    def test_a_reset_run_without_the_banner_fails(self):
        boot = bench.boot_facts([ACL_LINE])
        self.assertEqual(["missing boot line 'blindside fw'"], verdict(STEADY_WATCH, "watch", boot, True))

    def test_a_run_without_reset_does_not_need_the_boot_lines(self):
        self.assertEqual([], verdict(STEADY_WATCH, "watch-steady"))

    def test_too_few_controller_buffers_fail(self):
        boot = bench.boot_facts([BANNER, "ble: controller acl buffers=6"])
        self.assertEqual(["controller acl buffers >= 8"], verdict(STEADY_WATCH, "watch", boot, True, "0.2.0"))

    def test_another_firmware_version_fails(self):
        self.assertEqual(["firmware 0.3.0"], verdict(STEADY_WATCH, "watch", FULL_BOOT, True, "0.3.0"))


if __name__ == "__main__":
    unittest.main()
