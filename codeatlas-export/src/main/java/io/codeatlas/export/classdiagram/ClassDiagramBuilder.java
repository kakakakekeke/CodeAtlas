package io.codeatlas.export.classdiagram;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.export.classdiagram.ClassDiagramModel.Member;
import io.codeatlas.export.classdiagram.ClassDiagramModel.Relation;
import io.codeatlas.export.classdiagram.ClassDiagramModel.TypeBox;
import io.codeatlas.export.classdiagram.ClassDiagramOptions.MemberLevel;
import io.codeatlas.export.common.Texts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 그래프에서 {@link ClassDiagramModel}을 만든다. JAVA_TYPE, JAVA_FIELD, JAVA_METHOD, CONTAINS와 타입 사이 관계 엣지만 읽는다.
 * 속성이 빠진 노드는 ID에서 알 수 있는 것만으로 그린다.
 */
public final class ClassDiagramBuilder {

    private static final EdgeKind[] SUPERTYPE_KINDS = {EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS};

    private final GraphModel graph;
    private final ClassDiagramOptions options;
    /** 시작 타입 이름 중 후보에서 찾지 못한 것(준 순서). */
    private final Set<String> unresolvedNames = new LinkedHashSet<>();

    private ClassDiagramBuilder(GraphModel graph, ClassDiagramOptions options) {
        this.graph = graph;
        this.options = options;
    }

    public static ClassDiagramModel build(GraphModel graph, ClassDiagramOptions options) {
        return new ClassDiagramBuilder(graph, options == null ? ClassDiagramOptions.defaults() : options).build();
    }

    private ClassDiagramModel build() {
        Map<String, Node> candidates = new TreeMap<>();
        for (Node node : graph.nodes(NodeKind.JAVA_TYPE)) {
            if (eligible(node)) {
                candidates.put(node.id(), node);
            }
        }
        Set<String> scope = scope(candidates);
        EdgeKind[] kinds = options.relationKinds().toArray(new EdgeKind[0]);

        // 범위 안 타입의 external 상위 타입(이름만).
        Set<String> nameOnly = new LinkedHashSet<>();
        if (options.showExternalSupertypes()) {
            for (String id : scope) {
                for (Edge e : graph.outgoing(id, SUPERTYPE_KINDS)) {
                    if (options.relationKinds().contains(e.kind()) && !scope.contains(e.to()) && isExternal(e.to())) {
                        nameOnly.add(e.to());
                    }
                }
            }
        }

        List<String> warnings = new ArrayList<>();
        boolean omitMembers = options.members() == MemberLevel.NONE;
        if (options.maxTypes() > 0 && scope.size() > options.maxTypes()) {
            if (!omitMembers) {
                warnings.add("타입 " + scope.size() + "개가 상한 " + options.maxTypes() + "개를 넘어 멤버를 생략했다.");
            }
            omitMembers = true;
        }

        List<TypeBox> types = new ArrayList<>();
        for (String id : scope) {
            types.add(typeBox(id, candidates.get(id), false, omitMembers));
        }
        for (String id : nameOnly) {
            types.add(typeBox(id, graph.node(id).orElse(null), true, true));
        }
        types.sort(Comparator.comparing(TypeBox::id));

        Set<String> boxes = new HashSet<>(scope);
        boxes.addAll(nameOnly);
        Set<Relation> relations = new LinkedHashSet<>();
        if (kinds.length > 0) {
            for (String id : scope) {
                for (Edge e : graph.outgoing(id, kinds)) {
                    boolean supertype = e.kind() == EdgeKind.EXTENDS || e.kind() == EdgeKind.IMPLEMENTS;
                    if (scope.contains(e.to()) || (supertype && nameOnly.contains(e.to()))) {
                        relations.add(new Relation(e.from(), e.to(), e.kind(), blankToNull(e.attrString("field")),
                                blankToNull(e.attrString("multiplicity"))));
                    }
                }
            }
        }
        List<Relation> sorted = new ArrayList<>(relations);
        sorted.sort(Comparator.comparing(Relation::from).thenComparing(Relation::to).thenComparing(r -> r.kind().name())
                .thenComparing(r -> Texts.nullToEmpty(r.label())).thenComparing(r -> Texts.nullToEmpty(r.multiplicity())));
        return new ClassDiagramModel(types, sorted, warnings, List.copyOf(unresolvedNames));
    }

    /** external·익명·중첩 옵션을 통과하는 타입인지. */
    private boolean eligible(Node type) {
        String binaryName = Ids.remainder(type.id());
        if (!options.includeExternal() && isExternal(type)) {
            return false;
        }
        if (!options.includeAnonymous() && isAnonymousOrLocal(type, binaryName)) {
            return false;
        }
        return options.includeNested() || binaryName.indexOf('$') < 0;
    }

    private boolean isExternal(String typeId) {
        return graph.node(typeId).map(ClassDiagramBuilder::isExternal).orElse(true);
    }

    static boolean isExternal(Node type) {
        return type.kind() != NodeKind.JAVA_TYPE || type.attrBoolean("external") || !type.declared();
    }

    /** 바이너리 이름의 {@code $} 뒤 조각이 숫자로 시작하면 익명 또는 로컬 타입이다. */
    static boolean isAnonymousOrLocal(Node type, String binaryName) {
        if (type.attrBoolean("anonymous") || type.attrBoolean("local")) {
            return true;
        }
        for (int i = binaryName.indexOf('$'); i >= 0; i = binaryName.indexOf('$', i + 1)) {
            if (i + 1 < binaryName.length() && Character.isDigit(binaryName.charAt(i + 1))) {
                return true;
            }
        }
        return false;
    }

    private Set<String> scope(Map<String, Node> candidates) {
        boolean byPackage = options.packagePrefix() != null;
        boolean byTypes = !options.types().isEmpty();
        if (!byPackage && !byTypes) {
            return new LinkedHashSet<>(candidates.keySet());
        }
        Set<String> reached = new HashSet<>();
        if (byPackage) {
            String prefix = options.packagePrefix().strip();
            for (String id : candidates.keySet()) {
                String pkg = Texts.packageOf(Ids.remainder(id));
                if (pkg.equals(prefix) || (options.includeSubpackages() && (prefix.isEmpty() || pkg.startsWith(prefix + ".")))) {
                    reached.add(id);
                }
            }
        }
        if (byTypes) {
            Set<String> seeds = new LinkedHashSet<>();
            for (String name : options.types()) {
                List<String> resolved = resolve(name, candidates);
                seeds.addAll(resolved);
                if (resolved.isEmpty() && name != null && !name.isBlank()) {
                    unresolvedNames.add(name.strip());
                }
            }
            reached.addAll(seeds);
            EdgeKind[] kinds = options.relationKinds().toArray(new EdgeKind[0]);
            Set<String> frontier = seeds;
            for (int depth = 0; depth < options.neighborDepth() && !frontier.isEmpty() && kinds.length > 0; depth++) {
                Set<String> next = new LinkedHashSet<>();
                for (String id : frontier) {
                    for (Edge e : graph.outgoing(id, kinds)) {
                        if (candidates.containsKey(e.to()) && reached.add(e.to())) {
                            next.add(e.to());
                        }
                    }
                    for (Edge e : graph.incoming(id, kinds)) {
                        if (candidates.containsKey(e.from()) && reached.add(e.from())) {
                            next.add(e.from());
                        }
                    }
                }
                frontier = next;
            }
        }
        Set<String> ordered = new LinkedHashSet<>();
        for (String id : candidates.keySet()) {
            if (reached.contains(id)) {
                ordered.add(id);
            }
        }
        return ordered;
    }

    /** 노드 ID, 바이너리 이름, 단순 이름 순으로 맞춰 본다. */
    private static List<String> resolve(String name, Map<String, Node> candidates) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        String text = name.strip();
        if (candidates.containsKey(text)) {
            return List.of(text);
        }
        if (candidates.containsKey(Ids.javaType(text))) {
            return List.of(Ids.javaType(text));
        }
        List<String> found = new ArrayList<>();
        for (String id : candidates.keySet()) {
            String binaryName = Ids.remainder(id);
            String simple = binaryName.substring(binaryName.lastIndexOf('.') + 1);
            if (simple.equals(text) || simple.replace('$', '.').equals(text)
                    || simple.substring(simple.lastIndexOf('$') + 1).equals(text)) {
                found.add(id);
            }
        }
        return found;
    }

    private TypeBox typeBox(String id, Node node, boolean nameOnly, boolean omitMembers) {
        String binaryName = Ids.remainder(id);
        String pkg = Texts.packageOf(binaryName);
        String display = (pkg.isEmpty() ? binaryName : binaryName.substring(pkg.length() + 1)).replace('$', '.');
        String typeKind = "class";
        boolean isAbstract = false;
        String stereotype = null;
        List<String> typeParameters = List.of();
        boolean external = true;
        if (node != null && node.kind() == NodeKind.JAVA_TYPE) {
            typeKind = Texts.isBlank(node.attrString("typeKind")) ? "class" : node.attrString("typeKind").toLowerCase(Locale.ROOT);
            isAbstract = node.attrList("modifiers").contains("abstract") && "class".equals(typeKind);
            stereotype = blankToNull(node.attrString("stereotype"));
            typeParameters = node.attrList("typeParameters");
            external = isExternal(node);
        }
        List<Member> fields = new ArrayList<>();
        List<Member> methods = new ArrayList<>();
        if (!nameOnly && !omitMembers && node != null) {
            boolean implicitPublic = "interface".equals(typeKind) || "annotation".equals(typeKind);
            String simpleName = display.substring(display.lastIndexOf('.') + 1);
            for (Edge e : graph.outgoing(id, EdgeKind.CONTAINS)) {
                Node m = graph.node(e.to()).orElse(null);
                if (m == null) {
                    continue;
                }
                if (m.kind() == NodeKind.JAVA_FIELD) {
                    Member member = field(m, implicitPublic);
                    if (visible(member)) {
                        fields.add(member);
                    }
                } else if (m.kind() == NodeKind.JAVA_METHOD) {
                    Member member = method(m, implicitPublic, simpleName);
                    if (member != null && visible(member) && !(options.hideAccessors() && isAccessor(member))) {
                        methods.add(member);
                    }
                }
            }
            fields.sort(Comparator.comparing(Member::id));
            methods.sort(Comparator.comparing(Member::id));
        }
        return new TypeBox(id, binaryName, pkg, display, typeKind, isAbstract, stereotype, typeParameters, external, nameOnly,
                fields, methods);
    }

    private boolean visible(Member member) {
        return options.members() != MemberLevel.PUBLIC || member.visibility() == '+';
    }

    private static Member field(Node node, boolean implicitPublic) {
        List<String> modifiers = node.attrList("modifiers");
        String type = blankToNull(node.attrString("type"));
        return new Member(node.id(), memberName(node), visibility(modifiers, implicitPublic), modifiers.contains("static"), false,
                type == null ? null : Texts.simpleTypeText(type), List.of(), List.of(), false, false);
    }

    private static Member method(Node node, boolean implicitPublic, String typeSimpleName) {
        String name = memberName(node);
        if (name.startsWith("lambda$") || name.equals("<clinit>")) {
            return null;
        }
        List<String> modifiers = node.attrList("modifiers");
        boolean constructor = node.attrBoolean("constructor") || Ids.CONSTRUCTOR_NAME.equals(name);
        List<String> parameterTypes = node.attrs().containsKey("parameterTypes") ? node.attrList("parameterTypes")
                : parametersFromId(node.id());
        List<String> names = node.attrList("parameterNames");
        String returnType = blankToNull(node.attrString("returnType"));
        return new Member(node.id(), constructor ? typeSimpleName : name, visibility(modifiers, implicitPublic),
                modifiers.contains("static"), modifiers.contains("abstract"),
                constructor || returnType == null ? null : Texts.simpleTypeText(returnType),
                parameterTypes.stream().map(Texts::simpleTypeText).toList(),
                names.size() == parameterTypes.size() ? names : List.of(), constructor, true);
    }

    /** 멤버 ID의 {@code #} 뒤 이름. ID가 계약과 다르면 노드 이름. */
    private static String memberName(Node node) {
        String rest = Ids.remainder(node.id());
        int hash = rest.indexOf('#');
        if (hash < 0) {
            return node.name();
        }
        int paren = rest.indexOf('(', hash);
        return paren < 0 ? rest.substring(hash + 1) : rest.substring(hash + 1, paren);
    }

    /** 메서드 ID의 괄호 안을 최상위 쉼표로 나눈다. */
    static List<String> parametersFromId(String methodId) {
        int open = methodId.indexOf('(', Math.max(0, methodId.indexOf('#')));
        int close = methodId.lastIndexOf(')');
        if (open < 0 || close <= open + 1) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = open + 1; i < close; i++) {
            char c = methodId.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                result.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        result.add(current.toString().strip());
        return result;
    }

    private static char visibility(List<String> modifiers, boolean implicitPublic) {
        if (modifiers.contains("public")) {
            return '+';
        }
        if (modifiers.contains("private")) {
            return '-';
        }
        if (modifiers.contains("protected")) {
            return '#';
        }
        return implicitPublic ? '+' : '~';
    }

    /** {@code getX()}, {@code isX()}, {@code setX(v)}. */
    private static boolean isAccessor(Member m) {
        if (!m.method() || m.constructor()) {
            return false;
        }
        String n = m.name();
        int params = m.parameterTypes().size();
        return (params == 0 && prefixed(n, "get")) || (params == 0 && prefixed(n, "is")) || (params == 1 && prefixed(n, "set"));
    }

    private static boolean prefixed(String name, String prefix) {
        return name.length() > prefix.length() && name.startsWith(prefix) && !Character.isLowerCase(name.charAt(prefix.length()));
    }

    private static String blankToNull(String s) {
        return Texts.isBlank(s) ? null : s.strip();
    }
}
