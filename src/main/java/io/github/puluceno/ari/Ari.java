package io.github.puluceno.ari;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.github.puluceno.ari.DomainAgent.Answer;
import tools.jackson.databind.json.JsonMapper;

/** The hub. The model proposes a route and words the answer; this code decides everything else. */
@Service
class Ari {

    static final String TICKET_OFFER = "Reply **yes** and I will raise a support ticket with this conversation attached.";
    static final String REFUSED = "I can't help with that. I can answer questions about your treasury data or how to use the platform.";
    static final String CLARIFY = "Is this about **your data** (cash, exposure, forecasts), **how to do something** in the platform, or **talking to support**?";
    static final String DECLINED = "I can't make changes from chat: approving or paying needs your approval workflow. "
            + "Please do it in the app: https://app.treasury.example";

    //  regex stand-in for Azure Prompt Shields. The real control is that the model has no authority.
    private static final Pattern INJECTION = Pattern.compile(
            "ignore (all |any )?(previous|prior|above) instructions|system prompt|developer mode", Pattern.CASE_INSENSITIVE);
    private static final Pattern YES = Pattern.compile("(?i)\\s*(yes|yes please|confirm|go ahead)[.!]*\\s*");
    // A number, optionally followed by a scale word. "4.3 million" is a rewritten figure: the data never says million.
    private static final Pattern NUMBER = Pattern.compile(
            "(\\d[\\d,]*(?:\\.\\d+)?)(\\s*(?:thousand|million|billion|bn|mn|[km])\\b)?", Pattern.CASE_INSENSITIVE);

    private static final String COMPOSE_PROMPT = """
            You are Ari, the assistant of a treasury platform. Answer the QUESTION using ONLY the DATA.
            - Copy every number exactly as written, with its unit. Never calculate, round or convert.
            - Start by restating what was measured. End with the source and its as-of time.
            - If asked WHY, report what the data shows and say it is not a full causal explanation.
            - The user belongs to %s. If they ask about another organisation, say you can only show theirs.
            - Be brief.""";

    private static final Logger log = LoggerFactory.getLogger("audit");

    private final Model model;
    private final Map<String, DomainAgent> domains;
    private final MockTicketSystem tickets;
    private final JsonMapper json;
    private final String routerPrompt;

    /** The router prompt is built from the registered domains, so adding a domain needs no change here. */
    Ari(Model model, List<DomainAgent> domains, MockTicketSystem tickets, JsonMapper json) {
        this.model = model;
        this.domains = domains.stream().collect(Collectors.toMap(DomainAgent::id, Function.identity()));
        this.tickets = tickets;
        this.json = json;
        this.routerPrompt = "Route the user's message. Targets:\n"
                + domains.stream().map(d -> "- " + d.id() + ": " + d.description() + "\n").collect(Collectors.joining())
                + "- human: wants a person, support or a ticket\n"
                + "- action: wants Ari to DO something that changes data (approve, pay, submit, delete)\n"
                + "- clarify: ambiguous or off-topic\n"
                + "Reply with JSON only: {\"target\": \"...\", \"confidence\": 0.0-1.0, \"reason\": \"<one short sentence>\"}";
    }

    /**
     * Answers the last message of the conversation, with the audit trail appended.
     * Model, domain and ticket failures become replies, not exceptions.
     */
    String handle(User user, List<Msg> messages) {
        var trail = new Trail(UUID.randomUUID().toString().substring(0, 8), System.nanoTime(), new ArrayList<>());
        trail.step("received", "user=%s tenant=%s question=\"%s\"", user.email(), user.tenant(), messages.getLast().content());
        Reply reply = decide(user, messages, trail);
        trail.step("done", "outcome=%s total_ms=%d", reply.outcome(), trail.ms());
        // shown to everyone for the demo. In production only support staff would see this.
        return reply.text() + "\n\n<details>\n<summary>How Ari decided</summary>\n\n```\n"
                + String.join("\n", trail.lines()) + "\n```\n</details>";
    }

    /** Guard, then the "yes" check, then the model proposes a route and this code decides what happens. */
    private Reply decide(User user, List<Msg> messages, Trail trail) {
        String question = messages.getLast().content();
        if (INJECTION.matcher(question).find()) {
            trail.step("guard", "result=refused reason=injection-pattern model_called=false");
            return new Reply("refused", REFUSED);
        }
        trail.step("guard", "result=passed");
        if (YES.matcher(question).matches() && messages.size() >= 3
                && messages.get(messages.size() - 2).content().contains(TICKET_OFFER)) {
            trail.step("decide", "action=create-ticket reason=user-confirmed-offer model_called=false");
            return createTicket(user, messages, trail);
        }

        Route route;
        long started = System.nanoTime();
        try {
            route = json.readValue(model.complete(routerPrompt, question, true), Route.class);
        } catch (RuntimeException e) {
            trail.step("route", "result=failed error=\"%s\"", e.getMessage());
            return new Reply("model_down", "I'm having trouble right now. " + TICKET_OFFER);
        }
        trail.step("route", "proposed=%s confidence=%.2f reason=\"%s\" ms=%d",
                route.target(), route.confidence(), route.reason(), (System.nanoTime() - started) / 1_000_000);

        if (route.target() == null || route.confidence() < 0.6) {
            trail.step("decide", "action=clarify reason=confidence-below-0.6");
            return new Reply("clarify", CLARIFY);
        }
        DomainAgent domain = domains.get(route.target());
        if (domain != null) {
            trail.step("decide", "action=call-domain domain=%s registered=%s", domain.id(), domains.keySet());
            return answer(domain, question, user, trail);
        }
        Reply reply = switch (route.target()) {
            case "human" -> new Reply("ticket_offered", "Of course. " + TICKET_OFFER);
            case "action" -> new Reply("declined", DECLINED);
            default -> new Reply("clarify", CLARIFY);
        };
        trail.step("decide", "action=%s", reply.outcome());
        return reply;
    }

    /** Calls the domain for this user's tenant, has the model word the data, and falls back to the raw data. */
    private Reply answer(DomainAgent domain, String question, User user, Trail trail) {
        Optional<Answer> found;
        long started = System.nanoTime();
        try {
            found = domain.answer(question, user.tenant());
        } catch (RuntimeException e) {
            trail.step("domain", "domain=%s tenant=%s result=error error=\"%s\"", domain.id(), user.tenant(), e.getMessage());
            return new Reply("domain_down", "I couldn't reach " + domain.id() + " just now, so I haven't answered rather than guess. " + TICKET_OFFER);
        }
        if (found.isEmpty()) {
            trail.step("domain", "domain=%s tenant=%s result=no-answer", domain.id(), user.tenant());
            return new Reply("no_answer", "I couldn't find that in " + domain.id() + ". " + TICKET_OFFER);
        }
        Answer data = found.get();
        trail.step("domain", "domain=%s tenant=%s result=found interpretation=\"%s\" ms=%d",
                domain.id(), user.tenant(), data.interpretation(), (System.nanoTime() - started) / 1_000_000);
        String evidence = data.interpretation() + "\n" + data.data() + "\n" + data.source();
        started = System.nanoTime();
        try {
            String draft = model.complete(COMPOSE_PROMPT.formatted(user.tenant()),
                    "QUESTION: " + question + "\n\nDATA:\n" + evidence, false);
            trail.step("compose", "ms=%d", (System.nanoTime() - started) / 1_000_000);
            List<String> unsupported = unsupportedNumbers(draft, evidence + question);
            if (!unsupported.isEmpty()) {
                // The rejected values stay in the server log: printing them in the chat would show what the guard blocked.
                log.warn("turn={} rejected numbers {}", trail.turn(), unsupported);
                trail.step("number-check", "result=failed unsupported_numbers=%d action=show-raw-data", unsupported.size());
                return new Reply("number_check_failed", rawData(data, "I couldn't verify every number in my summary, so here is the source data."));
            }
            trail.step("number-check", "result=passed");
            return new Reply("answered", draft);
        } catch (RuntimeException e) {
            trail.step("compose", "result=failed error=\"%s\" action=show-raw-data", e.getMessage());
            return new Reply("compose_failed", rawData(data, "Summary unavailable, so here is the source data."));
        }
    }

    /** The problem is the user's message before the offer; the whole conversation is attached. */
    private Reply createTicket(User user, List<Msg> messages, Trail trail) {
        String problem = messages.get(messages.size() - 3).content();
        try {
            String key = tickets.create(user.email(), problem, messages);
            trail.step("ticket", "result=created key=%s messages_attached=%d", key, messages.size());
            return new Reply("ticket_created", "Done: I raised **" + key + "** with this conversation attached, so you won't need to repeat it.");
        } catch (RuntimeException e) {
            trail.step("ticket", "result=failed error=\"%s\"", e.getMessage());
            return new Reply("ticket_failed", "I couldn't create the ticket and nothing was submitted. Please email support@treasury.example.");
        }
    }

    /** Numbers in the answer that are not in the evidence. Whole numbers under 10 are list markers and days. */
    static List<String> unsupportedNumbers(String answer, String evidence) {
        Set<BigDecimal> known = NUMBER.matcher(evidence).results().map(m -> number(m.group(1))).collect(Collectors.toSet());
        return NUMBER.matcher(answer).results().filter(m -> m.group(2) != null
                || (m.group(1).contains(".") || number(m.group(1)).compareTo(BigDecimal.TEN) >= 0)
                        && !known.contains(number(m.group(1))))
                .map(m -> m.group().strip()).toList();
    }

    // 4,215,380.00 and 4215380 are the same number.
    private static BigDecimal number(String text) {
        return new BigDecimal(text.replace(",", "")).stripTrailingZeros();
    }

    private static String rawData(Answer data, String note) {
        return "**" + data.interpretation() + "**\n\n" + data.data().replace("\n", "  \n") + "\n\n" + data.source() + "\n\n_" + note + "_";
    }

    /** Who is asking. The tenant decides which organisation's data the domains return. */
    record User(String email, String tenant) {
    }

    /** One chat message, as the OpenAI API sends it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Msg(String role, String content) {
    }

    /** The model's proposal. Only a suggestion: decide() checks it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Route(String target, double confidence, String reason) {
    }

    /** outcome is the audit label; text is what the user sees. */
    private record Reply(String outcome, String text) {
    }

    /** One audit line per step, all tagged with the same turn id: grep the id to replay a decision. */
    private record Trail(String turn, long startedNanos, List<String> lines) {
        void step(String step, String detail, Object... args) {
            String line = "step=" + step + " " + detail.formatted(args);
            lines.add(line);
            log.info("turn={} {}", turn, line);
        }

        long ms() {
            return (System.nanoTime() - startedNanos) / 1_000_000;
        }
    }
}
