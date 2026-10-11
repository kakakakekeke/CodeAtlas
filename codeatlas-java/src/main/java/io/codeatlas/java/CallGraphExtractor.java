package io.codeatlas.java;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.SourceLocation;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.TextBlock;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.InstanceofExpression;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.ArrayCreation;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.CreationReference;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.MethodReference;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.TypeLiteral;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeMethodReference;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F-003 Java 콜 그래프. CALLS, INSTANTIATES, OVERRIDES, ACCESSES를 만든다.
 * 호출 대상은 JDT가 정적으로 해석한 선언이다. JDT가 해석하지 못한 호출은 수신자 타입에 대해 아는 것과 함께
 * {@link PendingCall}로 적어 두고, 모든 파일을 본 뒤 {@link CallResolver}가 Lombok 합성 멤버 등에서 대상을 찾는다.
 * 찾지 못하면 미해결로 남기고, classpath에 없는 외부 타입의 호출임이 분명하면 {@code <FQN>#<이름>(?)} external 메서드로 잇는다.
 */
final class CallGraphExtractor extends ASTVisitor {

    private static final int MAX_TRACK_DEPTH = 8;
    /** 미해결 호출 원문의 인자 자리. 인자 수는 원문이 아니라 엣지의 {@code argCount}에 남긴다(F-003-R8). */
    private static final String ARGUMENTS = "(…)";

    private final Unit unit;
    private final JavaProject project;
    private final GraphModel graph;
    private final CallResolver calls;
    /** 이 파일에서 만든 미해석 호출(호출 노드마다 하나). */
    private final Map<ASTNode, PendingCall> pendingByNode = new IdentityHashMap<>();

    CallGraphExtractor(Unit unit) {
        this.unit = unit;
        this.project = unit.project;
        this.graph = project.graph;
        this.calls = project.calls;
    }

    void run() {
        unit.cu.accept(this);
    }

    // ---- 호출 ----

    @Override
    public boolean visit(MethodInvocation node) {
        Unit.Owner owner = unit.owner(node);
        if (owner == null) {
            return true;
        }
        Target target = target(node);
        if (target != null) {
            call(owner, target, node.getName(), null, node.getExpression(), node.arguments());
            if (isReflectiveInvoke(target.binding())) {
                reflectiveInvoke(node, owner);
            }
        } else {
            pending(node);
        }
        return true;
    }

    // ---- 리플렉션 호출의 추론 (F-003-R20) ----

    private static boolean isReflectiveInvoke(IMethodBinding binding) {
        ITypeBinding declaring = binding.getMethodDeclaration().getDeclaringClass();
        return binding.getName().equals("invoke") && declaring != null && "java.lang.reflect.Method".equals(declaring.getQualifiedName());
    }

    /**
     * {@code X.getClass().getDeclaredMethod("상수", ..)}(또는 {@code getMethod})의 결과를 같은 메서드 안에서 {@code .invoke(X, ..)}한 꼴이고
     * X의 선언 타입이 감싼 클래스의 타입 매개변수이거나 프로젝트 타입이면, 모든 파일을 본 뒤 그 타입의 메서드로 잇도록 적어 둔다.
     * 그 꼴이 아니면 대상을 알 수 없다: 메서드당 INFO 진단 1건으로 남긴다.
     */
    private void reflectiveInvoke(MethodInvocation invoke, Unit.Owner owner) {
        SourceLocation location = unit.loc(invoke.getName());
        List<MethodInvocation> lookups = invoke.arguments().isEmpty() ? null : methodLookups(invoke);
        IVariableBinding target = lookups == null ? null : variable((Expression) invoke.arguments().get(0));
        if (target == null) {
            calls.reflectionNotResolved(owner, location);
            return;
        }
        ITypeBinding declared = target.getVariableDeclaration().getType();
        String genericType = null;
        int typeArgument = -1;
        String directType = null;
        if (declared != null && declared.isTypeVariable() && declared.getDeclaringClass() != null) {
            ITypeBinding[] parameters = declared.getDeclaringClass().getTypeParameters();
            for (int i = 0; i < parameters.length; i++) {
                if (parameters[i].isEqualTo(declared)) {
                    typeArgument = i;
                }
            }
            genericType = project.binary(declared.getDeclaringClass());
        } else {
            ITypeBinding reduced = Unit.reduce(declared);
            if (reduced != null && !reduced.isRecovered() && reduced.isFromSource() && !declared.isArray()) {
                directType = project.binary(reduced);
            }
        }
        if (directType == null && typeArgument < 0) {
            calls.reflectionNotResolved(owner, location);
            return;
        }
        for (MethodInvocation lookup : lookups) {
            String name = Constants.string((Expression) lookup.arguments().get(0));
            Expression on = unparen(lookup.getExpression());
            boolean sameObject = on instanceof MethodInvocation getClass && getClass.getName().getIdentifier().equals("getClass")
                    && getClass.arguments().isEmpty() && getClass.getExpression() != null
                    && target.isEqualTo(variable(getClass.getExpression()));
            if (name == null || !sameObject) {
                calls.reflectionNotResolved(owner, location);
                return;
            }
        }
        for (MethodInvocation lookup : lookups) {
            int argc = countable(lookup.arguments(), 1);
            if (argc < 0) {
                argc = countable(invoke.arguments(), 1);
            }
            calls.addReflection(new CallResolver.Reflection(owner, genericType, typeArgument, directType,
                    Constants.string((Expression) lookup.arguments().get(0)), argc, location));
        }
    }

    /** invoke의 수신자가 가리키는 {@code getDeclaredMethod}/{@code getMethod} 호출들: 그 호출 자체이거나 그 결과만 받은 지역 변수. 아니면 null. */
    private List<MethodInvocation> methodLookups(MethodInvocation invoke) {
        Expression receiver = invoke.getExpression() == null ? null : unparen(invoke.getExpression());
        List<Expression> values = new ArrayList<>();
        if (receiver instanceof SimpleName name && name.resolveBinding() instanceof IVariableBinding v && !v.isField() && !v.isParameter()) {
            ASTNode scope = null;
            for (ASTNode p = invoke.getParent(); p != null && scope == null; p = p.getParent()) {
                if (p instanceof MethodDeclaration || p instanceof org.eclipse.jdt.core.dom.Initializer) {
                    scope = p;
                }
            }
            if (scope == null) {
                return null;
            }
            scope.accept(new ASTVisitor() {
                @Override
                public boolean visit(VariableDeclarationFragment node) {
                    if (node.getInitializer() != null && v.isEqualTo(node.resolveBinding())) {
                        values.add(node.getInitializer());
                    }
                    return true;
                }

                @Override
                public boolean visit(Assignment node) {
                    if (node.getLeftHandSide() instanceof SimpleName left && v.isEqualTo(left.resolveBinding())) {
                        values.add(node.getRightHandSide());
                    }
                    return true;
                }
            });
        } else if (receiver != null) {
            values.add(receiver);
        }
        List<MethodInvocation> lookups = new ArrayList<>();
        for (Expression value : values) {
            Expression e = unparen(value);
            if (e instanceof MethodInvocation call && !call.arguments().isEmpty()
                    && (call.getName().getIdentifier().equals("getDeclaredMethod") || call.getName().getIdentifier().equals("getMethod"))) {
                lookups.add(call);
            } else {
                return null;
            }
        }
        return lookups.isEmpty() ? null : lookups;
    }

    /** 식이 가리키는 변수(지역 변수, 매개변수, {@code this.}를 붙였거나 붙이지 않은 필드). 변수가 아니면 null. */
    private static IVariableBinding variable(Expression expression) {
        Expression e = unparen(expression);
        if (e instanceof SimpleName name && name.resolveBinding() instanceof IVariableBinding v) {
            return v;
        }
        if (e instanceof FieldAccess access && access.getExpression() instanceof ThisExpression) {
            return access.resolveFieldBinding();
        }
        return null;
    }

    /** 앞의 skip개를 뺀 인자 수. 배열을 통째로 넘겨(가변 인자) 셀 수 없으면 -1. */
    private static int countable(List<?> arguments, int skip) {
        for (int i = skip; i < arguments.size(); i++) {
            ITypeBinding t = ((Expression) arguments.get(i)).resolveTypeBinding();
            if (t == null || t.isArray()) {
                return -1;
            }
        }
        return arguments.size() - skip;
    }

    @Override
    public boolean visit(TypeDeclaration node) {
        typeArguments(node, node.resolveBinding());
        return true;
    }

    @Override
    public boolean visit(AnonymousClassDeclaration node) {
        typeArguments(node, node.resolveBinding());
        return true;
    }

    /** 이 타입에서 본 제네릭 프로젝트 상위 타입들의 타입 인자를 적어 둔다(리플렉션 호출의 대상 타입을 정하는 데 쓴다). */
    private void typeArguments(ASTNode typeNode, ITypeBinding binding) {
        if (binding == null || binding.isRecovered()) {
            return;
        }
        String self = unit.typeName(typeNode);
        Set<String> seen = new HashSet<>();
        List<ITypeBinding> queue = new ArrayList<>();
        queue.add(binding);
        for (int i = 0; i < queue.size() && i < 200; i++) {
            ITypeBinding type = queue.get(i);
            List<ITypeBinding> supers = new ArrayList<>();
            if (type.getSuperclass() != null) {
                supers.add(type.getSuperclass());
            }
            supers.addAll(List.of(type.getInterfaces()));
            for (ITypeBinding s : supers) {
                if (s.isRecovered() || !seen.add(s.getKey() == null ? s.getQualifiedName() : s.getKey())) {
                    continue;
                }
                queue.add(s);
                ITypeBinding erasure = s.getErasure();
                if (!s.isParameterizedType() || erasure == null || !erasure.isFromSource()) {
                    continue;
                }
                List<String> arguments = new ArrayList<>();
                for (ITypeBinding argument : s.getTypeArguments()) {
                    ITypeBinding reduced = argument.isArray() ? null : Unit.reduce(argument);
                    arguments.add(reduced == null || reduced.isRecovered() ? null : project.binary(reduced));
                }
                calls.addTypeArguments(project.binary(erasure), self, arguments);
            }
        }
    }

    @Override
    public boolean visit(SuperMethodInvocation node) {
        Unit.Owner owner = unit.owner(node);
        if (owner == null) {
            return true;
        }
        IMethodBinding binding = node.resolveMethodBinding();
        String name = node.getName().getIdentifier();
        ITypeBinding enclosing = Unit.typeBinding(Unit.enclosingType(node));
        Target target = usable(binding, node.getName().getStartPosition(), node.arguments()) ? new Target(binding, false, false)
                : contradicted(binding, node.getName().getStartPosition(), node.arguments())
                ? alternative(binding, enclosing == null ? null : enclosing.getSuperclass(), false, node.arguments()) : null;
        if (target != null) {
            call(owner, target, node.getName(), "super", null, node.arguments());
        } else {
            PendingCall p = new PendingCall(PendingCall.Kind.SUPER_METHOD, owner, name, node.arguments().size(),
                    argumentTypes(node.arguments()), unit.loc(node.getName()), unit.loc(node), "super." + name + ARGUMENTS);
            superReceiver(p, node, "super", binding);
            calls.add(p);
        }
        return true;
    }

    @Override
    public boolean visit(ConstructorInvocation node) {
        Unit.Owner owner = unit.owner(node);
        if (owner == null) {
            return true;
        }
        IMethodBinding binding = node.resolveConstructorBinding();
        Target target = usable(binding, node.getStartPosition(), node.arguments()) ? new Target(binding, false, false)
                : contradicted(binding, node.getStartPosition(), node.arguments())
                ? alternative(binding, Unit.typeBinding(Unit.enclosingType(node)), false, node.arguments()) : null;
        if (target != null) {
            call(owner, target, node, "constructor", null, node.arguments());
        } else {
            PendingCall p = new PendingCall(PendingCall.Kind.SUPER_CONSTRUCTOR, owner, Ids.CONSTRUCTOR_NAME, node.arguments().size(),
                    argumentTypes(node.arguments()), unit.loc(node), unit.loc(node), "this" + ARGUMENTS);
            ASTNode type = Unit.enclosingType(node);
            p.receiver = type == null ? PendingCall.Marker.UNKNOWN : new PendingCall.Known(unit.typeName(type));
            p.syntheticOnly = true;
            calls.add(p);
        }
        return true;
    }

    @Override
    public boolean visit(SuperConstructorInvocation node) {
        Unit.Owner owner = unit.owner(node);
        if (owner == null) {
            return true;
        }
        IMethodBinding binding = node.resolveConstructorBinding();
        ITypeBinding enclosing = Unit.typeBinding(Unit.enclosingType(node));
        Target target = constructorTarget(binding, node.getStartPosition(), node.arguments(), enclosing == null ? null : enclosing.getSuperclass());
        if (target != null) {
            call(owner, target, node, "constructor", null, node.arguments());
        } else {
            PendingCall p = new PendingCall(PendingCall.Kind.SUPER_CONSTRUCTOR, owner, Ids.CONSTRUCTOR_NAME, node.arguments().size(),
                    argumentTypes(node.arguments()), unit.loc(node), unit.loc(node), "super" + ARGUMENTS);
            superReceiver(p, node, "constructor", binding);
            calls.add(p);
        }
        return true;
    }

    /**
     * 상위 클래스가 프로젝트 타입이면 그 타입의 합성 멤버에서 찾고, classpath에 없는 타입이면 그 타입의 external 호출로 남긴다.
     * 컴파일러가 고른 가까운 후보(closest)가 소스 없는 상위 클래스의 것이면 어느 오버로드인지 모르는 그 타입의 호출이다(F-003-R13).
     */
    private void superReceiver(PendingCall p, ASTNode node, String callType, IMethodBinding closest) {
        ITypeBinding type = Unit.typeBinding(Unit.enclosingType(node));
        ITypeBinding superclass = type == null ? null : type.getSuperclass();
        p.receiver = superclass != null && !superclass.isRecovered() && superclass.isFromSource()
                ? new PendingCall.Known(project.binary(superclass)) : PendingCall.Marker.UNKNOWN;
        p.syntheticOnly = true;
        String superFqn = missingSuperclass(node);
        if (superFqn != null) {
            p.fallback = PendingCall.Fallback.external(superFqn, callType, false);
        } else if (closest != null && superclass != null && !superclass.isRecovered()) {
            ITypeBinding declaring = closest.getMethodDeclaration().getDeclaringClass();
            if (declaring != null && !declaring.isRecovered() && !declaring.isFromSource()
                    && (!closest.isConstructor() || constructs(closest, superclass))) {
                String declaringName = project.binary(declaring);
                project.isJdk(declaring, declaringName);
                p.fallback = PendingCall.Fallback.external(declaringName, callType, false);
            }
        }
    }

    @Override
    public boolean visit(ClassInstanceCreation node) {
        Unit.Owner owner = unit.owner(node);
        if (owner == null) {
            return true;
        }
        SourceLocation location = unit.loc(node.getType());
        IMethodBinding binding = node.resolveConstructorBinding();
        AnonymousClassDeclaration anonymous = node.getAnonymousClassDeclaration();
        if (anonymous != null) {
            // 익명 타입의 생성자(소스에 없다)로 잇는다
            String anonymousName = unit.typeName(anonymous);
            List<String> params = new ArrayList<>();
            if (binding != null) {
                for (ITypeBinding p : binding.getMethodDeclaration().getParameterTypes()) {
                    params.add(project.binary(p));
                }
            }
            String constructor = project.syntheticMethod(anonymousName, Ids.CONSTRUCTOR_NAME, params, project.typeLocation(anonymousName));
            calls.addCall(owner, constructor, location, "constructor", null, false);
            graph.addEdge(Edge.of(owner.methodId(), Ids.javaType(anonymousName), EdgeKind.INSTANTIATES, location));
            return true;
        }
        // 컴파일러가 준 생성자를 믿을 수 없는가: 인자 불일치, 인자 수가 다른 후보, 만들려는 타입이 아닌 타입(java.lang.Object)의 생성자
        // 만들려는 타입이 풀지 못한 import의 이름이면 컴파일러가 준 생성자(다른 타입의 것)는 쓰지 않는다
        boolean shadowed = unit.importedInstead(node.getType().resolveBinding()) != null;
        Target target = shadowed ? null : constructorTarget(binding, node.getStartPosition(), node.arguments(), node.getType().resolveBinding());
        boolean bad = binding != null && target == null;
        TypeRef type = unit.ref(node.getType());
        String text = "new " + Unit.written(node.getType()) + ARGUMENTS;
        if (target != null) {
            call(owner, target, node.getType(), "constructor", null, node.arguments());
            calls.instantiates(owner, type, location);
        } else if (type == null || type.isUnresolved()) {
            calls.unresolved(owner, text, node.arguments().size(), location);
        } else if (type.isProject()) {
            // 선언된 생성자에는 맞는 것이 없다: Lombok 합성 생성자에서 찾는다
            PendingCall p = new PendingCall(PendingCall.Kind.CONSTRUCTOR, owner, Ids.CONSTRUCTOR_NAME, node.arguments().size(),
                    argumentTypes(node.arguments()), location, unit.loc(node), text);
            p.receiver = new PendingCall.Known(type.name());
            // 컴파일러가 보지 않은 곳이면 선언된 생성자도 후보다
            p.syntheticOnly = bad || unit.isBadCall(node.getStartPosition());
            calls.add(p);
        } else {
            calls.externalUnknown(owner, type.name(), Ids.CONSTRUCTOR_NAME, location, "constructor", false);
            calls.instantiates(owner, type, location);
        }
        return true;
    }

    @Override
    public boolean visit(EnumConstantDeclaration node) {
        ASTNode enumNode = Unit.enclosingType(node.getParent());
        if (enumNode == null) {
            return true;
        }
        IMethodBinding binding = node.resolveConstructorBinding();
        AnonymousClassDeclaration body = node.getAnonymousClassDeclaration();
        // 소스에 선언한 이 enum의 생성자로 해석됐는가. 맞는 생성자가 없으면 컴파일러는 가까운 후보나 java.lang.Enum의 생성자를 준다
        boolean declared = binding != null && !binding.isDefaultConstructor()
                && usable(binding, node.getStartPosition(), node.arguments()) && !unit.isBadCall(node.getName().getStartPosition())
                && constructs(binding, Unit.typeBinding(enumNode));
        if (!declared && !node.arguments().isEmpty()) {
            // 인자를 받는 생성자가 소스에 없다: Lombok 합성 생성자에서 찾고, 없으면 미해결이다
            PendingCall p = new PendingCall(PendingCall.Kind.SUPER_CONSTRUCTOR, new Unit.Owner(unit.clinit(enumNode), false),
                    Ids.CONSTRUCTOR_NAME, node.arguments().size(), argumentTypes(node.arguments()), unit.loc(node.getName()),
                    unit.loc(node), node.getName() + ARGUMENTS);
            p.receiver = new PendingCall.Known(unit.typeName(enumNode));
            p.syntheticOnly = true;
            calls.add(p);
        }
        if (declared || body != null) {
            Unit.Owner owner = new Unit.Owner(unit.clinit(enumNode), false);
            if (declared) {
                call(owner, binding, node.getName(), "constructor", null, node.arguments(), false);
            }
            if (body != null) {
                graph.addEdge(Edge.of(owner.methodId(), Ids.javaType(unit.typeName(body)), EdgeKind.INSTANTIATES, unit.loc(node.getName())));
            }
        }
        return true;
    }

    // ---- 메서드 참조 ----

    @Override
    public boolean visit(ExpressionMethodReference node) {
        methodReference(node, node.resolveMethodBinding(), node.getName(), node.getExpression() + "::" + node.getName(),
                node.getExpression(), null);
        return true;
    }

    @Override
    public boolean visit(TypeMethodReference node) {
        methodReference(node, node.resolveMethodBinding(), node.getName(), node.getType() + "::" + node.getName(), null, node.getType());
        return true;
    }

    @Override
    public boolean visit(SuperMethodReference node) {
        methodReference(node, node.resolveMethodBinding(), node.getName(), "super::" + node.getName(), null, null);
        return true;
    }

    @Override
    public boolean visit(CreationReference node) {
        Unit.Owner owner = unit.owner(node);
        if (owner == null) {
            return true;
        }
        IMethodBinding binding = node.resolveMethodBinding();
        if (usable(binding, -1, List.of())) {
            call(owner, binding, node, "methodRef", null, List.of(), false);
            calls.instantiates(owner, unit.ref(node.getType()), unit.loc(node));
        } else if (!node.getType().isArrayType()) {
            PendingCall p = new PendingCall(PendingCall.Kind.CONSTRUCTOR_REF, owner, Ids.CONSTRUCTOR_NAME, -1, List.of(),
                    unit.loc(node), unit.loc(node), node.getType() + "::new");
            p.receiver = known(unit.ref(node.getType()));
            p.syntheticOnly = true;
            calls.add(p);
        }
        return true;
    }

    private void methodReference(ASTNode node, IMethodBinding binding, SimpleName name, String text, Expression receiver, Type receiverType) {
        Unit.Owner owner = unit.owner(node);
        if (owner == null) {
            return;
        }
        if (usable(binding, -1, List.of())) {
            call(owner, binding, name, "methodRef", null, List.of(), false);
            return;
        }
        PendingCall p = new PendingCall(PendingCall.Kind.METHOD_REF, owner, name.getIdentifier(), -1, List.of(),
                unit.loc(name), unit.loc(node), text);
        if (receiver != null) {
            Described described = describeReceiver(receiver);
            p.receiver = described.type();
            p.syntheticOnly = described.fromCompiler();
        } else if (receiverType != null) {
            p.receiver = known(unit.ref(receiverType));
            p.syntheticOnly = true;
        } else {
            superReceiver(p, node, "methodRef", null);
        }
        calls.add(p);
    }

    private static PendingCall.TypeExpr known(TypeRef ref) {
        return ref == null || ref.isUnresolved() ? PendingCall.Marker.UNKNOWN : new PendingCall.Known(ref.name());
    }

    // ---- 엣지 만들기 ----

    /**
     * 컴파일러가 준 바인딩을 믿고 이을 수 있는가. 아니면 미해석 호출로 다뤄 모든 파일을 본 뒤에 찾거나 미해결로 남긴다.
     * 인자에 해석되지 않은 값이 있으면 컴파일러는 호출 자체에는 오류를 보고하지 않고 "가장 그럴듯한" 후보를 준다.
     * 그 후보의 인자 수가 호출과 맞지 않으면 가까운 후보일 뿐이다(F-003-R15).
     *
     * @param callStart 컴파일러가 인자 불일치를 보고하는 위치(메서드 이름 또는 호출 식의 시작). 메서드 참조처럼 없으면 -1
     */
    private boolean usable(IMethodBinding binding, int callStart, List<?> arguments) {
        return plausible(binding, callStart, arguments) && !contradictsUnresolvedParameter(binding, arguments);
    }

    /** 컴파일러가 오류 없이 준 후보이고 인자 수가 맞는가(F-003-R9, R15). */
    private boolean plausible(IMethodBinding binding, int callStart, List<?> arguments) {
        if (binding == null || binding.getMethodDeclaration().getDeclaringClass() == null || unit.isBadCall(callStart)) {
            return false;
        }
        if (!hasUnresolvedArgument(arguments)) {
            return true;
        }
        IMethodBinding decl = binding.getMethodDeclaration();
        int params = decl.getParameterTypes().length;
        return params == arguments.size() || (decl.isVarargs() && arguments.size() >= params - 1);
    }

    // ---- 미해석 타입 매개변수의 선언을 고른 호출: 다른 오버로드 찾기 (F-003-R21) ----

    /**
     * 호출의 대상으로 삼을 선언.
     *
     * @param inferred 미해석 타입이 섞인 자리를 표기로만 비교해 고른 것이다
     * @param replaced 컴파일러가 고른 선언이 아니라 여기서 찾은 다른 오버로드다
     */
    /**
     * @param others 가리지 못한 나머지 후보들. 비어 있지 않으면 binding과 이들 모두로 {@code ambiguous=true}로 잇는다(F-003-R24)
     */
    private record Target(IMethodBinding binding, boolean inferred, boolean replaced, List<IMethodBinding> others, boolean verified) {

        Target(IMethodBinding binding, boolean inferred, boolean replaced) {
            this(binding, inferred, replaced, List.of(), false);
        }

        Target(IMethodBinding binding, boolean inferred, boolean replaced, List<IMethodBinding> others) {
            this(binding, inferred, replaced, others, false);
        }
    }

    /**
     * 대상 하나(가리지 못한 호출이면 후보 전부)를 호출로 기록한다.
     * 가리지 못한 호출의 인자 가운데 모든 파일을 본 뒤에야 타입을 알 수 있는 것(Lombok 합성 getter의 결과 등)이 있으면
     * 후보를 적어 두었다가 그때 다시 가린다(F-003-R28).
     */
    private void call(Unit.Owner owner, Target target, ASTNode at, String callType, Expression receiver, List<?> arguments) {
        if (target.others().isEmpty()) {
            call(owner, target.binding(), at, callType, receiver, arguments, target.inferred(), false);
            return;
        }
        List<CallResolver.Candidate> candidates = new ArrayList<>();
        candidates.add(candidate(target.binding(), callType, receiver));
        for (IMethodBinding other : target.others()) {
            candidates.add(candidate(other, callType, receiver));
        }
        List<PendingCall.TypeExpr> later = trackedArguments(arguments);
        if (later == null) {
            for (CallResolver.Candidate c : candidates) {
                calls.emit(owner, c, unit.loc(at), true, true);
            }
        } else {
            calls.defer(new CallResolver.Deferred(owner, unit.loc(at), candidates, later));
        }
    }

    /**
     * 컴파일러도 식의 모양도 타입을 알려 주지 않는 인자마다 "모든 파일을 본 뒤에 알 수 있는 타입"(미해석 호출의 결과)을 적는다.
     * 이미 타입을 알아 후보와 맞춰 본 자리와 끝내 알 수 없는 자리는 null이다. 뒤에 알 수 있는 자리가 하나도 없으면 null을 돌려준다.
     */
    private List<PendingCall.TypeExpr> trackedArguments(List<?> arguments) {
        List<PendingCall.TypeExpr> types = new ArrayList<>();
        boolean any = false;
        for (Object a : arguments) {
            Expression argument = (Expression) a;
            PendingCall.TypeExpr type = null;
            // 컴파일러가 선언을 찾은 호출은 estimatedType이 이미 읽었다(오버로드마다 반환 타입이 달라 모르는 것은 뒤에도 모른다)
            boolean boundCall = unparen(argument) instanceof MethodInvocation invocation && target(invocation) != null;
            if (!(argument instanceof LambdaExpression) && !(argument instanceof MethodReference) && !boundCall
                    && estimatedType(argument, 0) == null) {
                PendingCall.TypeExpr described = describe(argument, 0).type();
                if (!(described instanceof PendingCall.Marker)) {
                    type = described;
                    any = true;
                }
            }
            types.add(type);
        }
        return any ? types : null;
    }

    // ---- 컴파일러가 타입을 주지 않은 인자 식의 타입 (F-003-R28) ----

    /**
     * 인자 식의 타입. 컴파일러가 준 타입이 있으면 그것이고, 없으면(식 안에 해석되지 않은 조각이 있다) 식의 모양에서 읽는다:
     * 문자열 리터럴이 낀 {@code +} 사슬은 {@code String}, 비교·논리식은 {@code boolean}, 캐스트·{@code new T[]}·{@code new T(..)}는 적은 타입,
     * 삼항은 양쪽이 같은 타입일 때 그 타입, {@code this}는 감싼 타입, 클래스 리터럴은 {@code Class},
     * 메서드 호출은 컴파일러가 찾은 선언의 반환 타입(이름·인자 수가 맞는 선언이 하나이거나 모두 같은 타입을 돌려줄 때).
     * 알 수 없으면 null이다.
     */
    private ITypeBinding estimatedType(Expression expression, int depth) {
        Expression e = unparen(expression);
        ITypeBinding type = e.resolveTypeBinding();
        if (type != null || depth > MAX_TRACK_DEPTH) {
            return type;
        }
        if (e instanceof InfixExpression infix) {
            InfixExpression.Operator op = infix.getOperator();
            if (op == InfixExpression.Operator.PLUS) {
                List<Expression> operands = new ArrayList<>(List.of(infix.getLeftOperand(), infix.getRightOperand()));
                for (Object extended : infix.extendedOperands()) {
                    operands.add((Expression) extended);
                }
                for (Expression operand : operands) {
                    Expression o = unparen(operand);
                    ITypeBinding t = o instanceof StringLiteral || o instanceof TextBlock ? null : estimatedType(o, depth + 1);
                    if (o instanceof StringLiteral || o instanceof TextBlock || (t != null && "java.lang.String".equals(t.getQualifiedName()))) {
                        return unit.cu.getAST().resolveWellKnownType("java.lang.String");
                    }
                }
                return null;
            }
            if (op == InfixExpression.Operator.EQUALS || op == InfixExpression.Operator.NOT_EQUALS || op == InfixExpression.Operator.LESS
                    || op == InfixExpression.Operator.LESS_EQUALS || op == InfixExpression.Operator.GREATER
                    || op == InfixExpression.Operator.GREATER_EQUALS || op == InfixExpression.Operator.CONDITIONAL_AND
                    || op == InfixExpression.Operator.CONDITIONAL_OR) {
                return unit.cu.getAST().resolveWellKnownType("boolean");
            }
            return null;
        }
        if (e instanceof InstanceofExpression || (e instanceof PrefixExpression prefix && prefix.getOperator() == PrefixExpression.Operator.NOT)) {
            return unit.cu.getAST().resolveWellKnownType("boolean");
        }
        if (e instanceof ConditionalExpression conditional) {
            ITypeBinding then = estimatedType(conditional.getThenExpression(), depth + 1);
            ITypeBinding otherwise = estimatedType(conditional.getElseExpression(), depth + 1);
            return then != null && otherwise != null && !then.isNullType() && project.binary(then).equals(project.binary(otherwise)) ? then : null;
        }
        if (e instanceof CastExpression cast) {
            return cast.getType().resolveBinding();
        }
        if (e instanceof ArrayCreation creation) {
            return creation.getType().resolveBinding();
        }
        if (e instanceof ClassInstanceCreation creation) {
            return creation.getAnonymousClassDeclaration() == null ? creation.getType().resolveBinding() : null;
        }
        if (e instanceof ThisExpression self) {
            return self.getQualifier() == null ? Unit.typeBinding(Unit.enclosingType(e)) : null;
        }
        if (e instanceof TypeLiteral) {
            return unit.cu.getAST().resolveWellKnownType("java.lang.Class");
        }
        if (e instanceof MethodInvocation invocation) {
            return declaredReturnType(invocation);
        }
        return null;
    }

    /**
     * 컴파일러가 선언은 찾았지만 인자를 풀지 못해 호출 식의 타입을 주지 않은 메서드 호출의 반환 타입.
     * 안쪽 인자를 몰라도 바깥 호출이 무엇을 돌려주는지는 선언에 적혀 있다. 다만 컴파일러가 고른 선언이 맞는 오버로드인지는 모르므로,
     * 이름·인자 수가 맞는 선언이 모두 같은 타입을 돌려줄 때만 쓴다. 타입 변수를 돌려주는 선언({@code <T> T get()})은 모른다.
     */
    private ITypeBinding declaredReturnType(MethodInvocation invocation) {
        IMethodBinding binding = invocation.resolveMethodBinding();
        if (binding == null || binding.getMethodDeclaration().getDeclaringClass() == null
                || shadowedReceiver(invocation.getExpression()) != null) {
            return null;
        }
        Expression receiver = invocation.getExpression() == null ? null : unparen(invocation.getExpression());
        boolean typeName = receiver instanceof Name n && n.resolveBinding() instanceof ITypeBinding;
        ITypeBinding start = lookupType(invocation, receiver, binding);
        List<IMethodBinding> candidates = overloads(binding, start != null ? start : binding.getMethodDeclaration().getDeclaringClass(),
                typeName, invocation.arguments().size(), true);
        ITypeBinding result = null;
        String name = null;
        for (IMethodBinding candidate : candidates) {
            ITypeBinding returned = candidate.getMethodDeclaration().getReturnType();
            ITypeBinding element = returned == null ? null : returned.isArray() ? returned.getElementType() : returned;
            if (element == null || element.isTypeVariable() || element.isCapture() || element.isWildcardType()
                    || "void".equals(element.getName())) {
                return null;
            }
            String binary = project.binary(returned);
            if (name != null && !name.equals(binary)) {
                return null;
            }
            name = binary;
            result = returned;
        }
        return result;
    }

    /** 호출 노드 → 대상(없으면 null 값). 체인의 바깥 링크가 안쪽 링크를 다시 물으므로 한 번만 정한다. */
    private final Map<MethodInvocation, Target> targets = new IdentityHashMap<>();
    private final Map<String, Boolean> resolvedHierarchies = new java.util.HashMap<>();

    /** 메서드 호출의 대상: 믿을 수 있는 컴파일러의 선택이거나, 믿지 못할 때 인자 타입으로 찾은 다른 오버로드. 없으면 null(미해석 호출로 다룬다). */
    private Target target(MethodInvocation node) {
        if (targets.containsKey(node)) {
            return targets.get(node);
        }
        if (shadowedReceiver(node.getExpression()) != null) {
            // 수신자의 타입이 풀지 못한 import의 이름을 컴파일러가 다른 타입으로 풀어 버린 것이다: 그 타입에서 찾은 메서드는 믿지 않는다
            targets.put(node, null);
            return null;
        }
        IMethodBinding binding = node.resolveMethodBinding();
        int start = node.getName().getStartPosition();
        Target target = null;
        if (usable(binding, start, node.arguments())) {
            target = new Target(binding, false, false);
            if (hasUnresolvedArgument(node.arguments())) {
                // 인자에 classpath에 없는 타입이 있으면 컴파일러는 먼저 선언된 후보를 준다: 타입 표기로 다시 고른다(F-003-R24)
                Expression receiver = node.getExpression() == null ? null : unparen(node.getExpression());
                boolean typeName = receiver instanceof Name n && n.resolveBinding() instanceof ITypeBinding;
                ITypeBinding start0 = lookupType(node, receiver, binding);
                target = amongOverloads(binding, start0 != null ? start0 : binding.getMethodDeclaration().getDeclaringClass(), typeName,
                        node.arguments());
            }
        } else if (contradicted(binding, start, node.arguments())) {
            Expression receiver = node.getExpression() == null ? null : unparen(node.getExpression());
            boolean typeName = receiver instanceof Name n && n.resolveBinding() instanceof ITypeBinding;
            target = alternative(binding, lookupType(node, receiver, binding), typeName, node.arguments());
        } else if (binding != null && binding.getMethodDeclaration().getDeclaringClass() != null && unit.isBadCall(start)
                && hasUnresolvedArgument(node.arguments())) {
            // 컴파일러가 인자 불일치로 본 호출인데 인자에 classpath에 없는 타입이 있다(X.class 등): 불일치가 그 탓일 수 있으므로
            // 타입 표기로 맞는 후보가 정확히 하나일 때만 그것으로 잇는다(F-003-R24). 아니면 전처럼 미해석 호출이다(F-003-R9)
            Expression receiver = node.getExpression() == null ? null : unparen(node.getExpression());
            boolean typeName = receiver instanceof Name n && n.resolveBinding() instanceof ITypeBinding;
            ITypeBinding start0 = lookupType(node, receiver, binding);
            ITypeBinding from = start0 != null ? start0 : binding.getMethodDeclaration().getDeclaringClass();
            if (from != null && !from.isRecovered()) {
                List<Target> matched = new ArrayList<>();
                List<Target> exact = new ArrayList<>();
                match(overloads(binding, from, typeName, node.arguments().size(), true), node.arguments(), matched, exact);
                Target single = pick(matched, exact);
                target = single == null ? null : new Target(single.binding(), true, true);
            }
        }
        targets.put(node, target);
        return target;
    }

    /** 생성자 호출의 대상. type은 만들려는 타입(또는 상위 클래스)이다. */
    private Target constructorTarget(IMethodBinding binding, int callStart, List<?> arguments, ITypeBinding type) {
        if (binding == null) {
            return null;
        }
        if (usable(binding, callStart, arguments)) {
            if (!constructs(binding, type)) {
                return null;
            }
            return hasUnresolvedArgument(arguments) ? amongOverloads(binding, type, false, arguments) : new Target(binding, false, false);
        }
        return contradicted(binding, callStart, arguments) && constructs(binding, type) ? alternative(binding, type, false, arguments) : null;
    }

    /** 인자 수까지는 맞는 후보인데 미해석 타입 매개변수 자리에 그 타입일 수 없는 인자가 왔는가. */
    private boolean contradicted(IMethodBinding binding, int callStart, List<?> arguments) {
        return plausible(binding, callStart, arguments) && contradictsUnresolvedParameter(binding, arguments);
    }

    /**
     * 컴파일러가 고른 선언의 매개변수 중 classpath에 없는 타입의 자리에, 그 타입일 수 없는 해석된 타입의 인자가 왔는가.
     * 컴파일러는 미해석 매개변수 타입에 무엇이든 맞는다고 보고 그 선언을 고른다(같은 이름의 상위 오버로드를 부른 호출이 자기 호출이 된다).
     * 미해석 타입의 인자도 무엇에든 맞는다고 보므로, 원시 타입·final 클래스 매개변수 자리에 미해석 타입의 값이 온 것도 같다.
     * 가변 인자 자리는 보지 않는다.
     */
    private boolean contradictsUnresolvedParameter(IMethodBinding binding, List<?> arguments) {
        if (binding == null || arguments.isEmpty()) {
            return false;
        }
        ITypeBinding[] params = binding.getMethodDeclaration().getParameterTypes();
        int fixed = binding.getMethodDeclaration().isVarargs() ? params.length - 1 : params.length;
        for (int i = 0; i < fixed && i < arguments.size(); i++) {
            ITypeBinding argument = ((Expression) arguments.get(i)).resolveTypeBinding();
            if (isUnresolvedType(params[i]) ? cannotBeUnresolvedType(argument) : cannotAcceptUnresolvedType(params[i], argument)) {
                return true;
            }
        }
        return false;
    }

    /** 반대 방향: 해석된 매개변수 타입이 원시 타입이나 final 클래스({@code String})인데 인자가 classpath에 없는 타입의 값이다. */
    private static boolean cannotAcceptUnresolvedType(ITypeBinding param, ITypeBinding argument) {
        if (argument == null || !isUnresolvedType(argument)) {
            return false;
        }
        if (param.isPrimitive()) {
            return true;
        }
        if (param.isArray()) {
            // 배열 자리에 배열이 아닌 값은 오지 않는다. 배열끼리는 원소 타입의 관계를 알 수 없다
            return !argument.isArray();
        }
        ITypeBinding erasure = param.getErasure();
        return !param.isTypeVariable() && erasure != null && Modifier.isFinal(erasure.getModifiers());
    }

    /**
     * 해석된 타입이고 그 계층에 classpath에 없는 상위 타입이 없다: 어떤 미해석 타입의 값도 될 수 없다({@code String}, {@code int} 등).
     * 미해석 타입을 상속한 프로젝트 타입, null, 타입 변수는 될 수도 있으므로 false다.
     */
    private boolean cannotBeUnresolvedType(ITypeBinding argument) {
        if (argument == null || argument.isNullType() || argument.isTypeVariable() || argument.isCapture()
                || argument.isWildcardType() || argument.isIntersectionType()) {
            return false;
        }
        // 타입 인자만 classpath에 없는 Set<? extends Missing>은 복구된 바인딩이지만 erasure(Set)는 해석된 타입이다
        if (isUnresolvedType(argument)) {
            return false;
        }
        ITypeBinding element = argument.isArray() ? argument.getElementType() : argument;
        return element != null && (element.isPrimitive() || (!shadowedByImport(element) && hierarchyResolved(element, 0)));
    }

    /**
     * 이 파일이 같은 단순 이름을 다른 FQN으로 단일 import했는가. import한 타입이 classpath에 없으면 컴파일러가 그 이름을
     * {@code java.lang}이나 같은 패키지의 타입으로 풀어 버린다({@code import org.activiti.bpmn.model.Process} → {@code java.lang.Process}).
     * 그런 바인딩은 소스가 뜻한 타입이 아니므로 해석된 타입으로 치지 않는다.
     */
    /**
     * 수신자(타입 이름 또는 값)의 타입이, 이 파일이 단일 import했지만 컴파일러가 풀지 못해 다른 타입으로 풀어 버린 것이면
     * import의 FQN. 아니면 null(F-003-R23).
     */
    private String shadowedReceiver(Expression receiver) {
        if (receiver == null || unit.unresolvedImports.isEmpty()) {
            return null;
        }
        Expression e = unparen(receiver);
        if (e instanceof ThisExpression || e instanceof SuperFieldAccess) {
            return null;
        }
        ITypeBinding type = e instanceof Name n && n.resolveBinding() instanceof ITypeBinding named ? named : e.resolveTypeBinding();
        return type == null || type.isArray() ? null : unit.importedInstead(type);
    }

    private boolean shadowedByImport(ITypeBinding type) {
        ITypeBinding erasure = type.getErasure() == null ? type : type.getErasure();
        if (erasure.isPrimitive() || erasure.isArray()) {
            return false;
        }
        ITypeBinding top = erasure;
        while (top.getDeclaringClass() != null) {
            top = top.getDeclaringClass();
        }
        String imported = unit.singleImports.get(top.getName());
        return imported != null && !imported.equals(top.getQualifiedName());
    }

    private boolean hierarchyResolved(ITypeBinding type, int depth) {
        if (type == null) {
            return true;
        }
        ITypeBinding erasure = type.getErasure() == null ? type : type.getErasure();
        if (erasure.isRecovered() || erasure.isTypeVariable() || depth > 40) {
            return false;
        }
        String key = erasure.getKey() == null ? erasure.getQualifiedName() : erasure.getKey();
        Boolean known = resolvedHierarchies.get(key);
        if (known != null) {
            return known;
        }
        // 순환 상속이 있어도 끝나게 먼저 적어 둔다
        resolvedHierarchies.put(key, true);
        boolean resolved = hierarchyResolved(erasure.getSuperclass(), depth + 1);
        for (ITypeBinding i : erasure.getInterfaces()) {
            resolved = resolved && hierarchyResolved(i, depth + 1);
        }
        resolvedHierarchies.put(key, resolved);
        return resolved;
    }

    /** 다른 오버로드를 찾기 시작할 타입: 수신자의 타입, 수신자가 없으면 컴파일러가 고른 선언을 물려받은 가장 안쪽의 감싼 타입. */
    private ITypeBinding lookupType(MethodInvocation node, Expression receiver, IMethodBinding chosen) {
        if (receiver == null) {
            ITypeBinding declaring = chosen.getMethodDeclaration().getDeclaringClass().getErasure();
            for (ASTNode t = Unit.enclosingType(node); t != null; t = Unit.enclosingType(t.getParent())) {
                ITypeBinding type = Unit.typeBinding(t);
                if (type != null && !type.isRecovered() && declaring != null && type.getErasure().isSubTypeCompatible(declaring)) {
                    return type;
                }
            }
            return null;
        }
        ITypeBinding type = receiver instanceof Name n && n.resolveBinding() instanceof ITypeBinding named ? named : receiver.resolveTypeBinding();
        return type == null || type.isRecovered() || type.isArray() || type.isPrimitive() || Unit.reduce(type) == null ? null : type;
    }

    /**
     * start와 그 상위 타입에서 이름·인자 수가 같은 다른 선언 중 인자 타입과 맞는 것을 찾는다(생성자는 그 타입의 것만).
     * 맞는 후보가 하나면 그것, 여럿이면 해석된 자리의 타입이 전부 정확히 같은 하나, 그래도 정하지 못하면 null이다.
     * 미해석 타입이 섞인 자리(표기로만 비교했거나 확인할 수 없는 자리)가 있으면 inferred다.
     */
    private Target alternative(IMethodBinding mistrusted, ITypeBinding start, boolean staticOnly, List<?> arguments) {
        if (start == null || start.isRecovered()) {
            return null;
        }
        List<Target> matched = new ArrayList<>();
        List<Target> exact = new ArrayList<>();
        match(overloads(mistrusted, start, staticOnly, arguments.size(), false), arguments, matched, exact);
        return pick(matched, exact);
    }

    /**
     * 맞는 후보 가운데 하나를 고른다: 맞는 후보가 하나면 그것, 여럿이면 해석된 자리의 타입이 전부 정확히 같은 하나,
     * 그래도 여럿이면 확인하지 못한 자리가 없는 하나(인자의 타입 표기가 그 후보의 매개변수 표기와 같다. F-003-R28). 정하지 못하면 null.
     */
    private static Target pick(List<Target> matched, List<Target> exact) {
        if (matched.size() == 1) {
            return matched.get(0);
        }
        if (exact.size() == 1) {
            return exact.get(0);
        }
        List<Target> verified = (exact.isEmpty() ? matched : exact).stream().filter(Target::verified).toList();
        return verified.size() == 1 ? verified.get(0) : null;
    }

    /**
     * 컴파일러가 고른 선언은 인자 수까지 맞지만 인자에 classpath에 없는 타입이 있어 "먼저 선언된 후보"일 수 있다(F-003-R24).
     * 그 선언을 포함해 이름·인자 수가 같은 후보들을 인자의 타입 표기와 비교한다: 맞는 후보가 하나면 그것(컴파일러의 선택과 다르면 INFERRED),
     * 가리지 못하면 맞는 후보 전부(하나도 맞지 않으면 후보 전부)를 {@code others}에 담아 준다. 후보가 하나뿐이면 컴파일러의 선택 그대로다.
     */
    private Target amongOverloads(IMethodBinding chosen, ITypeBinding start, boolean staticOnly, List<?> arguments) {
        Target asIs = new Target(chosen, false, false);
        if (start == null || start.isRecovered()) {
            return asIs;
        }
        List<IMethodBinding> candidates = overloads(chosen, start, staticOnly, arguments.size(), true);
        if (candidates.size() < 2) {
            return asIs;
        }
        List<Target> matched = new ArrayList<>();
        List<Target> exact = new ArrayList<>();
        match(candidates, arguments, matched, exact);
        String chosenId = project.methodId(chosen);
        Target single = pick(matched, exact);
        if (single != null) {
            // 컴파일러의 선택이 맞는 것으로 확인되면 그대로 둔다(같은 타입에 오버로드가 있으면 call()이 INFERRED로 남긴다)
            return project.methodId(single.binding()).equals(chosenId) ? asIs : new Target(single.binding(), true, true);
        }
        List<IMethodBinding> pool = matched.isEmpty() ? candidates : matched.stream().map(Target::binding).toList();
        IMethodBinding primary = pool.stream().filter(m -> project.methodId(m).equals(chosenId)).findFirst().orElse(pool.get(0));
        List<IMethodBinding> others = pool.stream().filter(m -> m != primary).toList();
        return new Target(primary, true, !project.methodId(primary).equals(chosenId), others);
    }

    /** 후보마다 인자와 맞춰 보고 맞는 것을 matched에, 그중 해석된 자리의 타입이 전부 정확히 같은 것을 exact에 담는다. */
    private void match(List<IMethodBinding> candidates, List<?> arguments, List<Target> matched, List<Target> exact) {
        for (IMethodBinding candidate : candidates) {
            // [0] 해석된 자리의 타입이 전부 정확히 같다, [1] 확인하지 못한 자리가 없다
            boolean[] same = {true, true};
            Match match = acceptsArguments(candidate, arguments, same);
            if (match != Match.NO) {
                Target target = new Target(candidate, match == Match.PARTIAL, true, List.of(), same[1]);
                matched.add(target);
                if (same[0]) {
                    exact.add(target);
                }
            }
        }
    }

    /**
     * start와 그 상위 타입에서 reference와 이름이 같고 인자 수가 맞는 선언들(생성자는 그 타입의 것만).
     * 가까운 타입의 선언이 같은 매개변수의 먼 선언을 가린다.
     *
     * @param includeReference reference 자신도 후보에 넣는가
     */
    private List<IMethodBinding> overloads(IMethodBinding reference, ITypeBinding start, boolean staticOnly, int argc, boolean includeReference) {
        boolean constructor = reference.isConstructor();
        String excluded = includeReference ? null : project.methodId(reference);
        Map<String, IMethodBinding> candidates = new java.util.LinkedHashMap<>();
        List<ITypeBinding> queue = new ArrayList<>(List.of(start));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < queue.size() && i < 200; i++) {
            ITypeBinding type = queue.get(i);
            if (type == null || type.isRecovered() || !seen.add(type.getKey() == null ? type.getQualifiedName() : type.getKey())) {
                continue;
            }
            for (IMethodBinding m : type.getDeclaredMethods()) {
                int params = m.getParameterTypes().length;
                if (m.isConstructor() != constructor || (!constructor && !m.getName().equals(reference.getName()))
                        || !(params == argc || (m.isVarargs() && argc >= params - 1))
                        || (staticOnly && !Modifier.isStatic(m.getModifiers())) || (i > 0 && Modifier.isPrivate(m.getModifiers()))) {
                    continue;
                }
                if (!project.methodId(m).equals(excluded)) {
                    // 가까운 타입의 선언이 같은 매개변수(상위 타입의 타입 인자를 넣은 것)의 먼 선언을 가린다
                    StringBuilder key = new StringBuilder();
                    for (ITypeBinding p : m.getParameterTypes()) {
                        key.append(project.binary(p)).append(',');
                    }
                    candidates.putIfAbsent(key.toString(), m);
                }
            }
            if (constructor) {
                break;
            }
            queue.add(type.getSuperclass());
            queue.addAll(List.of(type.getInterfaces()));
        }
        return List.copyOf(candidates.values());
    }

    /**
     * 후보가 이 인자들을 받을 수 있는가. 양쪽 다 해석된 자리는 대입 가능 여부로, 양쪽 다 미해석인 자리는 표기로 비교한다.
     * 한쪽만 미해석이면 분명히 맞지 않는 것(미해석 매개변수에 그 타입일 수 없는 인자, final 클래스·원시 타입 매개변수에 미해석 인자)만 뺀다.
     *
     * @param same 해석된 자리의 타입이 전부 정확히 같으면 true로 남는다
     * @return EXACT면 모든 자리가 해석된 타입으로 확인된 것, PARTIAL이면 미해석 타입이 섞인 것
     */
    private Match acceptsArguments(IMethodBinding candidate, List<?> arguments, boolean[] same) {
        ITypeBinding[] params = candidate.getParameterTypes();
        String candidateType = project.binary(candidate.getMethodDeclaration().getDeclaringClass());
        Match result = Match.EXACT;
        // 가변 인자는 값이 없을 수도, 여럿일 수도 있다: 둘째 값부터는 원소 타입과 맞춘다
        for (int i = 0; i < arguments.size(); i++) {
            boolean extra = i >= params.length;
            if (extra && (!candidate.isVarargs() || params.length == 0)) {
                return Match.NO;
            }
            ITypeBinding param = extra ? params[params.length - 1].getComponentType() : params[i];
            if (param == null) {
                return Match.NO;
            }
            ITypeBinding argument = estimatedType((Expression) arguments.get(i), 0);
            boolean paramUnresolved = isUnresolvedType(param);
            if (argument != null && argument.isNullType()) {
                // null은 참조 타입 자리 어디에나 맞는다: 가르는 근거로 쓰지 않는다
                if (param.isPrimitive()) {
                    return Match.NO;
                }
                continue;
            }
            if (argument == null || argument.isTypeVariable() || argument.isCapture() || argument.isWildcardType() || argument.isIntersectionType()
                    || (!argument.isPrimitive() && !isUnresolvedType(argument)
                    && shadowedByImport(argument.isArray() ? argument.getElementType() : argument))) {
                // 인자의 타입을 모른다: 확인할 수 없다
                result = Match.PARTIAL;
                same[1] = false;
                continue;
            }
            if (isUnresolvedType(argument)) {
                if (paramUnresolved) {
                    // 가변 인자 자리에는 배열도, 원소 하나도 올 수 있다
                    boolean dimensions = argument.getDimensions() == param.getDimensions()
                            || (candidate.isVarargs() && i == params.length - 1 && argument.getDimensions() == param.getDimensions() - 1);
                    if (!dimensions
                            || notation(writtenElement(argument), unit.resolveWritten(writtenElement(argument)),
                            writtenElement(param), unit.resolveWrittenIn(writtenElement(param), candidateType)) == Match.NO) {
                        return Match.NO;
                    }
                } else {
                    if (!(candidate.isVarargs() && i == params.length - 1) && cannotAcceptUnresolvedType(param, argument)) {
                        return Match.NO;
                    }
                    // 미해석 타입의 값이 해석된 타입의 자리에 올 수 있는지는 알 수 없다
                    same[1] = false;
                }
                result = Match.PARTIAL;
                continue;
            }
            if (paramUnresolved) {
                if (cannotBeUnresolvedType(argument)) {
                    return Match.NO;
                }
                result = Match.PARTIAL;
                same[1] = false;
                continue;
            }
            boolean varargsSlot = candidate.isVarargs() && i == params.length - 1 && param.isArray();
            ITypeBinding erasedParam = param.getErasure() == null ? param : param.getErasure();
            boolean accepted = argument.isAssignmentCompatible(param) || argument.isAssignmentCompatible(erasedParam)
                    || (varargsSlot && argument.isAssignmentCompatible(param.getElementType()));
            if (!accepted) {
                return Match.NO;
            }
            same[0] &= project.binary(argument).equals(project.binary(param));
        }
        return result;
    }

    private static boolean hasUnresolvedArgument(List<?> arguments) {
        for (Object a : arguments) {
            ITypeBinding t = ((Expression) a).resolveTypeBinding();
            if (t == null || t.isRecovered()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 생성자 바인딩이 그 타입의 생성자인가. 만들려는 타입(또는 상위 클래스)이 classpath에 없고 인자에 해석되지 않은 값이 있으면
     * 컴파일러가 {@code java.lang.Object}의 생성자를, 생성자 없는 enum이면 {@code java.lang.Enum}의 생성자를 준다.
     */
    private boolean constructs(IMethodBinding constructor, ITypeBinding type) {
        // 타입 인자만 classpath에 없는 Page<Missing>은 복구된 바인딩이지만 erasure(Page)는 해석된 타입이다
        ITypeBinding erasure = type == null ? null : type.getErasure();
        if (erasure == null || erasure.isRecovered()) {
            return false;
        }
        ITypeBinding declaring = constructor.getMethodDeclaration().getDeclaringClass();
        return project.binary(declaring).equals(project.binary(erasure));
    }

    /**
     * 해석된 호출 하나를 기록한다. {@link #usable}로 확인한 바인딩만 준다.
     *
     * @param callType 정해진 종류(super, constructor, methodRef). null이면 대상에 따라 static/interface/virtual
     * @param inferred 표기 비교로 고른 대상이라 INFERRED로 남긴다(F-003-R21)
     */
    private void call(Unit.Owner owner, IMethodBinding binding, ASTNode at, String callType, Expression receiver, List<?> arguments,
                      boolean inferred) {
        call(owner, binding, at, callType, receiver, arguments, inferred, false);
    }

    /** @param ambiguous 여러 후보 중 하나라 {@code ambiguous=true}를 붙인다(F-003-R24) */
    private void call(Unit.Owner owner, IMethodBinding binding, ASTNode at, String callType, Expression receiver, List<?> arguments,
                      boolean inferred, boolean ambiguous) {
        calls.emit(owner, candidate(binding, callType, receiver), unit.loc(at),
                inferred || guessedAmongOverloads(binding.getMethodDeclaration(), arguments), ambiguous);
    }

    /** 호출 대상 하나를 엣지로 쓰는 데 필요한 것(바인딩을 붙잡지 않는다). */
    private CallResolver.Candidate candidate(IMethodBinding binding, String callType, Expression receiver) {
        IMethodBinding decl = binding.getMethodDeclaration();
        ITypeBinding declaring = decl.getDeclaringClass();
        String declaringName = project.binary(declaring);
        String type = callType;
        if (type == null) {
            type = Modifier.isStatic(decl.getModifiers()) ? "static" : declaring.isInterface() ? "interface" : "virtual";
        }
        if (decl.isConstructor() && callType == null) {
            type = "constructor";
        }
        boolean objectMethodOfRecord = declaring.isRecord() && decl.isSyntheticRecordMethod()
                && decl.getParameterTypes().length <= 1 && Set.of("equals", "hashCode", "toString").contains(decl.getName());
        boolean external = !declaring.isFromSource() || objectMethodOfRecord;
        boolean jdk = external && (objectMethodOfRecord || project.isJdk(declaring, declaringName));
        String receiverType = null;
        if (receiver != null && !Modifier.isStatic(decl.getModifiers())) {
            ITypeBinding rt = Unit.reduce(receiver.resolveTypeBinding());
            if (rt != null && !rt.isRecovered()) {
                String name = project.binary(rt);
                if (!name.equals(declaringName)) {
                    receiverType = name;
                }
            }
        }
        List<String> parameters = new ArrayList<>();
        for (ITypeBinding p : decl.getParameterTypes()) {
            parameters.add(project.binary(p));
        }
        return new CallResolver.Candidate(project.methodId(decl), decl.isConstructor() ? Ids.CONSTRUCTOR_NAME : decl.getName(), declaringName,
                external, jdk, type, receiverType, parameters, decl.isVarargs());
    }

    /** 인자에 classpath에 없는 타입이 있고 이름·인자 수가 같은 후보가 둘 이상이면 컴파일러의 선택을 확신할 수 없다. */
    private boolean guessedAmongOverloads(IMethodBinding decl, List<?> arguments) {
        if (!hasUnresolvedArgument(arguments)) {
            return false;
        }
        int candidates = 0;
        for (IMethodBinding m : decl.getDeclaringClass().getDeclaredMethods()) {
            if (m.isConstructor() == decl.isConstructor() && m.getName().equals(decl.getName())
                    && m.getParameterTypes().length == decl.getParameterTypes().length) {
                candidates++;
            }
        }
        return candidates > 1;
    }

    static String clip(String text) {
        return CallResolver.clip(text);
    }

    // ---- JDT가 해석하지 못한 호출 ----

    /**
     * 바인딩이 없는(또는 인자 불일치로 믿을 수 없는) 메서드 호출을 미해석 호출로 적어 둔다. 대상은 모든 파일을 본 뒤에 찾는다.
     * 체인의 바깥 링크가 안쪽 링크를 수신자로 가리키므로 호출 노드마다 한 번만 만든다.
     */
    private PendingCall pending(MethodInvocation node) {
        PendingCall existing = pendingByNode.get(node);
        if (existing != null) {
            return existing;
        }
        Unit.Owner owner = unit.owner(node);
        if (owner == null) {
            return null;
        }
        String name = node.getName().getIdentifier();
        int argc = node.arguments().size();
        Expression receiver = node.getExpression();
        PendingCall p = new PendingCall(PendingCall.Kind.METHOD, owner, name, argc, argumentTypes(node.arguments()),
                unit.loc(node.getName()), unit.loc(node), (receiver == null ? "" : receiver + ".") + name + ARGUMENTS);
        pendingByNode.put(node, p);
        calls.add(p);

        String imported = shadowedReceiver(receiver);
        if (imported != null) {
            // 수신자는 import한 타입이다: 프로젝트 타입이면 그 타입에서 찾고, 아니면 그 타입의 소스 없는 메서드다(F-003-R23)
            boolean typeName = unparen(receiver) instanceof Name n && n.resolveBinding() instanceof ITypeBinding;
            p.receiver = new PendingCall.Known(imported);
            p.staticOnly = typeName;
            p.fallback = PendingCall.Fallback.external(imported, typeName ? "static" : "virtual", false);
            return p;
        }

        // 인자 불일치·모호함으로 컴파일러가 고른 가장 가까운 후보. 그 후보로는 잇지 않는다(F-003-R9)
        IMethodBinding closest = node.resolveMethodBinding();
        PendingCall.Fallback mismatch = closest == null ? null : externalMismatch(closest, name);
        // 컴파일러가 선언된 멤버에서 찾아 보고 실패했는가. 아니면(수신자 타입을 몰랐거나 보지 않은 코드) 선언된 멤버도 후보다
        boolean attempted = closest != null || unit.isBadCall(node.getName().getStartPosition());
        if (receiver == null || receiver instanceof ThisExpression) {
            p.syntheticOnly = attempted;
            p.implicitTypes = implicitTypes(node, receiver, name);
            p.fallback = mismatch != null ? mismatch : closest != null ? PendingCall.Fallback.UNRESOLVED : implicitFallback(node, receiver, name);
            return p;
        }
        Described described = describeReceiver(receiver);
        p.receiver = described.type();
        p.staticOnly = described.typeName();
        p.syntheticOnly = described.fromCompiler() && attempted;
        p.inferredReceiver = unparen(receiver) instanceof MethodInvocation lookup && target(lookup) == null && beanLookupType(lookup) != null;
        if (described.type() instanceof PendingCall.ResultOf result && unparen(receiver) instanceof MethodInvocation) {
            result.call().usedAsReceiver = true;
        }
        if (mismatch != null) {
            p.fallback = mismatch;
        } else if (closest == null && (described.fromCompiler() || described.type() == PendingCall.Marker.UNKNOWN)) {
            p.fallback = explicitFallback(receiver);
        }
        if (!p.fallback.isExternal() && described.fromCompiler()) {
            PendingCall.Fallback inherited = inheritedFallback(receiver, described.typeName(), name, argc, closest);
            if (inherited != null) {
                p.fallback = inherited;
            }
        }
        return p;
    }

    /**
     * 수신자가 프로젝트 타입인데 그 타입(과 해석된 상위 타입)에 이름·인자 수가 맞는 선언이 없고 classpath에 없는 상위 타입이 있으면,
     * 그 상위 타입에서 물려받은 메서드의 호출로 본다(F-003-R19). 컴파일러는 그 상위 타입의 멤버를 볼 수 없어 "없는 메서드"라고만 한다.
     * 타입 이름으로 부른 static 호출은 상위 클래스의 것이다. 인스턴스 호출은 외부 상위 타입이 하나면 그 타입,
     * 여럿이면 상위 클래스 쪽(ambiguous)이다. 해당하지 않으면 null.
     */
    private PendingCall.Fallback inheritedFallback(Expression receiver, boolean typeName, String name, int argc, IMethodBinding closest) {
        Expression e = unparen(receiver);
        ITypeBinding raw = typeName && e instanceof Name n && n.resolveBinding() instanceof ITypeBinding t ? t : e.resolveTypeBinding();
        ITypeBinding type = Unit.reduce(raw);
        if (type == null || type.isRecovered() || !type.isFromSource()) {
            return null;
        }
        if (closest != null && declaresCompatible(type, name, argc, new HashSet<>())) {
            // 프로젝트 타입에 인자 수가 맞는 같은 이름의 선언이 있다: 인자 타입이 맞지 않은 것이다(F-003-R9)
            return null;
        }
        MissingSupertype[] missingClass = new MissingSupertype[1];
        List<MissingSupertype> missingInterfaces = new ArrayList<>();
        collectMissingSupertypes(type, true, missingClass, missingInterfaces, new HashSet<>());
        MissingSupertype chosen;
        boolean ambiguous = false;
        if (typeName) {
            chosen = missingClass[0];
        } else if ((missingClass[0] == null ? 0 : 1) + missingInterfaces.size() == 1) {
            chosen = missingClass[0] != null ? missingClass[0] : missingInterfaces.get(0);
        } else {
            chosen = missingClass[0];
            ambiguous = true;
        }
        String fqn = chosen == null ? null : unit.resolveWrittenIn(chosen.written(), chosen.declaredIn());
        if (fqn == null && chosen != null) {
            String global = project.globalName(chosen.written());
            fqn = global.equals(chosen.written()) ? null : global;
        }
        if (fqn == null) {
            return null;
        }
        return PendingCall.Fallback.inherited(fqn, typeName ? "static" : "virtual", ambiguous, typeName ? null : project.binary(type));
    }

    /**
     * classpath에 없는 상위 타입 하나.
     *
     * @param written 소스에 쓴 이름
     * @param declaredIn 그 이름을 상위 타입으로 적은 프로젝트 타입의 바이너리 이름(그 파일의 import로 FQN을 복원한다)
     */
    private record MissingSupertype(String written, String declaredIn) {
    }

    /** type의 계층에서 classpath에 없는 상위 타입을 모은다: 상위 클래스 사슬 끝의 클래스(많아야 하나)와 인터페이스들. */
    private void collectMissingSupertypes(ITypeBinding type, boolean classChain, MissingSupertype[] missingClass,
                                          List<MissingSupertype> missingInterfaces, Set<String> seen) {
        if (type == null || type.isRecovered() || !seen.add(type.getKey() == null ? type.getQualifiedName() : type.getKey())) {
            return;
        }
        String declaredIn = project.binary(type);
        ITypeBinding superclass = type.getSuperclass();
        if (superclass != null && superclass.isRecovered()) {
            if (classChain) {
                missingClass[0] = new MissingSupertype(JavaProject.written(superclass), declaredIn);
            }
        } else {
            collectMissingSupertypes(superclass, classChain, missingClass, missingInterfaces, seen);
        }
        for (ITypeBinding i : type.getInterfaces()) {
            if (i.isRecovered()) {
                MissingSupertype missing = new MissingSupertype(JavaProject.written(i), declaredIn);
                if (!missingInterfaces.contains(missing)) {
                    missingInterfaces.add(missing);
                }
            } else {
                collectMissingSupertypes(i, false, missingClass, missingInterfaces, seen);
            }
        }
    }

    /** type과 해석된 상위 타입에 이름이 같고 인자 수가 맞는 메서드 선언이 있는가. */
    private static boolean declaresCompatible(ITypeBinding type, String name, int argc, Set<String> seen) {
        if (type == null || type.isRecovered() || !seen.add(type.getKey() == null ? type.getQualifiedName() : type.getKey())) {
            return false;
        }
        for (IMethodBinding m : type.getDeclaredMethods()) {
            int params = m.getParameterTypes().length;
            if (!m.isConstructor() && m.getName().equals(name) && (params == argc || (m.isVarargs() && argc >= params - 1))) {
                return true;
            }
        }
        if (declaresCompatible(type.getSuperclass(), name, argc, seen)) {
            return true;
        }
        for (ITypeBinding i : type.getInterfaces()) {
            if (declaresCompatible(i, name, argc, seen)) {
                return true;
            }
        }
        return false;
    }

    /** 수신자 없는 호출이 속할 수 있는 타입: 감싼 타입(안쪽부터)과 static import한 타입. {@code this.}면 가장 안쪽 타입만. */
    private List<String> implicitTypes(MethodInvocation node, Expression receiver, String name) {
        List<String> types = new ArrayList<>();
        if (receiver instanceof ThisExpression thisExpression && thisExpression.getQualifier() != null) {
            ITypeBinding qualified = Unit.reduce(thisExpression.resolveTypeBinding());
            if (qualified != null && !qualified.isRecovered()) {
                types.add(project.binary(qualified));
            }
            return types;
        }
        for (ASTNode t = Unit.enclosingType(node); t != null; t = Unit.enclosingType(t.getParent())) {
            types.add(unit.typeName(t));
            if (receiver != null) {
                return types;
            }
        }
        String staticOwner = unit.staticImports.get(name);
        if (staticOwner != null) {
            types.add(staticOwner);
        }
        types.addAll(unit.staticOnDemandImports);
        return types;
    }

    /** 수신자 없는 호출을 프로젝트에서 찾지 못했을 때: static import한 타입의 호출이거나, classpath에 없는 상위 타입이 하나면 그 타입의 메서드다. */
    private PendingCall.Fallback implicitFallback(MethodInvocation node, Expression receiver, String name) {
        String staticOwner = receiver == null ? unit.staticImports.get(name) : null;
        if (staticOwner != null) {
            return PendingCall.Fallback.external(staticOwner, "static", false);
        }
        Set<String> missing = new LinkedHashSet<>();
        for (ASTNode t = Unit.enclosingType(node); t != null; t = Unit.enclosingType(t.getParent())) {
            collectMissingSupertypes(Unit.typeBinding(t), missing, new HashSet<>());
            if (receiver != null) {
                break;
            }
        }
        String fqn = missing.size() == 1 ? missingFqn(missing.iterator().next()) : null;
        if (fqn != null) {
            return PendingCall.Fallback.inherited(fqn, "virtual", false, null);
        }
        // classpath에 없는 상위 타입이 여럿이면 수신자를 적은 호출과 같은 규칙이다(F-003-R19, R25): 상위 클래스 쪽으로, ambiguous
        ITypeBinding innermost = Unit.typeBinding(Unit.enclosingType(node));
        MissingSupertype[] missingClass = new MissingSupertype[1];
        collectMissingSupertypes(innermost, true, missingClass, new ArrayList<>(), new HashSet<>());
        if (missing.size() > 1 && missingClass[0] != null) {
            String superFqn = unit.resolveWrittenIn(missingClass[0].written(), missingClass[0].declaredIn());
            if (superFqn == null) {
                String global = project.globalName(missingClass[0].written());
                superFqn = global.equals(missingClass[0].written()) ? null : global;
            }
            if (superFqn != null) {
                return PendingCall.Fallback.inherited(superFqn, "virtual", true, null);
            }
        }
        return PendingCall.Fallback.UNRESOLVED;
    }

    /** 컴파일러가 아는 수신자 타입이 소스 없는 타입(classpath에 없어 이름만 복원한 타입 포함)이면 그 타입의 external 호출이다. */
    private PendingCall.Fallback explicitFallback(Expression receiver) {
        ITypeBinding receiverType = Unit.reduce(receiver.resolveTypeBinding());
        boolean typeName = receiver instanceof Name n && n.resolveBinding() instanceof ITypeBinding;
        TypeRef type = null;
        boolean staticCall = false;
        if (receiverType != null) {
            if (receiverType.isRecovered()) {
                TypeRef restored = typeName ? unit.refWritten(((Name) receiver).getFullyQualifiedName()) : unit.ref(receiverType);
                type = restored.isUnresolved() ? null : restored;
                staticCall = typeName;
            } else if (!receiverType.isFromSource()) {
                type = unit.ref(receiverType);
                staticCall = typeName;
            }
        } else if (receiver instanceof Name n && n.resolveBinding() == null) {
            // 변수로도 타입으로도 해석되지 않은 이름: import한 미해석 타입의 static 호출인지 본다
            String fqn = unit.resolveWritten(n.getFullyQualifiedName());
            if (fqn != null) {
                type = TypeRef.external(fqn);
                staticCall = true;
            }
        }
        return type != null ? PendingCall.Fallback.external(type.name(), staticCall ? "static" : "virtual", false)
                : PendingCall.Fallback.UNRESOLVED;
    }

    /**
     * 가장 가까운 후보를 선언한 타입이 소스 없는 타입이면, 어느 오버로드인지 모르는 그 타입의 external 호출로 다룬다(F-003-R13).
     * 같은 이름의 메서드가 모두 같은 타입을 돌려주면 그 타입으로 체인을 이어 간다.
     */
    private PendingCall.Fallback externalMismatch(IMethodBinding closest, String name) {
        IMethodBinding decl = closest.getMethodDeclaration();
        ITypeBinding declaring = decl.getDeclaringClass();
        if (declaring == null || declaring.isRecovered() || declaring.isFromSource()) {
            return null;
        }
        String declaringName = project.binary(declaring);
        project.isJdk(declaring, declaringName);
        Set<String> returns = new HashSet<>();
        for (IMethodBinding m : declaring.getDeclaredMethods()) {
            if (!m.isConstructor() && m.getName().equals(name)) {
                ITypeBinding returned = m.getReturnType();
                ITypeBinding reduced = returned == null || returned.isArray() ? null : Unit.reduce(returned);
                TypeRef ref = reduced == null ? null : unit.ref(reduced);
                returns.add(ref == null || ref.isUnresolved() ? "?" : ref.name());
            }
        }
        PendingCall.TypeExpr result = returns.size() == 1 && !returns.contains("?")
                ? new PendingCall.Known(returns.iterator().next()) : PendingCall.Marker.EXTERNAL_TAIL;
        String callType = Modifier.isStatic(decl.getModifiers()) ? "static" : declaring.isInterface() ? "interface" : "virtual";
        return new PendingCall.Fallback(declaringName, callType, false, result);
    }

    // ---- 식의 타입 추적 ----

    /**
     * 식의 타입에 대해 아는 것.
     *
     * @param typeName 식이 타입 이름이다(static 멤버 호출)
     * @param fromCompiler 컴파일러가 해석한 타입이다. false면 초기화식·호출 결과를 따라 직접 추적한 것이다
     */
    private record Described(PendingCall.TypeExpr type, boolean typeName, boolean fromCompiler) {
    }

    /** 식이 필드를 가리키면 그 필드. 아니면 null. */
    private static IVariableBinding fieldOf(Expression e) {
        IBinding binding = e instanceof Name name ? name.resolveBinding()
                : e instanceof FieldAccess access ? access.resolveFieldBinding()
                : e instanceof SuperFieldAccess access ? access.resolveFieldBinding() : null;
        return binding instanceof IVariableBinding variable && variable.isField() ? variable.getVariableDeclaration() : null;
    }

    /**
     * {@code <수신자>.getBean(X.class)} 또는 {@code getBean(<이름>, X.class)} 꼴이고 X를 알 수 있으면 X. 아니면 null.
     * Spring의 {@code BeanFactory.getBean}과 그것을 감싼 프로젝트의 도우미가 이 꼴이다.
     */
    private TypeRef beanLookupType(MethodInvocation invocation) {
        if (!invocation.getName().getIdentifier().equals("getBean") || invocation.arguments().isEmpty() || invocation.arguments().size() > 2) {
            return null;
        }
        Object last = invocation.arguments().get(invocation.arguments().size() - 1);
        if (!(last instanceof TypeLiteral literal) || literal.getType().isPrimitiveType() || literal.getType().isArrayType()) {
            return null;
        }
        TypeRef ref = unit.ref(literal.getType());
        return ref == null || ref.isUnresolved() ? null : ref;
    }

    /** node를 감싼 타입들 가운데 classpath에 없는 상위 타입을 가진 것이 있는가. */
    private static boolean inheritsFromMissingType(ASTNode node) {
        Set<String> missing = new HashSet<>();
        for (ASTNode t = Unit.enclosingType(node); t != null && missing.isEmpty(); t = Unit.enclosingType(t.getParent())) {
            collectMissingSupertypes(Unit.typeBinding(t), missing, new HashSet<>());
        }
        return !missing.isEmpty();
    }

    private static Expression unparen(Expression e) {
        while (e instanceof ParenthesizedExpression p) {
            e = p.getExpression();
        }
        return e;
    }

    /** 호출의 수신자: 타입 이름, Lombok 합성 필드({@code log}), 그 밖의 식. */
    private Described describeReceiver(Expression expression) {
        Expression e = unparen(expression);
        if (e instanceof Name n) {
            IBinding binding = n.resolveBinding();
            if (binding instanceof ITypeBinding typeBinding) {
                ITypeBinding reduced = Unit.reduce(typeBinding);
                TypeRef ref = reduced == null ? null : reduced.isRecovered() ? unit.refWritten(n.getFullyQualifiedName()) : unit.ref(reduced);
                return new Described(known(ref), true, true);
            }
            if (binding == null) {
                if (n instanceof SimpleName simple) {
                    Unit.LombokField field = unit.lombokField(simple);
                    if (field != null) {
                        return new Described(new PendingCall.Known(field.type()), false, false);
                    }
                    Described variable = lexical(simple, 0);
                    if (variable != null) {
                        return variable;
                    }
                }
                // 변수가 아니면 타입 이름이다(static 호출). 컴파일러가 보지 않은 곳의 이름일 수 있다
                TypeRef type = unit.refUnattempted(n.getFullyQualifiedName());
                if (type.isUnresolved() && n instanceof SimpleName simple && Character.isLowerCase(simple.getIdentifier().charAt(0))
                        && inheritsFromMissingType(simple)) {
                    // 어디에도 선언이 없는 소문자 이름: classpath에 없는 상위 타입에서 물려받은 필드로 본다(F-003-R26).
                    // 그 필드의 타입은 알 수 없으므로 소스 없는 타입의 값에 이어 부른 체인과 같이 다룬다
                    return new Described(PendingCall.Marker.EXTERNAL_TAIL, false, false);
                }
                return type.isUnresolved() ? new Described(PendingCall.Marker.UNKNOWN, false, true)
                        : new Described(new PendingCall.Known(type.name()), true, true);
            }
        }
        return describe(e, 0);
    }

    /**
     * 식의 타입. 컴파일러가 아는 타입을 먼저 쓰고, 바인딩이 끊긴 곳(미해석 호출의 결과, 그 결과를 받은 {@code var} 변수)은
     * AST를 따라 추적한다.
     */
    private Described describe(Expression expression, int depth) {
        Expression e = unparen(expression);
        if (depth > MAX_TRACK_DEPTH) {
            return new Described(PendingCall.Marker.UNKNOWN, false, false);
        }
        if (e instanceof MethodInvocation invocation) {
            Target target = target(invocation);
            if (target == null) {
                TypeRef bean = beanLookupType(invocation);
                if (bean != null) {
                    // 컴파일러가 풀지 못한 getBean(X.class)의 결과는 X로 본다(F-003-R27). 그 getBean 호출 자체는 따로 기록된다
                    return new Described(new PendingCall.Known(bean.name()), false, false);
                }
                PendingCall call = pending(invocation);
                return new Described(call == null ? PendingCall.Marker.UNKNOWN : new PendingCall.ResultOf(call), false, false);
            }
            IMethodBinding binding = target.binding();
            if (target.replaced() || invocation.resolveTypeBinding() == null) {
                // 인자에 해석되지 않은 식이 있으면 컴파일러가 호출 식의 타입을 주지 않는다: 고른 메서드의 반환 타입을 쓴다.
                // 컴파일러의 선택 대신 다른 오버로드를 골랐을 때도 그 메서드의 반환 타입이다
                ITypeBinding returned = binding.getReturnType();
                ITypeBinding reduced = returned == null || returned.isArray() ? null : Unit.reduce(returned);
                TypeRef ref = reduced == null ? null : unit.ref(reduced);
                if (ref != null && !ref.isUnresolved()) {
                    return new Described(new PendingCall.Known(ref.name()), false, false);
                }
                ITypeBinding declaring = binding.getMethodDeclaration().getDeclaringClass();
                return new Described(declaring != null && !declaring.isFromSource() ? PendingCall.Marker.EXTERNAL_TAIL
                        : PendingCall.Marker.UNKNOWN, false, false);
            }
        }
        if (e instanceof SimpleName name && name.resolveBinding() == null) {
            Described variable = lexical(name, depth);
            if (variable != null) {
                return variable;
            }
        }
        if (e instanceof SimpleName name && name.resolveBinding() instanceof IVariableBinding variable && !variable.isField()) {
            Expression initializer = inferredInitializer(variable);
            if (initializer != null) {
                return new Described(describe(initializer, depth + 1).type(), false, false);
            }
        }
        ITypeBinding type = e.resolveTypeBinding();
        if (type != null) {
            ITypeBinding reduced = Unit.reduce(type);
            TypeRef ref = reduced == null ? null : unit.ref(reduced);
            if (ref != null && ref.isUnresolved() && reduced.isRecovered()) {
                // 다른 파일(프로젝트 상위 타입)에 선언한 필드의 타입이 classpath에 없다: 그 필드를 선언한 파일의 import로 복원한다(F-003-R26)
                IVariableBinding field = fieldOf(e);
                ITypeBinding declaring = field == null ? null : field.getDeclaringClass();
                if (declaring != null && !declaring.isRecovered() && declaring.isFromSource()) {
                    String fqn = unit.resolveWrittenIn(JavaProject.written(reduced), project.binary(declaring));
                    if (fqn != null) {
                        ref = project.byName(fqn);
                    }
                }
            }
            if (ref != null && !ref.isUnresolved()) {
                return new Described(new PendingCall.Known(ref.name()), false, true);
            }
            // 타입을 모르는 값인데 소스 없는 타입의 메서드가 돌려준 것이면 외부 호출의 꼬리로 본다
            if (reduced != null && e instanceof MethodInvocation invocation) {
                IMethodBinding binding = invocation.resolveMethodBinding();
                ITypeBinding declaring = binding == null ? null : binding.getMethodDeclaration().getDeclaringClass();
                if (declaring != null && !declaring.isFromSource()) {
                    return new Described(PendingCall.Marker.EXTERNAL_TAIL, false, true);
                }
            }
            return new Described(PendingCall.Marker.UNKNOWN, false, true);
        }
        if (e instanceof ClassInstanceCreation creation) {
            return new Described(known(unit.ref(creation.getType())), false, false);
        }
        if (e instanceof CastExpression cast) {
            return new Described(known(unit.ref(cast.getType())), false, false);
        }
        return new Described(PendingCall.Marker.UNKNOWN, false, true);
    }

    /**
     * 바인딩이 없는 이름의 선언을 AST에서 직접 찾는다(컴파일러가 보지 않은 람다 본문 안의 지역 변수·매개변수·필드).
     * 안쪽 범위부터 본다. 선언을 찾지 못하면 null.
     */
    private Described lexical(SimpleName name, int depth) {
        String id = name.getIdentifier();
        int position = name.getStartPosition();
        for (ASTNode n = name.getParent(); n != null; n = n.getParent()) {
            if (n instanceof Block block) {
                for (Object s : block.statements()) {
                    if (((ASTNode) s).getStartPosition() >= position) {
                        break;
                    }
                    if (s instanceof VariableDeclarationStatement statement) {
                        Described found = declared(statement.getType(), statement.fragments(), id, depth);
                        if (found != null) {
                            return found;
                        }
                    }
                }
            } else if (n instanceof ForStatement loop) {
                for (Object init : loop.initializers()) {
                    if (init instanceof VariableDeclarationExpression expression) {
                        Described found = declared(expression.getType(), expression.fragments(), id, depth);
                        if (found != null) {
                            return found;
                        }
                    }
                }
            } else if (n instanceof TryStatement tryStatement) {
                for (Object resource : tryStatement.resources()) {
                    if (resource instanceof VariableDeclarationExpression expression) {
                        Described found = declared(expression.getType(), expression.fragments(), id, depth);
                        if (found != null) {
                            return found;
                        }
                    }
                }
            } else if (n instanceof EnhancedForStatement loop) {
                if (loop.getParameter().getName().getIdentifier().equals(id)) {
                    return parameter(loop.getParameter());
                }
            } else if (n instanceof CatchClause clause) {
                if (clause.getException().getName().getIdentifier().equals(id)) {
                    return parameter(clause.getException());
                }
            } else if (n instanceof LambdaExpression lambda) {
                for (Object p : lambda.parameters()) {
                    if (((VariableDeclaration) p).getName().getIdentifier().equals(id)) {
                        // 타입을 쓰지 않은 람다 매개변수는 따라갈 수 없다
                        return p instanceof SingleVariableDeclaration typed ? parameter(typed)
                                : new Described(PendingCall.Marker.UNKNOWN, false, false);
                    }
                }
            } else if (n instanceof MethodDeclaration method) {
                for (Object p : method.parameters()) {
                    SingleVariableDeclaration parameter = (SingleVariableDeclaration) p;
                    if (parameter.getName().getIdentifier().equals(id)) {
                        return parameter(parameter);
                    }
                }
            } else if (n instanceof AbstractTypeDeclaration || n instanceof AnonymousClassDeclaration) {
                List<?> members = n instanceof AbstractTypeDeclaration type ? type.bodyDeclarations()
                        : ((AnonymousClassDeclaration) n).bodyDeclarations();
                for (Object member : members) {
                    if (member instanceof FieldDeclaration field) {
                        Described found = declared(field.getType(), field.fragments(), id, MAX_TRACK_DEPTH);
                        if (found != null) {
                            return found;
                        }
                    }
                }
                if (n instanceof RecordDeclaration record) {
                    for (Object c : record.recordComponents()) {
                        SingleVariableDeclaration component = (SingleVariableDeclaration) c;
                        if (component.getName().getIdentifier().equals(id)) {
                            return parameter(component);
                        }
                    }
                }
            }
        }
        return null;
    }

    private Described declared(Type type, List<?> fragments, String id, int depth) {
        for (Object f : fragments) {
            VariableDeclarationFragment fragment = (VariableDeclarationFragment) f;
            if (!fragment.getName().getIdentifier().equals(id)) {
                continue;
            }
            if (fragment.getExtraDimensions() > 0 || type.isArrayType() || type.isPrimitiveType()) {
                return new Described(PendingCall.Marker.UNKNOWN, false, false);
            }
            if (isInferred(type)) {
                return fragment.getInitializer() == null || depth >= MAX_TRACK_DEPTH
                        ? new Described(PendingCall.Marker.UNKNOWN, false, false)
                        : new Described(describe(fragment.getInitializer(), depth + 1).type(), false, false);
            }
            return new Described(known(unit.ref(type)), false, false);
        }
        return null;
    }

    private Described parameter(SingleVariableDeclaration declaration) {
        Type type = declaration.getType();
        if (declaration.getExtraDimensions() > 0 || declaration.isVarargs() || type.isArrayType() || type.isPrimitiveType() || isInferred(type)) {
            return new Described(PendingCall.Marker.UNKNOWN, false, false);
        }
        return new Described(known(unit.ref(type)), false, false);
    }

    /** {@code var} 또는 Lombok {@code val}로 쓴 타입인가. */
    private static boolean isInferred(Type type) {
        if (type.isVar()) {
            return true;
        }
        if (type instanceof SimpleType simple && simple.getName().getFullyQualifiedName().equals("val")) {
            ITypeBinding binding = type.resolveBinding();
            return binding == null || binding.isRecovered() || "lombok.val".equals(binding.getQualifiedName());
        }
        return false;
    }

    /** {@code var}(또는 Lombok {@code val})로 선언한 지역 변수면 그 초기화식. 변수의 타입은 초기화식의 타입이다. */
    private Expression inferredInitializer(IVariableBinding variable) {
        ASTNode declaration = unit.cu.findDeclaringNode(variable.getVariableDeclaration());
        if (!(declaration instanceof VariableDeclarationFragment fragment) || fragment.getInitializer() == null) {
            return null;
        }
        Type type = fragment.getParent() instanceof VariableDeclarationStatement statement ? statement.getType()
                : fragment.getParent() instanceof VariableDeclarationExpression expression ? expression.getType() : null;
        return type != null && isInferred(type) ? fragment.getInitializer() : null;
    }

    /** 인자마다 타입에 대해 아는 것: 컴파일러가 아는 타입(erasure의 바이너리 이름)이거나, 추적해야 하는 미해석 호출의 결과다. */
    private List<PendingCall.TypeExpr> argumentTypes(List<?> arguments) {
        List<PendingCall.TypeExpr> types = new ArrayList<>();
        for (Object a : arguments) {
            Expression argument = (Expression) a;
            boolean function = argument instanceof LambdaExpression || argument instanceof MethodReference;
            ITypeBinding t = function ? argument.resolveTypeBinding() : estimatedType(argument, 0);
            if (t == null) {
                types.add(function ? PendingCall.Marker.UNKNOWN : describe(argument, 0).type());
            } else if (t.isNullType() || t.isRecovered() || t.isTypeVariable() || t.isCapture() || t.isWildcardType() || t.isIntersectionType()) {
                types.add(PendingCall.Marker.UNKNOWN);
            } else {
                types.add(new PendingCall.Known(project.binary(t)));
            }
        }
        return types;
    }

    /** node를 감싼 타입의 상위 클래스가 classpath에 없으면 그 FQN(복원할 수 있을 때). */
    private String missingSuperclass(ASTNode node) {
        ITypeBinding type = Unit.typeBinding(Unit.enclosingType(node));
        ITypeBinding superclass = type == null ? null : type.getSuperclass();
        return superclass != null && superclass.isRecovered() ? missingFqn(JavaProject.written(superclass)) : null;
    }

    private String missingFqn(String written) {
        String fqn = unit.resolveWritten(written);
        if (fqn == null) {
            String global = project.globalName(written);
            fqn = global.equals(written) ? null : global;
        }
        return fqn;
    }

    private static void collectMissingSupertypes(ITypeBinding type, Set<String> missing, Set<String> seen) {
        if (type == null || !seen.add(type.getKey() == null ? type.getQualifiedName() : type.getKey())) {
            return;
        }
        if (type.isRecovered()) {
            missing.add(JavaProject.written(type));
            return;
        }
        collectMissingSupertypes(type.getSuperclass(), missing, seen);
        for (ITypeBinding i : type.getInterfaces()) {
            collectMissingSupertypes(i, missing, seen);
        }
    }

    // ---- OVERRIDES ----

    @Override
    public boolean visit(MethodDeclaration node) {
        overrides(node);
        return true;
    }

    private void overrides(MethodDeclaration node) {
        IMethodBinding method = node.resolveBinding();
        if (method == null || method.isConstructor() || Modifier.isStatic(method.getModifiers())
                || Modifier.isPrivate(method.getModifiers()) || method.getDeclaringClass() == null) {
            return;
        }
        String from = unit.methodId(node);
        SourceLocation location = unit.loc(node.getName());
        Set<String> missing = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        ITypeBinding declaring = method.getDeclaringClass();
        boolean found = overridesIn(declaring.getSuperclass(), method, from, location, missing, seen);
        for (ITypeBinding i : declaring.getInterfaces()) {
            found |= overridesIn(i, method, from, location, missing, seen);
        }
        if (!found && hasUnresolvedParameter(method)) {
            // 매개변수에 classpath에 없는 타입이 있으면 컴파일러는 재정의를 판정하지 못한다: 프로젝트 상위 타입에서 표기로 찾는다(F-003-R17)
            Set<String> visited = new HashSet<>();
            found = overridesByNotation(declaring.getSuperclass(), method, from, location, visited);
            for (ITypeBinding i : declaring.getInterfaces()) {
                found |= overridesByNotation(i, method, from, location, visited);
            }
        }
        if (found || missing.isEmpty() || !hasOverrideAnnotation(node)) {
            return;
        }
        // @Override인데 해석된 상위 타입에는 선언이 없다: classpath에 없는 상위 타입의 메서드다
        String name = method.getName();
        String fqn = missing.size() == 1 ? missingFqn(missing.iterator().next()) : null;
        if (fqn != null) {
            String target = project.externalMethod(Ids.javaMethod(fqn, name, "?"), name, true);
            project.typeId(TypeRef.external(fqn));
            graph.addEdge(Edge.builder(from, target, EdgeKind.OVERRIDES).location(location).inferred().build());
        } else {
            // 원문에는 매개변수를 적지 않고 수는 argCount에 남긴다(java-call과 같은 표기, F-003-R18)
            project.unresolvedEdge(from, EdgeKind.OVERRIDES, JavaProject.CAT_OVERRIDE, name + ARGUMENTS, location,
                    Map.of("argCount", node.parameters().size()));
        }
    }

    // ---- 매개변수 타입이 classpath에 없는 메서드의 재정의: 표기 비교 (F-003-R17) ----

    /** 표기 비교의 결과. PARTIAL은 FQN을 복원하지 못한 자리가 있어 단순 이름으로만 비교한 것이다. */
    private enum Match { NO, PARTIAL, EXACT }

    private static boolean isUnresolvedType(ITypeBinding type) {
        ITypeBinding element = type.isArray() ? type.getElementType() : type;
        ITypeBinding erasure = element == null ? null : element.getErasure();
        ITypeBinding reduced = erasure == null ? element : erasure;
        return reduced != null && reduced.isRecovered();
    }

    /** 매개변수 타입 어딘가(타입 자체, 배열 원소, 제네릭 타입 인자)에 classpath에 없는 타입이 있는가. */
    private static boolean hasUnresolvedParameter(IMethodBinding method) {
        for (ITypeBinding p : method.getParameterTypes()) {
            ITypeBinding element = p.isArray() ? p.getElementType() : p;
            if (isUnresolvedType(p) || (element != null && element.isRecovered())) {
                return true;
            }
        }
        return false;
    }

    /**
     * type과 그 위의 프로젝트 타입에서, 가지마다 가장 가까운 "이름·매개변수 수·위치별 매개변수 타입 표기가 같은" 선언을 찾아 잇는다.
     * 컴파일러는 classpath에 없는 타입을 쓴 자리마다 서로 다른 타입으로 보기 때문에 {@link IMethodBinding#overrides}가 실패한다.
     */
    private boolean overridesByNotation(ITypeBinding type, IMethodBinding method, String from, SourceLocation location, Set<String> seen) {
        if (type == null || type.isRecovered() || !seen.add(type.getKey() == null ? type.getQualifiedName() : type.getKey())) {
            return false;
        }
        ITypeBinding erasure = type.getErasure() == null ? type : type.getErasure();
        if (!erasure.isFromSource()) {
            return false;
        }
        boolean found = false;
        for (IMethodBinding candidate : type.getDeclaredMethods()) {
            if (candidate.isConstructor() || !candidate.getName().equals(method.getName())
                    || Modifier.isStatic(candidate.getModifiers()) || Modifier.isPrivate(candidate.getModifiers())) {
                continue;
            }
            Match match = sameParameterNotation(method, candidate);
            if (match == Match.NO) {
                continue;
            }
            found = true;
            Edge.Builder b = Edge.builder(from, project.methodId(candidate.getMethodDeclaration()), EdgeKind.OVERRIDES).location(location);
            if (match == Match.PARTIAL) {
                b.inferred();
            }
            graph.addEdge(b.build());
        }
        if (found) {
            return true;
        }
        boolean above = overridesByNotation(type.getSuperclass(), method, from, location, seen);
        for (ITypeBinding i : type.getInterfaces()) {
            above |= overridesByNotation(i, method, from, location, seen);
        }
        return above;
    }

    /** 매개변수 수가 같고 위치마다 타입이 같은가. 해석된 자리는 erasure의 바이너리 이름으로, 미해석 자리는 표기로 비교한다. */
    private Match sameParameterNotation(IMethodBinding method, IMethodBinding candidate) {
        ITypeBinding[] own = method.getParameterTypes();
        ITypeBinding[] theirs = candidate.getParameterTypes();
        if (own.length != theirs.length) {
            return Match.NO;
        }
        String theirType = project.binary(candidate.getMethodDeclaration().getDeclaringClass());
        Match result = Match.EXACT;
        for (int i = 0; i < own.length; i++) {
            boolean ownUnresolved = isUnresolvedType(own[i]);
            if (ownUnresolved != isUnresolvedType(theirs[i])) {
                return Match.NO;
            }
            if (!ownUnresolved) {
                if (!project.binary(own[i]).equals(project.binary(theirs[i]))) {
                    return Match.NO;
                }
                continue;
            }
            if (own[i].getDimensions() != theirs[i].getDimensions()) {
                return Match.NO;
            }
            String ownWritten = writtenElement(own[i]);
            String theirWritten = writtenElement(theirs[i]);
            Match match = notation(ownWritten, unit.resolveWritten(ownWritten), theirWritten, unit.resolveWrittenIn(theirWritten, theirType));
            if (match == Match.NO) {
                return Match.NO;
            }
            if (match == Match.PARTIAL) {
                result = Match.PARTIAL;
            }
        }
        return result;
    }

    /** classpath에 없는 타입 둘의 표기 비교. FQN은 그 이름을 쓴 파일의 import로 복원한 것이고 복원하지 못했으면 null이다. */
    private static Match notation(String ownWritten, String ownFqn, String theirWritten, String theirFqn) {
        if (ownFqn != null && theirFqn != null) {
            return ownFqn.equals(theirFqn) ? Match.EXACT : Match.NO;
        }
        if (ownFqn == null && theirFqn == null) {
            return ownWritten.equals(theirWritten) ? Match.EXACT : Match.NO;
        }
        // 한쪽만 FQN을 안다: 단순 이름까지만 비교할 수 있다
        return JavaProject.simpleName(ownFqn != null ? ownFqn : ownWritten).equals(JavaProject.simpleName(theirFqn != null ? theirFqn : theirWritten))
                ? Match.PARTIAL : Match.NO;
    }

    /** classpath에 없는 타입(배열이면 원소 타입)을 소스에 쓴 이름. */
    private static String writtenElement(ITypeBinding type) {
        ITypeBinding element = type.isArray() ? type.getElementType() : type;
        ITypeBinding erasure = element.getErasure();
        return JavaProject.written(erasure == null ? element : erasure);
    }

    /** type과 그 위에서, 가지마다 가장 가까운 재정의 대상 선언을 찾아 잇는다. */
    private boolean overridesIn(ITypeBinding type, IMethodBinding method, String from, SourceLocation location,
                                Set<String> missing, Set<String> seen) {
        if (type == null || !seen.add(type.getKey() == null ? type.getQualifiedName() : type.getKey())) {
            return false;
        }
        if (type.isRecovered()) {
            missing.add(JavaProject.written(type));
            return false;
        }
        boolean found = false;
        for (IMethodBinding candidate : type.getDeclaredMethods()) {
            if (!candidate.isConstructor() && candidate.getName().equals(method.getName()) && method.overrides(candidate)) {
                found = true;
                IMethodBinding decl = candidate.getMethodDeclaration();
                ITypeBinding declaring = decl.getDeclaringClass();
                if (declaring.isFromSource()) {
                    graph.addEdge(Edge.of(from, project.methodId(decl), EdgeKind.OVERRIDES, location));
                } else {
                    String declaringName = project.binary(declaring);
                    boolean jdk = project.isJdk(declaring, declaringName);
                    if ("java.lang.Object".equals(declaringName)
                            || (jdk && project.options.externalCalls() != JavaOptions.ExternalCalls.ALL)) {
                        continue;
                    }
                    String target = project.externalMethod(project.methodId(decl), decl.getName(), false);
                    project.typeId(TypeRef.external(declaringName));
                    graph.addEdge(Edge.of(from, target, EdgeKind.OVERRIDES, location));
                }
            }
        }
        if (found) {
            return true;
        }
        boolean above = overridesIn(type.getSuperclass(), method, from, location, missing, seen);
        for (ITypeBinding i : type.getInterfaces()) {
            above |= overridesIn(i, method, from, location, missing, seen);
        }
        return above;
    }

    private static boolean hasOverrideAnnotation(MethodDeclaration node) {
        for (Object m : node.modifiers()) {
            if (m instanceof Annotation a) {
                String name = a.getTypeName().getFullyQualifiedName();
                if (name.equals("Override") || name.equals("java.lang.Override")) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- ACCESSES ----

    @Override
    public boolean visit(SimpleName node) {
        if (node.isDeclaration()) {
            return false;
        }
        IBinding binding = node.resolveBinding();
        if (binding == null) {
            lombokFieldAccess(node);
            return false;
        }
        if (!(binding instanceof IVariableBinding variable) || !variable.isField()) {
            return false;
        }
        IVariableBinding decl = variable.getVariableDeclaration();
        ITypeBinding declaring = decl.getDeclaringClass();
        if (declaring == null || !declaring.isFromSource()) {
            return false;
        }
        Unit.Owner owner = unit.owner(node);
        if (owner == null) {
            return false;
        }
        Edge.Builder b = Edge.builder(owner.methodId(), Ids.javaField(project.binary(declaring), decl.getName()), EdgeKind.ACCESSES)
                .location(unit.loc(node)).attr("access", isWrite(node) ? "write" : "read");
        if (owner.viaLambda()) {
            b.attr("viaLambda", true);
        }
        graph.addEdge(b.build());
        return false;
    }

    /** 해석되지 않은 이름이 Lombok 합성 필드({@code log})를 값으로 쓰는 자리면 읽기 접근으로 남긴다. */
    private void lombokFieldAccess(SimpleName node) {
        StructuralPropertyDescriptor where = node.getLocationInParent();
        if (where != MethodInvocation.EXPRESSION_PROPERTY && where != MethodInvocation.ARGUMENTS_PROPERTY
                && where != ClassInstanceCreation.ARGUMENTS_PROPERTY && where != Assignment.RIGHT_HAND_SIDE_PROPERTY
                && where != VariableDeclarationFragment.INITIALIZER_PROPERTY && where != ReturnStatement.EXPRESSION_PROPERTY) {
            return;
        }
        Unit.LombokField field = unit.lombokField(node);
        Unit.Owner owner = field == null ? null : unit.owner(node);
        if (owner == null) {
            return;
        }
        Edge.Builder b = Edge.builder(owner.methodId(), field.fieldId(), EdgeKind.ACCESSES).location(unit.loc(node)).attr("access", "read");
        if (owner.viaLambda()) {
            b.attr("viaLambda", true);
        }
        graph.addEdge(b.build());
    }

    /** 대입의 왼쪽이거나 {@code ++}/{@code --}의 대상이면 쓰기다. */
    private static boolean isWrite(SimpleName name) {
        ASTNode expression = name;
        ASTNode parent = name.getParent();
        if ((parent instanceof QualifiedName q && q.getName() == name)
                || (parent instanceof FieldAccess f && f.getName() == name)
                || (parent instanceof SuperFieldAccess s && s.getName() == name)) {
            expression = parent;
            parent = parent.getParent();
        }
        if (parent instanceof Assignment a) {
            return a.getLeftHandSide() == expression;
        }
        if (parent instanceof PostfixExpression) {
            return true;
        }
        if (parent instanceof PrefixExpression p) {
            return p.getOperator() == PrefixExpression.Operator.INCREMENT || p.getOperator() == PrefixExpression.Operator.DECREMENT;
        }
        return false;
    }
}
