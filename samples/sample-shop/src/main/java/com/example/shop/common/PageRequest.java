package com.example.shop.common;

/**
 * 페이징 요청 (record).
 *
 * @param page 1 부터 시작하는 페이지 번호
 * @param size 페이지 크기
 */
public record PageRequest(int page, int size) {

    /** 컴팩트 생성자: 범위를 벗어난 값은 기본값으로 바꾼다. */
    public PageRequest {
        if (page < 1) {
            page = 1;
        }
        if (size < 1 || size > 500) {
            size = 20;
        }
    }

    public static PageRequest of(int page, int size) {
        return new PageRequest(page, size);
    }

    /** ROWNUM 페이징의 시작 행 (이 값보다 큰 행부터) */
    public int startRow() {
        return (page - 1) * size;
    }

    /** ROWNUM 페이징의 끝 행 */
    public int endRow() {
        return page * size;
    }
}
