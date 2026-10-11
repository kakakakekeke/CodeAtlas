package io.codeatlas.export.calltree;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.export.common.DbGraph;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * 처리 흐름을 한 걸음씩 따라가는 규칙. 콜트리, 영향도, CRUD 매트릭스가 함께 쓴다.
 *
 * <p>아래 방향(callees)으로 따라가는 엣지: REQUESTS, MATCHES, ROUTES_TO, CALLS, EXECUTES, RENDERS, INCLUDES, USES,
 * READS/WRITES(컬럼은 테이블로 접는다), CONTAINS(루틴→구문, 잡→스텝, JSP→JS 모듈, JS 모듈→함수, JS 함수→함수, 타입→public 메서드),
 * TRIGGERS_ON의 역방향(쓰는 테이블→그 쓰기에 일어나는 트리거), DEFINES_BEAN의 역방향(빈→정의한 타입·메서드),
 * OVERRIDES의 역방향(인터페이스·추상 메서드→구현, 전이적으로).
 * NEXT는 따라가지 않는다(잡 아래 스텝의 순서를 정할 때만 본다). 위 방향(callers)은 같은 규칙을 거꾸로 적용한다.
 *
 * <p>테이블의 접근 종류에서 R은 테이블 수준 READS가 있을 때만 붙는다(컬럼으로 가는 READS는 테이블로 접어 세지 않는다).
 * 트리거는 쓰는 구문의 op가 트리거의 {@code triggerEvent}와 맞을 때만 따라가고, 맞는지 알 수 없으면 따라가되 불확실하다고 표시한다.
 *
 * <p><b>수신자 문맥.</b> 제네릭 상위 DAO의 메서드 하나({@code CrudDao<T>#findList})에서는 하위 매퍼마다 같은 id의 구문으로
 * {@code EXECUTES}가 나간다(링커가 추론한 것. 엣지나 구문의 {@code namespace}가 하위 매퍼 타입이다). 어느 구문이 실행되는지는
 * 호출 지점의 수신자 타입이 정하므로, 걸음마다 "경로상 가장 최근의 구체 수신자 타입"({@link Context})을 싣고 그것으로 팬아웃을 좁힌다.
 * <ul>
 * <li>호출 엣지에 {@code receiverType}이 있으면 그 타입이 문맥이다. {@code super} 호출, 상속받은 메서드를 수신자 없이 부르는 호출,
 *     같은 타입 안의 호출은 호출한 쪽의 문맥(없으면 호출한 쪽의 타입)을 잇는다. 그 밖의 호출(다른 객체의 메서드)은
 *     호출한 쪽의 문맥을 "감싼 객체의 타입"으로 한 걸음만 넘긴다(상위 서비스 안의 {@code this.dao.x()}).</li>
 * <li>팬아웃 엣지(상속받은 구문으로 가는 {@code EXECUTES}, {@code callType=reflection}인 {@code CALLS})는 그 엣지의 타입
 *     (namespace, receiverType)이 문맥 타입과 맞을 때만 따라간다. 맞다는 것은 두 타입이 {@code EXTENDS}/{@code IMPLEMENTS}로
 *     상·하위 관계이거나, 문맥 타입(과 그 상위 타입)이 그 타입을 묶는다는 뜻이다(상위 타입으로 가는 엣지의 {@code typeArguments},
 *     없으면 {@code DEPENDS_ON}·{@code ASSOCIATES}·{@code INJECTS}로 참조하는 타입).</li>
 * <li>맞는 것이 하나도 없으면 전부 따라가되 불확실하다고 표시한다({@code [?]}). 문맥이 없으면 전부 따라간다(표시 없음).</li>
 * <li>위 방향은 대칭이다: 구문에서 상속 메서드로 올라갈 때 구문의 namespace를 "필요한 타입"으로 싣고, 호출자 가운데 수신자 타입
 *     (또는 호출한 쪽의 타입)이 그것과 맞는 것만 남긴다. 다른 namespace와 맞는 호출자는 빼고, 어느 것과도 맞지 않는 호출자는
 *     {@code [?]}로 남긴다.</li>
 * </ul>
 *
 * <p><b>추론 걸음.</b> 걸음마다 그것이 추론인지와 까닭을 싣는다({@link Step#inference()}). CRUD 매트릭스는 추론 걸음을 거쳐서만 닿은 칸에
 * {@code ?}를, 영향도는 그런 항목에 {@code ~}를 붙인다. 추론 걸음은 콜트리에서 {@code ~}(신뢰도 INFERRED: 오버로드 후보 {@code ambiguous},
 * 구문 ID 접두 일치 {@code prefixMatch}, 뷰 후보 {@code candidate}·{@code suffixMatch}, 스크립트 로드 미확인, 다른 모듈의 핸들러
 * {@code crossModule}, redirect, 추론한 재정의 등), {@code [?]}, {@code [trigger?]}가 붙는 걸음이다. 다만 프레임워크의 규칙으로
 * 대상이 정해지는 세 가지는 엣지가 INFERRED여도 확정으로 본다: 요청 전 처리({@code callType=modelAttribute}: 스프링이 늘 부른다),
 * forward({@code callType=forward}: 같은 요청 안의 서버 내부 이동. 대상 매칭이 {@code ambiguous}·{@code suffixMatch}·{@code crossModule}이면 추론),
 * 수신자 문맥이 대상 하나를 고른 팬아웃 엣지(상속 매퍼 메서드의 구문, 리플렉션 호출). 반대로 팬아웃 엣지를 문맥 없이 전부 따라갔거나
 * 문맥이 대상 여럿과 맞았으면 콜트리에 표시가 없어도 추론 걸음이다(어느 구문이 실행되는지 정해지지 않았다).
 * 위 방향에서는 상속 메서드로 올라간 걸음을 "확인 대기"로 두고({@link Step#context()}에 조건이 실린다) 수신자가 맞는 호출자가
 * 그것을 확인한다.
 */
public final class FlowNavigator {

    /**
     * 한 걸음.
     *
     * @param nodeId 다음 노드
     * @param via 따라간 엣지 종류(구현 전개는 OVERRIDES)
     * @param confidence 같은 노드로 가는 엣지가 여럿이면 가장 확실한 것
     * @param site 엣지의 근거 위치(호출 지점). 없으면 null
     * @param detail 테이블 접근 종류({@code R}, {@code CU}), 스텝 구성 요소의 role, redirect/forward/super(호출 방식). 없으면 null
     * @param impl 인터페이스·추상 메서드의 구현으로 펼친 것
     * @param viaInterface 재정의한 상위 메서드의 호출자
     * @param triggerUncertain 트리거가 이 쓰기에 실제로 일어나는지 알 수 없는 걸음(트리거의 사건을 모르거나, 컬럼 지정 트리거인데
     *        구문이 쓰는 컬럼을 모르거나, 쓰기의 종류를 모를 때)
     * @param context 이 노드를 펼칠 때 쓸 수신자 문맥. 없으면 null. 같은 노드라도 문맥이 다르면 걸음이 따로다
     * @param receiverUncertain 수신자 문맥과 맞는 팬아웃 엣지가 없어서 전부 따라간 걸음({@code [?]})
     * @param ambiguous 오버로드를 가리지 못해 후보 전부로 이은 호출({@code CALLS}의 {@code ambiguous=true})이나, 후보 엔드포인트 여럿과 맞은
     *        요청({@code MATCHES}의 {@code ambiguous=true})으로만 닿은 걸음.
     *        같은 호출 지점의 후보들은 콜트리에서 {@code [ambiguous n]} 한 줄로 묶는다
     * @param inference 이 걸음이 추론이면 그 까닭, 확정이면 null. CRUD 매트릭스 칸의 {@code ?}와 영향도의 {@code ~}가 이것으로 정해진다
     *        ({@link FlowNavigator}의 "추론 걸음" 설명 참고). 같은 노드로 가는 엣지가 여럿이면 하나라도 확정일 때 null이다
     */
    public record Step(String nodeId, EdgeKind via, Confidence confidence, SourceLocation site, String detail, boolean impl,
            boolean viaInterface, boolean triggerUncertain, Context context, boolean receiverUncertain, boolean ambiguous,
            String inference) {

        /** 수신자 문맥이 없는 걸음. */
        public Step(String nodeId, EdgeKind via, Confidence confidence, SourceLocation site, String detail, boolean impl,
                boolean viaInterface, boolean triggerUncertain) {
            this(nodeId, via, confidence, site, detail, impl, viaInterface, triggerUncertain, null, false, false, null);
        }

        public Step(String nodeId, EdgeKind via, Confidence confidence, SourceLocation site, String detail, boolean impl,
                boolean viaInterface, boolean triggerUncertain, Context context, boolean receiverUncertain) {
            this(nodeId, via, confidence, site, detail, impl, viaInterface, triggerUncertain, context, receiverUncertain, false, null);
        }

        public Step(String nodeId, EdgeKind via, Confidence confidence, SourceLocation site, String detail, boolean impl,
                boolean viaInterface, boolean triggerUncertain, Context context, boolean receiverUncertain, boolean ambiguous) {
            this(nodeId, via, confidence, site, detail, impl, viaInterface, triggerUncertain, context, receiverUncertain, ambiguous, null);
        }

        /** 추론 걸음인지({@link #inference()}가 있다). */
        public boolean inferred() {
            return inference != null;
        }

        /**
         * 레이아웃으로 가는 걸음인지: 내용 페이지 → 데코레이터({@code INCLUDES}의 {@code via=sitemesh}), tiles template → 조각
         * ({@code via=tiles}), 핸들러 → tiles template({@code RENDERS}의 {@code via=tiles}이고 {@code tilesAttribute}가 아님).
         * 모든 화면이 공통 헤더·메뉴를 끌고 오지 않게 기본으로는 그 너머를 따라가지 않는다.
         */
        public boolean layout() {
            return (via == EdgeKind.INCLUDES || via == EdgeKind.RENDERS) && DETAIL_LAYOUT.equals(detail);
        }

        /** {@code super.m()}으로 온 걸음인지. 상위 구현을 명시적으로 부른 것이라 도착한 메서드에서 구현 전개를 하지 않는다. */
        public boolean superCall() {
            return via == EdgeKind.CALLS && "super".equals(detail);
        }

        /** 핸들러보다 먼저 실행되는 {@code @ModelAttribute} 메서드로 가는(위 방향에서는 그 핸들러로 올라온) 추론 호출의 걸음인지. */
        public boolean beforeCall() {
            return via == EdgeKind.CALLS && CALL_MODEL_ATTRIBUTE.equals(detail);
        }

        /** 다른 요청으로 넘어가는 걸음인지({@code callType=redirect}). 브라우저가 새 요청을 보내므로 원래 요청의 처리 흐름이 아니다. */
        public boolean redirect() {
            return via == EdgeKind.CALLS && CALL_REDIRECT.equals(detail);
        }

        /** 같은 요청 안에서 다른 핸들러로 넘어가는 걸음인지({@code callType=forward}: forward와 include). */
        public boolean forward() {
            return via == EdgeKind.CALLS && CALL_FORWARD.equals(detail);
        }

        /**
         * 걸음을 따로 세우는 호출 방식. 같은 대상이라도 {@code super} 호출, 요청 전 처리, redirect, forward는 보통의 호출과 한 줄로 합치지 않는다
         * (도착한 메서드의 아래가 다르거나, 그 아래를 펼치지 않는 줄이다). 그 밖에는 빈 문자열.
         */
        String callMode() {
            return superCall() || beforeCall() || redirect() || forward() ? detail : "";
        }

    }

    /**
     * 수신자 문맥.
     *
     * <p>아래 방향에서는 "이 노드(메서드)를 실행하는 객체의 타입"이다. {@code enclosing}이면 그 메서드의 객체가 아니라
     * 그것을 부른 쪽 객체의 타입이다(타입 매개변수로 선언된 필드의 메서드를 부른 경우. 그 메서드의 팬아웃을 고르는 데만 쓰고
     * 더 아래로 잇지 않는다). 위 방향에서는 "이 노드를 실행하는 객체의 타입이 맞아야 하는 타입"(구문의 namespace,
     * 리플렉션 호출의 viaTypes)이고 {@code origin}은 그 팬아웃이 나가는 메서드다.
     *
     * @param type {@code JAVA_TYPE} 노드 ID. 위 방향에서 리플렉션 팬아웃이면 쉼표로 이은 여러 개(그 가운데 하나와 맞으면 된다)
     * @param enclosing 아래 방향에서, 부른 쪽 객체의 타입이면 true
     * @param origin 위 방향에서, 팬아웃 엣지가 나가는 메서드의 ID. 아래 방향에서는 null
     * @param exact 위 방향에서, 상·하위 관계로만 맞춰 보는 조건이면 true(리플렉션 팬아웃). false면 묶는 타입으로도 맞춰 본다
     */
    public record Context(String type, boolean enclosing, String origin, boolean exact) {

        Context(String type, boolean enclosing, String origin) {
            this(type, enclosing, origin, false);
        }

        String key() {
            return (enclosing ? "~" : "") + type + (origin == null ? "" : "@" + origin);
        }
    }

    /** 쓰기가 트리거를 일으키는지. */
    private enum Fires {
        NO, YES, UNCERTAIN
    }

    private static final int MAX_HIERARCHY_DEPTH = 30;

    /** 호출 줄의 세부 표시로 쓰는 {@code callType}: 리플렉션으로 추론한 호출. 콜트리에서는 {@code [reflect]} 표식이 된다. */
    public static final String CALL_REFLECTION = "reflection";
    /** 핸들러 → 같은 컨트롤러의 {@code @ModelAttribute} 메서드. 콜트리에서는 {@code [before]} 표식이 된다. */
    public static final String CALL_MODEL_ATTRIBUTE = "modelAttribute";
    /** 뷰 이름 {@code redirect:}·{@code sendRedirect}로 넘어가는 핸들러. 콜트리에서는 {@code [redirect]} 표식이 된다. */
    public static final String CALL_REDIRECT = "redirect";
    /** {@code forward:}·{@code RequestDispatcher.forward/include}로 넘어가는 핸들러. 콜트리에서는 {@code [forward]} 표식이 된다. */
    public static final String CALL_FORWARD = "forward";
    /** 레이아웃으로 가는 걸음의 세부 표시. 콜트리에서는 {@code [layout]} 표식이 된다. */
    public static final String DETAIL_LAYOUT = "layout";
    /** 그 스크립트를 함께 싣는지 확인하지 못한 JS 호출({@code loadUnverified=true})의 세부 표시. */
    public static final String DETAIL_LOAD_UNVERIFIED = "로드 미확인";

    /** 구문 ID가 접두만 맞아 후보 구문 전부로 이은 실행({@code prefixMatch=true})의 세부 표시. */
    public static final String DETAIL_PREFIX_MATCH = "접두 일치";
    /** 구문 ID를 인자로 받는 메서드에 넘긴 문자열로 이은 실행({@code viaArgument=true})의 세부 표시. */
    public static final String DETAIL_VIA_ARGUMENT = "인자 구문";
    /** 동적 조각이 든 뷰 이름으로 찾은 화면({@code RENDERS}의 {@code dynamicSegment=true})의 세부 표시. */
    public static final String DETAIL_DYNAMIC_VIEW = "동적 뷰 이름 후보";
    /** 동적 뷰 이름의 후보를 모듈의 프로퍼티 값으로 좁힌 화면({@code narrowedBy=property})의 세부 표시. */
    public static final String DETAIL_DYNAMIC_VIEW_BY_PROPERTY = "동적 뷰 이름: 프로퍼티 값으로 정함";
    /** 동적 뷰 이름의 후보를 컨트롤러 모듈의 디렉터리 이름으로 좁힌 화면({@code narrowedBy=moduleName})의 세부 표시. */
    public static final String DETAIL_DYNAMIC_VIEW_BY_MODULE = "동적 뷰 이름: 모듈 이름으로 정함";

    /** 추론 걸음의 까닭: 수신자 타입에 맞는 팬아웃 대상을 고르지 못해 전부 따라갔다(콜트리의 {@code [?]}). */
    public static final String INFERENCE_RECEIVER_UNCERTAIN = "수신자 타입에 맞는 대상을 고르지 못해 전부 따라감([?])";
    /** 추론 걸음의 까닭: 수신자 타입을 모르거나 여럿과 맞아, 상속 매퍼 메서드의 구문(리플렉션 호출의 대상) 가운데 어느 것인지 정하지 못했다. */
    public static final String INFERENCE_RECEIVER_UNKNOWN = "수신자 타입으로 대상 하나를 정하지 못함(상속 매퍼 메서드의 구문·리플렉션 호출)";
    /** 추론 걸음의 까닭: 그 쓰기에 실제로 일어나는지 알 수 없는 트리거(콜트리의 {@code [trigger?]}). */
    public static final String INFERENCE_TRIGGER_UNCERTAIN = "그 쓰기에 일어나는지 알 수 없는 트리거([trigger?])";

    /** 팬아웃 엣지(상속 매퍼 메서드의 구문, 리플렉션 호출)를 어떻게 따라갔는지. */
    private enum Pick {
        /** 팬아웃 엣지가 아니다. */
        NONE,
        /** 수신자 문맥이 이 대상 하나를 골랐다(위 방향에서는 호출자가 확인할 것이다): 엣지가 INFERRED여도 확정이다. */
        SELECTED,
        /** 문맥이 없거나 대상 여럿과 맞아 고르지 못한 채 따라갔다: 추론이다. */
        UNSELECTED
    }

    /**
     * 걸음이 추론이면 그 까닭, 확정이면 null(트리거의 불확실은 걸음을 만들 때 따로 본다).
     *
     * @param confidence 걸음의 신뢰도(crossModule·loadUnverified로 낮춘 뒤의 값)
     */
    private static String inferenceOf(Edge edge, EdgeKind via, String detail, Confidence confidence, boolean receiverUncertain, Pick pick) {
        if (receiverUncertain) {
            return INFERENCE_RECEIVER_UNCERTAIN;
        }
        if (pick == Pick.UNSELECTED) {
            return INFERENCE_RECEIVER_UNKNOWN;
        }
        boolean ambiguous = edge != null && edge.attrBoolean("ambiguous");
        if (pick == Pick.SELECTED) {
            return ambiguous ? "후보 여럿(ambiguous)" : null;
        }
        // 요청으로 진입한 핸들러보다 먼저 실행되는 @ModelAttribute 메서드: 스프링이 늘 부른다.
        if (via == EdgeKind.CALLS && CALL_MODEL_ATTRIBUTE.equals(detail)) {
            return null;
        }
        if (confidence != Confidence.INFERRED) {
            return null;
        }
        if (edge == null) {
            return via == EdgeKind.OVERRIDES ? "추론한 재정의(OVERRIDES)" : "추론한 관계(" + via + ")";
        }
        if (ambiguous) {
            return "후보 여럿(ambiguous)";
        }
        if (edge.attrBoolean("prefixMatch")) {
            return "구문 ID 접두 일치(prefixMatch)";
        }
        if (edge.attrBoolean("viaArgument")) {
            return "인자로 넘긴 구문 ID(viaArgument)";
        }
        if (via == EdgeKind.RENDERS && edge.attrBoolean("dynamicSegment")) {
            String narrowedBy = edge.attrString("narrowedBy");
            return "property".equals(narrowedBy) ? "동적 조각이 든 뷰 이름을 프로퍼티 값으로 정함(narrowedBy=property)"
                    : "moduleName".equals(narrowedBy) ? "동적 조각이 든 뷰 이름을 모듈 이름으로 정함(narrowedBy=moduleName)"
                    : "동적 조각이 든 뷰 이름의 후보(dynamicSegment)";
        }
        if (via == EdgeKind.INCLUDES && edge.attrBoolean("dynamicDecorator")) {
            return "동적 데코레이터 이름(dynamicDecorator)";
        }
        if (edge.attrBoolean("candidate")) {
            return "뷰 후보(candidate)";
        }
        if (edge.attrBoolean("suffixMatch")) {
            return "뷰 이름 접미 일치(suffixMatch)";
        }
        if (DETAIL_LOAD_UNVERIFIED.equals(detail)) {
            return "스크립트 로드 미확인(loadUnverified)";
        }
        if (edge.attrBoolean("crossModule")) {
            return "다른 모듈의 핸들러(crossModule)";
        }
        if (via == EdgeKind.CALLS) {
            String callType = edge.attrString("callType");
            // forward는 같은 요청 안의 서버 내부 이동이고 링커가 그 경로를 엔드포인트로 맞춘 것이다: 엣지가 INFERRED여도 요청 흐름의 확정
            // 경로로 본다. 대상 매칭이 후보 여럿(ambiguous)·접미 일치(suffixMatch)·다른 모듈(crossModule)이면 위에서 이미 추론으로 판정했다.
            if (CALL_FORWARD.equals(callType)) {
                return null;
            }
            if (CALL_REDIRECT.equals(callType)) {
                return "뷰 이름·경로로 이은 redirect";
            }
            if (CALL_REFLECTION.equals(callType)) {
                return "리플렉션 호출(reflection)";
            }
            if (edge.attrString("inheritedFrom") != null) {
                return "classpath에 없는 상위 타입의 메서드(inheritedFrom)";
            }
            if ("global".equals(edge.attrString("resolution"))) {
                return "다른 스크립트의 전역 함수(resolution=global)";
            }
            if (edge.attrString("viaProp") != null) {
                return "props로 받은 함수(viaProp)";
            }
        }
        return "추론한 관계(" + via + ")";
    }

    /**
     * 문맥이 고른 팬아웃 엣지들 가운데 e의 대상이 하나로 정해졌는지. 상속 구문(EXECUTES)은 고른 엣지들의 namespace가 하나일 때,
     * 리플렉션 호출은 같은 호출 지점에서 고른 대상이 하나일 때다(객체 하나는 매퍼 하나의 구문만 실행한다).
     */
    private boolean singleTarget(Edge e, List<Edge> selected) {
        Set<String> targets = new HashSet<>();
        for (Edge other : selected) {
            if (other.kind() != e.kind()) {
                continue;
            }
            if (e.kind() == EdgeKind.EXECUTES) {
                targets.add(String.valueOf(namespaceOf(other)));
            } else if (java.util.Objects.equals(siteKey(other), siteKey(e))) {
                targets.add(other.to());
            }
        }
        return targets.size() <= 1;
    }

    private static String siteKey(Edge e) {
        SourceLocation at = e.location();
        return at == null ? null : at.file() + ":" + at.startLine() + ":" + at.startColumn();
    }

    /** type이 originMethodId의 팬아웃 엣지 가운데 대상 여럿을 고르는지(수신자가 맞기는 하지만 어느 구문인지는 정해지지 않는다). */
    private boolean selectsSeveralFanOuts(String type, String originMethodId) {
        if (originMethodId == null) {
            return false;
        }
        List<Edge> selected = new ArrayList<>();
        for (Edge e : graph.outgoing(originMethodId, EdgeKind.EXECUTES, EdgeKind.CALLS)) {
            boolean fanOut = e.kind() == EdgeKind.EXECUTES ? inheritedStatement(originMethodId, e) : reflectiveFanOut(e);
            if (fanOut && selects(type, e)) {
                selected.add(e);
            }
        }
        for (Edge e : selected) {
            if (!singleTarget(e, selected)) {
                return true;
            }
        }
        return false;
    }

    private final GraphModel graph;
    private final boolean expandImplementations;
    private final boolean narrowByInjection;
    private final boolean includeDataLoad;
    private final boolean expandLayouts;
    private Map<String, List<String>> columnsByTable;
    private final Map<String, Map<String, Confidence>> overridersCache = new HashMap<>();
    private final Map<String, Set<String>> supertypesCache = new HashMap<>();
    private final Map<String, Set<String>> boundTypesCache = new HashMap<>();
    private final Map<String, Boolean> fanOutCache = new HashMap<>();
    private final Map<String, Boolean> sensitiveCache = new HashMap<>();
    private final Map<String, Boolean> accessorCache = new HashMap<>();
    private final Map<String, Boolean> beforeCache = new HashMap<>();

    public FlowNavigator(GraphModel graph, boolean expandImplementations, boolean narrowByInjection) {
        this(graph, expandImplementations, narrowByInjection, true);
    }

    /**
     * @param includeDataLoad false면 데이터 적재 구문({@code SQL_STATEMENT}의 {@code attrs.dataLoad=true}: 스크립트의 리터럴 INSERT를
     *        파일·테이블 단위로 묶은 것)으로 가는 걸음을 내지 않는다
     */
    public FlowNavigator(GraphModel graph, boolean expandImplementations, boolean narrowByInjection, boolean includeDataLoad) {
        this(graph, expandImplementations, narrowByInjection, includeDataLoad, false);
    }

    /**
     * @param expandLayouts true면 위 방향에서 레이아웃 INCLUDES({@code via=sitemesh|tiles})도 거슬러 올라간다(그 레이아웃을 쓰는 화면 전부).
     *        아래 방향의 걸음은 어느 쪽이든 내고({@link Step#layout()}), 그 너머를 따라갈지는 쓰는 쪽이 정한다
     */
    public FlowNavigator(GraphModel graph, boolean expandImplementations, boolean narrowByInjection, boolean includeDataLoad,
            boolean expandLayouts) {
        this.graph = graph;
        this.expandImplementations = expandImplementations;
        this.narrowByInjection = narrowByInjection;
        this.includeDataLoad = includeDataLoad;
        this.expandLayouts = expandLayouts;
    }

    public GraphModel graph() {
        return graph;
    }

    /** 걸음을 모으면서 같은 노드로 가는 것(문맥도 같은 것)을 하나로 합친다. */
    private final class Steps {
        private final Map<String, Step> byKey = new LinkedHashMap<>();
        private final Map<String, Set<Character>> access = new HashMap<>();

        void add(String nodeId, Edge edge, EdgeKind via, String detail, boolean impl, boolean viaInterface) {
            add(nodeId, edge, via, detail, impl, viaInterface, false);
        }

        void add(String nodeId, Edge edge, EdgeKind via, String detail, boolean impl, boolean viaInterface, boolean uncertain) {
            Confidence confidence = edge == null ? Confidence.RESOLVED : edge.confidence();
            if (DETAIL_LOAD_UNVERIFIED.equals(detail) && confidence == Confidence.RESOLVED) {
                confidence = Confidence.INFERRED;
            }
            add(nodeId, confidence, edge == null ? null : edge.location(), via, detail, impl, viaInterface, uncertain, null, false,
                    edge != null && (via == EdgeKind.CALLS || via == EdgeKind.INCLUDES) && edge.attrBoolean("ambiguous"),
                    inferenceOf(edge, via, detail, confidence, false, Pick.NONE));
        }

        void add(String nodeId, Edge edge, EdgeKind via, String detail, boolean viaInterface, Context context, boolean receiverUncertain) {
            add(nodeId, edge, via, detail, viaInterface, context, receiverUncertain, Pick.NONE);
        }

        /** @param pick 팬아웃 엣지를 어떻게 따라갔는지(추론 걸음인지를 정한다) */
        void add(String nodeId, Edge edge, EdgeKind via, String detail, boolean viaInterface, Context context, boolean receiverUncertain,
                Pick pick) {
            // 함께 싣는지 확인하지 못한 스크립트의 함수로 간 호출은 확정이 아니다.
            Confidence confidence = DETAIL_LOAD_UNVERIFIED.equals(detail) && edge.confidence() == Confidence.RESOLVED
                    ? Confidence.INFERRED : edge.confidence();
            add(nodeId, confidence, edge.location(), via, detail, false, viaInterface, false, context, receiverUncertain,
                    via == EdgeKind.CALLS && edge.attrBoolean("ambiguous"), inferenceOf(edge, via, detail, confidence, receiverUncertain, pick));
        }

        /** @param reason 추론 걸음이면 그 까닭(트리거의 불확실은 uncertain으로 따로 준다) */
        void add(String nodeId, Confidence edgeConfidence, SourceLocation site, EdgeKind via, String detail, boolean impl,
                boolean viaInterface, boolean uncertain, Context context, boolean receiverUncertain, String reason) {
            add(nodeId, edgeConfidence, site, via, detail, impl, viaInterface, uncertain, context, receiverUncertain, false, reason);
        }

        void add(String nodeId, Confidence edgeConfidence, SourceLocation site, EdgeKind via, String detail, boolean impl,
                boolean viaInterface, boolean uncertain, Context context, boolean receiverUncertain, boolean ambiguous, String reason) {
            if (!includeDataLoad && isDataLoad(nodeId)) {
                return;
            }
            String key = key(nodeId, impl, viaInterface, context, callMode(via, detail));
            Confidence confidence = edgeConfidence;
            if (!graph.hasNode(nodeId) && Ids.kindOf(nodeId).orElse(NodeKind.UNRESOLVED) != NodeKind.DB_COLUMN) {
                confidence = Confidence.UNRESOLVED;
            } else if (graph.node(nodeId).map(n -> n.kind() == NodeKind.UNRESOLVED).orElse(false)) {
                confidence = Confidence.UNRESOLVED;
            }
            Step existing = byKey.get(key);
            if (existing == null) {
                byKey.put(key, new Step(nodeId, via, confidence, site, detail, impl, viaInterface, uncertain, context, receiverUncertain,
                        ambiguous, uncertain ? INFERENCE_TRIGGER_UNCERTAIN : reason));
                return;
            }
            // 같은 노드로 가는 엣지 가운데 하나라도 확정이면 그 걸음은 확정이다. 트리거의 불확실은 합친 걸음의 표시를 따른다.
            boolean triggerUncertain = existing.triggerUncertain() || uncertain;
            String existingReason = existing.triggerUncertain() ? reason : existing.inference();
            String inference = triggerUncertain ? INFERENCE_TRIGGER_UNCERTAIN : existingReason != null && reason != null ? existingReason : null;
            Confidence best = existing.confidence().ordinal() <= confidence.ordinal() ? existing.confidence() : confidence;
            // 읽기와 쓰기가 함께 있으면 쓰기로 본다(트리거를 따라가야 한다).
            EdgeKind kind = existing.via() == EdgeKind.WRITES || via == EdgeKind.WRITES ? EdgeKind.WRITES
                    : existing.via().compareTo(via) <= 0 ? existing.via() : via;
            SourceLocation firstSite = earlier(existing.site(), site);
            // 세부 표시가 다르면 사전순으로 앞선 것을 쓴다(입력 순서에 기대지 않게).
            String mergedDetail = existing.detail() == null ? detail
                    : detail == null || existing.detail().compareTo(detail) <= 0 ? existing.detail() : detail;
            byKey.put(key, new Step(nodeId, kind, best, firstSite, mergedDetail, impl, viaInterface,
                    triggerUncertain, context, existing.receiverUncertain() && receiverUncertain,
                    existing.ambiguous() && ambiguous, inference));
        }

        private String key(String nodeId, boolean impl, boolean viaInterface, Context context, String callMode) {
            return nodeId + "|" + impl + "|" + viaInterface + (context == null ? "" : "|" + context.key())
                    + (callMode.isEmpty() ? "" : "|" + callMode);
        }

        /** {@link Step#callMode()}와 같다(걸음을 만들기 전에 키를 정할 때 쓴다). */
        private String callMode(EdgeKind via, String detail) {
            if (via != EdgeKind.CALLS || detail == null) {
                return "";
            }
            return switch (detail) {
                case "super", CALL_MODEL_ATTRIBUTE, CALL_REDIRECT, CALL_FORWARD -> detail;
                default -> "";
            };
        }

        /**
         * 테이블 접근. 같은 테이블로 가는 READS/WRITES(컬럼 엣지 포함)를 하나로 합치고 C/R/U/D를 모은다.
         * 컬럼으로 가는 READS는 줄은 합치되 R로 세지 않는다.
         */
        void addAccess(String nodeId, Edge edge, String statementId, boolean uncertain) {
            if (!includeDataLoad && isDataLoad(nodeId)) {
                return;
            }
            String key = key(nodeId, false, false, null, "");
            access.computeIfAbsent(key, k -> new TreeSet<>(Comparator.comparingInt((Character c) -> Access.ORDER.indexOf(c))))
                    .addAll(Access.tableLetters(graph, edge, statementId));
            add(nodeId, edge, edge.kind(), null, false, false, uncertain);
        }

        /**
         * @param bySite true면 호출·실행·요청 줄을 호출 지점의 위치순(줄, 열)으로 앞에 놓는다. 위치를 모르는 줄과
         *        구현 전개·테이블·트리거 줄은 그 뒤에 노드 ID 순이다. false면 전부 노드 ID 순이다
         */
        List<Step> sorted(boolean bySite) {
            List<Step> steps = new ArrayList<>();
            for (Map.Entry<String, Step> e : byKey.entrySet()) {
                Step s = e.getValue();
                Set<Character> letters = access.get(e.getKey());
                if (letters != null && !letters.isEmpty()) {
                    StringBuilder detail = new StringBuilder();
                    letters.forEach(detail::append);
                    s = new Step(s.nodeId(), s.via(), s.confidence(), s.site(), detail.toString(), s.impl(), s.viaInterface(),
                            s.triggerUncertain(), s.context(), s.receiverUncertain(), s.ambiguous(), s.inference());
                }
                steps.add(s);
            }
            Comparator<Step> byId = Comparator.comparing(Step::nodeId).thenComparing(Step::impl).thenComparing(Step::viaInterface)
                    .thenComparing(step -> step.context() == null ? "" : step.context().key()).thenComparing(Step::superCall)
                    .thenComparing(Step::callMode);
            if (!bySite) {
                steps.sort(byId);
                return steps;
            }
            // 요청 전 처리(@ModelAttribute 메서드)가 맨 앞, 그다음 호출 지점 위치순, 나머지는 ID 순
            // 같은 줄에서 시작한 호출은 평가 순서다: 범위가 다른 호출에 포함된 쪽(중첩 호출 f(g(x))의 g)이 먼저 끝나므로 끝 위치 순으로 놓는다.
            steps.sort(Comparator.comparing((Step step) -> !before(step)).thenComparing((Step step) -> !positioned(step))
                    .thenComparingInt(step -> positioned(step) ? step.site().startLine() : 0)
                    .thenComparingInt(step -> positioned(step) ? endLine(step.site()) : 0)
                    .thenComparingInt(step -> positioned(step) ? endColumn(step.site()) : 0)
                    .thenComparingInt(step -> positioned(step) ? step.site().startColumn() : 0)
                    .thenComparing(byId));
            return steps;
        }

        /** 핸들러보다 먼저 실행되는 {@code @ModelAttribute} 메서드로 가는 추론 호출인지. 엣지의 위치는 호출 지점이 아니라 대상의 선언이다. */
        private boolean before(Step step) {
            return step.beforeCall();
        }

        /**
         * 호출 지점 위치순으로 놓을 줄인지: 한 파일 안에서 순서가 있는 엣지(호출, 실행, 요청, 포함, 뷰, 구성 요소)이고 줄 번호가 있다.
         * 구현 전개·테이블 접근·트리거와, 끝점마다 파일이 다른 엣지(엔드포인트의 핸들러 등)는 아니다.
         */
        private boolean positioned(Step step) {
            if (step.impl() || before(step) || step.site() == null || step.site().startLine() <= 0) {
                return false;
            }
            return switch (step.via()) {
                case CALLS, EXECUTES, REQUESTS, INCLUDES, RENDERS, USES -> true;
                default -> false;
            };
        }
    }

    /** 위치의 끝 줄. 끝을 모르면(0이거나 시작보다 앞) 시작 줄이다. */
    private static int endLine(SourceLocation at) {
        return at.endLine() >= at.startLine() ? at.endLine() : at.startLine();
    }

    /** 위치의 끝 열. 끝을 모르면 시작 열이다(점 하나짜리 위치). */
    private static int endColumn(SourceLocation at) {
        boolean known = at.endLine() > at.startLine() || (at.endLine() == at.startLine() && at.endColumn() > at.startColumn());
        return known ? at.endColumn() : at.startColumn();
    }

    private static SourceLocation earlier(SourceLocation a, SourceLocation b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        int byFile = a.file().compareTo(b.file());
        if (byFile != 0) {
            return byFile < 0 ? a : b;
        }
        if (a.startLine() != b.startLine()) {
            return a.startLine() < b.startLine() ? a : b;
        }
        return a.startColumn() <= b.startColumn() ? a : b;
    }

    // ---------------------------------------------------------------- 아래 방향

    /**
     * nodeId에서 한 걸음 아래로 갈 수 있는 곳. 수신자 문맥 없이 본다.
     *
     * @param callerId 이 노드에 온 직전 노드(주입 좁히기에 쓴다). 없으면 null
     * @param arrivedVia 이 노드에 올 때 따라온 엣지 종류. 시작 노드면 null
     */
    public List<Step> callees(String nodeId, String callerId, EdgeKind arrivedVia) {
        return callees(nodeId, callerId, arrival(arrivedVia));
    }

    /**
     * nodeId에서 한 걸음 아래로 갈 수 있는 곳. 호출·실행·요청은 호출 지점의 위치순(엣지 location의 줄, 열)이고,
     * 위치를 모르는 것과 구현 전개·테이블·트리거는 그 뒤에 노드 ID 순이다(잡 아래의 스텝은 NEXT 흐름 순서).
     *
     * @param callerId 이 노드에 온 직전 노드(주입 좁히기에 쓴다). 없으면 null
     * @param arrival 이 노드에 올 때의 걸음(따라온 엣지 종류, 수신자 문맥, super 호출 여부). 시작 노드면 null
     */
    public List<Step> callees(String nodeId, String callerId, Step arrival) {
        Steps steps = new Steps();
        NodeKind kind = kindOf(nodeId);
        EdgeKind arrivedVia = arrival == null ? null : arrival.via();
        Context context = arrival == null ? null : arrival.context();
        boolean javaMethod = kind == NodeKind.JAVA_METHOD;
        List<Edge> inherited = new ArrayList<>();
        List<Edge> reflections = new ArrayList<>();
        List<Edge> handlers = new ArrayList<>();
        // @ModelAttribute 메서드는 요청으로 진입한 핸들러보다 먼저 실행된다. 다른 메서드가 Java 호출로 부른 핸들러에서는 실행되지 않는다.
        boolean requestEntry = requestEntry(arrival);
        boolean clientRoute = kind == NodeKind.HTTP_REQUEST && isClientRoute(nodeId);
        List<Edge> views = new ArrayList<>();
        for (Edge e : graph.outgoing(nodeId, FLOW_KINDS)) {
            switch (e.kind()) {
                case REQUESTS -> steps.add(e.to(), e, e.kind(), null, false, false);
                case INCLUDES -> steps.add(e.to(), e, e.kind(), layoutInclude(e) ? DETAIL_LAYOUT : null, false, false);
                case RENDERS -> {
                    if (kind == NodeKind.HTTP_ENDPOINT) {
                        // 뷰 컨트롤러: 핸들러 없이 엔드포인트가 바로 화면을 그린다. 핸들러처럼 모듈마다 하나씩 있을 수 있다.
                        views.add(e);
                    } else {
                        // tiles 정의: template은 레이아웃이고 본문·조각(tilesAttribute)이 그 화면이다.
                        boolean template = "tiles".equals(e.attrString("via")) && !e.attrBoolean("tilesAttribute");
                        steps.add(e.to(), e, e.kind(), template ? DETAIL_LAYOUT : relationDetail(e), false, false);
                    }
                }
                case MATCHES -> {
                    // 클라이언트 라우트(화면 안의 이동)는 엔드포인트 대상이 아니다: 맞는 엔드포인트가 없다는 미해결 줄을 내지 않는다.
                    if (!(clientRoute && unresolvedTarget(e))) {
                        addMatch(steps, e.to(), e);
                    }
                }
                case ROUTES_TO -> handlers.add(e);
                case EXECUTES -> {
                    // 같은 지점에서 다른 방법(접두 일치 등)으로 풀린 참조: 미해결 줄을 따로 내지 않는다.
                    if (resolvedAnotherWay(nodeId, e)) {
                        continue;
                    }
                    if (javaMethod && inheritedStatement(nodeId, e)) {
                        inherited.add(e);
                    } else {
                        steps.add(e.to(), e, e.kind(), relationDetail(e), false, false);
                    }
                }
                case CALLS -> {
                    if (javaMethod && reflectiveFanOut(e)) {
                        reflections.add(e);
                    } else if (requestEntry || !CALL_MODEL_ATTRIBUTE.equals(e.attrString("callType"))) {
                        steps.add(e.to(), e, e.kind(), callDetail(e), false, javaMethod ? calleeContext(nodeId, context, e) : null, false);
                    }
                }
                case USES -> {
                    if (!coveredByUsedMethod(nodeId, e.to())) {
                        steps.add(e.to(), e, e.kind(), e.attrString("role"), false, false);
                    }
                }
                case READS, WRITES -> {
                    String table = DbGraph.accessedTable(graph, e).orElse(null);
                    if (table != null) {
                        steps.addAccess(table, e, nodeId, false);
                    } else if (!e.to().startsWith("db:column:")) {
                        steps.add(e.to(), e, e.kind(), null, false, false);
                    }
                }
                case CONTAINS -> {
                    if (followsContains(kind, e.to(), true)) {
                        steps.add(e.to(), e, e.kind(), null, false, false);
                    }
                }
                default -> {
                }
            }
        }
        // 팬아웃 엣지: 문맥 타입과 맞는 것만. 맞는 것이 없으면 전부([?]).
        // 문맥이 없으면 상속 구문은 전부(전과 같다), 리플렉션 호출은 전부 따라가되 [?]로 표시한다.
        String contextType = context == null ? null : context.type();
        followFanOut(steps, nodeId, context, inherited, contextType, false);
        followFanOut(steps, nodeId, context, reflections, context != null && !context.enclosing() ? contextType : null, true);
        String requestId = arrivedVia == EdgeKind.MATCHES ? callerId : null;
        String requestModule = requestId == null ? null : matchModule(requestId, nodeId);
        addHandlers(steps, nodeId, handlers, requestId, requestModule);
        addHandlers(steps, nodeId, views, requestId, requestModule);
        if ((kind == NodeKind.DB_TABLE || kind == NodeKind.DB_VIEW) && (arrivedVia == null || arrivedVia == EdgeKind.WRITES)) {
            for (Edge e : graph.incoming(nodeId, EdgeKind.TRIGGERS_ON)) {
                // 테이블에서 시작했으면(쓰는 구문을 모른다) 걸린 트리거를 모두 보인다.
                Fires fires = arrivedVia == null || callerId == null ? Fires.YES : fires(e.from(), callerId, nodeId);
                if (fires != Fires.NO) {
                    steps.add(e.from(), e, EdgeKind.TRIGGERS_ON, null, false, false, fires == Fires.UNCERTAIN);
                }
            }
        }
        if (kind == NodeKind.SPRING_BEAN) {
            for (Edge e : graph.incoming(nodeId, EdgeKind.DEFINES_BEAN)) {
                steps.add(e.from(), e, EdgeKind.DEFINES_BEAN, null, false, false);
            }
        }
        // super.m()으로 온 메서드는 상위 구현을 명시적으로 부른 것이라 구현으로 펼치지 않는다.
        if (javaMethod && expandImplementations && !(arrival != null && arrival.superCall()) && isDispatchTarget(nodeId)) {
            Map<String, Confidence> all = overriders(nodeId);
            for (String impl : implementations(nodeId, callerId)) {
                // 근거가 되는 OVERRIDES가 추론이면 구현 줄도 추론이다.
                Confidence byOverride = all.getOrDefault(impl, Confidence.RESOLVED);
                steps.add(impl, byOverride, null, EdgeKind.OVERRIDES, null, true, false, false,
                        implementationContext(context, impl), false, inferenceOf(null, EdgeKind.OVERRIDES, null, byOverride, false, Pick.NONE));
            }
        }
        List<Step> sorted = steps.sorted(true);
        return kind == NodeKind.BATCH_JOB ? stepsInFlowOrder(sorted) : sorted;
    }

    /** callees·callers가 보는 엣지 종류. 그 밖의 엣지(DEPENDS_ON 등)는 인덱스에서 꺼내지도 않는다. */
    private static final EdgeKind[] FLOW_KINDS = {EdgeKind.REQUESTS, EdgeKind.MATCHES, EdgeKind.ROUTES_TO, EdgeKind.RENDERS,
            EdgeKind.INCLUDES, EdgeKind.EXECUTES, EdgeKind.CALLS, EdgeKind.USES, EdgeKind.READS, EdgeKind.WRITES, EdgeKind.CONTAINS};

    /**
     * 팬아웃 엣지 묶음에서 문맥 타입이 고르는 것만 걸음으로 더한다. 고르는 것이 하나도 없으면 전부 더하고 불확실하다고 표시한다.
     *
     * @param contextType 문맥 타입. 없으면 null
     * @param uncertainWithoutContext 문맥이 없어 전부 따라갈 때도 불확실하다고 표시할지
     */
    private void followFanOut(Steps steps, String nodeId, Context context, List<Edge> fanOut, String contextType,
            boolean uncertainWithoutContext) {
        if (fanOut.isEmpty()) {
            return;
        }
        List<Edge> selected = new ArrayList<>();
        if (contextType != null) {
            for (Edge e : fanOut) {
                if (selects(contextType, e)) {
                    selected.add(e);
                }
            }
        }
        boolean anyMatch = !selected.isEmpty();
        boolean uncertain = contextType == null ? uncertainWithoutContext : !anyMatch;
        for (Edge e : anyMatch ? selected : fanOut) {
            boolean call = e.kind() == EdgeKind.CALLS;
            // 문맥이 이 대상 하나를 골랐을 때만 확정이다. 문맥 없이 전부 따라갔거나 여럿과 맞았으면 추론 걸음이다.
            Pick pick = anyMatch && singleTarget(e, selected) ? Pick.SELECTED : Pick.UNSELECTED;
            steps.add(e.to(), e, e.kind(), call ? callDetail(e) : relationDetail(e), false, call ? calleeContext(nodeId, context, e) : null, uncertain, pick);
        }
    }

    /** 요청 ↔ 엔드포인트 대응 걸음. 링커가 다른 모듈의 엔드포인트와 맞춘 것({@code crossModule=true})은 추론으로 표시한다. */
    private void addMatch(Steps steps, String nodeId, Edge matches) {
        addMatch(steps, nodeId, matches, null);
    }

    /** @param detail 줄 끝에 적을 표시(위 방향에서 그 요청의 웹 모듈: {@code 모듈 <경로>}). 없으면 null */
    private void addMatch(Steps steps, String nodeId, Edge matches, String detail) {
        Confidence confidence = matches.attrBoolean("crossModule") && matches.confidence() == Confidence.RESOLVED
                ? Confidence.INFERRED : matches.confidence();
        steps.add(nodeId, confidence, matches.location(), EdgeKind.MATCHES, detail, false, false, false, null, false,
                matches.attrBoolean("ambiguous"), inferenceOf(matches, EdgeKind.MATCHES, null, confidence, false, Pick.NONE));
    }

    /**
     * 엔드포인트 → 핸들러. 메서드와 경로가 같은 엔드포인트는 노드 하나라서, 멀티모듈 프로젝트에서는 서로 다른 웹 모듈의 핸들러
     * 여러 개로 ROUTES_TO가 나간다. 요청에서 왔고(requestId) 그 요청을 낸 파일과 같은 모듈의 핸들러가 있으면 그것만 따라간다.
     * 남은 핸들러가 여러 모듈에 걸쳐 있으면 줄마다 {@code (모듈 <경로>)}를 붙인다.
     *
     * <p>링커가 그 요청의 웹 모듈을 대응에 적어 두었으면({@code MATCHES}의 {@code module}) 경로로 짐작하지 않고 그것을 따른다:
     * 그 모듈의 핸들러와 그 모듈의 선언에서 나온 화면({@code RENDERS} 엣지의 위치가 그 모듈의 파일)만 따라가고, 없으면 아무것도 따라가지 않는다.
     *
     * @param matchModule 요청 → 이 엔드포인트 대응의 {@code module}. 없으면 null
     */
    private void addHandlers(Steps steps, String endpointId, List<Edge> handlers, String requestId, String matchModule) {
        if (handlers.isEmpty()) {
            return;
        }
        List<Edge> followed = handlers;
        String requestModule = requestId == null || matchModule != null ? null : moduleOf(requestId);
        if (matchModule != null) {
            followed = handlers.stream().filter(e -> inModule(declaredIn(e), matchModule)).toList();
        } else if (requestModule != null) {
            List<Edge> sameModule = handlers.stream().filter(e -> requestModule.equals(moduleOf(e.to()))).toList();
            if (!sameModule.isEmpty()) {
                followed = sameModule;
            }
        }
        Set<String> modules = new TreeSet<>();
        followed.forEach(e -> modules.add(String.valueOf(moduleOf(e.to()))));
        for (Edge e : followed) {
            String module = moduleOf(e.to());
            steps.add(e.to(), e, e.kind(), modules.size() > 1 && module != null ? moduleLabel(module) : null, false, false);
        }
    }

    private static String moduleLabel(String module) {
        return "모듈 " + (module.isEmpty() ? "." : module);
    }

    /**
     * 노드가 든 모듈의 루트 경로: 노드 위치의 파일 경로에서 {@code /src/main/}·{@code /src/test/} 앞부분이다
     * (루트 모듈이면 빈 문자열). 위치가 없거나 그런 조각이 없으면 null.
     */
    private String moduleOf(String nodeId) {
        return moduleOfFile(fileOf(nodeId));
    }

    private String fileOf(String nodeId) {
        SourceLocation location = graph.node(nodeId).map(Node::location).orElse(null);
        return location == null ? null : location.file();
    }

    /** 요청 → 엔드포인트 대응({@code MATCHES})에 링커가 적은 그 요청의 웹 모듈 루트 경로({@code module}). 없으면 null. */
    private String matchModule(String requestId, String endpointId) {
        for (Edge e : graph.outgoing(requestId, EdgeKind.MATCHES)) {
            if (e.to().equals(endpointId) && e.attrString("module") != null) {
                return e.attrString("module");
            }
        }
        return null;
    }

    /**
     * 엔드포인트에서 나가는 엣지가 선언된 파일: 핸들러({@code ROUTES_TO})는 그 메서드의 파일, 뷰 컨트롤러의 화면({@code RENDERS})은
     * 엣지의 위치(그 선언이 있는 설정 파일. 없으면 화면의 파일).
     */
    private String declaredIn(Edge e) {
        if (e.kind() == EdgeKind.RENDERS && e.location() != null && e.location().file() != null) {
            return e.location().file();
        }
        return fileOf(e.to());
    }

    /**
     * file이 module(모듈 루트 경로. 루트 모듈은 빈 문자열이나 {@code .})에 든 파일인지. 파일 경로에 {@code src/main/}·{@code src/test/}가
     * 있으면 그 앞부분이 모듈과 같아야 하고(하위 모듈의 파일은 상위 모듈의 것이 아니다), 없으면 모듈 경로 아래에 있으면 된다.
     */
    private static boolean inModule(String file, String module) {
        if (file == null) {
            return false;
        }
        String root = module.equals(".") ? "" : module.endsWith("/") ? module.substring(0, module.length() - 1) : module;
        String own = moduleOfFile(file);
        if (own != null) {
            return own.equals(root);
        }
        return root.isEmpty() || file.startsWith(root + "/");
    }

    /** 엔드포인트가 핸들러·뷰 컨트롤러의 화면을 둘 이상 가졌는지: 어느 요청(어느 핸들러)에서 왔느냐에 따라 그 아래(위)가 달라진다. */
    public boolean severalHandlers(String endpointId) {
        return graph.outgoing(endpointId, EdgeKind.ROUTES_TO, EdgeKind.RENDERS).size() > 1;
    }

    /** 엔드포인트의 선언(핸들러 메서드, 뷰 컨트롤러 선언)이 둘 이상의 모듈에 있는지. */
    private boolean declaredInSeveralModules(String endpointId) {
        Set<String> modules = new HashSet<>();
        for (Edge e : graph.outgoing(endpointId, EdgeKind.ROUTES_TO, EdgeKind.RENDERS)) {
            modules.add(String.valueOf(moduleOfFile(declaredIn(e))));
        }
        return modules.size() > 1;
    }

    /**
     * 엔드포인트의 URL로 넘어오는 forward·redirect: 그 엔드포인트의 핸들러로 들어오는 {@code callType=forward|redirect}인 {@code CALLS}
     * (링커는 넘어가는 경로를 엔드포인트에 맞춘 뒤 그 핸들러 메서드로 잇는다). 핸들러가 엔드포인트 여럿을 받으면 엣지의 {@code path}
     * (쿼리 제외)가 이 엔드포인트의 경로와 같은 것만 고르고, 그 핸들러의 어느 엔드포인트와도 같지 않으면(컨텍스트 경로 등) 가르지 못하므로 넣는다.
     */
    private List<Edge> requestEntriesInto(String endpointId) {
        List<Edge> entries = new ArrayList<>();
        for (Edge route : graph.outgoing(endpointId, EdgeKind.ROUTES_TO)) {
            List<String> siblings = new ArrayList<>();
            for (Edge other : graph.incoming(route.to(), EdgeKind.ROUTES_TO)) {
                if (!siblings.contains(other.from())) {
                    siblings.add(other.from());
                }
            }
            for (Edge e : graph.incoming(route.to(), EdgeKind.CALLS)) {
                if (!entersByRequest(e)) {
                    continue;
                }
                if (siblings.size() > 1) {
                    String path = e.attrString("path");
                    boolean here = samePath(endpointId, path);
                    if (!here && siblings.stream().anyMatch(sibling -> samePath(sibling, path))) {
                        continue;
                    }
                }
                entries.add(e);
            }
        }
        return entries;
    }

    /** path(쿼리·fragment 제외, 끝 슬래시 무시)가 엔드포인트의 경로({@code resolvedPath}, {@code path}, ID의 경로)와 같은지. */
    private boolean samePath(String endpointId, String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String wanted = trimPath(path);
        Node endpoint = graph.node(endpointId).orElse(null);
        String rest = Ids.remainder(endpointId);
        int space = rest.indexOf(' ');
        List<String> own = new ArrayList<>();
        own.add(space < 0 ? rest : rest.substring(space + 1));
        if (endpoint != null) {
            own.add(endpoint.attrString("resolvedPath"));
            own.add(endpoint.attrString("path"));
        }
        for (String candidate : own) {
            if (candidate != null && trimPath(candidate).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    private static String trimPath(String path) {
        String s = path.strip();
        int cut = s.length();
        for (char mark : new char[] {'?', '#'}) {
            int at = s.indexOf(mark);
            if (at >= 0 && at < cut) {
                cut = at;
            }
        }
        s = s.substring(0, cut);
        return s.length() > 1 && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String moduleOfFile(String file) {
        if (file == null) {
            return null;
        }
        for (String marker : List.of("src/main/", "src/test/")) {
            if (file.startsWith(marker)) {
                return "";
            }
            int at = file.indexOf("/" + marker);
            if (at >= 0) {
                return file.substring(0, at);
            }
        }
        return null;
    }

    /**
     * arrival로 도착한 nodeId의 전개 결과를 가르는 문맥의 키. 문맥과 무관하면 빈 문자열이다.
     * 반복 억제나 방문 표시의 키에 노드 ID 뒤에 붙인다: 수신자 문맥이 다르면 같은 메서드라도 아래가 다르고,
     * super 호출로 온 디스패치 대상은 구현으로 펼치지 않는다.
     */
    public String arrivalKey(String nodeId, Step arrival) {
        if (arrival == null) {
            return "";
        }
        String key = arrival.context() == null ? "" : "|" + arrival.context().key();
        if (arrival.superCall() && expandImplementations && !overriders(nodeId).isEmpty() && isDispatchTarget(nodeId)) {
            key += "|super";
        }
        // 요청으로 진입한 핸들러 아래에만 요청 전 처리([before])가 붙는다: Java 호출로 닿은 같은 핸들러와는 아래가 다르다.
        if (requestEntry(arrival) && hasBeforeCalls(nodeId)) {
            key += "|entry";
        }
        // [before]로 올라온 핸들러에서는 엔드포인트로만 올라간다(위 방향. 아래 방향에서 도착한 @ModelAttribute 메서드는 아래가 같다).
        if (arrival.beforeCall() && hasBeforeCalls(nodeId)) {
            key += "|before";
        }
        return key;
    }

    /**
     * arrival로 도착한 메서드가 "요청으로 진입한 핸들러"인지: 트리의 시작 노드이거나, 엔드포인트에서 {@code ROUTES_TO}로 도착했거나,
     * 다른 핸들러의 redirect·forward로 도착했다(둘 다 스프링이 요청을 다시 디스패치한다). 이때만 {@code callType=modelAttribute} 엣지를 따른다.
     */
    private static boolean requestEntry(Step arrival) {
        return arrival == null || arrival.via() == EdgeKind.ROUTES_TO || arrival.redirect() || arrival.forward();
    }

    /** methodId에서 {@code callType=modelAttribute} 호출이 나가는지(요청 전 처리가 있는 핸들러). */
    private boolean hasBeforeCalls(String methodId) {
        if (!isJavaMethodId(methodId)) {
            return false;
        }
        return beforeCache.computeIfAbsent(methodId, id -> {
            for (Edge e : graph.outgoing(id, EdgeKind.CALLS)) {
                if (CALL_MODEL_ATTRIBUTE.equals(e.attrString("callType"))) {
                    return true;
                }
            }
            return false;
        });
    }

    /** 레이아웃 관계인 INCLUDES인지: JSP → JSP이고 {@code via}가 {@code sitemesh}(내용 페이지 → 데코레이터)나 {@code tiles}(template → 조각). */
    private boolean layoutInclude(Edge e) {
        String via = e.attrString("via");
        return e.kind() == EdgeKind.INCLUDES && ("sitemesh".equals(via) || "tiles".equals(via))
                && kindOf(e.from()) == NodeKind.JSP_PAGE && kindOf(e.to()) == NodeKind.JSP_PAGE;
    }

    /** 이 JSP를 레이아웃으로 쓰는 화면 수(레이아웃 INCLUDES로 이 JSP를 가리키는 서로 다른 JSP). 위 방향에서 한 줄로 접어 알릴 때 쓴다. */
    public int layoutUsers(String jspId) {
        Set<String> users = new HashSet<>();
        for (Edge e : graph.incoming(jspId, EdgeKind.INCLUDES)) {
            if (layoutInclude(e)) {
                users.add(e.from());
            }
        }
        return users.size();
    }

    /** 클라이언트 라우트인지: 서버로 가는 요청이 아니라 화면 안의 이동(React Router 등)을 적은 {@code HTTP_REQUEST}({@code clientRoute=true}). */
    public boolean isClientRoute(String requestId) {
        return graph.node(requestId).map(n -> n.kind() == NodeKind.HTTP_REQUEST && n.attrBoolean("clientRoute")).orElse(false);
    }

    /** 엣지의 대상이 미해결인지(신뢰도가 UNRESOLVED이거나 대상이 없거나 UNRESOLVED 노드). */
    private boolean unresolvedTarget(Edge e) {
        return e.confidence() == Confidence.UNRESOLVED || graph.node(e.to()).map(n -> n.kind() == NodeKind.UNRESOLVED).orElse(true);
    }

    /** 엣지 종류만 아는 도착 걸음. 시작 노드면 null. */
    private static Step arrival(EdgeKind via) {
        return via == null ? null : new Step(null, via, Confidence.RESOLVED, null, null, false, false, false);
    }

    /** 잡의 자식 가운데 스텝을 NEXT 흐름 순서로 앞에 놓는다({@link BatchFlow}). 스텝이 아닌 자식은 그 뒤에 ID 순이다. */
    private List<Step> stepsInFlowOrder(List<Step> sorted) {
        Map<String, Step> stepNodes = new LinkedHashMap<>();
        List<Step> others = new ArrayList<>();
        for (Step s : sorted) {
            if (kindOf(s.nodeId()) == NodeKind.BATCH_STEP && !stepNodes.containsKey(s.nodeId())) {
                stepNodes.put(s.nodeId(), s);
            } else {
                others.add(s);
            }
        }
        List<Step> result = new ArrayList<>();
        for (String id : BatchFlow.order(graph, stepNodes.keySet())) {
            result.add(stepNodes.get(id));
        }
        result.addAll(others);
        return result;
    }

    /**
     * 호출 줄에 덧붙일 표시: 뷰 이름으로 넘어가는 redirect/forward, 상위 구현을 부르는 super,
     * 리플렉션으로 추론한 호출({@value #CALL_REFLECTION}), 요청 전 처리({@value #CALL_MODEL_ATTRIBUTE}).
     */
    private static String callDetail(Edge e) {
        String callType = e.attrString("callType");
        if (e.attrBoolean("loadUnverified")) {
            return DETAIL_LOAD_UNVERIFIED;
        }
        if (callType == null) {
            return null;
        }
        return switch (callType) {
            case "redirect", "forward", "super", CALL_REFLECTION, CALL_MODEL_ATTRIBUTE -> callType;
            default -> null;
        };
    }

    /**
     * 실행·뷰 줄에 덧붙일 표시: 접두만 맞은 구문({@value #DETAIL_PREFIX_MATCH}), 인자로 넘긴 구문 ID({@value #DETAIL_VIA_ARGUMENT}),
     * 동적 조각이 든 뷰 이름의 후보({@value #DETAIL_DYNAMIC_VIEW}. 링커가 좁힌 것은 {@code narrowedBy}에 따라
     * {@value #DETAIL_DYNAMIC_VIEW_BY_PROPERTY}, {@value #DETAIL_DYNAMIC_VIEW_BY_MODULE}). 모두 추론 엣지다.
     */
    private static String relationDetail(Edge e) {
        if (e.kind() == EdgeKind.EXECUTES) {
            return e.attrBoolean("viaArgument") ? DETAIL_VIA_ARGUMENT : e.attrBoolean("prefixMatch") ? DETAIL_PREFIX_MATCH : null;
        }
        if (e.kind() == EdgeKind.RENDERS) {
            if (!e.attrBoolean("dynamicSegment")) {
                return null;
            }
            // 링커가 근거(프로퍼티 값, 모듈 디렉터리 이름)로 후보를 좁힌 것은 "후보"라 부르지 않고 그 근거를 적는다.
            String narrowedBy = e.attrString("narrowedBy");
            return "property".equals(narrowedBy) ? DETAIL_DYNAMIC_VIEW_BY_PROPERTY
                    : "moduleName".equals(narrowedBy) ? DETAIL_DYNAMIC_VIEW_BY_MODULE : DETAIL_DYNAMIC_VIEW;
        }
        return null;
    }

    /**
     * 미해결 EXECUTES가 같은 지점에서 다른 방법으로 풀렸는지: 같은 출발 노드에서 같은 위치로 나가는 EXECUTES 가운데
     * {@code resolvedFrom}이 이 엣지의 미해결 대상인 것이 있다(링커가 접두 일치 등으로 후보 구문에 이은 것).
     */
    private boolean resolvedAnotherWay(String fromId, Edge executes) {
        if (!unresolvedTarget(executes)) {
            return false;
        }
        for (Edge other : graph.outgoing(fromId, EdgeKind.EXECUTES)) {
            if (executes.to().equals(other.attrString("resolvedFrom")) && sameStart(other.location(), executes.location())) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameStart(SourceLocation a, SourceLocation b) {
        if (a == null || b == null) {
            return a == b;
        }
        return java.util.Objects.equals(a.file(), b.file()) && a.startLine() == b.startLine() && a.startColumn() == b.startColumn();
    }

    // ---------------------------------------------------------------- 트리거

    /**
     * statementId가 tableId에 하는 쓰기가 triggerId를 일으키는지.
     * <ul>
     * <li>쓰기의 종류(WRITES의 op, 없으면 구문의 statementType)가 트리거의 사건과 맞아야 한다. MERGE는 INSERT와 UPDATE 둘 다다.
     *     {@code truncate=true}인 쓰기는 DML 트리거를 일으키지 않는다.</li>
     * <li>{@code UPDATE OF 컬럼} 트리거는 구문의 WRITES 컬럼 엣지가 그 컬럼을 포함할 때만 맞다.
     *     그 테이블로 가는 WRITES 컬럼 엣지가 하나도 없는 구문이면 알 수 없다.</li>
     * <li>트리거의 {@code triggerEvent}가 없거나 사건을 알아보지 못하면, 또는 쓰기의 종류를 모르면 알 수 없다.</li>
     * </ul>
     */
    private Fires fires(String triggerId, String statementId, String tableId) {
        Set<String> ops = new TreeSet<>();
        Set<String> updatedColumns = new TreeSet<>();
        boolean unknownOp = false;
        boolean anyWrite = false;
        for (Edge e : graph.outgoing(statementId, EdgeKind.WRITES)) {
            boolean column = Access.isColumn(graph, e.to());
            if (!tableId.equals(column ? DbGraph.tableOfColumn(graph, e.to()).orElse(null) : e.to()) || e.attrBoolean("truncate")) {
                continue;
            }
            anyWrite = true;
            String op = Access.writeOp(graph, e, statementId);
            if (op == null) {
                unknownOp = true;
                continue;
            }
            ops.add(op);
            if (column && (op.equals("UPDATE") || op.equals("MERGE"))) {
                updatedColumns.add(columnName(e.to(), tableId));
            }
        }
        if (!anyWrite) {
            return Fires.NO;
        }
        TriggerEvent event = TriggerEvent.parse(graph.node(triggerId).map(n -> n.attrString("triggerEvent")).orElse(null));
        if (!event.known()) {
            return Fires.UNCERTAIN;
        }
        boolean inserts = ops.contains("INSERT") || ops.contains("MERGE");
        boolean updates = ops.contains("UPDATE") || ops.contains("MERGE");
        boolean deletes = ops.contains("DELETE");
        boolean uncertain = unknownOp;
        for (TriggerEvent.Clause clause : event.clauses()) {
            switch (clause.event()) {
                case "INSERT" -> {
                    if (inserts) {
                        return Fires.YES;
                    }
                }
                case "DELETE" -> {
                    if (deletes) {
                        return Fires.YES;
                    }
                }
                default -> {
                    if (updates && clause.coversAny(updatedColumns)) {
                        return Fires.YES;
                    }
                    // 컬럼 지정 트리거인데 구문이 어느 컬럼을 쓰는지 모른다.
                    uncertain |= updates && updatedColumns.isEmpty();
                }
            }
        }
        return uncertain ? Fires.UNCERTAIN : Fires.NO;
    }

    private static String columnName(String columnId, String tableId) {
        String rest = Ids.remainder(columnId);
        String prefix = Ids.remainder(tableId) + ".";
        return rest.startsWith(prefix) ? rest.substring(prefix.length()) : rest.substring(rest.lastIndexOf('.') + 1);
    }

    /** CONTAINS 중 흐름으로 따라가는 것인지. */
    private boolean followsContains(NodeKind from, String toId, boolean downward) {
        NodeKind to = kindOf(toId);
        return switch (from) {
            case DB_ROUTINE -> to == NodeKind.SQL_STATEMENT;
            case BATCH_JOB -> to == NodeKind.BATCH_STEP;
            case JSP_PAGE -> to == NodeKind.JS_MODULE;
            case JS_MODULE, JS_FUNCTION -> to == NodeKind.JS_FUNCTION;
            case JAVA_TYPE -> downward && to == NodeKind.JAVA_METHOD && isEntryMethod(toId);
            default -> false;
        };
    }

    /** 스텝이 쓰는 타입 아래로 펼칠 메서드: 생성자가 아닌 public 메서드(수정자 정보가 없으면 전부). */
    private boolean isEntryMethod(String methodId) {
        Node method = graph.node(methodId).orElse(null);
        if (method == null || method.attrBoolean("constructor") || methodId.contains("#" + Ids.CONSTRUCTOR_NAME + "(")
                || methodId.contains("#lambda$") || methodId.contains("#<clinit>")) {
            return false;
        }
        List<String> modifiers = method.attrList("modifiers");
        return modifiers.isEmpty() || modifiers.contains("public");
    }

    /** 이 메서드가 호출될 때 구현으로 디스패치될 수 있는지(추상 메서드이거나 인터페이스의 인스턴스 메서드). */
    public boolean isDispatchTarget(String methodId) {
        Node method = graph.node(methodId).orElse(null);
        if (method == null || method.kind() != NodeKind.JAVA_METHOD) {
            return false;
        }
        List<String> modifiers = method.attrList("modifiers");
        if (modifiers.contains("abstract")) {
            return true;
        }
        if (modifiers.contains("static")) {
            return false;
        }
        if (graph.node(ownerType(methodId)).map(t -> "interface".equals(t.attrString("typeKind"))).orElse(false)) {
            return true;
        }
        for (Edge e : graph.incoming(methodId, EdgeKind.CALLS)) {
            if ("interface".equals(e.attrString("callType"))) {
                return true;
            }
        }
        return false;
    }

    /** 메서드를 가진 타입의 ID. CONTAINS를 먼저 보고 없으면 메서드 ID에서 만든다. */
    public String ownerType(String methodId) {
        for (Edge e : graph.incoming(methodId, EdgeKind.CONTAINS)) {
            if (kindOf(e.from()) == NodeKind.JAVA_TYPE) {
                return e.from();
            }
        }
        String rest = Ids.remainder(methodId);
        int hash = rest.indexOf('#');
        return Ids.javaType(hash < 0 ? rest : rest.substring(0, hash));
    }

    /**
     * methodId가 호출될 때 실행될 수 있는 구현: methodId를 (전이적으로) 재정의·구현한 메서드 가운데 본문이 있는 것.
     * callerId의 주입 정보가 있으면 주입되는 타입이 실제로 실행하는 것으로 좁힌다. 좁힌 결과가 비면 좁히지 않는다.
     */
    private List<String> implementations(String methodId, String callerId) {
        List<String> impls = List.copyOf(overriders(methodId).keySet());
        if (!narrowByInjection || callerId == null || kindOf(callerId) != NodeKind.JAVA_METHOD || impls.isEmpty()) {
            return impls;
        }
        List<String> targets = injectedTargets(ownerType(callerId), methodId);
        if (targets.isEmpty()) {
            return impls;
        }
        // 주입되는 타입이 methodId 자신을 실행하면(본문이 있는 메서드를 그대로 쓰는 타입) 펼칠 구현이 없다.
        targets.remove(methodId);
        return targets;
    }

    /**
     * methodId를 재정의·구현한 메서드 전부(ID 순). OVERRIDES는 가장 가까운 상위 하나로만 가므로
     * ({@code Vip#discount → Default#discount → DiscountPolicy#discount}) 거꾸로 끝까지 따라가야 간접 구현이 나온다.
     * 본문이 없는 것(추상 메서드, 인터페이스의 추상 메서드)은 거쳐 가기만 하고 결과에 넣지 않는다.
     */
    private Map<String, Confidence> overriders(String methodId) {
        Map<String, Confidence> cached = overridersCache.get(methodId);
        if (cached != null) {
            return cached;
        }
        // 메서드 → 거기까지 온 OVERRIDES 사슬에서 가장 낮은 신뢰도(처음 닿은 경로의 것)
        Map<String, Confidence> reached = new HashMap<>();
        reached.put(methodId, Confidence.RESOLVED);
        Map<String, Confidence> result = new java.util.TreeMap<>();
        List<String> frontier = List.of(methodId);
        for (int depth = 0; depth < MAX_HIERARCHY_DEPTH && !frontier.isEmpty(); depth++) {
            List<String> next = new ArrayList<>();
            for (String id : frontier) {
                Confidence soFar = reached.get(id);
                List<Edge> edges = new ArrayList<>(graph.incoming(id, EdgeKind.OVERRIDES));
                // 같은 메서드로 가는 엣지가 여럿이면 확실한 것을 먼저 본다(입력 순서에 기대지 않게).
                edges.sort(Comparator.comparing(Edge::from).thenComparing(Edge::confidence));
                for (Edge e : edges) {
                    if (!reached.containsKey(e.from())) {
                        Confidence confidence = soFar.ordinal() >= e.confidence().ordinal() ? soFar : e.confidence();
                        reached.put(e.from(), confidence);
                        next.add(e.from());
                        if (!isAbstract(e.from())) {
                            result.put(e.from(), confidence == Confidence.RESOLVED ? Confidence.RESOLVED : Confidence.INFERRED);
                        }
                    }
                }
            }
            frontier = next;
        }
        Map<String, Confidence> frozen = java.util.Collections.unmodifiableMap(result);
        overridersCache.put(methodId, frozen);
        return frozen;
    }

    /** 본문이 없는 메서드인지: {@code abstract}이거나, 인터페이스의 {@code default}·{@code static}·{@code private}이 아닌 메서드. */
    private boolean isAbstract(String methodId) {
        Node method = graph.node(methodId).orElse(null);
        if (method == null) {
            return false;
        }
        List<String> modifiers = method.attrList("modifiers");
        if (modifiers.contains("abstract")) {
            return true;
        }
        if (modifiers.contains("default") || modifiers.contains("static") || modifiers.contains("private")) {
            return false;
        }
        return graph.node(ownerType(methodId)).map(t -> "interface".equals(t.attrString("typeKind"))).orElse(false);
    }

    /**
     * callerType에 주입되는 구현 타입이 methodId 호출에서 실제로 실행하는 메서드(ID 순). 주입 정보가 없으면 빈 목록.
     * 후보 타입마다 그 타입에 선언된 재정의가 있으면 그것, 없으면 EXTENDS를 따라 올라가며 가장 가까운 상위 클래스의 구현을 고른다.
     * 후보 타입의 하위 클래스가 재정의한 것은 고르지 않는다. methodId 자신도 본문이 있으면 고를 수 있다.
     */
    private List<String> injectedTargets(String callerType, String methodId) {
        Set<String> candidates = injectedImplementations(callerType, ownerType(methodId));
        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, String> byOwner = new HashMap<>();
        for (String impl : overriders(methodId).keySet()) {
            byOwner.putIfAbsent(ownerType(impl), impl);
        }
        if (!isAbstract(methodId)) {
            byOwner.putIfAbsent(ownerType(methodId), methodId);
        }
        Set<String> targets = new TreeSet<>();
        for (String candidate : candidates) {
            String current = candidate;
            Set<String> walked = new HashSet<>();
            for (int i = 0; i < MAX_HIERARCHY_DEPTH && current != null && walked.add(current); i++) {
                String impl = byOwner.get(current);
                if (impl != null) {
                    targets.add(impl);
                    break;
                }
                current = superclass(current);
            }
        }
        return new ArrayList<>(targets);
    }

    /** EXTENDS로 가는 상위 클래스. 여럿이면(인터페이스의 다중 상속) ID가 앞선 것, 없으면 null. */
    private String superclass(String typeId) {
        String first = null;
        for (Edge e : graph.outgoing(typeId, EdgeKind.EXTENDS)) {
            if (first == null || e.to().compareTo(first) < 0) {
                first = e.to();
            }
        }
        return first;
    }

    /**
     * callerType이 interfaceType으로 주입받는 구현 타입. 정보가 없으면 빈 집합.
     * <ul>
     * <li>INJECTS(callerType→interfaceType)의 attrs.candidates</li>
     * <li>attrs.resolvedFrom이 interfaceType인 INFERRED INJECTS(callerType→구현 타입)가 정확히 하나일 때 그 대상</li>
     * <li>대상이 interfaceType을 구현·상속하는 구체 타입인 INJECTS(신뢰도와 resolvedFrom의 형식을 가리지 않는다):
     *     XML 주입({@code resolvedFrom=spring:bean:…}, {@code via=xml}), 필드 타입이 구현 클래스인 직접 주입.
     *     interfaceType으로 가는 주입이 후보 없이 따로 있으면(어느 구현이 들어오는지 모르는 주입) 이 규칙으로는 좁히지 않는다.
     *     다만 interfaceType 자신이 구체 타입이면 그것도 후보에 넣는다.</li>
     * </ul>
     */
    Set<String> injectedImplementations(String callerType, String interfaceType) {
        Set<String> allowed = new TreeSet<>();
        String interfaceName = Ids.remainder(interfaceType);
        List<String> inferredTargets = new ArrayList<>();
        Set<String> direct = new TreeSet<>();
        boolean unknownInjection = false;
        for (Edge e : graph.outgoing(callerType, EdgeKind.INJECTS)) {
            if (e.to().equals(interfaceType)) {
                unknownInjection |= e.attrList("candidates").isEmpty();
                for (String candidate : e.attrList("candidates")) {
                    String type = candidateType(candidate);
                    if (type != null) {
                        allowed.add(type);
                    }
                }
            }
            String resolvedFrom = e.attrString("resolvedFrom");
            if (e.confidence() == Confidence.INFERRED && resolvedFrom != null
                    && (resolvedFrom.equals(interfaceType) || resolvedFrom.equals(interfaceName)) && !inferredTargets.contains(e.to())) {
                inferredTargets.add(e.to());
            }
            if (!e.to().equals(interfaceType) && isConcreteType(e.to()) && isSubtype(e.to(), interfaceType)) {
                direct.add(e.to());
            }
        }
        if (inferredTargets.size() == 1) {
            allowed.add(inferredTargets.get(0));
        }
        if (!direct.isEmpty()) {
            if (!unknownInjection) {
                allowed.addAll(direct);
            } else if (isConcreteType(interfaceType)) {
                allowed.addAll(direct);
                allowed.add(interfaceType);
            }
        }
        return allowed;
    }

    /** 소스에 정의가 있는 클래스이고 추상이 아닌지(인터페이스·추상 클래스·애너테이션이 아님). */
    private boolean isConcreteType(String typeId) {
        Node type = graph.node(typeId).orElse(null);
        if (type == null || type.kind() != NodeKind.JAVA_TYPE) {
            return false;
        }
        String typeKind = type.attrString("typeKind");
        return !"interface".equals(typeKind) && !"annotation".equals(typeKind) && !type.attrList("modifiers").contains("abstract");
    }

    /** candidates 값(타입 ID, 바이너리 이름, 빈 ID나 빈 이름)을 타입 ID로 바꾼다. */
    private String candidateType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.startsWith("java:type:")) {
            return value;
        }
        String beanId = value.startsWith("spring:bean:") ? value : Ids.springBean(value);
        if (graph.hasNode(beanId)) {
            for (Edge e : graph.incoming(beanId, EdgeKind.DEFINES_BEAN)) {
                if (kindOf(e.from()) == NodeKind.JAVA_TYPE) {
                    return e.from();
                }
            }
        }
        return value.startsWith("spring:bean:") ? null : Ids.javaType(value);
    }

    // ---------------------------------------------------------------- 위 방향

    /** {@link #callers(String, String, EdgeKind)}와 같되 직전 노드를 모른다(트리거에서 온 테이블이면 쓰는 구문을 모두 낸다). */
    public List<Step> callers(String nodeId, EdgeKind arrivedVia) {
        return callers(nodeId, null, arrivedVia);
    }

    /** {@link #callers(String, String, Step)}와 같되 수신자 문맥 없이 본다. */
    public List<Step> callers(String nodeId, String fromId, EdgeKind arrivedVia) {
        return callers(nodeId, fromId, arrival(arrivedVia));
    }

    /**
     * nodeId로 한 걸음에 올 수 있는 곳(노드 ID 순).
     *
     * @param fromId 이 노드에 온 직전 노드(아래쪽). 트리거에서 테이블로 왔을 때 그 트리거를 일으키는 구문만 고르는 데 쓴다. 없으면 null
     * @param arrival 이 노드에 올 때의 걸음(따라온 엣지 종류, 필요한 수신자 타입). 시작 노드면 null
     */
    public List<Step> callers(String nodeId, String fromId, Step arrival) {
        Steps steps = new Steps();
        NodeKind kind = kindOf(nodeId);
        EdgeKind arrivedVia = arrival == null ? null : arrival.via();
        Context need = arrival == null ? null : arrival.context();
        boolean javaMethod = kind == NodeKind.JAVA_METHOD;
        boolean tableLike = kind == NodeKind.DB_TABLE || kind == NodeKind.DB_VIEW;
        boolean writesOnly = tableLike && arrivedVia == EdgeKind.TRIGGERS_ON;
        // @ModelAttribute 메서드에서 [before]로 올라온 핸들러: 그 메서드가 실행되는 것은 이 핸들러가 요청으로 진입했을 때뿐이므로
        // 위로는 엔드포인트(와 redirect·forward로 넘어오는 핸들러)로만 올라간다. 이 핸들러를 Java 호출로 부른 쪽은 호출자가 아니다.
        boolean entryOnly = javaMethod && arrival != null && arrival.beforeCall();
        // 트리거에서 올라온 테이블: 구문마다 그 트리거를 일으키는지 한 번씩만 따진다.
        Map<String, Fires> firing = new HashMap<>();
        Function<String, Fires> firesFor = statement -> !writesOnly || fromId == null ? Fires.YES
                : firing.computeIfAbsent(statement, st -> fires(fromId, st, nodeId));
        // 핸들러에서 올라온 엔드포인트: 그 핸들러로 가는 요청만(다른 모듈의 핸들러가 받는 요청은 뺀다).
        Set<String> handlerModules = new TreeSet<>();
        String handlerModule = null;
        if (kind == NodeKind.HTTP_ENDPOINT && arrivedVia == EdgeKind.ROUTES_TO && fromId != null) {
            handlerModule = moduleOf(fromId);
            for (Edge e : graph.outgoing(nodeId, EdgeKind.ROUTES_TO)) {
                handlerModules.add(String.valueOf(moduleOf(e.to())));
            }
        }
        // 링커가 요청의 웹 모듈을 적은 대응(MATCHES의 module)은 그 모듈의 핸들러·선언에서 올라왔을 때만 따라간다.
        List<String> arrivedFrom = new ArrayList<>();
        if (kind == NodeKind.HTTP_ENDPOINT && fromId != null && (arrivedVia == EdgeKind.ROUTES_TO || arrivedVia == EdgeKind.RENDERS)) {
            for (Edge e : graph.outgoing(nodeId, arrivedVia)) {
                if (e.to().equals(fromId)) {
                    arrivedFrom.add(String.valueOf(declaredIn(e)));
                }
            }
        }
        // 선언이 여러 모듈에 있는 엔드포인트에서 시작했으면(어느 핸들러에서 올라온 것이 아니면) 요청 줄마다 그 요청의 웹 모듈을 밝힌다.
        boolean tagRequestModules = kind == NodeKind.HTTP_ENDPOINT && arrivedFrom.isEmpty() && declaredInSeveralModules(nodeId);
        // 엔드포인트에서 시작했으면 그 URL로 넘어오는 forward·redirect 핸들러도 호출자다(핸들러에서 올라왔으면 핸들러의 호출자에 이미 있다).
        if (kind == NodeKind.HTTP_ENDPOINT && arrivedVia != EdgeKind.ROUTES_TO) {
            for (Edge e : requestEntriesInto(nodeId)) {
                steps.add(e.from(), e, EdgeKind.CALLS, callDetail(e), false, false);
            }
        }
        for (Edge e : graph.incoming(nodeId, FLOW_KINDS)) {
            switch (e.kind()) {
                case REQUESTS, ROUTES_TO ->
                    steps.add(e.from(), e, e.kind(), null, false, false);
                case RENDERS ->
                    steps.add(e.from(), e, e.kind(), relationDetail(e), false, false);
                case INCLUDES -> {
                    // 레이아웃을 쓰는 화면 전부로 올라가지 않는다(layoutUsers로 수만 알린다).
                    boolean layout = layoutInclude(e);
                    if (!layout || expandLayouts) {
                        steps.add(e.from(), e, e.kind(), layout ? DETAIL_LAYOUT : null, false, false);
                    }
                }
                case MATCHES -> {
                    // 클라이언트 라우트는 엔드포인트를 요청하지 않는다(미해결 대상으로 모인 것).
                    if (kind == NodeKind.UNRESOLVED && isClientRoute(e.from())) {
                        continue;
                    }
                    String matchModule = e.attrString("module");
                    if (matchModule != null && !arrivedFrom.isEmpty()) {
                        if (arrivedFrom.stream().anyMatch(file -> inModule(file, matchModule))) {
                            addMatch(steps, e.from(), e);
                        }
                        continue;
                    }
                    String requestModule = handlerModule == null ? null : moduleOf(e.from());
                    if (requestModule == null || requestModule.equals(handlerModule) || !handlerModules.contains(requestModule)) {
                        addMatch(steps, e.from(), e, tagRequestModules && matchModule != null ? moduleLabel(matchModule) : null);
                    }
                }
                case EXECUTES -> {
                    // 상속 메서드가 실행하는 구문: 그 메서드의 호출자는 수신자가 이 구문의 namespace와 맞아야 한다.
                    Context required = kindOf(e.from()) == NodeKind.JAVA_METHOD && inheritedStatement(e.from(), e)
                            ? new Context(namespaceOf(e), false, e.from()) : null;
                    // 상속 메서드로 올라간 걸음은 "확인 대기"다: 수신자가 이 namespace와 맞는 호출자가 확인한다(조건이 문맥에 실린다).
                    steps.add(e.from(), e, e.kind(), relationDetail(e), false, required, false, required != null ? Pick.SELECTED : Pick.NONE);
                }
                case CALLS -> {
                    if (entryOnly && !entersByRequest(e)) {
                        continue;
                    }
                    if (javaMethod) {
                        addCaller(steps, nodeId, e, need, false);
                    } else {
                        steps.add(e.from(), e, e.kind(), callDetail(e), false, false);
                    }
                }
                case USES -> {
                    if (!entryOnly) {
                        steps.add(e.from(), e, e.kind(), e.attrString("role"), false, false);
                    }
                }
                case READS, WRITES -> {
                    Fires fires = writesOnly && e.kind() == EdgeKind.READS ? Fires.NO : firesFor.apply(e.from());
                    if (fires != Fires.NO) {
                        steps.addAccess(e.from(), e, e.from(), fires == Fires.UNCERTAIN);
                    }
                }
                case CONTAINS -> {
                    NodeKind from = kindOf(e.from());
                    if (entryOnly) {
                        continue;
                    }
                    if (followsContains(from, nodeId, false) || (from == NodeKind.JAVA_TYPE && kind == NodeKind.JAVA_METHOD
                            && usedByStep(e.from()) && !stepUsesMethodsOf(e.from()))) {
                        steps.add(e.from(), e, e.kind(), null, false, false);
                    }
                }
                default -> {
                }
            }
        }
        if (tableLike) {
            for (String column : columnsOf(nodeId)) {
                for (Edge e : graph.incoming(column, EdgeKind.READS, EdgeKind.WRITES)) {
                    Fires fires = writesOnly && e.kind() == EdgeKind.READS ? Fires.NO : firesFor.apply(e.from());
                    if (fires != Fires.NO) {
                        steps.addAccess(e.from(), e, e.from(), fires == Fires.UNCERTAIN);
                    }
                }
            }
        }
        if (kind == NodeKind.DB_ROUTINE) {
            for (Edge e : graph.outgoing(nodeId, EdgeKind.TRIGGERS_ON)) {
                steps.add(e.to(), e, EdgeKind.TRIGGERS_ON, null, false, false);
            }
        }
        if ((kind == NodeKind.JAVA_TYPE || kind == NodeKind.JAVA_METHOD) && !entryOnly) {
            for (Edge e : graph.outgoing(nodeId, EdgeKind.DEFINES_BEAN)) {
                if (!graph.incoming(e.to(), EdgeKind.USES).isEmpty()) {
                    steps.add(e.to(), e, EdgeKind.DEFINES_BEAN, null, false, false);
                }
            }
        }
        if (kind == NodeKind.JAVA_METHOD && expandImplementations) {
            // 이 메서드가 (전이적으로) 재정의한 상위 메서드를 부르는 곳은 이 메서드로 디스패치될 수 있다.
            for (String superMethod : overridden(nodeId)) {
                for (Edge e : graph.incoming(superMethod, EdgeKind.CALLS, EdgeKind.ROUTES_TO, EdgeKind.USES, EdgeKind.EXECUTES)) {
                    // super.m()은 상위 구현을 곧바로 부르는 것이라 하위 재정의로 오지 않는다.
                    if (e.kind() == EdgeKind.CALLS && "super".equals(e.attrString("callType"))) {
                        continue;
                    }
                    if (entryOnly && e.kind() != EdgeKind.ROUTES_TO && !(e.kind() == EdgeKind.CALLS && entersByRequest(e))) {
                        continue;
                    }
                    if (narrowByInjection && kindOf(e.from()) == NodeKind.JAVA_METHOD) {
                        // 호출하는 쪽에 주입되는 구현이 이 메서드를 실행하지 않으면 뺀다(좁힐 수 없으면 넣는다).
                        List<String> targets = injectedTargets(ownerType(e.from()), superMethod);
                        if (!targets.isEmpty() && !targets.contains(nodeId)) {
                            continue;
                        }
                    }
                    if (e.kind() == EdgeKind.CALLS) {
                        // 수신자의 타입이 이 메서드를 가진 타입과 무관하면 이 재정의로 디스패치될 수 없다.
                        String receiver = typeId(e.attrString("receiverType"));
                        if (receiver == null && isJavaMethodId(e.from()) && !"static".equals(e.attrString("callType"))
                                && !ownerType(e.from()).equals(ownerType(superMethod))
                                && isSubtype(ownerType(e.from()), ownerType(superMethod))) {
                            // 상위 타입에서 물려받은 메서드를 자기 객체에 대고 부른 것: 수신자는 호출한 쪽의 타입이다.
                            receiver = ownerType(e.from());
                        }
                        if (receiver != null && !mayDispatch(receiver, ownerType(nodeId))) {
                            continue;
                        }
                        addCaller(steps, nodeId, e, need, true);
                    } else {
                        steps.add(e.from(), e, e.kind(), e.kind() == EdgeKind.USES ? e.attrString("role") : relationDetail(e), false, true);
                    }
                }
            }
        }
        return steps.sorted(false);
    }

    /**
     * methodId를 부르는 호출 엣지 하나를 호출자 걸음으로 더한다. need(필요한 수신자 타입)가 있으면 맞는 호출자만 남긴다.
     * <ul>
     * <li>엣지에 {@code receiverType}이 있으면 그 타입으로 판정한다.</li>
     * <li>없으면 호출한 쪽 메서드를 가진 타입으로 판정한다(그 메서드의 객체가 수신자이거나, 수신자를 필드로 가진 객체다).
     *     그 타입이 어느 것도 고르지 못하고 하위 타입이 있으면(제네릭 상위 클래스) need를 호출한 쪽으로 넘겨 그 위에서 다시 판정한다.</li>
     * <li>맞으면 남기고, 다른 팬아웃 대상과 맞으면 빼고, 어느 것과도 맞지 않으면(아래 방향에서 전부 따라가는 경우) {@code [?]}로 남긴다.</li>
     * </ul>
     * 리플렉션으로 추론한 호출은 그 엣지의 {@code receiverType}이 호출한 쪽에 새 need가 된다.
     */
    private void addCaller(Steps steps, String methodId, Edge e, Context need, boolean viaInterface) {
        String callerId = e.from();
        String detail = callDetail(e);
        if (!isJavaMethodId(callerId)) {
            steps.add(callerId, e, EdgeKind.CALLS, detail, false, viaInterface);
            return;
        }
        // 리플렉션 팬아웃 엣지: 이 엣지를 따라가려면 호출한 쪽의 객체가 viaTypes 가운데 하나여야 한다.
        Context up = reflectiveFanOut(e) ? new Context(String.join(",", viaTypes(e)), false, callerId, true) : null;
        String callType = e.attrString("callType");
        // 리플렉션 팬아웃 엣지를 거슬러 간 걸음도 확인 대기다(조건 up이 문맥에 실린다).
        Pick fanOut = up != null ? Pick.SELECTED : Pick.NONE;
        if (need == null) {
            steps.add(callerId, e, EdgeKind.CALLS, detail, viaInterface, up, false, fanOut);
            return;
        }
        if ("static".equals(callType) || "constructor".equals(callType) || "methodRef".equals(callType)) {
            // 수신자를 알 수 없는 호출: 조건을 확인하지 못한 채 남긴다.
            steps.add(callerId, e, EdgeKind.CALLS, detail, viaInterface, up, false, Pick.UNSELECTED);
            return;
        }
        String receiver = typeId(e.attrString("receiverType"));
        String subject = receiver != null ? receiver : ownerType(callerId);
        if (satisfies(subject, need)) {
            // 수신자가 맞지만 다른 대상과도 맞으면(여러 DAO를 참조하는 서비스) 어느 구문인지는 정해지지 않는다.
            steps.add(callerId, e, EdgeKind.CALLS, detail, viaInterface, up, false,
                    selectsSeveralFanOuts(subject, need.origin()) ? Pick.UNSELECTED : fanOut);
        } else if (selectsAnyFanOut(subject, need.origin())) {
            // 이 호출은 다른 namespace의 구문(다른 대상의 리플렉션 호출)을 실행한다.
            return;
        } else if (receiver == null && up == null && hasSubtypes(subject)) {
            // 제네릭 상위 클래스: 실제 객체는 그 하위 타입 가운데 하나다. 구체 하위 타입이 모두 다른 팬아웃 대상을 고르면
            // 이 호출로는 이 구문(대상)에 오지 않는다. 맞는 하위 타입이 있거나 알 수 없으면 조건을 넘겨 그 위에서 다시 판정한다.
            if (!noSubtypeCanSatisfy(subject, need)) {
                steps.add(callerId, e, EdgeKind.CALLS, detail, viaInterface, need, false);
            }
        } else {
            boolean thisCall = receiver == null && thisCall(callerId, methodId, callType);
            // 수신자 타입 표시 없이 다른 객체를 부른 호출자는 [?] 없이 남기지만, 조건을 확인한 것은 아니다.
            steps.add(callerId, e, EdgeKind.CALLS, detail, viaInterface, up, receiver != null || thisCall,
                    receiver != null || thisCall ? fanOut : Pick.UNSELECTED);
        }
    }

    /** 호출 엣지가 요청으로 진입시키는 것인지(다른 핸들러의 redirect·forward). */
    private static boolean entersByRequest(Edge calls) {
        String callType = calls.attrString("callType");
        return CALL_REDIRECT.equals(callType) || CALL_FORWARD.equals(callType);
    }

    /**
     * type의 구체 하위 타입(소스에 정의가 있는 추상 아닌 클래스)이 하나 이상 있고, 그 가운데 need를 채우는 것이 없으며,
     * 전부 origin 메서드의 다른 팬아웃 대상을 고르는지. 하위 타입 가운데 어느 대상도 고르지 못하는 것이 있으면(알 수 없음) false다.
     */
    private boolean noSubtypeCanSatisfy(String type, Context need) {
        boolean any = false;
        for (String sub : subtypes(type)) {
            if (!isConcreteType(sub)) {
                continue;
            }
            if (satisfies(sub, need) || !selectsAnyFanOut(sub, need.origin())) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /** methodId가 재정의한 상위 메서드 전부(OVERRIDES를 끝까지 따라간다). */
    private List<String> overridden(String methodId) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(methodId);
        List<String> frontier = List.of(methodId);
        for (int depth = 0; depth < MAX_HIERARCHY_DEPTH && !frontier.isEmpty(); depth++) {
            List<String> next = new ArrayList<>();
            for (String id : frontier) {
                for (Edge e : graph.outgoing(id, EdgeKind.OVERRIDES)) {
                    if (seen.add(e.to())) {
                        result.add(e.to());
                        next.add(e.to());
                    }
                }
            }
            frontier = next;
        }
        return result;
    }

    /**
     * 스텝의 USES 대상(타입 또는 빈)이 같은 스텝이 직접 USES하는 메서드로 이미 드러나는지.
     * 링커가 USES(STEP→METHOD)를 더했으면 타입·빈을 거쳐 public 메서드를 전부 펼칠 필요가 없다.
     */
    private boolean coveredByUsedMethod(String stepId, String targetId) {
        NodeKind target = kindOf(targetId);
        if (target != NodeKind.JAVA_TYPE && target != NodeKind.SPRING_BEAN) {
            return false;
        }
        Set<String> types = new HashSet<>();
        types.add(targetId);
        if (target == NodeKind.SPRING_BEAN) {
            types.clear();
            for (Edge e : graph.incoming(targetId, EdgeKind.DEFINES_BEAN)) {
                types.add(e.from());
            }
        }
        for (Edge e : graph.outgoing(stepId, EdgeKind.USES)) {
            if (kindOf(e.to()) == NodeKind.JAVA_METHOD && (types.contains(e.to()) || types.contains(ownerType(e.to())))) {
                return true;
            }
        }
        return false;
    }

    /** 어떤 스텝이 이 타입의 메서드를 직접 USES하는지. */
    private boolean stepUsesMethodsOf(String typeId) {
        for (Edge contains : graph.outgoing(typeId, EdgeKind.CONTAINS)) {
            if (kindOf(contains.to()) == NodeKind.JAVA_METHOD && !graph.incoming(contains.to(), EdgeKind.USES).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 배치 스텝이 이 타입(또는 이 타입이 정의한 빈)을 구성 요소로 쓰는지. */
    private boolean usedByStep(String typeId) {
        if (!graph.incoming(typeId, EdgeKind.USES).isEmpty()) {
            return true;
        }
        for (Edge e : graph.outgoing(typeId, EdgeKind.DEFINES_BEAN)) {
            if (!graph.incoming(e.to(), EdgeKind.USES).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 수신자 문맥

    private static boolean isJavaMethodId(String id) {
        return id.startsWith("java:method:");
    }

    /** 바이너리 이름 또는 타입 ID를 타입 ID로. 비어 있으면 null. */
    private static String typeId(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) {
            return null;
        }
        return nameOrId.startsWith("java:type:") ? nameOrId : Ids.javaType(nameOrId.strip());
    }

    /**
     * 메서드가 물려준 구문으로 가는 EXECUTES인지: 구문의 namespace가 그 메서드를 가진 타입의 하위 타입이거나,
     * 엣지에 {@code inherited=true}(또는 링커의 {@code declaredIn})가 있다. 그 메서드를 가진 타입 자신의 구문이면 아니다.
     */
    private boolean inheritedStatement(String methodId, Edge executes) {
        String namespace = namespaceOf(executes);
        if (namespace == null) {
            return false;
        }
        String owner = ownerType(methodId);
        if (namespace.equals(owner)) {
            return false;
        }
        return executes.attrBoolean("inherited") || executes.attrString("declaredIn") != null || isSubtype(namespace, owner);
    }

    /**
     * 리플렉션으로 추론한 호출 가운데 팬아웃인지: 제네릭 상위 메서드 하나에서 타입 인자를 고정한 하위 타입마다 다른 대상으로
     * 나가는 엣지({@code callType=reflection}, {@code viaTypes}=그 대상으로 타입 인자를 고정한 하위 타입들).
     * 대상 타입이 정해진 리플렉션 호출({@code viaTypes} 없음)은 보통의 추론 호출이다.
     */
    private static boolean reflectiveFanOut(Edge calls) {
        return calls.kind() == EdgeKind.CALLS && CALL_REFLECTION.equals(calls.attrString("callType")) && !calls.attrList("viaTypes").isEmpty();
    }

    private static List<String> viaTypes(Edge calls) {
        List<String> types = new ArrayList<>();
        for (String name : calls.attrList("viaTypes")) {
            String id = typeId(name);
            if (id != null) {
                types.add(id);
            }
        }
        return types;
    }

    /**
     * EXECUTES가 가리키는 구문의 namespace 타입 ID. 엣지의 {@code namespace}, 없으면 구문 노드의 {@code namespace},
     * 그것도 없으면 매퍼 구문 ID({@code mybatis:stmt:<namespace>.<id>})에서 읽는다.
     */
    private String namespaceOf(Edge executes) {
        String namespace = executes.attrString("namespace");
        if (namespace == null) {
            namespace = graph.node(executes.to()).map(n -> n.attrString("namespace")).orElse(null);
        }
        if (namespace == null && executes.to().startsWith("mybatis:stmt:")) {
            String rest = Ids.remainder(executes.to());
            int dot = rest.lastIndexOf('.');
            namespace = dot > 0 ? rest.substring(0, dot) : null;
        }
        return typeId(namespace);
    }

    /**
     * 문맥 타입 contextType이 팬아웃 엣지를 고르는지. 상속 구문(EXECUTES)은 그 namespace와 {@link #matches}로,
     * 리플렉션 호출은 {@code viaTypes} 가운데 문맥 타입과 상·하위 관계인 것이 있는지로 본다
     * (수신자의 정적 타입이 인터페이스면 그 구현 타입이, 하위 클래스면 그 상위 타입이 viaTypes에 있다).
     */
    private boolean selects(String contextType, Edge fanOut) {
        if (fanOut.kind() == EdgeKind.EXECUTES) {
            return matches(contextType, namespaceOf(fanOut));
        }
        for (String via : viaTypes(fanOut)) {
            if (related(contextType, via)) {
                return true;
            }
        }
        return false;
    }

    private boolean related(String a, String b) {
        return isSubtype(a, b) || isSubtype(b, a);
    }

    /** methodId에서 팬아웃 엣지(물려준 구문으로 가는 EXECUTES, 리플렉션 호출)가 나가는지. */
    private boolean hasFanOut(String methodId) {
        Boolean cached = fanOutCache.get(methodId);
        if (cached != null) {
            return cached;
        }
        boolean found = false;
        for (Edge e : graph.outgoing(methodId, EdgeKind.EXECUTES, EdgeKind.CALLS)) {
            if (e.kind() == EdgeKind.EXECUTES ? inheritedStatement(methodId, e) : reflectiveFanOut(e)) {
                found = true;
                break;
            }
        }
        fanOutCache.put(methodId, found);
        return found;
    }

    /**
     * 수신자 문맥에 따라 전개 결과가 달라지는 메서드인지: 팬아웃 엣지가 나가거나, 문맥을 이어받는 호출(같은 객체의 메서드 호출,
     * 다른 객체의 팬아웃 메서드 호출, 구현 전개)로 그런 메서드에 닿는다. 문맥은 이런 메서드로 갈 때만 싣는다
     * (상관없는 메서드까지 문맥별로 따로 펼치지 않게).
     */
    private boolean receiverSensitive(String methodId) {
        return receiverSensitive(methodId, new HashSet<>(), 0) == Boolean.TRUE;
    }

    /** @return 확정된 결과. 순환이나 깊이 제한 때문에 "아니다"를 확정하지 못했으면 null */
    private Boolean receiverSensitive(String methodId, Set<String> visiting, int depth) {
        Boolean cached = sensitiveCache.get(methodId);
        if (cached != null) {
            return cached;
        }
        if (hasFanOut(methodId)) {
            sensitiveCache.put(methodId, true);
            return true;
        }
        if (depth > 60 || !visiting.add(methodId)) {
            return null;
        }
        boolean incomplete = false;
        boolean found = false;
        List<String> next = new ArrayList<>();
        for (Edge e : graph.outgoing(methodId, EdgeKind.CALLS)) {
            String callType = e.attrString("callType");
            if (!isJavaMethodId(e.to()) || e.attrString("receiverType") != null || "static".equals(callType)
                    || "constructor".equals(callType) || "methodRef".equals(callType)) {
                continue;
            }
            if (thisCall(methodId, e.to(), callType)) {
                next.add(e.to());
            } else if (hasFanOut(e.to())) {
                found = true;
                break;
            }
        }
        if (!found && expandImplementations) {
            next.addAll(overriders(methodId).keySet());
        }
        for (int i = 0; !found && i < next.size(); i++) {
            Boolean below = receiverSensitive(next.get(i), visiting, depth + 1);
            found = below == Boolean.TRUE;
            incomplete |= below == null;
        }
        visiting.remove(methodId);
        if (found || !incomplete) {
            sensitiveCache.put(methodId, found);
            return found;
        }
        return null;
    }

    /**
     * callerId의 메서드가 targetId를 자기 객체에 대고 부르는지: {@code super} 호출, 같은 타입 안의 호출,
     * 상위 타입에서 물려받은 메서드를 수신자 타입 표시 없이 부르는 호출(수신자가 다른 타입의 객체면 java가 {@code receiverType}을 남긴다).
     */
    private boolean thisCall(String callerId, String targetId, String callType) {
        if ("super".equals(callType)) {
            return true;
        }
        String callerOwner = ownerType(callerId);
        String targetOwner = ownerType(targetId);
        return callerOwner.equals(targetOwner) || isSubtype(callerOwner, targetOwner);
    }

    /** callerId(문맥 callerContext)에서 호출 엣지 e로 간 메서드의 문맥. 문맥과 무관한 메서드로 가면 null. */
    private Context calleeContext(String callerId, Context callerContext, Edge e) {
        String target = e.to();
        if (!isJavaMethodId(target)) {
            return null;
        }
        String receiver = typeId(e.attrString("receiverType"));
        if (receiver != null) {
            return receiverSensitive(target) ? new Context(receiver, false, null) : null;
        }
        String callType = e.attrString("callType");
        if ("static".equals(callType) || "constructor".equals(callType) || "methodRef".equals(callType)) {
            return null;
        }
        String thisType = callerContext != null && !callerContext.enclosing() ? callerContext.type() : null;
        if (thisCall(callerId, target, callType)) {
            String callerOwner = ownerType(callerId);
            String type = thisType != null ? thisType : callerOwner.equals(ownerType(target)) ? null : callerOwner;
            return type != null && receiverSensitive(target) ? new Context(type, false, null) : null;
        }
        // 다른 객체의 메서드: 그 객체가 타입 매개변수로 선언된 필드일 수 있으므로 감싼 객체의 타입을 한 걸음만 넘긴다.
        return thisType != null && hasFanOut(target) ? new Context(thisType, true, null) : null;
    }

    /**
     * 구현으로 펼친 메서드의 문맥. 수신자 타입 R로 부른 인터페이스 메서드의 구현이면: 구현을 가진 타입이 R의 하위면 그 타입,
     * R이 그 타입의 하위면 R, 둘 다 아니면 R의 하위이면서 그 타입의 하위인 타입이 하나일 때 그것이다.
     */
    private Context implementationContext(Context context, String implId) {
        if (context == null || context.enclosing() || !receiverSensitive(implId)) {
            return null;
        }
        String owner = ownerType(implId);
        if (isSubtype(owner, context.type())) {
            return new Context(owner, false, null);
        }
        if (isSubtype(context.type(), owner)) {
            return context;
        }
        List<String> both = new ArrayList<>();
        for (String sub : subtypes(context.type())) {
            if (isSubtype(sub, owner)) {
                both.add(sub);
            }
        }
        return both.size() == 1 ? new Context(both.get(0), false, null) : context;
    }

    /** typeId의 (전이적) 상위 타입. 자신은 넣지 않는다. */
    private Set<String> supertypes(String typeId) {
        Set<String> cached = supertypesCache.get(typeId);
        if (cached != null) {
            return cached;
        }
        Set<String> result = new java.util.LinkedHashSet<>();
        List<String> frontier = List.of(typeId);
        for (int depth = 0; depth < MAX_HIERARCHY_DEPTH && !frontier.isEmpty(); depth++) {
            List<String> next = new ArrayList<>();
            for (String id : frontier) {
                for (Edge e : graph.outgoing(id, EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS)) {
                    if (!e.to().equals(typeId) && result.add(e.to())) {
                        next.add(e.to());
                    }
                }
            }
            next.sort(Comparator.naturalOrder());
            frontier = next;
        }
        supertypesCache.put(typeId, result);
        return result;
    }

    /**
     * 정적 타입이 receiverType인 수신자에 대고 부른 호출이 implOwner에 선언된 재정의로 디스패치될 수 있는지:
     * 두 타입이 상·하위 관계이거나, implOwner의 하위 타입 가운데 receiverType의 하위 타입이 있다.
     */
    private boolean mayDispatch(String receiverType, String implOwner) {
        if (isSubtype(implOwner, receiverType) || isSubtype(receiverType, implOwner)) {
            return true;
        }
        for (String sub : subtypes(implOwner)) {
            if (isSubtype(sub, receiverType)) {
                return true;
            }
        }
        return false;
    }

    /** sub가 sup과 같거나 그 (전이적) 하위 타입인지. */
    private boolean isSubtype(String sub, String sup) {
        return sub != null && sup != null && (sub.equals(sup) || supertypes(sub).contains(sup));
    }

    private boolean hasSubtypes(String typeId) {
        return !graph.incoming(typeId, EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS).isEmpty();
    }

    /** typeId의 (전이적) 하위 타입(ID 순). */
    private List<String> subtypes(String typeId) {
        Set<String> result = new TreeSet<>();
        List<String> frontier = List.of(typeId);
        for (int depth = 0; depth < MAX_HIERARCHY_DEPTH && !frontier.isEmpty(); depth++) {
            List<String> next = new ArrayList<>();
            for (String id : frontier) {
                for (Edge e : graph.incoming(id, EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS)) {
                    if (!e.from().equals(typeId) && result.add(e.from())) {
                        next.add(e.from());
                    }
                }
            }
            frontier = next;
        }
        return new ArrayList<>(result);
    }

    /**
     * typeId(와 그 상위 타입)가 묶는 타입. 상위 타입으로 가는 EXTENDS/IMPLEMENTS 엣지에 {@code typeArguments}(타입 인자의 바이너리 이름
     * 또는 타입 ID 목록)가 하나라도 있으면 그것만, 없으면 DEPENDS_ON·ASSOCIATES·INJECTS로 참조하는 타입 전부다.
     * 뒤쪽은 타입 인자가 아닌 타입(주입받는 다른 DAO)도 섞이므로 넓게 잡힌다.
     */
    private Set<String> boundTypes(String typeId) {
        Set<String> cached = boundTypesCache.get(typeId);
        if (cached != null) {
            return cached;
        }
        List<String> chain = new ArrayList<>();
        chain.add(typeId);
        chain.addAll(supertypes(typeId));
        Set<String> arguments = new TreeSet<>();
        Set<String> referenced = new TreeSet<>();
        for (String type : chain) {
            for (Edge e : graph.outgoing(type, EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS, EdgeKind.DEPENDS_ON, EdgeKind.ASSOCIATES,
                    EdgeKind.INJECTS)) {
                if (e.kind() == EdgeKind.EXTENDS || e.kind() == EdgeKind.IMPLEMENTS) {
                    for (String argument : e.attrList("typeArguments")) {
                        String id = typeId(argument);
                        if (id != null) {
                            arguments.add(id);
                        }
                    }
                } else {
                    referenced.add(e.to());
                }
            }
        }
        Set<String> result = arguments.isEmpty() ? referenced : arguments;
        boundTypesCache.put(typeId, result);
        return result;
    }

    /**
     * 문맥 타입 contextType이 팬아웃 엣지의 타입 target을 고르는지: 두 타입이 상·하위 관계이거나,
     * contextType이 묶는 타입 가운데 target(또는 그 하위 타입)이 있다.
     */
    private boolean matches(String contextType, String target) {
        if (contextType == null || target == null) {
            return false;
        }
        if (related(contextType, target)) {
            return true;
        }
        for (String bound : boundTypes(contextType)) {
            if (isSubtype(bound, target)) {
                return true;
            }
        }
        return false;
    }

    /** type이 need(위 방향의 조건)를 채우는지. */
    private boolean satisfies(String type, Context need) {
        for (String required : need.type().split(",")) {
            if (need.exact() ? related(type, required) : matches(type, required)) {
                return true;
            }
        }
        return false;
    }

    /** type이 originMethodId에서 나가는 팬아웃 엣지 가운데 하나라도 고르는지. */
    private boolean selectsAnyFanOut(String type, String originMethodId) {
        if (originMethodId == null) {
            return false;
        }
        for (Edge e : graph.outgoing(originMethodId, EdgeKind.EXECUTES, EdgeKind.CALLS)) {
            boolean fanOut = e.kind() == EdgeKind.EXECUTES ? inheritedStatement(originMethodId, e) : reflectiveFanOut(e);
            if (fanOut && selects(type, e)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 표시 옵션에 쓰는 판정

    /** 데이터 적재 구문인지: 스크립트의 리터럴 INSERT를 파일·테이블 단위로 묶은 구문 노드({@code attrs.dataLoad=true}). */
    public boolean isDataLoad(String nodeId) {
        return graph.node(nodeId).map(n -> n.kind() == NodeKind.SQL_STATEMENT && n.attrBoolean("dataLoad")).orElse(false);
    }

    /**
     * 콜트리에서 접어도 되는 접근자 메서드인지. 이름만 보지 않는다({@code isAuthenticated()}, {@code getNextStringId()},
     * {@code setComplete()}, static {@code setDataSource(String)}은 접근자가 아니다).
     * <ul>
     * <li>Lombok이 만들 멤버({@code synthetic=true}이고 {@code lombok}이 있는 메서드. 생성자 제외)이거나,</li>
     * <li>이름이 {@code get}/{@code is}/{@code set} + 대문자로 시작하고, 같은 타입에 대응하는 필드({@code JAVA_FIELD} 노드:
     *     {@code getFoo}/{@code isFoo}/{@code setFoo} ↔ {@code foo}. {@code getURL} ↔ {@code URL}, {@code isFoo} ↔ {@code isFoo}도 본다)가 있고,
     *     매개변수가 getter는 0개·setter는 1개이며 {@code static}이 아니다.</li>
     * </ul>
     * 어느 쪽이든 그 메서드와 그것을 재정의한 메서드 어디에서도 CALLS·EXECUTES가 나가지 않아야 한다.
     * 소스 없는 타입의 메서드는 접근자로 보지 않는다(external 접기 규칙만 따른다).
     */
    public boolean isAccessor(String methodId) {
        Boolean cached = accessorCache.get(methodId);
        if (cached != null) {
            return cached;
        }
        boolean accessor = false;
        Node method = graph.node(methodId).orElse(null);
        if (method != null && method.kind() == NodeKind.JAVA_METHOD && !isExternal(methodId)
                && (isBuilderCall(methodId) || pureConstructor(methodId, new HashSet<>()))) {
            // 생성 코드의 빌더 호출과, 다른 메서드를 부르지 않는 생성자 사슬은 흐름을 읽는 데 도움이 되지 않는다.
            accessor = true;
        } else if (method != null && method.kind() == NodeKind.JAVA_METHOD && !method.attrBoolean("constructor") && !isExternal(methodId)) {
            boolean lombok = method.attrBoolean("synthetic") && method.attr("lombok") != null;
            if (lombok || fieldAccessor(method)) {
                accessor = graph.outgoing(methodId, EdgeKind.CALLS, EdgeKind.EXECUTES).isEmpty();
                for (String overrider : overriders(methodId).keySet()) {
                    accessor &= graph.outgoing(overrider, EdgeKind.CALLS, EdgeKind.EXECUTES).isEmpty();
                }
            }
        }
        accessorCache.put(methodId, accessor);
        return accessor;
    }

    /**
     * MyBatis Generator가 만든 조건 빌더의 메서드인지: 메서드를 가진 타입의 단순 이름이 {@code *Example}이거나 그 중첩 타입
     * ({@code *Example$Criteria}, {@code $GeneratedCriteria}, {@code $Criterion})이다. {@code createCriteria}, {@code andXxxEqualTo},
     * {@code setOrderByClause} 같은 호출이 트리를 뒤덮지 않게 접근자와 함께 접는다(프로젝트 타입이지만 생성 코드다).
     */
    public boolean isBuilderCall(String methodId) {
        if (!isJavaMethodId(methodId)) {
            return false;
        }
        String rest = Ids.remainder(methodId);
        int hash = rest.indexOf('#');
        String type = hash < 0 ? rest : rest.substring(0, hash);
        String simple = type.substring(type.lastIndexOf('.') + 1);
        int dollar = simple.indexOf('$');
        String outer = dollar < 0 ? simple : simple.substring(0, dollar);
        return outer.length() > "Example".length() && outer.endsWith("Example");
    }

    /**
     * 부수 효과 없는 생성자인지: 소스에 정의가 있는 생성자이고, 본문에서 나가는 호출이 (역시 부수 효과 없는, 또는 소스 없는) 생성자뿐이며
     * 구문을 실행하지 않는다. {@code new A()} → {@code A#<init>} → {@code super(…)}로만 이어지는 사슬이 여기에 든다.
     */
    private boolean pureConstructor(String methodId, Set<String> visiting) {
        Node method = graph.node(methodId).orElse(null);
        if (method == null || method.kind() != NodeKind.JAVA_METHOD || !isConstructor(method)) {
            return false;
        }
        if (isExternal(methodId) || !visiting.add(methodId)) {
            return true;
        }
        if (!graph.outgoing(methodId, EdgeKind.EXECUTES).isEmpty()) {
            return false;
        }
        for (Edge e : graph.outgoing(methodId, EdgeKind.CALLS)) {
            if (!pureConstructor(e.to(), visiting)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isConstructor(Node method) {
        return method.attrBoolean("constructor") || method.id().contains("#" + Ids.CONSTRUCTOR_NAME + "(");
    }

    /**
     * 이 JSP를 포함하는 화면 수(INCLUDES로 이 JSP를 가리키는 서로 다른 JSP. 레이아웃 관계 포함). 위 방향에서 공통 JSP(헤더·메뉴·레이아웃)를
     * 한 줄로 접을지 정할 때 쓴다.
     */
    public int includingPages(String jspId) {
        Set<String> pages = new HashSet<>();
        for (Edge e : graph.incoming(jspId, EdgeKind.INCLUDES)) {
            if (kindOf(e.from()) == NodeKind.JSP_PAGE && !e.from().equals(jspId)) {
                pages.add(e.from());
            }
        }
        return pages.size();
    }

    /** 이름·매개변수 수·수정자가 접근자 꼴이고 같은 타입에 대응하는 필드가 있는지. */
    private boolean fieldAccessor(Node method) {
        String name = method.name();
        if (name == null || method.attrList("modifiers").contains("static")) {
            return false;
        }
        String prefix = name.startsWith("get") ? "get" : name.startsWith("set") ? "set" : name.startsWith("is") ? "is" : null;
        if (prefix == null || name.length() <= prefix.length() || !Character.isUpperCase(name.charAt(prefix.length()))) {
            return false;
        }
        if (parameterCount(method.id()) != ("set".equals(prefix) ? 1 : 0)) {
            return false;
        }
        String property = name.substring(prefix.length());
        String owner = Ids.remainder(ownerType(method.id()));
        List<String> candidates = new ArrayList<>();
        candidates.add(Character.toLowerCase(property.charAt(0)) + property.substring(1));
        candidates.add(property);
        if ("is".equals(prefix)) {
            candidates.add(name);
        }
        for (String field : candidates) {
            if (graph.node(Ids.javaField(owner, field)).map(n -> n.kind() == NodeKind.JAVA_FIELD).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    /** 메서드 ID({@code …#name(a,b<c,d>)})의 매개변수 수. 시그니처를 모르면({@code (?)}, 괄호 없음) -1. */
    private static int parameterCount(String methodId) {
        int open = methodId.indexOf('(', methodId.indexOf('#') + 1);
        int close = methodId.lastIndexOf(')');
        if (open < 0 || close < open) {
            return -1;
        }
        String params = methodId.substring(open + 1, close).strip();
        if (params.isEmpty()) {
            return 0;
        }
        if (params.equals("?")) {
            return -1;
        }
        int count = 1;
        int depth = 0;
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                count++;
            }
        }
        return count;
    }

    /** 테이블(뷰)의 컬럼 노드 ID. CONTAINS가 없는 참조 컬럼도 ID로 찾아 넣는다. */
    public List<String> columnsOf(String tableId) {
        if (columnsByTable == null) {
            Map<String, List<String>> map = new HashMap<>();
            List<Node> columns = new ArrayList<>(graph.nodes(NodeKind.DB_COLUMN));
            columns.sort(Comparator.comparing(Node::id));
            for (Node column : columns) {
                DbGraph.tableOfColumn(graph, column.id()).ifPresent(t -> map.computeIfAbsent(t, k -> new ArrayList<>()).add(column.id()));
            }
            columnsByTable = map;
        }
        return columnsByTable.getOrDefault(tableId, List.of());
    }

    /**
     * 전개 결과가 문맥(직전 노드, 따라온 엣지)에 따라 달라지는 노드인지. 반복 억제의 키를 만들 때 쓴다.
     */
    public boolean contextSensitive(String nodeId) {
        NodeKind kind = kindOf(nodeId);
        if (kind == NodeKind.DB_TABLE || kind == NodeKind.DB_VIEW) {
            return !graph.incoming(nodeId, EdgeKind.TRIGGERS_ON).isEmpty();
        }
        // 핸들러(뷰 컨트롤러의 화면)가 여럿인 엔드포인트: 어느 요청·어느 핸들러에서 왔느냐에 따라 따라가는 쪽이 다르다.
        if (kind == NodeKind.HTTP_ENDPOINT) {
            return severalHandlers(nodeId);
        }
        // 호출하는 쪽의 주입 정보에 따라 결과가 달라지는 디스패치 대상: 구현이 둘 이상이거나, 본문이 있는 메서드에 재정의가 하나 있을 때.
        if (kind != NodeKind.JAVA_METHOD || !narrowByInjection || !expandImplementations) {
            return false;
        }
        int implementations = overriders(nodeId).size();
        return (implementations > 1 || (implementations == 1 && !isAbstract(nodeId))) && isDispatchTarget(nodeId);
    }

    public NodeKind kindOf(String id) {
        return graph.node(id).map(Node::kind).orElseGet(() -> Ids.kindOf(id).orElse(NodeKind.UNRESOLVED));
    }

    /** external 메서드인지: 노드나 소유 타입이 external이거나, 소스에 정의가 없는 Java 메서드. */
    public boolean isExternal(String nodeId) {
        Node node = graph.node(nodeId).orElse(null);
        if (node == null) {
            return false;
        }
        if (node.attrBoolean("external")) {
            return true;
        }
        if (node.kind() != NodeKind.JAVA_METHOD) {
            return false;
        }
        return !node.declared() || graph.node(ownerType(nodeId)).map(t -> t.attrBoolean("external")).orElse(false);
    }
}
