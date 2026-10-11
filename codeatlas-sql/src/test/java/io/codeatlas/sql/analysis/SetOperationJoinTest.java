package io.codeatlas.sql.analysis;

import static io.codeatlas.sql.analysis.FactsTestSupport.joins;
import static io.codeatlas.sql.analysis.FactsTestSupport.reads;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * F-006-R30: {@code IN}·비교의 서브쿼리가 집합 연산(UNION [ALL], INTERSECT, MINUS, EXCEPT)이면 각 분기의 선택 컬럼과 조인을 만든다.
 * 집합 연산인 인라인 뷰·CTE와 조인하면 분기마다 원본 컬럼으로 잇는다(F-006-R38).
 */
class SetOperationJoinTest {

    static Stream<Arguments> cases() {
        return Stream.of(
                // sql, reads, joins
                Arguments.of("SELECT A.ID FROM TB_A A WHERE A.X IN (SELECT B.X FROM TB_B B UNION SELECT C.Y FROM TB_C C)",
                        "TB_A,TB_B,TB_C", "TB_A(X)=TB_B(X);TB_A(X)=TB_C(Y)"),
                Arguments.of("SELECT A.ID FROM TB_A A WHERE A.X IN (SELECT X FROM TB_B UNION ALL SELECT Y FROM TB_C)",
                        "TB_A,TB_B,TB_C", "TB_A(X)=TB_B(X);TB_A(X)=TB_C(Y)"),
                // 분기가 셋, 괄호로 묶은 분기
                Arguments.of("SELECT A.ID FROM TB_A A WHERE A.X IN (SELECT B.X FROM TB_B B UNION SELECT C.Y FROM TB_C C "
                        + "UNION ALL SELECT D.Z FROM TB_D D)", "TB_A,TB_B,TB_C,TB_D", "TB_A(X)=TB_B(X);TB_A(X)=TB_C(Y);TB_A(X)=TB_D(Z)"),
                Arguments.of("SELECT A.ID FROM TB_A A WHERE A.X IN ((SELECT B.X FROM TB_B B) UNION (SELECT C.Y FROM TB_C C))",
                        "TB_A,TB_B,TB_C", "TB_A(X)=TB_B(X);TB_A(X)=TB_C(Y)"),
                // 다른 집합 연산자
                Arguments.of("SELECT A.ID FROM TB_A A WHERE A.X IN (SELECT B.X FROM TB_B B INTERSECT SELECT C.Y FROM TB_C C)",
                        "TB_A,TB_B,TB_C", "TB_A(X)=TB_B(X);TB_A(X)=TB_C(Y)"),
                Arguments.of("SELECT A.ID FROM TB_A A WHERE A.X IN (SELECT B.X FROM TB_B B MINUS SELECT C.Y FROM TB_C C)",
                        "TB_A,TB_B,TB_C", "TB_A(X)=TB_B(X);TB_A(X)=TB_C(Y)"),
                Arguments.of("SELECT A.ID FROM TB_A A WHERE A.X IN (SELECT B.X FROM TB_B B EXCEPT SELECT C.Y FROM TB_C C)",
                        "TB_A,TB_B,TB_C", "TB_A(X)=TB_B(X);TB_A(X)=TB_C(Y)"),
                // 튜플 IN: 분기마다 위치별로
                Arguments.of("SELECT A.ID FROM TB_A A WHERE (A.K1, A.K2) IN (SELECT B.K1, B.K2 FROM TB_B B UNION SELECT C.P1, C.P2 FROM TB_C C)",
                        "TB_A,TB_B,TB_C", "TB_A(K1,K2)=TB_B(K1,K2);TB_A(K1,K2)=TB_C(P1,P2)"),
                // 스칼라 서브쿼리 등치, = ANY
                Arguments.of("SELECT A.ID FROM TB_A A WHERE A.X = (SELECT B.X FROM TB_B B WHERE B.ID = 1 UNION SELECT C.Y FROM TB_C C WHERE C.ID = 1)",
                        "TB_A,TB_B,TB_C", "TB_A(X)=TB_B(X);TB_A(X)=TB_C(Y)"),
                Arguments.of("SELECT A.ID FROM TB_A A WHERE A.X = ANY (SELECT B.X FROM TB_B B UNION SELECT C.Y FROM TB_C C)",
                        "TB_A,TB_B,TB_C", "TB_A(X)=TB_B(X);TB_A(X)=TB_C(Y)"),
                // 단일 컬럼 NOT IN은 전과 같이 조인이다
                Arguments.of("SELECT A.ID FROM TB_A A WHERE A.X NOT IN (SELECT B.X FROM TB_B B UNION SELECT C.Y FROM TB_C C)",
                        "TB_A,TB_B,TB_C", "TB_A(X)=TB_B(X);TB_A(X)=TB_C(Y)"),
                // 한 분기의 선택 항목이 식·리터럴이면 그 분기만 빠진다
                Arguments.of("SELECT A.ID FROM TB_A A WHERE A.X IN (SELECT B.X FROM TB_B B UNION SELECT 'N' FROM DUAL "
                        + "UNION SELECT C.Y || 'A' FROM TB_C C)", "TB_A,TB_B,TB_C", "TB_A(X)=TB_B(X)"),
                // 분기의 컬럼 수가 왼쪽과 다르면 그 분기는 만들지 않는다
                Arguments.of("SELECT A.ID FROM TB_A A WHERE A.X IN (SELECT B.X FROM TB_B B UNION SELECT C.Y, C.Z FROM TB_C C)",
                        "TB_A,TB_B,TB_C", "TB_A(X)=TB_B(X)"),
                // 분기 안쪽의 조인은 그대로 있다
                Arguments.of("SELECT A.ID FROM TB_A A WHERE A.X IN (SELECT B.X FROM TB_B B, TB_D D WHERE B.D_ID = D.ID UNION SELECT C.Y FROM TB_C C)",
                        "TB_A,TB_B,TB_C,TB_D", "TB_A(X)=TB_B(X);TB_A(X)=TB_C(Y);TB_B(D_ID)=TB_D(ID)"),
                // 튜플 NOT IN (서브쿼리)는 집합 연산이어도 만들지 않는다(F-006-R20)
                Arguments.of("SELECT A.ID FROM TB_A A WHERE (A.K1, A.K2) NOT IN (SELECT B.K1, B.K2 FROM TB_B B UNION SELECT C.P1, C.P2 FROM TB_C C)",
                        "TB_A,TB_B,TB_C", ""),
                // 집합 연산인 인라인 뷰와 조인하면 분기마다 원본 컬럼으로 잇는다(F-006-R38. 전에는 추적하지 않았다)
                Arguments.of("SELECT A.ID FROM TB_A A JOIN (SELECT B.X FROM TB_B B UNION SELECT C.Y FROM TB_C C) V ON A.X = V.X",
                        "TB_A,TB_B,TB_C", "TB_A(X)=TB_B(X);TB_A(X)=TB_C(Y)"),
                // 집합 연산인 CTE를 다시 고른 서브쿼리는 전과 같이 원본으로 추적하지 않는다
                Arguments.of("WITH V AS (SELECT B.X FROM TB_B B UNION SELECT C.Y FROM TB_C C) SELECT A.ID FROM TB_A A WHERE A.X IN (SELECT V.X FROM V)",
                        "TB_A,TB_B,TB_C", ""));
    }

    static Stream<Arguments> unionViews() {
        return Stream.of(
                Arguments.of("SELECT A.K FROM TB_A A JOIN (SELECT K FROM T1 UNION ALL SELECT K FROM T2) U ON A.K = U.K",
                        "T1,T2,TB_A", "TB_A(K)=T1(K);TB_A(K)=T2(K)"),
                // 선택 목록의 별칭과 위치로 분기의 컬럼을 맞춘다. WHERE의 등치와 (+)도 같다
                Arguments.of("SELECT A.ID FROM TB_A A, (SELECT B.ID AS REF_ID, B.NM FROM TB_B B UNION SELECT C.C_ID, C.NM FROM TB_C C) U "
                        + "WHERE A.REF_ID = U.REF_ID(+)", "TB_A,TB_B,TB_C", "TB_A(REF_ID)=TB_B(ID);TB_A(REF_ID)=TB_C(C_ID)"),
                // 선택 항목이 단순 컬럼이 아닌 분기는 빠진다
                Arguments.of("SELECT 1 FROM TB_A A JOIN (SELECT B.ID FROM TB_B B UNION ALL SELECT TO_NUMBER(C.CD) FROM TB_C C UNION ALL SELECT 0 FROM DUAL) U "
                        + "ON U.ID = A.B_ID", "TB_A,TB_B,TB_C", "TB_B(ID)=TB_A(B_ID)"),
                // CTE로 정의한 집합 연산
                Arguments.of("WITH U AS (SELECT B.ID FROM TB_B B UNION ALL SELECT C.ID FROM TB_C C) SELECT A.ID FROM TB_A A JOIN U ON U.ID = A.REF_ID",
                        "TB_A,TB_B,TB_C", "TB_B(ID)=TB_A(REF_ID);TB_C(ID)=TB_A(REF_ID)"),
                // 두 집합 연산 뷰끼리의 조인: 분기 쌍마다
                Arguments.of("SELECT 1 FROM (SELECT K FROM T1 UNION SELECT K FROM T2) U JOIN (SELECT K FROM T3 UNION SELECT K FROM T4) V ON U.K = V.K",
                        "T1,T2,T3,T4", "T1(K)=T3(K);T1(K)=T4(K);T2(K)=T3(K);T2(K)=T4(K)"),
                // 분기의 컬럼 수가 다르거나 * 이면 그 분기는 건너뛴다
                Arguments.of("SELECT 1 FROM TB_A A JOIN (SELECT * FROM T1 UNION ALL SELECT K FROM T2) U ON A.K = U.K", "T1,T2,TB_A", ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unionViews")
    @DisplayName("F-006-AC38 집합 연산인 인라인 뷰·CTE와의 조인: 분기마다 원본 테이블 컬럼으로 JOINS를 만든다(선택 항목이 단순 컬럼일 때) (표)")
    void joinThroughUnionView(String sql, String expectedReads, String expectedJoins) {
        StatementFacts f = FactsTestSupport.analyze(sql);

        assertThat(reads(f).replace("*", "")).as("reads of [%s]", sql).isEqualTo(expectedReads);
        assertThat(joins(f)).as("joins of [%s]", sql).isEqualTo(expectedJoins);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("F-006-AC30 IN·비교의 서브쿼리가 집합 연산이면 분기마다 선택 컬럼과 JOINS를 만든다 (표)")
    void setOperationSubquery(String sql, String expectedReads, String expectedJoins) {
        StatementFacts f = FactsTestSupport.analyze(sql);

        assertThat(reads(f)).as("reads of [%s]", sql).isEqualTo(expectedReads);
        assertThat(joins(f)).as("joins of [%s]", sql).isEqualTo(expectedJoins);
    }
}
