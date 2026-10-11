package io.codeatlas.web.analysis;

import io.codeatlas.web.analysis.Evaluator.Expr;
import io.codeatlas.web.syntax.SyntaxNode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 호출, new, 대입이 HTTP 요청 지점인지 가려낸다. URL과 메서드의 평가는 하지 않고 어느 식이 그것인지만 알려 준다. */
final class RequestMatcher {

    /**
     * 요청 지점 하나.
     *
     * @param api fetch, axios, jquery, xhr, beacon, eventsource, navigation, form, route(클라이언트 라우터 이동)
     * @param url URL 식. 옵션 객체에 url이 없으면 null
     * @param method 메서드 식. 없으면 null(기본 메서드를 쓴다)
     * @param defaultMethod 메서드 식이 없을 때의 메서드. 옵션을 읽을 수 없어 모르면 ANY
     * @param base axios 인스턴스의 baseURL 후보
     * @param silent 정적 경로가 없을 때 미해결로도 남기지 않을 것인가(요청인지 확실하지 않은 형태)
     * @param fallbackText url이 null일 때 미해결 원문으로 쓸 식
     * @param options 메서드를 그 자리에서 읽지 못한 옵션 객체 식(변수, 매개변수). 없으면 null
     * @param methodKeys options에서 메서드를 담는 프로퍼티 이름들
     * @param form 폼 action 대입이면 그 폼을 가리키는 식({@code document.listForm}, {@code frm}, {@code $('#f')}). 아니면 null
     */
    record Request(String api, Expr url, Expr method, String defaultMethod, List<UrlValue> base, boolean silent,
            SyntaxNode fallbackText, Expr options, List<String> methodKeys, SyntaxNode form) {

        Request(String api, Expr url, Expr method, String defaultMethod, List<UrlValue> base, boolean silent,
                SyntaxNode fallbackText) {
            this(api, url, method, defaultMethod, base, silent, fallbackText, null, List.of(), null);
        }

        Request withOptions(Expr optionsExpr, List<String> keys) {
            return new Request(api, url, method, defaultMethod, base, silent, fallbackText, optionsExpr, keys, form);
        }

        Request withForm(SyntaxNode formExpr) {
            return new Request(api, url, method, defaultMethod, base, silent, fallbackText, options, methodKeys, formExpr);
        }
    }

    private static final List<String> METHOD_KEY = List.of("method");
    private static final List<String> JQUERY_METHOD_KEYS = List.of("type", "method");

    private static final Set<String> AXIOS_METHODS = Set.of("get", "post", "put", "delete", "patch", "head", "options");
    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");
    private static final Set<String> WINDOW_NAMES = Set.of("window", "self", "top", "parent", "globalThis", "opener",
            "window.top", "window.parent", "window.opener", "window.self");

    /** IE의 모달 창 열기. 첫 인자가 여는 페이지의 URL이다({@code window.open}과 같은 화면 이동). */
    private static final Set<String> DIALOG_OPENERS = Set.of("showModalDialog", "showModelessDialog");
    /** 라우터 객체로 보는 이름({@code history.push}, {@code router.push}, {@code this.$router.replace}). */
    private static final Set<String> ROUTER_OBJECTS = Set.of("history", "router", "$router");
    private static final Set<String> ROUTER_METHODS = Set.of("push", "replace", "navigate");
    /** 라우터 객체·이동 함수를 돌려주는 훅과 생성 함수. */
    private static final Set<String> ROUTER_FACTORIES = Set.of("useHistory", "useRouter", "createBrowserHistory",
            "createHashHistory", "createMemoryHistory");

    private final Evaluator ev;

    RequestMatcher(Evaluator ev) {
        this.ev = ev;
    }

    // ------------------------------------------------------------------ 호출

    Request matchCall(SyntaxNode call, Scope scope) {
        SyntaxNode callee = Syntax.callee(call);
        List<SyntaxNode> args = Syntax.arguments(call);
        if (callee == null) {
            return null;
        }
        if (callee.is("identifier")) {
            String name = callee.text();
            if (name.equals("fetch") && scope.lookup("fetch") == null) {
                return fetch(args, scope);
            }
            if (ev.eval(callee, scope) instanceof Val.Axios ax) {
                return axiosCall(ax, args, scope, call);
            }
            if (DIALOG_OPENERS.contains(name) && !args.isEmpty() && nativeGlobal(name, scope)) {
                return new Request("navigation", new Expr(args.get(0), scope), null, "GET", List.of(), false, null);
            }
            if (scope.module.usesRouter() && !args.isEmpty() && isNavigateFunction(callee, scope)) {
                return route(args.get(0), scope);
            }
            return null;
        }
        if (!callee.is("member_expression")) {
            return null;
        }
        SyntaxNode object = Syntax.unwrap(callee.field("object"));
        String prop = Syntax.memberName(callee);
        if (object == null || prop == null) {
            return null;
        }
        if (prop.equals("fetch") && isWindow(object, scope)) {
            return fetch(args, scope);
        }
        if (isJQuery(object, scope)) {
            return jqueryStatic(prop, args, scope, call);
        }
        if (prop.equals("load") && !args.isEmpty() && isJQueryObject(object, scope) && isStringish(args.get(0))) {
            // $(sel).load(url[, data][, complete])
            boolean post = args.size() >= 2 && Syntax.unwrap(args.get(1)).is("object");
            return new Request("jquery", new Expr(args.get(0), scope), null, post ? "POST" : "GET", List.of(), true, null);
        }
        if ((prop.equals("attr") || prop.equals("prop")) && args.size() == 2 && isStringLiteral(args.get(0), "action")) {
            return new Request("form", new Expr(args.get(1), scope), null, "ANY", List.of(), true, null).withForm(object);
        }
        if ((prop.equals("assign") || prop.equals("replace")) && args.size() == 1 && isLocation(object, scope)) {
            return new Request("navigation", new Expr(args.get(0), scope), null, "GET", List.of(), false, null);
        }
        if (prop.equals("open") && !args.isEmpty() && isWindow(object, scope)) {
            return new Request("navigation", new Expr(args.get(0), scope), null, "GET", List.of(), false, null);
        }
        if (DIALOG_OPENERS.contains(prop) && !args.isEmpty() && isWindow(object, scope) && nativeGlobal(prop, scope)) {
            return new Request("navigation", new Expr(args.get(0), scope), null, "GET", List.of(), false, null);
        }
        if (scope.module.usesRouter() && ROUTER_METHODS.contains(prop) && !args.isEmpty() && isRouterObject(object, prop, scope)) {
            Request route = route(args.get(0), scope);
            if (route != null) {
                return route;
            }
        }
        if (prop.equals("open") && args.size() >= 2
                && (ev.eval(object, scope) instanceof Val.Xhr || isHttpMethodLiteral(args.get(0)))) {
            return new Request("xhr", new Expr(args.get(1), scope), new Expr(args.get(0), scope), "ANY", List.of(), false, null);
        }
        if (prop.equals("sendBeacon") && !args.isEmpty() && compact(object).endsWith("navigator")) {
            return new Request("beacon", new Expr(args.get(0), scope), null, "POST", List.of(), false, null);
        }
        Val ov = ev.eval(object, scope);
        if (!(ov instanceof Val.Axios) && ov instanceof Val.Unknown && object.is("identifier")
                && (object.text().equals("$http") || object.text().equals("$axios"))) {
            ov = new Val.Axios(List.of());
        }
        if (ov instanceof Val.Axios ax) {
            if (AXIOS_METHODS.contains(prop) && !args.isEmpty()) {
                return new Request("axios", new Expr(args.get(0), scope), null, prop.toUpperCase(Locale.ROOT), ax.base(), false, null);
            }
            if (prop.equals("request") && !args.isEmpty()) {
                return axiosConfig(ax, args.get(0), scope);
            }
        }
        return null;
    }

    private Request fetch(List<SyntaxNode> args, Scope scope) {
        if (args.isEmpty()) {
            return null;
        }
        Expr method = null;
        Expr options = null;
        String defaultMethod = "GET";
        if (args.size() >= 2) {
            if (ev.isObjectLike(args.get(1), scope)) {
                method = ev.properties(args.get(1), scope).get("method");
            } else {
                // 옵션이 변수·매개변수다: 메서드는 그 변수의 값 후보나 호출 지점의 인자에서 읽는다
                defaultMethod = "ANY";
                options = new Expr(args.get(1), scope);
            }
        }
        return new Request("fetch", new Expr(args.get(0), scope), method, defaultMethod, List.of(), false, null)
                .withOptions(options, METHOD_KEY);
    }

    /** {@code axios(url[, config])}, {@code axios(config)}. */
    private Request axiosCall(Val.Axios ax, List<SyntaxNode> args, Scope scope, SyntaxNode call) {
        if (args.isEmpty()) {
            return null;
        }
        if (ev.isObjectLike(args.get(0), scope)) {
            return axiosConfig(ax, args.get(0), scope);
        }
        Expr method = null;
        Expr options = null;
        String defaultMethod = "GET";
        if (args.size() >= 2) {
            if (ev.isObjectLike(args.get(1), scope)) {
                method = ev.properties(args.get(1), scope).get("method");
            } else {
                defaultMethod = "ANY";
                options = new Expr(args.get(1), scope);
            }
        }
        return new Request("axios", new Expr(args.get(0), scope), method, defaultMethod, ax.base(), false, null)
                .withOptions(options, METHOD_KEY);
    }

    private Request axiosConfig(Val.Axios ax, SyntaxNode config, Scope scope) {
        if (!ev.isObjectLike(config, scope)) {
            return new Request("axios", null, null, "ANY", ax.base(), false, config);
        }
        Map<String, Expr> props = ev.properties(config, scope);
        List<UrlValue> base = ax.base();
        Expr baseUrl = props.get("baseURL");
        if (baseUrl != null) {
            base = ev.evalString(baseUrl.node(), baseUrl.scope());
        }
        return new Request("axios", props.get("url"), props.get("method"), "GET", base, false, config);
    }

    private Request jqueryStatic(String prop, List<SyntaxNode> args, Scope scope, SyntaxNode call) {
        if (args.isEmpty()) {
            return null;
        }
        switch (prop) {
            case "ajax" -> {
                SyntaxNode first = args.get(0);
                if (ev.isObjectLike(first, scope)) {
                    Map<String, Expr> props = ev.properties(first, scope);
                    Expr method = props.get("type") != null ? props.get("type") : props.get("method");
                    return new Request("jquery", props.get("url"), method, "GET", List.of(), false, first);
                }
                if (isStringish(first) || args.size() >= 2) {
                    Expr method = null;
                    Expr options = null;
                    String defaultMethod = "GET";
                    if (args.size() >= 2) {
                        if (ev.isObjectLike(args.get(1), scope)) {
                            Map<String, Expr> props = ev.properties(args.get(1), scope);
                            method = props.get("type") != null ? props.get("type") : props.get("method");
                        } else {
                            defaultMethod = "ANY";
                            options = new Expr(args.get(1), scope);
                        }
                    }
                    return new Request("jquery", new Expr(first, scope), method, defaultMethod, List.of(), false, null)
                            .withOptions(options, JQUERY_METHOD_KEYS);
                }
                // $.ajax(settings): settings가 무엇인지 모른다.
                return new Request("jquery", null, null, "ANY", List.of(), false, first);
            }
            case "get", "getJSON", "getScript" -> {
                return jqueryShorthand(args, scope, "GET");
            }
            case "post" -> {
                return jqueryShorthand(args, scope, "POST");
            }
            default -> {
                return null;
            }
        }
    }

    /** {@code $.get(url, ...)} 또는 {@code $.get({ url, ... })}. */
    private Request jqueryShorthand(List<SyntaxNode> args, Scope scope, String method) {
        SyntaxNode first = args.get(0);
        if (Syntax.unwrap(first).is("object")) {
            return new Request("jquery", ev.properties(first, scope).get("url"), null, method, List.of(), false, first);
        }
        return new Request("jquery", new Expr(first, scope), null, method, List.of(), false, null);
    }

    // ------------------------------------------------------------------ 모달 창, 클라이언트 라우터

    /**
     * 이 이름이 브라우저의 것인가: 이 스크립트에도, 같은 페이지가 싣는 다른 스크립트에도 같은 이름의 프로젝트 선언이 없다.
     * 프로젝트가 대체 구현({@code window.showModalDialog = function …})을 싣고 있으면 그 함수의 호출로 해석해야 한다.
     */
    private boolean nativeGlobal(String name, Scope scope) {
        return scope.lookup(name) == null && scope.module.top.syms.get(name) == null
                && ev.globals(scope.module, name).syms().isEmpty();
    }

    /** {@code navigate(…)}: 이름이 navigate이거나 {@code useNavigate()}로 받은 함수이고, 프로젝트 함수가 아니다. */
    private boolean isNavigateFunction(SyntaxNode callee, Scope scope) {
        Sym s = scope.lookup(callee.text());
        String factory = s == null ? null : initCallName(s);
        if (!callee.text().equals("navigate") && !"useNavigate".equals(factory)) {
            return false;
        }
        return !(ev.eval(callee, scope) instanceof Val.Func);
    }

    /** {@code history.push}, {@code router.replace}, {@code this.props.history.push}, {@code useRouter()}로 받은 객체의 메서드. */
    private boolean isRouterObject(SyntaxNode object, String method, Scope scope) {
        List<String> chain = Syntax.chain(object);
        if (chain == null) {
            return false;
        }
        String last = chain.get(chain.size() - 1);
        boolean named = ROUTER_OBJECTS.contains(last) && !(method.equals("navigate") && last.equals("history"));
        if (!named && chain.size() == 1) {
            Sym s = scope.lookup(last);
            String factory = s == null ? null : initCallName(s);
            named = factory != null && ROUTER_FACTORIES.contains(factory);
        }
        return named;
    }

    /** 변수를 {@code 이름(…)} 호출로 한 번 초기화했으면 그 피호출자 이름. */
    private static String initCallName(Sym s) {
        SyntaxNode init = s.param || s.multiAssigned() || s.init == null || !s.destruct.isEmpty() ? null : Syntax.unwrap(s.init);
        if (init == null || !init.is("call_expression")) {
            return null;
        }
        SyntaxNode callee = Syntax.callee(init);
        return callee == null ? null : callee.is("member_expression") ? Syntax.memberName(callee) : callee.text();
    }

    /**
     * 클라이언트 라우터 이동의 대상 경로(F-010-R30). 인자가 문자열 식이면 그것, 객체면 {@code pathname}(react-router)·
     * {@code path}(vue-router)다. 경로가 없는 인자({@code navigate(-1)}, {@code { name: 'user' }})는 null.
     * 서버 요청이 아니므로 경로를 알 수 없어도 미해결로 남기지 않는다.
     */
    Request route(SyntaxNode argument, Scope scope) {
        SyntaxNode arg = Syntax.unwrap(argument);
        if (arg == null) {
            return null;
        }
        Expr url;
        if (arg.is("object")) {
            Map<String, Expr> props = ev.properties(arg, scope);
            url = props.get("pathname") != null ? props.get("pathname") : props.get("path");
            if (url == null) {
                return null;
            }
        } else if (isStringish(arg)) {
            url = new Expr(arg, scope);
        } else {
            return null;
        }
        return new Request(ROUTE, url, null, "GET", List.of(), true, null);
    }

    static final String ROUTE = "route";

    // ------------------------------------------------------------------ new, 대입

    Request matchNew(SyntaxNode node, Scope scope) {
        SyntaxNode ctor = Syntax.unwrap(node.field("constructor"));
        List<SyntaxNode> args = Syntax.arguments(node);
        if (ctor != null && ctor.is("identifier") && ctor.text().equals("EventSource") && scope.lookup("EventSource") == null
                && !args.isEmpty()) {
            return new Request("eventsource", new Expr(args.get(0), scope), null, "GET", List.of(), false, null);
        }
        return null;
    }

    Request matchAssign(SyntaxNode node, Scope scope) {
        SyntaxNode left = Syntax.unwrap(node.field("left"));
        SyntaxNode right = node.field("right");
        if (left == null || right == null) {
            return null;
        }
        if (isLocationTarget(left, scope)) {
            return new Request("navigation", new Expr(right, scope), null, "GET", List.of(), false, null);
        }
        if (left.is("member_expression") && "action".equals(Syntax.memberName(left))) {
            return new Request("form", new Expr(right, scope), null, "ANY", List.of(), true, null)
                    .withForm(Syntax.unwrap(left.field("object")));
        }
        return null;
    }

    // ------------------------------------------------------------------ 판정

    private static String compact(SyntaxNode node) {
        return node.text().replaceAll("\\s+", "");
    }

    private static String rootName(SyntaxNode node) {
        List<String> chain = Syntax.chain(node);
        return chain == null ? null : chain.get(0);
    }

    private boolean unboundRoot(SyntaxNode node, Scope scope) {
        String root = rootName(node);
        return root != null && scope.lookup(root) == null;
    }

    /** {@code window}, {@code self}, {@code top}, {@code parent} 등 창 객체인가. */
    private boolean isWindow(SyntaxNode node, Scope scope) {
        return WINDOW_NAMES.contains(compact(node)) && unboundRoot(node, scope);
    }

    /** {@code location}, {@code window.location}, {@code document.location} 등. */
    private boolean isLocation(SyntaxNode node, Scope scope) {
        String text = compact(node);
        if (!unboundRoot(node, scope)) {
            return false;
        }
        if (text.equals("location") || text.equals("document.location")) {
            return true;
        }
        return text.endsWith(".location") && WINDOW_NAMES.contains(text.substring(0, text.length() - ".location".length()));
    }

    /** 대입 대상이 {@code location} 또는 {@code location.href}인가. */
    private boolean isLocationTarget(SyntaxNode left, Scope scope) {
        if (left.is("identifier")) {
            return left.text().equals("location") && scope.lookup("location") == null;
        }
        if (!left.is("member_expression")) {
            return false;
        }
        if (isLocation(left, scope)) {
            return true;
        }
        SyntaxNode object = Syntax.unwrap(left.field("object"));
        return "href".equals(Syntax.memberName(left)) && object != null && isLocation(object, scope);
    }

    /** {@code $} 또는 {@code jQuery} 자체인가. IIFE 매개변수로 받은 {@code $}도 jQuery로 본다. */
    private boolean isJQuery(SyntaxNode node, Scope scope) {
        if (!node.is("identifier") || !(node.text().equals("$") || node.text().equals("jQuery"))) {
            return false;
        }
        Sym s = scope.lookup(node.text());
        if (s == null) {
            return true;
        }
        Val v = ev.valueOf(ev.canonical(s));
        return v instanceof Val.Unknown || v instanceof Val.JQuery;
    }

    /** {@code $(sel)}, {@code $(sel).find(x)}, {@code $el}처럼 jQuery 객체로 보이는 식인가. */
    private boolean isJQueryObject(SyntaxNode node, Scope scope) {
        SyntaxNode n = node;
        for (int i = 0; i < 20 && n != null; i++) {
            if (n.is("identifier")) {
                return n.text().startsWith("$") && n.text().length() > 1;
            }
            if (n.is("call_expression")) {
                SyntaxNode callee = Syntax.callee(n);
                if (callee == null) {
                    return false;
                }
                if (isJQuery(callee, scope)) {
                    return true;
                }
                n = callee;
            } else if (n.is("member_expression")) {
                n = Syntax.unwrap(n.field("object"));
            } else {
                return false;
            }
        }
        return false;
    }

    /** 문자열을 만드는 식으로 보이는가(리터럴, 템플릿, {@code +} 연결, 이름). 함수 식 등은 아니다. */
    private static boolean isStringish(SyntaxNode node) {
        SyntaxNode n = Syntax.unwrap(node);
        return n != null && n.isAny("string", "template_string", "binary_expression", "identifier", "member_expression",
                "ternary_expression");
    }

    private static boolean isStringLiteral(SyntaxNode node, String value) {
        SyntaxNode n = Syntax.unwrap(node);
        return n != null && n.is("string") && Syntax.stringContent(n).equals(value);
    }

    private static boolean isHttpMethodLiteral(SyntaxNode node) {
        SyntaxNode n = Syntax.unwrap(node);
        return n != null && n.is("string") && HTTP_METHODS.contains(Syntax.stringContent(n).toUpperCase(Locale.ROOT));
    }
}
