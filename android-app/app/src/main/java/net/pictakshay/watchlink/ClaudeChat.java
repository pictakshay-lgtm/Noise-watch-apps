package net.pictakshay.watchlink;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Duration;

/**
 * "Ask Claude" inside the app: answers questions about the synced watch data with the
 * Anthropic API, using the key the user typed into the app (stored only on the phone).
 * Blocking; call it off the main thread.
 */
final class ClaudeChat {
    static final String MODEL = "claude-opus-5";

    private static final String SYSTEM = "You are the assistant inside Watch Link, an app that syncs a Noise Icon 2 "
        + "smartwatch with the user's Android phone every 15 minutes. Answer questions about their activity, heart rate, "
        + "sleep, battery and weather using the watch data below. Be concise and practical, use plain language, and say "
        + "when the data is missing or too sparse to answer. You are not a doctor: for worrying readings, suggest seeing one.";

    private ClaudeChat() { }

    /**
     * @param apiKey   the user's Anthropic API key
     * @param history  [{role: "user"|"assistant", text}], oldest first, ending with the new question
     * @param dataDigest WatchData.summary() output
     */
    static String ask(String apiKey, JSONArray history, String dataDigest) {
        AnthropicClient client = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(Duration.ofMinutes(2))
            .build();
        try {
            MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(4000L)
                .system(SYSTEM + "\n\nWatch data:\n" + dataDigest)
                // If a safety classifier declines the request, let the API retry it on its
                // recommended fallback model instead of returning a refusal.
                .addBeta("server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
            for (int i = 0; i < history.length(); i++) {
                JSONObject m = history.optJSONObject(i);
                if (m == null || m.optString("text").isEmpty()) continue;
                if ("assistant".equals(m.optString("role"))) params.addAssistantMessage(m.optString("text"));
                else params.addUserMessage(m.optString("text"));
            }
            BetaMessage reply = client.beta().messages().create(params.build());
            if (reply.stopReason().map(r -> r.equals(BetaStopReason.REFUSAL)).orElse(false)) {
                return "Claude declined to answer that one. Try asking it a different way.";
            }
            StringBuilder out = new StringBuilder();
            for (BetaContentBlock block : reply.content()) {
                block.text().ifPresent(t -> out.append(t.text()));
            }
            return out.length() > 0 ? out.toString() : "(Claude sent an empty answer.)";
        } catch (UnauthorizedException e) {
            throw new IllegalStateException("The API key was rejected. Check it in Ask Claude settings.");
        } catch (RateLimitException e) {
            throw new IllegalStateException("Too many requests right now; wait a minute and try again.");
        } catch (AnthropicIoException e) {
            throw new IllegalStateException("Couldn't reach Claude. Check the phone's internet connection.");
        } catch (AnthropicServiceException e) {
            throw new IllegalStateException("Claude API error " + e.statusCode() + ": " + e.getMessage());
        } finally {
            client.close();
        }
    }
}
