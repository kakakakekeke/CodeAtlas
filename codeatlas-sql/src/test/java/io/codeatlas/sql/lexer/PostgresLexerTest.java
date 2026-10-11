package io.codeatlas.sql.lexer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PostgreSQL 읽기 방식의 렉서(F-019-R9, F-019-R10): 달러 인용, {@code E'..'}, {@code $1}, 연산자.
 * 같은 글자를 기본 읽기 방식에서는 전처럼 읽는다(F-019-R8).
 */
class PostgresLexerTest {

    private static List<Token> lex(String sql, Syntax syntax) {
        List<Token> tokens = Lexer.tokenize(sql, syntax);
        assertThat(tokens.get(tokens.size() - 1).type()).isEqualTo(TokenType.EOF);
        return tokens.subList(0, tokens.size() - 1);
    }

    private static String render(String sql, Syntax syntax) {
        StringBuilder sb = new StringBuilder();
        for (Token t : lex(sql, syntax)) {
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

    private static String pg(String sql) {
        return render(sql, Syntax.POSTGRES);
    }

    // ------------------------------------------------------------------ F-019-AC17: 따옴표로 감싼 소문자 이름

    @Test
    @DisplayName("F-019-AC17 PostgreSQL 읽기 방식에서 대문자가 없는 따옴표 이름은 본문을 대문자로 바꾼 따옴표 식별자다(따옴표 없는 이름과 같은 이름이 된다). 대문자가 든 이름은 원문 그대로다")
    void quotedNamesWithoutUppercaseFold() {
        assertThat(pg("\"orders\"")).isEqualTo("Q:\"ORDERS\"");
        assertThat(pg("public.\"order_items\".\"id\"")).isEqualTo("I:PUBLIC . Q:\"ORDER_ITEMS\" . Q:\"ID\"");
        assertThat(pg("\"user\" \"my col\" \"2fa\" \"a\"\"b\"")).isEqualTo("Q:\"USER\" Q:\"MY COL\" Q:\"2FA\" Q:\"A\"\"B\"");
        // 대문자가 하나라도 있으면 PostgreSQL에서도 따옴표 없는 이름과 다른 객체다
        assertThat(pg("\"camelCase\" \"ORDERS\" \"Orders\" \"order_ID\"")).isEqualTo("Q:\"camelCase\" Q:\"ORDERS\" Q:\"Orders\" Q:\"order_ID\"");
        // 닫히지 않은 따옴표 이름은 전처럼 그 줄 끝에서 끊고 원문 그대로 둔다
        List<Token> open = lex("SELECT \"abc\nFROM t", Syntax.POSTGRES);
        assertThat(open.get(1).text()).isEqualTo("\"abc");
        assertThat(open.get(1).unterminated()).isTrue();
    }

    @Test
    @DisplayName("F-019-AC17 대문자로 바꾼 따옴표 이름의 위치는 원문 범위 그대로다. 달러 인용 본문을 다시 토큰으로 만들 때도 같은 규칙이다")
    void foldedQuotedNamesKeepPositions() {
        List<Token> tokens = lex("SELECT \"id\"\n  FROM \"orders\" o", Syntax.POSTGRES);
        Token id = tokens.get(1);
        assertThat(id.text()).isEqualTo("\"ID\"");
        assertThat(id.offset()).isEqualTo(7);
        assertThat(id.endOffset()).isEqualTo(11);
        assertThat(id.line()).isEqualTo(1);
        assertThat(id.column()).isEqualTo(8);
        Token orders = tokens.get(3);
        assertThat(orders.text()).isEqualTo("\"ORDERS\"");
        assertThat(orders.line()).isEqualTo(2);
        assertThat(orders.column()).isEqualTo(8);
        assertThat(orders.endOffset() - orders.offset()).isEqualTo(8);
        assertThat(tokens.get(4).upper()).isEqualTo("O");

        Token body = lex("DO $$ BEGIN UPDATE \"orders\" SET \"status\" = 'x' WHERE \"Id\" = 1; END $$", Syntax.POSTGRES).get(1);
        assertThat(body.dollarQuoted()).isTrue();
        assertThat(Lexer.tokenizeBody(body, Syntax.POSTGRES)).extracting(Token::text).contains("\"ORDERS\"", "\"STATUS\"", "\"Id\"");
    }

    @Test
    @DisplayName("F-019-AC6 F-019-AC17 기본·MySQL 읽기 방식의 따옴표 이름은 전처럼 원문 그대로다(F-019-R8)")
    void quotedNamesInOtherSyntaxesAreUnchanged() {
        for (Syntax syntax : new Syntax[] {Syntax.DEFAULT, Syntax.MYSQL}) {
            assertThat(render("\"orders\" \"Orders\" \"ORDERS\"", syntax)).as(syntax.name()).isEqualTo("Q:\"orders\" Q:\"Orders\" Q:\"ORDERS\"");
        }
        assertThat(render("`orders`", Syntax.MYSQL)).isEqualTo("Q:\"ORDERS\"");
    }

    // ------------------------------------------------------------------ F-019-AC9: 연산자, E'', $1, #{..}

    @Test
    @DisplayName("F-019-AC9 PostgreSQL 읽기 방식에서 ->>·#>>·@>·~* 는 각각 기호 토큰 하나다")
    void operatorsAreSingleSymbols() {
        assertThat(pg("a->>'k'")).isEqualTo("I:A ->> S:'k'");
        assertThat(pg("a #>> '{x,y}'")).isEqualTo("I:A #>> S:'{x,y}'");
        assertThat(pg("tags @> ARRAY['a']")).isEqualTo("I:TAGS @> I:ARRAY [ S:'a' ]");
        assertThat(pg("memo ~* 'vip'")).isEqualTo("I:MEMO ~* S:'vip'");
    }

    @Test
    @DisplayName("F-019-AC9 F-019-R10의 나머지 연산자(->, #>, <@, &&, ~, !~, !~*, @@)도 기호 하나이고, 기존 기호(<>, >=, ::, ||, !=)는 그대로다")
    void otherOperators() {
        assertThat(pg("a->'k'->0")).isEqualTo("I:A -> S:'k' -> N:0");
        assertThat(pg("a #> '{x}'")).isEqualTo("I:A #> S:'{x}'");
        assertThat(pg("a <@ b && c")).isEqualTo("I:A <@ I:B && I:C");
        assertThat(pg("a ~ 'x' AND b !~ 'y' AND c !~* 'z'")).isEqualTo("I:A ~ S:'x' I:AND I:B !~ S:'y' I:AND I:C !~* S:'z'");
        assertThat(pg("doc @@ q")).isEqualTo("I:DOC @@ I:Q");
        assertThat(pg("a <> b AND c >= d AND e::text || f != g")).isEqualTo("I:A <> I:B I:AND I:C >= I:D I:AND I:E :: I:TEXT || I:F != I:G");
        // 공백 없이 붙여 써도 같다. 뺄셈과 비교는 그대로다
        assertThat(pg("p#>>'{a}'")).isEqualTo("I:P #>> S:'{a}'");
        assertThat(pg("a - b > c")).isEqualTo("I:A - I:B > I:C");
    }

    @Test
    @DisplayName("F-019-AC9 E'it\\'s'는 문자열 하나다(백슬래시 이스케이프). 접두사 없는 문자열의 백슬래시는 글자 그대로다")
    void escapeString() {
        List<Token> tokens = lex("SELECT E'it\\'s', 'a\\', e'x\\\\' FROM t", Syntax.POSTGRES);
        assertThat(tokens).filteredOn(t -> t.type() == TokenType.STRING).extracting(Token::text)
                .containsExactly("E'it\\'s'", "'a\\'", "e'x\\\\'");
        assertThat(tokens).noneMatch(Token::unterminated);
        assertThat(tokens.get(tokens.size() - 1).upper()).isEqualTo("T");
    }

    @Test
    @DisplayName("F-019-AC9 $1은 바인드이고 MyBatis #{id}·${col}은 그대로 자리표시자다(#>> 뒤에 #{..}가 와도 같다)")
    void bindsAndPlaceholders() {
        assertThat(pg("WHERE id = $1 AND n > $12")).isEqualTo("I:WHERE I:ID = B:$1 I:AND I:N > B:$12");
        assertThat(pg("WHERE id = #{id}")).isEqualTo("I:WHERE I:ID = B:#{id}");
        assertThat(pg("SELECT c.profile #>> #{path} FROM customers c WHERE c.id = #{id}"))
                .isEqualTo("I:SELECT I:C . I:PROFILE #>> B:#{path} I:FROM I:CUSTOMERS I:C I:WHERE I:C . I:ID = B:#{id}");
        List<Token> dynamic = lex("SELECT ${col}, t_${sfx}.a FROM t", Syntax.POSTGRES);
        assertThat(dynamic).filteredOn(Token::isDynamic).hasSize(2);
    }

    // ------------------------------------------------------------------ F-019-AC8: 달러 인용

    @Test
    @DisplayName("F-019-AC8 달러 인용($$..$$, $태그$..$태그$)은 문자열 토큰 하나다: 안의 ;, 따옴표, 다른 태그는 본문의 글자다")
    void dollarQuotedStrings() {
        String sql = "CREATE FUNCTION f() RETURNS int AS $body$ BEGIN DELETE FROM t; RETURN 'a;b'; END; $body$ LANGUAGE plpgsql; SELECT 1;";
        List<Token> tokens = lex(sql, Syntax.POSTGRES);
        List<Token> strings = tokens.stream().filter(t -> t.type() == TokenType.STRING).toList();
        assertThat(strings).hasSize(1);
        assertThat(strings.get(0).text()).isEqualTo("$body$ BEGIN DELETE FROM t; RETURN 'a;b'; END; $body$");
        assertThat(strings.get(0).unterminated()).isFalse();
        assertThat(tokens.stream().filter(t -> t.isSymbol(";")).count()).isEqualTo(2);

        assertThat(pg("DO $$ BEGIN NULL; END $$")).isEqualTo("I:DO S:$$ BEGIN NULL; END $$");
        // 안쪽의 다른 태그와 $$는 본문이다
        assertThat(pg("SELECT $a$ x $$ y $b$ z $a$, 1")).isEqualTo("I:SELECT S:$a$ x $$ y $b$ z $a$ , N:1");
        assertThat(pg("SELECT $$$$")).isEqualTo("I:SELECT S:$$$$");
    }

    @Test
    @DisplayName("F-019-AC8 달러 인용 토큰에서 안쪽 본문과 그 시작 위치(offset·줄·열)를 얻는다: 본문을 원문 위치에 맞춰 다시 토큰으로 만들 수 있다")
    void dollarQuoteBodyPosition() {
        String sql = "CREATE FUNCTION f() RETURNS int\nAS $fn$\nBEGIN\n  DELETE FROM t;\nEND;\n$fn$ LANGUAGE plpgsql";
        Token quote = lex(sql, Syntax.POSTGRES).stream().filter(Token::dollarQuoted).findFirst().orElseThrow();
        assertThat(quote.dollarTag()).isEqualTo("fn");
        assertThat(quote.dollarBody()).isEqualTo("\nBEGIN\n  DELETE FROM t;\nEND;\n");
        assertThat(quote.line()).isEqualTo(2);
        assertThat(quote.column()).isEqualTo(4);
        assertThat(quote.bodyOffset()).isEqualTo(sql.indexOf("$fn$") + 4);
        assertThat(quote.bodyLine()).isEqualTo(2);
        assertThat(quote.bodyColumn()).isEqualTo(8);

        List<Token> body = Lexer.tokenizeBody(quote, Syntax.POSTGRES);
        Token delete = body.stream().filter(t -> t.is("DELETE")).findFirst().orElseThrow();
        assertThat(delete.offset()).isEqualTo(sql.indexOf("DELETE"));
        assertThat(delete.line()).isEqualTo(4);
        assertThat(delete.column()).isEqualTo(3);
        assertThat(body.get(body.size() - 1).isEof()).isTrue();

        // 한 줄짜리: 본문의 열은 여는 구분자 바로 뒤다
        Token inline = lex("DO $$BEGIN NULL; END$$", Syntax.POSTGRES).get(1);
        assertThat(inline.dollarTag()).isEmpty();
        assertThat(inline.dollarBody()).isEqualTo("BEGIN NULL; END");
        assertThat(inline.bodyColumn()).isEqualTo(6);
        assertThat(Lexer.tokenizeBody(inline, Syntax.POSTGRES).get(0).column()).isEqualTo(6);

        // 달러 인용이 아닌 토큰
        Token plain = lex("SELECT 'x'", Syntax.POSTGRES).get(1);
        assertThat(plain.dollarQuoted()).isFalse();
        assertThat(plain.dollarBody()).isNull();
    }

    @Test
    @DisplayName("F-019-AC8 닫히지 않은 달러 인용은 입력 끝까지가 닫히지 않은 문자열 토큰 하나다(예외 없이 끝난다)")
    void unterminatedDollarQuote() {
        List<Token> tokens = lex("SELECT 1;\nCREATE FUNCTION f() RETURNS int AS $$ BEGIN DELETE FROM t;\nRETURN 1; END;", Syntax.POSTGRES);
        Token last = tokens.get(tokens.size() - 1);
        assertThat(last.type()).isEqualTo(TokenType.STRING);
        assertThat(last.unterminated()).isTrue();
        assertThat(last.dollarQuoted()).isTrue();
        assertThat(last.dollarBody()).isEqualTo(" BEGIN DELETE FROM t;\nRETURN 1; END;");
        assertThat(last.endLine()).isEqualTo(3);
        assertThat(tokens.stream().filter(t -> t.isSymbol(";")).count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ F-019-AC6: 같은 글자, 다른 읽기 방식

    @Test
    @DisplayName("F-019-AC6 같은 텍스트를 PostgreSQL 읽기 방식으로 읽으면 $$가 달러 인용의 시작이고 #은 이름의 글자가 아니다. 기본 읽기 방식은 전과 같다")
    void sameTextDifferentSyntax() {
        String sql = "SELECT A#B, $$C FROM T#1";
        assertThat(render(sql, Syntax.DEFAULT)).isEqualTo("I:SELECT I:A#B , $ $ I:C I:FROM I:T#1");
        List<Token> tokens = lex(sql, Syntax.POSTGRES);
        assertThat(tokens.get(1).upper()).isEqualTo("A");
        assertThat(tokens.get(2).isSymbol("#")).isTrue();
        Token quote = tokens.get(tokens.size() - 1);
        assertThat(quote.dollarQuoted()).isTrue();
        assertThat(quote.unterminated()).isTrue();
        assertThat(quote.text()).isEqualTo("$$C FROM T#1");
    }

    @Test
    @DisplayName("F-019-AC6 기본·MySQL 읽기 방식에서는 달러 인용, E'..', $1, PostgreSQL 연산자를 PostgreSQL 규칙으로 읽지 않는다(전과 같은 토큰이다)")
    void defaultSyntaxIsUnchanged() {
        assertThat(render("DO $$ BEGIN NULL; END $$", Syntax.DEFAULT)).isEqualTo("I:DO $ $ I:BEGIN I:NULL ; I:END $ $");
        assertThat(render("AS $fn$ x $fn$", Syntax.DEFAULT)).isEqualTo("I:AS $ I:FN$ I:X $ I:FN$");
        assertThat(render("a ->> 'k'", Syntax.DEFAULT)).isEqualTo("I:A - >> S:'k'");
        assertThat(render("a #>> '{x}'", Syntax.DEFAULT)).isEqualTo("I:A # >> S:'{x}'");
        assertThat(render("p#>>'{a}'", Syntax.DEFAULT)).isEqualTo("I:P# >> S:'{a}'");
        assertThat(render("t @> u AND m ~* 'v'", Syntax.DEFAULT)).isEqualTo("I:T @ > I:U I:AND I:M ~ * S:'v'");
        assertThat(render("WHERE id = $1", Syntax.DEFAULT)).isEqualTo("I:WHERE I:ID = $ N:1");
        assertThat(render("E'a'", Syntax.DEFAULT)).isEqualTo("I:E S:'a'");
        assertThat(render("x LIKE 'a\\' ESCAPE '\\'", Syntax.DEFAULT)).isEqualTo("I:X I:LIKE S:'a\\' I:ESCAPE S:'\\'");
        for (String sql : new String[] {"DO $$ BEGIN NULL; END $$", "a ->> 'k'", "t @> u", "WHERE id = $1", "E'a'"}) {
            assertThat(render(sql, Syntax.MYSQL)).as(sql).isEqualTo(render(sql, Syntax.DEFAULT));
        }
    }

    @Test
    @DisplayName("F-019-R10 PostgreSQL 읽기 방식에서도 이름 안의 $는 이름의 글자다(a$b). 이름에 붙은 $$는 달러 인용이 아니다")
    void dollarInsideIdentifier() {
        assertThat(pg("SELECT a$b, c$$ FROM t")).isEqualTo("I:SELECT I:A$B , I:C$$ I:FROM I:T");
        // 홀로 있는 $는 기호다
        assertThat(pg("SELECT $ FROM t")).isEqualTo("I:SELECT $ I:FROM I:T");
    }
}
