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
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.SourceFile;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 분석 한 번(프로젝트 하나) 동안 공유하는 상태와 이름 규칙.
 * 바이너리 이름과 메서드 ID 계산, 소스 없는 타입·메서드의 참조 노드, 합성 메서드 노드를 맡는다.
 */
final class JavaProject {

    static final String SOURCE_ROOT_MISMATCH = "SOURCE_ROOT_MISMATCH";
    static final String EXTERNAL_CALLS_SKIPPED = "EXTERNAL_CALLS_SKIPPED";
    /** 대상을 정적으로 알 수 없어 기록하지 않은 리플렉션 호출({@code Method.invoke})이 있는 메서드(F-003-R20). */
    static final String REFLECTION_CALL = "REFLECTION_CALL";
    static final String CAT_TYPE = "java-type";
    static final String CAT_CALL = "java-call";
    static final String CAT_OVERRIDE = "java-override";
    static final String CLINIT = "<clinit>";
    static final String INSTANCE_INIT = "<instance-init>";

    final AnalysisContext ctx;
    final GraphModel graph;
    final JavaOptions options;
    /** 단순 이름 → 프로젝트의 단일 import에 나온 FQN들. */
    final Map<String, Set<String>> importedBySimpleName;

    /** 소스 파일 이름으로 본 최상위 타입의 FQN(패키지 + 파일 이름). 컴파일러가 보지 않은 곳의 타입 이름을 풀 때 쓴다. */
    final Set<String> sourceTypes;

    private final Set<String> referenced = new HashSet<>();
    private final Set<String> declaredPackages = new HashSet<>();
    /** 바이너리 이름 → 출처로 판정한 JDK 여부. */
    private final Map<String, Boolean> jdkByName = new HashMap<>();

    /** 지금 추출 중인 파일. JDT 내부 로그를 진단으로 바꿀 때 위치로 쓴다(F-002-R18). 파일 사이(일괄 파싱 중)에는 null. */
    SourceFile currentFile;

    /** 기록하지 않고 건너뛴 JDK 메서드 호출 수. */
    int skippedJdkCalls;
    /** 기록하지 않고 건너뛴 그 밖의 라이브러리 메서드 호출 수. */
    int skippedExternalCalls;

    /** F-004: 모은 빈 정의와 주입 지점. 모든 파일을 본 뒤에 후보를 계산한다. */
    final SpringModel spring = new SpringModel();
    /** F-005: 모은 잡·스텝 정의. 모든 파일을 본 뒤에 잇는다. */
    final BatchModel batch = new BatchModel();
    /** F-003: 호출 엣지 쓰기와, 모든 파일을 본 뒤에 하는 미해석 호출의 해석. */
    final CallResolver calls;

    /** 파일 이름으로 본 최상위 타입의 FQN → 그 파일의 import. */
    private final Map<String, SourcePrescan.FileImports> importsBySourceType;

    /** {@code @Value("${키}")} 필드: {@code <최상위 타입 FQN>#<필드명>} → 자리표시자 원문(F-004-R23). */
    Map<String, String> valueFields = Map.of();

    JavaProject(AnalysisContext ctx, JavaOptions options, SourcePrescan prescan) {
        this(ctx, options, prescan.importedBySimpleName, prescan.sourceTypes, prescan.importsBySourceType);
        this.valueFields = prescan.valueFields;
    }

    JavaProject(AnalysisContext ctx, JavaOptions options, Map<String, Set<String>> importedBySimpleName, Set<String> sourceTypes) {
        this(ctx, options, importedBySimpleName, sourceTypes, Map.of());
    }

    private JavaProject(AnalysisContext ctx, JavaOptions options, Map<String, Set<String>> importedBySimpleName, Set<String> sourceTypes,
                        Map<String, SourcePrescan.FileImports> importsBySourceType) {
        this.ctx = ctx;
        this.graph = ctx.graph();
        this.options = options;
        this.importedBySimpleName = importedBySimpleName;
        this.sourceTypes = sourceTypes;
        this.importsBySourceType = importsBySourceType;
        this.calls = new CallResolver(this);
    }

    /** 프로젝트 타입(바이너리 이름)을 선언한 파일의 import. 최상위 타입 이름이 파일 이름과 다르면 알 수 없다(null). */
    SourcePrescan.FileImports importsOf(String binaryName) {
        int nested = binaryName.indexOf('$');
        return importsBySourceType.get(nested < 0 ? binaryName : binaryName.substring(0, nested));
    }

    /**
     * classpath에 없는 타입의 이름을 그 이름을 쓴 파일의 import로 FQN으로 복원한다. 복원하지 못하면 null.
     * 단일 import → 전체 이름으로 쓴 경우 → 와일드카드 import + 알려진 프레임워크 타입(Lombok 애너테이션 포함)
     * → 프로젝트의 다른 파일이 단일 import한 유일한 FQN이 이 파일의 와일드카드 패키지에 속하는 경우.
     */
    String resolveWritten(String written, Map<String, String> singleImports, List<String> onDemandImports) {
        int dot = written.indexOf('.');
        String first = dot < 0 ? written : written.substring(0, dot);
        String rest = written.substring(first.length()).replace('.', '$');
        String imported = singleImports.get(first);
        if (imported != null) {
            return imported + rest;
        }
        if (dot > 0 && Character.isLowerCase(first.charAt(0))) {
            return written;
        }
        for (String pkg : onDemandImports) {
            if (KnownTypes.contains(pkg + "." + first)) {
                return pkg + "." + first + rest;
            }
        }
        // Lombok 애너테이션은 프레임워크 타입 다음에 본다(@Value는 Spring의 것과 이름이 같다)
        for (String pkg : onDemandImports) {
            if (Lombok.isAnnotation(pkg + "." + written)) {
                return pkg + "." + first + rest;
            }
        }
        Set<String> global = importedBySimpleName.get(first);
        if (global != null && global.size() == 1) {
            String candidate = global.iterator().next();
            if (onDemandImports.contains(packageOf(candidate))) {
                return candidate + rest;
            }
        }
        return null;
    }

    // ---- 이름 규칙 ----

    /**
     * JDK 타입인가(F-002-R13). 해석된 바인딩에서 출처를 본 적이 있는 이름이면 그 결과를,
     * 아니면 이름 규칙({@link JdkTypes#isJdkName(String)})을 쓴다.
     */
    boolean isJdk(String binaryName) {
        Boolean known = jdkByName.get(binaryName);
        return known != null ? known : JdkTypes.isJdkName(binaryName);
    }

    /** 해석된 바인딩의 출처(JDK 시스템 모듈인가)로 판정하고 이름별로 기억한다. 출처를 모르면 이름 규칙을 쓴다. */
    boolean isJdk(ITypeBinding type, String binaryName) {
        ITypeBinding erasure = type == null ? null : type.getErasure();
        Boolean origin = JdkTypes.byOrigin(erasure == null ? type : erasure);
        if (origin == null) {
            return isJdk(binaryName);
        }
        jdkByName.put(binaryName, origin);
        return origin;
    }

    static boolean isJavaLang(String binaryName) {
        return binaryName.startsWith("java.lang.") && binaryName.indexOf('.', "java.lang.".length()) < 0;
    }

    static String simpleName(String name) {
        int cut = Math.max(name.lastIndexOf('.'), name.lastIndexOf('$'));
        return name.substring(cut + 1);
    }

    static String packageOf(String binaryName) {
        int dot = binaryName.lastIndexOf('.');
        return dot < 0 ? "" : binaryName.substring(0, dot);
    }

    /** classpath에 없어 복구된 바인딩이 소스에 어떻게 쓰였는지(단순 이름 또는 점으로 이은 이름). */
    static String written(ITypeBinding recovered) {
        String name = recovered.getBinaryName();
        if (name == null || name.isEmpty()) {
            name = recovered.getQualifiedName();
        }
        if (name == null || name.isEmpty()) {
            name = recovered.getName();
        }
        int generic = name.indexOf('<');
        return generic < 0 ? name : name.substring(0, generic);
    }

    /** 바이너리 이름으로 타입 참조를 만든다. 최상위 타입의 소스 파일이 프로젝트에 있으면 프로젝트 타입이다. */
    TypeRef byName(String binaryName) {
        int nested = binaryName.indexOf('$');
        String top = nested < 0 ? binaryName : binaryName.substring(0, nested);
        return sourceTypes.contains(top) ? TypeRef.project(binaryName) : TypeRef.external(binaryName);
    }

    /** 문맥 없이 정하는 이름: 첫 조각이 프로젝트 전체에서 하나의 FQN으로만 import되면 그 FQN, 아니면 쓴 그대로. */
    String globalName(String written) {
        int dot = written.indexOf('.');
        String first = dot < 0 ? written : written.substring(0, dot);
        Set<String> candidates = importedBySimpleName.get(first);
        if (candidates != null && candidates.size() == 1) {
            return candidates.iterator().next() + written.substring(first.length()).replace('.', '$');
        }
        return written;
    }

    /**
     * 타입의 바이너리 이름(erasure 기준). 배열은 {@code []} 접미사, 원시 타입은 그대로.
     * classpath에 없는 타입은 {@link #globalName(String)}으로 정한다(선언 쪽과 호출 쪽이 같은 이름을 쓰게 하려고 문맥을 쓰지 않는다).
     */
    String binary(ITypeBinding binding) {
        ITypeBinding b = binding.getErasure();
        if (b == null) {
            b = binding;
        }
        if (b.isArray()) {
            return binary(b.getElementType()) + "[]".repeat(b.getDimensions());
        }
        if (b.isPrimitive() || b.isNullType()) {
            return b.getName();
        }
        if (b.isRecovered()) {
            return globalName(written(b));
        }
        String name = b.getBinaryName();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        ITypeBinding declaring = b.getDeclaringClass();
        if (declaring != null) {
            return binary(declaring) + "$" + b.getName();
        }
        return b.getQualifiedName();
    }

    /** 메서드 ID. 제네릭 인스턴스가 아니라 선언을 기준으로 하고 매개변수는 erasure의 바이너리 이름이다. */
    String methodId(IMethodBinding binding) {
        IMethodBinding decl = binding.getMethodDeclaration();
        String declaring = binary(decl.getDeclaringClass());
        List<String> params = new ArrayList<>();
        for (ITypeBinding p : decl.getParameterTypes()) {
            params.add(parameterName(p, declaring));
        }
        String name = decl.isConstructor() ? Ids.CONSTRUCTOR_NAME : decl.getName();
        return Ids.javaMethod(declaring, name, params);
    }

    /**
     * 메서드 ID에 쓰는 매개변수 타입의 이름. {@link #binary(ITypeBinding)}와 같되, 그 메서드를 선언한 타입의 파일이
     * 같은 단순 이름을 단일 import했는데 컴파일러가 다른 타입으로 풀어 버린 자리는 import의 FQN이다(F-002-R20).
     * 선언 쪽과 호출 쪽이 같은 이름을 얻도록 선언한 타입의 파일 문맥만 쓴다.
     *
     * @param declaringType 메서드를 선언한 타입의 바이너리 이름
     */
    String parameterName(ITypeBinding parameter, String declaringType) {
        SourcePrescan.FileImports imports = importsOf(declaringType);
        ITypeBinding erasure = parameter.getErasure() == null ? parameter : parameter.getErasure();
        if (imports != null && !imports.single().isEmpty()) {
            ITypeBinding element = erasure.isArray() ? erasure.getElementType() : erasure;
            int nested = declaringType.indexOf('$');
            String over = importedInstead(element, packageOf(nested < 0 ? declaringType : declaringType.substring(0, nested)),
                    imports.single(), imports.onDemand(), false);
            if (over != null) {
                return over + "[]".repeat(erasure.getDimensions());
            }
        }
        return binary(parameter);
    }

    /**
     * 컴파일러가 단일 타입 import를 풀지 못해 그 이름을 다른 타입으로 풀어 버린 바인딩이면 import의 FQN(중첩 타입은 {@code $}로 이은 이름)을 준다.
     * 아니면 null이다(F-002-R20).
     *
     * <p>import한 타입이 classpath에 없으면 컴파일러는 그 단순 이름을 이름 찾기의 다음 범위에서 찾는다: 같은 패키지의 타입,
     * 와일드카드 import한 패키지의 타입, {@code java.lang}의 타입({@code import org.activiti.bpmn.model.Process;} → {@code java.lang.Process}).
     * 바인딩이 그 범위의 타입이고 파일이 같은 단순 이름을 다른 FQN으로 단일 import했을 때만 바꾼다. 전체 이름으로 쓴 다른 타입
     * ({@code java.sql.Date})이나 다른 곳에서 온 값의 타입은 그 범위 밖이라 건드리지 않는다.
     *
     * @param binding 타입 바인딩(배열이 아닌 것. 제네릭은 erasure로 본다)
     * @param contextPackage 그 이름을 쓴 파일의 패키지
     * @param singleImports 그 파일의 단일 import(단순 이름 → FQN)
     * @param onDemandImports 그 파일의 와일드카드 import
     * @param knownUnresolved singleImports가 "컴파일러가 풀지 못한 import"만 담고 있으면 true. false면(다른 파일의 import라 풀렸는지 모른다)
     *                        프로젝트 소스의 타입과 JDK 타입을 가리키는 import는 풀린 것으로 보고 바꾸지 않는다
     */
    String importedInstead(ITypeBinding binding, String contextPackage, Map<String, String> singleImports, List<String> onDemandImports,
                           boolean knownUnresolved) {
        if (binding == null || singleImports.isEmpty()) {
            return null;
        }
        ITypeBinding erasure = binding.getErasure() == null ? binding : binding.getErasure();
        if (erasure.isPrimitive() || erasure.isArray() || erasure.isNullType() || erasure.isTypeVariable() || erasure.isRecovered()
                || erasure.isLocal() || erasure.isAnonymous()) {
            return null;
        }
        StringBuilder nested = new StringBuilder();
        ITypeBinding top = erasure;
        while (top.getDeclaringClass() != null) {
            nested.insert(0, "$" + top.getName());
            top = top.getDeclaringClass();
        }
        String imported = singleImports.get(top.getName());
        if (imported == null || imported.equals(top.getQualifiedName()) || top.getPackage() == null) {
            return null;
        }
        String foundIn = top.getPackage().getName();
        if (!foundIn.equals("java.lang") && !foundIn.equals(contextPackage) && !onDemandImports.contains(foundIn)) {
            return null;
        }
        if (!knownUnresolved && (sourceTypes.contains(imported) || sourceTypes.contains(packageOf(imported))
                || (JdkTypes.isJdkName(imported) && JdkTypes.systemClass(imported) != null))) {
            return null;
        }
        return imported + nested;
    }

    // ---- 참조 노드 ----

    /** 타입 참조의 노드 ID. 소스 없는 타입이면 external 참조 노드를 만들어 둔다. UNRESOLVED면 null. */
    String typeId(TypeRef ref) {
        if (ref == null || ref.isUnresolved()) {
            return null;
        }
        String id = Ids.javaType(ref.name());
        if (!ref.isProject() && referenced.add(id)) {
            graph.reference(id, NodeKind.JAVA_TYPE, simpleName(ref.name()));
            graph.putAttrs(id, Map.of("external", true));
        }
        return id;
    }

    /** 소스 없는 타입의 메서드 참조 노드를 만들어 둔다. */
    String externalMethod(String methodId, String name, boolean signatureUnknown) {
        if (referenced.add(methodId)) {
            graph.reference(methodId, NodeKind.JAVA_METHOD, name);
            graph.putAttrs(methodId, signatureUnknown
                    ? Map.of("external", true, "signatureUnknown", true)
                    : Map.of("external", true));
        }
        return methodId;
    }

    /** 패키지 노드를 한 번만 만든다. */
    String packageNode(String packageName) {
        String id = Ids.javaPackage(packageName);
        if (declaredPackages.add(packageName)) {
            graph.declare(Node.builder(id, NodeKind.JAVA_PACKAGE).name(packageName).qualifiedName(packageName).build());
        }
        return id;
    }

    /** 소스에 없는 합성 메서드({@code <clinit>}, {@code <instance-init>}, 기본 생성자) 노드를 만들고 ID를 돌려준다. */
    String syntheticMethod(String typeName, String methodName, SourceLocation typeLocation) {
        return syntheticMethod(typeName, methodName, List.of(), typeLocation);
    }

    /** 매개변수가 있는 합성 메서드(익명 클래스의 생성자 등). 이미 정의된 노드가 있으면 그대로 둔다. */
    String syntheticMethod(String typeName, String methodName, List<String> parameterTypes, SourceLocation typeLocation) {
        String id = Ids.javaMethod(typeName, methodName, parameterTypes);
        if (referenced.add("synthetic " + id) && graph.node(id).filter(Node::declared).isEmpty()) {
            String params = String.join(",", parameterTypes.stream().map(JavaProject::simpleName).toList());
            Node.Builder b = Node.builder(id, NodeKind.JAVA_METHOD).name(methodName)
                    .qualifiedName(typeName + "#" + methodName + "(" + params + ")").location(typeLocation)
                    .attr("synthetic", true).attr("signature", methodName + "(" + params + ")");
            b.attr("constructor", Ids.CONSTRUCTOR_NAME.equals(methodName));
            if (CLINIT.equals(methodName)) {
                b.attr("modifiers", List.of("static"));
            }
            graph.declare(b.build());
            graph.addEdge(Edge.of(Ids.javaType(typeName), id, EdgeKind.CONTAINS));
        }
        return id;
    }

    /** attrs가 필요한 미해결 엣지: {@code unresolved:<분류>:<원문>} 노드, 신뢰도 UNRESOLVED 엣지, WARN 진단을 남긴다. */
    void unresolvedEdge(String from, EdgeKind kind, String category, String text, SourceLocation location, Map<String, ?> attrs) {
        String id = Ids.unresolved(category, text);
        graph.declare(Node.builder(id, NodeKind.UNRESOLVED).name(text).qualifiedName(text).location(location)
                .attr("category", category).build());
        graph.addEdge(Edge.builder(from, id, kind).unresolved().location(location).attrs(attrs).build());
        graph.report(Diagnostic.warn(DiagnosticCodes.UNRESOLVED_REFERENCE,
                "해석하지 못한 참조(" + category + "): " + text, location));
    }

    SourceLocation typeLocation(String typeName) {
        return graph.node(Ids.javaType(typeName)).map(Node::location).orElse(null);
    }
}
