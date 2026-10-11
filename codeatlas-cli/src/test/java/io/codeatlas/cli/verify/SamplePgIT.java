package io.codeatlas.cli.verify;

import io.codeatlas.core.model.EdgeKind;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * sample-pg(PostgreSQL) 통합 검증(F-019-R36, F-019-AC34). Pipeline API로 {@code samples/sample-pg}를 옵션 없이 분석하고
 * {@code expected/}의 정답과 대조해 {@code target/sample-pg-report.md}, {@code target/sample-pg-report.json},
 * {@code target/sample-pg-graph.json}을 쓴 뒤 임계값으로 단언한다. failsafe(verify 단계)에서만 돈다.
 * 대조 장치와 {@code sample.verify*} 스위치는 {@code SampleShopIT}와 같다({@link SampleVerifier}).
 *
 * <pre>
 * mvn -q -pl codeatlas-cli verify                              # 임계값으로 단언 (기본)
 * mvn -q -pl codeatlas-cli verify -Dsample.verify=report       # 단언 없이 리포트만 쓰고 통과 (진행 상황 측정)
 * </pre>
 *
 * sample-shop과 다른 점
 * <ul>
 *   <li>프로젝트 경로의 프로퍼티는 {@code sample.pg.project}(기본 ../samples/sample-pg)다. {@code sample.project}는 sample-shop의 것이다.</li>
 *   <li>깨진 입력({@code broken/})이 없다. 그 대신 {@code PARSE_FAILED}·{@code UNSUPPORTED_SYNTAX}가 샘플의 "일부러 넣은 미지원 구문"
 *       ({@code expected/expected-unresolved.md} 4절의 N 행. README 3절과 같은 표)의 위치에만 있어야 한다.</li>
 *   <li>정답 JSON의 형식으로 적지 못한 기대 둘(README 6절 "정답에 넣지 않은 것")을 따로 본다: {@link #FAST_HASH}에 {@code language}가 없다,
 *       미해결 엣지 U1이 있다.</li>
 * </ul>
 */
class SamplePgIT {

    static final String SAMPLE = "sample-pg";

    /** expected-unresolved.md의 표에서 "일부러 넣은 미지원 구문" 행의 번호 접두사(N1 = {@code db/01_schema.sql:517}의 REFRESH MATERIALIZED VIEW). */
    static final String UNSUPPORTED_POINT_PREFIX = "N";

    /** {@code db/02_routines.sql:208}의 {@code LANGUAGE c} 함수. 루틴 노드만 있고 {@code language}가 없어야 한다(F-019-R24, F-019-AC22). */
    static final String FAST_HASH = "db:routine:FAST_HASH";

    /** U1: 프로시저 ARCHIVE_ORDERS의 풀리지 않는 {@code EXECUTE format('INSERT INTO %I …', p_stats_table)} ({@code db/02_routines.sql:173}). */
    static final String U1_STATEMENT = "sql:stmt:ARCHIVE_ORDERS:173";
    static final String U1_UNRESOLVED = "unresolved:sql-dynamic:" + U1_STATEMENT;

    @Test
    @DisplayName("F-019-AC34 sample-pg: 옵션 없이 분석해 파이프라인이 끝까지 돌고, PARSE_FAILED·UNSUPPORTED_SYNTAX가 일부러 넣은 미지원 구문 위치에만 있고, "
            + "함정 위반이 없고, must-have가 전부 있고, FAST_HASH에 language가 없고, 미해결 엣지 U1이 있다")
    void samplePgMeetsExpectations() throws Exception {
        SampleVerifier.of(SAMPLE)
                .parseFailuresOnlyAt(UNSUPPORTED_POINT_PREFIX)
                .check(ExtraCheck.nodeWithoutAttr(FAST_HASH, "language"))
                .check(ExtraCheck.unresolvedEdge(U1_STATEMENT, EdgeKind.WRITES, U1_UNRESOLVED, Map.of("op", "INSERT")))
                .verify(SampleVerifier.Settings.fromSystemProperties("sample.pg.project", "../samples/sample-pg"));
    }
}
