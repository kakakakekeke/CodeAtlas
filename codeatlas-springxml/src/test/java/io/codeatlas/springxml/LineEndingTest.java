package io.codeatlas.springxml;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.SourceLocation;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * F-017-R17: 줄 끝은 LF, CRLF, CR 단독 셋 다 한 줄로 센다(다른 모듈과 같은 규칙). 시작 태그와 속성의 위치는 본문을 직접 훑어 구하므로
 * 훑는 쪽이 CR 단독을 줄로 세지 않으면 빈·엣지의 위치가 실제보다 위쪽 줄을 가리킨다(끝 위치는 StAX가 맞게 준다).
 */
class LineEndingTest {

    private static final String PATH = "src/main/resources/spring/context-shop.xml";

    private static final String XML = Fx.beans("""
              <!-- 주문
                   서비스 -->
              <bean id="orderService" class="com.a.OrderServiceImpl">
                <property name="orderDao"
                          ref="orderDao"/>
                <property name="names">
                  <list>
                    <ref bean="codeDao"/>
                  </list>
                </property>
              </bean>

              <bean id="orderDao"
                    class="com.a.OrderDao"
                    p:dataSource-ref="dataSource"/>
              <bean id="codeDao" class="com.a.CodeDao"/>
              <bean id="dataSource" class="com.a.Ds"/>
              <batch:job id="settleJob">
                <batch:step id="loadStep">
                  <batch:tasklet ref="orderService"/>
                </batch:step>
              </batch:job>
            """);

    static Stream<Arguments> lineEndings() {
        return Stream.of(Arguments.of("LF", "\n"), Arguments.of("CRLF", "\r\n"), Arguments.of("CR", "\r"), Arguments.of("섞임", null));
    }

    /** LF로 쓴 픽스처의 줄 끝을 바꾼다. eol이 null이면 줄마다 CR, CRLF, LF를 돌려 가며 섞는다. */
    private static String convert(String lf, String eol) {
        if (eol != null) {
            return lf.replace("\n", eol);
        }
        String[] eols = {"\r", "\r\n", "\n"};
        StringBuilder sb = new StringBuilder();
        int k = 0;
        for (int i = 0; i < lf.length(); i++) {
            char c = lf.charAt(i);
            sb.append(c == '\n' ? eols[k++ % eols.length] : String.valueOf(c));
        }
        return sb.toString();
    }

    private static String where(SourceLocation l) {
        return l == null ? "-" : l.startLine() + ":" + l.startColumn() + "-" + l.endLine() + ":" + l.endColumn();
    }

    /** 그래프의 모든 노드·엣지 위치를 한 줄씩 적는다(파일 노드 제외, 정렬). */
    private static List<String> positions(GraphModel graph) {
        Stream<String> nodes = graph.nodes().stream().filter(n -> !n.id().startsWith("file:"))
                .map((Node n) -> n.id() + " @" + where(n.location()));
        Stream<String> edges = graph.edges().stream()
                .map((Edge e) -> e.from() + " -" + e.kind() + "-> " + e.to() + " @" + where(e.location()));
        return Stream.concat(nodes, edges).sorted().collect(Collectors.toList());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lineEndings")
    @DisplayName("F-017-AC17 빈·스텝 노드와 주입 엣지의 위치(시작·끝 줄과 열)가 줄 끝이 LF·CRLF·CR 단독·섞임 어느 것이어도 같다")
    void positionsDoNotDependOnLineEnding(String label, String eol) {
        GraphModel expected = Fx.run(PATH, XML).graph();
        int beanLine = Fx.lineOf(XML, "<bean id=\"orderService\"");
        assertThat(Fx.node(expected, Ids.springBean("orderService")).location().startLine()).isEqualTo(beanLine);
        assertThat(Fx.node(expected, Ids.springBean("orderDao")).location().startLine()).isEqualTo(Fx.lineOf(XML, "<bean id=\"orderDao\""));
        assertThat(Fx.node(expected, Ids.springBean("orderDao")).location().endLine()).isEqualTo(Fx.lineOf(XML, "p:dataSource-ref"));

        GraphModel actual = Fx.run(PATH, convert(XML, eol)).graph();

        assertThat(positions(actual)).containsExactlyElementsOf(positions(expected));
        assertThat(actual.diagnostics()).hasSameSizeAs(expected.diagnostics());
    }

    @Test
    @DisplayName("F-017-AC17 시작 태그와 속성 이름의 위치: CR 단독 파일에서도 '<'와 속성 이름의 줄·열이 맞다")
    void tagAndAttributePositionsWithLoneCr() {
        String xml = "<beans>\r  <bean id=\"a\"\r        class=\"com.a.A\"/>\r\r  <bean\r    id=\"b\" class=\"com.a.B\"/>\r</beans>\r";

        List<XmlParser.TagPosition> tags = XmlParser.startTagPositions(xml);

        assertThat(tags).hasSize(3);
        assertThat(new int[] {tags.get(0).line(), tags.get(0).column()}).containsExactly(1, 1);
        assertThat(new int[] {tags.get(1).line(), tags.get(1).column()}).containsExactly(2, 3);
        assertThat(tags.get(1).attributes().get("id")).containsExactly(2, 9);
        assertThat(tags.get(1).attributes().get("class")).containsExactly(3, 9);
        assertThat(new int[] {tags.get(2).line(), tags.get(2).column()}).containsExactly(5, 3);
        assertThat(tags.get(2).attributes().get("id")).containsExactly(6, 5);
        assertThat(tags.get(2).attributes().get("class")).containsExactly(6, 12);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lineEndings")
    @DisplayName("F-017-AC17 깨진 XML의 PARSE_FAILED 위치도 줄 끝과 상관없이 같은 줄이다")
    void parseFailureLine(String label, String eol) {
        String broken = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<beans xmlns=\"http://www.springframework.org/schema/beans\">\n"
                + "  <bean id=\"a\" class=\"com.a.A\"/>\n  <bean id=\"broken\" class=\"com.a.B\">\n</beans>\n";
        Diagnostic expected = Fx.diagnostics(Fx.run("conf/broken.xml", broken).graph(), DiagnosticCodes.PARSE_FAILED).get(0);

        List<Diagnostic> failed = Fx.diagnostics(Fx.run("conf/broken.xml", convert(broken, eol)).graph(), DiagnosticCodes.PARSE_FAILED);

        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).location().startLine()).isEqualTo(expected.location().startLine()).isGreaterThan(1);
    }
}
