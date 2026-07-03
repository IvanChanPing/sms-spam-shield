//! spam_shield — on-device SMS scam/spam classification engine (library crate root).
//!
//! WHAT THIS IS
//!   The Rust core of "SMS Spam Shield", an open-source (Apache-2.0) drop-in any
//!   Android SMS app can embed to FLAG spam. It classifies an incoming message
//!   against on-device signals and returns a verdict (level + score + reasons);
//!   it never blocks/moves/deletes — the host app decides what to do.
//!
//!   A compact, self-contained engine with no messaging-app coupling.
//!
//! LAYERS (see README.md)
//!   L0 political-spam heuristic (content-aware; always-on; offline) — the reliable
//!      catcher for election-season political spam (rotating P2P numbers that no
//!      reputation DB tracks). [added on top of the reused engine]
//!   L2 feed matching (reused) — phishing URL/host/number vs downloaded feeds.
//!   L3 online reputation (reused, opt-in) — Safe Browsing / number reputation.
//!   (L1 local AI is a Kotlin-side layer — no bundled model — not in this crate.)
//!
//! FFI SURFACE (UniFFI proc-macro scaffolding; consumed by the Kotlin AAR)
//!   spam_configure(SpamConfig) · spam_refresh_feeds() → SpamRefreshResult (async) ·
//!   spam_classify(text, sender) → SpamVerdict (async) · spam_status() → SpamStatus.
//!   Kotlin bindings regenerate via the `uniffi-bindgen` bin (see Cargo.toml).
//!
//! HOW TO TEST — `cargo test` (host target) runs the reused extract/store/feeds/
//!   engine unit tests. STATUS: extraction step; on-device classify/feed-download
//!   are NOT verified here (no device/NDK in this env).

uniffi::setup_scaffolding!();

pub mod spam;
