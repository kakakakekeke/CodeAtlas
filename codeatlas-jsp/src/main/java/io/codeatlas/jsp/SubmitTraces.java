package io.codeatlas.jsp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 페이지 본문의 {@code .submit()} 호출이 어느 폼을 가리키는지 가린다(F-011-R30). JavaScript를 파싱하지 않고 글자만 본다:
 * 수신자 식({@code document.frm}, {@code $("#id")}, {@code document.forms[0]}, {@code this.form}, 매개변수·변수)을 읽어
 * 폼의 이름·ID·순번·위치와 맞춘다. 수신자를 알 수 없는 호출은 "모름"으로 두고, 쓰는 쪽이 action 없는 폼이 하나뿐일 때만 그 폼의
 * 것으로 본다. 다른 이름·ID를 명시한 호출과 다른 창({@code opener}, {@code parent})의 폼은 이 폼의 흔적이 아니다.
 */
final class SubmitTraces {

    /**
     * 페이지의 폼 하나.
     *
     * @param name name 속성. 없으면 null
     * @param id id 속성(Spring {@code <form:form>}은 없으면 modelAttribute). 없으면 null
     * @param anyKey 이름이나 ID에 EL·식이 섞여 값을 모른다(이름·ID를 명시한 호출이 이 폼일 수 있다)
     * @param tagStart 여는 태그의 시작 오프셋
     * @param bodyStart 여는 태그 바로 뒤
     * @param end 닫는 태그의 시작 오프셋(없으면 본문 끝)
     */
    record Form(String name, String id, boolean anyKey, int tagStart, int bodyStart, int end) {
    }

    private enum Kind { NAME_OR_ID, NAME, ID, INDEX, AT, UNKNOWN, NONE }

    /**
     * {@code .submit()}의 수신자가 가리키는 것.
     *
     * @param key 이름·ID, INDEX면 순번
     * @param offset AT일 때 그 식이 놓인 오프셋(그 자리를 품은 폼)
     */
    private record Target(Kind kind, String key, int offset) {
        static final Target UNKNOWN = new Target(Kind.UNKNOWN, null, -1);
        static final Target NONE = new Target(Kind.NONE, null, -1);
    }

    /**
     * 스크립트의 함수 하나.
     *
     * @param name 선언한 이름, 또는 대입·프로퍼티의 이름. 없으면 null
     * @param key 객체 리터럴의 프로퍼티로 적은 함수면 true({@code submitHandler: function(form)})
     * @param start {@code function}(화살표 함수는 매개변수)의 시작 오프셋
     * @param bodyStart 본문 여는 중괄호의 오프셋
     * @param end 본문 닫는 중괄호 다음 오프셋
     */
    private record Function(String name, boolean key, List<String> params, int start, int bodyStart, int end) {
    }

    private static final Pattern SUBMIT_CALL = Pattern.compile("\\.\\s*submit\\s*\\(");
    private static final Pattern FUNCTION = Pattern.compile(
            "\\bfunction\\b\\s*([\\w$]*)\\s*\\(([^)]*)\\)\\s*\\{|\\(([^()]*)\\)\\s*=>\\s*\\{|([\\w$]+)\\s*=>\\s*\\{");
    /** 이름 없는 함수 앞의 {@code 이름 =}, {@code 이름:}. */
    private static final Pattern NAMED_BEFORE = Pattern.compile("([\\w$]+)\\s*([=:])\\s*$");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final Pattern STRING_LITERAL = Pattern.compile("(['\"])([^'\"]*)\\1");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern SELECTOR_ID = Pattern.compile("(?:form)?#([\\w-]+)");
    private static final Pattern SELECTOR_ATTRIBUTE = Pattern.compile("(?:form)?\\[(name|id)\\s*=\\s*['\"]?([\\w.-]+)['\"]?\\]");
    private static final Pattern OTHER_WINDOW = Pattern.compile(
            "(?:window\\.)?(?:opener|parent|top|dialogArguments|frames)\\b.*", Pattern.DOTALL);
    private static final Pattern JQUERY = Pattern.compile("(?:\\$|jQuery)\\s*\\(");
    /** jQuery 체인에서 다른 요소로 옮겨 가는 메서드. 그 뒤의 수신자는 처음 고른 요소가 아니다. */
    private static final Set<String> TRAVERSALS = Set.of("find", "children", "parent", "parents", "closest", "next", "prev",
            "siblings", "contents", "filter", "not", "has", "eq", "first", "last", "nextAll", "prevAll", "add", "end",
            "andSelf", "addBack", "offsetParent");
    private static final Set<String> DOCUMENT_MEMBERS = Set.of("forms", "all", "body", "documentElement", "location",
            "getElementById", "getElementsByName", "querySelector", "write", "writeln", "cookie", "title");
    private static final int MAX_DEPTH = 4;
    /** 호출 지점·대입을 따라가며 모으는 후보의 한계. */
    private static final int MAX_TARGETS = 32;
    /** 페이지 하나에서 수신자 식을 풀어 보는 횟수의 한계. 넘으면 나머지는 모르는 것으로 둔다. */
    private static final int MAX_STEPS = 400;

    private final String text;
    private final List<Form> forms;
    private final List<int[]> scripts;
    private final List<Function> functions = new ArrayList<>();
    /** {@code .submit()} 호출마다 그 수신자가 가리킬 수 있는 것들. */
    private final List<List<Target>> calls = new ArrayList<>();
    private int steps;

    /**
     * @param text 페이지 본문
     * @param forms 페이지의 모든 폼(문서 순서. {@code document.forms[n]}의 n)
     * @param scripts {@code <script>} 본문의 구간들 {@code [시작, 끝)}
     * @param comments HTML·JSP 주석의 구간들. 그 안의 코드는 읽지 않는다
     */
    SubmitTraces(String text, List<Form> forms, List<int[]> scripts, List<int[]> comments) {
        this.forms = forms;
        this.scripts = scripts;
        this.text = mask(text, scripts, comments);
        Matcher call = SUBMIT_CALL.matcher(this.text);
        boolean any = call.find();
        if (!any) {
            return;
        }
        collectFunctions();
        do {
            String receiver = receiverBefore(call.start());
            calls.add(receiver.isEmpty() ? List.of(Target.UNKNOWN) : classify(receiver, call.start(), 0));
        } while (call.find());
    }

    /**
     * 이 폼을 가리킬 수 있는 {@code .submit()} 호출이 있는가.
     *
     * @param onlyActionless 이 폼이 페이지에서 하나뿐인 action 없는 폼인가(수신자를 모르는 호출을 이 폼의 것으로 본다)
     */
    boolean submits(Form form, boolean onlyActionless) {
        for (List<Target> targets : calls) {
            for (Target target : targets) {
                if (matches(target, form, onlyActionless)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matches(Target target, Form form, boolean onlyActionless) {
        switch (target.kind()) {
            case NAME_OR_ID:
                return form.anyKey() || target.key().equals(form.name()) || target.key().equals(form.id());
            case NAME:
                return form.anyKey() || target.key().equals(form.name());
            case ID:
                return form.anyKey() || target.key().equals(form.id());
            case INDEX:
                return forms.size() == 1 || forms.indexOf(form) == Integer.parseInt(target.key());
            case AT:
                return target.offset() >= form.tagStart() && target.offset() < form.end();
            case UNKNOWN:
                return onlyActionless;
            default:
                return false;
        }
    }

    // ---- 수신자 식 읽기 ----

    /** {@code .submit}의 점 앞에 놓인 수신자 식(식별자, 점, 괄호·대괄호 묶음의 연쇄). 없으면 빈 문자열. */
    private String receiverBefore(int dot) {
        int i = dot - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
            i--;
        }
        int end = i + 1;
        while (i >= 0) {
            char c = text.charAt(i);
            if (c == ')' || c == ']') {
                int open = matchingOpen(i);
                if (open < 0) {
                    break;
                }
                i = open - 1;
            } else if (isIdentifierChar(c)) {
                while (i >= 0 && isIdentifierChar(text.charAt(i))) {
                    i--;
                }
            } else if (c == '.') {
                i--;
                while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
                    i--;
                }
            } else {
                break;
            }
        }
        return text.substring(i + 1, end).trim();
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private int matchingOpen(int close) {
        int depth = 0;
        for (int i = close; i >= 0 && close - i < 2000; i--) {
            char c = text.charAt(i);
            if (c == ')' || c == ']') {
                depth++;
            } else if (c == '(' || c == '[') {
                if (--depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 여는 괄호·대괄호·중괄호의 짝 위치. 문자열 안의 괄호는 세지 않는다. 없으면 -1. */
    private static int matchingClose(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipString(s, i);
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (--depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 문자열 리터럴의 닫는 따옴표 위치. 홑·겹따옴표 문자열이 그 줄에서 닫히지 않으면 줄 끝. */
    private static int skipString(String s, int open) {
        char quote = s.charAt(open);
        for (int i = open + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                return i;
            } else if (c == '\n' && quote != '`') {
                return i;
            }
        }
        return s.length() - 1;
    }

    // ---- 수신자가 가리키는 것 ----

    /**
     * @param at 그 식이 놓인 본문 오프셋({@code this}가 어느 요소인지, 어느 함수의 매개변수인지 보는 데 쓴다)
     */
    private List<Target> classify(String raw, int at, int depth) {
        String e = stripParentheses(raw.trim());
        if (depth > MAX_DEPTH || e.isEmpty() || ++steps > MAX_STEPS) {
            return List.of(Target.UNKNOWN);
        }
        while (e.startsWith("window.") || e.startsWith("self.")) {
            e = e.substring(e.indexOf('.') + 1).trim();
        }
        if (OTHER_WINDOW.matcher(e).matches()) {
            return List.of(Target.NONE); // 다른 창·프레임의 폼이다
        }
        Matcher jquery = JQUERY.matcher(e);
        if (jquery.lookingAt()) {
            return jqueryTarget(e, jquery.end() - 1, at, depth);
        }
        if (e.equals("this") || e.equals("this.form")) {
            return List.of(inScript(at) ? Target.UNKNOWN : new Target(Kind.AT, null, at));
        }
        if (e.startsWith("document.") || e.startsWith("document[")) {
            return List.of(documentTarget(e.substring("document".length())));
        }
        if (IDENTIFIER.matcher(e).matches()) {
            return identifier(e, at, depth);
        }
        if (e.endsWith(".form") && IDENTIFIER.matcher(e.substring(0, e.length() - ".form".length())).matches()) {
            // el.form: el이 폼 안의 요소(this를 넘겨받은 매개변수)면 그 폼이다
            List<Target> owner = identifier(e.substring(0, e.length() - ".form".length()), at, depth);
            List<Target> out = new ArrayList<>();
            for (Target t : owner) {
                out.add(t.kind() == Kind.AT || t.kind() == Kind.NONE ? t : Target.UNKNOWN);
            }
            return out;
        }
        return List.of(Target.UNKNOWN);
    }

    private static String stripParentheses(String e) {
        String s = e;
        while (s.startsWith("(") && matchingClose(s, 0) == s.length() - 1) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    /** {@code document} 뒤의 나머지({@code .frm}, {@code .forms[0]}, {@code .getElementById("x")})가 가리키는 폼. */
    private static Target documentTarget(String rest) {
        String r = rest.trim();
        if (r.startsWith(".forms") || r.startsWith(".all")) {
            String member = r.substring(r.startsWith(".forms") ? ".forms".length() : ".all".length()).trim();
            if (member.startsWith(".") && IDENTIFIER.matcher(member.substring(1)).matches()) {
                return new Target(Kind.NAME_OR_ID, member.substring(1), -1);
            }
            if (member.startsWith("[") && member.endsWith("]")) {
                String index = member.substring(1, member.length() - 1).trim();
                Matcher literal = STRING_LITERAL.matcher(index);
                if (literal.matches()) {
                    return new Target(Kind.NAME_OR_ID, literal.group(2), -1);
                }
                if (NUMBER.matcher(index).matches() && r.startsWith(".forms")) {
                    return new Target(Kind.INDEX, index, -1);
                }
            }
            return Target.UNKNOWN;
        }
        for (String lookup : new String[] {".getElementById", ".getElementsByName", ".querySelector"}) {
            if (!r.startsWith(lookup)) {
                continue;
            }
            String call = r.substring(lookup.length()).trim();
            int close = call.startsWith("(") ? matchingClose(call, 0) : -1;
            if (close < 0) {
                return Target.UNKNOWN;
            }
            Matcher literal = STRING_LITERAL.matcher(call.substring(1, close).trim());
            String tail = call.substring(close + 1).trim();
            if (!literal.matches()) {
                return Target.UNKNOWN;
            }
            if (lookup.equals(".getElementById")) {
                return tail.isEmpty() ? new Target(Kind.ID, literal.group(2), -1) : Target.UNKNOWN;
            }
            if (lookup.equals(".getElementsByName")) {
                return tail.matches("\\[\\s*0\\s*\\]") ? new Target(Kind.NAME, literal.group(2), -1) : Target.UNKNOWN;
            }
            return tail.isEmpty() ? selector(literal.group(2)) : Target.UNKNOWN;
        }
        if (r.startsWith("[") && r.endsWith("]")) {
            Matcher literal = STRING_LITERAL.matcher(r.substring(1, r.length() - 1).trim());
            return literal.matches() ? new Target(Kind.NAME_OR_ID, literal.group(2), -1) : Target.UNKNOWN;
        }
        if (r.startsWith(".") && IDENTIFIER.matcher(r.substring(1)).matches() && !DOCUMENT_MEMBERS.contains(r.substring(1))) {
            return new Target(Kind.NAME_OR_ID, r.substring(1), -1); // document.frm
        }
        return Target.UNKNOWN;
    }

    /** CSS 선택자가 폼 하나를 이름·ID로 지목하면 그것. 클래스·태그·복합 선택자는 모른다. */
    private static Target selector(String selector) {
        String s = selector.trim();
        Matcher id = SELECTOR_ID.matcher(s);
        if (id.matches()) {
            return new Target(Kind.ID, id.group(1), -1);
        }
        Matcher attribute = SELECTOR_ATTRIBUTE.matcher(s);
        if (attribute.matches()) {
            return new Target(attribute.group(1).equals("id") ? Kind.ID : Kind.NAME, attribute.group(2), -1);
        }
        return Target.UNKNOWN;
    }

    /**
     * {@code $(…)}로 시작하는 식. 인자가 선택자 문자열이면 그 선택자, 식이면 그 식이 가리키는 것이다. 뒤에 다른 요소로 옮겨 가는
     * 메서드({@code find}, {@code parents})가 이어지면 처음 고른 요소가 아니다: {@code $(this).parents("form")}·
     * {@code closest("form")}만 그 요소가 든 폼으로 읽는다.
     */
    private List<Target> jqueryTarget(String e, int open, int at, int depth) {
        int close = matchingClose(e, open);
        if (close < 0) {
            return List.of(Target.UNKNOWN);
        }
        String argument = e.substring(open + 1, close).trim();
        String chain = e.substring(close + 1);
        boolean toForm = false;
        Matcher method = Pattern.compile("\\.\\s*([\\w$]+)\\s*\\(").matcher(chain);
        int from = 0;
        while (method.find(from)) {
            int end = matchingClose(chain, method.end() - 1);
            if (end < 0) {
                return List.of(Target.UNKNOWN);
            }
            if (TRAVERSALS.contains(method.group(1))) {
                String selector = chain.substring(method.end(), end).trim();
                boolean form = (method.group(1).equals("parents") || method.group(1).equals("closest"))
                        && selector.matches("(['\"])form\\1");
                if (!form || toForm) {
                    return List.of(Target.UNKNOWN);
                }
                toForm = true;
            }
            from = end + 1;
        }
        Matcher literal = STRING_LITERAL.matcher(argument);
        if (literal.matches()) {
            // 선택자로 고른 요소에서 폼으로 올라간 것은 그 요소가 어디 있는지 모른다
            return List.of(toForm ? Target.UNKNOWN : selector(literal.group(2)));
        }
        List<Target> inner = classify(argument, at, depth + 1);
        if (!toForm) {
            return inner;
        }
        List<Target> out = new ArrayList<>(inner.size());
        for (Target t : inner) {
            out.add(t.kind() == Kind.AT || t.kind() == Kind.NONE ? t : Target.UNKNOWN);
        }
        return out;
    }

    /**
     * 이름 하나가 가리키는 것. 이벤트 처리기 속성 안의 {@code form}은 그 요소의 폼이고, 함수의 매개변수면 그 함수를 부르는 자리의
     * 인자를, 변수면 대입한 값을 본다. 그런 것이 없고 페이지에 그 이름·ID의 폼이 있으면 그 폼이다(전역 이름으로 폼에 닿는 구형 코드).
     */
    private List<Target> identifier(String name, int at, int depth) {
        if (depth > MAX_DEPTH) {
            return List.of(Target.UNKNOWN);
        }
        if (name.equals("form") && !inScript(at)) {
            return List.of(new Target(Kind.AT, null, at));
        }
        Function owner = null;
        for (Function f : functions) {
            if (f.start() <= at && at < f.end() && f.params().contains(name) && (owner == null || f.start() > owner.start())) {
                owner = f;
            }
        }
        if (owner != null) {
            return parameter(owner, owner.params().indexOf(name), depth);
        }
        List<Target> assigned = new ArrayList<>();
        Matcher assign = Pattern.compile("(?<![\\w$.])" + Pattern.quote(name) + "\\s*=(?!=)").matcher(text);
        while (assign.find() && assigned.size() < MAX_TARGETS) {
            if (inScript(assign.start())) {
                String value = expressionFrom(assign.end());
                if (!value.isEmpty() && !value.equals(name)) {
                    assigned.addAll(classify(value, assign.start(), depth + 1));
                }
            }
        }
        if (!assigned.isEmpty()) {
            return assigned;
        }
        for (Form form : forms) {
            if (name.equals(form.name()) || name.equals(form.id())) {
                return List.of(new Target(Kind.NAME_OR_ID, name, -1));
            }
        }
        return List.of(Target.UNKNOWN);
    }

    /** 함수의 매개변수가 받는 것: 그 함수를 이름으로 부르는 자리의 인자들. 검증 플러그인의 submitHandler는 그 폼이다. */
    private List<Target> parameter(Function fn, int index, int depth) {
        if (fn.key() && "submitHandler".equals(fn.name())) {
            String receiver = validateReceiver(fn.start());
            return receiver == null ? List.of(Target.UNKNOWN) : classify(receiver, fn.start(), depth + 1);
        }
        if (fn.name() == null || fn.key()) {
            return List.of(Target.UNKNOWN);
        }
        List<Target> out = new ArrayList<>();
        Matcher call = Pattern.compile("(?<![\\w$.])" + Pattern.quote(fn.name()) + "\\s*\\(").matcher(text);
        while (call.find() && out.size() < MAX_TARGETS) {
            if (call.start() == fn.start() || text.substring(Math.max(0, call.start() - 12), call.start()).matches("(?s).*\\bfunction\\s+")) {
                continue; // 선언 자신
            }
            int open = call.end() - 1;
            int close = matchingClose(text, open);
            if (close < 0) {
                continue;
            }
            List<String> arguments = splitArguments(text.substring(open + 1, close));
            if (index < arguments.size()) {
                out.addAll(classify(arguments.get(index), call.start(), depth + 1));
            } else {
                out.add(Target.UNKNOWN);
            }
        }
        return out.isEmpty() ? List.of(Target.UNKNOWN) : out;
    }

    /** {@code $(X).validate({ … submitHandler: function(form){…} })}에서 X를 고른 식 {@code $(X)}. 그 꼴이 아니면 null. */
    private String validateReceiver(int functionStart) {
        Matcher validate = Pattern.compile("\\.\\s*validate\\s*\\(").matcher(text);
        int dot = -1;
        while (validate.find() && validate.start() < functionStart) {
            int close = matchingClose(text, validate.end() - 1);
            if (close < 0 || close > functionStart) {
                dot = validate.start(); // 이 호출의 괄호가 함수 뒤에서 닫힌다: 함수가 그 인자 안에 있다
            }
        }
        if (dot < 0) {
            return null;
        }
        String receiver = receiverBefore(dot);
        return receiver.isEmpty() ? null : receiver;
    }

    private static List<String> splitArguments(String arguments) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int i = 0; i < arguments.length(); i++) {
            char c = arguments.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipString(arguments, i);
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(arguments.substring(from, i).trim());
                from = i + 1;
            }
        }
        String last = arguments.substring(from).trim();
        if (!last.isEmpty() || !out.isEmpty()) {
            out.add(last);
        }
        return out;
    }

    /** 대입의 오른쪽 식: 문장 끝({@code ;}, 줄 끝, 묶음 밖의 {@code ,}·닫는 괄호)까지. */
    private String expressionFrom(int from) {
        int depth = 0;
        int i = from;
        for (; i < text.length() && i - from < 400; i++) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipString(text, i);
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (--depth < 0) {
                    break;
                }
            } else if (depth == 0 && (c == ';' || c == ',' || c == '\n')) {
                break;
            }
        }
        return text.substring(from, Math.min(i, text.length())).trim();
    }

    // ---- 함수와 구간 ----

    private void collectFunctions() {
        Matcher m = FUNCTION.matcher(text);
        while (m.find()) {
            if (!inScript(m.start())) {
                continue;
            }
            int open = m.end() - 1;
            int close = matchingClose(text, open);
            int end = close < 0 ? scriptEnd(open) : close + 1;
            String parameters = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
            List<String> params = new ArrayList<>();
            for (String p : parameters.split(",")) {
                String param = p.trim();
                int assign = param.indexOf('=');
                params.add(assign < 0 ? param : param.substring(0, assign).trim());
            }
            String name = m.group(1) == null || m.group(1).isEmpty() ? null : m.group(1);
            boolean key = false;
            if (name == null) {
                int lineStart = Math.max(text.lastIndexOf('\n', m.start()) + 1, m.start() - 200);
                Matcher before = NAMED_BEFORE.matcher(text.substring(lineStart, m.start()));
                if (before.find()) {
                    name = before.group(1);
                    key = before.group(2).equals(":");
                }
            }
            functions.add(new Function(name, key, params, m.start(), open, end));
        }
    }

    private boolean inScript(int offset) {
        for (int[] range : scripts) {
            if (offset >= range[0] && offset < range[1]) {
                return true;
            }
        }
        return false;
    }

    private int scriptEnd(int offset) {
        for (int[] range : scripts) {
            if (offset >= range[0] && offset < range[1]) {
                return range[1];
            }
        }
        return text.length();
    }

    /**
     * 주석을 같은 길이의 공백으로 지운 본문(오프셋이 그대로다). HTML·JSP 주석은 통째로, 스크립트 안에서는 JS 주석({@code //},
     * 블록 주석)과 JSP 주석을 지운다. 스크립트 전체를 감싼 {@code <!--}·{@code -->}는 주석이 아니다.
     */
    private static String mask(String text, List<int[]> scripts, List<int[]> comments) {
        char[] chars = text.toCharArray();
        for (int[] range : comments) {
            blank(chars, range[0], range[1]);
        }
        for (int[] range : scripts) {
            int end = Math.min(range[1], chars.length);
            for (int i = range[0]; i < end; i++) {
                char c = chars[i];
                if (c == '\'' || c == '"' || c == '`') {
                    i = Math.min(skipString(text, i), end - 1);
                } else if (c == '/' && i + 1 < end && chars[i + 1] == '*') {
                    int close = text.indexOf("*/", i + 2);
                    int to = close < 0 || close + 2 > end ? end : close + 2;
                    blank(chars, i, to);
                    i = to - 1;
                } else if (c == '/' && i + 1 < end && chars[i + 1] == '/' && startsComment(chars, i, range[0])) {
                    int to = text.indexOf('\n', i);
                    to = to < 0 || to > end ? end : to;
                    blank(chars, i, to);
                    i = to - 1;
                } else if (c == '<' && text.startsWith("<%--", i)) {
                    int close = text.indexOf("--%>", i + 4);
                    int to = close < 0 || close + 4 > end ? end : close + 4;
                    blank(chars, i, to);
                    i = to - 1;
                }
            }
        }
        return new String(chars);
    }

    /** {@code //}가 주석의 시작인가. URL의 {@code //}(앞이 글자나 {@code :})와 가린다. */
    private static boolean startsComment(char[] chars, int at, int from) {
        if (at == from) {
            return true;
        }
        char before = chars[at - 1];
        return Character.isWhitespace(before) || before == ';' || before == '{' || before == '}' || before == ')' || before == ',';
    }

    private static void blank(char[] chars, int from, int to) {
        for (int i = Math.max(0, from); i < to && i < chars.length; i++) {
            if (chars[i] != '\n') {
                chars[i] = ' ';
            }
        }
    }
}
