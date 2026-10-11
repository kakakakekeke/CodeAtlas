package io.codeatlas.sql;

import static io.codeatlas.sql.GraphAssert.edge;
import static io.codeatlas.sql.GraphAssert.hasEdge;
import static io.codeatlas.sql.GraphAssert.targets;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * samples/sample-shop 의 매퍼 구문 몇 개를 실제 원문 그대로 {@link SqlAnalyzer}에 넘겨 대조한다(cli와 mybatis 분석기를 거치지 않는다).
 * 매퍼 XML에서 구문 본문을 잘라 생산자(mybatis)처럼 내장 소스로 등록한다. {@code <include>} 태그는 지우고 나머지는 원문이다.
 */
class SampleShopMapperSqlTest {

    private static final String ORDER_MAPPER = "src/main/resources/mapper/order/OrderMapper.xml";
    private static final String ORDER_DAO = "src/main/resources/mapper/order/orderDao.xml";
    private static final String ORDER_MAPPER_NS = "com.example.shop.order.mapper.OrderMapper";

    private static GraphModel graph;

    @BeforeAll
    static void analyze() throws IOException {
        Path sample = Files.isDirectory(Path.of("samples/sample-shop")) ? Path.of("samples/sample-shop") : Path.of("../samples/sample-shop");
        assertThat(sample).isDirectory();
        try (TestProject project = TestProject.at(sample.toAbsolutePath().normalize())) {
            AnalysisContext ctx = project.context();
            embed(ctx, sample, ORDER_MAPPER, ORDER_MAPPER_NS, "selectOrderList");
            embed(ctx, sample, ORDER_DAO, "orderDao", "selectPaymentsByOrder");
            embed(ctx, sample, ORDER_DAO, "orderDao", "selectOrderHistory");
            embed(ctx, sample, ORDER_DAO, "orderDao", "updateDeliveryShip");
            graph = TestProject.run(ctx, new SqlAnalyzer()).graph();
        }
    }

    /** 매퍼 XML에서 id가 같은 구문의 본문을 잘라 내장 소스로 등록한다. */
    private static void embed(AnalysisContext ctx, Path sample, String file, String namespace, String id) throws IOException {
        String xml = Files.readString(sample.resolve(file), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<(select|insert|update|delete)\\s+id=\"" + id + "\"[^>]*>(.*?)</\\1>", Pattern.DOTALL).matcher(xml);
        assertThat(m.find()).as(file + " 의 " + id).isTrue();
        int line = (int) xml.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1;
        String sql = m.group(2).replaceAll("<include[^>]*/>", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        String owner = Ids.mybatisStatement(namespace, id);
        ctx.graph().declare(Node.builder(owner, NodeKind.SQL_STATEMENT).name(id).location(SourceLocation.of(file, line)).build());
        ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, owner, sql, SourceLocation.of(file, line, 1, line, 1), false, Map.of()));
    }

    private static Edge join(String a, String b, String statement) {
        List<Edge> edges = new java.util.ArrayList<>(graph.edges("db:table:" + a, EdgeKind.JOINS, "db:table:" + b));
        edges.removeIf(e -> !statement.equals(e.attrString("statement")));
        assertThat(edges).as(a + " JOINS " + b + " (" + statement + ")").hasSize(1);
        return edges.get(0);
    }

    @Test
    @DisplayName("F-006-AC13 샘플의 공통코드 조인 3건: TB_CODE 인스턴스의 GROUP_CD가 상수로 고정됐다는 사실이 JOINS attrs에 남는다")
    void codeTableJoinsCarryFixedColumns() {
        // OrderMapper.xml:78-79  O.ORDER_STATUS = C.CODE(+) AND C.GROUP_CD(+) = 'ORDER_STATUS'
        Edge order = join("TB_ORDER", "TB_CODE", Ids.mybatisStatement(ORDER_MAPPER_NS, "selectOrderList"));
        assertThat(order.attrList("leftColumns")).containsExactly("ORDER_STATUS");
        assertThat(order.attrList("rightColumns")).containsExactly("CODE");
        assertThat(order.attrList("rightFixedColumns")).containsExactly("GROUP_CD");
        assertThat(order.attrList("rightFixedValues")).containsExactly("GROUP_CD=ORDER_STATUS");

        // orderDao.xml:139-140  ON C.GROUP_CD = 'ORDER_STATUS' AND C.CODE = H.ORDER_STATUS
        Edge hist = join("TB_CODE", "TB_ORDER_HIST", "mybatis:stmt:orderDao.selectOrderHistory");
        assertThat(hist.attrList("leftColumns")).containsExactly("CODE");
        assertThat(hist.attrList("rightColumns")).containsExactly("ORDER_STATUS");
        assertThat(hist.attrList("leftFixedColumns")).containsExactly("GROUP_CD");
        assertThat(hist.attrList("leftFixedValues")).containsExactly("GROUP_CD=ORDER_STATUS");
        assertThat(hist.attrList("rightFixedValues")).containsExactly("ORDER_ID=?");

        // orderDao.xml:81-82  스칼라 상관 서브쿼리 WHERE C.GROUP_CD = 'PAY_METHOD' AND C.CODE = P.PAY_METHOD_CD
        Edge pay = join("TB_CODE", "TB_PAYMENT", "mybatis:stmt:orderDao.selectPaymentsByOrder");
        assertThat(pay.attrList("leftColumns")).containsExactly("CODE");
        assertThat(pay.attrList("rightColumns")).containsExactly("PAY_METHOD_CD");
        assertThat(pay.attrList("leftFixedColumns")).containsExactly("GROUP_CD");
        assertThat(pay.attrList("leftFixedValues")).containsExactly("GROUP_CD=PAY_METHOD");

        // 상수 조건이 없는 조인에는 attrs를 넣지 않는다 (orderDao.xml:137 O.ORDER_ID = H.ORDER_ID. H.ORDER_ID만 바인드로 고정된다)
        Edge plain = join("TB_ORDER", "TB_ORDER_HIST", "mybatis:stmt:orderDao.selectOrderHistory");
        assertThat(plain.attrs()).doesNotContainKeys("leftFixedColumns", "leftFixedValues");
        assertThat(plain.attrList("rightFixedColumns")).containsExactly("ORDER_ID");
    }

    @Test
    @DisplayName("F-006-AC14 샘플 orderDao.xml:113 updateDeliveryShip: 갱신 대상 TB_DELIVERY에는 테이블 수준 WRITES만, 조건으로 읽는 TB_ORDER는 READS")
    void updateDeliveryShipTargetIsNotRead() {
        String stmt = "mybatis:stmt:orderDao.updateDeliveryShip";
        assertThat(edge(graph, stmt, EdgeKind.WRITES, "db:table:TB_DELIVERY").attrString("op")).isEqualTo("UPDATE");
        assertThat(hasEdge(graph, stmt, EdgeKind.READS, "db:table:TB_DELIVERY")).isFalse();
        assertThat(hasEdge(graph, stmt, EdgeKind.READS, "db:table:TB_ORDER")).isTrue();
        assertThat(hasEdge(graph, stmt, EdgeKind.WRITES, "db:table:TB_ORDER")).isFalse();
        assertThat(targets(graph, stmt, EdgeKind.WRITES)).containsExactly(
                "db:column:TB_DELIVERY.DELIVERY_STATUS", "db:column:TB_DELIVERY.SHIP_DT", "db:table:TB_DELIVERY");
        // 대상 테이블의 컬럼 READS와 조인(must-have)은 그대로다
        assertThat(targets(graph, stmt, EdgeKind.READS)).contains("db:column:TB_DELIVERY.ORDER_ID", "db:column:TB_ORDER.ORDER_STATUS");
        assertThat(join("TB_DELIVERY", "TB_ORDER", stmt).attrList("rightFixedValues")).containsExactly("ORDER_ID=?", "ORDER_STATUS=30");
    }
}
