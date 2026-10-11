package io.codeatlas.sql;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.Analyzer;
import io.codeatlas.core.spi.Dialect;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.spi.FileType;
import io.codeatlas.core.spi.SourceFile;
import io.codeatlas.sql.analysis.Catalog;
import io.codeatlas.sql.analysis.DataLoadGrouper;
import io.codeatlas.sql.analysis.DdlProcessor;
import io.codeatlas.sql.analysis.DialectDetector;
import io.codeatlas.sql.analysis.DialectSelection;
import io.codeatlas.sql.analysis.DialectTokens;
import io.codeatlas.sql.analysis.DynamicText;
import io.codeatlas.sql.analysis.FactExtractor;
import io.codeatlas.sql.analysis.FactsWriter;
import io.codeatlas.sql.analysis.Locator;
import io.codeatlas.sql.analysis.SqlIds;
import io.codeatlas.sql.analysis.StatementFacts;
import io.codeatlas.sql.ast.Ddl;
import io.codeatlas.sql.ast.Pos;
import io.codeatlas.sql.ast.Statement;
import io.codeatlas.sql.lexer.Lexer;
import io.codeatlas.sql.lexer.Token;
import io.codeatlas.sql.parser.DdlParser;
import io.codeatlas.sql.parser.ParseProblem;
import io.codeatlas.sql.parser.ParseResult;
import io.codeatlas.sql.parser.SqlParser;
import io.codeatlas.sql.routine.RoutineBodyHandler;
import io.codeatlas.sql.script.HeaderOnlyRoutineHandler;
import io.codeatlas.sql.script.RoutineContext;
import io.codeatlas.sql.script.RoutineHeader;
import io.codeatlas.sql.script.RoutineUnit;
import io.codeatlas.sql.script.RoutineUnitHandler;
import io.codeatlas.sql.script.ScriptSplitter;
import io.codeatlas.sql.script.ScriptUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQL 분석기(소비자, order 200). SQL·DDL 파일과 {@code sql} 내장 소스를 분석한다.
 *
 * <ol>
 *   <li>모든 SQL 파일을 실행 단위로 흘려 읽으며 DDL과 루틴 헤더로 카탈로그를 만든다. 데이터 적재 INSERT는 테이블별 묶음에 더하고
 *       단위를 놓아 준다(큰 적재 스크립트가 메모리에 쌓이지 않게, F-006-R15).</li>
 *   <li>DDL 결과(테이블, 컬럼, 키, 뷰, 시퀀스, 시노님)와 루틴·패키지 노드를 그래프에 쓴다.</li>
 *   <li>루틴 단위는 {@link RoutineUnitHandler}(기본은 본문까지 분석하는 {@link RoutineBodyHandler})에 넘기고, 파일의 DML 구문과 뷰 정의 쿼리를 분석한다.</li>
 *   <li>내장 소스를 주인 노드별로 분석해 결과를 합친다.</li>
 * </ol>
 *
 * 파일 하나, 구문 하나의 실패는 진단으로 남기고 계속한다.
 */
public final class SqlAnalyzer implements Analyzer {

    private static final int SQL_ATTR_LIMIT = 2000;

    private final RoutineUnitHandler routineHandler;

    /** 루틴 본문까지 분석한다({@link RoutineBodyHandler}, F-008). */
    public SqlAnalyzer() {
        this(new RoutineBodyHandler());
    }

    /** 루틴 단위를 다른 핸들러로 처리한다. 헤더만 읽으려면 {@link HeaderOnlyRoutineHandler}를 준다. null이면 기본 핸들러다. */
    public SqlAnalyzer(RoutineUnitHandler routineHandler) {
        this.routineHandler = routineHandler == null ? new RoutineBodyHandler() : routineHandler;
    }

    @Override
    public String name() {
        return "sql";
    }

    @Override
    public int order() {
        return ORDER_CONSUMER;
    }

    /** 파일 하나를 나눈 결과. 데이터 적재 묶음에 들어간 INSERT 단위는 여기 남기지 않는다(F-006-R15). */
    private static final class FileUnits {
        final String path;
        /** 구문 노드의 dialect에 적을 방언(옵션, 경로의 Oracle·DB2 이름, 구문 특징 순). 모르면 null. */
        Dialect dialect;
        /** 파일의 방언 이름(표준 이름): 경로에서 읽고, 경로에 없으면 내용 특징으로 읽는다(F-007-R24). 없으면 null. */
        String token;
        /** 판정이 없을 때 루틴·블록의 문법을 고르는 힌트({@code --dialect}). 구문의 dialect로는 적지 않는다(F-006-R37). */
        Dialect hint;
        /** MySQL로 판정한 파일: 줄 처음의 #을 주석으로 읽는다(F-006-R27). */
        boolean mysql;
        /** 대상 외 방언으로 판정한 파일: 구문 특징으로 Oracle·DB2를 추측해 적지 않는다(F-006-R28). */
        boolean foreign;
        /** 묶음을 모두 버린 적재 파일(F-007-R20·R29): 초기화 구문도 노드로 만들지 않는다. */
        boolean loadExcluded;
        /**
         * 고르지 않은 방언의 파일: DML 구문을 만들지 않는다(F-007-R27). 적재 묶음은 테이블마다 고르므로(F-007-R29) 남을 수 있고,
         * 남은 묶음의 테이블을 비우는 초기화 구문만 함께 남는다.
         */
        boolean dialectExcluded;
        /** 고른 방언이 아닌 방언의 파일이면 그 방언: 남은 묶음과 초기화 구문에 {@code borrowedFrom}으로 적는다(F-007-R29). 아니면 null. */
        String borrowedFrom;
        /** 버린 묶음의 테이블: 그 테이블을 비우는 초기화 구문도 노드로 만들지 않는다. */
        final Set<String> droppedLoads = new HashSet<>();
        final List<UnitWork> units = new ArrayList<>();
        final DataLoadGrouper loads;
        /** 파일의 모양(종결자 수, 줄 시작 구문 키워드 수). 나누다 실패했으면 null. */
        ScriptSplitter.Shape shape;
        /** 이 파일에서 얻은 것의 수: DDL 구문, 루틴, 구문 노드, 적재 묶음. */
        int obtained;
        /** 정의·DML 키워드로 시작하지만 조용히 건너뛰는 관리 구문(CREATE USER, ALTER SESSION 등)의 수. */
        int silentContentUnits;

        FileUnits(String path, Dialect dialect, String token) {
            this.path = path;
            this.dialect = dialect;
            this.token = token;
            this.loads = new DataLoadGrouper(path);
        }
    }

    /**
     * 단위 하나의 작업 상태. 루틴 단위와 구문 노드가 될 DDL(뷰, CTAS)은 토큰까지 쥐고 있고, 그 밖의 구문은 본문과 위치만 남겼다가
     * 다시 볼 때 렉싱한다(토큰이 본문보다 열 배쯤 크다. 구문이 많은 스크립트에서 메모리를 아낀다, F-006-R15).
     */
    private static final class UnitWork {
        private ScriptUnit unit;
        private final ScriptUnit.Kind kind;
        private final String text;
        private final Pos pos;
        private final int offset;
        RoutineHeader header;
        ParseResult ddl;
        /** 데이터 적재 묶음으로 넘어가 따로 처리하지 않는 단위. */
        boolean grouped;
        /** 조건 없는 DELETE·TRUNCATE(초기화 구문). 적재 파일이면 구문 노드에 dataLoad를 붙인다(F-006-R24). */
        boolean reset;
        /** 초기화 구문이 비우는 테이블(묶음의 열쇠와 같은 꼴). 초기화 구문이 아니면 null. */
        String resetTable;
        /** MySQL로 판정한 파일의 단위(다시 렉싱할 때 줄 처음의 #을 주석으로 읽는다). */
        private final boolean mysql;

        UnitWork(ScriptUnit unit, boolean mysql) {
            this.mysql = mysql;
            this.unit = unit;
            this.kind = unit.kind();
            this.text = unit.text();
            this.pos = unit.pos();
            this.offset = unit.first().offset();
        }

        Pos pos() {
            return pos;
        }

        /** 단위(토큰 포함). 놓아 준 뒤면 본문을 다시 렉싱해 만든다. */
        ScriptUnit unit() {
            if (unit == null) {
                List<Token> tokens = Lexer.tokenizeAt(text, offset, pos.line(), pos.column(), mysql);
                unit = new ScriptUnit(kind, tokens.subList(0, tokens.size() - 1), text);
            }
            return unit;
        }

        /** 토큰을 놓아 준다. */
        void release() {
            unit = null;
        }
    }

    @Override
    public void analyze(AnalysisContext ctx) {
        GraphModel graph = ctx.graph();
        Catalog catalog = new Catalog();
        Dialect optionDialect = DialectDetector.fromOption(ctx.options().dialect());
        // 방언별 스크립트 여러 벌 가운데 쓸 방언의 순위(옵션, 프로젝트 설정, 대상 방언 순). 고른 결과의 요약도 여기 모인다(F-007-R18~R21)
        DialectSelection selection = DialectSelection.of(ctx);

        // 1. 파일을 단위로 흘려 읽는다: DDL과 루틴 헤더 → 카탈로그, 데이터 적재 INSERT → 묶음(단위는 놓아 준다)
        List<FileUnits> files = new ArrayList<>();
        DdlProcessor ddl = new DdlProcessor(catalog, graph, selection);
        for (SourceFile file : ctx.files().ofType(FileType.SQL)) {
            String path = file.relativePath();
            ctx.progress(path); // 힙이 모자랄 때 어느 파일을 읽던 중이었는지 알린다(F-006-R23)
            String pathToken = DialectSelection.dialectOfFile(path);
            FileUnits fu = new FileUnits(path, null, pathToken);
            fu.hint = optionDialect;
            selection.registerFile(path, pathToken);
            try {
                String text = file.readText();
                files.add(fu);
                // 파일의 방언: 경로의 방언 이름이 먼저고, 없으면 내용 특징(CREATE MEMORY TABLE, 백틱, VARCHAR2 등)으로 읽는다(F-007-R24)
                String token = pathToken != null ? pathToken : DialectDetector.contentDialect(text);
                fu.token = token;
                selection.registerFile(path, token);
                // 구문 노드에 적을 방언은 파일에서 판정한 것만이다: --dialect 옵션은 찍지 않는다(F-006-R37)
                Dialect pathDialect = "oracle".equals(token) ? Dialect.ORACLE : "db2".equals(token) ? Dialect.DB2 : null;
                fu.dialect = pathDialect;
                // MySQL 파일인가: 경로의 방언 이름이 먼저고, 이름이 없으면 옵션(--dialect면 아니다), 그다음 본문의 MySQL 특징이다
                fu.mysql = pathToken != null ? DialectTokens.isMySql(pathToken)
                        : optionDialect == null && (selection.mySqlProject() || DialectDetector.mysqlFeatures(text));
                // 대상 외 방언의 파일이면 구문 특징으로 Oracle·DB2를 추측하지 않는다(dialect를 비워 둔다)
                fu.foreign = pathDialect == null && (token != null || fu.mysql);
                // 옵션과 경로로 방언을 모르면 같은 훑기에서 구문 특징으로 판정한다
                DialectDetector.Scorer scorer = pathDialect == null && !fu.foreign ? new DialectDetector.Scorer() : null;
                fu.shape = ScriptSplitter.forEach(text, unit -> firstPass(graph, catalog, ddl, fu, unit), scorer == null ? null : scorer::accept,
                        fu.mysql);
                if (scorer != null) {
                    fu.dialect = scorer.result();
                }
            } catch (RuntimeException | StackOverflowError e) {
                graph.report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "SQL 파일을 읽거나 나누지 못함: " + e, SourceLocation.ofFile(path)));
            }
            // 파일을 다 봐야 데이터 적재 파일인지 안다: 적재 파일이면 쥐고 있던 소량의 INSERT도 묶음으로 넘어간다(F-006-R24)
            fu.loads.endOfFile();
            for (Object released : fu.loads.takeReleased()) {
                ((UnitWork) released).grouped = true;
            }
        }

        // 2. DDL 결과와 루틴 노드를 그래프에
        try {
            ddl.finish();
        } catch (RuntimeException | StackOverflowError e) {
            graph.report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "DDL 결과를 기록하지 못함: " + e, null));
        }
        selectLoadVariants(files, selection, catalog);
        selection.report(graph);
        Map<UnitWork, String> routineNodeIds = new LinkedHashMap<>();
        for (boolean specs : new boolean[] {false, true}) {
            for (FileUnits fu : files) {
                for (UnitWork work : fu.units) {
                    if (work.grouped || work.header == null || (work.header.kind() == RoutineHeader.Kind.PACKAGE) != specs) {
                        continue;
                    }
                    try {
                        routineNodeIds.put(work, declareRoutine(graph, catalog, fu, work));
                    } catch (RuntimeException | StackOverflowError e) {
                        graph.report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "루틴 헤더를 기록하지 못함: " + e,
                                Locator.ofFile(fu.path, work.pos()).locate(null)));
                    }
                }
            }
        }

        // 3. 루틴 본문(확장 지점), DML 구문, 뷰 정의 쿼리
        FactExtractor extractor = new FactExtractor(catalog);
        Map<Pos, DdlProcessor.PendingQuery> pendingByUnit = new LinkedHashMap<>();
        Map<String, Map<Pos, DdlProcessor.PendingQuery>> pendingByFile = new LinkedHashMap<>();
        for (DdlProcessor.PendingQuery p : ddl.pendingQueries()) {
            pendingByFile.computeIfAbsent(p.file(), k -> new LinkedHashMap<>()).put(p.unitPos(), p);
        }
        for (FileUnits fu : files) {
            ctx.progress(fu.path);
            Map<Pos, DdlProcessor.PendingQuery> pending = pendingByFile.getOrDefault(fu.path, pendingByUnit);
            try {
                dataLoads(graph, catalog, fu);
            } catch (RuntimeException | StackOverflowError e) {
                graph.report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "데이터 적재 묶음을 기록하지 못함: " + e, SourceLocation.ofFile(fu.path)));
            }
            for (UnitWork work : fu.units) {
                if (work.grouped || (work.reset && !resetKept(fu, work))) {
                    continue; // 묶음으로 넘어간 INSERT, 버린 묶음의 테이블을 비우는 초기화 구문
                }
                if (fu.dialectExcluded && work.header == null && work.ddl == null && !work.reset) {
                    continue; // 고르지 않은 방언의 파일에 든 DML 구문(방언별 생성 스크립트의 INSERT 등, F-007-R27)
                }
                Locator locator = Locator.ofFile(fu.path, work.pos());
                ScriptUnit unit = null;
                try {
                    unit = work.unit();
                    if (work.header != null) {
                        String nodeId = routineNodeIds.get(work);
                        if (nodeId != null) {
                            fu.obtained++;
                            routineHandler.handle(new RoutineUnit(unit, work.header, fu.path, nodeId),
                                    new RoutineContext(graph, catalog, fu.dialect, fu.path, locator).fallbackDialect(fu.hint));
                        } else {
                            graph.report(Diagnostic.info(DiagnosticCodes.UNSUPPORTED_SYNTAX,
                                    "지원하지 않는 루틴 단위: " + firstWords(unit), locator.locate(unit.pos())));
                        }
                    } else if (work.ddl != null) {
                        if (work.ddl.statement() instanceof Ddl) {
                            fu.obtained++;
                        } else if (work.ddl.statement() instanceof Statement.Other) {
                            fu.silentContentUnits++;
                        }
                        ddlUnit(graph, catalog, extractor, fu, work, pending.get(unit.pos()), locator);
                    } else if (dmlUnit(graph, catalog, extractor, fu, unit, locator)) {
                        fu.obtained++;
                        if (work.reset && fu.loads.loadFile()) {
                            // 적재 파일의 초기화 구문: 애플리케이션이 실행하는 구문이 아니다. 소비자가 적재 구문과 함께 기본으로 뺀다
                            Map<String, Object> attrs = new LinkedHashMap<>();
                            attrs.put("dataLoad", true);
                            attrs.put("rowCount", 0);
                            if (fu.borrowedFrom != null) {
                                attrs.put("borrowedFrom", fu.borrowedFrom); // 고른 방언이 아닌 방언의 파일에서 온 초기화 구문(F-007-R29)
                            }
                            graph.putAttrs(Ids.sqlStatement(fu.path, unit.startLine()), attrs);
                        }
                    }
                } catch (RuntimeException | StackOverflowError e) {
                    graph.report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "구문을 분석하지 못함: " + e, locator.locate(work.pos())));
                }
                work.release();
            }
            nothingRead(graph, fu);
        }

        // 4. 내장 소스
        Map<String, List<EmbeddedSource>> byOwner = new LinkedHashMap<>();
        for (EmbeddedSource source : ctx.embedded(EmbeddedSource.SQL)) {
            byOwner.computeIfAbsent(source.ownerNodeId(), k -> new ArrayList<>()).add(source);
        }
        for (Map.Entry<String, List<EmbeddedSource>> entry : byOwner.entrySet()) {
            SourceLocation origin = entry.getValue().get(0).origin();
            ctx.progress(origin == null || origin.file() == null ? entry.getKey() : origin.file());
            try {
                embedded(graph, catalog, extractor, optionDialect, selection, entry.getKey(), entry.getValue());
            } catch (RuntimeException | StackOverflowError e) {
                SourceLocation where = entry.getValue().get(0).origin();
                graph.report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "내장 SQL을 분석하지 못함(" + entry.getKey() + "): " + e, where));
            }
        }

        // 5. DB의 사전 뷰·카탈로그 테이블: DDL에 없는 것이 당연하다는 표시를 테이블 노드에 남긴다(F-006-R40)
        for (String table : catalog.systemCatalogTables()) {
            String id = io.codeatlas.sql.analysis.SqlIds.table(table);
            if (graph.hasNode(id)) {
                graph.putAttrs(id, Map.of("systemCatalog", true));
            }
        }
    }

    // ------------------------------------------------------------------ 1패스: 단위 하나

    /** 단위 하나를 처음 본다: 루틴 헤더와 DDL은 카탈로그에, 데이터 적재 INSERT는 묶음에 넣는다. 나중에 볼 단위만 fu.units에 남긴다. */
    private static void firstPass(GraphModel graph, Catalog catalog, DdlProcessor ddl, FileUnits fu, ScriptUnit unit) {
        UnitWork work = new UnitWork(unit, fu.mysql);
        Locator locator = Locator.ofFile(fu.path, unit.pos());
        try {
            if (unit.kind() == ScriptUnit.Kind.ROUTINE) {
                fu.loads.noteOther();
                work.header = RoutineHeader.parse(unit.tokens());
                registerRoutine(catalog, work.header, unit);
            } else if (unit.kind() == ScriptUnit.Kind.STATEMENT && DdlParser.isDdl(unit.tokens())) {
                if (!DataLoadGrouper.isSessionCommand(unit)) {
                    fu.loads.noteOther();
                }
                work.ddl = new DdlParser(new ArrayList<>(unit.tokens())).parse();
                ddl.apply(work.ddl.statement(), fu.path, locator, unit.pos(), unit.text());
            } else if (unit.kind() == ScriptUnit.Kind.STATEMENT && unit.first().is("INSERT")) {
                DataLoadGrouper.Candidate candidate = DataLoadGrouper.candidate(new SqlParser(new ArrayList<>(unit.tokens())).parseStatement());
                if (candidate != null) {
                    DataLoadGrouper.Offer offer = fu.loads.offer(work, unit, candidate);
                    for (Object released : fu.loads.takeReleased()) {
                        ((UnitWork) released).grouped = true;
                    }
                    if (offer == DataLoadGrouper.Offer.GROUPED) {
                        return; // 묶음에 더했다: 단위를 쥐고 있지 않는다
                    }
                } else {
                    fu.loads.noteOther();
                }
            } else if (unit.kind() == ScriptUnit.Kind.STATEMENT && (unit.first().is("DELETE") || unit.first().is("TRUNCATE"))
                    && (work.resetTable = DataLoadGrouper.resetTable(new SqlParser(new ArrayList<>(unit.tokens())).parseStatement())) != null) {
                work.reset = true; // 테이블을 비우는 초기화 구문: 적재 파일이면 dataLoad를 붙인다
                fu.loads.noteReset();
            } else if (!DataLoadGrouper.isSessionCommand(unit)) {
                fu.loads.noteOther();
            }
        } catch (RuntimeException | StackOverflowError e) {
            graph.report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "DDL을 해석하지 못함: " + e, locator.locate(unit.pos())));
        }
        if (unit.kind() == ScriptUnit.Kind.STATEMENT && !isQueryDdl(work)) {
            work.release();
            if (work.ddl != null && work.ddl.statement() instanceof Ddl) {
                // 구문 트리는 DdlProcessor가 쓰고 버린다. 여기에는 "DDL이었다"는 표시와 파서가 남긴 문제만 둔다
                work.ddl = new ParseResult(APPLIED_DDL, work.ddl.problems(), List.of(), work.ddl.pos());
            }
        }
        fu.units.add(work);
    }

    /** 카탈로그에 반영을 맡긴 DDL 구문의 자리 표시(구문 트리를 쥐고 있지 않으려고 쓴다). */
    private static final Ddl APPLIED_DDL = new Ddl.Multi(List.of());

    /** 구문 노드가 될 DDL(뷰 정의, CREATE TABLE AS SELECT)인가. 이런 단위는 구문 트리와 토큰을 나중에 다시 쓴다. */
    private static boolean isQueryDdl(UnitWork work) {
        if (work.ddl == null) {
            return false; // DML 구문: 다시 볼 때 렉싱한다
        }
        Statement st = work.ddl.statement();
        return st instanceof Ddl.CreateView || (st instanceof Ddl.CreateTable t && t.asQuery() != null);
    }

    /** 데이터 적재 파일의 묶음을 그래프에 쓰고 파일마다 INFO 진단 하나를 남긴다(F-006-R15, F-006-R24). */
    private static void dataLoads(GraphModel graph, Catalog catalog, FileUnits fu) {
        if (fu.loads.groupCount() == 0) {
            return;
        }
        fu.loads.write(graph, catalog, fu.dialect, !fu.foreign);
        fu.obtained += fu.loads.groupCount();
        StringBuilder message = new StringBuilder("데이터 적재 파일: 리터럴 INSERT ").append(fu.loads.statementCount()).append("건을 테이블별 구문 ")
                .append(fu.loads.groupCount()).append("개로 묶음");
        if (fu.loads.smallGroupCount() > 0) {
            message.append("(").append(DataLoadGrouper.MIN_STATEMENTS).append("건 미만인 테이블 ").append(fu.loads.smallGroupCount()).append("개 포함)");
        }
        // 묶음과 함께 버린 초기화 구문(다른 방언 파일의 묶음을 고른 테이블의 것)은 세지 않는다(F-007-R29)
        long resets = fu.units.stream().filter(work -> work.reset && resetKept(fu, work)).count();
        if (resets > 0) {
            message.append(", 초기화 구문(조건 없는 DELETE·TRUNCATE) ").append(resets).append("건도 dataLoad로 표시");
        }
        graph.report(Diagnostic.info(SqlDiagnosticCodes.DATA_LOAD_GROUPED, message.toString(), SourceLocation.ofFile(fu.path)));
    }

    /** 정의나 DML로 보이는 줄이 있는데 테이블도 구문도 얻지 못한 파일이면 WARN 진단을 남긴다(F-006-R22). */
    private static void nothingRead(GraphModel graph, FileUnits fu) {
        if (fu.obtained > 0 || fu.shape == null || fu.shape.contentLines() <= fu.silentContentUnits) {
            return;
        }
        graph.report(Diagnostic.warn(DiagnosticCodes.PARSE_FAILED,
                "이 파일에서 읽은 것이 없음: 정의나 DML로 보이는 줄이 " + fu.shape.contentLines() + "개 있지만 테이블·구문을 얻지 못함",
                SourceLocation.ofFile(fu.path)));
    }

    // ------------------------------------------------------------------ 루틴

    private static void registerRoutine(Catalog catalog, RoutineHeader header, ScriptUnit unit) {
        if (header.name() == null) {
            return;
        }
        String name = header.name().name();
        catalog.addSchema(header.name().qualifier());
        switch (header.kind()) {
            case PROCEDURE:
            case FUNCTION:
            case TRIGGER:
                catalog.addRoutine(null, name);
                if (header.bodyStart() >= 0) {
                    catalog.addRoutineBody(null, name);
                }
                break;
            case PACKAGE:
            case PACKAGE_BODY:
                catalog.addPackage(name);
                for (RoutineHeader.Member m : header.members()) {
                    catalog.addRoutine(name, m.name());
                    if (m.hasBody()) {
                        catalog.addRoutineBody(name, m.name());
                    }
                }
                // 패키지 수준 변수: 본문의 SQL에서 컬럼으로 오인하지 않게 한다(명세와 본문이 다른 파일일 수 있다)
                catalog.addPackageVariables(name, RoutineBodyHandler.packageVariableNames(unit.tokens(), header));
                break;
            default:
                break;
        }
    }

    /** 루틴·패키지 노드를 선언한다. @return 단위의 노드 ID. 선언할 수 없는 단위면 null */
    private static String declareRoutine(GraphModel graph, Catalog catalog, FileUnits fu, UnitWork work) {
        RoutineHeader header = work.header;
        if (header.name() == null) {
            return null;
        }
        ScriptUnit unit = work.unit();
        Locator locator = Locator.ofFile(fu.path, unit.pos());
        String name = header.name().name();
        String schema = header.name().qualifier();
        String dialect = fu.dialect == null ? null : fu.dialect.name();
        String fileId = Ids.file(fu.path);
        switch (header.kind()) {
            case PROCEDURE:
            case FUNCTION:
            case TRIGGER: {
                String id = SqlIds.routine(null, name);
                Node.Builder node = Node.builder(id, NodeKind.DB_ROUTINE).name(name)
                        .qualifiedName(schema == null ? name : schema + "." + name).location(locator.locate(unit.pos()))
                        .attr("routineType", header.kind().routineType()).attr("dialect", dialect).attr("schema", schema)
                        .attr("triggerEvent", header.triggerEvent());
                if (!header.parameters().isEmpty()) {
                    node.attr("parameters", header.parameters());
                }
                graph.declare(node.build());
                graph.addEdge(Edge.of(fileId, id, EdgeKind.CONTAINS));
                if (header.triggerTable() != null && !header.triggerTable().dynamic()) {
                    String table = header.triggerTable().name();
                    catalog.addSchema(header.triggerTable().qualifier());
                    String tableId = SqlIds.table(table);
                    graph.reference(tableId, NodeKind.DB_TABLE, table);
                    graph.addEdge(Edge.of(id, tableId, EdgeKind.TRIGGERS_ON, locator.locate(header.triggerTable().pos())));
                }
                return id;
            }
            case PACKAGE:
            case PACKAGE_BODY: {
                String id = SqlIds.dbPackage(name);
                Node.Builder node = Node.builder(id, NodeKind.DB_PACKAGE).name(name)
                        .qualifiedName(schema == null ? name : schema + "." + name).location(locator.locate(unit.pos()))
                        .attr("dialect", dialect).attr("schema", schema);
                boolean hadBody = graph.node(id).map(n -> n.attrBoolean("hasBody")).orElse(false);
                if (header.kind() == RoutineHeader.Kind.PACKAGE_BODY || hadBody) {
                    node.attr("hasBody", true);
                }
                graph.declare(node.build());
                graph.addEdge(Edge.of(fileId, id, EdgeKind.CONTAINS));
                for (RoutineHeader.Member member : header.members()) {
                    String memberId = SqlIds.routine(name, member.name());
                    Token first = unit.tokens().get(member.startToken());
                    Token last = unit.tokens().get(Math.max(member.startToken(), Math.min(member.endToken(), unit.tokens().size()) - 1));
                    Node.Builder m = Node.builder(memberId, NodeKind.DB_ROUTINE).name(member.name())
                            .qualifiedName(name + "." + member.name()).location(locator.locate(Pos.span(first, last)))
                            .attr("routineType", member.kind().routineType()).attr("dialect", dialect).attr("package", name);
                    if (!member.parameters().isEmpty()) {
                        m.attr("parameters", member.parameters());
                    }
                    graph.declare(m.build());
                    graph.addEdge(Edge.of(id, memberId, EdgeKind.CONTAINS));
                }
                return id;
            }
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ SQL 파일의 구문

    private void ddlUnit(GraphModel graph, Catalog catalog, FactExtractor extractor, FileUnits fu, UnitWork work,
            DdlProcessor.PendingQuery pending, Locator locator) {
        ScriptUnit unit = work.unit();
        ParseResult result = work.ddl;
        if (result.statement() instanceof Statement.Unparsed u) {
            graph.report(Diagnostic.info(DiagnosticCodes.UNSUPPORTED_SYNTAX, "지원하지 않는 구문: " + DynamicText.display(u.keyword()), locator.locate(unit.pos())));
            return;
        }
        reportProblems(graph, result.problems(), false, locator, unit.pos(), new HashSet<>());
        if (pending == null || pending.query() == null) {
            return;
        }
        String statementId = Ids.sqlStatement(fu.path, unit.startLine());
        boolean view = result.statement() instanceof Ddl.CreateView;
        StatementFacts facts = view ? extractor.extract(pending.query()) : extractor.extract(result.statement());
        declareStatement(graph, statementId, fu, unit, locator);
        String container = view ? SqlIds.view(pending.target().name) : Ids.file(fu.path);
        graph.addEdge(Edge.of(container, statementId, EdgeKind.CONTAINS));
        new FactsWriter(graph, catalog, statementId).add(facts, locator).finish(dialectOf(fu, unit));
    }

    /** @return 구문 노드를 만들었으면 true */
    private boolean dmlUnit(GraphModel graph, Catalog catalog, FactExtractor extractor, FileUnits fu, ScriptUnit unit, Locator locator) {
        if (unit.kind() == ScriptUnit.Kind.BLOCK) {
            // 익명 블록: 본문 파서(F-008)로 분석해 블록의 구문 노드 하나에 모은다
            String statementId = Ids.sqlStatement(fu.path, unit.startLine());
            declareStatement(graph, statementId, fu, unit, locator);
            graph.addEdge(Edge.of(Ids.file(fu.path), statementId, EdgeKind.CONTAINS));
            FactsWriter writer = new FactsWriter(graph, catalog, statementId);
            Dialect dialect = dialectOf(fu, unit);
            RoutineBodyHandler.analyzeBlock(unit.tokens(), unit.text(), graph, catalog, dialect != null ? dialect : fu.hint, writer, locator, false, new HashSet<>());
            writer.finish(dialect);
            return true;
        }
        ParseResult result = new SqlParser(new ArrayList<>(unit.tokens())).parseStatement();
        Statement statement = result.statement();
        if (statement instanceof Statement.Empty || statement instanceof Statement.Other) {
            return false;
        }
        if (statement instanceof Statement.Unparsed u) {
            graph.report(Diagnostic.info(DiagnosticCodes.UNSUPPORTED_SYNTAX, "지원하지 않는 구문: " + DynamicText.display(u.keyword()), locator.locate(unit.pos())));
            return false;
        }
        StatementFacts facts = extractor.extract(result);
        if (hasDynamic(unit.tokens())) {
            facts.markDynamic();
        }
        String statementId = Ids.sqlStatement(fu.path, unit.startLine());
        declareStatement(graph, statementId, fu, unit, locator);
        graph.addEdge(Edge.of(Ids.file(fu.path), statementId, EdgeKind.CONTAINS));
        new FactsWriter(graph, catalog, statementId).add(facts, locator).finish(dialectOf(fu, unit));
        reportProblems(graph, result.problems(), false, locator, unit.pos(), new HashSet<>());
        return true;
    }

    /** 구문 노드에 적을 방언: 파일의 방언, 모르면 그 구문의 특징. 대상 외 방언의 파일이면 적지 않는다(null, F-006-R28). */
    private static boolean hasDynamic(List<Token> tokens) {
        for (Token t : tokens) {
            if (t.isDynamic()) {
                return true;
            }
        }
        return false;
    }

    private static Dialect dialectOf(FileUnits fu, ScriptUnit unit) {
        return fu.dialect != null ? fu.dialect : fu.foreign ? null : DialectDetector.fromTokens(unit.tokens());
    }

    /**
     * 초기화 구문(조건 없는 DELETE·TRUNCATE)을 노드로 만드는가. 묶음을 모두 버린 파일의 것과, 버린 묶음의 테이블을 비우는 것은 만들지 않는다
     * (F-007-R20·R29). 고르지 않은 방언의 파일(F-007-R27)에서는 남은 묶음의 테이블을 비우는 것만 만든다.
     */
    private static boolean resetKept(FileUnits fu, UnitWork work) {
        if (fu.loadExcluded || fu.droppedLoads.contains(work.resetTable)) {
            return false;
        }
        return !fu.dialectExcluded || fu.loads.tables().contains(work.resetTable);
    }

    /**
     * 방언별 스크립트가 여러 벌인 프로젝트에서 데이터 적재 묶음을 <b>테이블마다</b> 한 방언의 것만 남긴다(F-007-R20·R29).
     *
     * <ul>
     *   <li>테이블의 적재 묶음을 가진 파일(방언을 읽은 것)들 가운데 DDL과 같은 방언 순위로 가장 앞선 방언의 파일 것만 남긴다. 그 방언의 파일이
     *       여럿이면(같은 방언의 픽스처 여러 개) 모두 남긴다.</li>
     *   <li>남은 묶음이 고른 방언({@link DialectSelection#mainDialect})이 아닌 방언의 파일 것이면 {@code borrowedFrom=<방언>}을 붙인다:
     *       고른 방언의 파일에 그 테이블의 적재가 없다.</li>
     *   <li>고른 방언이 아니고 정의도 쓰지 않은 파일(F-007-R27의 "고르지 않은 방언의 파일")의 묶음은 그 테이블이 DDL에 정의돼 있을 때만 남긴다.
     *       정의가 없는 테이블(방언별 comment 스크립트의 {@code _CUB_SCHEMA_COMMENTS})의 유령 노드를 방언 파일마다 만들지 않는다.
     *       그 파일의 DML 구문은 전처럼 만들지 않는다(방언별 생성 스크립트에 섞인 INSERT).</li>
     *   <li>방언을 읽지 못한 파일의 묶음은 고르는 대상이 아니다. 방언별 여러 벌이 감지되지 않은 프로젝트에서는 아무것도 버리지 않는다.</li>
     * </ul>
     * 묶음을 모두 버린 파일은 "제외한 파일"이고, 버린 묶음의 테이블을 비우는 초기화 구문도 노드로 만들지 않는다({@link #resetKept}).
     */
    private static void selectLoadVariants(List<FileUnits> files, DialectSelection selection, Catalog catalog) {
        Map<String, List<FileUnits>> loaders = new LinkedHashMap<>();
        for (FileUnits fu : files) {
            if (fu.loads.groupCount() == 0) {
                continue;
            }
            selection.present(fu.token);
            if (fu.token != null) {
                for (String table : fu.loads.tables()) {
                    loaders.computeIfAbsent(table, k -> new ArrayList<>()).add(fu);
                }
            }
        }
        // 같은 테이블의 묶음이 둘 이상의 방언에 있으면 방언별 적재 스크립트다(DDL 중복이 없어도 방언별 여러 벌로 감지한다)
        Set<FileUnits> variantFiles = new java.util.LinkedHashSet<>();
        loaders.values().stream().filter(list -> list.stream().map(fu -> fu.token).distinct().count() >= 2).forEach(variantFiles::addAll);
        for (FileUnits fu : files) { // 방언의 순위는 DDL과 같다(그 밖의 방언끼리는 물은 순서와 무관하다: F-007-R28)
            if (variantFiles.contains(fu)) {
                selection.rank(fu.token);
                selection.detectedLoad(fu.token);
            }
        }
        String main = selection.mainDialect();
        if (main == null) {
            return; // 방언별 여러 벌이 아니다
        }
        Set<FileUnits> hadGroups = new HashSet<>();
        int dropped = 0;
        for (Map.Entry<String, List<FileUnits>> e : loaders.entrySet()) {
            String table = e.getKey();
            String best = e.getValue().get(0).token;
            for (FileUnits fu : e.getValue()) {
                if (!fu.token.equals(best) && selection.rank(fu.token) < selection.rank(best)) {
                    best = fu.token;
                }
            }
            for (FileUnits fu : e.getValue()) {
                hadGroups.add(fu);
                boolean keep = fu.token.equals(best);
                if (keep && !fu.token.equals(main) && !selection.isUsedDefinitionFile(fu.path)) {
                    Catalog.TableDef def = catalog.table(catalog.resolveSynonym(table));
                    keep = def != null && def.declared; // 정의가 없는 테이블의 묶음을 다른 방언 파일에서 가져오지 않는다
                }
                if (keep) {
                    if (!fu.token.equals(main)) {
                        fu.loads.borrow(table, fu.token);
                        selection.borrowedLoadGroup(fu.token);
                    }
                } else if (fu.loads.drop(table)) {
                    fu.droppedLoads.add(table);
                    dropped++;
                }
            }
        }
        for (FileUnits fu : files) {
            if (fu.token == null || fu.token.equals(main)) {
                continue;
            }
            fu.borrowedFrom = fu.token;
            if (!selection.isUsedDefinitionFile(fu.path)) {
                fu.dialectExcluded = true;
                fu.obtained++; // 읽을 것이 없던 파일이 아니다: "읽은 것이 없음" 경고를 내지 않는다
            }
            if (hadGroups.contains(fu) && fu.loads.groupCount() == 0) {
                fu.loadExcluded = true;
                fu.obtained++;
                selection.excludedFile(fu.path);
            }
        }
        selection.droppedLoadGroups(dropped);
    }

    private static void declareStatement(GraphModel graph, String statementId, FileUnits fu, ScriptUnit unit, Locator locator) {
        String fileName = fu.path.substring(fu.path.lastIndexOf('/') + 1);
        graph.declare(Node.builder(statementId, NodeKind.SQL_STATEMENT).name(fileName + ":" + unit.startLine())
                .qualifiedName(fu.path + ":" + unit.startLine()).location(locator.locate(unit.pos()))
                .attr("sql", normalizeSql(unit.text())).build());
    }

    /** 공백을 한 칸으로 줄이고 길이를 제한한다. */
    static String normalizeSql(String text) {
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() > SQL_ATTR_LIMIT ? s.substring(0, SQL_ATTR_LIMIT) : s;
    }

    private static String firstWords(ScriptUnit unit) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < unit.tokens().size() && i < 4; i++) {
            sb.append(i == 0 ? "" : " ").append(unit.tokens().get(i).text());
        }
        return sb.toString();
    }

    /**
     * 파서가 남긴 문제를 PARSE_FAILED(WARN) 하나로 보고한다.
     *
     * @param majorOnly true면 가벼운 문제(MyBatis 전개 잔재)만 있을 때는 보고하지 않는다
     */
    private static void reportProblems(GraphModel graph, List<ParseProblem> problems, boolean majorOnly, Locator locator,
            Pos fallback, Set<String> reported) {
        ParseProblem first = null;
        int count = 0;
        for (ParseProblem p : problems) {
            if (majorOnly && p.minor()) {
                continue;
            }
            if (first == null) {
                first = p;
            }
            count++;
        }
        if (first == null) {
            return;
        }
        SourceLocation location = locator.locate(first.pos().known() ? first.pos() : fallback);
        String message = DynamicText.display("구문 일부를 해석하지 못함: " + first.message() + (count > 1 ? " (외 " + (count - 1) + "건)" : ""));
        if (reported.add(message + "@" + location)) {
            graph.report(Diagnostic.warn(DiagnosticCodes.PARSE_FAILED, message, location));
        }
    }

    // ------------------------------------------------------------------ 내장 소스

    private void embedded(GraphModel graph, Catalog catalog, FactExtractor extractor, Dialect optionDialect, DialectSelection selection,
            String ownerId, List<EmbeddedSource> sources) {
        FactsWriter writer = new FactsWriter(graph, catalog, ownerId);
        Set<String> reported = new HashSet<>();
        // 구문의 dialect 표기: 생산자가 넘긴 방언(attrs.dialect)이 먼저고, 없으면 내용 특징이다. --dialect 옵션은 찍지 않는다(F-006-R37).
        // 생산자가 대상 외 방언(mysql 등)이라고 알린 내장 소스는 내용 특징(SYSDATE 등)으로 Oracle·DB2라고 적지 않는다
        Dialect dialect = null;
        boolean foreign = false;
        List<Token> allTokens = new ArrayList<>();
        for (EmbeddedSource source : sources) {
            if (dialect == null) {
                dialect = DialectDetector.fromName(source.attrs().get("dialect"));
            }
            foreign |= dialect == null && DialectTokens.canonical(source.attrs().get("dialect")) != null;
            Locator locator = Locator.ofEmbedded(source);
            // MySQL의 SQL인가(줄 처음 # 주석, F-006-R27): 생산자가 넘긴 방언 이름이 먼저고, 없으면 옵션, 그다음 본문의 MySQL 특징이다
            String hint = DialectTokens.canonical(source.attrs().get("dialect"));
            boolean mysql = hint != null ? DialectTokens.isMySql(hint)
                    : optionDialect == null && (selection.mySqlProject() || DialectDetector.mysqlFeatures(source.text()));
            // 생산자가 치환 목록을 넘겼으면 ${…} 자리의 내부 표식을 원문으로 되돌려 읽는다: 메시지와 미해결 원문에 원문이 나온다(F-006-R26)
            String text = DynamicText.restore(source.text(), source.attrs().get(DynamicText.PARTS_KEY), source.exactPositions());
            for (ScriptUnit unit : ScriptSplitter.splitEmbedded(text, mysql)) {
                allTokens.addAll(unit.tokens());
                if (unit.kind() == ScriptUnit.Kind.BLOCK) {
                    RoutineBodyHandler.analyzeBlock(unit.tokens(), unit.text(), graph, catalog, dialect != null ? dialect : optionDialect, writer, locator, true, reported);
                    continue;
                }
                List<Token> tokens = new ArrayList<>(unit.tokens());
                ParseResult result = DdlParser.isDdl(tokens) ? new DdlParser(tokens).parse() : new SqlParser(tokens).parseStatement();
                Statement statement = result.statement();
                if (statement instanceof Statement.Empty) {
                    continue;
                }
                if (statement instanceof Statement.Unparsed u) {
                    SourceLocation where = locator.locate(unit.pos());
                    if (reported.add("unsupported:" + u.keyword() + "@" + where)) {
                        graph.report(Diagnostic.info(DiagnosticCodes.UNSUPPORTED_SYNTAX, "지원하지 않는 구문: " + DynamicText.display(u.keyword()) + " (" + ownerId + ")", where));
                    }
                }
                StatementFacts facts = extractor.extract(result);
                if (hasDynamic(tokens)) {
                    facts.markDynamic(); // 건너뛴 ${} 조각만 있어도 정적으로 다 알 수 없는 구문이다(F-006-R25)
                }
                writer.add(facts, locator);
                reportProblems(graph, result.problems(), true, locator, unit.pos(), reported);
            }
        }
        if (dialect == null && !foreign) {
            dialect = DialectDetector.fromTokens(allTokens);
        }
        writer.finish(dialect);
    }
}
