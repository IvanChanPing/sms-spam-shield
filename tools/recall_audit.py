#!/usr/bin/env python3
"""recall_audit.py — measure what the STRICT political heuristic MISSES.

WHAT / WHY
  The strict L0 heuristic flags conservatively (needs >=2 strong signals) to keep
  false positives at zero. That means it silently MISSES real political spam that
  only shows one keyword-visible signal. To measure the miss rate WITHOUT hand-
  labelling thousands of texts, we:
    1. run a DELIBERATELY BROAD net (this file) that over-selects any message with
       ANY political/fundraising/manipulation cue — it intentionally also grabs
       some non-political (false positives), by design;
    2. hand the candidate set (+ a random sample of the NON-candidates, to catch
       keyword-invisible misses) to a small LLM (Haiku/Sonnet) that reads each and
       says political-spam yes/no;
    3. compare that ground truth to what the strict heuristic caught.
  This is NOT the product detector and NOT a blocklist — it's a measurement harness.

INPUT : engine/tests/data/imc25_us.txt (1,492 US smishing msgs) + winred_live.txt
OUTPUT: tools/audit_candidates.txt  (broad-net hits, for the LLM judge)
        tools/audit_noncand_sample.txt (random non-candidates, miss-check)
RUN   : python3 tools/recall_audit.py
"""
import os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "..", "engine", "tests", "data")

# Broad, recall-maximising cues. Over-selection is intentional — the LLM sorts them.
POLITICAL = [
    "trump", "biden", "harris", "obama", "desantis", "pelosi", "kamala", "maga",
    "democrat", "republican", " gop", "dnc", "rnc", "patriot", "conservative",
    "liberal", "scalise", "rubio", "election", "vote", "ballot", "campaign",
    "senate", "congress", "president", "candidate", "midterm", "impeach",
    "amendment", "caucus", "primary", "polling", "poll", "petition", "committee",
    " pac", "super pac", "the left", "the right", "flip the", "your vote",
]
FUNDRAISING = [
    "donate", "donation", "donor", "chip in", "chipin", "pitch in", "pitchin",
    "contribute", "contribution", "fundrais", "actblue", "winred", "match",
    "matched", "goal", "pledge",
]
# emotional-manipulation cues common to rotating-number political blasts
MANIP = [
    "friend", "supporter", "asking 1 more", "1 more time", "deadline",
    "before midnight", "please help", "i need you", "i'm begging", "urgent",
    "final notice", "last chance", "responding to you", "did you see",
]

ALL = POLITICAL + FUNDRAISING + MANIP


def is_candidate(msg: str) -> bool:
    m = msg.lower()
    return any(k in m for k in ALL)


def load(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return [l.strip() for l in f if l.strip()]
    except FileNotFoundError:
        return []


def main():
    us = load(os.path.join(DATA, "imc25_us.txt"))
    win = load(os.path.join(DATA, "winred_live.txt"))
    cand = [m for m in us if is_candidate(m)]
    noncand = [m for m in us if not is_candidate(m)]
    win_cand = [m for m in win if is_candidate(m)]

    # deterministic "random" sample of non-candidates (no RNG dependency): every Nth
    step = max(1, len(noncand) // 150)
    sample = noncand[::step][:150]

    with open(os.path.join(HERE, "audit_candidates.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(cand))
    with open(os.path.join(HERE, "audit_noncand_sample.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(sample))

    print(f"US messages total           : {len(us)}")
    print(f"broad-net CANDIDATES         : {len(cand)}  ({100*len(cand)/max(1,len(us)):.1f}%)")
    print(f"non-candidates               : {len(noncand)}  (sampled {len(sample)} for miss-check)")
    print(f"winred_live broad-net hits   : {len(win_cand)}/{len(win)}  (strict heuristic caught 1/{len(win)})")
    print()
    print("wrote tools/audit_candidates.txt + tools/audit_noncand_sample.txt")


if __name__ == "__main__":
    main()
