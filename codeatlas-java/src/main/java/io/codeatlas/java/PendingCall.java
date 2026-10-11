package io.codeatlas.java;

import io.codeatlas.core.model.SourceLocation;

import java.util.List;

/**
 * JDT가 해석하지 못한 호출 하나. 파일을 볼 때는 AST에서 읽을 수 있는 것만 적어 두고,
 * 모든 파일을 본 뒤({@link CallResolver#resolvePending()}) 프로젝트 전체의 메서드 색인(Lombok 합성 멤버 포함)으로 대상을 찾는다.
 * AST 노드는 붙잡지 않는다.
 */
final class PendingCall {

    enum Kind { METHOD, SUPER_METHOD, CONSTRUCTOR, SUPER_CONSTRUCTOR, METHOD_REF, CONSTRUCTOR_REF }

    /** 식의 타입에 대해 아는 것. */
    sealed interface TypeExpr permits Known, ResultOf, Marker {
    }

    /** 바이너리 이름을 아는 타입(프로젝트 타입인지는 색인으로 판단한다). */
    record Known(String name) implements TypeExpr {
    }

    /** 다른 미해석 호출의 결과. 그 호출이 해석되면 반환 타입을 따라간다. */
    record ResultOf(PendingCall call) implements TypeExpr {
    }

    enum Marker implements TypeExpr {
        /** 소스 없는 타입의 메서드가 돌려준 값이라 타입을 모른다. 이어지는 호출은 프로젝트 메서드일 가능성이 낮다. */
        EXTERNAL_TAIL,
        /** 타입을 모른다. */
        UNKNOWN
    }

    /**
     * 프로젝트의 메서드로 해석하지 못했을 때 남길 것. {@code externalType}이 있으면 {@code <FQN>#<이름>(?)} external 호출이고,
     * 없으면 미해결 엣지(원문 {@link PendingCall#text})다.
     *
     * @param result 그 external 호출이 돌려주는 값의 타입에 대해 아는 것
     * @param inherited 프로젝트 타입이 classpath에 없는 상위 타입에서 물려받은 메서드로 추론한 것이다({@code attrs.inheritedFrom}, F-003-R19)
     * @param ambiguous 외부 상위 타입이 여럿이라 상위 클래스 쪽으로 고른 것이다(엣지는 하나라 {@code attrs.ambiguous}로는 쓰지 않는다, F-003-R29)
     * @param receiverType 그 호출의 수신자 타입(프로젝트 타입의 바이너리 이름). static 호출이거나 수신자가 없으면 null
     */
    record Fallback(String externalType, String callType, boolean inferred, TypeExpr result, boolean inherited, boolean ambiguous,
                    String receiverType) {

        static final Fallback UNRESOLVED = new Fallback(null, null, false, Marker.UNKNOWN, false, false, null);

        Fallback(String externalType, String callType, boolean inferred, TypeExpr result) {
            this(externalType, callType, inferred, result, false, false, null);
        }

        static Fallback external(String type, String callType, boolean inferred) {
            return new Fallback(type, callType, inferred, Marker.EXTERNAL_TAIL);
        }

        /** classpath에 없는 상위 타입에서 물려받은 메서드의 호출(INFERRED). */
        static Fallback inherited(String type, String callType, boolean ambiguous, String receiverType) {
            return new Fallback(type, callType, true, Marker.EXTERNAL_TAIL, true, ambiguous, receiverType);
        }

        boolean isExternal() {
            return externalType != null;
        }
    }

    final Kind kind;
    final Unit.Owner owner;
    /** 메서드 이름. 생성자면 {@code <init>}. */
    final String name;
    /** 인자 수. 메서드 참조처럼 알 수 없으면 -1. */
    final int argc;
    /** 인자마다 그 타입에 대해 아는 것. 인자 수를 모르면 빈 목록. */
    final List<TypeExpr> argTypes;
    /** 호출 지점(메서드 이름 또는 생성 타입). */
    final SourceLocation location;
    /** 호출 식 전체. 체인을 하나로 줄여 남길 때의 위치다. */
    final SourceLocation chainLocation;
    /** 미해결로 남길 때의 원문. 인자 자리는 {@code (…)}로 적고 인자 수는 {@link #argc}로 엣지에 남긴다. */
    final String text;

    /** 명시적 수신자(또는 생성할 타입)에 대해 아는 것. 수신자 없는 호출이면 null. */
    TypeExpr receiver;
    /** 수신자가 타입 이름이다(static 멤버만 대상). */
    boolean staticOnly;
    /** 컴파일러가 수신자 타입을 알고도 선언된 멤버에서 찾지 못했다. 합성 멤버만 후보로 본다. */
    boolean syntheticOnly;
    /** 수신자 없는 호출: 감싼 타입의 바이너리 이름(안쪽부터). */
    List<String> implicitTypes = List.of();
    Fallback fallback = Fallback.UNRESOLVED;
    /** 다른 미해석 호출의 직접 수신자다(체인의 안쪽 링크). */
    boolean usedAsReceiver;
    /** 수신자의 타입을 컴파일러가 아니라 관례({@code getBean(X.class)}의 결과는 X)로 정했다: 찾은 대상은 INFERRED다(F-003-R27). */
    boolean inferredReceiver;
    /** 체인의 안쪽 링크라서 체인 미해결 엣지를 바깥 링크에 맡겼다. 바깥 링크가 체인으로 남지 않으면 이 링크가 직접 남긴다. */
    boolean chainDeferred;

    boolean resolving;
    /** 해석이 끝났으면 결과 타입에 대해 아는 것({@link Known} 또는 {@link Marker}). */
    TypeExpr result;
    /** 해석된 대상이 돌려주는 타입(erasure의 바이너리 이름, 원시 타입·배열 포함). 모르면 null. 인자로 넘긴 호출의 타입을 읽을 때 쓴다. */
    String resultBinary;

    PendingCall(Kind kind, Unit.Owner owner, String name, int argc, List<TypeExpr> argTypes, SourceLocation location,
                SourceLocation chainLocation, String text) {
        this.kind = kind;
        this.owner = owner;
        this.name = name;
        this.argc = argc;
        this.argTypes = argTypes;
        this.location = location;
        this.chainLocation = chainLocation;
        this.text = text;
    }

    boolean isConstructor() {
        return kind == Kind.CONSTRUCTOR || kind == Kind.SUPER_CONSTRUCTOR || kind == Kind.CONSTRUCTOR_REF;
    }
}
