package io.codeatlas.export.calltree;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.NodeKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** 콜트리의 한 줄. 만든 뒤에는 바꾸지 않는다(자식 목록과 표식은 빌더만 채운다). */
public final class CallTreeNode {

    /** 줄에 붙는 표식. */
    public enum Marker {
        /** 인터페이스·추상 메서드의 구현으로 펼친 줄. */
        IMPL("impl", true),
        /** 재정의한 상위 메서드를 통해 호출하는 줄(callers). */
        VIA_INTERFACE("via interface", true),
        /** 트리거가 이 쓰기에 실제로 일어나는지 정적으로 알 수 없는 줄. */
        TRIGGER_UNCERTAIN("trigger?", true),
        /**
         * 수신자 타입과 맞는 구문(상속 메서드가 하위 매퍼마다 하나씩 실행하는 구문)이 없어서 전부 따라간 줄(callees),
         * 수신자 타입이 어느 구문과도 맞지 않는 호출자(callers).
         */
        RECEIVER_UNCERTAIN("?", true),
        /** 리플렉션으로 추론한 호출({@code callType=reflection}). */
        REFLECT("reflect", true),
        /** 핸들러보다 먼저 실행되는 {@code @ModelAttribute} 메서드({@code callType=modelAttribute}). callers에서는 그 핸들러 줄에 붙는다. */
        BEFORE("before", true),
        /**
         * 뷰 이름 {@code redirect:}나 {@code sendRedirect}로 넘어가는 다른 엔드포인트의 핸들러({@code callType=redirect}). 별개의 요청이라
         * 기본으로는 그 아래를 펼치지 않는다.
         */
        REDIRECT("redirect", true),
        /** {@code forward:}·{@code RequestDispatcher.forward/include}로 넘어가는 핸들러({@code callType=forward}). 기본으로는 그 아래를 펼치지 않는다. */
        FORWARD("forward", true),
        /** 레이아웃(sitemesh 데코레이터, tiles template과 그 조각). 기본으로는 그 아래를 펼치지 않는다. */
        LAYOUT("layout", true),
        /**
         * 여러 화면에 들어가는 공통 JSP(5개 이상의 페이지가 include하거나 레이아웃인 것). 위 방향에서 그 위(포함하는 화면 전부)를 펼치지 않는다.
         * 세부 표시에 {@code 포함하는 화면 n개}가 붙는다.
         */
        SHARED("shared", true),
        /** 경로상 조상과 같은 노드. 펼치지 않는다. */
        CYCLE("cycle", false),
        /** 앞에서 이미 펼친 노드. */
        SEE_ABOVE("see above", false),
        /** 깊이 제한으로 펼치지 않은 노드. */
        DEPTH_LIMIT("depth limit", false);

        private final String text;
        private final boolean leading;

        Marker(String text, boolean leading) {
            this.text = text;
            this.leading = leading;
        }

        /** 대괄호 없는 문구. 예: {@code via interface}. */
        public String text() {
            return text;
        }

        /** 이름 앞에 붙는 표식이면 true, 줄 끝에 붙으면 false. */
        public boolean leading() {
            return leading;
        }
    }

    private final String id;
    private final NodeKind kind;
    private final String label;
    private final String kindTag;
    private final String location;
    private final EdgeKind via;
    private final String callSite;
    private final String detail;
    private final Confidence confidence;
    private final Set<Marker> markers = EnumSet.noneOf(Marker.class);
    private final List<CallTreeNode> children = new ArrayList<>();

    CallTreeNode(String id, NodeKind kind, String label, String kindTag, String location, EdgeKind via, String callSite,
            String detail, Confidence confidence) {
        this.id = id;
        this.kind = kind;
        this.label = label;
        this.kindTag = kindTag;
        this.location = location;
        this.via = via;
        this.callSite = callSite;
        this.detail = detail;
        this.confidence = confidence;
    }

    /** 그래프 노드 ID. */
    public String id() {
        return id;
    }

    public NodeKind kind() {
        return kind;
    }

    /** 짧은 표시 이름. 예: {@code OrderService#find(Long)}. */
    public String label() {
        return label;
    }

    /** 종류 표시. Java 메서드는 빈 문자열. 예: {@code sql}, {@code table}. */
    public String kindTag() {
        return kindTag;
    }

    /** 노드 정의 위치 {@code file:line}. 없으면 호출 지점, 그것도 없으면 null. */
    public String location() {
        return location != null ? location : callSite;
    }

    /** 부모에서 이 노드로 온 엣지 종류. 시작 노드는 null. */
    public EdgeKind via() {
        return via;
    }

    /** 엣지의 근거 위치(호출 지점) {@code file:line}. 없으면 null. */
    public String callSite() {
        return callSite;
    }

    /** 테이블 접근 종류({@code R}, {@code CU} 등), 스텝 구성 요소의 role, super(호출 방식), {@code 모듈 <경로>}. 없으면 null. */
    public String detail() {
        return detail;
    }

    /** 부모와 이 노드를 잇는 엣지의 신뢰도. */
    public Confidence confidence() {
        return confidence;
    }

    public Set<Marker> markers() {
        return Collections.unmodifiableSet(markers);
    }

    public List<CallTreeNode> children() {
        return Collections.unmodifiableList(children);
    }

    void mark(Marker marker) {
        markers.add(marker);
    }

    void add(CallTreeNode child) {
        children.add(child);
    }
}
