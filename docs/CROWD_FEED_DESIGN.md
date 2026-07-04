# Crowd-feed design — a shared spam-fingerprint feed

**Status: DESIGN + client scaffolding built (`engine/src/spam/crowd.rs`). Server side NOT built.**
This document specs the whole feature so the server can be built without re-deriving anything.

## Why
Political spam rotates its sender numbers and per-recipient links too fast for any
number/domain blocklist to keep up — so a message only gets caught if the *content* is read.
A crowd feed makes that catch **collective**: the moment ONE user's app flags a new campaign,
its fingerprint is shared, and every other app matches the same campaign instantly — even
from numbers nobody has ever seen. It turns "someone has to be the first victim, per person"
into "someone has to be the first victim, **once, ever**."

## What crosses the wire — a fingerprint, never the message
The client uploads a `CrowdReport` (see `crowd.rs`), never raw text:

```json
{ "content_fp": "9f3a1c...", "sender_number": "13602182008", "first_seen_unix": 1751600000, "count": 1 }
```

- **`content_fp`** — the match key. The body is normalized (NFKC-fold styled Unicode, strip
  zero-width chars, drop the greeting, links, per-recipient opaque codes and long digit runs,
  strip `<placeholder>` tokens) then FNV-1a hashed. The SAME campaign to different people,
  from different numbers, with different links → the SAME `content_fp` (rotation-proof, and
  now styled/zero-width-proof too). Because name + link + number are stripped, the fingerprint
  carries **no PII** and the raw message can't be reconstructed from it.
- **`sender_number`** — kept deliberately (product decision) as a *bonus* signal so the feed
  also accrues a rotating-number list; matching keys on `content_fp`, so a rotated number
  never breaks the match.

## Read path (trivial, free, serverless)
A JSON array of `CrowdReport` served as a static file. Clients GET it on a schedule
(`spam_refresh_crowd` → `crowd::fetch_feed`) and match locally (`crowd::match_feed`, O(1) set
membership). This can be a plain file in a public GitHub repo pulled via
`raw.githubusercontent…` — exactly like the L2 threat feeds already work. No server needed to
*serve* the feed.

## Write path (the only part with real engineering) — GitHub Actions as the broker
The app can't be handed a GitHub write token (extractable). Instead, uploads are **brokered by
a GitHub Actions workflow** so there is still no server of ours to run:

1. Client `POST`s a `CrowdReport` — either to a tiny ingestion endpoint or, GitHub-natively,
   by triggering a [`repository_dispatch`](https://docs.github.com/rest/repos/repos#create-a-repository-dispatch-event)
   event (or filing an issue) via a scoped token / GitHub App.
2. A **workflow (YAML)** fires on that event and does the validation + commit:
   - **re-classifies** the submitted content itself (runs the same detector / an LLM) and
     rejects anything it doesn't independently agree is political spam — so a forged submission
     of a *legit* message is dropped regardless of who sent it;
   - **consensus**: only promotes a `content_fp` to the published feed after **N distinct
     reporters** (PhishTank model — no single report flags anything), tracking vote counts in a
     staging file; hidden tallies to avoid cascade bias;
   - **rate-limits** per install; appends the confirmed fingerprint to the feed file and commits.

The GitHub secret needed to verify attestation (below) lives in **GitHub Secrets**, read only
inside the workflow run — never in the app.

## Abuse resistance (layered; kept light on the client on purpose)
Poisoning a *political-spam* fingerprint feed has little payoff (no money in it), so the client
stays simple. Robustness is layered where it actually holds:

1. **Prove it's really the app** — optional **Play Integrity API** (Android) / **App Attest**
   (iOS): the platform *signs* a verdict that the request came from the genuine, untampered app
   on a genuine device; the workflow verifies the signature. Not forgeable by decompiling,
   because the signing key is Google's/Apple's, off-device. The client hook is
   `CrowdConfig.auth_header_*` (send the integrity token as a header). Caveats: needs Play
   Services (de-Googled phones fall back to consensus-only), per-app Play Console registration
   (so for a *drop-in library* this is an **optional hardening** a serious integrator enables,
   not a baseline), daily quota → always paired with consensus.
2. **Don't trust even a genuine app on one report** — server re-classification + N-reporter
   consensus (above). This is the always-on baseline and is what protects the zero-false-
   positive priority.

> Note on client-side encryption: obfuscating/encrypting a key *in the app* only raises the
> cost, never closes the hole — the decryption key and the plaintext-at-use both live on the
> attacker's device. Attestation works precisely because its root of trust is **off** the
> device. Encrypt as a speed-bump layer if desired, but the guarantees come from attestation +
> consensus, not from a client secret.

## An SMS provider can run their own
Every URL/credential is host-config (`CrowdConfig{feed_url, report_url, auth_header_*}` →
`SpamConfig.crowd_*`). A provider points `feed_url`/`report_url` at their own backend and adds
their own API-key/attestation header — no code change. The community GitHub-Actions feed is
just the default option.

## Client status (host-tested)
`crowd.rs`: `content_fingerprint`, `CrowdFeedStore`, `match_feed`, `build_report`, `fetch_feed`,
`submit_report` — 9 host tests (rotation-proof, styled/zero-width-proof, content/number match,
JSON round-trip, config gates). Wired into the FFI: `SpamConfig.crowd_*`, `spam_report_spam`,
`spam_refresh_crowd`, and `crowd::match_feed` inside `spam_classify`. **UNVERIFIED:** live
client↔server exchange (no server in the build env). Build the server per the write-path above.
