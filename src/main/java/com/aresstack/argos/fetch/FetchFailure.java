package com.aresstack.argos.fetch;

/** A fetch that did not deliver usable content, classified so an agent can decide what to do next. */
public final class FetchFailure extends RuntimeException {
    public enum Kind { INVALID_REQUEST, NOT_FOUND, BLOCKED, HTTP_ERROR, TIMEOUT, TOO_LARGE, FAILED }

    private final Kind kind;

    public FetchFailure(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public FetchFailure(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** Maps a final HTTP status to a failure, or {@code null} when the status delivers content. */
    public static FetchFailure forHttpStatus(int status) {
        if (status == 0 || (status >= 200 && status < 400)) return null;
        if (status == 404 || status == 410) return new FetchFailure(Kind.NOT_FOUND, "HTTP " + status);
        if (status == 401 || status == 403 || status == 429 || status == 451) {
            return new FetchFailure(Kind.BLOCKED, "HTTP " + status + " (access refused; Argos does not bypass it)");
        }
        return new FetchFailure(Kind.HTTP_ERROR, "HTTP " + status);
    }
}
