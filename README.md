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

## Status
Early development. `engine/` extracted and host-tested (23/23). L0 heuristic, the Kotlin
AAR, and the L1 AI layer are in progress. Not yet published.

## License
[Apache-2.0](LICENSE).
