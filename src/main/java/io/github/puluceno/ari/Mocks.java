package io.github.puluceno.ari;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// Everything fake lives in this file. Each mock stands where the real integration would plug in.

/** Omni, the analytics layer. Data is filed by organisation: there is no path to another tenant's rows. */
@Component
class MockOmni implements DomainAgent {

    private static final Map<String, Map<String, Answer>> DATA = Map.of(
            "acme-corp", Map.of(
                    "eur", new Answer("Net EUR exposure, all Acme Corp entities, 1-4 Oct 2026",
                            "Net EUR exposure: 4,215,380.00 EUR\nHedged: 2,950,000.00 EUR\nUnhedged: 1,265,380.00 EUR",
                            "[Omni: FX exposure](https://app.treasury.example/omni/fx-exposure), as of 2026-10-04 08:00 UTC"),
                    "march", new Answer("Forecast vs actual closing cash, March 2026, all Acme Corp entities",
                            "Forecast: 12,400,000.00 USD\nActual: 10,880,000.00 USD\nVariance: -1,520,000.00 USD (-12.3 %)\n"
                                    + "Largest contributor: customer receipts later than forecast, -1,100,000.00 USD\n"
                                    + "Second: FX revaluation, -420,000.00 USD",
                            "[Omni: Forecast accuracy](https://app.treasury.example/omni/forecast), as of 2026-04-02 06:00 UTC")),
            "globex", Map.of(
                    "eur", new Answer("Net EUR exposure, all Globex entities, 1-4 Oct 2026",
                            "Net EUR exposure: 812,040.00 EUR\nHedged: 0.00 EUR\nUnhedged: 812,040.00 EUR",
                            "[Omni: FX exposure](https://app.treasury.example/omni/fx-exposure), as of 2026-10-04 08:00 UTC")));

    public String id() {
        return "omni";
    }

    public String description() {
        return "the customer's own treasury figures: cash, FX exposure, forecast vs actual, variance";
    }

    public Optional<Answer> answer(String question, String tenant) {
        String q = question.toLowerCase();
        if (q.contains("liquidity")) {
            throw new IllegalStateException("Omni timed out after 10s (simulated)");
        }
        return DATA.getOrDefault(tenant, Map.of()).entrySet().stream()
                .filter(e -> q.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }
}

/** Help center search. Public articles only, so the tenant does not matter. */
@Component
class MockHelpCenter implements DomainAgent {

    private static final Map<String, Answer> ARTICLES = Map.of(
            "approval", new Answer("Help article: Set payment approval limits",
                    "1. Go to Settings > Payments > Approval rules (admin role needed).\n"
                            + "2. Add a rule per entity, currency and amount band.\n"
                            + "3. Choose how many approvers each band needs.",
                    "[Set payment approval limits](https://help.treasury.example/approval-limits)"),
            "bank", new Answer("Help article: Connect a bank account",
                    "1. Go to Settings > Bank connectivity > Add bank.\n"
                            + "2. Pick SWIFT, SFTP or the bank's API.\n"
                            + "3. Statements appear within one business day.",
                    "[Connect a bank account](https://help.treasury.example/connect-bank)"));

    public String id() {
        return "helpcenter";
    }

    public String description() {
        return "how to use the platform: settings, approval limits, connecting banks";
    }

    public Optional<Answer> answer(String question, String tenant) {
        String q = question.toLowerCase();
        return ARTICLES.entrySet().stream().filter(e -> q.contains(e.getKey())).map(Map.Entry::getValue).findFirst();
    }
}

/** Jira. Logs the ticket instead of calling the Jira API. */
@Component
class MockTicketSystem {

    private final AtomicInteger next = new AtomicInteger(1042);

    /** Returns the new ticket key, starting at RTS-1042. */
    String create(String email, String problem, List<Ari.Msg> transcript) {
        String key = "RTS-" + next.getAndIncrement();
        LoggerFactory.getLogger(getClass()).info("Jira ticket {} for {}: \"{}\" ({} messages attached)",
                key, email, problem, transcript.size());
        return key;
    }
}
