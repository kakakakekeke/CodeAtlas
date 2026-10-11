package io.codeatlas.java;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.ArrayType;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * F-002-R14 Lombok 합성 멤버. 애너테이션 처리기를 돌리지 않으므로 Lombok 애너테이션을 보고
 * 생성될 메서드·생성자·빌더 타입·log 필드를 {@code attrs.synthetic=true}, {@code attrs.lombok=<애너테이션 이름>} 노드로 만든다.
 * 소스에 같은 시그니처의 메서드가 이미 있으면 Lombok처럼 만들지 않는다.
 */
final class LombokSynthesizer {

    /** 필드 하나(레코드 구성 요소, 빌더가 붙은 생성자·메서드의 매개변수 포함). */
    private static final class Field {
        String name;
        Type type;
        int extraDimensions;
        boolean isStatic;
        boolean isFinal;
        boolean initialized;
        Map<String, Unit.Anno> annos = Map.of();
        SourceLocation location;
        /** 필드 선언의 조각. 매개변수에서 온 것이면 null. */
        VariableDeclarationFragment fragment;
        /** 이 필드의 JAVA_FIELD 노드 ID. 매개변수에서 온 것이면 null. */
        String fieldId;

        boolean has(String kind) {
            return annos.containsKey(kind);
        }
    }

    /** 합성 메서드의 매개변수. */
    private record Param(String name, String display, String binary) {
    }

    private final Unit unit;
    private final JavaProject project;
    private final GraphModel graph;

    private String typeName;
    private String simpleName;
    /** 이 타입에 이미 있는 메서드 ID(소스에 선언한 것과 여기서 만든 것). */
    private Set<String> methodIds;
    /** 소스에 선언한 메서드의 "이름(소문자)/매개변수 수". Lombok은 이름을 대소문자 구분 없이 비교한다. */
    private Set<String> declaredSignatures;
    private Unit.LombokType model;
    private boolean enumType;

    LombokSynthesizer(Unit unit) {
        this.unit = unit;
        this.project = unit.project;
        this.graph = project.graph;
    }

    /**
     * 타입 하나의 합성 멤버를 만든다.
     *
     * @param methodIds 그 타입에 선언된 메서드 ID. 만든 메서드의 ID를 여기에 더한다
     */
    void run(ASTNode typeNode, Set<String> methodIds) {
        if (!(typeNode instanceof AbstractTypeDeclaration type) || type instanceof AnnotationTypeDeclaration
                || (type instanceof TypeDeclaration t && t.isInterface())) {
            return;
        }
        Map<String, Unit.Anno> annos = lombokAnnotations(type.modifiers());
        List<Field> fields = fields(type);
        List<MethodDeclaration> builderMembers = new ArrayList<>();
        List<MethodDeclaration> constructors = new ArrayList<>();
        Set<String> signatures = new HashSet<>();
        for (Object member : type.bodyDeclarations()) {
            if (member instanceof MethodDeclaration m) {
                signatures.add(signature(m.getName().getIdentifier(), m.parameters().size()));
                if (m.isConstructor()) {
                    constructors.add(m);
                }
                if (lombokAnnotations(m.modifiers()).containsKey(Lombok.BUILDER)) {
                    builderMembers.add(m);
                }
            }
        }
        boolean anyFieldAnnotation = fields.stream().anyMatch(f -> !f.annos.isEmpty());
        if (annos.isEmpty() && !anyFieldAnnotation && builderMembers.isEmpty()) {
            return;
        }
        this.typeName = unit.typeName(type);
        this.simpleName = type.getName().getIdentifier();
        this.methodIds = methodIds;
        this.declaredSignatures = signatures;
        this.model = new Unit.LombokType();
        this.enumType = type instanceof EnumDeclaration;
        unit.lombokTypes.put(type, model);

        boolean value = annos.containsKey(Lombok.VALUE);
        boolean data = annos.containsKey(Lombok.DATA);
        Unit.Anno fieldDefaults = annos.get(Lombok.FIELD_DEFAULTS);
        if (value || Lombok.flag(fieldDefaults, "makeFinal", false)) {
            for (Field f : fields) {
                f.isFinal |= !f.isStatic && !f.has(Lombok.NON_FINAL);
            }
        }

        loggers(annos);
        accessors(annos, fields, data, value);
        constructors(type, annos, fields, constructors, data, value);
        objectMethods(annos, data, value);
        Unit.Anno builder = annos.containsKey(Lombok.BUILDER) ? annos.get(Lombok.BUILDER) : annos.get(Lombok.SUPER_BUILDER);
        if (builder != null) {
            boolean superBuilder = !annos.containsKey(Lombok.BUILDER);
            List<Field> builderFields = fields.stream()
                    .filter(f -> !f.isStatic && !(f.isFinal && f.initialized && !f.has(Lombok.BUILDER_DEFAULT))).toList();
            builder(type, builder, superBuilder ? Lombok.SUPER_BUILDER : Lombok.BUILDER, builderFields, simpleName, typeName, simpleName, false);
            if (superBuilder) {
                TypeRef superType = type instanceof TypeDeclaration t && t.getSuperclassType() != null ? unit.ref(t.getSuperclassType()) : null;
                String builderClass = Lombok.string(builder, "builderClassName", simpleName + "Builder");
                project.calls.superBuilder(typeName, typeName + "$" + builderClass,
                        superType != null && superType.isProject() ? superType.name() : null, unit.loc(builder.node()));
            }
        }
        for (MethodDeclaration member : builderMembers) {
            memberBuilder(type, member);
        }
    }

    // ---- 애너테이션과 필드 읽기 ----

    private Map<String, Unit.Anno> lombokAnnotations(List<?> modifiers) {
        Map<String, Unit.Anno> map = null;
        for (Object m : modifiers) {
            if (m instanceof Annotation annotation) {
                String kind = Lombok.kind(unit, annotation);
                if (kind != null) {
                    if (map == null) {
                        map = new LinkedHashMap<>();
                    }
                    map.putIfAbsent(kind, new Unit.Anno(annotation, "lombok." + kind, kind));
                }
            }
        }
        return map == null ? Map.of() : map;
    }

    private List<Field> fields(AbstractTypeDeclaration type) {
        List<Field> fields = new ArrayList<>();
        String owner = unit.typeName(type);
        if (type instanceof RecordDeclaration record) {
            for (Object c : record.recordComponents()) {
                SingleVariableDeclaration component = (SingleVariableDeclaration) c;
                Field f = parameter(component);
                f.isFinal = true;
                f.fieldId = Ids.javaField(owner, f.name);
                fields.add(f);
            }
        }
        for (Object member : type.bodyDeclarations()) {
            if (!(member instanceof FieldDeclaration declaration)) {
                continue;
            }
            Map<String, Unit.Anno> annos = lombokAnnotations(declaration.modifiers());
            int flags = declaration.getModifiers();
            for (Object o : declaration.fragments()) {
                VariableDeclarationFragment fragment = (VariableDeclarationFragment) o;
                Field f = new Field();
                f.name = fragment.getName().getIdentifier();
                f.type = declaration.getType();
                f.extraDimensions = fragment.getExtraDimensions();
                f.isStatic = Modifier.isStatic(flags);
                f.isFinal = Modifier.isFinal(flags);
                f.initialized = fragment.getInitializer() != null;
                f.annos = annos;
                f.location = unit.declLoc(fragment, fragment.getName());
                f.fragment = fragment;
                f.fieldId = Ids.javaField(owner, f.name);
                fields.add(f);
            }
        }
        return fields;
    }

    private Field parameter(SingleVariableDeclaration declaration) {
        Field f = new Field();
        f.name = declaration.getName().getIdentifier();
        f.type = declaration.getType();
        f.extraDimensions = declaration.getExtraDimensions() + (declaration.isVarargs() ? 1 : 0);
        f.annos = lombokAnnotations(declaration.modifiers());
        f.location = unit.declLoc(declaration, declaration.getName());
        return f;
    }

    private static String signature(String name, int parameters) {
        return name.toLowerCase(Locale.ROOT) + "/" + parameters;
    }

    private boolean declared(String name, int parameters) {
        return declaredSignatures.contains(signature(name, parameters));
    }

    private static boolean primitiveBoolean(Field f) {
        return f.extraDimensions == 0 && f.type instanceof PrimitiveType p && p.getPrimitiveTypeCode() == PrimitiveType.BOOLEAN;
    }

    // ---- 타입 이름 ----

    /** 표시용 타입(소스에 쓴 그대로). */
    private static String display(Field f) {
        return f.type.toString() + "[]".repeat(f.extraDimensions);
    }

    /** 메서드 ID에 쓰는 erasure의 바이너리 이름. */
    private String binary(Field f) {
        ITypeBinding binding = f.type.resolveBinding();
        String name;
        if (binding != null) {
            name = project.binary(binding);
        } else {
            name = project.globalName(Unit.written(f.type)) + (f.type instanceof ArrayType a ? "[]".repeat(a.getDimensions()) : "");
        }
        return name + "[]".repeat(f.extraDimensions);
    }

    /** 이어서 메서드를 부를 수 있는 타입이면 그 바이너리 이름, 아니면(원시 타입·배열·타입 변수·이름을 모르는 타입) null. */
    private String chainType(Field f) {
        if (f.extraDimensions > 0 || f.type.isArrayType() || f.type.isPrimitiveType()) {
            return null;
        }
        TypeRef ref = unit.ref(f.type);
        return ref == null || ref.isUnresolved() ? null : ref.name();
    }

    private Param param(Field f) {
        return new Param(f.name, display(f), binary(f));
    }

    // ---- 노드 만들기 ----

    /**
     * 합성 메서드 노드 하나를 만든다. 같은 ID의 메서드가 이미 있으면 만들지 않는다.
     *
     * @param returnDisplay 표시용 반환 타입. 생성자면 null
     * @param returnChain 이어서 부를 수 있는 반환 타입의 바이너리 이름. 없으면 null
     * @return 만든 노드의 ID. 만들지 않았으면 null
     */
    private String method(String owner, String name, List<Param> params, String returnDisplay, String returnChain, boolean returnsReceiver,
                          List<String> modifiers, String lombok, SourceLocation location) {
        List<String> binaries = params.stream().map(Param::binary).toList();
        String id = Ids.javaMethod(owner, name, binaries);
        boolean own = owner.equals(typeName);
        if (own ? !methodIds.add(id) : graph.node(id).filter(Node::declared).isPresent()) {
            return null;
        }
        boolean constructor = Ids.CONSTRUCTOR_NAME.equals(name);
        List<String> displays = params.stream().map(Param::display).toList();
        String shown = constructor ? JavaProject.simpleName(owner) : name;
        Node.Builder b = Node.builder(id, NodeKind.JAVA_METHOD).name(name)
                .qualifiedName(owner + "#" + name + "(" + String.join(",", displays) + ")")
                .location(location)
                .attr("synthetic", true)
                .attr("lombok", lombok)
                .attr("modifiers", modifiers)
                .attr("parameterTypes", displays)
                .attr("parameterNames", params.stream().map(Param::name).toList())
                .attr("constructor", constructor)
                .attr("signature", shown + "(" + String.join(", ", displays) + ")" + (returnDisplay == null ? "" : ": " + returnDisplay));
        if (returnDisplay != null) {
            b.attr("returnType", returnDisplay);
        }
        graph.declare(b.build());
        graph.addEdge(Edge.of(Ids.javaType(owner), id, EdgeKind.CONTAINS));
        project.calls.index(owner, new CallResolver.MethodInfo(id, name, binaries, false, modifiers.contains("static"), constructor,
                returnChain, returnsReceiver, true));
        return id;
    }

    private static List<String> withStatic(List<String> modifiers) {
        List<String> list = new ArrayList<>(modifiers);
        list.add("static");
        return list;
    }

    // ---- 로그 필드 ----

    private void loggers(Map<String, Unit.Anno> annos) {
        for (Map.Entry<String, Unit.Anno> e : annos.entrySet()) {
            String loggerType = Lombok.loggerType(e.getKey());
            if (loggerType == null || model.fields.containsKey("log")) {
                continue;
            }
            String id = Ids.javaField(typeName, "log");
            if (graph.node(id).filter(Node::declared).isPresent()) {
                continue;
            }
            graph.declare(Node.builder(id, NodeKind.JAVA_FIELD).name("log").qualifiedName(typeName + "#log")
                    .location(unit.loc(e.getValue().node()))
                    .attr("synthetic", true).attr("lombok", e.getKey())
                    .attr("type", loggerType).attr("modifiers", List.of("private", "static", "final")).attr("enumConstant", false).build());
            graph.addEdge(Edge.of(Ids.javaType(typeName), id, EdgeKind.CONTAINS));
            model.fields.put("log", loggerType);
        }
    }

    // ---- getter, setter, with ----

    private void accessors(Map<String, Unit.Anno> annos, List<Field> fields, boolean data, boolean value) {
        Unit.Anno classGetter = annos.get(Lombok.GETTER);
        Unit.Anno classSetter = annos.get(Lombok.SETTER);
        Unit.Anno classWith = annos.get(Lombok.WITH);
        Unit.Anno classAccessors = annos.get(Lombok.ACCESSORS);
        String implicit = data ? Lombok.DATA : value ? Lombok.VALUE : null;
        for (Field f : fields) {
            if (f.fieldId == null) {
                continue;
            }
            Unit.Anno accessors = f.has(Lombok.ACCESSORS) ? f.annos.get(Lombok.ACCESSORS) : classAccessors;
            boolean fluent = Lombok.flag(accessors, "fluent", false);
            boolean chain = Lombok.flag(accessors, "chain", fluent);
            boolean bool = primitiveBoolean(f);
            boolean classLevel = !f.isStatic && !f.name.startsWith("$");

            Unit.Anno getter = f.has(Lombok.GETTER) ? f.annos.get(Lombok.GETTER) : classLevel ? classGetter : null;
            if (getter != null || (classLevel && implicit != null)) {
                List<String> modifiers = Lombok.modifiers(Lombok.accessLevel(getter, "value"));
                if (modifiers != null && !getterDeclared(f.name, bool)) {
                    String id = method(typeName, Lombok.getterName(f.name, bool, fluent), List.of(), display(f), chainType(f), false,
                            f.isStatic ? withStatic(modifiers) : modifiers, getter != null ? Lombok.GETTER : implicit, f.location);
                    if (id != null && !(f.type.resolveBinding() != null && f.type.resolveBinding().isTypeVariable())) {
                        project.calls.returnBinary(id, binary(f));
                    }
                    access(id, f, "read");
                }
            }

            Unit.Anno setter = f.has(Lombok.SETTER) ? f.annos.get(Lombok.SETTER) : classLevel ? classSetter : null;
            if (!f.isFinal && (setter != null || (classLevel && data))) {
                List<String> modifiers = Lombok.modifiers(Lombok.accessLevel(setter, "value"));
                String name = Lombok.prefixedName("set", f.name, bool, fluent);
                if (modifiers != null && !declared(name, 1)) {
                    String id = method(typeName, name, List.of(param(f)), chain ? simpleName : "void", chain ? typeName : null, false,
                            f.isStatic ? withStatic(modifiers) : modifiers, setter != null ? Lombok.SETTER : Lombok.DATA, f.location);
                    access(id, f, "write");
                }
            }

            Unit.Anno with = f.has(Lombok.WITH) ? f.annos.get(Lombok.WITH) : classLevel ? classWith : null;
            if (with != null && !f.isStatic) {
                List<String> modifiers = Lombok.modifiers(Lombok.accessLevel(with, "value"));
                String name = Lombok.prefixedName("with", f.name, bool, false);
                if (modifiers != null && !declared(name, 1)) {
                    method(typeName, name, List.of(param(f)), simpleName, typeName, false, modifiers, Lombok.WITH, f.location);
                }
            }
        }
    }

    /** Lombok은 boolean 필드에서 is·get 두 이름 중 하나라도 있으면 getter를 만들지 않는다. */
    private boolean getterDeclared(String field, boolean bool) {
        List<String> bases = new ArrayList<>(List.of(field));
        if (Lombok.isPrefixedBoolean(field, bool)) {
            bases.add(field.substring(2));
        }
        for (String base : bases) {
            if (declared(Lombok.accessorName("get", base), 0) || (bool && declared(Lombok.accessorName("is", base), 0))) {
                return true;
            }
        }
        return false;
    }

    /** 합성 접근자에서 필드로 ACCESSES를 둔다(콜트리가 필드까지 이어지게). */
    private void access(String methodId, Field f, String access) {
        if (methodId != null) {
            graph.addEdge(Edge.builder(methodId, f.fieldId, EdgeKind.ACCESSES).location(f.location).attr("access", access).build());
        }
    }

    // ---- 생성자 ----

    private void constructors(AbstractTypeDeclaration type, Map<String, Unit.Anno> annos, List<Field> fields,
                              List<MethodDeclaration> explicit, boolean data, boolean value) {
        boolean classBuilder = annos.containsKey(Lombok.BUILDER);
        List<Field> all = fields.stream().filter(f -> f.fieldId != null && !f.isStatic && !f.name.startsWith("$")
                && !(f.isFinal && f.initialized && !(classBuilder && f.has(Lombok.BUILDER_DEFAULT)))).toList();
        List<Field> required = fields.stream().filter(f -> f.fieldId != null && !f.isStatic && !f.initialized
                && (f.isFinal || f.has(Lombok.NON_NULL))).toList();
        boolean annotated = annos.containsKey(Lombok.NO_ARGS) || annos.containsKey(Lombok.ALL_ARGS) || annos.containsKey(Lombok.REQUIRED_ARGS);

        if (annos.containsKey(Lombok.NO_ARGS)) {
            constructor(annos.get(Lombok.NO_ARGS), Lombok.NO_ARGS, List.of(), "staticName", null);
        }
        if (annos.containsKey(Lombok.ALL_ARGS)) {
            constructor(annos.get(Lombok.ALL_ARGS), Lombok.ALL_ARGS, all, "staticName", null);
        }
        if (annos.containsKey(Lombok.REQUIRED_ARGS)) {
            constructor(annos.get(Lombok.REQUIRED_ARGS), Lombok.REQUIRED_ARGS, required, "staticName", null);
        }
        if (annotated || !explicit.isEmpty() || type instanceof RecordDeclaration) {
            return;
        }
        // 명시적 생성자도 생성자 애너테이션도 없을 때만 @Value, @Data, @Builder가 생성자를 만든다
        if (value) {
            constructor(annos.get(Lombok.VALUE), Lombok.VALUE, all, "staticConstructor", null);
        } else if (classBuilder) {
            constructor(annos.get(Lombok.BUILDER), Lombok.BUILDER, all, null, List.of());
        } else if (data) {
            constructor(annos.get(Lombok.DATA), Lombok.DATA, required, "staticConstructor", null);
        }
    }

    /**
     * @param staticMember 정적 팩터리 이름을 담은 애너테이션 멤버. 없으면 null
     * @param fixedModifiers 접근 수준을 애너테이션에서 읽지 않고 정할 때의 수식어. 아니면 null
     */
    private void constructor(Unit.Anno anno, String lombok, List<Field> fields, String staticMember, List<String> fixedModifiers) {
        String factory = staticMember == null ? "" : Lombok.string(anno, staticMember, "");
        List<String> modifiers = fixedModifiers != null ? fixedModifiers : Lombok.modifiers(Lombok.accessLevel(anno, "access"));
        if (modifiers == null) {
            return;
        }
        if (enumType) {
            // enum의 생성자는 언제나 private이다
            modifiers = List.of("private");
        }
        SourceLocation location = unit.loc(anno.node());
        List<Param> params = fields.stream().map(this::param).toList();
        String id = method(typeName, Ids.CONSTRUCTOR_NAME, params, null, null, false, factory.isEmpty() ? modifiers : List.of("private"),
                lombok, location);
        if (id != null) {
            model.constructors.add(new Unit.LombokCtor(id, fields.stream().map(f -> f.fragment).toList()));
        }
        if (!factory.isEmpty() && !declared(factory, params.size())) {
            method(typeName, factory, params, simpleName, typeName, false, withStatic(modifiers), lombok, location);
        }
    }

    // ---- equals, hashCode, toString ----

    private void objectMethods(Map<String, Unit.Anno> annos, boolean data, boolean value) {
        String implicit = data ? Lombok.DATA : value ? Lombok.VALUE : null;
        Unit.Anno equals = annos.get(Lombok.EQUALS_AND_HASH_CODE);
        Unit.Anno toString = annos.get(Lombok.TO_STRING);
        Unit.Anno source = equals != null ? equals : implicit != null ? annos.get(implicit) : null;
        if (source != null) {
            String lombok = equals != null ? Lombok.EQUALS_AND_HASH_CODE : implicit;
            SourceLocation location = unit.loc(source.node());
            if (!declared("equals", 1)) {
                method(typeName, "equals", List.of(new Param("o", "Object", "java.lang.Object")), "boolean", null, false, List.of("public"),
                        lombok, location);
            }
            if (!declared("hashCode", 0)) {
                method(typeName, "hashCode", List.of(), "int", null, false, List.of("public"), lombok, location);
            }
        }
        source = toString != null ? toString : implicit != null ? annos.get(implicit) : null;
        if (source != null && !declared("toString", 0)) {
            method(typeName, "toString", List.of(), "String", "java.lang.String", false, List.of("public"),
                    toString != null ? Lombok.TO_STRING : implicit, unit.loc(source.node()));
        }
    }

    // ---- 빌더 ----

    /** 생성자나 static 메서드에 붙은 {@code @Builder}: 그 매개변수가 빌더의 필드다. */
    private void memberBuilder(AbstractTypeDeclaration type, MethodDeclaration member) {
        Unit.Anno anno = lombokAnnotations(member.modifiers()).get(Lombok.BUILDER);
        List<Field> params = new ArrayList<>();
        for (Object p : member.parameters()) {
            params.add(parameter((SingleVariableDeclaration) p));
        }
        if (member.isConstructor()) {
            builder(type, anno, Lombok.BUILDER, params, simpleName, typeName, simpleName, true);
            return;
        }
        Type returnType = member.getReturnType2();
        if (returnType == null) {
            return;
        }
        TypeRef target = returnType.isPrimitiveType() || returnType.isArrayType() ? null : unit.ref(returnType);
        String targetSimple = returnType.isPrimitiveType() ? Lombok.accessorName("", returnType.toString())
                : JavaProject.simpleName(Unit.written(returnType));
        builder(type, anno, Lombok.BUILDER, params, targetSimple, target == null || target.isUnresolved() ? null : target.name(),
                returnType.toString(), true);
    }

    /**
     * 빌더 타입과 그 메서드, {@code builder()}, {@code toBuilder()}를 만든다.
     *
     * @param targetSimple {@code build()}가 만드는 타입의 단순 이름(빌더 타입 이름의 앞부분)
     * @param targetChain {@code build()}가 돌려주는 타입의 바이너리 이름. 모르면 null
     * @param onMember 생성자·메서드에 붙은 것이면 true(위치는 그 애너테이션, 필드 노드 없음)
     */
    private void builder(AbstractTypeDeclaration type, Unit.Anno anno, String lombok, List<Field> fields, String targetSimple,
                         String targetChain, String targetDisplay, boolean onMember) {
        String builderSimple = Lombok.string(anno, "builderClassName", targetSimple + "Builder");
        String builderName = typeName + "$" + builderSimple;
        String builderId = Ids.javaType(builderName);
        SourceLocation location = unit.loc(anno.node());

        // 소스에 빌더 클래스를 직접 선언해 두었으면 그 타입에 없는 메서드만 더한다
        Set<String> declaredInBuilder = new HashSet<>();
        boolean builderDeclared = false;
        for (Object member : type.bodyDeclarations()) {
            if (member instanceof AbstractTypeDeclaration nested && nested.getName().getIdentifier().equals(builderSimple)) {
                builderDeclared = true;
                for (Object m : nested.bodyDeclarations()) {
                    if (m instanceof MethodDeclaration method) {
                        declaredInBuilder.add(signature(method.getName().getIdentifier(), method.parameters().size()));
                    }
                }
            }
        }
        if (!builderDeclared && graph.node(builderId).filter(Node::declared).isEmpty()) {
            List<String> modifiers = new ArrayList<>(List.of("public", "static"));
            if (Lombok.SUPER_BUILDER.equals(lombok)) {
                modifiers.add("abstract");
            }
            graph.declare(Node.builder(builderId, NodeKind.JAVA_TYPE).name(builderSimple).qualifiedName(builderName).location(location)
                    .attr("typeKind", "class").attr("modifiers", modifiers).attr("typeParameters", List.of())
                    .attr("synthetic", true).attr("lombok", lombok).build());
            graph.addEdge(Edge.of(Ids.javaType(typeName), builderId, EdgeKind.CONTAINS));
        }

        String builderMethod = Lombok.string(anno, "builderMethodName", "builder");
        if (!builderMethod.isEmpty() && !declared(builderMethod, 0)) {
            method(typeName, builderMethod, List.of(), builderSimple, builderName, false, List.of("public", "static"), lombok, location);
        }
        if (Lombok.flag(anno, "toBuilder", false) && !declared("toBuilder", 0)) {
            method(typeName, "toBuilder", List.of(), builderSimple, builderName, false, List.of("public"), lombok, location);
        }

        String prefix = Lombok.string(anno, "setterPrefix", "");
        for (Field f : fields) {
            SourceLocation at = onMember ? location : f.location;
            if (f.has(Lombok.SINGULAR)) {
                singular(builderName, builderSimple, f, prefix, lombok, at, declaredInBuilder);
                continue;
            }
            String name = prefix.isEmpty() ? f.name : Lombok.accessorName(prefix, f.name);
            if (!declaredInBuilder.contains(signature(name, 1))) {
                method(builderName, name, List.of(param(f)), builderSimple, builderName, true, List.of("public"), lombok, at);
            }
        }
        String buildMethod = Lombok.string(anno, "buildMethodName", "build");
        if (!declaredInBuilder.contains(signature(buildMethod, 0))) {
            method(builderName, buildMethod, List.of(), targetDisplay, targetChain, false, List.of("public"), lombok, location);
        }
    }

    /** {@code @Singular}: 원소 하나를 더하는 메서드, 컬렉션 전체를 더하는 메서드, 비우는 메서드. */
    private void singular(String builderName, String builderSimple, Field f, String prefix, String lombok, SourceLocation location,
                          Set<String> declaredInBuilder) {
        ITypeBinding binding = f.type.resolveBinding();
        boolean resolved = binding != null && !binding.isRecovered();
        boolean map = resolved && StructureExtractor.isSubtypeOf(binding, "java.util.Map");
        ITypeBinding[] arguments = resolved ? binding.getTypeArguments() : new ITypeBinding[0];

        String plural = prefix.isEmpty() ? f.name : Lombok.accessorName(prefix, f.name);
        String whole = map ? "java.util.Map" : "java.util.Collection";
        if (!declaredInBuilder.contains(signature(plural, 1))) {
            method(builderName, plural, List.of(new Param(f.name, display(f), whole)), builderSimple, builderName, true,
                    List.of("public"), lombok, location);
        }
        String clear = Lombok.accessorName("clear", f.name);
        if (!declaredInBuilder.contains(signature(clear, 0))) {
            method(builderName, clear, List.of(), builderSimple, builderName, true, List.of("public"), lombok, location);
        }
        String explicit = Lombok.string(f.annos.get(Lombok.SINGULAR), "value", "");
        String single = explicit.isEmpty() ? Lombok.singularize(f.name) : explicit;
        if (single == null) {
            return;
        }
        List<Param> params = new ArrayList<>();
        int expected = map ? 2 : 1;
        for (int i = 0; i < expected; i++) {
            ITypeBinding argument = arguments.length == expected ? bound(arguments[i]) : null;
            String name = map ? (i == 0 ? "key" : "value") : single;
            params.add(argument == null ? new Param(name, "Object", "java.lang.Object")
                    : new Param(name, argument.getName(), project.binary(argument)));
        }
        String name = prefix.isEmpty() ? single : Lombok.accessorName(prefix, single);
        if (!declaredInBuilder.contains(signature(name, params.size()))) {
            method(builderName, name, params, builderSimple, builderName, true, List.of("public"), lombok, location);
        }
    }

    /** 와일드카드 타입 인자는 그 상한으로 본다. 정할 수 없으면 null. */
    private static ITypeBinding bound(ITypeBinding argument) {
        if (argument.isWildcardType() || argument.isCapture()) {
            ITypeBinding bound = argument.getBound();
            return bound != null && argument.isUpperbound() ? bound : null;
        }
        return argument.isTypeVariable() ? null : argument;
    }
}
