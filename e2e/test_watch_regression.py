import unittest

from watch_regression import Reading, WatchLink, verdict, watch_link_in

DUAL = (
    "diag up=40s radar0[alive=1] imu0[ok=1] "
    "link0[role=watch trusted=1 sub=1 mtu=255 itvl=36 lat=0 sup=400 sent=1234 dropped=0] link1[-] bonds=1 disc=19 "
    "tx[fail=0 dropped=0 skipped=0]"
)
MVP = (
    "diag up=40s radar0[alive=1] imu0[ok=1] "
    "link[conn=1 sub=1 trusted=1 mtu=255 itvl=36 lat=0 sup=400 disc=19] tx[sent=812 fail=0 dropped=0 skipped=0]"
)


def good(seconds, sent):
    return Reading(seconds, WatchLink(trusted=True, subscribed=True, sent=sent))


class WatchLinkTest(unittest.TestCase):
    def test_the_dual_link_format_is_read(self):
        self.assertEqual(WatchLink(True, True, 1234), watch_link_in(DUAL))

    def test_the_mvp_link_format_is_read(self):
        self.assertEqual(WatchLink(True, True, 812), watch_link_in(MVP))

    def test_lines_without_a_watch_link_are_skipped(self):
        self.assertIsNone(watch_link_in("diag up=5s link0[-] link1[-] bonds=1 tx[fail=0 dropped=0 skipped=0]"))
        self.assertIsNone(watch_link_in("diag up=5s link0[role=phone trusted=1 sub=1 sent=9 dropped=0] link1[-]"))
        self.assertIsNone(watch_link_in("blindside passkey: 123456"))


class VerdictTest(unittest.TestCase):
    def test_a_trusted_link_with_rising_sent_passes(self):
        self.assertTrue(verdict([good(12.0, 10), good(17.0, 60)]).startswith("PASS"))

    def test_a_link_that_comes_up_after_30_seconds_fails(self):
        self.assertTrue(verdict([good(31.0, 10), good(36.0, 60)]).startswith("FAIL"))

    def test_a_link_that_never_sends_more_fails(self):
        self.assertTrue(verdict([good(12.0, 10), good(17.0, 10)]).startswith("FAIL"))

    def test_an_untrusted_or_unsubscribed_link_fails(self):
        readings = [Reading(5.0, WatchLink(False, True, 10)), Reading(10.0, WatchLink(True, False, 20))]
        self.assertTrue(verdict(readings).startswith("FAIL"))


if __name__ == "__main__":
    unittest.main()
