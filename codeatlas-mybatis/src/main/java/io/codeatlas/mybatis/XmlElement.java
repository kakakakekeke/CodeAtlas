package io.codeatlas.mybatis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * XML 요소. 원본 문자열 안의 위치(오프셋)를 함께 가진다. 위치를 확인하지 못한 요소는 오프셋이 -1이다.
 */
final class XmlElement implements XmlNode {

    /** 네임스페이스 접두사를 뗀 이름. */
    final String name;
    final String qName;
    final Map<String, String> attrs = new LinkedHashMap<>();
    final List<XmlNode> children = new ArrayList<>();

    /** 시작 태그의 {@code <} 위치. */
    int startOffset = -1;
    /** 시작 태그의 {@code >} 다음 위치(본문 시작). */
    int bodyStart = -1;
    /** 끝 태그의 {@code <} 위치(본문 끝, 제외). */
    int bodyEnd = -1;
    /** 끝 태그의 {@code >} 다음 위치. */
    int endOffset = -1;
    /** 파서가 알려 준 시작 태그 끝의 줄(오프셋을 확인하지 못했을 때 쓴다). */
    int fallbackLine;
    /** 끝 태그까지 읽었는가. 오류로 중단된 문서에서는 false일 수 있다. */
    boolean closed;

    XmlElement(String qName) {
        this.qName = qName;
        int colon = qName.indexOf(':');
        this.name = colon < 0 ? qName : qName.substring(colon + 1);
    }

    /** 속성값. 없으면 null. 비어 있으면(공백뿐이면) null. */
    String attr(String attrName) {
        String value = attrs.get(attrName);
        return value == null || value.isBlank() ? null : value.trim();
    }

    boolean hasPositions() {
        return startOffset >= 0 && bodyStart >= 0 && bodyEnd >= bodyStart && endOffset >= bodyEnd;
    }

    List<XmlElement> elements() {
        List<XmlElement> result = new ArrayList<>();
        for (XmlNode child : children) {
            if (child instanceof XmlElement e) {
                result.add(e);
            }
        }
        return result;
    }

    List<XmlElement> elements(String childName) {
        List<XmlElement> result = new ArrayList<>();
        for (XmlNode child : children) {
            if (child instanceof XmlElement e && e.name.equals(childName)) {
                result.add(e);
            }
        }
        return result;
    }

    /** 직계 텍스트 노드를 이은 것. */
    String ownText() {
        StringBuilder sb = new StringBuilder();
        for (XmlNode child : children) {
            if (child instanceof XmlText t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "<" + qName + " " + attrs + ">";
    }
}
