package io.codeatlas.export.calltree;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.export.calltree.CallTreeNode.Marker;
import io.codeatlas.export.calltree.CrudMatrix.Row;
import io.codeatlas.export.calltree.CrudMatrix.Subject;
import io.codeatlas.export.calltree.ImpactReport.Item;
import io.codeatlas.export.fixture.GenericDaoGraph;
import io.codeatlas.export.fixture.GraphFixture;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 제네릭 상위 DAO·서비스의 팬아웃을 호출 지점의 수신자 타입으로 좁히는 규칙(F-015-R29). */
class ReceiverNarrowingTest {

    private static String callees(GraphModel g, String start) {
        return CallTrees.toText(CallTrees.build(g, start, CallTreeOptions.callees()), false);
    }

    private static String callers(GraphModel g, String start) {
        return CallTrees.toText(CallTrees.build(g, start, CallTreeOptions.callers()), false);
    }

    @Test
    @DisplayName("F-015-AC35 callees: 호출 엣지의 receiverType과 맞는 namespace의 EXECUTES만 따라가고, 맞는 것이 없으면 전부 [?]로, 수신자 타입을 모르면 전부 따라간다")
    void calleesFollowOnlyReceiverNamespace() {
        GenericDaoGraph dao = new GenericDaoGraph();
        GraphModel g = dao.f.graph();

        assertThat(callees(g, dao.getDictDirect)).isEqualTo("""
                [endpoint] GET /dict/direct
                └─ DictController#direct()
                   └─ CrudDao#findList(Object)
                      └─ ~ [sql] DictDao.findList <SELECT>
                         └─ [table] SYS_DICT (R)
                """);
        // 수신자 타입이 없는 호출: 좁힐 문맥이 없으므로 전과 같이 전부
        assertThat(callees(g, dao.getRaw)).isEqualTo("""
                [endpoint] GET /raw
                └─ RawController#all()
                   └─ CrudDao#findList(Object)
                      ├─ ~ [sql] DictDao.findList <SELECT>
                      │  └─ [table] SYS_DICT (R)
                      ├─ ~ [sql] LogDao.findList <SELECT>
                      │  └─ [table] SYS_LOG (R)
                      └─ ~ [sql] UserDao.findList <SELECT>
                         └─ [table] SYS_USER (R)
                """);
        // 수신자 타입은 있는데 그 타입(과 상·하위 타입)의 구문이 하나도 없다: 전부 따라가되 [?]
        assertThat(callees(g, dao.getOdd)).isEqualTo("""
                [endpoint] GET /odd
                └─ OddController#x()
                   └─ CrudDao#findList(Object)
                      ├─ ~ [?] [sql] DictDao.findList <SELECT>
                      │  └─ [table] SYS_DICT (R)
                      ├─ ~ [?] [sql] LogDao.findList <SELECT>
                      │  └─ [table] SYS_LOG (R)
                      └─ ~ [?] [sql] UserDao.findList <SELECT>
                         └─ [table] SYS_USER (R)
                """);
        CallTree odd = CallTrees.build(g, dao.getOdd, CallTreeOptions.callees());
        CallTreeNode uncertain = odd.root().children().get(0).children().get(0).children().get(0);
        assertThat(uncertain.markers()).contains(Marker.RECEIVER_UNCERTAIN);
        assertThat(CallTrees.toJson(odd)).contains("\"markers\": [\"?\"]");

        // 같은 메서드를 수신자 타입이 다른 두 곳에서 부르면 호출 지점마다 따로 펼친다
        String both = dao.f.method(GenericDaoGraph.PKG + "DictController", "both");
        dao.call(both, dao.daoFindList, 70, "interface", GenericDaoGraph.PKG + "UserDao");
        dao.call(both, dao.daoFindList, 72, "interface", GenericDaoGraph.PKG + "LogDao");
        assertThat(callees(g, both)).isEqualTo("""
                DictController#both()
                ├─ CrudDao#findList(Object)
                │  └─ ~ [sql] UserDao.findList <SELECT>
                │     └─ [table] SYS_USER (R)
                └─ CrudDao#findList(Object)
                   └─ ~ [sql] LogDao.findList <SELECT>
                      └─ [table] SYS_LOG (R)
                """);

        // 수신자가 중간 인터페이스(namespace의 상위)여도 그 아래 namespace만 남는다
        dao.f.type(GenericDaoGraph.PKG + "SubDictDao", "interface", "public");
        dao.f.typeEdge(GenericDaoGraph.PKG + "SubDictDao", GenericDaoGraph.PKG + "DictDao", EdgeKind.EXTENDS);
        String sub = dao.f.method(GenericDaoGraph.PKG + "DictController", "sub");
        dao.call(sub, dao.daoFindList, 80, "interface", GenericDaoGraph.PKG + "SubDictDao");
        assertThat(callees(g, sub)).contains("DictDao.findList").doesNotContain("UserDao.findList").doesNotContain("[?]");
    }

    @Test
    @DisplayName("F-015-AC36 제네릭 상위 서비스: 하위 서비스 타입으로 부른 상속 메서드 안의 DAO 호출은 그 서비스가 묶는 DAO의 namespace로 좁힌다 (super 호출, 타입 인자, 참조 타입)")
    void genericServiceContextNarrowsDaoCalls() {
        GenericDaoGraph dao = new GenericDaoGraph();
        GraphModel g = dao.f.graph();

        assertThat(callees(g, dao.getDict)).isEqualTo("""
                [endpoint] GET /dict
                └─ DictController#list()
                   └─ CrudService#findPage(Object)
                      └─ CrudDao#findList(Object)
                         └─ ~ [sql] DictDao.findList <SELECT>
                            └─ [table] SYS_DICT (R)
                """);
        // 재정의한 메서드가 super.save를 부른다: 문맥은 호출한 쪽의 타입(UserService), 타입 인자(typeArguments)가 UserDao다
        assertThat(callees(g, dao.postUser)).isEqualTo("""
                [endpoint] POST /user
                └─ UserController#save()
                   └─ UserService#save(Object)
                      └─ CrudService#save(Object) (super)
                         └─ CrudDao#insert(Object)
                            └─ ~ [sql] UserDao.insert <INSERT>
                               └─ [table] SYS_USER (C)
                """);
        // 타입 인자를 모르면 그 서비스가 참조하는 DAO 전부(DEPENDS_ON, INJECTS)로 좁힌다
        assertThat(callees(g, dao.postMix)).isEqualTo("""
                [endpoint] POST /mix
                └─ MixController#save()
                   └─ CrudService#save(Object)
                      └─ CrudDao#insert(Object)
                         ├─ ~ [sql] DictDao.insert <INSERT>
                         │  └─ [table] SYS_DICT (C)
                         └─ ~ [sql] LogDao.insert <INSERT>
                            └─ [table] SYS_LOG (C)
                """);
        // 상위 서비스의 메서드에서 바로 시작하면 문맥이 없다: 전부
        assertThat(callees(g, dao.serviceSave)).contains("DictDao.insert").contains("UserDao.insert").contains("LogDao.insert")
                .doesNotContain("[?]");

        // 문맥이 다르면 같은 메서드도 따로 펼친다([see above]로 접지 않는다)
        String both = dao.f.method(GenericDaoGraph.PKG + "DictController", "both");
        dao.call(both, dao.serviceFindPage, 70, "virtual", GenericDaoGraph.PKG + "DictService");
        dao.call(both, dao.serviceFindPage, 71, "virtual", GenericDaoGraph.PKG + "UserService");
        dao.call(both, dao.serviceFindPage, 72, "virtual", GenericDaoGraph.PKG + "DictService");
        assertThat(callees(g, both)).isEqualTo("""
                DictController#both()
                ├─ CrudService#findPage(Object)
                │  └─ CrudDao#findList(Object)
                │     └─ ~ [sql] DictDao.findList <SELECT>
                │        └─ [table] SYS_DICT (R)
                └─ CrudService#findPage(Object)
                   └─ CrudDao#findList(Object)
                      └─ ~ [sql] UserDao.findList <SELECT>
                         └─ [table] SYS_USER (R)
                """);
    }

    @Test
    @DisplayName("F-015-AC37 callers: 구문에서 상속 메서드로 올라간 뒤 호출자는 receiverType(또는 그 서비스가 묶는 DAO)이 구문의 namespace와 맞는 것만 남는다")
    void callersKeepOnlyMatchingReceivers() {
        GenericDaoGraph dao = new GenericDaoGraph();
        GraphModel g = dao.f.graph();

        assertThat(callers(g, dao.dictFindList)).isEqualTo("""
                [sql] DictDao.findList <SELECT>
                └─ ~ CrudDao#findList(Object)
                   ├─ CrudService#findPage(Object)
                   │  └─ DictController#list()
                   │     └─ [endpoint] GET /dict
                   ├─ DictController#direct()
                   │  └─ [endpoint] GET /dict/direct
                   ├─ [?] OddController#x()
                   │  └─ [endpoint] GET /odd
                   └─ RawController#all()
                      └─ [endpoint] GET /raw
                """);
        assertThat(callers(g, dao.userInsert)).isEqualTo("""
                [sql] UserDao.insert <INSERT>
                └─ ~ CrudDao#insert(Object)
                   └─ CrudService#save(Object)
                      └─ UserService#save(Object) (super)
                         └─ UserController#save()
                            └─ [endpoint] POST /user
                """);
        assertThat(callers(g, dao.logInsert)).contains("MixController#save()").doesNotContain("UserService#save")
                .doesNotContain("[?]");
    }

    @Test
    @DisplayName("F-015-AC38 테이블 × 엔드포인트 매트릭스와 영향도도 같은 규칙으로 좁힌다 (엔드포인트마다 자기 DAO의 테이블만, 테이블마다 그 DAO를 쓰는 엔드포인트만)")
    void matrixAndImpactAreNarrowed() {
        GenericDaoGraph dao = new GenericDaoGraph();
        GraphModel g = dao.f.graph();

        CrudMatrix m = CrudMatrix.of(g, Subject.ENDPOINT);
        assertThat(cells(m, dao.getDict)).containsExactly("SYS_DICT=R");
        assertThat(cells(m, dao.getDictDirect)).containsExactly("SYS_DICT=R");
        assertThat(cells(m, dao.postUser)).containsExactly("SYS_USER=C");
        // 대상 하나로 좁히지 못한 칸(DAO 둘을 참조하는 서비스, 수신자 타입 미상, [?])에는 ?가 붙는다(F-015-AC63)
        assertThat(cells(m, dao.postMix)).containsExactly("SYS_DICT=C?", "SYS_LOG=C?");
        assertThat(cells(m, dao.getRaw)).containsExactly("SYS_DICT=R?", "SYS_LOG=R?", "SYS_USER=R?");
        assertThat(cells(m, dao.getOdd)).containsExactly("SYS_DICT=R?", "SYS_LOG=R?", "SYS_USER=R?");

        ImpactReport dict = ImpactAnalysis.analyze(g, "SYS_DICT");
        assertThat(dict.endpoints()).extracting(Item::label).containsExactly("GET /dict", "GET /dict/direct", "GET /odd", "GET /raw", "POST /mix");
        ImpactReport user = ImpactAnalysis.analyze(g, "SYS_USER");
        assertThat(user.endpoints()).extracting(Item::label).containsExactly("GET /odd", "GET /raw", "POST /user");
        // 여러 테이블에서 같은 상속 메서드로 올라가도 테이블마다 따로 좁힌다: SYS_LOG에는 /dict가 없다
        ImpactReport log = ImpactAnalysis.analyze(g, "SYS_LOG");
        assertThat(log.endpoints()).extracting(Item::label).containsExactly("GET /odd", "GET /raw", "POST /mix");
        assertThat(log.callers()).extracting(Item::label).doesNotContain("DictController#list()", "UserService#save(Object)");
    }

    @Test
    @DisplayName("F-015-AC55 callers: 조건을 넘겨받은 제네릭 상위 클래스의 메서드라도, 그 하위 타입이 모두 다른 매퍼를 묶으면 그 구문의 호출 사슬에 넣지 않는다 (jeesite의 TreeService#save → CrudService#get)")
    void genericIntermediateWithoutMatchingSubtypeIsDropped() {
        GenericDaoGraph dao = new GenericDaoGraph();
        GraphFixture f = dao.f;
        String pkg = GenericDaoGraph.PKG;
        // TreeService<D, T> extends CrudService<D, T>: 타입 인자가 타입 변수라 어느 DAO도 묶지 않는다
        f.attr(f.type(pkg + "TreeService", "class", "public", "abstract"), "typeParameters", List.of("D extends CrudDao<T>", "T"));
        f.graph().addEdge(Edge.builder(Ids.javaType(pkg + "TreeService"), Ids.javaType(GenericDaoGraph.CRUD_SERVICE), EdgeKind.EXTENDS)
                .attr("typeArguments", List.of("D", "T")).build());
        String move = f.method(pkg + "TreeService", "move", List.of("java.lang.Object"), "void", "public");
        dao.call(move, dao.serviceFindPage, 34, "super", null);
        // 하위 타입은 LogService 하나이고 LogDao를 묶는다
        f.attr(f.type(pkg + "LogService", "class", "public"), "stereotype", "service");
        f.graph().addEdge(Edge.builder(Ids.javaType(pkg + "LogService"), Ids.javaType(pkg + "TreeService"), EdgeKind.EXTENDS)
                .attr("typeArguments", List.of(pkg + "LogDao", pkg + "Log")).build());
        String handler = f.method(pkg + "LogController", "move");
        dao.call(handler, move, 60, "virtual", pkg + "LogService");
        f.endpoint("POST", "/log/move", handler);
        GraphModel g = f.graph();

        // SYS_DICT의 구문으로는 TreeService#move를 거쳐 올 수 없다
        assertThat(callers(g, dao.dictFindList)).doesNotContain("TreeService#move").doesNotContain("LogController");
        ImpactReport dict = ImpactAnalysis.analyze(g, "SYS_DICT");
        assertThat(dict.callers()).extracting(Item::label).doesNotContain("TreeService#move(Object)");
        assertThat(dict.endpoints()).extracting(Item::label).doesNotContain("POST /log/move");
        // SYS_LOG의 구문으로는 온다
        assertThat(callers(g, dao.logFindList)).contains("""
                   ├─ CrudService#findPage(Object)
                   │  └─ TreeService#move(Object) (super)
                   │     └─ LogController#move()
                   │        └─ [endpoint] POST /log/move
                """);
        ImpactReport log = ImpactAnalysis.analyze(g, "SYS_LOG");
        assertThat(log.callers()).extracting(Item::label).contains("TreeService#move(Object)");
        assertThat(log.endpoints()).extracting(Item::label).contains("POST /log/move");
        // 하위 타입을 모르는 제네릭 상위 클래스(묶는 DAO를 알 수 없다)는 전처럼 남긴다
        f.type(pkg + "OpenService", "class", "public", "abstract");
        f.typeEdge(pkg + "OpenService", GenericDaoGraph.CRUD_SERVICE, EdgeKind.EXTENDS);
        f.type(pkg + "SomeOpenService", "class", "public");
        f.typeEdge(pkg + "SomeOpenService", pkg + "OpenService", EdgeKind.EXTENDS);
        String open = f.method(pkg + "OpenService", "load", List.of(), "void", "public");
        dao.call(open, dao.serviceFindPage, 12, "super", null);
        assertThat(callers(f.graph(), dao.dictFindList)).contains("OpenService#load() (super)");
    }

    private static List<String> cells(CrudMatrix matrix, String endpointId) {
        for (Row row : matrix.rows()) {
            if (row.id().equals(endpointId)) {
                return row.cells().entrySet().stream().map(e -> Ids.remainder(e.getKey()) + "=" + e.getValue()).sorted().toList();
            }
        }
        return List.of();
    }

    @Test
    @DisplayName("F-015-AC39 리플렉션으로 추론한 호출(callType=reflection)은 [reflect]로 표시하고, 팬아웃(viaTypes)은 문맥의 서비스 타입이 든 것만 따라간다. 문맥이 없으면 전부 [?]")
    void reflectionFanOutIsNarrowed() {
        GraphFixture f = GraphFixture.create();
        String base = "com.r.BaseServiceImpl";
        f.type(base, "class", "public", "abstract");
        String select = f.method(base, "selectAll");
        for (String name : List.of("User", "Role")) {
            String mapper = "com.r." + name + "Mapper";
            f.attr(f.type(mapper, "interface", "public"), "stereotype", "mapper");
            String method = f.method(mapper, "selectAll", List.of(), "java.util.List", "public", "abstract");
            String statement = f.statement(mapper, "selectAll", "SELECT");
            f.reads(statement, "T_" + name.toUpperCase());
            f.edge(method, statement, EdgeKind.EXECUTES);
            String service = "com.r." + name + "ServiceImpl";
            f.type(service, "class", "public");
            f.typeEdge(service, base, EdgeKind.EXTENDS);
            f.type("com.r." + name + "Service", "interface", "public");
            f.typeEdge(service, "com.r." + name + "Service", EdgeKind.IMPLEMENTS);
            // java가 남기는 꼴: 대상 매퍼 타입(receiverType)과, 타입 인자를 그 매퍼로 고정한 하위 서비스 타입(viaTypes)
            f.edge(Edge.builder(select, method, EdgeKind.CALLS).inferred()
                    .location(SourceLocation.of("src/main/java/com/r/BaseServiceImpl.java", 20))
                    .attr("callType", "reflection").attr("receiverType", mapper).attr("viaTypes", List.of(service)).build());
        }
        f.type("com.r.UserController", "class", "public");
        String controller = f.method("com.r.UserController", "list");
        f.edge(Edge.builder(controller, select, EdgeKind.CALLS).location(SourceLocation.of("src/main/java/com/r/UserController.java", 9))
                .attr("callType", "virtual").attr("receiverType", "com.r.UserServiceImpl").build());
        // 수신자의 정적 타입이 인터페이스여도 그 구현 타입이 viaTypes에 있으면 맞는다
        String byInterface = f.method("com.r.UserController", "byInterface");
        f.edge(Edge.builder(byInterface, select, EdgeKind.CALLS).location(SourceLocation.of("src/main/java/com/r/UserController.java", 19))
                .attr("callType", "interface").attr("receiverType", "com.r.RoleService").build());
        // 대상 타입이 정해진 리플렉션 호출(viaTypes 없음)은 팬아웃이 아니다: 그대로 따라간다
        String direct = f.method("com.r.Tool", "invoke");
        f.edge(Edge.builder(direct, Ids.javaMethod("com.r.UserMapper", "selectAll"), EdgeKind.CALLS).inferred()
                .location(SourceLocation.of("src/main/java/com/r/Tool.java", 7)).attr("callType", "reflection")
                .attr("receiverType", "com.r.UserMapper").build());
        GraphModel g = f.graph();

        assertThat(callees(g, controller)).isEqualTo("""
                UserController#list()
                └─ BaseServiceImpl#selectAll()
                   └─ ~ [reflect] UserMapper#selectAll()
                      └─ [sql] UserMapper.selectAll <SELECT>
                         └─ [table] T_USER (R)
                """);
        assertThat(callees(g, byInterface)).contains("[reflect] RoleMapper#selectAll()").doesNotContain("UserMapper");
        assertThat(callees(g, direct)).isEqualTo("""
                Tool#invoke()
                └─ ~ [reflect] UserMapper#selectAll()
                   └─ [sql] UserMapper.selectAll <SELECT>
                      └─ [table] T_USER (R)
                """);
        // 문맥 없이 시작하면 전부 따라가되 [?]
        assertThat(callees(g, select)).isEqualTo("""
                BaseServiceImpl#selectAll()
                ├─ ~ [?] [reflect] RoleMapper#selectAll()
                │  └─ [sql] RoleMapper.selectAll <SELECT>
                │     └─ [table] T_ROLE (R)
                └─ ~ [?] [reflect] UserMapper#selectAll()
                   └─ [sql] UserMapper.selectAll <SELECT>
                      └─ [table] T_USER (R)
                """);
        CallTree tree = CallTrees.build(g, controller, CallTreeOptions.callees());
        assertThat(tree.root().children().get(0).children().get(0).markers()).containsExactly(Marker.REFLECT);
        assertThat(callers(g, Ids.table("T_ROLE"))).contains("~ [reflect] BaseServiceImpl#selectAll()").contains("UserController#byInterface()")
                .doesNotContain("UserController#list()").doesNotContain("Tool#invoke");
        assertThat(callers(g, Ids.table("T_USER"))).contains("UserController#list()").contains("~ [reflect] Tool#invoke()")
                .doesNotContain("byInterface");
        ImpactReport role = ImpactAnalysis.analyze(g, "T_ROLE");
        assertThat(role.callers()).extracting(Item::label).contains("UserController#byInterface()").doesNotContain("UserController#list()");
    }
}
