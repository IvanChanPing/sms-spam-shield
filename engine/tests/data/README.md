# Test corpus data

The `corpus` integration test (`engine/tests/corpus.rs`) validates the detector against a
**real, public** SMS corpus. The data file itself is **not committed** (to keep this
Apache-2.0 repo free of a third-party dataset copyright, and lean) — fetch it once:

```bash
cd engine/tests/data
curl -fsSL -o sms.zip "https://archive.ics.uci.edu/static/public/228/sms+spam+collection.zip"
unzip -o sms.zip SMSSpamCollection      # → engine/tests/data/SMSSpamCollection

# (optional, much larger) combined general-spam / smishing corpus (~84.8k messages):
curl -fsSL -o Combined-Labeled-Dataset.csv \
  "https://raw.githubusercontent.com/shaghayegh-hp/Smishing_Dataset/main/Combined-Labeled-Dataset.csv"
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

## Second dataset (optional, larger)
**Combined general-spam / smishing corpus** — ~84,863 messages (~53.4k ham + ~29.8k spam),
consolidated from 5 public sources. From GitHub `shaghayegh-hp/Smishing_Dataset`
(`Combined-Labeled-Dataset.csv`, columns `message,spam label,smishing label`). Used by the
`general_smishing_corpus_baseline` + `political_recall_estimate` tests.

## Measured result (2026-07-03)
L0 political-spam heuristic over BOTH corpora (`cargo test --test corpus -- --nocapture`):
- **False positives: 0 on ~58,000 real ham** (4,827 UCI + 53,396 combined = 0.00%). The
  detector flags none of tens of thousands of real legitimate messages.
- **General spam**: correctly ignores non-political spam (prize/ringtone/carrier/delivery) —
  low "recall" here is by design; this is a *political* detector.
- **Political spam within the combined corpus**: it pulls out only the political fundraising
  texts. After adding name-based markers (Trump/Biden/Pelosi/…/committees), catches rose
  **6 → 14** of the political fundraising spam present, still at **0 false positives**. A
  political NAME is only one signal, so it never flags without a second (a fundraising ask) —
  see the `figure_name_*` unit tests.
- No public *political-spam-labelled* dataset exists, so absolute political recall can only be
  measured against real user-supplied examples; this corpus measures the FP side at scale.
