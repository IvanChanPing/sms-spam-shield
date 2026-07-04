package com.spamshield

import android.content.Context
import uniffi.spam_shield.SpamConfig
import uniffi.spam_shield.SpamFeedKind
import uniffi.spam_shield.SpamFeedSource
import uniffi.spam_shield.SpamLevel
import uniffi.spam_shield.spamClassify
import uniffi.spam_shield.spamConfigure
import uniffi.spam_shield.spamRefreshCrowd
import uniffi.spam_shield.spamRefreshFeeds
import uniffi.spam_shield.spamReportSpam

/**
 * SpamShield — the one public entry point a host SMS app uses.
 *
 * WHAT THIS IS
 * -----------
 * A thin, clean Kotlin facade over the `spam_shield` Rust engine (exposed via its
 * UniFFI-generated `uniffi.spam_shield` bindings). It hides the FFI record/enum shapes behind
 * plain Kotlin types so dropping the library into an app is a few lines — see README "Quick
 * start". Flag-only: [classify] returns a [Verdict]; the host decides what to do with it
 * (badge, spam folder, silence, auto-hide) — the library never blocks delivery.
 *
 * HOW IT'S CALLED (the whole surface)
 * -----------------------------------
 *   1. [configure] once (app start / when settings change) — persists nothing itself; it hands
 *      the engine its toggles + the on-disk cache path (derived from `context.filesDir`).
 *   2. [scheduleAutoRefresh] once — enqueues a periodic WorkManager job so the threat feeds and
 *      the crowd feed self-refresh with ZERO per-boot manual action.
 *   3. [classify] on each incoming message (off the main thread) → [Verdict].
 *   4. [report] when the user/app confirms a spam → uploads a privacy-preserving fingerprint to
 *      the crowd feed (raw text never leaves the device). Opt-in; no-op if not configured.
 *
 * THREADING: [classify]/[report]/[refreshNow] are `suspend` (they may touch disk/network) — call
 * from a coroutine on a background dispatcher. [configure] is cheap/sync.
 *
 * STATUS: written against the UniFFI 0.28 Kotlin codegen for this crate. COMPILE-UNVERIFIED here
 * (no Android/Gradle build env in the sandbox). Residuals to confirm when the bindings are
 * generated (`uniffi-bindgen … --language kotlin`): the exact generated package
 * (`uniffi.spam_shield`), the enum entry spelling of `SpamLevel`, and that `spamClassify`/
 * `spamReportSpam`/`spamRefreshFeeds`/`spamRefreshCrowd` are emitted as `suspend` funs (they are
 * `#[uniffi::export(async_runtime = "tokio")]`). The `.so` (built via cargo-ndk) + the JNA
 * runtime must be on the app classpath.
 */
object SpamShield {

    /** Kind of a threat-feed's lines. Mirrors the engine's `SpamFeedKind`. */
    enum class FeedKind { URLS, HOSTS }

    /** One downloadable threat feed (L2). For keyed feeds the key is already in [url]. */
    data class Feed(val name: String, val url: String, val kind: FeedKind)

    /** Severity of a [Verdict]. */
    enum class Level { CLEAN, SUSPICIOUS, SPAM, SCAM }

    /** The classification result. [level] == CLEAN means "no opinion / looks fine". */
    data class Verdict(
        val level: Level,
        val score: Int,
        val reasons: List<String>,
        val matchedSource: String?,
    ) {
        val isSpam: Boolean get() = level == Level.SPAM || level == Level.SCAM
    }

    /**
     * Host-supplied configuration. Sensible privacy-first defaults: online lookups OFF, crowd
     * feed OFF (both opt-in). Set only what you use.
     */
    data class Config(
        /** Master toggle. When false, [classify] always returns CLEAN. */
        val enabled: Boolean = true,
        /** Threat feeds to download (L2). Empty = none. */
        val feeds: List<Feed> = emptyList(),
        /** Senders never flagged by the political heuristic (e.g. "Eventbrite", your bank). */
        val trustedSenders: List<String> = emptyList(),
        /** Opt-in crowd feed: URL to GET the shared fingerprint feed. Empty = off. */
        val crowdFeedUrl: String = "",
        /** Opt-in crowd feed: URL to POST reports to. Empty = no upload. */
        val crowdReportUrl: String = "",
        /** Optional header sent on crowd calls (API key / attestation token). */
        val crowdAuthHeaderName: String = "",
        val crowdAuthHeaderValue: String = "",
        /** Opt-in online layer (Safe Browsing / number reputation). Off by default. */
        val onlineEnabled: Boolean = false,
        val safeBrowsingApiKey: String = "",
    )

    /** Absolute path to the engine's JSON cache (survives restart). Under the app's filesDir. */
    private fun cachePath(context: Context): String =
        context.filesDir.resolve("spamshield-cache.json").absolutePath

    /** Configure the engine. Call at app start and whenever [Config] changes. Cheap + sync. */
    fun configure(context: Context, config: Config) {
        spamConfigure(config.toFfi(cachePath(context)))
    }

    /**
     * Classify one incoming message. [isKnownContact] must be supplied by the host (the library
     * never reads the address book) — a saved contact is never flagged. Suspend: run off-main.
     */
    suspend fun classify(sender: String, body: String, isKnownContact: Boolean = false): Verdict =
        spamClassify(body, sender, isKnownContact).toVerdict()

    /**
     * Report a message the user/app confirmed as spam to the crowd feed. Builds a
     * privacy-preserving fingerprint (raw text never leaves the device) and uploads it. Returns
     * true on success; a safe no-op (false) if the crowd feed isn't configured. Suspend.
     */
    suspend fun report(sender: String, body: String): Boolean = spamReportSpam(body, sender)

    /** Refresh threat feeds + crowd feed once, now. Returns true if anything installed. Suspend. */
    suspend fun refreshNow(): Boolean {
        val feeds = spamRefreshFeeds().ok
        val crowd = spamRefreshCrowd()
        return feeds || crowd
    }

    /** Enqueue the self-starting periodic refresh (see [SpamRefreshWorker]). Idempotent. */
    fun scheduleAutoRefresh(context: Context) = SpamRefreshWorker.schedule(context)

    // ---- mapping between the clean facade types and the generated FFI types ----

    private fun Config.toFfi(cachePath: String) = SpamConfig(
        enabled = enabled,
        onlineEnabled = onlineEnabled,
        cachePath = cachePath,
        feeds = feeds.map { SpamFeedSource(it.name, it.url, it.kind.toFfi()) },
        safebrowsingApiKey = safeBrowsingApiKey,
        numberReputationUrlTemplate = "",
        numberReputationFlagSubstring = "",
        numberReputationHeaderName = "",
        numberReputationHeaderValue = "",
        crowdEnabled = crowdFeedUrl.isNotEmpty() || crowdReportUrl.isNotEmpty(),
        crowdFeedUrl = crowdFeedUrl,
        crowdReportUrl = crowdReportUrl,
        crowdAuthHeaderName = crowdAuthHeaderName,
        crowdAuthHeaderValue = crowdAuthHeaderValue,
        trustedSenders = trustedSenders,
    )

    private fun FeedKind.toFfi() = when (this) {
        FeedKind.URLS -> SpamFeedKind.URLS
        FeedKind.HOSTS -> SpamFeedKind.HOSTS
    }

    private fun uniffi.spam_shield.SpamVerdict.toVerdict() = Verdict(
        level = when (level) {
            SpamLevel.CLEAN -> Level.CLEAN
            SpamLevel.SUSPICIOUS -> Level.SUSPICIOUS
            SpamLevel.SPAM -> Level.SPAM
            SpamLevel.SCAM -> Level.SCAM
        },
        score = score.toInt(),
        reasons = reasons,
        matchedSource = matchedSource,
    )
}
