package io.codeatlas.viewer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * viewer.html 조립. 클래스패스의 {@code template.html}에서 자리 표시를 찾아 스타일, 스크립트, 그래프 데이터, Mermaid를 끼워 넣는다.
 *
 * <p>자산 목록과 순서는 {@code assets.txt}에 있다. 그래프 데이터와 Mermaid는 gzip으로 압축하고 base64로 감싸 내장 블록
 * ({@code <script type="text/plain">})에 넣는다(F-016-R48). 큰 문자열을 만들지 않고 흘려 쓴다. base64 글자에는 HTML이 해석할
 * 글자({@code <})가 없어서 데이터에 {@code </script>}나 {@code <!--}가 있어도 문서 구조에 닿지 않는다. 뷰어는 브라우저의
 * {@code DecompressionStream}으로 블록을 푼다({@code js/core/embed.js}).
 */
final class ViewerHtml {

    static final String CSS_MARKER = "/*@@CSS@@*/";
    static final String JS_MARKER = "/*@@JS@@*/";
    static final String DATA_MARKER = "@@DATA@@";
    static final String MERMAID_MARKER = "@@MERMAID@@";

    private static final String MERMAID_RESOURCE = "vendor/mermaid.min.js";
    private static final Pattern SCRIPT_END = Pattern.compile("</(?=script)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_END = Pattern.compile("</(?=style)", Pattern.CASE_INSENSITIVE);

    /** 내장할 데이터(UTF-8 바이트)를 스트림에 쓰는 쪽. 스트림은 닫지 않는다. */
    @FunctionalInterface
    interface DataSource {
        void writeTo(OutputStream data) throws IOException;
    }

    private record Slot(String marker, int at) {
    }

    private ViewerHtml() {
    }

    static void write(Writer out, DataSource graphData) throws IOException {
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
                case DATA_MARKER -> writePacked(out, graphData);
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

    /** Mermaid 번들을 압축해 쓴다. 빌드가 번들을 넣지 않았으면 비워 두고, 뷰어는 그림 대신 Mermaid 소스를 보여 준다. */
    private static void writeMermaid(Writer out) throws IOException {
        try (InputStream in = ViewerHtml.class.getResourceAsStream(MERMAID_RESOURCE)) {
            if (in != null) {
                writePacked(out, in::transferTo);
            }
        }
    }

    /** 데이터를 gzip으로 압축하고 base64 글자로 쓴다. out은 닫지 않는다. */
    private static void writePacked(Writer out, DataSource source) throws IOException {
        OutputStream base64 = Base64.getEncoder().wrap(new AsciiOutput(out));
        GZIPOutputStream gzip = new GZIPOutputStream(base64, 1 << 16);
        source.writeTo(gzip);
        gzip.finish();
        // 남은 바이트와 채움 글자(=)까지 쓴다. AsciiOutput은 닫아도 out을 닫지 않는다.
        base64.close();
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

    /** base64 인코더가 내놓는 ASCII 바이트를 글자로 옮겨 writer에 쓴다. 감싼 writer는 닫지 않는다. */
    private static final class AsciiOutput extends OutputStream {

        private final Writer out;
        private final char[] chars = new char[8192];

        AsciiOutput(Writer out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b & 0x7f);
        }

        @Override
        public void write(byte[] buffer, int off, int len) throws IOException {
            int end = off + len;
            for (int at = off; at < end; ) {
                int n = Math.min(chars.length, end - at);
                for (int i = 0; i < n; i++) {
                    chars[i] = (char) (buffer[at + i] & 0x7f);
                }
                out.write(chars, 0, n);
                at += n;
            }
        }

        @Override
        public void close() {
            // 감싼 writer는 ViewerHtml.write가 이어서 쓴다.
        }
    }
}
