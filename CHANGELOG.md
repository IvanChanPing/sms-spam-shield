# Changelog

All notable changes to SMS Spam Shield are logged here. Format loosely follows
Keep-a-Changelog; timestamps are UTC.

## [Unreleased]

### 2026-07-04 — Recall audit (broad-net + LLM judge) + two-tier product decision
- **Method** (`tools/recall_audit.py`): to measure what the strict heuristic MISSES without
  hand-labelling thousands of texts, a deliberately BROAD keyword net over-selects candidates
  (175/1,492 US msgs, intentionally including false positives), then a small LLM (Haiku)
  adjudicates each as political-spam or not. NOT a blocklist — the LLM reads content and
  judges; the broad net is a measurement instrument, not the product detector.
- **Result:** Haiku confirmed **26/175 candidates are genuine political spam**, and **0/150**
  non-candidates were political (the keyword net itself misses ~none). The strict L0 heuristic
  caught only **3 of those 26 → ~12% recall**. The 23 misses are UNAMBIGUOUS political spam
  ("*OFFICIAL MESSAGE FROM PRES TRUMP* Would you vote…", "Rush $9 before I review our supporter
  list", "donate to my <party> campaign", NextGen CA voter surveys) — the conservative ≥2-strong
  rule is discarding them. Saved the 26 as `engine/tests/data/political_confirmed.txt` (gitignored;
  embeds IMC25 text) = the labeled political-spam recall set we previously lacked.
- **Decision (product tiering):** BASIC mode = the on-device script (free, zero-FP, catches the
  clear ones) — to be improved using the 26-msg recall set while re-verifying 0 FP on the ~58k ham.
  ADVANCED mode = opt-in AI (the two already-scaffolded backends: on-device Nano [free/private] or
  cloud [paid, off-device]) to "nail down everything," incl. the deliberately-vague texts. Next:
  raise BASIC recall against the confirmed set (FP-guarded); wire the tier toggle into the API.

### 2026-07-04 — US-only crowd-sourced corpus slice (IMC25) + detector run
- Added `imc25_us_political_flags` corpus test over a **US-only** slice of the 2025
  crowd-sourced smishing dataset (`reportsmishing/Smishing-Dataset-IMC25`, an IMC 2025
  paper mining public user reports). Filtered the 33.9k-row global set to rows whose
  reporting network was `original_network_country == USA` **and** `language == English`
  → **1,492 messages (2019–2023)**, US-by-construction and majority-English — the first
  slice that satisfies all of: post-2020-active, US-majority (100% US network), general
  (all smishing scam types), English. Staged as `tests/data/imc25_us.txt` (fetched/derived,
  not vendored). Rationale for filtering vs. using the raw set: the raw IMC25 is a *global*
  corpus (USA only 4.6% of identified networks; India 11%, NL/GB/ES/FR/AU dominate) so it
  fails the "not >50% foreign" rule — the US-network filter fixes that.
- **Detector result:** over the 1,492 US messages the L0 political detector flagged **3**,
  and all 3 are true political campaign/fundraising spam (a "Paid by …4Sheriff" GOTV text, a
  "donate to my campaign" ask, and a "Pres Trump … defend our Election from the Left" blast).
  It left the other 1,489 delivery/bank/refund smishing untouched — correct needle-in-haystack
  behavior for a political-specific detector, 0 obvious false positives on the flags.
- Added `winred_live_samples` test over **8 real 2024–2025 RNC/WinRed political-spam texts**
  scraped verbatim from the public ResourcesForLife archive (item41854/item42207) — the
  flagship rotating-number class no labelled dataset has. **Heuristic caught 1/8**: only the
  sample with explicit political+fundraising co-occurrence (GOP+donate+Trump). The other 7 are
  deliberately vague ("pitch in $25 to my next goal", "official party survey/question #3",
  "Justice Department just found…") and carry almost no keyword signal — catching them by
  keyword would break the zero-FP priority (they read like a friend/news). CONCLUSION: evasive
  political spam needs the semantic **AI layer** (L1), not more L0 keywords. Data staged at
  `tests/data/winred_live.txt` (WebFetch-extracted excerpts, may be partial).

### 2026-07-04 — Political-ENGAGEMENT signal (polls/petitions, not just donations)
- Added an `ENGAGEMENT_CTA` signal (sign-our-letter / petition / "who will you vote for" /
  take-our-poll / pledge-to-vote) as a strong category, so political spam that asks for
  ENGAGEMENT rather than money now flags (political + engagement = 2 signals). Kept
  political-specific so a "vote for your favorite flavor, reply to enter" contest and plain
  appointment/RSVP texts stay clean. NOTE: hardcoding the test sample's "-Titus" tag was
  removed (anti-cheat) — the Titus poll is caught by GENERAL signals ("plan to vote for" +
  Democrat/Republican).
- Result: ALL 6 confirmed real user samples now flag (4 fundraisers + poll + petition);
  **false positives still 0 across ~58,000 real ham**; corpus political catches 14 → 17;
  58 unit + 3 corpus tests green; no regressions.

### 2026-07-03 — Large-corpus baseline + name-based recall tune (FP-safe)
- Added `general_smishing_corpus_baseline` + `political_recall_estimate` tests over a
  **~84,863-message** consolidated general-spam/smishing corpus (GitHub
  `shaghayegh-hp/Smishing_Dataset`; fetched, not vendored). Combined with UCI, the detector
  now has **0 false positives across ~58,000 real ham messages**.
- **Recall tune:** the baseline surfaced that name-based political fundraising spam
  ("Trump … please contribute", "Speaker Pelosi …") slipped through because the political
  lexicon had only generic terms. Added US political figures / movements / committees
  (Trump/Biden/Pelosi/Kamala/MAGA/Patriot/NRCC/NRSC/…) to the `POLITICAL` lexicon →
  political catches on the corpus rose **6 → 14**, with **false positives still 0** on the
  ~58k ham.
- **Figure-name FP guardrail (proven):** a political NAME is only ONE strong signal, so the
  ≥2-strong rule means it can never flag alone — texting a friend about Trump/Biden/Pelosi
  (or a name + a casual "$20 pizza") stays clean; only *name + a fundraising ask* flags. New
  `figure_name_alone_is_clean` / `figure_name_plus_casual_money_is_clean` /
  `figure_name_plus_fundraising_is_spam` unit tests lock this in. `cargo test` → 57 unit +
  3 corpus, all pass.
- Broad `looks_political_broad` yardstick added to the corpus test ONLY as a measurement aid
  (not part of the product detector) to approximate political recall without hand-reading the
  corpus.

### 2026-07-03 — Real-corpus validation (UCI SMS Spam Collection)
- Added `engine/tests/corpus.rs` — runs the L0 political-spam heuristic over the full **UCI
  SMS Spam Collection** (5,574 real labelled SMS: 4,827 ham + 747 spam). **Result: 0 false
  positives on 4,827 real ham messages (0.00%)** — hard-validates the zero-false-positive
  priority against thousands of real legitimate texts (not synthetic). Flagged 0/747 general
  spam, which is EXPECTED/correct: UCI is 2005-era prize/ringtone spam, not political, and
  this detector is political-specific — so the corpus validates the false-positive side but
  not political recall. Dataset is fetched, not vendored (see `engine/tests/data/README.md`;
  test skips if absent). © Almeida & Gómez Hidalgo, redistributable with citation.

### 2026-07-03 — L1 AI layer: two independent AI backends (`android/spamshield-ai`)
- New Android library module with the optional AI classifier layer — **two separate,
  user-selectable backends** (not combined): `NanoAiClassifier` (on-device Gemini Nano via
  ML Kit GenAI Prompt API `com.google.mlkit:genai-prompt:1.0.0-beta2` — private, no key, zero
  app storage, Nano-capable devices only) and `CloudAiClassifier` (any OpenAI-compatible
  `/chat/completions` endpoint — works on any phone, needs key+network, content leaves device,
  opt-in). Shared `AiClassifier` interface + `AiVerdict` + `PoliticalSpamPrompt` (prompt encodes
  the diverse-topic political/donation definition + an explicit never-flag list for FP safety +
  tolerant JSON parsing). `classify()` returns null on any failure → caller falls back to the
  heuristic, never treats null as spam. Cloud uses only java.net + org.json (no extra deps).
- Gradle module scaffolding (AGP 8.5.2 / Kotlin 1.9.24 / compileSdk 34 / minSdk 26 / coroutines
  1.8.1). Doc: `docs/AI_LAYER.md`.
- STATUS: written against the documented APIs (surface read from the official ML Kit get-started,
  not guessed). **Compile + on-device UNVERIFIED** — the build sandbox has no working Android
  Gradle (system Gradle 4.4.1) and no Nano device. Residuals to confirm on-device are listed in
  `docs/AI_LAYER.md` (non-streaming response accessor; a couple of import sub-packages).

### 2026-07-03 — Evasion + link robustness (quick wins)
- **Zero-width strip** in `normalize()`: drops invisible format chars (ZWSP/ZWNJ/ZWJ/
  word-joiner/BOM/soft-hyphen, U+200B–U+200F, U+2060–U+2064, U+FEFF, U+00AD, …) before
  NFKC, so keyword evasion like `d‍o‌n​a⁠t﻿e` no longer slips past the lexicons. (Verified
  NFKC alone does NOT remove these.)
- **Scheme-less tracking links**: `has_tracking_link()` now scans raw whitespace tokens,
  catching the common SMS form `www.x.com/07011t1s2/lKBgJW` (previously only `http://`
  links were seen). Still excludes hyphenated slugs (e.g. `eventbrite.com/e/sunset-yoga`).
- 2 tests; `cargo test` → 54 pass, 0 warnings; no regressions.

### 2026-07-03 — Trusted-sender allowlist (anti-FP)
- **`HeuristicConfig.trusted_senders`** (new, default empty) + `sender_is_trusted()` /
  `digits_match()` in `heuristic.rs`: a host-supplied sender is never flagged as political
  spam regardless of content (mirrors `is_known_contact`). Matches case-insensitively for
  alphanumeric A2P sender IDs (e.g. "Eventbrite") or by digits for short codes / phone
  numbers (tolerant of a leading country code; short codes match exactly). Exempts the
  heuristic ONLY — phishing feed matches (L2/L3) still apply, so a spoofed/compromised
  trusted sender pushing a known-bad link is still caught. Closes the opted-in campaign-
  fundraiser-event-reminder false-positive edge. 4 tests; `cargo test` → 52 pass, 0 warnings.

### 2026-07-03 — L0 political-spam heuristic (step 2)
- **`engine/src/spam/heuristic.rs`** (new) — `classify_political(text, sender,
  is_known_contact, &HeuristicConfig) -> Option<Verdict>`: the flagship content-aware
  detector for unsolicited political campaign/fundraising texts (the rotating-number,
  "STOP-doesn't-stop-it" class that no reputation DB can catch). General signals only —
  fundraising + political/GOTV lexicons, survey CTA, FCC opt-out keywords (gate), "paid
  for by", per-recipient tracking shortlink, styled-Unicode evasion, ActBlue/WinRed/NGP
  fundraising domains, unknown-10-digit-P2P sender. NFKC-normalizes first to defeat
  "𝗺𝗮𝘁𝗵-𝗯𝗼𝗹𝗱" keyword evasion. Conservative decision rule (one weak signal never flags).
  Grounded in verified FCC rules + political-texting research + real-world sample
  messages — the samples are TEST FIXTURES only; the matcher hardcodes no spammer
  domain/number. Added dep `unicode-normalization 0.1`.
- `cargo test` → **11 heuristic tests + 23 reused = all pass**: 3 real samples flag via
  general signals; 4 clean controls (2FA / delivery / retail / personal) stay clean.
- Known gap (logged): tracking-link detection only sees `http://` links, not the common
  scheme-less `www.x.com/code`. Not yet wired into the FFI `spam_classify`. Status:
  host-tested; on-device UNVERIFIED.

### 2026-07-03 — False-positive hardening (precision-first)
- Reworked the L0 decision rule to flag ONLY on a fundraising-domain link OR ≥2
  independent STRONG signals {fundraising word, political, styled-Unicode, "paid for
  by"}. A bare money amount, opt-out wording, "reply YES", a shortlink, and an unknown
  sender are now boosters/reasons only — they never trigger a flag. Fixes two FP holes
  (retail "$20 off"; news link + one political word). Added realistic clean near-miss
  tests (2FA, appointment, bank alert, charity receipt, news link, contest, RSVP, retail;
  financial short-code notices: bank balance, Cash App payment, fraud/suspicious-activity
  alert, low balance; Eventbrite event reminders incl. a civic-named event) — 17 clean-
  control tests + 6 spam/recall. `cargo test` → 48 pass, 0 warnings.
- Trade-offs (intentional, per zero-FP priority): a pure GOTV text with no money/party/
  styled/tracking signal is not flagged; and an opted-in *campaign-fundraiser event*
  reminder (fundraising + political = 2 strong) would flag. Planned fix for legit bulk
  senders: a host-configurable trusted-sender allowlist (never flag Eventbrite / your
  bank / your subscriptions), alongside `is_known_contact`.

### 2026-07-03 — Project bootstrap + engine extraction (step 1)
- **Scoping + research** — decided the project: open-source (Apache-2.0) drop-in SMS
  spam-flagging library; flagship target = political spam; a compact, self-contained Rust
  engine core; pluggable local AI with **no bundled model** (on-device Gemini Nano via ML
  Kit GenAI, or developer cloud, or none). Verified local-AI feasibility (ONNX/Rust-on-
  Android, Gemini Nano is OS-owned = zero app storage, flagship-only today). See the README
  for the design.
- **`engine/` crate created** (`spam_shield` 0.1.0) — a standalone, self-contained UniFFI
  crate providing the offline detection layers (`extract`/`store`/`feeds`/`online`/`engine`/
  `mod`), with no messaging-app coupling.
  New `Cargo.toml` (minimal deps: once_cell, tokio, reqwest+rustls, url, serde/serde_json,
  log, uniffi 0.28) + `src/lib.rs` (`uniffi::setup_scaffolding!()` + `pub mod spam`).
  FFI surface unchanged: `spam_configure` / `spam_refresh_feeds` / `spam_classify` /
  `spam_status`. **`cargo test` → 23/23 host tests pass.** No Android build yet (no NDK
  run here); on-device classify UNVERIFIED.
- Added `LICENSE` (Apache-2.0, canonical text), `README.md`, `.gitignore`, this changelog.
