# Changelog

All notable changes to SMS Spam Shield are logged here. Format loosely follows
Keep-a-Changelog; timestamps are UTC.

## [Unreleased]

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
