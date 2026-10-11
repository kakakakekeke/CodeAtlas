package io.codeatlas.sql;

import static io.codeatlas.sql.GraphAssert.edge;
import static io.codeatlas.sql.GraphAssert.node;
import static io.codeatlas.sql.GraphAssert.targets;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code .properties} 파일의 값에 든 SQL(F-006-R43·R44). 보안 프레임워크의 권한 조회 SQL처럼 매퍼 밖 설정 파일에 적어 두고
 * 라이브러리가 실행하는 구문도 테이블의 영향도에 나와야 한다. 화면 문구는 SQL로 읽지 않는다.
 */
class PropertiesSqlTest {

    private static final String FILE = "src/main/resources/conf/security.properties";

    @Test
    @DisplayName("F-006-AC43 properties 값이 SQL 구문 꼴이면 키의 줄을 ID로 하는 구문 노드가 생기고(name·propertyKey는 키), 파일에서 CONTAINS가 오고, 본문의 테이블·조인·호출이 엣지로 남는다. EXECUTES는 없다")
    void sqlValuesBecomeStatements() {
        try (TestProject project = TestProject.create()
                .file("db/ddl.sql", """
                        CREATE TABLE COMTNROLEINFO (ROLE_CODE VARCHAR2(50), ROLE_PTTRN VARCHAR2(300), ROLE_TY VARCHAR2(80), ROLE_SORT VARCHAR2(10));
                        CREATE TABLE COMTNAUTHORROLERELATE (AUTHOR_CODE VARCHAR2(30), ROLE_CODE VARCHAR2(50));
                        """)
                .file(FILE, """
                        # 권한 조회
                        Globals.Auth = security
                        sqlRolesAndUrl = SELECT a.ROLE_PTTRN as url, b.AUTHOR_CODE authority FROM COMTNROLEINFO a, COMTNAUTHORROLERELATE b WHERE a.ROLE_CODE = b.ROLE_CODE AND a.ROLE_TY = 'url' ORDER BY a.ROLE_SORT
                        ! 소문자, 두 줄
                        sqlRoleAndUrl=select a.role_pttrn url, b.author_code authority \\
                            from comtnroleinfo a, comtnauthorrolerelate b where a.role_code = b.role_code
                        audit.insert: INSERT INTO TB_AUDIT (USER_ID, ACT) VALUES (?, ?)
                        user.lock = UPDATE TB_USER SET LOCK_AT = 'Y' WHERE USER_ID = ?
                        log.purge = DELETE FROM TB_LOG WHERE REG_DT < ?
                        stats.call = {call PKG_STATS.REFRESH(?)}
                        order.nextId = SELECT SEQ_ORDER.NEXTVAL FROM DUAL
                        """)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();

            String roles = "sql:stmt:" + FILE + ":3";
            Node node = node(g, roles);
            assertThat(node.kind()).isEqualTo(NodeKind.SQL_STATEMENT);
            assertThat(node.name()).isEqualTo("sqlRolesAndUrl");
            assertThat(node.attr("propertyKey")).isEqualTo("sqlRolesAndUrl");
            assertThat(node.attr("statementType")).isEqualTo("SELECT");
            assertThat(node.attrString("sql")).startsWith("SELECT a.ROLE_PTTRN as url").endsWith("ORDER BY a.ROLE_SORT");
            assertThat(node.location().file()).isEqualTo(FILE);
            assertThat(node.location().startLine()).isEqualTo(3);
            edge(g, "file:" + FILE, EdgeKind.CONTAINS, roles);
            assertThat(targets(g, roles, EdgeKind.READS)).contains("db:table:COMTNROLEINFO", "db:table:COMTNAUTHORROLERELATE",
                    "db:column:COMTNROLEINFO.ROLE_PTTRN");
            // 조인 조건은 테이블 사이의 JOINS로 남고 근거 구문이 이 구문이다
            assertThat(edge(g, "db:table:COMTNROLEINFO", EdgeKind.JOINS, "db:table:COMTNAUTHORROLERELATE").attrString("statement")).isEqualTo(roles);

            // 소문자로 쓰고 줄 끝 \로 이은 값: ID는 키가 있는 줄이고 테이블은 대문자 이름으로 간다
            String lower = "sql:stmt:" + FILE + ":5";
            assertThat(node(g, lower).name()).isEqualTo("sqlRoleAndUrl");
            assertThat(node(g, lower).attrString("sql")).contains("authority from comtnroleinfo");
            assertThat(targets(g, lower, EdgeKind.READS)).contains("db:table:COMTNROLEINFO", "db:table:COMTNAUTHORROLERELATE");

            // 구분자 ':' 와 쓰기 구문
            String insert = "sql:stmt:" + FILE + ":7";
            assertThat(node(g, insert).name()).isEqualTo("audit.insert");
            assertThat(node(g, insert).attr("statementType")).isEqualTo("INSERT");
            assertThat(targets(g, insert, EdgeKind.WRITES)).contains("db:table:TB_AUDIT");
            assertThat(targets(g, "sql:stmt:" + FILE + ":8", EdgeKind.WRITES)).contains("db:table:TB_USER");
            assertThat(targets(g, "sql:stmt:" + FILE + ":9", EdgeKind.WRITES)).contains("db:table:TB_LOG");
            assertThat(targets(g, "sql:stmt:" + FILE + ":10", EdgeKind.CALLS)).containsExactly("db:routine:PKG_STATS.REFRESH");
            // 더미 테이블을 읽어도 시퀀스를 읽는 구문은 연결 확인 쿼리가 아니다
            assertThat(targets(g, "sql:stmt:" + FILE + ":11", EdgeKind.READS)).containsExactly("db:sequence:SEQ_ORDER");

            // SQL이 아닌 값은 구문이 아니다. 누가 실행하는지는 알 수 없다
            List<Node> statements = g.nodes().stream().filter(n -> n.kind() == NodeKind.SQL_STATEMENT).toList();
            assertThat(statements).extracting(Node::name)
                    .containsExactlyInAnyOrder("sqlRolesAndUrl", "sqlRoleAndUrl", "audit.insert", "user.lock", "log.purge", "stats.call", "order.nextId");
            assertThat(statements).allSatisfy(n -> assertThat(g.incoming(n.id(), EdgeKind.EXECUTES)).isEmpty());
        }
    }

    @Test
    @DisplayName("F-006-AC44 SQL 구문 꼴이 아닌 값(Select, Select a list to delete!, Update Executive Calendar), 더미 테이블만 읽는 연결 확인 쿼리(SELECT 'x' FROM DUAL), 메시지 번들 파일의 값은 SQL로 읽지 않는다: 구문 노드도 테이블 노드도 없다")
    void labelsAndMessageBundlesAreNotSql() {
        String sentences = """
                page.pick = Select a file from the list
                cart.confirm = Delete from cart
                """;
        try (TestProject project = TestProject.create()
                .file("src/main/resources/app.properties", """
                        button.select = Select
                        validate.noDelList = Select a list to delete!
                        title = Update Executive Calendar
                        btn.withdraw = Delete Your Account
                        search.cnd = Select query criteria
                        call.center = Call center (weekdays)
                        with.you = With you as always
                        jdbc.url = jdbc:oracle:thin:@db:1521:XE
                        jdbc.testSql = SELECT 'x' FROM DUAL
                        db2.validationQuery = select 1 from sysibm.sysdummy1
                        """)
                .file("src/main/resources/message/message-common_en.properties", sentences)
                .file("src/main/resources/i18n/labels.properties", sentences)
                .file("src/main/resources/ApplicationResources.properties", sentences)
                .file("src/main/resources/texts_ko_KR.properties", sentences)) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();

            assertThat(g.nodes()).extracting(Node::kind)
                    .doesNotContain(NodeKind.SQL_STATEMENT, NodeKind.DB_TABLE, NodeKind.DB_COLUMN, NodeKind.DB_ROUTINE);
        }
    }
}
