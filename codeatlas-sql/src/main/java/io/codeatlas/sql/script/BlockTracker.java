package io.codeatlas.sql.script;

import io.codeatlas.sql.lexer.Token;

/**
 * PL/SQL·SQL PL 루틴 단위의 블록 깊이를 토큰 단위로 따라간다. 본문을 해석하지 않고 BEGIN/END, CASE/END,
 * 선언부(IS/AS/DECLARE ~ BEGIN)만 센다. 스크립트 분할기가 루틴 단위의 끝을, 헤더 파서가 패키지 멤버의 경계를 찾을 때 쓴다.
 */
final class BlockTracker {

    /** 열려 있는 BEGIN·CASE·컨테이너(패키지) 수. */
    int depth;
    /** 선언부에 있어 아직 BEGIN을 만나지 못한 서브프로그램 수. */
    int pending;
    int parens;
    /** PROCEDURE/FUNCTION 헤더를 읽는 중(IS/AS 또는 ;를 기다림). */
    boolean subHeader;
    private final boolean container;
    private final boolean declareOpens;
    private boolean containerOpened;
    private boolean skipNext;

    /**
     * @param container 패키지 명세·본문, TYPE BODY처럼 IS/AS ~ END가 서브프로그램들을 감싸는 단위
     * @param declareOpens 최상위 DECLARE가 선언부를 여는 단위(트리거, 익명 블록)
     */
    BlockTracker(boolean container, boolean declareOpens) {
        this.container = container;
        this.declareOpens = declareOpens;
    }

    /** 지금 최상위(어느 블록·선언부에도 속하지 않음)인가. */
    boolean atTop() {
        return depth == 0 && pending == 0 && parens == 0 && !subHeader;
    }

    /** 컨테이너(패키지) 바로 안쪽이고 서브프로그램 밖인가. */
    boolean atContainerLevel() {
        return container && containerOpened && depth == 1 && pending == 0 && parens == 0 && !subHeader;
    }

    /**
     * 토큰 하나를 먹인다.
     *
     * @return 이 토큰이 단위(또는 컨테이너 안의 멤버)를 끝낼 수 있는 {@code ;}이면 true: 괄호 밖이고 헤더·선언부가 아니다.
     *         깊이는 호출한 쪽이 {@link #depth}로 확인한다
     */
    boolean feed(Token t, Token prev, Token next) {
        if (skipNext) {
            skipNext = false;
            return false;
        }
        if (t.isSymbol("(")) {
            parens++;
            return false;
        }
        if (t.isSymbol(")")) {
            if (parens > 0) {
                parens--;
            }
            return false;
        }
        if (parens > 0 && t.isSymbol(";")) {
            parens = 0; // 괄호 안에 ; 는 올 수 없다: 닫히지 않은 괄호로 보고 문장 끝으로 친다(깨진 루틴이 뒤의 루틴까지 삼키지 않게)
        }
        if (parens > 0) {
            return false;
        }
        if (t.isSymbol(";")) {
            subHeader = false; // 헤더 중이었다면 전방 선언 또는 명세
            return pending == 0;
        }
        if (t.type() != io.codeatlas.sql.lexer.TokenType.IDENT) {
            return false;
        }
        if (prev != null && prev.isSymbol(".")) {
            return false;
        }
        switch (t.upper()) {
            case "PROCEDURE":
            case "FUNCTION":
                if (depth == 0 || pending > 0 || (container && depth == 1)) {
                    if (prev == null || !prev.isAny("END", "DROP", "ALTER")) {
                        subHeader = true;
                    }
                }
                break;
            case "IS":
            case "AS":
                if (container && !containerOpened && depth == 0) {
                    containerOpened = true;
                    depth = 1;
                } else if (subHeader) {
                    subHeader = false;
                    if (next == null || !next.isAny("LANGUAGE", "EXTERNAL")) {
                        pending++;
                    }
                }
                break;
            case "RETURNS":
            case "LANGUAGE":
            case "EXTERNAL":
                subHeader = false;
                break;
            case "DECLARE":
                if (declareOpens && depth == 0 && pending == 0) {
                    pending++;
                }
                break;
            case "BEGIN":
                subHeader = false;
                if (pending > 0) {
                    pending--;
                    depth++;
                } else if (container && depth == 1) {
                    // 패키지 초기화부: 패키지의 END와 짝이다
                } else {
                    depth++;
                }
                break;
            case "CASE":
                depth++;
                break;
            case "END":
                if (next != null && next.isAny("IF", "LOOP", "WHILE", "FOR", "REPEAT")) {
                    skipNext = true;
                } else {
                    if (next != null && next.is("CASE")) {
                        skipNext = true;
                    }
                    if (depth > 0) {
                        depth--;
                    }
                }
                break;
            default:
                break;
        }
        return false;
    }
}
