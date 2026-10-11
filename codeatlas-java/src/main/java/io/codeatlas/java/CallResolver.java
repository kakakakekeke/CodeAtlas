package io.codeatlas.java;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;

import java.lang.reflect.Method;
import java.lang.reflect.TypeVariable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 호출 엣지를 쓰는 곳. JDT가 해석한 호출은 파일을 보는 동안 바로 쓰고,
 * 해석하지 못한 호출({@link PendingCall})은 모든 파일을 본 뒤 프로젝트 전체의 메서드 색인으로 다시 찾는다(F-003-R11).
 * 색인에는 소스에 선언한 메서드와 Lombok 합성 멤버가 들어 있다.
 */
final class CallResolver {

    private static final int MAX_TEXT = 200;

    /**
     * 프로젝트 타입의 메서드 하나.
     *
     * @param parameterTypes 매개변수 타입 erasure의 바이너리 이름
     * @param returnType 반환 타입 erasure의 바이너리 이름. void·원시 타입·배열·타입 변수처럼 이어서 부를 타입이 아니면 null
     * @param returnsReceiver 수신자 자신의 타입을 돌려준다(빌더 메서드). 상위 빌더의 메서드를 하위 빌더로 불러도 체인이 이어진다
     * @param lombok Lombok 합성 멤버
     */
    record MethodInfo(String id, String name, List<String> parameterTypes, boolean varargs, boolean isStatic, boolean constructor,
                      String returnType, boolean returnsReceiver, boolean lombok) {
    }

    private final JavaProject project;
    private final GraphModel graph;
    private final Map<String, List<MethodInfo>> methodsByType = new HashMap<>();
    private final List<PendingCall> pending = new ArrayList<>();
    private final Map<String, List<String>> supertypes = new HashMap<>();
    private final Map<String, Boolean> projectTypes = new HashMap<>();
    private final Map<String, java.util.Optional<Class<?>>> jdkClasses = new HashMap<>();
    /** {@code @SuperBuilder} 타입 → 그 빌더 타입. */
    private final Map<String, String> superBuilders = new HashMap<>();
    /** 빌더 타입, 그 타입을 만든 클래스의 상위 타입, 위치. 모든 파일을 본 뒤 상위 빌더로 EXTENDS를 잇는다. */
    private final List<Object[]> superBuilderParents = new ArrayList<>();

    /**
     * 추론할 수 있는 꼴의 리플렉션 호출 하나(F-003-R20).
     *
     * @param genericType 대상 객체의 선언 타입이 타입 매개변수일 때 그 매개변수를 선언한 클래스의 바이너리 이름. 아니면 null
     * @param typeArgument 그 타입 매개변수의 위치
     * @param directType 대상 객체의 선언 타입이 프로젝트 타입일 때 그 바이너리 이름. 아니면 null
     * @param name 문자열 상수로 준 메서드 이름
     * @param argc 인자 수. 알 수 없으면 -1
     * @param location {@code invoke} 호출의 위치
     */
    record Reflection(Unit.Owner owner, String genericType, int typeArgument, String directType, String name, int argc,
                      SourceLocation location) {
    }

    /** 하위 타입에서 본 제네릭 상위 타입의 타입 인자(바이너리 이름. 타입 변수·와일드카드·미해석 타입이면 null). */
    private record TypeArguments(String subtype, List<String> arguments) {
    }

    private final List<Reflection> reflections = new ArrayList<>();
    /** 제네릭 프로젝트 타입 → 그 타입을 상속한 타입들이 준 타입 인자. */
    private final Map<String, List<TypeArguments>> typeArguments = new HashMap<>();
    /** 대상을 알 수 없는 리플렉션 호출이 있는 메서드 → 처음 나온 위치. */
    private final Map<String, SourceLocation> unresolvedReflection = new LinkedHashMap<>();

    /**
     * 호출 대상 후보 하나. 엣지를 쓰는 데 필요한 것만 적는다(컴파일러의 바인딩을 붙잡지 않는다).
     *
     * @param methodId 대상 메서드의 ID
     * @param declaringType 선언한 타입의 바이너리 이름
     * @param external 소스 없는 타입의 메서드다(external 참조 노드를 만들고 {@code java.externalCalls} 설정을 따른다)
     * @param callType 엣지의 {@code callType}
     * @param receiverType 엣지의 {@code receiverType}. 없으면 null
     * @param parameterTypes 매개변수 타입 erasure의 바이너리 이름(원시 타입·배열 포함)
     */
    record Candidate(String methodId, String name, String declaringType, boolean external, boolean jdk, String callType,
                     String receiverType, List<String> parameterTypes, boolean varargs) {
    }

    /**
     * 파일을 볼 때 타입으로 가리지 못한 호출 하나(F-003-R28). 인자 가운데 모든 파일을 본 뒤에야 타입을 알 수 있는 것이 있어
     * (Lombok 합성 getter의 결과 등) 그때 후보를 다시 가린다.
     *
     * @param candidates 파일을 볼 때 남은 후보(둘 이상)
     * @param argTypes 인자마다 뒤에 알 수 있는 타입. 이미 후보와 맞춰 본 자리와 알 수 없는 자리는 null
     */
    record Deferred(Unit.Owner owner, SourceLocation location, List<Candidate> candidates, List<PendingCall.TypeExpr> argTypes) {
    }

    private final List<Deferred> deferred = new ArrayList<>();
    /** 메서드 ID → 반환 타입 erasure의 바이너리 이름(원시 타입·배열 포함). 인자로 넘긴 호출 결과의 타입을 읽을 때 쓴다. */
    private final Map<String, String> returnBinaries = new HashMap<>();

    CallResolver(JavaProject project) {
        this.project = project;
        this.graph = project.graph;
    }

    // ---- 색인 ----

    void index(String typeName, MethodInfo method) {
        methodsByType.computeIfAbsent(typeName, k -> new ArrayList<>()).add(method);
    }

    void add(PendingCall call) {
        pending.add(call);
    }

    void defer(Deferred choice) {
        deferred.add(choice);
    }

    /** 메서드의 반환 타입(erasure의 바이너리 이름, 원시 타입·배열 포함)을 적어 둔다. 모르면 부르지 않는다. */
    void returnBinary(String methodId, String binary) {
        if (binary != null) {
            returnBinaries.put(methodId, binary);
        }
    }

    /** 해석된 호출 하나를 엣지로 쓴다. 소스 없는 타입의 메서드면 설정에 따라 기록하지 않고 수만 센다. */
    void emit(Unit.Owner owner, Candidate c, SourceLocation location, boolean inferred, boolean ambiguous) {
        String target = c.methodId();
        if (c.external()) {
            if (!recordExternal(c.jdk())) {
                return;
            }
            target = project.externalMethod(c.methodId(), c.name(), false);
            project.typeId(TypeRef.external(c.declaringType()));
        }
        addCall(owner, target, location, c.callType(), c.receiverType(), inferred, ambiguous ? Map.of("ambiguous", true) : Map.of());
    }

    void superBuilder(String typeName, String builderType, String superTypeName, SourceLocation location) {
        superBuilders.put(typeName, builderType);
        if (superTypeName != null) {
            superBuilderParents.add(new Object[]{builderType, superTypeName, location});
        }
    }

    void addReflection(Reflection reflection) {
        reflections.add(reflection);
    }

    void addTypeArguments(String genericType, String subtype, List<String> arguments) {
        typeArguments.computeIfAbsent(genericType, k -> new ArrayList<>()).add(new TypeArguments(subtype, arguments));
    }

    /** 대상을 정적으로 알 수 없는 리플렉션 호출: 엣지를 만들지 않고 메서드당 INFO 진단 1건으로 남긴다. */
    void reflectionNotResolved(Unit.Owner owner, SourceLocation location) {
        unresolvedReflection.putIfAbsent(owner.methodId(), location);
    }

    /** 메서드 ID에서 매개변수 타입 목록을 읽는다. */
    static List<String> parametersOf(String methodId) {
        int open = methodId.indexOf('(');
        int close = methodId.lastIndexOf(')');
        if (open < 0 || close <= open + 1) {
            return List.of();
        }
        return List.of(methodId.substring(open + 1, close).split(","));
    }

    /** 메서드 ID에서 선언한 타입의 바이너리 이름을 읽는다. */
    static String typeOf(String methodId) {
        String rest = Ids.remainder(methodId);
        int hash = rest.indexOf('#');
        return hash < 0 ? rest : rest.substring(0, hash);
    }

    // ---- 엣지 쓰기 ----

    void addCall(Unit.Owner owner, String target, SourceLocation location, String callType, String receiverType, boolean inferred) {
        addCall(owner, target, location, callType, receiverType, inferred, Map.of());
    }

    void addCall(Unit.Owner owner, String target, SourceLocation location, String callType, String receiverType, boolean inferred,
                 Map<String, ?> extra) {
        Edge.Builder b = Edge.builder(owner.methodId(), target, EdgeKind.CALLS).location(location).attr("callType", callType);
        if (owner.viaLambda()) {
            b.attr("viaLambda", true);
        }
        if (receiverType != null) {
            b.attr("receiverType", receiverType);
        }
        if (inferred) {
            b.inferred();
        }
        b.attrs(extra);
        graph.addEdge(b.build());
    }

    /** 옵션에 따라 외부 호출을 기록할지 정하고, 기록하지 않으면 개수를 센다. */
    boolean recordExternal(boolean jdk) {
        switch (project.options.externalCalls()) {
            case ALL:
                return true;
            case NON_JDK:
                if (!jdk) {
                    return true;
                }
                break;
            default:
                break;
        }
        if (jdk) {
            project.skippedJdkCalls++;
        } else {
            project.skippedExternalCalls++;
        }
        return false;
    }

    /** 시그니처를 모르는 소스 없는 타입의 메서드 호출. {@code <FQN>#<이름>(?)}로 잇는다. */
    void externalUnknown(Unit.Owner owner, String typeFqn, String name, SourceLocation location, String callType, boolean inferred) {
        externalUnknown(owner, typeFqn, name, location, callType, inferred, null, Map.of());
    }

    void externalUnknown(Unit.Owner owner, String typeFqn, String name, SourceLocation location, String callType, boolean inferred,
                         String receiverType, Map<String, ?> extra) {
        if (!recordExternal(project.isJdk(typeFqn))) {
            return;
        }
        String target = project.externalMethod(Ids.javaMethod(typeFqn, name, "?"), name, true);
        project.typeId(TypeRef.external(typeFqn));
        addCall(owner, target, location, callType, receiverType, inferred, extra);
    }

    /**
     * 프로젝트 타입이 소스 없는 상위 타입에서 물려받았다고 추론한 메서드의 호출(F-003-R19): {@code <상위 FQN>#<이름>(?)}로
     * INFERRED, {@code attrs.inheritedFrom}. 상위 후보가 여럿이라 상위 클래스 쪽으로 고른 것도 엣지는 하나라 표시가 같다.
     */
    private void inheritedCall(Unit.Owner owner, String typeFqn, String name, SourceLocation location, String callType,
                               String receiverType) {
        // 상위 후보가 여럿이어도 잇는 대상은 하나다: 후보가 하나뿐인 엣지에는 ambiguous를 붙이지 않는다(F-003-R29)
        externalUnknown(owner, typeFqn, name, location, callType, true, receiverType, Map.of("inheritedFrom", typeFqn));
    }

    /**
     * 미해결 호출 엣지. 원문에는 인자를 적지 않고({@code <수신자>.<이름>(…)}) 인자 수는 엣지의 {@code argCount}에 남긴다(F-003-R8).
     *
     * @param argc 인자 수. 메서드 참조처럼 알 수 없으면 음수
     */
    void unresolved(Unit.Owner owner, String text, int argc, SourceLocation location) {
        project.unresolvedEdge(owner.methodId(), EdgeKind.CALLS, JavaProject.CAT_CALL, clip(text), location,
                argc < 0 ? Map.of() : Map.of("argCount", argc));
    }

    void instantiates(Unit.Owner owner, TypeRef type, SourceLocation location) {
        if (type == null || type.isUnresolved()) {
            return;
        }
        if (!type.isProject()) {
            JavaOptions.ExternalCalls mode = project.options.externalCalls();
            if (mode == JavaOptions.ExternalCalls.NONE
                    || (mode == JavaOptions.ExternalCalls.NON_JDK && project.isJdk(type.name()))) {
                return;
            }
        }
        graph.addEdge(Edge.of(owner.methodId(), project.typeId(type), EdgeKind.INSTANTIATES, location));
    }

    static String clip(String text) {
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) + "..." : t;
    }

    // ---- 미해석 호출의 해석 ----

    /** 모든 파일을 본 뒤 한 번 부른다. */
    void resolvePending() {
        for (Object[] link : superBuilderParents) {
            String parentBuilder = superBuilders.get((String) link[1]);
            if (parentBuilder != null) {
                graph.addEdge(Edge.of(Ids.javaType((String) link[0]), Ids.javaType(parentBuilder), EdgeKind.EXTENDS, (SourceLocation) link[2]));
            }
        }
        resolveSyntheticOverrides();
        for (PendingCall call : pending) {
            resolve(call);
        }
        pending.clear();
        for (Deferred choice : deferred) {
            resolveDeferred(choice);
        }
        deferred.clear();
        resolveReflections();
    }

    // ---- 타입으로 가리지 못했던 호출을 다시 가리기 (F-003-R28) ----

    /**
     * 모든 파일을 본 뒤에 알게 된 인자 타입(합성 getter·프로젝트 메서드의 반환 타입)으로 후보를 다시 가린다.
     * 분명히 맞지 않는 후보를 빼고 하나만 남으면 그것으로 INFERRED, 여럿이면 그 가운데 알게 된 자리의 타입이 전부 정확히 같은 하나,
     * 그래도 가리지 못하면 남은 후보 전부로 INFERRED + {@code ambiguous=true}다. 전부 빠지면 원래 후보 그대로다.
     */
    private void resolveDeferred(Deferred choice) {
        List<String> argTypes = new ArrayList<>();
        for (PendingCall.TypeExpr expr : choice.argTypes()) {
            argTypes.add(expr == null ? null : binaryOf(expr));
        }
        List<Candidate> kept = new ArrayList<>();
        List<Candidate> exact = new ArrayList<>();
        for (Candidate c : choice.candidates()) {
            boolean compatible = true;
            boolean same = true;
            List<String> params = c.parameterTypes();
            for (int i = 0; i < argTypes.size() && compatible; i++) {
                String arg = argTypes.get(i);
                if (arg == null || params.isEmpty()) {
                    continue;
                }
                boolean variable = c.varargs() && i >= params.size() - 1;
                String param = params.get(Math.min(i, params.size() - 1));
                Boolean accepts = accepts(param, arg);
                if (variable && param.endsWith("[]") && !Boolean.TRUE.equals(accepts)) {
                    // 가변 인자 자리에는 원소 하나도 올 수 있다
                    Boolean element = accepts(param.substring(0, param.length() - 2), arg);
                    accepts = Boolean.TRUE.equals(element) ? Boolean.TRUE : accepts == null || element == null ? null : Boolean.FALSE;
                }
                if (Boolean.FALSE.equals(accepts)) {
                    compatible = false;
                } else if (accepts == null || !param.equals(arg)) {
                    same = false;
                }
            }
            if (compatible) {
                kept.add(c);
                if (same) {
                    exact.add(c);
                }
            }
        }
        List<Candidate> chosen = kept.isEmpty() ? choice.candidates() : kept.size() > 1 && exact.size() == 1 ? exact : kept;
        for (Candidate c : chosen) {
            emit(choice.owner(), c, choice.location(), true, chosen.size() > 1);
        }
    }

    /** 미해석 호출의 결과 타입(erasure의 바이너리 이름, 원시 타입·배열 포함). 모르면 null. */
    private String binaryOf(PendingCall.TypeExpr expr) {
        PendingCall.TypeExpr evaluated = eval(expr);
        if (expr instanceof PendingCall.ResultOf result && result.call().resultBinary != null) {
            return result.call().resultBinary;
        }
        return evaluated instanceof PendingCall.Known known ? known.name() : null;
    }

    private static final Set<String> STRING_SUPERTYPES = Set.of("java.lang.String", "java.lang.Object", "java.lang.CharSequence",
            "java.io.Serializable", "java.lang.Comparable", "java.lang.constant.Constable", "java.lang.constant.ConstantDesc");
    private static final Set<String> NUMBER_SUPERTYPES = Set.of("java.lang.Object", "java.lang.Number", "java.io.Serializable",
            "java.lang.Comparable", "java.lang.constant.Constable", "java.lang.constant.ConstantDesc");
    private static final Set<String> ARRAY_SUPERTYPES = Set.of("java.lang.Object", "java.io.Serializable", "java.lang.Cloneable");

    /**
     * 매개변수 타입이 그 타입의 인자를 받는가. 받으면 TRUE, 받지 않으면 FALSE, 판단할 수 없으면 null.
     * 원시 타입·박싱 타입·{@code String}·배열은 상위 타입이 정해져 있어 그 밖의 타입 자리에는 오지 않는다({@code String} 값은 {@code byte[]} 자리에 오지 않는다).
     */
    private Boolean accepts(String param, String arg) {
        if (param.equals(arg) || param.equals("java.lang.Object")) {
            return true;
        }
        boolean paramArray = param.endsWith("[]");
        boolean argArray = arg.endsWith("[]");
        if (argArray) {
            if (!paramArray) {
                return ARRAY_SUPERTYPES.contains(param);
            }
            String paramElement = param.substring(0, param.length() - 2);
            String argElement = arg.substring(0, arg.length() - 2);
            boolean primitiveElement = isPrimitiveName(paramElement) || isPrimitiveName(argElement);
            return primitiveElement ? Boolean.FALSE : accepts(paramElement, argElement);
        }
        String argKind = VALUE_KINDS.get(arg);
        if (argKind != null) {
            if (paramArray) {
                return false;
            }
            String paramKind = VALUE_KINDS.get(param);
            if (paramKind != null) {
                return paramKind.equals(argKind);
            }
            return "string".equals(argKind) ? STRING_SUPERTYPES.contains(param) : NUMBER_SUPERTYPES.contains(param);
        }
        if (paramArray || VALUE_KINDS.containsKey(param)) {
            // 배열·문자열·숫자 자리에 그 밖의 타입의 값은 오지 않는다(Object 등 넓은 타입의 값을 캐스트 없이 넘길 수 없다)
            return false;
        }
        if (isProjectType(arg) && isProjectType(param)) {
            return isSubtype(arg, param) ? Boolean.TRUE : null;
        }
        return null;
    }

    private static boolean isPrimitiveName(String name) {
        return Set.of("boolean", "byte", "short", "char", "int", "long", "float", "double").contains(name);
    }

    // ---- Lombok 합성 메서드가 낀 재정의 (F-003-R22) ----

    /**
     * 컴파일러는 Lombok 합성 메서드를 보지 못하므로, 합성 메서드가 상위 선언을 구현한 경우와 상위 타입의 합성 메서드를 하위가 재정의한 경우를
     * 판정하지 못한다. 모든 파일을 본 뒤 색인으로 한 번 더 본다: 프로젝트 상위 타입을 가지마다 올라가며 이름·매개변수 수·위치별 매개변수 타입
     * (erasure의 바이너리 이름, 미해석 타입은 표기)이 같은 가장 가까운 선언을 찾고, 둘 중 하나라도 합성 메서드면
     * {@code OVERRIDES}(RESOLVED, {@code attrs.lombok=true})를 만든다. 둘 다 소스에 선언한 메서드면 컴파일러의 판정(F-003-R5, R17)에 맡긴다.
     */
    private void resolveSyntheticOverrides() {
        boolean any = methodsByType.values().stream().anyMatch(methods -> methods.stream().anyMatch(MethodInfo::lombok));
        if (!any) {
            return;
        }
        for (String type : new java.util.TreeSet<>(methodsByType.keySet())) {
            List<SuperLink> supers = superLinks(type, Map.of());
            if (supers.isEmpty()) {
                continue;
            }
            for (MethodInfo method : methodsByType.get(type)) {
                if (method.constructor() || method.isStatic() || isPrivate(method)) {
                    continue;
                }
                Set<String> seen = new HashSet<>();
                for (SuperLink link : supers) {
                    syntheticOverride(method, link, seen, 0);
                }
            }
        }
    }

    /**
     * 프로젝트 상위 타입 하나와, 그 타입의 타입 매개변수 이름 → 하위 타입이 넘긴 타입 인자(바이너리 이름).
     * 타입 변수·와일드카드를 넘겼거나 인자를 적지 않았으면 그 매개변수는 들어 있지 않다.
     */
    private record SuperLink(String type, Map<String, String> typeArguments) {
    }

    private List<SuperLink> superLinks(String typeName, Map<String, String> own) {
        List<SuperLink> links = new ArrayList<>();
        List<String> ownParameters = typeParameterNames(typeName);
        for (Edge e : graph.outgoing(Ids.javaType(typeName), EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS)) {
            String target = Ids.remainder(e.to());
            if (!e.to().startsWith("java:type:") || !isProjectType(target)) {
                continue;
            }
            Map<String, String> arguments = new HashMap<>();
            List<String> given = e.attrList("typeArguments");
            List<String> parameters = typeParameterNames(target);
            for (int i = 0; i < given.size() && i < parameters.size(); i++) {
                String argument = given.get(i);
                // 하위 타입 자신의 타입 변수를 넘겼으면 그 변수에 주어진 인자로 바꾼다(주어지지 않았으면 모른다)
                String bound = ownParameters.contains(argument) ? own.get(argument) : "?".equals(argument) ? null : argument;
                if (bound != null) {
                    arguments.put(parameters.get(i), bound);
                }
            }
            links.add(new SuperLink(target, arguments));
        }
        return links;
    }

    /** 타입의 타입 매개변수 이름({@code attrs.typeParameters}의 {@code "T extends X"}에서 이름만). */
    private List<String> typeParameterNames(String typeName) {
        List<String> names = new ArrayList<>();
        for (String parameter : graph.node(Ids.javaType(typeName)).map(n -> n.attrList("typeParameters")).orElse(List.of())) {
            String trimmed = parameter.trim();
            int space = trimmed.indexOf(' ');
            names.add(space < 0 ? trimmed : trimmed.substring(0, space));
        }
        return names;
    }

    private boolean isPrivate(MethodInfo method) {
        return graph.node(method.id()).map(n -> n.attrList("modifiers").contains("private")).orElse(false);
    }

    /** link의 타입과 그 위에서 가장 가까운 재정의 대상을 찾아, 합성 메서드가 낀 것이면 잇는다. 선언을 찾았으면(잇지 않았어도) true. */
    private boolean syntheticOverride(MethodInfo method, SuperLink link, Set<String> seen, int depth) {
        if (depth > 50 || !seen.add(link.type())) {
            return false;
        }
        for (MethodInfo candidate : methodsByType.getOrDefault(link.type(), List.of())) {
            if (candidate.constructor() || candidate.isStatic() || !candidate.name().equals(method.name())
                    || candidate.parameterTypes().size() != method.parameterTypes().size() || isPrivate(candidate)
                    || !sameParameters(method, candidate, link.typeArguments())) {
                continue;
            }
            if ((method.lombok() || candidate.lombok()) && graph.edges(method.id(), EdgeKind.OVERRIDES, candidate.id()).isEmpty()) {
                graph.addEdge(Edge.builder(method.id(), candidate.id(), EdgeKind.OVERRIDES).location(nameLocation(method))
                        .attr("lombok", true).build());
            }
            return true;
        }
        boolean above = false;
        for (SuperLink next : superLinks(link.type(), link.typeArguments())) {
            above |= syntheticOverride(method, next, seen, depth + 1);
        }
        return above;
    }

    /**
     * 위치마다 매개변수 타입이 같은가. 메서드 ID의 표기(erasure의 바이너리 이름, classpath에 없는 타입은 프로젝트 전체에서 정한 이름)로 비교하고,
     * 후보의 매개변수가 상위 타입의 타입 매개변수 그 자체({@code K})이고 하위 타입이 그 인자를 넘겼으면 그 인자로 비교한다.
     */
    private boolean sameParameters(MethodInfo method, MethodInfo candidate, Map<String, String> typeArguments) {
        List<String> written = typeArguments.isEmpty() ? List.of()
                : graph.node(candidate.id()).map(n -> n.attrList("parameterTypes")).orElse(List.of());
        for (int i = 0; i < method.parameterTypes().size(); i++) {
            String own = method.parameterTypes().get(i);
            String given = i < written.size() ? typeArguments.get(written.get(i).trim()) : null;
            if (!own.equals(given != null ? given : candidate.parameterTypes().get(i))) {
                return false;
            }
        }
        return true;
    }

    /** OVERRIDES 엣지의 위치: 소스에 선언한 메서드는 이름, 합성 메서드는 그 노드의 위치(만든 필드나 애너테이션). */
    private SourceLocation nameLocation(MethodInfo method) {
        SourceLocation location = graph.node(method.id()).map(Node::location).orElse(null);
        if (location == null || method.lombok()) {
            return location;
        }
        return SourceLocation.of(location.file(), location.startLine(), location.startColumn(), location.startLine(),
                location.startColumn() + method.name().length());
    }

    // ---- 리플렉션 호출의 추론 (F-003-R20) ----

    /**
     * 적어 둔 리플렉션 호출마다 대상 타입을 정해 그 타입(과 프로젝트 상위 타입)의 이름·인자 수가 맞는 메서드로
     * {@code CALLS}(INFERRED, {@code callType=reflection}, {@code receiverType}=대상 타입)를 만든다.
     * 대상 타입이 타입 매개변수면 그 제네릭 클래스를 상속하며 타입 인자를 프로젝트 타입으로 고정한 하위 타입마다 만들고,
     * 어느 하위 타입을 거친 것인지는 {@code viaTypes}에 남긴다.
     */
    private void resolveReflections() {
        for (Reflection r : reflections) {
            // 대상 타입 → 그 타입으로 고정한 하위 타입들
            Map<String, Set<String>> targets = new java.util.TreeMap<>();
            if (r.directType() != null) {
                targets.put(r.directType(), new java.util.TreeSet<>());
            } else {
                for (TypeArguments given : typeArguments.getOrDefault(r.genericType(), List.of())) {
                    String type = r.typeArgument() < given.arguments().size() ? given.arguments().get(r.typeArgument()) : null;
                    if (type != null && isProjectType(type)) {
                        targets.computeIfAbsent(type, k -> new java.util.TreeSet<>()).add(given.subtype());
                    }
                }
            }
            // 대상 메서드 → (대상 타입들, 거친 하위 타입들, 후보가 여럿이었는가)
            Map<String, Set<String>> receiverTypes = new LinkedHashMap<>();
            Map<String, Set<String>> viaTypes = new HashMap<>();
            Set<String> ambiguous = new HashSet<>();
            PendingCall probe = new PendingCall(PendingCall.Kind.METHOD, r.owner(), r.name(), r.argc(), List.of(), r.location(),
                    r.location(), r.name());
            for (Map.Entry<String, Set<String>> target : targets.entrySet()) {
                List<MethodInfo> candidates = lookup(target.getKey(), probe, false);
                for (MethodInfo method : candidates) {
                    receiverTypes.computeIfAbsent(method.id(), k -> new java.util.TreeSet<>()).add(target.getKey());
                    viaTypes.computeIfAbsent(method.id(), k -> new java.util.TreeSet<>()).addAll(target.getValue());
                    if (candidates.size() > 1) {
                        ambiguous.add(method.id());
                    }
                }
            }
            if (receiverTypes.isEmpty()) {
                reflectionNotResolved(r.owner(), r.location());
                continue;
            }
            for (Map.Entry<String, Set<String>> e : receiverTypes.entrySet()) {
                Edge.Builder b = Edge.builder(r.owner().methodId(), e.getKey(), EdgeKind.CALLS).location(r.location()).inferred()
                        .attr("callType", "reflection");
                if (r.owner().viaLambda()) {
                    b.attr("viaLambda", true);
                }
                if (e.getValue().size() == 1) {
                    b.attr("receiverType", e.getValue().iterator().next());
                } else {
                    // 같은 선언(공통 상위 인터페이스의 메서드)에 여러 대상 타입이 닿았다
                    b.attr("receiverTypes", List.copyOf(e.getValue()));
                }
                if (!viaTypes.get(e.getKey()).isEmpty()) {
                    b.attr("viaTypes", List.copyOf(viaTypes.get(e.getKey())));
                }
                if (ambiguous.contains(e.getKey())) {
                    b.attr("ambiguous", true);
                }
                graph.addEdge(b.build());
            }
        }
        reflections.clear();
        unresolvedReflection.forEach((methodId, location) -> graph.report(Diagnostic.info(JavaProject.REFLECTION_CALL,
                "리플렉션 호출(Method.invoke)의 대상을 정적으로 알 수 없어 기록하지 않음: "
                        + graph.node(methodId).map(Node::qualifiedName).orElse(methodId), location)));
        unresolvedReflection.clear();
    }

    private PendingCall.TypeExpr resolve(PendingCall p) {
        if (p.result != null) {
            return p.result;
        }
        if (p.resolving) {
            return PendingCall.Marker.UNKNOWN;
        }
        p.resolving = true;
        p.result = doResolve(p);
        return p.result;
    }

    private PendingCall.TypeExpr eval(PendingCall.TypeExpr expr) {
        return expr instanceof PendingCall.ResultOf r ? resolve(r.call()) : expr;
    }

    private PendingCall.TypeExpr doResolve(PendingCall p) {
        PendingCall.TypeExpr receiver = p.receiver == null ? null : eval(p.receiver);
        List<MethodInfo> candidates = List.of();
        String receiverType = null;
        if (p.receiver == null) {
            for (String type : p.implicitTypes) {
                candidates = lookup(type, p, p.syntheticOnly);
                if (!candidates.isEmpty()) {
                    receiverType = type;
                    break;
                }
            }
        } else if (receiver instanceof PendingCall.Known known && isProjectType(known.name())) {
            receiverType = known.name();
            candidates = lookup(receiverType, p, p.syntheticOnly);
        }
        if (!candidates.isEmpty()) {
            return emit(p, candidates, receiverType);
        }
        if (p.fallback.isExternal() && !isProjectType(p.fallback.externalType())) {
            if (p.receiver instanceof PendingCall.ResultOf inner && inner.call().chainDeferred) {
                // 안쪽 링크가 체인 미해결을 이 링크에 맡겼는데 이 링크는 external 호출로 남는다: 안쪽 링크가 사라지지 않게 직접 남긴다
                externalChain(inner.call());
            }
            if (p.fallback.inherited()) {
                inheritedCall(p.owner, p.fallback.externalType(), p.name, p.location, p.fallback.callType(), p.fallback.receiverType());
            } else {
                externalUnknown(p.owner, p.fallback.externalType(), p.name, p.location, p.fallback.callType(), p.fallback.inferred());
            }
            return p.fallback.result() instanceof PendingCall.Known ? p.fallback.result() : externalResult(p.fallback.externalType(), p);
        }
        if (receiver instanceof PendingCall.Known known && !isProjectType(known.name())) {
            // 추적한 수신자 타입이 소스 없는 타입이다
            externalUnknown(p.owner, known.name(), p.name, p.location, externalCallType(p), false);
            if (p.kind == PendingCall.Kind.CONSTRUCTOR_REF) {
                instantiates(p.owner, TypeRef.external(known.name()), p.location);
            }
            return externalResult(known.name(), p);
        }
        if (receiver == PendingCall.Marker.EXTERNAL_TAIL) {
            // 체인의 안쪽 링크는 바깥 링크가 한 번에 남긴다
            if (p.usedAsReceiver) {
                p.chainDeferred = true;
            } else {
                externalChain(p);
            }
            return PendingCall.Marker.EXTERNAL_TAIL;
        }
        if (receiverType == null && p.receiver == null && !p.implicitTypes.isEmpty()) {
            receiverType = p.implicitTypes.get(0);
        }
        if (receiverType != null && isProjectType(receiverType) && !p.isConstructor()
                && (!p.syntheticOnly || p.kind == PendingCall.Kind.METHOD_REF)) {
            // 컴파일러가 보지 않은 호출인데 프로젝트 타입에 선언이 없다: 소스 없는 상위 타입에서 물려받은 메서드일 수 있다
            PendingCall.TypeExpr inherited = inheritedFromExternal(p, receiverType);
            if (inherited != null) {
                return inherited;
            }
        }
        unresolved(p.owner, p.text, p.argc, p.location);
        return PendingCall.Marker.UNKNOWN;
    }

    /**
     * 프로젝트 타입에서 찾지 못한 메서드가 {@code java.lang.Object}의 것이면 JDK 호출로,
     * 소스 없는 상위 타입이 정확히 하나면 그 타입의 메서드 호출(INFERRED)로 남긴다.
     * 여럿이면 상위 클래스 쪽으로 INFERRED(F-003-R19). 어느 쪽도 아니면 null.
     */
    private PendingCall.TypeExpr inheritedFromExternal(PendingCall p, String receiverType) {
        if (jdkMethodExists("java.lang.Object", p.name, p.argc)) {
            externalUnknown(p.owner, "java.lang.Object", p.name, p.location, externalCallType(p), false);
            return externalResult("java.lang.Object", p);
        }
        Set<String> external = new LinkedHashSet<>();
        // 상위 클래스 사슬 끝의 소스 없는 클래스(많아야 하나)
        String externalClass = null;
        Deque<String> queue = new ArrayDeque<>(List.of(receiverType));
        Set<String> classChain = new HashSet<>(List.of(receiverType));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String type = queue.poll();
            if (!seen.add(type)) {
                continue;
            }
            boolean isClass = classChain.contains(type)
                    && !"interface".equals(graph.node(Ids.javaType(type)).map(n -> n.attrString("typeKind")).orElse(null));
            for (Edge e : graph.outgoing(Ids.javaType(type), EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS)) {
                if (!e.to().startsWith("java:type:")) {
                    // 이름을 복원하지 못한 상위 타입이 있으면 어디서 물려받았는지 알 수 없다
                    return null;
                }
                String target = Ids.remainder(e.to());
                boolean superclass = isClass && e.kind() == EdgeKind.EXTENDS;
                if (isProjectType(target)) {
                    if (superclass) {
                        classChain.add(target);
                    }
                    queue.add(target);
                } else {
                    external.add(target);
                    if (superclass) {
                        externalClass = target;
                    }
                }
            }
        }
        String type;
        if (p.staticOnly) {
            // static 메서드는 인터페이스에서 물려받지 않는다
            type = externalClass;
        } else if (external.size() == 1) {
            type = external.iterator().next();
        } else if (p.receiver != null) {
            // 상위 후보가 여럿이면 상위 클래스 쪽이다. 엣지는 하나라 ambiguous는 붙이지 않는다(F-003-R29)
            type = externalClass;
        } else {
            // 수신자 없는 호출은 소스 없는 상위 타입이 둘 이상이면 미해결로 둔다(F-003 5절의 기존 가정)
            type = null;
        }
        if (type == null) {
            return null;
        }
        boolean explicitInstance = p.receiver != null && !p.staticOnly && p.kind == PendingCall.Kind.METHOD;
        inheritedCall(p.owner, type, p.name, p.location, externalCallType(p), explicitInstance ? receiverType : null);
        return externalResult(type, p);
    }

    // ---- JDK 타입의 메서드: 실행 중인 JDK에서 이름·인자 수로 찾는다 ----

    /** 소스 없는 타입의 메서드가 돌려주는 값. JDK 타입이고 같은 이름·인자 수의 메서드가 모두 같은 클래스를 돌려주면 그 타입, 아니면 꼬리. */
    private PendingCall.TypeExpr externalResult(String typeName, PendingCall p) {
        if (p.isConstructor() || p.argc < 0 || !project.isJdk(typeName)) {
            return PendingCall.Marker.EXTERNAL_TAIL;
        }
        String result = null;
        for (Method method : jdkMethods(typeName, p.name, p.argc)) {
            Class<?> raw = method.getReturnType();
            String returned = method.getGenericReturnType() instanceof TypeVariable || raw.isPrimitive() || raw.isArray() || raw == Object.class
                    ? "?" : raw.getName();
            if (result != null && !result.equals(returned)) {
                return PendingCall.Marker.EXTERNAL_TAIL;
            }
            result = returned;
        }
        return result == null || result.equals("?") ? PendingCall.Marker.EXTERNAL_TAIL : new PendingCall.Known(result);
    }

    private boolean jdkMethodExists(String typeName, String name, int argc) {
        return !jdkMethods(typeName, name, argc).isEmpty();
    }

    private List<Method> jdkMethods(String typeName, String name, int argc) {
        Class<?> type = jdkClasses.computeIfAbsent(typeName, n -> java.util.Optional.ofNullable(JdkTypes.systemClass(n))).orElse(null);
        if (type == null) {
            return List.of();
        }
        List<Method> found = new ArrayList<>();
        List<Method> all = new ArrayList<>();
        try {
            all.addAll(List.of(type.getMethods()));
            if (type.isInterface()) {
                all.addAll(List.of(Object.class.getMethods()));
            }
        } catch (LinkageError | RuntimeException e) {
            return List.of();
        }
        for (Method method : all) {
            int params = method.getParameterCount();
            if (method.getName().equals(name) && !method.isBridge() && !method.isSynthetic()
                    && (argc < 0 || params == argc || (method.isVarArgs() && argc >= params - 1))) {
                found.add(method);
            }
        }
        return found;
    }

    private static String externalCallType(PendingCall p) {
        return switch (p.kind) {
            case METHOD -> p.staticOnly ? "static" : "virtual";
            case SUPER_METHOD -> "super";
            case CONSTRUCTOR, SUPER_CONSTRUCTOR -> "constructor";
            case METHOD_REF, CONSTRUCTOR_REF -> "methodRef";
        };
    }

    /** 소스 없는 타입의 반환값에 이어 부른 체인: 체인당 미해결 엣지 1건과 INFO 진단 1건(F-003-R12). */
    private void externalChain(PendingCall p) {
        String text = clip(p.text);
        String id = Ids.unresolved(JavaProject.CAT_CALL, text);
        graph.declare(Node.builder(id, NodeKind.UNRESOLVED).name(text).qualifiedName(text).location(p.chainLocation)
                .attr("category", JavaProject.CAT_CALL).build());
        Edge.Builder b = Edge.builder(p.owner.methodId(), id, EdgeKind.CALLS).unresolved().location(p.chainLocation)
                .attr("externalChain", true);
        if (p.argc >= 0) {
            b.attr("argCount", p.argc);
        }
        if (p.owner.viaLambda()) {
            b.attr("viaLambda", true);
        }
        graph.addEdge(b.build());
        graph.report(Diagnostic.info(DiagnosticCodes.UNRESOLVED_REFERENCE,
                "소스 없는 타입의 반환값에 이어 부른 호출 체인이라 해석하지 않음(" + JavaProject.CAT_CALL + "): " + text, p.chainLocation));
    }

    private PendingCall.TypeExpr emit(PendingCall p, List<MethodInfo> candidates, String receiverType) {
        List<MethodInfo> chosen = candidates.size() > 1 ? narrow(candidates, p.argTypes) : candidates;
        boolean inferred = candidates.size() > 1 || p.inferredReceiver;
        boolean ambiguous = chosen.size() > 1;
        Set<String> results = new LinkedHashSet<>();
        for (MethodInfo method : chosen) {
            String declaring = typeOf(method.id());
            Edge.Builder b = Edge.builder(p.owner.methodId(), method.id(), EdgeKind.CALLS).location(p.location)
                    .attr("callType", callType(p, method, declaring));
            if (p.owner.viaLambda()) {
                b.attr("viaLambda", true);
            }
            if (!method.constructor() && !method.isStatic() && receiverType != null && !receiverType.equals(declaring)) {
                b.attr("receiverType", receiverType);
            }
            if (method.lombok()) {
                b.attr("lombok", true);
            }
            if (inferred) {
                b.inferred();
            }
            if (ambiguous) {
                b.attr("ambiguous", true);
            }
            graph.addEdge(b.build());
            dependsOn(p, declaring);
            String result = method.constructor() ? declaring : method.returnsReceiver() ? receiverType : method.returnType();
            results.add(result == null ? "?" : result);
        }
        if (p.kind == PendingCall.Kind.CONSTRUCTOR || p.kind == PendingCall.Kind.CONSTRUCTOR_REF) {
            instantiates(p.owner, TypeRef.project(receiverType), p.location);
        }
        Set<String> binaries = new HashSet<>();
        for (MethodInfo method : chosen) {
            binaries.add(method.constructor() ? typeOf(method.id()) : String.valueOf(returnBinaries.get(method.id())));
        }
        p.resultBinary = binaries.size() == 1 && !binaries.contains("null") ? binaries.iterator().next() : null;
        if (results.size() == 1 && !results.contains("?")) {
            return new PendingCall.Known(results.iterator().next());
        }
        return PendingCall.Marker.UNKNOWN;
    }

    private String callType(PendingCall p, MethodInfo method, String declaring) {
        return switch (p.kind) {
            case CONSTRUCTOR, SUPER_CONSTRUCTOR -> "constructor";
            case METHOD_REF, CONSTRUCTOR_REF -> "methodRef";
            case SUPER_METHOD -> "super";
            case METHOD -> method.isStatic() ? "static"
                    : "interface".equals(graph.node(Ids.javaType(declaring)).map(n -> n.attrString("typeKind")).orElse(null)) ? "interface"
                    : "virtual";
        };
    }

    /** 호출한 메서드를 선언한 타입으로의 의존(F-002의 DEPENDS_ON 규칙). 이미 더 강한 관계가 있으면 만들지 않는다. */
    private void dependsOn(PendingCall p, String declaring) {
        String from = typeOf(p.owner.methodId());
        if (from.equals(declaring)) {
            return;
        }
        String fromId = Ids.javaType(from);
        String toId = Ids.javaType(declaring);
        for (EdgeKind kind : List.of(EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS, EdgeKind.ASSOCIATES, EdgeKind.DEPENDS_ON)) {
            if (!graph.edges(fromId, kind, toId).isEmpty()) {
                return;
            }
        }
        graph.addEdge(Edge.of(fromId, toId, EdgeKind.DEPENDS_ON, p.location));
    }

    private boolean isProjectType(String name) {
        return projectTypes.computeIfAbsent(name,
                n -> graph.node(Ids.javaType(n)).filter(node -> node.kind() == NodeKind.JAVA_TYPE && node.declared()).isPresent());
    }

    private List<String> supertypesOf(String typeName) {
        return supertypes.computeIfAbsent(typeName, name -> {
            List<String> names = new ArrayList<>();
            for (Edge e : graph.outgoing(Ids.javaType(name), EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS)) {
                String target = Ids.remainder(e.to());
                if (e.to().startsWith("java:type:") && isProjectType(target) && !names.contains(target)) {
                    names.add(target);
                }
            }
            return names;
        });
    }

    /**
     * typeName과 그 상위 타입(프로젝트 타입)에서 이름·인자 수가 맞는 메서드를 찾는다. 가까운 타입의 선언이 같은 매개변수의 먼 선언을 가린다.
     * 생성자는 그 타입의 것만 본다.
     */
    private List<MethodInfo> lookup(String typeName, PendingCall p, boolean syntheticOnly) {
        boolean constructor = p.isConstructor();
        Map<String, MethodInfo> found = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>(List.of(typeName));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String type = queue.poll();
            if (!seen.add(type)) {
                continue;
            }
            for (MethodInfo method : methodsByType.getOrDefault(type, List.of())) {
                if (method.constructor() != constructor || (!constructor && !method.name().equals(p.name))
                        || (p.staticOnly && !method.isStatic()) || (syntheticOnly && !method.lombok()) || !arityMatches(method, p.argc)) {
                    continue;
                }
                found.putIfAbsent(String.join(",", method.parameterTypes()), method);
            }
            if (constructor) {
                break;
            }
            queue.addAll(supertypesOf(type));
        }
        return List.copyOf(found.values());
    }

    private static boolean arityMatches(MethodInfo method, int argc) {
        int params = method.parameterTypes().size();
        return argc < 0 || params == argc || (method.varargs() && argc >= params - 1);
    }

    // ---- 인자 타입으로 후보 줄이기 ----

    /** 인자 타입을 아는 자리에서 분명히 맞지 않는 후보를 뺀다. 전부 빠지면 원래 후보를 그대로 둔다. */
    private List<MethodInfo> narrow(List<MethodInfo> candidates, List<PendingCall.TypeExpr> argExprs) {
        if (argExprs.isEmpty()) {
            return candidates;
        }
        List<String> argTypes = new ArrayList<>();
        for (PendingCall.TypeExpr expr : argExprs) {
            argTypes.add(eval(expr) instanceof PendingCall.Known known ? known.name() : null);
        }
        List<MethodInfo> kept = new ArrayList<>();
        for (MethodInfo method : candidates) {
            boolean compatible = true;
            List<String> params = method.parameterTypes();
            for (int i = 0; i < argTypes.size() && i < params.size() && compatible; i++) {
                boolean lastVarargs = method.varargs() && i == params.size() - 1;
                String param = lastVarargs && params.get(i).endsWith("[]") ? params.get(i).substring(0, params.get(i).length() - 2) : params.get(i);
                compatible = argTypes.get(i) == null || mayAccept(param, argTypes.get(i));
            }
            if (compatible) {
                kept.add(method);
            }
        }
        return kept.isEmpty() ? candidates : kept;
    }

    private static final Map<String, String> VALUE_KINDS = Map.ofEntries(
            Map.entry("java.lang.String", "string"), Map.entry("boolean", "boolean"), Map.entry("java.lang.Boolean", "boolean"),
            Map.entry("char", "char"), Map.entry("java.lang.Character", "char"),
            Map.entry("byte", "number"), Map.entry("short", "number"), Map.entry("int", "number"), Map.entry("long", "number"),
            Map.entry("float", "number"), Map.entry("double", "number"),
            Map.entry("java.lang.Byte", "number"), Map.entry("java.lang.Short", "number"), Map.entry("java.lang.Integer", "number"),
            Map.entry("java.lang.Long", "number"), Map.entry("java.lang.Float", "number"), Map.entry("java.lang.Double", "number"));

    /** 매개변수 타입이 그 인자 타입을 받을 수 있는가. 판단할 수 없으면 true. */
    private boolean mayAccept(String param, String arg) {
        if (param.equals(arg) || param.equals("java.lang.Object")) {
            return true;
        }
        String paramKind = VALUE_KINDS.get(param);
        String argKind = VALUE_KINDS.get(arg);
        if (paramKind != null && argKind != null) {
            return paramKind.equals(argKind);
        }
        boolean paramProject = isProjectType(param);
        boolean argProject = isProjectType(arg);
        if (paramKind != null) {
            // 문자열·숫자 자리에 프로젝트 타입이나 배열은 오지 않는다
            return !argProject && !arg.endsWith("[]");
        }
        if (argKind != null) {
            return !paramProject && !param.endsWith("[]");
        }
        if (argProject && paramProject) {
            return isSubtype(arg, param);
        }
        return true;
    }

    private boolean isSubtype(String type, String ancestor) {
        Deque<String> queue = new ArrayDeque<>(List.of(type));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String t = queue.poll();
            if (t.equals(ancestor)) {
                return true;
            }
            if (seen.add(t)) {
                queue.addAll(supertypesOf(t));
            }
        }
        return false;
    }
}
