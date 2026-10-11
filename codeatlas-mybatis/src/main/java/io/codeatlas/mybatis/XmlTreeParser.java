package io.codeatlas.mybatis;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.DefaultHandler2;

/**
 * JDK 내장 SAX 파서로 위치를 아는 가벼운 XML 트리를 만든다.
 *
 * <ul>
 *   <li>문자열을 그대로 넘기므로 XML 선언의 encoding은 쓰이지 않는다.</li>
 *   <li>외부 DTD와 외부 엔티티는 읽지 않는다(네트워크·파일 접근 없음). 건너뛴 엔티티 이름은 결과에 남긴다.</li>
 *   <li>잘못된 XML이면 예외를 던지지 않고 오류 지점까지의 트리와 오류 정보를 돌려준다.</li>
 * </ul>
 *
 * <p>SAX의 Locator는 "방금 읽은 태그의 끝"을 알려 주므로, 그 지점에서 거꾸로 {@code <}를 찾아 태그의 시작을 구한다.
 * 찾은 자리가 기대한 모양이 아니면 그 요소의 오프셋은 -1로 둔다.
 */
final class XmlTreeParser {

    private XmlTreeParser() {
    }

    static XmlDocument parse(String text) {
        LineMap lines = new LineMap(text);
        Handler handler = new Handler(text, lines);
        String error = null;
        int errorLine = 0;
        int errorColumn = 0;
        try {
            XMLReader reader = newReader();
            reader.setContentHandler(handler);
            reader.setEntityResolver(handler);
            reader.setErrorHandler(handler);
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
            reader.parse(new InputSource(new StringReader(text)));
        } catch (SAXParseException e) {
            error = e.getMessage() == null ? e.toString() : e.getMessage();
            errorLine = Math.max(0, e.getLineNumber());
            errorColumn = Math.max(0, e.getColumnNumber());
        } catch (SAXException | IOException | ParserConfigurationException e) {
            error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        return new XmlDocument(text, lines, handler.root, error, errorLine, errorColumn, List.copyOf(handler.skipped));
    }

    private static XMLReader newReader() throws ParserConfigurationException, SAXException {
        // classpath에 다른 XML 파서가 있어도 JDK 내장 구현을 쓴다(아래 feature 이름이 내장 구현 것이다).
        SAXParserFactory factory = SAXParserFactory.newDefaultInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        factory.setXIncludeAware(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        // DOCTYPE 선언은 허용하되(매퍼 XML에 늘 있다) 외부의 것은 아무것도 읽지 않는다.
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        SAXParser parser = factory.newSAXParser();
        trySetProperty(parser, XMLConstants.ACCESS_EXTERNAL_DTD);
        trySetProperty(parser, XMLConstants.ACCESS_EXTERNAL_SCHEMA);
        return parser.getXMLReader();
    }

    private static void trySetProperty(SAXParser parser, String name) {
        try {
            parser.setProperty(name, "");
        } catch (SAXException e) {
            // 이 속성을 모르는 파서 구현이면 위의 feature만으로 막는다.
        }
    }

    private static final class Handler extends DefaultHandler2 {

        private final String raw;
        private final LineMap lines;
        private final Deque<XmlElement> stack = new ArrayDeque<>();
        private final StringBuilder text = new StringBuilder();
        private final List<String> skipped = new ArrayList<>();
        private Locator locator;
        private XmlElement root;

        Handler(String raw, LineMap lines) {
            this.raw = raw;
            this.lines = lines;
        }

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        /** 외부 엔티티(DTD 포함)는 어떤 것이든 빈 내용으로 대신한다. */
        @Override
        public InputSource resolveEntity(String name, String publicId, String baseURI, String systemId) {
            return new InputSource(new StringReader(""));
        }

        @Override
        public InputSource getExternalSubset(String name, String baseURI) {
            return null;
        }

        @Override
        public void skippedEntity(String name) {
            if (name != null && !name.startsWith("[") && !name.startsWith("%")) {
                skipped.add(name);
            }
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            flushText();
            XmlElement element = new XmlElement(qName);
            for (int i = 0; i < attributes.getLength(); i++) {
                element.attrs.put(attributes.getQName(i), attributes.getValue(i));
            }
            int tagEnd = currentOffset();
            element.fallbackLine = locator == null ? 0 : locator.getLineNumber();
            if (tagEnd > 0 && raw.charAt(tagEnd - 1) == '>') {
                int tagStart = raw.lastIndexOf('<', tagEnd - 1);
                if (tagStart >= 0 && raw.startsWith(qName, tagStart + 1)) {
                    element.startOffset = tagStart;
                    element.bodyStart = tagEnd;
                    if (raw.charAt(tagEnd - 2) == '/') {
                        // <a/> 꼴: 본문이 없다
                        element.bodyEnd = tagEnd;
                        element.endOffset = tagEnd;
                    }
                }
            }
            if (stack.isEmpty()) {
                if (root == null) {
                    root = element;
                }
            } else {
                stack.peek().children.add(element);
            }
            stack.push(element);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            flushText();
            XmlElement element = stack.pop();
            element.closed = true;
            if (element.startOffset >= 0 && element.endOffset < 0) {
                int tagEnd = currentOffset();
                int tagStart = tagEnd > 0 ? raw.lastIndexOf("</", tagEnd - 1) : -1;
                if (tagStart >= element.bodyStart && raw.startsWith(qName, tagStart + 2)) {
                    element.bodyEnd = tagStart;
                    element.endOffset = tagEnd;
                } else {
                    element.startOffset = -1;
                    element.bodyStart = -1;
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            flushText();
            throw e;
        }

        @Override
        public void error(SAXParseException e) {
            // 검증은 하지 않으므로 복구 가능한 오류는 무시한다.
        }

        @Override
        public void warning(SAXParseException e) {
        }

        private void flushText() {
            if (text.length() > 0) {
                if (!stack.isEmpty()) {
                    stack.peek().children.add(new XmlText(text.toString()));
                }
                text.setLength(0);
            }
        }

        private int currentOffset() {
            return locator == null ? -1 : lines.offset(locator.getLineNumber(), locator.getColumnNumber());
        }
    }
}
