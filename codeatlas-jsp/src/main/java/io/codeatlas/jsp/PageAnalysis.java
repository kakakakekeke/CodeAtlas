package io.codeatlas.jsp;

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
import io.codeatlas.jsp.JspToken.Type;
import io.codeatlas.jsp.UrlNormalizer.Kind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** JSP·HTML 파일 하나의 분석. 토큰을 문서 순서로 훑으며 그래프에 쓴다. */
final class PageAnalysis {

    static final String CATEGORY_INCLUDE = "jsp-include";
    static final String CATEGORY_URL = "http-url";
    /**
     * 페이지 노드에 싣는 폼 표의 attrs 키(F-011-R16). 값은 {@code "<키>=<METHOD>"} 문자열의 목록이고 키는 폼의 name 또는
     * {@code #} + id다. web(F-010-R24)이 스크립트의 폼 action 대입 지점에서 읽는다.
     */
    static final String FORM_METHODS_ATTR = "formMethods";
    /**
     * 페이지 노드에 싣는 필드 표의 attrs 키(F-011-R29). hidden·text input의 value가 서버 경로일 때
     * {@code "<폼 name 또는 #id>.<필드 name>=<정규화한 경로>"}와 {@code "#<input id>=<경로>"}를 싣는다. web(F-010-R41)이
     * {@code frm.zip_url.value} 같은 식을 URL로 평가할 때 읽는다.
     */
    static final String FORM_FIELDS_ATTR = "formFields";
    /** 현재 URL로 제출하는 폼의 요청임을 알리는 {@code HTTP_REQUEST}의 attrs 키(boolean, F-011-R28). */
    static final String SELF_SUBMIT_ATTR = "selfSubmit";
    /** 현재 URL로 제출하는 폼의 요청에서 URL 자리에 쓰는 표시. 경로는 그 페이지를 그리는 엔드포인트의 경로다(링커가 정한다). */
    static final String SELF_URL = "{self}";
    /** 스크립트가 폼의 action을 바꾸는 흔적: {@code x.action = …}, {@code attr('action', …)}, {@code setAttribute('action', …)}. */
    private static final Pattern ACTION_ASSIGNMENT = Pattern.compile(
            "\\.action\\s*=(?!=)|\\b(?:attr|prop|setAttribute)\\(\\s*['\"]action['\"]\\s*,");
    private static final Pattern FORM_CLOSE = Pattern.compile("(?i)</(?:[a-z][\\w-]*:)?form\\s*>");
    /** 제출을 막는 처리기: {@code …; return false}로 끝난다. */
    private static final Pattern RETURNS_FALSE = Pattern.compile("(?s).*\\breturn\\s+false\\s*;?\\s*");

    private static final Pattern EVENT_ATTRIBUTE = Pattern.compile("(?i)on[a-z]+");
    /** 커스텀 태그(Spring form 태그 등)가 그대로 내보내는 이벤트 속성. HTML 태그는 on으로 시작하면 모두 이벤트로 본다. */
    private static final Set<String> KNOWN_EVENTS = Set.of("onclick", "ondblclick", "onmousedown", "onmouseup",
            "onmouseover", "onmousemove", "onmouseout", "onkeypress", "onkeyup", "onkeydown", "onfocus", "onblur",
            "onchange", "onselect", "onsubmit", "onreset", "onload", "onunload", "oninput");
    private static final Pattern JAVASCRIPT_SCHEME = Pattern.compile("(?i)^\\s*javascript:");
    /** 하는 일이 없는 핸들러: 빈 값, void(0), return false 등. */
    private static final Pattern TRIVIAL_HANDLER = Pattern.compile(
            "(?:void\\s*\\(\\s*0\\s*\\)|void\\s+0|return\\s+false|return\\s+true|return|;|\\s)*");
    private static final Pattern REFRESH_URL = Pattern.compile("(?is)^[^;,]*[;,]\\s*url\\s*=\\s*(.*)$");
    /** {@code <c:url var>} 본문의 {@code <c:param>}을 찾을 때 볼 토큰 수의 한계. */
    private static final int PARAM_LOOKAHEAD = 60;

    private final AnalysisContext ctx;
    private final GraphModel graph;
    private final ProjectIndex index;
    private final SourceFile file;
    private final String path;
    private final boolean jsp;
    private final String pageId;
    private final String moduleId;
    private final String webRoot;
    private final String directory;

    private String text;
    private LineMap lines;

    /** 이 파일에서 선언한 taglib(페이지 속성용). */
    private final Map<String, String> ownTaglibs = new LinkedHashMap<>();
    /** 정적 include로 들어온 것까지 합친 taglib. */
    private final Map<String, String> taglibs = new HashMap<>();
    private final Map<String, String> urlVars = new HashMap<>();
    private final Set<String> contextVars = new HashSet<>();
    private final UrlNormalizer normalizer = new UrlNormalizer(urlVars, contextVars);
    private final List<String> externalScripts = new ArrayList<>();
    private final Set<String> seenHandlers = new HashSet<>();
    private final Set<String> seenTagFiles = new HashSet<>();
    /** 폼 표: 폼의 name 또는 {@code #id} → 메서드(문서 순서). 같은 키의 메서드가 다르면 ANY. */
    private final Map<String, String> formMethods = new LinkedHashMap<>();
    /** action이 없어 현재 URL로 보내는 POST 폼의 수. */
    private int selfPostForms;
    /** action이 없는 폼들(현재 URL로 제출한다). 페이지를 다 본 뒤 요청으로 만들지 정한다(F-011-R28). */
    private final List<SelfForm> selfForms = new ArrayList<>();
    /** 지금 열려 있는 폼의 폼 표 키(name, {@code #id}). 폼 밖이면 null. */
    private List<String> openFormKeys;
    /** hidden·text input에 든 URL: {@code <폼 키>.<필드>=<경로>}, {@code #<id>=<경로>}(문서 순서, F-011-R29). */
    private final Set<String> formFields = new java.util.LinkedHashSet<>();

    /**
     * action이 없는 폼 하나.
     *
     * @param method 메서드. EL이 섞여 알 수 없으면 null(ANY)
     * @param form 이름·ID와 본문 구간(제출 흔적을 이 폼과 짝짓는 데 쓴다)
     */
    private record SelfForm(String method, SourceLocation location, SubmitTraces.Form form, Map<String, Object> extra) {
    }

    /** 페이지의 모든 폼(문서 순서). {@code .submit()}의 수신자를 폼과 맞추는 데 쓴다(F-011-R30). */
    private final List<SubmitTraces.Form> forms = new ArrayList<>();
    /** 지금 열려 있는 폼. 폼 밖이면 null. */
    private SubmitTraces.Form openForm;
    /** 제출을 막지 않는 제출 버튼이 안에 있는 폼. */
    private final Set<SubmitTraces.Form> formsWithSubmitControl = new HashSet<>();
    /** {@code onsubmit}이 {@code return false}로 끝나는 폼: 버튼으로는 제출되지 않는다. */
    private final Set<SubmitTraces.Form> formsBlockingSubmit = new HashSet<>();
    /** {@code <script>} 본문의 구간과 HTML·JSP 주석의 구간(파일 오프셋). */
    private final List<int[]> scriptRanges = new ArrayList<>();
    private final List<int[]> commentRanges = new ArrayList<>();
    private final Set<String> seenFragments = new HashSet<>();
    private int scriptletCount;
    private boolean moduleDeclared;

    private final Layouts layouts;

    PageAnalysis(AnalysisContext ctx, ProjectIndex index, SourceFile file) {
        this(ctx, index, file, null);
    }

    /**
     * @param layouts 페이지가 고른 레이아웃({@code <meta name="decorator">})을 모아 둘 곳. 없으면 null
     */
    PageAnalysis(AnalysisContext ctx, ProjectIndex index, SourceFile file, Layouts layouts) {
        this.layouts = layouts;
        this.ctx = ctx;
        this.graph = ctx.graph();
        this.index = index;
        this.file = file;
        this.path = file.relativePath();
        this.jsp = file.type() == FileType.JSP;
        this.pageId = Ids.jspPage(path);
        this.moduleId = Ids.jsModule(path);
        this.webRoot = WebPaths.webRootOf(path);
        this.directory = WebPaths.directoryOf(path);
    }

    void run() {
        // CR 단독 줄 끝은 LF로 맞춘다(길이 그대로): 스캐너, 줄 번호, 스크립트 치환, 내장 소스가 모두 같은 줄을 본다(F-011-R20)
        text = LineMap.loneCrToLf(file.readText());
        lines = new LineMap(text);
        List<JspToken> tokens = JspScanner.scan(text, jsp ? JspScanner.Mode.JSP : JspScanner.Mode.HTML);

        graph.declare(Node.builder(pageId, NodeKind.JSP_PAGE).name(file.fileName()).qualifiedName(path)
                .location(file.location()).attr("pageType", pageType()).build());
        graph.addEdge(Edge.of(file.nodeId(), pageId, EdgeKind.CONTAINS));

        walk(tokens, text, 0);
        selfSubmitRequests();

        Map<String, Object> attrs = new LinkedHashMap<>();
        if (jsp) {
            List<String> declared = new ArrayList<>();
            ownTaglibs.forEach((prefix, library) -> declared.add(prefix + "=" + library));
            attrs.put("taglibs", declared);
            attrs.put("scriptletCount", scriptletCount);
        }
        if (!externalScripts.isEmpty()) {
            attrs.put("externalScripts", externalScripts);
        }
        if (!formMethods.isEmpty()) {
            List<String> forms = new ArrayList<>();
            formMethods.forEach((key, method) -> forms.add(key + "=" + method));
            attrs.put(FORM_METHODS_ATTR, forms);
        }
        if (selfPostForms > 0) {
            attrs.put("selfPostForms", selfPostForms);
        }
        if (!formFields.isEmpty()) {
            attrs.put(FORM_FIELDS_ATTR, new ArrayList<>(formFields));
        }
        graph.putAttrs(pageId, attrs);
    }

    /** 내용을 분석하지 않는 페이지(벤더 폴더): 페이지 노드만 만든다. */
    void declareSkipped(String reason) {
        graph.declare(Node.builder(pageId, NodeKind.JSP_PAGE).name(file.fileName()).qualifiedName(path)
                .location(file.location()).attr("pageType", pageType()).attr("skipped", reason).build());
        graph.addEdge(Edge.of(file.nodeId(), pageId, EdgeKind.CONTAINS));
    }

    private String pageType() {
        if (!jsp) {
            return "html";
        }
        String name = file.fileName().toLowerCase(Locale.ROOT);
        return name.endsWith(".jspf") ? "jspf" : name.endsWith(".tag") ? "tag" : "jsp";
    }

    /**
     * 토큰을 문서 순서로 처리한다.
     *
     * @param source 토큰의 오프셋이 가리키는 문자열(파일 본문 또는 스크립트 본문)
     * @param base source가 파일 본문에서 시작하는 오프셋
     */
    private void walk(List<JspToken> tokens, String source, int base) {
        for (int i = 0; i < tokens.size(); i++) {
            JspToken token = tokens.get(i);
            switch (token.type()) {
                case DIRECTIVE:
                    directive(token, base);
                    break;
                case SCRIPTLET:
                case EXPRESSION:
                case DECLARATION:
                    scriptletCount++;
                    break;
                case OPEN_TAG:
                    if (jsp) {
                        scriptletCount += countCodeElements(source, token.start(), token.end());
                    }
                    if (token.prefix().isEmpty()) {
                        htmlTag(token, base);
                    } else if (jsp) {
                        customTag(tokens, i, base);
                    }
                    if (!jsp) {
                        thymeleafFragments(token, base);
                    }
                    handlers(token, base);
                    break;
                case CLOSE_TAG:
                    if (token.localName().equalsIgnoreCase("form")) {
                        openFormKeys = null;
                        openForm = null;
                    }
                    break;
                case RAW_TEXT:
                    if (i > 0 && tokens.get(i - 1).isOpenTag("script")) {
                        scriptRanges.add(new int[] {base + token.start(), base + token.end()});
                        inlineScript(tokens.get(i - 1), token);
                    }
                    break;
                case JSP_COMMENT:
                    commentRanges.add(new int[] {base + token.start(), base + token.end()});
                    break;
                case HTML_COMMENT:
                    if (base == 0) {
                        // 스크립트 본문을 감싼 <!-- … -->는 주석이 아니다(스크립트 조각을 훑을 때는 세지 않는다)
                        commentRanges.add(new int[] {token.start(), token.end()});
                    }
                    break;
                default:
                    break;
            }
        }
    }

    /** 태그 안(속성 값)에 든 스크립틀릿·식의 수. */
    private static int countCodeElements(String source, int from, int to) {
        int count = 0;
        int i = source.indexOf("<%", from);
        while (i >= 0 && i < to) {
            char next = i + 2 < source.length() ? source.charAt(i + 2) : ' ';
            if (next != '-' && next != '@') {
                count++;
            }
            i = source.indexOf("<%", i + 2);
        }
        return count;
    }

    // ---- 지시자와 커스텀 태그 ----

    private void directive(JspToken token, int base) {
        if (token.name().equals("taglib")) {
            ProjectIndex.taglib(token, ownTaglibs);
            ProjectIndex.taglib(token, taglibs);
        } else if (token.name().equals("include") && token.hasAttr("file")) {
            include(token.attr("file"), "directive", token, base, null);
        }
    }

    private void customTag(List<JspToken> tokens, int i, int base) {
        JspToken token = tokens.get(i);
        String prefix = token.prefix();
        String local = token.localName();
        if (prefix.equals("jsp")) {
            if (local.equals("include") && token.hasAttr("page")) {
                include(token.attr("page"), "action", token, base, token.attribute("page").orElse(null));
            } else if (local.equals("forward") && token.hasAttr("page")) {
                include(token.attr("page"), "forward", token, base, token.attribute("page").orElse(null));
            } else if (local.equals("directive.include") && token.hasAttr("file")) {
                include(token.attr("file"), "directive", token, base, null);
            }
            return;
        }
        if (local.equals("import") && token.hasAttr("url")) {
            String url = token.attr("url").trim();
            if (!url.startsWith("//") && !url.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) {
                include(url, "import", token, base, token.attribute("url").orElse(null));
            }
        } else if (local.equals("redirect") && token.hasAttr("url")) {
            request(token, base, token.attribute("url").orElse(null), "GET", "redirect", Map.of());
        } else if (ScriptSanitizer.isUrlTag(token) && token.hasAttr("var")) {
            urlVariable(tokens, i);
        } else if (local.equals("set") && token.hasAttr("var")) {
            String var = token.attr("var").trim();
            urlVars.remove(var);
            if (ProjectIndex.isContextVariable(token)) {
                contextVars.add(var);
            } else {
                contextVars.remove(var);
            }
        } else if (local.equals("form") && isSpringForm(prefix)) {
            Map<String, Object> extra = new LinkedHashMap<>();
            String model = token.hasAttr("modelAttribute") ? token.attr("modelAttribute") : token.attr("commandName");
            if (model != null && !model.isBlank()) {
                extra.put("modelAttribute", model.trim());
            }
            String method = method(token, "POST");
            recordForm(token, method, model, base, extra);
            request(token, base, token.attribute("action").orElse(null), method, "form", extra);
        } else if (!TAGS_WITHOUT_URL.contains(local)) {
            if (local.equals("button") && isSpringForm(prefix)) {
                submitControl(token);
            }
            tagUrlAttributes(token, base);
        }
        tagFile(token, base);
    }

    /** URL 속성이 있어도 다른 규칙이 다루거나 URL을 내보내지 않는 JSTL·Spring 태그의 로컬 이름. */
    private static final Set<String> TAGS_WITHOUT_URL = Set.of("import", "redirect", "url", "param", "set", "out", "if", "when",
            "foreach", "forEach", "message", "eval", "include", "forward");
    /** 커스텀 태그에서 URL을 담는 속성 이름(소문자). */
    private static final Set<String> TAG_URL_ATTRIBUTES = Set.of("url", "href", "action", "src", "dataurl");

    /**
     * 접두사 있는 태그의 URL 속성(F-011-R24): {@code <sys:treeselect url="/sys/office/treeData?type=2">}처럼 태그 파일·태그
     * 라이브러리가 그 경로로 요청을 보낸다. 값이 서버 경로({@code /}로 시작하거나 컨텍스트 경로 식 + 경로)일 때만 요청으로 보고
     * 메서드는 모르므로 ANY, 그 태그가 정말 요청을 보내는지 모르므로 추론이다. 상대 경로·외부 URL·동적인 값·스크립트 파일은 보지 않는다.
     */
    private void tagUrlAttributes(JspToken token, int base) {
        for (JspAttribute attribute : token.attributes()) {
            if (!attribute.hasValue() || !TAG_URL_ATTRIBUTES.contains(attribute.name().toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (String variant : UrlNormalizer.ternaryVariants(attribute.value())) {
                UrlNormalizer.Result result = normalizer.normalize(variant);
                if (result.kind() != Kind.REQUEST || result.external() || !result.path().startsWith("/")
                        || result.path().toLowerCase(Locale.ROOT).endsWith(".js")) {
                    continue;
                }
                declareRequest(attribute.value(), result, null, "tag", location(base + token.start(), base + token.end()),
                        Map.of(), true);
            }
        }
    }

    private boolean isSpringForm(String prefix) {
        String library = taglibs.get(prefix);
        return library != null ? library.contains("springframework.org/tags/form") : prefix.equals("form");
    }

    /** {@code <c:url value var>}: 변수 → URL 원문을 기억한다. 본문의 {@code <c:param>}은 쿼리로 붙인다. */
    private void urlVariable(List<JspToken> tokens, int i) {
        JspToken token = tokens.get(i);
        String value = token.attr("value");
        StringBuilder raw = new StringBuilder(value.trim());
        if (!token.selfClosing()) {
            char separator = value.indexOf('?') >= 0 ? '&' : '?';
            for (int j = i + 1; j < tokens.size() && j <= i + PARAM_LOOKAHEAD; j++) {
                JspToken next = tokens.get(j);
                if (next.name().equals(token.name())) {
                    break; // 닫는 태그, 또는 닫지 않고 시작한 다음 url 태그
                }
                if (next.type() == Type.OPEN_TAG && next.localName().equals("param") && next.hasAttr("name")) {
                    raw.append(separator).append(next.attr("name").trim()).append('=');
                    raw.append(next.hasAttr("value") ? next.attr("value") : "");
                    separator = '&';
                }
            }
        }
        String var = token.attr("var").trim();
        urlVars.put(var, raw.toString());
        contextVars.remove(var);
    }

    /** {@code tagdir} 접두사의 태그 사용 → 태그 파일로의 INCLUDES. 한 페이지에서 같은 태그는 첫 사용만. */
    private void tagFile(JspToken token, int base) {
        String library = taglibs.get(token.prefix());
        if (library == null || !library.startsWith("tagdir:")) {
            return;
        }
        String tagPath = library.substring("tagdir:".length()) + "/" + token.localName();
        if (!seenTagFiles.add(tagPath)) {
            return;
        }
        SourceLocation location = location(base + token.start(), base + token.end());
        String target = WebPaths.resolve(webRoot, directory, tagPath + ".tag");
        if (index.page(target).isEmpty()) {
            String tagx = WebPaths.resolve(webRoot, directory, tagPath + ".tagx");
            if (index.page(tagx).isPresent()) {
                target = tagx;
            }
        }
        if (index.page(target).isPresent()) {
            addInclude(Ids.jspPage(target), "tag", tagPath + ".tag", location, false);
        } else {
            graph.addUnresolvedEdge(pageId, EdgeKind.INCLUDES, CATEGORY_INCLUDE, target == null ? tagPath + ".tag" : target,
                    location);
        }
    }

    // ---- 포함 ----

    /**
     * @param rawPath 속성 값 원문
     * @param via directive, action, import, forward
     * @param attribute 대상이 페이지가 아닐 때 요청으로 기록하는 데 쓰는 속성. 지시자는 null
     */
    private void include(String rawPath, String via, JspToken token, int base, JspAttribute attribute) {
        String raw = rawPath.trim();
        if (raw.isEmpty()) {
            return;
        }
        SourceLocation location = location(base + token.start(), base + token.end());
        if (UrlNormalizer.hasDynamicPart(raw)) {
            graph.addUnresolvedEdge(pageId, EdgeKind.INCLUDES, CATEGORY_INCLUDE, raw, location);
            return;
        }
        String targetPath = WebPaths.stripQuery(raw);
        String target = WebPaths.resolve(webRoot, directory, targetPath);
        if (index.page(target).isPresent()) {
            addInclude(Ids.jspPage(target), via, raw, location, false);
            if (via.equals("directive")) {
                // 지시자 include는 본문을 그대로 붙이므로 그 조각의 선언이 이 페이지에서도 유효하다
                ProjectIndex.Declarations inherited = index.declarationsOf(target);
                taglibs.putAll(inherited.taglibs());
                contextVars.addAll(inherited.contextVars());
            }
        } else if (attribute != null && !WebPaths.hasPageExtension(targetPath)) {
            // /common/menu.do 처럼 페이지가 아닌 대상: 서버가 처리하는 요청이다
            request(token, base, attribute, "GET", via.equals("forward") ? "forward" : "include", Map.of());
        } else {
            graph.addUnresolvedEdge(pageId, EdgeKind.INCLUDES, CATEGORY_INCLUDE, target == null ? raw : target, location);
        }
    }

    private void addInclude(String targetId, String via, String rawPath, SourceLocation location, boolean inferred) {
        Edge.Builder edge = Edge.builder(pageId, targetId, EdgeKind.INCLUDES).location(location)
                .attr("via", via).attr("path", rawPath);
        graph.addEdge(inferred ? edge.inferred().build() : edge.build());
    }

    // ---- HTML 태그 ----

    private void htmlTag(JspToken token, int base) {
        switch (token.name().toLowerCase(Locale.ROOT)) {
            case "a":
            case "area":
                linkRequest(token, base, "href", "GET", "link");
                break;
            case "form":
                String method = method(token, "GET");
                recordForm(token, method, null, base, Map.of());
                linkRequest(token, base, "action", method, "form");
                break;
            case "input":
                String inputType = token.attr("type");
                if (inputType != null && (inputType.trim().equalsIgnoreCase("submit") || inputType.trim().equalsIgnoreCase("image"))) {
                    submitControl(token);
                }
                inputField(token);
                break;
            case "button":
                String buttonType = token.attr("type");
                if (buttonType == null || !(buttonType.trim().equalsIgnoreCase("button") || buttonType.trim().equalsIgnoreCase("reset"))) {
                    submitControl(token);
                }
                break;
            case "iframe":
            case "frame":
                linkRequest(token, base, "src", "GET", "frame");
                break;
            case "script":
                scriptSource(token, base);
                break;
            case "meta":
                if (layouts != null && "decorator".equalsIgnoreCase(token.attr("name")) && token.hasAttr("content")
                        && !token.attr("content").isBlank()) {
                    // SiteMesh: 이 페이지를 감쌀 데코레이터의 이름. 모든 페이지를 본 뒤 decorators.xml과 맞춘다
                    layouts.decoratorMeta(path, token.attr("content").trim(), location(base + token.start(), base + token.end()));
                }
                if ("refresh".equalsIgnoreCase(token.attr("http-equiv")) && token.hasAttr("content")) {
                    Matcher matcher = REFRESH_URL.matcher(token.attr("content"));
                    if (matcher.matches()) {
                        String url = matcher.group(1).trim().replaceAll("^['\"]|['\"]$", "");
                        request(url, url, "GET", "refresh", location(base + token.start(), base + token.end()), Map.of(), -1,
                                "content");
                    }
                }
                break;
            default:
                break;
        }
    }

    /** method 속성. 없으면 기본값, EL·식이 섞였으면 null(ANY). */
    private static String method(JspToken token, String defaultMethod) {
        String method = token.attr("method");
        if (method == null || method.isBlank()) {
            return defaultMethod;
        }
        return UrlNormalizer.hasDynamicPart(method) ? null : method.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 폼을 폼 표에 적는다(F-011-R16). 키는 name과 {@code #id}다. Spring {@code <form:form>}은 id를 주지 않으면
     * {@code modelAttribute}(commandName)가 id가 된다. 이름·ID에 EL이 섞였으면 그 키는 적지 않는다.
     *
     * @param method 메서드. EL이 섞여 알 수 없으면 null(ANY)
     * @param springModel {@code <form:form>}의 modelAttribute. HTML 폼이면 null
     */
    private void recordForm(JspToken token, String method, String springModel, int base, Map<String, Object> extra) {
        String value = Ids.httpMethod(method);
        String name = token.attr("name");
        String id = token.attr("id");
        if ((id == null || id.isBlank()) && springModel != null && !springModel.isBlank()) {
            id = springModel;
        }
        List<String> keys = new ArrayList<>(2);
        if (name != null && !name.isBlank() && !UrlNormalizer.hasDynamicPart(name)) {
            formMethods.merge(name.trim(), value, (a, b) -> a.equals(b) ? a : "ANY");
            keys.add(name.trim());
        }
        if (id != null && !id.isBlank() && !UrlNormalizer.hasDynamicPart(id)) {
            formMethods.merge("#" + id.trim(), value, (a, b) -> a.equals(b) ? a : "ANY");
            keys.add("#" + id.trim());
        }
        openFormKeys = token.selfClosing() ? null : keys;
        boolean anyKey = (name != null && UrlNormalizer.hasDynamicPart(name)) || (id != null && UrlNormalizer.hasDynamicPart(id));
        int bodyStart = base + token.end();
        Matcher close = FORM_CLOSE.matcher(text);
        int end = token.selfClosing() ? bodyStart : bodyStart <= text.length() && close.find(bodyStart) ? close.start() : text.length();
        SubmitTraces.Form form = new SubmitTraces.Form(name == null || name.isBlank() ? null : name.trim(),
                id == null || id.isBlank() ? null : id.trim(), anyKey, base + token.start(), bodyStart, end);
        forms.add(form);
        openForm = token.selfClosing() ? null : form;
        String onsubmit = token.attr("onsubmit");
        if (onsubmit != null && RETURNS_FALSE.matcher(onsubmit).matches()) {
            formsBlockingSubmit.add(form);
        }
        String action = token.attr("action");
        boolean noAction = (action == null || action.isBlank()) && (jsp || Thymeleaf.attribute(token, "action") == null);
        if (noAction && value.equals("POST")) {
            selfPostForms++;
        }
        if (noAction) {
            selfForms.add(new SelfForm(method, location(base + token.start(), base + token.end()), form, new LinkedHashMap<>(extra)));
        }
    }

    /**
     * 폼 안의 제출 컨트롤({@code <input type="submit|image">}, type이 button·reset이 아닌 {@code <button>},
     * {@code <form:button>}). {@code onclick}이 {@code return false}로 끝나면 그 버튼은 폼을 제출하지 않는다(F-011-R30).
     */
    private void submitControl(JspToken token) {
        if (openForm == null) {
            return;
        }
        String onclick = token.attr("onclick");
        if (onclick == null || !RETURNS_FALSE.matcher(onclick).matches()) {
            formsWithSubmitControl.add(openForm);
        }
    }

    /**
     * action이 없는 폼은 현재 URL(그 페이지를 그리는 엔드포인트의 경로)로 제출된다(F-011-R28). 경로를 여기서는 모르므로 URL 자리에
     * {@value #SELF_URL}를 쓴 요청을 만들고 {@code selfSubmit=true}를 붙인다. 링커가 "이 페이지를 그리는 엔드포인트와 같은 경로·이
     * 메서드의 엔드포인트"로 잇는다. 스크립트가 action을 대입하는 페이지와, <b>그 폼을</b> 제출하는 흔적이 없는 폼에는 만들지 않는다
     * (AJAX로 값을 모아 보내는 폼이 흔하다). 흔적은 폼 안의, 제출을 막지 않는 제출 버튼과 그 폼을 가리키는 {@code .submit()}
     * 호출이다(F-011-R30, {@link SubmitTraces}). 폼이 정말 그렇게 제출되는지는 모르므로 추론이다.
     */
    private void selfSubmitRequests() {
        if (selfForms.isEmpty() || ACTION_ASSIGNMENT.matcher(text).find()) {
            return;
        }
        SubmitTraces traces = new SubmitTraces(text, forms, scriptRanges, commentRanges);
        for (SelfForm form : selfForms) {
            boolean control = formsWithSubmitControl.contains(form.form()) && !formsBlockingSubmit.contains(form.form());
            if (!control && !traces.submits(form.form(), selfForms.size() == 1)) {
                continue;
            }
            String httpMethod = Ids.httpMethod(form.method());
            if (!jsp && httpMethod.equals("GET")) {
                continue; // 정적 HTML의 GET 폼은 그 파일을 다시 여는 것이다(엔드포인트가 그리는 화면이 아니다)
            }
            String id = Ids.httpRequest(httpMethod, SELF_URL, path, form.location().startLine());
            Node.Builder node = Node.builder(id, NodeKind.HTTP_REQUEST).name(httpMethod + " " + SELF_URL)
                    .location(form.location())
                    .attr("httpMethod", httpMethod)
                    .attr("url", "")
                    .attr("path", SELF_URL)
                    .attr("dynamic", false)
                    .attr("api", "form")
                    .attr(SELF_SUBMIT_ATTR, true);
            form.extra().forEach(node::attr);
            graph.declare(node.build());
            graph.addEdge(Edge.builder(pageId, id, EdgeKind.REQUESTS).location(form.location()).inferred().build());
        }
    }

    /**
     * hidden·text input의 value가 서버 경로면 필드 표에 적는다(F-011-R29): 팝업 URL을 hidden 필드에 두고 스크립트가
     * {@code frm.zip_url.value}로 꺼내 여는 화면이 있다. 경로로 보는 값은 컨텍스트 경로 식 + {@code /…}, 또는 {@code /}로 시작하고
     * {@code .do}로 끝나거나 확장자 없이 두 조각 이상인 정적 경로다. 값은 요청이 아니다(그 값을 쓰는 스크립트의 지점이 요청이다).
     */
    private void inputField(JspToken token) {
        String type = token.attr("type");
        String value = token.attr("value");
        if (value == null || value.isBlank()
                || !(type == null || type.isBlank() || type.equalsIgnoreCase("hidden") || type.equalsIgnoreCase("text"))) {
            return;
        }
        String raw = value.trim();
        UrlNormalizer.Result result = normalizer.normalize(raw);
        if (result.kind() != Kind.REQUEST || result.external() || result.dynamic() || !result.path().startsWith("/")) {
            return;
        }
        if (raw.startsWith("/")) {
            // 컨텍스트 경로 식이 없는 값: 날짜·구분자 같은 다른 뜻의 값과 가리기 위해 경로 꼴을 더 본다
            String last = result.path().substring(result.path().lastIndexOf('/') + 1);
            boolean action = last.toLowerCase(Locale.ROOT).endsWith(".do");
            boolean plain = last.indexOf('.') < 0 && result.path().indexOf('/', 1) > 0 && !last.isEmpty();
            if (!action && !plain) {
                return;
            }
        }
        String url = result.path() + (result.query() == null ? "" : "?" + result.query());
        String name = token.attr("name");
        if (name != null && !name.isBlank() && !UrlNormalizer.hasDynamicPart(name)) {
            if (openFormKeys == null || openFormKeys.isEmpty()) {
                formFields.add("." + name.trim() + "=" + url);
            } else {
                for (String key : openFormKeys) {
                    formFields.add(key + "." + name.trim() + "=" + url);
                }
            }
        }
        String id = token.attr("id");
        if (id != null && !id.isBlank() && !UrlNormalizer.hasDynamicPart(id)) {
            formFields.add("#" + id.trim() + "=" + url);
        }
    }

    // ---- 요청 ----

    /**
     * 링크 속성 하나를 요청으로 기록한다. HTML 파일에서 같은 요소에 Thymeleaf 속성({@code th:href}, {@code th:action},
     * {@code th:src})이 있으면 그것을 읽고 정적 속성은 무시한다(F-011-R17): 정적 값은 브라우저로 템플릿을 그대로 열어 볼 때의
     * 원형용 경로일 뿐 서버가 내보내는 URL이 아니다.
     */
    private void linkRequest(JspToken token, int base, String name, String method, String api) {
        JspAttribute th = jsp ? null : Thymeleaf.attribute(token, name);
        if (th != null && th.hasValue()) {
            request(th.value(), Thymeleaf.linkToUrl(th.value()), method, api, location(base + token.start(), base + token.end()),
                    Map.of(), -1, th.name().toLowerCase(Locale.ROOT));
            return;
        }
        request(token, base, token.attribute(name).orElse(null), method, api, Map.of());
    }

    private void request(JspToken token, int base, JspAttribute attribute, String method, String api,
            Map<String, Object> extra) {
        if (attribute == null || !attribute.hasValue()) {
            return;
        }
        request(attribute.value(), attribute.value(), method, api, location(base + token.start(), base + token.end()), extra,
                base + attribute.valueStart(), attribute.name().toLowerCase(Locale.ROOT));
    }

    /**
     * @param raw 속성 값 원문(노드의 url과 미해결 원문에 쓴다)
     * @param url 정규화할 URL 원문. 보통 raw와 같고, Thymeleaf 링크 식이면 URL 꼴로 바꾼 것이다
     * @param valueOffset 속성 값이 파일에서 시작하는 오프셋({@code javascript:} URL을 핸들러로 넘길 때 쓴다). 모르면 -1
     */
    private void request(String raw, String url, String method, String api, SourceLocation location, Map<String, Object> extra,
            int valueOffset, String attributeName) {
        // EL이 cond ? 'a' : 'b' 꼴이면 {*} 하나가 아니라 후보 URL 둘이다(F-011-R25). 후보에서 온 요청은 추론이다
        List<String> variants = UrlNormalizer.ternaryVariants(url);
        if (variants.size() > 1) {
            boolean any = false;
            for (String variant : variants) {
                UrlNormalizer.Result candidate = normalizer.normalize(variant);
                if (candidate.kind() == Kind.REQUEST) {
                    declareRequest(raw, candidate, method, api, location, extra, true);
                    any = true;
                }
            }
            if (any) {
                return;
            }
        }
        UrlNormalizer.Result result = normalizer.normalize(url);
        if (result.kind() == Kind.SCRIPT) {
            if (valueOffset >= 0) {
                handler(raw, valueOffset, attributeName);
            }
        } else if (result.kind() == Kind.UNRESOLVED) {
            graph.addUnresolvedEdge(pageId, EdgeKind.REQUESTS, CATEGORY_URL, raw.trim(), location);
        } else if (result.kind() == Kind.REQUEST) {
            declareRequest(raw, result, method, api, location, extra, false);
        }
    }

    private void declareRequest(String raw, UrlNormalizer.Result result, String method, String api, SourceLocation location,
            Map<String, Object> extra, boolean inferred) {
        String httpMethod = Ids.httpMethod(method);
        String id = Ids.httpRequest(httpMethod, result.path(), path, location.startLine());
        Node.Builder node = Node.builder(id, NodeKind.HTTP_REQUEST).name(httpMethod + " " + result.path())
                .location(location)
                .attr("httpMethod", httpMethod)
                .attr("url", raw.trim())
                .attr("path", result.path())
                .attr("dynamic", result.dynamic())
                .attr("api", api);
        if (result.query() != null) {
            node.attr("query", result.query());
        }
        if (result.external()) {
            node.attr("external", true);
        }
        if (result.relative()) {
            node.attr("relative", true);
        }
        extra.forEach(node::attr);
        graph.declare(node.build());
        Edge.Builder edge = Edge.builder(pageId, id, EdgeKind.REQUESTS).location(location);
        graph.addEdge(inferred ? edge.inferred().build() : edge.build());
    }

    // ---- 스크립트 ----

    /** {@code <script src>}: 프로젝트 안의 JS 파일을 찾아 INCLUDES로 잇는다. 못 찾으면 externalScripts에만 남긴다. */
    private void scriptSource(JspToken token, int base) {
        JspAttribute th = jsp ? null : Thymeleaf.attribute(token, "src");
        if (th != null && th.hasValue()) {
            thymeleafScript(token, base, th.value().trim());
            return;
        }
        String raw = token.attr("src");
        if (raw == null || raw.isBlank()) {
            return;
        }
        raw = raw.trim();
        UrlNormalizer.Result result = normalizer.normalize(raw);
        if (result.kind() != Kind.REQUEST || result.external() || result.dynamic()) {
            externalScripts.add(raw);
            return;
        }
        resolveScript(result.path(), raw, location(base + token.start(), base + token.end()));
    }

    /**
     * {@code <script th:src="@{…}">}. 링크 식이 정적이면 그 경로로 찾는다. 접두가 동적이면({@code @{${uiPath} + '/js/main.js'}})
     * 원형용 정적 src가 가리키는 파일의 경로가 같은 꼬리({@code /js/main.js})로 끝날 때만 그 파일로 추론해 잇는다.
     */
    private void thymeleafScript(JspToken token, int base, String raw) {
        UrlNormalizer.Result result = normalizer.normalize(Thymeleaf.linkToUrl(raw));
        SourceLocation location = location(base + token.start(), base + token.end());
        if (result.kind() == Kind.REQUEST && !result.external() && !result.dynamic()) {
            resolveScript(result.path(), raw, location);
            return;
        }
        String prefix = UrlNormalizer.DYNAMIC + "/";
        String prototype = token.attr("src");
        if (result.kind() == Kind.REQUEST && !result.external() && result.path().startsWith(prefix) && prototype != null
                && !prototype.isBlank()) {
            String tail = result.path().substring(UrlNormalizer.DYNAMIC.length());
            UrlNormalizer.Result proto = normalizer.normalize(prototype.trim());
            if (!tail.contains(UrlNormalizer.DYNAMIC) && proto.kind() == Kind.REQUEST && !proto.external() && !proto.dynamic()) {
                Optional<SourceFile> file = index.script(WebPaths.resolve(webRoot, directory, proto.path()));
                if (file.isPresent() && ("/" + file.get().relativePath()).endsWith(tail)) {
                    addScriptInclude(file.get().relativePath(), raw, location, true, null);
                    return;
                }
            }
        }
        externalScripts.add(raw);
    }

    /** 정규화한 src 경로로 스크립트 파일을 찾아 잇는다. 못 찾으면 externalScripts에 원문을 남긴다. */
    private void resolveScript(String src, String raw, SourceLocation location) {
        // 웹 루트 기준(HTML의 상대 경로는 파일 위치 기준). JSP의 상대 경로는 요청 URL 기준이라 정확히 알 수 없다
        if (src.startsWith("/") || !jsp) {
            Optional<SourceFile> exact = index.script(WebPaths.resolve(webRoot, directory, src));
            if (exact.isPresent()) {
                addScriptInclude(exact.get().relativePath(), raw, location, false, null);
                return;
            }
        }
        String found = scriptBySuffix(src);
        if (found != null) {
            addScriptInclude(found, raw, location, true, null);
            return;
        }
        // 압축본(x.min.js)이 프로젝트에 없고 x.js만 있으면 그 파일을 싣는 것으로 본다(빌드가 압축본을 만든다)
        Matcher min = MIN_SCRIPT.matcher(src);
        if (min.matches()) {
            String plain = min.group(1) + min.group(2);
            Optional<SourceFile> exact = src.startsWith("/") || !jsp ? index.script(WebPaths.resolve(webRoot, directory, plain))
                    : Optional.empty();
            String sibling = exact.isPresent() ? exact.get().relativePath() : scriptBySuffix(plain);
            if (sibling != null) {
                addScriptInclude(sibling, raw, location, true, src);
                return;
            }
        }
        externalScripts.add(raw);
    }

    private static final Pattern MIN_SCRIPT = Pattern.compile("(?i)(.*)[.-]min(\\.(?:js|mjs|cjs))");

    /**
     * 웹 루트 기준으로 찾지 못한 src를 경로 끝으로 찾는다. mvc:resources 매핑이나 다른 모듈의 정적 자원처럼 물리 경로가 다를 수
     * 있다. src 전체로 끝나는 파일이 하나뿐이면 그것, 없으면 끝에서부터 가장 길게(두 조각 이상) 맞는 파일이 하나뿐일 때 그것이다.
     */
    private String scriptBySuffix(String src) {
        String suffix = src.replaceAll("^(?:\\.{0,2}/)+", "");
        if (suffix.isEmpty()) {
            return null;
        }
        List<String> candidates = index.scriptsEndingWith(suffix);
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        return candidates.isEmpty() ? index.scriptByLongestSuffix(suffix) : null;
    }

    // ---- Thymeleaf 조각 ----

    /**
     * {@code th:replace}·{@code th:insert}·{@code th:include}의 조각 식 → 그 템플릿 파일로의 INCLUDES(F-011-R18).
     * 템플릿 이름은 이 파일의 조상 {@code templates/} 폴더 기준이고 확장자는 {@code .html}이다. 같은 파일의 조각
     * ({@code :: name}, {@code this :: name})은 포함이 아니다. 한 페이지에서 같은 대상은 첫 사용만 기록한다.
     */
    private void thymeleafFragments(JspToken token, int base) {
        for (String name : List.of("replace", "insert", "include")) {
            JspAttribute attribute = Thymeleaf.attribute(token, name);
            if (attribute == null || !attribute.hasValue() || attribute.value().isBlank()) {
                continue;
            }
            String raw = attribute.value().trim();
            Thymeleaf.Fragment fragment = Thymeleaf.fragment(raw);
            SourceLocation location = location(base + token.start(), base + token.end());
            if (fragment.dynamic()) {
                graph.addUnresolvedEdge(pageId, EdgeKind.INCLUDES, CATEGORY_INCLUDE, raw, location);
                continue;
            }
            if (fragment.template() == null) {
                continue;
            }
            String file = WebPaths.hasPageExtension(fragment.template()) ? fragment.template() : fragment.template() + ".html";
            String root = templatesRoot();
            String target = WebPaths.resolve("", root == null ? directory : root, file);
            boolean inferred = false;
            if (index.page(target).isEmpty()) {
                List<String> candidates = index.pagesEndingWith(file);
                if (candidates.size() == 1) {
                    target = candidates.get(0);
                    inferred = true;
                } else {
                    graph.addUnresolvedEdge(pageId, EdgeKind.INCLUDES, CATEGORY_INCLUDE, target == null ? raw : target, location);
                    continue;
                }
            }
            if (!target.equals(path) && seenFragments.add(target)) {
                addInclude(Ids.jspPage(target), "thymeleaf", raw, location, inferred);
            }
        }
    }

    /** 이 파일의 가장 가까운 조상 {@code templates} 폴더의 경로. 없으면 null. */
    private String templatesRoot() {
        String[] segments = path.split("/");
        for (int i = segments.length - 2; i >= 0; i--) {
            if (segments[i].equals("templates")) {
                return String.join("/", java.util.Arrays.copyOfRange(segments, 0, i + 1));
            }
        }
        return null;
    }

    /**
     * @param minOf 페이지가 실제로 가리킨 것은 압축본이고 이 파일은 그 원본으로 본 것이면 압축본의 src 경로. 아니면 null
     */
    private void addScriptInclude(String scriptPath, String raw, SourceLocation location, boolean inferred, String minOf) {
        String targetId = Ids.jsModule(scriptPath);
        graph.reference(targetId, NodeKind.JS_MODULE, scriptPath.substring(scriptPath.lastIndexOf('/') + 1));
        Edge.Builder edge = Edge.builder(pageId, targetId, EdgeKind.INCLUDES).location(location)
                .attr("via", "script").attr("path", raw);
        if (minOf != null) {
            edge.attr("minOf", minOf);
        }
        graph.addEdge(inferred ? edge.inferred().build() : edge.build());
    }

    private void inlineScript(JspToken tag, JspToken body) {
        String src = tag.attr("src");
        if ((src != null && !src.isBlank()) || (!jsp && Thymeleaf.attribute(tag, "src") != null)) {
            return; // src가 있으면 브라우저는 본문을 무시한다
        }
        String type = tag.attr("type");
        if (!isJavaScriptType(type)) {
            return;
        }
        String code = body.text(text);
        if (code.isBlank()) {
            return;
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        if (jsp) {
            List<JspToken> tokens = JspScanner.scan(code, JspScanner.Mode.FRAGMENT);
            walk(tokens, code, body.start()); // 스크립트 안의 include, URL 변수, 스크립틀릿 수
            ScriptSanitizer.Sanitized sanitized = ScriptSanitizer.sanitizeTracked(code, tokens, urlVars);
            code = sanitized.text();
            putSubstitutions(attrs, sanitized);
        }
        if (type != null && !type.isBlank()) {
            attrs.put("scriptType", type.trim());
        }
        declareModule();
        ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.JS, moduleId, code, location(body.start(), body.end()), true, attrs));
    }

    private static boolean isJavaScriptType(String type) {
        if (type == null || type.isBlank()) {
            return true;
        }
        String lower = type.toLowerCase(Locale.ROOT);
        return lower.contains("javascript") || lower.contains("ecmascript") || lower.contains("module")
                || lower.contains("babel") || lower.contains("jsx");
    }

    private void handlers(JspToken token, int base) {
        boolean custom = !token.prefix().isEmpty();
        for (JspAttribute attribute : token.attributes()) {
            String name = attribute.name().toLowerCase(Locale.ROOT);
            if (attribute.hasValue() && (custom ? KNOWN_EVENTS.contains(name) : EVENT_ATTRIBUTE.matcher(name).matches())) {
                handler(attribute.value(), base + attribute.valueStart(), name);
            }
        }
    }

    /** 이벤트 핸들러나 {@code javascript:} URL의 코드를 내장 소스로 등록한다. 값이 같은 것은 한 번만. */
    private void handler(String raw, int offset, String attributeName) {
        String code = raw;
        int start = offset;
        Matcher scheme = JAVASCRIPT_SCHEME.matcher(code);
        if (scheme.find()) {
            start += scheme.end();
            code = code.substring(scheme.end());
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("inlineHandler", "true");
        attrs.put("attribute", attributeName);
        ScriptSanitizer.Sanitized sanitized = jsp
                ? ScriptSanitizer.sanitizeTracked(code, JspScanner.scan(code, JspScanner.Mode.FRAGMENT), urlVars)
                : new ScriptSanitizer.Sanitized(code, List.of());
        code = sanitized.text();
        boolean exact = true;
        String decoded = decodeEntities(code);
        if (!decoded.equals(code)) {
            code = decoded;
            exact = false; // 엔티티를 풀면 길이가 달라진다
            sanitized = decodeEntities(sanitized, decoded); // 치환 목록의 오프셋도 푼 본문 기준으로 옮긴다
        }
        putSubstitutions(attrs, sanitized);
        String key = code.trim();
        if (TRIVIAL_HANDLER.matcher(key).matches() || !seenHandlers.add(key)) {
            return;
        }
        declareModule();
        ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.JS, moduleId, code, location(start, offset + raw.length()), exact, attrs));
    }

    /** 치환한 것이 있으면 치환 목록을 내장 소스 attrs에 싣는다(F-011-R14). */
    private static void putSubstitutions(Map<String, String> attrs, ScriptSanitizer.Sanitized sanitized) {
        String encoded = sanitized.encodedSubstitutions();
        if (!encoded.isEmpty()) {
            attrs.put(ScriptSanitizer.SUBSTITUTIONS_ATTR, encoded);
        }
    }

    /**
     * 치환 결과의 엔티티를 풀고 치환 목록의 오프셋을 푼 본문 기준으로 옮긴다. 치환 구간과 그 사이 구간을 따로 풀어 맞춘다.
     * 엔티티가 구간 경계에 걸쳐 그렇게 푼 결과가 통째로 푼 것과 다르면 치환 목록을 버린다.
     */
    private static ScriptSanitizer.Sanitized decodeEntities(ScriptSanitizer.Sanitized sanitized, String decodedWhole) {
        String text = sanitized.text();
        StringBuilder sb = new StringBuilder(decodedWhole.length());
        List<ScriptSanitizer.Substitution> moved = new ArrayList<>(sanitized.substitutions().size());
        int position = 0;
        for (ScriptSanitizer.Substitution s : sanitized.substitutions()) {
            sb.append(decodeEntities(text.substring(position, s.offset())));
            int start = sb.length();
            sb.append(decodeEntities(text.substring(s.offset(), s.offset() + s.length())));
            moved.add(new ScriptSanitizer.Substitution(start, sb.length() - start, s.original()));
            position = s.offset() + s.length();
        }
        sb.append(decodeEntities(text.substring(position)));
        return new ScriptSanitizer.Sanitized(decodedWhole, sb.toString().equals(decodedWhole) ? moved : List.of());
    }

    private static String decodeEntities(String value) {
        if (value.indexOf('&') < 0) {
            return value;
        }
        return value.replace("&quot;", "\"").replace("&#34;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
                .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    /** 인라인 스크립트의 주인 노드. 내장 소스를 처음 등록할 때 한 번 만든다. */
    private void declareModule() {
        if (moduleDeclared) {
            return;
        }
        moduleDeclared = true;
        graph.declare(Node.builder(moduleId, NodeKind.JS_MODULE).name(file.fileName()).qualifiedName(path)
                .location(file.location()).attr("inline", true).build());
        graph.addEdge(Edge.of(pageId, moduleId, EdgeKind.CONTAINS));
    }

    private SourceLocation location(int start, int end) {
        return file.location(lines.line(start), lines.column(start), lines.line(end), lines.column(end));
    }
}
