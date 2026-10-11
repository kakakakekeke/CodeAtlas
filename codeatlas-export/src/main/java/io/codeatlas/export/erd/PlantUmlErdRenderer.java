package io.codeatlas.export.erd;

import io.codeatlas.export.common.Texts;
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
 * PlantUML entity 문법 렌더러. 엔티티는 {@code entity "표시 이름" as 별칭}으로 쓰고, 별칭은 문자·숫자·{@code _}만 남긴다.
 * NOT NULL 컬럼은 {@code *}, PK는 구분선({@code --}) 위에 둔다. 방향을 모르는 추론 관계는 카디널리티 없는 {@code ..} 선이다.
 * 방향은 알지만 카디널리티를 모르는 추론 관계({@link Relation#cardinalityUnknown()})는 카디널리티 표시 없이 자식에서 부모로 가는
 * 점선 화살표({@code 부모 <.. 자식})다.
 */
public final class PlantUmlErdRenderer {

    private PlantUmlErdRenderer() {
    }

    public static String render(ErdModel model, ErdOptions options) {
        StringBuilder out = new StringBuilder("@startuml\n");
        out.append("' CodeAtlas ERD: 엔티티 ").append(model.entities().size()).append("개, 관계 ").append(model.relations().size())
                .append("개\n");
        out.append("' 실선은 DDL 외래 키, 점선은 SQL에서 추론한 관계다. ").append(ErdLabels.INFERRED_PARENT_OPTIONAL).append('\n');
        if (model.relations().stream().anyMatch(Relation::cardinalityUnknown)) {
            out.append("' 화살표(<..)는 방향(자식 → 부모)만 DDL 외래 키로 확인하고 카디널리티는 정하지 못한 추론 관계다.\n");
        }
        if (ErdLabels.systemCatalogNote(model) != null) {
            out.append("' ").append(ErdLabels.systemCatalogNote(model)).append('\n');
        }
        out.append("hide circle\n");
        out.append("skinparam linetype ortho\n");
        Map<String, String> aliases = aliases(model);
        for (Entity e : model.entities()) {
            String title = clean(ErdLabels.displayName(e));
            if (options.showComments() && !Texts.isBlank(e.comment())) {
                title += "\\n(" + clean(e.comment()) + ")";
            }
            out.append("entity \"").append(title).append("\" as ").append(aliases.get(e.id()));
            if (!e.declared()) {
                out.append(" <<undeclared>>");
            } else if (ErdLabels.isView(e)) {
                out.append(" <<view>>");
            }
            out.append(" {\n");
            List<Column> pk = e.columns().stream().filter(Column::primaryKey).toList();
            List<Column> rest = e.columns().stream().filter(c -> !c.primaryKey()).toList();
            pk.forEach(c -> column(out, c, options));
            if (!pk.isEmpty() && !rest.isEmpty()) {
                out.append("  --\n");
            }
            rest.forEach(c -> column(out, c, options));
            out.append("}\n");
        }
        for (Relation r : model.relations()) {
            out.append(aliases.get(r.parentId())).append(' ');
            if (!r.directionKnown()) {
                out.append("..");
            } else if (r.cardinalityUnknown()) {
                // 부모 <.. 자식: 화살촉이 부모를 가리킨다(자식 → 부모). 끝에 카디널리티 표시를 두지 않는다.
                out.append("<..");
            } else {
                out.append(switch (r.parentSide()) {
                    case ZERO_OR_ONE -> "|o";
                    case MANY, UNKNOWN -> "}o";
                    case ONE -> "||";
                }).append(r.inferred() ? ".." : "--").append(switch (r.childSide()) {
                    case ZERO_OR_ONE -> "o|";
                    case ONE -> "||";
                    case MANY, UNKNOWN -> "o{";
                });
            }
            out.append(' ').append(aliases.get(r.childId()));
            String label = clean(ErdLabels.relationLabel(r));
            if (!label.isEmpty()) {
                out.append(" : ").append(label);
            }
            out.append('\n');
        }
        out.append("@enduml\n");
        return out.toString();
    }

    private static void column(StringBuilder out, Column c, ErdOptions options) {
        out.append("  ");
        if (c.notNull()) {
            out.append("* ");
        }
        out.append(clean(c.name())).append(" : ").append(Texts.isBlank(c.dataType()) ? ErdLabels.UNKNOWN_TYPE : clean(c.dataType()));
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
            out.append(" <<").append(String.join(",", keys)).append(">>");
        }
        if (options.showComments() && !Texts.isBlank(c.comment())) {
            out.append(" -- ").append(clean(c.comment()));
        }
        out.append('\n');
    }

    /** 엔티티 ID → 별칭. 겹치면 {@code _2}, {@code _3}을 붙인다. */
    private static Map<String, String> aliases(ErdModel model) {
        Map<String, String> aliases = new HashMap<>();
        Set<String> used = new HashSet<>();
        for (Entity e : model.entities()) {
            StringBuilder alias = new StringBuilder();
            for (int i = 0; i < e.name().length(); i++) {
                char c = e.name().charAt(i);
                alias.append(Character.isLetterOrDigit(c) || c == '_' ? c : '_');
            }
            if (alias.isEmpty() || Character.isDigit(alias.charAt(0))) {
                alias.insert(0, "T_");
            }
            String candidate = alias.toString();
            for (int n = 2; !used.add(candidate); n++) {
                candidate = alias + "_" + n;
            }
            aliases.put(e.id(), candidate);
        }
        return aliases;
    }

    private static String clean(String s) {
        return Texts.oneLine(s).replace('"', '\'');
    }
}
