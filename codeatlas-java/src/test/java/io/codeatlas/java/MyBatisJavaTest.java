package io.codeatlas.java;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.EmbeddedSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.codeatlas.java.Fx.edge;
import static io.codeatlas.java.Fx.node;
import static io.codeatlas.java.Fx.targets;
import static org.assertj.core.api.Assertions.assertThat;

/** F-004 Java 코드 안의 MyBatis. */
class MyBatisJavaTest {

    private static String m(String type, String name, String... params) {
        return Ids.javaMethod(type, name, params);
    }

    @Test
    @DisplayName("F-004-AC9 @Mapper가 붙은 인터페이스만 stereotype=mapper이고 default 메서드는 modifiers로 구분된다")
    void mapperStereotype() {
        GraphModel g = Fx.analyze(
                "src/com/a/OrderMapper.java", """
                        package com.a;
                        import org.apache.ibatis.annotations.Mapper;
                        @Mapper
                        public interface OrderMapper {
                            int count();
                            default int countTwice() { return count() * 2; }
                            static OrderMapper none() { return null; }
                        }
                        """,
                "src/com/a/LegacyMapper.java", "package com.a; public interface LegacyMapper { int count(); }");

        Node mapper = node(g, Ids.javaType("com.a.OrderMapper"));
        assertThat(mapper.attrString("stereotype")).isEqualTo("mapper");
        assertThat(mapper.qualifiedName()).isEqualTo("com.a.OrderMapper");
        assertThat(Fx.hasEdge(g, mapper.id(), EdgeKind.DEFINES_BEAN, Ids.springBean("orderMapper"))).isTrue();
        assertThat(node(g, Ids.javaType("com.a.LegacyMapper")).attrString("stereotype")).isNull();
        assertThat(node(g, m("com.a.OrderMapper", "count")).attrList("modifiers")).doesNotContain("default", "static");
        assertThat(node(g, m("com.a.OrderMapper", "countTwice")).attrList("modifiers")).contains("default");
        assertThat(node(g, m("com.a.OrderMapper", "none")).attrList("modifiers")).contains("static");
    }

    private static final String[] DAO = {
            "src/com/a/OrderDao.java", """
                    package com.a;
                    import java.util.List;
                    import org.mybatis.spring.SqlSessionTemplate;
                    public class OrderDao {
                        private static final String NAMESPACE = "orderDao";
                        private static final String STMT_INSERT_PAYMENT = NAMESPACE + ".insertPayment";
                        private final SqlSessionTemplate sqlSession;
                        public OrderDao(SqlSessionTemplate sqlSessionTemplate) { this.sqlSession = sqlSessionTemplate; }
                        public List<Object> selectOrders(Object p) { return sqlSession.selectList("orderDao.selectOrders", p); }
                        public int insertOrder(Object o) { return sqlSession.insert(NAMESPACE + ".insertOrder", o); }
                        public int insertPayment(Object o) { return sqlSession.insert(STMT_INSERT_PAYMENT, o); }
                        public Object shortId() { return sqlSession.selectOne("selectNow"); }
                        public int two(Object o) {
                            sqlSession.delete("com.a.mapper.OrderMapper.deleteItems", o);
                            return sqlSession.delete("com.a.mapper.OrderMapper.deleteOrder", o);
                        }
                        public <T> List<T> byId(String queryId, Object p) { return sqlSession.selectList(queryId, p); }
                        public void notMybatis(java.util.Map<String, String> map) { map.remove("orderDao.x"); }
                    }
                    """,
            "src/com/a/LegacyDao.java", """
                    package com.a;
                    import org.mybatis.spring.support.SqlSessionDaoSupport;
                    import org.apache.ibatis.session.SqlSession;
                    public class LegacyDao extends SqlSessionDaoSupport {
                        public int update(Object o) { return getSqlSession().update("legacy.updateOrder", o); }
                        public Object local() {
                            SqlSession session = getSqlSession();
                            return session.selectOne("legacy.selectOne");
                        }
                    }
                    """,
    };

    private static void assertDao(GraphModel g) {
        Edge select = edge(g, m("com.a.OrderDao", "selectOrders", "java.lang.Object"), EdgeKind.EXECUTES, Ids.mybatisStatement("orderDao", "selectOrders"));
        assertThat(select.to()).isEqualTo("mybatis:stmt:orderDao.selectOrders");
        assertThat(select.attrString("operation")).isEqualTo("selectList");
        assertThat(select.attrBoolean("shortId")).isFalse();
        assertThat(select.location().startLine()).isEqualTo(9);
        edge(g, m("com.a.OrderDao", "insertOrder", "java.lang.Object"), EdgeKind.EXECUTES, "mybatis:stmt:orderDao.insertOrder");
        edge(g, m("com.a.OrderDao", "insertPayment", "java.lang.Object"), EdgeKind.EXECUTES, "mybatis:stmt:orderDao.insertPayment");
        Edge shortId = edge(g, m("com.a.OrderDao", "shortId"), EdgeKind.EXECUTES, "mybatis:stmt:selectNow");
        assertThat(shortId.attrBoolean("shortId")).isTrue();
        assertThat(shortId.attrString("statementId")).isEqualTo("selectNow");
        assertThat(targets(g, m("com.a.OrderDao", "two", "java.lang.Object"), EdgeKind.EXECUTES)).containsExactly(
                "mybatis:stmt:com.a.mapper.OrderMapper.deleteItems", "mybatis:stmt:com.a.mapper.OrderMapper.deleteOrder");
        assertThat(targets(g, m("com.a.OrderDao", "notMybatis", "java.util.Map"), EdgeKind.EXECUTES)).isEmpty();
        edge(g, m("com.a.LegacyDao", "update", "java.lang.Object"), EdgeKind.EXECUTES, "mybatis:stmt:legacy.updateOrder");
        edge(g, m("com.a.LegacyDao", "local"), EdgeKind.EXECUTES, "mybatis:stmt:legacy.selectOne");
        // 구문 노드는 mybatis 모듈이 만든다
        assertThat(g.nodes(NodeKind.SQL_STATEMENT)).isEmpty();

        // F-004-AC11 상수가 아닌 ID
        Edge dynamic = edge(g, m("com.a.OrderDao", "byId", "java.lang.String", "java.lang.Object"), EdgeKind.EXECUTES,
                Ids.unresolved("mybatis-statement", "queryId"));
        assertThat(dynamic.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(g.diagnostics()).anyMatch(d -> d.code().equals(DiagnosticCodes.UNRESOLVED_REFERENCE) && d.message().contains("mybatis-statement"));
    }

    @Test
    @DisplayName("F-004-AC10 F-004-AC11 SqlSession 계열 호출의 상수 ID로 EXECUTES를 만들고 상수가 아니면 미해결로 남긴다 (jar 없음)")
    void sqlSessionCallsWithoutJar() {
        assertDao(Fx.analyze(DAO));
    }

    @Test
    @DisplayName("F-004-AC10 F-004-AC11 jar가 있을 때도 같은 EXECUTES가 나온다")
    void sqlSessionCallsWithJar() {
        assertDao(Fx.analyzeWithStubs(DAO));
    }

    @Test
    @DisplayName("F-004-AC10 옵션 java.sqlSessionTypes로 프로젝트의 공통 DAO를 SqlSession 계열로 지정할 수 있다")
    void customSqlSessionType() {
        String[] files = {
                "src/com/a/CommonDao.java", "package com.a; public class CommonDao { public Object selectList(String id, Object p) { return null; } }",
                "src/com/a/UserDao.java", "package com.a; public class UserDao extends CommonDao { Object all() { return selectList(\"user.selectAll\", null); } }",
        };
        assertThat(targets(Fx.analyze(files), m("com.a.UserDao", "all"), EdgeKind.EXECUTES)).isEmpty();
        GraphModel g = Fx.analyze(Fx.extra("java.sqlSessionTypes", "CommonDao"), Fx.files(files));
        assertThat(targets(g, m("com.a.UserDao", "all"), EdgeKind.EXECUTES)).containsExactly("mybatis:stmt:user.selectAll");
    }

    @Test
    @DisplayName("F-004-AC12 @Select 등의 SQL로 구문 노드를 만들고 #{}·${}·<script>를 바꿔 sql 내장 소스로 넘긴다")
    void annotationSql() {
        AnalysisContext ctx = Fx.analyzeCtx(AnalysisOptions.defaults(), Fx.files(
                "src/com/a/MemberMapper.java", """
                        package com.a;
                        import org.apache.ibatis.annotations.*;
                        import java.util.List;
                        @Mapper
                        public interface MemberMapper {
                            String TABLE = "T_MEMBER";
                            @Select("SELECT COUNT(*) FROM " + TABLE + " WHERE LOGIN_ID = #{loginId}")
                            int countByLoginId(@Param("loginId") String loginId);

                            @Update({"UPDATE ${table}", "SET NAME = #{name,jdbcType=VARCHAR}", "WHERE ID = #{id}"})
                            int rename(String table, String name, long id);

                            @Select(\"""
                                    <script>
                                    SELECT M.ID, M.NAME FROM T_MEMBER M
                                    <where>
                                      <if test="name != null">AND M.NAME = #{name}</if>
                                      <if test="ids != null">
                                        AND M.ID IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
                                      </if>
                                      AND M.AGE &lt; 20
                                    </where>
                                    </script>
                                    \""")
                            List<Object> search(String name, List<Long> ids);

                            @Delete("DELETE FROM T_MEMBER WHERE ID = #{id}")
                            int remove(long id);

                            @Insert("INSERT INTO T_MEMBER (ID) VALUES (#{id})")
                            int add(long id);

                            int xmlOnly();
                        }
                        """));
        GraphModel g = ctx.graph();

        Node count = node(g, "mybatis:stmt:com.a.MemberMapper.countByLoginId");
        assertThat(count.kind()).isEqualTo(NodeKind.SQL_STATEMENT);
        assertThat(count.attrString("namespace")).isEqualTo("com.a.MemberMapper");
        assertThat(count.attrString("statementId")).isEqualTo("countByLoginId");
        assertThat(count.attrBoolean("annotation")).isTrue();
        assertThat(count.attrBoolean("dynamic")).isFalse();
        assertThat(count.attrString("statementType")).isEqualTo("SELECT");
        assertThat(count.location().file()).isEqualTo("src/com/a/MemberMapper.java");
        assertThat(count.location().startLine()).isEqualTo(7);
        assertThat(node(g, "mybatis:stmt:com.a.MemberMapper.rename").attrString("statementType")).isEqualTo("UPDATE");
        assertThat(node(g, "mybatis:stmt:com.a.MemberMapper.remove").attrString("statementType")).isEqualTo("DELETE");
        assertThat(node(g, "mybatis:stmt:com.a.MemberMapper.add").attrString("statementType")).isEqualTo("INSERT");
        assertThat(node(g, "mybatis:stmt:com.a.MemberMapper.search").attrBoolean("dynamic")).isTrue();
        assertThat(g.node("mybatis:stmt:com.a.MemberMapper.xmlOnly")).isEmpty();
        assertThat(g.nodes(NodeKind.SQL_STATEMENT)).hasSize(5);

        List<EmbeddedSource> embedded = ctx.embedded(EmbeddedSource.SQL);
        assertThat(embedded).hasSize(5);
        EmbeddedSource first = sourceOf(embedded, count.id());
        assertThat(first.text()).isEqualTo("SELECT COUNT(*) FROM T_MEMBER WHERE LOGIN_ID = ?");
        assertThat(first.exactPositions()).isFalse();
        assertThat(first.origin().file()).isEqualTo("src/com/a/MemberMapper.java");
        assertThat(first.origin().startLine()).isEqualTo(7);
        assertThat(sourceOf(embedded, "mybatis:stmt:com.a.MemberMapper.rename").text())
                .isEqualTo("UPDATE __DYNAMIC__ SET NAME = ? WHERE ID = ?");
        String search = sourceOf(embedded, "mybatis:stmt:com.a.MemberMapper.search").text().replaceAll("\\s+", " ").trim();
        assertThat(search).isEqualTo("SELECT M.ID, M.NAME FROM T_MEMBER M WHERE M.NAME = ? AND M.ID IN ( ? ) AND M.AGE < 20");
    }

    private static EmbeddedSource sourceOf(List<EmbeddedSource> all, String owner) {
        return all.stream().filter(s -> s.ownerNodeId().equals(owner)).findFirst().orElseThrow();
    }
}
