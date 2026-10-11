package io.codeatlas.sql;

import static io.codeatlas.sql.GraphAssert.diagnostics;
import static io.codeatlas.sql.GraphAssert.edge;
import static io.codeatlas.sql.GraphAssert.hasEdge;
import static io.codeatlas.sql.GraphAssert.node;
import static io.codeatlas.sql.GraphAssert.targets;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 데이터 적재 스크립트의 리터럴 INSERT 묶기(F-006-R15, F-006-R24). 공개 프로젝트에서 INSERT 62만 건마다 구문 노드와 컬럼별 WRITES를
 * 만들어 메모리가 모자랐다. 한 파일에서 같은 테이블에 넣는 리터럴 INSERT가 10건 이상이면 구문 노드 하나로 묶는다.
 * 그런 묶음이 생긴 파일과 INSERT뿐인 파일(10건 이상)은 "데이터 적재 파일"이라, 10건 미만인 테이블의 INSERT도 테이블별로 묶는다.
 */
class DataLoadTest {

    private static GraphModel analyze(String path, String sql) {
        try (TestProject project = TestProject.create().file(path, sql)) {
            return project.run(new SqlAnalyzer()).graph();
        }
    }

    private static List<String> statementIds(GraphModel g) {
        return g.nodes(NodeKind.SQL_STATEMENT).stream().map(Node::id).collect(Collectors.toList());
    }

    /** {@code INSERT INTO <table> (CD, NM) VALUES ('<i>', 'x');} 줄을 count개 만든다. */
    private static String rows(String table, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append("INSERT INTO ").append(table).append(" (CD, NM) VALUES ('").append(i).append("', 'x');\n");
        }
        return sb.toString();
    }

    @Test
    @DisplayName("F-006-AC15 F-006-AC24 리터럴 INSERT는 (파일, 테이블)마다 구문 노드 하나로 묶인다: ID는 첫 INSERT 줄, dataLoad·rowCount, WRITES는 테이블 1개와 컬럼 합집합")
    void groupsLiteralInserts() {
        GraphModel g = analyze("db/data.sql", "-- 코드\n"
                + "INSERT INTO TB_CODE (CD, NM) VALUES ('A', '가');\n"
                + "INSERT INTO TB_CODE (CD, NM, USE_YN) VALUES ('B', '나', 'Y');\n"
                + "INSERT INTO TB_USR (ID, NM, REG_DT) VALUES (SEQ_USR.NEXTVAL, 'x', SYSDATE);\n"
                + "INSERT INTO TB_CODE (CD, NM, SORT) VALUES (SEQ_CODE.NEXTVAL, UPPER('d'), 1 + 2);\n"
                + rows("TB_CODE", 9)
                + "INSERT INTO TB_USR (ID, NM, REG_DT) VALUES (2, 'y' || 'z', TO_DATE('2020-01-01', 'YYYY-MM-DD'));\n"
                + "COMMIT;\n");
        // TB_CODE 12건이 묶이면 이 파일은 적재 파일이다: 기준(10건)에 못 미치는 TB_USR 2건도 테이블별 묶음 하나가 된다(F-006-R24)
        assertThat(statementIds(g)).containsExactlyInAnyOrder("sql:stmt:db/data.sql:2", "sql:stmt:db/data.sql:4");
        Node code = node(g, Ids.sqlStatement("db/data.sql", 2));
        assertThat(code.attrString("statementType")).isEqualTo("INSERT");
        assertThat(code.attr("dataLoad")).isEqualTo(true);
        assertThat(code.attr("rowCount")).isEqualTo(12);
        assertThat(code.attrString("sql")).isEqualTo("INSERT INTO TB_CODE (CD, NM) VALUES ('A', '가')");
        assertThat(code.name()).isEqualTo("data.sql:2");
        assertThat(code.qualifiedName()).isEqualTo("db/data.sql:2");
        assertThat(code.location().startLine()).isEqualTo(2);
        assertThat(code.attrString("dialect")).isEqualTo("ORACLE");
        assertThat(targets(g, code.id(), EdgeKind.WRITES)).containsExactly(
                "db:column:TB_CODE.CD", "db:column:TB_CODE.NM", "db:column:TB_CODE.SORT", "db:column:TB_CODE.USE_YN", "db:table:TB_CODE");
        assertThat(edge(g, code.id(), EdgeKind.WRITES, "db:table:TB_CODE").attrString("op")).isEqualTo("INSERT");
        assertThat(edge(g, code.id(), EdgeKind.WRITES, "db:column:TB_CODE.SORT").attrString("op")).isEqualTo("INSERT");
        assertThat(g.edges(code.id(), EdgeKind.WRITES, "db:table:TB_CODE")).hasSize(1);
        assertThat(edge(g, code.id(), EdgeKind.WRITES, "db:table:TB_CODE").location().startLine()).isEqualTo(2);
        assertThat(edge(g, code.id(), EdgeKind.WRITES, "db:column:TB_CODE.SORT").location().startLine()).isEqualTo(5);
        assertThat(hasEdge(g, "file:db/data.sql", EdgeKind.CONTAINS, code.id())).isTrue();
        // 묶음 노드의 엣지는 WRITES뿐이다(값 자리의 시퀀스 READS는 만들지 않는다)
        assertThat(g.outgoing(code.id())).allMatch(e -> e.kind() == EdgeKind.WRITES);
        assertThat(g.hasNode("db:sequence:SEQ_CODE")).isFalse();

        Node usr = node(g, Ids.sqlStatement("db/data.sql", 4));
        assertThat(usr.attr("dataLoad")).isEqualTo(true);
        assertThat(usr.attr("rowCount")).isEqualTo(2);
        assertThat(targets(g, usr.id(), EdgeKind.WRITES)).containsExactly(
                "db:column:TB_USR.ID", "db:column:TB_USR.NM", "db:column:TB_USR.REG_DT", "db:table:TB_USR");
        assertThat(g.outgoing(usr.id())).allMatch(e -> e.kind() == EdgeKind.WRITES);
        assertThat(g.hasNode("db:sequence:SEQ_USR")).isFalse();

        List<Diagnostic> info = diagnostics(g, SqlDiagnosticCodes.DATA_LOAD_GROUPED);
        assertThat(info).hasSize(1);
        assertThat(info.get(0).severity()).isEqualTo(Severity.INFO);
        assertThat(info.get(0).message()).contains("14건").contains("2개");
        assertThat(info.get(0).location().file()).isEqualTo("db/data.sql");
        assertThat(g.diagnostics()).hasSize(1);
    }

    @Test
    @DisplayName("F-006-AC15 묶기 시작하는 기준은 (파일, 테이블)의 리터럴 INSERT 10건: 9건뿐인 파일이면 전처럼 구문마다 노드, 10건이면 하나")
    void threshold() {
        GraphModel nine = analyze("db/c.sql", rows("TB_CODE", 9));
        assertThat(statementIds(nine)).hasSize(9);
        assertThat(nine.nodes(NodeKind.SQL_STATEMENT)).noneMatch(n -> n.attr("dataLoad") != null);
        assertThat(hasEdge(nine, "sql:stmt:db/c.sql:7", EdgeKind.WRITES, "db:table:TB_CODE")).isTrue();
        assertThat(nine.diagnostics()).isEmpty();

        GraphModel ten = analyze("db/c.sql", rows("TB_CODE", 10));
        assertThat(statementIds(ten)).containsExactly("sql:stmt:db/c.sql:1");
        assertThat(node(ten, "sql:stmt:db/c.sql:1").attr("rowCount")).isEqualTo(10);
        assertThat(diagnostics(ten, SqlDiagnosticCodes.DATA_LOAD_GROUPED)).hasSize(1);
    }

    @Test
    @DisplayName("F-006-AC15 다중 행 VALUES, INSERT ALL .. SELECT .. FROM DUAL, 컬럼 목록 없는 INSERT, SELECT 리터럴 FROM DUAL도 같은 규칙으로 묶는다")
    void multiRowForms() {
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO T1 (A, B) VALUES (1, 'x'), (2, 'y'), (3, 'z');\n"); // 1
        for (int i = 0; i < 9; i++) {
            sb.append("INSERT INTO T1 (A, C) VALUES (").append(i).append(", -1);\n"); // 2..10
        }
        for (int i = 0; i < 10; i++) {
            sb.append("INSERT ALL INTO T2 (A) VALUES (1) INTO T2 (A, B) VALUES (2, 'b') INTO T3 (K) VALUES ('k') SELECT * FROM DUAL;\n"); // 11..20
        }
        for (int i = 0; i < 10; i++) {
            sb.append("INSERT INTO T4 VALUES ('p', NULL);\n"); // 21..30
        }
        for (int i = 0; i < 10; i++) {
            sb.append("INSERT INTO T5 (A, B) SELECT 1, 'a' FROM DUAL UNION ALL SELECT 2, 'b' FROM DUAL;\n"); // 31..40
        }
        GraphModel g = analyze("db/load.sql", sb.toString());
        assertThat(statementIds(g)).containsExactlyInAnyOrder(
                "sql:stmt:db/load.sql:1", "sql:stmt:db/load.sql:11", "sql:stmt:db/load.sql:21", "sql:stmt:db/load.sql:31");
        Node t1 = node(g, "sql:stmt:db/load.sql:1");
        assertThat(t1.attr("rowCount")).isEqualTo(12);
        assertThat(targets(g, t1.id(), EdgeKind.WRITES)).containsExactly("db:column:T1.A", "db:column:T1.B", "db:column:T1.C", "db:table:T1");
        // INSERT ALL: 구문의 첫 대상 테이블의 묶음에 들어가고, 다른 대상 테이블의 WRITES도 그 노드에서 나간다
        Node all = node(g, "sql:stmt:db/load.sql:11");
        assertThat(all.attr("rowCount")).isEqualTo(30);
        assertThat(all.attr("dataLoad")).isEqualTo(true);
        assertThat(targets(g, all.id(), EdgeKind.WRITES)).containsExactly(
                "db:column:T2.A", "db:column:T2.B", "db:column:T3.K", "db:table:T2", "db:table:T3");
        Node t4 = node(g, "sql:stmt:db/load.sql:21");
        assertThat(t4.attr("rowCount")).isEqualTo(10);
        assertThat(targets(g, t4.id(), EdgeKind.WRITES)).containsExactly("db:table:T4");
        Node t5 = node(g, "sql:stmt:db/load.sql:31");
        assertThat(t5.attr("rowCount")).isEqualTo(20);
        assertThat(targets(g, t5.id(), EdgeKind.WRITES)).containsExactly("db:column:T5.A", "db:column:T5.B", "db:table:T5");
        assertThat(g.outgoing(t5.id())).allMatch(e -> e.kind() == EdgeKind.WRITES);
        assertThat(t5.attr("dummyTable")).isNull();
        assertThat(diagnostics(g, SqlDiagnosticCodes.DATA_LOAD_GROUPED).get(0).message()).contains("40건").contains("4개");
    }

    @Test
    @DisplayName("F-006-AC15 서브쿼리가 있거나 테이블을 읽는 INSERT, 다른 DML은 묶지 않고 지금처럼 구문마다 노드다")
    void nonLiteralInsertsStayIndividual() {
        GraphModel g = analyze("db/mix.sql", rows("T1", 10) // 1..10
                + "INSERT INTO T1 (A) SELECT A FROM SRC;\n" // 11
                + "INSERT INTO T1 (A) VALUES ((SELECT MAX(A) + 1 FROM T1));\n" // 12
                + "INSERT INTO T1 (CD, NM) VALUES ('z', 'x');\n" // 13
                + "UPDATE T1 SET A = 3 WHERE A = 2;\n" // 14
                + "INSERT INTO T1 (A, B) SELECT 1, X.B FROM DUAL, T9 X;\n" // 15
                + "INSERT INTO T1 (A) VALUES (5) RETURNING A INTO :v;\n" // 16
                + "INSERT INTO T1 (A) SELECT 1 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM T1 WHERE A = 1);\n"); // 17
        assertThat(statementIds(g)).containsExactlyInAnyOrder(
                "sql:stmt:db/mix.sql:1", "sql:stmt:db/mix.sql:11", "sql:stmt:db/mix.sql:12", "sql:stmt:db/mix.sql:14", "sql:stmt:db/mix.sql:15",
                "sql:stmt:db/mix.sql:16", "sql:stmt:db/mix.sql:17");
        assertThat(node(g, "sql:stmt:db/mix.sql:1").attr("rowCount")).isEqualTo(11);
        Node select = node(g, "sql:stmt:db/mix.sql:11");
        assertThat(select.attr("dataLoad")).isNull();
        assertThat(select.attr("rowCount")).isNull();
        assertThat(hasEdge(g, select.id(), EdgeKind.READS, "db:table:SRC")).isTrue();
        assertThat(node(g, "sql:stmt:db/mix.sql:12").attr("dataLoad")).isNull();
        assertThat(hasEdge(g, "sql:stmt:db/mix.sql:12", EdgeKind.READS, "db:table:T1")).isTrue();
        assertThat(node(g, "sql:stmt:db/mix.sql:14").attrString("statementType")).isEqualTo("UPDATE");
        assertThat(hasEdge(g, "sql:stmt:db/mix.sql:15", EdgeKind.READS, "db:table:T9")).isTrue();
        assertThat(node(g, "sql:stmt:db/mix.sql:16").attr("dataLoad")).isNull();
        assertThat(hasEdge(g, "sql:stmt:db/mix.sql:17", EdgeKind.READS, "db:table:T1")).isTrue();
    }

    @Test
    @DisplayName("F-006-AC15 묶음은 파일마다 따로다. 깨진 INSERT는 묶지 않고 진단을 남긴다. 사용자 정의 함수를 부른 적재 구문은 묶음 노드에서 CALLS가 나간다")
    void perFileAndBrokenAndUserFunction() {
        try (TestProject project = TestProject.create()
                .file("db/a.sql", rows("T1", 9) + "INSERT INTO T1 (CD) VALUES (FN_NEXT('T1'));\nINSERT INTO T1 (A, B VALUES (3, 4);\n")
                .file("db/b.sql", rows("T1", 10))
                .file("db/fn.sql", "CREATE OR REPLACE FUNCTION FN_NEXT (P VARCHAR2) RETURN NUMBER IS\nBEGIN\n  RETURN 1;\nEND;\n/\n")) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();
            assertThat(node(g, "sql:stmt:db/a.sql:1").attr("rowCount")).isEqualTo(10);
            assertThat(node(g, "sql:stmt:db/b.sql:1").attr("rowCount")).isEqualTo(10);
            assertThat(hasEdge(g, "sql:stmt:db/a.sql:1", EdgeKind.CALLS, "db:routine:FN_NEXT")).isTrue();
            assertThat(edge(g, "sql:stmt:db/a.sql:1", EdgeKind.CALLS, "db:routine:FN_NEXT").location().startLine()).isEqualTo(10);
            assertThat(hasEdge(g, "sql:stmt:db/b.sql:1", EdgeKind.CALLS, "db:routine:FN_NEXT")).isFalse();
            Node broken = node(g, "sql:stmt:db/a.sql:11");
            assertThat(broken.attr("dataLoad")).isNull();
            assertThat(hasEdge(g, broken.id(), EdgeKind.WRITES, "db:table:T1")).isTrue();
            assertThat(diagnostics(g, DiagnosticCodes.PARSE_FAILED)).hasSize(1);
            assertThat(diagnostics(g, SqlDiagnosticCodes.DATA_LOAD_GROUPED)).hasSize(2);
        }
    }

    @Test
    @DisplayName("F-006-AC15 매퍼(내장 소스)와 루틴 본문·익명 블록의 INSERT는 묶지 않는다")
    void embeddedAndRoutineInsertsAreNotGrouped() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            body.append("  INSERT INTO T1 (A) VALUES (").append(i).append(");\n");
        }
        try (TestProject project = TestProject.create()
                .file("mapper/M.xml", "<mapper namespace=\"m\"/>")
                .file("db/p.sql", "CREATE OR REPLACE PROCEDURE P_LOAD IS\nBEGIN\n" + body + "END;\n/\nBEGIN\n" + body + "END;\n/\n")) {
            AnalysisContext ctx = project.context();
            String owner = Ids.mybatisStatement("m", "ins");
            ctx.graph().declare(Node.builder(owner, NodeKind.SQL_STATEMENT).name("ins").location(SourceLocation.of("mapper/M.xml", 1)).build());
            ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, owner, "INSERT INTO T1 (A, B) VALUES ('x', 'y');\n".repeat(12),
                    SourceLocation.of("mapper/M.xml", 1, 1, 1, 1), false, Map.of()));
            GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();
            assertThat(node(g, owner).attr("dataLoad")).isNull();
            assertThat(targets(g, owner, EdgeKind.WRITES)).containsExactly("db:column:T1.A", "db:column:T1.B", "db:table:T1");
            assertThat(g.nodes(NodeKind.SQL_STATEMENT)).noneMatch(n -> n.attr("dataLoad") != null);
            for (int line = 3; line < 15; line++) {
                assertThat(g.hasNode("sql:stmt:P_LOAD:" + line)).as("line %d", line).isTrue();
            }
            assertThat(hasEdge(g, "sql:stmt:db/p.sql:17", EdgeKind.WRITES, "db:table:T1")).isTrue();
            assertThat(diagnostics(g, SqlDiagnosticCodes.DATA_LOAD_GROUPED)).isEmpty();
        }
    }

    @Test
    @DisplayName("F-006-AC24 10건 이상 묶음이 생긴 파일은 적재 파일이다: 10건 미만인 테이블의 리터럴 INSERT도 테이블별로 묶는다(1건이어도 dataLoad, rowCount=1)")
    void loadFileGroupsSmallTables() {
        // 기준에 못 미친 테이블이 앞에 오든 뒤에 오든 같다
        GraphModel g = analyze("db/data.sql", rows("TB_B", 3) // 1..3
                + rows("TB_A", 12) // 4..15
                + "INSERT INTO TB_C (CD, USE_YN) VALUES ('x', 'Y');\n" // 16
                + "UPDATE TB_A SET NM = 'y' WHERE CD = '1';\n" // 17
                + "DELETE FROM TB_A WHERE CD = '2';\n" // 18
                + "DELETE FROM TB_OLD;\n" // 19
                + "COMMIT;\n");
        assertThat(statementIds(g)).containsExactlyInAnyOrder("sql:stmt:db/data.sql:1", "sql:stmt:db/data.sql:4", "sql:stmt:db/data.sql:16",
                "sql:stmt:db/data.sql:17", "sql:stmt:db/data.sql:18", "sql:stmt:db/data.sql:19");
        Node b = node(g, "sql:stmt:db/data.sql:1");
        assertThat(b.attr("dataLoad")).isEqualTo(true);
        assertThat(b.attr("rowCount")).isEqualTo(3);
        assertThat(b.attrString("statementType")).isEqualTo("INSERT");
        assertThat(b.attrString("sql")).isEqualTo("INSERT INTO TB_B (CD, NM) VALUES ('0', 'x')");
        assertThat(targets(g, b.id(), EdgeKind.WRITES)).containsExactly("db:column:TB_B.CD", "db:column:TB_B.NM", "db:table:TB_B");
        assertThat(node(g, "sql:stmt:db/data.sql:4").attr("rowCount")).isEqualTo(12);
        Node c = node(g, "sql:stmt:db/data.sql:16");
        assertThat(c.attr("dataLoad")).isEqualTo(true);
        assertThat(c.attr("rowCount")).isEqualTo(1);
        assertThat(targets(g, c.id(), EdgeKind.WRITES)).containsExactly("db:column:TB_C.CD", "db:column:TB_C.USE_YN", "db:table:TB_C");
        // 적재 파일이어도 리터럴 INSERT가 아닌 구문은 보통의 구문이다. 조건 없는 DELETE(초기화)만 dataLoad가 붙는다
        assertThat(node(g, "sql:stmt:db/data.sql:17").attr("dataLoad")).isNull();
        assertThat(node(g, "sql:stmt:db/data.sql:18").attr("dataLoad")).isNull();
        Node reset = node(g, "sql:stmt:db/data.sql:19");
        assertThat(reset.attr("dataLoad")).isEqualTo(true);
        assertThat(reset.attr("rowCount")).isEqualTo(0);
        assertThat(reset.attrString("statementType")).isEqualTo("DELETE");
        assertThat(edge(g, reset.id(), EdgeKind.WRITES, "db:table:TB_OLD").attrString("op")).isEqualTo("DELETE");

        List<Diagnostic> info = diagnostics(g, SqlDiagnosticCodes.DATA_LOAD_GROUPED);
        assertThat(info).hasSize(1);
        assertThat(info.get(0).message()).contains("INSERT 16건").contains("3개").contains("10건 미만").contains("2개").contains("초기화").contains("1건");
        assertThat(g.diagnostics()).hasSize(1);
    }

    @Test
    @DisplayName("F-006-AC24 리터럴 INSERT(와 COMMIT, 조건 없는 DELETE·TRUNCATE, SET 류 세션 명령)뿐이고 INSERT가 10건 이상인 파일은 테이블마다 10건 미만이어도 적재 파일이다")
    void insertOnlyFileIsLoadFile() {
        String body = rows("T1", 4) // 4..7
                + rows("T2", 4) // 8..11
                + rows("T3", 3) // 12..14
                + "COMMIT;\n";
        GraphModel g = analyze("db/init_data.sql", "SET DEFINE OFF\n" + "DELETE FROM T1;\n" + "TRUNCATE TABLE T2;\n" + body);
        assertThat(statementIds(g)).containsExactlyInAnyOrder("sql:stmt:db/init_data.sql:2", "sql:stmt:db/init_data.sql:3",
                "sql:stmt:db/init_data.sql:4", "sql:stmt:db/init_data.sql:8", "sql:stmt:db/init_data.sql:12");
        assertThat(g.nodes(NodeKind.SQL_STATEMENT)).allMatch(n -> Boolean.TRUE.equals(n.attr("dataLoad")));
        assertThat(node(g, "sql:stmt:db/init_data.sql:4").attr("rowCount")).isEqualTo(4);
        assertThat(node(g, "sql:stmt:db/init_data.sql:12").attr("rowCount")).isEqualTo(3);
        Node truncate = node(g, "sql:stmt:db/init_data.sql:3");
        assertThat(truncate.attrString("statementType")).isEqualTo("DELETE");
        assertThat(edge(g, truncate.id(), EdgeKind.WRITES, "db:table:T2").attr("truncate")).isEqualTo(true);
        assertThat(diagnostics(g, SqlDiagnosticCodes.DATA_LOAD_GROUPED).get(0).message()).contains("INSERT 11건").contains("3개").contains("초기화").contains("2건");

        // INSERT가 9건이면 적재 파일이 아니다: 전처럼 구문마다 노드이고 초기화 구문에도 dataLoad가 없다
        GraphModel nine = analyze("db/init_data.sql", "DELETE FROM T1;\n" + rows("T1", 4) + rows("T2", 5) + "COMMIT;\n");
        assertThat(statementIds(nine)).hasSize(10);
        assertThat(nine.nodes(NodeKind.SQL_STATEMENT)).noneMatch(n -> n.attr("dataLoad") != null);
        assertThat(nine.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("F-006-AC24 DDL·COMMENT·다른 DML과 섞인 소량 INSERT 파일은 적재 파일이 아니다: 10건이 넘어도 테이블마다 10건 미만이면 전처럼 구문마다 노드다")
    void mixedFileStaysIndividual() {
        GraphModel g = analyze("db/04_comments_and_codes.sql", "CREATE TABLE T1 (CD VARCHAR2(10), NM VARCHAR2(10));\n" // 1
                + "COMMENT ON TABLE T1 IS '코드';\n" // 2
                + rows("T1", 7) // 3..9
                + rows("T2", 5) // 10..14
                + "DELETE FROM T3;\n" // 15
                + "COMMIT;\n");
        assertThat(statementIds(g)).hasSize(13);
        assertThat(g.hasNode("sql:stmt:db/04_comments_and_codes.sql:9")).isTrue();
        assertThat(g.nodes(NodeKind.SQL_STATEMENT)).noneMatch(n -> n.attr("dataLoad") != null);
        assertThat(diagnostics(g, SqlDiagnosticCodes.DATA_LOAD_GROUPED)).isEmpty();

        // SELECT 하나만 끼어도 "INSERT뿐인 파일"이 아니다
        GraphModel withSelect = analyze("db/x.sql", rows("T1", 6) + rows("T2", 6) + "SELECT COUNT(*) FROM T1;\n");
        assertThat(statementIds(withSelect)).hasSize(13);
        assertThat(withSelect.nodes(NodeKind.SQL_STATEMENT)).noneMatch(n -> n.attr("dataLoad") != null);
    }

    @Test
    @DisplayName("F-006-AC15 INSERT 20만 건짜리 스크립트도 구문 노드 하나, 엣지 몇 개로 끝난다(단위를 쥐고 있지 않는다)")
    void largeScript() {
        StringBuilder sb = new StringBuilder(32_000_000);
        for (int i = 0; i < 200_000; i++) {
            sb.append("INSERT INTO TB_BIG (ID, NM, AMT, REG_DT, USE_YN) VALUES (").append(i).append(", 'name ").append(i)
                    .append("', ").append(i % 97).append(".5, SYSDATE, 'Y');\n");
        }
        try (TestProject project = TestProject.create().file("db/big.sql", sb.toString())) {
            GraphModel g = Assertions.assertTimeoutPreemptively(Duration.ofSeconds(120), () -> project.run(new SqlAnalyzer()).graph());
            assertThat(g.nodes(NodeKind.SQL_STATEMENT)).hasSize(1);
            Node n = node(g, "sql:stmt:db/big.sql:1");
            assertThat(n.attr("rowCount")).isEqualTo(200_000);
            assertThat(g.outgoing(n.id())).hasSize(6);
        }
    }
}
