package com.operonix.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * Google Gemini implementation of {@link LlmProvider}.
 *
 * <p>Uses the plain {@code generateContent} REST endpoint rather than an SDK: one HTTP call, no
 * additional dependency, and the request shape the same model served by Google AI Studio expects.
 * Authentication is a query parameter, so the key never appears in a log line written here.
 */
@Component
public class GeminiProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiProvider.class);

    private final AiProperties properties;
    private final ObjectMapper mapper;
    private final RestClient client;

    public GeminiProvider(AiProperties properties, ObjectMapper mapper,
                          RestClient.Builder builder) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(
                        java.net.http.HttpClient.newBuilder()
                                .connectTimeout(properties.timeout())
                                .build()))
                .build();
    }

    @Override
    public String name() {
        return "gemini";
    }

    @Override
    public String model() {
        return properties.model();
    }

    @Override
    public boolean configured() {
        return properties.usable();
    }

    @Override
    public Completion complete(List<Message> messages, int maxOutputTokens) {
        if (!configured()) {
            throw new LlmUnavailableException("Gemini is not configured");
        }
        // Gemini takes one system instruction plus the user turns.
        String system = messages.stream().filter(message -> message.role() == Role.SYSTEM)
                .map(Message::text).reduce((left, right) -> left + "\n\n" + right).orElse("");
        ArrayNode contents = mapper.createArrayNode();
        for (Message message : messages) {
            if (message.role() != Role.USER) {
                continue;
            }
            ObjectNode turn = contents.addObject();
            turn.put("role", "user");
            turn.putArray("parts").addObject().put("text", message.text());
        }

        ObjectNode body = mapper.createObjectNode();
        body.set("contents", contents);
        if (!system.isBlank()) {
            ObjectNode instruction = body.putObject("systemInstruction");
            instruction.putArray("parts").addObject().put("text", system);
        }
        ObjectNode generation = body.putObject("generationConfig");
        generation.put("temperature", 0.2);
        generation.put("maxOutputTokens", Math.max(64, maxOutputTokens));

        String path = "/v1beta/models/" + properties.model() + ":generateContent";

        try {
            String raw = client.post()
                    .uri(uri -> uri.path(path).queryParam("key", properties.apiKey()).build())
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode response = mapper.readTree(raw == null ? "{}" : raw);
            String text = response.path("candidates").path(0).path("content").path("parts")
                    .path(0).path("text").asText("");
            if (text.isBlank()) {
                throw new LlmUnavailableException("Gemini returned an empty response");
            }
            int inputTokens = response.path("usageMetadata").path("promptTokenCount").asInt(0);
            int outputTokens = response.path("usageMetadata").path("candidatesTokenCount").asInt(0);
            return new Completion(text.trim(), inputTokens, outputTokens, properties.model());
        } catch (LlmUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            // Never surface the provider's response body: it can echo the request URL.
            log.warn("Gemini call failed: {}", ex.getClass().getSimpleName());
            throw new LlmUnavailableException("The AI provider could not be reached", ex);
        }
    }

    /** Exposed for diagnostics: the configured timeout, never the key. */
    public Duration timeout() {
        return properties.timeout();
    }
}
