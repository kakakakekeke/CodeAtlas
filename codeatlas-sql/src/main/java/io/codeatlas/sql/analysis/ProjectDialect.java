package io.codeatlas.sql.analysis;

import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.FileType;
import io.codeatlas.core.spi.SourceFile;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 프로젝트 설정 파일({@code .properties}, {@code .yml}·{@code .yaml})에서 읽은 DB 방언(F-007-R21).
 *
 * <p>방언별 DDL·데이터 스크립트 여러 벌 가운데 쓸 것을 고를 때, 명시한 옵션({@code --dialect}, {@code mybatis.dialectVariant}) 다음 순위로 쓴다.
 * mybatis 모듈의 {@code ProjectDialect}(방언별 매퍼 고르기, F-009-R22)와 <b>같은 키 목록·같은 규칙</b>이어야 한다. 두 모듈은 코드를 공유하지
 * 않으므로 양쪽 테스트에 같은 표를 둔다. 여기를 고치면 mybatis 쪽도 함께 고친다.
 *
 * <ul>
 *   <li>방언 이름을 값으로 갖는 키: {@code Globals.DbType}(전자정부), {@code jdbc.type}, {@code mybatis.configuration.database-id}.</li>
 *   <li>JDBC URL 키: {@code spring.datasource.url} — {@code jdbc:} 뒤의 조각에서 읽는다({@code jdbc:oracle:thin:..},
 *       {@code jdbc:log4jdbc:mysql:..}).</li>
 *   <li>드라이버 클래스 키: {@code spring.datasource.driver-class-name} — 클래스 이름의 조각에서 읽는다({@code com.mysql.cj.jdbc.Driver}).</li>
 *   <li>키는 대소문자와 {@code -}·{@code _}를 가리지 않는다. 주석 처리된 줄, {@code ${..}}가 든 값, {@code src/test} 아래의 파일은 보지 않는다.</li>
 *   <li>읽은 방언이 여러 파일·프로파일(YAML 문서)에 걸쳐 하나로 모이지 않으면(서로 다른 방언) 쓰지 않는다.</li>
 * </ul>
 */
public final class ProjectDialect {

    /**
     * 설정에서 읽은 방언과 그 근거.
     *
     * @param dialect 방언 표준 이름({@link DialectTokens#NAMES})
     * @param file 설정 파일(프로젝트 기준 상대 경로)
     * @param key 설정 키(파일에 쓴 그대로. YAML은 점으로 이은 경로)
     * @param value 설정 값
     */
    public record Setting(String dialect, String file, String key, String value) {

        /** {@code <파일>:<키>=<값>}. */
        public String describe() {
            return file + ":" + key + "=" + value;
        }
    }

    private static final Set<String> NAME_KEYS = Set.of("globals.dbtype", "jdbc.type", "mybatis.configuration.databaseid");
    private static final Set<String> URL_KEYS = Set.of("spring.datasource.url");
    private static final Set<String> DRIVER_KEYS = Set.of("spring.datasource.driverclassname");

    /** JDBC URL에서 방언 앞에 끼는 래퍼 드라이버의 조각. */
    private static final Set<String> URL_WRAPPERS = Set.of("log4jdbc", "p6spy", "jtds", "tc");

    private ProjectDialect() {
    }

    /**
     * 설정 파일들을 읽은 결과.
     *
     * @param setting 한 방언으로 모인 설정. 없거나 엇갈리면 null
     * @param conflicting 서로 다른 방언을 가리켜 쓰지 않은 설정들(방언마다 처음 것). 엇갈리지 않았으면 빈 목록
     */
    public record Reading(Setting setting, List<Setting> conflicting) {
    }

    /** 프로젝트 설정에서 읽은 방언. 없거나 서로 다른 값이 있으면 null. 읽지 못한 파일은 건너뛴다. */
    public static Setting read(AnalysisContext ctx) {
        return readAll(ctx).setting();
    }

    /** {@link #read}와 같되, 설정이 엇갈려 쓰지 않았으면 그 설정들도 돌려준다(요약 진단에 적는다, F-007-R23). */
    public static Reading readAll(AnalysisContext ctx) {
        List<Setting> found = new ArrayList<>();
        for (SourceFile file : ctx.files().ofType(FileType.PROPERTIES, FileType.YAML)) {
            String path = file.relativePath();
            if (path.startsWith("src/test/") || path.contains("/src/test/")) {
                continue;
            }
            try {
                found.addAll(settingsOf(path, file.type() == FileType.YAML, file.readText()));
            } catch (RuntimeException e) {
                // 읽지 못한 설정 파일은 방언 판정에 쓰지 않는다
            }
        }
        Setting agreed = agreed(found);
        List<Setting> conflicting = new ArrayList<>();
        if (agreed == null) {
            Set<String> seen = new LinkedHashSet<>();
            for (Setting s : found) {
                if (seen.add(s.dialect())) {
                    conflicting.add(s);
                }
            }
        }
        return new Reading(agreed, conflicting);
    }

    /** 읽은 설정이 한 방언으로 모이면 처음 것, 아니면 null. */
    static Setting agreed(List<Setting> found) {
        Set<String> dialects = new LinkedHashSet<>();
        found.forEach(s -> dialects.add(s.dialect()));
        return dialects.size() == 1 ? found.get(0) : null;
    }

    /** 설정 파일 하나에서 읽은 방언 설정들. */
    static List<Setting> settingsOf(String path, boolean yaml, String text) {
        List<Setting> out = new ArrayList<>();
        Map<String, List<String>> values = yaml ? flattenYaml(text) : loadProperties(text);
        values.forEach((key, list) -> {
            for (String value : list) {
                String dialect = dialectOf(key, value);
                if (dialect != null) {
                    out.add(new Setting(dialect, path, key, value));
                }
            }
        });
        return out;
    }

    /** 키와 값에서 읽은 방언. 방언을 정하는 키가 아니거나 값을 읽을 수 없으면 null. */
    static String dialectOf(String key, String value) {
        if (key == null || value == null || value.isBlank() || value.contains("${")) {
            return null;
        }
        String k = key.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        String v = value.trim();
        if (NAME_KEYS.contains(k)) {
            return DialectTokens.canonical(v);
        }
        if (URL_KEYS.contains(k)) {
            return fromJdbcUrl(v);
        }
        return DRIVER_KEYS.contains(k) ? fromDriverClass(v) : null;
    }

    /** {@code jdbc:<방언>:..}. 래퍼 드라이버 조각({@code log4jdbc} 등)은 건너뛴다. */
    static String fromJdbcUrl(String url) {
        String[] parts = url.toLowerCase(Locale.ROOT).split(":");
        if (parts.length < 2 || !parts[0].equals("jdbc")) {
            return null;
        }
        for (int i = 1; i < parts.length && i <= 3; i++) {
            if (URL_WRAPPERS.contains(parts[i])) {
                continue;
            }
            // informix-sqli, sqlserver, postgresql 같은 조각: 글자·숫자가 아닌 데서 끊은 앞부분을 본다
            return DialectTokens.canonical(parts[i].split("[^a-z0-9]+", 2)[0]);
        }
        return null;
    }

    /** 드라이버 클래스 이름의 조각(점으로 나눈 것)이 방언 토큰이거나 방언 토큰으로 시작하면 그 방언({@code DB2Driver}, {@code OracleDriver}). */
    static String fromDriverClass(String className) {
        String[] parts = className.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        for (String part : parts) {
            String dialect = DialectTokens.canonical(part);
            if (dialect != null) {
                return dialect;
            }
        }
        for (String part : parts) {
            for (String prefix : DRIVER_PREFIXES) {
                if (part.startsWith(prefix)) {
                    return DialectTokens.canonical(prefix);
                }
            }
        }
        return null;
    }

    /** 드라이버 클래스 이름 조각의 앞머리로 읽는 방언 토큰(긴 것부터). */
    private static final List<String> DRIVER_PREFIXES = List.of("goldilocks", "postgresql", "sqlserver", "altibase", "informix",
            "mariadb", "oracle", "tibero", "cubrid", "sybase", "sqlite", "hsqldb", "mysql", "derby", "db2");

    // ------------------------------------------------------------------ 파일 읽기

    /** {@code .properties}: 주석({@code #}, {@code !})과 이스케이프는 {@link Properties}가 처리한다. 같은 키가 여러 번이면 마지막 값이다. */
    static Map<String, List<String>> loadProperties(String text) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(text));
        } catch (IOException | IllegalArgumentException e) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String name : new java.util.TreeSet<>(properties.stringPropertyNames())) {
            out.put(name, List.of(properties.getProperty(name)));
        }
        return out;
    }

    /**
     * 들여쓰기로 중첩된 {@code key: value} 줄만 읽는 간이 YAML 해석: 키 경로를 점으로 이어 붙인다. 주석 줄, 목록 항목({@code - }),
     * 여러 줄 값은 건너뛴다. 문서가 여럿이면({@code ---}, 프로파일) 같은 키의 값이 여럿일 수 있다.
     */
    static Map<String, List<String>> flattenYaml(String text) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<Integer> indents = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.replace("\t", "    ");
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("---")) {
                indents.clear();
                keys.clear();
                continue;
            }
            int colon = keyEnd(trimmed);
            if (trimmed.startsWith("- ") || trimmed.equals("-") || colon <= 0) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                keys.remove(keys.size() - 1);
            }
            String key = unquote(trimmed.substring(0, colon).strip());
            String value = stripComment(trimmed.substring(colon + 1).strip());
            indents.add(indent);
            keys.add(key);
            if (!value.isEmpty() && !value.equals("|") && !value.equals(">")) {
                out.computeIfAbsent(String.join(".", keys), k -> new ArrayList<>()).add(unquote(value));
            }
        }
        return out;
    }

    /** 키를 끝내는 콜론의 위치(뒤가 공백이거나 줄 끝인 첫 콜론). 없으면 -1. */
    private static int keyEnd(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ':' && (i + 1 == line.length() || line.charAt(i + 1) == ' ')) {
                return i;
            }
            if (c == '"' || c == '\'' || c == ' ' && i + 1 < line.length() && line.charAt(i + 1) == '#') {
                if (c != ' ') {
                    int close = line.indexOf(c, i + 1);
                    if (close < 0) {
                        return -1;
                    }
                    i = close;
                } else {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static String stripComment(String value) {
        if (value.startsWith("\"") || value.startsWith("'")) {
            int close = value.indexOf(value.charAt(0), 1);
            return close > 0 ? value.substring(0, close + 1) : value;
        }
        int hash = value.indexOf(" #");
        return hash >= 0 ? value.substring(0, hash).strip() : value;
    }

    private static String unquote(String s) {
        return s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'') && s.charAt(s.length() - 1) == s.charAt(0)
                ? s.substring(1, s.length() - 1) : s;
    }
}
