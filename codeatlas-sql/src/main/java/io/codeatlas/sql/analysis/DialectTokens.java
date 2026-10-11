package io.codeatlas.sql.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 경로(파일 이름, 폴더 이름)에서 DB 방언을 읽는 토큰 표(F-007-R18).
 *
 * <p>방언별 DDL 여러 벌 가운데 쓸 것을 고를 때와, MySQL 관용 표기(줄 처음 {@code #} 주석)를 받을 파일을 가릴 때 쓴다.
 * mybatis 모듈의 {@code DialectVariants}(방언별 매퍼 고르기, F-009-R15·R20)와 <b>같은 목록·같은 규칙</b>이어야 한다. 두 모듈은 서로
 * 의존하지 않아 코드를 공유하지 않으므로, 양쪽 테스트({@code DialectTokensTest}, mybatis의 {@code DialectTokenTableTest})에 같은 표를 둔다.
 * 여기를 고치면 mybatis 쪽도 함께 고친다.
 *
 * <ul>
 *   <li>표준 이름: {@link #NAMES}. 별칭: {@code ora}→oracle, {@code postgresql}·{@code pgsql}→postgres, {@code maria}→mariadb,
 *       {@code sqlserver}→mssql, {@code hsqldb}→hsql. 대소문자를 가리지 않는다.</li>
 *   <li>표에 없는 토큰은 끝의 숫자(버전)를 떼고 한 번 더 본다: {@code mysql55}→mysql, {@code oracle11}→oracle.</li>
 *   <li>경로: 파일 이름(마지막 확장자 제외)을 글자·숫자가 아닌 문자로 나눈 토큰을 <b>뒤에서부터</b> 보고, 없으면 폴더 이름을 가까운 것부터 본다
 *       (폴더는 이름 전체가 방언 토큰일 때만).</li>
 * </ul>
 */
public final class DialectTokens {

    /**
     * 방언 표준 이름. 앞의 넷은 기본 선호 순서다: Oracle, DB2, Oracle 계열(Tibero, Altibase). 그 뒤(그 밖의 방언)는 이름의 목록일 뿐이고
     * 고르는 순서는 {@link #otherOrder}가 정한다.
     */
    public static final List<String> NAMES = List.of("oracle", "db2", "tibero", "altibase", "postgres", "mysql", "mariadb", "mssql",
            "cubrid", "goldilocks", "hsql", "h2", "derby", "sqlite", "sybase", "informix");

    /** 요청한 방언이 없을 때 먼저 고르는 방언(이 도구의 대상 방언과 그 계열): Oracle, DB2, Tibero, Altibase 순. */
    public static final List<String> PREFERRED = NAMES.subList(0, 4);

    private static final Map<String, String> ALIASES = Map.of(
            "ora", "oracle",
            "postgresql", "postgres", "pgsql", "postgres",
            "maria", "mariadb",
            "sqlserver", "mssql",
            "hsqldb", "hsql");

    private DialectTokens() {
    }

    /** 방언 토큰을 표준 이름으로. 방언이 아니면 null. */
    public static String canonical(String token) {
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
                name = lookup(lower.substring(0, end)); // 끝에 붙은 버전 숫자: mysql55, oracle11
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
     * 순서(F-007-R28). 매퍼 파일과 SQL 스크립트 파일 <b>양쪽의 경로에 다 나오는</b> 방언이 표준 이름의 사전순으로 먼저고, 나머지가 사전순으로
     * 그 뒤다. 경로만 본다(파일을 읽지 않는다).
     *
     * <p>mybatis 모듈의 {@code DialectVariants.otherOrder}(F-009-R26)와 <b>같은 규칙</b>이어야 한다: 두 모듈이 같은 파일 목록에서 같은 순서를
     * 얻어야 매퍼와 DDL이 같은 방언을 고른다. 양쪽 테스트({@code DialectTokensTest}, mybatis의 {@code DialectTokenTableTest})에 같은 표를 둔다.
     *
     * @param mapperPaths 프로젝트의 MyBatis 매퍼 파일 경로들
     * @param scriptPaths 프로젝트의 SQL 스크립트 파일 경로들
     */
    public static List<String> otherOrder(Collection<String> mapperPaths, Collection<String> scriptPaths) {
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
            String dialect = ofPath(path);
            if (dialect != null) {
                dialects.add(dialect);
            }
        }
        return dialects;
    }

    /** 파일 경로에서 읽은 방언(표준 이름). 파일 이름의 토큰을 뒤에서부터, 그다음 폴더 이름을 가까운 것부터 본다. 없으면 null. */
    public static String ofPath(String relativePath) {
        if (relativePath == null) {
            return null;
        }
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

    /** MySQL 계열(MySQL, MariaDB) 방언인가. */
    public static boolean isMySql(String dialect) {
        return "mysql".equals(dialect) || "mariadb".equals(dialect);
    }
}
