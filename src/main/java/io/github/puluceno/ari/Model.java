package io.github.puluceno.ari;

/** The only door to a language model. Azure in production, a fake in tests. */
interface Model {

    /** One prompt in, the reply text out. {@code jsonOutput} asks for a JSON object. Throws when the call fails. */
    String complete(String systemPrompt, String userMessage, boolean jsonOutput);
}
