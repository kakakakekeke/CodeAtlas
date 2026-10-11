package io.codeatlas.link;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** SPRING_BEAN 노드에서 그 빈의 타입(JAVA_TYPE)을 찾는 공통 로직. */
final class BeanSupport {

    /**
     * 빈 정의 하나.
     *
     * @param typeId 빈의 타입(그래프에 있는 JAVA_TYPE)
     * @param file 정의가 적힌 파일. 모르면 null
     */
    record Definition(String typeId, String file) {
    }

    private BeanSupport() {
    }

    /**
     * 빈을 정의한 타입의 ID들(그래프에 있는 JAVA_TYPE만). 이름이 같은 정의를 전부 모은다(어느 정의인지 가리지 않는다).
     * DEFINES_BEAN의 출발점이 타입이면 그 타입, {@code @Bean} 메서드면 beanType(엣지, 메서드, 빈 순) → 빈의 class → 메서드의 returnType 순으로 찾는다.
     * DEFINES_BEAN이 없으면 빈의 class로 찾는다.
     */
    static List<String> definingTypes(GraphModel graph, String beanId) {
        Set<String> types = new LinkedHashSet<>();
        definitions(graph, beanId).forEach(d -> types.add(d.typeId()));
        return List.copyOf(types);
    }

    /**
     * 참조하는 쪽에서 본 빈의 타입(F-012-R26). 빈 이름은 프로젝트 전체에서 하나의 노드라 main·test, 서로 다른 웹 모듈의 정의가 섞여 있다:
     * 테스트 소스({@code src/test/})의 정의는 참조하는 쪽도 테스트 소스일 때만 후보이고, 참조하는 쪽과 같은 모듈 루트의 정의가 있으면 그것만 본다.
     * 그렇게 좁히고도 타입이 둘 이상이면 고를 수 없으므로 빈 목록을 돌려준다.
     *
     * @param referrerFile 빈을 참조한 파일(엣지의 위치). 모르면 null
     */
    static List<String> definingTypes(GraphModel graph, String beanId, String referrerFile, ModuleRoots modules) {
        boolean fromTest = ModuleRoots.isTestPath(referrerFile);
        List<Definition> candidates = definitions(graph, beanId).stream()
                .filter(d -> fromTest || !ModuleRoots.isTestPath(d.file())).toList();
        List<Definition> sameModule = candidates.stream().filter(d -> modules.sameModule(referrerFile, d.file())).toList();
        if (!sameModule.isEmpty()) {
            candidates = sameModule;
        }
        Set<String> types = new LinkedHashSet<>();
        candidates.forEach(d -> types.add(d.typeId()));
        return types.size() == 1 ? List.copyOf(types) : List.of();
    }

    /** 빈의 정의들(타입을 그래프에서 찾은 것만). */
    static List<Definition> definitions(GraphModel graph, String beanId) {
        Node bean = graph.node(beanId).orElse(null);
        List<Definition> definitions = new ArrayList<>();
        for (Edge defines : graph.incoming(beanId, EdgeKind.DEFINES_BEAN)) {
            Node from = graph.node(defines.from()).orElse(null);
            if (from == null) {
                continue;
            }
            String file = fileOf(from) != null ? fileOf(from) : defines.location() == null ? null : defines.location().file();
            if (from.kind() == NodeKind.JAVA_TYPE) {
                definitions.add(new Definition(from.id(), file));
            } else if (from.kind() == NodeKind.JAVA_METHOD) {
                List<String> names = new ArrayList<>();
                names.add(defines.attrString("beanType"));
                names.add(from.attrString("beanType"));
                if (bean != null) {
                    names.add(bean.attrString("beanType"));
                    names.add(bean.attrString("class"));
                }
                names.add(from.attrString("returnType"));
                for (String name : names) {
                    String typeId = typeId(graph, name);
                    if (typeId != null) {
                        definitions.add(new Definition(typeId, file));
                        break;
                    }
                }
            }
        }
        if (definitions.isEmpty() && bean != null) {
            String typeId = typeId(graph, bean.attrString("class"));
            if (typeId != null) {
                definitions.add(new Definition(typeId, fileOf(bean)));
            }
        }
        return definitions;
    }

    private static String fileOf(Node node) {
        return node.location() == null ? null : node.location().file();
    }

    /** 타입 이름에 해당하는 JAVA_TYPE 노드의 ID. 그래프에 없으면(라이브러리 타입 등) null. 제네릭 인자는 떼고 본다. */
    static String typeId(GraphModel graph, String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return null;
        }
        String name = typeName.trim();
        int generic = name.indexOf('<');
        if (generic > 0) {
            name = name.substring(0, generic).trim();
        }
        String id = Ids.javaType(name);
        return graph.node(id).filter(n -> n.kind() == NodeKind.JAVA_TYPE).map(Node::id).orElse(null);
    }
}
