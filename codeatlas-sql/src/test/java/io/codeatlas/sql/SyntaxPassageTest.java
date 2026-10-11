package io.codeatlas.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.sql.ast.Routine;
import io.codeatlas.sql.ast.Statement;
import io.codeatlas.sql.lexer.Lexer;
import io.codeatlas.sql.lexer.Syntax;
import io.codeatlas.sql.lexer.Token;
import io.codeatlas.sql.lexer.TokenType;
import io.codeatlas.sql.parser.DdlParser;
import io.codeatlas.sql.parser.RoutineLanguage;
import io.codeatlas.sql.parser.RoutineParser;
import io.codeatlas.sql.parser.SqlParser;
import io.codeatlas.sql.script.ScriptSplitter;
import io.codeatlas.sql.script.ScriptUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 읽기 방식({@link Syntax})과 루틴 언어({@link RoutineLanguage})를 렉서·분할기·파서에 넘기는 통로(F-019-R6, ADR-0012).
 * 기본·MySQL 읽기 방식의 결과는 이 통로가 생기기 전과 같아야 한다(F-019-R8).
 */
class SyntaxPassageTest {

    private static String render(List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        for (Token t : tokens) {
            if (t.isEof()) {
                continue;
            }
            sb.append(sb.length() > 0 ? " " : "").append(t.type() == TokenType.IDENT ? "I:" + t.upper() : t.text());
        }
        return sb.toString();
    }

    private static List<Token> body(String text) {
        List<Token> tokens = Lexer.tokenize(text);
        return tokens.subList(0, tokens.size() - 1);
    }

    @Test
    @DisplayName("F-019-AC6 기본 읽기 방식에서는 #과 $가 이름의 글자다: A#B, A$$B, C$$, T#1은 식별자 하나이고 인자 없는 꼴과 결과가 같다")
    void hashAndDollarAreNameCharactersInDefaultSyntax() {
        String sql = "SELECT A#B, A$$B, C$$ FROM T#1";
        assertThat(render(Lexer.tokenize(sql, Syntax.DEFAULT))).isEqualTo("I:SELECT I:A#B , I:A$$B , I:C$$ I:FROM I:T#1");
        assertThat(render(Lexer.tokenize(sql, Syntax.DEFAULT))).isEqualTo(render(Lexer.tokenize(sql)));
        assertThat(render(Lexer.tokenizeAt(sql, 0, 1, 1, Syntax.DEFAULT))).isEqualTo(render(Lexer.tokenize(sql)));
        assertThat(Lexer.stream(sql, false, Syntax.DEFAULT).next().text()).isEqualTo("SELECT");
        // 이름은 $로 시작하지 못한다(지금까지의 동작 그대로): 맨 앞의 $$는 달러 인용이 아니라 기호 둘이다
        assertThat(render(Lexer.tokenize("SELECT $$C FROM T", Syntax.DEFAULT))).isEqualTo("I:SELECT $ $ I:C I:FROM I:T");
    }

    @Test
    @DisplayName("F-019-AC6 기본 읽기 방식의 DB2 --#SET TERMINATOR @ 스크립트는 전과 같이 나뉘고, 줄 처음의 #은 주석이 아니다")
    void db2TerminatorScriptSplitsAsBefore() {
        String script = "--#SET TERMINATOR @\nCREATE TABLE T1 (A INT)@\nINSERT INTO T1\nSELECT A FROM\n#TMP S@\n";
        List<ScriptUnit> units = new ArrayList<>();
        ScriptSplitter.Shape shape = ScriptSplitter.forEach(script, units::add, null, Syntax.DEFAULT);
        assertThat(units).extracting(ScriptUnit::text).containsExactlyElementsOf(
                ScriptSplitter.split(script).stream().map(ScriptUnit::text).toList());
        assertThat(units).hasSize(2);
        assertThat(units.get(1).tokens()).extracting(Token::text).contains("#TMP");
        assertThat(shape).isEqualTo(ScriptSplitter.shapeOf(script, Syntax.DEFAULT)).isEqualTo(ScriptSplitter.shapeOf(script));
        assertThat(shape.terminatorDirective()).isTrue();
    }

    @Test
    @DisplayName("F-019-AC6 MySQL 읽기 방식에서만 줄 처음의 #이 주석이다: 분할기와 렉서가 같은 값을 받는다")
    void mysqlSyntaxReadsLeadingHashAsComment() {
        String script = "# 머리말; 주석\nCREATE TABLE T1 (A INT);\n# 끝\n";
        List<ScriptUnit> mysql = new ArrayList<>();
        ScriptSplitter.forEach(script, mysql::add, null, Syntax.MYSQL);
        assertThat(mysql).hasSize(1);
        assertThat(mysql.get(0).first().text()).isEqualTo("CREATE");
        assertThat(ScriptSplitter.shapeOf(script, Syntax.MYSQL).terminators()).isEqualTo(1);
        assertThat(ScriptSplitter.shapeOf(script, Syntax.DEFAULT).terminators()).isEqualTo(2);

        String embedded = "# 주석\nSELECT A FROM T WHERE B = #{b}";
        assertThat(ScriptSplitter.splitEmbedded(embedded, Syntax.MYSQL).get(0).first().text()).isEqualTo("SELECT");
        assertThat(ScriptSplitter.splitEmbedded(embedded, Syntax.DEFAULT).get(0).first().text()).isEqualTo("#");
        assertThat(render(Lexer.tokenize("# c\nSELECT 'it\\'s'", Syntax.MYSQL))).isEqualTo("I:SELECT 'it\\'s'");
    }

    @Test
    @DisplayName("F-019-AC6 DML·DDL 파서는 읽기 방식을 받고, 기본 읽기 방식의 결과는 토큰만 받는 생성자와 같다")
    void parsersTakeSyntax() {
        String dml = "SELECT A#B FROM T#1 WHERE C = :v";
        Statement select = new SqlParser(Lexer.tokenize(dml), Syntax.DEFAULT).parseStatement().statement();
        assertThat(select).isInstanceOf(Statement.Select.class);
        assertThat(select).hasToString(new SqlParser(Lexer.tokenize(dml)).parseStatement().statement().toString());
        assertThat(SqlParser.parse(dml, Syntax.DEFAULT).statement()).hasToString(SqlParser.parse(dml).statement().toString());
        String ddl = "CREATE TABLE T#1 (A#B NUMBER(10) NOT NULL, CONSTRAINT PK_T PRIMARY KEY (A#B))";
        assertThat(new DdlParser(Lexer.tokenize(ddl), Syntax.DEFAULT).parse().statement())
                .hasToString(new DdlParser(Lexer.tokenize(ddl)).parse().statement().toString());
        // 익명 블록: 구문 파서가 안에서 본문 파서를 만들 때도 읽기 방식이 간다(기본이면 전과 같다)
        String block = "BEGIN UPDATE T SET A = 1; P1(2); END;";
        Statement viaSyntax = SqlParser.parse(block, Syntax.DEFAULT).statement();
        assertThat(viaSyntax).isInstanceOf(Statement.Block.class).hasToString(SqlParser.parse(block).statement().toString());
        assertThat(((Statement.Block) viaSyntax).statements()).hasSize(2);
    }

    @Test
    @DisplayName("F-019-AC6 본문 파서는 루틴 언어를 받는다: SQLPL이면 블록 안의 DECLARE가 선언문이고 PLSQL이면 선언부다")
    void routineParserTakesLanguage() {
        String body = "BEGIN DECLARE V INT; SET V = 1; END";
        RoutineParser sqlPl = new RoutineParser(body(body), RoutineLanguage.SQLPL);
        Routine.Block block = sqlPl.parseBody(false);
        assertThat(sqlPl.problems()).isEmpty();
        assertThat(block.declarations()).hasSize(1);
        assertThat(((Routine.Variable) block.declarations().get(0)).names()).containsExactly("V");
        String plsql = "DECLARE V NUMBER; BEGIN V := 1; END;";
        RoutineParser p = new RoutineParser(body(plsql), RoutineLanguage.PLSQL);
        Routine.Block plsqlBlock = p.parseBody(false);
        assertThat(p.problems()).isEmpty();
        assertThat(((Routine.Variable) plsqlBlock.declarations().get(0)).names()).containsExactly("V");
    }

    @Test
    @DisplayName("F-019-AC6 루틴 언어는 한곳에서 정한다: PostgreSQL 읽기 방식이면 PLPGSQL, 아니면 본문 특징으로 가른 PLSQL·SQLPL이다")
    void routineLanguageFollowsSyntax() {
        assertThat(RoutineLanguage.of(Syntax.DEFAULT, false)).isEqualTo(RoutineLanguage.PLSQL);
        assertThat(RoutineLanguage.of(Syntax.DEFAULT, true)).isEqualTo(RoutineLanguage.SQLPL);
        assertThat(RoutineLanguage.of(Syntax.MYSQL, true)).isEqualTo(RoutineLanguage.SQLPL);
        assertThat(RoutineLanguage.of(Syntax.POSTGRES, false)).isEqualTo(RoutineLanguage.PLPGSQL);
        assertThat(RoutineLanguage.of(Syntax.POSTGRES, true)).isEqualTo(RoutineLanguage.PLPGSQL);
        assertThat(RoutineLanguage.PLPGSQL.syntax()).isEqualTo(Syntax.POSTGRES);
        assertThat(RoutineLanguage.PLSQL.syntax()).isEqualTo(Syntax.DEFAULT);
        assertThat(RoutineLanguage.SQLPL.syntax()).isEqualTo(Syntax.DEFAULT);
    }
}
