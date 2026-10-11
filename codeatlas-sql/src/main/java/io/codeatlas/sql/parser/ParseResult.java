package io.codeatlas.sql.parser;

import io.codeatlas.sql.ast.Pos;
import io.codeatlas.sql.ast.QName;
import io.codeatlas.sql.ast.Statement;
import java.util.List;

/**
 * 구문 하나를 파싱한 결과.
 *
 * @param statement 구문 트리. 해석하지 못하면 {@link Statement.Unparsed}
 * @param problems 복구하며 지나간 문제
 * @param salvagedReads 구문 뒤쪽에 해석하지 못한 토큰이 남았을 때 FROM/JOIN 뒤에서 건진 테이블 이름
 * @param pos 구문의 범위
 */
public record ParseResult(Statement statement, List<ParseProblem> problems, List<QName> salvagedReads, Pos pos) {

    public ParseResult {
        problems = List.copyOf(problems);
        salvagedReads = List.copyOf(salvagedReads);
    }

    public boolean hasMajorProblems() {
        return problems.stream().anyMatch(p -> !p.minor());
    }

    public boolean hasProblems() {
        return !problems.isEmpty();
    }
}
