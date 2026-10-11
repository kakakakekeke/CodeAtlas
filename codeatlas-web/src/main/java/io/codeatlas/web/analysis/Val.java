package io.codeatlas.web.analysis;

import io.codeatlas.web.syntax.SyntaxNode;
import java.util.List;
import java.util.Map;

/** 식을 정적으로 평가한 값. 호출 대상과 HTTP 클라이언트를 가려내는 데 필요한 만큼만 구분한다. */
sealed interface Val {

    Val UNKNOWN = new Unknown();

    /** 함수. fn은 노드가 있는(이름 있는) 함수일 때만, node는 반환값을 따라갈 때 쓴다. */
    record Func(FunctionInfo fn, SyntaxNode node, ModuleInfo module) implements Val {
    }

    /** 클래스. */
    record Cls(ClassInfo info) implements Val {
    }

    /** {@code new C()}의 결과. classSym은 클래스나 생성자 함수의 심벌. */
    record Instance(Sym classSym) implements Val {
    }

    /** 객체 리터럴, enum, 네임스페이스. */
    record Obj(Map<String, Sym> props) implements Val {
    }

    /** {@code import * as ns}, {@code require('./m')}의 모듈 네임스페이스. */
    record Namespace(ModuleInfo module) implements Val {
    }

    /** axios 또는 {@code axios.create()}로 만든 인스턴스. base는 baseURL 후보(없으면 빈 목록). */
    record Axios(List<UrlValue> base) implements Val {
    }

    record JQuery() implements Val {
    }

    record Xhr() implements Val {
    }

    /** 프로젝트 모듈에서 import했지만 찾지 못한 것. text는 미해결 엣지의 원문. */
    record Missing(String text) implements Val {
    }

    /** 패키지에서 import한 것. */
    record Package(String spec, String name) implements Val {
    }

    record Unknown() implements Val {
    }

    /** 클래스 정보. */
    final class ClassInfo {
        final FunctionInfo fn;
        final Map<String, Sym> members;
        final SyntaxNode superExpr;
        final Scope scope;
        Sym sym;

        ClassInfo(FunctionInfo fn, Map<String, Sym> members, SyntaxNode superExpr, Scope scope) {
            this.fn = fn;
            this.members = members;
            this.superExpr = superExpr;
            this.scope = scope;
        }
    }
}
