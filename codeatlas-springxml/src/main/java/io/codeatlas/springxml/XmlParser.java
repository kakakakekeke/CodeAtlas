package io.codeatlas.springxml;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.stream.Location;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * 위치 정보를 주는 XML 파서(StAX). 폐쇄망에서 돌므로 외부 DTD·XSD·엔티티를 읽지 않는다.
 *
 * <p>StAX가 알려 주는 위치는 시작 태그가 끝난 자리라서, 여러 줄에 걸친 태그의 시작 줄을 알 수 없다.
 * 그래서 본문을 한 번 훑어 시작 태그의 '&lt;' 위치와 속성 이름의 위치를 문서 순서대로 구해 두고
 * n번째 START_ELEMENT에 n번째 위치를 붙인다.
 *
 * <p>줄 끝은 LF, CRLF, 홀로 있는 CR 셋 다 한 줄이다(F-017-R17). StAX는 줄은 맞게 세지만 홀로 있는 CR로 끝난 줄 다음의 열을
 * 하나 적게 줄 때가 있어, 끝 태그의 '&gt;' 위치도 같은 훑기에서 구해 줄이 같을 때 그 열을 쓴다.
 */
final class XmlParser {

    /** 파싱 실패. 위치를 알면 줄 번호가 들어 있다. */
    static final class XmlParseException extends Exception {
        private static final long serialVersionUID = 1L;
        final int line;
        final int column;

        XmlParseException(String message, int line, int column, Throwable cause) {
            super(message, cause);
            this.line = line;
            this.column = column;
        }
    }

    private XmlParser() {
    }

    static XmlElement parse(String text) throws XmlParseException {
        List<TagPosition> tagPositions = new ArrayList<>();
        List<int[]> endPositions = new ArrayList<>();
        scanTags(text, tagPositions, endPositions);
        int endIndex = 0;
        XMLStreamReader reader = null;
        try {
            reader = factory().createXMLStreamReader(new StringReader(text));
            Deque<XmlElement> stack = new ArrayDeque<>();
            XmlElement root = null;
            int tagIndex = 0;
            while (reader.hasNext()) {
                int event = reader.next();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        int line;
                        int column;
                        Map<String, int[]> attributePositions = Map.of();
                        if (tagIndex < tagPositions.size()) {
                            line = tagPositions.get(tagIndex).line();
                            column = tagPositions.get(tagIndex).column();
                            attributePositions = tagPositions.get(tagIndex).attributes();
                        } else {
                            Location location = reader.getLocation();
                            line = Math.max(location.getLineNumber(), 0);
                            column = 0;
                        }
                        tagIndex++;
                        XmlElement element = new XmlElement(reader.getNamespaceURI(), reader.getLocalName(), reader.getPrefix(), line, column);
                        element.attributePositions = attributePositions;
                        for (int i = 0; i < reader.getAttributeCount(); i++) {
                            String ns = reader.getAttributeNamespace(i);
                            String prefix = reader.getAttributePrefix(i);
                            element.attributes.add(new XmlElement.Attribute(ns == null ? "" : ns, prefix == null ? "" : prefix,
                                    reader.getAttributeLocalName(i), reader.getAttributeValue(i)));
                        }
                        if (stack.isEmpty()) {
                            root = element;
                        } else {
                            stack.peek().children.add(element);
                        }
                        stack.push(element);
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        XmlElement element = stack.pop();
                        Location location = reader.getLocation();
                        if (location.getLineNumber() > 0) {
                            element.endLine = location.getLineNumber();
                            element.endColumn = Math.max(location.getColumnNumber() - 1, 0);
                        }
                        // n번째 END_ELEMENT는 문서에서 n번째로 닫힌 자리(끝 태그 또는 빈 요소의 />)다. 직접 구한 '>'의 줄이 StAX와 같으면 그 열을 쓴다
                        int[] scanned = endIndex < endPositions.size() ? endPositions.get(endIndex) : null;
                        endIndex++;
                        if (scanned != null && scanned[0] == element.endLine) {
                            element.endColumn = scanned[1];
                        }
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                        if (!stack.isEmpty()) {
                            stack.peek().appendText(reader.getText());
                        }
                    }
                    default -> {
                        // 주석, PI, DTD 선언은 쓰지 않는다
                    }
                }
            }
            if (root == null) {
                throw new XmlParseException("루트 요소가 없다", 0, 0, null);
            }
            return root;
        } catch (XMLStreamException e) {
            Location location = e.getLocation();
            int line = location == null ? 0 : Math.max(location.getLineNumber(), 0);
            int column = location == null ? 0 : Math.max(location.getColumnNumber(), 0);
            throw new XmlParseException(firstLine(e.getMessage()), line, column, e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // 닫다가 난 오류는 결과에 영향이 없다
                }
            }
        }
    }

    /** 외부 접근을 모두 막은 팩터리. JDK 내장 구현을 쓴다(실행 jar에 다른 StAX 구현이 섞여도 같게 동작하도록). */
    private static XMLInputFactory factory() {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        // DOCTYPE은 건너뛰고 DTD를 읽지 않는다. 외부 엔티티도 풀지 않는다(XXE 차단).
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> new ByteArrayInputStream(new byte[0]));
        return factory;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "XML 파싱 실패";
        }
        // JDK 메시지는 "ParseError at [row,col]:[4,3]\nMessage: ..." 꼴이다
        int index = message.indexOf("Message:");
        String body = index >= 0 ? message.substring(index + "Message:".length()) : message;
        return body.strip().replaceAll("\\s+", " ");
    }

    /** 시작 태그 하나의 위치. line·column은 '<'의 자리(1부터), attributes는 속성 이름(접두사 포함) → 이름의 [줄, 열]. */
    record TagPosition(int line, int column, Map<String, int[]> attributes) {
    }

    /**
     * 오프셋을 [줄, 열]로 바꾼다. 오프셋은 늘어나는 순서로만 물어야 한다.
     * 줄 끝은 XML 파서(StAX)와 같이 LF, CRLF, 홀로 있는 CR이다(F-017-R17). CRLF는 LF에서 한 번만 센다.
     */
    private static final class LineCounter {
        private final String text;
        private int counted;
        private int line = 1;
        private int lineStart;

        LineCounter(String text) {
            this.text = text;
        }

        int[] position(int offset) {
            for (; counted < offset; counted++) {
                char c = text.charAt(counted);
                if (c == '\n' || (c == '\r' && (counted + 1 >= text.length() || text.charAt(counted + 1) != '\n'))) {
                    line++;
                    lineStart = counted + 1;
                }
            }
            return new int[] {line, offset - lineStart + 1};
        }
    }

    /** 시작 태그와 그 속성들의 위치를 문서 순서대로 구한다. 주석, CDATA, PI, DOCTYPE, 끝 태그는 건너뛴다. */
    static List<TagPosition> startTagPositions(String s) {
        List<TagPosition> positions = new ArrayList<>();
        scanTags(s, positions, new ArrayList<>());
        return positions;
    }

    /**
     * 본문을 한 번 훑어 시작 태그의 위치(starts)와, 요소가 닫히는 자리의 '&gt;' 위치(ends: 끝 태그 또는 빈 요소 {@code />}의 [줄, 열])를
     * 문서 순서대로 모은다.
     */
    private static void scanTags(String s, List<TagPosition> positions, List<int[]> ends) {
        LineCounter lines = new LineCounter(s);
        int n = s.length();
        int i = 0;
        while (i < n) {
            int lt = s.indexOf('<', i);
            if (lt < 0) {
                break;
            }
            if (s.startsWith("<!--", lt)) {
                i = after(s, "-->", lt + 4);
            } else if (s.startsWith("<![CDATA[", lt)) {
                i = after(s, "]]>", lt + 9);
            } else if (s.startsWith("<?", lt)) {
                i = after(s, "?>", lt + 2);
            } else if (s.startsWith("<!", lt)) {
                i = skipDeclaration(s, lt + 2);
            } else if (lt + 1 < n && s.charAt(lt + 1) == '/') {
                int gt = s.indexOf('>', lt + 2);
                if (gt < 0) {
                    break;
                }
                ends.add(lines.position(gt));
                i = gt + 1;
            } else {
                int[] start = lines.position(lt);
                Map<String, int[]> attributes = new HashMap<>();
                i = scanAttributes(s, lt + 1, lines, attributes);
                positions.add(new TagPosition(start[0], start[1], attributes));
                if (i >= 2 && i <= n && s.charAt(i - 1) == '>' && s.charAt(i - 2) == '/') {
                    ends.add(lines.position(i - 1)); // 빈 요소 <a/>: 여기서 닫힌다
                }
            }
        }
    }

    /** 시작 태그의 속성 이름 위치를 모으고 태그가 끝난 다음 자리를 돌려준다. */
    private static int scanAttributes(String s, int from, LineCounter lines, Map<String, int[]> attributes) {
        int n = s.length();
        int i = from;
        while (i < n && !Character.isWhitespace(s.charAt(i)) && s.charAt(i) != '>' && s.charAt(i) != '/') {
            i++;   // 요소 이름
        }
        while (i < n) {
            char c = s.charAt(i);
            if (c == '>') {
                return i + 1;
            }
            if (c == '<') {
                return i;   // 깨진 태그. 다음 태그부터 다시 본다
            }
            if (Character.isWhitespace(c) || c == '/') {
                i++;
                continue;
            }
            int nameStart = i;
            while (i < n && s.charAt(i) != '=' && s.charAt(i) != '>' && s.charAt(i) != '<' && !Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            String name = s.substring(nameStart, i);
            while (i < n && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            if (i >= n || s.charAt(i) != '=') {
                continue;
            }
            i++;
            while (i < n && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            if (i < n && (s.charAt(i) == '"' || s.charAt(i) == '\'')) {
                attributes.putIfAbsent(name, lines.position(nameStart));
                int close = s.indexOf(s.charAt(i), i + 1);
                i = close < 0 ? n : close + 1;
            }
        }
        return n;
    }

    private static int after(String s, String terminator, int from) {
        int end = s.indexOf(terminator, from);
        return end < 0 ? s.length() : end + terminator.length();
    }

    /** {@code <!DOCTYPE ... [ 내부 서브셋 ] >}을 건너뛴다. */
    private static int skipDeclaration(String s, int from) {
        int depth = 0;
        int n = s.length();
        int i = from;
        while (i < n) {
            char c = s.charAt(i);
            if (s.startsWith("<!--", i)) {
                i = after(s, "-->", i + 4);
                continue;
            }
            if (c == '"' || c == '\'') {
                int close = s.indexOf(c, i + 1);
                i = close < 0 ? n : close + 1;
                continue;
            }
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == '>' && depth <= 0) {
                return i + 1;
            }
            i++;
        }
        return n;
    }
}
