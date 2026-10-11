package io.codeatlas.sql.script;

import io.codeatlas.sql.ast.Pos;
import io.codeatlas.sql.ast.QName;
import io.codeatlas.sql.lexer.Token;
import io.codeatlas.sql.parser.TokenCursor;
import java.util.ArrayList;
import java.util.List;

/**
 * 루틴 단위의 헤더에서 읽은 것: 종류, 이름, 매개변수, 트리거 대상, 패키지 멤버. 본문은 해석하지 않는다.
 *
 * @param kind 종류
 * @param name 이름(스키마 한정 포함). 익명 블록이면 null
 * @param parameters 매개변수 원문을 정리한 목록(예: {@code P_ID IN NUMBER})
 * @param triggerTable 트리거 대상 테이블(트리거가 아니면 null)
 * @param triggerEvent 트리거 시점·사건(예: {@code AFTER UPDATE OF ORDER_STATUS})
 * @param members 패키지 명세·본문 안의 프로시저·함수(중첩 서브프로그램 제외)
 * @param bodyStart 본문(선언부 포함)이 시작하는 토큰 인덱스: IS/AS 다음 또는 DECLARE/BEGIN. 못 찾으면 -1
 */
public record RoutineHeader(Kind kind, QName name, List<String> parameters, QName triggerTable, String triggerEvent,
        List<Member> members, int bodyStart) {

    public enum Kind {
        PROCEDURE, FUNCTION, TRIGGER, PACKAGE, PACKAGE_BODY, TYPE_BODY, ANONYMOUS_BLOCK, UNKNOWN;

        /** 그래프의 routineType 값. 루틴이 아니면 null. */
        public String routineType() {
            return switch (this) {
                case PROCEDURE -> "PROCEDURE";
                case FUNCTION -> "FUNCTION";
                case TRIGGER -> "TRIGGER";
                default -> null;
            };
        }
    }

    /**
     * 패키지 멤버 하나.
     *
     * @param startToken 단위 토큰 목록에서 PROCEDURE/FUNCTION 키워드의 인덱스
     * @param endToken 멤버의 끝(끝의 {@code ;} 다음, 제외)
     * @param hasBody 본문이 있으면 true(명세·전방 선언이면 false)
     */
    public record Member(Kind kind, String name, List<String> parameters, int startToken, int endToken, boolean hasBody, Pos pos) {
        public Member {
            parameters = List.copyOf(parameters);
        }
    }

    public RoutineHeader {
        parameters = List.copyOf(parameters);
        members = List.copyOf(members);
    }

    /** 루틴 단위({@link ScriptUnit#tokens()})의 헤더를 읽는다. 어떤 입력에도 예외를 던지지 않는다. */
    public static RoutineHeader parse(List<Token> tokens) {
        int n = tokens.size();
        int i = 0;
        if (n == 0) {
            return new RoutineHeader(Kind.UNKNOWN, null, List.of(), null, null, List.of(), -1);
        }
        if (tokens.get(0).isAny("BEGIN", "DECLARE")) {
            return new RoutineHeader(Kind.ANONYMOUS_BLOCK, null, List.of(), null, null, List.of(), 0);
        }
        if (tokens.get(i).is("CREATE")) {
            i++;
        }
        if (i + 1 < n && tokens.get(i).is("OR") && tokens.get(i + 1).is("REPLACE")) {
            i += 2;
        }
        while (i < n && tokens.get(i).isAny("EDITIONABLE", "NONEDITIONABLE", "PUBLIC")) {
            i++;
        }
        Kind kind = Kind.UNKNOWN;
        if (i < n) {
            Token k = tokens.get(i);
            if (k.is("PROCEDURE")) {
                kind = Kind.PROCEDURE;
                i++;
            } else if (k.is("FUNCTION")) {
                kind = Kind.FUNCTION;
                i++;
            } else if (k.is("TRIGGER")) {
                kind = Kind.TRIGGER;
                i++;
            } else if (k.is("PACKAGE")) {
                kind = Kind.PACKAGE;
                i++;
                if (i < n && tokens.get(i).is("BODY")) {
                    kind = Kind.PACKAGE_BODY;
                    i++;
                }
            } else if (k.is("TYPE")) {
                kind = Kind.TYPE_BODY;
                i++;
                if (i < n && tokens.get(i).is("BODY")) {
                    i++;
                }
            }
        }
        if (kind == Kind.UNKNOWN || i >= n || !tokens.get(i).isIdentifier()) {
            return new RoutineHeader(kind, null, List.of(), null, null, List.of(), -1);
        }
        int[] cursor = {i};
        QName name = qualifiedName(tokens, cursor);
        i = cursor[0];
        List<String> parameters = List.of();
        QName triggerTable = null;
        String triggerEvent = null;
        List<Member> members = List.of();
        int bodyStart = -1;
        switch (kind) {
            case PROCEDURE:
            case FUNCTION: {
                if (i < n && tokens.get(i).isSymbol("(")) {
                    int close = matching(tokens, i);
                    parameters = splitParameters(tokens, i + 1, close);
                    i = Math.min(n, close + 1);
                }
                bodyStart = findBodyStart(tokens, i);
                break;
            }
            case TRIGGER: {
                int on = -1;
                int parens = 0;
                for (int k = i; k < n; k++) {
                    Token t = tokens.get(k);
                    if (t.isSymbol("(")) {
                        parens++;
                    } else if (t.isSymbol(")")) {
                        parens--;
                    } else if (parens == 0 && t.is("ON") && k + 1 < n && tokens.get(k + 1).isIdentifier()
                            && !tokens.get(k + 1).isAny("DATABASE", "SCHEMA")) {
                        on = k;
                        break;
                    } else if (parens == 0 && t.isAny("BEGIN", "DECLARE")) {
                        break;
                    }
                }
                if (on >= 0) {
                    triggerEvent = TokenCursor.joinTokens(tokens.subList(i, on));
                    int[] c = {on + 1};
                    triggerTable = qualifiedName(tokens, c);
                    i = c[0];
                }
                parens = 0;
                for (int k = i; k < n; k++) {
                    Token t = tokens.get(k);
                    if (t.isSymbol("(")) {
                        parens++;
                    } else if (t.isSymbol(")")) {
                        parens--;
                    } else if (parens == 0 && t.isAny("BEGIN", "DECLARE")) {
                        bodyStart = k;
                        break;
                    }
                }
                break;
            }
            default: {
                // 패키지, 타입 본문: IS/AS 다음부터 멤버를 훑는다
                int open = -1;
                for (int k = i; k < n; k++) {
                    if (tokens.get(k).isAny("IS", "AS")) {
                        open = k;
                        break;
                    }
                }
                if (open >= 0) {
                    bodyStart = open + 1;
                    members = scanMembers(tokens);
                }
                break;
            }
        }
        return new RoutineHeader(kind, name, parameters, triggerTable, triggerEvent, members, bodyStart);
    }

    private static int findBodyStart(List<Token> tokens, int from) {
        int parens = 0;
        for (int k = from; k < tokens.size(); k++) {
            Token t = tokens.get(k);
            if (t.isSymbol("(")) {
                parens++;
            } else if (t.isSymbol(")")) {
                parens--;
            } else if (parens == 0) {
                if (t.isAny("IS", "AS")) {
                    return k + 1;
                }
                if (t.is("BEGIN")) {
                    return k;
                }
                if (t.is("RETURN") && k > from && isDb2Function(tokens, from, k)) {
                    return k;
                }
            }
        }
        return -1;
    }

    private static boolean isDb2Function(List<Token> tokens, int from, int to) {
        for (int k = from; k < to; k++) {
            if (tokens.get(k).is("RETURNS")) {
                return true;
            }
        }
        return false;
    }

    private static List<Member> scanMembers(List<Token> tokens) {
        List<Member> members = new ArrayList<>();
        BlockTracker tracker = new BlockTracker(true, false);
        int n = tokens.size();
        int memberStart = -1;
        Token prev = null;
        for (int k = 0; k < n; k++) {
            Token t = tokens.get(k);
            Token next = k + 1 < n ? tokens.get(k + 1) : null;
            boolean startsMember = memberStart < 0 && tracker.atContainerLevel() && t.isAny("PROCEDURE", "FUNCTION")
                    && next != null && next.isIdentifier();
            if (startsMember) {
                memberStart = k;
            }
            boolean semicolon = tracker.feed(t, prev, next);
            prev = t;
            if (memberStart >= 0 && semicolon && tracker.atContainerLevel()) {
                members.add(member(tokens, memberStart, k + 1));
                memberStart = -1;
            }
        }
        if (memberStart >= 0) {
            members.add(member(tokens, memberStart, n));
        }
        return members;
    }

    private static Member member(List<Token> tokens, int start, int end) {
        Token keyword = tokens.get(start);
        Token nameToken = tokens.get(start + 1);
        Kind kind = keyword.is("FUNCTION") ? Kind.FUNCTION : Kind.PROCEDURE;
        List<String> parameters = List.of();
        int i = start + 2;
        if (i < end && tokens.get(i).isSymbol("(")) {
            int close = Math.min(matching(tokens, i), end);
            parameters = splitParameters(tokens, i + 1, close);
            i = close + 1;
        }
        boolean hasBody = false;
        int parens = 0;
        for (int k = i; k < end; k++) {
            Token t = tokens.get(k);
            if (t.isSymbol("(")) {
                parens++;
            } else if (t.isSymbol(")")) {
                parens--;
            } else if (parens == 0 && t.isAny("IS", "AS", "BEGIN")) {
                hasBody = true;
                break;
            }
        }
        return new Member(kind, io.codeatlas.core.model.DbName.normalize(nameToken.text()), parameters, start, end, hasBody, Pos.of(keyword));
    }

    private static QName qualifiedName(List<Token> tokens, int[] cursor) {
        int i = cursor[0];
        Token first = tokens.get(i);
        Token last = first;
        List<String> parts = new ArrayList<>();
        parts.add(first.text());
        i++;
        while (i + 1 < tokens.size() && tokens.get(i).isSymbol(".") && tokens.get(i + 1).isIdentifier()) {
            last = tokens.get(i + 1);
            parts.add(last.text());
            i += 2;
        }
        cursor[0] = i;
        return new QName(parts, null, Pos.span(first, last));
    }

    /** open 위치의 여는 괄호와 짝인 닫는 괄호의 인덱스. 없으면 끝. */
    private static int matching(List<Token> tokens, int open) {
        int depth = 0;
        for (int k = open; k < tokens.size(); k++) {
            Token t = tokens.get(k);
            if (t.isSymbol("(")) {
                depth++;
            } else if (t.isSymbol(")")) {
                depth--;
                if (depth == 0) {
                    return k;
                }
            }
        }
        return tokens.size();
    }

    private static List<String> splitParameters(List<Token> tokens, int from, int to) {
        List<String> list = new ArrayList<>();
        int depth = 0;
        int start = from;
        for (int k = from; k < to && k < tokens.size(); k++) {
            Token t = tokens.get(k);
            if (t.isSymbol("(")) {
                depth++;
            } else if (t.isSymbol(")")) {
                depth--;
            } else if (depth == 0 && t.isSymbol(",")) {
                if (k > start) {
                    list.add(TokenCursor.joinTokens(tokens.subList(start, k)));
                }
                start = k + 1;
            }
        }
        int end = Math.min(to, tokens.size());
        if (end > start) {
            list.add(TokenCursor.joinTokens(tokens.subList(start, end)));
        }
        return list;
    }
}
