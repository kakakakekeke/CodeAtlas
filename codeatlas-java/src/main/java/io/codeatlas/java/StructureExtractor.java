package io.codeatlas.java;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ArrayType;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeMemberDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IExtendedModifier;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NameQualifiedType;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeParameter;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F-002 Java 구조. 타입·멤버 노드와 CONTAINS, EXTENDS, IMPLEMENTS, ASSOCIATES, DEPENDS_ON, ANNOTATED_BY를 만든다.
 */
final class StructureExtractor extends ASTVisitor {

    /** 방문 중인 타입 하나. */
    private static final class Frame {
        final ASTNode node;
        final String name;
        final String id;
        final ITypeBinding binding;
        /** EXTENDS·IMPLEMENTS·ASSOCIATES로 이미 이은 대상(이 타입들로는 DEPENDS_ON을 만들지 않는다). */
        final Set<String> strong = new HashSet<>();
        /** 쓰인 타입 → 처음 쓰인 위치. */
        final Map<String, Dep> deps = new LinkedHashMap<>();
        final Set<String> declaredMethods = new HashSet<>();

        Frame(ASTNode node, String name, ITypeBinding binding) {
            this.node = node;
            this.name = name;
            this.id = Ids.javaType(name);
            this.binding = binding;
        }
    }

    private record Dep(TypeRef ref, SourceLocation location) {
    }

    private final Unit unit;
    private final JavaProject project;
    private final GraphModel graph;
    private final Deque<Frame> frames = new ArrayDeque<>();

    StructureExtractor(Unit unit) {
        this.unit = unit;
        this.project = unit.project;
        this.graph = project.graph;
    }

    void run() {
        CompilationUnit cu = unit.cu;
        if (!unit.packageName.isEmpty()) {
            project.packageNode(unit.packageName);
        }
        for (Object type : cu.types()) {
            ((ASTNode) type).accept(this);
        }
    }

    // ---- 타입 ----

    @Override
    public boolean visit(TypeDeclaration node) {
        Frame frame = enter(node, node.resolveBinding(), node.getName(), node.isInterface() ? "interface" : "class",
                node.modifiers(), node.typeParameters());
        if (node.getSuperclassType() != null) {
            superType(frame, node.getSuperclassType(), EdgeKind.EXTENDS);
        }
        for (Object t : node.superInterfaceTypes()) {
            superType(frame, (Type) t, node.isInterface() ? EdgeKind.EXTENDS : EdgeKind.IMPLEMENTS);
        }
        return true;
    }

    @Override
    public boolean visit(EnumDeclaration node) {
        Frame frame = enter(node, node.resolveBinding(), node.getName(), "enum", node.modifiers(), List.of());
        for (Object t : node.superInterfaceTypes()) {
            superType(frame, (Type) t, EdgeKind.IMPLEMENTS);
        }
        return true;
    }

    @Override
    public boolean visit(RecordDeclaration node) {
        Frame frame = enter(node, node.resolveBinding(), node.getName(), "record", node.modifiers(), node.typeParameters());
        for (Object t : node.superInterfaceTypes()) {
            superType(frame, (Type) t, EdgeKind.IMPLEMENTS);
        }
        for (Object c : node.recordComponents()) {
            SingleVariableDeclaration component = (SingleVariableDeclaration) c;
            String typeText = component.getType().toString() + "[]".repeat(component.getExtraDimensions());
            String fieldId = field(frame, component.getName(), unit.declLoc(component, component.getName()), typeText,
                    List.of("private", "final"), null, false);
            associate(frame, component.getType().resolveBinding(), component.getType(), component.getExtraDimensions() > 0,
                    component.getName().getIdentifier(), unit.loc(component));
            annotations(fieldId, component.modifiers());
        }
        return true;
    }

    @Override
    public boolean visit(AnnotationTypeDeclaration node) {
        enter(node, node.resolveBinding(), node.getName(), "annotation", node.modifiers(), List.of());
        return true;
    }

    @Override
    public boolean visit(AnonymousClassDeclaration node) {
        Frame frame = enter(node, node.resolveBinding(), null, "class", List.of(), List.of());
        ASTNode parent = node.getParent();
        if (parent instanceof ClassInstanceCreation creation) {
            ITypeBinding superBinding = creation.getType().resolveBinding();
            boolean isInterface = superBinding != null && !superBinding.isRecovered() && superBinding.isInterface();
            superType(frame, creation.getType(), isInterface ? EdgeKind.IMPLEMENTS : EdgeKind.EXTENDS);
        } else if (parent instanceof EnumConstantDeclaration) {
            ASTNode enumNode = Unit.enclosingType(parent);
            if (enumNode != null) {
                String enumName = unit.typeName(enumNode);
                graph.addEdge(Edge.of(frame.id, Ids.javaType(enumName), EdgeKind.EXTENDS, unit.loc(parent)));
                frame.strong.add(enumName);
            }
        }
        return true;
    }

    @Override
    public void endVisit(TypeDeclaration node) {
        leave();
    }

    @Override
    public void endVisit(EnumDeclaration node) {
        leave();
    }

    @Override
    public void endVisit(RecordDeclaration node) {
        leave();
    }

    @Override
    public void endVisit(AnnotationTypeDeclaration node) {
        leave();
    }

    @Override
    public void endVisit(AnonymousClassDeclaration node) {
        leave();
    }

    private Frame enter(ASTNode node, ITypeBinding binding, SimpleName nameNode, String typeKind, List<?> modifiers, List<?> typeParameters) {
        String name = unit.typeName(node);
        Frame outer = frames.peek();
        Frame frame = new Frame(node, name, binding);
        boolean anonymous = nameNode == null;
        boolean local = !anonymous && !(node.getParent() instanceof AbstractTypeDeclaration)
                && !(node.getParent() instanceof AnonymousClassDeclaration) && !(node.getParent() instanceof CompilationUnit);

        String displayName = anonymous ? name.substring(name.lastIndexOf('.') + 1) : nameNode.getIdentifier();
        // qualifiedName은 바이너리 이름이다(매퍼 namespace와 맞추는 링커가 이 값을 쓴다)
        Node.Builder b = Node.builder(frame.id, NodeKind.JAVA_TYPE).name(displayName).qualifiedName(name)
                .location(unit.declLoc(node, nameNode))
                .attr("typeKind", typeKind)
                .attr("modifiers", modifierNames(modifiers))
                .attr("typeParameters", typeParameterNames(typeParameters));
        if (anonymous) {
            b.attr("anonymous", true);
        }
        if (local) {
            b.attr("local", true);
        }
        if (anonymous || local) {
            MethodDeclaration method = enclosingMethod(node);
            if (method != null) {
                b.attr("enclosingMethod", unit.methodId(method));
            }
        }
        graph.declare(b.build());

        if (outer == null) {
            graph.addEdge(Edge.of(unit.file.nodeId(), frame.id, EdgeKind.CONTAINS));
            if (!unit.packageName.isEmpty()) {
                graph.addEdge(Edge.of(project.packageNode(unit.packageName), frame.id, EdgeKind.CONTAINS));
            }
        } else {
            graph.addEdge(Edge.of(outer.id, frame.id, EdgeKind.CONTAINS));
        }
        frames.push(frame);
        annotations(frame.id, modifiers);
        return frame;
    }

    private void leave() {
        Frame frame = frames.pop();
        new LombokSynthesizer(unit).run(frame.node, frame.declaredMethods);
        syntheticMethods(frame);
        for (Map.Entry<String, Dep> e : frame.deps.entrySet()) {
            if (!frame.strong.contains(e.getKey())) {
                unit.addTypeEdge(frame.id, EdgeKind.DEPENDS_ON, e.getValue().ref(), e.getValue().location(), Map.of());
            }
        }
    }

    private static MethodDeclaration enclosingMethod(ASTNode typeNode) {
        for (ASTNode p = typeNode.getParent(); p != null; p = p.getParent()) {
            if (p instanceof MethodDeclaration m) {
                return m;
            }
            if (p instanceof AbstractTypeDeclaration || p instanceof AnonymousClassDeclaration) {
                return null;
            }
        }
        return null;
    }

    private void superType(Frame frame, Type type, EdgeKind kind) {
        TypeRef ref = unit.ref(type);
        if (ref == null || "java.lang.Object".equals(ref.name())) {
            return;
        }
        List<String> typeArguments = typeArguments(type);
        String key = unit.addTypeEdge(frame.id, kind, ref, unit.loc(type),
                typeArguments.isEmpty() ? Map.of() : Map.of("typeArguments", typeArguments));
        if (key != null) {
            frame.strong.add(key);
        }
    }

    /**
     * 상위 타입에 넘긴 타입 인자(F-002-R15): 선언 순서대로 erasure의 바이너리 이름. 타입 변수는 이름, 와일드카드는 {@code ?},
     * 배열은 {@code []} 접미사다. 소스에 쓴 것을 AST에서 읽으므로 상위 타입이나 인자가 classpath에 없어도 남는다
     * (그때는 이 파일의 import로 복원한 FQN, 복원하지 못하면 쓴 이름). 다이아몬드는 컴파일러가 추론한 인자를 쓴다.
     */
    private List<String> typeArguments(Type type) {
        List<String> names = new ArrayList<>();
        if (!(type instanceof ParameterizedType parameterized)) {
            return names;
        }
        for (Object argument : parameterized.typeArguments()) {
            names.add(typeArgument((Type) argument));
        }
        ITypeBinding binding = names.isEmpty() ? type.resolveBinding() : null;
        if (binding != null && !binding.isRecovered()) {
            for (ITypeBinding argument : binding.getTypeArguments()) {
                names.add(typeArgument(argument));
            }
        }
        return names;
    }

    private String typeArgument(Type type) {
        if (type.isWildcardType()) {
            return "?";
        }
        int dimensions = type instanceof ArrayType array ? array.getDimensions() : 0;
        Type element = type instanceof ArrayType array ? array.getElementType() : type;
        ITypeBinding binding = element.resolveBinding();
        String name;
        if (element.isPrimitiveType()) {
            name = element.toString();
        } else if (binding != null && !binding.isRecovered() && (binding.getErasure() == null || !binding.getErasure().isRecovered())) {
            name = typeArgument(binding);
        } else {
            TypeRef ref = unit.ref(element);
            name = ref == null ? Unit.written(element) : ref.name();
        }
        return name + "[]".repeat(dimensions);
    }

    private String typeArgument(ITypeBinding binding) {
        if (binding.isWildcardType() || binding.isCapture()) {
            return "?";
        }
        if (binding.isArray()) {
            return typeArgument(binding.getElementType()) + "[]".repeat(binding.getDimensions());
        }
        return binding.isTypeVariable() ? binding.getName() : project.binary(binding);
    }

    /** 소스에 없지만 호출 대상이 되는 메서드(기본 생성자, record 접근자·표준 생성자, enum values/valueOf)를 합성 노드로 만든다. */
    private void syntheticMethods(Frame frame) {
        ITypeBinding binding = frame.binding;
        if (binding == null || binding.isRecovered() || binding.isInterface()) {
            return;
        }
        Set<String> components = new HashSet<>();
        if (frame.node instanceof RecordDeclaration record) {
            for (Object c : record.recordComponents()) {
                components.add(((SingleVariableDeclaration) c).getName().getIdentifier());
            }
        }
        // Lombok이 생성자를 만들면 컴파일러가 넣는 기본 생성자는 없다
        Unit.LombokType lombok = unit.lombokTypes.get(frame.node);
        boolean lombokConstructors = lombok != null && !lombok.constructors.isEmpty();
        for (IMethodBinding m : binding.getDeclaredMethods()) {
            boolean wanted = m.isConstructor()
                    || (binding.isEnum() && (m.getName().equals("values") || m.getName().equals("valueOf")))
                    || (binding.isRecord() && m.getParameterTypes().length == 0 && components.contains(m.getName()));
            if (!wanted || (m.isDefaultConstructor() && lombokConstructors)) {
                continue;
            }
            List<String> params = new ArrayList<>();
            List<String> paramTypes = new ArrayList<>();
            for (ITypeBinding p : m.getParameterTypes()) {
                params.add(project.binary(p));
                paramTypes.add(p.getName());
            }
            String name = m.isConstructor() ? Ids.CONSTRUCTOR_NAME : m.getName();
            String id = Ids.javaMethod(frame.name, name, params);
            if (!frame.declaredMethods.add(id)) {
                continue;
            }
            String display = m.isConstructor() ? JavaProject.simpleName(frame.name) : name;
            Node.Builder b = Node.builder(id, NodeKind.JAVA_METHOD).name(name)
                    .qualifiedName(frame.name + "#" + name + "(" + String.join(",", paramTypes) + ")")
                    .location(graph.node(frame.id).map(Node::location).orElse(null))
                    .attr("synthetic", true)
                    .attr("constructor", m.isConstructor())
                    .attr("modifiers", modifierNames(m.getModifiers()))
                    .attr("parameterTypes", paramTypes)
                    .attr("signature", display + "(" + String.join(", ", paramTypes) + ")"
                            + (m.isConstructor() ? "" : ": " + m.getReturnType().getName()));
            if (!m.isConstructor()) {
                b.attr("returnType", m.getReturnType().getName());
            }
            graph.declare(b.build());
            graph.addEdge(Edge.of(frame.id, id, EdgeKind.CONTAINS));
            project.calls.index(frame.name, new CallResolver.MethodInfo(id, name, params, false, Modifier.isStatic(m.getModifiers()),
                    m.isConstructor(), m.isConstructor() ? null : chainType(m.getReturnType()), false, false));
            if (!m.isConstructor() && m.getReturnType() != null && !m.getReturnType().isTypeVariable()) {
                project.calls.returnBinary(id, project.binary(m.getReturnType()));
            }
        }
    }

    /** 이어서 메서드를 부를 수 있는 타입이면 그 바이너리 이름, 아니면(원시 타입·배열·타입 변수·이름을 모르는 타입) null. */
    private String chainType(ITypeBinding type) {
        if (type == null || type.isArray() || type.isPrimitive()) {
            return null;
        }
        ITypeBinding reduced = Unit.reduce(type);
        if (reduced == null) {
            return null;
        }
        TypeRef ref = unit.ref(reduced);
        return ref.isUnresolved() ? null : ref.name();
    }

    // ---- 멤버 ----

    @Override
    public boolean visit(FieldDeclaration node) {
        Frame frame = frames.peek();
        if (frame == null) {
            return true;
        }
        List<String> modifiers = modifierNames(node.modifiers());
        for (Object f : node.fragments()) {
            VariableDeclarationFragment fragment = (VariableDeclarationFragment) f;
            String typeText = node.getType().toString() + "[]".repeat(fragment.getExtraDimensions());
            IVariableBinding vb = fragment.resolveBinding();
            Object constant = vb == null ? null : vb.getConstantValue();
            String fieldId = field(frame, fragment.getName(), unit.declLoc(fragment, fragment.getName()), typeText, modifiers,
                    constant, false);
            associate(frame, node.getType().resolveBinding(), node.getType(), fragment.getExtraDimensions() > 0,
                    fragment.getName().getIdentifier(), unit.loc(fragment));
            annotations(fieldId, node.modifiers());
        }
        return true;
    }

    @Override
    public boolean visit(EnumConstantDeclaration node) {
        Frame frame = frames.peek();
        if (frame != null) {
            String fieldId = field(frame, node.getName(), unit.declLoc(node, node.getName()), JavaProject.simpleName(frame.name),
                    List.of("public", "static", "final"), null, true);
            annotations(fieldId, node.modifiers());
        }
        return true;
    }

    private String field(Frame frame, SimpleName name, SourceLocation location, String typeText, List<String> modifiers,
                         Object constant, boolean enumConstant) {
        String id = Ids.javaField(frame.name, name.getIdentifier());
        Node.Builder b = Node.builder(id, NodeKind.JAVA_FIELD).name(name.getIdentifier())
                .qualifiedName(frame.name + "#" + name.getIdentifier()).location(location)
                .attr("type", typeText).attr("modifiers", modifiers).attr("enumConstant", enumConstant);
        if (constant != null) {
            b.attr("constantValue", String.valueOf(constant));
        }
        graph.declare(b.build());
        graph.addEdge(Edge.of(frame.id, id, EdgeKind.CONTAINS));
        return id;
    }

    @Override
    public boolean visit(MethodDeclaration node) {
        Frame frame = frames.peek();
        if (frame == null) {
            return true;
        }
        String id = unit.methodId(node);
        frame.declaredMethods.add(id);
        String name = node.isConstructor() ? Ids.CONSTRUCTOR_NAME : node.getName().getIdentifier();
        List<String> types = new ArrayList<>();
        List<String> names = new ArrayList<>();
        boolean varargs = false;
        for (Object o : node.parameters()) {
            SingleVariableDeclaration p = (SingleVariableDeclaration) o;
            types.add(p.getType().toString() + "[]".repeat(p.getExtraDimensions()) + (p.isVarargs() ? "..." : ""));
            names.add(p.getName().getIdentifier());
            varargs |= p.isVarargs();
        }
        if (node.isCompactConstructor() && frame.node instanceof RecordDeclaration record) {
            for (Object c : record.recordComponents()) {
                SingleVariableDeclaration p = (SingleVariableDeclaration) c;
                types.add(p.getType().toString());
                names.add(p.getName().getIdentifier());
            }
        }
        String returnType = node.isConstructor() || node.getReturnType2() == null ? null
                : node.getReturnType2().toString() + "[]".repeat(node.getExtraDimensions());
        String display = node.isConstructor() ? node.getName().getIdentifier() : name;
        List<String> thrown = new ArrayList<>();
        for (Object t : node.thrownExceptionTypes()) {
            thrown.add(t.toString());
        }
        Node.Builder b = Node.builder(id, NodeKind.JAVA_METHOD).name(name)
                .qualifiedName(frame.name + "#" + name + "(" + String.join(",", types) + ")")
                .location(unit.declLoc(node, node.getName()))
                .attr("modifiers", modifierNames(node.modifiers()))
                .attr("parameterTypes", types)
                .attr("parameterNames", names)
                .attr("constructor", node.isConstructor())
                .attr("signature", display + "(" + String.join(", ", types) + ")" + (returnType == null ? "" : ": " + returnType));
        if (returnType != null) {
            b.attr("returnType", returnType);
        }
        if (varargs) {
            b.attr("varargs", true);
        }
        if (!node.typeParameters().isEmpty()) {
            b.attr("typeParameters", typeParameterNames(node.typeParameters()));
        }
        if (!thrown.isEmpty()) {
            b.attr("throws", thrown);
        }
        graph.declare(b.build());
        graph.addEdge(Edge.of(frame.id, id, EdgeKind.CONTAINS));
        annotations(id, node.modifiers());
        // 호출 해석용 색인(F-003-R11): 합성 메서드의 결과에 이어 부른 호출을 이름·인자 수로 찾는다
        IMethodBinding binding = node.resolveBinding();
        String returnChain = null;
        if (!node.isConstructor()) {
            if (binding != null) {
                returnChain = chainType(binding.getReturnType());
            } else if (node.getReturnType2() != null && !node.getReturnType2().isPrimitiveType() && !node.getReturnType2().isArrayType()
                    && node.getExtraDimensions() == 0) {
                TypeRef ref = unit.ref(node.getReturnType2());
                returnChain = ref == null || ref.isUnresolved() ? null : ref.name();
            }
        }
        project.calls.index(frame.name, new CallResolver.MethodInfo(id, name, CallResolver.parametersOf(id), varargs,
                Modifier.isStatic(node.getModifiers()), node.isConstructor(), returnChain, false, false));
        if (!node.isConstructor() && binding != null && binding.getReturnType() != null && !binding.getReturnType().isTypeVariable()
                && !(binding.getReturnType().isArray() && binding.getReturnType().getElementType().isTypeVariable())) {
            project.calls.returnBinary(id, project.binary(binding.getReturnType()));
        }
        return true;
    }

    @Override
    public boolean visit(AnnotationTypeMemberDeclaration node) {
        Frame frame = frames.peek();
        if (frame == null) {
            return true;
        }
        String name = node.getName().getIdentifier();
        String id = Ids.javaMethod(frame.name, name);
        frame.declaredMethods.add(id);
        graph.declare(Node.builder(id, NodeKind.JAVA_METHOD).name(name).qualifiedName(frame.name + "#" + name + "()")
                .location(unit.declLoc(node, node.getName()))
                .attr("modifiers", modifierNames(node.modifiers()))
                .attr("parameterTypes", List.of()).attr("parameterNames", List.of())
                .attr("constructor", false)
                .attr("returnType", node.getType().toString())
                .attr("signature", name + "(): " + node.getType()).build());
        graph.addEdge(Edge.of(frame.id, id, EdgeKind.CONTAINS));
        return true;
    }

    // ---- ASSOCIATES ----

    private void associate(Frame frame, ITypeBinding binding, Type typeNode, boolean extraDimensions, String fieldName,
                           SourceLocation location) {
        Target target = binding == null ? null : unwrap(binding, extraDimensions ? "*" : "1", 0);
        TypeRef ref;
        String multiplicity;
        if (binding == null) {
            ref = unit.ref(typeNode);
            multiplicity = typeNode.isArrayType() || extraDimensions ? "*" : "1";
        } else if (target == null) {
            return;
        } else {
            ref = unit.ref(target.type);
            multiplicity = target.multiplicity;
        }
        if (ref == null || skipByPolicy(ref)) {
            return;
        }
        String key = unit.addTypeEdge(frame.id, EdgeKind.ASSOCIATES, ref, location,
                Map.of("field", fieldName, "multiplicity", multiplicity));
        if (key != null) {
            frame.strong.add(key);
        }
    }

    private record Target(ITypeBinding type, String multiplicity) {
    }

    /** 배열·컬렉션·Map(값)·Optional을 원소 타입으로 푼다. 연관 대상이 없으면 null. */
    private Target unwrap(ITypeBinding type, String multiplicity, int depth) {
        if (type == null || depth > 8) {
            return null;
        }
        if (type.isArray()) {
            return unwrap(type.getElementType(), "*", depth + 1);
        }
        if (type.isWildcardType() || type.isCapture()) {
            ITypeBinding bound = type.getBound();
            return bound == null ? null : unwrap(bound, multiplicity, depth + 1);
        }
        if (type.isPrimitive() || type.isTypeVariable() || type.isNullType() || type.isIntersectionType()) {
            return null;
        }
        ITypeBinding erasure = type.getErasure() == null ? type : type.getErasure();
        if (!erasure.isRecovered()) {
            ITypeBinding[] args = type.getTypeArguments();
            if (isSubtypeOf(erasure, "java.util.Map")) {
                return args.length == 2 ? unwrap(args[1], "*", depth + 1) : null;
            }
            if (isSubtypeOf(erasure, "java.util.Collection")) {
                return args.length == 1 ? unwrap(args[0], "*", depth + 1) : null;
            }
            if ("java.util.Optional".equals(erasure.getQualifiedName())) {
                return args.length == 1 ? unwrap(args[0], multiplicity, depth + 1) : null;
            }
        }
        return new Target(erasure, multiplicity);
    }

    static boolean isSubtypeOf(ITypeBinding type, String qualifiedName) {
        return isSubtypeOf(type, qualifiedName, 0);
    }

    private static boolean isSubtypeOf(ITypeBinding type, String qualifiedName, int depth) {
        if (type == null || depth > 20) {
            return false;
        }
        ITypeBinding erasure = type.getErasure() == null ? type : type.getErasure();
        if (qualifiedName.equals(erasure.getQualifiedName())) {
            return true;
        }
        if (isSubtypeOf(type.getSuperclass(), qualifiedName, depth + 1)) {
            return true;
        }
        for (ITypeBinding i : type.getInterfaces()) {
            if (isSubtypeOf(i, qualifiedName, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /** java.lang.*은 항상, JDK 타입은 옵션이 없으면 연관·의존 대상에서 뺀다. */
    private boolean skipByPolicy(TypeRef ref) {
        if (ref.isUnresolved() || ref.isProject()) {
            return false;
        }
        return JavaProject.isJavaLang(ref.name()) || (!project.options.includeJdkTypes() && project.isJdk(ref.name()));
    }

    // ---- ANNOTATED_BY ----

    private void annotations(String fromId, List<?> modifiers) {
        for (Object m : modifiers) {
            if (m instanceof Annotation annotation) {
                TypeRef ref = unit.ref(annotation.getTypeName());
                if (ref == null || (!ref.isUnresolved() && JavaProject.isJavaLang(ref.name()))) {
                    continue;
                }
                unit.addTypeEdge(fromId, EdgeKind.ANNOTATED_BY, ref, unit.loc(annotation), Map.of());
            }
        }
    }

    // ---- DEPENDS_ON 수집 ----

    private void dep(TypeRef ref, ASTNode at) {
        Frame frame = frames.peek();
        if (frame == null || ref == null || skipByPolicy(ref) || ref.name().equals(frame.name)) {
            return;
        }
        String key = ref.isUnresolved() ? "?" + ref.name() : ref.name();
        frame.deps.computeIfAbsent(key, k -> new Dep(ref, unit.loc(at)));
    }

    private void dep(ITypeBinding binding, ASTNode at) {
        ITypeBinding reduced = Unit.reduce(binding);
        if (reduced != null) {
            dep(unit.ref(reduced), at);
        }
    }

    @Override
    public boolean visit(SimpleType node) {
        dep(unit.ref(node), node);
        return false;
    }

    @Override
    public boolean visit(QualifiedType node) {
        dep(unit.ref(node), node);
        return true;
    }

    @Override
    public boolean visit(NameQualifiedType node) {
        dep(unit.ref(node), node);
        return false;
    }

    @Override
    public boolean visit(MarkerAnnotation node) {
        return false;
    }

    @Override
    public boolean visit(NormalAnnotation node) {
        for (Object v : node.values()) {
            ((ASTNode) v).accept(this);
        }
        return false;
    }

    @Override
    public boolean visit(SingleMemberAnnotation node) {
        node.getValue().accept(this);
        return false;
    }

    @Override
    public boolean visit(QualifiedName node) {
        if (node.resolveBinding() instanceof ITypeBinding type) {
            dep(type, node);
            return false;
        }
        return true;
    }

    @Override
    public boolean visit(SimpleName node) {
        if (node.isDeclaration()) {
            return false;
        }
        IBinding binding = node.resolveBinding();
        if (binding instanceof ITypeBinding type) {
            dep(type, node);
        } else if (binding instanceof IVariableBinding variable && variable.isField() && variable.getDeclaringClass() != null) {
            dep(variable.getDeclaringClass(), node);
        }
        return false;
    }

    @Override
    public boolean visit(MethodInvocation node) {
        IMethodBinding method = node.resolveMethodBinding();
        if (method != null && method.getDeclaringClass() != null && !unit.isBadCall(node.getName().getStartPosition())) {
            dep(method.getDeclaringClass(), node.getName());
        }
        // 메서드 이름은 타입이 아니므로 수신자와 인자만 내려간다
        if (node.getExpression() != null) {
            node.getExpression().accept(this);
        }
        for (Object t : node.typeArguments()) {
            ((ASTNode) t).accept(this);
        }
        for (Object a : node.arguments()) {
            ((ASTNode) a).accept(this);
        }
        return false;
    }

    // ---- 표시용 문자열 ----

    static List<String> modifierNames(List<?> modifiers) {
        List<String> names = new ArrayList<>();
        for (Object m : modifiers) {
            if (m instanceof IExtendedModifier em && em.isModifier()) {
                names.add(((Modifier) m).getKeyword().toString());
            }
        }
        return names;
    }

    static List<String> modifierNames(int flags) {
        List<String> names = new ArrayList<>();
        if (Modifier.isPublic(flags)) {
            names.add("public");
        }
        if (Modifier.isProtected(flags)) {
            names.add("protected");
        }
        if (Modifier.isPrivate(flags)) {
            names.add("private");
        }
        if (Modifier.isStatic(flags)) {
            names.add("static");
        }
        if (Modifier.isFinal(flags)) {
            names.add("final");
        }
        return names;
    }

    private static List<String> typeParameterNames(List<?> typeParameters) {
        List<String> names = new ArrayList<>();
        for (Object t : typeParameters) {
            names.add(((TypeParameter) t).toString());
        }
        return names;
    }
}
