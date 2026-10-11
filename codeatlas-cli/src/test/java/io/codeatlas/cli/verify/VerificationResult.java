package io.codeatlas.cli.verify;

import io.codeatlas.core.model.Confidence;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 대조 결과. 집계에서 {@code certainty=low} 항목은 따로 센다(기본 집계에 넣지 않는다).
 *
 * @param edges must-have 엣지 항목별 판정(정답 파일 순서)
 * @param nodes must-have 노드 항목별 판정
 * @param violations 걸린 함정 항목(certainty=low 포함)
 * @param mustNotTotal 함정 항목 수
 */
public record VerificationResult(List<EdgeResult> edges, List<NodeResult> nodes, List<Violation> violations, int mustNotTotal) {

    public VerificationResult {
        edges = List.copyOf(edges);
        nodes = List.copyOf(nodes);
        violations = List.copyOf(violations);
    }

    /** certainty=low가 아닌 must-have 엣지 집계. */
    public Tally edgeTally() {
        return tallyEdges(false);
    }

    /** certainty=low인 must-have 엣지 집계. */
    public Tally lowEdgeTally() {
        return tallyEdges(true);
    }

    public Tally nodeTally() {
        return tallyNodes(false);
    }

    public Tally lowNodeTally() {
        return tallyNodes(true);
    }

    /** EdgeKind 이름별 집계(certainty=low 제외). */
    public Map<String, Tally> edgesByKind() {
        Map<String, Tally> result = new TreeMap<>();
        for (EdgeResult r : edges) {
            if (!r.expected().lowCertainty()) {
                result.merge(r.expected().kind(), Tally.EMPTY.plus(r.verdict()), (a, b) -> a.plus(r.verdict()));
            }
        }
        return result;
    }

    /** NodeKind 이름별 집계(certainty=low 제외). */
    public Map<String, Tally> nodesByKind() {
        Map<String, Tally> result = new TreeMap<>();
        for (NodeResult r : nodes) {
            if (!r.expected().lowCertainty()) {
                result.merge(r.expected().kind().name(), Tally.EMPTY.plus(r.verdict()), (a, b) -> a.plus(r.verdict()));
            }
        }
        return result;
    }

    /** 실패 사유별 엣지 항목 수(certainty=low 제외). */
    public Map<FailReason, Integer> edgeFailuresByReason() {
        Map<FailReason, Integer> result = new EnumMap<>(FailReason.class);
        for (EdgeResult r : edges) {
            if (!r.expected().lowCertainty() && r.verdict() == Verdict.FAIL) {
                result.merge(r.reason(), 1, Integer::sum);
            }
        }
        return result;
    }

    /** 실패 사유별 노드 항목 수(certainty=low 제외). */
    public Map<FailReason, Integer> nodeFailuresByReason() {
        Map<FailReason, Integer> result = new EnumMap<>(FailReason.class);
        for (NodeResult r : nodes) {
            if (!r.expected().lowCertainty() && r.verdict() == Verdict.FAIL) {
                result.merge(r.reason(), 1, Integer::sum);
            }
        }
        return result;
    }

    /** 엣지가 있기만 하면 통과로 본 집계(certainty=low 제외). attrs만 달라 실패한 항목을 통과로 센다. */
    public Tally edgeExistenceTally() {
        Tally tally = Tally.EMPTY;
        for (EdgeResult r : edges) {
            if (!r.expected().lowCertainty()) {
                boolean exists = r.verdict().passed() || r.reason() == FailReason.ATTR_MISMATCH;
                boolean inferred = r.matched() != null && r.matched().confidence() == Confidence.INFERRED;
                tally = tally.plus(!exists ? Verdict.FAIL : inferred ? Verdict.INFERRED_PASS : Verdict.PASS);
            }
        }
        return tally;
    }

    /** 느슨한 규칙으로 통과한 엣지 항목 수(certainty=low 포함). */
    public long looseEdgePasses() {
        return edges.stream().filter(r -> r.verdict().passed() && r.loose()).count();
    }

    /** certainty=low가 아닌 함정 위반. 임계값은 이것으로 본다. */
    public List<Violation> hardViolations() {
        return violations.stream().filter(v -> !v.rule().lowCertainty()).toList();
    }

    private Tally tallyEdges(boolean low) {
        Tally tally = Tally.EMPTY;
        for (EdgeResult r : edges) {
            if (r.expected().lowCertainty() == low) {
                tally = tally.plus(r.verdict());
            }
        }
        return tally;
    }

    private Tally tallyNodes(boolean low) {
        Tally tally = Tally.EMPTY;
        for (NodeResult r : nodes) {
            if (r.expected().lowCertainty() == low) {
                tally = tally.plus(r.verdict());
            }
        }
        return tally;
    }
}
