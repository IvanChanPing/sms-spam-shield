package com.spamshield.ai

import com.google.mlkit.genai.prompt.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.download.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext

/**
 * NanoAiClassifier — the ON-DEVICE AI choice.
 *
 * WHAT / HOW IT'S CALLED
 * ----------------------
 * An [AiClassifier] backed by Google's on-device Gemini Nano through the ML Kit GenAI
 * "Prompt API" (`com.google.mlkit:genai-prompt`). The app constructs one and picks it as
 * its AI backend (vs. [CloudAiClassifier]). It runs the [PoliticalSpamPrompt] entirely on
 * the phone — nothing leaves the device, no API key.
 *
 * PRECONDITIONS (surfaced, not silent)
 * ------------------------------------
 * - The device must ship Gemini Nano via AICore (Pixel 8+/9/10, Galaxy S24+, some others).
 *   On other devices [isAvailable] returns false and the app should use the cloud choice or
 *   fall back to the heuristic only.
 * - The Nano model is provisioned + owned by the OS (AICore), so this adds ZERO storage to
 *   the app's APK. It may need a one-time on-device download ([downloadIfNeeded]).
 * - minSdk 26 (API 26+), required by the Prompt API.
 *
 * THREADING / FAILURE
 * -------------------
 * Both calls run on [Dispatchers.IO] and never touch the main thread. Any error (model not
 * present, generation failure, unparseable reply) resolves to `false`/`null` — never a crash
 * and never a false "spam", per the [AiClassifier] contract.
 *
 * STATUS: written against genai-prompt 1.0.0-beta2 docs; compile + on-device UNVERIFIED in
 * the build sandbox (needs a Nano-capable device). One residual to confirm on-device: the
 * exact accessor for the non-streaming response text (see [classify]).
 */
class NanoAiClassifier(
    private val model: GenerativeModel = Generation.getClient(),
) : AiClassifier {

    override val displayName: String = "On-device (Gemini Nano)"

    /** AVAILABLE = the model is present and ready to run right now. */
    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            model.checkStatus() == FeatureStatus.AVAILABLE
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Trigger the one-time on-device model download if the device supports Nano but hasn't
     * fetched it yet. Returns true once the model is AVAILABLE. Call this from a settings
     * action (it can take a while + use network); [classify] does NOT auto-download.
     */
    suspend fun downloadIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        try {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> true
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                    var completed = false
                    model.download()
                        .catch { /* swallow: report via the returned boolean below */ }
                        .collect { status ->
                            if (status is DownloadStatus.DownloadCompleted) completed = true
                        }
                    completed || model.checkStatus() == FeatureStatus.AVAILABLE
                }
                else -> false // UNAVAILABLE: device doesn't support Nano
            }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun classify(sender: String, body: String): AiVerdict? =
        withContext(Dispatchers.IO) {
            try {
                if (model.checkStatus() != FeatureStatus.AVAILABLE) return@withContext null
                val response = model.generateContent(PoliticalSpamPrompt.build(sender, body))
                // NOTE (verify on-device): the streaming API exposes text as
                // chunk.candidates[0].text; the non-streaming response mirrors that. If the
                // SDK exposes a convenience `response.text`, prefer it here.
                val text = response.candidates.firstOrNull()?.text
                PoliticalSpamPrompt.parseVerdict(text)
            } catch (e: Exception) {
                null
            }
        }
}
