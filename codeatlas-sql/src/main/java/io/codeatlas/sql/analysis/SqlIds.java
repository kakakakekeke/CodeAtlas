package io.codeatlas.sql.analysis;

import io.codeatlas.core.model.Ids;
import java.util.List;
import java.util.Locale;

/**
 * 정규화한 DB 이름에서 그래프 ID를 만든다. {@link Ids}는 원문 식별자를 받으므로, 대소문자를 지켜야 하는 이름
 * (따옴표로 감쌌던 것)은 다시 따옴표로 감싸서 넘긴다.
 */
public final class SqlIds {

    private SqlIds() {
    }

    /** 정규화한 이름을 {@link Ids}에 넘길 수 있는 원문 꼴로 바꾼다. */
    public static String raw(String normalized) {
        if (normalized == null) {
            return "";
        }
        boolean plain = normalized.equals(normalized.toUpperCase(Locale.ROOT)) && normalized.indexOf('.') < 0
                && normalized.indexOf('"') < 0 && normalized.equals(normalized.trim());
        return plain ? normalized : "\"" + normalized.replace("\"", "\"\"") + "\"";
    }

    public static String table(String name) {
        return Ids.table(raw(name));
    }

    public static String view(String name) {
        return Ids.view(raw(name));
    }

    public static String column(String table, String column) {
        return Ids.column(raw(table), raw(column));
    }

    public static String sequence(String name) {
        return Ids.sequence(raw(name));
    }

    public static String synonym(String name) {
        return Ids.synonym(raw(name));
    }

    public static String dbPackage(String name) {
        return Ids.dbPackage(raw(name));
    }

    /** 패키지 소속이면 {@code db:routine:PKG.NAME}, 아니면 {@code db:routine:NAME}. */
    public static String routine(String packageName, String name) {
        return packageName == null ? Ids.routine(raw(name)) : Ids.routine(raw(packageName), raw(name));
    }

    /**
     * 호출 이름(정규화한 조각)에서 루틴 ID를 정한다.
     * <ul>
     *   <li>{@code N} → {@code db:routine:N}</li>
     *   <li>{@code X.Y}: 카탈로그에 패키지 X가 있으면 {@code X.Y}, X가 DDL에서 스키마로 쓰인 이름이면 {@code Y}, 모르면 패키지로 보고 {@code X.Y}</li>
     *   <li>{@code S.P.N} → {@code P.N}</li>
     * </ul>
     */
    public static String routineOfCall(Catalog catalog, List<String> parts) {
        int n = parts.size();
        String name = parts.get(n - 1);
        if (n == 1) {
            return routine(null, name);
        }
        String qualifier = parts.get(n - 2);
        if (n == 2 && !catalog.hasPackage(qualifier) && catalog.isSchema(qualifier)) {
            return routine(null, name);
        }
        return routine(qualifier, name);
    }
}
