package io.codeatlas.core.pipeline;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import java.util.List;

/**
 * {@link Pipeline#export}의 결과.
 *
 * @param exporters 실행한 출력기의 이름(실행한 순서). 예외로 끝난 것도 들어 있다
 * @param diagnostics 이 실행에서 그래프에 새로 남은 진단(남은 순서). 출력기가 남긴 것({@code INVALID_OPTION} 등)과
 *                    {@code EXPORTER_FAILED}, {@code SERVICE_LOAD_FAILED}
 */
public record ExportResult(List<String> exporters, List<Diagnostic> diagnostics) {

    public ExportResult {
        exporters = List.copyOf(exporters);
        diagnostics = List.copyOf(diagnostics);
    }

    /** 출력기가 예외로 끝났거나({@code EXPORTER_FAILED}) 구현체를 만들지 못한({@code SERVICE_LOAD_FAILED}) 진단. */
    public List<Diagnostic> failures() {
        return diagnostics.stream()
                .filter(d -> DiagnosticCodes.EXPORTER_FAILED.equals(d.code()) || DiagnosticCodes.SERVICE_LOAD_FAILED.equals(d.code()))
                .toList();
    }

    /** 실패한 출력기가 하나라도 있으면 true. */
    public boolean failed() {
        return !failures().isEmpty();
    }
}
