package io.codeatlas.export.calltree;

/**
 * 콜트리 옵션. 불변이다.
 *
 * <pre>{@code
 * CallTreeOptions.builder().direction(Direction.CALLERS).maxDepth(5).hideExternal(true).build();
 * }</pre>
 */
public final class CallTreeOptions {

    /** 트리의 방향. */
    public enum Direction {
        /** 아래로: 시작 노드가 호출·실행·요청하는 쪽. */
        CALLEES,
        /** 위로: 시작 노드를 호출·실행·요청하는 쪽. */
        CALLERS
    }

    private final Direction direction;
    private final int maxDepth;
    private final int maxNodes;
    private final boolean expandImplementations;
    private final boolean narrowByInjection;
    private final boolean collapseRepeated;
    private final boolean hideExternal;
    private final boolean expandRenderedViews;
    private final boolean hideAccessors;
    private final boolean includeDataLoad;
    private final boolean expandLayouts;

    private CallTreeOptions(Builder b) {
        this.direction = b.direction;
        this.maxDepth = Math.max(0, b.maxDepth);
        this.maxNodes = Math.max(1, b.maxNodes);
        this.expandImplementations = b.expandImplementations;
        this.narrowByInjection = b.narrowByInjection;
        this.collapseRepeated = b.collapseRepeated;
        this.hideExternal = b.hideExternal;
        this.expandRenderedViews = b.expandRenderedViews;
        this.hideAccessors = b.hideAccessors;
        this.includeDataLoad = b.includeDataLoad;
        this.expandLayouts = b.expandLayouts;
    }

    /**
     * 아래 방향, 깊이 10, 노드 2000개, 구현 전개·주입 좁히기·반복 억제 켬, external·접근자 표시,
     * 컨트롤러가 그리는 JSP 아래는 펼치지 않음, 데이터 적재 구문 제외.
     */
    public static CallTreeOptions callees() {
        return builder().build();
    }

    /** 위 방향. 나머지는 {@link #callees()}와 같다. */
    public static CallTreeOptions callers() {
        return builder().direction(Direction.CALLERS).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Direction direction() {
        return direction;
    }

    /** 시작 노드에서 따라갈 최대 걸음 수. */
    public int maxDepth() {
        return maxDepth;
    }

    /** 트리의 노드 수 상한(시작 노드 포함). */
    public int maxNodes() {
        return maxNodes;
    }

    /**
     * 인터페이스·추상 메서드를 구현으로 펼치고(callees. OVERRIDES를 전이적으로 따라 간접 구현까지),
     * (전이적으로) 재정의한 상위 메서드의 호출자를 포함한다(callers).
     */
    public boolean expandImplementations() {
        return expandImplementations;
    }

    /** INJECTS 정보로 구현을 좁힌다: 주입되는 후보 타입에 선언된 재정의, 없으면 가장 가까운 상위 클래스의 구현만 남긴다. */
    public boolean narrowByInjection() {
        return narrowByInjection;
    }

    /** 이미 펼친 노드를 다시 만나면 {@code [see above]}로 줄인다. */
    public boolean collapseRepeated() {
        return collapseRepeated;
    }

    public boolean hideExternal() {
        return hideExternal;
    }

    /**
     * 화면 이동 너머를 펼칠지. 기본 false: callees에서 메서드가 RENDERS로 가리키는 JSP(그 화면의 include, 스크립트, 요청)와,
     * redirect·forward로 넘어가는 핸들러({@code callType=redirect|forward}. callers에서는 넘어오는 쪽 핸들러)는 한 줄만 남기고 펼치지 않는다.
     * true면 화면 → 요청 → 다음 엔드포인트로 계속 내려가므로, 공통 헤더의 메뉴 링크나 로그인 화면으로 가는 redirect가 있는 프로젝트에서는
     * 다른 엔드포인트의 트리를 끌고 와 노드 수 상한에서 잘린다. JSP나 넘어간 쪽 핸들러에서 시작한 트리는 이 옵션과 무관하게 펼친다.
     */
    public boolean expandRenderedViews() {
        return expandRenderedViews;
    }

    /**
     * callees에서 접근자 호출 줄을 뺄지. 기본 false. Lombok이 만들 멤버({@code synthetic=true}, {@code lombok})와,
     * 같은 타입에 대응하는 필드가 있는 {@code getXxx}/{@code isXxx}(매개변수 0개)·{@code setXxx}(1개) 가운데 static이 아니고
     * CALLS·EXECUTES가 나가지 않는 메서드(재정의한 메서드도)가 대상이다({@link FlowNavigator#isAccessor}).
     */
    public boolean hideAccessors() {
        return hideAccessors;
    }

    /**
     * 데이터 적재 구문({@code SQL_STATEMENT}의 {@code attrs.dataLoad=true}: 스크립트의 리터럴 INSERT를 파일·테이블 단위로 묶은 것)을
     * 트리에 넣을지. 기본 false(테이블의 callers에 데이터 스크립트가 줄줄이 나오지 않게).
     */
    public boolean includeDataLoad() {
        return includeDataLoad;
    }

    /**
     * 레이아웃 너머를 펼칠지. 기본 false: 내용 페이지 → 데코레이터, tiles template → 조각({@code INCLUDES}의 {@code via=sitemesh|tiles}),
     * 핸들러 → tiles template({@code RENDERS}의 {@code via=tiles})은 {@code [layout]} 한 줄만 남긴다. callers에서는 그 레이아웃을 쓰는
     * 화면을 줄줄이 내지 않고 "쓰는 화면 n개" 한 줄로 접는다. {@link #expandRenderedViews()}와는 따로다(화면 이동을 펼쳐도 레이아웃은
     * 이 옵션을 켜야 펼친다: 모든 화면이 공통 헤더·메뉴의 요청을 끌고 온다).
     */
    public boolean expandLayouts() {
        return expandLayouts;
    }

    public static final class Builder {
        private Direction direction = Direction.CALLEES;
        private int maxDepth = 10;
        private int maxNodes = 2000;
        private boolean expandImplementations = true;
        private boolean narrowByInjection = true;
        private boolean collapseRepeated = true;
        private boolean hideExternal;
        private boolean expandRenderedViews;
        private boolean hideAccessors;
        private boolean includeDataLoad;
        private boolean expandLayouts;

        private Builder() {
        }

        public Builder direction(Direction direction) {
            this.direction = direction == null ? Direction.CALLEES : direction;
            return this;
        }

        public Builder maxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
            return this;
        }

        public Builder maxNodes(int maxNodes) {
            this.maxNodes = maxNodes;
            return this;
        }

        public Builder expandImplementations(boolean expand) {
            this.expandImplementations = expand;
            return this;
        }

        public Builder narrowByInjection(boolean narrow) {
            this.narrowByInjection = narrow;
            return this;
        }

        public Builder collapseRepeated(boolean collapse) {
            this.collapseRepeated = collapse;
            return this;
        }

        public Builder hideExternal(boolean hide) {
            this.hideExternal = hide;
            return this;
        }

        public Builder expandRenderedViews(boolean expand) {
            this.expandRenderedViews = expand;
            return this;
        }

        public Builder hideAccessors(boolean hide) {
            this.hideAccessors = hide;
            return this;
        }

        public Builder includeDataLoad(boolean include) {
            this.includeDataLoad = include;
            return this;
        }

        public Builder expandLayouts(boolean expand) {
            this.expandLayouts = expand;
            return this;
        }

        public CallTreeOptions build() {
            return new CallTreeOptions(this);
        }
    }
}
