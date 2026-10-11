package io.codeatlas.cli;

/** 명령을 끝내지 못했을 때(그래프 파일이 없음, 대상이 없음 등). {@link Main}이 이유를 표준 오류에 알리고 종료 코드 1로 끝낸다. */
final class CommandFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    CommandFailure(String message) {
        super(message);
    }
}
