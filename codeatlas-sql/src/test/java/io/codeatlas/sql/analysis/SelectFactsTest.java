package io.codeatlas.sql.analysis;

import static io.codeatlas.sql.analysis.FactsTestSupport.analyze;
import static io.codeatlas.sql.analysis.FactsTestSupport.joins;
import static io.codeatlas.sql.analysis.FactsTestSupport.readColumns;
import static io.codeatlas.sql.analysis.FactsTestSupport.reads;
import static io.codeatlas.sql.analysis.FactsTestSupport.sampleCatalog;
import static io.codeatlas.sql.analysis.FactsTestSupport.sequences;
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

/** SQL 조각 → 테이블, 조인 (표 기반). 카탈로그 없이 돌린다. */
class SelectFactsTest {

    static Stream<Arguments> tableAndJoinCases() {
        return Stream.of(
                // sql, reads, joins
                Arguments.of("SELECT * FROM emp", "EMP*", ""),
                Arguments.of("select e.ename from scott.emp e where e.sal > 100", "EMP", ""),
                Arguments.of("SELECT e.ename, d.dname FROM emp e JOIN dept d ON e.deptno = d.deptno", "DEPT,EMP", "EMP(DEPTNO)=DEPT(DEPTNO)"),
                Arguments.of("SELECT e.ename FROM emp e INNER JOIN dept d ON (e.deptno = d.deptno AND d.loc = 'X')", "DEPT,EMP", "EMP(DEPTNO)=DEPT(DEPTNO)"),
                Arguments.of("SELECT 1 FROM emp e LEFT OUTER JOIN dept d ON e.deptno = d.deptno RIGHT JOIN salgrade s ON e.sal = s.losal FULL OUTER JOIN bonus b ON b.ename = e.ename",
                        "BONUS,DEPT,EMP,SALGRADE", "BONUS(ENAME)=EMP(ENAME);EMP(DEPTNO)=DEPT(DEPTNO);EMP(SAL)=SALGRADE(LOSAL)"),
                Arguments.of("SELECT 1 FROM emp e, dept d WHERE e.deptno = d.deptno AND e.sal > 1", "DEPT,EMP", "EMP(DEPTNO)=DEPT(DEPTNO)"),
                Arguments.of("SELECT 1 FROM emp e, dept d WHERE e.deptno(+) = d.deptno", "DEPT,EMP", "EMP(DEPTNO)=DEPT(DEPTNO)"),
                Arguments.of("SELECT 1 FROM emp e, dept d WHERE d.deptno = e.deptno (+) AND d.loc(+) = 'A'", "DEPT,EMP", "DEPT(DEPTNO)=EMP(DEPTNO)"),
                Arguments.of("SELECT 1 FROM emp JOIN dept USING (deptno)", "DEPT,EMP", "EMP(DEPTNO)=DEPT(DEPTNO)"),
                Arguments.of("SELECT 1 FROM a JOIN b USING (k1, k2)", "A,B", "A(K1,K2)=B(K1,K2)"),
                Arguments.of("SELECT 1 FROM a JOIN b ON a.k1 = b.k1 AND a.k2 = b.k2 AND b.k1 = a.k1", "A,B", "A(K1,K2)=B(K1,K2)"),
                Arguments.of("SELECT 1 FROM emp e, emp m WHERE e.mgr = m.empno", "EMP", "EMP(MGR)=EMP(EMPNO)"),
                // 같은 테이블 쌍이라도 인스턴스 쌍이 다르면 엣지를 나눈다(복합 키로 오인하지 않게)
                Arguments.of("SELECT 1 FROM ord o JOIN code c1 ON o.st = c1.cd JOIN code c2 ON o.tp = c2.cd AND o.grp = c2.grp", "CODE,ORD", "ORD(ST)=CODE(CD);ORD(TP,GRP)=CODE(CD,GRP)"),
                Arguments.of("SELECT 1 FROM a, b WHERE a.id = b.aid UNION ALL SELECT 2 FROM a, b WHERE b.aid = a.id", "A,B", "A(ID)=B(AID)"),
                // 같은 테이블의 같은 컬럼끼리(CTE 둘을 거친 경우 등)는 조인으로 남기지 않는다
                Arguments.of("WITH x AS (SELECT o.mid FROM ord o), y AS (SELECT o.mid, o.id FROM ord o) SELECT 1 FROM x JOIN y ON x.mid = y.mid", "ORD", ""),
                Arguments.of("SELECT 1 FROM cat c, cat p WHERE c.grp = p.grp AND c.pid = p.id", "CAT", "CAT(GRP,PID)=CAT(GRP,ID)"),
                Arguments.of("SELECT 1 FROM a CROSS JOIN b", "A,B", ""),
                Arguments.of("SELECT 1 FROM a NATURAL JOIN b", "A,B", ""),
                // 식이 낀 조건, 상수 조건은 조인이 아니다
                Arguments.of("SELECT 1 FROM a, b WHERE a.x = SUBSTR(b.y, 1, 2) AND a.z = b.w + 1 AND a.c = 'K' AND a.q || 'x' = b.q", "A,B", ""),
                // 다만 컬럼 하나만 감싼 형 변환·정리 함수(UPPER, TRIM)는 그 컬럼의 조인이다(F-006-R29. 전에는 조인이 아니었다)
                Arguments.of("SELECT 1 FROM a, b WHERE a.x = UPPER(b.y) AND a.z = b.w + 1 AND a.c = 'K' AND TRIM(a.q) = b.q", "A,B", "A(X,Q)=B(Y,Q)"),
                Arguments.of("SELECT 1 FROM a, b WHERE a.x <> b.y AND a.z > b.w", "A,B", ""),
                Arguments.of("SELECT 1 FROM a, b WHERE (a.x) = (b.y) OR a.z = b.w", "A,B", "A(X,Z)=B(Y,W)"),
                // 소속을 모르는 비한정 컬럼
                Arguments.of("SELECT 1 FROM a, b WHERE x = y", "A,B", ""),
                Arguments.of("SELECT 1 FROM a, b WHERE a.x = y", "A,B", ""),
                // 같은 인스턴스
                Arguments.of("SELECT 1 FROM a WHERE a.x = a.y", "A", ""),
                // CTE
                Arguments.of("WITH c AS (SELECT id, nm FROM t1 WHERE use_yn = 'Y') SELECT c.nm, t2.v FROM c JOIN t2 ON c.id = t2.t1_id", "T1,T2", "T1(ID)=T2(T1_ID)"),
                Arguments.of("WITH c (k, v) AS (SELECT id, UPPER(nm) FROM t1) SELECT 1 FROM c, t2 WHERE c.k = t2.t1_id AND c.v = t2.nm", "T1,T2", "T1(ID)=T2(T1_ID)"),
                Arguments.of("WITH a AS (SELECT * FROM t1), b AS (SELECT a.id, t2.x FROM a JOIN t2 ON a.id = t2.id) SELECT * FROM b", "T1*,T2", "T1(ID)=T2(ID)"),
                Arguments.of("WITH RECURSIVE r (id, parent_id, lvl) AS (SELECT id, parent_id, 1 FROM cat WHERE parent_id IS NULL UNION ALL SELECT c.id, c.parent_id, r.lvl + 1 FROM cat c JOIN r ON c.parent_id = r.id) SELECT * FROM r", "CAT", ""),
                Arguments.of("WITH r (id, pid) AS (SELECT id, pid FROM cat WHERE pid IS NULL UNION ALL SELECT c.id, c.pid FROM cat c, r WHERE c.pid = r.id) SELECT id FROM r", "CAT", ""),
                // 인라인 뷰
                Arguments.of("SELECT v.id FROM (SELECT o.id, o.mid FROM ord o) v JOIN mem m ON v.mid = m.id", "MEM,ORD", "ORD(MID)=MEM(ID)"),
                Arguments.of("SELECT v.id FROM (SELECT o.id, NVL(o.mid, 0) mid FROM ord o) v JOIN mem m ON v.mid = m.id", "MEM,ORD", ""),
                Arguments.of("SELECT 1 FROM (SELECT o.id AS oid, o.mid AS member_id FROM ord o) v, mem m WHERE v.member_id = m.id", "MEM,ORD", "ORD(MID)=MEM(ID)"),
                Arguments.of("SELECT 1 FROM (SELECT * FROM ord) v, mem m WHERE v.mid = m.id", "MEM,ORD*", "ORD(MID)=MEM(ID)"),
                // 집합 연산인 인라인 뷰와의 조인은 분기마다 잇는다(F-006-R38. 전에는 추적하지 않았다)
                Arguments.of("SELECT 1 FROM (SELECT id FROM a UNION ALL SELECT id FROM b) u JOIN c ON u.id = c.id", "A,B,C", "A(ID)=C(ID);B(ID)=C(ID)"),
                // ROWNUM 페이징 중첩
                Arguments.of("SELECT * FROM (SELECT ROWNUM rn, x.* FROM (SELECT o.order_id, m.member_nm FROM tb_order o, tb_member m WHERE o.member_id = m.member_id ORDER BY o.order_id DESC) x WHERE ROWNUM <= ?) WHERE rn >= ?",
                        "TB_MEMBER,TB_ORDER", "TB_ORDER(MEMBER_ID)=TB_MEMBER(MEMBER_ID)"),
                // 서브쿼리
                Arguments.of("SELECT 1 FROM ord o WHERE EXISTS (SELECT 1 FROM item i WHERE i.ord_id = o.id)", "ITEM,ORD", "ITEM(ORD_ID)=ORD(ID)"),
                Arguments.of("SELECT 1 FROM ord o WHERE NOT EXISTS (SELECT 1 FROM item i WHERE i.ord_id = o.id AND i.qty > 0)", "ITEM,ORD", "ITEM(ORD_ID)=ORD(ID)"),
                Arguments.of("SELECT 1 FROM ord o WHERE o.mid IN (SELECT m.id FROM mem m WHERE m.grade = 'V')", "MEM,ORD", "ORD(MID)=MEM(ID)"),
                Arguments.of("SELECT 1 FROM ord o WHERE o.mid NOT IN (SELECT id FROM mem)", "MEM,ORD", "ORD(MID)=MEM(ID)"),
                Arguments.of("SELECT 1 FROM ord o WHERE (o.a, o.b) IN (SELECT x.a, x.b FROM x)", "ORD,X", "ORD(A,B)=X(A,B)"),
                Arguments.of("SELECT 1 FROM ord o WHERE o.mid IN (SELECT MAX(m.id) FROM mem m)", "MEM,ORD", ""),
                Arguments.of("SELECT 1 FROM ord o WHERE o.mid IN (1, 2, 3)", "ORD", ""),
                Arguments.of("SELECT o.id, (SELECT m.nm FROM mem m WHERE m.id = o.mid) AS nm FROM ord o", "MEM,ORD", "MEM(ID)=ORD(MID)"),
                Arguments.of("SELECT 1 FROM ord o WHERE o.mid = (SELECT m.id FROM mem m WHERE m.login = ?)", "MEM,ORD", "ORD(MID)=MEM(ID)"),
                Arguments.of("SELECT 1 FROM ord o WHERE o.amt > (SELECT AVG(amt) FROM ord)", "ORD", ""),
                Arguments.of("SELECT 1 FROM ord o WHERE o.mid = ANY (SELECT m.id FROM mem m)", "MEM,ORD", "ORD(MID)=MEM(ID)"),
                // 집합 연산
                Arguments.of("SELECT id FROM a UNION SELECT id FROM b UNION ALL SELECT id FROM c MINUS SELECT id FROM d", "A,B,C,D", ""),
                Arguments.of("(SELECT id FROM a) INTERSECT (SELECT id FROM b) EXCEPT ALL SELECT id FROM c ORDER BY 1", "A,B,C", ""),
                // 계층 쿼리
                Arguments.of("SELECT LEVEL, c.id FROM cat c START WITH c.pid IS NULL CONNECT BY PRIOR c.id = c.pid ORDER SIBLINGS BY c.nm", "CAT", "CAT(ID)=CAT(PID)"),
                Arguments.of("SELECT c.id FROM cat c CONNECT BY NOCYCLE c.pid = PRIOR c.id AND LEVEL < 5", "CAT", "CAT(PID)=CAT(ID)"),
                // 분석 함수, CASE, 함수 특수형
                Arguments.of("SELECT e.ename, ROW_NUMBER() OVER (PARTITION BY e.deptno ORDER BY e.sal DESC NULLS LAST) rn, SUM(e.sal) OVER (ORDER BY e.hiredate ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) FROM emp e", "EMP", ""),
                Arguments.of("SELECT MAX(e.sal) KEEP (DENSE_RANK FIRST ORDER BY e.hiredate), LISTAGG(e.ename, ',') WITHIN GROUP (ORDER BY e.ename) FROM emp e GROUP BY e.deptno HAVING COUNT(*) > 1", "EMP", ""),
                Arguments.of("SELECT CASE WHEN e.sal > 100 THEN 'H' WHEN e.sal > 50 THEN 'M' ELSE 'L' END, CASE e.job WHEN 'A' THEN 1 ELSE 0 END, CAST(e.sal AS NUMBER(10,2)), EXTRACT(YEAR FROM e.hiredate), TRIM(BOTH ' ' FROM e.ename), COUNT(DISTINCT e.deptno), DECODE(e.job, 'A', 1, 2), DATE '2020-01-01', INTERVAL '1' DAY FROM emp e", "EMP", ""),
                Arguments.of("SELECT a, COUNT(*) FROM t GROUP BY ROLLUP(a, b), GROUPING SETS ((a), (b), ())", "T", ""),
                Arguments.of("SELECT XMLAGG(XMLELEMENT(e, t.nm || ',') ORDER BY t.nm).EXTRACT('//text()').GETCLOBVAL() FROM t", "T", ""),
                // FOR UPDATE, 힌트, 파티션, DB링크
                Arguments.of("SELECT /*+ FULL(e) */ e.* FROM emp e WHERE e.empno = :id FOR UPDATE OF e.sal NOWAIT", "EMP*", ""),
                Arguments.of("SELECT * FROM sales PARTITION (p2020) s, emp@remote_db r WHERE s.id = r.empno", "EMP*,SALES*", "SALES(ID)=EMP(EMPNO)"),
                Arguments.of("SELECT * FROM hr.emp@link.world e FOR UPDATE SKIP LOCKED", "EMP*", ""),
                // LATERAL, APPLY, TABLE()
                Arguments.of("SELECT 1 FROM dept d, LATERAL (SELECT e.ename FROM emp e WHERE e.deptno = d.deptno) x", "DEPT,EMP", "EMP(DEPTNO)=DEPT(DEPTNO)"),
                Arguments.of("SELECT 1 FROM dept d CROSS APPLY (SELECT e.ename FROM emp e WHERE e.deptno = d.deptno) x OUTER APPLY (SELECT 1 FROM bonus b WHERE b.dno = d.deptno) y", "BONUS,DEPT,EMP", "BONUS(DNO)=DEPT(DEPTNO);EMP(DEPTNO)=DEPT(DEPTNO)"),
                Arguments.of("SELECT t.column_value FROM ord o, TABLE(pkg_util.split(o.tags)) t", "ORD", ""),
                Arguments.of("SELECT * FROM TABLE(fn_rows(1)) f JOIN ord o ON o.id = f.id", "ORD*", ""),
                // PIVOT/UNPIVOT
                Arguments.of("SELECT * FROM (SELECT deptno, job, sal FROM emp) PIVOT (SUM(sal) FOR job IN ('A' AS a, 'B' AS b)) p ORDER BY 1", "EMP", ""),
                Arguments.of("SELECT * FROM sales UNPIVOT (amt FOR q IN (q1, q2)) u", "SALES*", ""),
                // DB2
                Arguments.of("SELECT e.ename FROM emp e WHERE e.deptno = ? ORDER BY e.ename FETCH FIRST 10 ROWS ONLY WITH UR", "EMP", ""),
                Arguments.of("SELECT e.ename FROM emp e ORDER BY 1 OFFSET 10 ROWS FETCH NEXT 5 ROWS ONLY", "EMP", ""),
                Arguments.of("SELECT * FROM emp e FOR READ ONLY WITH CS", "EMP*", ""),
                Arguments.of("SELECT * FROM emp OPTIMIZE FOR 1 ROW", "EMP*", ""),
                Arguments.of("SELECT e.ename FROM emp e FOR FETCH ONLY", "EMP", ""),
                Arguments.of("SELECT CURRENT TIMESTAMP, CURRENT DATE - 7 DAYS, e.hiredate + 1 MONTH FROM emp e WHERE e.hiredate > CURRENT DATE", "EMP", ""),
                Arguments.of("SELECT COALESCE(e.comm, 0), VALUE(e.sal, 0), SUBSTR(e.ename, 1, 3) CONCAT 'x' FROM emp AS e", "EMP", ""),
                Arguments.of("SELECT t.id FROM NEW TABLE (UPDATE ord SET st = 'X' WHERE id = ?) AS t", "", ""),
                // 테이블이 아닌 것
                Arguments.of("SELECT SYSDATE FROM DUAL", "", ""),
                Arguments.of("SELECT 1 FROM sys.dual", "", ""),
                Arguments.of("SELECT CURRENT DATE FROM SYSIBM.SYSDUMMY1", "", ""),
                Arguments.of("SELECT 'FROM x JOIN y' AS txt /* FROM z */ FROM a -- FROM w", "A", ""),
                Arguments.of("SELECT \"From\".\"Col A\" FROM \"My Table\" \"From\"", "My Table", ""),
                Arguments.of("VALUES (1, 'a'), (2, 'b')", "", ""),
                Arguments.of("VALUES NEXT VALUE FOR seq_a", "", ""),
                // 컬럼명으로 쓰인 키워드
                Arguments.of("SELECT t.type, t.date, t.level, t.value, t.name, t.comment, t.key FROM t WHERE t.type = 'A' ORDER BY t.date", "T", ""),
                Arguments.of("SELECT a.id FROM a, b WHERE a.type = b.type AND a.\"KEY\" = b.value", "A,B", "A(TYPE,KEY)=B(TYPE,VALUE)"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tableAndJoinCases")
    @DisplayName("F-006-AC2 F-006-AC5 F-006-AC6 SELECT 조각 → 테이블, 조인 (표)")
    void tablesAndJoins(String sql, String expectedReads, String expectedJoins) {
        ParseResult result = SqlParser.parse(sql);
        assertThat(result.problems()).as("parse problems of: " + sql).isEmpty();
        StatementFacts f = new FactExtractor(new Catalog()).extract(result);
        assertThat(f.statementType()).isIn("SELECT", "UPDATE");
        assertThat(reads(f)).as("reads").isEqualTo(expectedReads);
        assertThat(joins(f)).as("joins").isEqualTo(expectedJoins);
    }

    @Test
    @DisplayName("F-006-AC6 시퀀스는 테이블이 아니라 시퀀스 READS다")
    void sequences_() {
        StatementFacts f = analyze("SELECT seq_order.NEXTVAL, shop.seq_item.currval FROM dual");
        assertThat(reads(f)).isEmpty();
        assertThat(sequences(f)).isEqualTo("SEQ_ITEM,SEQ_ORDER");
        assertThat(f.dummyTable()).isEqualTo("DUAL");

        StatementFacts g = analyze("SELECT NEXT VALUE FOR sch.seq_a, PREVIOUS VALUE FOR seq_b FROM sysibm.sysdummy1");
        assertThat(sequences(g)).isEqualTo("SEQ_A,SEQ_B");
        assertThat(g.dummyTable()).isEqualTo("SYSIBM.SYSDUMMY1");
        assertThat(reads(g)).isEmpty();
    }

    @Test
    @DisplayName("F-006-AC6 FINAL TABLE (INSERT ..)는 안쪽 DML의 쓰기로 기록된다")
    void finalTable() {
        StatementFacts f = analyze("SELECT id FROM FINAL TABLE (INSERT INTO ord (id, nm) VALUES (NEXT VALUE FOR seq_ord, ?))");
        assertThat(writes(f)).isEqualTo("ORD[INSERT]");
        assertThat(reads(f)).isEmpty();
        assertThat(f.statementType()).isEqualTo("INSERT");
    }

    @Test
    @DisplayName("F-006-AC4 별칭으로 한정한 컬럼은 카탈로그 없이도 컬럼 READS가 된다")
    void qualifiedColumns() {
        StatementFacts f = analyze("SELECT e.ename, d.dname, sal FROM emp e, dept d WHERE e.deptno = d.deptno AND d.loc = ?");
        assertThat(readColumns(f)).isEqualTo("DEPT.DEPTNO,DEPT.DNAME,DEPT.LOC,EMP.DEPTNO,EMP.ENAME");
    }

    @Test
    @DisplayName("F-006-AC4 테이블이 하나뿐이면 비한정 컬럼도 그 테이블 컬럼이고 의사 컬럼·함수는 컬럼이 아니다")
    void singleTableColumns() {
        // LEVEL은 계층 쿼리(CONNECT BY)에서만 의사 컬럼이다(F-006-R33). CONNECT BY가 없으면 그 이름의 컬럼이다
        StatementFacts f = analyze("SELECT ename, NVL(comm, 0), ROWNUM, SYSDATE, LEVEL FROM emp WHERE deptno = :d AND ROWNUM <= 10"
                + " CONNECT BY PRIOR empno = mgr ORDER BY sal");
        assertThat(readColumns(f)).isEqualTo("EMP.COMM,EMP.DEPTNO,EMP.EMPNO,EMP.ENAME,EMP.MGR,EMP.SAL");
        StatementFacts flat = analyze("SELECT ename, ROWNUM, SYSDATE, LEVEL FROM emp WHERE deptno = :d");
        assertThat(readColumns(flat)).isEqualTo("EMP.DEPTNO,EMP.ENAME,EMP.LEVEL");
    }

    @Test
    @DisplayName("F-006-AC4 카탈로그로 비한정 컬럼의 소속을 정하고, 카탈로그가 없으면 만들지 않는다")
    void catalogColumns() {
        String sql = "SELECT ename, dname FROM emp, dept WHERE emp.deptno = dept.deptno AND loc = 'A'";
        StatementFacts with = analyze(sql, sampleCatalog());
        assertThat(readColumns(with)).isEqualTo("DEPT.DEPTNO,DEPT.DNAME,DEPT.LOC,EMP.DEPTNO,EMP.ENAME");
        StatementFacts without = analyze(sql);
        assertThat(readColumns(without)).isEqualTo("DEPT.DEPTNO,EMP.DEPTNO");
    }

    @Test
    @DisplayName("F-006-AC4 F-006-AC5 카탈로그가 있으면 비한정 컬럼끼리의 조인도 확정된다 (모호하면 만들지 않는다)")
    void catalogJoins() {
        Catalog c = sampleCatalog();
        StatementFacts f = analyze("SELECT ename FROM emp, salgrade, dept WHERE sal = losal AND emp.deptno = dept.deptno", c);
        assertThat(joins(f)).isEqualTo("EMP(DEPTNO)=DEPT(DEPTNO);EMP(SAL)=SALGRADE(LOSAL)");
        // DEPTNO는 두 테이블에 다 있어 모호하다
        StatementFacts g = analyze("SELECT deptno, ename FROM emp, dept", c);
        assertThat(readColumns(g)).isEqualTo("EMP.ENAME");
        // 상관 서브쿼리: 안쪽 테이블에 없는 컬럼은 바깥 스코프에서 찾는다
        StatementFacts h = analyze("SELECT dname FROM dept WHERE EXISTS (SELECT 1 FROM salgrade WHERE grade = 1 AND loc = 'X')", c);
        assertThat(readColumns(h)).isEqualTo("DEPT.DNAME,DEPT.LOC,SALGRADE.GRADE");
        StatementFacts i = analyze("SELECT dname FROM dept d WHERE EXISTS (SELECT 1 FROM emp WHERE mgr = 1 AND deptno = d.deptno)", c);
        assertThat(joins(i)).isEqualTo("EMP(DEPTNO)=DEPT(DEPTNO)");
    }

    @Test
    @DisplayName("F-006-AC4 서브쿼리 안의 비한정 컬럼은 가장 안쪽 스코프에 테이블이 하나뿐이면 그 테이블 컬럼이다")
    void innermostSingleTable() {
        StatementFacts f = analyze("SELECT o.id FROM ord o WHERE EXISTS (SELECT 1 FROM item WHERE ord_id = o.id)");
        assertThat(readColumns(f)).isEqualTo("ITEM.ORD_ID,ORD.ID");
        assertThat(joins(f)).isEqualTo("ITEM(ORD_ID)=ORD(ID)");
        // 안쪽 스코프에 테이블이 둘이면 확정하지 않는다
        StatementFacts g = analyze("SELECT o.id FROM ord o WHERE EXISTS (SELECT 1 FROM item, opt WHERE ord_id = o.id)");
        assertThat(readColumns(g)).isEqualTo("ORD.ID");
        assertThat(joins(g)).isEmpty();
    }

    @Test
    @DisplayName("F-006-AC4 SELECT *는 컬럼 엣지 없이 star 표시만 하고, 인라인 뷰를 거친 컬럼은 원본 테이블 컬럼으로 읽힌다")
    void starAndDerivedColumns() {
        StatementFacts f = analyze("SELECT v.nm, v.x FROM (SELECT m.nm, m.a + 1 AS x, m.b FROM mem m) v");
        assertThat(readColumns(f)).isEqualTo("MEM.A,MEM.B,MEM.NM");
        StatementFacts g = analyze("SELECT v.nm FROM (SELECT * FROM mem) v");
        assertThat(reads(g)).isEqualTo("MEM*");
        assertThat(readColumns(g)).isEqualTo("MEM.NM");
        StatementFacts h = analyze("SELECT e.*, d.dname FROM emp e, dept d");
        assertThat(reads(h)).isEqualTo("DEPT,EMP*");
    }

    @Test
    @DisplayName("F-006-AC5 시노님으로 정의된 이름: 테이블 엣지는 시노님 이름, 컬럼과 조인은 대상 테이블")
    void synonymResolution() {
        Catalog c = sampleCatalog();
        c.addSynonym("SYN_ORDER", "TB_ORDER");
        StatementFacts f = analyze("SELECT s.order_id, member_nm FROM syn_order s, tb_member m WHERE s.member_id = m.member_id", c);
        assertThat(reads(f)).isEqualTo("SYN_ORDER,TB_MEMBER");
        assertThat(readColumns(f)).isEqualTo("TB_MEMBER.MEMBER_ID,TB_MEMBER.MEMBER_NM,TB_ORDER.MEMBER_ID,TB_ORDER.ORDER_ID");
        assertThat(joins(f)).isEqualTo("TB_ORDER(MEMBER_ID)=TB_MEMBER(MEMBER_ID)");
    }

    @Test
    @DisplayName("F-006-AC2 SELECT INTO(PL/SQL)의 변수 목록은 받아들이고 컬럼으로 보지 않는다")
    void selectInto() {
        ParseResult r = SqlParser.parse("SELECT e.ename, e.sal INTO v_name, :sal FROM emp e WHERE e.empno = :id");
        assertThat(r.problems()).isEmpty();
        StatementFacts f = new FactExtractor(new Catalog()).extract(r);
        assertThat(reads(f)).isEqualTo("EMP");
        assertThat(readColumns(f)).isEqualTo("EMP.EMPNO,EMP.ENAME,EMP.SAL");
        ParseResult b = SqlParser.parse("SELECT e.ename BULK COLLECT INTO l_names FROM emp e");
        assertThat(b.problems()).isEmpty();
    }

    @Test
    @DisplayName("F-006-AC4 지역 변수 이름으로 알려 준 식별자는 컬럼으로 보지 않는다 (F-008용 훅)")
    void variablesAreNotColumns() {
        ParseResult r = SqlParser.parse("SELECT ename FROM emp WHERE empno = p_empno AND deptno = v_dept");
        StatementFacts f = new FactExtractor(new Catalog()).variables(name -> name.startsWith("P_") || name.startsWith("V_")).extract(r);
        assertThat(readColumns(f)).isEqualTo("EMP.DEPTNO,EMP.EMPNO,EMP.ENAME");
    }
}
