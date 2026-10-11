package io.codeatlas.mybatis;

/** XML 트리의 노드. 요소 또는 텍스트(엔티티와 CDATA를 푼 것). 주석은 트리에 없다. */
sealed interface XmlNode permits XmlElement, XmlText {
}
