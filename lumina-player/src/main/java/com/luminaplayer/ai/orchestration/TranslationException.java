package com.luminaplayer.ai.orchestration;

/**
 * Exception thrown when a translation operation fails.
 */
public class TranslationException extends Exception {

    public TranslationException(String message) {
        super(message);
    }

    public TranslationException(String message, Throwable cause) {
        super(message, cause);
    }
}
