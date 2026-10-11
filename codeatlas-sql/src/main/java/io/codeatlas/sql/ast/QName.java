package io.codeatlas.sql.ast;

import io.codeatlas.core.model.DbName;
import io.codeatlas.sql.lexer.Token;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 점으로 이은 이름({@code schema.table.column}, {@code pkg.proc}). 조각은 원문 그대로(따옴표 포함) 들고 있고
 * 정규화한 값은 메서드로 얻는다. {@code dbLink}는 {@code @link} 부분이고 없으면 null이다.
 */
public record QName(List<String> rawParts, String dbLink, Pos pos) {

    public QName {
        rawParts = List.copyOf(rawParts);
        pos = pos == null ? Pos.NONE : pos;
    }

    public static QName of(String... rawParts) {
        return new QName(List.of(rawParts), null, Pos.NONE);
    }

    public int size() {
        return rawParts.size();
    }

    /** 마지막 조각의 원문(따옴표 포함). */
    public String rawName() {
        return rawParts.get(rawParts.size() - 1);
    }

    /** 마지막 조각을 정규화한 이름(따옴표가 없으면 대문자). */
    public String name() {
        return DbName.normalize(rawName());
    }

    /** 끝에서 n번째(0이 마지막) 조각을 정규화한 값. 없으면 null. */
    public String part(int fromEnd) {
        int i = rawParts.size() - 1 - fromEnd;
        return i < 0 ? null : DbName.normalize(rawParts.get(i));
    }

    /** 끝에서 n번째 조각의 원문. 없으면 null. */
    public String rawPart(int fromEnd) {
        int i = rawParts.size() - 1 - fromEnd;
        return i < 0 ? null : rawParts.get(i);
    }

    /** 마지막 조각 바로 앞 한정자(스키마 또는 별칭). 없으면 null. */
    public String qualifier() {
        return part(1);
    }

    /** 정규화한 조각 전체. */
    public List<String> parts() {
        return rawParts.stream().map(DbName::normalize).collect(Collectors.toList());
    }

    /** 마지막 조각을 뺀 이름. 조각이 하나면 null. */
    public QName parent() {
        return rawParts.size() < 2 ? null : new QName(rawParts.subList(0, rawParts.size() - 1), null, pos);
    }

    /** 마지막 조각이 {@code ${}} 치환 자리를 포함하는가. */
    public boolean dynamic() {
        return isDynamic(rawName());
    }

    /** 어느 조각이든 {@code ${}} 치환 자리를 포함하는가. */
    public boolean anyDynamic() {
        return rawParts.stream().anyMatch(QName::isDynamic);
    }

    private static boolean isDynamic(String raw) {
        return raw.contains(Token.DYNAMIC) || raw.contains("${");
    }

    /** 정규화한 조각을 점으로 이은 표시용 이름. */
    public String display() {
        return String.join(".", parts());
    }

    @Override
    public String toString() {
        return display() + (dbLink == null ? "" : "@" + dbLink);
    }
}
