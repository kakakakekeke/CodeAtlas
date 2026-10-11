package io.codeatlas.cli.verify;

/**
 * 대조 옵션.
 *
 * @param strictEdgeAttrs true면 엣지는 있는데 정답의 attrs와 다른 경우를 실패(ATTR_MISMATCH)로 본다.
 *                        false면 샘플 README의 "속성 비교는 선택"대로 통과시키고 표시만 남긴다.
 */
public record CheckOptions(boolean strictEdgeAttrs) {

    public static CheckOptions defaults() {
        return new CheckOptions(true);
    }

    public CheckOptions withStrictEdgeAttrs(boolean strict) {
        return new CheckOptions(strict);
    }
}
