package io.codeatlas.sql.analysis;

import io.codeatlas.core.model.DbName;
import io.codeatlas.sql.ast.Ddl;
import io.codeatlas.sql.ast.Expr;
import io.codeatlas.sql.ast.Pos;
import io.codeatlas.sql.ast.QName;
import io.codeatlas.sql.ast.Query;
import io.codeatlas.sql.ast.Query.Cte;
import io.codeatlas.sql.ast.Query.QueryBody;
import io.codeatlas.sql.ast.Query.SelectItem;
import io.codeatlas.sql.ast.Statement;
import io.codeatlas.sql.ast.TableRef;
import io.codeatlas.sql.parser.Keywords;
import io.codeatlas.sql.parser.ParseResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 구문 트리에서 사실({@link StatementFacts})을 뽑는다. 스코프와 별칭을 해석해 테이블 참조(CTE·별칭·DUAL 제외),
 * 컬럼의 소속 테이블, 등치 조인 조건을 정한다. 확정할 수 없는 것은 만들지 않는다.
 *
 * <p>사용: {@code new FactExtractor(catalog).extract(parseResult)}. PL/SQL 본문 안의 구문이면
 * {@link #variables}로 지역 변수·매개변수 이름을 알려 컬럼으로 오인하지 않게 한다. 인스턴스는 재사용할 수 있다(상태 없음).
 */
public final class FactExtractor {

    /** 계층 쿼리({@code CONNECT BY})에서만 의사 컬럼인 이름. 그 밖의 구문에서는 보통의 이름이다(F-006-R33). */
    private static final java.util.Set<String> HIERARCHICAL_PSEUDO = java.util.Set.of("LEVEL", "CONNECT_BY_ISLEAF", "CONNECT_BY_ISCYCLE");

    /** DB가 제공하는 사전·카탈로그 스키마(Oracle {@code SYS}, DB2 {@code SYSCAT}·{@code SYSIBM}·{@code SYSSTAT} 등). */
    private static final java.util.Set<String> SYSTEM_SCHEMAS = java.util.Set.of(
            "SYS", "SYSCAT", "SYSIBM", "SYSSTAT", "SYSIBMADM", "SYSTOOLS", "SYSPROC", "SYSFUN", "INFORMATION_SCHEMA");
    private static final List<String> DICTIONARY_PREFIXES = List.of("USER_", "ALL_", "DBA_");
    /** Oracle 데이터 사전 뷰의 이름({@code USER_}·{@code ALL_}·{@code DBA_} 뒤). */
    private static final java.util.Set<String> DICTIONARY_VIEWS = java.util.Set.of(
            "TABLES", "TAB_COLUMNS", "TAB_COLS", "TAB_COMMENTS", "COL_COMMENTS", "CONSTRAINTS", "CONS_COLUMNS", "INDEXES", "IND_COLUMNS",
            "OBJECTS", "SEQUENCES", "VIEWS", "SYNONYMS", "TRIGGERS", "SOURCE", "PROCEDURES", "ARGUMENTS", "USERS", "TAB_PRIVS", "ROLE_PRIVS",
            "SYS_PRIVS", "COL_PRIVS", "DB_LINKS", "SEGMENTS", "EXTENTS", "TABLESPACES", "TAB_PARTITIONS", "TAB_SUBPARTITIONS", "PART_TABLES",
            "PART_KEY_COLUMNS", "DEPENDENCIES", "ERRORS", "JOBS", "SCHEDULER_JOBS", "MVIEWS", "TYPES", "LOBS", "TAB_STATISTICS", "CATALOG",
            "RECYCLEBIN", "FREE_SPACE", "DATA_FILES", "TS_QUOTAS", "DIRECTORIES", "AUDIT_TRAIL", "ALL_TABLES", "UPDATABLE_COLUMNS", "ROLES",
            "PROFILES", "IND_EXPRESSIONS", "TAB_MODIFICATIONS", "POLICIES");
    /** 접두사 없는 Oracle 사전 뷰·시노님. */
    private static final java.util.Set<String> DICTIONARY_NAMES = java.util.Set.of(
            "TAB", "CAT", "COLS", "DICT", "DICTIONARY", "NLS_SESSION_PARAMETERS", "NLS_DATABASE_PARAMETERS", "NLS_INSTANCE_PARAMETERS",
            "SESSION_PRIVS", "SESSION_ROLES", "GLOBAL_NAME", "PRODUCT_COMPONENT_VERSION", "ROLE_SYS_PRIVS", "ROLE_TAB_PRIVS", "ROLE_ROLE_PRIVS");

    /** 인자로 테이블 지정자(상관 이름)를 받는 DB2 함수. */
    private static final java.util.Set<String> TABLE_DESIGNATOR_FUNCTIONS = java.util.Set.of("RID", "RID_BIT", "RRN");

    /** 한정해서 써도 컬럼이 아닌 의사 컬럼. */
    private static final java.util.Set<String> QUALIFIED_PSEUDO = java.util.Set.of("ROWID", "ORA_ROWSCN", "OBJECT_VALUE", "COLUMN_VALUE");

    /**
     * 컬럼 하나만 감싸도 그 컬럼의 값으로 비교한다고 볼 수 있는 형 변환·정리 함수(F-006-R29). 이 함수로 감싼 컬럼의 등치는 조인으로 본다.
     * 값을 바꾸는 함수({@code SUBSTR}, {@code REPLACE}, {@code DECODE}, {@code LPAD} 등)는 넣지 않는다.
     */
    private static final java.util.Set<String> WRAPPERS = java.util.Set.of(
            "TRIM", "LTRIM", "RTRIM", "STRIP", "UPPER", "LOWER", "UCASE", "LCASE",
            "TO_CHAR", "TO_NCHAR", "TO_NUMBER", "TO_DATE", "CAST", "NVL", "COALESCE", "IFNULL", "VALUE",
            "CHAR", "VARCHAR", "INTEGER", "INT", "SMALLINT", "BIGINT", "DECIMAL", "DEC", "NUMERIC", "DOUBLE", "FLOAT", "REAL");

    /**
     * 함수 인자 자리에 이름만으로 오는 자료형·날짜 단위({@code CONVERT(FLOAT, x)}, {@code DATEADD(DAY, 1, x)}, {@code TRUNC(d, MONTH)}).
     * 카탈로그에 그 이름의 컬럼이 없으면 컬럼으로 추측하지 않는다(F-006-R31).
     */
    private static final java.util.Set<String> NON_COLUMN_ARGUMENTS = java.util.Set.of(
            "YEAR", "YEARS", "YY", "YYYY", "QUARTER", "QQ", "MONTH", "MONTHS", "MM", "MON", "WEEK", "WEEKS", "WK", "WW", "ISO_WEEK",
            "DAY", "DAYS", "DD", "DY", "DAYOFYEAR", "WEEKDAY", "HOUR", "HOURS", "HH", "MINUTE", "MINUTES", "MI", "SECOND", "SECONDS", "SS",
            "MILLISECOND", "MS", "MICROSECOND", "MICROSECONDS", "NANOSECOND",
            "FLOAT", "REAL", "DOUBLE", "INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT", "DECIMAL", "NUMERIC", "NUMBER", "MONEY", "BIT",
            "BOOLEAN", "CHAR", "NCHAR", "VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2", "TEXT", "NTEXT", "CLOB", "BLOB", "BINARY",
            "VARBINARY", "DATE", "TIME", "DATETIME", "DATETIME2", "SMALLDATETIME", "TIMESTAMP", "SIGNED", "UNSIGNED");

    /** 선택 목록 맨 앞에 이름처럼 오는 MySQL의 SELECT 수식어. 컬럼으로 추측하지 않는다(F-006-R31). */
    private static final java.util.Set<String> SELECT_MODIFIERS = java.util.Set.of(
            "SQL_CALC_FOUND_ROWS", "SQL_NO_CACHE", "SQL_CACHE", "SQL_SMALL_RESULT", "SQL_BIG_RESULT", "SQL_BUFFER_RESULT",
            "HIGH_PRIORITY", "STRAIGHT_JOIN", "DISTINCTROW");

    private final Catalog catalog;
    private Predicate<String> variables = name -> false;
    /** 루틴 본문 안의 구문을 보고 있다({@link #variables}로 변수 판별 함수를 받았다). */
    private boolean routineBody;
    /** 루틴 본문의 변수 스코프를 빠짐없이 안다. false면 선언되지 않은 이름을 컬럼으로 추측하지도 진단으로 알리지도 않는다. */
    private boolean scopeComplete = true;
    private String currentPackage;
    private Predicate<String> transientRelations = name -> false;

    public FactExtractor(Catalog catalog) {
        this.catalog = catalog == null ? new Catalog() : catalog;
    }

    /** 컬럼이 아니라 변수인 이름(정규화한 대문자)을 판별하는 함수를 준다. 카탈로그상 컬럼이 확실하면 컬럼이 이긴다. */
    public FactExtractor variables(Predicate<String> isVariable) {
        this.variables = isVariable == null ? name -> false : isVariable;
        this.routineBody = isVariable != null;
        return this;
    }

    /**
     * 루틴 본문의 변수 스코프를 빠짐없이 아는지 알려 준다(기본 true). 본문 파서가 선언을 놓쳤거나, 이름을 다 알 수 없는 암묵 변수가 있는
     * 자리(DB2 {@code FOR v AS SELECT * ..}의 본문)면 false를 준다: 그 구문에서는 DDL에 없는 이름이 변수일 수 있으므로
     * 컬럼 참조도 {@code UNKNOWN_COLUMN} 진단도 만들지 않는다(F-008-R17).
     */
    public FactExtractor scopeComplete(boolean complete) {
        this.scopeComplete = complete;
        return this;
    }

    /**
     * 구문이 들어 있는 패키지(정규화한 이름)를 알려 준다. 그 패키지의 루틴은 이름만으로 불러도 호출로 기록한다(F-008). 없으면 null.
     */
    public FactExtractor inPackage(String packageName) {
        this.currentPackage = packageName;
        return this;
    }

    /**
     * 테이블처럼 FROM에 오지만 테이블이 아닌 이름(정규화한 값)을 알려 준다: 트리거의 전이 테이블({@code NEW TABLE AS nt}) 등.
     * 이 이름은 테이블 엣지를 만들지 않고 컬럼을 알 수 없는 릴레이션으로 다룬다(F-008).
     */
    public FactExtractor transientRelations(Predicate<String> isTransient) {
        this.transientRelations = isTransient == null ? name -> false : isTransient;
        return this;
    }

    /** 파싱 결과에서 사실을 뽑는다. 구문 끝에서 건진 테이블(salvagedReads)은 READS로 넣는다. */
    public StatementFacts extract(ParseResult result) {
        Run run = new Run();
        // 파서가 다 읽지 못한 구문에서는 이름이 컬럼인지 믿을 수 없다: DDL에 없는 컬럼을 추측하지 않는다(F-006-R31)
        run.guessUndeclared = result.problems().stream().allMatch(io.codeatlas.sql.parser.ParseProblem::minor);
        run.statement(result.statement(), null);
        for (QName name : result.salvagedReads()) {
            run.readNamed(name);
        }
        return run.finish();
    }

    public StatementFacts extract(Statement statement) {
        Run run = new Run();
        run.statement(statement, null);
        return run.finish();
    }

    /** 쿼리 하나(뷰 정의, 커서 쿼리)에서 사실을 뽑는다. */
    public StatementFacts extract(Query query) {
        Run run = new Run();
        run.facts.noteType("SELECT");
        run.query(query, null);
        return run.finish();
    }

    /**
     * 쿼리의 출력 컬럼을 선택 목록 순서대로 돌려준다(뷰 컬럼 유도용). 이름은 별칭 또는 단순 컬럼 이름이고 별칭 없는 식이면 null이다.
     * {@code *}는 카탈로그에 원본 컬럼이 있을 때만 펼친다. 펼치지 못한 {@code *}가 있으면 {@link OutputColumns#complete()}가 false다.
     */
    public OutputColumns outputColumns(Query query) {
        Run run = new Run();
        Derived shape = run.query(query, null);
        List<OutputColumn> list = new ArrayList<>();
        boolean complete = run.expand(shape, list, 0) && !shape.opaque;
        return new OutputColumns(list, complete);
    }

    /** 쿼리 출력 컬럼. 원본이 단순 컬럼이면 sourceTable/sourceColumn이 채워진다. */
    public record OutputColumn(String name, String sourceTable, String sourceColumn) {
    }

    /** @param complete 모든 출력 컬럼의 자리를 알았으면 true(위치로 대응시킬 수 있다) */
    public record OutputColumns(List<OutputColumn> columns, boolean complete) {
    }

    // ------------------------------------------------------------------ 스코프 모델

    private abstract static class Relation {
        String alias;
    }

    /** 테이블(뷰·시노님 포함) 인스턴스 하나. */
    private static final class Base extends Relation {
        String refName;
        String effName;
        Catalog.TableDef def;
        boolean dummy;
        boolean opaque;
    }

    /** 인라인 뷰, CTE 참조, 테이블 함수. */
    private static final class Derived extends Relation {
        final Map<String, Origin> columns = new LinkedHashMap<>();
        final List<String> names = new ArrayList<>();
        final List<Origin> positional = new ArrayList<>();
        final List<Relation> starSources = new ArrayList<>();
        final List<Base> bases = new ArrayList<>();
        /** 선택 목록의 순서: Integer(names의 인덱스) 또는 Relation({@code *}의 원본). */
        final List<Object> layout = new ArrayList<>();
        /**
         * 집합 연산이면 그 분기(SELECT 한 덩어리)들의 모양. {@code IN}·비교의 서브쿼리(F-006-R30)와, 인라인 뷰·CTE로 쓰여
         * 조인 조건에 놓였을 때(F-006-R38) 분기마다 선택 컬럼의 원본을 본다.
         */
        final List<Derived> branches = new ArrayList<>();
        boolean opaque;
        /** 선택 목록에 동적 조각({@code ${}})이나 해석하지 못한 항목이 있어 "이 컬럼은 없다"고 말할 수 없다(F-006-R31). */
        boolean uncertain;

        void add(String name, Origin origin) {
            layout.add(names.size());
            names.add(name);
            positional.add(origin);
            if (name != null) {
                columns.putIfAbsent(name, origin == null ? Origin.NONE : origin);
            }
        }

        void addStar(Relation source) {
            starSources.add(source);
            layout.add(source);
        }

        Derived copy(String newAlias) {
            Derived d = new Derived();
            d.alias = newAlias;
            d.columns.putAll(columns);
            d.names.addAll(names);
            d.positional.addAll(positional);
            d.starSources.addAll(starSources);
            d.bases.addAll(bases);
            d.layout.addAll(layout);
            d.branches.addAll(branches); // 집합 연산인 인라인 뷰·CTE와의 조인에서 분기마다 원본을 찾는다(F-006-R38)
            d.opaque = opaque;
            d.uncertain = uncertain;
            return d;
        }
    }

    private record Origin(Base rel, String column) {
        static final Origin NONE = new Origin(null, null);

        boolean known() {
            return rel != null;
        }
    }

    private static final class Scope {
        final Scope parent;
        final List<Relation> relations = new ArrayList<>();
        final Map<String, Derived> ctes = new HashMap<>();
        /** 이 스코프의 SELECT 목록에 붙은 별칭. GROUP BY·HAVING·ORDER BY에서 별칭으로 쓴 이름을 컬럼으로 오인하지 않게 한다. */
        final java.util.Set<String> aliases = new java.util.HashSet<>();
        /** 이 스코프의 SELECT가 계층 쿼리다({@code START WITH}/{@code CONNECT BY}). {@code LEVEL}이 의사 컬럼인 범위다. */
        boolean hierarchical;

        Scope(Scope parent) {
            this.parent = parent;
        }

        Derived cte(String name) {
            for (Scope s = this; s != null; s = s.parent) {
                Derived d = s.ctes.get(name);
                if (d != null) {
                    return d;
                }
            }
            return null;
        }
    }

    private enum Has { YES, NO, UNKNOWN }

    /** @param guessed 카탈로그에 없는 컬럼을 "스코프의 하나뿐인 테이블"이라는 이유로 그 테이블에 붙였으면 true(F-006-R31) */
    private record Resolved(Relation rel, String column, boolean guessed) {
        Resolved(Relation rel, String column) {
            this(rel, column, false);
        }
    }

    private static final class JoinGroup {
        Base left;
        Base right;
        boolean prior;
        final List<String> leftColumns = new ArrayList<>();
        final List<String> rightColumns = new ArrayList<>();
        /** 컬럼 쌍마다 감싸인 쪽({@link StatementFacts.Join#PLAIN} 등, F-006-R29). */
        final List<Integer> wraps = new ArrayList<>();
        Pos pos;
    }

    /** 등치의 한쪽: 그 식이 가리키는 컬럼의 원본들(집합 연산 서브쿼리면 분기마다 하나)과 함수로 감싸였는지, 위치. */
    private record Operand(List<Origin> origins, boolean wrapped, Pos pos) {
        static final Operand NONE = new Operand(List.of(), false, Pos.NONE);
    }

    // ------------------------------------------------------------------ 한 번의 추출

    private final class Run {
        final StatementFacts facts = new StatementFacts();
        final List<JoinGroup> groups = new ArrayList<>();
        final IdentityHashMap<Query, Derived> shapes = new IdentityHashMap<>();
        /** 테이블 인스턴스별 고정 컬럼: 리터럴·바인드와 {@code =}로 묶인 컬럼 → 그 상수(F-006-R13). */
        final IdentityHashMap<Base, Map<String, String>> fixed = new IdentityHashMap<>();
        /** 테이블 이름별로 그 테이블을 읽는 인스턴스 수. */
        final Map<String, Integer> readInstances = new HashMap<>();
        /** 인라인 뷰를 거쳐 DML 대상이 된 테이블 인스턴스(F-006-R14). */
        final IdentityHashMap<Base, Boolean> writtenThroughView = new IdentityHashMap<>();
        /** DDL에 없는 비한정 컬럼을 추측해도 되는가(F-006-R31). 파서가 큰 문제를 남긴 구문이면 false. */
        boolean guessUndeclared = true;
        /** 추측으로 붙인 컬럼({@code 테이블.컬럼})과, 같은 컬럼을 추측 없이(별칭으로 한정해) 읽은 것. */
        final java.util.Set<String> guessed = new java.util.LinkedHashSet<>();
        final java.util.Set<String> firm = new java.util.HashSet<>();
        /** 함수 인자 자리의 자료형·날짜 단위 이름처럼 컬럼으로 추측하면 안 되는 참조. */
        final IdentityHashMap<Expr.ColumnRef, Boolean> notColumns = new IdentityHashMap<>();
        int depth;
        /** OR·NOT 아래에 있는 깊이. 0일 때의 조건만 항상 참이어야 하는 조건이다. */
        int disjunction;

        // ---- 구문 ----

        StatementFacts finish() {
            for (JoinGroup g : groups) {
                // 같은 테이블의 같은 컬럼끼리만 묶인 조건(X = X)은 관계 정보가 없다
                if (g.left.effName.equals(g.right.effName) && g.leftColumns.equals(g.rightColumns)) {
                    continue;
                }
                facts.join(new StatementFacts.Join(g.left.effName, g.right.effName, g.leftColumns, g.rightColumns, g.pos,
                        fixedOf(g.left), fixedOf(g.right), g.wraps));
            }
            groups.clear();
            dropReadsOfViewTargets();
            guessed.removeAll(firm);
            facts.closeGuesses(guessed);
            return facts;
        }

        private Map<String, java.util.Set<String>> fixedOf(Base base) {
            Map<String, java.util.Set<String>> out = new LinkedHashMap<>();
            Map<String, String> columns = fixed.get(base);
            if (columns != null) {
                columns.forEach((column, value) -> out.put(column, java.util.Set.of(value)));
            }
            return out;
        }

        /**
         * 인라인 뷰를 거친 DML({@code UPDATE (SELECT .. FROM a JOIN b ..) SET ..})에서 갱신되는 테이블 인스턴스는 대상이지 읽는 테이블이 아니다.
         * 그 테이블을 읽는 인스턴스가 모두 이런 대상이면 테이블 수준 읽기를 지운다. 서브쿼리 등에서 따로 읽는 인스턴스가 있으면 남긴다.
         */
        private void dropReadsOfViewTargets() {
            Map<String, Integer> written = new HashMap<>();
            for (Base base : writtenThroughView.keySet()) {
                written.merge(base.refName, 1, Integer::sum);
            }
            written.forEach((table, count) -> {
                if (readInstances.getOrDefault(table, 0) <= count) {
                    facts.removeRead(table);
                }
            });
            writtenThroughView.clear();
        }

        private void noteRead(String table, boolean lock, Pos pos) {
            readInstances.merge(table, 1, Integer::sum);
            facts.read(table, false, lock, pos);
        }

        void statement(Statement st, Scope parent) {
            if (st instanceof Statement.Select s) {
                facts.noteType("SELECT");
                query(s.query(), parent);
            } else if (st instanceof Statement.Insert s) {
                facts.noteType("INSERT");
                insert(s, parent);
            } else if (st instanceof Statement.Update s) {
                facts.noteType("UPDATE");
                update(s, parent);
            } else if (st instanceof Statement.Delete s) {
                facts.noteType("DELETE");
                delete(s, parent);
            } else if (st instanceof Statement.Merge s) {
                facts.noteType("MERGE");
                merge(s, parent);
            } else if (st instanceof Statement.Call s) {
                facts.noteType("CALL");
                if (!s.routine().anyDynamic()) {
                    facts.call(s.routine().parts(), true, s.routine().pos());
                } else {
                    facts.markDynamic();
                }
                Scope scope = new Scope(parent);
                s.args().forEach(a -> expr(a, scope));
            } else if (st instanceof Statement.Truncate s) {
                facts.noteType("DELETE");
                writeNamed(s.table(), "DELETE", true);
            } else if (st instanceof Statement.LockTable s) {
                facts.noteType("OTHER");
                for (QName name : s.tables()) {
                    if (name.dynamic()) {
                        facts.dynamicTable(false, null, name.rawName(), name.pos());
                    } else if (dummyName(name) == null) {
                        noteRead(name.name(), true, name.pos());
                    }
                }
            } else if (st instanceof Statement.WithDml s) {
                Scope scope = new Scope(parent);
                defineCtes(s.ctes(), scope);
                statement(s.dml(), scope);
            } else if (st instanceof Statement.Sequence s) {
                // 한 텍스트에 연달아 든 구문: 같은 주인에 합친다(F-006-R16)
                for (Statement inner : s.statements()) {
                    statement(inner, parent);
                }
            } else if (st instanceof Statement.Block s) {
                if (s.dynamic()) {
                    facts.markDynamic();
                }
                if (s.statements().isEmpty()) {
                    facts.noteType("OTHER");
                }
                for (Statement inner : s.statements()) {
                    statement(inner, parent);
                }
            } else if (st instanceof Ddl.CreateTable s && s.asQuery() != null) {
                facts.noteType("INSERT");
                writeNamed(s.name(), "INSERT", false);
                query(s.asQuery(), parent);
            } else if (st instanceof Statement.Other s) {
                facts.noteType("OTHER");
                if (s.dynamic()) {
                    facts.markDynamic();
                }
            } else {
                facts.noteType("OTHER");
            }
        }

        void readNamed(QName name) {
            if (name.size() == 1 && transientRelations.test(name.name())) {
                return;
            }
            if (name.dynamic()) {
                facts.dynamicTable(false, null, name.rawName(), name.pos());
            } else {
                String dummy = dummyName(name);
                if (dummy != null) {
                    facts.dummyTable(dummy);
                } else {
                    noteRead(name.name(), false, name.pos());
                    if (isSystemCatalog(name)) {
                        facts.systemCatalog(name.name()); // 구문 끝에서 건진 참조도 사전 테이블이면 그렇게 적는다(F-006-R41)
                    }
                }
            }
        }

        private void writeNamed(QName name, String op, boolean truncate) {
            if (name.dynamic()) {
                facts.dynamicTable(true, op, name.rawName(), name.pos());
            } else {
                facts.write(name.name(), op, truncate, name.pos());
            }
        }

        private void insert(Statement.Insert s, Scope parent) {
            Derived sourceShape = s.source() == null ? null : query(s.source(), parent);
            boolean multi = s.targets().size() > 1 || !s.conditions().isEmpty();
            Scope valueScope = new Scope(parent);
            if (multi && sourceShape != null) {
                valueScope.relations.add(sourceShape);
            }
            for (Expr c : s.conditions()) {
                expr(c, valueScope);
            }
            Relation firstTarget = null;
            for (Statement.InsertTarget t : s.targets()) {
                Scope targetScope = new Scope(parent);
                Relation target = dmlTarget(t.target(), targetScope, "INSERT");
                if (firstTarget == null) {
                    firstTarget = target;
                }
                boolean any = false;
                for (QName column : t.columns()) {
                    any |= writeColumn(target, column, "INSERT");
                }
                derivedTargetFallback(target, any, "INSERT");
                for (List<Expr> row : t.values()) {
                    row.forEach(v -> expr(v, valueScope));
                }
            }
            if (firstTarget != null && !s.returning().isEmpty()) {
                Scope scope = new Scope(parent);
                scope.relations.add(firstTarget);
                s.returning().forEach(r -> expr(r, scope));
            }
        }

        private void update(Statement.Update s, Scope parent) {
            Scope scope = new Scope(parent);
            Relation target = dmlTarget(s.target(), scope, "UPDATE");
            for (TableRef ref : s.from()) {
                tableRef(ref, scope, false);
            }
            boolean any = assignments(s.assignments(), target, scope, "UPDATE");
            derivedTargetFallback(target, any, "UPDATE");
            condition(s.where(), scope);
            s.returning().forEach(r -> expr(r, scope));
        }

        private boolean assignments(List<Statement.Assignment> list, Relation target, Scope scope, String op) {
            boolean any = false;
            for (Statement.Assignment a : list) {
                if (isRecordAssignment(a, target)) {
                    any = true; // PL/SQL의 SET ROW = 레코드: 행 전체를 쓴다. ROW는 컬럼이 아니다
                    continue;
                }
                for (QName column : a.columns()) {
                    any |= writeColumn(target, column, op);
                }
                expr(a.value(), scope);
            }
            return any;
        }

        private boolean isRecordAssignment(Statement.Assignment a, Relation target) {
            if (a.columns().size() != 1) {
                return false;
            }
            QName column = a.columns().get(0);
            return column.size() == 1 && !column.rawName().startsWith("\"") && column.name().equals("ROW")
                    && a.value() instanceof Expr.ColumnRef && has(target, "ROW", 0) != Has.YES;
        }

        private void delete(Statement.Delete s, Scope parent) {
            Scope scope = new Scope(parent);
            Relation target = dmlTarget(s.target(), scope, "DELETE");
            for (TableRef more : s.moreTargets()) {
                dmlTarget(more, scope, "DELETE"); // MySQL DELETE a, b FROM ..: 함께 지우는 테이블(조인 조건보다 먼저 스코프에 넣는다)
            }
            for (TableRef ref : s.from()) {
                tableRef(ref, scope, false);
            }
            derivedTargetFallback(target, false, "DELETE");
            condition(s.where(), scope);
            s.returning().forEach(r -> expr(r, scope));
        }

        private void merge(Statement.Merge s, Scope parent) {
            Scope scope = new Scope(parent);
            Relation target = dmlTarget(s.target(), scope, "MERGE");
            if (s.source() != null) {
                tableRef(s.source(), scope, false);
            }
            condition(s.on(), scope);
            boolean any = false;
            for (Statement.MergeAction a : s.actions()) {
                condition(a.condition(), scope);
                any |= assignments(a.assignments(), target, scope, "MERGE");
                for (QName column : a.insertColumns()) {
                    any |= writeColumn(target, column, "MERGE");
                }
                a.insertValues().forEach(v -> expr(v, scope));
                condition(a.where(), scope);
                condition(a.deleteWhere(), scope);
            }
            derivedTargetFallback(target, any, "MERGE");
        }

        /** DML 대상을 스코프에 넣는다. 이름이면 WRITES를 기록하고 READS는 만들지 않는다. */
        private Relation dmlTarget(TableRef ref, Scope scope, String op) {
            if (ref instanceof TableRef.Named n) {
                Base base = new Base();
                base.alias = alias(n.alias());
                if (n.name().anyDynamic()) {
                    facts.markDynamic();
                }
                if (n.name().dynamic()) {
                    facts.dynamicTable(true, op, n.name().rawName(), n.name().pos());
                    base.opaque = true;
                    base.dummy = true;
                    base.refName = base.effName = n.name().name();
                } else {
                    base.refName = n.name().name();
                    base.effName = catalog.resolveSynonym(base.refName);
                    base.def = catalog.table(base.effName);
                    facts.write(base.refName, op, false, n.name().pos());
                }
                scope.relations.add(base);
                return base;
            }
            if (ref instanceof TableRef.Derived d) {
                Derived shape = query(d.query(), scope.parent).copy(alias(d.alias()));
                scope.relations.add(shape);
                return shape;
            }
            if (ref instanceof TableRef.Function f) {
                expr(f.call(), scope);
                Derived opaque = new Derived();
                opaque.opaque = true;
                opaque.alias = alias(f.alias());
                scope.relations.add(opaque);
                return opaque;
            }
            return null;
        }

        /** @return 쓰기를 기록했으면 true */
        private boolean writeColumn(Relation target, QName column, String op) {
            if (column.anyDynamic()) {
                facts.markDynamic();
                return false;
            }
            String name = column.name();
            if (target instanceof Base b) {
                if (!guessUndeclared && has(b, name, 0) == Has.NO) {
                    return true; // 해석하지 못한 구간이 있는 구문: DDL에 없는 이름을 컬럼으로 만들지 않는다(F-006-R35)
                }
                if (!b.dummy && !b.opaque) {
                    facts.columnWrite(b.effName, name, op, column.pos());
                }
                return true;
            }
            if (target instanceof Derived d) {
                Origin o = origin(d, name);
                if (o != null && o.known()) {
                    facts.write(o.rel().refName, op, false, column.pos());
                    facts.columnWrite(o.rel().effName, o.column(), op, column.pos());
                    writtenThroughView.put(o.rel(), Boolean.TRUE);
                    return true;
                }
            }
            return false;
        }

        /** 인라인 뷰가 대상인데 어느 컬럼도 원본을 못 찾았으면, 원본 테이블이 하나일 때만 그 테이블을 쓴 것으로 본다. */
        private void derivedTargetFallback(Relation target, boolean anyWritten, String op) {
            if (!anyWritten && target instanceof Derived d && d.bases.size() == 1 && !d.bases.get(0).dummy) {
                facts.write(d.bases.get(0).refName, op, false, Pos.NONE);
                writtenThroughView.put(d.bases.get(0), Boolean.TRUE);
            }
        }

        // ---- 쿼리 ----

        private void defineCtes(List<Cte> ctes, Scope scope) {
            for (Cte cte : ctes) {
                String name = DbName.normalize(cte.name());
                // 재귀 참조용 자리: 컬럼 원본은 추적하지 않는다
                Derived placeholder = new Derived();
                placeholder.alias = name;
                if (cte.columns().isEmpty()) {
                    placeholder.opaque = true;
                } else {
                    cte.columns().forEach(c -> placeholder.add(DbName.normalize(c), null));
                }
                scope.ctes.put(name, placeholder);
                Derived shape = query(cte.query(), scope);
                Derived defined = shape.copy(name);
                if (!cte.columns().isEmpty()) {
                    defined = rename(shape, cte.columns(), name);
                }
                scope.ctes.put(name, defined);
            }
        }

        /** 컬럼 별칭 목록을 위치대로 적용한 새 모양. */
        private Derived rename(Derived shape, List<String> newNames, String alias) {
            Derived d = new Derived();
            d.alias = alias;
            d.bases.addAll(shape.bases);
            d.uncertain = shape.uncertain;
            boolean positionalKnown = shape.starSources.isEmpty() && !shape.opaque;
            for (int i = 0; i < newNames.size(); i++) {
                Origin o = positionalKnown && i < shape.positional.size() ? shape.positional.get(i) : null;
                d.add(DbName.normalize(newNames.get(i)), o);
            }
            return d;
        }

        Derived query(Query q, Scope outer) {
            Derived cached = shapes.get(q);
            if (cached != null) {
                return cached;
            }
            Derived result;
            if (++depth > 400) {
                depth--;
                result = new Derived();
                result.opaque = true;
                return result;
            }
            // 서브쿼리 안의 조건은 그 서브쿼리의 테이블 인스턴스에 대해 따로 본다(바깥이 OR 아래여도)
            int outerDisjunction = disjunction;
            disjunction = 0;
            try {
                Scope scope = outer;
                if (!q.ctes().isEmpty()) {
                    scope = new Scope(outer);
                    defineCtes(q.ctes(), scope);
                }
                Scope[] selectScope = new Scope[1];
                result = body(q.body(), scope, selectScope);
                Scope orderScope = selectScope[0] != null ? selectScope[0] : new Scope(scope);
                for (Expr e : q.orderBy()) {
                    if (e instanceof Expr.ColumnRef c && c.name().size() == 1 && result.columns.containsKey(c.name().name())) {
                        // 선택 목록의 이름(별칭 또는 단순 컬럼): 원본을 알면 그 컬럼을 읽은 것이다
                        Origin o = result.columns.get(c.name().name());
                        if (o != null && o.known()) {
                            facts.columnRead(o.rel().effName, o.column(), c.name().pos());
                        }
                        continue;
                    }
                    if (selectScope[0] == null && e instanceof Expr.ColumnRef) {
                        continue; // 집합 연산의 ORDER BY는 출력 컬럼 이름이다
                    }
                    expr(e, orderScope);
                }
                for (Expr e : q.tail()) {
                    expr(e, orderScope);
                }
                for (QName of : q.forUpdateOf()) {
                    expr(new Expr.ColumnRef(of, false), orderScope);
                }
            } finally {
                depth--;
                disjunction = outerDisjunction;
            }
            shapes.put(q, result);
            return result;
        }

        private Derived body(QueryBody body, Scope outer, Scope[] selectScope) {
            if (body instanceof Query.Select s) {
                return select(s, outer, selectScope);
            }
            if (body instanceof Query.SetOperation op) {
                Derived left = body(op.left(), outer, new Scope[1]);
                Derived right = body(op.right(), outer, new Scope[1]);
                Derived out = new Derived();
                out.bases.addAll(left.bases);
                out.bases.addAll(right.bases);
                // IN·비교의 서브쿼리로 쓰이면 분기마다 선택 컬럼을 본다(F-006-R30)
                out.branches.addAll(left.branches.isEmpty() ? List.of(left) : left.branches);
                out.branches.addAll(right.branches.isEmpty() ? List.of(right) : right.branches);
                out.uncertain = left.uncertain;
                if (left.opaque || !left.starSources.isEmpty()) {
                    out.opaque = true;
                }
                for (String name : left.names) {
                    out.add(name, null); // 집합 연산을 거친 컬럼은 원본을 추적하지 않는다
                }
                return out;
            }
            if (body instanceof Query.Values v) {
                Scope scope = new Scope(outer);
                v.rows().forEach(row -> row.forEach(e -> expr(e, scope)));
                Derived out = new Derived();
                out.opaque = true;
                return out;
            }
            if (body instanceof Query.Nested n) {
                return query(n.query(), outer);
            }
            Derived out = new Derived();
            out.opaque = true;
            return out;
        }

        private Derived select(Query.Select s, Scope outer, Scope[] selectScope) {
            Scope scope = new Scope(outer);
            scope.hierarchical = s.connectBy() != null || s.startWith() != null;
            selectScope[0] = scope;
            for (TableRef ref : s.from()) {
                tableRef(ref, scope, false);
            }
            Derived out = new Derived();
            for (Relation rel : scope.relations) {
                if (rel instanceof Base b) {
                    out.bases.add(b);
                } else if (rel instanceof Derived d) {
                    out.bases.addAll(d.bases);
                }
            }
            for (SelectItem item : s.items()) {
                if (item.alias() != null) {
                    scope.aliases.add(aliasName(item.alias()));
                }
                if (item.expr() instanceof Expr.Unparsed || item.expr() instanceof Expr.Missing
                        || (item.expr() instanceof Expr.ColumnRef c && c.name().anyDynamic())
                        || (item.expr() instanceof Expr.Star star && star.qualifier() != null && star.qualifier().anyDynamic())) {
                    out.uncertain = true;
                }
            }
            for (SelectItem item : s.items()) {
                Expr e = item.expr();
                if (e instanceof Expr.Star star) {
                    if (star.qualifier() == null) {
                        for (Relation rel : scope.relations) {
                            markStar(rel);
                            out.addStar(rel);
                        }
                        if (scope.relations.isEmpty()) {
                            out.opaque = true;
                        }
                    } else {
                        Relation rel = findRelation(star.qualifier().name(), scope);
                        if (rel != null) {
                            markStar(rel);
                            out.addStar(rel);
                        } else {
                            out.opaque = true;
                        }
                    }
                    continue;
                }
                expr(e, scope);
                Origin origin = null;
                String name = item.alias() == null ? null : aliasName(item.alias());
                if (e instanceof Expr.ColumnRef c) {
                    Resolved r = resolve(c, scope);
                    if (r != null) {
                        origin = origin(r.rel(), r.column());
                    }
                    if (name == null && !c.name().anyDynamic()) {
                        name = c.name().name();
                    }
                }
                out.add(name, origin);
            }
            condition(s.where(), scope);
            condition(s.startWith(), scope);
            condition(s.connectBy(), scope);
            s.groupBy().forEach(g -> expr(g, scope));
            condition(s.having(), scope);
            return out;
        }

        private void markStar(Relation rel) {
            if (rel instanceof Base b && !b.dummy) {
                facts.read(b.refName, true, false, Pos.NONE);
            }
        }

        private String alias(String raw) {
            return raw == null ? null : DbName.normalize(raw);
        }

        /** 선택 항목 별칭. 문자열 리터럴로 쓴 별칭({@code AS 'x'})도 받는다. */
        private String aliasName(String raw) {
            if (raw.length() >= 2 && raw.charAt(0) == '\'' && raw.charAt(raw.length() - 1) == '\'') {
                return raw.substring(1, raw.length() - 1);
            }
            return DbName.normalize(raw);
        }

        /** DUAL, SYSIBM.SYSDUMMY1이면 표시용 이름, 아니면 null. */
        private String dummyName(QName name) {
            String n = name.name();
            if (name.rawName().startsWith("\"")) {
                return null;
            }
            if (n.equals("DUAL") && (name.size() == 1 || "SYS".equals(name.qualifier()))) {
                return "DUAL";
            }
            if (n.equals("SYSDUMMY1") && (name.size() == 1 || "SYSIBM".equals(name.qualifier()))) {
                return "SYSIBM.SYSDUMMY1";
            }
            return null;
        }

        private void tableRef(TableRef ref, Scope scope, boolean forceLateral) {
            if (ref instanceof TableRef.Named n) {
                named(n, scope);
            } else if (ref instanceof TableRef.Derived d) {
                Scope outer = (d.lateral() || forceLateral) ? scope : scope.parent;
                Derived shape = query(d.query(), outer).copy(alias(d.alias()));
                if (!d.columnAliases().isEmpty()) {
                    shape = rename(shape, d.columnAliases(), alias(d.alias()));
                }
                if (d.pivot()) {
                    shape.opaque = true;
                    shape.columns.replaceAll((k, v) -> Origin.NONE);
                    shape.starSources.clear();
                    shape.layout.removeIf(o -> o instanceof Relation);
                }
                scope.relations.add(shape);
            } else if (ref instanceof TableRef.Function f) {
                expr(f.call(), scope);
                Derived opaque = new Derived();
                opaque.opaque = true;
                opaque.alias = alias(f.alias());
                scope.relations.add(opaque);
            } else if (ref instanceof TableRef.Join j) {
                int start = scope.relations.size();
                tableRef(j.left(), scope, false);
                int mid = scope.relations.size();
                tableRef(j.right(), scope, j.type().endsWith("APPLY"));
                int end = scope.relations.size();
                condition(j.on(), scope);
                for (String column : j.using()) {
                    String name = DbName.normalize(column);
                    Origin l = originAmong(scope.relations.subList(start, mid), name);
                    Origin r = originAmong(scope.relations.subList(mid, end), name);
                    if (l != null && r != null) {
                        if (l.rel() != r.rel()) {
                            addJoin(l, r, false, Pos.NONE, StatementFacts.Join.PLAIN);
                        }
                        facts.columnRead(l.rel().effName, l.column(), Pos.NONE);
                        facts.columnRead(r.rel().effName, r.column(), Pos.NONE);
                    }
                }
            } else if (ref instanceof TableRef.Dml d) {
                statement(d.statement(), scope.parent);
                Derived opaque = new Derived();
                opaque.opaque = true;
                opaque.alias = alias(d.alias());
                scope.relations.add(opaque);
            }
        }

        /**
         * DB가 제공하는 사전 뷰·카탈로그 테이블인가(F-006-R40): 사전 스키마({@code SYS}, {@code SYSCAT}, {@code SYSIBM}, {@code SYSSTAT} 등)로
         * 한정했거나, Oracle 데이터 사전 뷰의 이름({@code USER_}·{@code ALL_}·{@code DBA_} + 알려진 이름, {@code V$..})이다.
         * {@code USER_INFO}처럼 접두사만 같은 프로젝트 테이블은 아니다. 사전 스키마로 한정한 참조는 이름과 상관없이 사전 테이블이다:
         * 이름이나 스키마를 따옴표·백틱으로 감쌌어도, 대소문자가 어떻든 같다({@code information_schema.`TABLES`}, F-006-R41).
         */
        private boolean isSystemCatalog(QName name) {
            if (name.size() >= 2) {
                return SYSTEM_SCHEMAS.contains(name.qualifier().toUpperCase(java.util.Locale.ROOT));
            }
            if (name.rawName().startsWith("\"")) {
                return false;
            }
            String n = name.name();
            if (n.startsWith("V$") || n.startsWith("GV$") || n.startsWith("V_$") || DICTIONARY_NAMES.contains(n)) {
                return true;
            }
            for (String prefix : DICTIONARY_PREFIXES) {
                if (n.startsWith(prefix) && DICTIONARY_VIEWS.contains(n.substring(prefix.length()))) {
                    return true;
                }
            }
            return false;
        }

        private void named(TableRef.Named n, Scope scope) {
            QName name = n.name();
            if (name.anyDynamic()) {
                facts.markDynamic();
            }
            if (name.dynamic()) {
                facts.dynamicTable(false, null, name.rawName(), name.pos());
                Base base = new Base();
                base.alias = alias(n.alias());
                base.refName = base.effName = name.name();
                base.dummy = true;
                base.opaque = true;
                scope.relations.add(base);
                return;
            }
            if (name.size() == 1) {
                Derived cte = scope.cte(name.name());
                if (cte != null) {
                    Derived instance = cte.copy(n.alias() == null ? name.name() : alias(n.alias()));
                    if (n.pivot()) {
                        instance.opaque = true;
                    }
                    scope.relations.add(instance);
                    return;
                }
                if (transientRelations.test(name.name())) {
                    Derived opaque = new Derived();
                    opaque.opaque = true;
                    opaque.alias = n.alias() == null ? name.name() : alias(n.alias());
                    scope.relations.add(opaque);
                    return;
                }
            }
            Base base = new Base();
            base.alias = alias(n.alias());
            base.refName = name.name();
            String dummy = dummyName(name);
            if (dummy != null) {
                base.dummy = true;
                base.effName = base.refName;
                facts.dummyTable(dummy);
            } else {
                base.effName = catalog.resolveSynonym(base.refName);
                base.def = catalog.table(base.effName);
                base.opaque = n.pivot();
                noteRead(base.refName, false, name.pos());
                if (isSystemCatalog(name)) {
                    facts.systemCatalog(base.refName);
                }
                if (!n.columnAliases().isEmpty() && !n.pivot()) {
                    // DB2 상관 이름 뒤의 컬럼 목록(FROM T AS X (C1, C2)): 컬럼을 위치대로 바꿔 부른다. DDL을 알면 원래 컬럼에 대응시키고,
                    // 모르면 바꿔 부른 이름이 어느 컬럼인지 알 수 없다(그 테이블의 컬럼이라고 만들지 않는다)
                    Derived renamed = new Derived();
                    renamed.alias = base.alias != null ? base.alias : base.refName;
                    renamed.bases.add(base);
                    List<String> columns = base.def != null && base.def.columnsKnown() ? new ArrayList<>(base.def.columns.keySet()) : List.of();
                    for (int i = 0; i < n.columnAliases().size(); i++) {
                        renamed.add(DbName.normalize(n.columnAliases().get(i)), i < columns.size() ? new Origin(base, columns.get(i)) : null);
                    }
                    scope.relations.add(renamed);
                    return;
                }
            }
            scope.relations.add(base);
        }

        // ---- 이름 해석 ----

        private Relation findRelation(String qualifier, Scope scope) {
            for (Scope s = scope; s != null; s = s.parent) {
                for (Relation rel : s.relations) {
                    if (matches(rel, qualifier)) {
                        return rel;
                    }
                }
            }
            return null;
        }

        private boolean matches(Relation rel, String qualifier) {
            if (rel.alias != null) {
                return rel.alias.equals(qualifier);
            }
            return rel instanceof Base b && b.refName.equals(qualifier);
        }

        private Has has(Relation rel, String column, int guard) {
            if (guard > 50) {
                return Has.UNKNOWN;
            }
            if (rel instanceof Base b) {
                if (b.dummy && !b.opaque) {
                    return Has.NO;
                }
                if (b.opaque || b.def == null || !b.def.columnsKnown()) {
                    return Has.UNKNOWN;
                }
                return b.def.hasColumn(column) ? Has.YES : Has.NO;
            }
            Derived d = (Derived) rel;
            if (d.columns.containsKey(column)) {
                return Has.YES;
            }
            if (d.opaque) {
                return Has.UNKNOWN;
            }
            if (d.starSources.isEmpty()) {
                return Has.NO;
            }
            boolean unknown = false;
            for (Relation source : d.starSources) {
                Has h = has(source, column, guard + 1);
                if (h == Has.YES) {
                    return Has.YES;
                }
                unknown |= h == Has.UNKNOWN;
            }
            return unknown ? Has.UNKNOWN : Has.NO;
        }

        private Resolved resolve(Expr.ColumnRef ref, Scope scope) {
            QName name = ref.name();
            if (name.anyDynamic()) {
                facts.markDynamic();
                return null;
            }
            String column = name.name();
            if (name.size() >= 2) {
                Relation rel = findRelation(name.part(1), scope);
                if (rel == null && name.size() >= 3) {
                    // 별칭.컬럼.속성…(Oracle JSON 점 표기, 객체 타입 속성): 첫 조각이 스코프의 릴레이션이면 둘째 조각이 컬럼이다
                    Relation first = findRelation(name.parts().get(0), scope);
                    return first == null ? null : new Resolved(first, name.parts().get(1));
                }
                if (rel == null) {
                    return null;
                }
                if (!name.rawName().startsWith("\"") && QUALIFIED_PSEUDO.contains(column) && has(rel, column, 0) != Has.YES) {
                    return null;
                }
                if (!guessUndeclared && has(rel, column, 0) == Has.NO) {
                    return null; // 해석하지 못한 구간이 있는 구문: DDL로 확정되는 컬럼만 남긴다(F-006-R35)
                }
                return new Resolved(rel, column);
            }
            Scope innermost = null;
            for (Scope s = scope; s != null; s = s.parent) {
                if (s.relations.isEmpty()) {
                    continue;
                }
                Relation found = null;
                int yes = 0;
                Relation unknownRel = null;
                int unknown = 0;
                for (Relation rel : s.relations) {
                    Has h = has(rel, column, 0);
                    if (h == Has.YES) {
                        yes++;
                        found = rel;
                    } else if (h == Has.UNKNOWN) {
                        unknown++;
                        unknownRel = rel;
                    }
                }
                if (yes == 1) {
                    return new Resolved(found, column);
                }
                if (yes > 1) {
                    return null; // 모호
                }
                if (unknown == 0) {
                    if (innermost == null && s.relations.stream().anyMatch(rel -> !(rel instanceof Base b && b.dummy))) {
                        innermost = s;
                    }
                    continue; // 이 스코프에는 확실히 없음: 바깥으로
                }
                if (isPseudoIn(name, column, scope) || variables.test(column)) {
                    return null;
                }
                if (unknown == 1) {
                    // 카탈로그가 모르는 릴레이션이 하나뿐이고 나머지에는 확실히 없다: 가장 안쪽 스코프의 그 테이블 컬럼으로 본다
                    return new Resolved(unknownRel, column);
                }
                return null;
            }
            return innermost == null || !guessUndeclared || notColumns.containsKey(ref) ? null : undeclared(name, column, scope, innermost);
        }

        /**
         * 보이는 스코프의 모든 릴레이션에 카탈로그상 확실히 없는 비한정 이름(F-006-R31). 컬럼이 아닌 이름(의사 컬럼, 변수, SELECT 별칭,
         * 괄호 없이 부른 함수, 따옴표 이름)을 걸러 낸 뒤, 그 이름을 볼 수 있는 가장 안쪽 스코프에 실제 테이블이 하나뿐이면 그 테이블의
         * 컬럼으로 본다(DDL이 낡았거나 일부만 있다. 컬럼 노드는 참조로만 생긴다). 테이블이 여럿이거나 루틴 본문 안이면 소속을 추측하지 않고
         * 진단으로 알린다.
         *
         * @param innermost 더미 테이블이 아닌 릴레이션이 있는 가장 안쪽 스코프
         */
        private Resolved undeclared(QName name, String column, Scope scope, Scope innermost) {
            if (routineBody && !scopeComplete) {
                return null; // 변수 스코프를 다 알지 못하는 자리: 변수일 수 있는 이름을 컬럼이라고 말하지 않는다
            }
            if (Keywords.EXPR_STOP.contains(column) || Keywords.NEVER_GUESSED_COLUMNS.contains(column)) {
                return null; // SQL 키워드: 해석하지 못한 표기의 낱말일 뿐이다. DDL에 그 이름의 컬럼이 있을 때만 컬럼이다(F-006-R35)
            }
            if (name.rawName().startsWith("\"") || isPseudo(name, column) || variables.test(column) || SELECT_MODIFIERS.contains(column)
                    || catalog.hasRoutine(null, column) || (currentPackage != null && catalog.hasRoutine(currentPackage, column))) {
                return null;
            }
            for (Scope s = scope; s != null; s = s.parent) {
                if (s.aliases.contains(column)) {
                    return null; // GROUP BY·HAVING·ORDER BY에서 SELECT 별칭을 쓴 것
                }
            }
            List<Base> candidates = new ArrayList<>();
            int relations = 0;
            boolean onlyTables = true;
            for (Relation rel : innermost.relations) {
                if (rel instanceof Base b && b.dummy) {
                    continue;
                }
                relations++;
                onlyTables &= rel instanceof Base;
                if (!candidates(rel, candidates, 0)) {
                    return null; // 컬럼 목록을 믿을 수 없는 릴레이션이 끼어 있다: 모름
                }
            }
            if (candidates.isEmpty()) {
                return null; // 컬럼 목록이 분명한 인라인 뷰·CTE에 없는 이름: 테이블의 컬럼이라고 말할 근거가 없다
            }
            java.util.Set<String> tables = new java.util.LinkedHashSet<>();
            candidates.forEach(b -> tables.add(b.effName));
            // 릴레이션이 하나(테이블, 또는 *로 테이블 하나를 통째로 내보낸 인라인 뷰)이거나 같은 테이블의 인스턴스뿐이다(자기 조인).
            // 루틴 본문의 구문도 같은 규칙이다(F-008-R17): 변수·매개변수와 암묵 변수는 위에서 걸렀다
            if (tables.size() == 1 && (relations == 1 || onlyTables)) {
                guessed.add(candidates.get(0).effName + "." + column);
                return new Resolved(candidates.get(0), column, true);
            }
            for (Base b : candidates) {
                if (!b.def.declared || b.def.view) {
                    return null; // ALTER로만 본 테이블, 컬럼을 정의 쿼리에서 유도한 뷰: 컬럼 목록이 다 있다고 말할 수 없다
                }
            }
            facts.unknownColumn(column, tables, name.pos());
            return null;
        }

        /**
         * 릴레이션에서 그 이름이 올 수 있는 실제 테이블 인스턴스를 모은다: 테이블이면 그것, 인라인 뷰·CTE면 {@code *}로 통째로 내보낸
         * 원본들(선택 목록에 이름을 적은 항목은 이미 "없다"로 판정됐다).
         *
         * @return 컬럼 목록을 믿을 수 있으면 true
         */
        private boolean candidates(Relation rel, List<Base> into, int guard) {
            if (guard > 50) {
                return false;
            }
            if (rel instanceof Base b) {
                if (b.dummy) {
                    return !b.opaque;
                }
                if (b.opaque || b.def == null || !b.def.columnsKnown()) {
                    return false;
                }
                if (!into.contains(b)) {
                    into.add(b);
                }
                return true;
            }
            Derived d = (Derived) rel;
            if (d.opaque || d.uncertain) {
                return false;
            }
            for (Relation source : d.starSources) {
                if (!candidates(source, into, guard + 1)) {
                    return false;
                }
            }
            return true;
        }

        private boolean isPseudo(QName name, String column) {
            return !name.rawName().startsWith("\"") && Keywords.PSEUDO_COLUMNS.contains(column);
        }

        /**
         * DDL을 모르는 테이블의 컬럼일 수 있는 자리에서 의사 컬럼인가. {@code LEVEL}·{@code CONNECT_BY_ISLEAF}·{@code CONNECT_BY_ISCYCLE}은
         * 그 이름이 보이는 스코프 가운데 계층 쿼리가 있을 때만 의사 컬럼이다: {@code CONNECT BY}가 없는 구문의 {@code LEVEL}은 컬럼이다(F-006-R33).
         */
        private boolean isPseudoIn(QName name, String column, Scope scope) {
            if (!isPseudo(name, column)) {
                return false;
            }
            if (!HIERARCHICAL_PSEUDO.contains(column)) {
                return true;
            }
            for (Scope s = scope; s != null; s = s.parent) {
                if (s.hierarchical) {
                    return true;
                }
            }
            return false;
        }

        /** 릴레이션의 컬럼이 어느 테이블 인스턴스의 어느 컬럼인지. 식이거나 알 수 없으면 null. */
        private Origin origin(Relation rel, String column) {
            return origin(rel, column, 0);
        }

        private Origin origin(Relation rel, String column, int guard) {
            if (guard > 50) {
                return null;
            }
            if (rel instanceof Base b) {
                return b.dummy || b.opaque ? null : new Origin(b, column);
            }
            Derived d = (Derived) rel;
            Origin explicit = d.columns.get(column);
            if (explicit != null) {
                return explicit.known() ? explicit : null;
            }
            if (d.opaque) {
                return null;
            }
            Relation yes = null;
            int yesCount = 0;
            Relation unknownRel = null;
            int unknownCount = 0;
            for (Relation source : d.starSources) {
                Has h = has(source, column, guard + 1);
                if (h == Has.YES) {
                    yesCount++;
                    yes = source;
                } else if (h == Has.UNKNOWN) {
                    unknownCount++;
                    unknownRel = source;
                }
            }
            if (yesCount == 1) {
                return origin(yes, column, guard + 1);
            }
            if (yesCount == 0 && unknownCount == 1) {
                return origin(unknownRel, column, guard + 1);
            }
            return null;
        }

        /** 주어진 릴레이션들 가운데 비한정 컬럼의 원본(USING용). */
        private Origin originAmong(List<Relation> relations, String column) {
            Relation yes = null;
            int yesCount = 0;
            Relation unknownRel = null;
            int unknownCount = 0;
            for (Relation rel : relations) {
                Has h = has(rel, column, 0);
                if (h == Has.YES) {
                    yesCount++;
                    yes = rel;
                } else if (h == Has.UNKNOWN) {
                    unknownCount++;
                    unknownRel = rel;
                }
            }
            if (yesCount == 1) {
                return origin(yes, column);
            }
            if (yesCount == 0 && unknownCount == 1) {
                return origin(unknownRel, column);
            }
            return null;
        }

        private Origin originOf(Expr.ColumnRef ref, Scope scope) {
            if (ref.name().size() >= 3 && !ref.name().anyDynamic() && findRelation(ref.name().part(1), scope) == null) {
                return null; // 별칭.컬럼.속성: 컬럼의 속성을 비교한 것이지 컬럼 값을 비교한 것이 아니다(조인·고정 컬럼의 근거가 아니다)
            }
            Resolved r = resolve(ref, scope);
            if (r == null) {
                return null;
            }
            Origin o = origin(r.rel(), r.column());
            return o != null && o.known() ? o : null;
        }

        // ---- 식 ----

        /** 조건 식: AND/OR/NOT 구조를 따라 내려가며 등치 조건에서 조인을 뽑는다. */
        private void condition(Expr e, Scope scope) {
            if (e == null) {
                return;
            }
            if (++depth > 400) {
                depth--;
                return;
            }
            try {
                if (e instanceof Expr.Binary b && b.op().equals("AND")) {
                    condition(b.left(), scope);
                    condition(b.right(), scope);
                } else if (e instanceof Expr.Binary b && b.op().equals("OR")) {
                    disjunction++;
                    try {
                        condition(b.left(), scope);
                        condition(b.right(), scope);
                    } finally {
                        disjunction--;
                    }
                } else if (e instanceof Expr.Unary u && u.op().equals("NOT")) {
                    disjunction++;
                    try {
                        condition(u.operand(), scope);
                    } finally {
                        disjunction--;
                    }
                } else if (e instanceof Expr.Binary b && b.op().equals("=")) {
                    expr(b.left(), scope);
                    expr(b.right(), scope);
                    equality(b.left(), b.right(), scope);
                    fixedBy(b.left(), b.right(), scope);
                } else if (e instanceof Expr.In in) {
                    expr(e, scope);
                    if (in.subquery() != null) {
                        // 튜플 NOT IN (서브쿼리)는 "이 조합이 없다"는 반조인이다: 컬럼 쌍을 하나씩 떼어 조인으로 보지 않는다(F-006-R20)
                        if (!(in.not() && in.left() instanceof Expr.Tuple)) {
                            Derived shape = query(in.subquery(), scope);
                            inJoin(in.left(), shape, scope);
                        }
                    } else if (!in.not() && in.list().size() == 1 && !(in.list().get(0) instanceof Expr.Bind)) {
                        // IN (리터럴 하나)는 등치와 같다. IN (바인드 하나)는 MyBatis <foreach>를 한 번만 전개한 모양일 수 있어 고정으로 보지 않는다
                        fixedBy(in.left(), in.list().get(0), scope);
                    }
                } else {
                    expr(e, scope);
                }
            } finally {
                depth--;
            }
        }

        private void equality(Expr left, Expr right, Scope scope) {
            boolean leftPrior = left instanceof Expr.Unary u && u.op().equals("PRIOR");
            boolean rightPrior = right instanceof Expr.Unary u && u.op().equals("PRIOR");
            Expr l = leftPrior ? ((Expr.Unary) left).operand() : left;
            Expr r = rightPrior ? ((Expr.Unary) right).operand() : right;
            Operand lop = operand(l, scope);
            Operand rop = operand(r, scope);
            int wrap = (lop.wrapped() ? StatementFacts.Join.WRAP_LEFT : 0) | (rop.wrapped() ? StatementFacts.Join.WRAP_RIGHT : 0);
            for (Origin lo : lop.origins()) {
                for (Origin ro : rop.origins()) {
                    if (lo.rel() != ro.rel()) {
                        addJoin(lo, ro, false, lop.pos(), wrap);
                    } else if (leftPrior != rightPrior && !lo.column().equals(ro.column())) {
                        // CONNECT BY PRIOR a = b: 같은 테이블의 부모 행과 자식 행
                        addJoin(lo, ro, true, lop.pos(), wrap);
                    }
                }
            }
        }

        /**
         * {@code 컬럼 = 상수} 꼴이면 그 컬럼을 테이블 인스턴스의 고정 컬럼으로 적는다(F-006-R13). OR·NOT 아래의 조건은 항상 참이 아니므로 적지 않는다.
         */
        private void fixedBy(Expr left, Expr right, Scope scope) {
            if (disjunction > 0) {
                return;
            }
            Expr column = left;
            String value = constant(right, scope);
            if (value == null) {
                column = right;
                value = constant(left, scope);
            }
            if (value == null || !(column instanceof Expr.ColumnRef c)) {
                return;
            }
            Origin o = originOf(c, scope);
            if (o != null) {
                fixed.computeIfAbsent(o.rel(), k -> new LinkedHashMap<>()).putIfAbsent(o.column(), value);
            }
        }

        /**
         * 식이 구문 실행 동안 값 하나로 정해지는 상수면 그 표시 값, 아니면 null. 문자열 리터럴은 따옴표를 뗀 값, 숫자 등 다른 리터럴은 원문,
         * 바인드와 루틴 본문의 변수는 {@code ?}다. {@code NULL}과의 비교는 등치가 아니므로 상수로 보지 않는다.
         */
        private String constant(Expr e, Scope scope) {
            if (e instanceof Expr.Bind) {
                return "?";
            }
            if (e instanceof Expr.Literal l) {
                String text = l.text();
                if (text == null || text.isBlank() || text.equalsIgnoreCase("NULL")) {
                    return null;
                }
                return unquote(text);
            }
            if (e instanceof Expr.Unary u && (u.op().equals("-") || u.op().equals("+")) && u.operand() instanceof Expr.Literal l
                    && !l.text().isEmpty() && (Character.isDigit(l.text().charAt(0)) || l.text().charAt(0) == '.')) {
                return u.op().equals("-") ? "-" + l.text() : l.text();
            }
            if (e instanceof Expr.ColumnRef c && c.name().size() == 1 && !c.name().anyDynamic()
                    && variables.test(c.name().name()) && resolve(c, scope) == null) {
                return "?"; // 루틴의 변수·매개변수: 컬럼으로 풀리지 않았다
            }
            if (e instanceof Expr.ColumnRef c && c.name().size() >= 2 && !c.name().anyDynamic()
                    && variables.test(c.name().parts().get(0)) && resolve(c, scope) == null) {
                return "?"; // 레코드·루프 변수의 필드(r.col, v_row.col): 테이블 별칭이 아니라 변수다
            }
            return null;
        }

        /** {@code 'a''b'}, {@code N'..'} 꼴이면 따옴표를 뗀 값, 아니면 원문 그대로. */
        private String unquote(String text) {
            int start = text.startsWith("'") ? 0 : (text.length() > 1 && (text.charAt(0) == 'N' || text.charAt(0) == 'n') && text.charAt(1) == '\'' ? 1 : -1);
            if (start >= 0 && text.length() >= start + 2 && text.endsWith("'")) {
                return text.substring(start + 1, text.length() - 1).replace("''", "'");
            }
            return text;
        }

        /**
         * 등치의 한쪽이 가리키는 컬럼: 단순 컬럼, 컬럼 하나만 감싼 형 변환·정리 함수(F-006-R29), 단일 컬럼을 고르는 서브쿼리
         * (집합 연산이면 분기마다, F-006-R30). 그 밖의 식이면 원본이 없다.
         */
        private Operand operand(Expr e, Scope scope) {
            Expr.ColumnRef column = e instanceof Expr.ColumnRef c ? c : wrappedColumn(e, 0);
            if (column != null) {
                Origin o = originOf(column, scope);
                if (o != null) {
                    return new Operand(List.of(o), column != e, column.name().pos());
                }
                List<Origin> branches = unionOrigins(column, scope);
                return branches.isEmpty() ? Operand.NONE : new Operand(branches, column != e, column.name().pos());
            }
            if (e instanceof Expr.Subquery s) {
                Derived shape = query(s.query(), scope);
                List<Origin> origins = new ArrayList<>();
                for (Derived branch : shape.branches.isEmpty() ? List.of(shape) : shape.branches) {
                    if (branch.positional.size() == 1 && branch.starSources.isEmpty() && !branch.opaque) {
                        Origin o = branch.positional.get(0);
                        if (o != null && o.known()) {
                            origins.add(o);
                        }
                    }
                }
                return new Operand(origins, false, Pos.NONE);
            }
            return Operand.NONE;
        }

        /**
         * 컬럼이 집합 연산인 인라인 뷰·CTE의 것이면 분기마다 그 자리(선택 목록의 같은 위치)의 원본 컬럼을 모은다(F-006-R38).
         * 선택 항목이 단순 컬럼이 아니거나({@code *}, 식, 리터럴) 컬럼 수가 모자란 분기는 건너뛴다.
         */
        private List<Origin> unionOrigins(Expr.ColumnRef column, Scope scope) {
            if (column.name().size() >= 3 || column.name().anyDynamic()) {
                return List.of();
            }
            Resolved r = resolve(column, scope);
            if (r == null || !(r.rel() instanceof Derived d) || d.branches.isEmpty()) {
                return List.of();
            }
            int index = d.names.indexOf(r.column());
            if (index < 0) {
                return List.of();
            }
            List<Origin> origins = new ArrayList<>();
            for (Derived branch : d.branches) {
                if (branch.opaque || !branch.starSources.isEmpty() || index >= branch.positional.size()) {
                    continue;
                }
                Origin o = branch.positional.get(index);
                if (o != null && o.known()) {
                    origins.add(o);
                }
            }
            return origins;
        }

        /**
         * 식이 컬럼 하나만 감싼 형 변환·정리 함수({@link #WRAPPERS}, 중첩 가능)면 그 컬럼, 아니면 null(F-006-R29).
         * 컬럼 말고 다른 인자는 리터럴이어야 한다({@code TO_CHAR(x, 'fmt')}, {@code NVL(x, 'N')}, {@code TRIM(BOTH ' ' FROM x)}).
         * 연산식, 값을 바꾸는 함수, 컬럼이 둘 이상 든 함수, 사용자 정의 루틴과 이름이 같은 함수는 해당하지 않는다.
         */
        private Expr.ColumnRef wrappedColumn(Expr e, int guard) {
            if (guard > 20 || !(e instanceof Expr.FuncCall f) || f.name().size() != 1 || f.name().anyDynamic()
                    || f.name().rawName().startsWith("\"") || !f.extras().isEmpty() || f.args().isEmpty()) {
                return null;
            }
            String fn = f.name().name();
            if (!WRAPPERS.contains(fn) || variables.test(fn) || catalog.hasRoutine(null, fn)
                    || (currentPackage != null && catalog.hasRoutine(currentPackage, fn))) {
                return null;
            }
            Expr inner = null;
            for (int i = 0; i < f.args().size(); i++) {
                Expr arg = f.args().get(i);
                if (isLiteral(arg)) {
                    continue;
                }
                // 컬럼 자리는 첫 인자다. TRIM만 "TRIM(BOTH 'x' FROM col)"처럼 뒤에 온다
                if (inner != null || (i > 0 && !fn.equals("TRIM"))) {
                    return null;
                }
                inner = arg;
            }
            if (inner instanceof Expr.ColumnRef c) {
                return c;
            }
            return inner == null ? null : wrappedColumn(inner, guard + 1);
        }

        private boolean isLiteral(Expr e) {
            return e instanceof Expr.Literal
                    || (e instanceof Expr.Unary u && (u.op().equals("-") || u.op().equals("+")) && u.operand() instanceof Expr.Literal);
        }

        private void inJoin(Expr left, Derived shape, Scope scope) {
            if (!shape.branches.isEmpty()) {
                // 집합 연산: 분기마다 그 분기의 선택 컬럼과 잇는다(F-006-R30)
                for (Derived branch : shape.branches) {
                    inJoin(left, branch, scope);
                }
                return;
            }
            if (shape.opaque || !shape.starSources.isEmpty()) {
                return;
            }
            List<Expr> lefts = left instanceof Expr.Tuple t ? t.items() : List.of(left);
            if (lefts.size() != shape.positional.size()) {
                return;
            }
            for (int i = 0; i < lefts.size(); i++) {
                Operand lop = lefts.get(i) instanceof Expr.Subquery ? Operand.NONE : operand(lefts.get(i), scope);
                Origin ro = shape.positional.get(i);
                for (Origin lo : lop.origins()) {
                    if (ro != null && ro.known() && lo.rel() != ro.rel()) {
                        addJoin(lo, ro, false, lop.pos(), lop.wrapped() ? StatementFacts.Join.WRAP_LEFT : 0);
                    }
                }
            }
        }

        /** @param wrap 이 조건에서 함수로 감싸인 쪽(left·right 기준, {@link StatementFacts.Join#WRAP_LEFT} 등) */
        private void addJoin(Origin left, Origin right, boolean prior, Pos pos, int wrap) {
            for (JoinGroup g : groups) {
                if (g.prior != prior) {
                    continue;
                }
                if (g.left == left.rel() && g.right == right.rel()) {
                    addPair(g, left.column(), right.column(), prior, wrap);
                    return;
                }
                if (!prior && g.left == right.rel() && g.right == left.rel()) {
                    addPair(g, right.column(), left.column(), false, StatementFacts.Join.flip(wrap));
                    return;
                }
            }
            JoinGroup g = new JoinGroup();
            g.left = left.rel();
            g.right = right.rel();
            g.prior = prior;
            g.pos = pos;
            g.leftColumns.add(left.column());
            g.rightColumns.add(right.column());
            g.wraps.add(wrap);
            groups.add(g);
        }

        private void addPair(JoinGroup g, String leftColumn, String rightColumn, boolean prior, int wrap) {
            for (int i = 0; i < g.leftColumns.size(); i++) {
                if (g.leftColumns.get(i).equals(leftColumn) && g.rightColumns.get(i).equals(rightColumn)) {
                    // 같은 컬럼 쌍을 또 봤다: 한 번이라도 감싸지 않고 비교했으면 감싸인 조인이 아니다
                    g.wraps.set(i, StatementFacts.Join.combine(g.wraps.get(i), wrap));
                    return;
                }
                if (prior && g.leftColumns.get(i).equals(rightColumn) && g.rightColumns.get(i).equals(leftColumn)) {
                    g.wraps.set(i, StatementFacts.Join.combine(g.wraps.get(i), StatementFacts.Join.flip(wrap)));
                    return;
                }
            }
            g.leftColumns.add(leftColumn);
            g.rightColumns.add(rightColumn);
            g.wraps.add(wrap);
        }

        /** 값 식: 컬럼 읽기, 시퀀스, 함수 호출, 서브쿼리를 기록한다. 조인은 뽑지 않는다(서브쿼리 안의 조건은 그 안에서 뽑는다). */
        private void expr(Expr e, Scope scope) {
            if (e == null) {
                return;
            }
            if (++depth > 400) {
                depth--;
                return;
            }
            try {
                if (e instanceof Expr.ColumnRef c) {
                    Resolved r = resolve(c, scope);
                    if (r != null) {
                        Origin o = origin(r.rel(), r.column());
                        if (o != null && o.known()) {
                            facts.columnRead(o.rel().effName, o.column(), c.name().pos());
                            if (!r.guessed()) {
                                firm.add(o.rel().effName + "." + o.column());
                            }
                        }
                    }
                } else if (e instanceof Expr.FuncCall f) {
                    functionCall(f);
                    boolean convert = f.name().size() == 1 && f.name().name().equals("CONVERT");
                    if (f.name().size() == 1 && TABLE_DESIGNATOR_FUNCTIONS.contains(f.name().name())) {
                        // RID(t), RID_BIT(t): 인자는 컬럼이 아니라 테이블 지정자(상관 이름)다
                        for (Expr a : f.args()) {
                            if (!(a instanceof Expr.ColumnRef c && c.name().size() == 1 && !c.name().anyDynamic()
                                    && findRelation(c.name().name(), scope) != null)) {
                                expr(a, scope);
                            }
                        }
                        return;
                    }
                    for (Expr a : f.args()) {
                        // 인자 자리의 자료형·날짜 단위·문자셋 이름: CONVERT(FLOAT, x), CONVERT(x USING utf8), DATEADD(DAY, 1, x)
                        if (a instanceof Expr.ColumnRef c && c.name().size() == 1 && !c.name().anyDynamic()
                                && (convert || NON_COLUMN_ARGUMENTS.contains(c.name().name()) || c.name().name().startsWith("SQL_TSI_"))) {
                            notColumns.put(c, Boolean.TRUE);
                        }
                    }
                    f.args().forEach(a -> expr(a, scope));
                    f.extras().forEach(a -> expr(a, scope));
                } else if (e instanceof Expr.Binary b) {
                    expr(b.left(), scope);
                    expr(b.right(), scope);
                } else if (e instanceof Expr.Unary u) {
                    expr(u.operand(), scope);
                } else if (e instanceof Expr.Case c) {
                    expr(c.operand(), scope);
                    for (Expr.WhenClause w : c.whens()) {
                        expr(w.when(), scope);
                        expr(w.then(), scope);
                    }
                    expr(c.otherwise(), scope);
                } else if (e instanceof Expr.Subquery s) {
                    query(s.query(), scope);
                } else if (e instanceof Expr.Exists x) {
                    query(x.query(), scope);
                } else if (e instanceof Expr.In in) {
                    expr(in.left(), scope);
                    in.list().forEach(i -> expr(i, scope));
                    if (in.subquery() != null) {
                        query(in.subquery(), scope);
                    }
                } else if (e instanceof Expr.Between b) {
                    expr(b.value(), scope);
                    expr(b.low(), scope);
                    expr(b.high(), scope);
                } else if (e instanceof Expr.Tuple t) {
                    t.items().forEach(i -> expr(i, scope));
                } else if (e instanceof Expr.SequenceRef s) {
                    if (s.sequence().anyDynamic()) {
                        facts.markDynamic();
                    } else {
                        facts.sequence(s.sequence().name(), s.sequence().pos());
                    }
                }
            } finally {
                depth--;
            }
        }

        /** SQL 식 안의 함수 호출: 카탈로그에 루틴으로 정의된 이름일 때만 호출로 기록한다. */
        private void functionCall(Expr.FuncCall f) {
            QName name = f.name();
            if (name.anyDynamic()) {
                facts.markDynamic();
                return;
            }
            int n = name.size();
            String fn = name.name();
            if (n == 1) {
                if (variables.test(fn)) {
                    return; // 컬렉션 변수의 첨자 접근: v_tab(i)
                }
                if (currentPackage != null && catalog.hasRoutine(currentPackage, fn)) {
                    facts.call(List.of(currentPackage, fn), false, name.pos());
                } else if (catalog.hasRoutine(null, fn)) {
                    facts.call(List.of(fn), false, name.pos());
                }
            } else {
                String qualifier = name.part(1);
                if (catalog.hasRoutine(qualifier, fn)) {
                    facts.call(List.of(qualifier, fn), false, name.pos());
                } else if (n == 2 && !catalog.hasPackage(qualifier) && catalog.hasRoutine(null, fn)) {
                    facts.call(List.of(fn), false, name.pos());
                }
            }
        }

        // ---- 출력 컬럼 펼치기(뷰 컬럼 유도) ----

        /** @return 모든 자리를 펼쳤으면 true */
        boolean expand(Derived shape, List<OutputColumn> into, int guard) {
            if (guard > 20) {
                return false;
            }
            boolean complete = true;
            for (Object item : shape.layout) {
                if (item instanceof Integer i) {
                    Origin o = shape.positional.get(i);
                    boolean known = o != null && o.known();
                    into.add(new OutputColumn(shape.names.get(i), known ? o.rel().effName : null, known ? o.column() : null));
                } else if (item instanceof Base b) {
                    if (b.def != null && b.def.columnsKnown() && !b.opaque && !b.dummy) {
                        for (String column : b.def.columns.keySet()) {
                            into.add(new OutputColumn(column, b.effName, column));
                        }
                    } else {
                        complete = false;
                    }
                } else if (item instanceof Derived d) {
                    complete &= expand(d, into, guard + 1) && !d.opaque;
                }
            }
            return complete;
        }
    }
}
