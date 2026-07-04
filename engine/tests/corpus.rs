//! corpus.rs — validate the political-spam heuristic against a REAL public SMS corpus.
//!
//! WHAT THIS IS
//!   An integration test that runs the L0 political-spam heuristic over every message in
//!   the UCI SMS Spam Collection (5,574 real labelled SMS: 4,827 ham + 747 spam) and
//!   measures the two numbers that matter for a flag-only detector:
//!     * FALSE-POSITIVE RATE on real ham  — the product's #1 concern (must be ~0).
//!     * how much of the general spam it flags — EXPECTED to be low, because this is a
//!       POLITICAL-spam detector and UCI is 2005-era UK prize/ringtone/adult spam, not
//!       political. Low recall here is correct, not a failure.
//!
//! DATA (not committed — see tests/data/README.md to fetch it)
//!   Reads `tests/data/SMSSpamCollection` (TSV: `label<TAB>message`). If the file is
//!   absent the test SKIPS (so CI without the download still passes). The corpus is
//!   © Almeida & Gómez Hidalgo, free to use/redistribute with citation.
//!
//! HOW TO RUN
//!   cargo test --test corpus -- --nocapture     (prints the measured rates + FP samples)
//!   STATUS: host-runnable here; numbers are real (not synthetic).

use spam_shield::spam::heuristic::{classify_political, HeuristicConfig};
use std::path::PathBuf;

fn corpus_path() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/data/SMSSpamCollection")
}

fn first_chars(s: &str, n: usize) -> String {
    s.chars().take(n).collect()
}

/// A DELIBERATELY BROAD political labeller used ONLY as a measurement yardstick in
/// [political_recall_estimate] — NOT part of the product detector. It over-includes
/// (a few non-political messages sweep in) so we can approximate political recall
/// across a big corpus without hand-reading every message. If ANY of these markers
/// appears, we call the message "likely political".
fn looks_political_broad(msg: &str) -> bool {
    let m = msg.to_lowercase();
    const MARKERS: &[&str] = &[
        // figures / parties / movements
        "trump", "biden", "harris", "obama", "desantis", "pelosi", "kamala", "maga",
        "democrat", "republican", "liberal", "conservative", " gop", "dnc", "rnc",
        "the left", "the right", "patriot", "stop the steal", "stopthesteal",
        // committees / PACs / payment
        "nrcc", "nrsc", "dccc", "dscc", "actblue", "winred", "act.gop", "gopwin", "nrcc.news",
        // government / elections
        "senate", "congress", "house majority", "the house", "ballot", "midterm",
        "election", "impeach", "flip the", "your vote", "polling", "caucus", "primary",
        "president", "campaign", "candidate", "amendment", "super pac", "dark money",
    ];
    MARKERS.iter().any(|k| m.contains(k))
}

#[test]
fn uci_sms_spam_corpus_false_positive_rate() {
    let path = corpus_path();
    let bytes = match std::fs::read(&path) {
        Ok(b) => b,
        Err(_) => {
            eprintln!(
                "SKIP: UCI corpus not present at {path:?} — see tests/data/README.md to fetch it."
            );
            return;
        }
    };
    // The corpus is latin-1/mixed; lossy-decode so a few odd bytes don't abort the run.
    let text = String::from_utf8_lossy(&bytes);
    let cfg = HeuristicConfig::default();

    let (mut ham, mut ham_fp, mut spam, mut spam_hit) = (0u32, 0u32, 0u32, 0u32);
    let mut fp_examples: Vec<String> = Vec::new();

    for line in text.lines() {
        let mut cols = line.splitn(2, '\t');
        let label = cols.next().unwrap_or("");
        let msg = match cols.next() {
            Some(m) => m,
            None => continue,
        };
        // No sender in this corpus → tests the CONTENT heuristic (sender empty, not a contact).
        let flagged = classify_political(msg, "", false, &cfg).is_some();
        match label {
            "ham" => {
                ham += 1;
                if flagged {
                    ham_fp += 1;
                    if fp_examples.len() < 25 {
                        fp_examples.push(first_chars(msg, 150));
                    }
                }
            }
            "spam" => {
                spam += 1;
                if flagged {
                    spam_hit += 1;
                }
            }
            _ => {}
        }
    }

    let ham_fp_rate = 100.0 * ham_fp as f64 / ham.max(1) as f64;
    let spam_rate = 100.0 * spam_hit as f64 / spam.max(1) as f64;
    eprintln!("\n===== UCI SMS Spam Collection =====");
    eprintln!("ham:  {ham}  false-positives: {ham_fp}  ({ham_fp_rate:.2}%)");
    eprintln!("spam: {spam}  flagged: {spam_hit}  ({spam_rate:.1}% — low is EXPECTED, this is a political detector)");
    if !fp_examples.is_empty() {
        eprintln!("--- ham messages we flagged (false positives to inspect) ---");
        for (i, m) in fp_examples.iter().enumerate() {
            eprintln!("  FP[{i}]: {m}");
        }
    }
    eprintln!("===================================\n");

    // Regression guard: a POLITICAL-spam detector must almost never flag general ham.
    assert!(
        ham_fp_rate <= 0.5,
        "ham false-positive rate {ham_fp_rate:.2}% ({ham_fp}/{ham}) exceeds 0.5% — inspect the FP samples above"
    );
}

/// Baseline against a LARGE consolidated general-spam / smishing corpus
/// (~84.8k messages, 5 public sources merged; GitHub shaghayegh-hp/Smishing_Dataset,
/// `Combined-Labeled-Dataset.csv`, columns `message,spam label,smishing label`).
///
/// Measures two things a political-only detector should show here:
///   * FALSE-POSITIVE rate on tens of thousands of real ham (must stay ~0), and
///   * how much of the GENERAL spam it currently catches — expected LOW, and the
///     spam it DOES catch is the political/fundraising overlap. The printed HIT/MISS
///     samples show exactly what to tune for when broadening to general spam.
#[test]
fn general_smishing_corpus_baseline() {
    let path =
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/data/Combined-Labeled-Dataset.csv");
    let bytes = match std::fs::read(&path) {
        Ok(b) => b,
        Err(_) => {
            eprintln!("SKIP: combined smishing corpus not present — see tests/data/README.md.");
            return;
        }
    };
    let text = String::from_utf8_lossy(&bytes);
    let cfg = HeuristicConfig::default();

    let (mut ham, mut ham_fp, mut spam, mut spam_hit) = (0u32, 0u32, 0u32, 0u32);
    let (mut caught, mut missed) = (Vec::new(), Vec::new());

    for (i, line) in text.lines().enumerate() {
        if i == 0 {
            continue; // header
        }
        // message may contain commas → the two labels are the LAST two fields; peel from the right.
        let mut it = line.rsplitn(3, ',');
        let _smishing = it.next();
        let spam_lbl = match it.next() {
            Some(s) => s.trim(),
            None => continue,
        };
        let msg = match it.next() {
            Some(m) => m,
            None => continue,
        };
        let flagged = classify_political(msg, "", false, &cfg).is_some();
        match spam_lbl {
            "0" => {
                ham += 1;
                if flagged {
                    ham_fp += 1;
                }
            }
            "1" => {
                spam += 1;
                if flagged {
                    spam_hit += 1;
                    if caught.len() < 15 {
                        caught.push(first_chars(msg, 120));
                    }
                } else if missed.len() < 15 {
                    missed.push(first_chars(msg, 120));
                }
            }
            _ => {} // malformed / multi-line row → skip
        }
    }

    let ham_fp_rate = 100.0 * ham_fp as f64 / ham.max(1) as f64;
    let recall = 100.0 * spam_hit as f64 / spam.max(1) as f64;
    eprintln!("\n===== Combined general-spam/smishing corpus (~84.8k msgs) =====");
    eprintln!("ham:  {ham}  false-positives: {ham_fp}  ({ham_fp_rate:.3}%)");
    eprintln!("spam: {spam}  caught by the POLITICAL detector: {spam_hit}  ({recall:.2}% — LOW is expected)");
    eprintln!("--- spam we DID catch (the political / fundraising overlap) ---");
    for m in &caught {
        eprintln!("  HIT : {m}");
    }
    eprintln!("--- general spam we MISSED (what to tune for when broadening) ---");
    for m in &missed {
        eprintln!("  MISS: {m}");
    }
    eprintln!("===============================================================\n");

    // Guard the FALSE-POSITIVE side only. Recall is EXPECTED to be low for a political-
    // only detector — this baseline exists to quantify the gap, not to gate on recall.
    assert!(
        ham_fp_rate <= 1.0,
        "ham false-positive rate {ham_fp_rate:.3}% on the general corpus is too high — inspect"
    );
}

/// APPROXIMATE political-recall measurement. Uses the broad [looks_political_broad]
/// yardstick to pick the spam rows that "look political", then reports what fraction
/// OUR real detector flags — and prints the misses (capped) so they can be inspected
/// and used to tune recall WITHOUT hand-reading the whole corpus. Not a gate (no
/// assertion): the yardstick is broad/imperfect, so this is a guide, not a pass/fail.
#[test]
fn political_recall_estimate() {
    let path =
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/data/Combined-Labeled-Dataset.csv");
    let bytes = match std::fs::read(&path) {
        Ok(b) => b,
        Err(_) => {
            eprintln!("SKIP: combined corpus not present — see tests/data/README.md.");
            return;
        }
    };
    let text = String::from_utf8_lossy(&bytes);
    let cfg = HeuristicConfig::default();

    let (mut pol, mut pol_caught) = (0u32, 0u32);
    let mut missed: Vec<String> = Vec::new();
    for (i, line) in text.lines().enumerate() {
        if i == 0 {
            continue;
        }
        let mut it = line.rsplitn(3, ',');
        let _smishing = it.next();
        let spam_lbl = match it.next() {
            Some(s) => s.trim(),
            None => continue,
        };
        let msg = match it.next() {
            Some(m) => m,
            None => continue,
        };
        if spam_lbl != "1" || !looks_political_broad(msg) {
            continue; // only spam rows the broad yardstick calls political
        }
        pol += 1;
        if classify_political(msg, "", false, &cfg).is_some() {
            pol_caught += 1;
        } else if missed.len() < 70 {
            missed.push(first_chars(msg, 160));
        }
    }

    let recall = 100.0 * pol_caught as f64 / pol.max(1) as f64;
    eprintln!("\n===== POLITICAL RECALL ESTIMATE (broad-yardstick) =====");
    eprintln!("spam rows the broad yardstick calls political: {pol}");
    eprintln!("of those, OUR detector flags: {pol_caught}  (~{recall:.1}% recall)");
    eprintln!("--- political-looking spam we MISSED (tune targets, capped) ---");
    for m in &missed {
        eprintln!("  MISS: {m}");
    }
    eprintln!("=======================================================\n");
}

/// Run the detector over the US-ONLY subset of the IMC 2025 crowd-sourced smishing
/// dataset (reportsmishing/Smishing-Dataset-IMC25) — rows whose reporting network was
/// `original_network_country == USA` and `language == English` (1,492 msgs, 2019–2023).
/// This is the compliant US-majority slice: US-by-construction, recent, general (all
/// smishing scam types). Reports how many the political detector flags and prints them.
/// Run: cargo test --test corpus imc25_us -- --nocapture
#[test]
fn imc25_us_political_flags() {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/data/imc25_us.txt");
    let bytes = match std::fs::read(&path) {
        Ok(b) => b,
        Err(_) => {
            eprintln!("SKIP: imc25_us.txt not present — see tests/data/README.md.");
            return;
        }
    };
    let text = String::from_utf8_lossy(&bytes);
    let cfg = HeuristicConfig::default();

    let (mut total, mut flagged) = (0u32, 0u32);
    let mut hits: Vec<String> = Vec::new();
    for line in text.lines() {
        let msg = line.trim();
        if msg.is_empty() {
            continue;
        }
        total += 1;
        if classify_political(msg, "", false, &cfg).is_some() {
            flagged += 1;
            if hits.len() < 40 {
                hits.push(first_chars(msg, 150));
            }
        }
    }
    eprintln!("\n===== IMC25 US-only smishing subset (crowd-sourced, 2019–2023) =====");
    eprintln!("US-English messages: {total}  → flagged by the political detector: {flagged}");
    eprintln!("(all rows are smishing spam; these flags are the political/fundraising overlap)");
    for m in &hits {
        eprintln!("  FLAG: {m}");
    }
    eprintln!("===================================================================\n");
}

/// Detector run over REAL, RECENT (2024–2025) US political-spam samples scraped verbatim
/// from the public ResourcesForLife RNC/WinRed archive (item41854 / item42207) — the
/// flagship rotating-number fundraising-text class that no labelled dataset contains.
/// These are WebFetch-extracted excerpts (some may be partial). Prints per-message
/// FLAG/miss so we can see the catch profile on the hard, deliberately-vague samples.
/// Run: cargo test --test corpus winred_live -- --nocapture
#[test]
fn winred_live_samples() {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/data/winred_live.txt");
    let bytes = match std::fs::read(&path) {
        Ok(b) => b,
        Err(_) => {
            eprintln!("SKIP: winred_live.txt not present — see tests/data/README.md.");
            return;
        }
    };
    let text = String::from_utf8_lossy(&bytes);
    let cfg = HeuristicConfig::default();
    let (mut total, mut flagged) = (0u32, 0u32);
    eprintln!("\n===== Real 2024–2025 RNC/WinRed political-spam samples (live-scraped) =====");
    for line in text.lines() {
        let msg = line.trim();
        if msg.is_empty() {
            continue;
        }
        total += 1;
        let hit = classify_political(msg, "", false, &cfg).is_some();
        if hit {
            flagged += 1;
        }
        eprintln!("  {} {}", if hit { "FLAG" } else { "miss" }, first_chars(msg, 110));
    }
    eprintln!("caught {flagged}/{total} — misses are the deliberately-vague evasive texts (the AI-layer case)");
    eprintln!("=========================================================================\n");
}

// ---- Parsers for the different dataset formats → (is_ham, message). ----
fn parse_uci(text: &str) -> Vec<(bool, String)> {
    text.lines()
        .filter_map(|l| {
            let mut it = l.splitn(2, '\t');
            let lab = it.next()?;
            let msg = it.next()?;
            Some((lab.trim() == "ham", msg.to_string()))
        })
        .collect()
}
fn parse_combined(text: &str) -> Vec<(bool, String)> {
    // message,spam label,smishing label  (message may contain commas → peel from right)
    text.lines()
        .skip(1)
        .filter_map(|l| {
            let mut it = l.rsplitn(3, ',');
            let _sm = it.next()?;
            let spam = it.next()?.trim();
            let msg = it.next()?;
            if spam != "0" && spam != "1" {
                return None;
            }
            Some((spam == "0", msg.to_string()))
        })
        .collect()
}
fn parse_labeltext(text: &str) -> Vec<(bool, String)> {
    // LABEL,TEXT,URL,EMAIL,PHONE — classify the remainder after LABEL (trailing Yes/No
    // columns carry no political signal, so they don't affect the count).
    text.lines()
        .skip(1)
        .filter_map(|l| {
            let mut it = l.splitn(2, ',');
            let lab = it.next()?.trim().to_lowercase();
            let rest = it.next()?;
            Some((lab == "ham", rest.to_string()))
        })
        .collect()
}

/// Run the political-spam detector over EVERY message in ALL staged datasets and report
/// how many it flags as political spam (and how many of those were labelled ham = possible
/// false positives). Run: cargo test --test corpus flags_across -- --nocapture
#[test]
fn count_political_flags_across_all_datasets() {
    let cfg = HeuristicConfig::default();
    let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/data");
    let datasets: &[(&str, fn(&str) -> Vec<(bool, String)>)] = &[
        ("SMSSpamCollection", parse_uci),
        ("Combined-Labeled-Dataset.csv", parse_combined),
        ("Balanced_10191.csv", parse_labeltext),
        ("Mishra_5971.csv", parse_labeltext),
    ];
    let (mut gt, mut gf, mut gfh) = (0u32, 0u32, 0u32);
    eprintln!("\n===== POLITICAL FLAGS ACROSS ALL DATASETS =====");
    for (name, parse) in datasets {
        let bytes = match std::fs::read(dir.join(name)) {
            Ok(b) => b,
            Err(_) => {
                eprintln!("  {name}: SKIP (absent)");
                continue;
            }
        };
        let rows = parse(&String::from_utf8_lossy(&bytes));
        let (mut total, mut flag, mut flag_ham) = (0u32, 0u32, 0u32);
        for (is_ham, msg) in &rows {
            total += 1;
            if classify_political(msg, "", false, &cfg).is_some() {
                flag += 1;
                if *is_ham {
                    flag_ham += 1;
                }
            }
        }
        eprintln!("  {name}: {total} msgs → {flag} flagged political ({flag_ham} of them labelled ham = possible FP)");
        gt += total;
        gf += flag;
        gfh += flag_ham;
    }
    eprintln!("  ---------------------------------------------");
    eprintln!("  TOTAL: {gt} msgs → {gf} flagged political ({gfh} labelled ham)");
    eprintln!("===============================================\n");
}
