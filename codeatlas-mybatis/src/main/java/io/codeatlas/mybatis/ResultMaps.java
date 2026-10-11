package io.codeatlas.mybatis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 모든 매퍼의 {@code <resultMap>} 정의. resultMap은 그래프 노드로 만들지 않고, 구문이 쓸 때 그 내용(타입, 컬럼 대응, 중첩 select)을 모아 준다.
 */
final class ResultMaps {

    /** resultColumns 속성에 담는 대응의 상한. */
    static final int MAX_COLUMNS = 200;

    record Def(MapperFile file, XmlElement element, String id) {
    }

    /**
     * 구문 하나가 쓰는 resultMap(들)에서 모은 것.
     *
     * @param types 타입 이름 원문(type, javaType, ofType, resultType). 나온 순서, 중복 없음
     * @param columns {@code COLUMN=property} 대응. 중첩된 것은 {@code COLUMN=parent.child}
     * @param nestedSelects 중첩 select가 가리키는 구문 {@code namespace.id}
     * @param missing 찾지 못한 resultMap의 정규화된 이름
     */
    record Usage(Set<String> types, List<String> columns, Set<String> nestedSelects, Set<String> missing) {
    }

    private final Map<String, Def> byQualifiedId = new HashMap<>();

    /** 정의를 등록한다. 같은 ID가 이미 있으면 먼저 등록한 것을 둔다. */
    void add(String qualifiedId, Def def) {
        byQualifiedId.putIfAbsent(qualifiedId, def);
    }

    /**
     * @param reference 구문의 resultMap 속성값(쉼표로 여러 개일 수 있다)
     * @param namespace 구문의 namespace
     */
    Usage usage(String reference, String namespace) {
        Usage usage = new Usage(new LinkedHashSet<>(), new ArrayList<>(), new LinkedHashSet<>(), new LinkedHashSet<>());
        for (String name : reference.split(",")) {
            if (!name.isBlank()) {
                collectByName(name.trim(), namespace, "", "", usage, new HashSet<>());
            }
        }
        return usage;
    }

    private void collectByName(String name, String namespace, String propertyPrefix, String columnPrefix, Usage usage,
            Set<String> visiting) {
        String qualified = name.contains(".") ? name : namespace + "." + name;
        Def def = byQualifiedId.get(qualified);
        if (def == null) {
            usage.missing().add(qualified);
            return;
        }
        if (!visiting.add(qualified + "|" + propertyPrefix)) {
            return;
        }
        String parent = def.element().attr("extends");
        if (parent != null) {
            collectByName(parent, def.file().namespace(), propertyPrefix, columnPrefix, usage, visiting);
        }
        addType(def.element().attr("type"), usage);
        walk(def.element(), def.file().namespace(), propertyPrefix, columnPrefix, usage, visiting);
    }

    private void walk(XmlElement parent, String namespace, String propertyPrefix, String columnPrefix, Usage usage,
            Set<String> visiting) {
        for (XmlElement child : parent.elements()) {
            switch (child.name) {
                case "id", "result", "idArg", "arg" -> {
                    String column = child.attr("column");
                    String property = child.attr("property") != null ? child.attr("property") : child.attr("name");
                    if (column != null && property != null && usage.columns().size() < MAX_COLUMNS) {
                        usage.columns().add(columnPrefix + column + "=" + propertyPrefix + property);
                    }
                    addType(child.attr("javaType"), usage);
                    nested(child, namespace, propertyPrefix, columnPrefix, usage, visiting);
                }
                case "association", "collection" -> {
                    addType(child.attr("javaType"), usage);
                    addType(child.attr("ofType"), usage);
                    nested(child, namespace, propertyPrefix, columnPrefix, usage, visiting);
                }
                case "constructor" -> walk(child, namespace, propertyPrefix, columnPrefix, usage, visiting);
                case "discriminator" -> {
                    addType(child.attr("javaType"), usage);
                    for (XmlElement branch : child.elements("case")) {
                        addType(branch.attr("resultType"), usage);
                        String resultMap = branch.attr("resultMap");
                        if (resultMap != null) {
                            collectByName(resultMap, namespace, propertyPrefix, columnPrefix, usage, visiting);
                        }
                        walk(branch, namespace, propertyPrefix, columnPrefix, usage, visiting);
                    }
                }
                default -> {
                }
            }
        }
    }

    /** 중첩 select, 중첩 resultMap 참조, 안에 직접 적은 대응을 따라간다. */
    private void nested(XmlElement element, String namespace, String propertyPrefix, String columnPrefix, Usage usage,
            Set<String> visiting) {
        String select = element.attr("select");
        if (select != null) {
            usage.nestedSelects().add(select.contains(".") ? select : namespace + "." + select);
        }
        String property = element.attr("property") != null ? element.attr("property") : element.attr("name");
        String childProperties = property == null ? propertyPrefix : propertyPrefix + property + ".";
        String childColumns = columnPrefix + (element.attr("columnPrefix") == null ? "" : element.attr("columnPrefix"));
        String resultMap = element.attr("resultMap");
        if (resultMap != null) {
            collectByName(resultMap, namespace, childProperties, childColumns, usage, visiting);
        }
        if (!element.elements().isEmpty()) {
            walk(element, namespace, childProperties, childColumns, usage, visiting);
        }
    }

    private static void addType(String type, Usage usage) {
        if (type != null) {
            usage.types().add(type);
        }
    }
}
