package io.codeatlas.export.fixture;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.SourceLocation;
import java.util.List;
import java.util.Map;

/**
 * 제네릭 상위 DAO·서비스를 쓰는 프로젝트의 그래프(jeesite의 {@code CrudDao<T>}, {@code CrudService<D, T>} 꼴).
 *
 * <pre>
 * CrudDao#findList, #insert ~EXECUTES(INFERRED, namespace)→ DictDao·UserDao·LogDao의 같은 id 구문 (하위 매퍼마다 하나씩)
 * CrudService#findPage ─CALLS→ CrudDao#findList,  CrudService#save ─CALLS→ CrudDao#insert   (수신자 타입 없음: 타입 매개변수 D)
 * DictService, UserService, MixService extends CrudService
 * GET /dict        → DictController#list   ─CALLS(receiverType=DictService)→ CrudService#findPage
 * GET /dict/direct → DictController#direct ─CALLS(receiverType=DictDao)→ CrudDao#findList
 * POST /user       → UserController#save   ─CALLS→ UserService#save ─CALLS(super)→ CrudService#save
 * POST /mix        → MixController#save    ─CALLS(receiverType=MixService)→ CrudService#save
 * GET /raw         → RawController#all     ─CALLS→ CrudDao#findList   (수신자 타입을 모른다)
 * GET /odd         → OddController#x       ─CALLS(receiverType=OrphanDao)→ CrudDao#findList   (구문이 없는 하위 DAO)
 * </pre>
 */
public final class GenericDaoGraph {

    public static final String PKG = "com.g.";
    public static final String CRUD_DAO = PKG + "CrudDao";
    public static final String CRUD_SERVICE = PKG + "CrudService";

    public final GraphFixture f = GraphFixture.create();

    public final String daoFindList;
    public final String daoInsert;
    public final String serviceFindPage;
    public final String serviceSave;
    public final String userServiceSave;
    public final String dictFindList;
    public final String userFindList;
    public final String logFindList;
    public final String dictInsert;
    public final String userInsert;
    public final String logInsert;
    public final String getDict;
    public final String getDictDirect;
    public final String postUser;
    public final String postMix;
    public final String getRaw;
    public final String getOdd;

    public GenericDaoGraph() {
        f.table("SYS_DICT");
        f.table("SYS_USER");
        f.table("SYS_LOG");

        f.attr(f.type(CRUD_DAO, "interface", "public"), "typeParameters", List.of("T"));
        daoFindList = f.method(CRUD_DAO, "findList", List.of("java.lang.Object"), "java.util.List", "public", "abstract");
        daoInsert = f.method(CRUD_DAO, "insert", List.of("java.lang.Object"), "int", "public", "abstract");
        for (String dao : List.of("DictDao", "UserDao", "LogDao", "OrphanDao")) {
            f.type(PKG + dao, "interface", "public");
            f.typeEdge(PKG + dao, CRUD_DAO, EdgeKind.EXTENDS);
        }
        dictFindList = statement("DictDao", "findList", "SELECT", "SYS_DICT");
        userFindList = statement("UserDao", "findList", "SELECT", "SYS_USER");
        logFindList = statement("LogDao", "findList", "SELECT", "SYS_LOG");
        dictInsert = statement("DictDao", "insert", "INSERT", "SYS_DICT");
        userInsert = statement("UserDao", "insert", "INSERT", "SYS_USER");
        logInsert = statement("LogDao", "insert", "INSERT", "SYS_LOG");
        // 링커가 남기는 꼴 세 가지: namespace + inherited, namespace + declaredIn, 엣지 attrs 없음(구문 노드의 namespace로 읽는다)
        inherited(daoFindList, dictFindList, Map.of("namespace", PKG + "DictDao", "inherited", true));
        inherited(daoFindList, userFindList, Map.of("namespace", PKG + "UserDao", "declaredIn", Ids.javaType(CRUD_DAO)));
        inherited(daoFindList, logFindList, Map.of());
        inherited(daoInsert, dictInsert, Map.of("namespace", PKG + "DictDao", "inherited", true));
        inherited(daoInsert, userInsert, Map.of("namespace", PKG + "UserDao", "inherited", true));
        inherited(daoInsert, logInsert, Map.of("namespace", PKG + "LogDao", "inherited", true));

        f.attr(f.type(CRUD_SERVICE, "class", "public", "abstract"), "typeParameters", List.of("D extends CrudDao<T>", "T"));
        serviceFindPage = f.method(CRUD_SERVICE, "findPage", List.of("java.lang.Object"), "java.util.List", "public");
        serviceSave = f.method(CRUD_SERVICE, "save", List.of("java.lang.Object"), "void", "public");
        call(serviceFindPage, daoFindList, 30, "interface", null);
        call(serviceSave, daoInsert, 40, "interface", null);
        f.typeEdge(CRUD_SERVICE, CRUD_DAO, EdgeKind.DEPENDS_ON);

        // DictService extends CrudService<DictDao, Dict>: 타입 인자는 DEPENDS_ON으로만 드러난다
        service("DictService");
        f.typeEdge(PKG + "DictService", PKG + "DictDao", EdgeKind.DEPENDS_ON);
        // UserService: 상위 타입의 타입 인자가 엣지에 있다. save를 재정의하고 super.save를 부른다. 다른 DAO(LogDao)도 주입받는다
        service("UserService");
        f.graph().addEdge(Edge.builder(Ids.javaType(PKG + "UserService"), Ids.javaType(CRUD_SERVICE), EdgeKind.EXTENDS)
                .location(SourceLocation.of("src/main/java/com/g/UserService.java", 5))
                .attr("typeArguments", List.of(PKG + "UserDao", PKG + "User")).build());
        f.typeEdge(PKG + "UserService", PKG + "LogDao", EdgeKind.INJECTS);
        userServiceSave = f.method(PKG + "UserService", "save", List.of("java.lang.Object"), "void", "public");
        f.edge(userServiceSave, serviceSave, EdgeKind.OVERRIDES);
        call(userServiceSave, serviceSave, 20, "super", null);
        // MixService: 타입 인자를 모르고 DAO 둘을 참조한다(타입 인자 DictDao, 주입받는 LogDao)
        service("MixService");
        f.typeEdge(PKG + "MixService", PKG + "DictDao", EdgeKind.DEPENDS_ON);
        f.typeEdge(PKG + "MixService", PKG + "LogDao", EdgeKind.INJECTS);

        getDict = endpoint("GET", "/dict", "DictController", "list", serviceFindPage, PKG + "DictService");
        String direct = f.method(PKG + "DictController", "direct");
        call(direct, daoFindList, 50, "interface", PKG + "DictDao");
        getDictDirect = f.endpoint("GET", "/dict/direct", direct);
        postUser = endpoint("POST", "/user", "UserController", "save", userServiceSave, null);
        postMix = endpoint("POST", "/mix", "MixController", "save", serviceSave, PKG + "MixService");
        getRaw = endpoint("GET", "/raw", "RawController", "all", daoFindList, null);
        getOdd = endpoint("GET", "/odd", "OddController", "x", daoFindList, PKG + "OrphanDao");
    }

    private String statement(String dao, String id, String type, String table) {
        String statement = f.statement(PKG + dao, id, type);
        if ("SELECT".equals(type)) {
            f.reads(statement, table);
        } else {
            f.writes(statement, table, type);
        }
        return statement;
    }

    private void inherited(String method, String statement, Map<String, ?> attrs) {
        f.graph().addEdge(Edge.builder(method, statement, EdgeKind.EXECUTES).inferred()
                .location(f.graph().node(method).orElseThrow().location()).attrs(attrs).build());
    }

    private void service(String name) {
        f.attr(f.type(PKG + name, "class", "public"), "stereotype", "service");
        if (!"UserService".equals(name)) {
            f.typeEdge(PKG + name, CRUD_SERVICE, EdgeKind.EXTENDS);
        }
    }

    /** 호출 엣지. receiverType이 null이면 넣지 않는다. */
    public Edge call(String from, String to, int line, String callType, String receiverType) {
        String file = f.graph().node(from).orElseThrow().location().file();
        return f.graph().addEdge(Edge.builder(from, to, EdgeKind.CALLS).location(SourceLocation.of(file, line))
                .attr("callType", callType).attr("receiverType", receiverType).build());
    }

    private String endpoint(String httpMethod, String path, String controller, String handler, String target, String receiverType) {
        if (!f.graph().hasNode(Ids.javaType(PKG + controller))) {
            f.attr(f.type(PKG + controller, "class", "public"), "stereotype", "controller");
        }
        String method = f.method(PKG + controller, handler);
        call(method, target, 60, target.contains("CrudDao#") ? "interface" : "virtual", receiverType);
        return f.endpoint(httpMethod, path, method);
    }
}
