package io.codeatlas.jsp;

/**
 * 태그나 지시자의 속성 하나.
 *
 * @param name 속성 이름(원문 그대로)
 * @param value 속성 값 원문(따옴표 제외, 엔티티·EL·중첩 태그를 풀지 않은 그대로). 값이 없는 속성은 빈 문자열
 * @param valueStart 값이 시작하는 오프셋(따옴표 다음). 값이 없으면 이름의 끝
 * @param valueEnd 값이 끝나는 오프셋(닫는 따옴표 위치)
 * @param hasValue {@code =} 뒤에 값이 있었는가
 */
record JspAttribute(String name, String value, int valueStart, int valueEnd, boolean hasValue) {
}
