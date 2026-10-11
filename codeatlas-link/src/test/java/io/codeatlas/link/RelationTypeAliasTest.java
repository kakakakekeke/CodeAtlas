package io.codeatlas.link;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 테이블 관계 추론(F-018)의 자료형 비교에서 PostgreSQL 타입 별칭을 같은 타입으로 본다(F-019-R35). Oracle·DB2 타입의 비교 결과는 전과 같다.
 */
class RelationTypeAliasTest extends LinkTestBase {

    private static final List<String> KEY = List.of("K");

    /** 자식 {@code CHILD.K}(childType)와 부모 {@code PARENT.K}(parentType, 기본 키)의 조인에서 추론한 관계. */
    private Edge inferred(String childType, String parentType) {
        String child = table("CHILD", "CHILD_ID");
        String parent = table("PARENT", "K");
        column("CHILD", "K", childType);
        column("PARENT", "K", parentType);
        joins(child, parent, KEY, KEY, "s1");

        run(new RelationInferenceLinker());

        return only(child, EdgeKind.REFERENCES);
    }

    @Test
    @DisplayName("F-019-AC33 orders.customer_id bigint = customers.id bigserial 조인과 varchar(10) = character varying(10) 조인에는 typeMismatch가 없고, "
            + "integer = text 조인에는 있다")
    void acceptanceExamples() {
        String orders = table("ORDERS", "ID");
        String customers = table("CUSTOMERS", "ID");
        String a = table("A", "A_ID");
        String b = table("B", "CODE");
        String c = table("C", "C_ID");
        String d = table("D", "N");
        column("ORDERS", "CUSTOMER_ID", "BIGINT");
        column("CUSTOMERS", "ID", "BIGSERIAL");
        column("A", "CODE", "VARCHAR(10)");
        column("B", "CODE", "CHARACTER VARYING(10)");
        column("C", "N", "INTEGER");
        column("D", "N", "TEXT");
        joins(orders, customers, List.of("CUSTOMER_ID"), List.of("ID"), "s1");
        joins(a, b, List.of("CODE"), List.of("CODE"), "s2");
        joins(c, d, List.of("N"), List.of("N"), "s3");

        run(new RelationInferenceLinker());

        assertThat(only(orders, EdgeKind.REFERENCES).attrs()).doesNotContainKey("typeMismatch");
        assertThat(only(a, EdgeKind.REFERENCES).attrs()).doesNotContainKey("typeMismatch");
        assertThat(only(c, EdgeKind.REFERENCES).attr("typeMismatch")).isEqualTo(true);
    }

    @ParameterizedTest(name = "{0} = {1}")
    @DisplayName("F-019-AC33 PostgreSQL 타입 별칭끼리는 같은 타입이다(대소문자, 길이·정밀도, 배열이 아닌 것)")
    @CsvSource(delimiter = '|', value = {
            "serial | integer",
            "bigserial | bigint",
            "smallserial | smallint",
            "serial4 | int4",
            "serial8 | int8",
            "serial2 | int2",
            "int | integer",
            "int4 | integer",
            "int8 | bigint",
            "int2 | smallint",
            "BIGSERIAL | BIGINT",
            "varchar | character varying",
            "varchar(10) | character varying(10)",
            "bool | boolean",
            "timestamptz | timestamp with time zone",
            "TIMESTAMPTZ | TIMESTAMP(6) WITH TIME ZONE",
            "timestamptz | timestamp",
            "decimal | numeric",
            "decimal(12,2) | numeric(12, 2)",
            "float8 | double precision",
            "float4 | real",
            "text | varchar(200)",
            "text | character varying",
            "TEXT | CHAR(10)"})
    void aliasesAreSameType(String childType, String parentType) {
        assertThat(inferred(childType, parentType).attrs()).doesNotContainKey("typeMismatch");
    }

    @ParameterizedTest(name = "{0} = {1}")
    @DisplayName("F-019-AC33 계열이 다른 타입의 조인은 전처럼 typeMismatch=true다")
    @CsvSource(delimiter = '|', value = {
            "integer | text",
            "bigserial | varchar(20)",
            "serial | character varying",
            "bool | integer",
            "boolean | varchar(1)",
            "timestamptz | bigint",
            "int8 | timestamptz",
            "uuid | bigint",
            "uuid | varchar(36)"})
    void differentFamiliesStillMismatch(String childType, String parentType) {
        assertThat(inferred(childType, parentType).attr("typeMismatch")).isEqualTo(true);
    }

    @ParameterizedTest(name = "{0} = {1} → 불일치 {2}")
    @DisplayName("F-019-AC33 기존 Oracle·DB2 타입의 비교 결과는 달라지지 않는다")
    @CsvSource(delimiter = '|', value = {
            "NUMBER(10) | INTEGER | false",
            "NUMBER | DECIMAL(10,2) | false",
            "DECFLOAT | BINARY_DOUBLE | false",
            "VARCHAR2(10) | CHAR(10) | false",
            "VARCHAR2(10) | NVARCHAR2(10) | false",
            "CLOB | VARGRAPHIC(10) | false",
            "DATE | TIMESTAMP(6) | false",
            "TIME | TIMESTAMP | false",
            "VARCHAR2(10) | NUMBER(10) | true",
            "DATE | NUMBER | true",
            "CHAR(8) | DATE | true",
            "RAW(16) | VARCHAR2(32) | true",
            "BLOB | CLOB | true",
            "XML | CLOB | true",
            "RAW(16) | RAW(16) | false"})
    void oracleAndDb2Unchanged(String childType, String parentType, boolean mismatch) {
        Edge edge = inferred(childType, parentType);
        if (mismatch) {
            assertThat(edge.attr("typeMismatch")).isEqualTo(true);
        } else {
            assertThat(edge.attrs()).doesNotContainKey("typeMismatch");
        }
    }
}
