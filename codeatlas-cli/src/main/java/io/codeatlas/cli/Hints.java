package io.codeatlas.cli;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.ProjectInfo;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.export.report.SummaryReport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * analyze 요약 끝에 붙는 힌트(설계 4.5). 진단과 그래프를 보고 해당할 때만 한 줄씩 만든다. 분석 결과를 바꾸지 않는다.
 *
 * <p>힌트에 적는 {@code --opt} 키는 설계 3.8의 키여야 한다(F-001-AC47이 대조한다).
 */
final class Hints {

    /** 미해결 Java 호출이 이 수를 넘어야 {@code --classpath}·{@code --exclude-tests} 안내를 낸다. */
    static final int UNRESOLVED_CALL_THRESHOLD = 50;

    // 다른 모듈이 정의한 진단 코드와 분류. cli는 문자열로만 안다(모듈 사이의 약속은 설계 3.6·3.7).
    private static final String DIALECT_VARIANT_MAPPERS = "DIALECT_VARIANT_MAPPERS";
    private static final String DIALECT_VARIANT_DDL = "DIALECT_VARIANT_DDL";
    private static final String DIALECT_NOT_FOUND = "DIALECT_NOT_FOUND";
    private static final String DIALECT_NOT_TARGET = "DIALECT_NOT_TARGET";
    /** 대상 외 방언을 고르는 옵션. {@code --dialect}는 oracle·db2·postgres·auto만 받는다. 매퍼(mybatis)와 DDL(sql) 선택이 둘 다 이 값을 읽는다. */
    private static final String VARIANT_OPTION = "--opt mybatis.dialectVariant=<방언>";
    /** 대상 방언을 고르는 옵션 안내(F-019-R34). {@code AnalyzeCommand}의 사용법, 계약 문서 4.5의 힌트 표와 같은 값이다. */
    private static final String DIALECT_OPTION = "--dialect <oracle|db2|postgres>";
    private static final String UNKNOWN_COLUMN = "UNKNOWN_COLUMN";
    private static final String DATA_LOAD_GROUPED = "DATA_LOAD_GROUPED";
    private static final String DDL_NOT_APPLIED = "DDL_NOT_APPLIED";
    private static final String CATEGORY_JAVA_CALL = "java-call";

    /**
     * 방언 표준 이름과 별칭. sql 모듈의 {@code DialectTokens}, mybatis 모듈의 {@code DialectVariants}와 같은 표다(설계 3.8의
     * {@code mybatis.dialectVariant}). cli는 그 모듈들의 클래스를 쓰지 않으므로 표를 따로 둔다: 그쪽 표가 바뀌면 여기도 고친다.
     * 앞의 다섯이 기본 선호 순서다(F-019-R4): Oracle, DB2, PostgreSQL, Oracle 계열(Tibero, Altibase).
     */
    private static final List<String> DIALECT_NAMES = List.of("oracle", "db2", "postgres", "tibero", "altibase", "mysql", "mariadb", "mssql",
            "cubrid", "goldilocks", "hsql", "h2", "derby", "sqlite", "sybase", "informix");
    private static final Map<String, String> DIALECT_ALIASES = Map.of("ora", "oracle", "postgresql", "postgres", "pgsql", "postgres",
            "maria", "mariadb", "sqlserver", "mssql", "hsqldb", "hsql");
    private static final String DIALECT_VARIANT_ATTR = "dialectVariant";

    private Hints() {
    }

    /** 힌트 문장들(한 문장이 한 줄). 해당하는 것이 없으면 빈 목록. */
    static List<String> of(GraphModel graph) {
        ProjectInfo project = graph.project();
        AnalysisOptions options = project == null || project.options() == null ? AnalysisOptions.defaults() : project.options();
        List<String> hints = new ArrayList<>();
        dialectVariants(graph, hints);
        unresolvedCalls(graph, options, hints);
        sqlScripts(graph, hints);
        unknownColumns(graph, hints);
        vendorFiles(graph, hints);
        frontAndBack(graph, hints);
        return hints;
    }

    /**
     * (a) 방언 선택. 방언별 매퍼 여러 벌: mybatis가 남긴 요약(감지한 방언, 쓴 방언)을 옮기고 바꾸는 옵션을 알린다.
     * 방언별 DDL 여러 벌: sql이 남긴 요약. 요청한 방언의 파일이 없어 다른 방언으로 대체했으면 그 사실. 여러 벌은 아니지만 SQL이 대상 외
     * 방언으로 보이면({@code DIALECT_NOT_TARGET}) 그 사실. 매퍼와 DDL에서 고른 방언이 다르면 경고 한 줄을 더한다(구문과 테이블 정의가
     * 서로 다른 DB의 것이다). 고른 방언은 진단 문구가 아니라 그래프에서 읽는다({@link #mismatchedDialects}).
     *
     * <p>방언 정책(sql·mybatis): 고르는 순서는 명시값({@code --dialect}, {@code mybatis.dialectVariant}) → 프로젝트 설정이 가리키는 대상 방언
     * (oracle, db2, postgres) → Oracle → DB2 → PostgreSQL → Oracle 계열 → 설정의 대상 외 방언 → 경로순이다(F-019-R4). 설정과 다르게
     * 대상 방언을 골랐다는 것은 INFO로 오고(그대로 옮긴다),
     * WARN은 대상 방언 파일이 없어 대상 외 방언을 썼을 때와 요청한 방언의 파일이 없을 때뿐이다(맨 위에 "주의:"로 올린다).
     * 메시지에 바꾸는 옵션 안내가 이미 들어 있으면 덧붙이지 않는다.
     */
    private static void dialectVariants(GraphModel graph, List<String> hints) {
        String mappers = null;
        String ddl = null;
        String notTarget = null;
        boolean mappersWarn = false;
        boolean ddlWarn = false;
        List<String> notFound = new ArrayList<>();
        for (Diagnostic d : graph.diagnostics()) {
            if (DIALECT_VARIANT_MAPPERS.equals(d.code()) && mappers == null) {
                mappers = oneLine(d.message());
                mappersWarn = d.severity() == Severity.WARN;
            } else if (DIALECT_VARIANT_DDL.equals(d.code()) && ddl == null) {
                ddl = oneLine(d.message());
                ddlWarn = d.severity() == Severity.WARN;
            } else if (DIALECT_NOT_FOUND.equals(d.code())) {
                notFound.add(oneLine(d.message()));
            } else if (DIALECT_NOT_TARGET.equals(d.code()) && notTarget == null) {
                notTarget = oneLine(d.message());
            }
        }
        List<String> notes = new ArrayList<>();
        if (mappers != null) {
            String text = mappers.contains("mybatis.dialectVariant") ? mappers
                    : sentence(mappers) + "다른 방언을 쓰려면 " + VARIANT_OPTION + ", 전부 합치려면 --opt mybatis.dialectVariant=all";
            (mappersWarn ? hints : notes).add(mappersWarn ? "주의: " + text : text);
        }
        if (ddl != null) {
            String text = ddl.contains("mybatis.dialectVariant") ? ddl
                    : sentence(ddl) + "다른 방언의 DDL을 쓰려면 " + DIALECT_OPTION + ", 그 밖의 방언은 " + VARIANT_OPTION;
            (ddlWarn ? hints : notes).add(ddlWarn ? "주의: " + text : text);
        }
        for (String message : notFound) {
            hints.add("주의: " + (message.contains("mybatis.dialectVariant") ? message : sentence(message)
                    + "분석 대상에 그 방언의 파일이 있는지 확인한다. 감지한 방언으로 분석하려면 " + DIALECT_OPTION + ", 그 밖의 방언은 " + VARIANT_OPTION));
        }
        hints.addAll(notes);
        if (notTarget != null) {
            hints.add(notTarget);
        }
        String[] mismatch = mismatchedDialects(graph);
        if (mismatch != null) {
            hints.add("경고: 매퍼는 " + mismatch[0] + ", DDL은 " + mismatch[1] + " 방언 파일을 골랐다. 구문과 테이블 정의가 서로 다른 DB의 것이라 "
                    + "컬럼·관계 해석이 어긋날 수 있다: " + VARIANT_OPTION + "으로 맞춘다(매퍼와 DDL 선택이 둘 다 이 값을 읽는다. "
                    + "대상 방언이면 " + DIALECT_OPTION + "도 된다)");
        }
    }

    /** 덧붙일 안내 앞에 놓을 문장: 마침표로 끝나지 않으면 마침표를 붙이고 한 칸 띈다. */
    private static String sentence(String message) {
        return message + (message.endsWith(".") ? " " : ". ");
    }

    /**
     * 매퍼와 DDL에서 고른 방언이 서로 다르면 {매퍼 방언, DDL 방언}, 같거나 어느 한쪽을 알 수 없으면 null.
     *
     * <ul>
     * <li>매퍼: 매퍼 구문({@code SQL_STATEMENT})의 {@code attrs.dialectVariant}(설계 3.5 "방언별 매퍼")에서 가장 많은 값.
     *     값이 있는 구문이 없으면 {@code MYBATIS_MAPPER}의 같은 속성. 방언 변형이 아니거나 {@code all}로 합쳤으면 값이 없다.</li>
     * <li>DDL: 정의 위치가 있는 테이블({@code DB_TABLE})마다 그 파일 경로의 방언 토큰({@link #dialectOfPath})을 읽어 가장 많은 값.
     *     경로에 방언이 없는 파일의 테이블은 세지 않는다.</li>
     * </ul>
     * 가장 많은 값이 여럿이면(같은 수) 양쪽에 공통인 방언이 하나라도 있을 때 같다고 본다.
     */
    private static String[] mismatchedDialects(GraphModel graph) {
        Map<String, Integer> mapperCounts = new TreeMap<>();
        for (NodeKind kind : List.of(NodeKind.SQL_STATEMENT, NodeKind.MYBATIS_MAPPER)) {
            for (Node node : graph.nodes(kind)) {
                String variant = node.attrString(DIALECT_VARIANT_ATTR);
                if (variant != null && !variant.isBlank()) {
                    String name = canonicalDialect(variant);
                    mapperCounts.merge(name != null ? name : variant.strip().toLowerCase(Locale.ROOT), 1, Integer::sum);
                }
            }
            if (!mapperCounts.isEmpty()) {
                break;
            }
        }
        Map<String, Integer> ddlCounts = new TreeMap<>();
        for (Node table : graph.nodes(NodeKind.DB_TABLE)) {
            String dialect = table.location() == null ? null : dialectOfPath(table.location().file());
            if (dialect != null) {
                ddlCounts.merge(dialect, 1, Integer::sum);
            }
        }
        List<String> mapperTop = mostFrequent(mapperCounts);
        List<String> ddlTop = mostFrequent(ddlCounts);
        if (mapperTop.isEmpty() || ddlTop.isEmpty() || mapperTop.stream().anyMatch(ddlTop::contains)) {
            return null;
        }
        return new String[] {mapperTop.get(0), ddlTop.get(0)};
    }

    /** 가장 많이 나온 값들. 표준 이름의 선호 순서(그 밖의 값은 뒤에 이름순)로 돌려준다. */
    private static List<String> mostFrequent(Map<String, Integer> counts) {
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<String> top = new ArrayList<>();
        counts.forEach((name, count) -> {
            if (count == max) {
                top.add(name);
            }
        });
        top.sort(Comparator.comparingInt((String name) -> {
            int rank = DIALECT_NAMES.indexOf(name);
            return rank < 0 ? DIALECT_NAMES.size() : rank;
        }).thenComparing(Comparator.naturalOrder()));
        return top;
    }

    /**
     * 파일 경로에서 읽은 방언(표준 이름). 없으면 null. sql·mybatis와 같은 규칙이다: 파일 이름(마지막 확장자 제외)을 글자·숫자가 아닌
     * 문자로 나눈 토큰을 뒤에서부터 보고, 없으면 폴더 이름을 가까운 것부터 본다(폴더는 이름 전체가 방언 토큰일 때만).
     */
    static String dialectOfPath(String path) {
        if (path == null) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = normalized.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        String[] tokens = (dot > 0 ? name.substring(0, dot) : name).split("[^A-Za-z0-9]+");
        for (int i = tokens.length - 1; i >= 0; i--) {
            String dialect = canonicalDialect(tokens[i]);
            if (dialect != null) {
                return dialect;
            }
        }
        if (slash > 0) {
            String[] folders = normalized.substring(0, slash).split("/");
            for (int i = folders.length - 1; i >= 0; i--) {
                String dialect = canonicalDialect(folders[i]);
                if (dialect != null) {
                    return dialect;
                }
            }
        }
        return null;
    }

    /** 방언 토큰을 표준 이름으로(별칭, 끝에 붙은 버전 숫자 {@code mysql55}·{@code oracle11} 포함). 방언이 아니면 null. */
    private static String canonicalDialect(String token) {
        String lower = token.strip().toLowerCase(Locale.ROOT);
        String name = DIALECT_ALIASES.getOrDefault(lower, lower);
        if (DIALECT_NAMES.contains(name)) {
            return name;
        }
        int end = lower.length();
        while (end > 0 && Character.isDigit(lower.charAt(end - 1))) {
            end--;
        }
        if (end == 0 || end == lower.length()) {
            return null;
        }
        name = DIALECT_ALIASES.getOrDefault(lower.substring(0, end), lower.substring(0, end));
        return DIALECT_NAMES.contains(name) ? name : null;
    }

    /**
     * (b) 미해결 Java 호출이 많을 때: 의존 jar가 없으면 {@code --classpath}, 절반 넘게 테스트 코드면 {@code --exclude-tests}.
     * 같은 지점에서 다른 방법으로 풀린 참조({@code resolvedBy}가 있는 미해결 노드)로 가는 엣지는 세지 않는다({@link UnresolvedRefs}).
     */
    private static void unresolvedCalls(GraphModel graph, AnalysisOptions options, List<String> hints) {
        int total = 0;
        int inTests = 0;
        for (Edge edge : graph.edges(EdgeKind.CALLS)) {
            if (edge.confidence() != Confidence.UNRESOLVED) {
                continue;
            }
            Node target = graph.node(edge.to()).orElse(null);
            if (target == null || target.kind() != NodeKind.UNRESOLVED || !CATEGORY_JAVA_CALL.equals(target.attrString("category"))
                    || UnresolvedRefs.resolvedElsewhere(target)) {
                continue;
            }
            total++;
            if (edge.location() != null && inTestSources(edge.location().file())) {
                inTests++;
            }
        }
        if (total <= UNRESOLVED_CALL_THRESHOLD) {
            return;
        }
        if (options.classpath().isEmpty()) {
            hints.add("미해결 Java 호출 " + total + "건: 의존 jar(또는 클래스 폴더)를 --classpath로 주면 라이브러리 타입이 해석되어 줄어든다");
        }
        if (inTests * 2 > total && !options.excludes().contains(AnalyzeCommand.TEST_SOURCES_GLOB)) {
            hints.add("미해결 Java 호출 " + total + "건 가운데 " + inTests + "건이 테스트 코드(src/test)에서 나왔다: --exclude-tests로 테스트 소스를 뺄 수 있다");
        }
    }

    private static boolean inTestSources(String file) {
        return file != null && (file.startsWith("src/test/") || file.contains("/src/test/"));
    }

    /** (c) SQL 스크립트: 같은 테이블의 중복 정의, 데이터 적재 묶음, 적용하지 않은 upgrade 스크립트의 ALTER. */
    private static void sqlScripts(GraphModel graph, List<String> hints) {
        int duplicates = 0;
        int dataLoads = 0;
        int notApplied = 0;
        for (Diagnostic d : graph.diagnostics()) {
            switch (d.code()) {
                case DiagnosticCodes.DUPLICATE_OBJECT_NAME -> duplicates += fromXml(graph, d) ? 0 : 1;
                case DATA_LOAD_GROUPED -> dataLoads++;
                case DDL_NOT_APPLIED -> notApplied++;
                default -> {
                }
            }
        }
        // 방언별 DDL에서 한 벌을 고른 것이면 그 요약(DIALECT_VARIANT_DDL)이 같은 사실을 알린다: 파일 쌍마다 나온 중복 안내는 접는다.
        if (duplicates > 0 && graph.diagnostics().stream().noneMatch(d -> DIALECT_VARIANT_DDL.equals(d.code()))) {
            hints.add("같은 테이블이 여러 파일에 정의돼 있어 한 벌만 썼다(진단 DUPLICATE_OBJECT_NAME " + duplicates + "건): "
                    + "쓸 DDL만 --ddl로 주거나 나머지를 --exclude로 빼면 어느 정의를 쓸지 직접 정할 수 있다");
        }
        if (dataLoads > 0) {
            hints.add("데이터 적재 스크립트 " + dataLoads + "개 파일의 INSERT를 테이블별 구문으로 묶었다: CRUD 매트릭스·콜트리·테이블 점검에서는 기본으로 빠진다"
                    + "(넣으려면 --opt export.includeDataLoad=true, 분석에서 아예 빼려면 --exclude)");
        }
        if (notApplied > 0) {
            hints.add("upgrade·patch·migration 스크립트 " + notApplied + "개 파일의 ALTER TABLE은 이미 반영된 변경으로 보고 적용하지 않았다"
                    + "(파일 목록은 diagnostics.md의 DDL_NOT_APPLIED)");
        }
    }

    /** (e) SQL이 DDL에 없는 컬럼을 참조한다({@code UNKNOWN_COLUMN}: 소속 테이블을 정하지 못한 컬럼이 있는 구문마다 1건). */
    private static void unknownColumns(GraphModel graph, List<String> hints) {
        int count = 0;
        for (Diagnostic d : graph.diagnostics()) {
            if (UNKNOWN_COLUMN.equals(d.code())) {
                count++;
            }
        }
        if (count > 0) {
            hints.add("SQL이 DDL에 없는 컬럼을 참조한다(진단 UNKNOWN_COLUMN " + count + "건): 그 컬럼의 엣지는 만들지 않았다. "
                    + "DDL이 오래됐거나 다른 방언·다른 버전의 DDL이 선택됐을 수 있다(--ddl, " + DIALECT_OPTION + ", 그 밖의 방언은 " + VARIANT_OPTION
                    + "을 확인한다)");
        }
    }

    /**
     * (f) 프런트와 백엔드가 따로 분석된 경우. 서버 요청({@code report.md}의 "화면 요청" 절과 같은 수: 외부 URL, 정적 페이지 링크,
     * 클라이언트 라우트를 뺀 화면 요청. F-001-R60)은 있는데 엔드포인트가 하나도 없으면 백엔드와 함께 분석하라는 안내를, 엔드포인트는 있는데
     * 화면 요청(클라이언트 라우트 제외)이 하나도 없으면 프런트 소스가 분석 범위에 없다는 안내를 낸다.
     */
    private static void frontAndBack(GraphModel graph, List<String> hints) {
        int requests = 0;
        for (Node request : graph.nodes(NodeKind.HTTP_REQUEST)) {
            if (!request.attrBoolean("clientRoute")) {
                requests++;
            }
        }
        int endpoints = graph.nodes(NodeKind.HTTP_ENDPOINT).size();
        // report.md의 "화면 요청" 절과 같은 것을 같은 이름으로 센다: 서버 요청(외부 URL, 정적 페이지 링크, 클라이언트 라우트 제외).
        int server = requests > 0 && endpoints == 0 ? SummaryReport.requestStats(graph).server() : 0;
        if (server > 0 && endpoints == 0) {
            hints.add("서버 요청 " + server + "건이 있지만 엔드포인트가 없다(외부 URL·정적 페이지 링크·클라이언트 라우트를 뺀 수: report.md의 "
                    + "\"화면 요청\"과 같다): 백엔드 프로젝트와 함께 분석하면(두 프로젝트를 담은 상위 폴더를 "
                    + "분석 대상으로 준다) 화면 요청이 엔드포인트에 연결된다");
        } else if (endpoints > 0 && requests == 0) {
            hints.add("엔드포인트 " + endpoints + "개가 있지만 화면 요청이 없다: 프런트엔드 소스(JSP, JS·TS)가 분석 범위에 없으면 화면에서 "
                    + "엔드포인트로 이어지는 흐름이 나오지 않는다. 프런트 프로젝트와 함께 담은 상위 폴더를 분석 대상으로 준다");
        }
    }

    /** DUPLICATE_OBJECT_NAME은 Spring XML의 빈 이름 중복에도 쓰인다. 위치의 파일 종류로 가른다. */
    private static boolean fromXml(GraphModel graph, Diagnostic d) {
        if (d.location() == null || d.location().file() == null) {
            return false;
        }
        String file = d.location().file();
        String fileType = graph.node(Ids.file(file)).map(n -> n.attrString("fileType")).orElse(null);
        return fileType != null ? fileType.endsWith("XML") : file.endsWith(".xml");
    }

    /**
     * (d) 내용을 분석하지 않은 벤더 스크립트·페이지({@code skipped=vendor}). 진단 FILE_SKIPPED는 폴더마다 1건(요약 메시지:
     * {@code 벤더 스크립트 7개 건너뜀(이름 3, 라이선스 4): <폴더>})이라 노드로 센다. 메시지의 문구와 건수는 읽지 않는다.
     */
    private static void vendorFiles(GraphModel graph, List<String> hints) {
        int skipped = 0;
        for (NodeKind kind : List.of(NodeKind.JS_MODULE, NodeKind.JSP_PAGE)) {
            for (Node node : graph.nodes(kind)) {
                if ("vendor".equals(node.attrString("skipped"))) {
                    skipped++;
                }
            }
        }
        if (skipped > 0) {
            hints.add("벤더(서드파티) 스크립트·페이지 " + skipped + "개는 내용을 분석하지 않았다(진단 FILE_SKIPPED): 프로젝트 코드가 잘못 걸렸으면 "
                    + "--opt web.vendorPatterns=!<glob>, 벤더 파일도 분석하려면 --opt web.skipVendor=false");
        }
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s*\\R\\s*", " ").strip();
    }
}
