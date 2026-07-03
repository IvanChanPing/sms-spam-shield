package com.spamshield.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * CloudAiClassifier — the CLOUD AI choice.
 *
 * WHAT / HOW IT'S CALLED
 * ----------------------
 * An [AiClassifier] that asks a developer-configured cloud LLM (any OpenAI-compatible
 * `/chat/completions` endpoint — OpenAI, Google Gemini's OpenAI-compat layer, a self-hosted
 * server, etc.) to judge the message with the [PoliticalSpamPrompt]. The app constructs one
 * with a [CloudAiConfig] and picks it as its AI backend (vs. [NanoAiClassifier]).
 *
 * WHY / WHEN
 * ----------
 * Works on ANY phone (no Nano requirement) — the trade-off is that the message text LEAVES
 * the device to the configured endpoint, and it needs an API key + network. It is OPT-IN;
 * the app must make the privacy implication clear to the user before enabling it.
 *
 * NETWORK / FAILURE
 * -----------------
 * Runs on [Dispatchers.IO] with connect/read timeouts. Any failure (not configured, non-2xx,
 * network error, unparseable reply) resolves to `false`/`null` — never a crash, never a
 * false "spam", per the [AiClassifier] contract. Uses only java.net + org.json (both bundled
 * on Android) so it adds NO third-party HTTP/JSON dependency.
 *
 * PERMISSION: the host app must declare `<uses-permission android:name="android.permission.INTERNET"/>`.
 *
 * STATUS: standard OpenAI-compatible chat-completions call; compile UNVERIFIED in the build
 * sandbox (no Android build env); the request/response shape follows the documented API.
 */
class CloudAiClassifier(
    private val config: CloudAiConfig,
) : AiClassifier {

    override val displayName: String = config.displayName

    /** Configured = we have an endpoint + a key. (We don't ping the network here.) */
    override suspend fun isAvailable(): Boolean =
        config.baseUrl.isNotBlank() && config.apiKey.isNotBlank()

    override suspend fun classify(sender: String, body: String): AiVerdict? =
        withContext(Dispatchers.IO) {
            if (!isAvailable()) return@withContext null
            val prompt = PoliticalSpamPrompt.build(sender, body)
            val requestBody = JSONObject().apply {
                put("model", config.model)
                put("temperature", 0.0) // deterministic classification
                put("max_tokens", 120)
                put(
                    "messages",
                    JSONArray().put(
                        JSONObject().put("role", "user").put("content", prompt),
                    ),
                )
            }.toString()

            var conn: HttpURLConnection? = null
            try {
                val url = URL(config.baseUrl.trimEnd('/') + "/chat/completions")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = config.connectTimeoutMs
                    readTimeout = config.readTimeoutMs
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                }
                conn.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode !in 200..299) return@withContext null
                val text = conn.inputStream.bufferedReader().use(BufferedReader::readText)

                // OpenAI-compatible: choices[0].message.content holds the model's reply.
                val content = JSONObject(text)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                PoliticalSpamPrompt.parseVerdict(content)
            } catch (e: Exception) {
                null
            } finally {
                conn?.disconnect()
            }
        }
}

/**
 * Configuration for [CloudAiClassifier]. The developer supplies these (e.g. from their
 * app's settings). Defaults target OpenAI, but ANY OpenAI-compatible endpoint works — point
 * [baseUrl] at Gemini's OpenAI-compat URL, a local server, etc.
 *
 * @property baseUrl        API base, WITHOUT the trailing `/chat/completions`
 *                          (e.g. "https://api.openai.com/v1").
 * @property apiKey         bearer token for the endpoint. Empty = backend unavailable.
 * @property model          model id (e.g. "gpt-4o-mini").
 * @property displayName    label for logs / a settings screen.
 * @property connectTimeoutMs / readTimeoutMs  network timeouts (ms).
 */
data class CloudAiConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val displayName: String = "Cloud AI",
    val connectTimeoutMs: Int = 8_000,
    val readTimeoutMs: Int = 12_000,
)
