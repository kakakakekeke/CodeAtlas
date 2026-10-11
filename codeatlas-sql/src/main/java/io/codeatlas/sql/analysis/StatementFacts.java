package io.codeatlas.sql.analysis;

import io.codeatlas.sql.ast.Pos;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 구문 하나(또는 같은 주인의 여러 전개본)에서 뽑은 사실: 읽고 쓴 테이블·컬럼, 조인, 시퀀스, 호출.
 * 테이블 이름은 정규화한 값(스키마 제외)이다. {@link #merge}로 합칠 수 있다.
 */
public final class StatementFacts {

    /** 읽은 테이블. {@code star}는 {@code SELECT *}, {@code lock}은 LOCK TABLE. */
    public record TableRead(String table, boolean star, boolean lock, Pos pos) {
    }

    /** 쓴 테이블. op: INSERT, UPDATE, DELETE, MERGE. */
    public record TableWrite(String table, String op, boolean truncate, Pos pos) {
    }

    /** 읽거나 쓴 컬럼. 읽기면 op는 null. */
    public record ColumnUse(String table, String column, String op, Pos pos) {
    }

    /**
     * 한 쌍의 테이블 인스턴스 사이의 등치 조건 묶음. 컬럼은 순서대로 대응한다.
     *
     * @param leftFixed 왼쪽 테이블 인스턴스의 컬럼 중 같은 구문에서 리터럴·바인드와 {@code =}로 묶인 것(F-006-R13).
     *        키는 컬럼, 값은 그 상수들(문자열 리터럴은 따옴표를 뗀 값, 바인드와 변수는 {@code ?}). 순서는 나온 순서다
     * @param rightFixed 오른쪽 테이블 인스턴스의 고정 컬럼
     * @param wraps 컬럼 쌍마다(컬럼 목록과 같은 순서) 형 변환·정리 함수로 감싸인 쪽(F-006-R29): {@link #PLAIN}이면 감싸이지 않은
     *        등치가 한 번이라도 있었다, 아니면 {@link #WRAP_LEFT}·{@link #WRAP_RIGHT}의 비트 합. 비어 있으면 모두 {@link #PLAIN}이다
     */
    public record Join(String leftTable, String rightTable, List<String> leftColumns, List<String> rightColumns, Pos pos,
            Map<String, Set<String>> leftFixed, Map<String, Set<String>> rightFixed, List<Integer> wraps) {

        /** 그 컬럼 쌍을 감싸지 않고 비교한 조건이 있다. */
        public static final int PLAIN = 0;
        /** 그 컬럼 쌍의 모든 조건에서 어느 한쪽이 감싸였고, 왼쪽 컬럼이 감싸인 적이 있다. */
        public static final int WRAP_LEFT = 1;
        /** 그 컬럼 쌍의 모든 조건에서 어느 한쪽이 감싸였고, 오른쪽 컬럼이 감싸인 적이 있다. */
        public static final int WRAP_RIGHT = 2;

        public Join {
            leftColumns = List.copyOf(leftColumns);
            rightColumns = List.copyOf(rightColumns);
            leftFixed = copyFixed(leftFixed);
            rightFixed = copyFixed(rightFixed);
            wraps = wraps == null || wraps.stream().allMatch(w -> w == PLAIN) ? List.of() : List.copyOf(wraps);
        }

        public Join(String leftTable, String rightTable, List<String> leftColumns, List<String> rightColumns, Pos pos) {
            this(leftTable, rightTable, leftColumns, rightColumns, pos, Map.of(), Map.of(), List.of());
        }

        public Join(String leftTable, String rightTable, List<String> leftColumns, List<String> rightColumns, Pos pos,
                Map<String, Set<String>> leftFixed, Map<String, Set<String>> rightFixed) {
            this(leftTable, rightTable, leftColumns, rightColumns, pos, leftFixed, rightFixed, List.of());
        }

        /** 감싸인 컬럼 쌍이 하나라도 있는가(그 쌍의 모든 근거가 감싼 조건이다). */
        public boolean wrapped() {
            return !wraps.isEmpty();
        }

        /** 왼쪽 컬럼이 함수로 감싸인 쌍이 있는가. */
        public boolean leftWrapped() {
            return wraps.stream().anyMatch(w -> (w & WRAP_LEFT) != 0);
        }

        /** 오른쪽 컬럼이 함수로 감싸인 쌍이 있는가. */
        public boolean rightWrapped() {
            return wraps.stream().anyMatch(w -> (w & WRAP_RIGHT) != 0);
        }

        private int wrapAt(int index) {
            return index < wraps.size() ? wraps.get(index) : PLAIN;
        }

        /** 좌우를 바꾼 표시. */
        static int flip(int wrap) {
            return ((wrap & WRAP_LEFT) != 0 ? WRAP_RIGHT : 0) | ((wrap & WRAP_RIGHT) != 0 ? WRAP_LEFT : 0);
        }

        /** 같은 컬럼 쌍의 두 근거를 합친 표시: 한쪽이라도 감싸지 않았으면 {@link #PLAIN}, 아니면 감싸인 쪽을 모은다. */
        static int combine(int a, int b) {
            return a == PLAIN || b == PLAIN ? PLAIN : a | b;
        }

        /** 이 조인에 그 컬럼 쌍(방향 무관)이 있으면 주어진 좌우 기준의 표시, 없으면 null. */
        private Integer wrapFor(String lt, String lc, String rt, String rc) {
            if (leftTable.equals(lt) && rightTable.equals(rt)) {
                for (int i = 0; i < leftColumns.size(); i++) {
                    if (leftColumns.get(i).equals(lc) && rightColumns.get(i).equals(rc)) {
                        return wrapAt(i);
                    }
                }
            }
            if (leftTable.equals(rt) && rightTable.equals(lt)) {
                for (int i = 0; i < leftColumns.size(); i++) {
                    if (leftColumns.get(i).equals(rc) && rightColumns.get(i).equals(lc)) {
                        return flip(wrapAt(i));
                    }
                }
            }
            return null;
        }

        /** 같은 조인을 다른 곳에서 또 봤을 때의 표시: 양쪽에 다 있는 컬럼 쌍은 합치고, 이쪽에만 있는 쌍은 그대로 둔다. */
        private List<Integer> wrapsWith(Join other) {
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < leftColumns.size(); i++) {
                Integer theirs = other.wrapFor(leftTable, leftColumns.get(i), rightTable, rightColumns.get(i));
                out.add(theirs == null ? wrapAt(i) : combine(wrapAt(i), theirs));
            }
            return out;
        }

        private static Map<String, Set<String>> copyFixed(Map<String, Set<String>> fixed) {
            Map<String, Set<String>> copy = new LinkedHashMap<>();
            if (fixed != null) {
                fixed.forEach((column, values) -> copy.put(column, new LinkedHashSet<>(values)));
            }
            return java.util.Collections.unmodifiableMap(copy);
        }

        /** 방향을 무시한 컬럼 대응 집합({@code "A.X=B.Y"} 형태, 이름순 정규화). */
        Set<String> pairSet() {
            Set<String> set = new LinkedHashSet<>();
            for (int i = 0; i < leftColumns.size(); i++) {
                String l = leftTable + "." + leftColumns.get(i);
                String r = rightTable + "." + rightColumns.get(i);
                set.add(l.compareTo(r) <= 0 ? l + "=" + r : r + "=" + l);
            }
            return set;
        }

        boolean sameTables(Join other) {
            return (leftTable.equals(other.leftTable) && rightTable.equals(other.rightTable))
                    || (leftTable.equals(other.rightTable) && rightTable.equals(other.leftTable));
        }

        /** 좌→우 방향을 살린 컬럼 대응 집합. 자기 조인의 좌우를 가릴 때 쓴다. */
        private Set<String> directedPairs() {
            Set<String> set = new LinkedHashSet<>();
            for (int i = 0; i < leftColumns.size(); i++) {
                set.add(leftColumns.get(i) + "=" + rightColumns.get(i));
            }
            return set;
        }

        /** other의 좌우가 이 조인의 좌우와 같은 테이블 인스턴스 쪽을 가리키는가({@link #sameTables}가 참일 때만 뜻이 있다). */
        private boolean sameOrientation(Join other) {
            if (!leftTable.equals(rightTable)) {
                return leftTable.equals(other.leftTable);
            }
            Set<String> mine = directedPairs();
            Set<String> theirs = other.directedPairs();
            return mine.containsAll(theirs) || theirs.containsAll(mine);
        }

        /**
         * 같은 조인을 다른 곳(같은 구문의 다른 인스턴스 쌍, 같은 주인의 다른 전개본)에서 또 봤을 때의 고정 컬럼:
         * 양쪽 모두에서 고정된 컬럼만 남기고 값은 모은다. 한 곳에서라도 상수 조건 없이 조인했다면 고정이라고 말할 수 없기 때문이다.
         * 감싸인 컬럼 쌍의 표시도 같은 발상으로 합친다: 한 곳에서라도 감싸지 않고 비교했으면 감싸인 조인이 아니다(F-006-R29).
         */
        Join withFixedCommonTo(Join other) {
            boolean same = sameOrientation(other);
            return new Join(leftTable, rightTable, leftColumns, rightColumns, pos,
                    common(leftFixed, same ? other.leftFixed : other.rightFixed),
                    common(rightFixed, same ? other.rightFixed : other.leftFixed), wrapsWith(other));
        }

        private static Map<String, Set<String>> common(Map<String, Set<String>> a, Map<String, Set<String>> b) {
            Map<String, Set<String>> out = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> e : a.entrySet()) {
                Set<String> other = b.get(e.getKey());
                if (other != null) {
                    Set<String> values = new LinkedHashSet<>(e.getValue());
                    values.addAll(other);
                    out.put(e.getKey(), values);
                }
            }
            return out;
        }
    }

    public record SequenceUse(String sequence, Pos pos) {
    }

    /**
     * 소속 테이블을 정하지 못한 비한정 컬럼(F-006-R31): 스코프의 선언된 테이블 어느 DDL에도 없다.
     *
     * @param tables 후보 테이블(그 컬럼을 볼 수 있는 가장 안쪽 스코프의 테이블, 나온 순서)
     */
    public record UnknownColumn(String column, Set<String> tables, Pos pos) {
        public UnknownColumn {
            tables = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(tables));
        }
    }

    /**
     * 루틴 호출. {@code explicit}이면 CALL 구문(이름은 원문 조각을 정규화한 것: {@code [SCHEMA,] [PKG,] NAME}),
     * 아니면 SQL 식 안의 함수 호출 중 카탈로그에 있는 루틴({@code [PKG,] NAME}).
     */
    public record Call(List<String> name, boolean explicit, Pos pos) {
        public Call {
            name = List.copyOf(name);
        }
    }

    /**
     * 정적으로 알 수 없는 대상. 테이블 자리의 {@code ${}} 치환이거나, {@code statement}가 true면 풀지 못한 동적 SQL 구문 전체다(F-008).
     * write면 op가 있다. text는 원문 조각(동적 SQL이면 {@code {*}}가 든 SQL).
     */
    public record DynamicTable(boolean write, String op, String text, Pos pos, boolean statement) {
        public DynamicTable(boolean write, String op, String text, Pos pos) {
            this(write, op, text, pos, false);
        }
    }

    private final List<String> types = new ArrayList<>();
    private final Map<String, TableRead> tableReads = new LinkedHashMap<>();
    private final Map<String, TableWrite> tableWrites = new LinkedHashMap<>();
    private final Map<String, ColumnUse> columnReads = new LinkedHashMap<>();
    private final Map<String, ColumnUse> columnWrites = new LinkedHashMap<>();
    private final List<Join> joins = new ArrayList<>();
    private final Map<String, SequenceUse> sequences = new LinkedHashMap<>();
    private final Map<String, Call> calls = new LinkedHashMap<>();
    private final List<DynamicTable> dynamicTables = new ArrayList<>();
    private final Map<String, UnknownColumn> unknownColumns = new LinkedHashMap<>();
    /**
     * 추측과 그 추측을 한 추출(전개본)이 읽은 테이블(F-006-R31). 키가 {@code 테이블.컬럼}이면 DDL에 없는 컬럼을 "스코프의 하나뿐인 테이블"이라는
     * 이유로 붙인 컬럼 읽기이고, {@code ?컬럼}이면 소속을 정하지 못한 컬럼이다. 다른 전개본이 읽는 테이블로 그 이름이 설명되는지 볼 때 쓴다.
     */
    private final Map<String, Set<String>> guesses = new LinkedHashMap<>();
    private boolean dynamic;
    private String dummyTable;
    /** DB의 사전 뷰·카탈로그 테이블로 읽은 이름(F-006-R40). */
    private final java.util.Set<String> systemCatalogs = new java.util.LinkedHashSet<>();

    // ---- 기록 ----

    /** 구문 종류를 하나 적는다({@code SELECT, INSERT, UPDATE, DELETE, MERGE, CALL, OTHER}). */
    public void noteType(String type) {
        types.add(type);
    }

    void read(String table, boolean star, boolean lock, Pos pos) {
        TableRead old = tableReads.get(table);
        if (old == null) {
            tableReads.put(table, new TableRead(table, star, lock, pos));
        } else if ((star && !old.star()) || (lock && !old.lock())) {
            tableReads.put(table, new TableRead(table, old.star() || star, old.lock() || lock, old.pos()));
        }
    }

    void write(String table, String op, boolean truncate, Pos pos) {
        tableWrites.putIfAbsent(table + "|" + op, new TableWrite(table, op, truncate, pos));
    }

    void columnRead(String table, String column, Pos pos) {
        columnReads.putIfAbsent(table + "." + column, new ColumnUse(table, column, null, pos));
    }

    void columnWrite(String table, String column, String op, Pos pos) {
        columnWrites.putIfAbsent(table + "." + column + "|" + op, new ColumnUse(table, column, op, pos));
    }

    /** 같은 테이블 쌍·같은 컬럼 대응 집합이면 한 번만 넣는다(고정 컬럼은 양쪽에 다 있는 것만 남긴다). */
    void join(Join join) {
        Set<String> pairs = join.pairSet();
        for (int i = 0; i < joins.size(); i++) {
            Join j = joins.get(i);
            if (j.sameTables(join) && j.pairSet().equals(pairs)) {
                joins.set(i, j.withFixedCommonTo(join));
                return;
            }
        }
        joins.add(join);
    }

    /** 테이블 수준 읽기를 지운다. DML 대상으로만 쓰인 인라인 뷰 안의 테이블 인스턴스에 쓴다(F-006-R14). 컬럼 읽기는 그대로 둔다. */
    void removeRead(String table) {
        tableReads.remove(table);
    }

    /** 시퀀스 참조({@code seq.NEXTVAL}, {@code NEXT VALUE FOR seq})를 적는다. name은 스키마를 뺀 정규화한 이름이다. */
    public void sequence(String name, Pos pos) {
        sequences.putIfAbsent(name, new SequenceUse(name, pos));
    }

    /**
     * 루틴 호출을 적는다. explicit이 false면 name은 확정된 {@code [PKG,] NAME}이다(본문 분석이 판정을 끝낸 호출).
     */
    public void call(List<String> name, boolean explicit, Pos pos) {
        calls.putIfAbsent(String.join(".", name) + "|" + explicit, new Call(name, explicit, pos));
    }

    void dynamicTable(boolean write, String op, String text, Pos pos) {
        dynamic = true;
        dynamicTables.add(new DynamicTable(write, op, text, pos));
    }

    /** 풀지 못한 동적 SQL 구문 하나를 적는다(F-008). {@code unresolved:sql-dynamic:<구문 ID>}로의 미해결 엣지가 된다. */
    public void dynamicStatement(boolean write, String op, String text, Pos pos) {
        dynamic = true;
        dynamicTables.add(new DynamicTable(write, op, text, pos, true));
    }

    /** 동적 SQL(또는 {@code ${}} 치환)이 있는 구문임을 적는다. */
    public void markDynamic() {
        dynamic = true;
    }

    void dummyTable(String name) {
        if (dummyTable == null) {
            dummyTable = name;
        }
    }

    /**
     * 한 번의 추출이 끝났을 때 부른다: 추측으로 붙인 컬럼 읽기({@code 테이블.컬럼})와 소속을 정하지 못한 컬럼에, 이 추출이 읽은 테이블을 함께 적어 둔다(F-006-R31).
     */
    void closeGuesses(Collection<String> guessedReadKeys) {
        Set<String> seen = Set.copyOf(tableReads.keySet());
        for (String key : guessedReadKeys) {
            if (columnReads.containsKey(key)) {
                guesses.put(key, seen);
            }
        }
        for (String column : unknownColumns.keySet()) {
            guesses.put("?" + column, seen);
        }
    }

    /**
     * 추측 가운데 다른 전개본이 읽는 테이블로 설명되는 것을 지운다(F-006-R31): 그 추측을 한 추출은 읽지 않았지만 같은 주인의 다른 추출이 읽는 테이블의
     * DDL에 그 이름의 컬럼이 있으면, MyBatis {@code <choose>} 조합이 만든 가짜 짝(이 테이블의 컬럼 목록 + 저 테이블)으로 보고
     * 컬럼 읽기도 미확인 컬럼도 남기지 않는다. 같은 추출이 함께 읽는 테이블은 근거가 아니다(스코프에서 이미 봤거나 보이지 않는 자리다).
     *
     * @param declared (테이블, 컬럼) → 그 테이블의 DDL에 그 컬럼이 있는가
     */
    void dropGuessesExplainedBy(java.util.function.BiPredicate<String, String> declared) {
        for (Map.Entry<String, Set<String>> e : new ArrayList<>(guesses.entrySet())) {
            String key = e.getKey();
            boolean unknown = key.startsWith("?");
            ColumnUse use = unknown ? null : columnReads.get(key);
            String column = unknown ? key.substring(1) : use == null ? null : use.column();
            if (column == null) {
                continue;
            }
            for (String table : tableReads.keySet()) {
                if (!e.getValue().contains(table) && (use == null || !table.equals(use.table())) && declared.test(table, column)) {
                    if (unknown) {
                        unknownColumns.remove(column);
                    } else {
                        columnReads.remove(key);
                    }
                    guesses.remove(key);
                    break;
                }
            }
        }
    }

    /** 소속 테이블을 정하지 못한 컬럼을 적는다. 같은 이름은 한 번만 남기고 후보 테이블은 모은다(F-006-R31). */
    void unknownColumn(String column, Collection<String> tables, Pos pos) {
        UnknownColumn old = unknownColumns.get(column);
        if (old == null) {
            unknownColumns.put(column, new UnknownColumn(column, new LinkedHashSet<>(tables), pos));
        } else if (!old.tables().containsAll(tables)) {
            Set<String> all = new LinkedHashSet<>(old.tables());
            all.addAll(tables);
            unknownColumns.put(column, new UnknownColumn(column, all, old.pos()));
        }
    }

    // ---- 조회 ----

    /**
     * 구문 종류. 쓰기 구문(INSERT/UPDATE/DELETE/MERGE)이 있으면 그 첫 번째, 없으면 CALL, SELECT, OTHER 순이다.
     */
    public String statementType() {
        for (String t : types) {
            if (t.equals("INSERT") || t.equals("UPDATE") || t.equals("DELETE") || t.equals("MERGE")) {
                return t;
            }
        }
        if (types.contains("CALL")) {
            return "CALL";
        }
        if (types.contains("SELECT")) {
            return "SELECT";
        }
        return "OTHER";
    }

    /** 구문에서 뽑은 것이 하나라도 있는가. */
    public boolean isEmpty() {
        return tableReads.isEmpty() && tableWrites.isEmpty() && sequences.isEmpty() && calls.isEmpty()
                && dynamicTables.isEmpty() && types.stream().allMatch(t -> t.equals("OTHER"));
    }

    public Collection<TableRead> tableReads() {
        return tableReads.values();
    }

    public Collection<TableWrite> tableWrites() {
        return tableWrites.values();
    }

    public Collection<ColumnUse> columnReads() {
        return columnReads.values();
    }

    public Collection<ColumnUse> columnWrites() {
        return columnWrites.values();
    }

    public List<Join> joins() {
        return List.copyOf(joins);
    }

    public Collection<SequenceUse> sequences() {
        return sequences.values();
    }

    public Collection<Call> calls() {
        return calls.values();
    }

    public List<DynamicTable> dynamicTables() {
        return List.copyOf(dynamicTables);
    }

    /** 소속 테이블을 정하지 못한 비한정 컬럼(이름마다 하나, 나온 순서). */
    /** 이 테이블을 DB의 사전 뷰·카탈로그 테이블로 읽었다(사전 스키마로 한정했거나 사전 뷰의 이름이다). */
    void systemCatalog(String table) {
        systemCatalogs.add(table);
    }

    public java.util.Set<String> systemCatalogs() {
        return systemCatalogs;
    }

    public Collection<UnknownColumn> unknownColumns() {
        return unknownColumns.values();
    }

    /** {@code ${}} 치환이나 동적 SQL 실행이 있는 구문. */
    public boolean dynamic() {
        return dynamic;
    }

    /** {@code DUAL} 또는 {@code SYSIBM.SYSDUMMY1}을 썼으면 그 이름, 아니면 null. */
    public String dummyTable() {
        return dummyTable;
    }

    /**
     * 다른 사실(같은 주인의 다른 전개본, 블록 안의 다른 구문)을 합친다. 조인은 같은 테이블 쌍에서 컬럼 대응 집합이
     * 포함 관계면 큰 쪽 하나만 남긴다(전개본마다 조건이 더 붙거나 빠진 같은 조인으로 본다).
     */
    public StatementFacts merge(StatementFacts other) {
        types.addAll(other.types);
        other.tableReads.values().forEach(r -> read(r.table(), r.star(), r.lock(), r.pos()));
        other.tableWrites.forEach(tableWrites::putIfAbsent);
        // 한쪽에서라도 추측 없이 읽은 컬럼은 추측이 아니다. 양쪽 다 추측이면 그 추출들이 읽은 테이블을 모은다
        Set<String> firm = new java.util.HashSet<>(columnReads.keySet());
        firm.removeAll(guesses.keySet());
        for (String key : other.columnReads.keySet()) {
            if (!other.guesses.containsKey(key)) {
                firm.add(key);
            }
        }
        other.columnReads.forEach(columnReads::putIfAbsent);
        other.guesses.forEach((key, seen) -> guesses.merge(key, seen, (mine, theirs) -> {
            Set<String> all = new LinkedHashSet<>(mine);
            all.addAll(theirs);
            return all;
        }));
        guesses.keySet().removeAll(firm);
        other.columnWrites.forEach(columnWrites::putIfAbsent);
        for (Join j : other.joins) {
            mergeJoin(j);
        }
        other.sequences.forEach(sequences::putIfAbsent);
        other.calls.forEach(calls::putIfAbsent);
        dynamicTables.addAll(other.dynamicTables);
        other.unknownColumns.values().forEach(u -> unknownColumn(u.column(), u.tables(), u.pos()));
        dynamic |= other.dynamic;
        systemCatalogs.addAll(other.systemCatalogs);
        if (dummyTable == null) {
            dummyTable = other.dummyTable;
        }
        return this;
    }

    private void mergeJoin(Join join) {
        Set<String> pairs = join.pairSet();
        for (int i = 0; i < joins.size(); i++) {
            Join j = joins.get(i);
            if (!j.sameTables(join)) {
                continue;
            }
            Set<String> existing = j.pairSet();
            if (existing.containsAll(pairs)) {
                joins.set(i, j.withFixedCommonTo(join));
                return;
            }
            if (pairs.containsAll(existing)) {
                Join wider = new Join(join.leftTable(), join.rightTable(), join.leftColumns(), join.rightColumns(), j.pos(),
                        join.leftFixed(), join.rightFixed(), join.wraps());
                joins.set(i, wider.withFixedCommonTo(j));
                return;
            }
        }
        joins.add(join);
    }
}
