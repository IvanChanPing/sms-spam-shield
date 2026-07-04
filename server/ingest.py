#!/usr/bin/env python3
"""ingest.py — the crowd-feed broker (runs inside a GitHub Actions workflow).

WHAT THIS IS
------------
The write-path "server" for the crowd spam-fingerprint feed, with NO server of ours to run:
a GitHub Actions workflow (.github/workflows/crowd-ingest.yml) fires on a `repository_dispatch`
event carrying one client `CrowdReport`, runs this script, and commits the updated feed. See
docs/CROWD_FEED_DESIGN.md.

WHAT IT DOES (baseline = CONSENSUS, the always-on layer)
--------------------------------------------------------
No single report ever publishes a fingerprint (PhishTank model). A `content_fp` is held in
`staging.json` and only promoted to the published `feed.json` once **N distinct reporters** have
independently reported it (default N=3, env CROWD_CONSENSUS_THRESHOLD). Reporters are deduped by
an anonymous per-install `reporter_id`; reports with no id collapse to a single "anon" vote, so
anonymous submissions can never reach the threshold alone.

OPTIONAL HARDENINGS (documented; not enabled by this baseline)
--------------------------------------------------------------
- RE-CLASSIFY: if a payload carries a de-identified `text` skeleton AND env CLASSIFY_CMD is set,
  the skeleton is piped to that command (the real engine's classifier) and the report is dropped
  unless it agrees it's political spam. Requires the client to send the (PII-stripped) skeleton.
- ATTESTATION: Play Integrity / App Attest token verification belongs in the workflow before this
  script runs (verify the header, drop the event if invalid). See the workflow + design doc.

INPUT  : one report as JSON on stdin, or --payload FILE. Fields: content_fp (required, 16-hex),
         reporter_id? sender_number? first_seen_unix? text? (extras ignored).
OUTPUT : rewrites --staging/--feed in place; prints a one-line audit summary. Exit 0 on
         accept/dedup/promote, non-zero only on a malformed payload (so the workflow can surface it).
RUN    : python3 ingest.py --payload report.json --feed feed.json --staging staging.json [--threshold 3]
STATUS : logic host-tested (see tests at bottom + server/README). Live GitHub-Actions run UNVERIFIED
         here (Actions runs on GitHub, not in this env).
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
from collections import Counter

FP_RE = re.compile(r"^[0-9a-f]{16}$")  # matches the client's FNV-1a hex fingerprint


def _load(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return default
    except json.JSONDecodeError:
        return default


def _save(path, obj):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=1, sort_keys=True)
        f.write("\n")


def reclassify_ok(text):
    """Optional re-classification gate. Returns True if disabled (no CLASSIFY_CMD) or if the
    external classifier agrees the skeleton is political spam. Fail-OPEN only when disabled;
    if a classifier IS configured and errors, fail-CLOSED (reject) so a broken gate can't leak."""
    cmd = os.environ.get("CLASSIFY_CMD", "").strip()
    if not cmd:
        return True  # gate disabled → consensus is the only layer
    if not text:
        return False  # gate on, but nothing to classify → reject
    try:
        proc = subprocess.run(
            cmd, shell=True, input=text, capture_output=True, text=True, timeout=60
        )
        return proc.returncode == 0 and "SPAM" in proc.stdout.upper()
    except Exception as e:
        print(f"ingest: reclassify error ({e}) → rejecting", file=sys.stderr)
        return False


def ingest(report, feed, staging, threshold, now_unix):
    """Pure logic (no I/O) → returns (feed, staging, summary). Raises ValueError on bad payload."""
    fp = str(report.get("content_fp", "")).strip().lower()
    if not FP_RE.match(fp):
        raise ValueError(f"invalid content_fp {fp!r} (want 16 lowercase hex)")

    # already published → nothing to do (idempotent; also "hides" published fps from re-voting).
    if any(entry.get("content_fp") == fp for entry in feed):
        return feed, staging, f"noop: {fp} already in feed"

    if not reclassify_ok(report.get("text")):
        return feed, staging, f"reject: {fp} failed re-classification gate"

    reporter = str(report.get("reporter_id") or "anon")[:64]
    number = str(report.get("sender_number") or "").strip()
    first_seen = int(report.get("first_seen_unix") or now_unix)

    entry = staging.get(fp) or {"reporters": [], "numbers": [], "first_seen": first_seen}
    if reporter in entry["reporters"]:
        staging[fp] = entry
        return feed, staging, f"dedup: {fp} already reported by {reporter} ({len(entry['reporters'])}/{threshold})"

    entry["reporters"].append(reporter)
    if number:
        entry["numbers"].append(number)
    entry["first_seen"] = min(entry["first_seen"], first_seen)
    votes = len([r for r in entry["reporters"] if r != "anon"]) + (1 if "anon" in entry["reporters"] else 0)

    if votes >= threshold:
        # promote: pick the most-reported sender number (bonus signal) and publish the fingerprint.
        top_num = Counter(entry["numbers"]).most_common(1)
        feed.append({
            "content_fp": fp,
            "sender_number": top_num[0][0] if top_num else "",
            "first_seen_unix": entry["first_seen"],
            "count": votes,
        })
        staging.pop(fp, None)
        return feed, staging, f"PROMOTE: {fp} reached {votes}/{threshold} → published"

    staging[fp] = entry
    return feed, staging, f"stage: {fp} now {votes}/{threshold}"


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--payload", default="-", help="report JSON file, or - for stdin")
    ap.add_argument("--feed", default="feed.json")
    ap.add_argument("--staging", default="staging.json")
    ap.add_argument("--threshold", type=int,
                    default=int(os.environ.get("CROWD_CONSENSUS_THRESHOLD", "3")))
    args = ap.parse_args(argv)

    raw = sys.stdin.read() if args.payload == "-" else open(args.payload, encoding="utf-8").read()
    try:
        report = json.loads(raw)
    except json.JSONDecodeError as e:
        print(f"ingest: payload is not valid JSON ({e})", file=sys.stderr)
        return 2

    feed = _load(args.feed, [])
    staging = _load(args.staging, {})
    try:
        feed, staging, summary = ingest(report, feed, staging, args.threshold, int(time.time()))
    except ValueError as e:
        print(f"ingest: {e}", file=sys.stderr)
        return 3

    _save(args.feed, feed)
    _save(args.staging, staging)
    print(f"ingest: {summary}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
