package io.github.puluceno.ari;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.json.JsonMapper;

/** Azure OpenAI chat completions: one POST, no SDK. */
@Component
class AzureModel implements Model {

    private final RestClient http;
    private final JsonMapper json;
    private final String url;
    private final String apiKey;
    private final String deployment;

    AzureModel(JsonMapper json,
            @Value("${AZURE_AI_CHAT_COMPLETIONS_URL}") String url,
            @Value("${AZURE_AI_API_KEY}") String apiKey,
            @Value("${AZURE_AI_DEPLOYMENT:gpt-6-luna}") String deployment) {
        var timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(10));
        timeouts.setReadTimeout(Duration.ofSeconds(30));
        this.http = RestClient.builder().requestFactory(timeouts).build();
        this.json = json;
        this.url = url;
        this.apiKey = apiKey;
        this.deployment = deployment;
    }

    @Override
    public String complete(String systemPrompt, String userMessage, boolean jsonOutput) {
        var body = new HashMap<String, Object>(Map.of(
                "model", deployment,
                "reasoning_effort", "low",
                "max_completion_tokens", 4000,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage))));
        if (jsonOutput) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        String raw = http.post().uri(url)
                .header("api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(body))
                .retrieve()
                // Keep Azure's error body: a bare status code tells you nothing.
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new IllegalStateException("Azure " + response.getStatusCode() + ": "
                            + new String(response.getBody().readAllBytes(), UTF_8));
                })
                .body(String.class);
        return json.readValue(raw, Completion.class).choices().getFirst().message().content();
    }

    /** Only the path Ari reads from Azure's response: choices[0].message.content. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Completion(List<Choice> choices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(Message message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String content) {
    }
}
