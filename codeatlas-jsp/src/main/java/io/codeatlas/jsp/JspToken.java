package io.codeatlas.jsp;

import java.util.List;
import java.util.Optional;

/**
 * 스캐너가 낸 요소 하나. 오프셋은 스캔한 문자열 기준이고 {@code [start, end)} 구간이다.
 * 토큰 사이의 빈 구간은 일반 텍스트다.
 *
 * @param type 요소 종류
 * @param start 요소 시작 오프셋
 * @param end 요소 끝 오프셋(제외)
 * @param name 태그 이름 또는 지시자 이름. 그 밖에는 빈 문자열
 * @param attributes 태그·지시자의 속성. 그 밖에는 빈 목록
 * @param selfClosing {@code />}로 닫은 여는 태그인가
 * @param contentStart 구분자를 뺀 안쪽 내용의 시작({@code <%= x %>}의 x, {@code ${x}}의 x)
 * @param contentEnd 안쪽 내용의 끝(제외)
 */
record JspToken(Type type, int start, int end, String name, List<JspAttribute> attributes, boolean selfClosing,
        int contentStart, int contentEnd) {

    enum Type {
        /** {@code <%@ ... %>} */
        DIRECTIVE,
        /** {@code <% ... %>} */
        SCRIPTLET,
        /** {@code <%= ... %>} */
        EXPRESSION,
        /** {@code <%! ... %>} */
        DECLARATION,
        /** {@code <%-- ... --%>} */
        JSP_COMMENT,
        /** {@code <!-- ... -->} */
        HTML_COMMENT,
        /** {@code ${...}} 또는 {@code #{...}} */
        EL,
        /** 여는 태그(액션, 커스텀, HTML) */
        OPEN_TAG,
        /** 닫는 태그 */
        CLOSE_TAG,
        /** {@code <script>}, {@code <style>}의 본문 */
        RAW_TEXT
    }

    static JspToken simple(Type type, int start, int end, int contentStart, int contentEnd) {
        return new JspToken(type, start, end, "", List.of(), false, contentStart, contentEnd);
    }

    /** 요소 전체의 원문. */
    String text(String source) {
        return source.substring(start, end);
    }

    /** 구분자를 뺀 안쪽 내용. */
    String content(String source) {
        return source.substring(contentStart, contentEnd);
    }

    /** 이름으로 속성을 찾는다(대소문자 무시). 같은 이름이 여러 번이면 첫 번째. */
    Optional<JspAttribute> attribute(String attributeName) {
        for (JspAttribute attribute : attributes) {
            if (attribute.name().equalsIgnoreCase(attributeName)) {
                return Optional.of(attribute);
            }
        }
        return Optional.empty();
    }

    /** 속성 값 원문. 없으면 null. */
    String attr(String attributeName) {
        return attribute(attributeName).map(JspAttribute::value).orElse(null);
    }

    boolean hasAttr(String attributeName) {
        return attribute(attributeName).isPresent();
    }

    /** 이 이름의 여는 태그인가(대소문자 무시). */
    boolean isOpenTag(String tagName) {
        return type == Type.OPEN_TAG && name.equalsIgnoreCase(tagName);
    }

    /** {@code c:url}의 {@code c}. 접두사가 없으면 빈 문자열. */
    String prefix() {
        int colon = name.indexOf(':');
        return colon < 0 ? "" : name.substring(0, colon);
    }

    /** {@code c:url}의 {@code url}. 접두사가 없으면 이름 전체. */
    String localName() {
        return name.substring(name.indexOf(':') + 1);
    }
}
