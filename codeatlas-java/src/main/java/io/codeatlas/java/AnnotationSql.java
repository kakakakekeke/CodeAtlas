package io.codeatlas.java;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code @Select} 등 애너테이션에 적힌 MyBatis SQL을 SQL 분석기가 읽을 수 있는 본문으로 바꾼다.
 * {@code #{..}}는 {@code ?}, {@code ${..}}는 {@code __DYNAMIC__}로 바꾸고, {@code <script>} 본문은 동적 태그를 걷어 낸다
 * (모든 분기를 이어 붙인다. {@code <where>}/{@code <set>}/{@code <trim>}/{@code <foreach>}는 키워드와 여닫는 문자로 바꾼다).
 */
final class AnnotationSql {

    /** 바꾼 결과. */
    record Result(String sql, boolean dynamic) {
    }

    private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z][\\w.-]*)((?:\"[^\"]*\"|'[^']*'|[^>\"'])*?)(/?)>");
    private static final Pattern ATTR = Pattern.compile("([\\w.-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern CDATA = Pattern.compile("<!\\[CDATA\\[(.*?)]]>", Pattern.DOTALL);
    private static final Pattern DOLLAR = Pattern.compile("\\$\\{[^}]*}");
    private static final Pattern HASH = Pattern.compile("#\\{[^}]*}");
    private static final Pattern LEADING_AND_OR = Pattern.compile("^(?i)(and|or)\\b\\s*");

    private AnnotationSql() {
    }

    static Result transform(String raw) {
        String text = raw;
        boolean dynamic = text.toLowerCase(Locale.ROOT).contains("<script");
        if (dynamic) {
            text = decodeEntities(stripTags(text));
        }
        text = DOLLAR.matcher(text).replaceAll("__DYNAMIC__");
        text = HASH.matcher(text).replaceAll("?");
        return new Result(text.trim(), dynamic);
    }

    private static final class Frame {
        final String tag;
        final Map<String, String> attrs;
        final StringBuilder body = new StringBuilder();

        Frame(String tag, Map<String, String> attrs) {
            this.tag = tag;
            this.attrs = attrs;
        }
    }

    private static String stripTags(String script) {
        String text = script.replaceAll("(?s)<!--.*?-->", " ");
        Matcher cdata = CDATA.matcher(text);
        StringBuilder protectedText = new StringBuilder();
        while (cdata.find()) {
            String inner = cdata.group(1).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            cdata.appendReplacement(protectedText, Matcher.quoteReplacement(inner));
        }
        cdata.appendTail(protectedText);
        text = protectedText.toString();

        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame("", Map.of()));
        Matcher m = TAG.matcher(text);
        int last = 0;
        while (m.find()) {
            stack.peek().body.append(text, last, m.start());
            last = m.end();
            String tag = m.group(2).toLowerCase(Locale.ROOT);
            boolean closing = !m.group(1).isEmpty();
            boolean selfClosing = !m.group(4).isEmpty();
            if (closing) {
                if (stack.size() > 1) {
                    Frame done = stack.pop();
                    stack.peek().body.append(render(done));
                }
            } else if (!selfClosing) {
                Map<String, String> attrs = new HashMap<>();
                Matcher a = ATTR.matcher(m.group(3));
                while (a.find()) {
                    attrs.put(a.group(1).toLowerCase(Locale.ROOT), a.group(2) != null ? a.group(2) : a.group(3));
                }
                stack.push(new Frame(tag, attrs));
            } else {
                stack.peek().body.append(' ');
            }
        }
        stack.peek().body.append(text.substring(last));
        while (stack.size() > 1) {
            Frame open = stack.pop();
            stack.peek().body.append(render(open));
        }
        return stack.peek().body.toString();
    }

    private static String render(Frame frame) {
        String body = frame.body.toString();
        switch (frame.tag) {
            case "where": {
                String b = LEADING_AND_OR.matcher(body.trim()).replaceFirst("");
                return b.isEmpty() ? " " : " WHERE " + b + " ";
            }
            case "set": {
                String b = body.trim();
                if (b.endsWith(",")) {
                    b = b.substring(0, b.length() - 1);
                }
                return b.isEmpty() ? " " : " SET " + b + " ";
            }
            case "trim": {
                String b = body.trim();
                for (String override : frame.attrs.getOrDefault("prefixoverrides", "").split("\\|")) {
                    String o = override.trim();
                    if (!o.isEmpty() && b.toLowerCase(Locale.ROOT).startsWith(o.toLowerCase(Locale.ROOT))) {
                        b = b.substring(o.length()).trim();
                        break;
                    }
                }
                for (String override : frame.attrs.getOrDefault("suffixoverrides", "").split("\\|")) {
                    String o = override.trim();
                    if (!o.isEmpty() && b.toLowerCase(Locale.ROOT).endsWith(o.toLowerCase(Locale.ROOT))) {
                        b = b.substring(0, b.length() - o.length()).trim();
                        break;
                    }
                }
                return b.isEmpty() ? " "
                        : " " + frame.attrs.getOrDefault("prefix", "") + " " + b + " " + frame.attrs.getOrDefault("suffix", "") + " ";
            }
            case "foreach":
                return " " + frame.attrs.getOrDefault("open", "") + " " + body.trim() + " " + frame.attrs.getOrDefault("close", "") + " ";
            case "bind", "selectkey":
                return " ";
            default:
                return " " + body + " ";
        }
    }

    private static String decodeEntities(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }
}
