package io.codeatlas.sql.analysis;

import static io.codeatlas.sql.analysis.FactsTestSupport.joins;
import static io.codeatlas.sql.analysis.FactsTestSupport.readColumns;
import static io.codeatlas.sql.analysis.FactsTestSupport.reads;
import static io.codeatlas.sql.analysis.FactsTestSupport.writeColumns;
import static io.codeatlas.sql.analysis.FactsTestSupport.writes;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.sql.ast.Statement;
import io.codeatlas.sql.parser.ParseResult;
import io.codeatlas.sql.parser.SqlParser;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 공개 프로젝트에 돌려 찾은 문제의 최소 재현(식별자는 바꿨다). 구문 하나의 파싱과 사실 추출 수준. */
class CorpusFindingsTest {

    private static StatementFacts facts(String sql) {
        return FactsTestSupport.analyze(sql);
    }

    private static StatementFacts clean(String sql) {
        ParseResult r = SqlParser.parse(sql);
        assertThat(r.problems()).as("problems of [%s]", sql).isEmpty();
        return new FactExtractor(new Catalog()).extract(r);
    }

    // ------------------------------------------------------------------ B2: 연달아 든 구문

    @Test
    @DisplayName("F-006-AC16 한 텍스트에 SELECT 둘이 이어지면 따로 파싱해 합치고, 둘째 구문의 컬럼이 테이블로 둔갑하지 않는다")
    void consecutiveSelects() {
        String sql = "SELECT MBR_ID AS ID, MBR_NM AS NAME FROM TB_GNR WHERE MBR_ID = ? "
                + "SELECT ENT_ID AS ID, CO_NM AS NAME, BIZ_NO AS NO FROM TB_ENT WHERE ENT_ID = ?";
        ParseResult r = SqlParser.parse(sql);
        assertThat(r.statement()).isInstanceOf(Statement.Sequence.class);
        assertThat(((Statement.Sequence) r.statement()).statements()).hasSize(2).allMatch(s -> s instanceof Statement.Select);
        assertThat(r.problems()).isEmpty();
        StatementFacts f = new FactExtractor(new Catalog()).extract(r);
        assertThat(reads(f)).isEqualTo("TB_ENT,TB_GNR");
        assertThat(readColumns(f)).isEqualTo("TB_ENT.BIZ_NO,TB_ENT.CO_NM,TB_ENT.ENT_ID,TB_GNR.MBR_ID,TB_GNR.MBR_NM");
        assertThat(f.statementType()).isEqualTo("SELECT");
    }

    @Test
    @DisplayName("F-006-AC16 UPDATE 셋이 종결자 없이 이어져도 셋 다 잡힌다")
    void consecutiveUpdates() {
        StatementFacts f = clean("UPDATE TB_A SET ST = 'X' WHERE ID = ? UPDATE TB_B SET ST = 'X' WHERE A_ID = ? "
                + "UPDATE TB_C SET ST = 'X', DT = SYSDATE WHERE A_ID = ?");
        assertThat(writes(f)).isEqualTo("TB_A[UPDATE],TB_B[UPDATE],TB_C[UPDATE]");
        assertThat(writeColumns(f)).isEqualTo("TB_A.ST,TB_B.ST,TB_C.DT,TB_C.ST");
        assertThat(f.statementType()).isEqualTo("UPDATE");
    }

    static Stream<Arguments> sequences() {
        return Stream.of(
                // sql, reads, writes
                Arguments.of("DELETE FROM TB_A WHERE ID = ? DELETE FROM TB_B WHERE A_ID = ?", "", "TB_A[DELETE],TB_B[DELETE]"),
                Arguments.of("INSERT INTO TB_A (ID) VALUES (?) INSERT INTO TB_B (ID, NM) VALUES (?, ?)", "", "TB_A[INSERT],TB_B[INSERT]"),
                Arguments.of("INSERT INTO TB_A (ID) VALUES (?) SELECT MAX(ID) FROM TB_A", "TB_A", "TB_A[INSERT]"),
                Arguments.of("SELECT ID FROM TB_A WHERE X = ? ORDER BY ID SELECT ID FROM TB_B", "TB_A,TB_B", ""),
                Arguments.of("SELECT ID FROM TB_A WHERE X = ? UPDATE TB_B SET ST = 1 WHERE ID = ? DELETE FROM TB_C WHERE ID = ?",
                        "TB_A", "TB_B[UPDATE],TB_C[DELETE]"),
                Arguments.of("SELECT ID FROM TB_A; SELECT ID FROM TB_B;", "TB_A,TB_B", ""),
                Arguments.of("UPDATE TB_A SET ST = 1 WHERE ID = ? WITH C AS (SELECT ID FROM TB_B) SELECT ID FROM C", "TB_B", "TB_A[UPDATE]"),
                Arguments.of("SELECT ID FROM TB_A WHERE X = ? CALL PKG.PROC(?)", "TB_A", ""),
                Arguments.of("MERGE INTO TB_A D USING TB_B S ON (D.ID = S.ID) WHEN MATCHED THEN UPDATE SET D.V = S.V DELETE WHERE D.V IS NULL "
                        + "UPDATE TB_C SET X = 1", "TB_B", "TB_A[MERGE],TB_C[UPDATE]"),
                // 구문 안의 키워드는 끊지 않는다
                Arguments.of("INSERT INTO TB_A (ID) SELECT ID FROM TB_B", "TB_B", "TB_A[INSERT]"),
                Arguments.of("SELECT ID FROM TB_A UNION ALL SELECT ID FROM TB_B", "TB_A,TB_B", ""),
                Arguments.of("SELECT ID FROM TB_A WHERE ID IN (SELECT A_ID FROM TB_B) FOR UPDATE", "TB_A,TB_B", ""),
                Arguments.of("SELECT ID FROM TB_A WITH UR", "TB_A", ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sequences")
    @DisplayName("F-006-AC16 구문 경계에서 잘라 같은 주인에 합친다 (표)")
    void sequenceTable(String sql, String expectedReads, String expectedWrites) {
        StatementFacts f = clean(sql);
        assertThat(reads(f)).isEqualTo(expectedReads);
        assertThat(writes(f)).isEqualTo(expectedWrites);
    }

    @Test
    @DisplayName("F-006-AC16 최상위 SELECT 뒤에 남은 토큰의 쉼표를 FROM 목록으로 잇지 않는다")
    void commaAfterGarbageIsNotFromList() {
        // 둘째 SELECT 키워드가 없는 조각: 예전에는 쉼표 뒤의 CO_NM, BIZ_NO가 테이블이 됐다
        ParseResult r = SqlParser.parse("SELECT MBR_ID FROM TB_GNR WHERE MBR_ID = ? ENT_ID AS ID, CO_NM AS NAME, BIZ_NO AS NO FROM TB_ENT");
        assertThat(r.problems()).isNotEmpty();
        StatementFacts f = new FactExtractor(new Catalog()).extract(r);
        assertThat(reads(f)).isEqualTo("TB_ENT,TB_GNR");
    }

    @Test
    @DisplayName("F-006-AC16 복구하며 건진 이름은 FROM/JOIN 키워드 바로 뒤의 것만 테이블로 인정한다")
    void salvageOnlyAfterKeyword() {
        // 해석하지 못한 꼬리의 FROM 목록: 첫 이름만 확실하다(쉼표 뒤는 별칭·컬럼일 수 있다)
        ParseResult r = SqlParser.parse("UPDATE TB_A SET X = 1 WHERE ID = 2 @@ FROM TB_B B, COL_X, COL_Y JOIN TB_C C ON 1 = 1");
        assertThat(r.problems()).isNotEmpty();
        assertThat(r.salvagedReads()).extracting(q -> q.name()).containsExactly("TB_B", "TB_C");
        StatementFacts f = new FactExtractor(new Catalog()).extract(r);
        assertThat(reads(f)).isEqualTo("TB_B,TB_C");
        assertThat(writes(f)).isEqualTo("TB_A[UPDATE]");
    }

    // ------------------------------------------------------------------ B24: 별칭이 앞에 오는 DELETE/UPDATE

    @Test
    @DisplayName("F-006-AC21 DELETE 별칭 FROM 테이블 별칭: 별칭은 테이블이 아니고 가리키는 테이블만 WRITES")
    void aliasFirstDelete() {
        StatementFacts f = clean("DELETE RNS FROM TB_RECIPE_STEP RNS WHERE RNS.RECIPE_ID = ?");
        assertThat(writes(f)).isEqualTo("TB_RECIPE_STEP[DELETE]");
        assertThat(reads(f)).isEmpty();
        assertThat(readColumns(f)).isEqualTo("TB_RECIPE_STEP.RECIPE_ID");

        f = clean("DELETE D FROM TB_DTL D INNER JOIN TB_MST M ON D.MST_ID = M.MST_ID WHERE M.ST = 'X'");
        assertThat(writes(f)).isEqualTo("TB_DTL[DELETE]");
        assertThat(reads(f)).isEqualTo("TB_MST");
        assertThat(joins(f)).isEqualTo("TB_DTL(MST_ID)=TB_MST(MST_ID)");

        f = clean("DELETE TB_DTL FROM TB_DTL JOIN TB_MST M ON TB_DTL.MST_ID = M.MST_ID");
        assertThat(writes(f)).isEqualTo("TB_DTL[DELETE]");
        assertThat(reads(f)).isEqualTo("TB_MST");

        // 보통의 DELETE는 그대로다
        assertThat(writes(clean("DELETE FROM TB_A A WHERE A.ID = ?"))).isEqualTo("TB_A[DELETE]");
        assertThat(writes(clean("DELETE TB_A WHERE ID = ?"))).isEqualTo("TB_A[DELETE]");
        assertThat(writes(clean("DELETE TB_A A WHERE A.ID = ?"))).isEqualTo("TB_A[DELETE]");
    }

    @Test
    @DisplayName("F-006-AC21 UPDATE 별칭 SET .. FROM 테이블 별칭: 별칭이 가리키는 테이블이 대상이다")
    void aliasFirstUpdate() {
        StatementFacts f = clean("UPDATE D SET D.ST = M.ST FROM TB_DTL D JOIN TB_MST M ON D.MST_ID = M.MST_ID WHERE M.ID = ?");
        assertThat(writes(f)).isEqualTo("TB_DTL[UPDATE]");
        assertThat(reads(f)).isEqualTo("TB_MST");
        assertThat(writeColumns(f)).isEqualTo("TB_DTL.ST");
        assertThat(joins(f)).isEqualTo("TB_DTL(MST_ID)=TB_MST(MST_ID)");
        // FROM에 그 이름의 별칭이 없으면 이름 그대로 테이블이다
        f = clean("UPDATE TB_A SET X = S.X FROM TB_S S WHERE TB_A.ID = S.ID");
        assertThat(writes(f)).isEqualTo("TB_A[UPDATE]");
        assertThat(reads(f)).isEqualTo("TB_S");
    }

    // ------------------------------------------------------------------ B12: MBG Example_Where_Clause

    static Stream<String> mbgClauses() {
        return Stream.of(
                "select id, nm from tb_b WHERE ( __DYNAMIC__ ? )",
                "select id, nm from tb_b WHERE ( __DYNAMIC__ )",
                "select id, nm from tb_b WHERE ( __DYNAMIC__ and __DYNAMIC__ ? and __DYNAMIC__ ? and ? and __DYNAMIC__ ( ? , ? ) )",
                "select id, nm from tb_b WHERE ( __DYNAMIC__ ? ) or ( __DYNAMIC__ ? and ? )",
                "select count(*) from tb_b WHERE ( __DYNAMIC__ #{criterion.value} and __DYNAMIC__ #{criterion.value} and #{criterion.secondValue} )",
                "select id from tb_b WHERE ( __DYNAMIC__ 'A' and __DYNAMIC__ 10 ) order by __DYNAMIC__",
                "update tb_b set nm = ? WHERE ( __DYNAMIC__ ? and __DYNAMIC__ ( ? ) )",
                "delete from tb_b WHERE ( __DYNAMIC__ ? and ? )");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mbgClauses")
    @DisplayName("F-006-AC19 조건 자리의 __DYNAMIC__ 뒤 피연산자는 불투명한 조건 하나로 삼키고 문제를 남기지 않는다")
    void mbgWhereClause(String sql) {
        ParseResult r = SqlParser.parse(sql);
        assertThat(r.problems()).as("problems").isEmpty();
        StatementFacts f = new FactExtractor(new Catalog()).extract(r);
        assertThat(reads(f) + writes(f).replaceAll("\\[.*", "")).isEqualTo("TB_B");
        assertThat(f.dynamic()).isTrue();
        assertThat(f.dynamicTables()).isEmpty();
    }

    // ------------------------------------------------------------------ B13: 반조인, 스칼라 서브쿼리

    static Stream<Arguments> antiJoins() {
        return Stream.of(
                // sql, reads, joins
                // 튜플 NOT IN: 왼쪽 튜플이 두 테이블에 걸쳐 있다. 예전에는 TB_BBS_USE(TRGT_ID)=TB_CLB_USR(USR_ID)가 생겼다
                Arguments.of("SELECT A.BBS_ID FROM TB_BBS A, TB_CLB_USR F WHERE A.CLB_ID = F.CLB_ID "
                        + "AND (A.BBS_ID, F.USR_ID) NOT IN (SELECT BBS_ID, TRGT_ID FROM TB_BBS_USE)",
                        "TB_BBS,TB_BBS_USE,TB_CLB_USR", "TB_BBS(CLB_ID)=TB_CLB_USR(CLB_ID)"),
                Arguments.of("SELECT A.ID FROM TB_A A WHERE (A.K1, A.K2) NOT IN (SELECT B.K1, B.K2 FROM TB_B B)", "TB_A,TB_B", ""),
                Arguments.of("DELETE FROM TB_A A WHERE (A.K1, A.K2) NOT IN (SELECT B.K1, B.K2 FROM TB_B B, TB_C C WHERE B.C_ID = C.ID)",
                        "TB_B,TB_C", "TB_B(C_ID)=TB_C(ID)"),
                // 긍정 IN/EXISTS는 전과 같다
                Arguments.of("SELECT A.ID FROM TB_A A WHERE (A.K1, A.K2) IN (SELECT B.K1, B.K2 FROM TB_B B)", "TB_A,TB_B", "TB_A(K1,K2)=TB_B(K1,K2)"),
                Arguments.of("SELECT A.ID FROM TB_A A WHERE A.ID IN (SELECT B.A_ID FROM TB_B B)", "TB_A,TB_B", "TB_A(ID)=TB_B(A_ID)"),
                Arguments.of("SELECT A.ID FROM TB_A A WHERE EXISTS (SELECT 1 FROM TB_B B WHERE B.A_ID = A.ID)", "TB_A,TB_B", "TB_B(A_ID)=TB_A(ID)"),
                // 단일 컬럼 NOT IN과 NOT EXISTS의 상관 조건은 키 대응이 분명해 전과 같이 조인이다
                // (샘플 정답 OrderMapper.deleteOrder의 TB_PAYMENT↔TB_ORDER가 NOT EXISTS 상관 조건이다)
                Arguments.of("SELECT A.ID FROM TB_A A WHERE A.ID NOT IN (SELECT B.A_ID FROM TB_B B)", "TB_A,TB_B", "TB_A(ID)=TB_B(A_ID)"),
                Arguments.of("DELETE FROM TB_ORD WHERE ORD_ID = ? AND NOT EXISTS (SELECT 1 FROM TB_PAY P WHERE P.ORD_ID = TB_ORD.ORD_ID AND P.ST = 'DONE')",
                        "TB_PAY", "TB_PAY(ORD_ID)=TB_ORD(ORD_ID)"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("antiJoins")
    @DisplayName("F-006-AC20 튜플 NOT IN (서브쿼리) 반조인은 컬럼 쌍을 JOINS로 만들지 않는다 (표)")
    void antiJoinTable(String sql, String expectedReads, String expectedJoins) {
        StatementFacts f = clean(sql);
        assertThat(reads(f)).isEqualTo(expectedReads);
        assertThat(joins(f)).isEqualTo(expectedJoins);
    }

    @Test
    @DisplayName("F-006-AC20 스칼라 서브쿼리의 등치는 서브쿼리 테이블과 바깥 테이블만 잇고 바깥의 형제 테이블끼리 잇지 않는다")
    void scalarSubqueryJoinsOnlySubqueryTable() {
        StatementFacts f = clean("SELECT B.MNU_NO, (SELECT C.URL FROM TB_PGM C WHERE B.PGM_NM = C.PGM_NM) AS CHK_URL "
                + "FROM TB_MNU_CRT A, TB_MNU B WHERE A.MNU_NO = B.MNU_NO "
                + "AND A.AUTH_CD = (SELECT C.AUTH_CD FROM TB_USR_AUTH C WHERE C.USR_ID = ?) ORDER BY MNU_ORDR");
        assertThat(joins(f)).isEqualTo("TB_MNU(PGM_NM)=TB_PGM(PGM_NM);TB_MNU_CRT(AUTH_CD)=TB_USR_AUTH(AUTH_CD);TB_MNU_CRT(MNU_NO)=TB_MNU(MNU_NO)");
        // 서브쿼리 안에서 바깥 테이블 둘을 각각 참조해도 바깥 둘끼리는 잇지 않는다
        f = clean("SELECT A.ID, (SELECT C.NM FROM TB_C C WHERE C.A_ID = A.ID AND C.B_ID = B.ID) FROM TB_A A, TB_B B");
        assertThat(joins(f)).isEqualTo("TB_C(A_ID)=TB_A(ID);TB_C(B_ID)=TB_B(ID)");
    }

    // ------------------------------------------------------------------ 방언 관용

    static Stream<Arguments> foreignDialect() {
        return Stream.of(
                // sql, reads, writes
                Arguments.of("SELECT `id`, `user_name` FROM `sys_user` WHERE `del_flag` = '0'", "SYS_USER", ""),
                Arguments.of("INSERT INTO `sys_user` (`id`, `key`, `order`) VALUES (1, 'a', 2)", "", "SYS_USER[INSERT]"),
                Arguments.of("SELECT a.`key` FROM `db1`.`sys_dict` a JOIN `sys_user` u ON u.`id` = a.`create_by`", "SYS_DICT,SYS_USER", ""),
                Arguments.of("SELECT A.ID FROM TB_A A WHERE A.DT::date = ?::date AND A.AMT::numeric(10,2) > 0", "TB_A", ""),
                Arguments.of("SELECT (A.X || A.Y)::varchar AS K, CAST(A.Z AS int)::text FROM TB_A A WHERE A.TS >= now()::timestamp", "TB_A", ""),
                Arguments.of("SELECT A.ID FROM TB_A A WHERE A.CD = ANY (?::varchar[]) AND A.ST::character varying = 'A'", "TB_A", ""),
                Arguments.of("SELECT A.ID FROM TB_A A WHERE A.TS::timestamp without time zone < ? ORDER BY A.ID::int DESC", "TB_A", ""),
                Arguments.of("SELECT A.ID FROM TB_A A ORDER BY A.ID LIMIT ? OFFSET ?", "TB_A", ""),
                Arguments.of("SELECT A.ID FROM TB_A A ORDER BY A.ID LIMIT #{offset}, #{limit}", "TB_A", ""),
                Arguments.of("SELECT A.ID FROM TB_A A LIMIT 0, 4", "TB_A", ""),
                Arguments.of("SELECT A.ID FROM TB_A A WHERE A.X = 1 LIMIT 1", "TB_A", ""),
                Arguments.of("SELECT * FROM (SELECT A.ID FROM TB_A A ORDER BY A.ID LIMIT 10 OFFSET 20) T", "TB_A", ""),
                Arguments.of("SELECT A.ID FROM TB_A A LIMIT 1 FOR UPDATE", "TB_A", ""),
                Arguments.of("UPDATE TB_A SET X = 1 WHERE ID = ? LIMIT 1", "", "TB_A[UPDATE]"),
                Arguments.of("DELETE FROM TB_A WHERE ID = ? LIMIT 1", "", "TB_A[DELETE]"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("foreignDialect")
    @DisplayName("F-006-AC18 범위 밖 방언의 흔한 문법(백틱, ::캐스트, LIMIT)을 문제 없이 읽는다 (표)")
    void foreignDialectTable(String sql, String expectedReads, String expectedWrites) {
        StatementFacts f = clean(sql);
        assertThat(reads(f)).isEqualTo(expectedReads);
        assertThat(writes(f)).isEqualTo(expectedWrites);
    }

    @Test
    @DisplayName("F-006-AC18 백틱 식별자는 키워드가 아니고 이름은 대문자로 정규화한다")
    void backtickColumns() {
        StatementFacts f = clean("SELECT a.`key`, a.`order` FROM `sys_dict` a WHERE a.`from` = ?");
        assertThat(readColumns(f)).isEqualTo("SYS_DICT.FROM,SYS_DICT.KEY,SYS_DICT.ORDER");
        f = clean("INSERT INTO `sys_dict` (`id`, `group`) VALUES (?, ?)");
        assertThat(writeColumns(f)).isEqualTo("SYS_DICT.GROUP,SYS_DICT.ID");
    }
}
