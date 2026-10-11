package io.codeatlas.web.analysis;

import io.codeatlas.web.analysis.ModuleInfo.FnScope;
import io.codeatlas.web.analysis.UrlValue.Part;
import io.codeatlas.web.syntax.SyntaxNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 2단계의 핵심: 식이 가리키는 심벌과 값을 스코프, import, 멤버 체인을 따라가며 정적으로 구한다.
 * 타입 정보는 없다. 알 수 없으면 {@link Val#UNKNOWN}이나 동적 URL 조각으로 답하고 추측하지 않는다.
 */
final class Evaluator {

    /**
     * 전역 이름을 찾은 결과.
     *
     * @param included true면 주인 페이지가 직접 싣는 모듈에서 찾았다. false면 다른 페이지가 두 스크립트를 함께 싣는다는 것이 근거다
     * @param pages included가 false일 때, syms와 같은 순서로 그 심벌의 모듈을 함께 싣는 페이지의 노드 ID
     */
    record GlobalHits(List<Sym> syms, boolean included, List<String> pages) {
        static final GlobalHits NONE = new GlobalHits(List.of(), false, List.of());
    }

    /**
     * 프로젝트 함수로 잇지 않는 전역 이름: 표준 내장 객체, DOM·브라우저 API, 잘 알려진 라이브러리의 전역.
     * 같은 이름을 선언한 스크립트(폴리필, prototype.js의 {@code $})가 함께 실려도 그것을 부른 것으로 보지 않는다.
     */
    static final Set<String> BUILTIN_GLOBALS = Set.of(
            // 표준 내장
            "Object", "Function", "Array", "String", "Number", "Boolean", "Symbol", "BigInt", "Date", "Math", "JSON", "RegExp",
            "Error", "TypeError", "RangeError", "SyntaxError", "ReferenceError", "EvalError", "URIError", "Promise", "Map",
            "Set", "WeakMap", "WeakSet", "Proxy", "Reflect", "Intl", "ArrayBuffer", "DataView", "Int8Array", "Uint8Array",
            "Float32Array", "Float64Array", "Atomics", "globalThis", "undefined", "NaN", "Infinity", "parseInt", "parseFloat",
            "isNaN", "isFinite", "eval", "encodeURI", "decodeURI", "encodeURIComponent", "decodeURIComponent", "escape",
            "unescape",
            // DOM·브라우저
            "window", "document", "navigator", "location", "history", "screen", "console", "self", "top", "parent", "frames",
            "opener", "localStorage", "sessionStorage", "indexedDB", "performance", "crypto", "setTimeout", "setInterval",
            "clearTimeout", "clearInterval", "setImmediate", "requestAnimationFrame", "cancelAnimationFrame",
            "queueMicrotask", "alert", "confirm", "prompt", "open", "close", "print",
            "fetch", "XMLHttpRequest", "ActiveXObject", "FormData", "URL", "URLSearchParams", "Blob", "File", "FileReader",
            "Image", "Audio", "Option", "Event", "CustomEvent", "Node", "Element", "HTMLElement", "NodeList", "DOMParser",
            "MutationObserver", "IntersectionObserver", "ResizeObserver", "WebSocket", "EventSource", "Worker",
            "Notification", "getComputedStyle", "matchMedia", "atob", "btoa", "structuredClone", "AbortController",
            "Headers", "Request", "Response", "TextEncoder", "TextDecoder",
            // 모듈 시스템·잘 알려진 라이브러리
            "require", "define", "module", "exports", "process", "global", "$", "jQuery", "_", "angular", "Vue", "React",
            "ReactDOM", "axios", "moment", "dayjs", "Backbone", "ko", "d3", "echarts", "Chart", "Handlebars", "Mustache",
            "Swal", "swal", "toastr", "layer", "layui", "laydate", "CKEDITOR", "CKFinder", "tinymce", "UE", "KindEditor",
            "WdatePicker", "Prototype", "$$", "$A", "$F", "$H", "$R", "$w", "Ext", "dojo", "YUI", "Modernizr", "Zepto",
            "Raphael", "Highcharts", "SWFUpload", "swfobject", "WebUploader", "Waves", "Clipboard");

    static final String JSP_EXPR = "__JSP_EXPR__";
    private static final Pattern CONTEXT_NAME = Pattern.compile("(?i).*(ctx|context).*");
    private static final Set<String> AXIOS_LIKE_NAMES = Set.of("$http", "$axios", "axios");
    private static final int MAX_DEPTH = 200;
    private static final int MAX_ALTERNATIVES = 8;
    private static final int MAX_GLOBAL_CANDIDATES = 8;

    private final ModuleResolver resolver;
    /** 어느 페이지가 어느 스크립트를 싣는가. 전역 이름을 다른 스크립트에서 찾는 근거. */
    private final PageScripts pages;

    /**
     * 해석 도중 근거가 약한 길(다른 페이지가 함께 싣는 스크립트의 전역, HOC 벗기기, 여러 후보)을 썼는가.
     * 해석을 시작할 때 호출자가 {@link #reset()}으로 지운다.
     */
    boolean inferred;
    /** 해석 도중 "두 스크립트를 함께 싣는 페이지"를 근거로 전역을 찾았으면 그 페이지의 노드 ID. */
    String viaPage;
    private int depth;
    private int epoch;
    private final Map<SyntaxNode, Boolean> returning = new IdentityHashMap<>();
    private final Map<ModuleInfo, Map<String, GlobalHits>> globalCache = new HashMap<>();

    /** 프로젝트의 모든 모듈. 함께 싣는 근거가 없는 전역 이름을 프로젝트 전체에서 셀 때 쓴다. */
    private final java.util.Collection<ModuleInfo> modules;
    private Map<String, List<Sym>> projectGlobals;

    /**
     * 폼 필드의 값을 읽는 식({@code frm.zip_url.value}, {@code $('#id').val()})이 가리키는 URL을 찾아 주는 쪽(F-010-R41).
     * 페이지의 필드 표는 그래프에 있으므로 분석 본체가 준다.
     */
    interface FieldUrls {
        /**
         * @return 그 식이 읽는 필드에 든 URL들. 폼 필드를 읽는 식이 아니거나 표에 없으면 null
         */
        FieldValue lookup(SyntaxNode expression, Scope scope);
    }

    /**
     * @param urls 필드에 든 URL(정규화한 경로, 쿼리 포함)
     * @param certain 주인 페이지의 표에서 폼까지 맞춰 찾았는가. 아니면(필드 이름만으로, 또는 이 스크립트를 싣는 페이지들에서) 추론이다
     */
    record FieldValue(List<String> urls, boolean certain) {
    }

    /** 필드 표를 읽는 쪽. 없으면 폼 필드 식은 종전대로 값을 모르는 식이다. */
    FieldUrls fieldUrls;
    /** 식이 놓인 조각을 찾아 주는 쪽(내장 소스의 치환 목록을 읽는 데 쓴다). */
    java.util.function.Function<SyntaxNode, ModuleInfo.Unit> unitOf;

    Evaluator(ModuleResolver resolver, PageScripts pages, java.util.Collection<ModuleInfo> modules) {
        this.resolver = resolver;
        this.pages = pages;
        this.modules = modules;
    }

    /**
     * 프로젝트 전체에서 이 전역 이름을 최상위에 선언한 심벌들(F-010-R34). 모듈 문법이 없는 스크립트와 인라인 스크립트만 보고
     * 벤더·건너뛴 스크립트, import, 내장·라이브러리 전역 이름은 뺀다. 함께 싣는 근거가 없으므로 유일할 때만 쓰는 약한 근거다.
     */
    List<Sym> projectGlobals(String name) {
        if (BUILTIN_GLOBALS.contains(name)) {
            return List.of();
        }
        if (projectGlobals == null) {
            projectGlobals = new HashMap<>();
            for (ModuleInfo m : modules) {
                if (!m.classic() || m.vendor || m.skipped) {
                    continue;
                }
                for (Map.Entry<String, Sym> e : m.top.syms.entrySet()) {
                    Sym s = e.getValue();
                    if (s.importRef == null && !s.param) {
                        projectGlobals.computeIfAbsent(e.getKey(), k -> new ArrayList<>(1)).add(s);
                    }
                }
            }
        }
        return projectGlobals.getOrDefault(name, List.of());
    }

    /** 새 해석을 시작한다: 추론 표시를 지운다. */
    void reset() {
        inferred = false;
        viaPage = null;
    }

    /** 심벌에 멤버를 더 붙인 뒤 부른다. 그 전에 캐시한 값을 버린다. */
    void invalidate() {
        epoch++;
        globalCache.clear();
        projectGlobals = null;
    }

    // ------------------------------------------------------------------ 전역

    /**
     * 이 스크립트에 없는 전역 이름을 다른 스크립트에서 찾는다(F-010-R18). 근거는 "같은 페이지가 싣는 스크립트"뿐이다:
     * 인라인 스크립트는 주인 페이지가 싣는 모듈에서 먼저 찾고, 없으면(그리고 JS 파일은 처음부터) 이 스크립트와 다른 스크립트를
     * 함께 싣는 페이지가 있는 경우에만 그 스크립트에서 찾는다. 함께 싣는 페이지를 모르면 찾지 않는다.
     * 내장·라이브러리 전역 이름과 벤더 스크립트는 출처도 대상도 아니다.
     */
    GlobalHits globals(ModuleInfo from, String name) {
        if (!from.classic() || from.vendor || BUILTIN_GLOBALS.contains(name)) {
            return GlobalHits.NONE;
        }
        return globalCache.computeIfAbsent(from, k -> new HashMap<>()).computeIfAbsent(name, k -> findGlobals(from, name));
    }

    private GlobalHits findGlobals(ModuleInfo from, String name) {
        List<Sym> hits = new ArrayList<>();
        for (ModuleInfo m : pages.included(from)) {
            addGlobal(m, from, name, hits);
        }
        if (!hits.isEmpty()) {
            return new GlobalHits(hits, true, List.of());
        }
        List<String> via = new ArrayList<>();
        for (Map.Entry<ModuleInfo, String> e : pages.coLoaded(from).entrySet()) {
            int before = hits.size();
            addGlobal(e.getKey(), from, name, hits);
            if (hits.size() > before) {
                via.add(e.getValue());
                if (hits.size() > MAX_GLOBAL_CANDIDATES) {
                    return GlobalHits.NONE;
                }
            }
        }
        return hits.isEmpty() ? GlobalHits.NONE : new GlobalHits(hits, false, via);
    }

    private static void addGlobal(ModuleInfo m, ModuleInfo from, String name, List<Sym> hits) {
        if (m == from || m.vendor) {
            return;
        }
        Sym s = m.top.syms.get(name);
        if (s != null && s.importRef == null && !s.param && !hits.contains(s)) {
            hits.add(s);
        }
    }

    /** 다른 스크립트의 전역 중 이름이 하나뿐이면 그것. */
    private Sym uniqueGlobal(ModuleInfo from, String name) {
        GlobalHits hits = globals(from, name);
        if (hits.syms().size() != 1) {
            return null;
        }
        if (!hits.included()) {
            inferred = true;
            viaPage = hits.pages().get(0);
        }
        return hits.syms().get(0);
    }

    // ------------------------------------------------------------------ 심벌

    /** 식(식별자, 멤버 체인, this)이 가리키는 심벌. import와 별칭을 따라간 끝이다. 모르면 null. */
    Sym resolveSym(SyntaxNode expression, Scope scope) {
        SyntaxNode expr = Syntax.unwrap(expression);
        if (expr == null || depth > MAX_DEPTH) {
            return null;
        }
        depth++;
        try {
            switch (expr.type()) {
                case "identifier", "shorthand_property_identifier", "type_identifier" -> {
                    Sym s = scope.lookup(expr.text());
                    if (s == null) {
                        s = uniqueGlobal(scope.module, expr.text());
                    }
                    return s == null ? null : canonical(s);
                }
                case "this" -> {
                    return scope.lookupThis();
                }
                case "member_expression", "subscript_expression" -> {
                    String name = Syntax.memberName(expr);
                    SyntaxNode object = Syntax.unwrap(expr.field("object"));
                    if (name == null || object == null) {
                        return null;
                    }
                    if (object.is("identifier") && (object.text().equals("window") || object.text().equals("globalThis"))
                            && scope.lookup(object.text()) == null) {
                        Sym s = scope.module.top.syms.get(name);
                        if (s == null) {
                            s = uniqueGlobal(scope.module, name);
                        }
                        return s == null ? null : canonical(s);
                    }
                    Sym o = resolveSym(object, scope);
                    Sym m = o != null ? memberOfSym(o, name) : memberOfVal(eval(object, scope), name);
                    return m == null ? null : canonical(m);
                }
                default -> {
                    return null;
                }
            }
        } finally {
            depth--;
        }
    }

    /** import와 별칭(다른 이름을 그대로 가리키는 변수), 구조 분해를 따라간 끝의 심벌. */
    Sym canonical(Sym start) {
        Sym s = start;
        for (int i = 0; i < 20; i++) {
            Sym next = step(s);
            if (next == null || next == s) {
                return s;
            }
            s = next;
        }
        return s;
    }

    private Sym step(Sym s) {
        if (s.stepping) {
            return null;
        }
        s.stepping = true;
        try {
            if (s.importRef != null) {
                ModuleInfo m = resolver.module(s.module, s.importRef.spec());
                if (m == null) {
                    return null;
                }
                String name = s.importRef.name();
                if (name.equals(Sym.NAMESPACE)) {
                    return null;
                }
                if (name.equals(Sym.CJS)) {
                    return m.cjsExports;
                }
                return exportOf(m, name, 0);
            }
            if (s.preset != null || s.param || s.multiAssigned() || s.init == null) {
                return null;
            }
            SyntaxNode init = Syntax.unwrap(s.init);
            if (init == null) {
                return null;
            }
            if (s.destruct.isEmpty()) {
                if (init.isAny("identifier", "shorthand_property_identifier", "member_expression", "subscript_expression", "this")) {
                    return resolveSym(init, s.initScope);
                }
                return null;
            }
            Sym cur = resolveSym(init, s.initScope);
            Val v = cur == null ? eval(init, s.initScope) : null;
            for (String key : s.destruct) {
                Sym m = cur != null ? memberOfSym(cur, key) : memberOfVal(v, key);
                if (m == null) {
                    return null;
                }
                cur = canonical(m);
                v = null;
            }
            return cur;
        } finally {
            s.stepping = false;
        }
    }

    /** 모듈이 내보내는 이름의 심벌. re-export를 따라간다. 없으면 null. */
    Sym exportOf(ModuleInfo m, String name, int level) {
        Sym s = m.exports.get(name);
        if (s != null) {
            return s;
        }
        if (m.cjsExports != null) {
            if (valueOf(m.cjsExports) instanceof Val.Obj o) {
                Sym p = o.props().get(name);
                if (p != null) {
                    return p;
                }
            }
            if (name.equals(Sym.DEFAULT)) {
                return m.cjsExports;
            }
        }
        if (level < 5 && !name.equals(Sym.DEFAULT)) {
            for (String spec : m.starReexports) {
                ModuleInfo t = resolver.module(m, spec);
                if (t != null && t != m) {
                    s = exportOf(t, name, level + 1);
                    if (s != null) {
                        return s;
                    }
                }
            }
        }
        return null;
    }

    Sym memberOfSym(Sym s, String name) {
        Val v = valueOf(s);
        if (v instanceof Val.Cls c) {
            // 클래스가 선언한 멤버가 나중에 붙인 것(this.m = this.m.bind(this))보다 먼저다.
            Sym declared = c.info().members.get(name);
            if (declared != null) {
                return declared;
            }
        }
        Sym m = s.member(name);
        return m != null ? m : memberOfVal(v, name);
    }

    Sym memberOfVal(Val v, String name) {
        if (v instanceof Val.Obj o) {
            return o.props().get(name);
        }
        if (v instanceof Val.Cls c) {
            return classMember(c.info(), name, 0);
        }
        if (v instanceof Val.Instance i) {
            return memberOfSym(i.classSym(), name);
        }
        if (v instanceof Val.Namespace n) {
            return exportOf(n.module(), name, 0);
        }
        return null;
    }

    private Sym classMember(Val.ClassInfo info, String name, int level) {
        Sym m = info.members.get(name);
        if (m == null && info.sym != null) {
            m = info.sym.member(name);
        }
        if (m != null || info.superExpr == null || level > 8) {
            return m;
        }
        Sym sup = resolveSym(info.superExpr, info.scope);
        if (sup != null && valueOf(sup) instanceof Val.Cls c && c.info() != info) {
            return classMember(c.info(), name, level + 1);
        }
        return null;
    }

    // ------------------------------------------------------------------ 값

    Val valueOf(Sym s) {
        if (s.cached != null && s.cacheEpoch == epoch) {
            return s.cached;
        }
        if (s.evaluating || depth > MAX_DEPTH) {
            return Val.UNKNOWN;
        }
        s.evaluating = true;
        depth++;
        try {
            Val v = compute(s);
            s.cached = v;
            s.cacheEpoch = epoch;
            return v;
        } finally {
            depth--;
            s.evaluating = false;
        }
    }

    private Val compute(Sym s) {
        if (s.preset != null) {
            return s.preset;
        }
        if (s.importRef != null) {
            String spec = s.importRef.spec();
            String name = s.importRef.name();
            ModuleResolver.Resolution r = resolver.resolve(s.module, spec);
            if (r.kind() == ModuleResolver.Kind.UNRESOLVED) {
                return new Val.Missing(r.text() + "#" + name);
            }
            if (r.kind() != ModuleResolver.Kind.MODULE) {
                return packageValue(spec, name);
            }
            ModuleInfo m = r.module();
            if (name.equals(Sym.NAMESPACE)) {
                return new Val.Namespace(m);
            }
            if (name.equals(Sym.CJS)) {
                return moduleValue(m);
            }
            Sym target = canonical(s);
            if (target != null && target != s) {
                return valueOf(target);
            }
            if (m.skipped) {
                return Val.UNKNOWN;
            }
            return new Val.Missing(m.path + "#" + name);
        }
        if (s.param || s.state) {
            return Val.UNKNOWN;
        }
        Sym c = canonical(s);
        if (c != null && c != s) {
            return valueOf(c);
        }
        if (s.init == null || s.multiAssigned() || !s.destruct.isEmpty()) {
            return Val.UNKNOWN;
        }
        return eval(s.init, s.initScope);
    }

    private static Val packageValue(String spec, String name) {
        if (spec.equals("axios") && (name.equals(Sym.DEFAULT) || name.equals(Sym.NAMESPACE) || name.equals(Sym.CJS) || name.equals("axios"))) {
            return new Val.Axios(List.of());
        }
        if (spec.equals("jquery")) {
            return new Val.JQuery();
        }
        return new Val.Package(spec, name);
    }

    /** {@code require('./m')}의 값. */
    private Val moduleValue(ModuleInfo m) {
        if (m.cjsExports != null) {
            Val v = valueOf(m.cjsExports);
            if (!(v instanceof Val.Obj) && !(v instanceof Val.Unknown)) {
                return v;
            }
        }
        return new Val.Namespace(m);
    }

    Val eval(SyntaxNode expression, Scope scope) {
        SyntaxNode expr = Syntax.unwrap(expression);
        if (expr == null || depth > MAX_DEPTH) {
            return Val.UNKNOWN;
        }
        depth++;
        try {
            switch (expr.type()) {
                case "identifier", "shorthand_property_identifier", "type_identifier", "this", "member_expression",
                        "subscript_expression" -> {
                    Sym s = resolveSym(expr, scope);
                    if (s != null) {
                        return valueOf(s);
                    }
                    if (Syntax.isIdentifier(expr)) {
                        return scope.lookup(expr.text()) == null ? specialGlobal(expr.text()) : Val.UNKNOWN;
                    }
                    if (expr.is("member_expression")) {
                        Val ov = eval(expr.field("object"), scope);
                        String name = Syntax.memberName(expr);
                        if (ov instanceof Val.Namespace n && !n.module().skipped) {
                            return new Val.Missing(n.module().path + "#" + name);
                        }
                        if (ov instanceof Val.Missing missing) {
                            return missing;
                        }
                        if (ov instanceof Val.Unknown && name != null && AXIOS_LIKE_NAMES.contains(name)) {
                            // this.$http, this.$axios, Vue.axios
                            return new Val.Axios(List.of());
                        }
                    }
                    return Val.UNKNOWN;
                }
                case "object" -> {
                    Val v = scope.module.literalVals.get(expr);
                    return v != null ? v : lazyObject(expr, scope);
                }
                case "function_expression", "arrow_function", "generator_function", "function_declaration",
                        "generator_function_declaration", "method_definition", "function" -> {
                    return new Val.Func(scope.module.functionByNode.get(expr), expr, scope.module);
                }
                case "class", "class_declaration", "abstract_class_declaration" -> {
                    Val v = scope.module.literalVals.get(expr);
                    return v != null ? v : Val.UNKNOWN;
                }
                case "new_expression" -> {
                    SyntaxNode ctor = Syntax.unwrap(expr.field("constructor"));
                    if (ctor == null) {
                        return Val.UNKNOWN;
                    }
                    if (ctor.is("identifier") && ctor.text().equals("XMLHttpRequest") && scope.lookup("XMLHttpRequest") == null) {
                        return new Val.Xhr();
                    }
                    Sym cs = resolveSym(ctor, scope);
                    if (cs != null) {
                        Val cv = valueOf(cs);
                        if (cv instanceof Val.Cls || cv instanceof Val.Func) {
                            return new Val.Instance(cs);
                        }
                    }
                    return Val.UNKNOWN;
                }
                case "call_expression" -> {
                    return evalCall(expr, scope);
                }
                case "await_expression" -> {
                    return eval(Syntax.firstNamed(expr), scope);
                }
                default -> {
                    return Val.UNKNOWN;
                }
            }
        } finally {
            depth--;
        }
    }

    /** 수집 단계가 등록하지 않은 객체 리터럴(예: tsconfig 밖의 특수한 자리)을 그 자리에서 값으로 만든다. */
    private Val lazyObject(SyntaxNode object, Scope scope) {
        Map<String, Sym> props = new LinkedHashMap<>();
        for (SyntaxNode member : object.namedChildren()) {
            if (member.is("pair")) {
                String key = Syntax.propertyName(member.field("key"));
                if (key != null && member.field("value") != null) {
                    Sym p = new Sym(key, scope.module);
                    p.init = member.field("value");
                    p.initScope = scope;
                    props.putIfAbsent(key, p);
                }
            } else if (member.is("shorthand_property_identifier")) {
                Sym p = new Sym(member.text(), scope.module);
                p.init = member;
                p.initScope = scope;
                props.putIfAbsent(member.text(), p);
            }
        }
        Val v = new Val.Obj(props);
        scope.module.literalVals.put(object, v);
        return v;
    }

    private static Val specialGlobal(String name) {
        return switch (name) {
            case "axios" -> new Val.Axios(List.of());
            case "$", "jQuery" -> new Val.JQuery();
            default -> Val.UNKNOWN;
        };
    }

    private Val evalCall(SyntaxNode call, Scope scope) {
        SyntaxNode callee = Syntax.callee(call);
        List<SyntaxNode> args = Syntax.arguments(call);
        if (callee == null) {
            return Val.UNKNOWN;
        }
        // import('./m')
        if (callee.is("import")) {
            ModuleInfo m = stringArgModule(args, scope);
            return m == null ? Val.UNKNOWN : new Val.Namespace(m);
        }
        // require('./m')
        if (callee.is("identifier") && callee.text().equals("require") && scope.lookup("require") == null
                && args.size() == 1 && Syntax.unwrap(args.get(0)).is("string")) {
            String spec = Syntax.stringContent(Syntax.unwrap(args.get(0)));
            ModuleResolver.Resolution r = resolver.resolve(scope.module, spec);
            return switch (r.kind()) {
                case MODULE -> moduleValue(r.module());
                case UNRESOLVED -> new Val.Missing(r.text() + "#" + Sym.CJS);
                default -> packageValue(spec, Sym.CJS);
            };
        }
        // IIFE
        if (Syntax.isFunction(callee)) {
            return returnValue(callee, scope.module);
        }
        String calleeText = callee.text();
        // useCallback(fn), memo(fn), forwardRef(fn): 감싼 함수 그대로
        if (Syntax.FUNCTION_WRAPPERS.contains(calleeText) && !args.isEmpty() && Syntax.isFunction(Syntax.unwrap(args.get(0)))) {
            return eval(args.get(0), scope);
        }
        // React.lazy(() => import('./X')), loadable(...)
        if ((calleeText.equals("lazy") || calleeText.equals("React.lazy") || calleeText.equals("loadable")) && !args.isEmpty()) {
            SyntaxNode imported = findImportCall(args.get(0));
            if (imported != null) {
                ModuleInfo m = stringArgModule(Syntax.arguments(imported), scope);
                if (m != null) {
                    Sym d = exportOf(m, Sym.DEFAULT, 0);
                    return d == null ? new Val.Missing(m.path + "#default") : valueOf(canonical(d));
                }
            }
            return Val.UNKNOWN;
        }
        // f.bind(x): 함수 그대로
        if (callee.is("member_expression") && "bind".equals(Syntax.memberName(callee))) {
            Val bound = eval(callee.field("object"), scope);
            if (bound instanceof Val.Func) {
                return bound;
            }
        }
        // axios.create({ baseURL })
        if (callee.is("member_expression") && "create".equals(Syntax.memberName(callee))) {
            Val ov = eval(callee.field("object"), scope);
            if (ov instanceof Val.Axios parent) {
                List<UrlValue> base = parent.base();
                if (!args.isEmpty()) {
                    Expr baseExpr = properties(args.get(0), scope).get("baseURL");
                    if (baseExpr != null) {
                        List<UrlValue> own = evalString(baseExpr.node(), baseExpr.scope());
                        base = base.isEmpty() ? own : product(base, own, true);
                    }
                }
                return new Val.Axios(base);
            }
        }
        Val cv = eval(callee, scope);
        if (cv instanceof Val.Func f) {
            return returnValue(f.node(), f.module());
        }
        if (cv instanceof Val.Unknown || cv instanceof Val.Package) {
            // HOC: memo(X), connect(a, b)(X), withRouter(X) — 인자 가운데 컴포넌트가 하나뿐이면 그것으로 본다(추론).
            Val only = null;
            int count = 0;
            for (SyntaxNode arg : args) {
                SyntaxNode a = Syntax.unwrap(arg);
                if (a == null || !(Syntax.isIdentifier(a) || a.is("member_expression"))) {
                    continue;
                }
                Val av = eval(a, scope);
                boolean component = av instanceof Val.Cls
                        || (av instanceof Val.Func af && af.fn() != null && Character.isUpperCase(af.fn().shortName().charAt(0)));
                if (component) {
                    only = av;
                    count++;
                }
            }
            if (count == 1) {
                inferred = true;
                return only;
            }
        }
        return Val.UNKNOWN;
    }

    private ModuleInfo stringArgModule(List<SyntaxNode> args, Scope scope) {
        if (args.isEmpty()) {
            return null;
        }
        SyntaxNode a = Syntax.unwrap(args.get(0));
        return a != null && a.is("string") ? resolver.module(scope.module, Syntax.stringContent(a)) : null;
    }

    private static SyntaxNode findImportCall(SyntaxNode root) {
        Deque<SyntaxNode> queue = new ArrayDeque<>();
        queue.add(root);
        int budget = 200;
        while (!queue.isEmpty() && budget-- > 0) {
            SyntaxNode n = queue.poll();
            if (n.is("call_expression") && n.field("function") != null && n.field("function").is("import")) {
                return n;
            }
            queue.addAll(n.namedChildren());
        }
        return null;
    }

    /** 함수가 값 있는 return을 하나만 가지면 그 식의 값. */
    private Val returnValue(SyntaxNode fnNode, ModuleInfo module) {
        FnScope fs = module.fnScopes.get(fnNode);
        if (fs == null || returning.containsKey(fnNode)) {
            return Val.UNKNOWN;
        }
        Sym.Assign only = null;
        for (Sym.Assign r : fs.returns) {
            SyntaxNode v = Syntax.unwrap(r.value());
            if (v == null || v.isAny("null", "undefined", "true", "false", "number")) {
                continue;
            }
            if (only != null) {
                return Val.UNKNOWN;
            }
            only = r;
        }
        if (only == null) {
            return Val.UNKNOWN;
        }
        returning.put(fnNode, Boolean.TRUE);
        try {
            return eval(only.value(), only.scope());
        } finally {
            returning.remove(fnNode);
        }
    }

    /** 식과 그 식을 평가할 스코프. */
    record Expr(SyntaxNode node, Scope scope) {
    }

    /** 옵션 객체의 프로퍼티들. 객체 리터럴이거나 객체 상수를 가리키는 이름일 때만. 모르면 빈 맵. */
    Map<String, Expr> properties(SyntaxNode expression, Scope scope) {
        Map<String, Expr> out = new LinkedHashMap<>();
        SyntaxNode expr = Syntax.unwrap(expression);
        if (expr == null) {
            return out;
        }
        if (expr.is("object")) {
            for (SyntaxNode member : expr.namedChildren()) {
                if (member.is("pair")) {
                    String key = Syntax.propertyName(member.field("key"));
                    if (key != null && member.field("value") != null) {
                        out.putIfAbsent(key, new Expr(member.field("value"), scope));
                    }
                } else if (member.is("shorthand_property_identifier")) {
                    out.putIfAbsent(member.text(), new Expr(member, scope));
                }
            }
            return out;
        }
        if (eval(expr, scope) instanceof Val.Obj o) {
            putProps(o, out);
        }
        // var opts = {}; opts.url = '/x'; opts.type = 'POST': 나중에 붙인 멤버
        Sym s = expr.isAny("identifier", "member_expression") ? resolveSym(expr, scope) : null;
        if (s != null && s.members != null && !s.param) {
            for (Map.Entry<String, Sym> e : s.members.entrySet()) {
                Sym p = e.getValue();
                if (p.init != null) {
                    out.put(e.getKey(), new Expr(p.init, p.initScope));
                }
            }
        }
        return out;
    }

    private static void putProps(Val.Obj o, Map<String, Expr> out) {
        for (Map.Entry<String, Sym> e : o.props().entrySet()) {
            Sym p = e.getValue();
            if (p.init != null) {
                out.put(e.getKey(), new Expr(p.init, p.initScope));
            }
        }
    }

    /**
     * 변수가 가리킬 수 있는 객체들의 프로퍼티. 여러 번 대입한 변수와 React 상태는 대입한(또는 setter에 넘긴) 객체마다 하나다.
     * 빈 객체 리터럴({@code let o = {}})은 자리만 잡은 것이라 뺀다. 객체가 아닌 값이 섞인 후보는 건너뛴다(흐름을 보지 않으므로
     * 이 결과를 쓴 관계는 추론이다).
     */
    List<Map<String, Expr>> objectCandidates(Sym s, SyntaxNode use) {
        List<Map<String, Expr>> out = new ArrayList<>();
        if (s == null || s.param || s.importRef != null || depth > MAX_DEPTH) {
            return out;
        }
        List<Sym.Assign> candidates = new ArrayList<>();
        if (s.init != null && s.destruct.isEmpty()) {
            candidates.add(new Sym.Assign(s.init, s.initScope));
        }
        candidates.addAll(s.assigns);
        candidates = reachable(candidates, use);
        depth++;
        try {
            for (Sym.Assign c : candidates) {
                if (out.size() >= MAX_ALTERNATIVES) {
                    break;
                }
                SyntaxNode value = Syntax.unwrap(c.value());
                if (value == null) {
                    continue;
                }
                Val v = Syntax.isFunction(value) ? returnValue(value, c.scope().module) : eval(value, c.scope());
                if (v instanceof Val.Obj o && !o.props().isEmpty()) {
                    Map<String, Expr> props = new LinkedHashMap<>();
                    putProps(o, props);
                    out.add(props);
                }
            }
        } finally {
            depth--;
        }
        return out;
    }

    /** 여러 값이 대입되는 객체 변수(여러 번 대입한 변수, React 상태)인가. 이런 변수의 프로퍼티는 후보로만 읽는다. */
    static boolean hasObjectCandidates(Sym s) {
        return s != null && !s.param && s.importRef == null && (s.state || s.multiAssigned());
    }

    /**
     * 옵션 객체 식에서 HTTP 메서드를 읽는다(F-010-R20). 객체 리터럴·객체 상수면 그 프로퍼티, 여러 번 대입한 변수면 후보 객체마다 읽는다.
     *
     * @param keys 메서드를 담는 프로퍼티 이름(앞의 것이 먼저다)
     * @return 대문자 메서드 이름들. 후보 가운데 하나라도 메서드를 정적으로 알 수 없으면 null, 후보 객체에 메서드 프로퍼티가
     *     아예 없으면 빈 목록(API의 기본 메서드를 쓴다)
     */
    List<String> optionMethods(SyntaxNode options, Scope scope, List<String> keys) {
        SyntaxNode expr = Syntax.unwrap(options);
        if (expr == null) {
            return null;
        }
        List<Map<String, Expr>> candidates;
        if (isObjectLike(expr, scope)) {
            candidates = List.of(properties(expr, scope));
        } else {
            Sym s = expr.isAny("identifier", "member_expression") ? resolveSym(expr, scope) : null;
            if (!hasObjectCandidates(s)) {
                return null;
            }
            candidates = objectCandidates(s, expr);
            if (candidates.isEmpty()) {
                return null;
            }
            inferred = true;
        }
        List<String> out = new ArrayList<>();
        for (Map<String, Expr> props : candidates) {
            Expr method = null;
            for (String key : keys) {
                method = props.get(key);
                if (method != null) {
                    break;
                }
            }
            if (method == null) {
                continue;
            }
            for (UrlValue v : evalString(method.node(), method.scope())) {
                String literal = v.literalOrNull();
                if (literal == null || !literal.matches("[A-Za-z]+")) {
                    return null;
                }
                String upper = literal.toUpperCase(java.util.Locale.ROOT);
                if (!out.contains(upper)) {
                    out.add(upper);
                }
            }
        }
        return out;
    }

    /**
     * 옵션 객체 식에서 메서드를 담은 프로퍼티의 값 식들. 객체 리터럴·객체 상수면 하나, 여러 번 대입한 변수면 후보 객체마다 하나다
     * (메서드 프로퍼티가 없는 후보는 건너뛴다). 메서드와 URL이 같은 객체에서 오는지 가리는 데 쓴다(F-010-R27).
     */
    List<Expr> optionMethodExprs(SyntaxNode options, Scope scope, List<String> keys) {
        SyntaxNode expr = Syntax.unwrap(options);
        if (expr == null) {
            return List.of();
        }
        List<Map<String, Expr>> candidates;
        if (isObjectLike(expr, scope)) {
            candidates = List.of(properties(expr, scope));
        } else {
            Sym s = expr.isAny("identifier", "member_expression") ? resolveSym(expr, scope) : null;
            if (!hasObjectCandidates(s)) {
                return List.of();
            }
            candidates = objectCandidates(s, expr);
        }
        List<Expr> out = new ArrayList<>();
        for (Map<String, Expr> props : candidates) {
            for (String key : keys) {
                Expr method = props.get(key);
                if (method != null) {
                    out.add(method);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * {@code obj.prop} 꼴의 식에서, obj가 여러 값이 대입되는 객체 변수(여러 번 대입한 변수, React 상태)일 때 그 변수와 프로퍼티 이름.
     *
     * @param object 객체 변수의 심벌
     * @param prop 프로퍼티 이름
     * @param use 그 객체를 쓴 자리(도달할 수 없는 가지의 대입을 후보에서 빼는 데 쓴다)
     */
    record CandidateMember(Sym object, String prop, SyntaxNode use) {
    }

    /** 식이 {@link CandidateMember} 꼴이면 그것, 아니면(객체 상수의 프로퍼티, 그 밖의 식) null. */
    CandidateMember candidateMember(SyntaxNode expression, Scope scope) {
        SyntaxNode expr = Syntax.unwrap(expression);
        if (expr == null || !expr.isAny("member_expression", "subscript_expression") || depth > MAX_DEPTH) {
            return null;
        }
        String name = Syntax.memberName(expr);
        SyntaxNode object = Syntax.unwrap(expr.field("object"));
        if (name == null || object == null || !object.isAny("identifier", "member_expression") || resolveSym(expr, scope) != null) {
            return null;
        }
        Sym o = resolveSym(object, scope);
        return hasObjectCandidates(o) ? new CandidateMember(o, name, object) : null;
    }

    /**
     * 후보 객체 하나에서 읽은 URL과 메서드.
     *
     * @param urls 그 객체의 URL 프로퍼티 값 후보(불확실 표시가 붙어 있다)
     * @param methods 그 객체의 메서드 프로퍼티 값 후보. 그 객체에 메서드 프로퍼티가 없으면 null
     */
    record PairedCandidate(List<UrlValue> urls, List<UrlValue> methods) {
    }

    /**
     * URL 식이 이 객체 변수의 프로퍼티를 (상수, 삼항, 템플릿, {@code +} 연결을 따라가) 읽으면 그 객체를 쓴 자리. 읽지 않으면 null.
     * {@code const editURL = c ? `${modeInfo.editURL}/${id}` : modeInfo.editURL}처럼 프로퍼티에서 파생된 URL도 그 객체의
     * 메서드와 짝지을 수 있게 한다(F-010-R39).
     */
    SyntaxNode referenceTo(Sym target, SyntaxNode expression, Scope scope, int level) {
        SyntaxNode expr = Syntax.unwrap(expression);
        if (expr == null || target == null || level > 6) {
            return null;
        }
        switch (expr.type()) {
            case "member_expression", "subscript_expression" -> {
                CandidateMember m = candidateMember(expr, scope);
                return m != null && m.object() == target ? m.use() : null;
            }
            case "identifier" -> {
                Sym s = scope.lookup(expr.text());
                if (s == null || s.param || s.state || s.importRef != null) {
                    return null;
                }
                if (s.init != null && s.destruct.isEmpty()) {
                    SyntaxNode found = referenceTo(target, s.init, s.initScope, level + 1);
                    if (found != null) {
                        return found;
                    }
                }
                for (Sym.Assign a : s.assigns) {
                    SyntaxNode found = referenceTo(target, a.value(), a.scope(), level + 1);
                    if (found != null) {
                        return found;
                    }
                }
                return null;
            }
            case "ternary_expression" -> {
                SyntaxNode found = referenceTo(target, expr.field("consequence"), scope, level + 1);
                return found != null ? found : referenceTo(target, expr.field("alternative"), scope, level + 1);
            }
            case "binary_expression" -> {
                SyntaxNode found = referenceTo(target, expr.field("left"), scope, level + 1);
                return found != null ? found : referenceTo(target, expr.field("right"), scope, level + 1);
            }
            case "template_string" -> {
                for (SyntaxNode c : expr.namedChildren()) {
                    if (c.is("template_substitution")) {
                        SyntaxNode found = referenceTo(target, Syntax.firstNamed(c), scope, level + 1);
                        if (found != null) {
                            return found;
                        }
                    }
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /** 평가하는 동안 값을 후보 객체 하나로 고정한 객체 변수와 그 객체의 프로퍼티. 고정하지 않았으면 null. */
    private Sym pinnedObject;
    private Map<String, Expr> pinnedProps;
    /** 고정한 객체에 없는 프로퍼티를 읽었는가. */
    private boolean pinnedMiss;

    /**
     * 객체 변수에 대입된 객체 리터럴마다, 그 변수를 그 객체로 고정하고 URL 식을 평가해 메서드 프로퍼티와 함께 읽는다
     * (F-010-R27, R39). URL 식은 그 객체의 프로퍼티 자체({@code modeInfo.editURL})이거나 거기서 파생된 식이다. URL 식이 읽는
     * 프로퍼티가 없는 후보는 건너뛴다. 어느 객체가 실제 값인지는 흐름을 봐야 알 수 있으므로 결과를 쓴 관계는 추론이다.
     *
     * @param use 그 객체를 쓴 자리(도달할 수 없는 가지의 대입을 후보에서 빼는 데 쓴다)
     */
    List<PairedCandidate> pairedCandidates(Expr url, Sym object, SyntaxNode use, String methodProp) {
        if (object.stringEvaluating || pinnedObject != null) {
            return List.of();
        }
        List<PairedCandidate> out = new ArrayList<>();
        for (Map<String, Expr> props : objectCandidates(object, use)) {
            pinnedObject = object;
            pinnedProps = props;
            pinnedMiss = false;
            List<UrlValue> urls = new ArrayList<>();
            try {
                for (UrlValue v : evalString(url.node(), url.scope())) {
                    add(urls, v.asUncertain());
                }
            } finally {
                pinnedObject = null;
                pinnedProps = null;
            }
            if (pinnedMiss) {
                continue;
            }
            Expr method = props.get(methodProp);
            out.add(new PairedCandidate(urls, method == null ? null : evalString(method.node(), method.scope())));
        }
        if (!out.isEmpty()) {
            inferred = true;
        }
        return out;
    }

    /** 옵션 객체로 읽을 수 있는 식인가(객체 리터럴이거나 객체 상수). */
    boolean isObjectLike(SyntaxNode expression, Scope scope) {
        SyntaxNode expr = Syntax.unwrap(expression);
        return expr != null && (expr.is("object") || eval(expr, scope) instanceof Val.Obj);
    }

    // ------------------------------------------------------------------ 문자열(URL)

    /** URL 식을 평가한다. 갈래(삼항, 여러 번 대입)가 있으면 후보가 여러 개다. 비어 있지 않다. */
    List<UrlValue> evalString(SyntaxNode expression, Scope scope) {
        SyntaxNode expr = Syntax.unwrap(expression);
        if (expr == null || depth > MAX_DEPTH) {
            return List.of(UrlValue.DYNAMIC);
        }
        depth++;
        try {
            switch (expr.type()) {
                case "string" -> {
                    String content = Syntax.stringContent(expr);
                    return scope.module.embedded ? embeddedLiterals(expr, content) : List.of(literal(jspMarkup(content)));
                }
                case "number" -> {
                    return List.of(UrlValue.literal(expr.text()));
                }
                case "template_string" -> {
                    List<UrlValue> acc = List.of(UrlValue.EMPTY);
                    for (SyntaxNode c : expr.namedChildren()) {
                        if (c.is("string_fragment")) {
                            acc = product(acc, scope.module.embedded ? embeddedLiterals(c, c.text()) : List.of(literal(c.text())), false);
                        } else if (c.is("escape_sequence")) {
                            acc = product(acc, List.of(UrlValue.literal(Syntax.unescape(c.text()))), false);
                        } else if (c.is("template_substitution")) {
                            acc = product(acc, evalString(Syntax.firstNamed(c), scope), false);
                        }
                    }
                    return acc;
                }
                case "binary_expression" -> {
                    String op = Syntax.operator(expr);
                    if (op.equals("+")) {
                        return product(evalString(expr.field("left"), scope), evalString(expr.field("right"), scope), false);
                    }
                    if (op.equals("||") || op.equals("??")) {
                        return union(evalString(expr.field("left"), scope), evalString(expr.field("right"), scope));
                    }
                    return List.of(UrlValue.DYNAMIC);
                }
                case "ternary_expression" -> {
                    return union(evalString(expr.field("consequence"), scope), evalString(expr.field("alternative"), scope));
                }
                case "await_expression" -> {
                    return evalString(Syntax.firstNamed(expr), scope);
                }
                case "identifier", "shorthand_property_identifier" -> {
                    String name = expr.text();
                    if (name.equals(JSP_EXPR)) {
                        return List.of(UrlValue.CONTEXT);
                    }
                    Sym s = resolveSym(expr, scope);
                    return s == null ? List.of(unknown(name)) : narrowByConditions(symString(s, name, expr), expr, s, scope);
                }
                case "member_expression", "subscript_expression" -> {
                    String name = Syntax.memberName(expr);
                    Sym s = resolveSym(expr, scope);
                    if (s == null) {
                        List<UrlValue> candidates = memberCandidates(expr, name, scope);
                        if (candidates.isEmpty()) {
                            candidates = fieldValues(expr, scope);
                        }
                        return candidates.isEmpty() ? List.of(unknown(name)) : candidates;
                    }
                    return symString(s, name, expr);
                }
                case "call_expression" -> {
                    SyntaxNode callee = Syntax.callee(expr);
                    List<SyntaxNode> args = Syntax.arguments(expr);
                    if (callee == null) {
                        return List.of(UrlValue.DYNAMIC);
                    }
                    if (callee.is("identifier") && (callee.text().equals("encodeURI") || callee.text().equals("String"))
                            && args.size() == 1) {
                        return evalString(args.get(0), scope);
                    }
                    if (callee.is("member_expression") && "concat".equals(Syntax.memberName(callee))) {
                        List<UrlValue> acc = evalString(callee.field("object"), scope);
                        for (SyntaxNode a : args) {
                            acc = product(acc, evalString(a, scope), false);
                        }
                        return acc;
                    }
                    if (args.isEmpty() && callee.is("member_expression") && "val".equals(Syntax.memberName(callee))) {
                        // $('#zipUrl').val(): hidden 필드에 든 URL
                        List<UrlValue> field = fieldValues(expr, scope);
                        if (!field.isEmpty()) {
                            return field;
                        }
                    }
                    String name = callee.is("member_expression") ? Syntax.memberName(callee) : callee.text();
                    return List.of(unknown(name));
                }
                default -> {
                    return List.of(UrlValue.DYNAMIC);
                }
            }
        } finally {
            depth--;
        }
    }

    /**
     * {@code obj.prop}에서 obj가 여러 값이 대입되는 객체 변수(여러 번 대입, React 상태)일 때: 후보 객체마다 그 프로퍼티의 값(F-010-R21).
     * 후보가 없으면 빈 목록. 결과는 흐름을 보지 않은 후보라 불확실 표시가 붙는다.
     */
    private List<UrlValue> memberCandidates(SyntaxNode member, String name, Scope scope) {
        SyntaxNode object = Syntax.unwrap(member.field("object"));
        if (name == null || object == null || !object.isAny("identifier", "member_expression")) {
            return List.of();
        }
        Sym o = resolveSym(object, scope);
        if (!hasObjectCandidates(o) || o.stringEvaluating) {
            return List.of();
        }
        o.stringEvaluating = true;
        try {
            List<UrlValue> out = new ArrayList<>();
            if (o == pinnedObject) {
                // 이 객체 변수의 값을 후보 객체 하나로 고정하고 평가하는 중이다: 그 객체의 프로퍼티만 읽는다
                Expr value = pinnedProps.get(name);
                if (value == null) {
                    pinnedMiss = true;
                    return out;
                }
                Sym pinned = pinnedObject;
                Map<String, Expr> props = pinnedProps;
                boolean miss = pinnedMiss;
                pinnedObject = null;
                try {
                    for (UrlValue v : evalString(value.node(), value.scope())) {
                        add(out, v.asUncertain());
                    }
                } finally {
                    pinnedObject = pinned;
                    pinnedProps = props;
                    pinnedMiss = miss;
                }
                return out;
            }
            for (Map<String, Expr> props : objectCandidates(o, object)) {
                Expr value = props.get(name);
                if (value == null) {
                    continue;
                }
                for (UrlValue v : evalString(value.node(), value.scope())) {
                    add(out, v.asUncertain());
                }
            }
            return out;
        } finally {
            o.stringEvaluating = false;
        }
    }

    /**
     * 폼 필드의 값을 읽는 식이면 페이지의 필드 표에서 찾은 URL들(F-010-R41). 아니거나 표에 없으면 빈 목록.
     * 주인 페이지의 표에서 폼까지 맞춰 찾은 것이 아니면 추론이다.
     */
    private List<UrlValue> fieldValues(SyntaxNode expr, Scope scope) {
        FieldValue found = fieldUrls == null ? null : fieldUrls.lookup(expr, scope);
        if (found == null || found.urls().isEmpty()) {
            return List.of();
        }
        boolean uncertain = !found.certain() || found.urls().size() > 1;
        List<UrlValue> out = new ArrayList<>(found.urls().size());
        for (String url : found.urls()) {
            UrlValue v = UrlValue.literal(url);
            add(out, uncertain ? v.asUncertain() : v);
        }
        return out;
    }

    private static final Pattern JSP_URL_TAG = Pattern.compile(
            "<[\\w-]+:url\\b[^>]*?\\bvalue\\s*=\\s*(['\"])(.*?)\\1[^>]*?>(?:\\s*</[\\w-]+:url\\s*>)?", Pattern.DOTALL);
    private static final Pattern JSP_VALUE = Pattern.compile("\\$\\{[^}]*\\}|<%=.*?%>", Pattern.DOTALL);

    /**
     * JS 파일의 문자열에 JSP 태그·식이 그대로 적힌 경우(F-010-R31): 파일이 JSP를 거쳐 나가거나, JSP에서 옮겨 적은 코드다.
     * jsp가 인라인 스크립트에 하는 치환과 같게 읽는다: {@code <c:url value='X'/>}는 X, {@code ${…}}와 {@code <%=…%>}는
     * {@code __JSP_EXPR__}(맨 앞이면 컨텍스트 경로, 그 밖에서는 동적 조각). 템플릿 리터럴의 {@code ${…}}는 JS 식이라 여기로 오지 않는다.
     */
    static String jspMarkup(String text) {
        if (text.indexOf("${") < 0 && text.indexOf("<%=") < 0 && text.indexOf(":url") < 0) {
            return text;
        }
        String out = JSP_URL_TAG.matcher(text).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(m.group(2)));
        return JSP_VALUE.matcher(out).replaceAll(JSP_EXPR);
    }

    /** EL의 본문이 {@code 조건 ? '리터럴A' : '리터럴B'} 꼴(F-011-R25와 같은 규칙: 따옴표는 홑·겹, 조건에 {@code ?}가 없다). */
    private static final Pattern EL_TERNARY = Pattern.compile(
            "\\$\\{[^?]*\\?\\s*(['\"])([^'\"]*)\\1\\s*:\\s*(['\"])([^'\"]*)\\3\\s*\\}", Pattern.DOTALL);
    /** 한 문자열에서 후보로 펼치는 삼항 EL의 수(2개면 후보 4개). */
    private static final int MAX_TERNARIES = 2;

    /**
     * 인라인 스크립트의 문자열 리터럴. {@code __JSP_EXPR__} 자리의 원문(치환 목록, F-011-R14)이 {@code ${c ? 'a' : 'b'}} 꼴이면
     * 그 자리를 {@code {*}}로 두지 않고 a와 b를 넣은 후보로 펼친다(F-010-R44). 후보는 흐름을 모르고 고른 것이라 불확실 표시가 붙는다.
     *
     * @param node 문자열 노드(또는 템플릿 리터럴의 조각). 본문에서의 오프셋으로 치환 목록을 찾는다
     * @param content 그 노드의 내용(이스케이프를 푼 것)
     */
    private List<UrlValue> embeddedLiterals(SyntaxNode node, String content) {
        if (!content.contains(JSP_EXPR)) {
            return List.of(UrlValue.literal(content));
        }
        ModuleInfo.Unit unit = unitOf == null ? null : unitOf.apply(node);
        if (unit == null || unit.substitutions == null) {
            return List.of(literal(content));
        }
        // 이스케이프를 푼 내용과 원문은 자리표시자가 나오는 순서가 같다: k번째 자리표시자끼리 맞춘다
        String raw = node.text();
        List<String> variants = List.of(content);
        int ternaries = 0;
        int rawAt = raw.lastIndexOf(JSP_EXPR);
        int contentAt = content.lastIndexOf(JSP_EXPR);
        // 뒤에서부터 바꿔야 앞쪽 자리의 위치가 그대로다
        while (rawAt >= 0 && contentAt >= 0) {
            String original = unit.substitutions.originalAt(node.startOffset() + rawAt);
            java.util.regex.Matcher m = original == null ? null : EL_TERNARY.matcher(original.trim());
            // 쿼리 값 자리의 삼항(앞에 '?'나 '&'가 있다)은 경로를 가르지 않는다: 펼치면 경로가 같은 후보 둘이 되어 쿼리 값 하나만
            // 남고 추론이 된다. 그 자리는 종전대로 {*}로 둔다(F-010-R44 개정)
            if (m != null && m.matches() && !inQuery(content, contentAt) && ++ternaries <= MAX_TERNARIES) {
                List<String> next = new ArrayList<>(variants.size() * 2);
                for (String variant : variants) {
                    for (String branch : new String[] {m.group(2), m.group(4)}) {
                        String replaced = variant.substring(0, contentAt) + branch + variant.substring(contentAt + JSP_EXPR.length());
                        if (!next.contains(replaced)) {
                            next.add(replaced);
                        }
                    }
                }
                variants = next;
            }
            rawAt = rawAt == 0 ? -1 : raw.lastIndexOf(JSP_EXPR, rawAt - 1);
            contentAt = contentAt == 0 ? -1 : content.lastIndexOf(JSP_EXPR, contentAt - 1);
        }
        if (variants.size() == 1) {
            return List.of(literal(variants.get(0)));
        }
        List<UrlValue> out = new ArrayList<>(variants.size());
        for (String variant : variants) {
            add(out, literal(variant).asUncertain());
        }
        return out;
    }

    /** 문자열의 그 자리가 쿼리 문자열 안인가: 앞에 {@code ?}나 {@code &}가 있다. */
    private static boolean inQuery(String content, int at) {
        for (int i = 0; i < at; i++) {
            char c = content.charAt(i);
            if (c == '?' || c == '&') {
                return true;
            }
        }
        return false;
    }

    /** 문자열 리터럴. {@code __JSP_EXPR__} 자리는 컨텍스트 경로 후보 조각으로 나눈다. */
    private static UrlValue literal(String text) {
        if (!text.contains(JSP_EXPR)) {
            return UrlValue.literal(text);
        }
        List<Part> parts = new ArrayList<>();
        int from = 0;
        while (true) {
            int at = text.indexOf(JSP_EXPR, from);
            if (at < 0) {
                break;
            }
            if (at > from) {
                parts.add(Part.lit(text.substring(from, at)));
            }
            parts.add(Part.CTX);
            from = at + JSP_EXPR.length();
        }
        if (from < text.length()) {
            parts.add(Part.lit(text.substring(from)));
        }
        return new UrlValue(parts, false);
    }

    /** 값을 모르는 이름. 이름이 컨텍스트 경로처럼 보이면 맨 앞에서 뗄 수 있는 조각으로 둔다. */
    private static UrlValue unknown(String name) {
        return name != null && CONTEXT_NAME.matcher(name).matches() ? UrlValue.CONTEXT : UrlValue.DYNAMIC;
    }

    /**
     * 쓰는 자리에서 도달할 수 있는 대입만 남긴다(F-010-R27): 쓰는 자리와 대입이 같은 if 문의 서로 다른 가지(then과 else)에 있으면
     * 그 대입은 이 자리의 값이 될 수 없다. 흐름 전체를 보는 것이 아니라 이 한 가지만 본다. 전부 걸러지면 원래 후보를 그대로 둔다.
     */
    private static List<Sym.Assign> reachable(List<Sym.Assign> candidates, SyntaxNode use) {
        if (use == null || candidates.size() < 2) {
            return candidates;
        }
        List<Sym.Assign> out = new ArrayList<>(candidates.size());
        for (Sym.Assign c : candidates) {
            if (!exclusiveBranches(c.value(), use)) {
                out.add(c);
            }
        }
        return out.isEmpty() || out.size() == candidates.size() ? candidates : out;
    }

    /** 두 노드가 같은 if 문의 then 가지와 else 가지에 따로 들어 있는가. */
    static boolean exclusiveBranches(SyntaxNode a, SyntaxNode b) {
        if (a == null || b == null) {
            return false;
        }
        Map<SyntaxNode, SyntaxNode> above = new IdentityHashMap<>();
        SyntaxNode child = a;
        for (SyntaxNode p = a.parent(); p != null; p = p.parent()) {
            above.put(p, child);
            child = p;
        }
        child = b;
        for (SyntaxNode p = b.parent(); p != null; p = p.parent()) {
            SyntaxNode other = above.get(p);
            if (other != null) {
                // 가장 가까운 공통 조상
                if (other == child || !p.is("if_statement")) {
                    return false;
                }
                SyntaxNode consequence = p.field("consequence");
                SyntaxNode alternative = p.field("alternative");
                return (other == consequence && child == alternative) || (other == alternative && child == consequence);
            }
            child = p;
        }
        return false;
    }

    /**
     * 쓰는 자리를 감싼 if·삼항의 조건이 이 변수를 문자열 리터럴과 비교하면({@code x === 'POST'}, {@code 'POST' != x},
     * {@code &&}로 이은 것 포함) 후보 값을 그 가지에 맞게 좁힌다. 좁힌 결과가 비면 원래 후보를 그대로 둔다.
     */
    private List<UrlValue> narrowByConditions(List<UrlValue> values, SyntaxNode use, Sym s, Scope scope) {
        if (values.size() < 2) {
            return values;
        }
        List<UrlValue> out = values;
        SyntaxNode child = use;
        for (SyntaxNode p = use.parent(); p != null && out.size() > 1; p = p.parent()) {
            if (p.isAny("if_statement", "ternary_expression")) {
                if (child == p.field("consequence")) {
                    out = applyCondition(out, p.field("condition"), true, s, scope, 0);
                } else if (child == p.field("alternative")) {
                    out = applyCondition(out, p.field("condition"), false, s, scope, 0);
                }
            }
            child = p;
        }
        return out;
    }

    private List<UrlValue> applyCondition(List<UrlValue> values, SyntaxNode condition, boolean positive, Sym s, Scope scope,
            int level) {
        SyntaxNode c = Syntax.unwrap(condition);
        if (c == null || level > 8) {
            return values;
        }
        if (c.is("unary_expression") && c.field("operator") != null && c.field("operator").type().equals("!")) {
            return applyCondition(values, c.field("argument"), !positive, s, scope, level + 1);
        }
        if (!c.is("binary_expression")) {
            return values;
        }
        String op = Syntax.operator(c);
        if ((op.equals("&&") && positive) || (op.equals("||") && !positive)) {
            List<UrlValue> left = applyCondition(values, c.field("left"), positive, s, scope, level + 1);
            return applyCondition(left, c.field("right"), positive, s, scope, level + 1);
        }
        boolean equal = op.equals("===") || op.equals("==");
        if (!equal && !op.equals("!==") && !op.equals("!=")) {
            return values;
        }
        SyntaxNode left = Syntax.unwrap(c.field("left"));
        SyntaxNode right = Syntax.unwrap(c.field("right"));
        String literal = null;
        if (left != null && right != null) {
            if (right.is("string") && refersTo(left, s, scope)) {
                literal = Syntax.stringContent(right);
            } else if (left.is("string") && refersTo(right, s, scope)) {
                literal = Syntax.stringContent(left);
            }
        }
        if (literal == null) {
            return values;
        }
        boolean keepEqual = equal == positive;
        List<UrlValue> out = new ArrayList<>(values.size());
        for (UrlValue v : values) {
            if (literal.equals(v.literalOrNull()) == keepEqual) {
                out.add(v);
            }
        }
        return out.isEmpty() ? values : out;
    }

    private boolean refersTo(SyntaxNode node, Sym s, Scope scope) {
        if (!node.is("identifier")) {
            return false;
        }
        Sym found = scope.lookup(node.text());
        return found != null && (found == s || canonical(found) == s);
    }

    private List<UrlValue> symString(Sym s, String name, SyntaxNode use) {
        if (s.param) {
            boolean ctxLike = CONTEXT_NAME.matcher(s.name).matches();
            if (s.paramOwner != null && s.paramIndex >= 0 && !s.multiAssigned()) {
                return List.of(new UrlValue(List.of(Part.param(s.paramOwner, s.paramIndex, ctxLike)), false));
            }
            return List.of(ctxLike ? UrlValue.CONTEXT : UrlValue.DYNAMIC);
        }
        if (s.importRef != null || s.stringEvaluating) {
            return List.of(UrlValue.DYNAMIC);
        }
        List<Sym.Assign> candidates = new ArrayList<>();
        if (s.init != null && s.destruct.isEmpty()) {
            candidates.add(new Sym.Assign(s.init, s.initScope));
        }
        candidates.addAll(s.assigns);
        if (candidates.isEmpty()) {
            return List.of(unknown(name));
        }
        boolean several = candidates.size() > 1;
        candidates = reachable(candidates, use);
        s.stringEvaluating = true;
        try {
            List<UrlValue> out = new ArrayList<>();
            for (Sym.Assign c : candidates) {
                for (UrlValue v : evalString(c.value(), c.scope())) {
                    // 값은 모르지만 이름이 컨텍스트 경로인 변수: var ctx = $('#ctx').val()
                    if (v.parts().size() == 1 && v.parts().get(0).kind() == UrlValue.Kind.DYN && CONTEXT_NAME.matcher(s.name).matches()) {
                        v = UrlValue.CONTEXT;
                    }
                    add(out, several ? v.asUncertain() : v);
                }
            }
            out = withoutEmpty(out);
            for (Sym.Assign a : s.appends) {
                out = product(out, evalString(a.value(), a.scope()), false);
            }
            return out;
        } finally {
            s.stringEvaluating = false;
        }
    }

    private static void add(List<UrlValue> out, UrlValue v) {
        if (out.size() < MAX_ALTERNATIVES && !out.contains(v)) {
            out.add(v);
        }
    }

    private static List<UrlValue> union(List<UrlValue> a, List<UrlValue> b) {
        List<UrlValue> out = new ArrayList<>(a.size() + b.size());
        for (UrlValue v : a) {
            add(out, v);
        }
        for (UrlValue v : b) {
            add(out, v);
        }
        return withoutEmpty(out);
    }

    /**
     * 후보 가운데 빈 문자열을 뺀다(다른 후보가 있을 때만). {@code let url = ""}, {@code id || ""}의 빈 문자열은 자리만 잡은 값이라
     * URL 후보로 두면 {@code /api}, {@code /board/} 같은 반쪽 요청이 생긴다.
     */
    private static List<UrlValue> withoutEmpty(List<UrlValue> values) {
        if (values.size() < 2) {
            return values;
        }
        List<UrlValue> out = new ArrayList<>(values.size());
        for (UrlValue v : values) {
            if (!v.parts().isEmpty()) {
                out.add(v);
            }
        }
        return out.isEmpty() ? values : out;
    }

    /** 모든 조합의 연결. asBase면 뒤의 값을 baseURL 규칙으로 붙인다. */
    static List<UrlValue> product(List<UrlValue> a, List<UrlValue> b, boolean asBase) {
        List<UrlValue> out = new ArrayList<>(Math.min(a.size() * b.size(), MAX_ALTERNATIVES));
        for (UrlValue x : a) {
            for (UrlValue y : b) {
                add(out, asBase ? y.withBase(x) : x.concat(y));
            }
        }
        return out;
    }
}
