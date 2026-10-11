package io.codeatlas.sql.analysis;

import io.codeatlas.core.model.DbName;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.Dialect;
import io.codeatlas.sql.SqlDiagnosticCodes;
import io.codeatlas.sql.ast.Ddl;
import io.codeatlas.sql.ast.Pos;
import io.codeatlas.sql.ast.QName;
import io.codeatlas.sql.ast.Query;
import io.codeatlas.sql.ast.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * DDL 구문을 받아 모으고(1패스), 모든 파일을 본 뒤 카탈로그에 반영해 그래프에 쓴다(2패스).
 * 2패스로 나눈 까닭: FK의 참조 컬럼이 생략되면 부모의 PK로 채우는데 부모 정의가 뒤(다른 파일)에 올 수 있고,
 * ALTER TABLE·COMMENT ON·CREATE INDEX가 노드의 attrs를 나중에 바꾸며, 같은 테이블의 정의가 방언별 폴더에 여러 벌 있으면
 * 모든 파일을 봐야 어느 것을 쓸지 정할 수 있기 때문이다(F-007-R14).
 */
public final class DdlProcessor {

    /** 뷰 정의 쿼리 또는 CREATE TABLE AS SELECT: DML 단계에서 구문 노드로 분석한다. */
    public record PendingQuery(Catalog.TableDef target, Statement statement, Query query, String file, Pos unitPos,
            String sql, Locator locator, List<String> explicitColumns) {
    }

    private record NamedObject(String name, String schema, String file, SourceLocation location, String target, boolean isPublic) {
    }

    /** 테이블·뷰에 관한 DDL 구문 하나(모든 파일을 본 뒤 어느 정의를 쓸지 정하고 적용한다). */
    private record Event(Ddl statement, String file, Locator locator, Pos unitPos, String sql) {
    }

    private final Catalog catalog;
    private final GraphModel graph;
    private final DialectSelection selection;
    private final List<PendingQuery> pending = new ArrayList<>();
    private final List<NamedObject> sequences = new ArrayList<>();
    private final List<NamedObject> synonyms = new ArrayList<>();
    private final List<Event> events = new ArrayList<>();
    /** 다른 테이블에서 컬럼을 가져오는 테이블(F-019-R20): 이름 → 그 CREATE TABLE. 정의를 모두 반영한 뒤 {@link #inheritColumns()}가 푼다. */
    private final Map<String, Derivation> derivations = new LinkedHashMap<>();

    private record Derivation(Ddl.CreateTable statement, Locator locator) {
    }
    /** 파일이 처음 나온 순서(경로순으로 들어온다). */
    private final Map<String, Integer> fileOrder = new LinkedHashMap<>();

    public DdlProcessor(Catalog catalog, GraphModel graph) {
        this(catalog, graph, DialectSelection.defaults());
    }

    /** @param optionDialect {@code --dialect}로 정한 방언. 없으면(auto) null */
    public DdlProcessor(Catalog catalog, GraphModel graph, Dialect optionDialect) {
        this(catalog, graph, new DialectSelection(optionDialect == Dialect.AUTO ? null : optionDialect, null, null));
    }

    /** @param selection 방언별 파일 여러 벌 가운데 쓸 방언의 순위. 고른 결과의 요약도 여기에 모은다(진단은 호출한 쪽이 {@code report}로 낸다) */
    public DdlProcessor(Catalog catalog, GraphModel graph, DialectSelection selection) {
        this.catalog = catalog;
        this.graph = graph;
        this.selection = selection;
    }

    public List<PendingQuery> pendingQueries() {
        return pending;
    }

    // ------------------------------------------------------------------ 1패스: 카탈로그

    /**
     * DDL 구문 하나를 받는다. 시퀀스·시노님은 바로 카탈로그에 넣고, 테이블·뷰에 관한 구문(CREATE TABLE/VIEW, ALTER TABLE, COMMENT ON,
     * CREATE INDEX)은 모아 뒀다가 {@link #finish()}에서 어느 파일의 정의를 쓸지 정한 뒤 카탈로그에 반영한다(F-007-R14).
     *
     * @param unitPos 구문 전체의 범위
     * @param sql 구문 원문(뷰·CTAS의 구문 노드 attrs.sql용)
     */
    public void apply(Statement statement, String file, Locator locator, Pos unitPos, String sql) {
        fileOrder.putIfAbsent(file, fileOrder.size());
        if (statement instanceof Ddl.Multi m) {
            m.statements().forEach(s -> apply(s, file, locator, unitPos, sql));
        } else if (statement instanceof Ddl.CreateTable || statement instanceof Ddl.AlterTable || statement instanceof Ddl.CommentOn
                || statement instanceof Ddl.CreateIndex || statement instanceof Ddl.CreateView) {
            // 원문은 구문 노드가 될 것(뷰, CTAS)만 쥔다
            boolean query = statement instanceof Ddl.CreateView || (statement instanceof Ddl.CreateTable t && t.asQuery() != null);
            events.add(new Event((Ddl) statement, file, locator, unitPos, query ? sql : null));
        } else if (statement instanceof Ddl.CreateSequence s) {
            catalog.addSequence(s.name().name());
            catalog.addSchema(s.name().qualifier());
            sequences.add(new NamedObject(s.name().name(), schemaOf(s.name()), file, locator.locate(unitPos), null, false));
        } else if (statement instanceof Ddl.CreateSynonym s) {
            catalog.addSynonym(s.name().name(), s.target().name());
            catalog.addSchema(s.target().qualifier());
            synonyms.add(new NamedObject(s.name().name(), schemaOf(s.name()), file, locator.locate(unitPos),
                    s.target().display() + (s.target().dbLink() == null ? "" : "@" + s.target().dbLink().toUpperCase(java.util.Locale.ROOT)),
                    s.isPublic()));
        }
    }

    private void createTable(Ddl.CreateTable s, String file, Locator locator, Pos unitPos, String sql) {
        String name = s.name().name();
        String schema = schemaOf(s.name());
        catalog.addSchema(schema);
        Catalog.TableDef def = catalog.tableOrCreate(name);
        if (def.declared && def.schema != null && schema != null && !def.schema.equals(schema)) {
            graph.report(Diagnostic.warn(DiagnosticCodes.DUPLICATE_OBJECT_NAME,
                    "테이블 " + name + "이(가) 서로 다른 스키마 " + def.schema + ", " + schema + "에 정의돼 있어 한 노드로 합쳐짐",
                    locator.locate(unitPos)));
        }
        if (!def.declared) {
            def.location = locator.locate(unitPos);
            def.file = file;
        }
        def.declared = true;
        def.view = false;
        if (schema != null) {
            def.schema = schema;
        }
        def.temporary |= s.temporary();
        for (Ddl.ColumnDef c : s.columns()) {
            applyColumn(def, c, locator);
        }
        for (Ddl.Constraint c : s.constraints()) {
            applyConstraint(def, c, locator);
        }
        if (s.asQuery() != null) {
            pending.add(new PendingQuery(def, s, s.asQuery(), file, unitPos, sql, locator,
                    s.columns().stream().map(c -> DbName.normalize(c.name())).collect(Collectors.toList())));
        }
        if (s.partitionOf() != null) {
            def.partitionOf = s.partitionOf().name(); // 구문에 적힌 사실이다: 부모의 정의가 없어도 남긴다
        }
        if (!s.sources().isEmpty()) {
            s.sources().forEach(source -> catalog.addSchema(source.table().qualifier()));
            derivations.put(name, new Derivation(s, locator));
        }
    }

    // ------------------------------------------------------------------ LIKE, INHERITS, PARTITION OF (F-019-R20)

    /**
     * {@code LIKE}·{@code INHERITS}·{@code PARTITION OF}로 가리킨 테이블의 컬럼을 넣는다. CREATE TABLE을 모두 반영한 뒤(원본·부모의 정의가 뒤나
     * 다른 파일에 있어도 된다), ALTER TABLE·COMMENT ON·CREATE INDEX를 반영하기 전에 부른다(가져온 컬럼에 붙는 주석·인덱스가 그 컬럼을 찾는다).
     * 그래서 가져오는 것은 원본의 CREATE TABLE에 적힌 컬럼이다: 원본에 ALTER TABLE로 더한 컬럼은 따라오지 않는다.
     * 원본·부모의 정의가 없으면 컬럼을 만들지 않고(노드도 관계도 만들지 않는다) 그 테이블의 컬럼 목록을 "다 알지 못함"으로 표시한다.
     */
    private void inheritColumns() {
        java.util.Set<String> done = new java.util.HashSet<>();
        for (String name : new ArrayList<>(derivations.keySet())) {
            inheritColumns(name, done, new java.util.HashSet<>());
        }
        derivations.clear();
    }

    private void inheritColumns(String name, java.util.Set<String> done, java.util.Set<String> visiting) {
        Derivation d = derivations.get(name);
        Catalog.TableDef def = catalog.table(name);
        if (d == null || def == null || done.contains(name) || !visiting.add(name)) {
            return; // 가져올 것이 없거나, 이미 풀었거나, 서로를 가리키는 고리다(고리면 지금 든 컬럼을 그대로 쓴다)
        }
        Ddl.CreateTable s = d.statement();
        Map<String, Catalog.ColumnDef> own = new LinkedHashMap<>(def.columns);
        Map<String, Catalog.ColumnDef> merged = new LinkedHashMap<>();
        // 1. 부모(INHERITS, PARTITION OF)의 컬럼이 자기 컬럼보다 앞이다
        for (Ddl.ColumnSource source : s.sources()) {
            if (source.kind() != Ddl.ColumnSource.Kind.LIKE) {
                copyColumns(def, source, d.locator(), merged, done, visiting);
            }
        }
        // 2. 자기 컬럼을 적힌 순서대로. LIKE는 놓인 자리에 원본의 컬럼을 넣는다. 부모에게서 온 것과 같은 이름이면 부모의 자리에 합친다
        for (int i = 0; i <= s.columns().size(); i++) {
            for (Ddl.ColumnSource source : s.sources()) {
                if (source.kind() == Ddl.ColumnSource.Kind.LIKE && Math.min(source.position(), s.columns().size()) == i) {
                    copyColumns(def, source, d.locator(), merged, done, visiting);
                }
            }
            if (i < s.columns().size()) {
                String column = DbName.normalize(s.columns().get(i).name());
                Catalog.ColumnDef declared = own.remove(column);
                if (declared != null) {
                    merged.merge(column, declared, DdlProcessor::overlay);
                }
            }
        }
        // 3. 제약이 이름만 만든 컬럼(UNIQUE (가져온 컬럼))
        own.forEach((column, stub) -> merged.merge(column, stub, DdlProcessor::overlay));
        def.columns.clear();
        def.columns.putAll(merged);
        visiting.remove(name);
        done.add(name);
    }

    /** source가 가리키는 테이블의 컬럼을 into에 더한다. 그 테이블의 정의가 없으면(뷰도 포함: 컬럼을 아직 모른다) 아무것도 더하지 않는다. */
    private void copyColumns(Catalog.TableDef def, Ddl.ColumnSource source, Locator locator, Map<String, Catalog.ColumnDef> into,
            java.util.Set<String> done, java.util.Set<String> visiting) {
        String parentName = source.table().name();
        Catalog.TableDef parent = catalog.table(parentName);
        if (parent == null || !parent.declared || parent.view || parent == def) {
            def.columnsIncomplete = true;
            return;
        }
        inheritColumns(parentName, done, visiting); // 부모가 또 다른 테이블에서 가져오면 그것부터
        if (parent.columnsIncomplete || visiting.contains(parentName)) {
            def.columnsIncomplete = true;
        }
        SourceLocation location = locator.locate(source.pos());
        for (Catalog.ColumnDef original : parent.columns.values()) {
            Catalog.ColumnDef copy = new Catalog.ColumnDef(original.name);
            copy.dataType = original.dataType;
            copy.notNull = original.notNull || (source.keys() && parent.primaryKey.contains(original.name));
            copy.location = location;
            if (source.defaults()) {
                copy.defaultValue = original.defaultValue;
            }
            if (source.identity()) {
                copy.identity = original.identity;
            }
            if (source.keys()) {
                copy.primaryKey = original.primaryKey;
                copy.unique = original.unique;
            }
            if (source.kind() != Ddl.ColumnSource.Kind.LIKE) {
                copy.virtual = original.virtual;
            }
            into.merge(copy.name, copy, (first, second) -> {
                first.notNull |= second.notNull; // 여러 부모의 같은 이름: 앞의 자리에 합친다
                return first;
            });
        }
        if (source.keys()) {
            if (def.primaryKey.isEmpty() && !parent.primaryKey.isEmpty()) {
                def.primaryKey = new ArrayList<>(parent.primaryKey);
            }
            for (List<String> key : parent.uniqueKeys) {
                if (!def.uniqueKeys.contains(key)) {
                    def.uniqueKeys.add(new ArrayList<>(key));
                }
            }
        }
    }

    /** 가져온 컬럼 inherited 위에 그 테이블이 스스로 적은 같은 이름의 컬럼 own을 얹는다(자리는 가져온 컬럼의 것). */
    private static Catalog.ColumnDef overlay(Catalog.ColumnDef inherited, Catalog.ColumnDef own) {
        if (own.dataType != null) {
            inherited.dataType = own.dataType;
        }
        if (own.defaultValue != null) {
            inherited.defaultValue = own.defaultValue;
        }
        if (own.comment != null) {
            inherited.comment = own.comment;
        }
        if (own.location != null) {
            inherited.location = own.location;
        }
        inherited.notNull |= own.notNull;
        inherited.primaryKey |= own.primaryKey;
        inherited.unique |= own.unique;
        inherited.identity |= own.identity;
        inherited.indexed |= own.indexed;
        inherited.virtual |= own.virtual;
        return inherited;
    }

    /** 스키마 한정자(따옴표 안의 채움 공백은 뗀다). 없으면 null. */
    private static String schemaOf(QName name) {
        String schema = name.qualifier();
        return schema == null || schema.isBlank() ? null : schema.trim();
    }

    private void applyColumn(Catalog.TableDef def, Ddl.ColumnDef c, Locator locator) {
        String name = DbName.normalize(c.name());
        boolean isNew = !def.hasColumn(name);
        Catalog.ColumnDef column = def.columnOrCreate(name);
        if (isNew || column.location == null) {
            column.location = locator.locate(c.pos());
        }
        if (c.dataType() != null) {
            column.dataType = c.dataType();
        }
        if (c.notNull() != null) {
            column.notNull = c.notNull();
        }
        if (c.defaultValue() != null) {
            column.defaultValue = c.defaultValue().isEmpty() ? null : c.defaultValue();
        }
        if (c.primaryKey()) {
            column.primaryKey = true;
            if (!def.primaryKey.contains(name)) {
                def.primaryKey = new ArrayList<>(List.of(name));
            }
        }
        column.unique |= c.unique();
        column.identity |= c.identity();
        column.virtual |= c.virtual();
        if (c.reference() != null) {
            applyConstraint(def, c.reference(), locator);
        }
    }

    private void applyConstraint(Catalog.TableDef def, Ddl.Constraint c, Locator locator) {
        List<String> columns = c.columns().stream().map(DbName::normalize).collect(Collectors.toList());
        switch (c.kind()) {
            case "PRIMARY KEY":
                if (!columns.isEmpty()) {
                    def.primaryKey = new ArrayList<>(columns);
                }
                break;
            case "UNIQUE":
                addUnique(def, columns);
                break;
            case "FOREIGN KEY": {
                if (c.refTable() == null) {
                    break;
                }
                Catalog.ForeignKey fk = new Catalog.ForeignKey();
                fk.constraint = c.name() == null ? null : DbName.normalize(c.name());
                fk.columns = columns;
                fk.refTable = c.refTable().name();
                fk.refColumns = c.refColumns().stream().map(DbName::normalize).collect(Collectors.toList());
                fk.onDelete = c.onDelete();
                fk.location = locator.locate(c.pos());
                catalog.addSchema(c.refTable().qualifier());
                addForeignKey(def, fk);
                break;
            }
            case "INDEX":
                // MySQL의 테이블 수준 인덱스 절(F-007-R16): CREATE INDEX와 같게 기록한다
                if (c.name() != null && !columns.isEmpty()) {
                    addIndex(def, DbName.normalize(c.name()) + "(" + String.join(",", columns) + ")");
                }
                for (String name : columns) {
                    Catalog.ColumnDef column = def.column(name);
                    if (column != null) {
                        column.indexed = true;
                    }
                }
                break;
            default:
                break;
        }
    }

    /**
     * 같은 FK(컬럼, 부모, 참조 컬럼이 같다)가 이미 있으면 빠진 정보(제약 이름, 참조 컬럼, ON DELETE)만 채운다.
     *
     * @return 새로 더했으면 true
     */
    private static boolean addForeignKey(Catalog.TableDef def, Catalog.ForeignKey fk) {
        for (Catalog.ForeignKey old : def.foreignKeys) {
            if (old.columns.equals(fk.columns) && old.refTable.equals(fk.refTable)
                    && (old.refColumns.isEmpty() || fk.refColumns.isEmpty() || old.refColumns.equals(fk.refColumns))) {
                if (old.constraint == null) {
                    old.constraint = fk.constraint;
                }
                if (old.refColumns.isEmpty()) {
                    old.refColumns = fk.refColumns;
                }
                if (old.onDelete == null) {
                    old.onDelete = fk.onDelete;
                }
                return false;
            }
        }
        def.foreignKeys.add(fk);
        return true;
    }

    private static void addIndex(Catalog.TableDef def, String entry) {
        if (!def.indexes.contains(entry)) {
            def.indexes.add(entry);
        }
    }

    private void addUnique(Catalog.TableDef def, List<String> columns) {
        if (columns.size() == 1) {
            def.columnOrCreate(columns.get(0)).unique = true;
        } else if (columns.size() > 1 && !def.uniqueKeys.contains(columns)) {
            def.uniqueKeys.add(columns);
        }
    }

    private void alterTable(Ddl.AlterTable s, Locator locator) {
        Catalog.TableDef def = catalog.tableOrCreate(s.name().name());
        catalog.addSchema(s.name().qualifier());
        for (Ddl.AlterAction action : s.actions()) {
            if (action instanceof Ddl.AddColumn a) {
                applyColumn(def, a.column(), locator);
            } else if (action instanceof Ddl.ModifyColumn m) {
                applyColumn(def, m.column(), locator);
            } else if (action instanceof Ddl.AddConstraint a) {
                applyConstraint(def, a.constraint(), locator);
            } else if (action instanceof Ddl.DropColumn d) {
                String name = DbName.normalize(d.name());
                def.columns.remove(name);
                def.primaryKey.remove(name);
            } else if (action instanceof Ddl.RenameColumn r) {
                renameColumn(def, DbName.normalize(r.from()), DbName.normalize(r.to()));
            } else if (action instanceof Ddl.AttachPartition a) {
                // pg_dump의 파티션: 자식은 자기 컬럼을 적은 CREATE TABLE로 이미 정의돼 있다. 부모의 이름만 붙인다(F-019-R20).
                // 자식의 정의가 없으면 노드를 만들지 않는다
                Catalog.TableDef partition = catalog.table(a.partition().name());
                catalog.addSchema(a.partition().qualifier());
                if (partition != null && partition.declared) {
                    partition.partitionOf = s.name().name();
                }
            }
        }
    }

    private void renameColumn(Catalog.TableDef def, String from, String to) {
        if (!def.columns.containsKey(from) || def.columns.containsKey(to)) {
            return;
        }
        Map<String, Catalog.ColumnDef> copy = new LinkedHashMap<>(def.columns);
        def.columns.clear();
        for (Map.Entry<String, Catalog.ColumnDef> e : copy.entrySet()) {
            if (e.getKey().equals(from)) {
                Catalog.ColumnDef old = e.getValue();
                Catalog.ColumnDef renamed = new Catalog.ColumnDef(to);
                renamed.dataType = old.dataType;
                renamed.notNull = old.notNull;
                renamed.defaultValue = old.defaultValue;
                renamed.comment = old.comment;
                renamed.primaryKey = old.primaryKey;
                renamed.unique = old.unique;
                renamed.identity = old.identity;
                renamed.indexed = old.indexed;
                renamed.virtual = old.virtual;
                renamed.location = old.location;
                def.columns.put(to, renamed);
            } else {
                def.columns.put(e.getKey(), e.getValue());
            }
        }
        def.primaryKey.replaceAll(c -> c.equals(from) ? to : c);
    }

    private void commentOn(Ddl.CommentOn s) {
        if (s.target().equals("TABLE")) {
            Catalog.TableDef def = catalog.table(s.name().name());
            if (def != null) {
                def.comment = s.text();
            }
        } else if (s.name().size() >= 2) {
            Catalog.TableDef def = catalog.table(s.name().part(1));
            Catalog.ColumnDef column = def == null ? null : def.column(s.name().name());
            if (column != null) {
                column.comment = s.text();
            }
        }
    }

    private void createIndex(Ddl.CreateIndex s) {
        Catalog.TableDef def = catalog.tableOrCreate(s.table().name());
        catalog.addSchema(s.table().qualifier());
        // 이름 없는 인덱스(PostgreSQL CREATE INDEX ON t (..))는 이름 자리를 비운다: DB가 붙일 이름을 지어내지 않는다
        String label = s.name() == null ? (s.unique() ? "UNIQUE" : "") : s.name().name() + (s.unique() ? " UNIQUE" : "");
        addIndex(def, label + "(" + String.join(",", s.expressions()) + ")");
        List<String> columns = s.columns().stream().map(DbName::normalize).collect(Collectors.toList());
        for (String name : columns) {
            Catalog.ColumnDef column = def.column(name);
            if (column != null) {
                column.indexed = true;
            }
        }
        // 부분 유일 인덱스(.. WHERE 조건)는 조건에 맞는 행끼리만 유일하다: 컬럼·테이블의 유일 키로 올리지 않는다(F-019-R21)
        if (s.unique() && !s.partial() && columns.size() == s.expressions().size()) {
            if (columns.size() == 1) {
                Catalog.ColumnDef column = def.column(columns.get(0));
                if (column != null) {
                    column.unique = true;
                }
            } else if (!columns.isEmpty() && !def.uniqueKeys.contains(columns)) {
                def.uniqueKeys.add(columns);
            }
        }
    }

    private void createView(Ddl.CreateView s, String file, Locator locator, Pos unitPos, String sql) {
        String name = s.name().name();
        catalog.addSchema(s.name().qualifier());
        Catalog.TableDef def = catalog.tableOrCreate(name);
        def.view = true;
        def.declared = true;
        def.materialized = s.materialized();
        def.schema = schemaOf(s.name());
        def.location = locator.locate(unitPos);
        def.file = file;
        def.columns.clear();
        List<String> explicit = s.columns().stream().map(DbName::normalize).collect(Collectors.toList());
        explicit.forEach(c -> def.columnOrCreate(c).location = def.location);
        pending.add(new PendingQuery(def, s, s.query(), file, unitPos, sql, locator, explicit));
    }

    // ------------------------------------------------------------------ 2패스: 그래프

    /**
     * 모든 DDL을 받은 뒤 부른다: 테이블마다 쓸 정의를 정해 카탈로그에 반영하고, 뷰·CTAS 컬럼을 유도하고, FK 참조 컬럼을 채우고,
     * 노드와 엣지를 쓴다.
     */
    public void finish() {
        resolveDefinitions();
        for (PendingQuery p : pending) {
            deriveColumns(p);
        }
        for (Catalog.TableDef def : catalog.tables()) {
            writeTable(def);
        }
        for (Catalog.TableDef def : catalog.tables()) {
            writeForeignKeys(def);
        }
        for (NamedObject s : preferred(sequences)) {
            String id = SqlIds.sequence(s.name());
            graph.declare(Node.builder(id, NodeKind.DB_SEQUENCE).name(s.name()).qualifiedName(qualified(s.schema(), s.name()))
                    .location(s.location()).attr("schema", s.schema()).build());
            graph.addEdge(Edge.of(Ids.file(s.file()), id, EdgeKind.CONTAINS));
        }
        for (NamedObject s : preferred(synonyms)) {
            String id = SqlIds.synonym(s.name());
            Node.Builder node = Node.builder(id, NodeKind.DB_SYNONYM).name(s.name()).qualifiedName(qualified(s.schema(), s.name()))
                    .location(s.location()).attr("schema", s.schema()).attr("target", s.target());
            if (s.isPublic()) {
                node.attr("public", true);
            }
            graph.declare(node.build());
            graph.addEdge(Edge.of(Ids.file(s.file()), id, EdgeKind.CONTAINS));
            String targetName = catalog.synonyms().get(s.name());
            String targetId = SqlIds.table(targetName);
            graph.reference(targetId, NodeKind.DB_TABLE, targetName);
            graph.addEdge(Edge.of(id, targetId, EdgeKind.ALIAS_OF, s.location()));
        }
    }

    private static String qualified(String schema, String name) {
        return schema == null ? name : schema + "." + name;
    }

    // ------------------------------------------------------------------ 어느 정의를 쓸지 (F-007-R14, R15)

    /**
     * 같은 테이블·뷰의 CREATE가 여러 파일에 있으면(방언별 DDL 폴더) 한 파일의 것만 쓴다. 고르는 순서는 {@link #better}다
     * (upgrade 스크립트는 뒤로, 프로젝트에 하나인 방언 순위 {@link DialectSelection#rank}, 경로순).
     * CREATE를 먼저 반영하고 ALTER TABLE·COMMENT ON·CREATE INDEX를 받은 순서대로 반영한다(주석 파일이 DDL 파일보다 경로상 앞에 있어도 된다).
     * 선택되지 않은 정의 파일과 다른 방언 폴더의 ALTER·INDEX·COMMENT, 현재 DDL이 따로 있는 테이블에 대한 upgrade 스크립트의 ALTER는 반영하지 않는다.
     */
    private void resolveDefinitions() {
        // 1. 테이블·뷰마다 정의한 파일들
        Map<String, List<String>> definers = new LinkedHashMap<>();
        Map<String, String> schemaOfDefinition = new LinkedHashMap<>();
        for (Event e : events) {
            QName name = definedName(e.statement());
            if (name != null) {
                List<String> files = definers.computeIfAbsent(name.name(), k -> new ArrayList<>());
                if (!files.contains(e.file())) {
                    files.add(e.file());
                    schemaOfDefinition.put(name.name() + "@" + e.file(), schemaOf(name));
                }
            }
        }
        // 2. 방언의 순위를 정한다: 중복 정의가 있는 테이블의 정의 파일을 경로순(upgrade 스크립트는 뒤)으로 묻는다. 방언을 읽지 못한 파일이
        //    그 밖의 방언 파일보다 경로상 먼저인지가 여기서 정해진다(그 밖의 방언끼리의 순서는 물은 순서와 무관하다: F-007-R28)
        java.util.Set<String> duplicatedDefiners = new java.util.LinkedHashSet<>();
        definers.values().stream().filter(files -> files.size() > 1).forEach(duplicatedDefiners::addAll);
        for (boolean upgrade : new boolean[] {false, true}) {
            for (String f : fileOrder.keySet()) {
                if (isUpgradePath(f) == upgrade && duplicatedDefiners.contains(f)) {
                    selection.rank(selection.dialectOf(f));
                }
            }
        }
        // 3. 쓸 파일을 고르고, 중복은 (쓴 파일, 버린 파일) 쌍으로 센다
        Map<String, String> chosen = new LinkedHashMap<>();
        Map<String, Map<String, java.util.Set<String>>> duplicates = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> d : definers.entrySet()) {
            List<String> files = d.getValue();
            String best = files.get(0);
            for (String f : files) {
                if (better(f, best)) {
                    best = f;
                }
            }
            chosen.put(d.getKey(), best);
            for (String f : files) {
                if (f.equals(best)) {
                    continue;
                }
                String a = schemaOfDefinition.get(d.getKey() + "@" + best);
                String b = schemaOfDefinition.get(d.getKey() + "@" + f);
                if (a != null && b != null && !a.equals(b)) {
                    graph.report(Diagnostic.warn(DiagnosticCodes.DUPLICATE_OBJECT_NAME,
                            "테이블 " + d.getKey() + "이(가) 서로 다른 스키마 " + a + ", " + b + "에 정의돼 있어 " + best + "의 정의만 사용함",
                            SourceLocation.ofFile(f)));
                } else {
                    duplicates.computeIfAbsent(best, k -> new LinkedHashMap<>()).computeIfAbsent(f, k -> new java.util.LinkedHashSet<>()).add(d.getKey());
                }
            }
        }
        summarizeVariants(definers, chosen);
        reportDuplicates(duplicates);
        // 4. CREATE TABLE/VIEW: 고른 파일의 것만
        for (Event e : events) {
            QName name = definedName(e.statement());
            if (name == null || !e.file().equals(chosen.get(name.name()))) {
                continue;
            }
            if (e.statement() instanceof Ddl.CreateTable s) {
                createTable(s, e.file(), e.locator(), e.unitPos(), e.sql());
            } else if (e.statement() instanceof Ddl.CreateView s) {
                createView(s, e.file(), e.locator(), e.unitPos(), e.sql());
            }
        }
        inheritColumns();
        // 5. 나머지는 받은 순서대로
        Map<String, NotApplied> notApplied = new LinkedHashMap<>();
        for (Event e : events) {
            if (definedName(e.statement()) != null) {
                continue;
            }
            String table = targetTable(e.statement());
            String definition = table == null ? null : chosen.get(table);
            if (definition != null && !definition.equals(e.file())) {
                List<String> files = definers.get(table);
                if (e.statement() instanceof Ddl.AlterTable alter && isUpgradePath(e.file()) && !isUpgradePath(definition)) {
                    // 현재 DDL에 이미 반영됐을 과거의 변경(F-007-R15). 외래 키 추가만 대상이 현재 정의에 있으면 적용한다(F-007-R22)
                    upgradeAlter(alter, e.locator(), notApplied.computeIfAbsent(e.file(), k -> new NotApplied()));
                    continue;
                }
                if (files.contains(e.file())) {
                    continue; // 선택되지 않은 정의 파일의 구문
                }
                String dialect = selection.dialectOf(e.file());
                if (files.size() > 1 && dialect != null && !dialect.equals(selection.dialectOf(definition))) {
                    continue; // 다른 방언 폴더의 구문
                }
            }
            if (e.statement() instanceof Ddl.AlterTable s) {
                alterTable(s, e.locator());
            } else if (e.statement() instanceof Ddl.CommentOn s) {
                commentOn(s);
            } else if (e.statement() instanceof Ddl.CreateIndex s) {
                createIndex(s);
            }
        }
        notApplied.forEach((file, n) -> graph.report(Diagnostic.info(SqlDiagnosticCodes.DDL_NOT_APPLIED, n.message(), SourceLocation.ofFile(file))));
        events.clear();
    }

    /** upgrade 스크립트 하나에서 적용하지 않은 ALTER TABLE의 집계(F-007-R15, F-007-R22). */
    private static final class NotApplied {
        int statements;
        int addedColumns;
        int changedColumns;
        int constraints;
        int appliedForeignKeys;

        String message() {
            List<String> kinds = new ArrayList<>();
            if (addedColumns > 0) {
                kinds.add("컬럼 추가 " + addedColumns);
            }
            if (changedColumns > 0) {
                kinds.add("컬럼 변경·삭제 " + changedColumns);
            }
            if (constraints > 0) {
                kinds.add("제약 추가 " + constraints);
            }
            String applied = "외래 키 추가 " + appliedForeignKeys + "건은 대상 테이블·컬럼이 현재 정의에 있어 적용함(REFERENCES의 fromUpgrade=true)";
            if (statements == 0) {
                return "upgrade·patch·migration 스크립트의 " + applied + ". 적용하지 않은 ALTER TABLE은 없음";
            }
            return "upgrade·patch·migration 스크립트의 ALTER TABLE " + statements + "건을 적용하지 않음"
                    + (kinds.isEmpty() ? "" : "(" + String.join(", ", kinds) + ")")
                    + ": 대상 테이블의 CREATE TABLE이 다른 파일에 있어 이미 반영된 변경으로 본다"
                    + (appliedForeignKeys > 0 ? ". " + applied : "");
        }
    }

    /**
     * 현재 정의가 다른 파일에 있는 테이블에 대한 upgrade 스크립트의 ALTER TABLE 하나. 컬럼·키를 바꾸는 동작은 적용하지 않고 종류별로 센다.
     * 외래 키 추가는 자식 컬럼과 부모 테이블(참조 컬럼을 적었으면 그 컬럼)이 현재 정의에 있으면 적용하고 {@code fromUpgrade}로 표시한다:
     * 과거에 더한 FK는 현재 스키마에도 유효할 가능성이 높고, 덤프한 현재 DDL에는 FK가 빠져 있는 일이 흔하다(F-007-R22).
     */
    private void upgradeAlter(Ddl.AlterTable s, Locator locator, NotApplied count) {
        Catalog.TableDef def = catalog.table(s.name().name());
        boolean skipped = s.actions().isEmpty();
        for (Ddl.AlterAction action : s.actions()) {
            if (action instanceof Ddl.AddConstraint a && a.constraint().kind().equals("FOREIGN KEY") && foreignKeyApplies(def, a.constraint())) {
                int before = def.foreignKeys.size();
                applyConstraint(def, a.constraint(), locator);
                if (def.foreignKeys.size() > before) {
                    def.foreignKeys.get(def.foreignKeys.size() - 1).fromUpgrade = true;
                    count.appliedForeignKeys++;
                }
                continue;
            }
            skipped = true;
            if (action instanceof Ddl.AddColumn) {
                count.addedColumns++;
            } else if (action instanceof Ddl.ModifyColumn || action instanceof Ddl.DropColumn || action instanceof Ddl.RenameColumn) {
                count.changedColumns++;
            } else if (action instanceof Ddl.AddConstraint) {
                count.constraints++;
            }
        }
        if (skipped) {
            count.statements++;
        }
    }

    /** FK의 자식 컬럼이 모두 이 테이블에 있고, 부모 테이블이 정의돼 있으며 적어 준 참조 컬럼이 부모에 있는가. */
    private boolean foreignKeyApplies(Catalog.TableDef def, Ddl.Constraint c) {
        if (def == null || c.refTable() == null || c.columns().isEmpty()) {
            return false;
        }
        for (String column : c.columns()) {
            if (!def.hasColumn(DbName.normalize(column))) {
                return false;
            }
        }
        Catalog.TableDef parent = catalog.table(c.refTable().name());
        if (parent == null || !parent.declared) {
            return false;
        }
        for (String column : c.refColumns()) {
            if (!parent.hasColumn(DbName.normalize(column))) {
                return false;
            }
        }
        return true;
    }

    /** 정의 파일 f가 g보다 먼저인가: upgrade 스크립트가 아닌 것, 방언 순위가 앞선 것, 경로순으로 앞선 것 순이다. */
    private boolean better(String f, String g) {
        if (isUpgradePath(f) != isUpgradePath(g)) {
            return !isUpgradePath(f);
        }
        int rf = selection.rank(selection.dialectOf(f));
        int rg = selection.rank(selection.dialectOf(g));
        return rf != rg ? rf < rg : fileOrder.get(f) < fileOrder.get(g);
    }

    /**
     * 방언별 DDL 여러 벌을 고른 결과를 요약에 더한다(F-007-R19): 정의한 파일들의 방언이 둘 이상인 테이블·뷰가 있으면 방언 변형이다.
     * 그런 테이블을 정의한 방언 파일("방언 변형 파일")에서 쓴 테이블 수를 방언별로 세고, 쓴 것이 하나도 없는 변형 파일을 제외한 파일로 센다.
     */
    private void summarizeVariants(Map<String, List<String>> definers, Map<String, String> chosen) {
        java.util.Set<String> variantFiles = new java.util.LinkedHashSet<>();
        for (List<String> files : definers.values()) {
            java.util.Set<String> dialects = new java.util.LinkedHashSet<>();
            for (String f : files) {
                String dialect = selection.dialectOf(f);
                selection.present(dialect);
                if (dialect != null) {
                    dialects.add(dialect);
                }
            }
            if (dialects.size() >= 2) {
                for (String f : files) {
                    if (selection.dialectOf(f) != null) {
                        variantFiles.add(f);
                    }
                }
            }
        }
        if (variantFiles.isEmpty()) {
            return;
        }
        variantFiles.forEach(f -> selection.detectedDdl(selection.dialectOf(f)));
        java.util.Set<String> used = new java.util.HashSet<>(chosen.values());
        used.forEach(selection::usedDefinitionFile);
        chosen.forEach((table, file) -> {
            if (variantFiles.contains(file)) {
                selection.usedTable(selection.dialectOf(file));
            }
        });
        variantFiles.stream().filter(f -> !used.contains(f)).forEach(selection::excludedFile);
    }

    /** CREATE TABLE/VIEW면 정의하는 이름, 아니면 null. */
    private static QName definedName(Ddl statement) {
        if (statement instanceof Ddl.CreateTable s) {
            return s.name();
        }
        return statement instanceof Ddl.CreateView s ? s.name() : null;
    }

    /** ALTER TABLE·COMMENT ON·CREATE INDEX가 가리키는 테이블 이름(정규화). 알 수 없으면 null. */
    private static String targetTable(Ddl statement) {
        if (statement instanceof Ddl.AlterTable s) {
            return s.name().name();
        }
        if (statement instanceof Ddl.CreateIndex s) {
            return s.table().name();
        }
        if (statement instanceof Ddl.CommentOn s) {
            return s.target().equals("TABLE") ? s.name().name() : (s.name().size() >= 2 ? s.name().part(1) : null);
        }
        return null;
    }

    /** 과거의 변경을 쌓아 둔 스크립트로 보이는 경로인가: upgrade·patch·migration 폴더, 또는 그런 이름으로 시작하는 파일. */
    static boolean isUpgradePath(String path) {
        String[] segments = path.toLowerCase(java.util.Locale.ROOT).replace('\\', '/').split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            String s = segments[i];
            if (s.contains("upgrade") || s.contains("patch") || s.contains("migration") || s.contains("migrate")) {
                return true;
            }
        }
        String file = segments[segments.length - 1];
        return file.startsWith("upgrade") || file.startsWith("patch") || file.startsWith("migrat") || file.matches("update[^a-z].*");
    }

    /**
     * 중복 정의를 파일 쌍마다 INFO 하나로 알린다. 서로 다른 두 폴더 사이에 파일 쌍이 여럿이면(테이블마다 파일 하나인 배치) 폴더 쌍 하나로 줄인다.
     */
    private void reportDuplicates(Map<String, Map<String, java.util.Set<String>>> duplicates) {
        final class Summary {
            final java.util.Set<String> used = new java.util.LinkedHashSet<>();
            final java.util.Set<String> dropped = new java.util.LinkedHashSet<>();
            final java.util.Set<String> tables = new java.util.LinkedHashSet<>();
        }
        Map<String, Summary> summaries = new LinkedHashMap<>();
        duplicates.forEach((used, dropped) -> dropped.forEach((file, names) -> {
            String usedDialect = selection.dialectOf(used);
            String droppedDialect = selection.dialectOf(file);
            if (usedDialect != null && droppedDialect != null && !usedDialect.equals(droppedDialect)) {
                return; // 방언별 여러 벌에서 한 벌을 고른 것: DIALECT_VARIANT_DDL 요약이 알린다. "한 벌만 지정 권장"은 필요 없다(F-007-R27)
            }
            // 서로 다른 폴더 사이의 쌍은 폴더 쌍으로 모은다. 같은 폴더 안의 쌍은 파일 쌍 그대로다
            String key = directoryOf(used).equals(directoryOf(file)) ? used + "|" + file : directoryOf(used) + "|" + directoryOf(file);
            Summary summary = summaries.computeIfAbsent(key, k -> new Summary());
            summary.used.add(used);
            summary.dropped.add(file);
            summary.tables.addAll(names);
        }));
        for (Summary s : summaries.values()) {
            String firstUsed = s.used.iterator().next();
            String firstDropped = s.dropped.iterator().next();
            String a = s.used.size() > 1 ? folderLabel(firstUsed) : firstUsed;
            String b = s.dropped.size() > 1 ? folderLabel(firstDropped) : firstDropped;
            graph.report(Diagnostic.info(DiagnosticCodes.DUPLICATE_OBJECT_NAME,
                    "같은 테이블 " + s.tables.size() + "개의 정의가 " + a + "와(과) " + b + "에 중복됨; " + a + "의 정의를 사용. "
                            + "--exclude나 --ddl로 한 벌만 지정 권장", SourceLocation.ofFile(firstDropped)));
        }
    }

    private static String folderLabel(String path) {
        String directory = directoryOf(path);
        return directory.isEmpty() ? "최상위 폴더" : directory;
    }

    private static String directoryOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash + 1);
    }

    /** 같은 이름이 여러 파일에 있으면 테이블과 같은 순서로 고른 하나만 남긴다(시퀀스, 시노님). */
    private List<NamedObject> preferred(List<NamedObject> objects) {
        Map<String, NamedObject> byName = new LinkedHashMap<>();
        for (NamedObject o : objects) {
            NamedObject old = byName.get(o.name());
            if (old == null || (!old.file().equals(o.file()) && better(o.file(), old.file()))) {
                byName.put(o.name(), o);
            }
        }
        return new ArrayList<>(byName.values());
    }

    /** 뷰·CTAS의 컬럼을 정의 쿼리의 선택 목록에서 얻는다(이름이 없으면 건너뛴다. 타입은 원본 컬럼에서). */
    private void deriveColumns(PendingQuery p) {
        if (p.query() == null) {
            return;
        }
        FactExtractor.OutputColumns out;
        try {
            out = new FactExtractor(catalog).outputColumns(p.query());
        } catch (RuntimeException | StackOverflowError e) {
            return;
        }
        Catalog.TableDef def = p.target();
        List<String> explicit = p.explicitColumns();
        if (!explicit.isEmpty()) {
            if (out.complete() && out.columns().size() == explicit.size()) {
                for (int i = 0; i < explicit.size(); i++) {
                    copyType(def.columnOrCreate(explicit.get(i)), out.columns().get(i));
                }
            }
            return;
        }
        for (FactExtractor.OutputColumn c : out.columns()) {
            if (c.name() == null || def.hasColumn(c.name())) {
                continue;
            }
            Catalog.ColumnDef column = def.columnOrCreate(c.name());
            column.location = def.location;
            copyType(column, c);
        }
    }

    private void copyType(Catalog.ColumnDef column, FactExtractor.OutputColumn source) {
        if (column.dataType != null || source.sourceTable() == null) {
            return;
        }
        Catalog.TableDef table = catalog.table(source.sourceTable());
        Catalog.ColumnDef original = table == null ? null : table.column(source.sourceColumn());
        if (original != null) {
            column.dataType = original.dataType;
        }
    }

    private String idOf(Catalog.TableDef def) {
        return def.view ? SqlIds.view(def.name) : SqlIds.table(def.name);
    }

    private void writeTable(Catalog.TableDef def) {
        String id = idOf(def);
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (def.schema != null) {
            attrs.put("schema", def.schema);
        }
        if (def.comment != null) {
            attrs.put("comment", def.comment);
        }
        if (!def.primaryKey.isEmpty()) {
            attrs.put("primaryKey", List.copyOf(def.primaryKey));
        }
        if (!def.uniqueKeys.isEmpty()) {
            attrs.put("uniqueKeys", def.uniqueKeys.stream().map(k -> String.join(",", k)).collect(Collectors.toList()));
        }
        if (!def.indexes.isEmpty()) {
            attrs.put("indexes", List.copyOf(def.indexes));
        }
        if (def.temporary) {
            attrs.put("temporary", true);
        }
        if (def.partitionOf != null) {
            attrs.put("partitionOf", def.partitionOf);
        }
        if (def.materialized) {
            attrs.put("materialized", true);
        }
        if (def.declared) {
            graph.declare(Node.builder(id, def.view ? NodeKind.DB_VIEW : NodeKind.DB_TABLE).name(def.name)
                    .qualifiedName(qualified(def.schema, def.name)).location(def.location).attrs(attrs).build());
            if (def.file != null) {
                graph.addEdge(Edge.of(Ids.file(def.file), id, EdgeKind.CONTAINS));
            }
        } else {
            graph.reference(id, NodeKind.DB_TABLE, def.name);
            attrs.remove("schema");
            if (!attrs.isEmpty()) {
                graph.putAttrs(id, attrs);
            }
        }
        int ordinal = 0;
        for (Catalog.ColumnDef column : def.columns.values()) {
            ordinal++;
            boolean pk = column.primaryKey || def.primaryKey.contains(column.name);
            String columnId = SqlIds.column(def.name, column.name);
            Node.Builder node = Node.builder(columnId, NodeKind.DB_COLUMN).name(column.name)
                    .qualifiedName(def.name + "." + column.name).location(column.location)
                    .attr("dataType", column.dataType).attr("ordinal", ordinal)
                    .attr("defaultValue", column.defaultValue).attr("comment", column.comment);
            if (!def.view) {
                node.attr("nullable", !(column.notNull || pk)).attr("primaryKey", pk);
            }
            if (column.unique) {
                node.attr("unique", true);
            }
            if (column.identity) {
                node.attr("identity", true);
            }
            if (column.indexed) {
                node.attr("indexed", true);
            }
            if (column.virtual) {
                node.attr("virtual", true);
            }
            graph.declare(node.build());
            graph.addEdge(Edge.of(id, columnId, EdgeKind.CONTAINS));
        }
    }

    /** upgrade 스크립트가 아닌 FK(현재 정의의 FK)가 적힌 파일. 처음 쓸 때 한 번 모은다. */
    private java.util.Set<String> filesWithCurrentForeignKeys;

    /**
     * upgrade 스크립트에서 가져온 FK가 "지금은 없는 제약"일 수 있는가(F-007-R26): 그 테이블의 현재 정의 파일이 다른 FK를 하나라도 담고 있거나
     * 그 테이블에 upgrade가 아닌 FK가 이미 있으면, 현재 정의가 FK를 적는 스키마인데 이 FK만 빠져 있는 것이다.
     * 현재 정의가 FK를 전혀 담지 않으면(제약을 따로 관리하거나 덤프에서 뺀 스키마) 과거에 더한 FK가 지금도 유효하다고 본다.
     */
    private boolean missingFromCurrentDefinition(Catalog.TableDef def) {
        if (filesWithCurrentForeignKeys == null) {
            filesWithCurrentForeignKeys = new java.util.HashSet<>();
            for (Catalog.TableDef t : catalog.tables()) {
                for (Catalog.ForeignKey k : t.foreignKeys) {
                    if (!k.fromUpgrade && k.location != null && k.location.file() != null) {
                        filesWithCurrentForeignKeys.add(k.location.file());
                    }
                }
            }
        }
        return (def.file != null && filesWithCurrentForeignKeys.contains(def.file)) || def.foreignKeys.stream().anyMatch(k -> !k.fromUpgrade);
    }

    private void writeForeignKeys(Catalog.TableDef def) {
        String id = idOf(def);
        for (Catalog.ForeignKey fk : def.foreignKeys) {
            Catalog.TableDef parent = catalog.table(fk.refTable);
            List<String> refColumns = fk.refColumns;
            if (refColumns.isEmpty() && parent != null && parent.primaryKey.size() == fk.columns.size()) {
                refColumns = parent.primaryKey;
            }
            String parentId = SqlIds.table(fk.refTable);
            graph.reference(parentId, NodeKind.DB_TABLE, fk.refTable);
            Edge.Builder edge = Edge.builder(id, parentId, EdgeKind.REFERENCES).location(fk.location)
                    .attr("columns", List.copyOf(fk.columns)).attr("source", "DDL")
                    .attr("constraint", fk.constraint).attr("onDelete", fk.onDelete);
            if (!refColumns.isEmpty()) {
                edge.attr("refColumns", List.copyOf(refColumns));
            }
            if (fk.fromUpgrade) {
                edge.attr("fromUpgrade", true);
                if (missingFromCurrentDefinition(def)) {
                    // 현재 정의가 FK를 적는 스키마인데 이 FK는 없다: 확정된 제약이 아니라 추론이다
                    edge.confidence(io.codeatlas.core.model.Confidence.INFERRED).attr("note", "현재 정의에 없음");
                }
            }
            graph.addEdge(edge.build());
        }
    }
}
