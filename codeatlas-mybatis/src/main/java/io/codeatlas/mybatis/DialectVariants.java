package io.codeatlas.mybatis;

import io.codeatlas.core.spi.Dialect;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * 방언별 매퍼 여러 벌 가운데 하나를 고른다(F-009-R15).
 *
 * <p>전자정부 계열 프로젝트는 같은 namespace·같은 구문 id의 매퍼를 {@code Xxx_SQL_oracle.xml}, {@code Xxx_SQL_mysql.xml}처럼
 * 방언별 파일로 여러 벌 두고 실행할 때 하나만 읽는다. 전부 합치면 구문의 테이블이 합집합이 되므로 하나만 고른다.
 *
 * <ul>
 *   <li>방언은 파일 이름의 토큰(글자·숫자가 아닌 문자로 나눈 것. 뒤에서부터)이나 폴더 이름에서 읽는다. 토큰 표(표준 이름, 별칭, 끝의 버전 숫자)와
 *       읽는 규칙은 sql 모듈의 {@code DialectTokens}(방언별 DDL 고르기, F-007-R18)와 <b>같은 목록·같은 규칙</b>이어야 한다. 두 모듈은 코드를
 *       공유하지 않으므로 양쪽 테스트({@code DialectTokenTableTest}, sql의 {@code DialectTokensTest})에 같은 표를 둔다. 여기를 고치면 sql 쪽도 고친다.</li>
 *   <li>선호 순서(F-009-R24. sql 모듈의 DDL 선택 F-007-R23과 같되 맨 앞 둘의 순서만 다르다 — DDL은 {@code --dialect}가 먼저):
 *       ① {@code mybatis.dialectVariant}로 준 방언, {@code --dialect}의 방언 ② 프로젝트 설정({@link ProjectDialect})이 대상 방언(oracle, db2)을
 *       가리키면 그것 ③ Oracle ④ DB2 ⑤ Oracle 계열(Tibero, Altibase. 설정이 이 가운데 하나를 가리키면 그것이 먼저)
 *       ⑥ 프로젝트 설정이 가리키는 대상 외 방언 ⑦ 그 밖의 방언: 매퍼 파일과 SQL 스크립트 파일 양쪽의 경로에 다 나오는 방언이 이름의
 *       사전순으로 먼저, 나머지가 사전순으로 그 뒤({@link #otherOrder}, F-009-R26. sql 모듈도 같은 파일 목록에서 같은 순서를 얻는다:
 *       F-007-R28). 설정이 대상 외 방언(전자정부 템플릿의 {@code Globals.DbType=mysql})이어도 대상 방언의 파일이 있으면 그쪽을 고른다.</li>
 *   <li>한 namespace가 서로 다른 방언의 파일 둘 이상에 정의돼 있으면 "방언 변형 namespace"다.</li>
 *   <li>그 안에서 id(구문, 조각, resultMap)마다, 그 id를 정의한 방언 파일 중 선호 순서가 가장 앞인 방언의 정의만 남긴다.
 *       방언을 읽지 못한 파일의 정의는 건드리지 않는다.</li>
 * </ul>
 */
final class DialectVariants {

    /** 옵션 키. 값: 방언 이름(강제), {@code all}(고르지 않고 전부 합친다), {@code auto}(기본). */
    static final String OPTION = "mybatis.dialectVariant";

    /**
     * 방언 표준 이름. 앞의 넷은 기본 선호 순서다: Oracle, DB2, Oracle 계열(Tibero, Altibase). 그 뒤(그 밖의 방언)는 이름의 목록일 뿐이고
     * 고르는 순서는 {@link #otherOrder}가 정한다. sql 모듈의 {@code DialectTokens.NAMES}와 같다.
     */
    static final List<String> NAMES = List.of("oracle", "db2", "tibero", "altibase", "postgres", "mysql",
            "mariadb", "mssql", "cubrid", "goldilocks", "hsql", "h2", "derby", "sqlite", "sybase", "informix");

    /** 이 도구의 대상 방언과 그 계열: Oracle, DB2, Tibero, Altibase. */
    static final List<String> PREFERRED = NAMES.subList(0, 4);

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("ora", "oracle"),
            Map.entry("postgresql", "postgres"), Map.entry("pgsql", "postgres"),
            Map.entry("maria", "mariadb"),
            Map.entry("sqlserver", "mssql"),
            Map.entry("hsqldb", "hsql"));

    /** 방언 토큰을 표준 이름으로. 표에 없으면 끝의 숫자(버전)를 떼고 한 번 더 본다({@code mysql55}, {@code oracle11}). 방언이 아니면 null. */
    static String canonical(String token) {
        if (token == null) {
            return null;
        }
        String lower = token.trim().toLowerCase(Locale.ROOT);
        String name = lookup(lower);
        if (name == null) {
            int end = lower.length();
            while (end > 0 && lower.charAt(end - 1) >= '0' && lower.charAt(end - 1) <= '9') {
                end--;
            }
            if (end > 0 && end < lower.length()) {
                name = lookup(lower.substring(0, end));
            }
        }
        return name;
    }

    private static String lookup(String lower) {
        String name = ALIASES.getOrDefault(lower, lower);
        return NAMES.contains(name) ? name : null;
    }

    /**
     * 요청·프로젝트 설정·대상 방언(과 그 계열)으로 순위가 정해지지 않는 "그 밖의 방언"({@link #NAMES}에서 {@link #PREFERRED}를 뺀 것)의
     * 순서(F-009-R26). 매퍼 파일과 SQL 스크립트 파일 <b>양쪽의 경로에 다 나오는</b> 방언이 표준 이름의 사전순으로 먼저고, 나머지가 사전순으로
     * 그 뒤다. 경로만 본다(파일을 읽지 않는다).
     *
     * <p>sql 모듈의 {@code DialectTokens.otherOrder}(F-007-R28)와 <b>같은 규칙</b>이어야 한다: 두 모듈이 같은 파일 목록에서 같은 순서를
     * 얻어야 매퍼와 DDL이 같은 방언을 고른다. 양쪽 테스트({@code DialectTokenTableTest}, sql의 {@code DialectTokensTest})에 같은 표를 둔다.
     *
     * @param mapperPaths 프로젝트의 MyBatis 매퍼 파일 경로들
     * @param scriptPaths 프로젝트의 SQL 스크립트 파일 경로들
     */
    static List<String> otherOrder(Collection<String> mapperPaths, Collection<String> scriptPaths) {
        Set<String> onBoth = dialectsOf(mapperPaths);
        onBoth.retainAll(dialectsOf(scriptPaths));
        onBoth.removeAll(PREFERRED);
        Set<String> rest = new TreeSet<>(NAMES);
        rest.removeAll(PREFERRED);
        rest.removeAll(onBoth);
        List<String> order = new ArrayList<>(onBoth);
        order.addAll(rest);
        return List.copyOf(order);
    }

    /** 경로들에서 읽은 방언(표준 이름의 사전순). */
    private static Set<String> dialectsOf(Collection<String> paths) {
        Set<String> dialects = new TreeSet<>();
        for (String path : paths) {
            String dialect = dialectOf(path);
            if (dialect != null) {
                dialects.add(dialect);
            }
        }
        return dialects;
    }

    /** 파일 경로에서 읽은 방언. 파일 이름의 토큰을 뒤에서부터, 그다음 폴더 이름을 가까운 것부터 본다. 없으면 null. */
    static String dialectOf(String relativePath) {
        String path = relativePath.replace('\\', '/');
        int slash = path.lastIndexOf('/');
        String name = path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        String[] tokens = (dot > 0 ? name.substring(0, dot) : name).split("[^A-Za-z0-9]+");
        for (int i = tokens.length - 1; i >= 0; i--) {
            String dialect = canonical(tokens[i]);
            if (dialect != null) {
                return dialect;
            }
        }
        if (slash > 0) {
            String[] folders = path.substring(0, slash).split("/");
            for (int i = folders.length - 1; i >= 0; i--) {
                String dialect = canonical(folders[i]);
                if (dialect != null) {
                    return dialect;
                }
            }
        }
        return null;
    }

    private final boolean all;
    private final List<String> order;
    /** 방언 변형 namespace → 그 namespace의 파일에서 읽은 방언들. */
    private final Map<String, Set<String>> variantNamespaces = new LinkedHashMap<>();
    /** 정의가 하나라도 남은 파일. */
    private final Set<MapperFile> used = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<MapperFile> variantFiles = Collections.newSetFromMap(new IdentityHashMap<>());

    /** 명시한 요청 하나: 방언과 그것을 준 옵션 이름. */
    record Request(String dialect, String option) {
    }

    /** 명시한 요청(선호 순서대로): {@code mybatis.dialectVariant}, {@code --dialect}. */
    private final List<Request> requests = new ArrayList<>();
    private final ProjectDialect.Setting setting;
    /** 서로 다른 방언을 가리켜 쓰지 않은 프로젝트 설정들. */
    private final List<ProjectDialect.Setting> conflicting;
    /** 이 도구의 대상 방언. */
    private static final List<String> TARGETS = List.of("oracle", "db2");

    /**
     * @param forced 옵션으로 강제한 방언(표준 이름). 없으면 null
     * @param all 고르지 않고 전부 합치는가
     * @param dialect {@code --dialect} 값
     * @param setting 프로젝트 설정에서 읽은 방언. 없으면 null
     * @param mappers namespace → 그 namespace를 정의한 파일들
     */
    DialectVariants(String forced, boolean all, Dialect dialect, ProjectDialect.Setting setting, Map<String, List<MapperFile>> mappers) {
        this(forced, all, dialect, setting, List.of(), otherOrder(List.of(), List.of()), mappers);
    }

    /**
     * @param conflicting 서로 다른 방언을 가리켜 쓰지 않은 프로젝트 설정들(요약 진단에 적는다)
     * @param others 그 밖의 방언의 순서({@link #otherOrder})
     */
    DialectVariants(String forced, boolean all, Dialect dialect, ProjectDialect.Setting setting, List<ProjectDialect.Setting> conflicting,
            List<String> others, Map<String, List<MapperFile>> mappers) {
        this.all = all;
        this.setting = setting;
        this.conflicting = List.copyOf(conflicting);
        List<String> preferred = new ArrayList<>();
        if (forced != null) {
            preferred.add(forced);
            requests.add(new Request(forced, OPTION));
        }
        String optionDialect = dialect == Dialect.ORACLE ? "oracle" : dialect == Dialect.DB2 ? "db2" : null;
        if (optionDialect != null && !preferred.contains(optionDialect)) {
            preferred.add(optionDialect);
            requests.add(new Request(optionDialect, "--dialect"));
        }
        String configured = setting == null ? null : setting.dialect();
        if (configured != null && TARGETS.contains(configured) && !preferred.contains(configured)) {
            preferred.add(configured); // 설정이 대상 방언을 가리킨다
        }
        for (String name : TARGETS) {
            if (!preferred.contains(name)) {
                preferred.add(name);
            }
        }
        if (configured != null && PREFERRED.contains(configured) && !preferred.contains(configured)) {
            preferred.add(configured); // Oracle 계열 안에서는 설정이 가리키는 것이 먼저다
        }
        for (String name : PREFERRED) {
            if (!preferred.contains(name)) {
                preferred.add(name);
            }
        }
        if (configured != null && !preferred.contains(configured)) {
            preferred.add(configured); // 대상 외 방언: 대상 방언과 그 계열 다음, 그 밖의 방언보다 먼저
        }
        for (String name : others) {
            if (!preferred.contains(name)) {
                preferred.add(name);
            }
        }
        this.order = List.copyOf(preferred);
        mappers.forEach((namespace, files) -> {
            Set<String> dialects = new TreeSet<>(java.util.Comparator.comparingInt(this::rank));
            for (MapperFile file : files) {
                if (file.dialect() != null) {
                    dialects.add(file.dialect());
                }
            }
            if (dialects.size() >= 2) {
                variantNamespaces.put(namespace, dialects);
                for (MapperFile file : files) {
                    if (file.dialect() != null) {
                        variantFiles.add(file);
                    }
                }
            }
        });
    }

    private int rank(String dialect) {
        int index = order.indexOf(dialect);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    /**
     * 명시해 요청한 방언 가운데 매퍼 파일이 없어 쓰지 못한 것(선호 순서대로, {@code 방언(`옵션`)}). 파일이 있는 요청을 만나면 거기서 멈춘다.
     * 방언 변형이 없거나 전부 합치는 방식이면 빈 목록이다.
     */
    List<String> missingRequests() {
        List<String> missing = new ArrayList<>();
        if (all || !detected()) {
            return missing;
        }
        List<String> present = dialects();
        for (Request r : requests) {
            if (present.contains(r.dialect())) {
                break;
            }
            missing.add(r.dialect() + "(`" + r.option() + "`)");
        }
        return missing;
    }

    /** 이 방언이 명시한 요청이 아니라 프로젝트 설정에서 읽은 것이면 그 설정. 아니면 null. */
    ProjectDialect.Setting settingFor(String dialect) {
        return setting != null && setting.dialect().equals(dialect) && requests.stream().noneMatch(r -> r.dialect().equals(dialect))
                ? setting : null;
    }

    /** 프로젝트 설정에서 읽은 방언(쓰였든 아니든). 없으면 null. */
    ProjectDialect.Setting setting() {
        return setting;
    }

    /** 서로 다른 방언을 가리켜 쓰지 않은 프로젝트 설정들. */
    List<ProjectDialect.Setting> conflicting() {
        return conflicting;
    }

    /** 이 방언을 옵션으로 명시해 요청했는가. */
    boolean requested(String dialect) {
        return requests.stream().anyMatch(r -> r.dialect().equals(dialect));
    }

    /** 파일들 가운데 방언 변형 파일의 방언으로 선호 순서가 가장 앞인 것. 방언 변형 파일이 없으면 null. */
    String bestDialect(List<MapperFile> files) {
        String best = null;
        for (MapperFile file : files) {
            String dialect = variantOf(file);
            if (dialect != null && (best == null || rank(dialect) < rank(best))) {
                best = dialect;
            }
        }
        return best;
    }

    /** 방언 변형이 하나라도 있는가. */
    boolean detected() {
        return !variantNamespaces.isEmpty();
    }

    /** 고르지 않고 전부 합치는 방식인가. */
    boolean all() {
        return all;
    }

    /** 방언 변형 namespace의 방언 파일이면 그 방언. 아니면 null. */
    String variantOf(MapperFile file) {
        return variantFiles.contains(file) ? file.dialect() : null;
    }

    /** 고른 방언(전부 합치는 방식이면 null). 방언 변형 namespace의 파일이 아니면 null. */
    String selectedVariantOf(MapperFile file) {
        return all ? null : variantOf(file);
    }

    /**
     * 한 id의 정의들 가운데 남길 것. 방언 변형 파일의 정의는 선호 순서가 가장 앞인 방언의 것만 남기고, 나머지 정의는 그대로 둔다.
     * 남긴 정의의 파일은 "쓰인 파일"로 기록한다.
     */
    <T> List<T> select(List<T> defs, Function<T, MapperFile> fileOf) {
        String best = null;
        if (!all) {
            for (T def : defs) {
                String dialect = variantOf(fileOf.apply(def));
                if (dialect != null && (best == null || rank(dialect) < rank(best))) {
                    best = dialect;
                }
            }
        }
        List<T> kept = new ArrayList<>();
        for (T def : defs) {
            MapperFile file = fileOf.apply(def);
            String dialect = variantOf(file);
            if (best == null || dialect == null || dialect.equals(best)) {
                kept.add(def);
                used.add(file);
            }
        }
        return kept;
    }

    /** namespace의 파일들 가운데 매퍼 노드의 위치로 삼을 파일: 방언 변형이면 선호 순서가 가장 앞인 방언의 첫 파일, 아니면 첫 파일. */
    MapperFile primaryFile(List<MapperFile> files) {
        MapperFile best = null;
        if (!all) {
            for (MapperFile file : files) {
                String dialect = variantOf(file);
                if (dialect != null && (best == null || rank(dialect) < rank(best.dialect()))) {
                    best = file;
                }
            }
        }
        if (best == null) {
            best = files.get(0);
        }
        used.add(best);
        return best;
    }

    /** 이 파일이 매퍼를 정의한 것으로 볼지: 고르지 않는 방식이거나, 방언 변형 파일이 아니거나, 정의가 하나라도 남았거나, namespace의 대표 파일이다. */
    boolean contributes(MapperFile file) {
        return all || !variantFiles.contains(file) || used.contains(file);
    }

    /** 방언 변형 파일인데 정의가 하나도 남지 않은 파일 수. */
    int excludedFileCount() {
        int count = 0;
        for (MapperFile file : variantFiles) {
            if (!used.contains(file)) {
                count++;
            }
        }
        return count;
    }

    /** 감지한 방언들(선호 순서). */
    List<String> dialects() {
        Set<String> dialects = new TreeSet<>(java.util.Comparator.comparingInt(this::rank));
        variantNamespaces.values().forEach(dialects::addAll);
        return List.copyOf(dialects);
    }
}
