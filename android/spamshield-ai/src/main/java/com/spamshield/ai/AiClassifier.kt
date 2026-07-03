package com.spamshield.ai

/**
 * AiClassifier — the optional L1 "AI reads the message and decides" layer of SMS Spam
 * Shield.
 *
 * WHAT THIS IS
 * -----------
 * A tiny, pluggable interface with TWO independent, user-selectable implementations
 * (they do NOT work together — the user/app picks one):
 *   1. [NanoAiClassifier]  — ON-DEVICE Gemini Nano via ML Kit GenAI Prompt API. Private
 *                            (nothing leaves the phone), no API key, but only on phones
 *                            that ship Nano (Pixel 8+, Galaxy S24+, …). Zero app storage
 *                            (the model is owned by the OS / AICore).
 *   2. [CloudAiClassifier] — a developer-configured CLOUD LLM (any OpenAI-compatible
 *                            chat endpoint: OpenAI, Gemini, a local server, …). Works on
 *                            ANY phone, needs a key + network, and the message text LEAVES
 *                            the device (opt-in, documented).
 *
 * WHY AI AT ALL (it complements, doesn't replace, the Rust heuristic)
 * -----------------------------------------------------------------
 * The offline heuristic (L0, in the `spam_shield` Rust engine) already catches political
 * spam that carries a donation ask + political language. The AI's job is the HARDER case
 * the rulebook struggles with: unsolicited political messages on wildly varied topics that
 * are "always trying to get you invested in the election / to donate" but phrase it in ways
 * no fixed keyword list anticipates. A zero-shot "is this political spam?" generalises
 * across topics. It is OPTIONAL and OFF by default; the app decides whether to run it.
 *
 * CONTRACT
 * --------
 * [classify] returns an [AiVerdict] or `null`. `null` means "couldn't decide" (model not
 * available/downloaded, network/API error, unparseable output) — the caller should treat
 * that as "no AI opinion" and fall back to the heuristic verdict, NEVER as spam. Both
 * methods are `suspend` and must be called off the main thread; they never block delivery.
 *
 * HOW TO TEST — build the `spamshield-ai` module in Android Studio and drive it from the
 * sample app on a real device (Nano needs a Nano-capable phone; Cloud needs a key). See
 * docs/AI_LAYER.md. STATUS: written against the documented ML Kit GenAI Prompt API
 * (genai-prompt 1.0.0-beta2) + a generic OpenAI-compatible cloud call; compile + on-device
 * UNVERIFIED here (no Android build env / no Nano device in the build sandbox).
 */
interface AiClassifier {

    /** A short human-readable name for logs / a settings screen (e.g. "On-device (Gemini Nano)"). */
    val displayName: String

    /**
     * Whether this AI is usable right now. Nano: the model is downloaded + available.
     * Cloud: an endpoint + key are configured. Cheap; safe to call before [classify].
     */
    suspend fun isAvailable(): Boolean

    /**
     * Ask the AI whether [body] (from [sender]) is unsolicited political spam.
     * Returns `null` if the AI could not produce a usable answer (see class contract).
     */
    suspend fun classify(sender: String, body: String): AiVerdict?
}

/**
 * One AI opinion about a message.
 *
 * @property isSpam     true = the AI judged it unsolicited political spam.
 * @property confidence 0.0–1.0 self-reported confidence (advisory only).
 * @property reason     short human-readable justification (for diagnostics / a badge).
 */
data class AiVerdict(
    val isSpam: Boolean,
    val confidence: Float,
    val reason: String,
)

/**
 * The shared instruction both backends send to their model. It encodes the user's real
 * definition of the problem (diverse-topic political messages that push donation /
 * election engagement) AND the product's #1 rule — DO NOT create false positives — via an
 * explicit "never flag" list. The model is asked for a strict JSON object so the reply is
 * machine-parseable by [parseVerdict].
 */
object PoliticalSpamPrompt {

    /** Build the full prompt for one message. Kept tiny (SMS is well under the 4k-token cap). */
    fun build(sender: String, body: String): String =
        """
        You are a spam filter for a phone's text messages. Decide whether ONE SMS is
        UNSOLICITED POLITICAL SPAM: a message from a political campaign, PAC, party, or
        advocacy group — usually from an unknown number — trying to get the recipient to
        DONATE money, take political action, or become emotionally invested in an election.
        These messages cover many different topics but share that intent.

        Do NOT flag (these are NOT political spam):
        - personal messages from a real person
        - 2FA / one-time verification codes
        - appointment or reservation reminders
        - bank, card, or payment-app alerts (balance, charges, fraud/suspicious activity)
        - package / delivery notifications
        - retail or store promotions and sales
        - newsletters or event reminders the user clearly signed up for

        Respond with ONLY a compact JSON object and nothing else:
        {"spam": true or false, "confidence": a number from 0 to 1, "reason": "a short reason"}

        The SMS is from "$sender":
        "$body"
        """.trimIndent()

    /**
     * Parse a model's raw text reply into an [AiVerdict]. Tolerant: it locates the first
     * JSON object in the text (models sometimes wrap it in prose/markdown) and reads the
     * fields defensively. Returns `null` if no usable JSON/`spam` field is found.
     */
    fun parseVerdict(raw: String?): AiVerdict? {
        if (raw.isNullOrBlank()) return null
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val json = org.json.JSONObject(raw.substring(start, end + 1))
            if (!json.has("spam")) return null
            val isSpam = json.optBoolean("spam", false)
            val confidence = json.optDouble("confidence", if (isSpam) 0.6 else 0.4)
                .toFloat().coerceIn(0f, 1f)
            val reason = json.optString("reason", "").ifBlank {
                if (isSpam) "AI flagged as political spam" else "AI: not political spam"
            }
            AiVerdict(isSpam, confidence, reason)
        } catch (e: org.json.JSONException) {
            null
        }
    }
}
