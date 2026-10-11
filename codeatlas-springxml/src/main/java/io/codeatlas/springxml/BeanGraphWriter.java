package io.codeatlas.springxml;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.springxml.BeanDef.Prop;
import io.codeatlas.springxml.BeanDef.Ref;
import io.codeatlas.springxml.BeanRegistry.EffProp;
import io.codeatlas.springxml.BeanRegistry.Resolved;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 빈 정의를 그래프에 쓴다: SPRING_BEAN 노드, DEFINES_BEAN, INJECTS, FILE attrs. */
final class BeanGraphWriter {

    /** 빈 참조의 대상. id는 java:type 또는 spring:bean 노드 ID. */
    record Target(String id, boolean isType, BeanDef def, String beanName) {
    }

    private final BeanRegistry registry;
    private final GraphModel graph;

    BeanGraphWriter(BeanRegistry registry, GraphModel graph) {
        this.registry = registry;
        this.graph = graph;
    }

    void writeBean(BeanDef def) {
        String beanId = Ids.springBean(def.name);
        Map<String, EffProp> props = registry.effectiveProps(def);
        String implClass = registry.effectiveClass(def);
        // 이름이 같은 정의가 여럿이면 노드의 attrs와 위치는 첫 정의(main 쪽, 경로순)의 것만 쓴다(F-017-R13)
        List<BeanDef> sameName = def.inner ? List.of() : registry.definitionsOf(def.name);
        if (sameName.size() > 1 && sameName.get(0) != def) {
            graph.reference(beanId, NodeKind.SPRING_BEAN, def.name);
            writeEdges(def, beanId, props, implClass);
            return;
        }

        Node.Builder node = Node.builder(beanId, NodeKind.SPRING_BEAN).name(def.name).qualifiedName(def.name).location(def.location);
        // class: 해석한 클래스. 플레이스홀더라 해석하지 못했으면 원문을 남긴다
        node.attr("class", implClass != null ? implClass : def.className);
        node.attr("scope", registry.effectiveScope(def));
        if (def.abstractBean) {
            node.attr("abstract", true);
        }
        node.attr("parent", def.parent);
        node.attr("factoryBean", def.factoryBean);
        node.attr("factoryMethod", def.factoryMethod);
        node.attr("initMethod", def.initMethod);
        node.attr("destroyMethod", def.destroyMethod);
        node.attr("autowire", def.autowire);
        node.attr("profile", def.profile);
        node.attr("element", def.element);
        if (def.primary) {
            node.attr("primary", true);
        }
        if (def.inner) {
            node.attr("inner", true);
        }
        List<String> aliases = def.inner ? List.of() : registry.aliasesOf(def);
        if (!aliases.isEmpty()) {
            node.attr("aliases", aliases);
        }
        if (implClass != null && BeanRegistry.simpleName(implClass).equals("MapperFactoryBean")) {
            node.attr("mapperInterface", registry.mapperInterface(def));
        }
        def.extraAttrs.forEach(node::attr);
        for (EffProp effective : props.values()) {
            Prop prop = effective.prop();
            if (prop.attrKey == null) {
                continue;
            }
            if (prop.items != null) {
                if (!prop.items.isEmpty()) {
                    node.attr(prop.attrKey, prop.items);
                }
            } else if (prop.scalar != null) {
                node.attr(prop.attrKey, prop.scalar);
            }
        }
        if (sameName.size() > 1) {
            List<String> others = new ArrayList<>();
            for (BeanDef other : sameName.subList(1, sameName.size())) {
                String otherClass = registry.effectiveClass(other) != null ? registry.effectiveClass(other) : other.className;
                others.add(other.file + ":" + other.location.startLine() + (otherClass == null ? "" : " " + otherClass));
            }
            node.attr("otherDefinitions", others);
        }
        graph.declare(node.build());
        writeEdges(def, beanId, props, implClass);
    }

    /** 정의 하나의 엣지: 파일의 CONTAINS, DEFINES_BEAN, 주입. 이름이 같은 정의가 여럿이어도 정의마다 만든다. */
    private void writeEdges(BeanDef def, String beanId, Map<String, EffProp> props, String implClass) {
        if (!def.inner) {
            graph.addEdge(Edge.of(Ids.file(def.file), beanId, EdgeKind.CONTAINS, def.location));
        }
        String exposed = registry.exposedType(def);
        if (exposed != null) {
            graph.addEdge(Edge.of(referenceType(exposed), beanId, EdgeKind.DEFINES_BEAN, def.location));
        }

        // 주입: 주입받는 쪽은 구현 클래스(MapperFactoryBean이면 팩터리 클래스 자신)
        boolean ownerIsBean = implClass == null;
        if (ownerIsBean && def.abstractBean) {
            // 클래스 없는 추상 빈은 템플릿이다. 상속한 자식 빈에서 엣지를 만든다
            return;
        }
        String from = ownerIsBean ? beanId : referenceType(implClass);
        for (EffProp effective : props.values()) {
            for (Ref ref : effective.prop().refs) {
                writeInjection(from, ownerIsBean, effective, ref);
            }
        }
    }

    private void writeInjection(String from, boolean ownerIsBean, EffProp effective, Ref ref) {
        if (ref.inner == null && !BeanRegistry.isLiteral(ref.beanName)) {
            // 플레이스홀더로 준 빈 이름은 알 수 없다
            graph.addUnresolvedEdge(from, EdgeKind.INJECTS, "spring-bean-ref", ref.beanName, ref.location);
            return;
        }
        Target target = target(ref);
        Edge.Builder edge = Edge.builder(from, target.id(), EdgeKind.INJECTS).location(ref.location).attr("via", "xml");
        edge.attr("property", effective.prop().property);
        if (ref.inner != null) {
            edge.attr("innerBean", true);
        } else {
            edge.attr("qualifier", ref.beanName);
        }
        if (!target.isType()) {
            edge.attr("unresolvedBeanRef", true);
        }
        if (ownerIsBean) {
            edge.attr("unresolvedBeanSource", true);
        }
        if (ref.idref) {
            edge.attr("idref", true);
        }
        edge.attr("inheritedFrom", effective.inheritedFrom());
        graph.addEdge(edge.build());
    }

    /** 참조의 대상 노드. 클래스를 XML에서 알면 java:type, 모르면 spring:bean. 대상 노드는 참조로 등록해 둔다. */
    Target target(Ref ref) {
        if (ref.inner != null) {
            String type = registry.exposedType(ref.inner);
            if (type != null) {
                return new Target(referenceType(type), true, ref.inner, ref.inner.name);
            }
            return new Target(referenceBean(ref.inner.name), false, ref.inner, ref.inner.name);
        }
        return target(ref.beanName, ref.location == null ? null : ref.location.file());
    }

    Target target(String beanName, String fromFile) {
        Resolved resolved = registry.resolve(beanName, fromFile);
        if (resolved.type() != null) {
            return new Target(referenceType(resolved.type()), true, resolved.def(), resolved.canonicalName());
        }
        return new Target(referenceBean(resolved.canonicalName()), false, resolved.def(), resolved.canonicalName());
    }

    String referenceType(String fqn) {
        String id = Ids.javaType(fqn);
        graph.reference(id, NodeKind.JAVA_TYPE, BeanRegistry.simpleName(fqn));
        return id;
    }

    private String referenceBean(String name) {
        String id = Ids.springBean(name);
        graph.reference(id, NodeKind.SPRING_BEAN, name);
        return id;
    }

    /** import, component-scan, MyBatis 연결 설정을 FILE 노드 attrs로 남긴다. */
    void writeFileAttrs(FileModel model, List<String> importedFiles) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        putList(attrs, "imports", model.imports);
        putList(attrs, "importedFiles", importedFiles);
        putList(attrs, "componentScan", model.componentScan);
        putList(attrs, "propertyLocations", model.propertyLocations);

        List<String> mybatisScan = new ArrayList<>();
        List<String> mapperLocations = new ArrayList<>();
        List<String> typeAliasesPackages = new ArrayList<>();
        String configLocation = null;
        for (BeanDef def : model.beans) {
            String type = registry.effectiveClass(def);
            if (type == null) {
                continue;
            }
            Map<String, EffProp> props = registry.effectiveProps(def);
            switch (BeanRegistry.simpleName(type)) {
                case "SqlSessionFactoryBean" -> {
                    mapperLocations.addAll(splitValues(props.get("mapperLocations")));
                    typeAliasesPackages.addAll(splitValues(props.get("typeAliasesPackage")));
                    List<String> config = splitValues(props.get("configLocation"));
                    if (configLocation == null && !config.isEmpty()) {
                        configLocation = config.get(0);
                    }
                }
                // MapperConfigurer: 전자정부프레임워크가 MapperScannerConfigurer를 상속해 만든 클래스
                case "MapperScannerConfigurer", "MapperConfigurer" -> mybatisScan.addAll(splitValues(props.get("basePackage")));
                default -> {
                    // MyBatis 설정 빈이 아니다
                }
            }
        }
        mybatisScan.addAll(model.mybatisScan);
        putList(attrs, "mybatisScan", mybatisScan);
        putList(attrs, "mybatisMapperLocations", mapperLocations);
        putList(attrs, "mybatisTypeAliasesPackage", typeAliasesPackages);
        if (configLocation != null) {
            attrs.put("mybatisConfigLocation", configLocation);
        }
        if (!attrs.isEmpty()) {
            graph.putAttrs(model.file.nodeId(), attrs);
        }
    }

    private static List<String> splitValues(EffProp effective) {
        List<String> values = new ArrayList<>();
        if (effective != null) {
            for (String value : effective.prop().strings()) {
                values.addAll(BeanParser.Names.split(value));
            }
        }
        return values;
    }

    private static void putList(Map<String, Object> attrs, String key, List<String> values) {
        if (values != null && !values.isEmpty()) {
            attrs.put(key, List.copyOf(values));
        }
    }
}
