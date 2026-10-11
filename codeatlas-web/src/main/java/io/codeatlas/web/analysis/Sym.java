package io.codeatlas.web.analysis;

import io.codeatlas.web.syntax.SyntaxNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 이름 하나(변수, 함수, 클래스, import, 매개변수, 객체 프로퍼티)에 대한 심벌.
 * 값은 선언 때 계산하지 않고 초기화 식과 그 스코프를 들고 있다가 필요할 때 평가한다.
 */
final class Sym {

    /** 대입 한 번: 값 식과 그 식이 놓인 스코프. */
    record Assign(SyntaxNode value, Scope scope) {
    }

    /** import 바인딩. name은 가져온 이름, {@link #DEFAULT}, {@link #NAMESPACE}, {@link #CJS} 중 하나. */
    record ImportRef(String spec, String name) {
    }

    static final String DEFAULT = "default";
    static final String NAMESPACE = "*";
    /** {@code import x = require('m')}: 모듈의 CommonJS 값. */
    static final String CJS = "=";

    final String name;
    final ModuleInfo module;

    /** 초기화 식과 그 스코프. 없으면 null. */
    SyntaxNode init;
    Scope initScope;
    /** 구조 분해로 꺼낸 경로. {@code const { a: { b } } = x}의 b면 [a, b]. */
    List<String> destruct = List.of();
    /** 선언 뒤의 대입({@code =})들. */
    List<Assign> assigns = List.of();
    /** {@code +=}로 덧붙인 식들. */
    List<Assign> appends = List.of();
    /** 나중에 붙인 멤버({@code App.init = ...}, {@code Foo.prototype.bar = ...}, {@code this.x = ...}). */
    Map<String, Sym> members;
    /** 선언만으로 값이 정해진 것(함수 선언, 클래스, enum, 네임스페이스). */
    Val preset;
    ImportRef importRef;
    /** 매개변수나 값을 알 수 없는 바인딩(배열 구조 분해, catch 변수 등). */
    boolean param;
    /**
     * 매개변수면 그 함수(이름 있는 함수일 때만)와 순번. 매개변수를 구조 분해해 꺼낸 이름이면 함수만 있고 순번은 -1이다.
     */
    FunctionInfo paramOwner;
    int paramIndex = -1;
    /** 첫 매개변수(컴포넌트의 props)를 구조 분해해 꺼낸 이름이면 그 프로퍼티 이름. {@code { onSave: save }}의 save면 onSave. */
    String propName;
    /** 이 심벌 아래에 선언되는 함수의 이름 경로 접두사. */
    String namePath;
    /**
     * React 상태({@code const [v, setV] = useState({…})}의 v). 값은 실행 중에 바뀌므로 상수로 따라가지 않고,
     * 초기값과 {@code setV({…})}로 넘긴 객체들을 후보로만 본다(init과 assigns에 들어 있다).
     */
    boolean state;
    /** 상태의 setter({@code setV})면 그 상태의 심벌. */
    Sym stateOf;

    // 평가 상태
    Val cached;
    int cacheEpoch = -1;
    boolean evaluating;
    boolean stepping;
    boolean stringEvaluating;

    Sym(String name, ModuleInfo module) {
        this.name = name;
        this.module = module;
    }

    boolean multiAssigned() {
        return !assigns.isEmpty();
    }

    void addAssign(SyntaxNode value, Scope scope) {
        if (assigns.isEmpty()) {
            assigns = new ArrayList<>(2);
        }
        assigns.add(new Assign(value, scope));
    }

    void addAppend(SyntaxNode value, Scope scope) {
        if (appends.isEmpty()) {
            appends = new ArrayList<>(2);
        }
        appends.add(new Assign(value, scope));
    }

    Sym member(String memberName) {
        return members == null ? null : members.get(memberName);
    }

    void putMember(String memberName, Sym sym) {
        if (members == null) {
            members = new LinkedHashMap<>();
        }
        members.put(memberName, sym);
    }

    @Override
    public String toString() {
        return "Sym(" + name + ")";
    }
}
