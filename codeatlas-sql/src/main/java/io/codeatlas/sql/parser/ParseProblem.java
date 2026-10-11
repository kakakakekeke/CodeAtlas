package io.codeatlas.sql.parser;

import io.codeatlas.sql.ast.Pos;

/**
 * 파서가 복구하며 지나간 문제.
 *
 * @param minor true면 MyBatis 전개 잔재에서 흔한 가벼운 문제(빠진 피연산자, 끝에 남은 쉼표, 빈 목록)
 */
public record ParseProblem(String message, Pos pos, boolean minor) {
}
