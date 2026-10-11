package io.codeatlas.web.analysis;

import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.web.syntax.SyntaxNode;

/** JS_FUNCTION 노드 하나가 될 함수(또는 클래스). */
final class FunctionInfo {

    final String id;
    final String namePath;
    final String kind;
    final ModuleInfo module;
    final SyntaxNode node;
    final SourceLocation location;
    /** 감싸는 함수 노드. 없으면 모듈 직속. */
    final FunctionInfo parent;
    boolean async;
    boolean exported;
    boolean component;
    /** 본문에 JSX가 있다(이름 있는 중첩 함수 안의 것은 제외). */
    boolean hasJsx;
    /** 클래스면 상위 클래스 식의 원문. */
    String superClass;
    boolean hasRender;
    /** 이름에 JSP 식이 섞여 있었다({@code function ${prefix}Open}). 이름 경로의 그 자리는 {@code {*}}다. */
    boolean dynamicName;

    FunctionInfo(String id, String namePath, String kind, ModuleInfo module, SyntaxNode node, SourceLocation location,
            FunctionInfo parent) {
        this.id = id;
        this.namePath = namePath;
        this.kind = kind;
        this.module = module;
        this.node = node;
        this.location = location;
        this.parent = parent;
    }

    String shortName() {
        int dot = namePath.lastIndexOf('.');
        return dot < 0 ? namePath : namePath.substring(dot + 1);
    }

    boolean isClass() {
        return "class".equals(kind);
    }

    @Override
    public String toString() {
        return id;
    }
}
