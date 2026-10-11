package io.codeatlas.cli.verify;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.SourceLocation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 진단을 보고 내리는 판정. */
public final class DiagnosticChecks {

    /** 파일 단위 실패로 인정하는 진단 코드(samples/sample-shop/expected/expected-unresolved.md 5절). */
    public static final Set<String> PARSE_FAILURE_CODES = Set.of(DiagnosticCodes.PARSE_FAILED, DiagnosticCodes.UNSUPPORTED_SYNTAX);

    /** 분석기, 링커, 구현체 탐색이 통째로 실패했을 때 core가 남기는 진단 코드. */
    public static final Set<String> COMPONENT_FAILURE_CODES =
            Set.of(DiagnosticCodes.ANALYZER_FAILED, DiagnosticCodes.LINKER_FAILED, DiagnosticCodes.SERVICE_LOAD_FAILED);

    private DiagnosticChecks() {
    }

    /** 파일마다 그 파일 위치로 남은 PARSE_FAILED / UNSUPPORTED_SYNTAX 진단. 없으면 빈 목록(= 조용히 넘어갔다). */
    public static Map<String, List<Diagnostic>> parseFailures(GraphModel graph, List<String> files) {
        Map<String, List<Diagnostic>> result = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = graph.diagnostics();
        for (String file : files) {
            result.put(file, diagnostics.stream()
                    .filter(d -> PARSE_FAILURE_CODES.contains(d.code()) && d.location() != null && d.location().file().equals(file))
                    .toList());
        }
        return result;
    }

    /**
     * 그 지점에 남은 PARSE_FAILED / UNSUPPORTED_SYNTAX 진단. 지점과 같은 파일이고 진단이 지점의 줄 범위 안에서 시작해야 한다
     * ({@link UnresolvedPoints.Point#near}처럼 근처 줄이나 경로의 뒷부분만 맞는 것은 치지 않는다).
     */
    public static List<Diagnostic> parseFailuresAt(GraphModel graph, UnresolvedPoints.Point point) {
        return graph.diagnostics().stream().filter(d -> PARSE_FAILURE_CODES.contains(d.code()) && at(point, d.location())).toList();
    }

    /**
     * 허용한 지점(샘플의 "일부러 넣은 미지원 구문" 목록) 어디에도 들지 않는 PARSE_FAILED / UNSUPPORTED_SYNTAX 진단(F-019-AC34).
     * 위치가 없거나 줄을 모르는 진단도 밖으로 친다.
     */
    public static List<Diagnostic> parseFailuresOutside(GraphModel graph, List<UnresolvedPoints.Point> allowed) {
        return graph.diagnostics().stream()
                .filter(d -> PARSE_FAILURE_CODES.contains(d.code()) && allowed.stream().noneMatch(point -> at(point, d.location())))
                .toList();
    }

    private static boolean at(UnresolvedPoints.Point point, SourceLocation location) {
        return location != null && location.file().equals(point.file())
                && location.startLine() >= point.startLine() && location.startLine() <= point.endLine();
    }

    /** 분석기·링커가 예외로 죽었거나 구현체를 만들지 못했다는 진단. */
    public static List<Diagnostic> componentFailures(GraphModel graph) {
        return graph.diagnostics().stream().filter(d -> COMPONENT_FAILURE_CODES.contains(d.code())).toList();
    }
}
