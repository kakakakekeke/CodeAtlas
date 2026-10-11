package io.codeatlas.sql.analysis;

import static io.codeatlas.sql.analysis.FactsTestSupport.joins;
import static io.codeatlas.sql.analysis.FactsTestSupport.readColumns;
import static io.codeatlas.sql.analysis.FactsTestSupport.reads;
import static io.codeatlas.sql.analysis.FactsTestSupport.sequences;
import static io.codeatlas.sql.analysis.FactsTestSupport.writeColumns;
import static io.codeatlas.sql.analysis.FactsTestSupport.writes;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.sql.ast.Statement;
import io.codeatlas.sql.lexer.Lexer;
import io.codeatlas.sql.lexer.Syntax;
import io.codeatlas.sql.parser.DdlParser;
import io.codeatlas.sql.parser.ParseProblem;
import io.codeatlas.sql.parser.ParseResult;
import io.codeatlas.sql.parser.SqlParser;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * PostgreSQL DML(F-019-R12~R17). 방언끼리 겹치지 않는 문법({@code ON CONFLICT}, {@code DELETE … USING}, 데이터 변경 CTE, {@code ->>} 등)은
 * 읽기 방식과 무관하게 읽고(ADR-0012), 겹치는 표기({@code #>>}, {@code ONLY 이름})만 PostgreSQL 읽기 방식에서 읽는다.
 */
class PostgresDmlFactsTest {

    /** spec 5절 "DML 예시"의 테이블. */
    private static Catalog shop() {
        Catalog c = new Catalog();
        c.defineTable("CUSTOMERS", "ID", "EMAIL", "NAME", "TAGS", "PROFILE", "CREATED_AT");
        c.defineTable("ORDERS", "ID", "CUSTOMER_ID", "STATUS", "AMOUNT", "MEMO", "ORDERED_AT");
        return c;
    }

    private record Read(ParseResult result, StatementFacts facts) {
        String problems() {
            return result.problems().stream().map(ParseProblem::message).collect(Collectors.joining(" / "));
        }

        /** 읽기·쓰기 테이블과 읽기·쓰기 컬럼의 테이블 이름 모두. */
        String everyName() {
            return reads(facts) + "|" + writes(facts) + "|" + readColumns(facts) + "|" + writeColumns(facts);
        }
    }

    private static Read read(String sql, Syntax syntax, Catalog catalog) {
        ParseResult result = SqlParser.parse(sql, syntax);
        return new Read(result, new FactExtractor(catalog).syntax(syntax).extract(result));
    }

    private static Read pg(String sql) {
        return read(sql, Syntax.POSTGRES, shop());
    }

    // ------------------------------------------------------------------ F-019-AC11: ON CONFLICT

    @ParameterizedTest
    @EnumSource(value = Syntax.class, names = {"DEFAULT", "POSTGRES"})
    @DisplayName("F-019-AC11 INSERT … ON CONFLICT (..) DO UPDATE SET .. RETURNING: 쓰기는 CUSTOMERS(EMAIL, NAME)뿐이고 EXCLUDED는 테이블도 컬럼의 소속도 아니다")
    void onConflictDoUpdate(Syntax syntax) {
        Read r = read("INSERT INTO customers (email, name) VALUES (#{e}, #{n}) ON CONFLICT (email) DO UPDATE SET name = EXCLUDED.name RETURNING id",
                syntax, shop());
        assertThat(r.problems()).isEmpty();
        assertThat(r.facts.statementType()).isEqualTo("INSERT");
        assertThat(writes(r.facts)).isEqualTo("CUSTOMERS[INSERT]");
        assertThat(writeColumns(r.facts)).isEqualTo("CUSTOMERS.EMAIL,CUSTOMERS.NAME");
        assertThat(reads(r.facts)).isEmpty();
        assertThat(r.everyName()).doesNotContain("EXCLUDED");
    }

    @ParameterizedTest
    @EnumSource(value = Syntax.class, names = {"DEFAULT", "POSTGRES"})
    @DisplayName("F-019-AC11 ON CONFLICT DO NOTHING, ON CONSTRAINT 이름, 대상 뒤의 WHERE, DO UPDATE의 대입 컬럼과 WHERE도 읽는다: 대입 컬럼은 쓰기다")
    void onConflictVariants(Syntax syntax) {
        Read nothing = read("INSERT INTO customers (email, name) VALUES (#{e}, #{n}) ON CONFLICT DO NOTHING", syntax, shop());
        assertThat(nothing.problems()).isEmpty();
        assertThat(writes(nothing.facts)).isEqualTo("CUSTOMERS[INSERT]");
        assertThat(writeColumns(nothing.facts)).isEqualTo("CUSTOMERS.EMAIL,CUSTOMERS.NAME");

        Read constraint = read("INSERT INTO customers (email) VALUES (#{e}) ON CONFLICT ON CONSTRAINT customers_email_key DO NOTHING RETURNING *", syntax, shop());
        assertThat(constraint.problems()).isEmpty();
        assertThat(constraint.everyName()).isEqualTo("|CUSTOMERS[INSERT]||CUSTOMERS.EMAIL");

        Read update = read("INSERT INTO customers AS c (email) VALUES (#{e}) ON CONFLICT (lower(email)) WHERE email IS NOT NULL "
                + "DO UPDATE SET name = EXCLUDED.name, created_at = now() WHERE c.name IS DISTINCT FROM EXCLUDED.name", syntax, shop());
        assertThat(update.problems()).isEmpty();
        assertThat(writes(update.facts)).isEqualTo("CUSTOMERS[INSERT]");
        assertThat(writeColumns(update.facts)).isEqualTo("CUSTOMERS.CREATED_AT,CUSTOMERS.EMAIL,CUSTOMERS.NAME");
        assertThat(reads(update.facts)).isEmpty();
        assertThat(update.everyName()).doesNotContain("EXCLUDED");

        // INSERT … SELECT 뒤의 ON CONFLICT: 조인의 ON 조건으로 읽지 않는다
        Read select = read("INSERT INTO customers (id, email) SELECT o.customer_id, o.memo FROM orders o JOIN customers c ON c.id = o.customer_id "
                + "ON CONFLICT (id) DO UPDATE SET email = EXCLUDED.email", syntax, shop());
        assertThat(select.problems()).isEmpty();
        assertThat(writes(select.facts)).isEqualTo("CUSTOMERS[INSERT]");
        assertThat(reads(select.facts)).isEqualTo("CUSTOMERS,ORDERS");
        assertThat(joins(select.facts)).isEqualTo("CUSTOMERS(ID)=ORDERS(CUSTOMER_ID)");
        assertThat(select.everyName()).doesNotContain("EXCLUDED").doesNotContain("CONFLICT");
    }

    @Test
    @DisplayName("F-019-AC11 MySQL의 ON DUPLICATE KEY UPDATE와 조인의 ON 조건은 전과 같다(ON CONFLICT 판정이 끼어들지 않는다)")
    void onConflictDoesNotDisturbOtherOnClauses() {
        Read mysql = read("INSERT INTO customers (id, name) VALUES (1, 'a') ON DUPLICATE KEY UPDATE name = VALUES(name)", Syntax.DEFAULT, shop());
        assertThat(mysql.problems()).isEmpty();
        assertThat(writeColumns(mysql.facts)).isEqualTo("CUSTOMERS.ID,CUSTOMERS.NAME");
        // CONFLICT라는 컬럼의 조인 조건
        Read column = read("SELECT a.id FROM ta a JOIN tb b ON conflict = 1", Syntax.DEFAULT, new Catalog());
        assertThat(column.problems()).isEmpty();
        assertThat(reads(column.facts)).isEqualTo("TA,TB");
    }

    // ------------------------------------------------------------------ F-019-AC12: DELETE … USING

    @ParameterizedTest
    @EnumSource(value = Syntax.class, names = {"DEFAULT", "POSTGRES"})
    @DisplayName("F-019-AC12 DELETE FROM 대상 별칭 USING 테이블 WHERE ..: 대상은 쓰기, USING의 테이블은 읽기, WHERE의 등치는 조인이다")
    void deleteUsing(Syntax syntax) {
        Read r = read("DELETE FROM order_items oi USING orders o WHERE o.id = oi.order_id AND o.status = 'NEW'", syntax, shop());
        assertThat(r.problems()).isEmpty();
        assertThat(r.facts.statementType()).isEqualTo("DELETE");
        assertThat(writes(r.facts)).isEqualTo("ORDER_ITEMS[DELETE]");
        assertThat(reads(r.facts)).isEqualTo("ORDERS");
        assertThat(readColumns(r.facts)).isEqualTo("ORDERS.ID,ORDERS.STATUS,ORDER_ITEMS.ORDER_ID");
        assertThat(joins(r.facts)).isEqualTo("ORDERS(ID)=ORDER_ITEMS(ORDER_ID)");
    }

    @Test
    @DisplayName("F-019-AC12 USING에 테이블이 여럿이거나 조인이 있어도 읽고 RETURNING도 읽는다. MySQL식(USING에 대상을 다시 적는 꼴)은 대상을 읽기로 만들지 않는다")
    void deleteUsingVariants() {
        Read many = read("DELETE FROM order_items oi USING orders o, customers c WHERE o.id = oi.order_id AND c.id = o.customer_id RETURNING oi.order_id",
                Syntax.DEFAULT, shop());
        assertThat(many.problems()).isEmpty();
        assertThat(writes(many.facts)).isEqualTo("ORDER_ITEMS[DELETE]");
        assertThat(reads(many.facts)).isEqualTo("CUSTOMERS,ORDERS");
        assertThat(joins(many.facts)).isEqualTo("CUSTOMERS(ID)=ORDERS(CUSTOMER_ID);ORDERS(ID)=ORDER_ITEMS(ORDER_ID)");

        Read mysql = read("DELETE FROM orders USING orders JOIN customers c ON c.id = orders.customer_id WHERE c.email = ?", Syntax.DEFAULT, shop());
        assertThat(mysql.problems()).isEmpty();
        assertThat(writes(mysql.facts)).isEqualTo("ORDERS[DELETE]");
        assertThat(reads(mysql.facts)).isEqualTo("CUSTOMERS");
        assertThat(joins(mysql.facts)).isEqualTo("CUSTOMERS(ID)=ORDERS(CUSTOMER_ID)");

        // USING이 없는 DELETE는 전과 같다
        Read plain = read("DELETE FROM orders o WHERE o.status = 'OLD'", Syntax.DEFAULT, shop());
        assertThat(plain.everyName()).isEqualTo("|ORDERS[DELETE]|ORDERS.STATUS|");
    }

    // ------------------------------------------------------------------ F-019-AC13: 데이터 변경 CTE

    @ParameterizedTest
    @EnumSource(value = Syntax.class, names = {"DEFAULT", "POSTGRES"})
    @DisplayName("F-019-AC13 WITH RECURSIVE와 데이터 변경 CTE(DELETE … RETURNING): 읽기 ORDERS, 쓰기 SESSION_CACHE이고 CTE 이름 T·DEL은 테이블이 아니다")
    void dataModifyingCte(Syntax syntax) {
        Read r = read("WITH RECURSIVE t AS (SELECT id FROM orders WHERE id = #{id} UNION ALL SELECT o.id FROM orders o JOIN t ON o.customer_id = t.id), "
                + "del AS (DELETE FROM session_cache WHERE k = #{k} RETURNING k) SELECT t.id, (SELECT count(*) FROM del) FROM t", syntax, shop());
        assertThat(r.problems()).isEmpty();
        assertThat(reads(r.facts)).isEqualTo("ORDERS");
        assertThat(writes(r.facts)).isEqualTo("SESSION_CACHE[DELETE]");
        assertThat(readColumns(r.facts)).isEqualTo("ORDERS.CUSTOMER_ID,ORDERS.ID,SESSION_CACHE.K");
        assertThat(joins(r.facts)).isEmpty();
    }

    @Test
    @DisplayName("F-019-AC13 INSERT·UPDATE·DELETE CTE와 WITH 뒤의 DML: 대상은 모두 쓰기이고 CTE 이름(INS, UPD, MOVED)은 테이블이 아니다")
    void dataModifyingCteVariants() {
        Read two = read("WITH ins AS (INSERT INTO audit_log (id, memo) VALUES (#{id}, 'x') RETURNING id), "
                + "upd AS (UPDATE orders SET status = 'DONE' WHERE id = #{id} RETURNING id, status) SELECT i.id, u.status FROM ins i, upd u", Syntax.DEFAULT, shop());
        assertThat(two.problems()).isEmpty();
        assertThat(writes(two.facts)).isEqualTo("AUDIT_LOG[INSERT],ORDERS[UPDATE]");
        assertThat(writeColumns(two.facts)).isEqualTo("AUDIT_LOG.ID,AUDIT_LOG.MEMO,ORDERS.STATUS");
        assertThat(reads(two.facts)).isEmpty();

        Read moved = read("WITH moved AS (DELETE FROM orders WHERE status = 'OLD' RETURNING *) INSERT INTO orders_archive SELECT * FROM moved", Syntax.DEFAULT, shop());
        assertThat(moved.problems()).isEmpty();
        assertThat(writes(moved.facts)).isEqualTo("ORDERS[DELETE],ORDERS_ARCHIVE[INSERT]");
        assertThat(reads(moved.facts)).isEmpty();

        // 보통의 CTE는 전과 같다
        Read plain = read("WITH c AS (SELECT id FROM orders) SELECT id FROM c", Syntax.DEFAULT, shop());
        assertThat(plain.everyName()).isEqualTo("ORDERS||ORDERS.ID|");
    }

    // ------------------------------------------------------------------ F-019-AC14: 그 밖의 식과 절 (spec 5절 "DML 예시")

    @Test
    @DisplayName("F-019-AC14 예시 1: ::, ->>, #>>, ILIKE, @>, ARRAY[..]::text[], ~*, interval, NULLS LAST, LIMIT .. OFFSET — 읽기 ORDERS·CUSTOMERS, 조인 ORDERS.CUSTOMER_ID = CUSTOMERS.ID")
    void example1() {
        Read r = pg("""
                SELECT o.id, o.amount::text AS amt, c.profile->>'grade' AS grade, c.profile #>> '{addr,city}' AS city
                  FROM orders o JOIN customers c ON c.id = o.customer_id
                 WHERE c.name ILIKE '%' || #{q} || '%' AND c.tags @> ARRAY[#{tag}]::text[] AND o.memo ~* 'vip'
                   AND o.ordered_at >= now() - interval '7 days'
                 ORDER BY o.ordered_at DESC NULLS LAST LIMIT #{size} OFFSET #{offset}
                """);
        assertThat(r.problems()).isEmpty();
        assertThat(r.facts.statementType()).isEqualTo("SELECT");
        assertThat(reads(r.facts)).isEqualTo("CUSTOMERS,ORDERS");
        assertThat(writes(r.facts)).isEmpty();
        assertThat(joins(r.facts)).isEqualTo("CUSTOMERS(ID)=ORDERS(CUSTOMER_ID)");
        assertThat(readColumns(r.facts)).isEqualTo("CUSTOMERS.ID,CUSTOMERS.NAME,CUSTOMERS.PROFILE,CUSTOMERS.TAGS,"
                + "ORDERS.AMOUNT,ORDERS.CUSTOMER_ID,ORDERS.ID,ORDERS.MEMO,ORDERS.ORDERED_AT");
        assertThat(r.facts.unknownColumns()).isEmpty();
    }

    @Test
    @DisplayName("F-019-AC14 예시 2: DISTINCT ON, LEFT JOIN LATERAL (..) x ON true, 함수 별칭 g(n), = ANY(..::bigint[]), FOR UPDATE OF c SKIP LOCKED — X·G·GENERATE_SERIES는 테이블이 아니다")
    void example2() {
        Read r = pg("""
                SELECT DISTINCT ON (c.id) c.id, x.amount, g.n
                  FROM customers c
                  LEFT JOIN LATERAL (SELECT amount FROM orders o WHERE o.customer_id = c.id ORDER BY ordered_at DESC LIMIT 1) x ON true
                  CROSS JOIN generate_series(1, 3) AS g(n)
                 WHERE c.id = ANY(#{ids}::bigint[])
                   FOR UPDATE OF c SKIP LOCKED
                """);
        assertThat(r.problems()).isEmpty();
        assertThat(reads(r.facts)).isEqualTo("CUSTOMERS,ORDERS");
        assertThat(writes(r.facts)).isEmpty();
        assertThat(joins(r.facts)).isEqualTo("ORDERS(CUSTOMER_ID)=CUSTOMERS(ID)");
        assertThat(readColumns(r.facts)).isEqualTo("CUSTOMERS.ID,ORDERS.AMOUNT,ORDERS.CUSTOMER_ID,ORDERS.ORDERED_AT");
        assertThat(r.facts.unknownColumns()).isEmpty();
        assertThat(r.facts.calls()).isEmpty();
    }

    @Test
    @DisplayName("F-019-AC14 예시 3: 집계 FILTER (WHERE ..), 첨자 c.tags[1], IS DISTINCT FROM — 읽기 CUSTOMERS·ORDERS")
    void example3() {
        Read r = pg("""
                SELECT c.id, count(o.id) FILTER (WHERE o.status = 'PAID') AS paid, c.tags[1]
                  FROM customers c LEFT JOIN orders o ON o.customer_id = c.id
                 WHERE o.status IS DISTINCT FROM 'NEW' GROUP BY c.id
                """);
        assertThat(r.problems()).isEmpty();
        assertThat(reads(r.facts)).isEqualTo("CUSTOMERS,ORDERS");
        assertThat(joins(r.facts)).isEqualTo("ORDERS(CUSTOMER_ID)=CUSTOMERS(ID)");
        assertThat(readColumns(r.facts)).isEqualTo("CUSTOMERS.ID,CUSTOMERS.TAGS,ORDERS.CUSTOMER_ID,ORDERS.ID,ORDERS.STATUS");
    }

    @Test
    @DisplayName("F-019-AC14 예시 4: UPDATE ONLY 테이블 별칭 SET .. FROM .. RETURNING * — 쓰기 ORDERS, 읽기 CUSTOMERS. ONLY는 테이블이 아니다")
    void example4() {
        Read r = pg("UPDATE ONLY orders o SET status = 'SHIPPED' FROM customers c WHERE c.id = o.customer_id AND c.email = #{email} RETURNING *");
        assertThat(r.problems()).isEmpty();
        assertThat(r.facts.statementType()).isEqualTo("UPDATE");
        assertThat(writes(r.facts)).isEqualTo("ORDERS[UPDATE]");
        assertThat(writeColumns(r.facts)).isEqualTo("ORDERS.STATUS");
        assertThat(reads(r.facts)).isEqualTo("CUSTOMERS");
        assertThat(joins(r.facts)).isEqualTo("CUSTOMERS(ID)=ORDERS(CUSTOMER_ID)");
        assertThat(r.everyName()).doesNotContain("ONLY");
    }

    @Test
    @DisplayName("F-019-AC14 예시 5: TRUNCATE TABLE ONLY t, u RESTART IDENTITY CASCADE — 쓰기 ORDERS, CUSTOMERS")
    void example5() {
        Read r = pg("TRUNCATE TABLE ONLY orders, customers RESTART IDENTITY CASCADE");
        assertThat(r.problems()).isEmpty();
        assertThat(writes(r.facts)).isEqualTo("CUSTOMERS[DELETE],ORDERS[DELETE]");
        assertThat(reads(r.facts)).isEmpty();
        assertThat(r.facts.tableWrites()).allMatch(StatementFacts.TableWrite::truncate);

        Read plain = pg("TRUNCATE orders, public.customers");
        assertThat(plain.problems()).isEmpty();
        assertThat(writes(plain.facts)).isEqualTo("CUSTOMERS[DELETE],ORDERS[DELETE]");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT o.id FROM orders o WHERE o.memo SIMILAR TO '%(vip|gold)%' AND o.status NOT SIMILAR TO 'X%'",
        "SELECT o.id FROM orders o WHERE o.memo NOT ILIKE #{q} AND o.status IS NOT DISTINCT FROM #{s}",
        "SELECT o.id FROM orders o WHERE o.memo ~ '^a' AND o.memo !~ 'b$' AND o.memo !~* 'c' AND o.status ILIKE ANY(ARRAY['a%', 'b%'])",
        "SELECT o.id, o.memo->'a'->>'b', o.memo #> '{a,b}', o.amount::numeric(10,2) FROM orders o WHERE o.memo <@ #{doc}::jsonb AND o.memo @@ to_tsquery('x')",
        "SELECT o.id FROM orders o WHERE o.status = ANY(ARRAY['A', 'B']) AND ARRAY[o.id] && #{ids}::bigint[] AND (ARRAY[1, 2, 3])[1] = 1",
        "SELECT o.id FROM orders o ORDER BY o.ordered_at NULLS FIRST LIMIT ALL OFFSET 5",
        "SELECT o.id FROM orders o WHERE o.id = $1 FOR NO KEY UPDATE",
        "SELECT o.id FROM orders o WHERE o.id = $1 FOR SHARE OF o NOWAIT",
        "SELECT o.id FROM orders o WHERE o.id = $1 FOR KEY SHARE SKIP LOCKED",
        "SELECT o.id FROM ONLY orders o WHERE o.ordered_at > now() - interval '1 day' * 3",
        "SELECT o.id, E'a\\'b' FROM orders o WHERE o.memo = E'x\\ny'",
    })
    @DisplayName("F-019-AC14 그 밖의 식과 절: SIMILAR TO, IS NOT DISTINCT FROM, ~·!~·!~*, ->·#>·<@·@@·&&, ANY(ARRAY[..]), LIMIT ALL, FOR NO KEY UPDATE|SHARE|KEY SHARE, FROM ONLY, E'..' — ORDERS만 읽는다")
    void otherExpressionsAndClauses(String sql) {
        Read r = pg(sql);
        assertThat(r.problems()).as(sql).isEmpty();
        assertThat(reads(r.facts)).as(sql).isEqualTo("ORDERS");
        assertThat(writes(r.facts)).as(sql).isEmpty();
        assertThat(readColumns(r.facts)).as(sql).matches("(ORDERS\\.(ID|MEMO|STATUS|AMOUNT|ORDERED_AT),?)+");
        assertThat(r.facts.unknownColumns()).as(sql).isEmpty();
    }

    @Test
    @DisplayName("F-019-AC14 FROM 함수(..) WITH ORDINALITY AS 별칭(컬럼, ..), 첨자 구간 a[1:2], DELETE FROM ONLY: 함수·별칭·타입 이름이 테이블이나 컬럼이 되지 않는다")
    void functionsInFromAndSubscripts() {
        Read ordinality = pg("SELECT c.id, t.tag, t.n FROM customers c CROSS JOIN LATERAL unnest(c.tags) WITH ORDINALITY AS t(tag, n) WHERE c.tags[1:2] IS NOT NULL");
        assertThat(ordinality.problems()).isEmpty();
        assertThat(ordinality.everyName()).isEqualTo("CUSTOMERS||CUSTOMERS.ID,CUSTOMERS.TAGS|");

        Read fn = pg("SELECT g.n FROM generate_series(1, 10) g(n)");
        assertThat(fn.problems()).isEmpty();
        assertThat(fn.everyName()).isEqualTo("|||");

        Read delete = pg("DELETE FROM ONLY orders WHERE status = 'OLD' RETURNING *");
        assertThat(delete.problems()).isEmpty();
        assertThat(delete.everyName()).isEqualTo("|ORDERS[DELETE]|ORDERS.STATUS|");
    }

    @Test
    @DisplayName("F-019-AC14 CREATE TABLE … AS … WITH [NO] DATA: 원본을 읽고 새 테이블에 쓴다. WITH DATA라는 CTE와 섞이지 않는다")
    void createTableAsWithData() {
        for (String tail : new String[] {"WITH NO DATA", "WITH DATA"}) {
            String sql = "CREATE TABLE orders_copy AS SELECT o.id, o.status FROM orders o WHERE o.status = 'NEW' " + tail;
            ParseResult result = new DdlParser(Lexer.tokenize(sql, Syntax.POSTGRES), Syntax.POSTGRES).parse();
            assertThat(result.problems()).as(sql).isEmpty();
            StatementFacts facts = new FactExtractor(shop()).extract(result);
            assertThat(reads(facts)).as(sql).isEqualTo("ORDERS");
            assertThat(writes(facts)).as(sql).isEqualTo("ORDERS_COPY[INSERT]");
        }
        Read cte = read("SELECT o.id FROM orders o WHERE o.id IN (WITH data AS (SELECT id FROM orders) SELECT id FROM data)", Syntax.DEFAULT, shop());
        assertThat(cte.problems()).isEmpty();
        assertThat(reads(cte.facts)).isEqualTo("ORDERS");
    }

    @Test
    @DisplayName("F-019-AC14 겹치지 않는 표기(::, ->>, @>, ~*, ILIKE, DISTINCT ON, FILTER, ARRAY[..], a[1])는 기본 읽기 방식에서도 읽는다")
    void nonOverlappingNotationInDefaultSyntax() {
        Read r = read("""
                SELECT DISTINCT ON (o.id) o.id, o.amount::text, c.profile->>'grade', count(*) FILTER (WHERE o.status = 'PAID'), c.tags[1]
                  FROM orders o JOIN customers c ON c.id = o.customer_id
                 WHERE c.name ILIKE #{q} AND c.tags @> ARRAY[#{tag}]::text[] AND o.memo ~* 'vip' AND c.profile -> 'a' ->> 'b' = 'x'
                 LIMIT 10
                """, Syntax.DEFAULT, shop());
        assertThat(r.problems()).isEmpty();
        assertThat(reads(r.facts)).isEqualTo("CUSTOMERS,ORDERS");
        assertThat(joins(r.facts)).isEqualTo("CUSTOMERS(ID)=ORDERS(CUSTOMER_ID)");
        assertThat(readColumns(r.facts)).isEqualTo("CUSTOMERS.ID,CUSTOMERS.NAME,CUSTOMERS.PROFILE,CUSTOMERS.TAGS,"
                + "ORDERS.AMOUNT,ORDERS.CUSTOMER_ID,ORDERS.ID,ORDERS.MEMO,ORDERS.STATUS");
    }

    @Test
    @DisplayName("F-019-AC14 겹치는 표기는 PostgreSQL 읽기 방식에서만 읽는다: 기본에서 #>>는 연산자가 아니고, ONLY 이름은 ONLY라는 테이블이며, MySQL의 x<@v는 비교다")
    void overlappingNotationOnlyInPostgresSyntax() {
        Read hash = read("SELECT c.profile #>> '{a,b}' FROM customers c WHERE c.id = #{id}", Syntax.DEFAULT, shop());
        assertThat(hash.result.problems()).isNotEmpty();
        assertThat(reads(hash.facts)).isEqualTo("CUSTOMERS");

        Read only = read("UPDATE ONLY SET a = 1", Syntax.DEFAULT, new Catalog());
        assertThat(only.problems()).isEmpty();
        assertThat(writes(only.facts)).isEqualTo("ONLY[UPDATE]");

        Read variable = read("SELECT a.id FROM ta a WHERE a.n<@lim AND a.m > @@max_n", Syntax.DEFAULT, new Catalog());
        assertThat(variable.problems()).isEmpty();
        assertThat(variable.everyName()).isEqualTo("TA||TA.ID,TA.M,TA.N|");
    }

    @Test
    @DisplayName("F-019-AC14 PostgreSQL 키워드와 같은 낱말을 이름으로 쓴 기본 읽기 방식의 구문은 전과 같이 읽는다: ILIKE·SIMILAR 별칭, ONLY·LATERAL 테이블, DATA라는 CTE, 뺄셈 뒤의 비교")
    void keywordsAsNamesInDefaultSyntax() {
        Read alias = read("SELECT a ilike, b similar FROM t", Syntax.DEFAULT, new Catalog());
        assertThat(alias.problems()).isEmpty();
        assertThat(alias.everyName()).isEqualTo("T||T.A,T.B|");

        Read columns = read("SELECT t.ilike, t.similar, t.conflict FROM t WHERE t.only = 1 AND t.a - 1 > 0 AND t.b -1 >= t.c", Syntax.DEFAULT,
                new Catalog());
        assertThat(columns.problems()).isEmpty();
        assertThat(columns.everyName()).isEqualTo("T||T.A,T.B,T.C,T.CONFLICT,T.ILIKE,T.ONLY,T.SIMILAR|");

        Read tables = read("SELECT o.a FROM only o JOIN lateral l ON l.id = o.id", Syntax.DEFAULT, new Catalog());
        assertThat(tables.problems()).isEmpty();
        assertThat(reads(tables.facts)).isEqualTo("LATERAL,ONLY");

        Read cte = read("WITH data AS (SELECT a FROM t), del AS (SELECT b FROM u) SELECT d.a FROM data d, del", Syntax.DEFAULT, new Catalog());
        assertThat(cte.problems()).isEmpty();
        assertThat(reads(cte.facts)).isEqualTo("T,U");

        // SQL*Plus의 치환 변수 &&이름은 연산자 &&가 아니다: 전처럼 해석하지 못한 토큰으로 남고 변수 이름이 컬럼이 되지 않는다
        Read substitution = read("SELECT a FROM t WHERE b = 1 &&extra", Syntax.DEFAULT, new Catalog());
        assertThat(substitution.result.problems()).isNotEmpty();
        assertThat(readColumns(substitution.facts)).doesNotContain("EXTRA");
    }

    // ------------------------------------------------------------------ F-019-AC15: 시퀀스 함수

    @ParameterizedTest
    @EnumSource(value = Syntax.class, names = {"DEFAULT", "POSTGRES"})
    @DisplayName("F-019-AC15 nextval('seq')·currval('seq')·setval('seq', ..)의 첫 인자가 문자열 리터럴이면 그 시퀀스 참조다(스키마 한정, ::regclass 포함). 리터럴이 아니면 참조가 없다")
    void sequenceFunctions(Syntax syntax) {
        assertThat(sequences(read("SELECT nextval('orders_id_seq')", syntax, shop()).facts)).isEqualTo("ORDERS_ID_SEQ");
        Read insert = read("INSERT INTO orders (id) VALUES (nextval('public.orders_id_seq'::regclass))", syntax, shop());
        assertThat(insert.problems()).isEmpty();
        assertThat(sequences(insert.facts)).isEqualTo("ORDERS_ID_SEQ");
        assertThat(insert.everyName()).isEqualTo("|ORDERS[INSERT]||ORDERS.ID");
        assertThat(sequences(read("SELECT setval('s', 1)", syntax, shop()).facts)).isEqualTo("S");
        assertThat(sequences(read("SELECT currval('s'), CURRVAL('public.t_seq')", syntax, shop()).facts)).isEqualTo("S,T_SEQ");
        assertThat(sequences(read("SELECT nextval('\"OrderSeq\"')", syntax, shop()).facts)).isEqualTo("OrderSeq");

        Read variable = read("SELECT nextval(v_name)", syntax, shop());
        assertThat(variable.problems()).isEmpty();
        assertThat(variable.facts.sequences()).isEmpty();
        assertThat(read("SELECT nextval(#{seq})", syntax, shop()).facts.sequences()).isEmpty();
        assertThat(read("SELECT nextval('seq_' || #{kind})", syntax, shop()).facts.sequences()).isEmpty();

        // setval의 나머지 인자는 그대로 읽는다
        Read setval = read("SELECT setval('orders_id_seq', (SELECT max(o.id) FROM orders o))", syntax, shop());
        assertThat(setval.problems()).isEmpty();
        assertThat(sequences(setval.facts)).isEqualTo("ORDERS_ID_SEQ");
        assertThat(reads(setval.facts)).isEqualTo("ORDERS");
    }

    @Test
    @DisplayName("F-019-AC15 pg_catalog.을 붙여 쓴 setval(pg_dump의 뒷정리)은 시퀀스 참조를 만들지 않는다. Oracle·DB2의 시퀀스 표기는 전과 같다")
    void sequenceFunctionBoundaries() {
        assertThat(pg("SELECT pg_catalog.setval('public.orders_id_seq', 10, true)").facts.sequences()).isEmpty();
        assertThat(sequences(read("SELECT seq_ord.NEXTVAL FROM dual", Syntax.DEFAULT, shop()).facts)).isEqualTo("SEQ_ORD");
        assertThat(sequences(read("VALUES NEXT VALUE FOR seq_ord", Syntax.DEFAULT, shop()).facts)).isEqualTo("SEQ_ORD");
        // MariaDB의 NEXTVAL(seq)는 이름을 받는다: 문자열 리터럴이 아니므로 이 규칙의 대상이 아니다
        assertThat(read("SELECT NEXTVAL(seq_ord)", Syntax.DEFAULT, shop()).facts.sequences()).isEmpty();
    }

    // ------------------------------------------------------------------ F-019-AC16: 시스템 카탈로그, FROM 없는 SELECT

    @Test
    @DisplayName("F-019-AC16 PostgreSQL 읽기 방식: pg_class, pg_catalog.pg_tables, information_schema.columns는 시스템 카탈로그다. pg_로 시작해도 카탈로그 이름이 아니면 프로젝트의 테이블이다")
    void systemCatalogs() {
        Syntax syntax = Syntax.POSTGRES;
        assertThat(read("SELECT relname FROM pg_class", syntax, shop()).facts.systemCatalogs()).containsExactly("PG_CLASS");
        assertThat(read("SELECT * FROM pg_catalog.pg_tables", syntax, shop()).facts.systemCatalogs()).containsExactly("PG_TABLES");
        assertThat(read("SELECT * FROM information_schema.columns", syntax, shop()).facts.systemCatalogs()).containsExactly("COLUMNS");
        assertThat(read("SELECT a.pid FROM pg_stat_activity a JOIN pg_catalog.app_thing t ON t.pid = a.pid", syntax, shop()).facts.systemCatalogs())
                .containsExactlyInAnyOrder("PG_STAT_ACTIVITY", "APP_THING");
        // PG사(결제) 테이블처럼 접두사만 같은 이름, 따옴표로 감싼 이름(대문자가 없는 따옴표 이름은 따옴표 없는 이름과 같은 이름으로 읽는다)
        Read project = read("SELECT p.id FROM pg_payment p JOIN \"pg_class\" q ON q.id = p.id", syntax, shop());
        assertThat(reads(project.facts)).isEqualTo("PG_CLASS,PG_PAYMENT");
        assertThat(project.facts.systemCatalogs()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = Syntax.class, names = {"DEFAULT", "MYSQL"})
    @DisplayName("F-019-AC16 기본·MySQL 읽기 방식: 스키마 없이 쓴 PG_ 이름(PG_CLASS, PG_SETTINGS, PG_STAT_DAILY)은 업무 테이블이다(F-019 이전과 같다). pg_catalog.·information_schema.로 한정한 참조만 시스템 카탈로그다")
    void unqualifiedPgNamesAreCatalogsOnlyInPostgresSyntax(Syntax syntax) {
        for (String sql : List.of("SELECT relname FROM pg_class", "SELECT PG_CD, MID FROM PG_SETTINGS WHERE USE_YN = 'Y'",
                "SELECT SUM(AMT) FROM PG_STAT_DAILY WHERE STAT_DT = #{dt}", "SELECT * FROM PG_STATIO_LOG l JOIN PG_TYPE t ON t.id = l.id",
                "SELECT a.pid FROM pg_stat_activity a")) {
            assertThat(read(sql, syntax, shop()).facts.systemCatalogs()).as(sql).isEmpty();
        }
        assertThat(read("SELECT * FROM pg_catalog.pg_tables", syntax, shop()).facts.systemCatalogs()).containsExactly("PG_TABLES");
        assertThat(read("SELECT * FROM information_schema.columns", syntax, shop()).facts.systemCatalogs()).containsExactly("COLUMNS");
        assertThat(read("SELECT a.pid FROM pg_stat_activity a JOIN pg_catalog.app_thing t ON t.pid = a.pid", syntax, shop()).facts.systemCatalogs())
                .containsExactly("APP_THING");
        // Oracle 데이터 사전의 이름 규칙은 그대로다
        assertThat(read("SELECT TABLE_NAME FROM USER_TABLES", syntax, shop()).facts.systemCatalogs()).containsExactly("USER_TABLES");
        Read quoted = read("SELECT p.id FROM pg_payment p JOIN \"pg_class\" q ON q.id = p.id", syntax, shop());
        assertThat(reads(quoted.facts)).isEqualTo("PG_PAYMENT,pg_class");
        assertThat(quoted.facts.systemCatalogs()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = Syntax.class, names = {"DEFAULT", "POSTGRES"})
    @DisplayName("F-019-AC16 FROM 없는 SELECT는 더미 테이블 없이 읽는다: 테이블 참조도 문제도 없다")
    void selectWithoutFrom(Syntax syntax) {
        for (String sql : List.of("SELECT now()", "SELECT 1", "SELECT current_date, #{a}::int + 1 AS n", "SELECT nextval('s') AS id")) {
            Read r = read(sql, syntax, shop());
            assertThat(r.problems()).as(sql).isEmpty();
            assertThat(r.facts.statementType()).as(sql).isEqualTo("SELECT");
            assertThat(reads(r.facts)).as(sql).isEmpty();
            assertThat(r.facts.dummyTable()).as(sql).isNull();
        }
    }

    // ------------------------------------------------------------------ F-019-AC36: COPY (쿼리) TO

    @Test
    @DisplayName("F-019-AC36 COPY (쿼리) TO ..: 괄호 안의 쿼리를 읽어 SELECT 구문의 읽기와 조인으로 기록한다. TO 뒤의 대상과 옵션은 받아 넘긴다")
    void copyQueryTo() {
        for (String tail : List.of("TO STDOUT", "TO STDOUT;", "TO stdout WITH (FORMAT csv, HEADER true, DELIMITER ';')", "TO '/tmp/orders.csv' CSV HEADER",
                "TO PROGRAM 'gzip > /tmp/orders.gz' WITH (FORMAT text)")) {
            String sql = "COPY (SELECT o.id FROM orders o JOIN customers c ON c.id = o.customer_id) " + tail;
            Read r = pg(sql);
            assertThat(r.problems()).as(sql).isEmpty();
            assertThat(r.facts.statementType()).as(sql).isEqualTo("SELECT");
            assertThat(reads(r.facts)).as(sql).isEqualTo("CUSTOMERS,ORDERS");
            assertThat(readColumns(r.facts)).as(sql).isEqualTo("CUSTOMERS.ID,ORDERS.CUSTOMER_ID,ORDERS.ID");
            assertThat(joins(r.facts)).as(sql).isEqualTo("CUSTOMERS(ID)=ORDERS(CUSTOMER_ID)");
            assertThat(writes(r.facts)).as(sql).isEmpty();
        }
        Read cte = pg("COPY (WITH x AS (SELECT id FROM orders WHERE status = 'NEW') SELECT x.id FROM x) TO STDOUT");
        assertThat(cte.problems()).isEmpty();
        assertThat(cte.everyName()).isEqualTo("ORDERS||ORDERS.ID,ORDERS.STATUS|");
        Read union = pg("COPY ((SELECT id FROM orders) UNION ALL (SELECT id FROM customers)) TO STDOUT");
        assertThat(union.problems()).isEmpty();
        assertThat(reads(union.facts)).isEqualTo("CUSTOMERS,ORDERS");
        Read values = pg("COPY (VALUES (1, 'a'), (2, 'b')) TO STDOUT");
        assertThat(values.problems()).isEmpty();
        assertThat(values.facts.statementType()).isEqualTo("SELECT");
        assertThat(values.everyName()).isEqualTo("|||");
    }

    @Test
    @DisplayName("F-019-AC36 COPY (INSERT|UPDATE|DELETE .. RETURNING ..) TO ..: 괄호 안의 데이터 변경 구문을 읽어 그 대상을 쓰기로 기록한다")
    void copyDataChangingStatementTo() {
        Read delete = pg("COPY (DELETE FROM orders WHERE status = 'OLD' RETURNING id) TO STDOUT");
        assertThat(delete.problems()).isEmpty();
        assertThat(delete.facts.statementType()).isEqualTo("DELETE");
        assertThat(delete.everyName()).isEqualTo("|ORDERS[DELETE]|ORDERS.ID,ORDERS.STATUS|");
        Read insert = pg("COPY (INSERT INTO orders (id) SELECT c.id FROM customers c RETURNING id) TO '/tmp/new.csv'");
        assertThat(insert.problems()).isEmpty();
        assertThat(reads(insert.facts)).isEqualTo("CUSTOMERS");
        assertThat(writes(insert.facts)).isEqualTo("ORDERS[INSERT]");
    }

    @Test
    @DisplayName("F-019-AC36 COPY (쿼리) TO의 쿼리를 읽지 못하면 조용히 넘기지 않는다: 문제를 남기고 읽은 데까지의 테이블은 남긴다")
    void copyQueryToWithUnreadableQuery() {
        Read garbage = pg("COPY (FROBNICATE orders) TO STDOUT");
        assertThat(garbage.problems()).isNotEmpty();
        Read unclosed = pg("COPY (SELECT o.id FROM orders o JOIN customers c ON c.id = o.customer_id TO STDOUT");
        assertThat(unclosed.problems()).isNotEmpty();
        assertThat(reads(unclosed.facts)).isEqualTo("CUSTOMERS,ORDERS");
        Read broken = pg("COPY (SELECT o.id FROM orders o WHERE o.id = = 1 }) TO STDOUT");
        assertThat(broken.problems()).isNotEmpty();
        assertThat(reads(broken.facts)).isEqualTo("ORDERS");
    }

    @Test
    @DisplayName("F-019-AC36 COPY 테이블 FROM '경로'와 COPY 테이블 [(컬럼)] TO ..는 전처럼 조용히 넘긴다(F-019-R23). 기본 읽기 방식의 COPY는 전처럼 모르는 구문이다")
    void copyTableFormsStaySilent() {
        for (String sql : List.of("COPY orders FROM '/tmp/a.csv'", "COPY orders TO '/tmp/out.csv'", "COPY orders (id, status) TO STDOUT WITH (FORMAT csv)",
                "COPY public.orders FROM PROGRAM 'cat /tmp/a.csv'")) {
            Read r = pg(sql);
            assertThat(r.problems()).as(sql).isEmpty();
            assertThat(r.result.statement()).as(sql).isInstanceOf(Statement.Other.class);
            assertThat(r.everyName()).as(sql).isEqualTo("|||");
        }
        assertThat(pg("COPY orders (id, status) FROM stdin").result.statement()).isInstanceOf(Statement.Copy.class);
        assertThat(read("COPY (SELECT id FROM orders) TO STDOUT", Syntax.DEFAULT, shop()).result.statement()).isInstanceOf(Statement.Unparsed.class);
    }
}
