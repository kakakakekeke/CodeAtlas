--
-- sample-pg 루틴: PL/pgSQL 함수·프로시저, LANGUAGE sql 루틴, 트리거, DO 블록
-- 01_schema.sql 다음에 실행한다.
--

SET check_function_bodies = false;
SET search_path = public, pg_catalog;

--
-- LANGUAGE sql 함수: 매개변수를 이름으로 쓴다. LANGUAGE 가 본문 앞에 온다(pg_dump 가 내는 꼴).
--

CREATE FUNCTION public.calc_order_total(p_order_id bigint) RETURNS numeric
    LANGUAGE sql STABLE
    AS $$
    SELECT coalesce(sum(i.qty * i.unit_price), 0)
      FROM order_items i
     WHERE i.order_id = p_order_id;
$$;


ALTER FUNCTION public.calc_order_total(p_order_id bigint) OWNER TO shop;

--
-- LANGUAGE sql 프로시저: 이름 없는 매개변수를 $1 로 쓴다.
--

CREATE PROCEDURE public.purge_old_events(integer)
    LANGUAGE sql
    AS $$
    DELETE FROM order_events WHERE created_at < now() - ($1 || ' days')::interval;
$$;

--
-- PL/pgSQL 함수: 태그 있는 달러 인용($body$), LANGUAGE 가 본문 뒤에 온다(손으로 쓴 꼴).
--

CREATE OR REPLACE FUNCTION public.touch_customer(p_customer_id bigint) RETURNS void
AS $body$
DECLARE
    v_seen jsonb;
BEGIN
    v_seen := jsonb_build_object('last_seen', now());
    UPDATE customers
       SET profile = coalesce(profile, '{}'::jsonb) || v_seen
     WHERE id = p_customer_id;
    IF NOT FOUND THEN
        RAISE NOTICE 'customer % not found', p_customer_id;
    END IF;
END;
$body$
LANGUAGE plpgsql;

--
-- PL/pgSQL 함수: 주문 마감. 본문에 F-019-R25~R28 의 문장을 모았다.
--

CREATE OR REPLACE FUNCTION public.close_order(p_order_id bigint, p_memo text DEFAULT NULL)
    RETURNS integer
    LANGUAGE plpgsql
    AS $$
<<main>>
DECLARE
    v_cnt integer := 0;
    v_customer_id orders.customer_id%TYPE;
    v_total numeric(12,2);
    v_tries integer := 0;
    r record;
BEGIN
    SELECT o.customer_id INTO STRICT v_customer_id
      FROM orders o
     WHERE o.id = p_order_id
       FOR UPDATE;

    v_total := calc_order_total(p_order_id);

    UPDATE orders
       SET status = 'SHIPPED', memo = coalesce(p_memo, memo), amount = v_total
     WHERE id = p_order_id;
    GET DIAGNOSTICS v_cnt = ROW_COUNT;

    IF v_cnt = 0 THEN
        RAISE EXCEPTION 'order % not found', p_order_id USING ERRCODE = 'P0002';
    ELSIF v_total > 1000000 THEN
        RAISE NOTICE 'large order %: %', p_order_id, v_total;
    ELSE
        NULL;
    END IF;

    FOR r IN
        SELECT i.product_id, i.qty
          FROM order_items i
         WHERE i.order_id = p_order_id
    LOOP
        CONTINUE WHEN r.qty = 0;
        UPDATE products SET stock = stock - r.qty WHERE id = r.product_id;
    END LOOP;

    WHILE v_tries < 3 LOOP
        v_tries := v_tries + 1;
        EXIT WHEN v_tries = 2;
    END LOOP;

    PERFORM public.touch_customer(v_customer_id);

    EXECUTE format('DELETE FROM %I WHERE k = $1', 'session_cache')
      USING 'order:' || p_order_id::text;

    BEGIN
        INSERT INTO payments (order_id, method, amount, paid_at)
        VALUES (p_order_id, 'POINT', 0, now());
    EXCEPTION
        WHEN unique_violation OR check_violation THEN
            RAISE WARNING 'payment row skipped for %: %', p_order_id, SQLERRM;
    END;

    RETURN v_cnt;
EXCEPTION
    WHEN no_data_found THEN
        RETURN -1;
    WHEN too_many_rows THEN
        RETURN -2;
END main;
$$;


ALTER FUNCTION public.close_order(p_order_id bigint, p_memo text) OWNER TO shop;

--
-- Name: FUNCTION close_order(p_order_id bigint, p_memo text); Type: COMMENT; Schema: public; Owner: shop
--

COMMENT ON FUNCTION public.close_order(p_order_id bigint, p_memo text) IS '주문 마감: 상태 변경, 재고 차감, 세션 캐시 삭제';

--
-- PL/pgSQL 함수: RETURNS TABLE 과 RETURN QUERY.
--

CREATE FUNCTION public.order_timeline(p_order_id bigint)
    RETURNS TABLE(event_type text, created_at timestamp with time zone, status text)
    LANGUAGE plpgsql STABLE
    AS $$
BEGIN
    RETURN QUERY
    SELECT e.event_type, e.created_at, o.status::text
      FROM order_events e
      JOIN orders o ON o.id = e.order_id
     WHERE e.order_id = p_order_id
     ORDER BY e.created_at;
END;
$$;

--
-- PL/pgSQL 프로시저: 데이터 변경 CTE, CALL, 풀 수 없는 동적 SQL, COMMIT.
-- 자동 커밋 연결에서 CALL 로 부른다.
--

CREATE OR REPLACE PROCEDURE public.archive_orders(p_before date, INOUT p_moved integer DEFAULT 0, p_stats_table text DEFAULT NULL)
    LANGUAGE plpgsql
    AS $proc$
BEGIN
    WITH moved AS (
        DELETE FROM orders o
         WHERE o.status = 'SHIPPED'
           AND o.ordered_at < p_before
        RETURNING o.*
    )
    INSERT INTO orders_archive
    SELECT * FROM moved;
    GET DIAGNOSTICS p_moved = ROW_COUNT;

    IF p_stats_table IS NOT NULL THEN
        EXECUTE format('INSERT INTO %I (run_at, moved) VALUES (now(), $1)', p_stats_table)
          USING p_moved;
    END IF;

    CALL public.purge_old_events(90);
    COMMIT;
END;
$proc$;

--
-- 트리거 함수와 트리거. NEW·OLD·TG_OP 는 변수다.
--

CREATE FUNCTION public.trg_orders_audit() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
            RETURN NEW;
        END IF;
    END IF;
    INSERT INTO order_events (order_id, event_type, payload)
    VALUES (NEW.id, TG_OP, to_jsonb(NEW));
    RETURN NEW;
END;
$$;


CREATE TRIGGER orders_audit AFTER INSERT OR UPDATE ON public.orders FOR EACH ROW EXECUTE FUNCTION public.trg_orders_audit();

--
-- 본문을 분석하지 않는 언어(C)의 함수 헤더. 루틴 노드만 생긴다.
--

CREATE FUNCTION public.fast_hash(text) RETURNS integer
    LANGUAGE c IMMUTABLE STRICT
    AS '$libdir/shop_ext', 'fast_hash';

--
-- 익명 블록: 만료된 세션을 지우고 기본 고객을 한 번 건드린다.
--

DO $$
DECLARE
    v_n integer;
BEGIN
    SELECT count(*) INTO v_n FROM session_cache;
    IF v_n > 0 THEN
        DELETE FROM session_cache WHERE expires_at < now();
    END IF;
    PERFORM touch_customer(1);
END
$$;
