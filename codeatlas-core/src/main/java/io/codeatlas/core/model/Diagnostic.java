package io.codeatlas.core.model;

import java.util.Objects;

/** 분석 중에 남기는 진단. code는 {@link DiagnosticCodes}의 값을 쓴다. location은 없으면 null. */
public record Diagnostic(Severity severity, String code, String message, SourceLocation location) {

    public Diagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        message = message == null ? "" : message;
    }

    public static Diagnostic info(String code, String message, SourceLocation location) {
        return new Diagnostic(Severity.INFO, code, message, location);
    }

    public static Diagnostic warn(String code, String message, SourceLocation location) {
        return new Diagnostic(Severity.WARN, code, message, location);
    }

    public static Diagnostic error(String code, String message, SourceLocation location) {
        return new Diagnostic(Severity.ERROR, code, message, location);
    }
}
