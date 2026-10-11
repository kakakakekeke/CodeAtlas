package io.codeatlas.export.erd;

import io.codeatlas.export.common.Texts;
import io.codeatlas.export.erd.ErdModel.Cardinality;
import io.codeatlas.export.erd.ErdModel.Column;
import io.codeatlas.export.erd.ErdModel.Entity;
import io.codeatlas.export.erd.ErdModel.Relation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mermaid {@code erDiagram} 렌더러.
 *
 * <p>문법 제약과 대응:
 * <ul>
 * <li>엔티티 이름이 {@code [A-Za-z_][A-Za-z0-9_]*}가 아니면 큰따옴표 이름을 쓴다({@code "}, {@code %}, {@code \}는 바꾼다).</li>
 * <li>속성의 타입과 이름은 {@code [A-Za-z_][A-Za-z0-9_()]*}만 쓴다. 타입의 쉼표·공백은 {@code _}로 바꾸고,
 *     바뀐 컬럼 이름은 주석에 원래 이름을 남긴다.</li>
 * <li>관계에는 카디널리티가 반드시 있어야 하므로 방향을 모르는 관계는 양쪽 {@code }o}/{@code o{}로 그리고 라벨에 적는다.</li>
 * <li>방향은 알지만 카디널리티를 모르는 추론 관계({@link Relation#cardinalityUnknown()})도 끝 표시는 가장 약한 양쪽 {@code }o}/{@code o{}다
 *     (어느 쪽도 "하나"라고 단정하지 않는다). 다른 관계처럼 부모를 왼쪽에 두고 라벨에 {@code 카디널리티 모름}을 적으며, 그런 관계가 있을 때만
 *     머리 주석에 한 줄을 더 적는다.</li>
 * </ul>
 */
public final class MermaidErdRenderer {

    /** 방향만 알고 카디널리티를 모르는 추론 관계가 있을 때 머리에 더 적는 주석 줄. 중괄호는 쓰지 않는다(Mermaid가 지시문으로 볼 수 있다). */
    static final String CARDINALITY_UNKNOWN_LEGEND = "    %% '" + ErdLabels.CARDINALITY_UNKNOWN
            + "'은 방향(왼쪽이 부모)만 DDL 외래 키로 확인하고 카디널리티는 정하지 못한 추론 관계다. 양쪽 끝 표시는 N:M이라는 뜻이 아니다.\n";

    private MermaidErdRenderer() {
    }

    public static String render(ErdModel model, ErdOptions options) {
        StringBuilder out = new StringBuilder("erDiagram\n");
        long inferred = model.relations().stream().filter(Relation::inferred).count();
        out.append("    %% CodeAtlas ERD: 엔티티 ").append(model.entities().size()).append("개, 관계 ")
                .append(model.relations().size()).append("개 (추론 ").append(inferred).append("개)\n");
        out.append("    %% 실선(--)은 DDL 외래 키, 점선(..)은 SQL에서 추론한 관계다. ").append(ErdLabels.INFERRED_PARENT_OPTIONAL).append('\n');
        if (model.relations().stream().anyMatch(Relation::cardinalityUnknown)) {
            out.append(CARDINALITY_UNKNOWN_LEGEND);
        }
        if (ErdLabels.systemCatalogNote(model) != null) {
            out.append("    %% ").append(ErdLabels.systemCatalogNote(model)).append('\n');
        }
        Map<String, String> names = new HashMap<>();
        for (Entity e : model.entities()) {
            String name = entityName(e);
            names.put(e.id(), name);
            if (options.showComments() && !Texts.isBlank(e.comment())) {
                out.append("    %% ").append(Texts.oneLine(e.name())).append(": ").append(Texts.oneLine(e.comment())).append('\n');
            }
            if (e.columns().isEmpty()) {
                out.append("    ").append(name).append('\n');
                continue;
            }
            out.append("    ").append(name).append(" {\n");
            Set<String> used = new HashSet<>();
            int index = 0;
            for (Column c : e.columns()) {
                index++;
                String attrName = uniqueName(attributeName(c, index), used);
                out.append("        ").append(typeWord(c.dataType())).append(' ').append(attrName);
                List<String> keys = new ArrayList<>();
                if (c.primaryKey()) {
                    keys.add("PK");
                }
                if (c.foreignKey()) {
                    keys.add("FK");
                }
                if (c.unique() && !c.primaryKey()) {
                    keys.add("UK");
                }
                if (!keys.isEmpty()) {
                    out.append(' ').append(String.join(", ", keys));
                }
                String comment = columnComment(c, attrName, options.showComments());
                if (!comment.isEmpty()) {
                    out.append(" \"").append(comment).append('"');
                }
                out.append('\n');
            }
            out.append("    }\n");
        }
        for (Relation r : model.relations()) {
            out.append("    ").append(names.get(r.parentId())).append(' ').append(parentMarker(r)).append(r.inferred() ? ".." : "--")
                    .append(childMarker(r)).append(' ').append(names.get(r.childId())).append(" : \"")
                    .append(quoted(ErdLabels.relationLabel(r))).append("\"\n");
        }
        return out.toString();
    }

    private static String parentMarker(Relation r) {
        return switch (r.parentSide()) {
            case ZERO_OR_ONE -> "|o";
            case MANY, UNKNOWN -> "}o";
            case ONE -> "||";
        };
    }

    private static String childMarker(Relation r) {
        return switch (r.childSide()) {
            case ZERO_OR_ONE -> "o|";
            case ONE -> "||";
            case MANY, UNKNOWN -> "o{";
        };
    }

    static String entityName(Entity e) {
        // Mermaid에는 엔티티 스테레오타입이 없어 뷰도 이름 접미로 구분한다.
        String display = ErdLabels.displayName(e) + (e.declared() && ErdLabels.isView(e) ? " (VIEW)" : "");
        if (display.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return display;
        }
        String cleaned = Texts.oneLine(display).replace('"', '\'').replace('%', '_').replace('\\', '_');
        return '"' + (cleaned.isEmpty() ? "_" : cleaned) + '"';
    }

    /** 타입 단어. 쉼표와 공백 묶음은 {@code _} 하나로, 그 밖의 안전하지 않은 문자도 {@code _}로 바꾼다. */
    static String typeWord(String dataType) {
        if (Texts.isBlank(dataType)) {
            return ErdLabels.UNKNOWN_TYPE;
        }
        String s = dataType.strip().replaceAll("[,\\s]+", "_").replaceAll("[^A-Za-z0-9_()]", "_");
        s = s.replace("(_", "(").replace("_)", ")");
        return s.matches("[A-Za-z_].*") ? s : "_" + s;
    }

    /** 속성 이름. 안전한 문자만 남기고, 남는 글자가 없으면 {@code col<순번>}. */
    private static String attributeName(Column c, int index) {
        String s = c.name().replaceAll("[^A-Za-z0-9_]", "_");
        if (!s.matches(".*[A-Za-z0-9].*")) {
            return "col" + (c.ordinal() > 0 ? c.ordinal() : index);
        }
        return s.matches("[A-Za-z_].*") ? s : "_" + s;
    }

    private static String uniqueName(String name, Set<String> used) {
        String candidate = name;
        for (int n = 2; !used.add(candidate); n++) {
            candidate = name + "_" + n;
        }
        return candidate;
    }

    private static String columnComment(Column c, String attrName, boolean showComments) {
        List<String> parts = new ArrayList<>();
        if (!attrName.equals(c.name())) {
            parts.add(c.name());
        }
        if (c.notNull() && !c.primaryKey()) {
            parts.add("NOT NULL");
        }
        if (c.referencedOnly()) {
            parts.add(ErdLabels.REFERENCED_COLUMN);
        }
        if (showComments && !Texts.isBlank(c.comment())) {
            parts.add(c.comment());
        }
        return quoted(String.join(", ", parts));
    }

    /** 큰따옴표 문자열 안에 넣을 수 있게 따옴표와 줄바꿈을 없앤다. */
    private static String quoted(String s) {
        return Texts.oneLine(s).replace('"', '\'');
    }
}
