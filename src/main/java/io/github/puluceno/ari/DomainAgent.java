package io.github.puluceno.ari;

import java.util.Optional;

/** One product area. Its owning team owns this; the hub only knows the contract. */
interface DomainAgent {

    /** Routing key the classifier answers with. */
    String id();

    /** What this area answers. Goes into the classifier prompt. */
    String description();

    /** Empty when there is no answer. Throws when the system is down. Only ever sees one tenant. */
    Optional<Answer> answer(String question, String tenant);

    /** Data, not prose. interpretation = what was measured, shown so the user can catch a misreading. */
    record Answer(String interpretation, String data, String source) {
    }
}
