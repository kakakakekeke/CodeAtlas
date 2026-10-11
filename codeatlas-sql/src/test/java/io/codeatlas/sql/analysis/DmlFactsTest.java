package io.codeatlas.sql.analysis;

import static io.codeatlas.sql.analysis.FactsTestSupport.analyze;
import static io.codeatlas.sql.analysis.FactsTestSupport.calls;
import static io.codeatlas.sql.analysis.FactsTestSupport.joins;
import static io.codeatlas.sql.analysis.FactsTestSupport.readColumns;
import static io.codeatlas.sql.analysis.FactsTestSupport.reads;
import static io.codeatlas.sql.analysis.FactsTestSupport.sampleCatalog;
import static io.codeatlas.sql.analysis.FactsTestSupport.sequences;
import static io.codeatlas.sql.analysis.FactsTestSupport.writeColumns;
import static io.codeatlas.sql.analysis.FactsTestSupport.writes;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.sql.parser.ParseResult;
import io.codeatlas.sql.parser.SqlParser;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** DML 조각 → 구문 종류, 읽기, 쓰기, 쓴 컬럼, 조인 (표 기반). */
class DmlFactsTest {

    static Stream<Arguments> dmlCases() {
        return Stream.of(
                // sql, type, reads, writes, writeColumns, joins
                Arguments.of("INSERT INTO ord (id, nm, reg_dt) VALUES (?, #{nm}, SYSDATE)", "INSERT", "", "ORD[INSERT]", "ORD.ID,ORD.NM,ORD.REG_DT", ""),
                Arguments.of("INSERT INTO shop.ord VALUES (1, 'a')", "INSERT", "", "ORD[INSERT]", "", ""),
                Arguments.of("INSERT INTO ord (id, nm) VALUES (1, 'a'), (2, 'b')", "INSERT", "", "ORD[INSERT]", "ORD.ID,ORD.NM", ""),
                Arguments.of("INSERT /*+ APPEND */ INTO hist (id, amt) SELECT o.id, i.amt FROM ord o JOIN item i ON i.ord_id = o.id WHERE o.dt < ?",
                        "INSERT", "ITEM,ORD", "HIST[INSERT]", "HIST.AMT,HIST.ID", "ITEM(ORD_ID)=ORD(ID)"),
                Arguments.of("INSERT INTO hist (id) WITH c AS (SELECT id FROM ord) SELECT id FROM c", "INSERT", "ORD", "HIST[INSERT]", "HIST.ID", ""),
                Arguments.of("INSERT ALL INTO t1 (a, b) VALUES (x, y) INTO t2 (c) VALUES (z) SELECT x, y, z FROM src", "INSERT", "SRC", "T1[INSERT],T2[INSERT]", "T1.A,T1.B,T2.C", ""),
                Arguments.of("INSERT FIRST WHEN amt > 100 THEN INTO big (id) VALUES (id) WHEN amt > 10 THEN INTO mid (id) VALUES (id) ELSE INTO small (id) VALUES (id) SELECT id, amt FROM src",
                        "INSERT", "SRC", "BIG[INSERT],MID[INSERT],SMALL[INSERT]", "BIG.ID,MID.ID,SMALL.ID", ""),
                Arguments.of("INSERT INTO ord (id) VALUES (seq_ord.NEXTVAL) RETURNING id INTO :id", "INSERT", "", "ORD[INSERT]", "ORD.ID", ""),
                Arguments.of("UPDATE ord SET st = 'C', upd_dt = SYSDATE WHERE id = ?", "UPDATE", "", "ORD[UPDATE]", "ORD.ST,ORD.UPD_DT", ""),
                Arguments.of("UPDATE ord o SET o.st = ?, o.amt = o.amt + 1 WHERE o.id IN (SELECT i.ord_id FROM item i WHERE i.pid = ?)", "UPDATE", "ITEM", "ORD[UPDATE]", "ORD.AMT,ORD.ST", "ORD(ID)=ITEM(ORD_ID)"),
                Arguments.of("UPDATE ord o SET (o.amt, o.cnt) = (SELECT SUM(i.amt), COUNT(*) FROM item i WHERE i.ord_id = o.id) WHERE o.st = 'A'",
                        "UPDATE", "ITEM", "ORD[UPDATE]", "ORD.AMT,ORD.CNT", "ITEM(ORD_ID)=ORD(ID)"),
                Arguments.of("UPDATE ord o SET o.nm = (SELECT m.nm FROM mem m WHERE m.id = o.mid)", "UPDATE", "MEM", "ORD[UPDATE]", "ORD.NM", "MEM(ID)=ORD(MID)"),
                Arguments.of("UPDATE (SELECT o.st, m.grade FROM ord o JOIN mem m ON o.mid = m.id WHERE m.grade = 'V') SET st = 'X'", "UPDATE", "MEM", "ORD[UPDATE]", "ORD.ST", "ORD(MID)=MEM(ID)"),
                Arguments.of("UPDATE ord o SET o.st = s.st FROM stage s WHERE s.id = o.id", "UPDATE", "STAGE", "ORD[UPDATE]", "ORD.ST", "STAGE(ID)=ORD(ID)"),
                Arguments.of("UPDATE ord SET st = ? WHERE CURRENT OF c1", "UPDATE", "", "ORD[UPDATE]", "ORD.ST", ""),
                Arguments.of("DELETE FROM ord WHERE id = ?", "DELETE", "", "ORD[DELETE]", "", ""),
                Arguments.of("DELETE ord o WHERE o.dt < ?", "DELETE", "", "ORD[DELETE]", "", ""),
                Arguments.of("DELETE FROM item i WHERE EXISTS (SELECT 1 FROM ord o WHERE o.id = i.ord_id AND o.st = 'D')", "DELETE", "ORD", "ITEM[DELETE]", "", "ORD(ID)=ITEM(ORD_ID)"),
                Arguments.of("MERGE INTO stock s USING (SELECT i.pid, SUM(i.qty) qty FROM item i GROUP BY i.pid) x ON (s.pid = x.pid) "
                        + "WHEN MATCHED THEN UPDATE SET s.qty = s.qty - x.qty, s.upd_dt = SYSDATE DELETE WHERE s.qty < 0 "
                        + "WHEN NOT MATCHED THEN INSERT (pid, qty) VALUES (x.pid, x.qty)",
                        "MERGE", "ITEM", "STOCK[MERGE]", "STOCK.PID,STOCK.QTY,STOCK.UPD_DT", "STOCK(PID)=ITEM(PID)"),
                Arguments.of("MERGE INTO tgt t USING src s ON t.id = s.id AND t.k = s.k WHEN MATCHED AND s.del = 'Y' THEN DELETE WHEN MATCHED THEN UPDATE SET t.v = s.v WHEN NOT MATCHED THEN INSERT (id, k, v) VALUES (s.id, s.k, s.v) ELSE IGNORE",
                        "MERGE", "SRC", "TGT[MERGE]", "TGT.ID,TGT.K,TGT.V", "TGT(ID,K)=SRC(ID,K)"),
                Arguments.of("MERGE INTO tgt USING DUAL ON (id = ?) WHEN MATCHED THEN UPDATE SET v = ? WHEN NOT MATCHED THEN INSERT (id, v) VALUES (?, ?)",
                        "MERGE", "", "TGT[MERGE]", "TGT.ID,TGT.V", ""),
                Arguments.of("TRUNCATE TABLE shop.tmp_ord", "DELETE", "", "TMP_ORD[DELETE]", "", ""),
                Arguments.of("TRUNCATE TABLE tmp_ord IMMEDIATE", "DELETE", "", "TMP_ORD[DELETE]", "", ""),
                Arguments.of("LOCK TABLE ord, item IN EXCLUSIVE MODE NOWAIT", "OTHER", "ITEM,ORD", "", "", ""),
                Arguments.of("WITH c AS (SELECT id FROM src) SELECT id FROM c", "SELECT", "SRC", "", "", ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dmlCases")
    @DisplayName("F-006-AC2 F-006-AC3 DML 조각 → 종류, 읽기, 쓰기, 조인 (표)")
    void dml(String sql, String type, String expectedReads, String expectedWrites, String expectedWriteColumns, String expectedJoins) {
        ParseResult result = SqlParser.parse(sql);
        assertThat(result.problems()).as("parse problems of: " + sql).isEmpty();
        StatementFacts f = new FactExtractor(new Catalog()).extract(result);
        assertThat(f.statementType()).isEqualTo(type);
        assertThat(reads(f)).as("reads").isEqualTo(expectedReads);
        assertThat(writes(f)).as("writes").isEqualTo(expectedWrites);
        assertThat(writeColumns(f)).as("write columns").isEqualTo(expectedWriteColumns);
        assertThat(joins(f)).as("joins").isEqualTo(expectedJoins);
    }

    @Test
    @DisplayName("F-006-AC3 UPDATE 대상 테이블의 WHERE·SET 우변 컬럼은 컬럼 READS, 테이블 수준 READS는 없다")
    void updateReadsTargetColumns() {
        StatementFacts f = analyze("UPDATE ord SET amt = amt + ?, st = 'C' WHERE id = ? AND st = 'A'");
        assertThat(reads(f)).isEmpty();
        assertThat(readColumns(f)).isEqualTo("ORD.AMT,ORD.ID,ORD.ST");
        assertThat(writeColumns(f)).isEqualTo("ORD.AMT,ORD.ST");
        assertThat(f.columnWrites()).allMatch(c -> "UPDATE".equals(c.op()));
    }

    @Test
    @DisplayName("F-006-AC14 조인 인라인 뷰 UPDATE·DELETE: 갱신되는 테이블 인스턴스에는 테이블 수준 READS가 없고, 조건으로만 읽는 테이블은 READS다")
    void inlineViewDmlTargetIsNotRead() {
        StatementFacts f = analyze("UPDATE (SELECT d.dlv_st, d.ship_dt, o.ord_st FROM dlv d, ord o WHERE d.ord_id = o.ord_id AND o.ord_st = '30') "
                + "SET dlv_st = 'SHIP', ship_dt = SYSDATE");
        assertThat(writes(f)).isEqualTo("DLV[UPDATE]");
        assertThat(reads(f)).isEqualTo("ORD");
        assertThat(writeColumns(f)).isEqualTo("DLV.DLV_ST,DLV.SHIP_DT");
        // 대상 테이블의 컬럼 READS와 조인은 그대로 남는다
        assertThat(readColumns(f)).isEqualTo("DLV.DLV_ST,DLV.ORD_ID,DLV.SHIP_DT,ORD.ORD_ID,ORD.ORD_ST");
        assertThat(joins(f)).isEqualTo("DLV(ORD_ID)=ORD(ORD_ID)");

        StatementFacts del = analyze("DELETE FROM (SELECT * FROM ord WHERE st = 'X')");
        assertThat(writes(del)).isEqualTo("ORD[DELETE]");
        assertThat(reads(del)).isEmpty();
    }

    @Test
    @DisplayName("F-006-AC14 같은 구문에서 대상 테이블을 별도 인스턴스로 다시 읽으면(서브쿼리) 테이블 수준 READS가 남는다")
    void targetReadAgainThroughAnotherInstance() {
        StatementFacts f = analyze("UPDATE (SELECT d.st FROM dlv d WHERE d.ord_id IN (SELECT x.ord_id FROM dlv x WHERE x.st = 'A')) SET st = 'B'");
        assertThat(writes(f)).isEqualTo("DLV[UPDATE]");
        assertThat(reads(f)).isEqualTo("DLV");
        StatementFacts g = analyze("UPDATE ord o SET o.st = 'X' WHERE EXISTS (SELECT 1 FROM ord p WHERE p.id = o.pid)");
        assertThat(reads(g)).isEqualTo("ORD");
        // 어느 테이블이 갱신되는지 정하지 못한 인라인 뷰는 그대로 둔다(쓰기를 만들지 않고 읽기만 남긴다)
        StatementFacts h = analyze("UPDATE (SELECT o.st, m.grade FROM ord o JOIN mem m ON o.mid = m.id) SET flag = 1");
        assertThat(writes(h)).isEmpty();
        assertThat(reads(h)).isEqualTo("MEM,ORD");
    }

    @Test
    @DisplayName("F-006-AC3 INSERT ... SELECT, MERGE의 컬럼 소속을 카탈로그로 정한다")
    void dmlWithCatalog() {
        Catalog c = sampleCatalog();
        StatementFacts f = analyze("INSERT INTO tb_order_item (order_id, item_seq, product_id, price) "
                + "SELECT order_id, 1, p.product_id, p.price FROM tb_order, tb_product p WHERE order_status = 'N'", c);
        assertThat(writeColumns(f)).isEqualTo("TB_ORDER_ITEM.ITEM_SEQ,TB_ORDER_ITEM.ORDER_ID,TB_ORDER_ITEM.PRICE,TB_ORDER_ITEM.PRODUCT_ID");
        assertThat(readColumns(f)).isEqualTo("TB_ORDER.ORDER_ID,TB_ORDER.ORDER_STATUS,TB_PRODUCT.PRICE,TB_PRODUCT.PRODUCT_ID");
        StatementFacts m = analyze("MERGE INTO tb_order_item t USING tb_product p ON (t.product_id = p.product_id) WHEN MATCHED THEN UPDATE SET price = p.price", c);
        assertThat(writeColumns(m)).isEqualTo("TB_ORDER_ITEM.PRICE");
        assertThat(m.columnWrites()).allMatch(w -> "MERGE".equals(w.op()));
        assertThat(joins(m)).isEqualTo("TB_ORDER_ITEM(PRODUCT_ID)=TB_PRODUCT(PRODUCT_ID)");
    }

    @Test
    @DisplayName("F-006-AC6 INSERT 값의 시퀀스는 시퀀스 READS다")
    void insertSequence() {
        StatementFacts f = analyze("INSERT INTO ord (id) VALUES (seq_ord.NEXTVAL)");
        assertThat(sequences(f)).isEqualTo("SEQ_ORD");
        assertThat(reads(f)).isEmpty();
        StatementFacts g = analyze("INSERT INTO ord (id) VALUES (NEXT VALUE FOR seq_ord)");
        assertThat(sequences(g)).isEqualTo("SEQ_ORD");
    }

    static Stream<Arguments> callCases() {
        return Stream.of(
                Arguments.of("CALL sp_close(?, ?)", "SP_CLOSE"),
                Arguments.of("call pkg_order.close_order(#{orderId, mode=IN}, #{result, mode=OUT, jdbcType=VARCHAR})", "PKG_ORDER.CLOSE_ORDER"),
                Arguments.of("{call pkg_order.close_order(?, ?)}", "PKG_ORDER.CLOSE_ORDER"),
                Arguments.of("{ ? = call fn_get_code_nm(?, ?) }", "FN_GET_CODE_NM"),
                Arguments.of("{call shop.pkg_order.close_order(?)}", "SHOP.PKG_ORDER.CLOSE_ORDER"),
                Arguments.of("EXEC sp_daily_sum('20200101')", "SP_DAILY_SUM"),
                Arguments.of("EXECUTE pkg_a.run", "PKG_A.RUN"),
                Arguments.of("BEGIN pkg_order.close_order(1, :out); END;", "PKG_ORDER.CLOSE_ORDER"),
                Arguments.of("begin sp_a; sp_b(1); :ret := fn_c(2); end;", "SP_A,SP_B"),
                Arguments.of("DECLARE v NUMBER; BEGIN sp_a(v); IF v > 0 THEN pkg.b(v); ELSE NULL; END IF; DBMS_OUTPUT.PUT_LINE('x'); COMMIT; END;", "PKG.B,SP_A"),
                Arguments.of("CALL sp_noargs", "SP_NOARGS"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("callCases")
    @DisplayName("F-006-AC7 호출 구문 → statementType=CALL, 호출 대상 (표)")
    void callStatements(String sql, String expectedCalls) {
        ParseResult result = SqlParser.parse(sql);
        assertThat(result.problems()).as("parse problems of: " + sql).isEmpty();
        StatementFacts f = new FactExtractor(new Catalog()).extract(result);
        assertThat(f.statementType()).isEqualTo("CALL");
        assertThat(calls(f)).isEqualTo(expectedCalls);
        assertThat(reads(f)).isEmpty();
    }

    @Test
    @DisplayName("F-006-AC7 익명 블록 안의 DML은 읽기·쓰기로 모이고 종류는 첫 쓰기 구문을 따른다")
    void blockWithDml() {
        StatementFacts f = analyze("BEGIN\n  UPDATE ord SET st = 'C' WHERE id = :id;\n  INSERT INTO hist (id) SELECT o.id FROM ord o WHERE o.id = :id;\n  pkg_log.write('x');\nEND;");
        assertThat(f.statementType()).isEqualTo("UPDATE");
        assertThat(writes(f)).isEqualTo("HIST[INSERT],ORD[UPDATE]");
        assertThat(reads(f)).isEqualTo("ORD");
        assertThat(calls(f)).isEqualTo("PKG_LOG.WRITE");
    }

    @Test
    @DisplayName("F-006-AC7 SQL 식 안의 함수 호출은 카탈로그에 루틴으로 정의된 이름만 호출로 본다")
    void knownFunctionCalls() {
        Catalog c = new Catalog();
        c.addRoutine(null, "FN_GET_CODE_NM");
        c.addPackage("PKG_ORDER");
        c.addRoutine("PKG_ORDER", "GET_AMT");
        StatementFacts f = new FactExtractor(c).extract(SqlParser.parse(
                "SELECT fn_get_code_nm('A', o.st), NVL(pkg_order.get_amt(o.id), 0), shop.pkg_order.get_amt(1), UPPER(o.nm), other_fn(1) FROM ord o"));
        assertThat(f.statementType()).isEqualTo("SELECT");
        assertThat(calls(f)).isEqualTo("FN_GET_CODE_NM,PKG_ORDER.GET_AMT");
    }
}
