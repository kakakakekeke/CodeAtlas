package io.codeatlas.web.analysis;

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
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.spi.FileType;
import io.codeatlas.core.spi.SourceFile;
import io.codeatlas.web.analysis.Evaluator.Expr;
import io.codeatlas.web.analysis.Evaluator.GlobalHits;
import io.codeatlas.web.analysis.ModuleInfo.ImportRecord;
import io.codeatlas.web.analysis.ModuleInfo.PendingMember;
import io.codeatlas.web.analysis.ModuleInfo.PropPass;
import io.codeatlas.web.analysis.ModuleInfo.RouteAttr;
import io.codeatlas.web.analysis.ModuleInfo.Site;
import io.codeatlas.web.analysis.ModuleInfo.SiteKind;
import io.codeatlas.web.analysis.ModuleInfo.Unit;
import io.codeatlas.web.analysis.UrlValue.Kind;
import io.codeatlas.web.analysis.UrlValue.Part;
import io.codeatlas.web.syntax.JsDialect;
import io.codeatlas.web.syntax.JsParser;
import io.codeatlas.web.syntax.ParserUnavailableException;
import io.codeatlas.web.syntax.SyntaxNode;
import io.codeatlas.web.syntax.SyntaxTree;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 프로젝트 한 번의 JS·TS 분석. 1단계(모듈마다 선언 수집) → 2단계(모듈을 넘나드는 해석과 그래프 기록).
 */
public final class JsProjectAnalysis {

    /** 압축 파일을 건너뛸 때의 진단 코드. */
    public static final String FILE_SKIPPED = "FILE_SKIPPED";
    /** 매개변수·props로 받은 함수의 호출 가운데 대상을 정하지 못해 기록하지 않은 건수를 알리는 진단 코드(INFO, 모듈마다 1건). */
    public static final String CALLS_SKIPPED = "CALLS_SKIPPED";
    public static final String CATEGORY_MODULE = "js-module";
    public static final String CATEGORY_CALL = "js-call";
    public static final String CATEGORY_URL = "http-url";
    /**
     * jsp가 {@code JSP_PAGE} 노드에 싣는 폼 표의 attrs 키. 값은 {@code "<키>=<METHOD>"} 문자열의 목록이고 키는 폼의 name 또는
     * {@code #} + id다(F-011-R16).
     */
    public static final String FORM_METHODS_ATTR = "formMethods";

    /**
     * 클라이언트 라우터가 처리하는 화면 전환임을 알리는 {@code HTTP_REQUEST}의 attrs 키(boolean). 서버 요청이 아니므로 링커가
     * 엔드포인트와 맞추지 않고 미해결로도 남기지 않는다(F-010-R30).
     */
    public static final String CLIENT_ROUTE_ATTR = "clientRoute";
    /**
     * 메서드를 하나로 정하지 못한 요청({@code httpMethod=ANY})의 후보 메서드 목록을 담는 {@code HTTP_REQUEST}의 attrs 키
     * (List&lt;String&gt;, F-010-R39). 링커가 이 메서드의 엔드포인트(와 메서드를 가리지 않는 엔드포인트)로만 잇는다.
     */
    public static final String HTTP_METHODS_ATTR = "httpMethods";
    /** 함께 싣는 근거 없이 "프로젝트에 정의가 하나뿐"이라는 것만으로 이은 전역 호출임을 알리는 {@code CALLS}의 attrs 키(boolean). */
    public static final String LOAD_UNVERIFIED_ATTR = "loadUnverified";

    private static final int MAX_WRAPPER_DEPTH = 3;
    /** {@code to} 속성으로 이동 경로를 받는 react-router 컴포넌트. */
    private static final Set<String> ROUTE_LINKS = Set.of("Link", "NavLink", "Navigate", "Redirect");

    /**
     * 요청 래퍼의 틀: 이 함수의 매개변수가 URL(과 메서드)에 그대로 들어간다.
     *
     * @param fn 매개변수의 주인(호출하면 요청이 나가는 함수)
     * @param via 실제 요청 지점이 있는 가장 안쪽 래퍼 함수의 ID
     * @param optionsParam 요청 옵션 객체를 받는 매개변수의 순번(메서드를 호출 지점의 인자에서 읽는다). 없으면 -1
     * @param methodKeys 옵션 객체에서 메서드를 담는 프로퍼티 이름들
     * @param markup HTML 문자열 안의 태그 속성에서 온 틀인가(F-010-R45). 호출 지점의 값이 서버 경로 꼴일 때만 요청으로 만든다
     */
    private record Template(FunctionInfo fn, String api, String method, int methodParam, UrlValue url, String via, int level,
            int optionsParam, List<String> methodKeys, boolean markup) {
    }

    /** 한 호출 지점에서 나온 요청 후보 하나. 그 지점의 후보를 다 모은 뒤 정리해서 기록한다. */
    private record Candidate(Site site, SourceLocation at, String api, List<String> methods, UrlValue.Resolved url,
            SyntaxNode urlExpr, boolean inferred, String via, Map<String, Object> extra) {
    }

    /** 한 요청 지점의 URL 후보들과 그 URL에 짝지은 메서드 후보들. 짝을 모르면 지점 전체가 한 묶음이다. */
    private record Variant(List<UrlValue> urls, List<String> methods) {
    }

    /** 메서드 후보가 이보다 많으면 갈래를 나누지 않고 ANY로 둔다. */
    private static final int MAX_METHOD_CANDIDATES = 3;
    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");
    /** 옵션 객체의 url이 가리켜도 서버 요청으로 보지 않는 정적 자원 확장자. */
    private static final Set<String> STATIC_EXTENSIONS = Set.of("css", "js", "map", "png", "jpg", "jpeg", "gif", "ico", "svg",
            "bmp", "webp", "woff", "woff2", "ttf", "eot", "otf", "swf", "mp3", "mp4", "wav", "pdf");

    /**
     * 호출 대상을 푼 결과.
     *
     * @param global 전역 이름을, 두 스크립트를 함께 싣는 페이지를 근거로 다른 스크립트에서 찾았는가
     * @param pages global일 때 targets와 같은 순서로 그 근거 페이지의 노드 ID
     */
    private record Resolution(List<FunctionInfo> targets, boolean inferred, boolean ambiguous, boolean global, String missing,
            List<String> pages, boolean loadUnverified, boolean manyDefinitions) {
        static final Resolution NONE = new Resolution(List.of(), false, false, false, null, List.of());
        /** 전역 이름의 정의가 프로젝트에 여럿이라 잇지 않았다. */
        static final Resolution MANY = new Resolution(List.of(), false, false, false, null, List.of(), false, true);

        Resolution(List<FunctionInfo> targets, boolean inferred, boolean ambiguous, boolean global, String missing,
                List<String> pages) {
            this(targets, inferred, ambiguous, global, missing, pages, false, false);
        }
    }

    private final AnalysisContext ctx;
    private final GraphModel graph;
    private final JsParser parser;
    private final Map<String, ModuleInfo> byPath = new LinkedHashMap<>();
    private final Map<String, ModuleInfo> byId = new LinkedHashMap<>();
    private Evaluator ev;
    private PageScripts pages;
    private RequestMatcher requests;
    private ModuleResolver resolver;
    private final Map<FunctionInfo, List<Template>> templates = new HashMap<>();
    /** 프로젝트가 선언한 클라이언트 라우트 경로({@code <Route path>})를 {@code /}로 나눈 것. */
    private final List<String[]> clientRoutes = new ArrayList<>();
    /** 함께 싣는 근거 없이 이은 호출 지점(래퍼 전파로 만든 요청도 추론으로 둔다). */
    private final Set<SyntaxNode> unverifiedCalls = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    /** 호출 지점과 그 유일한 대상(래퍼 전파용). */
    private final List<Map.Entry<Site, FunctionInfo>> resolvedCalls = new ArrayList<>();
    /**
     * 매개변수·props로 받은 값을 가리키는 식.
     *
     * @param fn 그 매개변수의 함수(props면 컴포넌트)
     * @param prop props의 프로퍼티면 그 이름, 아니면 null
     * @param key 같은 매개변수(같은 prop)를 가리키는 식끼리 같은 값
     */
    private record ParamUse(FunctionInfo fn, String prop, Object key) {
    }

    /** JSX 부모들이 컴포넌트의 prop 하나에 넘긴 것. */
    private static final class PropInfo {
        /** 넘긴 프로젝트 함수들. */
        final List<FunctionInfo> targets = new ArrayList<>(2);
        /** 함수(프로젝트 함수 참조나 함수 리터럴)를 넘긴 부모가 있는가. */
        boolean function;
    }

    /** 대상을 정하지 못해 기록하지 않은, 매개변수·props로 받은 함수의 호출(모듈별). */
    private static final class Skipped {
        int calls;
        int passes;
        /** 정의가 프로젝트에 여럿인 전역 이름의 호출(함께 싣는 근거가 없어 고르지 못했다). */
        int globals;
        final TreeSet<Integer> lines = new TreeSet<>();
    }

    /** 컴포넌트 → prop 이름 → JSX 부모가 넘긴 것. */
    private final Map<FunctionInfo, Map<String, PropInfo>> props = new HashMap<>();
    /** 함수를 받는다고 확인한 매개변수·prop({@link ParamUse#key()}). */
    private final Set<Object> functionParams = new HashSet<>();
    /** 매개변수·props를 다른 호출에 넘긴 지점. 그 매개변수가 함수를 받는지는 호출 해석이 다 끝나야 안다. */
    private final List<Map.Entry<Site, Object>> paramPasses = new ArrayList<>();
    private final Map<ModuleInfo, Skipped> skipped = new LinkedHashMap<>();
    /** 구문 트리의 루트 → 그 트리를 파싱한 소스. 식이 어느 조각에 놓였는지 찾는 데 쓴다. */
    private final Map<SyntaxNode, Unit> unitByRoot = new IdentityHashMap<>();

    public JsProjectAnalysis(AnalysisContext ctx, JsParser parser) {
        this.ctx = ctx;
        this.graph = ctx.graph();
        this.parser = parser;
    }

    public void run() {
        if (!collectFiles()) {
            return;
        }
        collectEmbedded();
        for (ModuleInfo m : byId.values()) {
            Collector.finish(m);
        }
        settleBannerCandidates();
        resolver = new ModuleResolver(ctx.projectRoot(), byPath, parser);
        pages = PageScripts.build(graph, byId);
        ev = new Evaluator(resolver, pages, byId.values());
        ev.fieldUrls = this::fieldUrls;
        ev.unitOf = this::unitOf;
        requests = new RequestMatcher(ev);
        applyPendingMembers();
        ev.invalidate();
        markFlags();
        for (ModuleInfo m : byId.values()) {
            guarded(m, "라우트 선언", () -> collectClientRoutes(m));
        }
        for (ModuleInfo m : byId.values()) {
            guarded(m, "함수 노드", () -> emitFunctions(m));
        }
        for (ModuleInfo m : byId.values()) {
            guarded(m, "import", () -> emitImports(m));
        }
        for (ModuleInfo m : byId.values()) {
            guarded(m, "요청", () -> emitRequests(m));
        }
        for (ModuleInfo m : byId.values()) {
            guarded(m, "라우트 링크", () -> emitRouteLinks(m));
        }
        for (ModuleInfo m : byId.values()) {
            guarded(m, "JSX 요소의 URL", () -> emitHostLinks(m));
        }
        for (ModuleInfo m : byId.values()) {
            guarded(m, "props", () -> collectProps(m));
        }
        for (ModuleInfo m : byId.values()) {
            guarded(m, "호출", () -> emitCalls(m));
        }
        propagateWrappers();
        reportSkippedCalls();
    }

    private void guarded(ModuleInfo m, String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | StackOverflowError e) {
            graph.report(Diagnostic.warn(DiagnosticCodes.PARSE_FAILED,
                    "JS 분석 중 오류(" + what + "): " + m.path + ": " + e, SourceLocation.ofFile(m.path)));
        }
    }

    // ------------------------------------------------------------------ 1단계: 수집

    /**
     * 머리 주석의 라이선스 배너로만 벤더 후보가 된 파일. 다른 스크립트를 다 읽은 뒤 프로젝트 정합성 검사로 판정한다(F-010-R40).
     */
    private record BannerCandidate(SourceFile file, String path, String text) {
    }

    private VendorRules vendor;
    /** 벤더 폴더(또는 벤더 파일이 든 폴더) → 판정 근거 → 건너뛴 파일 수. 진단은 파일마다가 아니라 폴더마다 1건이다. */
    private final Map<String, Map<String, Integer>> vendorSkipped = new java.util.TreeMap<>();
    private final List<BannerCandidate> bannerCandidates = new ArrayList<>();
    /** 폴더 → 내용을 분석한(벤더도 압축본도 아닌) 스크립트 수. */
    private final Map<String, Integer> analyzedPerDirectory = new HashMap<>();
    /** 폴더 → 벤더로 판정했거나 벤더 후보인 스크립트 수. */
    private final Map<String, Integer> vendorPerDirectory = new HashMap<>();
    /** 머리 주석 후보의 함수 호출을 세지 않는 크기(이보다 큰 파일은 파싱하지 않고 벤더로 둔다). */
    private static final int MAX_CANDIDATE_LENGTH = 200_000;
    /** 프로젝트의 다른 스크립트가 이만큼의 최상위 함수를 부르면 프로젝트 파일로 본다. */
    private static final int PROJECT_CALLS = 5;

    /**
     * JS·TS 파일마다 모듈 노드를 만들고 선언을 모은다.
     *
     * @return 파서를 쓸 수 없는 환경이면 false(모듈 노드만 만들고 분석은 하지 않는다)
     */
    private boolean collectFiles() {
        boolean available = true;
        vendor = new VendorRules(ctx.options().extra());
        for (SourceFile file : ctx.files().ofType(FileType.JS, FileType.TS)) {
            String path = file.relativePath();
            ctx.progress(path);
            ModuleInfo module = new ModuleInfo(Ids.jsModule(path), path, false);
            graph.declare(Node.builder(module.id, NodeKind.JS_MODULE)
                    .name(file.fileName()).qualifiedName(path).location(SourceLocation.ofFile(path)).build());
            graph.addEdge(Edge.of(file.nodeId(), module.id, EdgeKind.CONTAINS));
            if (!available) {
                continue;
            }
            String skipReason = null;
            String vendorReason = null;
            boolean candidate = false;
            String vendorRoot = null;
            try {
                // 읽지 않고 판정할 수 있는 것부터: 벤더 폴더, 압축본 이름, 벤더 파일 이름
                boolean notVendor = vendor.excluded(path);
                vendorRoot = notVendor ? null : vendor.vendorDirectory(path);
                if (vendorRoot != null) {
                    vendorReason = VendorRules.REASON_PATH;
                } else if (!notVendor && vendor.matchesPattern(path)) {
                    vendorRoot = parentDirectory(path);
                    vendorReason = VendorRules.REASON_OPTION;
                }
                boolean minifiedName = vendorRoot == null && isMinifiedName(path);
                if (vendorRoot == null && !notVendor && !minifiedName && vendor.vendorFileName(path)) {
                    vendorRoot = parentDirectory(path);
                    vendorReason = VendorRules.REASON_NAME;
                }
                String text = null;
                if (vendorRoot == null || !vendor.skip()) {
                    text = file.readText();
                    // 압축 여부는 이름이 아니라 내용으로 본다(F-010-R36): 이름에 .min이 없어도 내용이 압축본이면 건너뛰고,
                    // .min.js라도 내용이 압축돼 있지 않으면 분석한다
                    if (vendorRoot == null && isMinified(path, text)) {
                        skipReason = "minified";
                    } else if (vendorRoot == null && !notVendor) {
                        if (minifiedName && vendor.vendorFileName(path)) {
                            vendorReason = VendorRules.REASON_NAME;
                        } else if (VendorRules.looksGenerated(text)) {
                            vendorReason = VendorRules.REASON_GENERATED;
                        } else if (VendorRules.hasLicenseBanner(text)) {
                            // 머리 주석만으로는 벤더로 굳히지 않는다: 다른 스크립트를 다 읽은 뒤에 정한다(F-010-R40)
                            candidate = true;
                        }
                        if (vendorReason != null) {
                            vendorRoot = parentDirectory(path);
                        }
                    }
                }
                String directory = parentDirectory(path);
                if (candidate) {
                    bannerCandidates.add(new BannerCandidate(file, path, text));
                    vendorPerDirectory.merge(directory, 1, Integer::sum);
                } else {
                    module.vendor = vendorRoot != null;
                    if (skipReason == null && module.vendor && vendor.skip()) {
                        skipReason = "vendor";
                        vendorSkipped.computeIfAbsent(vendorRoot, k -> new HashMap<>()).merge(vendorReason, 1, Integer::sum);
                    }
                    if (module.vendor) {
                        vendorPerDirectory.merge(directory, 1, Integer::sum);
                    } else if (skipReason == null) {
                        analyzedPerDirectory.merge(directory, 1, Integer::sum);
                    }
                    if (skipReason != null) {
                        module.skipped = true;
                        if (skipReason.equals("minified")) {
                            graph.report(Diagnostic.info(FILE_SKIPPED, "압축된 스크립트라 내용을 분석하지 않았다: " + path,
                                    SourceLocation.ofFile(path)));
                        }
                    } else {
                        parseFile(module, path, text);
                    }
                }
            } catch (ParserUnavailableException e) {
                graph.report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "JS·TS 분석을 건너뛴다: " + e.getMessage(), null));
                available = false;
                continue;
            } catch (RuntimeException | StackOverflowError e) {
                // 이 파일만 포기한다. 모으다 만 선언은 버리고 모듈 노드만 남긴다.
                boolean wasVendor = module.vendor;
                module = new ModuleInfo(module.id, path, false);
                module.vendor = wasVendor;
                skipReason = null;
                candidate = false;
                graph.report(Diagnostic.warn(DiagnosticCodes.PARSE_FAILED, "JS 파일을 분석하지 못했다: " + path + ": " + e,
                        SourceLocation.ofFile(path)));
            }
            byPath.put(path, module);
            byId.put(module.id, module);
            if (!candidate) {
                putModuleAttrs(module, skipReason);
            }
        }
        if (!available) {
            // 파서가 없으면 후보를 견줄 수 없다: 머리 주석대로 벤더로 둔다
            for (BannerCandidate c : bannerCandidates) {
                settleCandidate(c, true);
            }
            bannerCandidates.clear();
            reportVendorSkipped();
        }
        return available;
    }

    private void parseFile(ModuleInfo module, String path, String text) {
        Unit unit = new Unit(module, path, null);
        unit.tree = parser.parse(text, JsDialect.forFileName(path));
        unitByRoot.put(unit.tree.root(), unit);
        module.units.add(unit);
        reportSyntaxError(unit);
        new Collector(module, unit).collect();
    }

    private void putModuleAttrs(ModuleInfo module, String skipReason) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (skipReason != null) {
            attrs.put("skipped", skipReason);
        } else {
            attrs.put("moduleKind", module.moduleKind());
            if (module.vendor) {
                attrs.put("vendor", true);
            }
        }
        graph.putAttrs(module.id, attrs);
    }

    /**
     * 머리 주석의 라이선스 배너로 벤더 후보가 된 파일을 판정한다(F-010-R40). 프로젝트가 직접 쓴 스크립트에도 라이선스 문구가
     * 붙는다(오픈소스 프로젝트의 자기 헤더, 옮겨 온 코드의 출처 표기). 다음 가운데 하나면 벤더가 아니다:
     * (1) 같은 폴더에서 혼자만 걸렸고(나머지 스크립트는 모두 분석 대상) 파일 이름이 라이브러리 배포판의 꼴이 아니다,
     * (2) 프로젝트의 다른 스크립트(인라인 스크립트 포함)가 이 파일의 최상위 함수를 {@value #PROJECT_CALLS}개 이상 부른다.
     * 모든 스크립트를 읽고 난 뒤에 부른다.
     */
    private void settleBannerCandidates() {
        Set<String> candidatePaths = new HashSet<>();
        bannerCandidates.forEach(c -> candidatePaths.add(c.path()));
        for (BannerCandidate c : bannerCandidates) {
            boolean vendorFile = true;
            try {
                String directory = parentDirectory(c.path());
                boolean alone = vendorPerDirectory.getOrDefault(directory, 0) == 1
                        && analyzedPerDirectory.getOrDefault(directory, 0) >= 1 && !VendorRules.libraryShapedName(c.path());
                ModuleInfo module = byPath.get(c.path());
                boolean parsed = false;
                if (alone) {
                    vendorFile = false;
                } else if (c.text().length() <= MAX_CANDIDATE_LENGTH && mayDeclareFunctions(c.text())) {
                    parseFile(module, c.path(), c.text());
                    Collector.finish(module);
                    parsed = true;
                    vendorFile = calledTopLevelFunctions(module, candidatePaths) < PROJECT_CALLS;
                }
                if (!vendorFile || !vendor.skip()) {
                    if (!parsed) {
                        parseFile(module, c.path(), c.text());
                        Collector.finish(module);
                    }
                } else if (parsed) {
                    // 벤더로 남는다: 모은 선언을 버린다
                    module.units.forEach(u -> unitByRoot.remove(u.tree.root()));
                    ModuleInfo fresh = new ModuleInfo(module.id, c.path(), false);
                    byPath.put(c.path(), fresh);
                    byId.put(fresh.id, fresh);
                }
            } catch (ParserUnavailableException e) {
                vendorFile = true;
            } catch (RuntimeException | StackOverflowError e) {
                ModuleInfo fresh = new ModuleInfo(Ids.jsModule(c.path()), c.path(), false);
                byPath.put(c.path(), fresh);
                byId.put(fresh.id, fresh);
                graph.report(Diagnostic.warn(DiagnosticCodes.PARSE_FAILED, "JS 파일을 분석하지 못했다: " + c.path() + ": " + e,
                        SourceLocation.ofFile(c.path())));
            }
            settleCandidate(c, vendorFile);
        }
        bannerCandidates.clear();
        reportVendorSkipped();
    }

    private static final java.util.regex.Pattern FUNCTION_DECLARATION = java.util.regex.Pattern
            .compile("(?m)^[ \\t]*(?:async\\s+)?function\\b|^[ \\t]*(?:var|let|const)\\s+[\\w$]+\\s*=\\s*(?:async\\s+)?(?:function\\b|\\()");

    /** 파싱하기 전에 싸게 걸러 낸다: 함수 선언으로 보이는 줄이 {@value #PROJECT_CALLS}개가 안 되면 최상위 함수도 그만큼 없다. */
    private static boolean mayDeclareFunctions(String text) {
        java.util.regex.Matcher m = FUNCTION_DECLARATION.matcher(text);
        int count = 0;
        while (m.find()) {
            if (++count >= PROJECT_CALLS) {
                return true;
            }
        }
        return false;
    }

    private void settleCandidate(BannerCandidate c, boolean vendorFile) {
        ModuleInfo module = byPath.get(c.path());
        module.vendor = vendorFile;
        String skipReason = null;
        if (vendorFile && vendor.skip()) {
            skipReason = "vendor";
            module.skipped = true;
            vendorSkipped.computeIfAbsent(parentDirectory(c.path()), k -> new HashMap<>())
                    .merge(VendorRules.REASON_LICENSE, 1, Integer::sum);
        }
        putModuleAttrs(module, skipReason);
    }

    /** 프로젝트의 다른 스크립트(벤더·벤더 후보 제외)가 이름으로 부르는, 이 모듈의 최상위 함수의 수. */
    private int calledTopLevelFunctions(ModuleInfo module, Set<String> candidatePaths) {
        Set<String> names = new HashSet<>();
        for (FunctionInfo fn : module.functions.values()) {
            if (fn.parent == null && !fn.isClass() && fn.namePath.indexOf('.') < 0 && module.top.syms.containsKey(fn.namePath)) {
                names.add(fn.namePath);
            }
        }
        if (names.size() < PROJECT_CALLS) {
            return 0;
        }
        Set<String> called = new HashSet<>();
        for (ModuleInfo other : byId.values()) {
            if (other == module || other.vendor || other.skipped || candidatePaths.contains(other.path)) {
                continue;
            }
            for (Site site : other.sites) {
                if (site.kind() != SiteKind.CALL) {
                    continue;
                }
                SyntaxNode callee = Syntax.callee(site.node());
                String name = callee == null ? null : callee.is("identifier") ? callee.text()
                        : callee.is("member_expression") && callee.field("object") != null
                                && callee.field("object").text().equals("window") ? Syntax.memberName(callee) : null;
                if (name != null && names.contains(name) && site.scope().lookup(name) == null) {
                    called.add(name);
                }
            }
        }
        return called.size();
    }

    private void reportVendorSkipped() {
        vendorSkipped.forEach((directory, reasons) -> {
            int total = 0;
            StringBuilder why = new StringBuilder();
            for (String reason : VendorRules.REASONS) {
                Integer count = reasons.get(reason);
                if (count == null) {
                    continue;
                }
                total += count;
                why.append(why.length() == 0 ? "" : ", ").append(reason).append(reasons.size() > 1 ? " " + count : "");
            }
            graph.report(Diagnostic.info(FILE_SKIPPED, "벤더 스크립트 " + total + "개 건너뜀(" + why + "): " + directory,
                    SourceLocation.ofFile(directory)));
        });
        vendorSkipped.clear();
    }

    private static String parentDirectory(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "." : path.substring(0, slash);
    }

    private void collectEmbedded() {
        for (EmbeddedSource src : ctx.embedded(EmbeddedSource.JS)) {
            String owner = src.ownerNodeId();
            String hostFile = src.origin() != null ? src.origin().file() : null;
            String path = owner.startsWith("js:module:") ? Ids.remainder(owner) : hostFile != null ? hostFile : owner;
            ctx.progress(path);
            ModuleInfo module = byId.get(owner);
            if (module == null) {
                module = new ModuleInfo(owner, path, true);
                byId.put(owner, module);
                if (!graph.hasNode(owner)) {
                    graph.reference(owner, NodeKind.JS_MODULE, path);
                }
            }
            ModuleInfo target = module;
            int sitesBefore = target.sites.size();
            try {
                Unit unit = new Unit(target, hostFile != null ? hostFile : path, src);
                unit.tree = parser.parse(src.text(), JsDialect.JAVASCRIPT);
                unitByRoot.put(unit.tree.root(), unit);
                target.units.add(unit);
                reportSyntaxError(unit);
                new Collector(target, unit).collect();
            } catch (ParserUnavailableException e) {
                graph.report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "인라인 스크립트 분석을 건너뛴다: " + e.getMessage(), null));
                return;
            } catch (RuntimeException | StackOverflowError e) {
                while (target.sites.size() > sitesBefore) {
                    target.sites.remove(target.sites.size() - 1);
                }
                graph.report(Diagnostic.warn(DiagnosticCodes.PARSE_FAILED, "인라인 스크립트를 분석하지 못했다: " + path + ": " + e,
                        src.origin()));
            }
        }
    }

    private void reportSyntaxError(Unit unit) {
        SyntaxTree tree = unit.tree;
        if (tree.hasError()) {
            SourceLocation at = unit.loc(tree.firstError());
            graph.report(Diagnostic.warn(DiagnosticCodes.PARSE_FAILED,
                    "구문 오류가 있어 해석할 수 있는 부분만 분석했다: " + unit.file + ":" + at.startLine(), at));
        }
    }

    private static boolean isMinifiedName(String path) {
        return path.toLowerCase(Locale.ROOT).matches(".*[.-]min\\.(js|mjs|cjs)$");
    }

    /**
     * 압축된 스크립트인가. 내용으로 판정한다: 1,000자 이상이면서 평균 줄 길이가 500자를 넘거나, 5,000자 넘는 한 줄이 파일의
     * 절반을 넘는다(머리 주석 뒤에 한 줄로 압축한 것). 이름이 {@code .min.js}류면 압축본으로 보되, 줄이 10개 이상이고 가장 긴 줄이
     * 500자 이하면(이름만 min인 손으로 쓴 파일) 압축본이 아니다.
     */
    static boolean isMinified(String path, String text) {
        long lines = lineCount(text);
        int longest = longestLine(text);
        if (isMinifiedName(path)) {
            return !(lines >= 10 && longest <= 500);
        }
        if (text.length() < 1_000) {
            return false;
        }
        return text.length() / lines > 500 || (longest > 5_000 && longest * 2L > text.length());
    }

    private static int longestLine(String text) {
        int longest = 0;
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n' || text.charAt(i) == '\r') {
                longest = Math.max(longest, i - start);
                start = i + 1;
            }
        }
        return longest;
    }

    /** 줄 수. LF, CRLF, CR 단독을 모두 줄 끝 하나로 센다(F-010-R29). */
    static long lineCount(String text) {
        long lines = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n'))) {
                lines++;
            }
        }
        return lines;
    }

    /** {@code A.b.c = 값}을 A의 멤버로 붙인다. A가 다른 모듈에서 온 것이어도 따라간다. */
    private void applyPendingMembers() {
        for (ModuleInfo m : byId.values()) {
            for (PendingMember pm : m.pendingMembers) {
                try {
                    applyPendingMember(m, pm);
                } catch (RuntimeException | StackOverflowError e) {
                    // 붙이지 못한 멤버는 해석되지 않을 뿐이다.
                }
            }
        }
    }

    private void applyPendingMember(ModuleInfo m, PendingMember pm) {
        List<String> path = pm.path();
        String rootName = path.get(0);
        Sym root = pm.scope().lookup(rootName);
        if (root == null) {
            if (!pm.createRoot()) {
                return;
            }
            // 다른 파일에서 선언한 네임스페이스에 멤버를 붙이는 레거시 패턴. 이 모듈의 전역으로 둔다.
            root = new Sym(rootName, m);
            root.namePath = rootName;
            m.top.syms.put(rootName, root);
        } else if (root.param || root.importRef != null && !pm.createRoot()) {
            return;
        } else {
            root = ev.canonical(root);
        }
        if (path.size() == 1) {
            // A.prototype = { ... }: 객체의 멤버를 A의 멤버로
            if (ev.eval(pm.leaf().init, pm.leaf().initScope) instanceof Val.Obj o) {
                for (Map.Entry<String, Sym> e : o.props().entrySet()) {
                    root.putMember(e.getKey(), e.getValue());
                }
            }
            return;
        }
        Sym cur = root;
        for (int i = 1; i < path.size() - 1; i++) {
            Sym next = ev.memberOfSym(cur, path.get(i));
            if (next == null) {
                next = new Sym(path.get(i), m);
                cur.putMember(path.get(i), next);
            } else {
                next = ev.canonical(next);
            }
            cur = next;
        }
        cur.putMember(path.get(path.size() - 1), pm.leaf());
    }

    // ------------------------------------------------------------------ 속성

    private void markFlags() {
        for (ModuleInfo m : byId.values()) {
            List<Sym> exported = new ArrayList<>(m.exports.values());
            if (m.cjsExports != null) {
                exported.add(m.cjsExports);
            }
            for (Sym s : exported) {
                try {
                    markExported(m, s, 0);
                } catch (RuntimeException | StackOverflowError e) {
                    // exported 표시만 빠진다.
                }
            }
            for (FunctionInfo fn : m.functions.values()) {
                if (fn.isClass()) {
                    fn.component = fn.hasRender && fn.superClass != null
                            && fn.superClass.replaceAll("\\s+", "").matches("(React\\.)?(Pure)?Component(<.*)?");
                } else {
                    String name = fn.shortName();
                    fn.component = fn.hasJsx && !name.isEmpty() && Character.isUpperCase(name.charAt(0));
                }
            }
        }
    }

    private void markExported(ModuleInfo m, Sym s, int level) {
        Val v = ev.valueOf(ev.canonical(s));
        if (v instanceof Val.Func f && f.fn() != null && f.fn().module == m) {
            f.fn().exported = true;
        } else if (v instanceof Val.Cls c && c.info().fn.module == m) {
            c.info().fn.exported = true;
        } else if (v instanceof Val.Obj o && level == 0) {
            // export default { a, b }, module.exports = { a, b }: 객체에 담아 내보낸 함수들
            for (Sym p : o.props().values()) {
                markExported(m, p, level + 1);
            }
        }
    }

    // ------------------------------------------------------------------ 기록: 함수, import

    private void emitFunctions(ModuleInfo m) {
        for (FunctionInfo fn : m.functions.values()) {
            Node.Builder node = Node.builder(fn.id, NodeKind.JS_FUNCTION)
                    .name(fn.shortName())
                    .qualifiedName(m.path + "#" + fn.namePath)
                    .location(fn.location)
                    .attr("async", fn.async)
                    .attr("exported", fn.exported)
                    .attr("component", fn.component)
                    .attr("kind", fn.kind);
            if (fn.dynamicName) {
                node.attr("dynamicName", true);
            }
            graph.declare(node.build());
            graph.addEdge(Edge.of(fn.parent != null ? fn.parent.id : m.id, fn.id, EdgeKind.CONTAINS));
        }
    }

    private void emitImports(ModuleInfo m) {
        for (ImportRecord imp : m.imports) {
            ModuleResolver.Resolution r = resolver.resolve(m, imp.spec());
            SourceLocation at = imp.unit().loc(imp.node());
            switch (r.kind()) {
                case MODULE -> {
                    if (r.module() != m) {
                        graph.addEdge(Edge.builder(m.id, r.module().id, EdgeKind.IMPORTS).location(at).attr("spec", imp.spec()).build());
                    }
                }
                case UNRESOLVED -> graph.addUnresolvedEdge(m.id, EdgeKind.IMPORTS, CATEGORY_MODULE, r.text(), at);
                default -> {
                }
            }
        }
    }

    // ------------------------------------------------------------------ 기록: 요청

    private static String ownerId(Site site) {
        return site.owner() != null ? site.owner().id : site.unit().module.id;
    }

    /**
     * 식의 원문 한 줄 요약(HTTP_REQUEST의 url, 미해결 원문). 식이 내장 소스에 있으면 호스트 파일에 쓴 그대로 되살린다
     * (F-010-R14). 식은 요청 지점과 다른 조각에 있을 수 있어(옵션 객체 상수) 식이 속한 트리로 조각을 찾는다.
     */
    private String brief(SyntaxNode node) {
        Unit unit = unitOf(node);
        return Syntax.brief(unit == null ? node.text() : unit.sourceText(node));
    }

    /** 식이 놓인 조각(파일, 또는 인라인 스크립트 조각 하나). 모르면 null. */
    private Unit unitOf(SyntaxNode node) {
        SyntaxNode root = node;
        for (SyntaxNode p = node.parent(); p != null; p = p.parent()) {
            root = p;
        }
        return unitByRoot.get(root);
    }

    private RequestMatcher.Request match(Site site) {
        return switch (site.kind()) {
            case CALL -> requests.matchCall(site.node(), site.scope());
            case NEW -> requests.matchNew(site.node(), site.scope());
            case ASSIGN -> requests.matchAssign(site.node(), site.scope());
            default -> null;
        };
    }

    /** 요청 지점인 것을 기록하고, 요청이 아닌 호출 지점은 그대로 남겨 둔다. */
    private void emitRequests(ModuleInfo m) {
        List<Site> remaining = new ArrayList<>(m.sites.size());
        for (Site site : m.sites) {
            RequestMatcher.Request request = null;
            try {
                request = match(site);
                if (request != null) {
                    emitRequest(site, request);
                }
            } catch (RuntimeException | StackOverflowError e) {
                graph.report(Diagnostic.warn(DiagnosticCodes.PARSE_FAILED, "요청 지점을 해석하지 못했다: " + e, site.unit().loc(site.node())));
            }
            if (request == null) {
                try {
                    emitMarkupRequests(site);
                } catch (RuntimeException | StackOverflowError e) {
                    // HTML 문자열 안의 URL만 포기한다.
                }
            }
            if (request == null && site.kind() != SiteKind.ASSIGN) {
                remaining.add(site);
            }
        }
        m.sites.clear();
        m.sites.addAll(remaining);
    }

    /** 지금 주소를 그대로 다시 여는 식: {@code location = location.href}, {@code location.href = window.location.pathname}. */
    private static final java.util.regex.Pattern CURRENT_LOCATION = java.util.regex.Pattern
            .compile("(?:(?:window|document|self|top|parent)\\.)?location(?:\\.(?:href|pathname))?");

    private void emitRequest(Site site, RequestMatcher.Request request) {
        if (request.api().equals("navigation") && request.url() != null
                && CURRENT_LOCATION.matcher(request.url().node().text().replaceAll("\\s+", "")).matches()
                && request.url().scope().lookup("location") == null) {
            return; // 새로 고침: 어디로 가는 요청이 아니다
        }
        SourceLocation at = site.unit().loc(site.node());
        ev.reset();
        List<UrlValue> urls = List.of();
        if (request.url() != null) {
            urls = ev.evalString(request.url().node(), request.url().scope());
        }
        // 메서드
        List<String> methods = List.of(request.defaultMethod());
        int methodParam = -1;
        FunctionInfo methodParamFn = null;
        int optionsParam = -1;
        FunctionInfo optionsParamFn = null;
        if (request.method() != null) {
            List<UrlValue> ms = ev.evalString(request.method().node(), request.method().scope());
            methods = List.of("ANY");
            if (ms.size() == 1) {
                String literal = ms.get(0).literalOrNull();
                List<Part> parts = ms.get(0).parts();
                if (literal != null && literal.matches("[A-Za-z]+")) {
                    methods = List.of(literal.toUpperCase(Locale.ROOT));
                } else if (parts.size() == 1 && parts.get(0).kind() == Kind.PARAM) {
                    methodParam = parts.get(0).index();
                    methodParamFn = parts.get(0).fn();
                }
            } else {
                List<String> literals = methodLiterals(ms);
                if (literals != null) {
                    methods = literals;
                }
            }
        } else if (request.options() != null) {
            // 옵션 객체가 변수다(F-010-R20): 이름 있는 함수의 매개변수면 호출 지점의 인자에서, 아니면 그 변수의 값 후보에서 읽는다
            SyntaxNode options = Syntax.unwrap(request.options().node());
            Sym param = options != null && options.is("identifier") ? request.options().scope().lookup(options.text()) : null;
            if (param != null && param.param) {
                if (param.paramOwner != null && param.paramIndex >= 0) {
                    optionsParam = param.paramIndex;
                    optionsParamFn = param.paramOwner;
                }
            } else {
                methods = methodsOf(ev.optionMethods(options, request.options().scope(), request.methodKeys()));
            }
        } else if (request.form() != null) {
            methods = List.of(formMethod(site, request.form(), request.url() == null ? null : request.url().scope()));
        }
        boolean inferred = ev.inferred || methods.size() > 1;
        // 메서드와 URL이 같은 객체 변수의 프로퍼티면(modeInfo.method, modeInfo.editURL) 대입한 객체마다 짝지어 읽는다(F-010-R27)
        List<Expr> methodExprs = request.method() != null ? List.of(request.method())
                : request.options() != null && optionsParam < 0
                        ? ev.optionMethodExprs(request.options().node(), request.options().scope(), request.methodKeys())
                        : List.of();
        List<Variant> variants = request.url() == null ? null
                : pairedVariants(request.url(), methodExprs);
        if (variants == null) {
            variants = List.of(new Variant(urls, methods));
        } else {
            inferred = true;
        }
        boolean route = request.api().equals(RequestMatcher.ROUTE);
        boolean emitted = false;
        boolean allIgnorable = !urls.isEmpty();
        List<Candidate> candidates = new ArrayList<>();
        for (Variant variant : variants) {
            String templateMethod = variant.methods().size() == 1 ? variant.methods().get(0) : "ANY";
            for (UrlValue own : variant.urls()) {
                // 정적 조각은 URL 식 자체에 있어야 한다. baseURL만 정적인 것은 어디로 가는지 모르는 요청이다.
                UrlValue.Resolved ownResolved = own.resolve();
                for (UrlValue url : withBase(request.base(), own)) {
                    registerTemplate(url, request.api(), templateMethod, methodParam, methodParamFn, optionsParam, optionsParamFn,
                            request.methodKeys(), null, 0);
                    UrlValue.Resolved r = url.resolve();
                    if (ownResolved.ignorable() || r.ignorable()) {
                        continue;
                    }
                    allIgnorable = false;
                    // 루트("/")로 가는 화면 이동은 정적 조각이 없어도 어디로 가는지 아는 요청이다(F-010-R43)
                    boolean root = request.api().equals("navigation") && isRoot(ownResolved) && isRoot(r);
                    if (route ? !isRoutePath(r) : !root && (!ownResolved.staticPath() || !r.staticPath())) {
                        continue;
                    }
                    emitted = true;
                    candidates.add(new Candidate(site, at, request.api(), variant.methods(), r, request.url().node(),
                            inferred || url.uncertain(), null, Map.of()));
                }
            }
        }
        declareCandidates(candidates);
        if (!emitted && !allIgnorable && !request.silent()) {
            SyntaxNode text = request.url() != null ? request.url().node() : request.fallbackText();
            graph.addUnresolvedEdge(ownerId(site), EdgeKind.REQUESTS, CATEGORY_URL, text == null ? "?" : brief(text), at);
        }
    }

    /**
     * 메서드 식이 객체 변수의 프로퍼티이고 URL 식이 같은 변수의 프로퍼티(또는 거기서 파생된 식)면 그 변수에 대입된 객체 리터럴마다
     * (URL들, 메서드들) 한 묶음을 만든다(F-010-R27, R39). 그 객체에 메서드 프로퍼티가 없거나 값을 모르면 그 묶음의 메서드는 ANY다.
     *
     * @return 짝지을 수 없으면(서로 다른 변수, 메서드 식이 프로퍼티가 아님) null
     */
    private List<Variant> pairedVariants(Expr url, List<Expr> methodExprs) {
        if (url == null || methodExprs.isEmpty()) {
            return null;
        }
        Sym object = null;
        String methodProp = null;
        for (Expr e : methodExprs) {
            Evaluator.CandidateMember m = ev.candidateMember(e.node(), e.scope());
            if (m == null || (object != null && m.object() != object) || (methodProp != null && !methodProp.equals(m.prop()))) {
                return null;
            }
            object = m.object();
            methodProp = m.prop();
        }
        SyntaxNode use = ev.referenceTo(object, url.node(), url.scope(), 0);
        if (use == null) {
            return null;
        }
        List<Evaluator.PairedCandidate> paired = ev.pairedCandidates(url, object, use, methodProp);
        if (paired.isEmpty()) {
            return null;
        }
        List<Variant> out = new ArrayList<>(paired.size());
        for (Evaluator.PairedCandidate c : paired) {
            List<String> literals = c.methods() == null ? null : methodLiterals(c.methods());
            out.add(new Variant(c.urls(), literals == null ? List.of("ANY") : literals));
        }
        return out;
    }

    /** 클라이언트 라우트로 기록할 만한 경로인가: 프로젝트 안의 경로이고 정적 조각이 있거나 루트({@code /})다. */
    private static boolean isRoutePath(UrlValue.Resolved r) {
        return !r.external() && (r.staticPath() || r.path().equals("/"));
    }

    /** 서버(또는 앱)의 루트 경로 그 자체인가. */
    private static boolean isRoot(UrlValue.Resolved r) {
        return !r.external() && !r.dynamic() && r.path().equals("/");
    }

    /** 메서드 식의 후보가 전부 메서드 이름 리터럴이면 그 목록(대문자, 중복 제거). 하나라도 아니거나 너무 많으면 null. */
    private static List<String> methodLiterals(List<UrlValue> values) {
        List<String> out = new ArrayList<>();
        for (UrlValue v : values) {
            String literal = v.literalOrNull();
            if (literal == null || !literal.matches("[A-Za-z]+")) {
                return null;
            }
            String upper = literal.toUpperCase(Locale.ROOT);
            if (!out.contains(upper)) {
                out.add(upper);
            }
        }
        return out.isEmpty() || out.size() > MAX_METHOD_CANDIDATES ? null : out;
    }

    /** 옵션 객체에서 읽은 메서드 목록을 요청의 메서드 후보로: 모르면 ANY, 메서드 프로퍼티가 없으면 기본값 GET, 너무 많으면 ANY. */
    private static List<String> methodsOf(List<String> read) {
        if (read == null || read.size() > MAX_METHOD_CANDIDATES) {
            return List.of("ANY");
        }
        return read.isEmpty() ? List.of("GET") : read;
    }

    private static List<UrlValue> withBase(List<UrlValue> base, UrlValue url) {
        return base.isEmpty() ? List.of(url) : Evaluator.product(base, List.of(url), true);
    }

    /**
     * 한 호출 지점의 요청 후보를 정리해 기록한다. 같은 메서드·경로는 한 번만 만들고, 다른 후보의 "미지 접두 버전"
     * ({@code {*}/orders}와 {@code /api/orders}가 함께 있을 때의 앞의 것)은 버린다(F-010-R22). 기준 URL이
     * {@code env ?? '/api'}처럼 갈리는 경우 구체적인 쪽만 남기기 위해서다.
     */
    private void declareCandidates(List<Candidate> candidates) {
        List<Candidate> kept = new ArrayList<>(candidates.size());
        Set<String> paths = new HashSet<>();
        for (Candidate c : candidates) {
            if (!isUnknownPrefixVariant(c, candidates)) {
                kept.add(c);
                paths.add(c.url().path());
            }
        }
        if (paths.size() < 2) {
            Set<String> seen = new HashSet<>();
            for (Candidate c : kept) {
                boolean inferred = c.inferred() || c.methods().size() > 1;
                for (String method : c.methods()) {
                    if (seen.add(method + " " + c.url().path())) {
                        declareRequest(c, method, inferred, null);
                    }
                }
            }
            return;
        }
        // URL 후보가 여럿이다: 메서드와 곱하면 소스에 없는 요청(PUT /board)이 생기므로 URL마다 노드 하나만 만든다(F-010-R27).
        // 그 URL에 짝지어진 메서드가 하나면 그 메서드, 여럿이면(짝을 모르거나 같은 URL을 여러 객체가 낸다) ANY로 두되 후보를
        // httpMethods로 남긴다(F-010-R39). 후보 가운데 모르는 메서드가 있으면 ANY뿐이다
        Map<String, Candidate> first = new LinkedHashMap<>();
        Map<String, List<String>> methodsByPath = new HashMap<>();
        Map<String, Boolean> inferredByPath = new HashMap<>();
        for (Candidate c : kept) {
            String path = c.url().path();
            first.putIfAbsent(path, c);
            List<String> methods = methodsByPath.computeIfAbsent(path, k -> new ArrayList<>(2));
            for (String method : c.methods()) {
                if (!methods.contains(method)) {
                    methods.add(method);
                }
            }
            inferredByPath.merge(path, c.inferred() || c.methods().size() > 1, Boolean::logicalOr);
        }
        first.forEach((path, c) -> {
            List<String> methods = methodsByPath.get(path);
            if (methods.size() == 1) {
                declareRequest(c, methods.get(0), inferredByPath.get(path), null);
            } else {
                declareRequest(c, "ANY", true, methods.contains("ANY") ? null : methods);
            }
        });
    }

    private static boolean isUnknownPrefixVariant(Candidate c, List<Candidate> all) {
        String path = c.url().path();
        if (all.size() < 2 || !path.startsWith(UrlValue.DYNAMIC_TOKEN)) {
            return false;
        }
        String tail = path.substring(UrlValue.DYNAMIC_TOKEN.length());
        if (tail.isEmpty()) {
            return false;
        }
        for (Candidate other : all) {
            String otherPath = other.url().path();
            if (other != c && other.methods().equals(c.methods()) && !otherPath.startsWith(UrlValue.DYNAMIC_TOKEN)
                    && otherPath.endsWith(tail)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param methodCandidates 메서드를 하나로 정하지 못했을 때의 후보 메서드들({@code httpMethods}). 없으면 null
     */
    private void declareRequest(Candidate c, String method, boolean inferred, List<String> methodCandidates) {
        UrlValue.Resolved r = c.url();
        SourceLocation at = c.at();
        String id = Ids.httpRequest(method, r.path(), at.file(), at.startLine());
        boolean relative = !r.external() && !r.path().startsWith("/") && !r.path().startsWith(UrlValue.DYNAMIC_TOKEN);
        Node.Builder node = Node.builder(id, NodeKind.HTTP_REQUEST)
                .name(Ids.httpMethod(method) + " " + r.path())
                .location(at)
                .attr("httpMethod", Ids.httpMethod(method))
                .attr("url", c.urlExpr() == null ? r.full() : brief(c.urlExpr()))
                .attr("path", r.path())
                .attr("dynamic", r.dynamic())
                .attr("api", c.api());
        if (r.query() != null) {
            node.attr("query", r.query());
        }
        if (r.external()) {
            node.attr("external", true);
        }
        if (relative) {
            node.attr("relative", true);
        }
        if (c.via() != null) {
            node.attr("via", c.via());
        }
        if (c.api().equals(RequestMatcher.ROUTE) || (c.api().equals("navigation") && isClientRoute(r))) {
            // 서버 요청이 아니라 클라이언트 라우터가 처리하는 화면 전환이다. 링커가 엔드포인트와 맞추지 않는다(F-010-R30)
            node.attr(CLIENT_ROUTE_ATTR, true);
        }
        if (methodCandidates != null) {
            // httpMethod는 계약상 단일 값이라 ANY로 두고, 링커가 대응을 이 메서드들로 좁힐 수 있게 후보를 따로 싣는다
            node.attr(HTTP_METHODS_ATTR, List.copyOf(methodCandidates));
        }
        c.extra().forEach(node::attr);
        graph.declare(node.build());
        Edge.Builder edge = Edge.builder(ownerId(c.site()), id, EdgeKind.REQUESTS).location(at);
        if (inferred) {
            edge.inferred();
        }
        graph.addEdge(edge.build());
    }

    /** URL에 이름 있는 함수의 매개변수가 들어 있으면 그 함수를 요청 래퍼로 등록한다. */
    private void registerTemplate(UrlValue url, String api, String method, int methodParam, FunctionInfo methodParamFn,
            int optionsParam, FunctionInfo optionsParamFn, List<String> methodKeys, String via, int level) {
        registerTemplate(url, api, method, methodParam, methodParamFn, optionsParam, optionsParamFn, methodKeys, via, level, false);
    }

    private void registerTemplate(UrlValue url, String api, String method, int methodParam, FunctionInfo methodParamFn,
            int optionsParam, FunctionInfo optionsParamFn, List<String> methodKeys, String via, int level, boolean markup) {
        if (level >= MAX_WRAPPER_DEPTH) {
            return;
        }
        FunctionInfo fn = null;
        for (Part p : url.parts()) {
            if (p.kind() == Kind.PARAM && p.fn() != null) {
                fn = p.fn();
                break;
            }
        }
        if (fn == null) {
            return;
        }
        int mp = methodParamFn == fn ? methodParam : -1;
        int op = optionsParamFn == fn ? optionsParam : -1;
        String fixedMethod = methodParam >= 0 && mp < 0 ? "ANY" : method;
        Template t = new Template(fn, api, fixedMethod, mp, url, via != null ? via : fn.id, level, op, methodKeys, markup);
        List<Template> list = templates.computeIfAbsent(fn, k -> new ArrayList<>());
        if (list.size() < 16 && !list.contains(t)) {
            list.add(t);
        }
    }

    /** 래퍼 호출 지점에서 인자를 끼워 넣어 요청을 만든다. 래퍼를 감싼 래퍼는 몇 단계까지만 따라간다. */
    private void propagateWrappers() {
        Set<String> done = new HashSet<>();
        // 호출 지점 → 그 지점의 요청 후보. 틀이 여러 개(기준 URL의 갈래)여도 한 지점의 후보를 함께 정리한다.
        Map<SyntaxNode, List<Candidate>> bySite = new java.util.IdentityHashMap<>();
        List<SyntaxNode> order = new ArrayList<>();
        for (int round = 0; round < MAX_WRAPPER_DEPTH; round++) {
            boolean progressed = false;
            for (Map.Entry<Site, FunctionInfo> call : resolvedCalls) {
                List<Template> list = templates.get(call.getValue());
                if (list == null) {
                    continue;
                }
                for (Template t : new ArrayList<>(list)) {
                    String key = System.identityHashCode(call.getKey().node()) + ":" + System.identityHashCode(t);
                    if (!done.add(key)) {
                        continue;
                    }
                    progressed = true;
                    try {
                        List<Candidate> found = instantiate(call.getKey(), t);
                        if (!found.isEmpty()) {
                            SyntaxNode node = call.getKey().node();
                            if (!bySite.containsKey(node)) {
                                order.add(node);
                            }
                            bySite.computeIfAbsent(node, k -> new ArrayList<>()).addAll(found);
                        }
                    } catch (RuntimeException | StackOverflowError e) {
                        // 이 호출 지점의 전파만 포기한다.
                    }
                }
            }
            if (!progressed) {
                break;
            }
        }
        for (SyntaxNode node : order) {
            declareCandidates(bySite.get(node));
        }
    }

    private List<Candidate> instantiate(Site site, Template t) {
        List<SyntaxNode> args = Syntax.arguments(site.node());
        ev.reset();
        List<String> methods = List.of(t.method());
        List<Expr> methodExprs = List.of();
        if (t.methodParam() >= 0) {
            methods = List.of("ANY");
            if (t.methodParam() < args.size()) {
                List<String> literals = methodLiterals(ev.evalString(args.get(t.methodParam()), site.scope()));
                if (literals != null) {
                    methods = literals;
                }
                methodExprs = List.of(new Expr(args.get(t.methodParam()), site.scope()));
            }
        } else if (t.optionsParam() >= 0) {
            // 래퍼가 옵션 객체를 매개변수로 받아 그대로 넘긴다: 호출 지점의 인자에서 메서드를 읽는다. 인자를 주지 않았으면 기본값이다
            if (t.optionsParam() < args.size()) {
                methods = methodsOf(ev.optionMethods(args.get(t.optionsParam()), site.scope(), t.methodKeys()));
                methodExprs = ev.optionMethodExprs(args.get(t.optionsParam()), site.scope(), t.methodKeys());
            } else {
                methods = List.of("GET");
            }
        }
        // URL 인자와 메서드가 같은 객체 변수의 프로퍼티면 대입한 객체마다 짝지어 끼운다(F-010-R27)
        List<Candidate> out = new ArrayList<>();
        for (Part p : t.url().parts()) {
            if (p.kind() != Kind.PARAM || p.fn() != t.fn() || p.index() >= args.size()) {
                continue;
            }
            List<Variant> variants = pairedVariants(new Expr(args.get(p.index()), site.scope()), methodExprs);
            if (variants != null) {
                for (Variant variant : variants) {
                    expand(site, t, args, p.index(), variant.urls(), variant.methods(), true, out);
                }
                return out;
            }
        }
        expand(site, t, args, -1, null, methods, false, out);
        return out;
    }

    /**
     * 래퍼의 URL 틀에 호출 지점의 인자를 끼워 요청 후보를 만든다.
     *
     * @param fixedIndex 값이 이미 정해진 매개변수의 순번(짝지은 객체 후보의 URL). 없으면 -1
     * @param fixed fixedIndex 자리에 끼울 값들
     */
    private void expand(Site site, Template t, List<SyntaxNode> args, int fixedIndex, List<UrlValue> fixed, List<String> methods,
            boolean paired, List<Candidate> out) {
        SourceLocation at = site.unit().loc(site.node());
        List<UrlValue> urls = List.of(UrlValue.EMPTY);
        SyntaxNode urlArg = null;
        // 인자가 경로를 담고 있는가. 경로 변수(id) 하나를 넘기는 호출은 래퍼 호출이 아니다.
        boolean argHasPath = false;
        for (Part p : t.url().parts()) {
            List<UrlValue> piece;
            if (p.kind() == Kind.PARAM && p.fn() == t.fn()) {
                if (p.index() < args.size()) {
                    urlArg = args.get(p.index());
                    piece = p.index() == fixedIndex ? fixed : ev.evalString(urlArg, site.scope());
                    for (UrlValue v : piece) {
                        argHasPath |= v.hasPathLiteral();
                    }
                } else {
                    piece = List.of(UrlValue.DYNAMIC);
                }
            } else {
                piece = List.of(new UrlValue(List.of(p), false));
            }
            urls = Evaluator.product(urls, piece, false);
        }
        boolean emit = argHasPath || t.url().startsWithParam();
        boolean inferred = paired || ev.inferred || methods.size() > 1 || unverifiedCalls.contains(site.node());
        String templateMethod = methods.size() == 1 ? methods.get(0) : "ANY";
        boolean route = t.api().equals(RequestMatcher.ROUTE);
        for (UrlValue url : urls) {
            if (t.url().uncertain()) {
                url = url.asUncertain();
            }
            registerTemplate(url, t.api(), templateMethod, -1, null, -1, null, t.methodKeys(), t.via(), t.level() + 1, t.markup());
            UrlValue.Resolved r = url.resolve();
            if (!emit || r.ignorable()
                    || (route ? !isRoutePath(r) : !r.staticPath() && !(t.api().equals("navigation") && isRoot(r)))) {
                continue;
            }
            if (t.markup() && (r.external() || !looksLikeServerPath(r.path()))) {
                continue; // HTML 문자열의 태그 속성으로 들어가는 값: 서버 경로 꼴일 때만 요청이다(상대 경로, 정적 자원 제외)
            }
            out.add(new Candidate(site, at, t.api(), methods, r, urlArg, inferred || url.uncertain(), t.via(), Map.of()));
        }
    }

    // ------------------------------------------------------------------ 기록: 클라이언트 라우트

    /** 요소 이름이 라우터 패키지에서 import한 것이면 그 import. 아니면 null. */
    private static Sym.ImportRef routerImport(RouteAttr attr) {
        Sym s = attr.scope().lookup(attr.component().text());
        return s == null || s.importRef == null || !ModuleInfo.isRouterSpec(s.importRef.spec()) ? null : s.importRef;
    }

    /** 경로 속성의 값 후보. {@code to={{ pathname: X }}}면 X다. 경로가 없는 객체면 빈 목록. */
    private List<UrlValue> routeValues(RouteAttr attr) {
        return attributeValues(attr.value(), attr.scope());
    }

    private List<UrlValue> attributeValues(SyntaxNode attrValue, Scope attrScope) {
        SyntaxNode value = attrValue;
        Scope scope = attrScope;
        if (value.is("object")) {
            Expr pathname = ev.properties(value, scope).get("pathname");
            if (pathname == null) {
                return List.of();
            }
            value = pathname.node();
            scope = pathname.scope();
        }
        if (value.is("string") && Syntax.stringContent(value).isEmpty() && value.text().length() > 2) {
            // JSX 문자열 속성을 조각 없이 한 토큰으로 주는 문법
            return List.of(UrlValue.literal(value.text().substring(1, value.text().length() - 1)));
        }
        return ev.evalString(value, scope);
    }

    /** {@code <Route path="/login">}: 프로젝트가 선언한 클라이언트 라우트를 모은다. 요청이 아니다. */
    private void collectClientRoutes(ModuleInfo m) {
        for (RouteAttr attr : m.routeAttrs) {
            Sym.ImportRef imported = routerImport(attr);
            if (imported == null || !attr.attr().equals("path") || !imported.name().equals("Route")) {
                continue;
            }
            ev.reset();
            for (UrlValue v : routeValues(attr)) {
                UrlValue.Resolved r = v.resolve();
                if (!r.external() && !r.dynamic() && r.path().startsWith("/")) {
                    clientRoutes.add(r.path().split("/"));
                }
            }
        }
    }

    /** {@code <Link to>}, {@code <NavLink to>}, {@code <Navigate to>}, {@code <Redirect to>}, next/link의 {@code <Link href>}. */
    private void emitRouteLinks(ModuleInfo m) {
        for (RouteAttr attr : m.routeAttrs) {
            Sym.ImportRef imported = routerImport(attr);
            if (imported == null) {
                continue;
            }
            boolean link = attr.attr().equals("to") ? ROUTE_LINKS.contains(imported.name())
                    : attr.attr().equals("href") && imported.spec().equals("next/link");
            if (!link) {
                continue;
            }
            try {
                Site site = new Site(SiteKind.JSX, attr.at(), attr.scope(), attr.owner(), attr.unit());
                SourceLocation at = attr.unit().loc(attr.at());
                ev.reset();
                List<Candidate> candidates = new ArrayList<>();
                for (UrlValue v : routeValues(attr)) {
                    UrlValue.Resolved r = v.resolve();
                    if (!r.ignorable() && isRoutePath(r)) {
                        candidates.add(new Candidate(site, at, RequestMatcher.ROUTE, List.of("GET"), r, attr.value(),
                                ev.inferred || v.uncertain(), null, Map.of()));
                    }
                }
                declareCandidates(candidates);
            } catch (RuntimeException | StackOverflowError e) {
                // 이 링크만 포기한다.
            }
        }
    }

    /**
     * JSX의 HTML 요소가 가리키는 서버 경로(F-010-R42): {@code <a href>}는 {@code api=link}, {@code <form action>}은
     * {@code api=form}(메서드는 method 속성, 없으면 GET), {@code <img src>}는 {@code api=img}, {@code <iframe src>}는
     * {@code api=frame}. 값이 서버 경로 꼴일 때만 만들고(정적 자원·외부 URL·상대 경로·값을 모르는 식은 미해결로도 남기지 않는다),
     * 경로가 선언된 클라이언트 라우트와 같으면 {@code clientRoute=true}를 붙인다.
     */
    private void emitHostLinks(ModuleInfo m) {
        for (ModuleInfo.HostAttr attr : m.hostAttrs) {
            try {
                Site site = new Site(SiteKind.JSX, attr.at(), attr.scope(), attr.owner(), attr.unit());
                SourceLocation at = attr.unit().loc(attr.at());
                String api = switch (attr.element()) {
                    case "form" -> "form";
                    case "img" -> "img";
                    case "iframe", "frame" -> "frame";
                    default -> "link";
                };
                ev.reset();
                String method = "GET";
                if (attr.method() != null) {
                    List<UrlValue> values = attributeValues(attr.method(), attr.scope());
                    String literal = values.size() == 1 ? values.get(0).literalOrNull() : null;
                    if (literal == null) {
                        method = "ANY";
                    } else if (HTTP_METHODS.contains(literal.trim().toUpperCase(Locale.ROOT))) {
                        method = literal.trim().toUpperCase(Locale.ROOT);
                    }
                }
                List<Candidate> candidates = new ArrayList<>();
                for (UrlValue v : attributeValues(attr.value(), attr.scope())) {
                    UrlValue.Resolved r = v.resolve();
                    if (r.ignorable() || r.external() || !looksLikeServerPath(r.path())) {
                        continue;
                    }
                    Map<String, Object> extra = isClientRoute(r) ? Map.of(CLIENT_ROUTE_ATTR, true) : Map.of();
                    candidates.add(new Candidate(site, at, api, List.of(method), r, attr.value(), ev.inferred || v.uncertain(), null,
                            extra));
                }
                declareCandidates(candidates);
            } catch (RuntimeException | StackOverflowError e) {
                // 이 요소만 포기한다.
            }
        }
    }

    /** HTML 문자열을 DOM에 넣는 메서드: {@code $(el).html(…)}, {@code .append(…)}, {@code document.write(…)}. */
    private static final Set<String> MARKUP_METHODS = Set.of("html", "append");

    /**
     * 이 지점이 HTML 문자열을 DOM에 넣는 자리면 그 문자열 식들: {@code x.html(H)}·{@code x.append(H, …)},
     * {@code document.write(H)}·{@code writeln}, {@code el.insertAdjacentHTML(pos, H)}, 요소를 만드는 {@code $(H)}·
     * {@code jQuery(H)}, {@code el.innerHTML = H}·{@code +=}. 아니면 빈 목록.
     */
    private static List<SyntaxNode> markupExpressions(Site site) {
        SyntaxNode node = site.node();
        if (site.kind() == SiteKind.ASSIGN) {
            SyntaxNode left = Syntax.unwrap(node.field("left"));
            SyntaxNode right = node.field("right");
            return left != null && right != null && left.is("member_expression") && "innerHTML".equals(Syntax.memberName(left))
                    ? List.of(right) : List.of();
        }
        if (site.kind() != SiteKind.CALL) {
            return List.of();
        }
        SyntaxNode callee = Syntax.callee(node);
        List<SyntaxNode> args = Syntax.arguments(node);
        if (callee == null || args.isEmpty()) {
            return List.of();
        }
        if (callee.is("identifier")) {
            boolean jquery = (callee.text().equals("$") || callee.text().equals("jQuery")) && site.scope().lookup(callee.text()) == null;
            return jquery ? List.of(args.get(0)) : List.of();
        }
        if (!callee.is("member_expression")) {
            return List.of();
        }
        String name = Syntax.memberName(callee);
        SyntaxNode object = Syntax.unwrap(callee.field("object"));
        if (name == null || object == null) {
            return List.of();
        }
        if (MARKUP_METHODS.contains(name)) {
            return args;
        }
        if (name.equals("write") || name.equals("writeln")) {
            String receiver = object.text().replaceAll("\\s+", "");
            return receiver.equals("document") || receiver.endsWith(".document") ? args : List.of();
        }
        if (name.equals("insertAdjacentHTML")) {
            return args.size() >= 2 ? List.of(args.get(1)) : List.of();
        }
        return List.of();
    }

    /**
     * DOM에 넣는 HTML 문자열 안의 태그가 가리키는 URL(F-010-R45): {@code .html('<iframe src="' + url + '">')}처럼 스크립트가
     * 문자열로 조립한 {@code <iframe src>}·{@code <a href>}·{@code <form action>}·{@code <img src>}. 문자열 식을 평가해
     * (상수, {@code +} 연결, 템플릿 리터럴, JSP 치환) 속성 값을 읽고, JSX의 HTML 요소와 같은 꼴의 요청
     * ({@code api=frame|link|form|img})으로 기록한다. 서버 경로 꼴이 아닌 값(정적 자원, 외부 URL, 상대 경로, {@code #},
     * {@code javascript:})은 요청이 아니다. 값에 정적인 조각이 하나도 없으면 미해결({@code http-url})로 남기고({@code <img>}는
     * 남기지 않는다), 값이 이름 있는 함수의 매개변수면 그 함수의 호출 지점에서 요청을 만든다(요청 래퍼).
     */
    private void emitMarkupRequests(Site site) {
        List<SyntaxNode> expressions = markupExpressions(site);
        if (expressions.isEmpty()) {
            return;
        }
        for (SyntaxNode expression : expressions) {
            SyntaxNode html = Syntax.unwrap(expression);
            if (html == null || !html.isAny("string", "template_string", "binary_expression", "identifier", "member_expression")) {
                continue;
            }
            // 위치는 호출 식의 첫 줄이 아니라 HTML 문자열 식이다(여러 줄에 걸친 $('<div>')\n.html('<iframe …>') 꼴이 흔하다)
            SourceLocation at = site.unit().loc(html);
            ev.reset();
            // 태그의 순번 → 그 태그의 URL 후보들(평가 결과가 여러 갈래면 같은 순번끼리가 한 지점의 후보다)
            Map<Integer, List<Candidate>> byTag = new java.util.TreeMap<>();
            Set<Integer> unknown = new java.util.TreeSet<>();
            for (UrlValue value : ev.evalString(html, site.scope())) {
                for (Markup.Link link : Markup.links(value)) {
                    UrlValue.Resolved r = link.url().resolve();
                    if (r.ignorable()) {
                        continue;
                    }
                    String api = switch (link.element()) {
                        case "form" -> "form";
                        case "img" -> "img";
                        case "iframe", "frame" -> "frame";
                        default -> "link";
                    };
                    String method = link.method() == null ? "GET" : link.method();
                    boolean image = api.equals("img");
                    if (image && link.open()) {
                        continue; // src가 이 문자열에서 끝나지 않는다: 확장자를 몰라 정적 자원인지 가릴 수 없다
                    }
                    if (!image) {
                        registerTemplate(link.url(), api, method, -1, null, -1, null, List.of(), null, 0, true);
                    }
                    if (r.external()) {
                        continue;
                    }
                    if (looksLikeServerPath(r.path())) {
                        Map<String, Object> extra = isClientRoute(r) ? Map.of(CLIENT_ROUTE_ATTR, true) : Map.of();
                        byTag.computeIfAbsent(link.ordinal(), k -> new ArrayList<>()).add(new Candidate(site, at, api,
                                List.of(method), r, html, ev.inferred || link.url().uncertain(), null, extra));
                    } else if (!r.staticPath() && !image) {
                        unknown.add(link.ordinal());
                    }
                }
            }
            byTag.values().forEach(this::declareCandidates);
            unknown.removeAll(byTag.keySet());
            if (!unknown.isEmpty()) {
                graph.addUnresolvedEdge(ownerId(site), EdgeKind.REQUESTS, CATEGORY_URL, brief(html), at);
            }
        }
    }

    /**
     * 경로가 프로젝트가 선언한 클라이언트 라우트인가. {@code location.href = URL.LOGIN}처럼 브라우저가 페이지를 다시 받더라도
     * 그 경로를 처리하는 것은 클라이언트 라우터다. 라우트의 {@code :id} 조각은 무엇에든, 끝의 {@code *}는 나머지 전부에 맞는다.
     */
    private boolean isClientRoute(UrlValue.Resolved r) {
        if (clientRoutes.isEmpty() || r.external() || !r.path().startsWith("/")) {
            return false;
        }
        String[] segments = r.path().split("/");
        for (String[] route : clientRoutes) {
            boolean splat = route.length > 0 && route[route.length - 1].equals("*");
            int n = splat ? route.length - 1 : route.length;
            if (splat ? segments.length < n : segments.length != n) {
                continue;
            }
            boolean match = true;
            for (int i = 0; i < n && match; i++) {
                match = route[i].startsWith(":") || route[i].equals(segments[i]);
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 기록: 폼 메서드, 옵션 객체의 URL

    /** 모듈 → 그 모듈에서 {@code X.method = '…'}로 메서드를 대입한 지점들. */
    private final Map<ModuleInfo, List<Site>> methodAssigns = new HashMap<>();
    /** 페이지 ID → 그 페이지(와 include한 페이지들)의 폼 표: 폼 이름 또는 {@code #id} → 메서드. */
    private final Map<String, Map<String, String>> formTables = new HashMap<>();

    /**
     * 폼 action을 바꾸는 지점의 HTTP 메서드(F-010-R24). 같은 함수에서 같은 폼에 {@code .method = 'get'}을 대입했으면 그것,
     * 아니면 이 스크립트를 싣는 페이지의 폼 표(jsp가 {@code JSP_PAGE}의 {@code attrs.formMethods}로 준다)에서 그 이름·ID의 폼
     * 메서드를 읽는다. 폼을 알 수 없거나 페이지마다 다르면 ANY다.
     */
    private String formMethod(Site site, SyntaxNode form, Scope scope) {
        String formText = form.text().replaceAll("\\s+", "");
        SyntaxNode function = enclosingFunction(site.node());
        for (Site assign : methodAssigns.computeIfAbsent(site.unit().module, JsProjectAnalysis::collectMethodAssigns)) {
            if (assign.unit() != site.unit() || enclosingFunction(assign.node()) != function) {
                continue;
            }
            SyntaxNode left = Syntax.unwrap(assign.node().field("left"));
            SyntaxNode right = Syntax.unwrap(assign.node().field("right"));
            SyntaxNode object = left == null ? null : Syntax.unwrap(left.field("object"));
            if (object != null && right != null && right.is("string") && object.text().replaceAll("\\s+", "").equals(formText)) {
                String literal = Syntax.stringContent(right).toUpperCase(Locale.ROOT);
                if (HTTP_METHODS.contains(literal)) {
                    return literal;
                }
            }
        }
        String key = formKey(form, scope == null ? site.scope() : scope, 0);
        if (key == null) {
            return "ANY";
        }
        ModuleInfo module = site.unit().module;
        if (module.embedded) {
            String own = formTable(Ids.jspPage(module.path)).get(key);
            if (own != null) {
                return own;
            }
        }
        String found = null;
        for (String page : pages.pagesOf(module)) {
            String method = formTable(page).get(key);
            if (method == null) {
                continue;
            }
            if (found != null && !found.equals(method)) {
                return "ANY";
            }
            found = method;
        }
        return found == null ? "ANY" : found;
    }

    /** 노드를 감싼 가장 가까운 함수(이름 없는 콜백 포함). 최상위 코드면 null. */
    private static SyntaxNode enclosingFunction(SyntaxNode node) {
        for (SyntaxNode p = node.parent(); p != null; p = p.parent()) {
            if (Syntax.isFunction(p) || p.is("method_definition")) {
                return p;
            }
        }
        return null;
    }

    private static List<Site> collectMethodAssigns(ModuleInfo module) {
        List<Site> out = new ArrayList<>();
        for (Site s : module.sites) {
            if (s.kind() != SiteKind.ASSIGN) {
                continue;
            }
            SyntaxNode left = Syntax.unwrap(s.node().field("left"));
            if (left != null && left.is("member_expression") && "method".equals(Syntax.memberName(left))) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * 폼을 가리키는 식에서 폼 표의 키(이름, 또는 {@code #id})를 읽는다: {@code document.NAME}, {@code document.forms.NAME},
     * {@code document.forms['NAME']}, {@code document.all.NAME}, {@code document.getElementById('ID')}, {@code $('#ID')},
     * {@code $('form[name=NAME]')}, 그리고 그런 값을 한 번 대입한 변수. 모르면 null.
     */
    private String formKey(SyntaxNode expression, Scope scope, int level) {
        SyntaxNode expr = Syntax.unwrap(expression);
        if (expr == null || level > 3) {
            return null;
        }
        if (expr.is("identifier")) {
            Sym s = scope.lookup(expr.text());
            if (s == null || s.param || s.multiAssigned() || s.init == null || !s.destruct.isEmpty()) {
                return null;
            }
            return formKey(s.init, s.initScope, level + 1);
        }
        if (expr.isAny("member_expression", "subscript_expression")) {
            List<String> chain = Syntax.chain(expr);
            if (chain == null) {
                return null;
            }
            if (chain.get(0).equals("window") && chain.size() > 1) {
                chain = chain.subList(1, chain.size());
            }
            if (!chain.get(0).equals("document") || scope.lookup("document") != null) {
                return null;
            }
            if (chain.size() == 2) {
                return chain.get(1);
            }
            if (chain.size() == 3 && (chain.get(1).equals("forms") || chain.get(1).equals("all"))) {
                return chain.get(2);
            }
            return null;
        }
        if (expr.is("call_expression")) {
            SyntaxNode callee = Syntax.callee(expr);
            List<SyntaxNode> args = Syntax.arguments(expr);
            SyntaxNode arg = args.size() >= 1 ? Syntax.unwrap(args.get(0)) : null;
            if (callee == null || arg == null) {
                return null;
            }
            String calleeText = callee.text().replaceAll("\\s+", "");
            if (calleeText.equals("document.getElementById") && arg.is("string")) {
                return "#" + Syntax.stringContent(arg);
            }
            if (calleeText.equals("$") || calleeText.equals("jQuery")) {
                if (!arg.is("string")) {
                    return formKey(arg, scope, level + 1);
                }
                String selector = Syntax.stringContent(arg).trim();
                if (selector.matches("#[\\w-]+")) {
                    return selector;
                }
                java.util.regex.Matcher byName = java.util.regex.Pattern
                        .compile("(?:form)?\\[name=['\"]?([\\w-]+)['\"]?\\]").matcher(selector);
                return byName.matches() ? byName.group(1) : null;
            }
        }
        return null;
    }

    /**
     * jsp가 {@code JSP_PAGE} 노드에 싣는 필드 표의 attrs 키. 값은 {@code "<폼 name 또는 #id>.<필드 name>=<경로>"}와
     * {@code "#<input id>=<경로>"} 문자열의 목록이다(F-011-R29).
     */
    public static final String FORM_FIELDS_ATTR = "formFields";
    private static final int MAX_FIELD_URLS = 8;
    /** 페이지 ID → 그 페이지(와 include한 페이지들)의 필드 표: 키 → URL들. */
    private final Map<String, Map<String, List<String>>> fieldTables = new HashMap<>();

    /**
     * 폼 필드의 값을 읽는 식이 가리키는 URL(F-010-R41): {@code document.frm.zip_url.value}, {@code frm.zip_url.value},
     * {@code document.getElementById('zipUrl').value}, {@code $('#zipUrl').val()}. hidden 필드에 팝업 URL을 두고 스크립트가
     * 꺼내 여는 화면에서 그 URL을 jsp가 페이지의 필드 표로 넘긴다. 폼을 아는 식은 {@code <폼 키>.<필드>}로, 폼이 매개변수 등이라
     * 모르는 식은 필드 이름만으로 찾는다. 인라인 스크립트는 주인 페이지의 표를 먼저 보고, 없으면(그리고 JS 파일은) 그 스크립트를
     * 싣는 페이지들의 표에서 찾는다(값이 갈리면 후보마다).
     */
    private Evaluator.FieldValue fieldUrls(SyntaxNode expression, Scope scope) {
        SyntaxNode expr = Syntax.unwrap(expression);
        if (expr == null) {
            return null;
        }
        String key = null;
        String field = null;
        if (expr.is("member_expression") && "value".equals(Syntax.memberName(expr))) {
            SyntaxNode target = Syntax.unwrap(expr.field("object"));
            if (target == null) {
                return null;
            }
            if (target.is("call_expression")) {
                key = formKey(target, scope, 0);
            } else if (target.isAny("member_expression", "subscript_expression")) {
                field = Syntax.memberName(target);
                String form = field == null ? null : formKey(target.field("object"), scope, 0);
                if (form != null) {
                    key = form + "." + field;
                }
            }
        } else if (expr.is("call_expression")) {
            SyntaxNode callee = Syntax.callee(expr);
            key = callee == null ? null : formKey(callee.field("object"), scope, 0);
        }
        if (key == null && field == null) {
            return null;
        }
        if (key != null && !key.contains(".") && !key.startsWith("#")) {
            return null; // 폼 자체를 가리키는 식이다(document.frm.value)
        }
        ModuleInfo module = scope.module;
        String suffix = "." + field;
        if (module.embedded) {
            List<String> own = fieldLookup(Ids.jspPage(module.path), key, key == null ? suffix : null);
            if (!own.isEmpty()) {
                return new Evaluator.FieldValue(own, key != null);
            }
        }
        List<String> found = new ArrayList<>();
        List<String> loading = pages.pagesOf(module);
        for (String page : loading) {
            for (String url : fieldLookup(page, key, key == null ? suffix : null)) {
                if (!found.contains(url) && found.size() < MAX_FIELD_URLS) {
                    found.add(url);
                }
            }
        }
        if (loading.isEmpty() && !module.embedded) {
            // 어느 페이지가 싣는지 모르는 스크립트: 그 필드의 URL이 프로젝트 전체에 하나뿐일 때만 쓴다(F-010-R34와 같은 약한 근거)
            for (Node page : graph.nodes(NodeKind.JSP_PAGE)) {
                if (!page.attrs().containsKey(FORM_FIELDS_ATTR)) {
                    continue;
                }
                for (String url : fieldLookup(page.id(), key, key == null ? suffix : null)) {
                    if (!found.contains(url)) {
                        found.add(url);
                    }
                }
            }
            if (found.size() != 1) {
                return null;
            }
        }
        return found.isEmpty() ? null : new Evaluator.FieldValue(found, false);
    }

    /** 페이지의 필드 표에서 키가 같은 것(key), 또는 폼을 가리지 않고 필드 이름이 같은 것(suffix)의 URL들. */
    private List<String> fieldLookup(String page, String key, String suffix) {
        Map<String, List<String>> table = fieldTable(page);
        if (key != null) {
            return table.getOrDefault(key, List.of());
        }
        List<String> out = new ArrayList<>();
        table.forEach((k, urls) -> {
            if (k.endsWith(suffix)) {
                for (String url : urls) {
                    if (!out.contains(url)) {
                        out.add(url);
                    }
                }
            }
        });
        return out;
    }

    private Map<String, List<String>> fieldTable(String page) {
        Map<String, List<String>> known = fieldTables.get(page);
        if (known != null) {
            return known;
        }
        Map<String, List<String>> table = new LinkedHashMap<>();
        for (String p : pages.includedPages(page)) {
            Node node = graph.node(p).orElse(null);
            Object raw = node == null ? null : node.attrs().get(FORM_FIELDS_ATTR);
            if (!(raw instanceof List<?> entries)) {
                continue;
            }
            for (Object entry : entries) {
                String text = String.valueOf(entry);
                int eq = text.indexOf('=');
                if (eq <= 0 || eq == text.length() - 1) {
                    continue;
                }
                List<String> urls = table.computeIfAbsent(text.substring(0, eq), k -> new ArrayList<>(1));
                String url = text.substring(eq + 1);
                if (!urls.contains(url)) {
                    urls.add(url);
                }
            }
        }
        fieldTables.put(page, table);
        return table;
    }

    /** 페이지와 그 페이지가 include하는 페이지들의 폼 표를 합친 것. 같은 키의 메서드가 서로 다르면 ANY. */
    private Map<String, String> formTable(String page) {
        Map<String, String> known = formTables.get(page);
        if (known != null) {
            return known;
        }
        Map<String, String> table = new HashMap<>();
        for (String p : pages.includedPages(page)) {
            Node node = graph.node(p).orElse(null);
            Object raw = node == null ? null : node.attrs().get(FORM_METHODS_ATTR);
            if (!(raw instanceof List<?> entries)) {
                continue;
            }
            for (Object entry : entries) {
                String text = String.valueOf(entry);
                int eq = text.lastIndexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String method = text.substring(eq + 1).trim().toUpperCase(Locale.ROOT);
                table.merge(text.substring(0, eq), method, (a, b) -> a.equals(b) ? a : "ANY");
            }
        }
        formTables.put(page, table);
        return table;
    }

    /**
     * 요청 API가 아닌 호출의 옵션 객체에 든 URL(F-010-R23): {@code $('#t').bootstrapTable({ url: '/x', method: 'get' })},
     * zTree의 {@code async: { url }}, DataTables의 {@code ajax: '/x'}·{@code ajax: { url }}, jquery-confirm의
     * {@code content: 'url:/x'}. 그 플러그인이 정말 요청을 보내는지는 모르므로 {@code api=option}인 요청을 추론으로 만든다.
     * 값이 서버 경로처럼 보일 때만 만들고 그렇지 않으면 미해결로도 남기지 않는다.
     */
    private void emitOptionRequests(Site site) {
        List<SyntaxNode> args = Syntax.arguments(site.node());
        if (args.isEmpty()) {
            return;
        }
        List<Candidate> candidates = new ArrayList<>();
        SourceLocation at = site.unit().loc(site.node());
        SyntaxNode calleeNode = site.kind() == SiteKind.NEW ? site.node().field("constructor") : Syntax.callee(site.node());
        String callee = calleeNode == null ? "" : calleeNode.text().replaceAll("\\s+", "");
        for (SyntaxNode arg : args) {
            SyntaxNode inner = Syntax.unwrap(arg);
            if (inner == null) {
                continue;
            }
            if (inner.isAny("string", "template_string", "binary_expression")) {
                prefixedArgument(site, at, inner, callee, candidates);
                continue;
            }
            if (!inner.isAny("object", "identifier", "member_expression")) {
                continue;
            }
            Map<String, Evaluator.Expr> props = ev.properties(inner, site.scope());
            if (props.isEmpty()) {
                continue;
            }
            optionUrl(site, at, props.get("url"), "url", methodOfOptions(props), null, callee, candidates);
            for (String nested : List.of("ajax", "async")) {
                Evaluator.Expr value = props.get(nested);
                if (value == null) {
                    continue;
                }
                if (ev.isObjectLike(value.node(), value.scope())) {
                    Map<String, Evaluator.Expr> inside = ev.properties(value.node(), value.scope());
                    optionUrl(site, at, inside.get("url"), nested + ".url", methodOfOptions(inside), null, callee, candidates);
                } else if (nested.equals("ajax")) {
                    optionUrl(site, at, value, nested, null, null, callee, candidates);
                }
            }
            // jquery-confirm: content: 'url:/x'. layer: type이 2면 content가 iframe으로 여는 URL이다
            optionUrl(site, at, props.get("content"), "content", "GET", "url:", callee, candidates);
            if (isLayerFrame(props, callee)) {
                optionUrl(site, at, props.get("content"), "content", "GET", null, callee, candidates);
            }
        }
        declareCandidates(candidates);
    }

    /** layer.open({ type: 2, content: url }): iframe 층. */
    private static boolean isLayerFrame(Map<String, Evaluator.Expr> props, String callee) {
        Evaluator.Expr type = props.get("type");
        SyntaxNode node = type == null ? null : Syntax.unwrap(type.node());
        return node != null && node.is("number") && node.text().equals("2") && (callee.equals("layer.open") || callee.endsWith(".layer.open"));
    }

    /** 옵션 객체의 {@code method}·{@code type}·{@code mtype}이 HTTP 메서드 이름이면 그것, 값을 모르면 ANY, 그런 프로퍼티가 없으면 null. */
    private String methodOfOptions(Map<String, Evaluator.Expr> props) {
        boolean present = false;
        for (String key : List.of("method", "type", "mtype")) {
            Evaluator.Expr value = props.get(key);
            if (value == null) {
                continue;
            }
            present = true;
            List<UrlValue> values = ev.evalString(value.node(), value.scope());
            String literal = values.size() == 1 ? values.get(0).literalOrNull() : null;
            if (literal != null && HTTP_METHODS.contains(literal.toUpperCase(Locale.ROOT))) {
                return literal.toUpperCase(Locale.ROOT);
            }
        }
        return present ? "ANY" : null;
    }

    /**
     * 옵션에 메서드를 적지 않았을 때 그 플러그인이 쓰는 기본 메서드(F-010-R32). 모르는 플러그인이면 ANY.
     * bootstrapTable의 {@code method} 기본값은 get, jqGrid의 {@code mtype}은 GET, DataTables의 {@code ajax}는 GET,
     * zTree의 {@code async.type}은 post, easyui의 datagrid 계열은 post다.
     */
    private static String pluginDefaultMethod(String callee, String optionKey) {
        String last = callee.substring(callee.lastIndexOf('.') + 1);
        switch (optionKey) {
            case "url":
                if (last.equals("bootstrapTable") || last.equals("jqGrid")) {
                    return "GET";
                }
                if (EASYUI_GRIDS.contains(last)) {
                    return "POST";
                }
                break;
            case "ajax", "ajax.url":
                if (last.equals("DataTable") || last.equals("dataTable")) {
                    return "GET";
                }
                break;
            case "async.url":
                if (callee.contains("zTree")) {
                    return "POST";
                }
                break;
            default:
                break;
        }
        return "ANY";
    }

    private static final Set<String> EASYUI_GRIDS = Set.of("datagrid", "treegrid", "combogrid", "combobox", "combotree",
            "propertygrid");

    private Map<String, Object> optionExtra(String key, String callee) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("optionKey", key);
        if (!callee.isEmpty()) {
            String text = Syntax.brief(callee);
            extra.put("callee", text.length() > 80 ? text.substring(0, 77) + "..." : text);
        }
        return extra;
    }

    /**
     * @param method 옵션에서 읽은 메서드. 옵션에 메서드가 없으면 null(플러그인의 기본값을 쓴다)
     * @param prefix 값이 이 접두로 시작할 때만 본다(jquery-confirm의 {@code content: 'url:/path'}). 없으면 null
     */
    private void optionUrl(Site site, SourceLocation at, Evaluator.Expr value, String key, String method, String prefix,
            String callee, List<Candidate> out) {
        SyntaxNode node = value == null ? null : Syntax.unwrap(value.node());
        if (node == null || !node.isAny("string", "template_string", "binary_expression", "identifier", "member_expression")) {
            return;
        }
        String resolved = method != null ? method : pluginDefaultMethod(callee, key);
        for (UrlValue url : ev.evalString(node, value.scope())) {
            if (prefix != null) {
                url = stripPrefix(url, prefix);
                if (url == null) {
                    continue;
                }
            }
            UrlValue.Resolved r = url.resolve();
            if (r.ignorable() || r.external() || !looksLikeServerPath(r.path())) {
                continue;
            }
            out.add(new Candidate(site, at, "option", List.of(resolved), r, node, true, null, optionExtra(key, callee)));
        }
    }

    /**
     * 인자로 준 문자열이 {@code iframe:}·{@code url:}로 시작하고 그 뒤가 서버 경로면(jBox의 {@code $.jBox("iframe:/x")},
     * {@code top.$.jBox.open("iframe:…")}) 그 경로를 여는 요청으로 본다. jBox는 {@code get:}·{@code post:}도 받는다.
     */
    private void prefixedArgument(Site site, SourceLocation at, SyntaxNode node, String callee, List<Candidate> out) {
        String text = node.text();
        boolean jbox = callee.toLowerCase(Locale.ROOT).contains("jbox");
        if (!text.contains("iframe:") && !text.contains("url:") && !(jbox && (text.contains("get:") || text.contains("post:")))) {
            return;
        }
        for (UrlValue url : ev.evalString(node, site.scope())) {
            String method = "GET";
            UrlValue stripped = stripPrefix(url, "iframe:");
            if (stripped == null) {
                stripped = stripPrefix(url, "url:");
            }
            if (stripped == null && jbox) {
                stripped = stripPrefix(url, "get:");
                if (stripped == null) {
                    stripped = stripPrefix(url, "post:");
                    method = "POST";
                }
            }
            if (stripped == null) {
                continue;
            }
            UrlValue.Resolved r = stripped.resolve();
            if (r.ignorable() || r.external() || !looksLikeServerPath(r.path())) {
                continue;
            }
            out.add(new Candidate(site, at, "option", List.of(method), r, node, true, null, optionExtra("arg", callee)));
        }
    }

    /** {@code 'url:/path'}의 접두를 뗀다. 그 꼴이 아니면 null. */
    private static UrlValue stripPrefix(UrlValue url, String prefix) {
        List<Part> parts = url.parts();
        if (parts.isEmpty() || parts.get(0).kind() != Kind.LIT || !parts.get(0).text().startsWith(prefix)) {
            return null;
        }
        List<Part> rest = new ArrayList<>(parts);
        rest.set(0, Part.lit(parts.get(0).text().substring(prefix.length())));
        return new UrlValue(rest, url.uncertain());
    }

    /**
     * 서버 경로처럼 보이는가: (알 수 없는 접두사 뒤에) {@code /}로 시작하고 첫 조각이 정적이며 정적 자원 확장자로 끝나지 않는다.
     * {@code data.json} 같은 상대 파일과 {@code {*}/{*}.html}처럼 정적 조각이 없는 것은 아니다.
     */
    private static boolean looksLikeServerPath(String path) {
        String p = path.startsWith(UrlValue.DYNAMIC_TOKEN) ? path.substring(UrlValue.DYNAMIC_TOKEN.length()) : path;
        if (!p.startsWith("/") || p.startsWith("//")) {
            return false;
        }
        int end = p.indexOf('/', 1);
        String first = end < 0 ? p.substring(1) : p.substring(1, end);
        if (first.isEmpty() || first.contains(UrlValue.DYNAMIC_TOKEN) || !first.chars().anyMatch(Character::isLetterOrDigit)) {
            return false;
        }
        String last = p.substring(p.lastIndexOf('/') + 1);
        int dot = last.lastIndexOf('.');
        return dot < 0 || !STATIC_EXTENSIONS.contains(last.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ 기록: 호출

    private void emitCalls(ModuleInfo m) {
        for (Site site : m.sites) {
            try {
                emitCall(site);
            } catch (RuntimeException | StackOverflowError e) {
                graph.report(Diagnostic.warn(DiagnosticCodes.PARSE_FAILED, "호출 지점을 해석하지 못했다: " + e, site.unit().loc(site.node())));
            }
        }
    }

    private void emitCall(Site site) {
        SyntaxNode target;
        String callType;
        switch (site.kind()) {
            case CALL -> {
                target = Syntax.callee(site.node());
                callType = "call";
            }
            case NEW -> {
                target = Syntax.unwrap(site.node().field("constructor"));
                callType = "constructor";
            }
            case JSX -> {
                target = site.node();
                callType = "jsx";
            }
            case REF -> {
                target = site.node();
                callType = "ref";
            }
            default -> {
                return;
            }
        }
        if (site.kind() == SiteKind.CALL || site.kind() == SiteKind.NEW) {
            try {
                emitOptionRequests(site);
            } catch (RuntimeException | StackOverflowError e) {
                // 옵션 객체의 URL만 포기한다.
            }
        }
        if (target == null) {
            return;
        }
        SiteKind kind = site.kind();
        if (kind == SiteKind.CALL) {
            // f.bind(x)는 f를 실행하지 않는다. 함수 참조를 넘긴 것과 같게 기록한다.
            SyntaxNode bound = boundFunction(target, site.scope());
            if (bound != null) {
                target = bound;
                kind = SiteKind.REF;
                callType = "ref";
            }
        }
        Resolution r = resolveCallable(target, site.scope(), kind);
        SourceLocation at = site.unit().loc(site.node());
        String from = ownerId(site);
        if (r.missing() != null) {
            if (kind != SiteKind.REF) {
                graph.addUnresolvedEdge(from, EdgeKind.CALLS, CATEGORY_CALL, r.missing(), at);
            }
            return;
        }
        if (r.targets().isEmpty()) {
            if (r.manyDefinitions()) {
                // 함께 싣는 근거가 없고 그 이름의 정의가 프로젝트에 여럿이다: 고르지 않고 건수만 남긴다(F-010-R34)
                Skipped s = skipped.computeIfAbsent(site.unit().module, k -> new Skipped());
                s.globals++;
                if (at != null && at.startLine() > 0) {
                    s.lines.add(at.startLine());
                }
            } else if (site.kind() == SiteKind.CALL || site.kind() == SiteKind.REF) {
                receivedFunction(site, target, kind, from, at);
            }
            return;
        }
        if (r.loadUnverified()) {
            unverifiedCalls.add(site.node());
        }
        for (int i = 0; i < r.targets().size(); i++) {
            FunctionInfo fn = r.targets().get(i);
            if (kind == SiteKind.REF && (fn.isClass() || fn.id.equals(from))) {
                continue;
            }
            Edge.Builder edge = Edge.builder(from, fn.id, EdgeKind.CALLS).location(at).attr("callType", callType);
            if (r.inferred()) {
                edge.inferred();
            }
            if (r.global()) {
                edge.attr("resolution", "global");
                if (i < r.pages().size() && r.pages().get(i) != null) {
                    edge.attr("viaPage", r.pages().get(i));
                }
            }
            if (r.ambiguous()) {
                edge.attr("ambiguous", true);
            }
            if (r.loadUnverified()) {
                edge.attr(LOAD_UNVERIFIED_ATTR, true);
            }
            graph.addEdge(edge.build());
        }
        if (kind == SiteKind.CALL && r.targets().size() == 1) {
            resolvedCalls.add(Map.entry(site, r.targets().get(0)));
        }
    }

    // ------------------------------------------------------------------ 기록: props·매개변수로 받은 함수

    /** JSX에서 컴포넌트에 넘긴 속성을 컴포넌트와 prop 이름별로 모은다(F-010-R15). */
    private void collectProps(ModuleInfo m) {
        for (PropPass pass : m.propPasses) {
            Resolution component = resolveCallable(pass.component(), pass.scope(), SiteKind.JSX);
            if (component.targets().size() != 1) {
                continue;
            }
            PropInfo info = props.computeIfAbsent(component.targets().get(0), k -> new LinkedHashMap<>())
                    .computeIfAbsent(pass.prop(), k -> new PropInfo());
            if (Syntax.isFunction(pass.value())) {
                // 인라인 함수: 본문의 호출은 부모 함수에 귀속돼 있다. 자식에서 잇는 엣지는 만들지 않는다.
                info.function = true;
                continue;
            }
            for (FunctionInfo fn : resolveCallable(pass.value(), pass.scope(), SiteKind.REF).targets()) {
                if (fn.isClass()) {
                    continue;
                }
                info.function = true;
                if (!info.targets.contains(fn)) {
                    info.targets.add(fn);
                }
            }
        }
    }

    /**
     * 대상을 찾지 못한 호출·참조 지점이 매개변수나 props로 받은 함수를 쓰는 것이면: 컴포넌트가 props로 받은 함수를 호출한 지점은
     * JSX 부모가 넘긴 프로젝트 함수로 잇고(F-010-R15), 나머지는 기록하지 않은 건수로 센다(F-010-R16).
     */
    private void receivedFunction(Site site, SyntaxNode target, SiteKind siteKind, String from, SourceLocation at) {
        SyntaxNode expr = Syntax.unwrap(target);
        SiteKind kind = siteKind;
        if (kind == SiteKind.CALL && expr != null && expr.is("member_expression")) {
            String name = Syntax.memberName(expr);
            if ("call".equals(name) || "apply".equals(name)) {
                // callback.call(this, x), callback.apply(this, args): 그 자리에서 부른다
                expr = Syntax.unwrap(expr.field("object"));
            } else if ("bind".equals(name)) {
                // callback.bind(this): 부르지 않고 넘긴다
                expr = Syntax.unwrap(expr.field("object"));
                kind = SiteKind.REF;
            }
        }
        ParamUse use = paramUse(expr, site.scope());
        if (use == null) {
            return;
        }
        if (kind != SiteKind.CALL) {
            paramPasses.add(Map.entry(site, use.key()));
            return;
        }
        functionParams.add(use.key());
        PropInfo info = use.prop() == null || use.fn().isClass() ? null : props.getOrDefault(use.fn(), Map.of()).get(use.prop());
        if (info == null || info.targets.isEmpty()) {
            skip(site, at, true);
            return;
        }
        for (FunctionInfo fn : info.targets) {
            graph.addEdge(Edge.builder(from, fn.id, EdgeKind.CALLS).location(at)
                    .attr("callType", "call").attr("viaProp", use.prop()).inferred().build());
        }
    }

    /**
     * 식이 이름 있는 함수의 매개변수(구조 분해해 꺼낸 이름 포함)나 컴포넌트의 props 프로퍼티를 가리키면 그 쓰임. 아니면 null.
     * 이름 없는 콜백의 매개변수는 프로젝트 밖(라이브러리)이 값을 주므로 대상이 아니다.
     */
    private ParamUse paramUse(SyntaxNode expr, Scope scope) {
        if (expr == null) {
            return null;
        }
        if (Syntax.isIdentifier(expr)) {
            Sym s = scope.lookup(expr.text());
            if (s == null) {
                return null;
            }
            if (!s.param) {
                // const { onSave } = props: 컴포넌트 본문에서 props를 구조 분해해 꺼낸 이름
                SyntaxNode init = s.destruct.size() == 1 && !s.multiAssigned() ? Syntax.unwrap(s.init) : null;
                Sym p = init != null && init.is("identifier") ? s.initScope.lookup(init.text()) : null;
                return p != null && isProps(p) ? new ParamUse(p.paramOwner, s.destruct.get(0), propKey(p.paramOwner, s.destruct.get(0)))
                        : null;
            }
            if (s.paramOwner == null) {
                return null;
            }
            if (s.propName != null) {
                return new ParamUse(s.paramOwner, s.propName, propKey(s.paramOwner, s.propName));
            }
            return new ParamUse(s.paramOwner, null, s.paramIndex >= 0 ? s.paramOwner.id + "#arg:" + s.paramIndex : s);
        }
        List<String> chain = expr.is("member_expression") ? Syntax.chain(expr) : null;
        if (chain == null) {
            return null;
        }
        if (chain.size() == 2 && !chain.get(0).equals("this")) {
            // props.onSave: 컴포넌트의 첫 매개변수의 프로퍼티. 컴포넌트가 아닌 함수의 e.preventDefault() 같은 것과 구분한다.
            Sym s = scope.lookup(chain.get(0));
            if (s != null && isProps(s)) {
                return new ParamUse(s.paramOwner, chain.get(1), propKey(s.paramOwner, chain.get(1)));
            }
        } else if (chain.size() == 3 && chain.get(0).equals("this") && chain.get(1).equals("props")) {
            // this.props.onSave: 클래스 컴포넌트
            Sym self = scope.lookupThis();
            if (self != null && self.preset instanceof Val.Instance i && ev.valueOf(i.classSym()) instanceof Val.Cls c) {
                return new ParamUse(c.info().fn, chain.get(2), propKey(c.info().fn, chain.get(2)));
            }
        }
        return null;
    }

    /** 컴포넌트(JSX가 있는 대문자 함수이거나 JSX 요소로 쓰인 함수)의 첫 매개변수인가. */
    private boolean isProps(Sym s) {
        return s.param && s.paramOwner != null && s.paramIndex == 0 && (s.paramOwner.component || props.containsKey(s.paramOwner));
    }

    private static String propKey(FunctionInfo component, String prop) {
        return component.id + "#prop:" + prop;
    }

    private void skip(Site site, SourceLocation at, boolean call) {
        Skipped s = skipped.computeIfAbsent(site.unit().module, k -> new Skipped());
        if (call) {
            s.calls++;
        } else {
            s.passes++;
        }
        if (at != null && at.startLine() > 0) {
            s.lines.add(at.startLine());
        }
    }

    /**
     * 기록하지 않은 "받은 함수의 호출"을 모듈마다 INFO 진단 1건으로 알린다(F-010-R16). 매개변수를 다른 호출에 넘긴 지점은
     * 그 매개변수가 함수를 받는다는 근거(직접 호출, 호출하는 쪽이 함수를 넘김, JSX 부모가 함수를 넘김)가 있을 때만 센다.
     */
    private void reportSkippedCalls() {
        try {
            for (Map.Entry<Site, FunctionInfo> call : resolvedCalls) {
                Site site = call.getKey();
                SyntaxNode callee = Syntax.callee(site.node());
                if (callee != null && callee.is("member_expression")
                        && ("call".equals(Syntax.memberName(callee)) || "apply".equals(Syntax.memberName(callee)))) {
                    continue; // 인자 순번이 매개변수 순번과 다르다
                }
                List<SyntaxNode> args = Syntax.arguments(site.node());
                for (int i = 0; i < args.size(); i++) {
                    SyntaxNode arg = Syntax.unwrap(args.get(i));
                    if (arg != null && (Syntax.isFunction(arg)
                            || (arg.isAny("identifier", "member_expression") && ev.eval(arg, site.scope()) instanceof Val.Func))) {
                        functionParams.add(call.getValue().id + "#arg:" + i);
                    }
                }
            }
            for (Map.Entry<FunctionInfo, Map<String, PropInfo>> component : props.entrySet()) {
                for (Map.Entry<String, PropInfo> prop : component.getValue().entrySet()) {
                    if (prop.getValue().function) {
                        functionParams.add(propKey(component.getKey(), prop.getKey()));
                    }
                }
            }
            for (Map.Entry<Site, Object> pass : paramPasses) {
                if (functionParams.contains(pass.getValue())) {
                    skip(pass.getKey(), pass.getKey().unit().loc(pass.getKey().node()), false);
                }
            }
        } catch (RuntimeException | StackOverflowError e) {
            // 건수만 덜 세진다.
        }
        for (Map.Entry<ModuleInfo, Skipped> e : skipped.entrySet()) {
            Skipped s = e.getValue();
            StringBuilder lines = new StringBuilder();
            int shown = 0;
            for (int line : s.lines) {
                if (shown == 10) {
                    lines.append(", …");
                    break;
                }
                lines.append(shown++ == 0 ? "" : ", ").append(line);
            }
            String path = e.getKey().path;
            graph.report(Diagnostic.info(CALLS_SKIPPED, "수신자를 알 수 없는 호출 " + (s.calls + s.passes + s.globals)
                    + "건을 기록하지 않았다(매개변수·props로 받은 함수: 직접 호출 " + s.calls + "건, 다른 호출에 넘김 " + s.passes
                    + "건" + (s.globals > 0 ? "; 정의가 여럿인 전역 이름 " + s.globals + "건" : "") + "; 줄 " + lines + "): " + path,
                    SourceLocation.ofFile(path)));
        }
    }

    /** 피호출자가 {@code f.bind}이고 f가 함수면 f의 식. 아니면 null. */
    private SyntaxNode boundFunction(SyntaxNode callee, Scope scope) {
        SyntaxNode expr = Syntax.unwrap(callee);
        if (expr == null || !expr.is("member_expression") || !"bind".equals(Syntax.memberName(expr))) {
            return null;
        }
        SyntaxNode object = Syntax.unwrap(expr.field("object"));
        return object != null && ev.eval(object, scope) instanceof Val.Func ? object : null;
    }

    /** 호출 대상 식을 함수로 푼다. 전역 이름은 다른 스크립트에서 찾는다. */
    private Resolution resolveCallable(SyntaxNode expression, Scope scope, SiteKind kind) {
        SyntaxNode expr = Syntax.unwrap(expression);
        if (expr == null) {
            return Resolution.NONE;
        }
        // f.call(x), f.apply(x): 그 자리에서 f를 실행한다. f.bind(x)는 emitCall이 함수 참조(ref)로 돌려 보낸다.
        if (kind == SiteKind.CALL && expr.is("member_expression")) {
            String prop = Syntax.memberName(expr);
            if ("call".equals(prop) || "apply".equals(prop)) {
                SyntaxNode object = Syntax.unwrap(expr.field("object"));
                if (object != null && ev.eval(object, scope) instanceof Val.Func) {
                    expr = object;
                }
            }
        }
        ev.reset();
        List<String> chain = Syntax.chain(expr);
        if (chain != null && !chain.get(0).equals("this")) {
            int start = 1;
            String root = chain.get(0);
            Sym local = scope.lookup(root);
            if (local == null && chain.size() >= 2 && (root.equals("window") || root.equals("globalThis"))) {
                root = chain.get(1);
                start = 2;
                local = scope.module.top.syms.get(root);
            }
            List<String> rest = chain.subList(start, chain.size());
            if (local != null) {
                Val v = follow(ev.canonical(local), rest);
                Resolution r = toResolution(v, kind, ev.inferred);
                if (!r.targets().isEmpty() || r.missing() != null || local.param) {
                    return r;
                }
                if (local.importRef != null) {
                    // 프로젝트 모듈이 내보낸 이름을 직접 불렀는데 함수인지 알 수 없다(라이브러리 호출 결과 등): 미해결로 남긴다.
                    if (rest.isEmpty() && kind != SiteKind.REF && !local.importRef.name().equals(Sym.NAMESPACE)
                            && !(v instanceof Val.Axios) && !(v instanceof Val.Cls)) {
                        ModuleResolver.Resolution mr = resolver.resolve(local.module, local.importRef.spec());
                        if (mr.kind() == ModuleResolver.Kind.MODULE && !mr.module().skipped) {
                            return new Resolution(List.of(), false, false, false, mr.module().path + "#" + local.importRef.name(),
                                    List.of());
                        }
                    }
                    return r;
                }
                // 이 파일에서는 `var App = App || {}`처럼만 선언하고 멤버는 다른 파일에서 붙인 경우
                if (rest.isEmpty() || !scope.module.classic()) {
                    return r;
                }
            }
            GlobalHits hits = ev.globals(scope.module, root);
            if (hits.syms().isEmpty()) {
                return kind == SiteKind.CALL || kind == SiteKind.NEW ? unverifiedGlobal(scope.module, root, rest, kind)
                        : Resolution.NONE;
            }
            boolean global = !hits.included();
            List<FunctionInfo> targets = new ArrayList<>();
            List<String> via = new ArrayList<>();
            for (int i = 0; i < hits.syms().size(); i++) {
                ev.reset();
                Resolution r = toResolution(follow(ev.canonical(hits.syms().get(i)), rest), kind, false);
                for (FunctionInfo fn : r.targets()) {
                    if (!targets.contains(fn)) {
                        targets.add(fn);
                        via.add(global ? hits.pages().get(i) : null);
                    }
                }
            }
            if (targets.isEmpty()) {
                return Resolution.NONE;
            }
            boolean ambiguous = targets.size() > 1;
            return new Resolution(targets, global || ambiguous, ambiguous, global, null, via);
        }
        Sym s = ev.resolveSym(expr, scope);
        Val v = s != null ? ev.valueOf(s) : ev.eval(expr, scope);
        return toResolution(v, kind, ev.inferred);
    }

    /**
     * 함께 싣는 근거를 찾지 못한 전역 이름(F-010-R34). 프로젝트(벤더 제외)에서 그 이름으로 함수에 닿는 최상위 정의가 정확히 하나이고
     * 그것이 JS 파일에 있으면 그 함수로 잇는다(추론, {@code loadUnverified}). 다른 페이지의 인라인 스크립트에만 있는 정의는
     * 이 스크립트가 실을 길이 없으므로 잇지 않는다. 정의가 여럿이면 고르지 않는다.
     */
    private Resolution unverifiedGlobal(ModuleInfo from, String root, List<String> rest, SiteKind kind) {
        if (!from.classic() || from.vendor) {
            return Resolution.NONE;
        }
        List<FunctionInfo> found = new ArrayList<>(1);
        int definitions = 0;
        for (Sym s : ev.projectGlobals(root)) {
            if (s.module == from) {
                continue;
            }
            ev.reset();
            Resolution r = toResolution(follow(ev.canonical(s), rest), kind, false);
            if (r.targets().isEmpty()) {
                continue;
            }
            definitions++;
            for (FunctionInfo fn : r.targets()) {
                if (!found.contains(fn)) {
                    found.add(fn);
                }
            }
        }
        if (definitions == 0) {
            return Resolution.NONE;
        }
        if (definitions > 1 || found.size() != 1) {
            return Resolution.MANY;
        }
        if (found.get(0).module.embedded) {
            return Resolution.NONE;
        }
        return new Resolution(found, true, false, false, null, List.of(), true, false);
    }

    /** 심벌에서 멤버 이름들을 따라간 끝의 값. */
    private Val follow(Sym start, List<String> names) {
        Sym cur = start;
        for (String name : names) {
            Sym m = ev.memberOfSym(cur, name);
            if (m == null) {
                Val v = ev.valueOf(cur);
                if (v instanceof Val.Namespace n && !n.module().skipped) {
                    return new Val.Missing(n.module().path + "#" + name);
                }
                if (v instanceof Val.Missing) {
                    return v;
                }
                return Val.UNKNOWN;
            }
            cur = ev.canonical(m);
        }
        return ev.valueOf(cur);
    }

    private Resolution toResolution(Val v, SiteKind kind, boolean inferred) {
        // 값을 따라가다 다른 스크립트의 전역을 거쳤으면(var f = otherGlobal; f()) 그 근거 페이지를 함께 남긴다
        String page = inferred ? ev.viaPage : null;
        List<String> pages = page == null ? List.of() : List.of(page);
        if (v instanceof Val.Func f && f.fn() != null) {
            return new Resolution(List.of(f.fn()), inferred, false, page != null, null, pages);
        }
        if (v instanceof Val.Cls c && kind != SiteKind.CALL) {
            FunctionInfo target = c.info().fn;
            if (kind == SiteKind.NEW) {
                Sym ctor = c.info().members.get("constructor");
                if (ctor != null && ev.valueOf(ctor) instanceof Val.Func cf && cf.fn() != null) {
                    target = cf.fn();
                }
            }
            return new Resolution(List.of(target), inferred, false, page != null, null, pages);
        }
        if (v instanceof Val.Missing missing) {
            return new Resolution(List.of(), false, false, false, missing.text(), List.of());
        }
        return Resolution.NONE;
    }
}
