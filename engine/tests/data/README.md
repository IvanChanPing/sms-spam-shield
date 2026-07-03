# Test corpus data

The `corpus` integration test (`engine/tests/corpus.rs`) validates the detector against a
**real, public** SMS corpus. The data file itself is **not committed** (to keep this
Apache-2.0 repo free of a third-party dataset copyright, and lean) — fetch it once:

```bash
cd engine/tests/data
curl -fsSL -o sms.zip "https://archive.ics.uci.edu/static/public/228/sms+spam+collection.zip"
unzip -o sms.zip SMSSpamCollection      # → engine/tests/data/SMSSpamCollection
```

Then:

```bash
cargo test --test corpus -- --nocapture
```

If the file is absent the test **skips** (so CI without the download still passes).

## Dataset
**UCI SMS Spam Collection v.1** — 5,574 real labelled English SMS (4,827 ham + 747 spam).
© Tiago A. de Almeida & José María Gómez Hidalgo; provided free with citation requested:

> Almeida, T.A., Gómez Hidalgo, J.M., Yamakami, A. *Contributions to the study of SMS Spam
> Filtering: New Collection and Results.* Proc. ACM DOCENG'11.
> <https://archive.ics.uci.edu/dataset/228/sms+spam+collection>

## Measured result (2026-07-03)
Running the L0 political-spam heuristic over the full corpus:
- **Ham: 4,827 → 0 false positives (0.00%)** — the detector flags none of thousands of real
  legitimate messages.
- Spam: 747 → 0 flagged. **Expected**: this corpus is general 2005-era prize/ringtone spam,
  not political; a political-spam detector correctly ignores it. This corpus therefore
  validates the *false-positive* side hard, but does **not** test political *recall*.
