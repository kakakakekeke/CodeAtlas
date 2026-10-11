package io.codeatlas.cli.verify;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * sample-shop 통합 검증. Pipeline API로 {@code samples/sample-shop}을 분석하고 {@code expected/}의 정답과 대조해
 * {@code target/sample-shop-report.md}, {@code target/sample-shop-report.json}, {@code target/sample-shop-graph.json}을 쓴 뒤
 * 임계값으로 단언한다. failsafe(verify 단계)에서만 돈다. 대조 장치는 {@link SampleVerifier}로 {@code SamplePgIT}와 같이 쓴다.
 *
 * <pre>
 * mvn -q -pl codeatlas-cli verify                              # 임계값으로 단언 (기본)
 * mvn -q -pl codeatlas-cli verify -Dsample.verify=report       # 단언 없이 리포트만 쓰고 통과 (진행 상황 측정)
 * </pre>
 *
 * 시스템 프로퍼티 (괄호 안은 기본값)
 * <ul>
 *   <li>{@code sample.verify} (assert): {@code assert} 또는 {@code report}</li>
 *   <li>{@code sample.verify.minEdgePassRate} (100): must-have 엣지 통과율 하한(%). certainty=low 제외</li>
 *   <li>{@code sample.verify.minNodePassRate} (100): must-have 노드 통과율 하한(%). certainty=low 제외</li>
 *   <li>{@code sample.verify.maxViolations} (0): 허용하는 함정 위반 수. certainty=low 제외</li>
 *   <li>{@code sample.verify.edgeAttrs} (strict): {@code strict}면 엣지의 attrs 불일치를 실패로, {@code lenient}면 통과로 본다</li>
 *   <li>{@code sample.project} (../samples/sample-shop), {@code sample.report.dir} (target)</li>
 * </ul>
 */
class SampleShopIT {

    static final String SAMPLE = "sample-shop";

    /** 파일 단위 실패로 기록돼야 하는 깨진 입력(samples/sample-shop/README.md 3절). */
    static final List<String> BROKEN_FILES = List.of("broken/BrokenService.java", "broken/broken_query.sql", "broken/BrokenMapper.xml");

    @Test
    @DisplayName("sample-shop: 파이프라인이 끝까지 돌고, 깨진 파일은 진단으로 남고, 함정 위반이 없고, must-have가 전부 있다")
    void sampleShopMeetsExpectations() throws Exception {
        SampleVerifier.of(SAMPLE)
                .brokenFiles(BROKEN_FILES)
                .verify(SampleVerifier.Settings.fromSystemProperties("sample.project", "../samples/sample-shop"));
    }
}
