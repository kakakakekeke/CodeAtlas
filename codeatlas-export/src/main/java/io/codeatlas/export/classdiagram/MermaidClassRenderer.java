package io.codeatlas.export.classdiagram;

import io.codeatlas.export.classdiagram.ClassDiagramModel.Member;
import io.codeatlas.export.classdiagram.ClassDiagramModel.Relation;
import io.codeatlas.export.classdiagram.ClassDiagramModel.TypeBox;
import io.codeatlas.export.common.Texts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Mermaid {@code classDiagram} 렌더러.
 *
 * <p>문법 제약과 대응:
 * <ul>
 * <li>클래스 식별자는 문자·숫자·{@code _}만 쓴다. 단순 이름이 겹치면 전체 이름으로 만들고, 식별자가 표시 이름과 다르면
 *     {@code class id["표시 이름"]} 라벨을 붙인다(제네릭 타입은 라벨 대신 {@code ~T~}).</li>
 * <li>멤버 안의 제네릭 {@code <>}는 {@code ~}로, 중괄호는 소괄호로 바꾼다.</li>
 * <li>namespace 이름은 패키지의 {@code .}을 {@code _}로 바꾼 것이다. namespace 안에는 클래스 정의만, 관계는 밖에 쓴다.</li>
 * </ul>
 */
public final class MermaidClassRenderer {

    private static final Set<String> RESERVED = Set.of("class", "namespace", "end", "note", "direction", "link", "click",
            "callback", "style", "classDef", "cssClass", "href", "call", "classDiagram", "for", "as", "of");

    private MermaidClassRenderer() {
    }

    public static String render(ClassDiagramModel model, ClassDiagramOptions options) {
        StringBuilder out = new StringBuilder("classDiagram\n");
        out.append("    %% CodeAtlas 클래스 다이어그램: 타입 ").append(model.types().size()).append("개, 관계 ")
                .append(model.relations().size()).append("개\n");
        for (String warning : model.warnings()) {
            out.append("    %% 경고: ").append(Texts.oneLine(warning)).append('\n');
        }
        Map<String, String> ids = identifiers(model);
        if (options.groupByPackage()) {
            Map<String, List<TypeBox>> byPackage = new TreeMap<>();
            for (TypeBox t : model.types()) {
                byPackage.computeIfAbsent(t.packageName(), k -> new ArrayList<>()).add(t);
            }
            for (Map.Entry<String, List<TypeBox>> e : byPackage.entrySet()) {
                if (e.getKey().isEmpty()) {
                    e.getValue().forEach(t -> type(out, t, ids.get(t.id()), "    "));
                    continue;
                }
                out.append("    namespace ").append(e.getKey().replaceAll("[^A-Za-z0-9_]", "_")).append(" {\n");
                e.getValue().forEach(t -> type(out, t, ids.get(t.id()), "        "));
                out.append("    }\n");
            }
        } else {
            model.types().forEach(t -> type(out, t, ids.get(t.id()), "    "));
        }
        for (Relation r : model.relations()) {
            String from = ids.get(r.from());
            String to = ids.get(r.to());
            out.append("    ");
            switch (r.kind()) {
                case EXTENDS -> out.append(to).append(" <|-- ").append(from);
                case IMPLEMENTS -> out.append(to).append(" <|.. ").append(from);
                case ASSOCIATES -> {
                    out.append(from).append(" --> ");
                    if (r.multiplicity() != null) {
                        out.append('"').append(text(r.multiplicity())).append("\" ");
                    }
                    out.append(to);
                    if (r.label() != null) {
                        out.append(" : ").append(text(r.label()));
                    }
                }
                case INJECTS -> out.append(from).append(" ..> ").append(to).append(" : inject");
                default -> out.append(from).append(" ..> ").append(to);
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static void type(StringBuilder out, TypeBox t, String id, String indent) {
        out.append(indent).append("class ").append(id);
        if (!t.typeParameters().isEmpty()) {
            List<String> params = t.typeParameters().stream().map(p -> text(p.strip().split("\\s+")[0]).replace("~", "")).toList();
            out.append('~').append(String.join(", ", params)).append('~');
        } else if (!id.equals(t.displayName())) {
            out.append("[\"").append(text(t.displayName())).append("\"]");
        }
        List<String> lines = new ArrayList<>();
        if (!t.nameOnly()) {
            lines.addAll(annotations(t));
            for (Member f : t.fields()) {
                lines.add(f.visibility() + (f.type() == null ? "" : generics(f.type()) + " ") + text(f.name()) + (f.isStatic() ? "$" : ""));
            }
            for (Member m : t.methods()) {
                StringBuilder line = new StringBuilder().append(m.visibility()).append(text(m.name())).append('(');
                for (int i = 0; i < m.parameterTypes().size(); i++) {
                    if (i > 0) {
                        line.append(", ");
                    }
                    line.append(generics(m.parameterTypes().get(i)));
                    if (!m.parameterNames().isEmpty()) {
                        line.append(' ').append(text(m.parameterNames().get(i)));
                    }
                }
                line.append(')');
                if (m.type() != null) {
                    line.append(' ').append(generics(m.type()));
                }
                line.append(m.isAbstract() ? "*" : m.isStatic() ? "$" : "");
                lines.add(line.toString());
            }
        }
        if (lines.isEmpty()) {
            out.append('\n');
            return;
        }
        out.append(" {\n");
        for (String line : lines) {
            out.append(indent).append("    ").append(line).append('\n');
        }
        out.append(indent).append("}\n");
    }

    static List<String> annotations(TypeBox t) {
        List<String> lines = new ArrayList<>();
        switch (t.typeKind()) {
            case "interface" -> lines.add("<<interface>>");
            case "enum" -> lines.add("<<enumeration>>");
            case "record" -> lines.add("<<record>>");
            case "annotation" -> lines.add("<<annotation>>");
            default -> {
                if (t.isAbstract()) {
                    lines.add("<<abstract>>");
                }
            }
        }
        if (t.stereotype() != null) {
            lines.add("<<" + stereotypeLabel(t.stereotype()) + ">>");
        }
        return lines;
    }

    /** {@code service} → {@code Service}. */
    static String stereotypeLabel(String stereotype) {
        String s = stereotype.replaceAll("[<>{}\"\\s]", "");
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /** 타입 ID → Mermaid 클래스 식별자. */
    private static Map<String, String> identifiers(ClassDiagramModel model) {
        Map<String, Integer> counts = new HashMap<>();
        for (TypeBox t : model.types()) {
            counts.merge(sanitize(t.displayName()), 1, Integer::sum);
        }
        Map<String, String> ids = new HashMap<>();
        Set<String> used = new HashSet<>();
        for (TypeBox t : model.types()) {
            String base = sanitize(t.displayName());
            if (counts.get(base) > 1) {
                base = sanitize(t.binaryName());
            }
            if (RESERVED.contains(base)) {
                base = base + "_";
            }
            String candidate = base;
            for (int n = 2; !used.add(candidate); n++) {
                candidate = base + "_" + n;
            }
            ids.put(t.id(), candidate);
        }
        return ids;
    }

    private static String sanitize(String name) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            out.append(Character.isLetterOrDigit(c) || c == '_' ? c : '_');
        }
        return out.isEmpty() ? "_" : out.toString();
    }

    private static String generics(String type) {
        return text(type).replace('<', '~').replace('>', '~');
    }

    /** 멤버·라벨에 쓸 수 없는 문자를 바꾼다. */
    private static String text(String s) {
        return Texts.oneLine(s).replace('{', '(').replace('}', ')').replace('"', '\'');
    }
}
