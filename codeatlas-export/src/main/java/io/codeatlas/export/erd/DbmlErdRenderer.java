package io.codeatlas.export.erd;

import io.codeatlas.export.common.Texts;
import io.codeatlas.export.erd.ErdModel.Cardinality;
import io.codeatlas.export.erd.ErdModel.Column;
import io.codeatlas.export.erd.ErdModel.Entity;
import io.codeatlas.export.erd.ErdModel.Relation;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * DBML 렌더러. 식별자가 {@code [A-Za-z_][A-Za-z0-9_]*}가 아니면 큰따옴표로 감싸고, 타입은 {@code 이름} 또는
 * {@code 이름(숫자[,숫자])} 꼴이 아니면 큰따옴표로 감싼다. DBML에는 선 모양 구분이 없어 추론 관계는 줄 끝 주석으로 표시한다.
 * 컬럼을 모르는 관계는 {@code Ref}로 쓸 수 없어 주석으로 남긴다. 방향은 알지만 카디널리티를 모르는 추론 관계는 {@code 자식 > 부모}(N:1)로
 * 단정하지 않고 자식을 앞에 둔 {@code <>}로 쓰고 줄 끝 주석에 {@code 카디널리티 모름}을 적는다(방향 미상도 {@code <>}라서 주석으로 갈린다).
 */
public final class DbmlErdRenderer {

    private DbmlErdRenderer() {
    }

    public static String render(ErdModel model, ErdOptions options) {
        StringBuilder out = new StringBuilder();
        out.append("// CodeAtlas ERD: 엔티티 ").append(model.entities().size()).append("개, 관계 ").append(model.relations().size())
                .append("개\n");
        if (model.relations().stream().anyMatch(Relation::inferred)) {
            // DBML에는 필수 여부 표기가 없다: 추론 관계의 '>'·'-'를 "부모가 반드시 있다"로 읽지 않게 알린다.
            out.append("// 추론 관계(줄 끝 주석 '추론')는 제약이 없어 부모가 반드시 있는지 알 수 없다.\n");
        }
        if (ErdLabels.systemCatalogNote(model) != null) {
            out.append("// ").append(ErdLabels.systemCatalogNote(model)).append('\n');
        }
        for (Entity e : model.entities()) {
            out.append("Table ").append(identifier(e.name()));
            String note = tableNote(e, options);
            if (!note.isEmpty()) {
                out.append(" [note: ").append(string(note)).append(']');
            }
            out.append(" {\n");
            for (Column c : e.columns()) {
                out.append("  ").append(identifier(c.name())).append(' ').append(type(c.dataType()));
                List<String> settings = new ArrayList<>();
                if (c.primaryKey()) {
                    settings.add("pk");
                }
                if (c.unique() && !c.primaryKey()) {
                    settings.add("unique");
                }
                if (Boolean.FALSE.equals(c.nullable())) {
                    settings.add("not null");
                }
                if (c.referencedOnly()) {
                    settings.add("note: " + string(ErdLabels.REFERENCED_COLUMN));
                } else if (options.showComments() && !Texts.isBlank(c.comment())) {
                    settings.add("note: " + string(c.comment()));
                }
                if (!settings.isEmpty()) {
                    out.append(" [").append(String.join(", ", settings)).append(']');
                }
                out.append('\n');
            }
            out.append("}\n");
        }
        for (Relation r : model.relations()) {
            Entity child = model.entities().stream().filter(e -> e.id().equals(r.childId())).findFirst().orElse(null);
            Entity parent = model.entities().stream().filter(e -> e.id().equals(r.parentId())).findFirst().orElse(null);
            boolean writable = child != null && parent != null && !r.columns().isEmpty()
                    && r.columns().size() == r.refColumns().size()
                    && r.columns().stream().allMatch(n -> child.column(n).isPresent())
                    && r.refColumns().stream().allMatch(n -> parent.column(n).isPresent());
            if (!writable) {
                String reason = r.columns().isEmpty() || r.columns().size() != r.refColumns().size() ? "컬럼 미상" : "컬럼 생략";
                out.append("// Ref(").append(reason).append("): ").append(r.child()).append(" > ").append(r.parent());
                if (r.inferred()) {
                    out.append(" (").append(ErdLabels.inferredNote(r)).append(')');
                }
                out.append('\n');
                continue;
            }
            out.append("Ref");
            if (!r.inferred() && r.constraint() != null) {
                out.append(' ').append(identifier(r.constraint()));
            }
            out.append(": ").append(endpoint(r.child(), r.columns())).append(' ').append(operator(r)).append(' ')
                    .append(endpoint(r.parent(), r.refColumns()));
            if (r.inferred()) {
                out.append(" // ").append(ErdLabels.inferredNote(r));
            } else if (r.fromUpgrade()) {
                out.append(" // ").append(ErdLabels.FROM_UPGRADE);
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String tableNote(Entity e, ErdOptions options) {
        List<String> parts = new ArrayList<>();
        if (!e.declared()) {
            parts.add(ErdLabels.UNDECLARED + " (SQL에서만 참조)");
        }
        if (ErdLabels.isView(e)) {
            parts.add("VIEW");
        }
        if (options.showComments() && !Texts.isBlank(e.comment())) {
            parts.add(e.comment());
        }
        return String.join(" / ", parts);
    }

    private static String operator(Relation r) {
        if (!r.directionKnown() || r.cardinalityUnknown()) {
            return "<>";
        }
        return r.childSide() == Cardinality.MANY ? ">" : "-";
    }

    private static String endpoint(String table, List<String> columns) {
        String cols = columns.size() == 1 ? identifier(columns.get(0))
                : columns.stream().map(DbmlErdRenderer::identifier).collect(Collectors.joining(", ", "(", ")"));
        return identifier(table) + "." + cols;
    }

    static String identifier(String name) {
        if (name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return name;
        }
        return '"' + Texts.oneLine(name).replace('"', '\'') + '"';
    }

    static String type(String dataType) {
        if (Texts.isBlank(dataType)) {
            return ErdLabels.UNKNOWN_TYPE;
        }
        String s = Texts.oneLine(dataType);
        if (s.matches("[A-Za-z_][A-Za-z0-9_]*(\\(\\d+(,\\d+)?\\))?")) {
            return s;
        }
        return '"' + s.replace('"', '\'') + '"';
    }

    /** 작은따옴표 문자열. */
    private static String string(String s) {
        return "'" + Texts.oneLine(s).replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
