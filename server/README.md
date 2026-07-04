# Crowd-feed server (GitHub-Actions broker)

The write-path "server" for the shared spam-fingerprint feed — with **no server of ours to run**.
GitHub Actions is the compute; the feed is a static file served by `raw.githubusercontent`.
Design rationale: [`../docs/CROWD_FEED_DESIGN.md`](../docs/CROWD_FEED_DESIGN.md).

## Files
| File | Role |
|---|---|
| `ingest.py` | The broker. Consensus logic (N distinct reporters → publish). Pure stdlib. |
| `feed.json` | The **published** feed (a JSON array of `CrowdReport`). Clients download this. |
| `staging.json` | Pending fingerprints + their reporter votes (below threshold). |
| `../.github/workflows/crowd-ingest.yml` | Fires on `repository_dispatch: crowd-report`, runs `ingest.py`, commits. |

## Read path (clients) — zero setup
Point the client at the raw feed URL:
```
SpamShield.Config(crowdFeedUrl = "https://raw.githubusercontent.com/<owner>/<repo>/main/server/feed.json")
```
`spam_refresh_crowd` GETs it on a schedule; `spam_classify` matches incoming messages against it.

## Write path — how a report becomes a published fingerprint
1. A client uploads one `CrowdReport` `{content_fp, reporter_id, sender_number?}`.
2. The workflow runs `ingest.py`, which holds the fingerprint in `staging.json` and **only
   promotes it to `feed.json` once `CROWD_CONSENSUS_THRESHOLD` distinct `reporter_id`s** (default 3)
   have reported it. No single report ever publishes anything.

### Two ways to submit (both server-free)
- **GitHub-Actions broker (dispatch-mode, built in):** set the client config to
  ```
  SpamShield.Config(
    crowdReportUrl        = "https://api.github.com/repos/<owner>/<repo>/dispatches",
    crowdDispatchEventType = "crowd-report",
    crowdAuthHeaderName   = "Authorization",
    crowdAuthHeaderValue  = "Bearer <token>",
  )
  ```
  The client then POSTs the GitHub `repository_dispatch` **envelope**
  `{"event_type":"crowd-report","client_payload":{…report…}}` with the GitHub headers
  (`crowd::request_body` / `submit_report` handle this; host-tested by
  `request_body_bare_vs_dispatch_envelope`). Every report carries an **anonymous per-install
  `reporter_id`** (a persisted UUID the `SpamShield` facade generates) so consensus can count
  distinct reporters.
- **Provider endpoint (bare mode):** leave `crowdDispatchEventType` empty and point
  `crowdReportUrl` at any endpoint you control that accepts a bare `CrowdReport` and runs
  `ingest.py`. This is the path an **SMS provider hosting their own** feed uses.

The **token** for either path must have `contents:write` on the repo — use a **fine-grained PAT
scoped to just this repo** or a **GitHub App installation token**. A leaked dispatch token only lets
an attacker *submit* reports (still subject to consensus), not write `feed.json` directly.

## Security model (what actually holds)
- **Consensus (always on):** N distinct `reporter_id`s required. Reports with no id collapse to a
  single `anon` vote, so anonymous submissions **cannot reach the threshold alone** (verified in
  `ingest.py`'s tests). Raise `CROWD_CONSENSUS_THRESHOLD` for a stricter feed.
- **Re-classification (optional):** set env `CLASSIFY_CMD` to a command that reads a de-identified
  message skeleton on stdin and prints a verdict; `ingest.py` then drops any report whose skeleton
  the classifier doesn't call SPAM (fail-closed). Requires the client to also send the PII-stripped
  `text` skeleton (not sent today). Wire the real engine here to avoid a re-implementation.
- **Attestation (optional):** verify a Play Integrity / App Attest token **in the workflow** before
  `ingest.py` runs (drop the event if invalid); the verification key lives in GitHub Secrets. This
  is what makes `reporter_id`s hard to forge at scale.

## Config knobs
- `CROWD_CONSENSUS_THRESHOLD` (workflow env, default `3`) — votes needed to publish.
- `CLASSIFY_CMD` (optional) — enable the re-classification gate.
- Repo **Settings → Actions → Workflow permissions → Read and write** (or the `permissions:` block
  already in the workflow) so the bot can commit the feed.

## Status
`ingest.py` consensus/promotion/dedup/anti-anon-flood/malformed-reject logic is **host-tested**
(run the scenario in the repo history, or `python3 ingest.py --payload - …`). The **live GitHub
Actions run is UNVERIFIED** here (Actions runs on GitHub, not in this environment) — enable the
workflow and send a test `repository_dispatch` to exercise it end-to-end.
