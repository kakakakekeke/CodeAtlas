package io.codeatlas.cli;

/** 명령줄 인자가 잘못됐을 때. */
final class UsageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    UsageException(String message) {
        super(message);
    }
}
