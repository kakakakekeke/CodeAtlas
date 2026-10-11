package io.codeatlas.web.syntax;

import java.util.EnumMap;
import java.util.Map;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSPoint;
import org.treesitter.TSTree;
import org.treesitter.TSTreeCursor;
import org.treesitter.TreeSitterJavascript;
import org.treesitter.TreeSitterTsx;
import org.treesitter.TreeSitterTypescript;

/**
 * tree-sitter(tree-sitter-ng 바인딩)로 파싱한다. 문법은 javascript(JSX 포함), typescript, tsx.
 *
 * <p>네이티브 라이브러리는 바인딩 jar의 {@code lib/} 리소스에 들어 있고, 처음 쓸 때 임시 파일로 풀어
 * {@code System.load}로 올린다. 네트워크를 쓰지 않으며 shade한 단일 jar에서도 같은 방식으로 동작한다.
 *
 * <p>바인딩은 소스를 UTF-8로 넘기므로 tree-sitter가 주는 오프셋과 열은 바이트 단위다. 이 클래스가 Java 문자열의
 * 문자 오프셋과 열로 바꾼다. 트리는 {@link TreeNode}로 한 번 복사하고 네이티브 트리는 바로 닫는다.
 *
 * <p>한 인스턴스는 한 스레드에서만 쓴다.
 */
public final class TreeSitterJsParser implements JsParser {

    private final Map<JsDialect, TSLanguage> languages = new EnumMap<>(JsDialect.class);

    @Override
    public SyntaxTree parse(String source, JsDialect dialect) {
        String text = source == null ? "" : source;
        TSLanguage language = language(dialect);
        try (TSParser parser = new TSParser()) {
            parser.setLanguage(language);
            String input = sanitize(text);
            try (TSTree tree = parser.parseString(null, input)) {
                return copy(tree.getRootNode(), new Offsets(input, text));
            }
        } catch (LinkageError e) {
            throw unavailable(e);
        }
    }

    /**
     * 파서에 넘길 입력을 만든다. 모든 치환은 <b>길이를 보존</b>하므로 위치가 어긋나지 않고, 노드의 원문({@link SyntaxNode#text()})은
     * 원래 소스에서 읽으므로 이름과 문자열 값도 바뀌지 않는다. 바뀌는 것은 파서가 보는 글자뿐이다.
     *
     * <ul>
     *   <li>바인딩이 받지 못하는 문자: 짝 없는 서로게이트는 UTF-8로 쓸 수 없어 U+FFFD로, NUL은 공백으로.</li>
     *   <li>CR 단독(뒤에 LF가 없는 {@code \r}): 줄 끝이다. 문법과 위치 계산이 LF만 줄 끝으로 세므로 LF로 보인다. 그대로 두면
     *       파일 전체가 한 줄이 되어 줄 번호가 모두 1이고 줄 끝에 기대는 문장(세미콜론 없는 문장, 한 줄 주석)이 깨진다(F-010-R29).</li>
     *   <li>식별자로 쓴 {@code using}: 문법이 {@code using} 선언의 키워드로 읽어 {@code using = function(){}}에서 구문 오류를 낸다.
     *       바로 뒤에 이름이 오는 진짜 선언({@code using x = …})이 아니면 파서에는 {@code usinq}로 보인다.</li>
     *   <li>줄 맨 앞의 HTML 주석 열기 {@code <!--}와 닫기 {@code -->}: 스크립트에서는 한 줄 주석이다. 문법이 함수 선언 뒤의
     *       {@code <!--}를 받지 못하므로 {@code //--}, {@code //>}로 보인다.</li>
     *   <li>홑 {@code &}: JSX 텍스트와 JSX 문자열 속성 안의 {@code &}(엔티티가 아닌 것, 예: {@code R&D})를 문법이 받지 못한다.
     *       {@code &&}, {@code &=}, 엔티티({@code &amp;})가 아닌 {@code &}는 {@code |}로 보인다. 식에서는 둘 다 이항 연산자라
     *       트리 모양이 같고, 분석은 두 연산자를 구분해 쓰지 않는다.</li>
     * </ul>
     */
    static String sanitize(String text) {
        StringBuilder sb = null;
        boolean lineStart = true;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                lineStart = true;
                continue;
            }
            if (c == '\r' && (i + 1 >= length || text.charAt(i + 1) != '\n')) {
                // CR 단독도 줄 끝이다(옛 Mac 형식). 문법은 LF만 줄 끝으로 세므로 LF로 보이게 한다
                sb = builder(sb, text);
                sb.setCharAt(i, '\n');
                lineStart = true;
                continue;
            }
            boolean atLineStart = lineStart;
            if (c != ' ' && c != '\t' && c != '\r' && c != '\uFEFF') {
                lineStart = false;
            }
            char replacement = c;
            int at = i;
            if (c == 0) {
                replacement = ' ';
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 < length && Character.isLowSurrogate(text.charAt(i + 1))) {
                    i++;
                    continue;
                }
                replacement = '\uFFFD';
            } else if (Character.isLowSurrogate(c)) {
                replacement = '\uFFFD';
            } else if (c == '<' && atLineStart && text.startsWith("<!--", i)) {
                sb = builder(sb, text);
                sb.setCharAt(i, '/');
                sb.setCharAt(i + 1, '/');
                i += 3;
                continue;
            } else if (c == '-' && atLineStart && text.startsWith("-->", i)) {
                sb = builder(sb, text);
                sb.setCharAt(i, '/');
                sb.setCharAt(i + 1, '/');
                i += 2;
                continue;
            } else if (c == '&') {
                if (loneAmpersand(text, i)) {
                    replacement = '|';
                }
            } else if (c == 'u' && text.startsWith("using", i) && usingAsIdentifier(text, i)) {
                at = i + 4;
                replacement = 'q';
                i += 4;
            }
            if (replacement != text.charAt(at)) {
                sb = builder(sb, text);
                sb.setCharAt(at, replacement);
            }
        }
        return sb == null ? text : sb.toString();
    }

    private static StringBuilder builder(StringBuilder sb, String text) {
        return sb == null ? new StringBuilder(text) : sb;
    }

    /** {@code &&}, {@code &=}, 엔티티({@code &amp;}, {@code &#39;})의 일부가 아닌 {@code &}인가. */
    private static boolean loneAmpersand(String text, int i) {
        char previous = i > 0 ? text.charAt(i - 1) : ' ';
        char next = i + 1 < text.length() ? text.charAt(i + 1) : ' ';
        if (previous == '&' || next == '&' || next == '=') {
            return false;
        }
        int limit = Math.min(text.length(), i + 12);
        for (int k = i + 1; k < limit; k++) {
            char c = text.charAt(k);
            if (c == ';') {
                return k == i + 1;
            }
            if (!(c == '#' && k == i + 1) && !isAsciiLetterOrDigit(c)) {
                return true;
            }
        }
        return true;
    }

    private static boolean isAsciiLetterOrDigit(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /** i에서 시작하는 {@code using}이 낱말 하나이고, 바로 뒤에 이름이 오는 using 선언이 아닌가. */
    private static boolean usingAsIdentifier(String text, int i) {
        if (i > 0 && isIdentifierPart(text.charAt(i - 1))) {
            return false;
        }
        int k = i + 5;
        if (k < text.length() && isIdentifierPart(text.charAt(k))) {
            return false;
        }
        while (k < text.length() && (text.charAt(k) == ' ' || text.charAt(k) == '\t')) {
            k++;
        }
        return k >= text.length() || !isIdentifierPart(text.charAt(k));
    }

    private static boolean isIdentifierPart(char c) {
        return c == '_' || c == '$' || Character.isLetterOrDigit(c);
    }

    /** UTF-8 바이트 오프셋을 문자 오프셋과 줄·열로 바꾼다. */
    private static final class Offsets {
        final String original;
        /** 바이트 오프셋 → 문자 오프셋. 전부 ASCII면 null(같은 값). */
        final int[] byteToChar;
        /** 줄 번호(0부터) → 그 줄이 시작하는 문자 오프셋. */
        final int[] lineStarts;

        Offsets(String parsed, String original) {
            this.original = original;
            int length = parsed.length();
            int lines = 1;
            int bytes = 0;
            for (int i = 0; i < length; i++) {
                char c = parsed.charAt(i);
                if (c == '\n') {
                    lines++;
                }
                bytes += c < 0x80 ? 1 : c < 0x800 ? 2 : Character.isSurrogate(c) ? 2 : 3;
            }
            int[] starts = new int[lines];
            int[] map = bytes == length ? null : new int[bytes + 1];
            int line = 0;
            int b = 0;
            for (int i = 0; i < length; i++) {
                char c = parsed.charAt(i);
                if (map != null) {
                    int n = c < 0x80 ? 1 : c < 0x800 ? 2 : Character.isSurrogate(c) ? 2 : 3;
                    for (int k = 0; k < n; k++) {
                        map[b++] = i;
                    }
                }
                if (c == '\n') {
                    starts[++line] = i + 1;
                }
            }
            if (map != null) {
                map[bytes] = length;
            }
            this.byteToChar = map;
            this.lineStarts = starts;
        }

        int charOffset(int byteOffset) {
            if (byteToChar == null) {
                return Math.min(byteOffset, original.length());
            }
            return byteToChar[Math.max(0, Math.min(byteOffset, byteToChar.length - 1))];
        }

        /** 문자 오프셋의 열(1부터). */
        int column(int row, int charOffset) {
            int start = row < lineStarts.length ? lineStarts[row] : 0;
            return Math.max(charOffset - start, 0) + 1;
        }
    }

    private TSLanguage language(JsDialect dialect) {
        TSLanguage language = languages.get(dialect);
        if (language == null) {
            try {
                language = switch (dialect) {
                    case JAVASCRIPT -> new TreeSitterJavascript();
                    case TYPESCRIPT -> new TreeSitterTypescript();
                    case TSX -> new TreeSitterTsx();
                };
            } catch (LinkageError | RuntimeException e) {
                throw unavailable(e);
            }
            languages.put(dialect, language);
        }
        return language;
    }

    private static ParserUnavailableException unavailable(Throwable cause) {
        return new ParserUnavailableException("tree-sitter 네이티브 라이브러리를 올리지 못했다 (os="
                + System.getProperty("os.name") + ", arch=" + System.getProperty("os.arch") + "): " + cause, cause);
    }

    /** 커서로 트리를 훑어 복사한다. 깊은 트리에서도 스택이 넘치지 않게 재귀를 쓰지 않는다. */
    private static SyntaxTree copy(TSNode rootNode, Offsets offsets) {
        TreeNode firstError = null;
        TreeNode root = null;
        TreeNode current = null;
        try (TSTreeCursor cursor = new TSTreeCursor(rootNode)) {
            boolean descend = true;
            while (true) {
                if (descend) {
                    TreeNode copied = copyNode(cursor.currentNode(), cursor.currentFieldName(), offsets, current);
                    if (current == null) {
                        root = copied;
                    } else {
                        current.addChild(copied);
                    }
                    if (firstError == null && (copied.isError() || copied.isMissing())) {
                        firstError = copied;
                    }
                    current = copied;
                    if (cursor.gotoFirstChild()) {
                        continue;
                    }
                }
                // 지금 노드는 끝났다. 형제로 가거나 부모로 올라간다.
                if (cursor.gotoNextSibling()) {
                    current = (TreeNode) current.parent();
                    descend = true;
                } else if (cursor.gotoParent()) {
                    current = (TreeNode) current.parent();
                    descend = false;
                    if (current == null) {
                        break;
                    }
                } else {
                    break;
                }
            }
        }
        return new SyntaxTree(root, firstError);
    }

    private static TreeNode copyNode(TSNode node, String fieldName, Offsets offsets, TreeNode parent) {
        TSPoint start = node.getStartPoint();
        TSPoint end = node.getEndPoint();
        int startOffset = offsets.charOffset(node.getStartByte());
        int endOffset = offsets.charOffset(node.getEndByte());
        // 끝 위치는 "제외"(마지막 문자 다음)다. tree-sitter의 end point를 1부터 세는 값으로만 바꾼다.
        int endColumn = offsets.column(end.getRow(), endOffset);
        return new TreeNode(offsets.original, node.getType(), fieldName, node.isNamed(), node.isError(), node.isMissing(),
                startOffset, endOffset,
                start.getRow() + 1, offsets.column(start.getRow(), startOffset),
                end.getRow() + 1, endColumn, parent);
    }
}
