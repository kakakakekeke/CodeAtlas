package io.codeatlas.sql.script;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.sql.lexer.Syntax;
import io.codeatlas.sql.lexer.Token;
import io.codeatlas.sql.lexer.TokenType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PostgreSQL 루틴 헤더(F-019-R24·R29): 매개변수, 반환 타입, 순서와 무관한 속성, 본문의 자리, 트리거. */
class PostgresRoutineHeaderTest {

    private static ScriptUnit unit(String text) {
        List<ScriptUnit> units = new ArrayList<>();
        ScriptSplitter.forEach(text, units::add, null, Syntax.POSTGRES);
        assertThat(units).hasSize(1);
        assertThat(units.get(0).kind()).isEqualTo(ScriptUnit.Kind.ROUTINE);
        lastUnit = units.get(0);
        return lastUnit;
    }

    /** 가장 최근에 나눈 단위(헤더가 가리키는 토큰을 꺼낼 때 쓴다). */
    private static ScriptUnit lastUnit;

    private static Token bodyToken(RoutineHeader h) {
        return lastUnit.tokens().get(h.postgres().bodyToken());
    }

    private static RoutineHeader header(String text) {
        return RoutineHeader.parse(unit(text).tokens(), Syntax.POSTGRES);
    }

    @Test
    @DisplayName("F-019-AC22 매개변수: [IN|OUT|INOUT|VARIADIC] [이름] 타입 [DEFAULT 식 | = 식]. 표기는 이름 [모드] 타입이고, 이름 없는 매개변수는 타입부터다")
    void parameters() {
        RoutineHeader h = header("""
                CREATE OR REPLACE FUNCTION shop.f(p_a bigint, IN p_b text DEFAULT 'x'::text, OUT o_c numeric(12,2), INOUT io_d integer = 0,
                    VARIADIC p_e text[], integer, timestamp with time zone, double precision, character varying(10), p_f interval DEFAULT '1 day',
                    "Mixed" int, p_g IN OUT date, p_h orders.id%TYPE, IN bigint, p_i timestamp without time zone DEFAULT now(), p_j numeric(10,2)[])
                RETURNS void LANGUAGE plpgsql AS $$ BEGIN NULL; END $$;
                """);
        assertThat(h.kind()).isEqualTo(RoutineHeader.Kind.FUNCTION);
        assertThat(h.name().name()).isEqualTo("F");
        assertThat(h.name().qualifier()).isEqualTo("SHOP");
        assertThat(h.parameters()).containsExactly("P_A BIGINT", "P_B IN TEXT DEFAULT 'x'::TEXT", "O_C OUT NUMERIC(12,2)", "IO_D INOUT INTEGER = 0",
                "P_E VARIADIC TEXT[]", "INTEGER", "TIMESTAMP WITH TIME ZONE", "DOUBLE PRECISION", "CHARACTER VARYING(10)", "P_F INTERVAL DEFAULT '1 day'",
                "Mixed INT", "P_G INOUT DATE", "P_H ORDERS.ID%TYPE", "IN BIGINT", "P_I TIMESTAMP WITHOUT TIME ZONE DEFAULT NOW()", "P_J NUMERIC(10,2)[]");
        assertThat(h.postgres().variables()).containsExactly("P_A", "P_B", "O_C", "IO_D", "P_E", "P_F", "Mixed", "P_G", "P_H", "P_I", "P_J");
        assertThat(h.postgres().returnType()).isEqualTo("VOID");
        assertThat(h.postgres().language()).isEqualTo("PLPGSQL");
    }

    @Test
    @DisplayName("F-019-AC22 속성은 순서와 무관하다: LANGUAGE가 본문 앞·뒤, 옛 표기 LANGUAGE 'plpgsql', SECURITY DEFINER, SET .., RETURNS NULL ON NULL INPUT, COST·ROWS·PARALLEL")
    void attributesInAnyOrder() {
        RoutineHeader before = header("CREATE FUNCTION f() RETURNS integer LANGUAGE plpgsql STABLE SECURITY DEFINER AS $$ BEGIN RETURN 1; END $$;");
        assertThat(bodyToken(before).dollarTag()).isEmpty();
        RoutineHeader after = header("CREATE FUNCTION f() RETURNS integer AS $body$ BEGIN RETURN 1; END $body$ LANGUAGE 'plpgsql' VOLATILE COST 100;");
        assertThat(bodyToken(after).dollarTag()).isEqualTo("body");
        RoutineHeader mixed = header("""
                CREATE FUNCTION f(a int) RETURNS SETOF public.orders
                    RETURNS NULL ON NULL INPUT SET search_path = public, pg_temp SET work_mem TO '64MB' SET timezone FROM CURRENT
                    ROWS 100 PARALLEL SAFE NOT LEAKPROOF
                    AS $$ SELECT * FROM orders $$
                    LANGUAGE sql;
                """);
        assertThat(bodyToken(mixed).type()).isEqualTo(TokenType.STRING);
        assertThat(bodyToken(mixed).dollarBody()).isEqualTo(" SELECT * FROM orders ");
        for (RoutineHeader h : List.of(before, after, mixed)) {
            assertThat(h.bodyStart()).isEqualTo(h.postgres().bodyToken()).isGreaterThan(0);
        }
        assertThat(before.postgres().language()).isEqualTo("PLPGSQL");
        assertThat(after.postgres().language()).isEqualTo("PLPGSQL");
        assertThat(after.postgres().returnType()).isEqualTo("INTEGER");
        assertThat(mixed.postgres().language()).isEqualTo("SQL");
        assertThat(mixed.postgres().returnType()).isEqualTo("SETOF PUBLIC.ORDERS");
        assertThat(mixed.parameters()).containsExactly("A INT");
    }


    @Test
    @DisplayName("F-019-AC22 RETURNS TABLE(..)의 컬럼은 변수다. 트리거 함수는 RETURNS trigger로 알아본다")
    void returnsTableAndTrigger() {
        RoutineHeader table = header("""
                CREATE FUNCTION t(p int) RETURNS TABLE (id bigint, name character varying(20), at timestamp with time zone)
                LANGUAGE plpgsql AS $$ BEGIN RETURN; END $$;
                """);
        assertThat(table.postgres().returnType()).isEqualTo("TABLE(ID BIGINT, NAME CHARACTER VARYING(20), AT TIMESTAMP WITH TIME ZONE)");
        assertThat(table.postgres().variables()).containsExactly("P", "ID", "NAME", "AT");
        assertThat(table.postgres().returnsTrigger()).isFalse();
        RoutineHeader trigger = header("CREATE FUNCTION trg() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$;");
        assertThat(trigger.postgres().returnsTrigger()).isTrue();
        assertThat(header("CREATE FUNCTION trg() RETURNS pg_catalog.trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$;").postgres().returnsTrigger()).isTrue();
        assertThat(header("CREATE FUNCTION e() RETURNS event_trigger LANGUAGE plpgsql AS $$ BEGIN NULL; END $$;").postgres().returnsTrigger()).isTrue();
    }

    @Test
    @DisplayName("F-019-AC22 F-019-AC29 본문의 자리: 작은따옴표 본문, C 함수의 AS '파일', '심볼', SQL 표준 본문(RETURN 식, BEGIN ATOMIC)은 LANGUAGE가 없어도 sql이다. 본문이 없으면 -1")
    void bodyForms() {
        RoutineHeader quoted = header("CREATE FUNCTION cnt(bigint) RETURNS bigint LANGUAGE sql AS 'SELECT count(*) FROM orders WHERE customer_id = $1';");
        assertThat(quoted.postgres().bodyToken()).isGreaterThan(0);
        assertThat(quoted.postgres().language()).isEqualTo("SQL");
        RoutineHeader c = header("CREATE FUNCTION fast_hash(text) RETURNS integer LANGUAGE c IMMUTABLE STRICT AS '$libdir/shop_ext', 'fast_hash';");
        assertThat(c.postgres().language()).isEqualTo("C");
        assertThat(c.postgres().returnType()).isEqualTo("INTEGER");
        assertThat(c.parameters()).containsExactly("TEXT");
        RoutineHeader returning = header("CREATE FUNCTION one() RETURNS int RETURN 1;");
        assertThat(returning.postgres().bodyToken()).isEqualTo(-1);
        assertThat(returning.postgres().language()).isEqualTo("SQL");
        assertThat(returning.bodyStart()).isGreaterThan(0);
        assertThat(returning.postgres().returnType()).isEqualTo("INT");
        RoutineHeader atomic = header("CREATE PROCEDURE p(a int) BEGIN ATOMIC DELETE FROM t WHERE id = a; END;");
        assertThat(atomic.kind()).isEqualTo(RoutineHeader.Kind.PROCEDURE);
        assertThat(atomic.postgres().language()).isEqualTo("SQL");
        assertThat(atomic.bodyStart()).isGreaterThan(0);
        RoutineHeader none = header("CREATE FUNCTION nothing(a int) RETURNS int LANGUAGE plpgsql;");
        assertThat(none.bodyStart()).isEqualTo(-1);
        assertThat(none.postgres().language()).isEqualTo("PLPGSQL");
    }

    @Test
    @DisplayName("F-019-AC27 트리거 헤더: 사건 표기, 대상 테이블, 실행하는 함수(FUNCTION·PROCEDURE, 스키마 포함). CONSTRAINT TRIGGER와 INSTEAD OF도 같다")
    void triggers() {
        RoutineHeader h = header(
                "CREATE TRIGGER orders_audit AFTER INSERT OR UPDATE ON public.orders FOR EACH ROW EXECUTE FUNCTION public.trg_orders_audit();");
        assertThat(h.kind()).isEqualTo(RoutineHeader.Kind.TRIGGER);
        assertThat(h.name().name()).isEqualTo("ORDERS_AUDIT");
        assertThat(h.triggerEvent()).isEqualTo("AFTER INSERT OR UPDATE");
        assertThat(h.triggerTable().name()).isEqualTo("ORDERS");
        assertThat(h.postgres().triggerFunction().name()).isEqualTo("TRG_ORDERS_AUDIT");
        assertThat(h.postgres().triggerFunction().qualifier()).isEqualTo("PUBLIC");
        assertThat(h.bodyStart()).isEqualTo(-1);
        RoutineHeader constraint = header("""
                CREATE CONSTRAINT TRIGGER chk AFTER INSERT ON orders DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW WHEN (NEW.id > 0) EXECUTE PROCEDURE check_order('a', 1);
                """);
        assertThat(constraint.kind()).isEqualTo(RoutineHeader.Kind.TRIGGER);
        assertThat(constraint.name().name()).isEqualTo("CHK");
        assertThat(constraint.triggerEvent()).isEqualTo("AFTER INSERT");
        assertThat(constraint.triggerTable().name()).isEqualTo("ORDERS");
        assertThat(constraint.postgres().triggerFunction().name()).isEqualTo("CHECK_ORDER");
        RoutineHeader instead = header("CREATE OR REPLACE TRIGGER v_ins INSTEAD OF INSERT OR DELETE ON v_orders FOR EACH ROW EXECUTE FUNCTION v_ins_fn();");
        assertThat(instead.triggerEvent()).isEqualTo("INSTEAD OF INSERT OR DELETE");
        assertThat(instead.triggerTable().name()).isEqualTo("V_ORDERS");
        RoutineHeader broken = header("CREATE TRIGGER t AFTER INSERT ON orders;");
        assertThat(broken.postgres().triggerFunction()).isNull();
    }

    @Test
    @DisplayName("F-019-AC6 기본 읽기 방식의 헤더는 그대로다: PostgreSQL 정보가 없고 PL/SQL·SQL PL 헤더를 전과 같이 읽는다")
    void defaultSyntaxIsUnchanged() {
        List<ScriptUnit> units = ScriptSplitter.split("CREATE OR REPLACE PROCEDURE shop.sp_a (p_id IN NUMBER, p_nm OUT VARCHAR2) IS BEGIN NULL; END;\n/\n");
        RoutineHeader plain = RoutineHeader.parse(units.get(0).tokens());
        RoutineHeader withSyntax = RoutineHeader.parse(units.get(0).tokens(), Syntax.DEFAULT);
        assertThat(withSyntax).isEqualTo(plain);
        assertThat(plain.postgres()).isNull();
        assertThat(plain.parameters()).containsExactly("P_ID IN NUMBER", "P_NM OUT VARCHAR2");
        assertThat(RoutineHeader.parse(units.get(0).tokens(), Syntax.MYSQL).postgres()).isNull();
    }
}
