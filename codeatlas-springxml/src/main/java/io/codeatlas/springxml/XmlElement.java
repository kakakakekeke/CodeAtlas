package io.codeatlas.springxml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 위치 정보를 가진 XML 요소. 분석에 필요한 만큼만 담은 가벼운 트리다. */
final class XmlElement {

    /** 속성 하나. namespace와 prefix는 접두사 없는 속성이면 빈 문자열. */
    record Attribute(String namespace, String prefix, String localName, String value) {

        /** 파일에 적힌 이름(접두사 포함). */
        String qName() {
            return prefix.isEmpty() ? localName : prefix + ":" + localName;
        }
    }

    final String namespace;
    final String localName;
    final String prefix;
    /** 시작 태그의 '<' 위치(1부터). */
    final int line;
    final int column;
    /** 끝 태그의 '>' 위치. */
    int endLine;
    int endColumn;
    final List<Attribute> attributes = new ArrayList<>();
    /** 속성 이름(접두사 포함) → 이름이 시작하는 [줄, 열]. 구하지 못했으면 비어 있다. */
    Map<String, int[]> attributePositions = Map.of();
    final List<XmlElement> children = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();

    XmlElement(String namespace, String localName, String prefix, int line, int column) {
        this.namespace = namespace == null ? "" : namespace;
        this.localName = localName;
        this.prefix = prefix == null ? "" : prefix;
        this.line = line;
        this.column = column;
        this.endLine = line;
        this.endColumn = column;
    }

    void appendText(String s) {
        text.append(s);
    }

    /** 이 요소 바로 아래의 텍스트(자식 요소의 텍스트는 뺀다). */
    String text() {
        return text.toString();
    }

    /** 접두사 없는 속성값. 없으면 null. */
    String attr(String name) {
        for (Attribute a : attributes) {
            if (a.namespace().isEmpty() && a.localName().equals(name)) {
                return a.value();
            }
        }
        return null;
    }

    /** 속성 이름이 시작하는 [줄, 열]. 모르면 null. */
    int[] attributePosition(String qName) {
        return attributePositions.get(qName);
    }

    /** 접두사 없는 속성값을 다듬은 것. 없거나 비어 있으면 null. */
    String attrTrim(String name) {
        String value = attr(name);
        if (value == null) {
            return null;
        }
        value = value.strip();
        return value.isEmpty() ? null : value;
    }

    /** 주어진 네임스페이스의 속성들(p:, c: 용). */
    List<Attribute> attributesIn(String ns) {
        List<Attribute> found = new ArrayList<>();
        for (Attribute a : attributes) {
            if (a.namespace().equals(ns)) {
                found.add(a);
            }
        }
        return found;
    }

    boolean is(String ns, String name) {
        return localName.equals(name) && namespace.equals(ns);
    }

    /** beans 네임스페이스의 요소인가. DTD 방식 파일은 네임스페이스가 없으므로 빈 네임스페이스도 beans로 본다. */
    boolean isBeans(String name) {
        return localName.equals(name) && isBeansNamespace();
    }

    boolean isBeansNamespace() {
        return namespace.isEmpty() || namespace.equals(Ns.BEANS);
    }

    boolean isBatch(String name) {
        return localName.equals(name) && namespace.equals(Ns.BATCH);
    }

    /** 같은 네임스페이스의 자식 중 이름이 맞는 것들. */
    List<XmlElement> children(String ns, String name) {
        List<XmlElement> found = new ArrayList<>();
        for (XmlElement child : children) {
            if (child.is(ns, name)) {
                found.add(child);
            }
        }
        return found;
    }

    /** 진단 메시지용 이름(접두사 포함). */
    String qName() {
        return prefix.isEmpty() ? localName : prefix + ":" + localName;
    }

    @Override
    public String toString() {
        return "<" + qName() + ">@" + line;
    }
}
