# SMS Spam Shield — L1 AI layer (`android/spamshield-ai`)

The optional AI layer: **two independent, user-selectable AI backends** that read a message
and decide if it's unsolicited political spam. They do **not** work together — the app picks
one (or offers the user a choice). AI complements the offline Rust heuristic (L0); it is
OPTIONAL and OFF by default.

## The two choices
| Backend | Class | Privacy | Needs | Works on |
|---|---|---|---|---|
| **On-device** | `NanoAiClassifier` | nothing leaves the phone | a Nano-capable device | Pixel 8+/9/10, Galaxy S24+, … |
| **Cloud** | `CloudAiClassifier` | message text is sent to the endpoint (opt-in) | API key + network | any phone |

Both implement `AiClassifier`:
```kotlin
suspend fun isAvailable(): Boolean
suspend fun classify(sender: String, body: String): AiVerdict?   // null = no opinion → fall back to heuristic, NEVER treat as spam
```
Shared prompt + tolerant JSON parsing live in `PoliticalSpamPrompt`. The prompt encodes the
real problem (diverse-topic political/donation messages) AND an explicit "never flag" list
(2FA, bank/payment alerts, delivery, retail, appointments, personal) to protect against false
positives.

## Using it (host app)
```kotlin
// On-device:
val ai = NanoAiClassifier()
if (ai.isAvailable()) {
    val v = ai.classify(sender, body)          // AiVerdict(isSpam, confidence, reason) or null
}
// Or cloud (any OpenAI-compatible endpoint):
val ai = CloudAiClassifier(CloudAiConfig(apiKey = "sk-…", model = "gpt-4o-mini"))
```
Call off the main thread (both are `suspend`). Verdict-only — the host decides what to do.

## Build & test (needs a real Android env — NOT done in this repo's build sandbox)
Prereqs: Android Studio (or Android SDK) + JDK 17. minSdk 26.
```bash
cd android
# In Android Studio: open this folder, let it sync (it manages the Gradle wrapper).
# Or from CLI (first generate the wrapper jar, then build):
gradle wrapper --gradle-version 8.9
./gradlew :spamshield-ai:assembleRelease      # → spamshield-ai/build/outputs/aar/
```
On-device test: put `NanoAiClassifier`/`CloudAiClassifier` behind a button in a tiny app,
type in a sample political-spam text + a clean control, and read the returned `AiVerdict`.
Nano requires a Nano-capable phone; the cloud path requires a valid key.

## STATUS — honest
- **Written against the documented APIs** (ML Kit `genai-prompt:1.0.0-beta2` + a generic
  OpenAI-compatible chat call). **Compile + on-device UNVERIFIED** here — the build sandbox
  has no working Android Gradle (system Gradle is 4.4.1) and no Nano-capable device.
- **One residual to confirm on-device:** the exact accessor for Nano's non-streaming response
  text. The streaming API exposes `chunk.candidates[0].text`; `NanoAiClassifier.classify`
  reads `response.candidates.firstOrNull()?.text` to match. If the SDK exposes a convenience
  `response.text`, use that. Cross-check against the ML Kit GenAI sample when you build.
- **Import paths** for `FeatureStatus` / `DownloadStatus` (and whether `Generation.getClient()`
  takes a `Context`) follow the docs but the exact sub-package isn't pinned — Android Studio's
  auto-import will resolve these on first sync.
- Versions in the Gradle files (AGP 8.5.2, Kotlin 1.9.24, coroutines 1.8.1, compileSdk 34)
  are recent known-good defaults; bump as needed for your environment.
