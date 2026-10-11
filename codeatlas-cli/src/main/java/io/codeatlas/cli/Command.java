package io.codeatlas.cli;

import java.io.PrintStream;

/**
 * 하위 명령 하나. 새 명령은 이 인터페이스를 구현해 {@link Main}의 레지스트리에 등록한다.
 * 사용법 문구는 등록된 명령들의 {@link #synopsis()}와 {@link #help()}를 모아 만든다.
 */
interface Command {

    /** 명령 이름. 예: {@code analyze}. */
    String name();

    /** 사용법의 한 항목. {@code codeatlas <이름> ...} 형태이고 여러 줄일 수 있다(줄 끝 개행 없음). */
    String synopsis();

    /** 옵션과 동작 설명. 없으면 빈 문자열. 있으면 줄 끝 개행으로 끝난다. */
    String help();

    /**
     * 명령을 실행하고 종료 코드를 돌려준다.
     *
     * @param args 명령 이름 뒤의 인자
     * @throws UsageException 인자가 잘못되면. {@link Main}이 사용법과 함께 알리고 종료 코드 2로 끝낸다
     */
    int run(String[] args, PrintStream out, PrintStream err);
}
