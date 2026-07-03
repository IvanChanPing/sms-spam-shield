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
