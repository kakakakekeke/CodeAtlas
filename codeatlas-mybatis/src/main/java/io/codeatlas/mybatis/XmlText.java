package io.codeatlas.mybatis;

/** 텍스트 노드. 엔티티와 CDATA는 풀려 있다. */
record XmlText(String text) implements XmlNode {
}
