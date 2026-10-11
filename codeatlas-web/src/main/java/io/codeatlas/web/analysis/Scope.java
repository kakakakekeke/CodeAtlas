package io.codeatlas.web.analysis;

import java.util.LinkedHashMap;
import java.util.Map;

/** 렉시컬 스코프. 모듈, 함수, 블록마다 하나. */
final class Scope {

    final Scope parent;
    final ModuleInfo module;
    /** {@code var}와 함수 선언이 끌어올려지는 경계(모듈, 함수)인가. */
    final boolean functionScope;
    final Map<String, Sym> syms = new LinkedHashMap<>();
    /** 이 스코프가 {@code this}를 새로 정하는가(화살표가 아닌 함수, 클래스 본문). */
    boolean ownThis;
    /** ownThis일 때의 this. 모르면 null. */
    Sym thisSym;

    Scope(Scope parent, ModuleInfo module, boolean functionScope) {
        this.parent = parent;
        this.module = module;
        this.functionScope = functionScope;
    }

    Scope varScope() {
        Scope s = this;
        while (!s.functionScope && s.parent != null) {
            s = s.parent;
        }
        return s;
    }

    Sym lookup(String name) {
        for (Scope s = this; s != null; s = s.parent) {
            Sym sym = s.syms.get(name);
            if (sym != null) {
                return sym;
            }
        }
        return null;
    }

    /** 이 자리의 this. 모르면 null. */
    Sym lookupThis() {
        for (Scope s = this; s != null; s = s.parent) {
            if (s.ownThis) {
                return s.thisSym;
            }
        }
        return null;
    }
}
