package io.codeatlas.sql.analysis;

import io.codeatlas.sql.parser.ParseResult;
import io.codeatlas.sql.parser.SqlParser;
import java.util.stream.Collectors;

/** 표 기반 테스트용: SQL 조각을 분석해 사실을 비교하기 쉬운 문자열로 바꾼다. */
final class FactsTestSupport {

    private FactsTestSupport() {
    }

    static StatementFacts analyze(String sql) {
        return analyze(sql, new Catalog());
    }

    static StatementFacts analyze(String sql, Catalog catalog) {
        ParseResult result = SqlParser.parse(sql);
        return new FactExtractor(catalog).extract(result);
    }

    /** 읽은 테이블. 이름순, 쉼표 구분. {@code SELECT *}로 읽은 테이블은 뒤에 {@code *}. */
    static String reads(StatementFacts f) {
        return f.tableReads().stream().map(r -> r.table() + (r.star() ? "*" : "")).sorted().collect(Collectors.joining(","));
    }

    /** 쓴 테이블. {@code T[OP]} 이름순. */
    static String writes(StatementFacts f) {
        return f.tableWrites().stream().map(w -> w.table() + "[" + w.op() + "]").sorted().collect(Collectors.joining(","));
    }

    static String readColumns(StatementFacts f) {
        return f.columnReads().stream().map(c -> c.table() + "." + c.column()).sorted().collect(Collectors.joining(","));
    }

    static String writeColumns(StatementFacts f) {
        return f.columnWrites().stream().map(c -> c.table() + "." + c.column()).sorted().collect(Collectors.joining(","));
    }

    /** 조인. {@code A(X,Y)=B(K,L)} 이름순, 세미콜론 구분. */
    static String joins(StatementFacts f) {
        return f.joins().stream()
                .map(j -> j.leftTable() + "(" + String.join(",", j.leftColumns()) + ")=" + j.rightTable() + "(" + String.join(",", j.rightColumns()) + ")")
                .sorted().collect(Collectors.joining(";"));
    }

    static String sequences(StatementFacts f) {
        return f.sequences().stream().map(StatementFacts.SequenceUse::sequence).sorted().collect(Collectors.joining(","));
    }

    static String calls(StatementFacts f) {
        return f.calls().stream().map(c -> String.join(".", c.name())).sorted().collect(Collectors.joining(","));
    }

    /** 스키마: EMP, DEPT, SALGRADE, TB_ORDER, TB_ORDER_ITEM, TB_MEMBER, TB_PRODUCT. */
    static Catalog sampleCatalog() {
        Catalog c = new Catalog();
        c.defineTable("EMP", "EMPNO", "ENAME", "JOB", "MGR", "HIREDATE", "SAL", "COMM", "DEPTNO");
        c.defineTable("DEPT", "DEPTNO", "DNAME", "LOC");
        c.defineTable("SALGRADE", "GRADE", "LOSAL", "HISAL");
        c.defineTable("TB_ORDER", "ORDER_ID", "MEMBER_ID", "ORDER_DT", "ORDER_STATUS", "TOTAL_AMT");
        c.defineTable("TB_ORDER_ITEM", "ORDER_ID", "ITEM_SEQ", "PRODUCT_ID", "QTY", "PRICE");
        c.defineTable("TB_MEMBER", "MEMBER_ID", "MEMBER_NM", "GRADE_CD");
        c.defineTable("TB_PRODUCT", "PRODUCT_ID", "PRODUCT_NM", "PRICE", "CATEGORY_ID");
        return c;
    }
}
