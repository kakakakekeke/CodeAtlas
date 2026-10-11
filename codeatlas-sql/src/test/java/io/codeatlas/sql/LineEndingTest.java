package io.codeatlas.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.testing.TestProject;
import io.codeatlas.sql.lexer.Lexer;
import io.codeatlas.sql.lexer.Syntax;
import io.codeatlas.sql.lexer.Token;
import io.codeatlas.sql.lexer.TokenType;
import io.codeatlas.sql.script.ScriptSplitter;
import io.codeatlas.sql.script.ScriptUnit;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * F-006-R32: 줄 끝은 LF, CRLF, CR 단독(옛 Mac·일부 편집기) 셋 다 한 줄로 센다. 다른 모듈(web, jsp, mybatis)과 같은 규칙이다.
 * 줄 번호가 구문 ID({@code sql:stmt:<파일>:<줄>}, 루틴 구문 ID)에 들어가므로 어긋나면 노드가 다른 줄을 가리키거나 서로 합쳐진다.
 */
class LineEndingTest {

    /** LF로 쓴 픽스처를 다른 줄 끝으로 바꾼다. */
    private static String with(String lf, String eol) {
        return lf.replace("\n", eol);
    }

    /** 줄마다 줄 끝을 돌려 가며 섞는다(CR, CRLF, LF). */
    private static String mixed(String lf) {
        String[] eols = {"\r", "\r\n", "\n"};
        StringBuilder sb = new StringBuilder();
        int k = 0;
        for (int i = 0; i < lf.length(); i++) {
            char c = lf.charAt(i);
            if (c == '\n') {
                sb.append(eols[k++ % eols.length]);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static Stream<Arguments> lineEndings() {
        return Stream.of(Arguments.of("LF", "\n"), Arguments.of("CRLF", "\r\n"), Arguments.of("CR", "\r"), Arguments.of("섞임", null));
    }

    private static String convert(String lf, String eol) {
        return eol == null ? mixed(lf) : with(lf, eol);
    }

    private static String positions(List<Token> tokens) {
        return tokens.stream().filter(t -> !t.isEof())
                .map(t -> t.text() + "@" + t.line() + ":" + t.column() + (t.firstOnLine() ? "^" : "") + (t.blankLineBefore() ? "_" : ""))
                .collect(Collectors.joining(" "));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lineEndings")
    @DisplayName("F-006-AC32 렉서: 줄 끝이 LF·CRLF·CR 단독·섞임 어느 것이어도 토큰의 줄·열, 줄 처음 표시, 빈 줄 표시가 같다")
    void lexerCountsEveryLineEnding(String label, String eol) {
        String lf = "SELECT A, -- 주석 FROM X\n       'x' AS S /* 여러\n줄 */, B\n  FROM T\n\n WHERE A = 1\n";
        String expected = positions(Lexer.tokenize(lf));
        assertThat(expected).contains("FROM@4:3^").contains("WHERE@6:2^_").contains("B@3:7");

        assertThat(positions(Lexer.tokenize(convert(lf, eol)))).isEqualTo(expected);
        // 주석을 남기는 렉싱에서도 주석이 줄 끝에서 끝난다(다음 줄을 삼키지 않는다)
        List<Token> withComments = Lexer.tokenizeWithComments(convert(lf, eol));
        Token comment = withComments.stream().filter(t -> t.type() == TokenType.COMMENT).findFirst().orElseThrow();
        assertThat(comment.text()).isEqualTo("-- 주석 FROM X");
        assertThat(comment.endLine()).isEqualTo(1);
    }

    @Test
    @DisplayName("F-006-AC32 렉서: CR 단독으로 끝난 줄의 닫히지 않은 문자열·따옴표 이름·#{..}는 그 줄에서 끊긴다")
    void unterminatedTokensEndAtLoneCr() {
        List<Token> tokens = Lexer.tokenize("SELECT \"A\rFROM T\rWHERE X = #{id\rAND Y = 1");
        assertThat(tokens.stream().filter(t -> t.is("FROM")).findFirst().orElseThrow().line()).isEqualTo(2);
        assertThat(tokens.stream().filter(t -> t.is("AND")).findFirst().orElseThrow().line()).isEqualTo(4);
        Token quoted = tokens.stream().filter(t -> t.type() == TokenType.QUOTED_IDENT).findFirst().orElseThrow();
        assertThat(quoted.unterminated()).isTrue();
        assertThat(quoted.text()).isEqualTo("\"A");
    }

    @Test
    @DisplayName("F-006-AC32 렉서: MySQL로 판정한 SQL의 줄 처음 # 주석도 CR 단독에서 끝난다")
    void hashCommentEndsAtLoneCr() {
        List<Token> tokens = Lexer.tokenize("# 주석 SELECT\rSELECT A\r# 또\rFROM T", Syntax.MYSQL);
        assertThat(positions(tokens)).isEqualTo("SELECT@2:1^ A@2:8 FROM@4:1^ T@4:6");
    }

    /** 단위마다 "종류@시작줄:첫 두 단어" */
    private static String summary(List<ScriptUnit> units) {
        return units.stream()
                .map(u -> u.kind().name().charAt(0) + "@" + u.startLine() + ":"
                        + u.tokens().stream().limit(2).map(Token::upper).collect(Collectors.joining(" ")))
                .collect(Collectors.joining(" | "));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lineEndings")
    @DisplayName("F-006-AC32 스크립트 분할: 줄 단독 /, -- 주석, 빈 줄, PROMPT 줄, 줄 명령, 줄 끝 @ 가 어느 줄 끝에서나 같게 나뉜다")
    void splitterHonoursEveryLineEnding(String label, String eol) {
        String lf = "PROMPT it's a ; start\n"
                + "CREATE OR REPLACE PROCEDURE SP_A IS\nBEGIN\n  UPDATE T1 SET A = 1; -- 끝 ;\n  DELETE FROM T2;\nEND;\n/\n"
                + "SELECT A / B FROM T3\n/\n"
                + "SET DEFINE OFF\n"
                + "INSERT INTO T4 (A) VALUES (1)\n\nUPDATE T5 SET A = 2;\n"
                + "-- 주석 ; 주석\nDELETE FROM T6;\n";
        String expected = "R@2:CREATE OR | S@8:SELECT A | S@10:SET DEFINE | S@11:INSERT INTO | S@13:UPDATE T5 | S@15:DELETE FROM";
        assertThat(summary(ScriptSplitter.split(lf))).isEqualTo(expected);

        assertThat(summary(ScriptSplitter.split(convert(lf, eol)))).isEqualTo(expected);
    }

    @Test
    @DisplayName("F-006-AC32 스크립트 분할: CR 단독 파일에서 DB2의 줄 끝 @ 종결자와 줄바꿈으로만 나눈 스크립트(F-006-R22)의 모양 판정")
    void db2TerminatorAndLineSeparatedShapeWithLoneCr() {
        String db2 = "CREATE PROCEDURE SP_B ()\rBEGIN\r  UPDATE T1 SET A = 1;\rEND@\rSELECT 1 FROM SYSIBM.SYSDUMMY1@\r";
        assertThat(summary(ScriptSplitter.split(db2))).isEqualTo("R@1:CREATE PROCEDURE | S@5:SELECT 1");

        String lines = "DROP TABLE IF EXISTS S1\rCREATE TABLE S1 (A INT)\rINSERT INTO S1 VALUES (1)\rINSERT INTO S1 VALUES (2)\r";
        ScriptSplitter.Shape shape = ScriptSplitter.shapeOf(lines);
        assertThat(shape.lineSeparated()).isTrue();
        assertThat(shape.lineStartStatements()).isEqualTo(4);
        assertThat(summary(ScriptSplitter.split(lines))).isEqualTo("S@1:DROP TABLE | S@2:CREATE TABLE | S@3:INSERT INTO | S@4:INSERT INTO");
        // 줄 단독 / 는 종결자로 센다
        assertThat(ScriptSplitter.shapeOf("SELECT 1 FROM A\r/\rSELECT 2 FROM B\r/\r").terminators()).isEqualTo(2);
    }

    private static final String SCRIPT = """
            -- 조회 스크립트
            SELECT A.ID
              FROM TB_A A
             WHERE A.ST = '1';

            UPDATE TB_A SET NM = 'x'
             WHERE ID = 1
            /
            DELETE FROM TB_B WHERE ID = 2;
            """;

    private static final String ROUTINE = """
            CREATE OR REPLACE PROCEDURE SP_LINES (P_ID IN NUMBER) IS
                V_CNT NUMBER;
            BEGIN
                -- 건수
                SELECT COUNT(*)
                  INTO V_CNT
                  FROM TB_A
                 WHERE ID = P_ID;

                UPDATE TB_B
                   SET NM = 'y'
                 WHERE ID = P_ID;
            END;
            /
            """;

    private static List<String> statementIds(GraphModel g) {
        return g.nodes(NodeKind.SQL_STATEMENT).stream().map(Node::id).sorted().collect(Collectors.toList());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lineEndings")
    @DisplayName("F-006-AC32 구문 ID의 줄: SQL 파일의 sql:stmt:<파일>:<줄>과 루틴 구문 ID, 구문 노드의 위치가 줄 끝과 상관없이 같다")
    void statementIdsDoNotDependOnLineEnding(String label, String eol) {
        try (TestProject project = TestProject.create().file("db/query.sql", convert(SCRIPT, eol)).file("db/oracle/sp_lines.prc", convert(ROUTINE, eol))) {
            GraphModel g = project.run(new SqlAnalyzer()).graph();

            assertThat(statementIds(g)).containsExactlyInAnyOrder(
                    Ids.sqlStatement("db/query.sql", 2), Ids.sqlStatement("db/query.sql", 6), Ids.sqlStatement("db/query.sql", 9),
                    Ids.routineStatement("SP_LINES", 5), Ids.routineStatement("SP_LINES", 10));
            Node select = GraphAssert.node(g, Ids.sqlStatement("db/query.sql", 2));
            assertThat(select.location().startLine()).isEqualTo(2);
            assertThat(select.location().endLine()).isEqualTo(4);
            assertThat(select.attrString("sql")).isEqualTo("SELECT A.ID FROM TB_A A WHERE A.ST = '1'");
            Node inRoutine = GraphAssert.node(g, Ids.routineStatement("SP_LINES", 5));
            assertThat(inRoutine.location().startLine()).isEqualTo(5);
            assertThat(inRoutine.location().endLine()).isEqualTo(8);
            assertThat(inRoutine.attrString("sql")).isEqualTo("SELECT COUNT(*) INTO V_CNT FROM TB_A WHERE ID = P_ID");
            assertThat(GraphAssert.hasEdge(g, Ids.routineStatement("SP_LINES", 10), EdgeKind.WRITES, "db:table:TB_B")).isTrue();
            assertThat(GraphAssert.node(g, "db:routine:SP_LINES").location().startLine()).isEqualTo(1);
            assertThat(g.diagnostics()).as("진단 없음").isEmpty();
        }
    }
}
