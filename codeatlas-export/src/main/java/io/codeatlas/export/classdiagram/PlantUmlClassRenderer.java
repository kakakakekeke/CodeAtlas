package io.codeatlas.export.classdiagram;

import io.codeatlas.export.classdiagram.ClassDiagramModel.Member;
import io.codeatlas.export.classdiagram.ClassDiagramModel.Relation;
import io.codeatlas.export.classdiagram.ClassDiagramModel.TypeBox;
import io.codeatlas.export.common.Texts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * PlantUML class 문법 렌더러. 타입은 {@code class "표시 이름" as 별칭}으로 쓰고, 별칭은 바이너리 이름의 {@code .}과 {@code $}를
 * {@code _}로 바꾼 것이다. 패키지 묶음은 {@code package "이름" { }}다.
 */
public final class PlantUmlClassRenderer {

    private PlantUmlClassRenderer() {
    }

    public static String render(ClassDiagramModel model, ClassDiagramOptions options) {
        StringBuilder out = new StringBuilder("@startuml\n");
        out.append("' CodeAtlas 클래스 다이어그램: 타입 ").append(model.types().size()).append("개, 관계 ")
                .append(model.relations().size()).append("개\n");
        for (String warning : model.warnings()) {
            out.append("' 경고: ").append(Texts.oneLine(warning)).append('\n');
        }
        Map<String, String> aliases = aliases(model);
        if (options.groupByPackage()) {
            Map<String, List<TypeBox>> byPackage = new TreeMap<>();
            for (TypeBox t : model.types()) {
                byPackage.computeIfAbsent(t.packageName(), k -> new ArrayList<>()).add(t);
            }
            for (Map.Entry<String, List<TypeBox>> e : byPackage.entrySet()) {
                if (e.getKey().isEmpty()) {
                    e.getValue().forEach(t -> type(out, t, aliases.get(t.id()), ""));
                    continue;
                }
                out.append("package \"").append(text(e.getKey())).append("\" {\n");
                e.getValue().forEach(t -> type(out, t, aliases.get(t.id()), "  "));
                out.append("}\n");
            }
        } else {
            model.types().forEach(t -> type(out, t, aliases.get(t.id()), ""));
        }
        for (Relation r : model.relations()) {
            String from = aliases.get(r.from());
            String to = aliases.get(r.to());
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
                case INJECTS -> out.append(from).append(" ..> ").append(to).append(" : <<inject>>");
                default -> out.append(from).append(" ..> ").append(to);
            }
            out.append('\n');
        }
        out.append("@enduml\n");
        return out.toString();
    }

    private static void type(StringBuilder out, TypeBox t, String alias, String indent) {
        String keyword = switch (t.typeKind()) {
            case "interface" -> "interface";
            case "enum" -> "enum";
            case "annotation" -> "annotation";
            default -> t.isAbstract() ? "abstract class" : "class";
        };
        String title = t.displayName();
        if (!t.typeParameters().isEmpty()) {
            title += "<" + String.join(", ", t.typeParameters()) + ">";
        }
        out.append(indent).append(keyword).append(" \"").append(text(title)).append("\" as ").append(alias);
        if ("record".equals(t.typeKind())) {
            out.append(" <<record>>");
        }
        if (t.stereotype() != null) {
            out.append(" <<").append(MermaidClassRenderer.stereotypeLabel(t.stereotype())).append(">>");
        }
        if (t.nameOnly()) {
            out.append(" <<external>>");
        }
        out.append(" {\n");
        for (Member f : t.fields()) {
            out.append(indent).append("  ").append(f.visibility()).append(' ').append(f.isStatic() ? "{static} " : "")
                    .append(text(f.name()));
            if (f.type() != null) {
                out.append(" : ").append(text(f.type()));
            }
            out.append('\n');
        }
        for (Member m : t.methods()) {
            out.append(indent).append("  ").append(m.visibility()).append(' ').append(m.isStatic() ? "{static} " : "")
                    .append(m.isAbstract() ? "{abstract} " : "").append(text(m.name())).append('(');
            for (int i = 0; i < m.parameterTypes().size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                if (!m.parameterNames().isEmpty()) {
                    out.append(text(m.parameterNames().get(i))).append(" : ");
                }
                out.append(text(m.parameterTypes().get(i)));
            }
            out.append(')');
            if (m.type() != null) {
                out.append(" : ").append(text(m.type()));
            }
            out.append('\n');
        }
        out.append(indent).append("}\n");
    }

    private static Map<String, String> aliases(ClassDiagramModel model) {
        Map<String, String> aliases = new HashMap<>();
        Set<String> used = new HashSet<>();
        for (TypeBox t : model.types()) {
            StringBuilder alias = new StringBuilder();
            for (int i = 0; i < t.binaryName().length(); i++) {
                char c = t.binaryName().charAt(i);
                alias.append(Character.isLetterOrDigit(c) || c == '_' ? c : '_');
            }
            if (alias.isEmpty() || Character.isDigit(alias.charAt(0))) {
                alias.insert(0, "T_");
            }
            String candidate = alias.toString();
            for (int n = 2; !used.add(candidate); n++) {
                candidate = alias + "_" + n;
            }
            aliases.put(t.id(), candidate);
        }
        return aliases;
    }

    private static String text(String s) {
        return Texts.oneLine(s).replace('{', '(').replace('}', ')').replace('"', '\'');
    }
}
