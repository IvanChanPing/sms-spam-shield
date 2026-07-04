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

# (optional) two more labelled SMS sets (LABEL = ham/spam/smishing), from Mendeley Data:
curl -fsSL -o Balanced_10191.csv "https://data.mendeley.com/public-files/datasets/vmg875v4xs/files/f167b0a7-c411-45d4-9cbc-ee06c3b42753/file_downloaded"
curl -fsSL -o d5971.zip "https://data.mendeley.com/public-files/datasets/f45bkkt8pr/files/edb361de-918d-469f-9106-e84823830665/file_downloaded" \
  && unzip -o d5971.zip Dataset_5971.csv && mv Dataset_5971.csv Mishra_5971.csv

# (optional) US-only slice of the 2025 crowd-sourced IMC25 smishing set (public user reports).
# Downloads the 33.9k-row global set, keeps only rows reported on a US network + English text
# → imc25_us.txt (1,492 msgs, 2019–2023). US-by-construction; used by imc25_us_political_flags.
curl -fsSL -o imc25.csv "https://raw.githubusercontent.com/reportsmishing/Smishing-Dataset-IMC25/main/dataset/final_dataset_output.csv"
python3 - <<'PY'
import csv
out=[]
with open('imc25.csv',encoding='utf-8',errors='replace') as f:
    for r in csv.DictReader(f):
        if (r.get('original_network_country') or '').strip().upper()!='USA': continue
        if (r.get('language') or '').strip()!='English': continue
        t=(r.get('text') or '').replace('\t',' ').replace('\r',' ').replace('\n',' ').strip()
        if t: out.append(t)
open('imc25_us.txt','w',encoding='utf-8').write('\n'.join(out))
print('imc25_us.txt lines:',len(out))
PY
```

### All datasets used by the corpus tests (all real, all fetched-not-vendored, ~106k SMS total)
| File | Msgs | Labels | Source |
|---|---|---|---|
| `SMSSpamCollection` | 5,574 | ham/spam | UCI (Almeida & Gómez Hidalgo) |
| `Combined-Labeled-Dataset.csv` | 84,863 | spam/smishing flags | shaghayegh-hp/Smishing_Dataset (6 sources merged) |
| `Balanced_10191.csv` | 10,191 | ham/spam/smishing | Mendeley `vmg875v4xs` (balanced, 2025) |
| `Mishra_5971.csv` | 5,971 | ham/spam/smishing | Mendeley `f45bkkt8pr` (Mishra & Soni) |

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
