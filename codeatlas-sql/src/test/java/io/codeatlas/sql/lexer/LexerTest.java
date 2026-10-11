package io.codeatlas.sql.lexer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class LexerTest {

    private static List<Token> lex(String sql) {
        List<Token> tokens = Lexer.tokenize(sql);
        assertThat(tokens.get(tokens.size() - 1).type()).isEqualTo(TokenType.EOF);
        return tokens.subList(0, tokens.size() - 1);
    }

    private static String render(String sql) {
        StringBuilder sb = new StringBuilder();
        for (Token t : lex(sql)) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(switch (t.type()) {
                case IDENT -> "I:" + t.upper();
                case QUOTED_IDENT -> "Q:" + t.text();
                case STRING -> "S:" + t.text();
                case NUMBER -> "N:" + t.text();
                case BIND -> "B:" + t.text();
                case SYMBOL -> t.text();
                default -> t.type().name();
            });
        }
        return sb.toString();
    }

    @Test
    @DisplayName("F-006-AC1 주석·힌트는 빠지고 문자열, 따옴표 식별자, 바인드, ${} 잔재가 위치와 함께 나온다")
    void ac1() {
        String sql = "SELECT /*+ INDEX(t) */ 'it''s', q'[a'b]', \"My Col\", :id, #{name}, ${col}\n  FROM t -- 주석";
        List<Token> tokens = lex(sql);
        assertThat(tokens).filteredOn(t -> t.type() == TokenType.STRING).extracting(Token::text)
                .containsExactly("'it''s'", "q'[a'b]'");
        assertThat(tokens).filteredOn(t -> t.type() == TokenType.QUOTED_IDENT).extracting(Token::text)
                .containsExactly("\"My Col\"");
        assertThat(tokens).filteredOn(t -> t.type() == TokenType.BIND).extracting(Token::text)
                .containsExactly(":id", "#{name}");
        assertThat(tokens).filteredOn(Token::isDynamic).hasSize(1);
        assertThat(tokens).noneMatch(t -> t.type() == TokenType.COMMENT);
        Token from = tokens.stream().filter(t -> t.is("FROM")).findFirst().orElseThrow();
        assertThat(from.line()).isEqualTo(2);
        assertThat(from.column()).isEqualTo(3);
        Token select = tokens.get(0);
        assertThat(select.line()).isEqualTo(1);
        assertThat(select.column()).isEqualTo(1);
        assertThat(select.endColumn()).isEqualTo(7);
    }

    static Stream<Arguments> tokenCases() {
        return Stream.of(
                Arguments.of("a.b", "I:A . I:B"),
                Arguments.of("select 1.5, .5, 1e3, 10", "I:SELECT N:1.5 , N:.5 , N:1e3 , N:10"),
                Arguments.of("for i in 1..10", "I:FOR I:I I:IN N:1 .. N:10"),
                Arguments.of("a||b", "I:A || I:B"),
                Arguments.of("a.x = b.y(+)", "I:A . I:X = I:B . I:Y (+)"),
                Arguments.of("a.x = b.y ( + )", "I:A . I:X = I:B . I:Y (+)"),
                Arguments.of("p(x => 1)", "I:P ( I:X => N:1 )"),
                Arguments.of("v := 1", "I:V := N:1"),
                Arguments.of("a != b and a <> b and a ^= b and a <= b and a >= b", "I:A != I:B I:AND I:A <> I:B I:AND I:A ^= I:B I:AND I:A <= I:B I:AND I:A >= I:B"),
                Arguments.of("emp@link", "I:EMP @ I:LINK"),
                Arguments.of("N'한글' x'0A'", "S:N'한글' S:x'0A'"),
                Arguments.of("q'{it's}' Q'!a!' q'<x>' q'(y)'", "S:q'{it's}' S:Q'!a!' S:q'<x>' S:q'(y)'"),
                Arguments.of("where id = ? and nm = :1 and x = :name", "I:WHERE I:ID = B:? I:AND I:NM = B::1 I:AND I:X = B::name"),
                Arguments.of("#{item.id,jdbcType=NUMERIC}", "B:#{item.id,jdbcType=NUMERIC}"),
                Arguments.of("TB_${suffix} t", "I:TB___DYNAMIC__ I:T"),
                Arguments.of("order by ${col}", "I:ORDER I:BY I:__DYNAMIC__"),
                Arguments.of("select 한글컬럼, col$1, col#2 from t", "I:SELECT I:한글컬럼 , I:COL$1 , I:COL#2 I:FROM I:T"),
                Arguments.of("a -- c\n/* x\n y */ b", "I:A I:B"),
                Arguments.of("{call p(?)}", "{ I:CALL I:P ( B:? ) }"),
                Arguments.of("\"a\"\"b\"", "Q:\"a\"\"b\""),
                Arguments.of("x%type", "I:X % I:TYPE"),
                Arguments.of(":new.id", "B::new . I:ID"),
                Arguments.of("a/*unterminated", "I:A"),
                Arguments.of("<<lbl>>", "<< I:LBL >>"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tokenCases")
    @DisplayName("F-006-AC1 토큰 종류 (표)")
    void tokens(String sql, String expected) {
        assertThat(render(sql)).isEqualTo(expected);
    }

    @Test
    @DisplayName("F-006-AC1 닫히지 않은 문자열은 그 줄 끝에서 끊고 표시한다")
    void unterminatedString() {
        List<Token> tokens = lex("SET nm = '닫히지 않음\n WHERE id = 1");
        Token s = tokens.stream().filter(t -> t.type() == TokenType.STRING).findFirst().orElseThrow();
        assertThat(s.unterminated()).isTrue();
        assertThat(tokens).anyMatch(t -> t.is("WHERE"));
    }

    @Test
    @DisplayName("F-006-AC1 주석 포함 모드에서는 주석 토큰과 빈 줄 표시가 나온다")
    void commentsAndBlankLines() {
        List<Token> tokens = Lexer.tokenizeWithComments("--#SET TERMINATOR @\nselect 1\n\nselect 2");
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.COMMENT);
        assertThat(tokens.get(0).text()).isEqualTo("--#SET TERMINATOR @");
        List<Token> selects = tokens.stream().filter(t -> t.is("SELECT")).toList();
        assertThat(selects.get(0).blankLineBefore()).isFalse();
        assertThat(selects.get(1).blankLineBefore()).isTrue();
        assertThat(selects.get(1).firstOnLine()).isTrue();
        assertThat(tokens.stream().filter(t -> t.type() == TokenType.NUMBER).findFirst().orElseThrow().firstOnLine()).isFalse();
    }

    @Test
    @DisplayName("F-006-AC1 여러 줄 토큰의 끝 위치")
    void multiLineEnd() {
        List<Token> tokens = lex("'a\nbc' x");
        assertThat(tokens.get(0).endLine()).isEqualTo(2);
        assertThat(tokens.get(0).endColumn()).isEqualTo(4);
        assertThat(tokens.get(1).line()).isEqualTo(2);
        assertThat(tokens.get(1).column()).isEqualTo(5);
    }

    @Test
    @DisplayName("F-008-AC1 DB2 레이블이 붙어 있어도(L1:LOOP) 바인드가 아니라 레이블 + 키워드로 나온다")
    void db2LabelAttachedToKeyword() {
        assertThat(render("L1:LOOP")).isEqualTo("I:L1 : I:LOOP");
        assertThat(render("fetch_loop:while v > 0 do")).isEqualTo("I:FETCH_LOOP : I:WHILE I:V > N:0 I:DO");
        assertThat(render("L2:BEGIN L3:FOR L4:REPEAT")).isEqualTo("I:L2 : I:BEGIN I:L3 : I:FOR I:L4 : I:REPEAT");
        // 바인드는 그대로다: 앞이 이름이 아니거나, 떨어져 있거나, 블록 키워드가 아니다
        assertThat(render("x = :loop")).isEqualTo("I:X = B::loop");
        assertThat(render("a :loop")).isEqualTo("I:A B::loop");
        assertThat(render("a:id")).isEqualTo("I:A B::id");
        assertThat(render("L1:LOOPS")).isEqualTo("I:L1 B::LOOPS");
    }

    @Test
    @DisplayName("F-006-AC18 MySQL 백틱 식별자는 따옴표 식별자로 읽고 이름은 대문자로 정규화한다(위치는 백틱 포함)")
    void backtickIdentifier() {
        assertThat(render("select `id`, `Order` from `sys_user` u")).isEqualTo("I:SELECT Q:\"ID\" , Q:\"ORDER\" I:FROM Q:\"SYS_USER\" I:U");
        List<Token> tokens = lex("x `my col` y");
        assertThat(tokens.get(1).type()).isEqualTo(TokenType.QUOTED_IDENT);
        assertThat(tokens.get(1).column()).isEqualTo(3);
        assertThat(tokens.get(1).endColumn()).isEqualTo(11);
        assertThat(tokens.get(1).unterminated()).isFalse();
        assertThat(tokens.get(2).column()).isEqualTo(12);
        // 닫히지 않은 백틱은 그 줄 끝에서 끊는다
        List<Token> open = lex("a `b c\nd");
        assertThat(open.get(1).unterminated()).isTrue();
        assertThat(open.get(2).is("D")).isTrue();
        assertThat(open.get(2).line()).isEqualTo(2);
    }

    @Test
    @DisplayName("F-006-AC18 PostgreSQL 캐스트 ::는 기호 하나이고 뒤의 타입 이름을 바인드로 읽지 않는다")
    void doubleColonCast() {
        assertThat(render("a::int")).isEqualTo("I:A :: I:INT");
        assertThat(render("?::date, :id::text, #{v}::numeric")).isEqualTo("B:? :: I:DATE , B::id :: I:TEXT , B:#{v} :: I:NUMERIC");
        // 바인드와 대입은 그대로다
        assertThat(render("x := :v")).isEqualTo("I:X := B::v");
    }

    @Test
    @DisplayName("F-006-AC18 F-006-AC27 MySQL로 판정한 SQL의 줄 처음 # 주석과 16진 리터럴 0x..: 주석은 빠지고 리터럴은 숫자 하나다. #{..} 바인드와 이름 안의 #은 그대로다")
    void hashCommentAndHexLiteral() {
        String sql = "# 주석 'x\nSELECT 1 FROM t\n  # 들여쓴 주석\nWHERE a = #{v} AND col#1 = 2";
        assertThat(Lexer.tokenize(sql, true)).filteredOn(t -> !t.isEof()).extracting(Token::text)
                .containsExactly("SELECT", "1", "FROM", "t", "WHERE", "a", "=", "#{v}", "AND", "col#1", "=", "2");
        assertThat(Lexer.stream("# c\nx", true, true).next().text()).isEqualTo("# c");
        assertThat(render("VALUES (0x89504E470D0A, 0X1f, 0, 0xZ)")).isEqualTo("I:VALUES ( N:0x89504E470D0A , N:0X1f , N:0 , N:0 I:XZ )");
    }

    @Test
    @DisplayName("F-006-AC27 MySQL이라는 근거가 없으면 줄 처음의 #은 주석이 아니다: #으로 시작하는 이름(DB2·MSSQL의 #TMP, ##G)은 식별자 하나다")
    void hashIsNotCommentByDefault() {
        assertThat(render("SELECT A FROM\n#TMP T\nWHERE T.#KEY = 1 AND B = #{v}")).isEqualTo(
                "I:SELECT I:A I:FROM I:#TMP I:T I:WHERE I:T . I:#KEY = N:1 I:AND I:B = B:#{v}");
        assertThat(render("FROM ##G_TMP")).isEqualTo("I:FROM I:##G_TMP");
        // 이름이 따르지 않는 #은 전처럼 기호다
        assertThat(render("a # b")).isEqualTo("I:A # I:B");
        assertThat(Lexer.tokenizeWithComments("# c\nx")).noneMatch(t -> t.type() == TokenType.COMMENT);
    }

    @Test
    @DisplayName("F-006-AC15 흘려 읽는 렉서는 한꺼번에 렉싱한 것과 같은 토큰을 내고 끝에서는 EOF를 계속 돌려준다")
    void streamEqualsTokenize() {
        String sql = "SELECT a, 'b''c' /* x */ FROM t -- y\nWHERE L1:LOOP (+) q'[z]' `k` #{p} ${d}x :b";
        for (boolean comments : new boolean[] {false, true}) {
            List<Token> all = comments ? Lexer.tokenizeWithComments(sql) : Lexer.tokenize(sql);
            Lexer.Stream stream = Lexer.stream(sql, comments);
            for (Token expected : all) {
                Token t = stream.next();
                assertThat(t.type()).isEqualTo(expected.type());
                assertThat(t.text()).isEqualTo(expected.text());
                assertThat(t.offset()).isEqualTo(expected.offset());
            }
            assertThat(stream.next().isEof()).isTrue();
            assertThat(stream.next().isEof()).isTrue();
        }
    }
}
