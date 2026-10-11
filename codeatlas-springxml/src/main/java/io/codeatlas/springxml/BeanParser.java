package io.codeatlas.springxml;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.springxml.BeanDef.Prop;
import io.codeatlas.springxml.BeanDef.Ref;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** {@code <beans>} 문서를 {@link FileModel}로 읽는다. 이름 해석과 상속 병합은 하지 않는다. */
final class BeanParser {

    /** 그래프에 남길 것이 없어 조용히 넘기는 확장 요소(로컬 이름). */
    private static final Set<String> QUIET = Set.of(
            "annotation-driven", "annotation-config", "aspectj-autoproxy", "default-servlet-handler", "resources",
            "interceptors", "property-placeholder", "job-repository", "description");

    private final FileModel model;
    private final GraphModel graph;
    private final BatchParser batchParser;
    private final Set<String> reportedUnsupported = new HashSet<>();

    BeanParser(FileModel model, GraphModel graph) {
        this.model = model;
        this.graph = graph;
        this.batchParser = new BatchParser(model, this);
    }

    SourceLocation location(XmlElement e) {
        return SourceLocation.of(model.path(), e.line, e.column, e.endLine, e.endColumn);
    }

    /** 속성의 위치. 속성 위치를 모르면 요소의 위치. */
    SourceLocation location(XmlElement e, String attributeQName) {
        int[] position = e.attributePosition(attributeQName);
        if (position == null) {
            return location(e);
        }
        return SourceLocation.of(model.path(), position[0], position[1], position[0], position[1] + attributeQName.length() - 1);
    }

    void parseRoot(XmlElement root) {
        parseBeansElement(root, root.attrTrim("profile"));
    }

    private void parseBeansElement(XmlElement beans, String profile) {
        for (XmlElement child : beans.children) {
            if (child.isBeansNamespace()) {
                switch (child.localName) {
                    case "bean" -> model.beans.add(parseBean(child, false, profile));
                    case "alias" -> {
                        String name = child.attrTrim("name");
                        String alias = child.attrTrim("alias");
                        if (name != null && alias != null) {
                            model.aliases.add(new String[] {name, alias});
                        }
                    }
                    case "import" -> {
                        String resource = child.attrTrim("resource");
                        if (resource != null) {
                            model.imports.add(resource);
                        }
                    }
                    case "beans" -> {
                        String nested = child.attrTrim("profile");
                        parseBeansElement(child, nested != null ? nested : profile);
                    }
                    default -> {
                        // description 등
                    }
                }
            } else if (child.namespace.equals(Ns.BATCH)) {
                if (!batchParser.parseTopLevel(child, profile)) {
                    extension(child, profile);
                }
            } else if (child.is(Ns.CONTEXT, "component-scan")) {
                model.componentScan.addAll(Names.split(child.attr("base-package")));
            } else if (child.is(Ns.CONTEXT, "property-placeholder")) {
                model.propertyLocations.addAll(Names.split(child.attr("location")));
            } else if (child.is(Ns.MYBATIS, "scan")) {
                model.mybatisScan.addAll(Names.split(child.attr("base-package")));
            } else if (child.namespace.equals(Ns.UTIL) && isUtilCollection(child.localName)) {
                model.beans.add(parseUtilCollection(child, false, profile));
            } else if (child.is(Ns.MVC, "view-controller")) {
                viewController(child);
            } else if (child.is(Ns.AOP, "config")) {
                graph.report(Diagnostic.info(DiagnosticCodes.UNSUPPORTED_SYNTAX,
                        "aop:config는 분석하지 않는다(범위 밖)", location(child)));
            } else {
                extension(child, profile);
            }
        }
    }

    /**
     * {@code <mvc:view-controller path view-name>}(F-017-R16): 핸들러 메서드 없이 뷰를 바로 그리는 엔드포인트다.
     * {@code HTTP_ENDPOINT}({@code GET <path>}, {@code viewController=true})를 만들고 view-name을 {@code viewNames}에,
     * 선언마다의 자리와 뷰 이름을 {@code viewDeclarations}({@code <파일>:<줄>:<열> <view-name>})에 남긴다.
     * 뷰 이름을 JSP와 잇는 일(엔드포인트 → JSP_PAGE {@code RENDERS})은 링커(F-012)가 한다. {@code ROUTES_TO}는 만들지 않는다.
     */
    private void viewController(XmlElement e) {
        String path = e.attrTrim("path");
        if (path == null || path.isEmpty()) {
            return;
        }
        String normalized = io.codeatlas.core.model.Ids.normalizePath(path);
        String id = io.codeatlas.core.model.Ids.httpEndpoint("GET", normalized);
        // 이름은 java 분석기가 만드는 엔드포인트와 같은 꼴("GET <경로>")이다
        io.codeatlas.core.model.Node.Builder node = io.codeatlas.core.model.Node.builder(id, io.codeatlas.core.model.NodeKind.HTTP_ENDPOINT)
                .name("GET " + normalized).qualifiedName("GET " + normalized).location(location(e))
                .attr("httpMethod", "GET").attr("path", normalized).attr("viewController", true);
        String viewName = e.attrTrim("view-name");
        if (viewName != null && !viewName.isEmpty()) {
            // 엔드포인트 노드는 메서드와 경로가 같으면 하나다: 여러 모듈이 같은 경로를 선언하면 선언마다의 자리와 뷰 이름을 모아 둔다
            io.codeatlas.core.model.Node existing = graph.node(id).orElse(null);
            java.util.List<String> names = new ArrayList<>(existing == null ? List.of() : existing.attrList("viewNames"));
            java.util.List<String> declarations = new ArrayList<>(existing == null ? List.of() : existing.attrList("viewDeclarations"));
            if (!names.contains(viewName)) {
                names.add(viewName);
            }
            declarations.add(model.path() + ":" + e.line + ":" + e.column + " " + viewName);
            node.attr("viewNames", names).attr("viewDeclarations", declarations);
        }
        graph.declare(node.build());
    }

    /** 해석하지 않는 확장 요소. 종류마다 한 번 INFO 진단을 남기고, 그 안의 {@code <bean>}은 일반 빈으로 읽는다. */
    private void extension(XmlElement e, String profile) {
        BeanDef bean = namespaceBean(e, profile);
        if (bean != null) {
            model.beans.add(bean);
        }
        if (!QUIET.contains(e.localName) && reportedUnsupported.add(e.namespace + "|" + e.localName)) {
            graph.report(Diagnostic.info(DiagnosticCodes.UNSUPPORTED_SYNTAX,
                    "해석하지 않는 설정 요소: " + displayName(e) + " (안의 <bean>만 읽는다)", location(e)));
        }
        collectNestedBeans(e, profile);
    }

    // ---- id가 있는 확장 네임스페이스 요소 (F-017-R14) ----

    /**
     * {@code <jdbc:embedded-database id="dataSource">}처럼 {@code id}가 있는 확장 요소는 그 이름의 빈을 등록한다.
     * 요소의 내용은 해석하지 않고 빈이 있다는 것만 남긴다(다른 빈의 {@code ref}가 미해결로 떨어지지 않게).
     * 클래스는 알려진 요소의 대표 타입이고 모르면 비운다. id가 없거나 플레이스홀더면 null.
     */
    private BeanDef namespaceBean(XmlElement e, String profile) {
        String id = e.attrTrim("id");
        if (id == null || id.isEmpty() || !BeanRegistry.isLiteral(id) || e.isBeansNamespace()) {
            return null;
        }
        BeanDef def = new BeanDef();
        def.file = model.path();
        def.location = location(e);
        def.profile = profile;
        def.name = id;
        String namespace = namespaceName(e);
        def.element = namespace.isEmpty() ? e.localName : namespace + ":" + e.localName;
        String type = switch (def.element) {
            case "jdbc:embedded-database" -> "javax.sql.DataSource";
            case "jee:jndi-lookup" -> e.attrTrim("expected-type");
            case "dubbo:reference" -> e.attrTrim("interface");
            case "task:executor" -> "org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor";
            case "task:scheduler" -> "org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler";
            case "tx:advice" -> "org.springframework.transaction.interceptor.TransactionInterceptor";
            default -> null;
        };
        def.className = type == null || type.isEmpty() ? null : type;
        return def;
    }

    /** 네임스페이스 URI의 마지막 경로 조각({@code .../schema/jdbc} → {@code jdbc}). 접두사는 파일마다 달라도 같은 이름이 된다. URI가 없으면 접두사. */
    private static String namespaceName(XmlElement e) {
        String uri = e.namespace;
        while (uri.endsWith("/")) {
            uri = uri.substring(0, uri.length() - 1);
        }
        int cut = Math.max(uri.lastIndexOf('/'), uri.lastIndexOf(':'));
        String tail = cut < 0 ? uri : uri.substring(cut + 1);
        return tail.isEmpty() ? e.prefix : tail;
    }

    private static String displayName(XmlElement e) {
        if (!e.prefix.isEmpty()) {
            return e.qName();
        }
        return e.namespace.isEmpty() ? e.localName : "{" + e.namespace + "}" + e.localName;
    }

    private void collectNestedBeans(XmlElement e, String profile) {
        for (XmlElement child : e.children) {
            if (child.isBeans("bean")) {
                model.beans.add(parseBean(child, false, profile));
            } else {
                collectNestedBeans(child, profile);
            }
        }
    }

    // ---- <bean> ----

    BeanDef parseBean(XmlElement e, boolean inner, String profile) {
        BeanDef def = new BeanDef();
        def.file = model.path();
        def.location = location(e);
        def.inner = inner;
        def.profile = profile;
        if (!inner) {
            String id = e.attrTrim("id");
            List<String> names = Names.split(e.attr("name"));
            if (id != null) {
                def.name = id;
                def.aliases.addAll(names);
            } else if (!names.isEmpty()) {
                def.name = names.get(0);
                def.aliases.addAll(names.subList(1, names.size()));
            }
        }
        def.className = e.attrTrim("class");
        def.parent = e.attrTrim("parent");
        def.factoryBean = e.attrTrim("factory-bean");
        def.factoryMethod = e.attrTrim("factory-method");
        def.scope = e.attrTrim("scope");
        def.abstractBean = "true".equalsIgnoreCase(e.attrTrim("abstract"));
        def.primary = "true".equalsIgnoreCase(e.attrTrim("primary"));
        def.initMethod = e.attrTrim("init-method");
        def.destroyMethod = e.attrTrim("destroy-method");
        def.autowire = e.attrTrim("autowire");

        // p:이름="값", p:이름-ref="빈"
        for (XmlElement.Attribute a : e.attributesIn(Ns.P)) {
            boolean ref = a.localName().endsWith("-ref");
            String name = ref ? a.localName().substring(0, a.localName().length() - 4) : a.localName();
            SourceLocation at = location(e, a.qName());
            Prop prop = new Prop(name, name, "property." + name, at);
            if (ref) {
                prop.refs.add(Ref.named(a.value().strip(), at));
            } else {
                prop.scalar = a.value();
            }
            def.props.put(prop.key, prop);
        }
        // c:이름="값", c:_0="값", c:이름-ref="빈"
        for (XmlElement.Attribute a : e.attributesIn(Ns.C)) {
            boolean ref = a.localName().endsWith("-ref");
            String name = ref ? a.localName().substring(0, a.localName().length() - 4) : a.localName();
            if (name.startsWith("_")) {
                name = name.substring(1);
            }
            SourceLocation at = location(e, a.qName());
            Prop prop = constructorArg(name, at);
            if (ref) {
                prop.refs.add(Ref.named(a.value().strip(), at));
            } else {
                prop.scalar = a.value();
            }
            def.props.put(prop.key, prop);
        }

        int position = 0;
        for (XmlElement child : e.children) {
            if (!child.isBeansNamespace()) {
                continue;
            }
            switch (child.localName) {
                case "property" -> {
                    String name = child.attrTrim("name");
                    if (name != null) {
                        Prop prop = new Prop(name, name, "property." + name, location(child));
                        parseValueHolder(child, prop, def);
                        def.props.put(prop.key, prop);
                    }
                }
                case "constructor-arg" -> {
                    String name = child.attrTrim("name");
                    String index = child.attrTrim("index");
                    String label = name != null ? name : index != null ? index : String.valueOf(position++);
                    Prop prop = constructorArg(label, location(child));
                    parseValueHolder(child, prop, def);
                    def.props.put(prop.key, prop);
                }
                case "lookup-method" -> {
                    String name = child.attrTrim("name");
                    String bean = child.attrTrim("bean");
                    if (bean != null) {
                        Prop prop = new Prop("lookup-method[" + name + "]", name, null, location(child));
                        prop.refs.add(Ref.named(bean, location(child)));
                        def.props.put(prop.key, prop);
                    }
                }
                default -> {
                    // description, meta, qualifier, replaced-method
                }
            }
        }
        return def;
    }

    private static Prop constructorArg(String label, SourceLocation location) {
        return new Prop("constructor-arg[" + label + "]", "constructor-arg[" + label + "]", null, location);
    }

    /** {@code <property>}, {@code <constructor-arg>}의 값: ref·value 속성과 자식 요소. */
    private void parseValueHolder(XmlElement holder, Prop prop, BeanDef owner) {
        String ref = holder.attrTrim("ref");
        if (ref != null) {
            prop.refs.add(Ref.named(ref, location(holder)));
        }
        String value = holder.attr("value");
        if (value != null) {
            prop.scalar = value;
        }
        for (XmlElement child : holder.children) {
            parseValueElement(child, prop, owner, false);
        }
    }

    private void parseValueElement(XmlElement e, Prop prop, BeanDef owner, boolean inCollection) {
        boolean beans = e.isBeansNamespace();
        boolean util = e.namespace.equals(Ns.UTIL);
        if (!beans && !util) {
            return;
        }
        switch (e.localName) {
            case "ref" -> {
                String name = firstNonNull(e.attrTrim("bean"), e.attrTrim("local"), e.attrTrim("parent"));
                if (name != null) {
                    prop.refs.add(Ref.named(name, location(e)));
                }
            }
            case "idref" -> {
                String name = firstNonNull(e.attrTrim("bean"), e.attrTrim("local"), null);
                if (name != null) {
                    prop.refs.add(Ref.idref(name, location(e)));
                }
            }
            case "bean" -> {
                BeanDef inner = parseBean(e, true, owner.profile);
                owner.innerBeans.add(inner);
                prop.refs.add(Ref.inner(inner, location(e)));
            }
            case "value" -> {
                String text = e.text().strip();
                if (inCollection) {
                    prop.items.add(text);
                } else {
                    prop.scalar = text;
                }
            }
            case "list", "set", "array" -> {
                prop.collection();
                for (XmlElement item : e.children) {
                    parseValueElement(item, prop, owner, true);
                }
            }
            case "map" -> {
                prop.collection();
                for (XmlElement entry : e.children) {
                    if (entry.localName.equals("entry")) {
                        parseMapEntry(entry, prop, owner);
                    }
                }
            }
            case "props", "properties" -> {
                prop.collection();
                for (XmlElement p : e.children) {
                    if (p.localName.equals("prop")) {
                        prop.items.add(p.attrTrim("key") + "=" + p.text().strip());
                    }
                }
            }
            default -> {
                // null, description, meta
            }
        }
    }

    private void parseMapEntry(XmlElement entry, Prop prop, BeanDef owner) {
        String key = entry.attr("key");
        String keyRef = entry.attrTrim("key-ref");
        if (keyRef != null) {
            prop.refs.add(Ref.named(keyRef, location(entry)));
        }
        for (XmlElement child : entry.children) {
            if (child.localName.equals("key")) {
                for (XmlElement k : child.children) {
                    if (k.localName.equals("value")) {
                        key = k.text().strip();
                    } else {
                        parseValueElement(k, prop, owner, true);
                    }
                }
            }
        }
        String label = key == null ? "" : key;
        String value = entry.attr("value");
        if (value != null) {
            prop.items.add(label + "=" + value);
        }
        String valueRef = entry.attrTrim("value-ref");
        if (valueRef != null) {
            prop.refs.add(Ref.named(valueRef, location(entry)));
        }
        for (XmlElement child : entry.children) {
            if (child.localName.equals("key")) {
                continue;
            }
            if (child.localName.equals("value") && child.isBeansNamespace()) {
                prop.items.add(label + "=" + child.text().strip());
            } else {
                parseValueElement(child, prop, owner, true);
            }
        }
    }

    // ---- util:list|set|map|properties ----

    private static boolean isUtilCollection(String localName) {
        return localName.equals("list") || localName.equals("set") || localName.equals("map") || localName.equals("properties");
    }

    private BeanDef parseUtilCollection(XmlElement e, boolean inner, String profile) {
        BeanDef def = new BeanDef();
        def.file = model.path();
        def.location = location(e);
        def.inner = inner;
        def.profile = profile;
        def.element = "util:" + e.localName;
        def.utilCollection = true;
        def.name = inner ? null : e.attrTrim("id");
        def.scope = e.attrTrim("scope");
        def.generatedNameBase = "org.springframework.beans.factory.config." + switch (e.localName) {
            case "list" -> "ListFactoryBean";
            case "set" -> "SetFactoryBean";
            case "map" -> "MapFactoryBean";
            default -> "PropertiesFactoryBean";
        };
        String collectionClass = firstNonNull(e.attrTrim("list-class"), e.attrTrim("set-class"), e.attrTrim("map-class"));
        if (collectionClass != null) {
            def.extraAttrs.put("collectionClass", collectionClass);
        }
        String location = e.attrTrim("location");
        if (location != null) {
            def.extraAttrs.put("location", location);
        }
        Prop elements = new Prop("<elements>", null, "values", def.location);
        elements.collection();
        switch (e.localName) {
            case "map" -> {
                for (XmlElement entry : e.children) {
                    if (entry.localName.equals("entry")) {
                        parseMapEntry(entry, elements, def);
                    }
                }
            }
            case "properties" -> {
                for (XmlElement p : e.children) {
                    if (p.localName.equals("prop")) {
                        elements.items.add(p.attrTrim("key") + "=" + p.text().strip());
                    }
                }
            }
            default -> {
                for (XmlElement item : e.children) {
                    parseValueElement(item, elements, def, true);
                }
            }
        }
        def.props.put(elements.key, elements);
        return def;
    }

    private static String firstNonNull(String a, String b, String c) {
        return a != null ? a : b != null ? b : c;
    }

    /** 배치 요소 안의 인라인 {@code <bean>}. 내부 빈으로 읽어 파일 모델에 붙인다. */
    BeanDef parseDetachedInnerBean(XmlElement e, String profile) {
        BeanDef inner = parseBean(e, true, profile);
        model.detachedInnerBeans.add(inner);
        return inner;
    }

    /** 쉼표·세미콜론·공백으로 나눈 이름 목록 도우미. */
    static final class Names {
        private Names() {
        }

        static List<String> split(String value) {
            List<String> parts = new ArrayList<>();
            if (value == null) {
                return parts;
            }
            for (String part : value.split("[,;\\s]+")) {
                if (!part.isEmpty()) {
                    parts.add(part);
                }
            }
            return parts;
        }
    }
}
