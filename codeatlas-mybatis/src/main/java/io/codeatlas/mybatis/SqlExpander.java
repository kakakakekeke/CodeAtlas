package io.codeatlas.mybatis;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.SourceLocation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 동적 SQL 전개기. 조건식은 평가하지 않고, 테이블·컬럼·조인 정보가 빠지지 않는 SQL 텍스트를 만든다.
 *
 * <ul>
 *   <li>{@code <if>}: 본문을 넣는다(test 문자열이 같은 {@code <when>}이 거짓인 전개본에서만 뺀다). 다만 조건이 "식 == 리터럴" 꼴
 *       ({@link IfTest})이면 그 식을 구문 전체에서 값 하나로 고정해 값이 맞는 {@code <if>}만 넣는다. 전개본 1번은 식마다 처음 나온 값이고, 다른 값마다 "그 식의 값만 바꾼" 전개본을 하나씩 더 만든다.
 *       서로 배타적인 분기({@code userSe == 'GNR'} / {@code 'ENT'}, {@code dbName == 'oracle'} / {@code 'mysql'})가
 *       한 전개본에 이어 붙어 SQL이 깨지는 것을 막는다.</li>
 *   <li>{@code <choose>}: MyBatis처럼 조건이 참인 첫 {@code <when>}, 없으면 {@code <otherwise>}를 넣는다. 조건의 참·거짓은 구문 전체에서
 *       고정한 상태로 정한다. {@code <when test>}가 등치 꼴이면 {@code <if>}와 같은 "식의 값"으로, 아니면 test 문자열(공백 정규화)마다 둔
 *       참·거짓으로 본다. 그래서 조건이 같은 {@code <when>}은 choose가 달라도(include한 조각 안이어도) 함께 움직인다.
 *       전개본 1번은 식마다 처음 나온 값, test 문자열은 모두 참이다(서로 무관한 choose는 각자 첫 분기). 나머지 분기는 분기마다
 *       "그 분기가 골라지게 하는 데 필요한 값만 바꾼" 전개본을 하나씩 더 만든다(조합하지 않으므로 전개본 수는 분기 수의 합 수준이다).
 *       상한은 {@link #MAX_VARIANTS}.</li>
 *   <li>{@code <where>}, {@code <set>}, {@code <trim>}: 접두·접미를 붙이고 덮어쓸 토큰을 뗀다. 본문이 비면 생략한다.</li>
 *   <li>{@code <foreach>}: {@code open + 본문 한 번 + close}.</li>
 *   <li>{@code <include>}: 조각 본문을 그 자리에 전개한다. {@code <property>} 값은 조각 안의 {@code ${name}}에 치환한다.</li>
 *   <li>{@code <bind>}, {@code <selectKey>}: 본문에 넣지 않는다(selectKey는 호출하는 쪽이 따로 전개한다).</li>
 *   <li>알 수 없는 태그(구식 iBatis 태그 등): 본문만 전개하고 태그 이름을 결과에 담아 준다.</li>
 * </ul>
 */
final class SqlExpander {

    /** 한 구문 정의에서 등록하는 전개본의 상한. */
    static final int MAX_VARIANTS = 16;
    /** 전개본을 만들려고 시도하는 횟수의 상한(같은 본문이 반복해 나오는 경우를 막는다). */
    private static final int MAX_ATTEMPTS = MAX_VARIANTS * 4;
    /** 식의 값 자리에 두는 표시: 알려진 어느 리터럴과도 같지 않다(XML 속성에 올 수 없는 글자로 시작해 리터럴과 겹치지 않는다). */
    private static final String NO_VALUE = "\0none";
    /** test 문자열의 상태 자리에 두는 표시: 거짓. 없으면 참이다. */
    private static final String FALSE = "\0false";
    /** {@link Choice#values}에서 test 문자열 키 앞에 붙이는 말(식에는 {@code :}이 올 수 없어 식과 겹치지 않는다). */
    private static final String TEST_KEY = "test:";

    /** 조각을 찾고, 전개 중에 알게 된 사실(include 관계, 문제)을 받는 쪽. */
    interface Host {
        /**
         * 정규화된 ID({@code namespace.id})의 조각. 정의가 여럿이면 include를 시작한 구문과 같은 파일(없으면 같은 방언 파일)의 것,
         * 그 안에서 databaseId가 맞는 것을 먼저 고른다. 없으면 null.
         */
        FragmentDef findFragment(String qualifiedId, String databaseId, MapperFile from);

        void included(String fromNodeId, FragmentDef target, Confidence confidence, SourceLocation location);

        void unresolvedInclude(String fromNodeId, String reference, SourceLocation location);

        void cyclicInclude(String fromNodeId, String chain, SourceLocation location);
    }

    /**
     * @param variants 전개본. {@code #{}}와 {@code ${}}를 치환했고 줄 단위로 다듬었다. 본문이 비면 빈 목록
     * @param dynamicTags 본문의 모양을 바꾸는 동적 태그(if, choose, where, set, trim, foreach, 모르는 태그)를 만났는가
     * @param dollars 치환되지 않고 남은 {@code ${...}}의 식(중복 없이, 나온 순서)
     * @param truncated 상한 때문에 만들지 못한 전개본이 있는가
     * @param includes include를 만났는가
     * @param binds {@code <bind>}를 만났는가(본문에는 넣지 않는다)
     * @param unknownTags 만난 알 수 없는 태그의 이름(중복 없이, 나온 순서)
     * @param variantParts 전개본마다(같은 순서) 그 본문의 {@code __DYNAMIC__} 자리에 있던 {@code ${…}} 원문 목록
     */
    record Expansion(List<String> variants, boolean dynamicTags, List<String> dollars, boolean truncated, boolean includes,
            boolean binds, List<String> unknownTags, List<List<String>> variantParts) {

        /** 전개본 index의 {@code __DYNAMIC__} 자리에 있던 {@code ${…}} 원문(등장 순서, F-009-R21). */
        List<String> partsOf(int index) {
            return index < variantParts.size() ? variantParts.get(index) : List.of();
        }

        boolean dynamic() {
            return dynamicTags || binds || !dollars.isEmpty();
        }
    }

    /**
     * 전개본 하나를 만들 때의 선택(구문 전체에서 고정하는 조건의 상태).
     *
     * @param forced 조건의 상태로는 고를 수 없는 분기(앞의 when에 가린 것)를 억지로 고른 choose와 그 분기 번호. 보통은 비어 있다
     * @param values 기본과 다르게 고정한 것만 담는다. 등치 조건의 식 → 값(기본은 그 식에서 처음 나온 값, {@link #NO_VALUE}는 어느 값도 아님),
     *     {@link #TEST_KEY}를 붙인 test 문자열 → {@link #FALSE}(기본은 참)
     */
    private record Choice(Map<XmlElement, Integer> forced, Map<String, String> values) {
    }

    private final Host host;

    SqlExpander(Host host) {
        this.host = host;
    }

    /**
     * @param container 본문을 전개할 요소(구문, selectKey, 조각)
     * @param file container가 들어 있는 매퍼 파일
     * @param ownerNodeId container의 본문에 직접 있는 include의 주인 노드 ID
     * @param databaseId 구문의 databaseId(조각을 고를 때 쓴다). 없으면 null
     * @param selfFragmentId container가 조각이면 그 조각의 정규화된 ID(자기 자신으로 돌아오는 include를 끊는다). 아니면 null
     */
    Expansion expand(XmlElement container, MapperFile file, String ownerNodeId, String databaseId, String selfFragmentId) {
        Run run = new Run(databaseId, file);
        List<Choice> queue = new ArrayList<>();
        queue.add(new Choice(Map.of(), Map.of()));
        // 같은 상태를 두 번 전개하지 않는다(값의 발견과 choose 분기의 발견이 같은 상태를 가리키는 일이 흔하다).
        Set<Map<String, String>> queued = new java.util.HashSet<>();
        queued.add(Map.of());
        Set<String> seen = new LinkedHashSet<>();
        List<String> variants = new ArrayList<>();
        List<List<String>> variantParts = new ArrayList<>();
        boolean truncated = false;

        for (int i = 0; i < queue.size(); i++) {
            if (variants.size() >= MAX_VARIANTS || i >= MAX_ATTEMPTS) {
                truncated = true;
                break;
            }
            Choice choice = queue.get(i);
            run.choice = choice;
            run.discovered.clear();
            StringBuilder out = new StringBuilder();
            Scope scope = new Scope(file, ownerNodeId, Map.of(), selfFragmentId == null ? List.of() : List.of(selfFragmentId));
            run.render(container, scope, out);

            // 이번에 처음 만난 갈림(choose의 다른 분기, 등치 조건의 다른 값)마다 그것 하나만 바꾼 전개본을 예약한다.
            for (Discovery found : run.discovered) {
                if (found.choose() != null) {
                    for (int branch = 0; branch < found.branches().size(); branch++) {
                        if (branch != found.selected()) {
                            enqueue(run.withBranch(choice, found.choose(), found.branches(), branch), queue, queued);
                        }
                    }
                } else {
                    enqueue(run.withValue(choice, found.expr(), found.value()), queue, queued);
                }
            }

            SqlParams.Result substituted = SqlParams.plain(out.toString());
            run.dollars.addAll(substituted.dollars());
            String text = SqlText.tidyLines(substituted.text());
            if (!text.isEmpty() && seen.add(SqlText.collapse(text))) {
                variants.add(text);
                variantParts.add(substituted.originals());
            }
        }
        return new Expansion(List.copyOf(variants), run.dynamicTags, List.copyOf(run.dollars), truncated, run.includes,
                run.binds, List.copyOf(run.unknownTags), List.copyOf(variantParts));
    }

    private static void enqueue(Choice choice, List<Choice> queue, Set<Map<String, String>> queued) {
        // 억지로 고른 분기가 있는 선택은 드물고 서로 겹치지 않으므로 상태만 같은지 본다.
        if (!choice.forced().isEmpty() || queued.add(choice.values())) {
            queue.add(choice);
        }
    }

    /**
     * 전개 중에 처음 만난 갈림: choose(분기들과 그때 골라진 분기 번호. 고른 것이 없으면 -1) 또는 등치 조건의 새 값.
     */
    private record Discovery(XmlElement choose, List<XmlElement> branches, int selected, String expr, String value) {
    }

    /**
     * {@code <if>}나 {@code <when>}의 조건을 읽은 것. 둘 중 하나만 있다.
     *
     * @param equalities 등치 꼴이면 AND로 걸린 등치 조건들. 아니면 빈 목록
     * @param key 등치 꼴이 아니면 test 문자열의 상태 키. test가 없는 {@code <if>}는 null(항상 참)
     */
    private record Condition(List<IfTest.Equality> equalities, String key) {
    }

    /** include로 들어간 한 단계의 문맥. */
    private record Scope(MapperFile file, String ownerNodeId, Map<String, String> properties, List<String> includeStack) {
    }

    /** 구문 하나를 전개하는 동안의 상태. */
    private final class Run {
        private final String databaseId;
        private final MapperFile statementFile;
        private final String statementNamespace;
        private Choice choice;
        /** 이번 전개본에서 처음 만난 갈림(만난 순서). */
        private final List<Discovery> discovered = new ArrayList<>();
        /** 지금까지 만난 choose. */
        private final Set<XmlElement> chooses = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        /** 등치 조건의 식 → 지금까지 만난 값(만난 순서). 첫 값이 기본값이다. */
        private final Map<String, Set<String>> knownValues = new HashMap<>();
        /** {@code <if>}·{@code <when>} 요소 → 읽어 낸 조건(전개본마다 다시 읽지 않게). */
        private final Map<XmlElement, Condition> conditions = new IdentityHashMap<>();
        private final Set<String> dollars = new LinkedHashSet<>();
        private final Set<String> unknownTags = new LinkedHashSet<>();
        private boolean dynamicTags;
        private boolean includes;
        private boolean binds;

        Run(String databaseId, MapperFile statementFile) {
            this.databaseId = databaseId;
            this.statementFile = statementFile;
            this.statementNamespace = statementFile.namespace();
        }

        void render(XmlElement parent, Scope scope, StringBuilder out) {
            for (XmlNode child : parent.children) {
                if (child instanceof XmlText text) {
                    out.append(applyProperties(text.text(), scope.properties()));
                } else if (child instanceof XmlElement element) {
                    renderElement(element, scope, out);
                }
            }
        }

        private void renderElement(XmlElement element, Scope scope, StringBuilder out) {
            switch (element.name) {
                case "if" -> {
                    dynamicTags = true;
                    if (testHolds(element)) {
                        render(element, scope, out);
                    }
                }
                case "when", "otherwise" -> {
                    dynamicTags = true;
                    render(element, scope, out);
                }
                case "choose" -> renderChoose(element, scope, out);
                case "where" -> {
                    dynamicTags = true;
                    out.append(' ').append(trim(body(element, scope), "WHERE", null, List.of("AND", "OR"), List.of())).append(' ');
                }
                case "set" -> {
                    dynamicTags = true;
                    out.append(' ').append(trim(body(element, scope), "SET", null, List.of(","), List.of(","))).append(' ');
                }
                case "trim" -> {
                    dynamicTags = true;
                    out.append(' ').append(trim(body(element, scope),
                            attr(element, "prefix", scope), attr(element, "suffix", scope),
                            overrides(attr(element, "prefixOverrides", scope)),
                            overrides(attr(element, "suffixOverrides", scope)))).append(' ');
                }
                case "foreach" -> {
                    dynamicTags = true;
                    String open = attr(element, "open", scope);
                    String close = attr(element, "close", scope);
                    if (open != null) {
                        out.append(open);
                    }
                    render(element, scope, out);
                    if (close != null) {
                        out.append(close);
                    }
                }
                case "bind" -> binds = true;
                case "selectKey", "property" -> {
                    // selectKey는 호출하는 쪽이 별도 내장 소스로 전개한다.
                }
                case "include" -> renderInclude(element, scope, out);
                default -> {
                    dynamicTags = true;
                    unknownTags.add(element.qName);
                    render(element, scope, out);
                }
            }
        }

        private void renderChoose(XmlElement choose, Scope scope, StringBuilder out) {
            dynamicTags = true;
            List<XmlElement> branches = new ArrayList<>();
            for (XmlElement child : choose.elements()) {
                if (child.name.equals("when") || child.name.equals("otherwise")) {
                    branches.add(child);
                }
            }
            if (branches.isEmpty()) {
                return;
            }
            // 고르기 전에 모든 when의 값을 알려 둔다(어느 분기가 골라지든 식의 기본값이 같게).
            int mark = discovered.size();
            for (XmlElement branch : branches) {
                if (branch.name.equals("when")) {
                    learn(condition(branch));
                }
            }
            Integer forced = choice.forced().get(choose);
            int index = forced != null ? Math.min(forced, branches.size() - 1) : select(branches, choice.values());
            if (chooses.add(choose)) {
                // 분기의 전개본을 when에서 알게 된 값의 전개본보다 먼저 예약한다(전개본 번호가 분기 순서를 따르게).
                discovered.add(mark, new Discovery(choose, List.copyOf(branches), index, null, null));
            }
            if (index >= 0) {
                render(branches.get(index), scope, out);
            }
        }

        /** MyBatis가 고르는 분기: 조건이 참인 첫 {@code <when>}, 없으면 {@code <otherwise>}. 그것도 없으면 -1. */
        private int select(List<XmlElement> branches, Map<String, String> values) {
            int otherwise = -1;
            for (int i = 0; i < branches.size(); i++) {
                XmlElement branch = branches.get(i);
                if (!branch.name.equals("when")) {
                    otherwise = otherwise < 0 ? i : otherwise;
                } else if (holds(condition(branch), values)) {
                    return i;
                }
            }
            return otherwise;
        }

        /**
         * 이 {@code <if>}의 본문을 이번 전개본에 넣을지. 조건이 "식 == 리터럴" 꼴이면 식의 이번 값(고정한 값, 없으면 그 식에서 처음 나온 값)이
         * 조건의 리터럴과 같을 때만 넣는다. 그 꼴이 아니면 넣는다. 다만 test 문자열이 같은 {@code <when>}이 거짓으로 고정된 전개본에서는 뺀다.
         */
        private boolean testHolds(XmlElement element) {
            Condition condition = condition(element);
            learn(condition);
            return holds(condition, choice.values());
        }

        private Condition condition(XmlElement element) {
            Condition condition = conditions.get(element);
            if (condition == null) {
                String test = element.attrs.get("test");
                List<IfTest.Equality> equalities = IfTest.parse(test);
                String key = null;
                if (equalities.isEmpty()) {
                    if (test != null && !test.isBlank()) {
                        key = TEST_KEY + SqlText.collapse(test);
                    } else if (element.name.equals("when")) {
                        // test가 없는 when은 다른 것과 묶이지 않는 저만의 상태를 갖는다. test가 없는 if는 항상 넣는다.
                        key = TEST_KEY + "#" + conditions.size();
                    }
                }
                condition = new Condition(equalities, key);
                conditions.put(element, condition);
            }
            return condition;
        }

        /** 조건의 등치 값을 알려 둔다. 식에서 처음 나온 값이 기본값이고, 그 뒤에 처음 나온 값마다 전개본을 예약한다. */
        private void learn(Condition condition) {
            for (IfTest.Equality equality : condition.equalities()) {
                Set<String> known = knownValues.computeIfAbsent(equality.expr(), k -> new LinkedHashSet<>());
                for (String value : equality.values()) {
                    if (known.add(value) && known.size() > 1) {
                        discovered.add(new Discovery(null, List.of(), -1, equality.expr(), value));
                    }
                }
            }
        }

        private boolean holds(Condition condition, Map<String, String> values) {
            if (condition.equalities().isEmpty()) {
                return condition.key() == null || !values.containsKey(condition.key());
            }
            for (IfTest.Equality equality : condition.equalities()) {
                if (!equality.values().contains(current(equality.expr(), values))) {
                    return false;
                }
            }
            return true;
        }

        /** 식의 이번 값: 고정한 값, 없으면 그 식에서 처음 나온 값. */
        private String current(String expr, Map<String, String> values) {
            String value = values.get(expr);
            return value != null ? value : knownValues.get(expr).iterator().next();
        }

        /** 식의 값만 바꾼 선택. 기본값으로 돌아가는 것이면 고정을 푼다(같은 상태가 한 가지로만 적히게). */
        Choice withValue(Choice base, String expr, String value) {
            Map<String, String> values = new HashMap<>(base.values());
            fix(values, expr, value);
            return new Choice(base.forced(), values);
        }

        private void fix(Map<String, String> values, String expr, String value) {
            if (value.equals(knownValues.get(expr).iterator().next())) {
                values.remove(expr);
            } else {
                values.put(expr, value);
            }
        }

        /**
         * choose가 target 분기를 고르게 하는 선택: target이 when이면 그 조건을 참으로, 그보다 앞선(otherwise면 모든) when을 거짓으로 만드는 데
         * 필요한 것만 base에서 바꾼다. 등치 조건은 식의 값을 바꾸고(거짓으로 만들 때는 "어느 값도 아님"), 그 밖의 조건은 test 문자열의 상태를 바꾼다.
         * 바꾼 상태는 구문 전체에 걸리므로 같은 조건을 쓰는 다른 choose와 if가 함께 움직인다.
         * 앞의 when에 가려 상태로는 고를 수 없는 분기는 억지로 고른다(본문을 잃지 않으려고).
         */
        Choice withBranch(Choice base, XmlElement choose, List<XmlElement> branches, int target) {
            Map<String, String> values = new HashMap<>(base.values());
            Set<String> pinned = new java.util.HashSet<>();
            XmlElement wanted = branches.get(target);
            boolean when = wanted.name.equals("when");
            if (when) {
                Condition condition = condition(wanted);
                if (condition.key() != null) {
                    values.remove(condition.key());
                    pinned.add(condition.key());
                }
                for (IfTest.Equality equality : condition.equalities()) {
                    fix(values, equality.expr(), valueFor(equality, values, branches, target));
                    pinned.add(equality.expr());
                }
            }
            for (int i = 0; i < branches.size() && (!when || i < target); i++) {
                XmlElement earlier = branches.get(i);
                if (!earlier.name.equals("when")) {
                    continue;
                }
                Condition condition = condition(earlier);
                if (!holds(condition, values)) {
                    continue;
                }
                if (condition.key() != null && !pinned.contains(condition.key())) {
                    values.put(condition.key(), FALSE);
                }
                for (IfTest.Equality equality : condition.equalities()) {
                    if (!pinned.contains(equality.expr())) {
                        values.put(equality.expr(), NO_VALUE);
                        break;
                    }
                }
            }
            if (select(branches, values) == target) {
                return new Choice(base.forced(), values);
            }
            Map<XmlElement, Integer> forced = new IdentityHashMap<>(base.forced());
            forced.put(choose, target);
            return new Choice(forced, values);
        }

        /**
         * target when의 등치 조건을 참으로 만들 값. 이미 맞는 값이면 그대로 두고, 여러 값 중에서는 같은 식 하나만 보는 앞선 when에
         * 가리지 않는 값을 먼저 고른다.
         */
        private String valueFor(IfTest.Equality equality, Map<String, String> values, List<XmlElement> branches, int target) {
            List<String> candidates = new ArrayList<>();
            String now = current(equality.expr(), values);
            if (equality.values().contains(now)) {
                candidates.add(now);
            }
            candidates.addAll(equality.values());
            for (String candidate : candidates) {
                boolean shadowed = false;
                for (int i = 0; i < target && !shadowed; i++) {
                    List<IfTest.Equality> earlier = branches.get(i).name.equals("when")
                            ? condition(branches.get(i)).equalities() : List.of();
                    shadowed = earlier.size() == 1 && earlier.get(0).expr().equals(equality.expr())
                            && earlier.get(0).values().contains(candidate);
                }
                if (!shadowed) {
                    return candidate;
                }
            }
            return candidates.get(0);
        }

        private void renderInclude(XmlElement include, Scope scope, StringBuilder out) {
            includes = true;
            SourceLocation location = scope.file().location(include);
            String refid = include.attr("refid");
            if (refid == null) {
                host.unresolvedInclude(scope.ownerNodeId(), "(refid 없음)", location);
                return;
            }
            refid = applyProperties(refid, scope.properties()).trim();
            FragmentDef target = null;
            Confidence confidence = Confidence.RESOLVED;
            String qualified = refid.contains(".") ? refid : statementNamespace + "." + refid;
            if (!refid.contains("${")) {
                target = host.findFragment(qualified, databaseId, statementFile);
                if (target == null && !refid.contains(".") && !scope.file().namespace().equals(statementNamespace)) {
                    // MyBatis는 구문의 namespace에서만 찾는다. 조각이 정의된 namespace에 있으면 추론으로 잇는다.
                    target = host.findFragment(scope.file().namespace() + "." + refid, databaseId, statementFile);
                    confidence = Confidence.INFERRED;
                }
            }
            if (target == null) {
                host.unresolvedInclude(scope.ownerNodeId(), qualified, location);
                return;
            }
            host.included(scope.ownerNodeId(), target, confidence, location);
            String targetId = target.qualifiedId();
            if (scope.includeStack().contains(targetId)) {
                host.cyclicInclude(scope.ownerNodeId(), String.join(" → ", scope.includeStack()) + " → " + targetId, location);
                return;
            }
            Map<String, String> properties = new HashMap<>(scope.properties());
            for (XmlElement property : include.elements("property")) {
                String name = property.attr("name");
                String value = property.attrs.get("value");
                if (name != null && value != null) {
                    properties.put(name, applyProperties(value, scope.properties()));
                }
            }
            List<String> stack = new ArrayList<>(scope.includeStack());
            stack.add(targetId);
            render(target.element(), new Scope(target.file(), target.nodeId(), properties, stack), out);
        }

        private String body(XmlElement element, Scope scope) {
            StringBuilder inner = new StringBuilder();
            render(element, scope, inner);
            return inner.toString();
        }

        private String attr(XmlElement element, String name, Scope scope) {
            String value = element.attrs.get(name);
            return value == null ? null : applyProperties(value, scope.properties());
        }
    }

    // ---- 텍스트 규칙 ----

    /** include의 property로 값이 정해진 {@code ${name}}만 치환한다. 나머지는 그대로 둔다. */
    static String applyProperties(String text, Map<String, String> properties) {
        if (properties.isEmpty() || text.indexOf("${") < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf("${", i);
            if (open < 0) {
                out.append(text, i, text.length());
                break;
            }
            int close = text.indexOf('}', open + 2);
            if (close < 0) {
                out.append(text, i, text.length());
                break;
            }
            out.append(text, i, open);
            String value = properties.get(text.substring(open + 2, close).trim());
            if (value != null) {
                out.append(value);
            } else {
                out.append(text, open, close + 1);
            }
            i = close + 1;
        }
        return out.toString();
    }

    private static List<String> overrides(String attribute) {
        if (attribute == null) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : attribute.split("\\|")) {
            if (!token.isBlank()) {
                tokens.add(token.strip());
            }
        }
        return tokens;
    }

    /**
     * {@code <trim>} 규칙. 본문이 비면 빈 문자열. 아니면 맨 앞에서 prefixOverrides 중 하나를, 맨 뒤에서 suffixOverrides 중 하나를 떼고
     * prefix와 suffix를 공백 한 칸을 두고 붙인다. 토큰 비교는 대소문자를 가리지 않고, 단어 토큰({@code AND})은 단어 경계에서만 뗀다.
     */
    static String trim(String body, String prefix, String suffix, List<String> prefixOverrides, List<String> suffixOverrides) {
        String text = body.strip();
        if (text.isEmpty()) {
            return "";
        }
        for (String token : prefixOverrides) {
            if (startsWithToken(text, token)) {
                text = text.substring(token.length()).stripLeading();
                break;
            }
        }
        for (String token : suffixOverrides) {
            if (endsWithToken(text, token)) {
                text = text.substring(0, text.length() - token.length()).stripTrailing();
                break;
            }
        }
        StringBuilder out = new StringBuilder();
        if (prefix != null && !prefix.isBlank()) {
            out.append(prefix.strip()).append(' ');
        }
        out.append(text);
        if (suffix != null && !suffix.isBlank()) {
            out.append(' ').append(suffix.strip());
        }
        return out.toString();
    }

    private static boolean startsWithToken(String text, String token) {
        if (!text.regionMatches(true, 0, token, 0, token.length())) {
            return false;
        }
        if (text.length() == token.length() || !isWordChar(token.charAt(token.length() - 1))) {
            return true;
        }
        return !isWordChar(text.charAt(token.length()));
    }

    private static boolean endsWithToken(String text, String token) {
        int at = text.length() - token.length();
        if (at < 0 || !text.regionMatches(true, at, token, 0, token.length())) {
            return false;
        }
        if (at == 0 || !isWordChar(token.charAt(0))) {
            return true;
        }
        return !isWordChar(text.charAt(at - 1));
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }
}
