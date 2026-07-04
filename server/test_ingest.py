#!/usr/bin/env python3
"""test_ingest.py — host tests for the crowd-feed broker's consensus logic.

Exercises ingest.ingest() (pure, no I/O) directly. Run: python3 server/test_ingest.py
(exit 0 = all pass). Covers: promote-at-threshold, per-reporter dedup, anonymous-flood cap,
malformed-fingerprint reject, already-published no-op, most-reported-number selection.
Status: these are the checks behind the "host-tested" claim in server/README.md.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ingest import ingest  # noqa: E402

FP = "9f3a1c0000000001"
NOW = 1_700_000_000


def rep(**kw):
    kw.setdefault("content_fp", FP)
    return kw


def run(reports, threshold=3, feed=None, staging=None):
    feed, staging = feed if feed is not None else [], staging if staging is not None else {}
    last = ""
    for r in reports:
        feed, staging, last = ingest(r, feed, staging, threshold, NOW)
    return feed, staging, last


def test_promote_at_threshold():
    feed, staging, last = run([
        rep(reporter_id="r1", sender_number="13602182008"),
        rep(reporter_id="r2", sender_number="14045551234"),
        rep(reporter_id="r3", sender_number="13602182008"),
    ])
    assert len(feed) == 1 and feed[0]["content_fp"] == FP, feed
    assert feed[0]["count"] == 3
    # most-reported number wins (13602182008 reported twice)
    assert feed[0]["sender_number"] == "13602182008", feed[0]
    assert FP not in staging, "promoted fp must leave staging"
    assert "PROMOTE" in last


def test_reporter_dedup_no_double_vote():
    feed, staging, _ = run([rep(reporter_id="r1"), rep(reporter_id="r1"), rep(reporter_id="r1")])
    assert feed == [], "one reporter can't reach threshold by resubmitting"
    assert len(staging[FP]["reporters"]) == 1


def test_anonymous_flood_capped():
    feed, staging, _ = run([rep(), rep(), rep(), rep()])  # 4 anon reports, no reporter_id
    assert feed == [], "anonymous reports must never publish alone"
    assert staging[FP]["reporters"] == ["anon"]


def test_malformed_fp_rejected():
    for bad in ["NOTHEX", "", "9f3a1c", "9f3a1c00000000012", "ZZZ"]:
        try:
            ingest(rep(content_fp=bad, reporter_id="r1"), [], {}, 3, NOW)
        except ValueError:
            continue
        raise AssertionError(f"expected reject for {bad!r}")


def test_already_published_is_noop():
    feed = [{"content_fp": FP, "sender_number": "", "first_seen_unix": NOW, "count": 3}]
    feed2, staging, last = ingest(rep(reporter_id="r9"), feed, {}, 3, NOW)
    assert feed2 == feed and staging == {} and "noop" in last


def test_threshold_two():
    feed, _, last = run([rep(reporter_id="a"), rep(reporter_id="b")], threshold=2)
    assert len(feed) == 1 and "PROMOTE" in last


if __name__ == "__main__":
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    for t in tests:
        t()
        print(f"ok  {t.__name__}")
    print(f"\n{len(tests)} passed")
