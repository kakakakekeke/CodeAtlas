package io.codeatlas.mybatis;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.scan.FileClassifier;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.Analyzer;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.spi.FileType;
import io.codeatlas.core.spi.SourceFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MyBatis 매퍼 XML 분석기(F-009).
 *
 * <p>매퍼 XML을 해석해 매퍼·구문·조각 노드를 만들고, 동적 SQL을 전개한 텍스트를 {@code sql} 내장 소스로 등록한다.
 * SQL 자체는 파싱하지 않는다(뒤 순서의 sql 분석기가 내장 소스를 꺼내 구문 노드에 READS/WRITES 등을 붙인다).
 *
 * <p>두 번에 나눠 읽는다. 1패스: 모든 매퍼 파일을 파싱해 구문·조각·resultMap 정의를 모은다. 2패스: 구문을 전개한다.
 * 다른 파일의 조각을 include하는 구문이 파일 순서와 상관없이 해석되게 하기 위해서다.
 * 그 사이에, 같은 namespace가 방언별 파일로 여러 벌 있으면 id마다 한 방언의 정의만 남긴다({@link DialectVariants}).
 */
public final class MyBatisAnalyzer implements Analyzer {

    /** 미해결 참조 분류. */
    static final String CATEGORY_FRAGMENT = "mybatis-fragment";
    static final String CATEGORY_TYPE = "mybatis-type";
    static final String CATEGORY_RESULT_MAP = "mybatis-resultmap";
    /** 같은 구문 ID가 같은 databaseId로 여러 번 정의됨. */
    static final String DUPLICATE_STATEMENT_ID = "DUPLICATE_STATEMENT_ID";
    /** 방언별 매퍼 여러 벌을 감지해 하나를 골랐다(또는 옵션에 따라 전부 합쳤다). 분석 한 번에 요약 1건. */
    static final String DIALECT_VARIANT_MAPPERS = "DIALECT_VARIANT_MAPPERS";
    /** 요청한 방언({@code mybatis.dialectVariant}, {@code --dialect})의 매퍼 파일이 없어 다른 방언으로 대체했다. sql 모듈도 DDL에 대해 같은 코드를 쓴다. */
    static final String DIALECT_NOT_FOUND = "DIALECT_NOT_FOUND";

    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");
    private static final Set<String> IGNORED_TAGS = Set.of("cache", "cache-ref", "parameterMap");

    @Override
    public String name() {
        return "mybatis";
    }

    @Override
    public int order() {
        return ORDER_PRODUCER;
    }

    @Override
    public void analyze(AnalysisContext ctx) {
        new Session(ctx).run();
    }

    /**
     * {@code <select|insert|update|delete>} 정의 하나.
     *
     * @param id namespace를 뗀 id
     * @param qualifiedId namespace까지 붙은 ID({@link MapperIds})
     */
    private static final java.util.regex.Pattern XML_COMMENT = java.util.regex.Pattern.compile("<!--(.*?)-->", java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern COMMENTED_STATEMENT = java.util.regex.Pattern.compile(
            "<(select|insert|update|delete)\\b[^>]*?\\bid\\s*=\\s*[\"']([^\"']+)[\"']");
    /** 요약 진단에 적는 구문 ID의 상한. */
    private static final int MAX_LISTED = 20;

    private record StatementDef(MapperFile file, XmlElement element, String id, String qualifiedId) {
        String databaseId() {
            return element.attr("databaseId");
        }
    }

    /**
     * 내장 소스로 등록할 SQL 텍스트 하나.
     *
     * @param body 공백을 정리한 전개본 본문. 같은 본문인지 가릴 때 쓴다
     * @param parts 본문의 {@code __DYNAMIC__} 자리에 있던 {@code ${…}} 원문(등장 순서)
     */
    private record SqlPiece(String text, SourceLocation origin, boolean exact, String body, List<String> parts) {

        /** 내장 소스 attrs에 치환 목록을 싣는다({@code ${…}}가 있을 때만, F-009-R21). */
        void putParts(Map<String, String> attrs) {
            if (!parts.isEmpty()) {
                attrs.put(SqlParams.DYNAMIC_PARTS, SqlParams.encodeParts(parts));
            }
        }
    }

    /** 분석 한 번의 상태. */
    private static final class Session implements SqlExpander.Host {

        private final AnalysisContext ctx;
        private final GraphModel graph;
        private final SqlExpander expander = new SqlExpander(this);
        private final ResultMaps resultMaps = new ResultMaps();
        /** namespace → 그 namespace를 정의한 파일들(읽은 순서). */
        private final Map<String, List<MapperFile>> mappers = new LinkedHashMap<>();
        private final Map<String, List<FragmentDef>> fragments = new LinkedHashMap<>();
        private final Map<String, List<StatementDef>> statements = new LinkedHashMap<>();
        private final Map<String, List<ResultMaps.Def>> resultMapDefs = new LinkedHashMap<>();
        private DialectVariants variants;
        /** 방언별 매퍼에서 고른 대표 방언. 방언 변형이 없거나 전부 합치는 방식이면 null. */
        private String mainDialect;
        /** 선택한 방언 파일에서 XML 주석으로 빠져 있어 다른 방언에서 보충하지 않은 구문(namespace.id). */
        private final List<String> commentedOut = new ArrayList<>();
        private final Map<MapperFile, java.util.Set<String>> commentedIdsByFile = new java.util.IdentityHashMap<>();
        /** 같은 진단을 여러 번 남기지 않으려고(전개본마다 같은 include를 다시 지난다). */
        private final Set<String> reported = new HashSet<>();
        private TypeAliases aliases;

        Session(AnalysisContext ctx) {
            this.ctx = ctx;
            this.graph = ctx.graph();
        }

        void run() {
            reportSqlMaps();
            aliases = new TypeAliases(new JavaTypeIndex(ctx));
            new AliasConfigReader(ctx, aliases, this::report).readAll();

            // 1패스: 파싱하고 정의를 모은다
            for (SourceFile file : ctx.files().ofType(FileType.MYBATIS_MAPPER)) {
                ctx.progress(file.relativePath());
                try {
                    readMapper(file);
                } catch (RuntimeException | StackOverflowError e) {
                    report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "매퍼 XML을 읽지 못함: " + e, file.location()));
                }
            }
            // 방언별로 여러 벌인 매퍼는 하나만 남기고, 매퍼 노드를 만든다
            selectDialectVariants();
            declareMappers();
            // 2패스: 조각을 먼저(조각 사이의 INCLUDES가 조각의 namespace 기준으로 먼저 들어가게), 그다음 구문
            fragments.forEach((qualifiedId, defs) -> guarded(defs.get(0).file(), defs.get(0).element(),
                    () -> declareFragment(defs)));
            statements.forEach((nodeId, defs) -> guarded(defs.get(0).file(), defs.get(0).element(),
                    () -> declareStatement(nodeId, defs)));
        }

        private void guarded(MapperFile file, XmlElement element, Runnable work) {
            ctx.progress(file.path());
            try {
                work.run();
            } catch (RuntimeException | StackOverflowError e) {
                report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "매퍼 요소를 해석하지 못함: " + e, file.location(element)));
            }
        }

        // ---- iBatis ----

        private void reportSqlMaps() {
            for (SourceFile file : ctx.files().ofType(FileType.XML)) {
                try {
                    if ("sqlMap".equals(FileClassifier.sniffXmlRoot(file.absolutePath()))) {
                        report(Diagnostic.info(DiagnosticCodes.UNSUPPORTED_SYNTAX,
                                "iBatis 2.x sqlMap 파일은 분석하지 않는다", file.location()));
                    }
                } catch (java.io.IOException | RuntimeException e) {
                    // 읽지 못한 파일은 스캐너나 담당 분석기가 보고한다.
                }
            }
        }

        // ---- 1패스 ----

        private void readMapper(SourceFile source) {
            XmlDocument doc = XmlTreeParser.parse(source.readText());
            XmlElement root = doc.root();
            String path = source.relativePath();
            if (doc.failed()) {
                SourceLocation at = doc.errorLine() > 0
                        ? source.location(doc.errorLine(), doc.errorColumn(), doc.errorLine(), doc.errorColumn())
                        : source.location();
                report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED,
                        "매퍼 XML 파싱 실패(오류 지점 전에 끝난 구문만 등록한다): " + doc.errorMessage(), at));
            }
            for (String entity : doc.skippedEntities()) {
                report(Diagnostic.info(DiagnosticCodes.UNSUPPORTED_SYNTAX,
                        "외부 엔티티 &" + entity + ";는 읽지 않고 비워 둔다", source.location()));
            }
            if (root == null || !root.name.equals("mapper")) {
                return;
            }
            String namespace = root.attr("namespace");
            if (namespace == null) {
                report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED,
                        "매퍼에 namespace가 없어 구문 ID를 만들 수 없다", doc.location(path, root)));
                return;
            }
            MapperFile file = new MapperFile(source, doc, namespace, DialectVariants.dialectOf(path));
            mappers.computeIfAbsent(namespace, k -> new ArrayList<>()).add(file);

            for (XmlElement child : root.elements()) {
                if (!child.closed) {
                    // 오류로 끝까지 읽지 못한 요소는 본문이 불완전하다.
                    continue;
                }
                String id = child.attr("id");
                if (STATEMENT_TAGS.contains(child.name)) {
                    if (id == null) {
                        report(Diagnostic.warn(DiagnosticCodes.PARSE_FAILED,
                                "<" + child.name + ">에 id가 없어 건너뛴다", file.location(child)));
                        continue;
                    }
                    MapperIds.ElementId elementId = elementId(file, child, id);
                    statements.computeIfAbsent(MapperIds.statementNodeId(elementId.qualifiedId()), k -> new ArrayList<>())
                            .add(new StatementDef(file, child, elementId.shortId(), elementId.qualifiedId()));
                } else if (child.name.equals("sql")) {
                    if (id == null) {
                        report(Diagnostic.warn(DiagnosticCodes.PARSE_FAILED, "<sql>에 id가 없어 건너뛴다", file.location(child)));
                        continue;
                    }
                    MapperIds.ElementId elementId = elementId(file, child, id);
                    fragments.computeIfAbsent(elementId.qualifiedId(), k -> new ArrayList<>())
                            .add(new FragmentDef(file, child, elementId.shortId(), elementId.qualifiedId()));
                } else if (child.name.equals("resultMap")) {
                    if (id != null) {
                        MapperIds.ElementId elementId = elementId(file, child, id);
                        resultMapDefs.computeIfAbsent(elementId.qualifiedId(), k -> new ArrayList<>())
                                .add(new ResultMaps.Def(file, child, elementId.shortId()));
                    }
                } else if (!IGNORED_TAGS.contains(child.name)) {
                    report(Diagnostic.info(DiagnosticCodes.UNSUPPORTED_SYNTAX,
                            "매퍼의 알 수 없는 요소 <" + child.qName + ">를 건너뛴다", file.location(child)));
                }
            }
        }

        /**
         * 요소의 id를 namespace로 한정한다. id가 이미 {@code <namespace>.}로 시작하면 다시 붙이지 않는다.
         * 점이 있는데 namespace로 시작하지 않는 id는 MyBatis가 오류로 보는 것이라 원문을 그대로 쓰고 경고한다.
         */
        private MapperIds.ElementId elementId(MapperFile file, XmlElement element, String id) {
            MapperIds.ElementId elementId = MapperIds.of(file.namespace(), id);
            if (elementId.foreign()) {
                report(Diagnostic.warn(DiagnosticCodes.PARSE_FAILED,
                        "<" + element.name + "> id \"" + id + "\"에 점이 있는데 namespace \"" + file.namespace()
                                + "\"로 시작하지 않는다(MyBatis는 오류로 본다). 원문을 그대로 ID로 쓴다", file.location(element)));
            }
            return elementId;
        }

        // ---- 방언별 매퍼 ----

        private List<String> paths(FileType type) {
            return ctx.files().ofType(type).stream().map(SourceFile::relativePath).toList();
        }

        /** 방언별로 여러 벌 있는 정의 가운데 하나만 남긴다(F-009-R15). resultMap은 남긴 것을 등록한다. */
        private void selectDialectVariants() {
            String option = ctx.options().extra().get(DialectVariants.OPTION);
            String forced = null;
            boolean all = false;
            if (option != null && !option.isBlank() && !option.trim().equalsIgnoreCase("auto")) {
                all = option.trim().equalsIgnoreCase("all");
                forced = all ? null : DialectVariants.canonical(option);
                if (!all && forced == null) {
                    report(Diagnostic.warn(DiagnosticCodes.INVALID_OPTION,
                            DialectVariants.OPTION + "=" + option + "는 아는 방언 이름이 아니다(auto, all, oracle, db2, postgres, mysql 등). auto로 본다",
                            null));
                }
            }
            ProjectDialect.Reading reading = ProjectDialect.readAll(ctx);
            // 그 밖의 방언끼리의 순서는 프로젝트의 파일 목록(매퍼와 SQL 스크립트의 경로)에서 정한다: sql 모듈과 같은 방언을 고르게(F-009-R26)
            List<String> others = DialectVariants.otherOrder(paths(FileType.MYBATIS_MAPPER), paths(FileType.SQL));
            variants = new DialectVariants(forced, all, ctx.options().dialect(), reading.setting(), reading.conflicting(), others, mappers);
            statements.replaceAll((nodeId, defs) -> variants.select(defs, StatementDef::file));
            dropCommentedOut();
            // 대표 방언: 감지한 방언(선호 순서) 가운데 구문을 하나라도 고른 첫 방언. 그 밖의 방언에서 온 구문은 "보충한 구문"이다(F-009-R25)
            if (variants.detected() && !all) {
                java.util.Set<String> withStatements = new java.util.HashSet<>();
                statements.values().forEach(defs -> {
                    String dialect = variants.selectedVariantOf(defs.get(0).file());
                    if (dialect != null) {
                        withStatements.add(dialect);
                    }
                });
                List<String> dialects = variants.dialects();
                mainDialect = dialects.stream().filter(withStatements::contains).findFirst().orElse(dialects.get(0));
            }
            fragments.replaceAll((qualifiedId, defs) -> variants.select(defs, FragmentDef::file));
            resultMapDefs.forEach((qualifiedId, defs) -> resultMaps.add(qualifiedId, variants.select(defs, ResultMaps.Def::file).get(0)));
        }

        /**
         * 선택한 방언 파일에서 XML 주석으로 뺀 구문은 다른 방언 파일에서 보충하지 않는다(F-009-R25): 그 namespace에서 선호 순서가 가장 앞인
         * 방언의 파일에 같은 id의 구문이 주석 안에 있으면, 그 방언에서는 일부러 뺀 구문으로 보고 구문을 만들지 않는다.
         */
        private void dropCommentedOut() {
            if (!variants.detected() || variants.all()) {
                return;
            }
            statements.entrySet().removeIf(entry -> {
                StatementDef def = entry.getValue().get(0);
                String dialect = variants.selectedVariantOf(def.file());
                List<MapperFile> files = mappers.get(def.file().namespace());
                String best = files == null ? null : variants.bestDialect(files);
                if (dialect == null || best == null || dialect.equals(best)) {
                    return false;
                }
                for (MapperFile file : files) {
                    if (best.equals(file.dialect()) && commentedIds(file).contains(def.id())) {
                        commentedOut.add(def.qualifiedId());
                        return true;
                    }
                }
                return false;
            });
        }

        /** 매퍼 파일의 XML 주석 안에 있는 구문 요소의 id들. */
        private java.util.Set<String> commentedIds(MapperFile file) {
            return commentedIdsByFile.computeIfAbsent(file, f -> {
                java.util.Set<String> ids = new java.util.HashSet<>();
                java.util.regex.Matcher comment = XML_COMMENT.matcher(f.doc().raw());
                while (comment.find()) {
                    java.util.regex.Matcher statement = COMMENTED_STATEMENT.matcher(comment.group(1));
                    while (statement.find()) {
                        ids.add(statement.group(2));
                    }
                }
                return ids;
            });
        }

        private void declareMappers() {
            mappers.forEach((namespace, files) -> {
                String mapperId = Ids.mybatisMapper(namespace);
                MapperFile primary = variants.primaryFile(files);
                XmlElement root = primary.doc().root();
                SourceLocation rootLocation = root.startOffset >= 0
                        ? primary.doc().location(primary.path(), root.startOffset, root.closed ? root.endOffset : root.bodyStart)
                        : primary.location(root);
                Node.Builder node = Node.builder(mapperId, NodeKind.MYBATIS_MAPPER)
                        .name(TypeAliases.simpleName(namespace)).qualifiedName(namespace)
                        .location(rootLocation).attr("namespace", namespace);
                String dialect = variants.selectedVariantOf(primary);
                if (dialect != null) {
                    node.attr("dialectVariant", dialect);
                }
                graph.declare(node.build());
                for (MapperFile file : files) {
                    if (variants.contributes(file)) {
                        graph.addEdge(Edge.of(file.source().nodeId(), mapperId, EdgeKind.CONTAINS));
                    }
                }
            });
            if (!variants.detected()) {
                return;
            }
            List<String> dialects = variants.dialects();
            String detected = "방언별 매퍼 " + dialects.size() + "벌 감지(" + String.join(", ", dialects) + "): ";
            if (variants.all()) {
                report(Diagnostic.info(DIALECT_VARIANT_MAPPERS, detected + DialectVariants.OPTION
                        + "=all이라 모든 방언 파일의 정의를 한 구문에 합쳤다", null));
                return;
            }
            Map<String, List<String>> statementsByDialect = new LinkedHashMap<>();
            statements.values().forEach(defs -> {
                String dialect = variants.selectedVariantOf(defs.get(0).file());
                if (dialect != null) {
                    statementsByDialect.computeIfAbsent(dialect, k -> new ArrayList<>()).add(defs.get(0).qualifiedId());
                }
            });
            String main = mainDialect != null ? mainDialect : dialects.get(0);
            List<String> missing = variants.missingRequests();
            if (!missing.isEmpty()) {
                // 요청한 방언의 매퍼 파일이 하나도 없다: 조용히 다른 방언으로 가지 않고 무엇으로 대체했는지 알린다(F-009-R20)
                report(Diagnostic.warn(DIALECT_NOT_FOUND, "요청한 방언 " + String.join(", ", missing) + "의 매퍼 파일이 없음: 감지한 방언("
                        + String.join(", ", dialects) + ") 가운데 " + main + " 파일을 사용", null));
            }
            StringBuilder message = new StringBuilder(detected).append(main).append(" 파일을 사용, 제외한 파일 ")
                    .append(variants.excludedFileCount()).append("개.");
            // 다른 방언 파일에서 보충한 구문: 방언별 수와 구문 ID(전체 20개까지, F-009-R25)
            List<String> others = new ArrayList<>();
            int[] listed = {0};
            statementsByDialect.forEach((dialect, ids) -> {
                if (!dialect.equals(main)) {
                    others.add(dialect + " " + ids.size() + "개: " + listIds(ids, listed));
                }
            });
            if (!others.isEmpty()) {
                message.append(' ').append(main).append(" 파일에 없는 구문은 다른 방언 파일에서 가져왔다(")
                        .append(String.join("; ", others)).append(").");
            }
            if (!commentedOut.isEmpty()) {
                message.append(' ').append(main).append(" 파일에서 XML 주석으로 뺀 구문 ").append(commentedOut.size())
                        .append("개는 보충하지 않음(").append(listIds(commentedOut, new int[] {0})).append(").");
            }
            ProjectDialect.Setting setting = variants.setting();
            boolean requested = variants.requested(main);
            boolean bySetting = setting != null && setting.dialect().equals(main) && !requested;
            boolean target = DialectVariants.PREFERRED.contains(main);
            if (bySetting) {
                message.append(" 프로젝트 설정(").append(setting.describe()).append(")에 따라 ").append(main).append(" 선택.");
            } else if (setting != null && !requested && target) {
                // 설정은 대상 외 방언을 가리키지만 대상 방언의 매퍼가 있어 그쪽을 골랐다(F-009-R24)
                message.append(" 프로젝트 설정(").append(setting.describe()).append(")과 다르게 대상 방언 ").append(main)
                        .append(" 파일을 사용. 설정대로 분석하려면 `--opt ").append(DialectVariants.OPTION).append("=")
                        .append(setting.dialect()).append("`.");
            }
            if (!variants.conflicting().isEmpty()) {
                List<String> each = new ArrayList<>();
                variants.conflicting().forEach(c -> each.add(c.file() + "=" + c.dialect()));
                message.append(" 프로젝트 설정이 엇갈려 쓰지 않음(").append(String.join(", ", each)).append(").");
            }
            if (!target && !requested) {
                // 대상 방언(Oracle, DB2, PostgreSQL과 Oracle 계열)의 매퍼가 하나도 없어 대상 외 방언을 쓴다: WARN으로 알린다(F-009-R24, F-019-R4)
                message.append(" 이 도구의 대상 방언(Oracle, DB2, PostgreSQL)의 파일이 없어 ").append(main).append(" 파일을 씀: SQL 해석이 부정확할 수 있음");
                report(Diagnostic.warn(DIALECT_VARIANT_MAPPERS, message.toString(), null));
                return;
            }
            message.append(" `--opt ").append(DialectVariants.OPTION).append("=<방언|all>`로 변경");
            report(Diagnostic.info(DIALECT_VARIANT_MAPPERS, message.toString(), null));
        }

        /** 구문 ID를 쉼표로 잇는다. 요약 하나에 적는 ID는 모두 합쳐 {@link #MAX_LISTED}개까지고 넘으면 "외 n개"를 붙인다. */
        private String listIds(List<String> ids, int[] listed) {
            List<String> shown = new ArrayList<>();
            for (String id : ids) {
                if (listed[0] >= MAX_LISTED) {
                    break;
                }
                shown.add(id);
                listed[0]++;
            }
            String text = String.join(", ", shown);
            int rest = ids.size() - shown.size();
            return rest > 0 ? (text.isEmpty() ? "" : text + " ") + "외 " + rest + "개" : text;
        }

        // ---- 2패스: 조각 ----

        private void declareFragment(List<FragmentDef> defs) {
            FragmentDef first = defs.get(0);
            String nodeId = first.nodeId();
            String mapperId = Ids.mybatisMapper(first.namespace());
            Node.Builder node = Node.builder(nodeId, NodeKind.SQL_FRAGMENT)
                    .name(first.id()).qualifiedName(first.qualifiedId()).location(first.file().location(first.element()))
                    .attr("namespace", first.namespace()).attr("fragmentId", first.id());
            graph.declare(node.build());
            graph.addEdge(Edge.of(mapperId, nodeId, EdgeKind.CONTAINS));

            String sql = null;
            boolean dynamic = false;
            Set<String> unknownTags = new LinkedHashSet<>();
            for (FragmentDef def : defs) {
                SqlExpander.Expansion expansion = expander.expand(def.element(), def.file(), nodeId, def.databaseId(), def.qualifiedId());
                dynamic |= expansion.dynamic();
                unknownTags.addAll(expansion.unknownTags());
                if (sql == null && !expansion.variants().isEmpty()) {
                    sql = SqlText.forAttr(SqlParams.restore(expansion.variants().get(0), expansion.partsOf(0)));
                }
            }
            reportUnknownTags("조각 " + first.qualifiedId(), unknownTags, first.file().location(first.element()));
            node.attr("sql", sql == null ? "" : sql).attr("dynamic", dynamic);
            String dialectVariant = variants.selectedVariantOf(first.file());
            if (dialectVariant != null) {
                node.attr("dialectVariant", dialectVariant);
            }
            List<String> databaseIds = databaseIds(defs.stream().map(FragmentDef::databaseId).toList());
            if (!databaseIds.isEmpty()) {
                node.attr("databaseIds", databaseIds);
            }
            graph.declare(node.build());
        }

        // ---- 2패스: 구문 ----

        private void declareStatement(String nodeId, List<StatementDef> defs) {
            StatementDef first = defs.get(0);
            String namespace = first.file().namespace();
            XmlElement element = first.element();
            String qualifiedId = first.qualifiedId();
            Node.Builder node = Node.builder(nodeId, NodeKind.SQL_STATEMENT)
                    .name(first.id()).qualifiedName(qualifiedId).location(first.file().location(element))
                    .attr("namespace", namespace).attr("statementId", first.id()).attr("tag", element.name);
            String dialectVariant = variants.selectedVariantOf(first.file());
            if (dialectVariant != null) {
                node.attr("dialectVariant", dialectVariant);
                if (mainDialect != null && !dialectVariant.equals(mainDialect)) {
                    node.attr("borrowedFrom", dialectVariant); // 고른 방언 파일에 없어 다른 방언 파일에서 가져온 구문(F-009-R25)
                }
            }
            // 주인 노드는 내장 소스보다 먼저 있어야 한다(계약 4.1).
            graph.declare(node.build());
            graph.addEdge(Edge.of(Ids.mybatisMapper(namespace), nodeId, EdgeKind.CONTAINS));

            copyAttr(element, "parameterType", node);
            copyAttr(element, "resultType", node);
            copyAttr(element, "resultMap", node);
            copyAttr(element, "keyProperty", node);
            copyIntAttr(element, "fetchSize", node);
            copyIntAttr(element, "timeout", node);
            if (element.attr("useGeneratedKeys") != null) {
                node.attr("useGeneratedKeys", Boolean.parseBoolean(element.attr("useGeneratedKeys")));
            }
            String jdbcType = element.attr("statementType");
            if (jdbcType != null) {
                node.attr("jdbcStatementType", jdbcType.toUpperCase(Locale.ROOT));
            }

            String representative = null;
            // 사람이 볼 sql 속성: ${…} 자리를 내부 표식(__DYNAMIC__)이 아니라 원문으로 적는다(F-009-R21)
            String representativeSql = null;
            boolean dynamic = false;
            boolean hasSelectKey = false;
            int variantCount = 0;
            int keyVariantCount = 0;
            Set<String> seenDefinitions = new HashSet<>();
            boolean duplicated = false;
            // 본문이 같은 전개본은 정의가 여러 벌이어도 한 번만 등록한다(databaseId가 다르면 다른 것으로 본다).
            Set<String> seenBodies = new HashSet<>();
            Set<String> seenKeyBodies = new HashSet<>();
            Set<String> unknownTags = new LinkedHashSet<>();
            Set<String> resultColumns = new LinkedHashSet<>();
            Set<String> nestedSelects = new LinkedHashSet<>();

            for (StatementDef def : defs) {
                // 방언별 파일의 정의를 전부 합치는 방식에서는 방언이 다른 파일의 같은 id를 중복으로 보지 않는다.
                String fileDialect = variants.variantOf(def.file());
                duplicated |= !seenDefinitions.add(def.databaseId() + "|" + (variants.all() && fileDialect != null ? fileDialect : ""));
                Map<String, String> sourceAttrs = new LinkedHashMap<>();
                if (def.databaseId() != null) {
                    sourceAttrs.put("dialect", def.databaseId().toLowerCase(Locale.ROOT));
                    sourceAttrs.put("databaseId", def.databaseId());
                } else if (fileDialect != null) {
                    // 방언별 파일에서 온 구문은 그 파일의 방언을 힌트로 넘긴다.
                    sourceAttrs.put("dialect", fileDialect);
                }
                SourceLocation location = def.file().location(def.element());

                // 본문
                SqlExpander.Expansion expansion = expander.expand(def.element(), def.file(), nodeId, def.databaseId(), null);
                dynamic |= expansion.dynamic();
                unknownTags.addAll(expansion.unknownTags());
                if (representative == null && !expansion.variants().isEmpty()) {
                    representative = expansion.variants().get(0);
                    representativeSql = SqlParams.restore(representative, expansion.partsOf(0));
                }
                List<SqlPiece> pieces = pieces(def.file(), def.element(), expansion, location);
                for (SqlPiece piece : pieces) {
                    if (!seenBodies.add(def.databaseId() + "|" + piece.body())) {
                        continue;
                    }
                    Map<String, String> attrs = new LinkedHashMap<>(sourceAttrs);
                    attrs.put("variant", String.valueOf(++variantCount));
                    piece.putParts(attrs);
                    ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, nodeId, piece.text(), piece.origin(), piece.exact(), attrs));
                }
                if (expansion.truncated()) {
                    report(Diagnostic.warn(DiagnosticCodes.UNSUPPORTED_SYNTAX,
                            "구문 " + qualifiedId + "의 전개본이 상한 " + SqlExpander.MAX_VARIANTS
                                    + "개를 넘어 일부 <choose> 분기나 <if> 등치 조건의 값을 전개하지 못했다", location));
                }
                Set<String> dollars = new LinkedHashSet<>(expansion.dollars());

                // selectKey
                for (XmlElement selectKey : def.element().elements("selectKey")) {
                    hasSelectKey = true;
                    SqlExpander.Expansion keyExpansion = expander.expand(selectKey, def.file(), nodeId, def.databaseId(), null);
                    dynamic |= keyExpansion.dynamic();
                    unknownTags.addAll(keyExpansion.unknownTags());
                    dollars.addAll(keyExpansion.dollars());
                    for (SqlPiece piece : pieces(def.file(), selectKey, keyExpansion, def.file().location(selectKey))) {
                        if (!seenKeyBodies.add(def.databaseId() + "|" + piece.body())) {
                            continue;
                        }
                        Map<String, String> attrs = new LinkedHashMap<>(sourceAttrs);
                        attrs.put("selectKey", "true");
                        attrs.put("variant", String.valueOf(++keyVariantCount));
                        piece.putParts(attrs);
                        ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, nodeId, piece.text(), piece.origin(), piece.exact(), attrs));
                    }
                }

                if (!dollars.isEmpty()) {
                    report(Diagnostic.info(DiagnosticCodes.DYNAMIC_SQL,
                            "구문 " + qualifiedId + "에 ${} 치환이 있어 일부를 정적으로 알 수 없다: ${"
                                    + String.join("}, ${", dollars) + "}", location));
                }

                usesTypes(nodeId, def, resultColumns, nestedSelects);
            }

            reportUnknownTags("구문 " + qualifiedId, unknownTags, first.file().location(element));
            if (duplicated) {
                report(Diagnostic.warn(DUPLICATE_STATEMENT_ID,
                        "구문 " + qualifiedId + "가 같은 databaseId로 " + defs.size()
                                + "번 정의되어 있다. 전개본을 모두 등록한다", first.file().location(element)));
            }

            boolean callable = "CALLABLE".equalsIgnoreCase(jdbcType) || (representative != null && SqlText.isCall(representative));
            boolean merge = !callable && representative != null && SqlText.isMerge(representative);
            node.attr("statementType", callable ? "CALL" : merge ? "MERGE" : element.name.toUpperCase(Locale.ROOT));
            node.attr("sql", representativeSql == null ? "" : SqlText.forAttr(representativeSql));
            node.attr("dynamic", dynamic);
            node.attr("variants", variantCount);
            if (hasSelectKey) {
                node.attr("selectKey", true);
            }
            List<String> databaseIds = databaseIds(defs.stream().map(StatementDef::databaseId).toList());
            if (!databaseIds.isEmpty()) {
                node.attr("databaseIds", databaseIds);
            }
            if (!resultColumns.isEmpty()) {
                node.attr("resultColumns", List.copyOf(resultColumns));
            }
            if (!nestedSelects.isEmpty()) {
                node.attr("nestedSelects", List.copyOf(nestedSelects));
            }
            graph.declare(node.build());
        }

        /**
         * 요소 본문의 내장 소스 텍스트. 동적 태그도 include도 없으면 원본 위치를 그대로 유지한 텍스트 하나(exact),
         * 아니면 전개본들(exact 아님, origin은 요소의 시작 위치).
         */
        private List<SqlPiece> pieces(MapperFile file, XmlElement element, SqlExpander.Expansion expansion, SourceLocation location) {
            if (expansion.variants().isEmpty()) {
                return List.of();
            }
            // <bind>는 본문에 넣지 않는 요소라 공백으로 지우면 위치를 유지할 수 있다(동적 태그로 세지 않는다).
            if (!expansion.dynamicTags() && !expansion.includes() && expansion.variants().size() == 1) {
                SqlPiece exact = exactPiece(file, element, expansion.variants().get(0));
                if (exact != null) {
                    return List.of(exact);
                }
            }
            List<SqlPiece> result = new ArrayList<>();
            for (int i = 0; i < expansion.variants().size(); i++) {
                String variant = expansion.variants().get(i);
                result.add(new SqlPiece(variant, location, false, SqlText.collapse(variant), expansion.partsOf(i)));
            }
            return result;
        }

        /** 위치를 보존한 본문. 보존할 수 없으면 null. */
        private SqlPiece exactPiece(MapperFile file, XmlElement element, String expanded) {
            if (!element.hasPositions()) {
                return null;
            }
            List<int[]> blanks = new ArrayList<>();
            for (XmlElement child : element.elements()) {
                // 여기까지 왔으면 자식 요소는 selectKey, bind처럼 본문에 넣지 않는 것뿐이다.
                if (!child.hasPositions()) {
                    return null;
                }
                blanks.add(new int[] {child.startOffset, child.endOffset});
            }
            String raw = file.doc().raw();
            String preserved = StaticBody.preserve(raw, element.bodyStart, element.bodyEnd, blanks);
            if (preserved == null) {
                return null;
            }
            SqlParams.Result padded = SqlParams.padded(preserved);
            if (padded == null) {
                return null;
            }
            String text = padded.text();
            // 파서가 풀어 준 본문과 같은 내용인지 확인한다(다르면 위치 보존을 포기한다).
            if (!withoutWhitespace(text).equals(withoutWhitespace(expanded))) {
                return null;
            }
            int start = 0;
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
                start++;
            }
            int end = text.length();
            while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
                end--;
            }
            if (start == end) {
                return null;
            }
            SourceLocation origin = file.doc().location(file.path(), element.bodyStart + start, element.bodyStart + end);
            return new SqlPiece(text.substring(start, end), origin, true, SqlText.collapse(expanded), padded.originals());
        }

        private static String withoutWhitespace(String s) {
            StringBuilder out = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                if (!Character.isWhitespace(s.charAt(i))) {
                    out.append(s.charAt(i));
                }
            }
            return out.toString();
        }

        // ---- USES_TYPE ----

        private void usesTypes(String nodeId, StatementDef def, Set<String> resultColumns, Set<String> nestedSelects) {
            XmlElement element = def.element();
            String parameterType = element.attr("parameterType");
            if (parameterType != null) {
                usesType(nodeId, parameterType, "parameter", def.file().attrLocation(element, "parameterType"));
            }
            String resultType = element.attr("resultType");
            if (resultType != null) {
                usesType(nodeId, resultType, "result", def.file().attrLocation(element, "resultType"));
            }
            String resultMap = element.attr("resultMap");
            if (resultMap != null) {
                SourceLocation location = def.file().attrLocation(element, "resultMap");
                ResultMaps.Usage usage = resultMaps.usage(resultMap, def.file().namespace());
                for (String type : usage.types()) {
                    usesType(nodeId, type, "resultMap", location);
                }
                for (String missing : usage.missing()) {
                    unresolvedEdge(nodeId, EdgeKind.USES_TYPE, CATEGORY_RESULT_MAP, missing, location, "resultMap");
                }
                resultColumns.addAll(usage.columns());
                nestedSelects.addAll(usage.nestedSelects());
            }
        }

        private void usesType(String nodeId, String typeName, String role, SourceLocation location) {
            TypeAliases.Resolution resolution = aliases.resolve(typeName);
            switch (resolution.kind()) {
                case JDK -> {
                    // JDK 타입은 엣지를 만들지 않는다. 원문은 구문 노드의 속성에 있다.
                }
                case RESOLVED, INFERRED -> {
                    String typeId = Ids.javaType(resolution.fqn());
                    graph.reference(typeId, NodeKind.JAVA_TYPE, TypeAliases.simpleName(resolution.fqn()));
                    Edge.Builder edge = Edge.builder(nodeId, typeId, EdgeKind.USES_TYPE)
                            .confidence(resolution.kind() == TypeAliases.Kind.INFERRED ? Confidence.INFERRED : Confidence.RESOLVED)
                            .location(location).attr("role", role);
                    if (!typeName.equals(resolution.fqn())) {
                        edge.attr("alias", typeName);
                    }
                    graph.addEdge(edge.build());
                }
                case UNRESOLVED -> unresolvedEdge(nodeId, EdgeKind.USES_TYPE, CATEGORY_TYPE, typeName, location, role);
            }
        }

        /** 미해결 노드 + UNRESOLVED 엣지 + 진단. {@code GraphModel.addUnresolvedEdge}와 같되 엣지에 role을 붙인다. */
        private void unresolvedEdge(String from, EdgeKind kind, String category, String text, SourceLocation location, String role) {
            String id = Ids.unresolved(category, text);
            graph.declare(Node.builder(id, NodeKind.UNRESOLVED).name(text).qualifiedName(text).location(location)
                    .attr("category", category).build());
            Edge.Builder edge = Edge.builder(from, id, kind).unresolved().location(location);
            if (role != null) {
                edge.attr("role", role);
            }
            graph.addEdge(edge.build());
            report(Diagnostic.warn(DiagnosticCodes.UNRESOLVED_REFERENCE, "해석하지 못한 참조(" + category + "): " + text, location));
        }

        // ---- SqlExpander.Host ----

        @Override
        public FragmentDef findFragment(String qualifiedId, String databaseId, MapperFile from) {
            List<FragmentDef> defs = fragments.get(qualifiedId);
            if (defs == null) {
                return null;
            }
            if (defs.size() > 1) {
                // 여러 파일에 정의된 조각(방언별 파일을 전부 합칠 때, 중복 정의)은 구문과 같은 파일, 같은 방언 파일의 것을 먼저 본다.
                List<FragmentDef> near = defs.stream().filter(def -> def.file() == from).toList();
                if (near.isEmpty() && from.dialect() != null) {
                    near = defs.stream().filter(def -> from.dialect().equals(def.file().dialect())).toList();
                }
                if (!near.isEmpty()) {
                    defs = near;
                }
            }
            FragmentDef noDatabaseId = null;
            for (FragmentDef def : defs) {
                if (def.databaseId() == null) {
                    noDatabaseId = noDatabaseId == null ? def : noDatabaseId;
                } else if (def.databaseId().equalsIgnoreCase(databaseId)) {
                    return def;
                }
            }
            return noDatabaseId != null ? noDatabaseId : defs.get(0);
        }

        @Override
        public void included(String fromNodeId, FragmentDef target, Confidence confidence, SourceLocation location) {
            graph.addEdge(Edge.builder(fromNodeId, target.nodeId(), EdgeKind.INCLUDES).confidence(confidence).location(location).build());
        }

        @Override
        public void unresolvedInclude(String fromNodeId, String reference, SourceLocation location) {
            unresolvedEdge(fromNodeId, EdgeKind.INCLUDES, CATEGORY_FRAGMENT, reference, location, null);
        }

        @Override
        public void cyclicInclude(String fromNodeId, String chain, SourceLocation location) {
            report(Diagnostic.warn(DiagnosticCodes.UNSUPPORTED_SYNTAX, "순환 include를 끊었다: " + chain, location), "cyclic|" + location);
        }

        /** 알 수 없는 태그(구식 iBatis 태그 등)는 구문·조각마다 한 번만 알린다. */
        private void reportUnknownTags(String owner, Set<String> tags, SourceLocation location) {
            if (!tags.isEmpty()) {
                report(Diagnostic.info(DiagnosticCodes.UNSUPPORTED_SYNTAX,
                        owner + "에 알 수 없는 태그 <" + String.join(">, <", tags) + ">가 있어 본문만 전개한다", location));
            }
        }

        // ---- 공통 ----

        private void report(Diagnostic diagnostic) {
            report(diagnostic, diagnostic.code() + "|" + diagnostic.location() + "|" + locationKey(diagnostic.location()) + "|" + diagnostic.message());
        }

        /** 같은 key의 진단은 한 번만 남긴다. */
        private void report(Diagnostic diagnostic, String key) {
            if (reported.add(key)) {
                graph.report(diagnostic);
            }
        }

        private static String locationKey(SourceLocation location) {
            return location == null ? "" : location.startLine() + ":" + location.startColumn();
        }

        private static void copyAttr(XmlElement element, String name, Node.Builder node) {
            String value = element.attr(name);
            if (value != null) {
                node.attr(name, value);
            }
        }

        private static void copyIntAttr(XmlElement element, String name, Node.Builder node) {
            String value = element.attr(name);
            if (value == null) {
                return;
            }
            try {
                node.attr(name, Integer.parseInt(value));
            } catch (NumberFormatException e) {
                node.attr(name, value);
            }
        }

        private static List<String> databaseIds(List<String> values) {
            List<String> result = new ArrayList<>();
            for (String value : values) {
                if (value != null && !result.contains(value)) {
                    result.add(value);
                }
            }
            return result;
        }
    }
}
