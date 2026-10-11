package io.codeatlas.java;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.SourceFile;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.AnnotationTypeMemberDeclaration;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.ArrayInitializer;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.ArrayType;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NameQualifiedType;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 컴파일 단위(파일) 하나의 문맥. 위치 계산, import를 이용한 이름 복원, 선언 노드의 ID를 맡는다.
 */
final class Unit {

    final JavaProject project;
    final SourceFile file;
    final CompilationUnit cu;
    final String packageName;

    /** 단일 import: 단순 이름 → FQN. */
    final Map<String, String> singleImports = new HashMap<>();
    /** 단일 import 가운데 컴파일러가 풀지 못한 것(classpath에 없는 타입): 단순 이름 → FQN. */
    final Map<String, String> unresolvedImports = new HashMap<>();
    /** 와일드카드 import의 패키지(또는 타입) 이름. */
    final List<String> onDemandImports = new ArrayList<>();
    /** 단일 static import: 멤버 이름 → 타입 FQN. */
    final Map<String, String> staticImports = new HashMap<>();
    /** 와일드카드 static import의 타입 FQN. */
    final List<String> staticOnDemandImports = new ArrayList<>();

    /**
     * Lombok이 타입 하나에 만든 것 중 다른 추출기가 알아야 하는 것(F-002-R14).
     * {@link LombokSynthesizer}가 구조 추출 때 채운다.
     */
    static final class LombokType {
        /** 합성 생성자(선언 순서). */
        final List<LombokCtor> constructors = new ArrayList<>();
        /** 합성 필드: 이름 → 타입의 FQN({@code log}). */
        final Map<String, String> fields = new HashMap<>();
    }

    /**
     * 합성 생성자 하나.
     *
     * @param id 메서드 노드 ID
     * @param fields 매개변수가 된 필드 선언(매개변수 순서)
     */
    record LombokCtor(String id, List<VariableDeclarationFragment> fields) {
    }

    /** 합성 필드 참조: 필드 노드 ID와 타입의 FQN. */
    record LombokField(String fieldId, String type) {
    }

    /** 타입 선언 노드 → Lombok이 만든 것. Lombok 애너테이션이 없는 타입은 들어 있지 않다. */
    final Map<ASTNode, LombokType> lombokTypes = new IdentityHashMap<>();

    private final Map<ASTNode, String> typeNames = new IdentityHashMap<>();
    private final Map<MethodDeclaration, String> methodIds = new IdentityHashMap<>();
    /** 컴파일러가 인자 불일치·모호함을 보고한 호출의 시작 위치. 이런 호출의 바인딩은 "가장 가까운 후보"라 믿지 않는다. */
    private final Set<Integer> badCallStarts = new HashSet<>();

    Unit(JavaProject project, SourceFile file, CompilationUnit cu) {
        this.project = project;
        this.file = file;
        this.cu = cu;
        this.packageName = cu.getPackage() == null ? "" : cu.getPackage().getName().getFullyQualifiedName();
        for (Object o : cu.imports()) {
            ImportDeclaration imp = (ImportDeclaration) o;
            String name = imp.getName().getFullyQualifiedName();
            if (imp.isStatic()) {
                if (imp.isOnDemand()) {
                    staticOnDemandImports.add(name);
                } else {
                    int dot = name.lastIndexOf('.');
                    if (dot > 0) {
                        staticImports.put(name.substring(dot + 1), name.substring(0, dot));
                    }
                }
            } else if (imp.isOnDemand()) {
                onDemandImports.add(name);
            } else {
                singleImports.put(JavaProject.simpleName(name), name);
                IBinding imported = imp.resolveBinding();
                if (imported == null || (imported instanceof ITypeBinding type && type.isRecovered())) {
                    unresolvedImports.put(JavaProject.simpleName(name), name);
                }
            }
        }
        for (IProblem problem : cu.getProblems()) {
            if (problem.isError() && isCallMismatch(problem.getID())) {
                badCallStarts.add(problem.getSourceStart());
            }
        }
        assignTypeNames();
    }

    private static boolean isCallMismatch(int id) {
        return id == IProblem.UndefinedMethod || id == IProblem.ParameterMismatch || id == IProblem.AmbiguousMethod
                || id == IProblem.UndefinedConstructor || id == IProblem.AmbiguousConstructor
                || id == IProblem.ParameterizedMethodArgumentTypeMismatch
                || id == IProblem.ParameterizedConstructorArgumentTypeMismatch
                || id == IProblem.UndefinedConstructorInDefaultConstructor || id == IProblem.UndefinedConstructorInImplicitConstructorCall;
    }

    /** 이 위치에서 시작하는 호출을 컴파일러가 해석 실패로 보고했는가. */
    boolean isBadCall(int startPosition) {
        return badCallStarts.contains(startPosition);
    }

    // ---- 위치 ----

    SourceLocation loc(ASTNode node) {
        return loc(node.getStartPosition(), node.getLength());
    }

    SourceLocation loc(int start, int length) {
        if (start < 0) {
            return file.location();
        }
        // 끝 위치는 마지막 글자 다음(제외)이다
        int last = start + Math.max(length, 1) - 1;
        return file.location(Math.max(cu.getLineNumber(start), 0), Math.max(cu.getColumnNumber(start) + 1, 0),
                Math.max(cu.getLineNumber(last), 0), Math.max(cu.getColumnNumber(last) + 2, 0));
    }

    /** 노드가 시작하는 자리를 {@code 줄:열}로 적은 것(둘 다 1부터). attrs의 병렬 위치 목록({@code viewNameLines} 등)에 쓴다. */
    String lineColumn(ASTNode node) {
        int start = node.getStartPosition();
        return Math.max(cu.getLineNumber(start), 0) + ":" + Math.max(cu.getColumnNumber(start) + 1, 0);
    }

    /** 선언의 위치: 이름에서 시작해 선언 끝까지(앞의 주석과 애너테이션은 뺀다). */
    SourceLocation declLoc(ASTNode declaration, ASTNode name) {
        if (name == null) {
            return loc(declaration);
        }
        int end = declaration.getStartPosition() + declaration.getLength();
        return loc(name.getStartPosition(), Math.max(end - name.getStartPosition(), name.getLength()));
    }

    // ---- 타입 선언의 바이너리 이름 ----

    /** 소스 순서대로 모든 타입 선언의 바이너리 이름을 확정한다(익명·로컬 타입의 번호가 순서에 따라 정해지기 때문). */
    private void assignTypeNames() {
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                return assign(node, node.resolveBinding(), node.getName().getIdentifier());
            }

            @Override
            public boolean visit(EnumDeclaration node) {
                return assign(node, node.resolveBinding(), node.getName().getIdentifier());
            }

            @Override
            public boolean visit(RecordDeclaration node) {
                return assign(node, node.resolveBinding(), node.getName().getIdentifier());
            }

            @Override
            public boolean visit(AnnotationTypeDeclaration node) {
                return assign(node, node.resolveBinding(), node.getName().getIdentifier());
            }

            @Override
            public boolean visit(AnonymousClassDeclaration node) {
                return assign(node, node.resolveBinding(), null);
            }
        });
    }

    private boolean assign(ASTNode node, ITypeBinding binding, String simpleName) {
        String name = null;
        if (binding != null && !binding.isRecovered()) {
            name = binding.getBinaryName();
        }
        if (name == null || name.isEmpty()) {
            ASTNode outer = enclosingType(node.getParent());
            if (outer == null) {
                name = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
            } else if (simpleName == null) {
                name = typeNames.get(outer) + "$anon" + cu.getLineNumber(node.getStartPosition());
            } else if (node.getParent() instanceof AbstractTypeDeclaration || node.getParent() instanceof AnonymousClassDeclaration) {
                name = typeNames.get(outer) + "$" + simpleName;
            } else {
                name = typeNames.get(outer) + "$1" + simpleName;
            }
        }
        typeNames.put(node, name);
        return true;
    }

    /** 타입 선언 노드(익명 클래스 본문 포함)의 바이너리 이름. */
    String typeName(ASTNode typeDeclaration) {
        return typeNames.get(typeDeclaration);
    }

    /**
     * 프로젝트 타입(바이너리 이름)을 선언한 파일의 import. 이 파일에 선언한 타입이면 이 파일의 것이고,
     * 다른 파일의 타입인데 어느 파일인지 모르면(최상위 타입 이름이 파일 이름과 다름) 빈 import다.
     */
    SourcePrescan.FileImports importsFor(String binaryName) {
        if (typeNames.containsValue(binaryName)) {
            return new SourcePrescan.FileImports(singleImports, onDemandImports);
        }
        SourcePrescan.FileImports imports = project.importsOf(binaryName);
        return imports == null ? SourcePrescan.FileImports.NONE : imports;
    }

    /** classpath에 없는 타입의 이름을, 그 이름을 쓴 프로젝트 타입(바이너리 이름)의 파일 문맥으로 FQN으로 복원한다. 복원하지 못하면 null. */
    String resolveWrittenIn(String written, String declaredIn) {
        SourcePrescan.FileImports imports = importsFor(declaredIn);
        return project.resolveWritten(written, imports.single(), imports.onDemand());
    }

    /** node부터 위로 올라가며 처음 만나는 타입 선언(익명 클래스 본문 포함). */
    static ASTNode enclosingType(ASTNode node) {
        for (ASTNode p = node; p != null; p = p.getParent()) {
            if (p instanceof AbstractTypeDeclaration || p instanceof AnonymousClassDeclaration) {
                return p;
            }
        }
        return null;
    }

    static ITypeBinding typeBinding(ASTNode typeDeclaration) {
        if (typeDeclaration instanceof AbstractTypeDeclaration t) {
            return t.resolveBinding();
        }
        if (typeDeclaration instanceof AnonymousClassDeclaration a) {
            return a.resolveBinding();
        }
        return null;
    }

    // ---- 메서드 ID ----

    /** 메서드 선언의 노드 ID. 바인딩이 없으면(심하게 깨진 코드) 소스에 쓴 타입 이름으로 만든다. */
    String methodId(MethodDeclaration method) {
        return methodIds.computeIfAbsent(method, m -> {
            IMethodBinding binding = m.resolveBinding();
            ASTNode owner = enclosingType(m.getParent());
            if (binding != null && binding.getDeclaringClass() != null && owner != null) {
                // 선언한 타입 이름은 이 파일에서 확정한 것을 쓴다
                List<String> params = new ArrayList<>();
                for (ITypeBinding p : binding.getMethodDeclaration().getParameterTypes()) {
                    // 호출 쪽(JavaProject.methodId)과 같은 규칙: 선언한 타입의 파일이 단일 import한 이름은 그 FQN이다
                    params.add(project.parameterName(p, typeName(owner)));
                }
                return Ids.javaMethod(typeName(owner), binding.isConstructor() ? Ids.CONSTRUCTOR_NAME : binding.getName(), params);
            }
            List<String> params = new ArrayList<>();
            for (Object o : m.parameters()) {
                SingleVariableDeclaration p = (SingleVariableDeclaration) o;
                ITypeBinding pb = p.getType().resolveBinding();
                String name = pb != null ? project.binary(pb) : project.globalName(written(p.getType()));
                params.add(name + "[]".repeat(p.getExtraDimensions() + (p.isVarargs() ? 1 : 0)));
            }
            String typeName = owner == null ? packageName : typeName(owner);
            return Ids.javaMethod(typeName, m.isConstructor() ? Ids.CONSTRUCTOR_NAME : m.getName().getIdentifier(), params);
        });
    }

    // ---- 애너테이션 ----

    /**
     * 애너테이션 하나. classpath에 없어도 import로 FQN을 복원하고, 복원하지 못하면 단순 이름으로 비교한다.
     *
     * @param node AST 노드
     * @param fqn 바이너리 이름. 알 수 없으면 null
     * @param simpleName 소스에 쓴 이름의 마지막 조각
     */
    record Anno(Annotation node, String fqn, String simpleName) {

        /** 주어진 FQN의 애너테이션인가. FQN을 모르면 단순 이름이 같은지로 판단한다. */
        boolean is(String annotationFqn) {
            return fqn != null ? fqn.equals(annotationFqn) : simpleName.equals(JavaProject.simpleName(annotationFqn));
        }

        boolean isAny(String... annotationFqns) {
            for (String f : annotationFqns) {
                if (is(f)) {
                    return true;
                }
            }
            return false;
        }

        /** 멤버 값. {@code @A("x")} 형태는 이름 {@code value}로 찾는다. 없으면 null. */
        Expression value(String member) {
            if (node instanceof SingleMemberAnnotation single) {
                return "value".equals(member) ? single.getValue() : null;
            }
            if (node instanceof NormalAnnotation normal) {
                for (Object o : normal.values()) {
                    MemberValuePair pair = (MemberValuePair) o;
                    if (pair.getName().getIdentifier().equals(member)) {
                        return pair.getValue();
                    }
                }
            }
            return null;
        }

        /** 여러 이름 중 처음 있는 멤버의 값을 배열 초기화식이면 펼쳐서 돌려준다. */
        List<Expression> values(String... members) {
            for (String member : members) {
                Expression value = value(member);
                if (value != null) {
                    List<Expression> list = new ArrayList<>();
                    if (value instanceof ArrayInitializer array) {
                        for (Object e : array.expressions()) {
                            list.add((Expression) e);
                        }
                    } else {
                        list.add(value);
                    }
                    return list;
                }
            }
            return List.of();
        }
    }

    /** 수식어 목록(선언의 modifiers())에서 애너테이션을 뽑는다. */
    List<Anno> annos(List<?> modifiers) {
        List<Anno> list = new ArrayList<>();
        for (Object m : modifiers) {
            if (m instanceof Annotation annotation) {
                TypeRef ref = ref(annotation.getTypeName());
                String fqn = ref == null || ref.isUnresolved() ? null : ref.name();
                list.add(new Anno(annotation, fqn, JavaProject.simpleName(annotation.getTypeName().getFullyQualifiedName())));
            }
        }
        return list;
    }

    static Anno find(List<Anno> annos, String... fqns) {
        for (Anno a : annos) {
            if (a.isAny(fqns)) {
                return a;
            }
        }
        return null;
    }

    // ---- 코드의 소유자 ----

    /**
     * 호출·필드 접근이 귀속되는 메서드.
     *
     * @param methodId 소유자 메서드 ID(합성 노드일 수 있다)
     * @param viaLambda 람다 본문 안이면 true
     */
    record Owner(String methodId, boolean viaLambda) {
    }

    /**
     * node를 감싼 소유자를 찾는다. 람다는 건너뛰어 감싼 메서드로 가고, 필드 초기화식·초기화 블록·enum 상수 인자는 합성 소유자로 간다.
     * 애너테이션 안의 식처럼 실행 코드가 아니면 null.
     */
    Owner owner(ASTNode node) {
        boolean lambda = false;
        for (ASTNode p = node.getParent(); p != null; p = p.getParent()) {
            if (p instanceof Annotation) {
                return null;
            }
            if (p instanceof LambdaExpression) {
                lambda = true;
            } else if (p instanceof MethodDeclaration m) {
                return new Owner(methodId(m), lambda);
            } else if (p instanceof Initializer init) {
                ASTNode type = enclosingType(init.getParent());
                return type == null ? null : new Owner(Modifier.isStatic(init.getModifiers()) ? clinit(type) : instanceInit(type), lambda);
            } else if (p instanceof FieldDeclaration field) {
                ASTNode type = enclosingType(field.getParent());
                if (type == null) {
                    return null;
                }
                boolean isStatic = Modifier.isStatic(field.getModifiers()) || type instanceof AnnotationTypeDeclaration
                        || (type instanceof TypeDeclaration t && t.isInterface());
                return new Owner(isStatic ? clinit(type) : instanceInit(type), lambda);
            } else if (p instanceof EnumConstantDeclaration constant) {
                ASTNode type = enclosingType(constant.getParent());
                return type == null ? null : new Owner(clinit(type), lambda);
            } else if (p instanceof AbstractTypeDeclaration || p instanceof AnonymousClassDeclaration
                    || p instanceof AnnotationTypeMemberDeclaration) {
                return null;
            }
        }
        return null;
    }

    /** static 초기화 코드의 소유자 {@code <clinit>()}. */
    String clinit(ASTNode typeDeclaration) {
        String typeName = typeName(typeDeclaration);
        return project.syntheticMethod(typeName, JavaProject.CLINIT, project.typeLocation(typeName));
    }

    /** 인스턴스 초기화 코드의 소유자: 명시적 생성자가 없으면 기본 생성자, 하나면 그 생성자, 여럿이면 {@code <instance-init>()}. */
    String instanceInit(ASTNode typeDeclaration) {
        String typeName = typeName(typeDeclaration);
        List<MethodDeclaration> constructors = new ArrayList<>();
        if (typeDeclaration instanceof AbstractTypeDeclaration type) {
            for (Object member : type.bodyDeclarations()) {
                if (member instanceof MethodDeclaration m && m.isConstructor()) {
                    constructors.add(m);
                }
            }
        }
        // Lombok이 만드는 생성자도 생성자로 센다
        LombokType lombok = lombokTypes.get(typeDeclaration);
        int synthesized = lombok == null ? 0 : lombok.constructors.size();
        if (constructors.size() + synthesized == 1) {
            return synthesized == 1 ? lombok.constructors.get(0).id() : methodId(constructors.get(0));
        }
        if (constructors.size() + synthesized > 1) {
            return project.syntheticMethod(typeName, JavaProject.INSTANCE_INIT, project.typeLocation(typeName));
        }
        ITypeBinding binding = typeBinding(typeDeclaration);
        if (binding != null) {
            for (IMethodBinding m : binding.getDeclaredMethods()) {
                if (m.isConstructor()) {
                    List<String> params = new ArrayList<>();
                    for (ITypeBinding p : m.getParameterTypes()) {
                        params.add(project.binary(p));
                    }
                    return project.syntheticMethod(typeName, Ids.CONSTRUCTOR_NAME, params, project.typeLocation(typeName));
                }
            }
        }
        return project.syntheticMethod(typeName, Ids.CONSTRUCTOR_NAME, project.typeLocation(typeName));
    }

    /** 해석되지 않은 이름이 감싼 타입(바깥 타입 포함)의 Lombok 합성 필드({@code log})면 그 필드. 아니면 null. */
    LombokField lombokField(SimpleName name) {
        if (lombokTypes.isEmpty()) {
            return null;
        }
        for (ASTNode type = enclosingType(name); type != null; type = enclosingType(type.getParent())) {
            LombokType lombok = lombokTypes.get(type);
            String fieldType = lombok == null ? null : lombok.fields.get(name.getIdentifier());
            if (fieldType != null) {
                return new LombokField(Ids.javaField(typeName(type), name.getIdentifier()), fieldType);
            }
        }
        return null;
    }

    // ---- 타입 참조 해석 ----

    /** 소스에 쓴 타입 이름(제네릭 인자와 배열 차원 제외). */
    static String written(Type type) {
        if (type instanceof SimpleType t) {
            return t.getName().getFullyQualifiedName();
        }
        if (type instanceof ParameterizedType t) {
            return written(t.getType());
        }
        if (type instanceof QualifiedType t) {
            return written(t.getQualifier()) + "." + t.getName().getIdentifier();
        }
        if (type instanceof NameQualifiedType t) {
            return t.getQualifier().getFullyQualifiedName() + "." + t.getName().getIdentifier();
        }
        if (type instanceof ArrayType t) {
            return written(t.getElementType());
        }
        return type.toString();
    }

    /**
     * classpath에 없는 타입의 이름을 이 파일의 import로 FQN으로 복원한다. 복원하지 못하면 null.
     * 단일 import → 전체 이름으로 쓴 경우 → 와일드카드 import + 알려진 프레임워크 타입(Lombok 애너테이션 포함)
     * → 프로젝트의 다른 파일이 단일 import한 유일한 FQN이 이 파일의 와일드카드 패키지에 속하는 경우.
     */
    String resolveWritten(String written) {
        return project.resolveWritten(written, singleImports, onDemandImports);
    }

    /** 배열은 원소 타입으로, 제네릭은 erasure로 줄인다. 타입 변수·와일드카드·원시 타입·null은 null. */
    static ITypeBinding reduce(ITypeBinding binding) {
        if (binding == null) {
            return null;
        }
        ITypeBinding b = binding.isArray() ? binding.getElementType() : binding;
        if (b == null || b.isPrimitive() || b.isNullType() || b.isTypeVariable() || b.isWildcardType() || b.isCapture()
                || b.isIntersectionType()) {
            return null;
        }
        ITypeBinding erasure = b.getErasure();
        return erasure == null ? b : erasure;
    }

    /**
     * 이 파일이 단일 import한 이름인데 그 import를 컴파일러가 풀지 못해 다른 타입({@code java.lang}, 같은 패키지, 와일드카드 import한
     * 패키지의 같은 이름)으로 풀어 버린 바인딩이면 import의 FQN, 아니면 null(F-002-R20). 배열은 원소 타입으로 본다.
     */
    String importedInstead(ITypeBinding binding) {
        if (binding == null || unresolvedImports.isEmpty()) {
            return null;
        }
        return project.importedInstead(binding.isArray() ? binding.getElementType() : binding, packageName, unresolvedImports,
                onDemandImports, true);
    }

    /**
     * 클래스·인터페이스 타입 바인딩을 타입 참조로 바꾼다. {@link #reduce(ITypeBinding)}한 것을 준다.
     * 구조·시그니처·호출 해석이 모두 이 변환을 거친다: 단일 import한 이름은 컴파일러가 다른 타입으로 풀었어도 import의 FQN이다.
     */
    TypeRef ref(ITypeBinding reduced) {
        String imported = importedInstead(reduced);
        if (imported != null) {
            return project.byName(imported);
        }
        if (reduced.isRecovered()) {
            String written = JavaProject.written(reduced);
            String fqn = resolveWritten(written);
            return fqn != null ? TypeRef.external(fqn) : TypeRef.unresolved(written);
        }
        String name = project.binary(reduced);
        if (reduced.isFromSource()) {
            return TypeRef.project(name);
        }
        // 출처(JDK 시스템 모듈인가)를 이름별로 기억해 둔다
        project.isJdk(reduced, name);
        return TypeRef.external(name);
    }

    /** 타입 노드의 참조. 타입 변수나 원시 타입이면 null. */
    TypeRef ref(Type type) {
        ITypeBinding binding = type.resolveBinding();
        if (binding != null) {
            ITypeBinding reduced = reduce(binding);
            if (reduced == null) {
                return null;
            }
            // 복구된 바인딩은 전체 이름의 앞부분만 담고 있을 수 있어 소스에 쓴 이름으로 복원한다
            return reduced.isRecovered() && !type.isVar() ? refWritten(written(type)) : ref(reduced);
        }
        if (type.isPrimitiveType() || type.isVar()) {
            return null;
        }
        return refUnattempted(written(type));
    }

    /**
     * 컴파일러가 보지 않아 바인딩이 아예 없는 타입 이름(해석되지 않은 호출에 넘긴 람다 본문 등)을 소스의 이름 규칙대로 푼다:
     * import → 이 파일에 선언한 타입 → 같은 패키지와 와일드카드 import한 패키지의 프로젝트 타입 → {@code java.lang}.
     */
    TypeRef refUnattempted(String written) {
        String fqn = resolveWritten(written);
        if (fqn != null) {
            return project.byName(fqn);
        }
        int dot = written.indexOf('.');
        String first = dot < 0 ? written : written.substring(0, dot);
        String rest = written.substring(first.length()).replace('.', '$');
        for (String declared : typeNames.values()) {
            if (JavaProject.simpleName(declared).equals(first)) {
                return TypeRef.project(declared + rest);
            }
        }
        List<String> packages = new ArrayList<>();
        packages.add(packageName);
        packages.addAll(onDemandImports);
        for (String pkg : packages) {
            String candidate = pkg.isEmpty() ? first : pkg + "." + first;
            if (project.sourceTypes.contains(candidate)) {
                return TypeRef.project(candidate + rest);
            }
        }
        if (rest.isEmpty() && JdkTypes.isJavaLangType(first)) {
            return TypeRef.external("java.lang." + first);
        }
        // 와일드카드로 import한 JDK 패키지의 타입은 실행 중인 JDK에서 확인한다
        for (String pkg : onDemandImports) {
            String candidate = pkg + "." + first;
            if (JdkTypes.isJdkName(candidate) && JdkTypes.systemClass(candidate) != null) {
                return TypeRef.external(candidate + rest);
            }
        }
        return TypeRef.unresolved(written);
    }

    /** 바인딩이 없는 이름을 import로 복원한다. */
    TypeRef refWritten(String written) {
        String fqn = resolveWritten(written);
        return fqn != null ? TypeRef.external(fqn) : TypeRef.unresolved(written);
    }

    /** 이름 노드가 가리키는 타입의 참조(애너테이션 이름 등). */
    TypeRef ref(Name name) {
        if (name.resolveBinding() instanceof ITypeBinding tb) {
            ITypeBinding reduced = reduce(tb);
            if (reduced == null) {
                return null;
            }
            return reduced.isRecovered() ? refWritten(name.getFullyQualifiedName()) : ref(reduced);
        }
        return refWritten(name.getFullyQualifiedName());
    }

    /**
     * 타입으로 가는 엣지를 더한다. 이름을 복원하지 못한 타입이면 미해결 엣지(분류 java-type)를 남긴다.
     *
     * @return 엣지의 대상 키(중복 판단용). 더하지 않았으면 null
     */
    String addTypeEdge(String fromId, EdgeKind kind, TypeRef ref, SourceLocation location, Map<String, ?> attrs) {
        if (ref == null) {
            return null;
        }
        if (ref.isUnresolved()) {
            project.graph.addUnresolvedEdge(fromId, kind, JavaProject.CAT_TYPE, ref.name(), location);
            return "?" + ref.name();
        }
        project.graph.addEdge(Edge.builder(fromId, project.typeId(ref), kind).location(location).attrs(attrs).build());
        return ref.name();
    }
}
