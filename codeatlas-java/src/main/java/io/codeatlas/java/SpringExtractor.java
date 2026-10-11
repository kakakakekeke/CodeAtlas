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
import io.codeatlas.core.spi.EmbeddedSource;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.ArrayType;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclarationStatement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.WildcardType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.codeatlas.java.KnownTypes.CONTEXT;
import static io.codeatlas.java.KnownTypes.FACTORY;
import static io.codeatlas.java.KnownTypes.IBATIS;
import static io.codeatlas.java.KnownTypes.STEREOTYPE;
import static io.codeatlas.java.KnownTypes.WEB;

/**
 * F-004 애너테이션 기반 Spring과 Java 코드 안의 MyBatis.
 * 스테레오타입·{@code @Bean}의 빈, 주입 지점, 요청 매핑(엔드포인트), 뷰 이름, Java 코드의 redirect·forward,
 * SqlSession 호출의 구문 실행, 애너테이션 SQL을 다룬다.
 * Spring·MyBatis 타입이 classpath에 없어도 import와 단순 이름으로 인식한다.
 */
final class SpringExtractor extends ASTVisitor {

    static final String CAT_STATEMENT = "mybatis-statement";
    static final String CAT_VIEW = "view";
    static final String CAT_ENDPOINT = "http-endpoint";

    private static final String BEAN = CONTEXT + "Bean";
    private static final String PRIMARY = CONTEXT + "Primary";
    private static final String SCOPE = CONTEXT + "Scope";
    private static final String AUTOWIRED = FACTORY + "Autowired";
    private static final String QUALIFIER = FACTORY + "Qualifier";
    private static final String VALUE = FACTORY + "Value";
    private static final String[] INJECT = {AUTOWIRED, "javax.inject.Inject", "jakarta.inject.Inject"};
    private static final String[] RESOURCE = {"javax.annotation.Resource", "jakarta.annotation.Resource"};
    private static final String[] NAMED = {"javax.inject.Named", "jakarta.inject.Named"};
    private static final String REQUEST_MAPPING = WEB + "RequestMapping";
    private static final String REST_CONTROLLER = WEB + "RestController";
    private static final String RESPONSE_BODY = WEB + "ResponseBody";
    private static final String EXCEPTION_HANDLER = WEB + "ExceptionHandler";
    private static final String MODEL_ATTRIBUTE = WEB + "ModelAttribute";
    /** 요청 경로 안의 프로퍼티 자리표시자 {@code ${이름}} 또는 {@code ${이름:기본값}}. */
    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\$\\{\\s*([^}:\\s]+)[^}]*}");
    private static final String MAPPER = IBATIS + "Mapper";

    /** 스테레오타입 애너테이션 → attrs.stereotype 값. */
    private static final Map<String, String> STEREOTYPES = new LinkedHashMap<>();
    /** 메서드 전용 매핑 애너테이션 → HTTP 메서드. */
    private static final Map<String, String> METHOD_MAPPINGS = new LinkedHashMap<>();
    /** 애너테이션 SQL → statementType. */
    private static final Map<String, String> SQL_ANNOTATIONS = new LinkedHashMap<>();

    static {
        STEREOTYPES.put(STEREOTYPE + "Controller", "controller");
        STEREOTYPES.put(REST_CONTROLLER, "controller");
        STEREOTYPES.put(STEREOTYPE + "Service", "service");
        STEREOTYPES.put(STEREOTYPE + "Repository", "repository");
        STEREOTYPES.put(CONTEXT + "Configuration", "configuration");
        STEREOTYPES.put("org.springframework.boot.autoconfigure.SpringBootApplication", "configuration");
        STEREOTYPES.put("org.springframework.boot.test.context.TestConfiguration", "configuration");
        STEREOTYPES.put(MAPPER, "mapper");
        STEREOTYPES.put(STEREOTYPE + "Component", "component");
        STEREOTYPES.put(WEB + "ControllerAdvice", "component");
        STEREOTYPES.put(WEB + "RestControllerAdvice", "component");
        STEREOTYPES.put("javax.inject.Named", "component");
        STEREOTYPES.put("jakarta.inject.Named", "component");
        METHOD_MAPPINGS.put(WEB + "GetMapping", "GET");
        METHOD_MAPPINGS.put(WEB + "PostMapping", "POST");
        METHOD_MAPPINGS.put(WEB + "PutMapping", "PUT");
        METHOD_MAPPINGS.put(WEB + "DeleteMapping", "DELETE");
        METHOD_MAPPINGS.put(WEB + "PatchMapping", "PATCH");
        SQL_ANNOTATIONS.put(IBATIS + "Select", "SELECT");
        SQL_ANNOTATIONS.put(IBATIS + "Insert", "INSERT");
        SQL_ANNOTATIONS.put(IBATIS + "Update", "UPDATE");
        SQL_ANNOTATIONS.put(IBATIS + "Delete", "DELETE");
    }

    private static final Set<String> SQL_SESSION_OPERATIONS = Set.of(
            "selectOne", "selectList", "selectMap", "selectCursor", "select", "insert", "update", "delete");
    /** 주입 대상을 감싸는 타입: 타입 인자가 실제 대상이다. */
    private static final Set<String> INJECTION_WRAPPERS = Set.of(
            "List", "Set", "Collection", "Optional", "ObjectProvider", "Provider", "ObjectFactory");

    /** 응답 객체로 보는 타입(상위 타입 포함)의 단순 이름. */
    private static final Set<String> RESPONSE_TYPES = Set.of("HttpServletResponse", "HttpServletResponseWrapper");
    private static final Set<String> DISPATCHER_TYPES = Set.of("RequestDispatcher");
    /** 필터·인터셉터 표시보다 앞서는 스테레오타입. 그 밖의 것({@code component}, {@code service}, {@code repository})은 빈 등록용으로 보고 필터·인터셉터로 적는다. */
    private static final Set<String> WEB_ROLE_YIELDS_TO = Set.of("controller", "configuration", "mapper");
    /** Spring Security의 리다이렉트 전략(상위 타입 포함)의 단순 이름. {@code sendRedirect(request, response, url)}의 셋째 인자가 경로다. */
    private static final Set<String> REDIRECT_STRATEGY_TYPES = Set.of("RedirectStrategy", "DefaultRedirectStrategy");

    private final Unit unit;
    private final JavaProject project;
    private final GraphModel graph;
    /**
     * 메서드 ID → 그 메서드의 코드가 redirect·forward하는 경로 패턴(나온 순서) → 그 호출 지점의 {@code 줄:열}(처음 나온 자리).
     * 파일을 다 본 뒤 메서드 attrs로 쓴다.
     */
    private final Map<String, Map<String, String>> redirects = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> forwards = new LinkedHashMap<>();
    private final Map<MethodDeclaration, Map<String, List<Expression>>> localValuesByMethod = new IdentityHashMap<>();
    /** 메서드 ID → 구문 ID처럼 보이는 문자열 상수 인자(나온 순서) → 그 인자의 {@code 줄:열}(처음 나온 자리). */
    private final Map<String, Map<String, String>> statementIdLiterals = new LinkedHashMap<>();
    /** 구문 ID의 꼴: 식별자 조각이 점으로 둘 이상 이어지고 공백이 없다({@code <namespace>.<id>}). */
    private static final java.util.regex.Pattern STATEMENT_ID_LIKE =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_$-]*(\\.[A-Za-z_][A-Za-z0-9_$-]*)+");

    SpringExtractor(Unit unit) {
        this.unit = unit;
        this.project = unit.project;
        this.graph = project.graph;
    }

    void run() {
        unit.cu.accept(this);
        // 경로 목록과 같은 순서의 호출 지점 목록을 함께 남긴다(F-004-R19). 링커가 엣지의 위치로 쓴다
        redirects.forEach((methodId, paths) -> graph.putAttrs(methodId,
                Map.of("redirects", List.copyOf(paths.keySet()), "redirectLines", List.copyOf(paths.values()))));
        forwards.forEach((methodId, paths) -> graph.putAttrs(methodId,
                Map.of("forwards", List.copyOf(paths.keySet()), "forwardLines", List.copyOf(paths.values()))));
        // 구문 ID처럼 보이는 문자열 상수 인자(F-004-R24). 그 ID의 매퍼 구문이 실제로 있는지는 링커가 본다
        statementIdLiterals.forEach((methodId, literals) -> graph.putAttrs(methodId,
                Map.of("statementIdLiterals", List.copyOf(literals.keySet()), "statementIdLiteralLines", List.copyOf(literals.values()))));
    }

    @Override
    public boolean visit(TypeDeclaration node) {
        type(node, node.isInterface());
        return true;
    }

    @Override
    public boolean visit(EnumDeclaration node) {
        type(node, false);
        return true;
    }

    @Override
    public boolean visit(RecordDeclaration node) {
        type(node, false);
        return true;
    }

    // ---- 타입 하나 ----

    private void type(AbstractTypeDeclaration node, boolean isInterface) {
        String typeName = unit.typeName(node);
        String typeId = Ids.javaType(typeName);
        List<Unit.Anno> annos = unit.annos(node.modifiers());
        String stereotype = stereotype(annos, node.resolveBinding());
        boolean isAbstract = Modifier.isAbstract(node.getModifiers());
        boolean rest = Unit.find(annos, REST_CONTROLLER, WEB + "RestControllerAdvice") != null;

        // 요청 처리의 진입점: 서블릿 필터와 핸들러 인터셉터(F-004-R18). 구체적인 Spring 스테레오타입이 있으면 그것을 둔다
        String webRole = isInterface ? null : webRole(node.resolveBinding(), typeName, 0, new HashSet<>());
        String typeStereotype = webRole != null && !WEB_ROLE_YIELDS_TO.contains(String.valueOf(stereotype)) ? webRole : stereotype;
        if (stereotype == null && typeStereotype != null) {
            graph.putAttrs(typeId, Map.of("stereotype", typeStereotype));
        }
        if (stereotype != null) {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("stereotype", typeStereotype);
            if (rest) {
                attrs.put("restController", true);
            }
            boolean isBean = isInterface ? "mapper".equals(stereotype) : !isAbstract;
            if (isBean) {
                String beanName = explicitBeanName(annos);
                if (beanName == null) {
                    beanName = decapitalize(typeName.substring(typeName.lastIndexOf('.') + 1).replace('$', '.'));
                }
                attrs.put("beanName", beanName);
                stereotypeBean(node, typeName, typeId, beanName, stereotype, annos);
            }
            graph.putAttrs(typeId, attrs);
        }

        List<MethodDeclaration> constructors = new ArrayList<>();
        List<MethodDeclaration> methods = new ArrayList<>();
        List<FieldDeclaration> fields = new ArrayList<>();
        for (Object member : node.bodyDeclarations()) {
            if (member instanceof MethodDeclaration m) {
                (m.isConstructor() ? constructors : methods).add(m);
            } else if (member instanceof FieldDeclaration f) {
                fields.add(f);
            }
        }
        injections(node, typeId, stereotype != null, fields, constructors, methods);

        Unit.Anno classMapping = Unit.find(annos, REQUEST_MAPPING);
        boolean classResponseBody = rest || Unit.find(annos, RESPONSE_BODY) != null;
        List<String> handlers = new ArrayList<>();
        List<SpringModel.ModelAttributeMethod> modelAttributes = new ArrayList<>();
        for (MethodDeclaration method : methods) {
            List<Unit.Anno> methodAnnos = unit.annos(method.modifiers());
            String methodId = unit.methodId(method);
            boolean handler = !isInterface && endpoints(method, methodId, methodAnnos, classMapping);
            if ((handler || Unit.find(methodAnnos, EXCEPTION_HANDLER) != null)
                    && !classResponseBody && Unit.find(methodAnnos, RESPONSE_BODY) == null) {
                viewNames(method, methodId, true);
            } else if (method.getReturnType2() != null && Unit.written(method.getReturnType2()).equals("String")
                    && method.getExtraDimensions() == 0 && !method.getReturnType2().isArrayType()) {
                // 핸들러가 반환식에서 부를 수 있는 메서드: 돌려주는 뷰 이름을 적어만 둔다(F-004-R14)
                viewNames(method, methodId, false);
            }
            if (handler) {
                handlers.add(methodId);
            } else if (!isInterface && Unit.find(methodAnnos, MODEL_ATTRIBUTE) != null) {
                // 매핑이 함께 붙은 메서드는 핸들러다(반환값을 모델에 넣을 뿐이다)
                modelAttributes.add(new SpringModel.ModelAttributeMethod(methodId, methodId.substring(methodId.indexOf('#') + 1),
                        unit.declLoc(method, method.getName())));
            }
            Unit.Anno bean = Unit.find(methodAnnos, BEAN);
            if (bean != null && method.getBody() != null) {
                beanMethod(method, methodId, typeName, bean, methodAnnos);
            }
            annotationSql(method, typeName, methodAnnos);
        }
        if (!handlers.isEmpty()) {
            project.spring.handlers.put(typeName, handlers);
        }
        if (!modelAttributes.isEmpty()) {
            project.spring.modelAttributes.put(typeName, modelAttributes);
        }
    }

    /** 스테레오타입 값. 프로젝트가 만든 합성 애너테이션은 메타 애너테이션을 세 단계까지 따라간다. */
    private String stereotype(List<Unit.Anno> annos, ITypeBinding typeBinding) {
        for (Map.Entry<String, String> e : STEREOTYPES.entrySet()) {
            if (Unit.find(annos, e.getKey()) != null) {
                return e.getValue();
            }
        }
        for (Unit.Anno anno : annos) {
            ITypeBinding annotationType = anno.node().resolveTypeBinding();
            String found = metaStereotype(annotationType, 0, new HashSet<>());
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private String metaStereotype(ITypeBinding annotationType, int depth, Set<String> seen) {
        if (annotationType == null || annotationType.isRecovered() || !annotationType.isFromSource() || depth >= 3
                || !seen.add(annotationType.getQualifiedName())) {
            return null;
        }
        for (IAnnotationBinding meta : annotationType.getAnnotations()) {
            ITypeBinding metaType = meta.getAnnotationType();
            if (metaType == null) {
                continue;
            }
            String fqn = metaType.isRecovered() ? unit.project.globalName(JavaProject.written(metaType)) : project.binary(metaType);
            for (Map.Entry<String, String> e : STEREOTYPES.entrySet()) {
                boolean match = fqn.contains(".") ? fqn.equals(e.getKey()) : fqn.equals(JavaProject.simpleName(e.getKey()));
                if (match) {
                    return e.getValue();
                }
            }
            String deeper = metaStereotype(metaType, depth + 1, seen);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    /**
     * 타입의 계층에 서블릿 필터({@code javax.servlet.Filter} 구현, {@code OncePerRequestFilter} 상속 등)나
     * 핸들러 인터셉터가 있으면 {@code filter} / {@code interceptor}. classpath에 없는 상위 타입은 그 이름을 적은 파일의 import로 FQN을 복원한다.
     *
     * @param declaredIn type을 상위 타입으로 적은 프로젝트 타입의 바이너리 이름
     */
    private String webRole(ITypeBinding type, String declaredIn, int depth, Set<String> seen) {
        if (type == null || depth > 30) {
            return null;
        }
        ITypeBinding erasure = type.getErasure() == null ? type : type.getErasure();
        if (erasure.isRecovered()) {
            String written = JavaProject.written(erasure);
            String fqn = unit.resolveWrittenIn(written, declaredIn);
            if (fqn != null) {
                return KnownTypes.webRole(fqn);
            }
            String global = project.globalName(written);
            return global.equals(written) ? null : KnownTypes.webRole(global);
        }
        String name = project.binary(erasure);
        if (!seen.add(name)) {
            return null;
        }
        if (!erasure.isFromSource()) {
            String role = KnownTypes.webRole(name);
            if (role != null) {
                return role;
            }
        }
        String next = erasure.isFromSource() ? name : declaredIn;
        String role = webRole(erasure.getSuperclass(), next, depth + 1, seen);
        for (ITypeBinding i : erasure.getInterfaces()) {
            if (role == null) {
                role = webRole(i, next, depth + 1, seen);
            }
        }
        return role;
    }

    private String explicitBeanName(List<Unit.Anno> annos) {
        for (Unit.Anno anno : annos) {
            boolean named = false;
            for (String fqn : STEREOTYPES.keySet()) {
                named |= anno.is(fqn) && !fqn.equals(MAPPER);
            }
            if (named) {
                String value = Constants.string(anno.value("value"));
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    /** {@code java.beans.Introspector.decapitalize}와 같은 규칙: 앞 두 글자가 대문자면 그대로 둔다. */
    static String decapitalize(String name) {
        if (name.isEmpty() || (name.length() > 1 && Character.isUpperCase(name.charAt(0)) && Character.isUpperCase(name.charAt(1)))) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private void stereotypeBean(AbstractTypeDeclaration node, String typeName, String typeId, String beanName, String stereotype,
                                List<Unit.Anno> annos) {
        boolean primary = Unit.find(annos, PRIMARY) != null;
        SourceLocation location = unit.declLoc(node, node.getName());
        Node.Builder b = Node.builder(Ids.springBean(beanName), NodeKind.SPRING_BEAN).name(beanName).qualifiedName(beanName)
                .location(location).attr("class", typeName).attr("beanType", typeName)
                .attr("source", "stereotype").attr("stereotype", stereotype);
        if (primary) {
            b.attr("primary", true);
        }
        String scope = scope(annos);
        if (scope != null) {
            b.attr("scope", scope);
        }
        // 빈 노드는 모든 파일을 본 뒤에 만든다: 이름이 같은 정의가 여럿이면 하나의 것으로 일관되게 정해야 한다(F-004-R22)
        project.spring.stereotypeBeans.add(b.build());
        graph.addEdge(Edge.of(typeId, Ids.springBean(beanName), EdgeKind.DEFINES_BEAN, location));
        project.spring.beans.add(new SpringModel.Bean(beanName, typeName, supertypes(node.resolveBinding(), typeName), primary));
    }

    private String scope(List<Unit.Anno> annos) {
        Unit.Anno scope = Unit.find(annos, SCOPE);
        if (scope == null) {
            return null;
        }
        String value = Constants.string(scope.value("value"));
        return value != null ? value : Constants.string(scope.value("scopeName"));
    }

    /** 타입 자신과 해석된 상위 타입 전부의 바이너리 이름. */
    private Set<String> supertypes(ITypeBinding type, String selfName) {
        Set<String> names = new LinkedHashSet<>();
        names.add(selfName);
        collectSupertypes(type, names, 0);
        return names;
    }

    private void collectSupertypes(ITypeBinding type, Set<String> names, int depth) {
        if (type == null || type.isRecovered() || depth > 30) {
            return;
        }
        names.add(project.binary(type));
        collectSupertypes(type.getSuperclass(), names, depth + 1);
        for (ITypeBinding i : type.getInterfaces()) {
            collectSupertypes(i, names, depth + 1);
        }
    }

    // ---- 주입 ----

    private void injections(AbstractTypeDeclaration typeNode, String typeId, boolean isStereotype, List<FieldDeclaration> fields,
                            List<MethodDeclaration> constructors, List<MethodDeclaration> methods) {
        for (FieldDeclaration field : fields) {
            List<Unit.Anno> annos = unit.annos(field.modifiers());
            Unit.Anno inject = Unit.find(annos, INJECT);
            Unit.Anno resource = Unit.find(annos, RESOURCE);
            if (inject == null && resource == null) {
                continue;
            }
            for (Object f : field.fragments()) {
                VariableDeclarationFragment fragment = (VariableDeclarationFragment) f;
                inject(typeId, field.getType(), annos, "field", fragment.getName().getIdentifier(), unit.loc(fragment), inject);
            }
        }

        boolean explicitConstructor = false;
        for (MethodDeclaration constructor : constructors) {
            Unit.Anno inject = Unit.find(unit.annos(constructor.modifiers()), INJECT);
            if (inject != null) {
                explicitConstructor = true;
                constructorInjection(typeId, constructor, inject);
            }
        }
        if (!explicitConstructor && isStereotype && constructors.size() == 1 && !constructors.get(0).parameters().isEmpty()) {
            constructorInjection(typeId, constructors.get(0), null);
        }

        for (MethodDeclaration method : methods) {
            List<Unit.Anno> annos = unit.annos(method.modifiers());
            Unit.Anno inject = Unit.find(annos, INJECT);
            Unit.Anno resource = Unit.find(annos, RESOURCE);
            if ((inject == null && resource == null) || Unit.find(annos, BEAN) != null) {
                continue;
            }
            Map<String, String> assigned = assignedFields(method);
            for (Object p : method.parameters()) {
                SingleVariableDeclaration param = (SingleVariableDeclaration) p;
                String name = param.getName().getIdentifier();
                String field = assigned.get(name);
                if (field == null) {
                    String methodName = method.getName().getIdentifier();
                    field = methodName.startsWith("set") && methodName.length() > 3 && method.parameters().size() == 1
                            ? decapitalize(methodName.substring(3)) : name;
                }
                List<Unit.Anno> pointAnnos = new ArrayList<>(unit.annos(param.modifiers()));
                pointAnnos.addAll(annos);
                inject(typeId, param.getType(), pointAnnos, "setter", field, unit.loc(param), inject);
            }
        }

        // Lombok이 만드는 생성자: 소스에 생성자가 없는 스테레오타입 클래스에 합성 생성자가 정확히 하나일 때 그 매개변수를 주입으로 본다
        Unit.LombokType lombok = unit.lombokTypes.get(typeNode);
        if (isStereotype && constructors.isEmpty() && lombok != null && lombok.constructors.size() == 1) {
            for (VariableDeclarationFragment fragment : lombok.constructors.get(0).fields()) {
                if (fragment != null && fragment.getParent() instanceof FieldDeclaration field) {
                    inject(typeId, field.getType(), unit.annos(field.modifiers()), "constructor", fragment.getName().getIdentifier(),
                            unit.loc(fragment), null);
                }
            }
        }
    }

    private void constructorInjection(String typeId, MethodDeclaration constructor, Unit.Anno inject) {
        Map<String, String> assigned = assignedFields(constructor);
        for (Object p : constructor.parameters()) {
            SingleVariableDeclaration param = (SingleVariableDeclaration) p;
            String name = param.getName().getIdentifier();
            inject(typeId, param.getType(), unit.annos(param.modifiers()), "constructor", assigned.getOrDefault(name, name),
                    unit.loc(param), inject);
        }
    }

    /** 메서드 본문에서 {@code this.field = param} 형태로 대입한 것: 매개변수 이름 → 필드 이름. */
    private static Map<String, String> assignedFields(MethodDeclaration method) {
        Map<String, String> map = new HashMap<>();
        if (method.getBody() == null) {
            return map;
        }
        method.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(Assignment node) {
                if (node.getRightHandSide() instanceof SimpleName source) {
                    Expression left = node.getLeftHandSide();
                    String field = null;
                    if (left instanceof FieldAccess access && access.getExpression() instanceof ThisExpression) {
                        field = access.getName().getIdentifier();
                    } else if (left instanceof SimpleName name && name.resolveBinding() instanceof IVariableBinding v && v.isField()) {
                        field = name.getIdentifier();
                    }
                    if (field != null) {
                        map.putIfAbsent(source.getIdentifier(), field);
                    }
                }
                return true;
            }
        });
        return map;
    }

    private void inject(String fromId, Type declared, List<Unit.Anno> pointAnnos, String via, String field, SourceLocation location,
                        Unit.Anno injectAnno) {
        if (Unit.find(pointAnnos, VALUE) != null) {
            return;
        }
        Type targetType = unwrapInjection(declared);
        TypeRef target = unit.ref(targetType);
        if (target == null || (!target.isUnresolved() && JavaProject.isJavaLang(target.name()))) {
            return;
        }
        SpringModel.Injection injection = new SpringModel.Injection();
        injection.fromId = fromId;
        injection.target = target;
        ITypeBinding binding = Unit.reduce(targetType.resolveBinding());
        injection.targetAbstract = binding != null && !binding.isRecovered() && binding.isFromSource()
                && (binding.isInterface() || Modifier.isAbstract(binding.getModifiers()));
        injection.via = via;
        injection.field = field;
        injection.location = location;
        Unit.Anno qualifier = Unit.find(pointAnnos, QUALIFIER);
        if (qualifier == null) {
            qualifier = Unit.find(pointAnnos, NAMED);
        }
        if (qualifier != null) {
            injection.qualifier = Constants.string(qualifier.value("value"));
        }
        Unit.Anno resource = Unit.find(pointAnnos, RESOURCE);
        if (injection.qualifier == null && resource != null) {
            injection.qualifier = Constants.string(resource.value("name"));
        }
        if (injectAnno != null && Boolean.FALSE.equals(Constants.eval(injectAnno.value("required")))) {
            injection.required = false;
        }
        project.spring.injections.add(injection);
    }

    private static Type unwrapInjection(Type type) {
        if (type instanceof ArrayType array) {
            return array.getElementType();
        }
        if (type instanceof ParameterizedType parameterized) {
            String base = JavaProject.simpleName(Unit.written(parameterized.getType()));
            List<?> args = parameterized.typeArguments();
            Type arg = null;
            if (INJECTION_WRAPPERS.contains(base) && args.size() == 1) {
                arg = (Type) args.get(0);
            } else if (base.equals("Map") && args.size() == 2) {
                arg = (Type) args.get(1);
            }
            if (arg instanceof WildcardType wildcard) {
                arg = wildcard.getBound();
            }
            if (arg != null) {
                return unwrapInjection(arg);
            }
        }
        return type;
    }

    // ---- 엔드포인트 ----

    /** 식 하나가 상수면 value, 아니면 원문 text만 있다. */
    private record PathValue(String value, String text) {
    }

    private List<PathValue> paths(Unit.Anno mapping) {
        List<PathValue> paths = new ArrayList<>();
        if (mapping != null) {
            for (Expression e : mapping.values("value", "path")) {
                paths.add(new PathValue(Constants.string(e), e.toString()));
            }
        }
        if (paths.isEmpty()) {
            paths.add(new PathValue("", ""));
        }
        return paths;
    }

    private static List<String> httpMethods(Unit.Anno requestMapping) {
        List<String> methods = new ArrayList<>();
        if (requestMapping != null) {
            for (Expression e : requestMapping.values("method")) {
                if (e instanceof QualifiedName q) {
                    methods.add(q.getName().getIdentifier().toUpperCase(Locale.ROOT));
                } else if (e instanceof SimpleName s) {
                    methods.add(s.getIdentifier().toUpperCase(Locale.ROOT));
                } else if (e instanceof FieldAccess f) {
                    methods.add(f.getName().getIdentifier().toUpperCase(Locale.ROOT));
                }
            }
        }
        return methods;
    }

    /** 매핑 애너테이션마다 엔드포인트를 만든다. 매핑이 하나라도 있으면 true. */
    private boolean endpoints(MethodDeclaration method, String methodId, List<Unit.Anno> annos, Unit.Anno classMapping) {
        boolean any = false;
        List<String> mappings = new ArrayList<>();
        for (Unit.Anno anno : annos) {
            List<String> httpMethods;
            if (anno.is(REQUEST_MAPPING)) {
                httpMethods = httpMethods(anno);
                if (httpMethods.isEmpty()) {
                    httpMethods = httpMethods(classMapping);
                }
                if (httpMethods.isEmpty()) {
                    httpMethods = List.of(Ids.ANY_METHOD);
                }
            } else {
                String mapped = null;
                for (Map.Entry<String, String> e : METHOD_MAPPINGS.entrySet()) {
                    if (anno.is(e.getKey())) {
                        mapped = e.getValue();
                    }
                }
                if (mapped == null) {
                    continue;
                }
                httpMethods = List.of(mapped);
            }
            any = true;
            SourceLocation location = unit.loc(anno.node());
            for (PathValue classPath : paths(classMapping)) {
                for (PathValue methodPath : paths(anno)) {
                    if (classPath.value() == null || methodPath.value() == null) {
                        String text = classPath.value() == null ? classPath.text() : methodPath.text();
                        String unresolved = Ids.unresolved(CAT_ENDPOINT, text);
                        graph.declare(Node.builder(unresolved, NodeKind.UNRESOLVED).name(text).qualifiedName(text).location(location)
                                .attr("category", CAT_ENDPOINT).build());
                        graph.addEdge(Edge.builder(unresolved, methodId, EdgeKind.ROUTES_TO).unresolved().location(location).build());
                        graph.report(Diagnostic.warn(DiagnosticCodes.UNRESOLVED_REFERENCE,
                                "해석하지 못한 참조(" + CAT_ENDPOINT + "): 요청 경로가 상수가 아님: " + text, location));
                        continue;
                    }
                    String path = Ids.normalizePath(methodPath.value().isEmpty() ? classPath.value()
                            : classPath.value() + "/" + methodPath.value());
                    for (String httpMethod : httpMethods) {
                        String id = Ids.httpEndpoint(httpMethod, path);
                        String label = Ids.httpMethod(httpMethod) + " " + path;
                        Node.Builder endpoint = Node.builder(id, NodeKind.HTTP_ENDPOINT).name(label).qualifiedName(label).location(location)
                                .attr("httpMethod", Ids.httpMethod(httpMethod)).attr("path", path);
                        List<String> placeholders = placeholders(path);
                        if (!placeholders.isEmpty()) {
                            // 값으로 바꾸고 요청과 맞추는 일은 링커가 한다(F-004-R16)
                            endpoint.attr("placeholders", placeholders);
                        }
                        graph.declare(endpoint.build());
                        graph.addEdge(Edge.of(id, methodId, EdgeKind.ROUTES_TO, location));
                        mappings.add(label);
                    }
                }
            }
        }
        if (!mappings.isEmpty()) {
            graph.putAttrs(methodId, Map.of("httpMappings", mappings));
        }
        return any;
    }

    /** 경로에 든 프로퍼티 자리표시자의 이름(나온 순서, 중복 없이). {@code ${name:기본값}}은 이름만. 경로 변수 {@code {id}}는 아니다. */
    static List<String> placeholders(String path) {
        Set<String> names = new LinkedHashSet<>();
        java.util.regex.Matcher m = PLACEHOLDER.matcher(path);
        while (m.find()) {
            names.add(m.group(1));
        }
        return List.copyOf(names);
    }

    // ---- 뷰 이름 ----

    /** 메서드 자신의 코드만 본다(람다, 익명 클래스, 로컬 클래스 안은 건너뛴다). */
    private abstract static class OwnCodeVisitor extends ASTVisitor {
        @Override
        public boolean visit(LambdaExpression node) {
            return false;
        }

        @Override
        public boolean visit(AnonymousClassDeclaration node) {
            return false;
        }

        @Override
        public boolean visit(TypeDeclarationStatement node) {
            return false;
        }
    }

    /**
     * 메서드가 돌려주는 뷰 이름을 모은다.
     *
     * @param handler 뷰 이름을 남기는 핸들러면 true: {@code viewNames}와 미해결 엣지를 쓴다.
     *                false면 핸들러가 반환식에서 부를 수 있는 헬퍼다: {@code return} 식에서 읽은 것을 적어만 둔다
     */
    private void viewNames(MethodDeclaration method, String methodId, boolean handler) {
        if (method.getBody() == null) {
            return;
        }
        String returnType = method.getReturnType2() == null ? "" : JavaProject.simpleName(Unit.written(method.getReturnType2()));
        boolean returnsViewName = returnType.equals("String") || returnType.equals("Object");
        Map<String, List<Expression>> localValues = localValues(method);
        SpringModel.Views views = new SpringModel.Views(methodId, handler);
        Set<String> names = views.names;
        Map<String, SourceLocation> unknown = new LinkedHashMap<>();
        // 도우미의 매개변수: 변수 키 → 위치. 반환식에 매개변수가 섞였으면 호출 지점의 인자를 대입할 틀로 적는다(F-004-R25)
        Map<String, Integer> parameters = new HashMap<>();
        if (!handler) {
            for (int i = 0; i < method.parameters().size(); i++) {
                IVariableBinding parameter = ((SingleVariableDeclaration) method.parameters().get(i)).resolveBinding();
                if (parameter != null) {
                    parameters.put(parameter.getKey(), i);
                }
            }
        }
        method.getBody().accept(new OwnCodeVisitor() {
            @Override
            public boolean visit(ReturnStatement node) {
                if (returnsViewName && node.getExpression() != null) {
                    String template = parameters.isEmpty() ? null : parameterTemplate(node.getExpression(), parameters, localValues, 0);
                    if (template != null && template.indexOf(SpringModel.PARAMETER_MARK) >= 0) {
                        if (!views.parameterTemplates.contains(template)) {
                            views.parameterTemplates.add(template);
                        }
                    } else {
                        viewExpression(node.getExpression(), localValues, views, unknown, 0, unit.lineColumn(node));
                    }
                }
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation node) {
                if (handler && JavaProject.simpleName(Unit.written(node.getType())).equals("ModelAndView") && !node.arguments().isEmpty()) {
                    Expression first = (Expression) node.arguments().get(0);
                    ITypeBinding type = first.resolveTypeBinding();
                    if (Constants.string(first) != null || (type != null && "java.lang.String".equals(type.getQualifiedName()))) {
                        viewExpression(first, localValues, views, unknown, 0, unit.lineColumn(node));
                    }
                }
                return true;
            }

            @Override
            public boolean visit(MethodInvocation node) {
                if (handler && node.getName().getIdentifier().equals("setViewName") && node.arguments().size() == 1) {
                    viewExpression((Expression) node.arguments().get(0), localValues, views, unknown, 0, unit.lineColumn(node.getName()));
                }
                return true;
            }
        });
        views.dynamic = !unknown.isEmpty();
        project.spring.views.put(methodId, views);
        if (!handler) {
            return;
        }
        // 다른 메서드의 뷰 이름을 합쳐야 하면 모든 파일을 본 뒤 링커가 쓴다
        if (!names.isEmpty() && views.delegations.isEmpty()) {
            graph.putAttrs(methodId, Map.of("viewNames", List.copyOf(names), "viewNameLines", views.siteList()));
            if (!views.candidates.isEmpty()) {
                graph.putAttrs(methodId, Map.of("viewNameCandidates", List.copyOf(views.candidates)));
            }
        }
        unknown.forEach((text, location) -> graph.addUnresolvedEdge(methodId, EdgeKind.RENDERS, CAT_VIEW, text, location));
    }

    /**
     * 도우미의 반환식을 틀로 읽는다: {@code +}와 {@code .concat(..)}으로 이은 조각마다 상수는 값으로, 매개변수는
     * {@link SpringModel#PARAMETER_MARK}로 감싼 위치로, 한 번만 대입된 지역 변수는 그 값으로, 나머지는 {@code {*}}로 바꾼다.
     */
    private static String parameterTemplate(Expression expression, Map<String, Integer> parameters, Map<String, List<Expression>> localValues,
                                            int depth) {
        List<Expression> operands = new ArrayList<>();
        flattenConcat(expression, operands);
        StringBuilder sb = new StringBuilder();
        for (Expression operand : operands) {
            Object constant = Constants.eval(operand);
            String part = null;
            if (constant != null) {
                part = String.valueOf(constant);
            } else if (operand instanceof SimpleName name && name.resolveBinding() instanceof IVariableBinding v && !v.isField()) {
                Integer index = parameters.get(v.getKey());
                List<Expression> values = localValues.getOrDefault(v.getKey(), List.of());
                if (index != null && values.isEmpty()) {
                    part = "" + SpringModel.PARAMETER_MARK + index + SpringModel.PARAMETER_MARK;
                } else if (index == null && values.size() == 1 && depth < 3) {
                    part = parameterTemplate(values.get(0), parameters, localValues, depth + 1);
                }
            } else {
                // 프로퍼티를 읽는 꼴의 호출은 그 자리에 ${키}를 남긴다(F-004-R26)
                part = propertyRead(operand);
            }
            if (part == null) {
                part = Constants.DYNAMIC;
            }
            if (!(part.equals(Constants.DYNAMIC) && sb.toString().endsWith(Constants.DYNAMIC))) {
                sb.append(part);
            }
        }
        return sb.toString();
    }

    /** 문자열 연결식({@code +}, {@code a.concat(b)})을 피연산자 목록으로 편다. 상수로 계산되는 부분식은 통째로 둔다. */
    private static void flattenConcat(Expression expression, List<Expression> out) {
        Expression e = unparen(expression);
        if (Constants.eval(e) != null) {
            out.add(e);
        } else if (e instanceof InfixExpression infix && infix.getOperator() == InfixExpression.Operator.PLUS) {
            flattenConcat(infix.getLeftOperand(), out);
            flattenConcat(infix.getRightOperand(), out);
            for (Object extended : infix.extendedOperands()) {
                flattenConcat((Expression) extended, out);
            }
        } else if (e instanceof MethodInvocation call && call.getName().getIdentifier().equals("concat") && call.arguments().size() == 1
                && call.getExpression() != null) {
            flattenConcat(call.getExpression(), out);
            flattenConcat((Expression) call.arguments().get(0), out);
        } else {
            out.add(e);
        }
    }

    /** 지역 변수에 들어가는 값: 변수 키 → 초기화식과 대입식들. */
    private static Map<String, List<Expression>> localValues(MethodDeclaration method) {
        Map<String, List<Expression>> values = new HashMap<>();
        method.getBody().accept(new OwnCodeVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment node) {
                IVariableBinding v = node.resolveBinding();
                if (v != null && node.getInitializer() != null) {
                    values.computeIfAbsent(v.getKey(), k -> new ArrayList<>()).add(node.getInitializer());
                }
                return true;
            }

            @Override
            public boolean visit(Assignment node) {
                if (node.getLeftHandSide() instanceof SimpleName name && name.resolveBinding() instanceof IVariableBinding v
                        && !v.isField() && node.getOperator() == Assignment.Operator.ASSIGN) {
                    values.computeIfAbsent(v.getKey(), k -> new ArrayList<>()).add(node.getRightHandSide());
                }
                return true;
            }
        });
        return values;
    }

    /** @param site 이 식을 뷰 이름으로 내는 자리(반환문, {@code ModelAndView} 생성식, {@code setViewName} 호출)의 {@code 줄:열} */
    private void viewExpression(Expression expression, Map<String, List<Expression>> localValues, SpringModel.Views views,
                                Map<String, SourceLocation> unknown, int depth, String site) {
        Expression e = expression;
        while (e instanceof ParenthesizedExpression p) {
            e = p.getExpression();
        }
        if (e instanceof ConditionalExpression conditional) {
            viewExpression(conditional.getThenExpression(), localValues, views, unknown, depth, site);
            viewExpression(conditional.getElseExpression(), localValues, views, unknown, depth, site);
            return;
        }
        // @Value("${키}") 필드를 이어 붙인 이름은 그 자리에 ${키}를 남긴다: 링커가 프로퍼티 값으로 푼다(F-004-R23)
        String withPlaceholders = Constants.string(e) != null ? null : placeholderTemplate(e);
        if (withPlaceholders != null) {
            views.add(withPlaceholders, site);
            return;
        }
        // '리터럴 + 지역 변수'는 그 변수에 대입된 리터럴마다 뷰 이름을 낸다(F-004-R21)
        List<String> expanded = Constants.string(e) != null ? null : expandLocals(e, localValues);
        if (expanded != null) {
            expanded.forEach(name -> views.add(name, site));
            if (expanded.size() > 1) {
                views.candidates.addAll(expanded);
            }
            return;
        }
        // 상수 부분이 있으면 나머지는 {*}로 둔 이름을 남긴다
        String template = Constants.template(e);
        if (template != null) {
            views.add(template, site);
            return;
        }
        if (e instanceof SimpleName name && depth < 3) {
            IBinding binding = name.resolveBinding();
            if (binding instanceof IVariableBinding v && !v.isField() && !v.isParameter()) {
                List<Expression> values = localValues.get(v.getKey());
                if (values != null && !values.isEmpty()) {
                    for (Expression value : values) {
                        viewExpression(value, localValues, views, unknown, depth + 1, site);
                    }
                    return;
                }
            }
        }
        if (e.getNodeType() == ASTNode.NULL_LITERAL) {
            return;
        }
        String resolved = calledProjectMethod(e);
        // 자기 자신을 부르는 식은 따라갈 것이 없다. 다만 컴파일러가 미해석 매개변수 타입 때문에 자기 자신을 고른 것이면
        // (thymeleaf(Model)의 return thymeleaf("/index")) 인자 타입이 맞는 같은 이름의 다른 선언을 찾는다(F-004-R25)
        String other = resolved != null && (resolved.equals(views.methodId) || choseUnresolvedParameter(e)) ? otherOverload(e) : null;
        String called = other != null ? other : resolved;
        if (called != null && !called.equals(views.methodId)) {
            // 그 호출은 Java CALLS로 이미 남는다. 호출된 메서드가 돌려주는 뷰 이름은 모든 파일을 본 뒤에 합친다(F-004-R14)
            String text = CallGraphExtractor.clip(e.toString());
            // 같은 식에 여러 return이 닿아도(지역 변수 경유) 한 번만 적는다
            if (views.delegations.stream().noneMatch(d -> d.targetId().equals(called) && d.text().equals(text))) {
                // 합쳐 올 뷰 이름의 자리는 이 메서드 안의 그 호출 지점(메서드 이름의 자리: 그 호출의 CALLS와 같다)이다
                ASTNode callName = e instanceof MethodInvocation call ? call.getName() : e instanceof SuperMethodInvocation call ? call.getName() : e;
                List<?> arguments = e instanceof MethodInvocation call ? call.arguments() : ((SuperMethodInvocation) e).arguments();
                List<String> values = new ArrayList<>();
                for (Object argument : arguments) {
                    String value = Constants.string((Expression) argument);
                    values.add(value != null ? value : Constants.template((Expression) argument));
                }
                views.delegations.add(new SpringModel.ViewDelegation(called, text, unit.loc(e), unit.lineColumn(callName), values));
            }
            return;
        }
        unknown.putIfAbsent(CallGraphExtractor.clip(e.toString()), unit.loc(e));
    }

    /**
     * 식이 {@code @Value("${키}")}가 붙은 필드(상위 클래스의 것 포함)를 가리키면 그 자리표시자 원문({@code ${키}}). 아니면 null.
     */
    private String valuePlaceholder(Expression operand) {
        Expression e = unparen(operand);
        IBinding binding = e instanceof Name name ? name.resolveBinding()
                : e instanceof FieldAccess access ? access.resolveFieldBinding()
                : e instanceof SuperFieldAccess access ? access.resolveFieldBinding() : null;
        if (!(binding instanceof IVariableBinding field) || !field.isField() || project.valueFields.isEmpty()) {
            return null;
        }
        ITypeBinding declaring = field.getVariableDeclaration().getDeclaringClass();
        if (declaring == null || declaring.isRecovered()) {
            return null;
        }
        String type = project.binary(declaring);
        int nested = type.indexOf('$');
        return project.valueFields.get((nested < 0 ? type : type.substring(0, nested)) + "#" + field.getName());
    }

    /** 프로퍼티를 읽는 메서드로 보는 이름(F-004-R26). */
    private static final Set<String> PROPERTY_READERS = Set.of("get", "getProperty", "getString", "getValue", "getConfig");
    /** 프로퍼티 키로 보는 문자열: 식별자 조각이 {@code .}·{@code -}로 이어진 것(공백·경로 구분자 없음). */
    private static final java.util.regex.Pattern PROPERTY_KEY = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:[.-][A-Za-z0-9_]+)*");

    /**
     * 식이 "프로퍼티를 읽는 꼴의 호출"이면 그 자리표시자({@code ${키}}), 아니면 null(F-004-R26).
     * 수신자를 적은 호출이고, 이름이 {@link #PROPERTY_READERS} 가운데 하나이고, 인자가 키 꼴의 문자열 상수 하나일 때다
     * ({@code PropertiesFileUtil.getInstance().get("app.name")}, {@code env.getProperty("theme")}).
     * 선언을 찾았으면 문자열을 돌려주는 메서드여야 하고, 컬렉션({@code java.util}의 {@code Properties}·{@code ResourceBundle} 말고)과
     * 서블릿 API의 메서드는 아니다. 그 키가 정말 프로퍼티인지는 링커가 프로젝트 properties에서 값을 찾아 확인한다(없으면 {@code {*}}로 읽는다).
     */
    static String propertyRead(Expression operand) {
        Expression e = unparen(operand);
        if (!(e instanceof MethodInvocation call) || call.getExpression() == null || call.arguments().size() != 1
                || !PROPERTY_READERS.contains(call.getName().getIdentifier())) {
            return null;
        }
        String key = Constants.string((Expression) call.arguments().get(0));
        if (key == null || !PROPERTY_KEY.matcher(key).matches()) {
            return null;
        }
        IMethodBinding binding = call.resolveMethodBinding();
        if (binding != null) {
            ITypeBinding returned = binding.getReturnType();
            if (returned != null && !returned.isRecovered() && !"java.lang.String".equals(returned.getQualifiedName())) {
                return null;
            }
            ITypeBinding declaring = binding.getMethodDeclaration().getDeclaringClass();
            String owner = declaring == null ? "" : declaring.getErasure().getQualifiedName();
            if ((owner.startsWith("java.util.") && !owner.equals("java.util.Properties") && !owner.equals("java.util.ResourceBundle"))
                    || owner.startsWith("javax.servlet.") || owner.startsWith("jakarta.servlet.")) {
                return null;
            }
        }
        return "${" + key + "}";
    }

    /**
     * 문자열 연결식에 {@code @Value("${키}")} 필드가 있으면, 상수는 값으로·그 필드는 {@code ${키}}로·나머지는 {@code {*}}로 바꾼 문자열.
     * 그런 필드가 없거나 상수 부분이 없으면 null이다.
     */
    private String placeholderTemplate(Expression expression) {
        List<Expression> operands = new ArrayList<>();
        flatten(expression, operands);
        StringBuilder sb = new StringBuilder();
        boolean anyPlaceholder = false;
        boolean anyConstant = false;
        for (Expression operand : operands) {
            Object constant = Constants.eval(operand);
            String placeholder = constant != null ? null : valuePlaceholder(operand);
            if (constant == null && placeholder == null) {
                placeholder = propertyRead(operand);
            }
            if (constant != null) {
                sb.append(constant);
                anyConstant |= !String.valueOf(constant).isEmpty();
            } else if (placeholder != null) {
                sb.append(placeholder);
                anyPlaceholder = true;
            } else if (!sb.toString().endsWith(Constants.DYNAMIC)) {
                sb.append(Constants.DYNAMIC);
            }
        }
        return anyPlaceholder && anyConstant ? sb.toString() : null;
    }

    /** 펼친 뷰 이름 후보의 상한. 넘으면 펼치지 않는다. */
    private static final int MAX_VIEW_CANDIDATES = 8;

    /**
     * 문자열 연결식의 피연산자가 상수이거나 "문자열 상수만 대입된 지역 변수"뿐이면, 변수 값의 조합마다 만든 문자열들을 준다.
     * 그런 변수가 없거나, 값을 모르는 피연산자가 하나라도 있거나, 조합이 너무 많으면 null이다.
     */
    private static List<String> expandLocals(Expression expression, Map<String, List<Expression>> localValues) {
        List<Expression> operands = new ArrayList<>();
        flatten(expression, operands);
        if (operands.size() < 2) {
            return null;
        }
        List<String> results = new ArrayList<>(List.of(""));
        boolean anyVariable = false;
        for (Expression operand : operands) {
            Object constant = Constants.eval(operand);
            List<String> options = new ArrayList<>();
            if (constant != null) {
                options.add(String.valueOf(constant));
            } else if (operand instanceof SimpleName name && name.resolveBinding() instanceof IVariableBinding v && !v.isField() && !v.isParameter()) {
                for (Expression value : localValues.getOrDefault(v.getKey(), List.of())) {
                    String text = Constants.string(value);
                    if (text == null) {
                        return null;
                    }
                    if (!options.contains(text)) {
                        options.add(text);
                    }
                }
                anyVariable = true;
            }
            if (options.isEmpty() || results.size() * options.size() > MAX_VIEW_CANDIDATES) {
                return null;
            }
            List<String> next = new ArrayList<>();
            for (String prefix : results) {
                for (String option : options) {
                    next.add(prefix + option);
                }
            }
            results = next;
        }
        return anyVariable ? results : null;
    }

    /** 컴파일러가 고른 선언의 매개변수 가운데 classpath에 없는 타입의 자리에 해석된 타입의 인자(문자열 리터럴 등)가 왔는가. */
    private static boolean choseUnresolvedParameter(Expression e) {
        if (!(e instanceof MethodInvocation call) || call.resolveMethodBinding() == null) {
            return false;
        }
        ITypeBinding[] params = call.resolveMethodBinding().getMethodDeclaration().getParameterTypes();
        for (int i = 0; i < params.length && i < call.arguments().size(); i++) {
            ITypeBinding argument = ((Expression) call.arguments().get(i)).resolveTypeBinding();
            if (params[i].isRecovered() && argument != null && !argument.isRecovered() && !argument.isNullType()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 컴파일러가 고른 선언을 믿을 수 없는 호출 식(자기 자신의 호출, 미해석 매개변수 자리에 해석된 인자)에서,
     * 같은 이름·인자 수의 다른 프로젝트 선언 가운데 매개변수 타입이 모두 해석됐고
     * 인자를 받을 수 있는 것이 정확히 하나면 그 메서드의 노드 ID. 아니면 null.
     */
    private String otherOverload(Expression e) {
        if (!(e instanceof MethodInvocation call)) {
            return null;
        }
        IMethodBinding chosen = call.resolveMethodBinding();
        ITypeBinding start = chosen == null ? null : chosen.getMethodDeclaration().getDeclaringClass();
        String own = chosen == null ? null : project.methodId(chosen);
        String found = null;
        for (int depth = 0; start != null && !start.isRecovered() && start.isFromSource() && depth < 30; start = start.getSuperclass(), depth++) {
            for (IMethodBinding candidate : start.getDeclaredMethods()) {
                ITypeBinding[] params = candidate.getParameterTypes();
                if (candidate.isConstructor() || !candidate.getName().equals(call.getName().getIdentifier())
                        || params.length != call.arguments().size() || project.methodId(candidate).equals(own)) {
                    continue;
                }
                boolean accepts = true;
                for (int i = 0; i < params.length && accepts; i++) {
                    ITypeBinding argument = ((Expression) call.arguments().get(i)).resolveTypeBinding();
                    accepts = argument != null && !argument.isRecovered() && !params[i].isRecovered() && argument.isAssignmentCompatible(params[i]);
                }
                if (accepts) {
                    if (found != null) {
                        return null;
                    }
                    found = project.methodId(candidate);
                }
            }
        }
        return found;
    }

    /** 식이 컴파일러가 해석한 프로젝트 메서드의 호출이면 그 메서드의 노드 ID. 아니면 null. */
    private String calledProjectMethod(Expression e) {
        IMethodBinding binding;
        int nameStart;
        if (e instanceof MethodInvocation call) {
            binding = call.resolveMethodBinding();
            nameStart = call.getName().getStartPosition();
        } else if (e instanceof SuperMethodInvocation call) {
            binding = call.resolveMethodBinding();
            nameStart = call.getName().getStartPosition();
        } else {
            return null;
        }
        if (binding == null || unit.isBadCall(nameStart)) {
            return null;
        }
        IMethodBinding decl = binding.getMethodDeclaration();
        ITypeBinding declaring = decl.getDeclaringClass();
        return declaring == null || declaring.isRecovered() || !declaring.isFromSource() ? null : project.methodId(decl);
    }

    // ---- @Bean ----

    private void beanMethod(MethodDeclaration method, String methodId, String typeName, Unit.Anno bean, List<Unit.Anno> annos) {
        List<String> names = new ArrayList<>();
        for (Expression e : bean.values("value", "name")) {
            String name = Constants.string(e);
            if (name != null && !name.isEmpty()) {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            names.add(method.getName().getIdentifier());
        }
        TypeRef declared = method.getReturnType2() == null ? null : unit.ref(method.getReturnType2());
        String beanType = declared == null ? null : declared.name();

        // 반환하는 객체: new X(..) 또는 new X(..)로 초기화한 지역 변수
        ClassInstanceCreation[] creation = new ClassInstanceCreation[1];
        String[] variableKey = new String[1];
        Map<String, List<Expression>> localValues = localValues(method);
        method.getBody().accept(new OwnCodeVisitor() {
            @Override
            public boolean visit(ReturnStatement node) {
                Expression e = node.getExpression();
                while (e instanceof ParenthesizedExpression p) {
                    e = p.getExpression();
                }
                if (e instanceof ClassInstanceCreation c && creation[0] == null) {
                    creation[0] = c;
                } else if (e instanceof SimpleName name && name.resolveBinding() instanceof IVariableBinding v && !v.isField()
                        && creation[0] == null) {
                    for (Expression value : localValues.getOrDefault(v.getKey(), List.of())) {
                        if (value instanceof ClassInstanceCreation c) {
                            creation[0] = c;
                            variableKey[0] = v.getKey();
                        }
                    }
                }
                return true;
            }
        });
        String beanClass = beanType;
        ITypeBinding classBinding = method.getReturnType2() == null ? null : Unit.reduce(method.getReturnType2().resolveBinding());
        Map<String, String> properties = new LinkedHashMap<>();
        if (creation[0] != null && creation[0].getAnonymousClassDeclaration() == null) {
            TypeRef created = unit.ref(creation[0].getType());
            if (created != null) {
                beanClass = created.name();
                classBinding = Unit.reduce(creation[0].getType().resolveBinding());
            }
            String simple = JavaProject.simpleName(Unit.written(creation[0].getType()));
            if (simple.endsWith("ViewResolver") && creation[0].arguments().size() == 2) {
                String prefix = Constants.string((Expression) creation[0].arguments().get(0));
                String suffix = Constants.string((Expression) creation[0].arguments().get(1));
                if (prefix != null && suffix != null) {
                    properties.put("property.prefix", prefix);
                    properties.put("property.suffix", suffix);
                }
            }
        }
        if (variableKey[0] != null) {
            // 그 변수에 상수로 준 setter 값
            method.getBody().accept(new OwnCodeVisitor() {
                @Override
                public boolean visit(MethodInvocation node) {
                    String name = node.getName().getIdentifier();
                    if (name.startsWith("set") && name.length() > 3 && node.arguments().size() == 1
                            && node.getExpression() instanceof SimpleName receiver
                            && receiver.resolveBinding() instanceof IVariableBinding v && variableKey[0].equals(v.getKey())) {
                        Object value = Constants.eval((Expression) node.arguments().get(0));
                        if (value != null) {
                            properties.put("property." + decapitalize(name.substring(3)), String.valueOf(value));
                        }
                    }
                    return true;
                }
            });
        }

        boolean primary = Unit.find(annos, PRIMARY) != null;
        String scope = scope(annos);
        SourceLocation location = unit.declLoc(method, method.getName());
        if (beanType != null) {
            graph.putAttrs(methodId, Map.of("beanType", beanType));
        }
        for (String name : names) {
            String beanId = Ids.springBean(name);
            Node.Builder b = Node.builder(beanId, NodeKind.SPRING_BEAN).name(name).qualifiedName(name).location(location)
                    .attr("source", "beanMethod").attr("beanType", beanType).attr("class", beanClass);
            properties.forEach(b::attr);
            if (primary) {
                b.attr("primary", true);
            }
            if (scope != null) {
                b.attr("scope", scope);
            }
            graph.declare(b.build());
            Edge.Builder edge = Edge.builder(methodId, beanId, EdgeKind.DEFINES_BEAN).location(location);
            if (beanType != null) {
                edge.attr("beanType", beanType);
            }
            graph.addEdge(edge.build());
            project.spring.beanMethods.add(new SpringModel.BeanMethod(name, typeName, method.getName().getIdentifier(), methodId));
            boolean projectClass = classBinding != null && !classBinding.isRecovered() && classBinding.isFromSource();
            project.spring.beans.add(new SpringModel.Bean(name, projectClass ? project.binary(classBinding) : null,
                    projectClass ? supertypes(classBinding, project.binary(classBinding)) : Set.of(), primary));
        }
    }

    // ---- MyBatis: 애너테이션 SQL ----

    private void annotationSql(MethodDeclaration method, String typeName, List<Unit.Anno> annos) {
        for (Unit.Anno anno : annos) {
            String statementType = null;
            for (Map.Entry<String, String> e : SQL_ANNOTATIONS.entrySet()) {
                if (anno.is(e.getKey())) {
                    statementType = e.getValue();
                }
            }
            if (statementType == null) {
                continue;
            }
            String statementName = method.getName().getIdentifier();
            String id = Ids.mybatisStatement(typeName, statementName);
            SourceLocation location = unit.loc(anno.node());
            StringBuilder sql = new StringBuilder();
            boolean constant = true;
            for (Expression e : anno.values("value")) {
                String part = Constants.string(e);
                if (part == null) {
                    constant = false;
                    break;
                }
                if (sql.length() > 0) {
                    sql.append(' ');
                }
                sql.append(part);
            }
            Node.Builder b = Node.builder(id, NodeKind.SQL_STATEMENT).name(statementName).qualifiedName(typeName + "." + statementName)
                    .location(location).attr("namespace", typeName).attr("statementId", statementName)
                    .attr("annotation", true).attr("statementType", statementType);
            if (!constant || sql.length() == 0) {
                graph.declare(b.attr("dynamic", true).build());
                graph.report(Diagnostic.warn(DiagnosticCodes.DYNAMIC_SQL,
                        "애너테이션 SQL이 문자열 상수가 아니라 본문을 분석하지 못함: " + typeName + "." + statementName, location));
                return;
            }
            AnnotationSql.Result result = AnnotationSql.transform(sql.toString());
            graph.declare(b.attr("dynamic", result.dynamic()).attr("sql", result.sql().replaceAll("\\s+", " ")).build());
            unit.project.ctx.addEmbedded(EmbeddedSource.sql(id, result.sql(), location, false));
            return;
        }
    }

    // ---- Java 코드의 redirect·forward (F-004-R13) ----

    /**
     * {@code response.sendRedirect(<식>)}과 {@code getRequestDispatcher(<식>).forward(..)}/{@code .include(..)}의 경로를
     * 호출한 메서드의 {@code redirects}·{@code forwards}에 모은다. 링커가 뷰 이름의 {@code redirect:}처럼 엔드포인트 핸들러로 잇는다.
     * 경로를 전혀 알 수 없으면 미해결 엣지(CALLS, 분류 http-endpoint)를 남긴다.
     */
    private void dispatch(MethodInvocation node) {
        String name = node.getName().getIdentifier();
        Expression receiver = unparen(node.getExpression());
        if (receiver == null) {
            return;
        }
        if (name.equals("sendRedirect") && node.arguments().size() == 1) {
            if (hasType(receiver, RESPONSE_TYPES) || isCallOn(receiver, "toHttp", "WebUtils")) {
                dispatchTo(node, List.of((Expression) node.arguments().get(0)), "redirect", redirects);
            }
        } else if (name.equals("sendRedirect") && node.arguments().size() == 3) {
            // Spring Security: RedirectStrategy.sendRedirect(request, response, url)
            if (hasType(receiver, REDIRECT_STRATEGY_TYPES) || isCallOn(receiver, "getRedirectStrategy", null)) {
                dispatchTo(node, List.of((Expression) node.arguments().get(2)), "redirect", redirects);
            }
        } else if (name.equals("issueRedirect") && node.arguments().size() >= 3) {
            // Shiro: WebUtils.issueRedirect(request, response, url, ..)
            if (receiver instanceof Name type && JavaProject.simpleName(type.getFullyQualifiedName()).equals("WebUtils")) {
                dispatchTo(node, List.of((Expression) node.arguments().get(2)), "redirect", redirects);
            }
        } else if ((name.equals("forward") || name.equals("include")) && node.arguments().size() == 2) {
            List<Expression> paths = dispatcherPaths(receiver, node);
            if (paths != null) {
                dispatchTo(node, paths.isEmpty() ? List.of(receiver) : paths, "forward", forwards);
            }
        }
    }

    /**
     * forward/include의 수신자가 가리키는 디스패처의 경로 식들. {@code getRequestDispatcher(<식>)} 호출이거나 그 결과를 받은 지역 변수다.
     * 디스패처이긴 한데 어디서 온 것인지 모르면 빈 목록, 디스패처가 아니면 null.
     */
    private List<Expression> dispatcherPaths(Expression receiver, ASTNode at) {
        if (isDispatcherLookup(receiver)) {
            return List.of((Expression) ((MethodInvocation) receiver).arguments().get(0));
        }
        List<Expression> paths = new ArrayList<>();
        if (receiver instanceof SimpleName name && name.resolveBinding() instanceof IVariableBinding v && !v.isField() && !v.isParameter()) {
            for (Expression value : localValuesAt(at).getOrDefault(v.getKey(), List.of())) {
                Expression e = unparen(value);
                if (isDispatcherLookup(e)) {
                    paths.add((Expression) ((MethodInvocation) e).arguments().get(0));
                }
            }
        }
        return paths.isEmpty() && !hasType(receiver, DISPATCHER_TYPES) ? null : paths;
    }

    /** 식이 {@code <타입 이름>.<메서드>(..)} 호출인가. typeName이 null이면 수신자를 따지지 않는다(수신자 없는 호출 포함). */
    private static boolean isCallOn(Expression e, String method, String typeName) {
        if (!(e instanceof MethodInvocation call) || !call.getName().getIdentifier().equals(method)) {
            return false;
        }
        return typeName == null || (call.getExpression() instanceof Name type
                && JavaProject.simpleName(type.getFullyQualifiedName()).equals(typeName));
    }

    /** {@code response.encodeRedirectURL(<식>)}으로 감싼 경로면 안의 식. */
    private static Expression unwrapEncoded(Expression e) {
        while (e instanceof MethodInvocation call && call.arguments().size() == 1 && call.getExpression() != null
                && (call.getName().getIdentifier().equals("encodeRedirectURL") || call.getName().getIdentifier().equals("encodeRedirectUrl"))) {
            e = unparen((Expression) call.arguments().get(0));
        }
        return e;
    }

    private static boolean isDispatcherLookup(Expression e) {
        return e instanceof MethodInvocation call && call.getName().getIdentifier().equals("getRequestDispatcher")
                && call.arguments().size() == 1;
    }

    private void dispatchTo(MethodInvocation call, List<Expression> paths, String callType, Map<String, Map<String, String>> sink) {
        Unit.Owner owner = unit.owner(call);
        if (owner == null) {
            return;
        }
        Map<String, List<Expression>> locals = localValuesAt(call);
        Set<String> found = new LinkedHashSet<>();
        Map<String, SourceLocation> unknown = new LinkedHashMap<>();
        for (Expression path : paths) {
            dispatchExpression(path, locals, found, unknown, 0);
        }
        if (!found.isEmpty()) {
            // 호출 지점은 sendRedirect·forward·include 메서드 이름의 자리다(그 호출의 CALLS와 같다)
            String site = unit.lineColumn(call.getName());
            Map<String, String> known = sink.computeIfAbsent(owner.methodId(), k -> new LinkedHashMap<>());
            found.forEach(path -> known.putIfAbsent(path, site));
        }
        unknown.forEach((text, location) -> project.unresolvedEdge(owner.methodId(), EdgeKind.CALLS, CAT_ENDPOINT, text, location,
                Map.of("callType", callType)));
    }

    private void dispatchExpression(Expression expression, Map<String, List<Expression>> locals, Set<String> found,
                                    Map<String, SourceLocation> unknown, int depth) {
        Expression e = unwrapEncoded(unparen(expression));
        if (e instanceof ConditionalExpression conditional) {
            dispatchExpression(conditional.getThenExpression(), locals, found, unknown, depth);
            dispatchExpression(conditional.getElseExpression(), locals, found, unknown, depth);
            return;
        }
        String pattern = pathPattern(e, locals, this::valuePlaceholder);
        if (pattern != null) {
            found.add(pattern);
            return;
        }
        if (e instanceof SimpleName name && depth < 3 && name.resolveBinding() instanceof IVariableBinding v && !v.isField() && !v.isParameter()) {
            List<Expression> values = locals.get(v.getKey());
            if (values != null && !values.isEmpty()) {
                for (Expression value : values) {
                    dispatchExpression(value, locals, found, unknown, depth + 1);
                }
                return;
            }
        }
        if (e.getNodeType() != ASTNode.NULL_LITERAL) {
            unknown.putIfAbsent(CallGraphExtractor.clip(e.toString()), unit.loc(e));
        }
    }

    /**
     * 경로 식을 경로 패턴으로 바꾼다. 상수 부분은 값으로, 알 수 없는 부분은 {@code {*}}로 두고,
     * 맨 앞의 컨텍스트 경로({@code request.getContextPath()})와 쿼리 문자열·fragment는 뗀다. 상수 부분이 하나도 없으면 null.
     */
    private static String pathPattern(Expression e, Map<String, List<Expression>> locals,
                                      java.util.function.Function<Expression, String> placeholders) {
        List<Expression> operands = new ArrayList<>();
        flatten(e, operands);
        StringBuilder sb = new StringBuilder();
        boolean leadingContext = false;
        for (Expression operand : operands) {
            Object value = Constants.eval(operand);
            // @Value("${키}") 필드는 그 자리에 ${키}를 남긴다(F-004-R23)
            String placeholder = value != null || operands.size() < 2 ? null : placeholders.apply(operand);
            if (value != null) {
                sb.append(value);
            } else if (placeholder != null) {
                sb.append(placeholder);
            } else if (sb.length() == 0 && !leadingContext && isContextPath(operand, locals, 0)) {
                leadingContext = true;
            } else if (!sb.toString().endsWith(Constants.DYNAMIC)) {
                sb.append(Constants.DYNAMIC);
            }
        }
        String pattern = sb.toString();
        int cut = pattern.length();
        for (char stop : new char[]{'?', '#'}) {
            int at = pattern.indexOf(stop);
            if (at >= 0 && at < cut) {
                cut = at;
            }
        }
        pattern = pattern.substring(0, cut);
        if (pattern.isEmpty() && leadingContext && cut == sb.length()) {
            // 컨텍스트 경로뿐이면 애플리케이션 루트다
            return "/";
        }
        if (pattern.startsWith(Constants.DYNAMIC) && !pattern.contains("/")) {
            // 앞이 동적이고 상수 부분에 경로 구분자가 없다(쿼리 문자열 조각 등): 경로를 아는 것이 아니다
            return null;
        }
        return pattern.replace(Constants.DYNAMIC, "").isEmpty() ? null : pattern;
    }

    /** 문자열 연결식을 피연산자 목록으로 편다. 상수로 계산되는 부분식은 통째로 둔다. */
    private static void flatten(Expression expression, List<Expression> out) {
        Expression e = unparen(expression);
        if (e instanceof InfixExpression infix && infix.getOperator() == InfixExpression.Operator.PLUS && Constants.eval(e) == null) {
            flatten(infix.getLeftOperand(), out);
            flatten(infix.getRightOperand(), out);
            for (Object extended : infix.extendedOperands()) {
                flatten((Expression) extended, out);
            }
        } else {
            out.add(e);
        }
    }

    /** {@code getContextPath()} 호출이거나 그 결과만 받은 지역 변수인가. */
    private static boolean isContextPath(Expression expression, Map<String, List<Expression>> locals, int depth) {
        Expression e = unparen(expression);
        if (e instanceof MethodInvocation call) {
            return call.getName().getIdentifier().equals("getContextPath") && call.arguments().isEmpty();
        }
        if (e instanceof SimpleName name && depth < 3 && name.resolveBinding() instanceof IVariableBinding v && !v.isField() && !v.isParameter()) {
            List<Expression> values = locals.getOrDefault(v.getKey(), List.of());
            if (values.isEmpty()) {
                return false;
            }
            for (Expression value : values) {
                if (!isContextPath(value, locals, depth + 1)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static Expression unparen(Expression e) {
        while (e instanceof ParenthesizedExpression p) {
            e = p.getExpression();
        }
        return e;
    }

    /** at을 감싼 메서드의 지역 변수 값들. 메서드 밖(필드 초기화식 등)이면 빈 맵. */
    private Map<String, List<Expression>> localValuesAt(ASTNode at) {
        for (ASTNode p = at.getParent(); p != null; p = p.getParent()) {
            if (p instanceof MethodDeclaration method) {
                return method.getBody() == null ? Map.of() : localValuesByMethod.computeIfAbsent(method, SpringExtractor::localValues);
            }
            if (p instanceof AbstractTypeDeclaration || p instanceof AnonymousClassDeclaration) {
                break;
            }
        }
        return Map.of();
    }

    /** 식의 타입(상위 타입 포함)의 단순 이름이 names에 드는가. 타입이 해석되지 않아도 선언 타입의 단순 이름으로 판단한다. */
    private static boolean hasType(Expression e, Set<String> names) {
        if (hierarchyHasName(e.resolveTypeBinding(), names, new HashSet<>())) {
            return true;
        }
        return e instanceof Name name && name.resolveBinding() instanceof IVariableBinding v
                && hierarchyHasName(v.getType(), names, new HashSet<>());
    }

    private static boolean hierarchyHasName(ITypeBinding type, Set<String> names, Set<String> seen) {
        if (type == null || seen.size() > 100) {
            return false;
        }
        ITypeBinding erasure = type.getErasure() == null ? type : type.getErasure();
        String simple = erasure.isRecovered() ? JavaProject.simpleName(JavaProject.written(erasure)) : erasure.getName();
        if (names.contains(simple)) {
            return true;
        }
        if (erasure.isRecovered() || !seen.add(erasure.getKey() == null ? simple : erasure.getKey())) {
            return false;
        }
        if (hierarchyHasName(erasure.getSuperclass(), names, seen)) {
            return true;
        }
        for (ITypeBinding i : erasure.getInterfaces()) {
            if (hierarchyHasName(i, names, seen)) {
                return true;
            }
        }
        return false;
    }

    // ---- MyBatis: SqlSession 호출 ----

    @Override
    public boolean visit(MethodInvocation node) {
        if (SQL_SESSION_OPERATIONS.contains(node.getName().getIdentifier()) && !node.arguments().isEmpty()
                && isSqlSession(node.getExpression(), node)) {
            executes(node, node.getName(), (Expression) node.arguments().get(0));
        } else {
            statementIdArguments(node);
        }
        dispatch(node);
        return true;
    }

    /**
     * 라이브러리 메서드 호출에 넘긴 "구문 ID처럼 보이는 문자열 상수"를 적어 둔다(F-004-R24). 구문 ID를 받아 대신 실행하는
     * 라이브러리 메서드({@code excelService.uploadExcel("ZipManageDAO.insertExcelZip", ..)})를 링커가 구문과 잇는 데 쓴다.
     * 구문 ID를 실행하러 넘기는 자리가 아닌 호출은 뺀다: 프로젝트 메서드(컴파일러가 선언을 찾았거나 수신자 없이 부른 것),
     * JDK 메서드({@code System.getProperty("user.dir")}), 값을 견주는 단언({@code assert*}),
     * 구문을 찾아만 보는 호출({@code getMappedStatement}, {@code hasStatement}).
     */
    private void statementIdArguments(MethodInvocation node) {
        String name = node.getName().getIdentifier();
        if (node.arguments().isEmpty() || name.startsWith("assert") || STATEMENT_LOOKUPS.contains(name)) {
            return;
        }
        Map<String, String> found = null;
        for (Object a : node.arguments()) {
            Expression argument = (Expression) a;
            String value = Constants.string(argument);
            if (value == null || value.length() > 200 || !STATEMENT_ID_LIKE.matcher(value).matches()) {
                continue;
            }
            if (found == null) {
                IMethodBinding binding = node.resolveMethodBinding();
                ITypeBinding declaring = binding == null ? null : binding.getMethodDeclaration().getDeclaringClass();
                boolean known = declaring != null && !declaring.isRecovered();
                Unit.Owner owner = unit.owner(node);
                // 선언을 모르는 호출은 수신자를 적은 것만 본다(수신자 없는 호출은 자기 타입의 메서드이거나 static import한 도우미다)
                if (owner == null || (known ? declaring.isFromSource() || project.isJdk(declaring, project.binary(declaring))
                        : node.getExpression() == null || node.getExpression() instanceof ThisExpression)) {
                    return;
                }
                found = statementIdLiterals.computeIfAbsent(owner.methodId(), k -> new LinkedHashMap<>());
            }
            found.putIfAbsent(value, unit.lineColumn(argument));
        }
    }

    /** 구문 ID로 구문을 찾아만 보는 MyBatis {@code Configuration}의 메서드. 실행이 아니다. */
    private static final Set<String> STATEMENT_LOOKUPS = Set.of("getMappedStatement", "hasStatement");

    @Override
    public boolean visit(SuperMethodInvocation node) {
        if (SQL_SESSION_OPERATIONS.contains(node.getName().getIdentifier()) && !node.arguments().isEmpty()) {
            ITypeBinding enclosing = Unit.typeBinding(Unit.enclosingType(node));
            if (enclosing != null && hierarchyHasSessionType(enclosing.getSuperclass(), new HashSet<>())) {
                executes(node, node.getName(), (Expression) node.arguments().get(0));
            }
        }
        return true;
    }

    /** 수신자가 SqlSession 계열인가. 타입이 해석되지 않아도 선언 타입의 단순 이름으로 판단한다. */
    private boolean isSqlSession(Expression receiver, ASTNode at) {
        if (receiver == null || receiver instanceof ThisExpression) {
            return hierarchyHasSessionType(Unit.typeBinding(Unit.enclosingType(at)), new HashSet<>());
        }
        if (receiver instanceof MethodInvocation call && call.getName().getIdentifier().equals("getSqlSession")
                && call.arguments().isEmpty()) {
            return true;
        }
        if (hierarchyHasSessionType(receiver.resolveTypeBinding(), new HashSet<>())) {
            return true;
        }
        if (receiver instanceof Name name && name.resolveBinding() instanceof IVariableBinding v) {
            return hierarchyHasSessionType(v.getType(), new HashSet<>());
        }
        return false;
    }

    private boolean hierarchyHasSessionType(ITypeBinding type, Set<String> seen) {
        return hierarchyHasName(type, project.options.sqlSessionTypes(), seen);
    }

    private void executes(ASTNode call, SimpleName operation, Expression statement) {
        Unit.Owner owner = unit.owner(call);
        if (owner == null) {
            return;
        }
        Expression id = unparen(statement);
        String value = Constants.string(id);
        if (value == null && id instanceof SimpleName name && name.resolveBinding() instanceof IVariableBinding v && !v.isField() && !v.isParameter()) {
            // 한 번만 대입된 지역 변수에 담은 구문 ID는 그 값을 따라간다(F-004-R20)
            List<Expression> values = localValuesAt(call).getOrDefault(v.getKey(), List.of());
            if (values.size() == 1) {
                id = unparen(values.get(0));
                value = Constants.string(id);
            }
        }
        if (value == null) {
            // '리터럴 접두 + 동적': namespace까지 리터럴이면 접두를 남겨 링커가 그 접두로 시작하는 구문을 찾게 한다(F-004-R20)
            String template = Constants.template(id);
            int dynamic = template == null ? -1 : template.indexOf(Constants.DYNAMIC);
            if (dynamic > 0 && template.lastIndexOf('.', dynamic) > 0) {
                String prefix = template.substring(0, dynamic);
                project.unresolvedEdge(owner.methodId(), EdgeKind.EXECUTES, CAT_STATEMENT, prefix + Constants.DYNAMIC, unit.loc(operation),
                        Map.of("statementPrefix", prefix, "operation", operation.getIdentifier()));
                return;
            }
        }
        addExecutes(graph, owner.methodId(), value, statement.toString(), unit.loc(operation), operation.getIdentifier());
    }

    /**
     * 구문 ID 문자열로 EXECUTES를 만든다. 마지막 '.' 앞이 namespace다. '.'이 없으면 ID 그대로 쓰고 shortId=true.
     * value가 null이면(상수가 아님) 미해결 엣지를 남긴다.
     */
    static void addExecutes(GraphModel graph, String fromId, String value, String text, SourceLocation location, String operation) {
        if (value == null || value.isBlank()) {
            graph.addUnresolvedEdge(fromId, EdgeKind.EXECUTES, CAT_STATEMENT, CallGraphExtractor.clip(text), location);
            return;
        }
        String statement = value.trim();
        int dot = statement.lastIndexOf('.');
        Edge.Builder b;
        if (dot > 0 && dot < statement.length() - 1) {
            b = Edge.builder(fromId, Ids.mybatisStatement(statement.substring(0, dot), statement.substring(dot + 1)), EdgeKind.EXECUTES)
                    .attr("statementId", statement.substring(dot + 1)).attr("namespace", statement.substring(0, dot));
        } else {
            b = Edge.builder(fromId, NodeKind.SQL_STATEMENT.idPrefix() + ":" + statement, EdgeKind.EXECUTES)
                    .attr("shortId", true).attr("statementId", statement);
        }
        if (operation != null) {
            b.attr("operation", operation);
        }
        graph.addEdge(b.location(location).build());
    }
}
