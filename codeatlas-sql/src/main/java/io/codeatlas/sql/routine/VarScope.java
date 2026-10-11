package io.codeatlas.sql.routine;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 루틴 본문의 이름 스코프 하나. 서브프로그램, DECLARE 블록, DB2 복합문, FOR·FORALL 루프, 핸들러마다 하나씩 생기고 바깥 스코프로 이어진다.
 * 이름은 정규화한 값(따옴표가 없으면 대문자)이다.
 */
final class VarScope {

    private final VarScope parent;
    private final Set<String> variables = new HashSet<>();
    private final Set<String> routines = new HashSet<>();
    private boolean open;

    VarScope(VarScope parent) {
        this.parent = parent;
    }

    /** 변수, 상수, 매개변수, 커서, 루프 변수, 예외 이름을 선언한다. */
    void declare(String name) {
        if (name != null && !name.isEmpty()) {
            variables.add(name);
        }
    }

    void declareAll(Collection<String> names) {
        for (String n : names) {
            declare(n);
        }
    }

    /** 지역 서브프로그램 이름을 선언한다. */
    void declareRoutine(String name) {
        if (name != null && !name.isEmpty()) {
            routines.add(name);
        }
    }

    /** 이 스코프나 바깥 스코프에 선언된 변수인가. */
    boolean isVariable(String name) {
        for (VarScope s = this; s != null; s = s.parent) {
            if (s.variables.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 이 스코프에 이름을 다 알 수 없는 암묵 변수가 있다고 표시한다: DB2 {@code FOR v AS SELECT * FROM 컬럼을 모르는 테이블 DO}의 본문처럼
     * 선택 목록의 컬럼 이름이 변수로 보이는데 그 이름을 알 수 없는 경우다(F-008-R16).
     */
    void markOpen() {
        open = true;
    }

    /** 이 스코프나 바깥 스코프에 이름을 다 알 수 없는 암묵 변수가 있는가. 그러면 "선언되지 않은 이름"을 컬럼이라고 말할 수 없다. */
    boolean isOpen() {
        for (VarScope s = this; s != null; s = s.parent) {
            if (s.open) {
                return true;
            }
        }
        return false;
    }

    /** 이 스코프나 바깥 스코프에 선언된 지역 서브프로그램인가. */
    boolean isLocalRoutine(String name) {
        for (VarScope s = this; s != null; s = s.parent) {
            if (s.routines.contains(name)) {
                return true;
            }
        }
        return false;
    }
}
