package io.codeatlas.sql.analysis;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.Dialect;
import io.codeatlas.core.spi.FileType;
import io.codeatlas.core.spi.SourceFile;
import io.codeatlas.sql.SqlDiagnosticCodes;
import io.codeatlas.sql.lexer.Syntax;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 방언별 SQL 스크립트(DDL, 데이터 적재) 여러 벌 가운데 프로젝트 전체로 한 방언을 일관되게 고르는 규칙과 그 결과의 요약(F-007-R18~R21).
 *
 * <p>방언의 순위(작을수록 먼저)는 프로젝트에 하나다(F-019-R4. F-007-R23의 순위를 대체한다). mybatis 모듈이 방언별 매퍼를 고르는 순서와 같되
 * 맨 앞 둘의 순서만 다르다(매퍼는 {@code mybatis.dialectVariant}가 {@code --dialect}보다 먼저다). 이 도구의 대상 방언은 Oracle·DB2·PostgreSQL이라,
 * 프로젝트 설정이 대상 외 방언(전자정부 템플릿의 {@code Globals.DbType=mysql})을 가리켜도 대상 방언의 파일이 있으면 그쪽을 고른다:
 * <ol>
 *   <li>{@code --dialect}로 준 방언(oracle, db2, postgres), {@code mybatis.dialectVariant}로 준 방언 이름({@code auto}·{@code all}과 모르는 값은 건너뛴다)</li>
 *   <li>프로젝트 설정({@link ProjectDialect})이 대상 방언(oracle, db2, postgres)을 가리키면 그 방언</li>
 *   <li>Oracle</li>
 *   <li>DB2</li>
 *   <li>PostgreSQL(Oracle·DB2 뒤다: Oracle·DB2 파일이 있는 프로젝트의 기존 결과를 바꾸지 않는다)</li>
 *   <li>Oracle 계열(Tibero, Altibase). 프로젝트 설정이 이 가운데 하나를 가리키면 그것이 먼저다</li>
 *   <li>프로젝트 설정이 가리키는 대상 외 방언</li>
 *   <li>그 밖의 방언: 매퍼 파일과 SQL 스크립트 파일 양쪽의 경로에 다 나오는 방언이 이름의 사전순으로 먼저, 나머지가 사전순으로 그 뒤
 *       ({@link DialectTokens#otherOrder}, F-007-R28. mybatis 모듈도 같은 파일 목록에서 같은 순서를 얻는다: F-009-R26).
 *       방언을 읽지 못한 파일은 처음 물은 순서로 선다: 호출하는 쪽이 경로순으로 물으므로, 그 밖의 방언 파일 어느 것보다 경로상 먼저면
 *       그 밖의 방언들 앞, 아니면 뒤다</li>
 * </ol>
 * 테이블마다 그 테이블을 정의한 파일 가운데 순위가 가장 앞선 방언의 파일을 쓴다. 순위가 프로젝트에 하나이므로, 가장 앞선 방언 파일에 정의가 없는
 * 테이블만 다음 순위에서 가져오게 된다. 파일의 방언은 경로에서 읽고, 경로에 방언 이름이 없으면 내용 특징으로 읽은 것을 쓴다({@link #registerFile}).
 */
public final class DialectSelection {

    /** mybatis 모듈의 옵션 키. DDL 쪽도 읽는다(F-007-R18). */
    public static final String VARIANT_OPTION = "mybatis.dialectVariant";

    /** 명시한 요청 하나: 방언과 그것을 준 옵션 이름. */
    private record Request(String dialect, String option) {
    }

    private final List<Request> requests = new ArrayList<>();
    /** {@code --dialect}. 없으면(auto) null. */
    private final Dialect optionDialect;
    private final ProjectDialect.Setting setting;
    /** 서로 다른 방언을 가리켜 쓰지 않은 프로젝트 설정들. */
    private final List<ProjectDialect.Setting> conflicting;
    private final Map<String, Integer> ranks = new LinkedHashMap<>();
    /** 파일 → 그 파일의 방언(경로의 방언 이름, 없으면 내용 특징). 등록하지 않은 파일은 경로로만 읽는다. */
    private final Map<String, String> fileDialects = new LinkedHashMap<>();
    /** 이 도구의 대상 방언(oracle, db2, postgres). */
    private static final List<String> TARGETS = DialectTokens.TARGETS;
    /** 요청·설정·대상 방언이 아닌 "그 밖의 방언"의 순서(F-007-R28). 그 가운데 하나를 처음 물을 때 이 순서로 한꺼번에 순위를 준다. */
    private final List<String> others;

    // ---- 결과 요약(DdlProcessor와 SqlAnalyzer가 채운다)
    /** 방언이 다른 파일에 중복 정의된 테이블·뷰가 있는가. */
    private boolean ddlVariants;
    /** 방언별로 여러 벌인 것으로 감지한 방언. */
    private final Set<String> detected = new LinkedHashSet<>();
    /** 정의·적재 스크립트가 있는 모든 방언(요청한 방언의 파일이 있는지 볼 때 쓴다). */
    private final Set<String> present = new LinkedHashSet<>();
    /** 방언 → 그 방언 파일의 정의를 쓴 테이블·뷰 수. */
    private final Map<String, Integer> usedTables = new LinkedHashMap<>();
    private final Set<String> excludedFiles = new LinkedHashSet<>();
    private int droppedLoadGroups;
    /** 방언 → 고른 방언의 파일에 적재가 없어 그 방언 파일의 것을 쓴 적재 묶음 수(F-007-R29). */
    private final Map<String, Integer> borrowedLoadGroups = new LinkedHashMap<>();

    /**
     * @param optionDialect {@code --dialect}. 없으면(auto) null
     * @param variantOption {@code mybatis.dialectVariant}의 값. 없으면 null
     * @param setting 프로젝트 설정에서 읽은 방언. 없으면 null
     */
    public DialectSelection(Dialect optionDialect, String variantOption, ProjectDialect.Setting setting) {
        this(optionDialect, variantOption, setting, List.of());
    }

    /** @param conflicting 서로 다른 방언을 가리켜 쓰지 않은 프로젝트 설정들(요약 진단에 적는다) */
    public DialectSelection(Dialect optionDialect, String variantOption, ProjectDialect.Setting setting, List<ProjectDialect.Setting> conflicting) {
        this(optionDialect, variantOption, setting, conflicting, DialectTokens.otherOrder(List.of(), List.of()));
    }

    /**
     * @param others 그 밖의 방언의 순서({@link DialectTokens#otherOrder}). 프로젝트의 파일 목록을 모르면 이름의 사전순이다
     */
    public DialectSelection(Dialect optionDialect, String variantOption, ProjectDialect.Setting setting, List<ProjectDialect.Setting> conflicting,
            List<String> others) {
        this.conflicting = List.copyOf(conflicting);
        this.others = List.copyOf(others);
        this.optionDialect = DialectDetector.fromOption(optionDialect);
        String requested = DialectDetector.tokenOf(this.optionDialect);
        if (requested != null) {
            requests.add(new Request(requested, "--dialect"));
        }
        String variant = DialectTokens.canonical(variantOption);
        if (variant != null && requests.stream().noneMatch(r -> r.dialect().equals(variant))) {
            requests.add(new Request(variant, VARIANT_OPTION));
        }
        this.setting = setting;
        requests.forEach(r -> ranks.putIfAbsent(r.dialect(), ranks.size()));
        String configured = setting == null ? null : setting.dialect();
        if (configured != null && TARGETS.contains(configured)) {
            ranks.putIfAbsent(configured, ranks.size()); // 설정이 대상 방언을 가리킨다
        }
        TARGETS.forEach(d -> ranks.putIfAbsent(d, ranks.size()));
        if (configured != null && DialectTokens.PREFERRED.contains(configured)) {
            ranks.putIfAbsent(configured, ranks.size()); // Oracle 계열 안에서는 설정이 가리키는 것이 먼저다
        }
        DialectTokens.PREFERRED.forEach(d -> ranks.putIfAbsent(d, ranks.size())); // 대상 방언은 위에서 넣었다: 남은 것은 Oracle 계열이다
        if (configured != null) {
            ranks.putIfAbsent(configured, ranks.size()); // 대상 외 방언: 대상 방언과 그 계열 다음, 그 밖의 방언보다 먼저
        }
    }

    /** 옵션과 프로젝트 설정, 프로젝트의 파일 목록(그 밖의 방언의 순서, F-007-R28)을 읽어 만든다. */
    public static DialectSelection of(AnalysisContext ctx) {
        ProjectDialect.Reading reading = ProjectDialect.readAll(ctx);
        List<String> others = DialectTokens.otherOrder(paths(ctx, FileType.MYBATIS_MAPPER), paths(ctx, FileType.SQL));
        return new DialectSelection(DialectDetector.fromOption(ctx.options().dialect()), ctx.options().extra().get(VARIANT_OPTION),
                reading.setting(), reading.conflicting(), others);
    }

    private static List<String> paths(AnalysisContext ctx, FileType type) {
        return ctx.files().ofType(type).stream().map(SourceFile::relativePath).toList();
    }

    /** 옵션도 설정도 없는 기본 순위. */
    public static DialectSelection defaults() {
        return new DialectSelection(null, null, null);
    }

    /**
     * 파일 경로에서 읽은 방언(표준 이름). {@link DialectTokens#ofPath}에 더해 대상 방언의 확장자 힌트({@code .pks}, {@code .db2},
     * {@code .pgsql} 등, {@link DialectDetector#fromPath})도 본다. 없으면 null.
     */
    public static String dialectOfFile(String path) {
        String dialect = DialectTokens.ofPath(path);
        if (dialect == null) {
            dialect = DialectDetector.tokenOf(DialectDetector.fromPath(path));
        }
        return dialect;
    }

    /** 파일의 방언을 등록한다: 경로에 방언 이름이 없어 내용 특징으로 읽은 것까지(F-007-R24). null이면 "방언을 읽지 못한 파일"이다. */
    public void registerFile(String path, String dialect) {
        fileDialects.put(path, dialect);
    }

    /** 파일의 방언(표준 이름): 등록한 것이 있으면 그것, 없으면 경로에서 읽는다. 없으면 null. */
    public String dialectOf(String path) {
        return fileDialects.containsKey(path) ? fileDialects.get(path) : dialectOfFile(path);
    }

    /**
     * 방언의 순위(작을수록 먼저). 요청·설정·대상 방언이 아닌 "그 밖의 방언"은 그 가운데 하나를 처음 물을 때 {@link #others}의 순서로
     * 한꺼번에 순위를 받는다(물은 순서와 무관하다, F-007-R28). null(방언을 읽지 못한 파일)은 처음 물은 순서로 선다: 그 밖의 방언보다
     * 먼저 물으면 그 앞, 아니면 그 뒤다.
     */
    public int rank(String dialect) {
        String key = dialect == null ? "" : dialect;
        Integer rank = ranks.get(key);
        if (rank == null) {
            if (dialect != null) {
                others.forEach(d -> ranks.putIfAbsent(d, ranks.size()));
            }
            ranks.putIfAbsent(key, ranks.size());
            rank = ranks.get(key);
        }
        return rank;
    }

    /** {@code mybatis.dialectVariant}로 준 방언, 없으면 프로젝트 설정이 가리키는 방언(표준 이름). 둘 다 없으면 null. */
    private String declaredDialect() {
        for (Request r : requests) {
            if (r.option().equals(VARIANT_OPTION)) {
                return r.dialect();
            }
        }
        return setting == null ? null : setting.dialect();
    }

    /**
     * 옵션과 프로젝트 설정({@code --dialect}, {@code mybatis.dialectVariant}, 설정 파일)이 정하는 읽기 방식. 정하지 못하면 null
     * ({@link DialectDetector#syntaxOfProject}). SQL 파일의 읽기 방식을 정할 때 쓴다: 파일을 읽는 동안에는 스크립트에서 고른 방언을 아직 모른다.
     */
    public Syntax declaredSyntax() {
        return DialectDetector.syntaxOfProject(optionDialect, declaredDialect(), null);
    }

    /**
     * 프로젝트 방언이 정하는 읽기 방식(F-019-R7): {@link #declaredSyntax}에 더해 스크립트에서 고른 방언({@link #scriptDialect})까지 본다.
     * 내장 소스의 읽기 방식을 정할 때 쓴다(SQL 파일을 모두 읽은 뒤에 부른다). 정하지 못하면 null.
     */
    public Syntax projectSyntax() {
        return DialectDetector.syntaxOfProject(optionDialect, declaredDialect(), scriptDialect());
    }

    /**
     * 스크립트에서 고른 방언(표준 이름): 방언별 여러 벌이면 고른 방언({@link #mainDialect}), 아니면 방언을 읽은 스크립트가 모두 한 방언일 때 그 방언.
     * 방언을 읽은 스크립트가 없거나 방언이 섞였으면 null.
     */
    public String scriptDialect() {
        String main = mainDialect();
        if (main != null) {
            return main;
        }
        Set<String> dialects = new LinkedHashSet<>();
        fileDialects.values().forEach(d -> {
            if (d != null) {
                dialects.add(d);
            }
        });
        return dialects.size() == 1 ? dialects.iterator().next() : null;
    }

    // ------------------------------------------------------------------ 결과 요약

    /** 방언이 다른 파일에 중복 정의된 테이블·뷰의 정의 파일에서 읽은 방언. */
    void detectedDdl(String dialect) {
        ddlVariants = true;
        detected.add(dialect);
    }

    /** 방언이 다른 파일에 같은 테이블의 데이터 적재 묶음이 있다. */
    public void detectedLoad(String dialect) {
        detected.add(dialect);
    }

    /** 정의나 적재 스크립트가 있는 방언. */
    public void present(String dialect) {
        if (dialect != null) {
            present.add(dialect);
        }
    }

    /** 정의를 하나라도 쓴 파일(다른 방언에서 보충한 정의의 파일 포함). */
    private final Set<String> usedDefinitionFiles = new LinkedHashSet<>();

    void usedDefinitionFile(String file) {
        usedDefinitionFiles.add(file);
    }

    public boolean isUsedDefinitionFile(String file) {
        return usedDefinitionFiles.contains(file);
    }

    /** 방언별 스크립트가 여러 벌일 때 고른 방언(감지한 방언 가운데 순위가 가장 앞선 것). 여러 벌이 아니면 null. */
    public String mainDialect() {
        String best = null;
        for (String d : detected) {
            if (best == null || rank(d) < rank(best)) {
                best = d;
            }
        }
        return best;
    }

    /** 이 방언 파일의 정의를 쓴 테이블·뷰 하나. */
    void usedTable(String dialect) {
        usedTables.merge(dialect, 1, Integer::sum);
    }

    /** 방언 변형 파일인데 쓴 것이 하나도 없는 파일. */
    public void excludedFile(String file) {
        excludedFiles.add(file);
    }

    /** 다른 방언 파일의 것이라 만들지 않은 데이터 적재 묶음. */
    public void droppedLoadGroups(int count) {
        droppedLoadGroups += count;
    }

    /** 고른 방언의 파일에 그 테이블의 적재가 없어 이 방언 파일의 것을 쓴 데이터 적재 묶음 하나(F-007-R29). */
    public void borrowedLoadGroup(String dialect) {
        borrowedLoadGroups.merge(dialect, 1, Integer::sum);
    }

    /**
     * 방언별 스크립트가 여러 벌이었으면 요약 진단 하나({@code DIALECT_VARIANT_DDL})를, 요청한 방언의 파일이 하나도 없었으면
     * {@code DIALECT_NOT_FOUND}(WARN)를 남긴다. 위치는 없다(프로젝트 전체에 대한 것이다).
     */
    public void report(GraphModel graph) {
        if (detected.isEmpty()) {
            reportSingleForeign(graph);
            return;
        }
        List<String> dialects = new ArrayList<>(detected);
        dialects.sort(Comparator.comparingInt(this::rank));
        String main = dialects.get(0);

        List<String> missing = new ArrayList<>();
        for (Request r : requests) {
            if (present.contains(r.dialect())) {
                break;
            }
            missing.add(r.dialect() + "(`" + r.option() + "`)");
        }
        if (!missing.isEmpty()) {
            graph.report(Diagnostic.warn(SqlDiagnosticCodes.DIALECT_NOT_FOUND,
                    "요청한 방언 " + String.join(", ", missing) + "의 DDL·데이터 스크립트 파일이 없음: 감지한 방언(" + String.join(", ", dialects)
                            + ") 가운데 " + main + " 파일을 사용", null));
        }

        StringBuilder message = new StringBuilder(ddlVariants ? "방언별 DDL " : "방언별 데이터 적재 스크립트 ").append(dialects.size())
                .append("벌 감지(").append(String.join(", ", dialects)).append("): ").append(main).append(" 파일을 사용");
        if (ddlVariants) {
            message.append("(테이블 ").append(usedTables.getOrDefault(main, 0)).append("개), 다른 방언에서 보충 ");
            List<String> others = new ArrayList<>();
            int supplemented = 0;
            for (Map.Entry<String, Integer> e : usedTables.entrySet()) {
                if (!e.getKey().equals(main)) {
                    supplemented += e.getValue();
                    others.add(e.getKey() + " " + e.getValue() + "개");
                }
            }
            message.append(supplemented).append("개");
            if (!others.isEmpty()) {
                message.append('(').append(String.join(", ", others)).append(')');
            }
        }
        message.append(", 제외한 파일 ").append(excludedFiles.size()).append("개.");
        if (droppedLoadGroups > 0) {
            message.append(" 데이터 적재 스크립트도 같은 규칙으로 골라 다른 방언 파일의 묶음 ").append(droppedLoadGroups).append("개를 만들지 않음.");
        }
        if (!borrowedLoadGroups.isEmpty()) {
            // 적재 묶음은 테이블마다 고른다: 고른 방언의 파일에 적재가 없는 테이블은 다른 방언 파일의 묶음이 남는다(borrowedFrom, F-007-R29)
            List<String> each = new ArrayList<>();
            int borrowed = 0;
            for (Map.Entry<String, Integer> e : borrowedLoadGroups.entrySet()) {
                borrowed += e.getValue();
                each.add(e.getKey() + " " + e.getValue() + "개");
            }
            each.sort(Comparator.comparingInt(x -> rank(x.substring(0, x.indexOf(' ')))));
            message.append(' ').append(main).append(" 파일에 적재가 없는 테이블의 묶음 ").append(borrowed).append("개는 다른 방언 파일의 것을 씀(")
                    .append(String.join(", ", each)).append(").");
        }
        boolean requested = requests.stream().anyMatch(r -> r.dialect().equals(main));
        boolean bySetting = setting != null && setting.dialect().equals(main) && !requested;
        boolean target = DialectTokens.PREFERRED.contains(main);
        if (bySetting) {
            message.append(" 프로젝트 설정(").append(setting.describe()).append(")에 따라 ").append(main).append(" 선택.");
        } else if (setting != null && !requested && target) {
            // 설정은 대상 외 방언(또는 파일이 없는 방언)을 가리키지만 대상 방언의 파일이 있어 그쪽을 골랐다
            message.append(" 프로젝트 설정(").append(setting.describe()).append(")과 다르게 대상 방언 ").append(main).append(" 파일을 사용");
            if (!present.contains(setting.dialect())) {
                message.append("(").append(setting.dialect()).append(" 스크립트 파일은 없음)");
            }
            message.append(". 설정대로 분석하려면 `--opt ").append(VARIANT_OPTION).append("=").append(setting.dialect()).append("`.");
        }
        if (!conflicting.isEmpty()) {
            List<String> each = new ArrayList<>();
            conflicting.forEach(c -> each.add(c.file() + "=" + c.dialect()));
            message.append(" 프로젝트 설정이 엇갈려 쓰지 않음(").append(String.join(", ", each)).append(").");
        }
        // 대상 방언(과 그 계열)의 파일이 하나도 없어 대상 외 방언을 쓰게 된 경우만 경고다(명시해 요청한 방언이면 아니다)
        boolean foreign = !target && !requested;
        if (foreign) {
            message.append(" 이 도구의 대상 방언(Oracle, DB2, PostgreSQL)의 파일이 없어 ").append(main)
                    .append(" 파일을 씀: SQL 해석이 부정확할 수 있음");
        } else {
            message.append(" `--dialect <oracle|db2|postgres>`(그 밖의 방언은 `--opt ").append(VARIANT_OPTION).append("=<방언>`)로 변경");
        }
        graph.report(foreign ? Diagnostic.warn(SqlDiagnosticCodes.DIALECT_VARIANT_DDL, message.toString(), null)
                : Diagnostic.info(SqlDiagnosticCodes.DIALECT_VARIANT_DDL, message.toString(), null));
    }

    /**
     * 방언별 여러 벌이 아닌 프로젝트인데 SQL 스크립트가 대상 외 방언으로만 돼 있으면 INFO 한 건으로 알린다(F-007-R25).
     * 방언을 읽은 스크립트가 없거나 대상 방언(과 그 계열)의 스크립트가 하나라도 있으면 남기지 않는다.
     */
    private void reportSingleForeign(GraphModel graph) {
        Set<String> dialects = new LinkedHashSet<>();
        fileDialects.values().forEach(d -> {
            if (d != null) {
                dialects.add(d);
            }
        });
        if (dialects.isEmpty() || dialects.stream().anyMatch(DialectTokens.PREFERRED::contains)
                || requests.stream().anyMatch(r -> dialects.contains(r.dialect()))) {
            return; // 대상 방언(계열)의 스크립트가 있거나, 그 방언을 명시해 요청했다
        }
        graph.report(Diagnostic.info(SqlDiagnosticCodes.DIALECT_NOT_TARGET, "이 프로젝트의 SQL은 " + String.join(", ", dialects)
                + "로 보임. 대상 방언이 아니어서 일부 구문을 해석하지 못할 수 있음(이 도구의 대상 방언은 Oracle, DB2, PostgreSQL)", null));
    }
}
