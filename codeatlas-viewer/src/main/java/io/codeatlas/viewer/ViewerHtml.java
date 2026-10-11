package io.codeatlas.viewer;

import java.io.BufferedReader;
import java.io.FilterWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * viewer.html 조립. 클래스패스의 {@code template.html}에서 자리 표시를 찾아 스타일, 스크립트, 그래프 JSON, Mermaid를 끼워 넣는다.
 *
 * <p>자산 목록과 순서는 {@code assets.txt}에 있다. 그래프와 Mermaid는 큰 문자열을 만들지 않고 흘려 쓴다.
 * HTML 안에서 안전하게 하는 규칙은 두 가지다.
 * <ul>
 * <li>JSON 블록({@code <script type="application/json">}): {@code <}를 {@code <}로 쓴다. JSON에서 {@code <}는 문자열 안에만
 *     나오므로 값은 그대로이고, {@code </script>}나 {@code <!--}가 문서 구조에 닿지 않는다.</li>
 * <li>Mermaid: 소스 전체를 JSON 문자열 하나로 넣고 뷰어가 처음 다이어그램을 그릴 때 실행한다.</li>
 * </ul>
 */
final class ViewerHtml {

    static final String CSS_MARKER = "/*@@CSS@@*/";
    static final String JS_MARKER = "/*@@JS@@*/";
    static final String DATA_MARKER = "@@DATA@@";
    static final String MERMAID_MARKER = "@@MERMAID@@";

    private static final String MERMAID_RESOURCE = "vendor/mermaid.min.js";
    private static final Pattern SCRIPT_END = Pattern.compile("</(?=script)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_END = Pattern.compile("</(?=style)", Pattern.CASE_INSENSITIVE);

    /** 그래프 JSON을 writer에 쓰는 쪽. */
    @FunctionalInterface
    interface JsonSource {
        void writeTo(Writer json) throws IOException;
    }

    private record Slot(String marker, int at) {
    }

    private ViewerHtml() {
    }

    static void write(Writer out, JsonSource graphJson) throws IOException {
        String template = readText("template.html");
        List<String> assets = assetList();

        // 자리 표시는 템플릿에서만 찾는다. 끼워 넣는 내용에 같은 글자가 있어도 건드리지 않는다.
        List<Slot> slots = new ArrayList<>();
        for (String marker : List.of(CSS_MARKER, JS_MARKER, DATA_MARKER, MERMAID_MARKER)) {
            int at = template.indexOf(marker);
            if (at < 0) {
                throw new IOException("template.html에 자리 표시가 없음: " + marker);
            }
            slots.add(new Slot(marker, at));
        }
        slots.sort(Comparator.comparingInt(Slot::at));

        int pos = 0;
        for (Slot slot : slots) {
            out.write(template, pos, slot.at() - pos);
            switch (slot.marker()) {
                case CSS_MARKER -> writeAssets(out, assets, ".css", STYLE_END);
                case JS_MARKER -> writeAssets(out, assets, ".js", SCRIPT_END);
                case DATA_MARKER -> {
                    Writer safe = new HtmlSafeJsonWriter(out);
                    graphJson.writeTo(safe);
                    safe.flush();
                }
                default -> writeMermaid(out);
            }
            pos = slot.at() + slot.marker().length();
        }
        out.write(template, pos, template.length() - pos);
        out.flush();
    }

    /** 확장자가 맞는 자산을 assets.txt 순서대로 잇는다. 종료 태그로 읽힐 글자가 있으면 {@code <\/}로 바꾼다(JS·CSS 문자열 안에서는 같은 뜻). */
    private static void writeAssets(Writer out, List<String> assets, String extension, Pattern endTag) throws IOException {
        for (String asset : assets) {
            if (!asset.endsWith(extension)) {
                continue;
            }
            out.write("/* ---- " + asset + " ---- */\n");
            out.write(endTag.matcher(readText(asset)).replaceAll("<\\\\/"));
            out.write("\n");
        }
    }

    /** Mermaid 번들을 JSON 문자열 하나로 쓴다. 빌드가 번들을 넣지 않았으면 비워 두고, 뷰어는 그림 대신 Mermaid 소스를 보여 준다. */
    private static void writeMermaid(Writer out) throws IOException {
        try (InputStream in = ViewerHtml.class.getResourceAsStream(MERMAID_RESOURCE)) {
            if (in == null) {
                return;
            }
            Reader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            out.write('"');
            char[] buffer = new char[8192];
            StringBuilder escaped = new StringBuilder(9000);
            for (int n = reader.read(buffer); n >= 0; n = reader.read(buffer)) {
                escaped.setLength(0);
                for (int i = 0; i < n; i++) {
                    appendJsonChar(escaped, buffer[i]);
                }
                out.append(escaped);
            }
            out.write('"');
        }
    }

    private static void appendJsonChar(StringBuilder sb, char c) {
        switch (c) {
            case '"' -> sb.append("\\\"");
            case '\\' -> sb.append("\\\\");
            case '\n' -> sb.append("\\n");
            case '\r' -> sb.append("\\r");
            case '\t' -> sb.append("\\t");
            case '<' -> sb.append("\\u003c");
            default -> {
                if (c < 0x20) {
                    sb.append(String.format("\\u%04x", (int) c));
                } else {
                    sb.append(c);
                }
            }
        }
    }

    private static List<String> assetList() throws IOException {
        List<String> assets = new ArrayList<>();
        for (String line : readText("assets.txt").split("\\R")) {
            String name = line.strip();
            if (!name.isEmpty() && !name.startsWith("#")) {
                assets.add(name);
            }
        }
        return assets;
    }

    private static String readText(String resource) throws IOException {
        try (InputStream in = ViewerHtml.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("뷰어 자산이 클래스패스에 없음: io/codeatlas/viewer/" + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** JSON을 HTML의 script 블록 안에 넣을 수 있게 {@code <}를 {@code <}로 바꿔 쓴다. 감싼 writer는 닫지 않는다. */
    private static final class HtmlSafeJsonWriter extends FilterWriter {

        HtmlSafeJsonWriter(Writer out) {
            super(out);
        }

        @Override
        public void write(int c) throws IOException {
            if (c == '<') {
                out.write("\\u003c");
            } else {
                out.write(c);
            }
        }

        @Override
        public void write(char[] buffer, int off, int len) throws IOException {
            int start = off;
            int end = off + len;
            for (int i = off; i < end; i++) {
                if (buffer[i] == '<') {
                    out.write(buffer, start, i - start);
                    out.write("\\u003c");
                    start = i + 1;
                }
            }
            out.write(buffer, start, end - start);
        }

        @Override
        public void write(String text, int off, int len) throws IOException {
            int start = off;
            int end = off + len;
            for (int i = off; i < end; i++) {
                if (text.charAt(i) == '<') {
                    out.write(text, start, i - start);
                    out.write("\\u003c");
                    start = i + 1;
                }
            }
            out.write(text, start, end - start);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
