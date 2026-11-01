package com.operonix.ai;

import java.util.List;

/**
 * The one seam between Operonix and a language model.
 *
 * <p>Providers receive a prompt plus data that the server has already computed and return prose. They
 * never receive a database connection, a query language, or the right to produce a number that the
 * application then displays: the prompt states figures that are already authoritative, and the
 * response is only commentary on them.
 */
public interface LlmProvider {

    String name();

    String model();

    /** False when no credentials are configured; callers must then use the deterministic fallback. */
    boolean configured();

    Completion complete(List<Message> messages, int maxOutputTokens);

    enum Role {
        SYSTEM,
        USER
    }

    record Message(Role role, String text) {
    }

    record Completion(String text, int inputTokens, int outputTokens, String model) {
    }

    /** Raised when the provider is unreachable or rejects the request; callers degrade, never crash. */
    class LlmUnavailableException extends RuntimeException {

        public LlmUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        public LlmUnavailableException(String message) {
            super(message);
        }
    }
}
