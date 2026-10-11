package io.codeatlas.web.analysis;

import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.web.syntax.SyntaxNode;
import io.codeatlas.web.syntax.SyntaxTree;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JS 모듈 하나(파일, 또는 한 JSP의 인라인 스크립트 전체). */
final class ModuleInfo {

    /** 원문을 되살릴 수 없는 JSP 식 자리에 쓰는 표기. */
    static final String UNKNOWN_EXPR = "${…}";

    /** 파싱한 소스 하나. 파일 모듈은 1개, 인라인 모듈은 스크립트 조각마다 1개. */
    static final class Unit {
        final ModuleInfo module;
        final String file;
        final EmbeddedSource embedded;
        /** 내장 소스의 치환 목록. 생산자가 싣지 않았거나 읽을 수 없으면 null. */
        final Substitutions substitutions;
        SyntaxTree tree;

        Unit(ModuleInfo module, String file, EmbeddedSource embedded) {
            this.module = module;
            this.file = file;
            this.embedded = embedded;
            this.substitutions = embedded == null ? null
                    : Substitutions.parse(embedded.attrs().get(Substitutions.ATTR), embedded.text().length());
        }

        /**
         * 노드의 원문. 내장 소스면 생산자가 바꿔 넣은 자리를 호스트 파일에 쓴 그대로 되돌린다.
         * 치환 목록이 없으면 자리표시자를 {@value #UNKNOWN_EXPR}로만 바꾼다(내부 토큰을 밖에 보이지 않는다).
         */
        String sourceText(SyntaxNode n) {
            if (embedded == null) {
                return n.text();
            }
            if (substitutions != null) {
                return substitutions.restore(embedded.text(), n.startOffset(), n.endOffset());
            }
            return n.text().replace(Evaluator.JSP_EXPR, UNKNOWN_EXPR);
        }

        /** 노드의 위치를 호스트 파일 기준으로. */
        SourceLocation loc(SyntaxNode n) {
            if (embedded != null) {
                return embedded.locationOf(n.startLine(), n.startColumn(), n.endLine(), n.endColumn());
            }
            return new SourceLocation(file, n.startLine(), n.startColumn(), n.endLine(), n.endColumn());
        }
    }

    /** import, require, 동적 import 한 건. */
    record ImportRecord(String spec, SyntaxNode node, Unit unit) {
    }

    /** 함수 본문의 스코프와 값이 있는 return 식들. */
    static final class FnScope {
        final Scope scope;
        final List<Sym.Assign> returns = new ArrayList<>(2);

        FnScope(Scope scope) {
            this.scope = scope;
        }
    }

    enum SiteKind { CALL, NEW, JSX, REF, ASSIGN }

    /** 나중에 해석할 지점. */
    record Site(SiteKind kind, SyntaxNode node, Scope scope, FunctionInfo owner, Unit unit) {
    }

    /**
     * JSX에서 컴포넌트에 넘긴 속성 하나: {@code <Child onSelect={handleSelect} />}.
     *
     * @param component 요소 이름 식(Child)
     * @param prop 속성 이름(onSelect)
     * @param value 넘긴 식(함수 참조나 함수 리터럴)
     */
    record PropPass(SyntaxNode component, String prop, SyntaxNode value, Scope scope) {
    }

    /** 라우터 컴포넌트의 경로를 담는 JSX 속성 이름. */
    static final java.util.Set<String> ROUTE_ATTRIBUTES = java.util.Set.of("to", "href", "path");

    /**
     * 컴포넌트 요소의 경로 속성 하나: {@code <Link to={URL.MAIN}>}, {@code <Route path="/login">}.
     *
     * @param component 요소 이름(Link)
     * @param attr 속성 이름(to, href, path)
     * @param value 값 식(문자열이거나 중괄호 안의 식)
     * @param at 요소의 여는 태그(위치용)
     */
    record RouteAttr(SyntaxNode component, String attr, SyntaxNode value, Scope scope, FunctionInfo owner, Unit unit,
            SyntaxNode at) {
    }

    /** URL을 담는 HTML 요소의 JSX 속성: 요소 이름 → 속성 이름(F-010-R42). */
    static final Map<String, String> HOST_URL_ATTRIBUTES = Map.of("a", "href", "area", "href", "form", "action", "img", "src",
            "iframe", "src", "frame", "src");

    /**
     * JSX의 HTML 요소에 적은 URL 속성 하나: {@code <a href="/api/x">}, {@code <img src={url}>}, {@code <form action=…>}.
     *
     * @param element 요소 이름(a, area, form, img, iframe, frame)
     * @param value 값 식(문자열이거나 중괄호 안의 식)
     * @param method {@code <form>}의 method 속성 값 식. 없으면 null
     * @param at 요소의 여는 태그(위치용)
     */
    record HostAttr(String element, SyntaxNode value, SyntaxNode method, Scope scope, FunctionInfo owner, Unit unit, SyntaxNode at) {
    }

    /** {@code a.b.c = 값}: 다른 모듈까지 다 읽은 뒤에 멤버로 붙인다. */
    record PendingMember(List<String> path, Scope scope, Sym leaf, boolean createRoot) {
    }

    /** {@code 이름 = 값}, {@code 이름 += 값}. 모듈을 다 읽은 뒤(끌어올림 반영) 심벌에 붙인다. */
    record PendingAssign(String name, Scope scope, SyntaxNode value, boolean append, String namePath) {
    }

    final String id;
    final String path;
    final boolean embedded;
    final Scope top;
    final List<Unit> units = new ArrayList<>();
    final Map<String, Sym> exports = new LinkedHashMap<>();
    final List<String> starReexports = new ArrayList<>();
    /** {@code module.exports = X}, {@code export = X}. */
    Sym cjsExports;
    final List<ImportRecord> imports = new ArrayList<>();
    final Map<String, FunctionInfo> functions = new LinkedHashMap<>();
    final Map<SyntaxNode, FunctionInfo> functionByNode = new IdentityHashMap<>();
    final Map<SyntaxNode, FnScope> fnScopes = new IdentityHashMap<>();
    /** 객체 리터럴과 클래스 노드의 값. */
    final Map<SyntaxNode, Val> literalVals = new IdentityHashMap<>();
    final List<Site> sites = new ArrayList<>();
    final List<PropPass> propPasses = new ArrayList<>();
    final List<RouteAttr> routeAttrs = new ArrayList<>();
    final List<HostAttr> hostAttrs = new ArrayList<>();
    private Boolean usesRouter;
    final List<PendingMember> pendingMembers = new ArrayList<>();
    final List<PendingAssign> pendingAssigns = new ArrayList<>();
    /** import/export/require/module.exports를 쓰는가. */
    boolean usesModuleSyntax;
    boolean usesCommonJs;
    /** 압축 파일이나 벤더 스크립트라 내용을 분석하지 않았다. */
    boolean skipped;
    /** 벤더(서드파티) 스크립트로 판정했다. 분석했더라도 전역 이름 해석의 출처와 대상에서 뺀다. */
    boolean vendor;

    ModuleInfo(String id, String path, boolean embedded) {
        this.id = id;
        this.path = path;
        this.embedded = embedded;
        this.top = new Scope(null, this, true);
    }

    /** 클라이언트 라우터 패키지의 import 지정자인가: react-router…, vue-router, next/…. */
    static boolean isRouterSpec(String spec) {
        return spec.startsWith("react-router") || spec.equals("vue-router") || spec.startsWith("vue-router/")
                || spec.equals("next") || spec.startsWith("next/");
    }

    /** 이 모듈이 클라이언트 라우터를 import하는가(F-010-R30). 수집이 끝난 뒤에 부른다. */
    boolean usesRouter() {
        if (usesRouter == null) {
            boolean found = false;
            for (ImportRecord imp : imports) {
                found |= isRouterSpec(imp.spec());
            }
            usesRouter = found;
        }
        return usesRouter;
    }

    /** 전역 스코프를 공유하는 스크립트(모듈 문법이 없는 파일, 인라인 스크립트)인가. */
    boolean classic() {
        return embedded || !usesModuleSyntax;
    }

    String moduleKind() {
        if (embedded) {
            return "inline";
        }
        if (!usesModuleSyntax) {
            return "script";
        }
        return usesCommonJs ? "commonjs" : "esm";
    }

    @Override
    public String toString() {
        return id;
    }
}
