package io.github.puluceno.ari;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.json.JsonMapper;

/** OpenAI-compatible endpoint, so any chat UI (Open WebUI here) can talk to Ari. */
@RestController
class ChatController {

    private static final Logger log = LoggerFactory.getLogger("audit");

    // Demo directory. In production the organisation comes from the SSO token. The presenter plays an Acme user.
    private static final Map<String, String> TENANT_BY_EMAIL_DOMAIN = Map.of(
            "acme.example", "acme-corp", "ripple.com", "acme-corp", "globex.example", "globex");

    private final Ari ari;
    private final JsonMapper json;
    private final byte[] expectedAuth;

    ChatController(Ari ari, JsonMapper json, @Value("${HUB_API_KEY}") String apiKey) {
        this.ari = ari;
        this.json = json;
        this.expectedAuth = ("Bearer " + apiKey).getBytes(UTF_8);
    }

    /** Lists one model, "ari", so the chat UI shows it in its picker. */
    @GetMapping("/v1/models")
    Map<String, Object> models(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        requireApiKey(auth);
        return Map.of("data", List.of(Map.of("id", "ari", "object", "model")));
    }

    /** Checks the API key, maps the user's email to a tenant, and returns Ari's answer as JSON or one SSE event. */
    @PostMapping("/v1/chat/completions")
    ResponseEntity<String> chat(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = "X-OpenWebUI-User-Email", required = false) String email,
            @RequestBody ChatRequest request) {
        requireApiKey(auth);
        // trusts the email header because only Open WebUI holds HUB_API_KEY. Production verifies a signed SSO token.
        String tenant = email == null ? null : TENANT_BY_EMAIL_DOMAIN.get(email.substring(email.indexOf('@') + 1).toLowerCase());
        if (tenant == null) {
            log.warn("step=identity result=rejected reason=unknown-organisation email={}", email);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User belongs to no known organisation");
        }

        String answer = ari.handle(new Ari.User(email, tenant), request.messages());

        var message = Map.of("role", "assistant", "content", answer);
        if (!Boolean.TRUE.equals(request.stream())) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(Map.of(
                    "object", "chat.completion", "model", "ari",
                    "choices", List.of(Map.of("index", 0, "message", message, "finish_reason", "stop")))));
        }
        // The whole answer in one event: it must pass the number check before anything is sent.
        String chunk = json.writeValueAsString(Map.of(
                "object", "chat.completion.chunk", "model", "ari",
                "choices", List.of(Map.of("index", 0, "delta", message, "finish_reason", "stop"))));
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body("data: " + chunk + "\n\ndata: [DONE]\n\n");
    }

    private void requireApiKey(String auth) {
        if (auth == null || !MessageDigest.isEqual(expectedAuth, auth.getBytes(UTF_8))) {
            log.warn("step=identity result=rejected reason=invalid-api-key");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API key");
        }
    }

    /** The fields Ari reads from an OpenAI chat request; the rest are ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatRequest(List<Ari.Msg> messages, Boolean stream) {
    }
}
