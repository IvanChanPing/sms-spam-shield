# SMS Spam Shield

An open-source, drop-in **spam-prevention library for Android SMS apps**. Add it to any
messaging app to **flag** incoming spam — the app decides what to do with the flag.

**Flagship target: political spam** — the election-season flood of P2P campaign/fundraising
texts sent from dozens of constantly-rotating numbers, where replying "STOP" doesn't stop
them. That class defeats every phone-number/domain blocklist on purpose (the numbers rotate
too fast to ever land in a reputation database), so Spam Shield catches it by reading the
**content** — the one thing that actually works for political spam.

## Design in one line
`classify(sender, body) → Verdict{ level, score, reasons[] }` — **flag only, never blocks
delivery.** The host app decides: badge it, move it to a spam folder, silence it, or offer
its users an auto-hide toggle. An optional `AutoFilter` helper is provided for that.

## Layers (all optional except L0)
| Layer | What | Cost | Network |
|---|---|---|---|
| **L0 Political-spam heuristic** | Content signals ("Paid by" disclaimer, fundraising/GOTV language, reply-STOP, unknown P2P sender, shortlink). The reliable political-spam catcher. | tiny | none |
| **L1 Local AI** *(optional)* | Prompt-driven ("is this unsolicited political spam?"). Uses on-device **Gemini Nano** (ML Kit GenAI Prompt API) where the phone has it → developer-configured **cloud** LLM → none. **No bundled model** — the app never ships gigabytes. | ~0 | on-device or cloud |
| **L2 Feed matching** *(optional)* | Phishing URL/host/number vs downloaded threat feeds (OpenPhish/URLhaus). | small | download |
| **L3 Online reputation** *(optional)* | Safe Browsing / number-reputation lookups. | ~0 | per-message |

Opt-in layers (L2/L3/cloud AI) send data off-device and/or need a one-time key → **off by
default**. Non-commercial feeds (OpenPhish, Safe Browsing v4) are never bundled, so the
library itself stays commercially usable.

## Architecture
- **`engine/`** — Rust core (UniFFI → `.so`): message extraction, the L0 heuristic, feed
  matching (L2) and online reputation (L3). No ML, tiny, self-contained binary.
- **`android/`** — Kotlin library (AAR): the public `SpamShield` API, the pluggable L1 AI
  layer (Nano / cloud), and the optional `AutoFilter` helper.

## Quick start (drop it in)
Add the core AAR (and, optionally, the AI layer), then it's ~4 lines:

```kotlin
// 1. one-time setup (app start)
SpamShield.configure(context, SpamShield.Config(
    trustedSenders = listOf("Eventbrite", "22395"),           // never-flag list (optional)
    crowdFeedUrl   = "https://…/feed.json",                    // opt-in crowd feed (optional)
))
SpamShield.scheduleAutoRefresh(context)                        // self-starting feed refresh

// 2. on each incoming SMS (off the main thread)
val verdict = SpamShield.classify(sender, body, isKnownContact = false)
if (verdict.isSpam) markAsSpam(message)                        // YOUR app decides what to do

// 3. when the user confirms spam → help everyone (optional, opt-in)
SpamShield.report(sender, body)                               // uploads a fingerprint, not the text
```

`classify` never blocks delivery — it returns a `Verdict{ level, score, reasons, matchedSource }`
and the host decides (badge / spam folder / silence / auto-hide). The optional on-device or cloud
**AI** (`:spamshield-ai`) plugs in behind the same idea for the harder, vaguer cases.

## Repository map
| Path | What lives here |
|---|---|
| `engine/src/spam/heuristic.rs` | **L0** political-spam content detector (the flagship). Lexicons + the ≥2-signal decision rule. |
| `engine/src/spam/crowd.rs` | **Crowd feed** client: rotation-proof fingerprint, feed store, match, report, transport. |
| `engine/src/spam/{extract,store,feeds,online}.rs` | URL/number extraction · indicator store · L2 threat-feed download · L3 online lookups. |
| `engine/src/spam/engine.rs` | Offline decision (`classify_offline`) + the `Verdict`/`SpamLevel` types. |
| `engine/src/spam/mod.rs` | The UniFFI surface: `spam_configure` / `spam_classify` / `spam_report_spam` / `spam_refresh_*` / `spam_status`. |
| `engine/tests/corpus.rs` | Real-corpus tests (false-positive + recall) — see `tests/data/README.md` to fetch data. |
| `android/spamshield/` | Core AAR: the `SpamShield` facade + the self-starting `SpamRefreshWorker`. |
| `android/spamshield-ai/` | Optional L1 AI layer (`NanoAiClassifier` / `CloudAiClassifier`). |
| `docs/` | `AI_LAYER.md`, `CROWD_FEED_DESIGN.md`, architecture spec. |

## Status
Early development. The Rust `engine/` (L0 political-spam heuristic, crowd feed, threat feeds) passes
**68 unit + 7 real-corpus tests** with **0 false positives across ~105k real messages**, and the FFI
exposes `spam_configure` / `spam_classify` / `spam_report_spam` / `spam_refresh_*` / `spam_status`. The
Kotlin `SpamShield` facade and self-starting refresh worker sit on top of the generated UniFFI bindings.
The crowd-feed server is a GitHub-Actions consensus broker (`server/`, `docs/CROWD_FEED_DESIGN.md`). The
optional L1 AI layer ships two backends — on-device Gemini Nano, or any OpenAI-compatible cloud model.
Not yet published to a package registry.

## License
[Apache-2.0](LICENSE).
