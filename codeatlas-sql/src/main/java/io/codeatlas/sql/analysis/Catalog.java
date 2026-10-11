package io.codeatlas.sql.analysis;

import io.codeatlas.core.model.SourceLocation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DDL에서 모은 DB 객체 정의(테이블·뷰와 컬럼, 키, 시노님, 시퀀스, 패키지, 루틴, 스키마 이름).
 * DML 분석이 비한정 컬럼의 소속 테이블을 정하고 호출 대상을 판정할 때 쓴다. 이름은 모두 정규화한 값(스키마 제외, 따옴표 없으면 대문자)이다.
 */
public final class Catalog {

    /** 컬럼 정의. */
    public static final class ColumnDef {
        public final String name;
        public String dataType;
        public boolean notNull;
        public String defaultValue;
        public String comment;
        public boolean primaryKey;
        public boolean unique;
        public boolean identity;
        public boolean indexed;
        public boolean virtual;
        public SourceLocation location;

        public ColumnDef(String name) {
            this.name = name;
        }
    }

    /** 외래 키. refColumns가 비어 있으면 아직 부모 PK로 채우지 않은 것이다. */
    public static final class ForeignKey {
        public String constraint;
        public List<String> columns = new ArrayList<>();
        public String refTable;
        public String refTableRaw;
        public List<String> refColumns = new ArrayList<>();
        public String onDelete;
        public SourceLocation location;
        /** upgrade·patch·migration 스크립트의 ALTER TABLE에서 온 FK(현재 정의의 파일에는 없다, F-007-R22). */
        public boolean fromUpgrade;
    }

    /** 테이블 또는 뷰 정의. */
    public static final class TableDef {
        public final String name;
        public String schema;
        public boolean view;
        public boolean materialized;
        public boolean temporary;
        /** CREATE TABLE/VIEW로 정의됐는가(ALTER만 있으면 false). */
        public boolean declared;
        public String comment;
        public SourceLocation location;
        public String file;
        /** PostgreSQL 파티션이면 부모 테이블의 이름({@code CREATE TABLE p PARTITION OF 부모}), 아니면 null(F-019-R20). */
        public String partitionOf;
        /**
         * 컬럼을 물려준 테이블({@code LIKE}·{@code INHERITS}·{@code PARTITION OF})의 정의를 찾지 못해 컬럼 목록이 다 차지 않았는가.
         * 그러면 자기 컬럼만 들어 있다: "이 테이블에 없는 컬럼"이라고 말할 수 없다.
         */
        public boolean columnsIncomplete;
        public final Map<String, ColumnDef> columns = new LinkedHashMap<>();
        public List<String> primaryKey = new ArrayList<>();
        public final List<List<String>> uniqueKeys = new ArrayList<>();
        public final List<ForeignKey> foreignKeys = new ArrayList<>();
        public final List<String> indexes = new ArrayList<>();

        public TableDef(String name) {
            this.name = name;
        }

        public ColumnDef column(String columnName) {
            return columns.get(columnName);
        }

        public ColumnDef columnOrCreate(String columnName) {
            return columns.computeIfAbsent(columnName, ColumnDef::new);
        }

        public boolean hasColumn(String columnName) {
            return columns.containsKey(columnName);
        }

        /** 컬럼 목록을 아는가(컬럼 소속 판정에 쓸 수 있는가). */
        public boolean columnsKnown() {
            return !columns.isEmpty() && !columnsIncomplete;
        }
    }

    private final Map<String, TableDef> tables = new LinkedHashMap<>();
    private final Map<String, String> synonyms = new LinkedHashMap<>();
    private final Set<String> sequences = new LinkedHashSet<>();
    private final Set<String> packages = new LinkedHashSet<>();
    private final Set<String> routines = new LinkedHashSet<>();
    private final Set<String> schemas = new LinkedHashSet<>();
    private final Set<String> routineBodies = new LinkedHashSet<>();
    private final Map<String, Set<String>> packageVariables = new LinkedHashMap<>();

    /** 테이블·뷰 정의. 없으면 null. */
    public TableDef table(String name) {
        return name == null ? null : tables.get(name);
    }

    public TableDef tableOrCreate(String name) {
        return tables.computeIfAbsent(name, TableDef::new);
    }

    /** 테이블 이름 → 사전 뷰·카탈로그 테이블이 아닌 것으로 읽은 적이 있는가(F-006-R40). 구문들이 읽은 이름만 든다. */
    private final Map<String, Boolean> plainUse = new LinkedHashMap<>();

    /** 구문이 테이블을 읽었다고 알린다. system이면 DB의 사전 뷰·카탈로그 테이블로 읽은 것이다. */
    public void noteTableUse(String table, boolean system) {
        plainUse.merge(table, !system, Boolean::logicalOr);
    }

    /** 읽은 구문이 모두 사전 뷰·카탈로그 테이블로 읽었고 DDL에 정의가 없는 테이블 이름들. */
    public List<String> systemCatalogTables() {
        List<String> names = new ArrayList<>();
        plainUse.forEach((table, plain) -> {
            if (!plain && table(table) == null) {
                names.add(table);
            }
        });
        return names;
    }

    public Collection<TableDef> tables() {
        return tables.values();
    }

    /** 컬럼 이름만으로 테이블을 정의한다(테스트와 간단한 구성용). */
    public TableDef defineTable(String name, String... columnNames) {
        TableDef def = tableOrCreate(name);
        def.declared = true;
        for (String c : columnNames) {
            def.columnOrCreate(c);
        }
        return def;
    }

    public void addSynonym(String name, String target) {
        synonyms.put(name, target);
    }

    public Map<String, String> synonyms() {
        return synonyms;
    }

    /** 시노님이면 가리키는 이름(연쇄는 5단계까지), 아니면 그대로. */
    public String resolveSynonym(String name) {
        String current = name;
        for (int i = 0; i < 5; i++) {
            if (tables.containsKey(current)) {
                return current;
            }
            String target = synonyms.get(current);
            if (target == null || target.equals(current)) {
                return current;
            }
            current = target;
        }
        return current;
    }

    public void addSequence(String name) {
        sequences.add(name);
    }

    public boolean hasSequence(String name) {
        return sequences.contains(name);
    }

    public void addPackage(String name) {
        packages.add(name);
    }

    public boolean hasPackage(String name) {
        return name != null && packages.contains(name);
    }

    /** 루틴을 등록한다. packageName은 독립 루틴이면 null. */
    public void addRoutine(String packageName, String name) {
        routines.add(packageName == null ? name : packageName + "." + name);
    }

    public boolean hasRoutine(String packageName, String name) {
        return routines.contains(packageName == null ? name : packageName + "." + name);
    }

    /** 본문이 있는 루틴임을 기억한다(패키지 명세에만 있는 루틴과 구분한다). packageName은 독립 루틴이면 null. */
    public void addRoutineBody(String packageName, String name) {
        routineBodies.add(packageName == null ? name : packageName + "." + name);
    }

    public boolean hasRoutineBody(String packageName, String name) {
        return routineBodies.contains(packageName == null ? name : packageName + "." + name);
    }

    /** 패키지 명세·본문의 패키지 수준 변수·상수·커서 이름을 더한다(F-008: 패키지 안 루틴의 SQL에서 컬럼으로 오인하지 않게 한다). */
    public void addPackageVariables(String packageName, Collection<String> names) {
        if (packageName != null && names != null && !names.isEmpty()) {
            packageVariables.computeIfAbsent(packageName, k -> new LinkedHashSet<>()).addAll(names);
        }
    }

    /** 패키지 수준 변수 이름(명세와 본문 합쳐서). 없으면 빈 집합. */
    public Set<String> packageVariables(String packageName) {
        Set<String> names = packageName == null ? null : packageVariables.get(packageName);
        return names == null ? Set.of() : names;
    }

    /** DDL에서 스키마로 쓰인 이름을 기억한다. */
    public void addSchema(String name) {
        if (name != null && !name.isBlank()) {
            schemas.add(name.trim());
        }
    }

    public boolean isSchema(String name) {
        return name != null && schemas.contains(name);
    }
}
