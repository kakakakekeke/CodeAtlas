package io.codeatlas.java;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;

import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F-004 마무리. 모든 파일의 빈 정의를 본 뒤 주입 지점마다 INJECTS를 만들고,
 * 대상이 인터페이스·추상 타입이면 구현 빈 후보를 {@code attrs.candidates}에 남긴다.
 * 다른 메서드를 부르는 반환식의 뷰 이름(F-004-R14)과 {@code @ModelAttribute} 메서드로의 호출(F-004-R15)도 여기서 잇는다.
 */
final class SpringLinker {

    private final JavaProject project;

    SpringLinker(JavaProject project) {
        this.project = project;
    }

    void run() {
        declareStereotypeBeans();
        for (SpringModel.Injection injection : project.spring.injections) {
            if (injection.target.isUnresolved()) {
                project.graph.addUnresolvedEdge(injection.fromId, EdgeKind.INJECTS, JavaProject.CAT_TYPE, injection.target.name(), injection.location);
                continue;
            }
            Edge.Builder b = Edge.builder(injection.fromId, project.typeId(injection.target), EdgeKind.INJECTS)
                    .location(injection.location).attr("via", injection.via).attr("field", injection.field);
            if (injection.qualifier != null) {
                b.attr("qualifier", injection.qualifier);
            }
            if (!injection.required) {
                b.attr("required", false);
            }
            List<String> candidates = candidates(injection);
            // 후보가 주입 대상 자신뿐이면(@Mapper 인터페이스 등) 남길 정보가 없다
            if (!candidates.isEmpty() && !candidates.equals(List.of(injection.target.name()))) {
                b.attr("candidates", candidates.stream().map(Ids::javaType).toList());
            }
            project.graph.addEdge(b.build());
        }
        delegatedViews();
        modelAttributeCalls();
    }

    // ---- 이름이 같은 스테레오타입 빈 (F-004-R22) ----

    /**
     * 스테레오타입 빈 노드를 만든다. 이름이 같은 정의가 여럿이면(다른 모듈의 같은 이름 클래스, 테스트 소스) 노드의 attrs와 위치는
     * main 쪽({@code src/test/}가 아닌 것)·파일 경로순 첫 정의의 것으로 하고, 나머지는 {@code otherDefinitions}에
     * {@code <파일>:<줄> <class>}로 남긴다(springxml이 XML 빈에 하는 것과 같다). 정의마다의 DEFINES_BEAN은 이미 있다.
     */
    private void declareStereotypeBeans() {
        Map<String, List<Node>> byId = new LinkedHashMap<>();
        for (Node bean : project.spring.stereotypeBeans) {
            byId.computeIfAbsent(bean.id(), k -> new ArrayList<>()).add(bean);
        }
        byId.forEach((id, definitions) -> {
            List<Node> sorted = new ArrayList<>(definitions);
            sorted.sort(java.util.Comparator.comparing((Node n) -> isTestPath(fileOf(n))).thenComparing(SpringLinker::fileOf)
                    .thenComparingInt(n -> n.location() == null ? 0 : n.location().startLine()));
            Node first = sorted.get(0);
            if (sorted.size() > 1) {
                List<String> others = new ArrayList<>();
                for (Node other : sorted.subList(1, sorted.size())) {
                    others.add(fileOf(other) + ":" + (other.location() == null ? 0 : other.location().startLine()) + " " + other.attrString("class"));
                }
                first = first.toBuilder().attr("otherDefinitions", others).build();
            }
            project.graph.declare(first);
        });
    }

    private static String fileOf(Node node) {
        return node.location() == null ? "" : node.location().file();
    }

    private static boolean isTestPath(String file) {
        return ("/" + file).contains("/src/test/");
    }

    // ---- 다른 메서드를 부르는 반환식의 뷰 이름 (F-004-R14) ----

    /**
     * 반환식이 프로젝트 메서드 호출인 핸들러마다, 호출된 메서드가 돌려주는 뷰 이름을 전이적으로 모아 {@code viewNames}에 합친다.
     * 호출된 메서드가 값을 정하지 못하는 헬퍼이거나 본문을 볼 수 없으면(인터페이스 등) 지금처럼 미해결 엣지를 남긴다.
     */
    private void delegatedViews() {
        Map<String, SpringModel.Views> all = project.spring.views;
        all.forEach((methodId, views) -> {
            if (!views.handler || views.delegations.isEmpty()) {
                return;
            }
            // 뷰 이름 → 이 메서드 안에서 그 이름이 나오는 자리(줄:열). 자기 코드의 것이 먼저고, 합쳐 온 것은 그 호출 지점이다(F-004-R19)
            Set<String> candidates = new LinkedHashSet<>(views.candidates);
            Map<String, String> sites = new LinkedHashMap<>();
            for (String name : views.names) {
                sites.put(name, views.sites.getOrDefault(name, "0:0"));
            }
            for (SpringModel.ViewDelegation delegation : views.delegations) {
                Set<String> seen = new HashSet<>();
                seen.add(methodId);
                Set<String> returned = new LinkedHashSet<>();
                if (!returnedViews(delegation.targetId(), returned, seen, candidates, delegation.arguments())) {
                    project.graph.addUnresolvedEdge(methodId, EdgeKind.RENDERS, SpringExtractor.CAT_VIEW, delegation.text(), delegation.location());
                }
                returned.forEach(name -> sites.putIfAbsent(name, delegation.site()));
            }
            if (!sites.isEmpty()) {
                project.graph.putAttrs(methodId, Map.of("viewNames", List.copyOf(sites.keySet()), "viewNameLines", List.copyOf(sites.values())));
                if (!candidates.isEmpty()) {
                    project.graph.putAttrs(methodId, Map.of("viewNameCandidates", List.copyOf(candidates)));
                }
            }
        });
    }

    /**
     * 메서드가 돌려주는 뷰 이름을 names에 더한다.
     *
     * @return 그 메서드의 반환값을 다 설명했으면 true. 값을 정하지 못한 반환식이 남았거나 본문을 모르면 false.
     *         핸들러는 자기 미해결을 스스로 남기므로 true다
     */
    private boolean returnedViews(String methodId, Set<String> names, Set<String> seen, Set<String> candidates, List<String> arguments) {
        if (!seen.add(methodId)) {
            return true;
        }
        SpringModel.Views views = project.spring.views.get(methodId);
        if (views == null) {
            return false;
        }
        names.addAll(views.names);
        candidates.addAll(views.candidates);
        boolean settled = !views.dynamic;
        // 인자로 뷰 이름을 만드는 도우미: 호출 지점의 인자를 대입한다(F-004-R25)
        for (String template : views.parameterTemplates) {
            String name = substitute(template, arguments);
            if (name == null) {
                settled = false;
            } else {
                names.add(name);
            }
        }
        for (SpringModel.ViewDelegation delegation : views.delegations) {
            settled &= returnedViews(delegation.targetId(), names, seen, candidates, delegation.arguments());
        }
        return settled || views.handler;
    }

    /**
     * 도우미의 반환식 틀에 호출 지점의 인자를 넣는다. 상수가 아닌 인자의 자리는 {@code {*}}다.
     * 상수 조각이 하나도 남지 않으면(인자를 그대로 돌려주는 도우미에 변수를 넘겼다) 뷰 이름을 알 수 없으므로 null이다.
     */
    static String substitute(String template, List<String> arguments) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            int end = c == SpringModel.PARAMETER_MARK ? template.indexOf(SpringModel.PARAMETER_MARK, i + 1) : -1;
            if (end < 0) {
                sb.append(c);
                i++;
                continue;
            }
            int index = Integer.parseInt(template.substring(i + 1, end));
            String value = index < arguments.size() ? arguments.get(index) : null;
            sb.append(value == null ? Constants.DYNAMIC : value);
            i = end + 1;
        }
        String name = sb.toString();
        while (name.contains(Constants.DYNAMIC + Constants.DYNAMIC)) {
            name = name.replace(Constants.DYNAMIC + Constants.DYNAMIC, Constants.DYNAMIC);
        }
        return name.replace(Constants.DYNAMIC, "").isBlank() ? null : name;
    }

    // ---- @ModelAttribute 메서드 (F-004-R15) ----

    /**
     * 요청 핸들러가 있는 클래스마다, 그 클래스와 프로젝트 상위 클래스의 {@code @ModelAttribute} 메서드로
     * 각 핸들러에서 {@code CALLS}(INFERRED, {@code callType=modelAttribute})를 만든다. Spring이 매 요청 핸들러 호출 전에 부른다.
     */
    private void modelAttributeCalls() {
        if (project.spring.modelAttributes.isEmpty()) {
            return;
        }
        project.spring.handlers.forEach((typeName, handlers) -> {
            List<SpringModel.ModelAttributeMethod> methods = new ArrayList<>();
            Set<String> signatures = new HashSet<>();
            Set<String> seen = new HashSet<>();
            String type = typeName;
            while (type != null && seen.add(type) && seen.size() <= 50) {
                for (SpringModel.ModelAttributeMethod method : project.spring.modelAttributes.getOrDefault(type, List.of())) {
                    // 하위 클래스가 재정의한 메서드는 하위의 것만 불린다
                    if (signatures.add(method.signature())) {
                        methods.add(method);
                    }
                }
                type = superclass(type);
            }
            for (String handler : handlers) {
                for (SpringModel.ModelAttributeMethod method : methods) {
                    if (!handler.equals(method.methodId())) {
                        project.graph.addEdge(Edge.builder(handler, method.methodId(), EdgeKind.CALLS).inferred()
                                .location(method.location()).attr("callType", "modelAttribute").build());
                    }
                }
            }
        });
    }

    /** 프로젝트에 선언한 상위 클래스의 바이너리 이름. 없으면 null. */
    private String superclass(String typeName) {
        for (Edge e : project.graph.outgoing(Ids.javaType(typeName), EdgeKind.EXTENDS)) {
            Node target = project.graph.node(e.to()).orElse(null);
            if (target != null && target.kind() == NodeKind.JAVA_TYPE && target.declared()) {
                return Ids.remainder(e.to());
            }
        }
        return null;
    }

    /** 후보 타입의 바이너리 이름. 고르는 순서: qualifier → 필드명 → @Primary → 전부. */
    private List<String> candidates(SpringModel.Injection injection) {
        if (!injection.target.isProject() || !injection.targetAbstract) {
            return List.of();
        }
        List<SpringModel.Bean> assignable = new ArrayList<>();
        for (SpringModel.Bean bean : project.spring.beans) {
            if (bean.typeName() != null && bean.supertypes().contains(injection.target.name())) {
                assignable.add(bean);
            }
        }
        if (injection.qualifier != null) {
            return typeNames(assignable.stream().filter(b -> b.name().equals(injection.qualifier)).toList());
        }
        if (typeNames(assignable).size() > 1) {
            List<SpringModel.Bean> byName = assignable.stream().filter(b -> b.name().equals(injection.field)).toList();
            if (!byName.isEmpty()) {
                return typeNames(byName);
            }
            List<SpringModel.Bean> primary = assignable.stream().filter(SpringModel.Bean::primary).toList();
            if (typeNames(primary).size() == 1) {
                return typeNames(primary);
            }
        }
        return typeNames(assignable);
    }

    private static List<String> typeNames(List<SpringModel.Bean> beans) {
        Set<String> names = new LinkedHashSet<>();
        for (SpringModel.Bean bean : beans) {
            names.add(bean.typeName());
        }
        return List.copyOf(names);
    }
}
