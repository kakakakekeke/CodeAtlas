package io.codeatlas.sql.script;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;

/** 헤더만 읽는 핸들러: 본문을 분석하지 않고, 본문이 있는 단위마다 INFO 진단을 남긴다. 기본 핸들러는 본문을 분석하는 {@code RoutineBodyHandler}다. */
public final class HeaderOnlyRoutineHandler implements RoutineUnitHandler {

    @Override
    public void handle(RoutineUnit unit, RoutineContext context) {
        RoutineHeader header = unit.header();
        boolean hasBody = switch (header.kind()) {
            case PACKAGE -> false;
            case PACKAGE_BODY -> true;
            default -> header.bodyStart() >= 0;
        };
        if (hasBody && header.name() != null) {
            context.graph().report(Diagnostic.info(DiagnosticCodes.UNSUPPORTED_SYNTAX,
                    "루틴 본문은 분석하지 않음: " + header.kind() + " " + header.name().display(),
                    context.location(unit.unit().pos())));
        }
    }
}
