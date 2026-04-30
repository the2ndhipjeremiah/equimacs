package org.equimacs.cli;

public class CliParseException extends RuntimeException {
    public CliParseException(String message) {
        super(message);
    }

    public CliParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
