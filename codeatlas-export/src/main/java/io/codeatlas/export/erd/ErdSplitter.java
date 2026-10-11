package io.codeatlas.export.erd;

import io.codeatlas.export.common.Texts;
import io.codeatlas.export.erd.ErdModel.Entity;
import io.codeatlas.export.erd.ErdModel.Relation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 큰 ERD를 주제 영역별 Mermaid 다이어그램으로 나눈다. Mermaid는 다이어그램 텍스트가 {@code maxTextSize}(기본 50,000자)를 넘으면
 * 그리지 않으므로, 테이블이 수백 개인 스키마는 한 장으로 볼 수 없다.
 *
 * <p>영역은 먼저 테이블 이름의 접두사다: 이름을 {@code _}로 나눈 첫 토큰이 같은 테이블끼리 묶고, 한 묶음의 다이어그램이 한도를 넘으면
 * 둘째 토큰까지로 다시 나누고, 그래도 넘으면 이름순으로 잘라 {@code <영역>-1}, {@code <영역>-2}…로 만든다.
 *
 * <p>접두사로 묶이지 않는 테이블(같은 접두사가 {@value #MIN_AREA_SIZE}개 미만: 밑줄 없는 이름의 스키마에서는 전부)은 관계로 묶는다:
 * 관계({@code REFERENCES})로 이어진 테이블끼리(연결 요소) 한 영역이고, 요소가 한도를 넘으면 그 안에서 가장 많이 참조되는 테이블들을
 * 중심으로 가까운 테이블끼리 나눈다. 영역 이름은 그 영역에서 가장 많이 참조되는 테이블의 이름이다. 전체 테이블의
 * {@value #HUB_PERCENT}%를 넘는 테이블이 참조하는 테이블(공통코드 같은 허브)은 요소를 하나로 뭉치게 하므로 영역에 넣지 않고
 * {@link Split#hubs()}로 따로 알린다(그것을 참조하는 영역마다 이름만 있는 외곽 엔티티로 나온다).
 *
 * <p>관계로 묶은 묶음이 테이블 {@value #MAX_AREA_TABLES}개(또는 글자 수 한도)를 넘으면 그 안에서 가장 많이 참조되는 테이블(중심 테이블,
 * {@link Split#centers()})을 빼고 나머지를 다시 묶는다: {@value #MIN_AREA_SIZE}개 이상인 조각은 저마다 영역이 되고(아직 크면 같은 방법으로
 * 또 나눈다), 그보다 작은 조각은 중심과 함께 중심 이름의 영역이 된다. 중심은 허브와 달리 영역 안에 있고, 그것을 참조하는 다른 영역에는
 * 이름만 나온다. 중심을 빼도 갈리지 않으면(가장 큰 조각이 묶음의 3/4을 넘으면) 중심 여럿에 가까운 테이블끼리 나눈다.
 *
 * <p>관계로 묶이지 않고 남은 테이블(묶음이 {@value #MIN_AREA_SIZE}개 미만)은 이름의 공통 접두사로 묶는다: 가장 긴 접두사부터, 그 접두사를
 * 가진 테이블 가운데 아직 묶이지 않은 것이 {@value #MIN_AREA_SIZE}개 이상이면 한 영역({@code COMTNSMS*})이다. 접두사는
 * {@value #MIN_NAME_PREFIX}자 이상이어야 한다. 그러고도 남는 테이블만 {@value #ETC}로 합친다. 영역 다이어그램에는 그 영역의 테이블(컬럼은
 * 옵션대로)과, 관계로 이어진 다른 영역의 테이블(이름만 있는 외곽 엔티티)과, 그 관계가 나온다.
 *
 * <pre>{@code
 * ErdModel model = Erd.model(graph, options);
 * if (Erd.render(model, options, ErdFormat.MERMAID).length() > 45_000) {
 *     for (ErdSplitter.Area area : ErdSplitter.split(model, options, 45_000).areas()) {
 *         Files.writeString(dir.resolve("erd-" + area.fileName() + ".mmd"), area.mermaid());
 *     }
 * }
 * }</pre>
 */
public final class ErdSplitter {

    /** 작은 묶음을 합친 영역의 표시 이름. 파일 이름은 {@code etc}다. */
    public static final String ETC = "기타";

    /** 이보다 테이블이 적은 묶음(접두사, 관계 군집)은 {@value #ETC}로 합친다. */
    public static final int MIN_AREA_SIZE = 3;

    /** 접두사로 묶이지 않은 테이블 가운데, 전체 테이블의 이 비율(%)을 넘는 테이블이 참조하는 것은 허브로 본다. */
    public static final int HUB_PERCENT = 20;

    /** 관계로 묶은 영역과 이름 접두 군집 하나의 테이블 수 상한. 접두사 영역과 {@value #ETC}는 글자 수 한도만 본다. */
    public static final int MAX_AREA_TABLES = 30;

    /** 이름 접두 군집의 접두사는 이 글자 수 이상이어야 한다. */
    public static final int MIN_NAME_PREFIX = 3;

    /** 영역 표시 이름({@link Area#label()})에 넣는 논리명의 글자 수 상한. 넘으면 자르고 {@code …}를 붙인다. */
    public static final int MAX_LABEL_COMMENT = 40;

    private static final Comparator<Entity> BY_NAME = Comparator.comparing(Entity::name).thenComparing(Entity::id);

    private ErdSplitter() {
    }

    /**
     * 영역 하나.
     *
     * @param name 영역 이름(접두사, {@code SYS-2}, 관계로 묶은 영역은 그 안에서 가장 많이 참조되는 테이블 이름, 이름 접두 군집은
     *        {@code COMTNSMS*}, {@value #ETC})
     * @param label 표시 이름. 영역 이름이 된 테이블에 논리명(comment)이 있으면 {@code 논리명(영역 이름)}, 없으면 영역 이름
     * @param fileName 파일 이름에 쓸 조각(안전한 문자만, 영역끼리 대소문자 무시하고도 겹치지 않는다). 논리명과 {@code *}는 넣지 않는다
     * @param tables 이 영역의 테이블 수
     * @param innerRelations 양 끝이 이 영역인 관계 수
     * @param outerRelations 한쪽 끝이 다른 영역인 관계 수. 허브와의 관계는 세지 않는다
     * @param hubRelations 한쪽 끝이 이 영역이고 다른 끝이 허브 테이블인 관계 수
     * @param mermaid 이 영역의 {@code erDiagram}
     */
    public record Area(String name, String label, String fileName, int tables, int innerRelations, int outerRelations, int hubRelations,
            String mermaid) {
    }

    /**
     * 허브 테이블: 어느 영역에도 넣지 않은, 많은 테이블이 참조하는 테이블.
     *
     * @param name 테이블 이름
     * @param referencedBy 이 테이블을 참조하는 테이블 수
     */
    public record Hub(String name, int referencedBy) {
    }

    /**
     * 나눈 결과.
     *
     * @param areas 영역(이름순, {@value #ETC}는 끝)
     * @param crossRelations 영역 쌍(이름순으로 앞선 쪽이 키의 앞, {@code "A\tB"}) → 두 영역 사이의 관계 수
     * @param hubs 허브 테이블(이름순). 관계로 묶은 영역이 없으면 비어 있다
     * @param byRelations 관계로 묶은 영역이 하나라도 있으면 true
     * @param centers 중심 테이블(이름순). 테이블 수나 글자 수 한도를 넘은 관계 묶음이 없으면 비어 있다
     * @param byNames 이름의 공통 접두사로 묶은 영역이 하나라도 있으면 true
     */
    public record Split(List<Area> areas, Map<String, Integer> crossRelations, List<Hub> hubs, boolean byRelations, List<Center> centers,
            boolean byNames) {
        public Split {
            areas = List.copyOf(areas);
            crossRelations = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(crossRelations));
            hubs = List.copyOf(hubs);
            centers = List.copyOf(centers);
        }
    }

    /**
     * 중심 테이블: 테이블이 {@value #MAX_AREA_TABLES}개를 넘는(또는 글자 수 한도를 넘는) 관계 묶음에서 가장 많이 참조되는 테이블.
     *
     * @param name 테이블 이름
     * @param referencedBy 이 테이블을 참조하는 테이블 수
     * @param areas 이 테이블을 빼고 나머지를 다시 묶어 만든 영역의 이름(이름순)
     */
    public record Center(String name, int referencedBy, List<String> areas) {
        public Center {
            areas = List.copyOf(areas);
        }
    }

    /**
     * @param model 전체 모델
     * @param options 영역 다이어그램을 그릴 표시 옵션(컬럼 표시 수준, 주석)
     * @param maxChars 영역 다이어그램 하나의 글자 수 한도. 테이블 하나짜리 다이어그램이 한도를 넘으면 그대로 둔다
     */
    public static Split split(ErdModel model, ErdOptions options, int maxChars) {
        ErdOptions opts = options == null ? ErdOptions.defaults() : options;
        // 1) 첫 토큰으로 묶는다
        Map<String, List<Entity>> groups = new TreeMap<>();
        for (Entity e : model.entities()) {
            groups.computeIfAbsent(prefix(e.name(), 1), k -> new ArrayList<>()).add(e);
        }
        // 2) 한도를 넘는 묶음은 둘째 토큰까지로 다시 묶는다
        Map<String, List<Entity>> refined = new TreeMap<>();
        for (Map.Entry<String, List<Entity>> g : groups.entrySet()) {
            if (g.getValue().size() < 2 || fits(model, g.getValue(), opts, maxChars)) {
                refined.computeIfAbsent(g.getKey(), k -> new ArrayList<>()).addAll(g.getValue());
                continue;
            }
            for (Entity e : g.getValue()) {
                refined.computeIfAbsent(prefix(e.name(), 2), k -> new ArrayList<>()).add(e);
            }
        }
        // 3) 작은 묶음(접두사로 묶이지 않는 테이블)은 관계로 묶는다
        Map<String, List<Entity>> named = new TreeMap<>();
        List<Entity> leftover = new ArrayList<>();
        for (Map.Entry<String, List<Entity>> g : refined.entrySet()) {
            if (g.getValue().size() < MIN_AREA_SIZE) {
                leftover.addAll(g.getValue());
            } else {
                named.put(g.getKey(), g.getValue());
            }
        }
        boolean prefixAreas = !named.isEmpty();
        Clusters clusters = new RelationClusterer(model, opts, maxChars).cluster(leftover);
        // 영역 이름 → 테이블 수 상한(없으면 글자 수 한도만 본다), 영역 이름 → 표시 이름에 넣을 논리명
        Map<String, Integer> caps = new HashMap<>();
        Map<String, String> comments = new HashMap<>();
        Map<String, String> renamed = new HashMap<>();
        for (Map.Entry<String, List<Entity>> cluster : clusters.areas().entrySet()) {
            // 관계로 묶은 영역의 이름(테이블 이름)이 접두사 영역의 이름과 겹치면 접두사 영역이 그 이름을 갖는다.
            String unique = unique(cluster.getKey(), named);
            named.put(unique, cluster.getValue());
            caps.put(unique, MAX_AREA_TABLES);
            comments.put(unique, clusters.comments().get(cluster.getKey()));
            renamed.put(cluster.getKey(), unique);
        }
        List<Center> centers = new ArrayList<>();
        for (Center center : clusters.centers()) {
            centers.add(new Center(center.name(), center.referencedBy(), center.areas().stream().map(renamed::get).toList()));
        }
        // 3-2) 관계로 묶이지 않고 남은 테이블은 이름의 공통 접두사로 묶는다
        Map<String, List<Entity>> nameClusters = new TreeMap<>();
        List<Entity> etc = clusterByNames(clusters.rest(), nameClusters);
        for (Map.Entry<String, List<Entity>> cluster : nameClusters.entrySet()) {
            String unique = unique(cluster.getKey(), named);
            named.put(unique, cluster.getValue());
            caps.put(unique, MAX_AREA_TABLES);
        }
        Map<String, List<Entity>> merged = new LinkedHashMap<>(named);
        if (!prefixAreas && clusters.areas().isEmpty() && clusters.hubs().isEmpty() && nameClusters.isEmpty() && !etc.isEmpty()) {
            // 접두사로도 관계로도 이름으로도 묶이는 테이블이 없다: 전체를 한 묶음으로 보고 이름순으로 자른다.
            merged.put("ALL", etc);
            etc = List.of();
        }
        if (!etc.isEmpty()) {
            etc.sort(BY_NAME);
            merged.put(ETC, etc);
        }
        // 4) 아직 한도를 넘는 묶음은 이름순으로 자른다(관계로 묶은 영역과 이름 접두 군집은 테이블 수도 한도다)
        Map<String, List<Entity>> finalGroups = new LinkedHashMap<>();
        Map<String, String> labels = new HashMap<>();
        for (Map.Entry<String, List<Entity>> g : merged.entrySet()) {
            List<List<Entity>> chunks = chunk(model, g.getValue(), opts, maxChars, caps.getOrDefault(g.getKey(), Integer.MAX_VALUE));
            for (int i = 0; i < chunks.size(); i++) {
                String name = chunks.size() == 1 ? g.getKey() : g.getKey() + "-" + (i + 1);
                finalGroups.put(name, chunks.get(i));
                labels.put(name, label(name, comments.get(g.getKey())));
            }
        }

        Map<String, String> areaOf = new HashMap<>();
        finalGroups.forEach((name, entities) -> entities.forEach(e -> areaOf.put(e.id(), name)));
        Map<String, Integer> cross = new TreeMap<>();
        for (Relation r : model.relations()) {
            String a = areaOf.get(r.childId());
            String b = areaOf.get(r.parentId());
            if (a != null && b != null && !a.equals(b)) {
                cross.merge(order(a) .compareTo(order(b)) <= 0 ? a + "\t" + b : b + "\t" + a, 1, Integer::sum);
            }
        }
        Map<String, Integer> sortedCross = new LinkedHashMap<>();
        cross.entrySet().stream().sorted(java.util.Comparator.comparing((Map.Entry<String, Integer> e) -> order(e.getKey().split("\t")[0]))
                .thenComparing(e -> order(e.getKey().split("\t")[1]))).forEach(e -> sortedCross.put(e.getKey(), e.getValue()));

        List<Area> areas = new ArrayList<>();
        Set<String> usedFileNames = new TreeSet<>();
        for (Map.Entry<String, List<Entity>> g : finalGroups.entrySet()) {
            Set<String> ids = new TreeSet<>();
            g.getValue().forEach(e -> ids.add(e.id()));
            int inner = 0;
            int outer = 0;
            int hub = 0;
            for (Relation r : model.relations()) {
                boolean child = ids.contains(r.childId());
                boolean parent = ids.contains(r.parentId());
                if (child && parent) {
                    inner++;
                } else if (child || parent) {
                    // 허브는 어느 영역에도 없다: 허브와의 관계를 "다른 영역과의 관계"로 세면 영역 간 관계 수와 어긋난다.
                    if (clusters.hubIds().contains(child ? r.parentId() : r.childId())) {
                        hub++;
                    } else {
                        outer++;
                    }
                }
            }
            // 이름 접두 군집(COMTNSMS*)의 파일 이름에는 *를 넣지 않는다.
            String base = ETC.equals(g.getKey()) ? "etc" : g.getKey().startsWith(ETC + "-") ? "etc" + g.getKey().substring(ETC.length())
                    : Texts.fileSafe(g.getKey().replace("*", ""));
            String fileName = base;
            for (int n = 2; !usedFileNames.add(fileName.toLowerCase(Locale.ROOT)); n++) {
                fileName = base + "_" + n;
            }
            areas.add(new Area(g.getKey(), labels.get(g.getKey()), fileName, g.getValue().size(), inner, outer, hub,
                    MermaidErdRenderer.render(areaModel(model, g.getValue()), opts)));
        }
        return new Split(areas, sortedCross, clusters.hubs(), !clusters.areas().isEmpty(), centers, !nameClusters.isEmpty());
    }

    /** 이름이 이미 쓰였으면 {@code -2}, {@code -3}…을 붙인다. */
    private static String unique(String name, Map<String, ?> used) {
        String unique = name;
        for (int n = 2; used.containsKey(unique); n++) {
            unique = name + "-" + n;
        }
        return unique;
    }

    /** 표시 이름: 논리명이 있으면 {@code 논리명(영역 이름)}. 논리명은 한 줄로 만들고 {@value #MAX_LABEL_COMMENT}자에서 자른다. */
    static String label(String areaName, String comment) {
        String text = Texts.oneLine(comment);
        if (text.isEmpty()) {
            return areaName;
        }
        if (text.codePointCount(0, text.length()) > MAX_LABEL_COMMENT) {
            text = text.substring(0, text.offsetByCodePoints(0, MAX_LABEL_COMMENT)) + "…";
        }
        return text + "(" + areaName + ")";
    }

    /**
     * 관계로 묶은 결과.
     *
     * @param areas 영역 이름 → 테이블
     * @param comments 영역 이름 → 그 이름이 된 테이블의 논리명(없으면 키가 없다)
     * @param hubs 허브 테이블(이름순)
     * @param hubIds 허브 테이블의 ID
     * @param centers 중심 테이블(이름순)
     * @param rest 어느 영역에도 들지 못한 나머지
     */
    private record Clusters(Map<String, List<Entity>> areas, Map<String, String> comments, List<Hub> hubs, Set<String> hubIds,
            List<Center> centers, List<Entity> rest) {
    }

    /**
     * 접두사로 묶이지 않은 테이블을 관계로 묶는다.
     * <ol>
     * <li>허브를 뺀다: 전체 테이블의 {@value #HUB_PERCENT}%를 넘는 테이블이 참조하는 테이블.</li>
     * <li>남은 테이블 사이의 관계로 연결 요소를 만든다. {@value #MIN_AREA_SIZE}개 미만인 요소는 나머지다.</li>
     * <li>한도(테이블 {@value #MAX_AREA_TABLES}개, 글자 수)를 넘는 요소는 그 안에서 가장 많이 참조되는 테이블(중심)을 빼고 다시 묶는다.
     *     {@value #MIN_AREA_SIZE}개 이상인 조각은 저마다 영역이 되고(아직 크면 같은 방법으로 또 나눈다), 그보다 작은 조각은 중심과 함께
     *     중심 이름의 영역이 된다. 중심을 빼도 갈리지 않으면(가장 큰 조각이 요소의 3/4을 넘으면) 중심 k개(많이 참조되는 순)를 정하고
     *     테이블마다 가장 가까운 중심에 붙여 나눈다(모든 조각이 한도 안에 드는 가장 작은 k).</li>
     * <li>영역 이름은 그 영역 안에서 가장 많이 참조되는 테이블 이름이다(같으면 이름순). 중심의 영역은 중심 이름이다.</li>
     * </ol>
     */
    private static final class RelationClusterer {
        private final ErdModel model;
        private final ErdOptions options;
        private final int maxChars;
        private final Map<String, Entity> byId = new LinkedHashMap<>();
        /** 테이블 → 그것을 참조하는 테이블(자기 참조 제외). */
        private final Map<String, Set<String>> referencedBy = new HashMap<>();
        /** 허브를 뺀 후보 테이블 사이의 이웃(방향을 가리지 않는다). */
        private final Map<String, Set<String>> adjacent = new HashMap<>();
        private final Map<String, List<Entity>> areas = new TreeMap<>();
        private final Map<String, String> comments = new HashMap<>();
        /** 중심 테이블. 값은 [중심 엔티티, 그것을 빼고 만든 영역의 이름]. */
        private final List<Map.Entry<Entity, List<String>>> centers = new ArrayList<>();
        private final List<Entity> rest = new ArrayList<>();

        RelationClusterer(ErdModel model, ErdOptions options, int maxChars) {
            this.model = model;
            this.options = options;
            this.maxChars = maxChars;
        }

        Clusters cluster(List<Entity> leftover) {
            List<Entity> candidates = new ArrayList<>(leftover);
            candidates.sort(BY_NAME);
            candidates.forEach(e -> byId.put(e.id(), e));
            // 허브 판정은 전체 테이블 기준이다.
            for (Relation r : model.relations()) {
                if (!r.childId().equals(r.parentId())) {
                    referencedBy.computeIfAbsent(r.parentId(), k -> new TreeSet<>()).add(r.childId());
                }
            }
            List<Hub> hubs = new ArrayList<>();
            Set<String> hubIds = new HashSet<>();
            int total = model.entities().size();
            for (Entity e : candidates) {
                int count = referencedBy.getOrDefault(e.id(), Set.of()).size();
                if (count * 100 > total * HUB_PERCENT) {
                    hubs.add(new Hub(e.name(), count));
                    hubIds.add(e.id());
                }
            }
            for (Relation r : model.relations()) {
                String a = r.childId();
                String b = r.parentId();
                if (!a.equals(b) && byId.containsKey(a) && byId.containsKey(b) && !hubIds.contains(a) && !hubIds.contains(b)) {
                    adjacent.computeIfAbsent(a, k -> new TreeSet<>()).add(b);
                    adjacent.computeIfAbsent(b, k -> new TreeSet<>()).add(a);
                }
            }
            List<Entity> withoutHubs = candidates.stream().filter(e -> !hubIds.contains(e.id())).toList();
            componentsOf(withoutHubs, null).forEach(this::place);
            centers.sort(Map.Entry.comparingByKey(BY_NAME));
            List<Center> named = new ArrayList<>();
            for (Map.Entry<Entity, List<String>> center : centers) {
                named.add(new Center(center.getKey().name(), referencedBy.getOrDefault(center.getKey().id(), Set.of()).size(), center.getValue()));
            }
            return new Clusters(areas, comments, hubs, hubIds, named, rest);
        }

        /** entities(이름순) 안에서 관계로 이어진 묶음들. skipId의 테이블은 빼고 잇는다. 묶음은 이름이 앞선 테이블이 든 것부터, 묶음 안은 이름순. */
        private List<List<Entity>> componentsOf(List<Entity> entities, String skipId) {
            Set<String> inside = new HashSet<>();
            entities.forEach(e -> inside.add(e.id()));
            Set<String> seen = new HashSet<>();
            if (skipId != null) {
                seen.add(skipId);
            }
            List<List<Entity>> out = new ArrayList<>();
            for (Entity start : entities) {
                if (!seen.add(start.id())) {
                    continue;
                }
                List<Entity> component = new ArrayList<>();
                List<String> queue = new ArrayList<>(List.of(start.id()));
                for (int head = 0; head < queue.size(); head++) {
                    component.add(byId.get(queue.get(head)));
                    for (String next : adjacent.getOrDefault(queue.get(head), Set.of())) {
                        if (inside.contains(next) && seen.add(next)) {
                            queue.add(next);
                        }
                    }
                }
                component.sort(BY_NAME);
                out.add(component);
            }
            return out;
        }

        private boolean tooBig(List<Entity> entities) {
            return entities.size() > MAX_AREA_TABLES || !fits(model, entities, options, maxChars);
        }

        /** namer의 이름으로 영역을 만들고 그 이름을 돌려준다(이름이 같은 테이블이 이미 영역 이름이면 {@code -2}…를 붙인다). */
        private String addArea(Entity namer, List<Entity> entities) {
            String name = unique(namer.name(), areas);
            areas.put(name, entities);
            if (!Texts.isBlank(namer.comment())) {
                comments.put(name, namer.comment());
            }
            return name;
        }

        /** 묶음 하나를 영역으로 만들고 만든 영역의 이름을 돌려준다. */
        private List<String> place(List<Entity> component) {
            if (component.size() < MIN_AREA_SIZE) {
                rest.addAll(component);
                return List.of();
            }
            Entity center = mostReferenced(component, referencedBy).get(0);
            if (!tooBig(component)) {
                return List.of(addArea(center, component));
            }
            List<List<Entity>> subs = componentsOf(component, center.id());
            int largest = 0;
            for (List<Entity> sub : subs) {
                largest = Math.max(largest, sub.size());
            }
            List<String> made = new ArrayList<>();
            if (largest * 4 > component.size() * 3) {
                // 중심을 빼도 갈리지 않는다: 중심 여럿에 가까운 테이블끼리 나눈다.
                for (List<Entity> part : splitAroundCenters(component)) {
                    if (part.size() < MIN_AREA_SIZE) {
                        rest.addAll(part);
                    } else {
                        made.add(addArea(mostReferenced(part, referencedBy).get(0), part));
                    }
                }
                return made;
            }
            List<Entity> own = new ArrayList<>(List.of(center));
            for (List<Entity> sub : subs) {
                if (sub.size() < MIN_AREA_SIZE) {
                    own.addAll(sub);
                } else {
                    made.addAll(place(sub));
                }
            }
            own.sort(BY_NAME);
            String name = addArea(center, own);
            java.util.Collections.sort(made);
            centers.add(Map.entry(center, made));
            return List.of(name);
        }

        /**
         * 한도를 넘는 연결 요소를 중심 테이블 k개로 나눈다. 테이블은 관계를 따라 가장 가까운 중심(거리가 같으면 먼저 고른 중심)에 붙는다.
         * 첫 중심은 요소 안에서 가장 많이 참조되는 테이블이고, 다음 중심은 이미 고른 중심들에서 가장 먼 테이블이다(거리가 같으면 많이 참조되는
         * 순, 이름순). 서로 먼 테이블을 중심으로 삼아야 조각이 고르게 나온다(사슬 모양에서 이웃한 테이블들이 중심이 되면 낱개로 흩어진다).
         * 조각이 모두 한도 안에 드는(테이블 하나짜리는 그대로 둔다) 가장 작은 k의 결과를 돌려준다.
         */
        private List<List<Entity>> splitAroundCenters(List<Entity> component) {
            List<Entity> ranked = mostReferenced(component, referencedBy);
            Set<String> inside = new HashSet<>();
            component.forEach(e -> inside.add(e.id()));
            List<String> seeds = new ArrayList<>(List.of(ranked.get(0).id()));
            while (seeds.size() < ranked.size()) {
                // 다음 중심: 지금의 중심들에서 가장 먼 테이블
                Map<String, Integer> distance = new HashMap<>();
                List<String> reach = new ArrayList<>(seeds);
                seeds.forEach(id -> distance.put(id, 0));
                for (int at = 0; at < reach.size(); at++) {
                    String from = reach.get(at);
                    for (String next : adjacent.getOrDefault(from, Set.of())) {
                        if (inside.contains(next) && !distance.containsKey(next)) {
                            distance.put(next, distance.get(from) + 1);
                            reach.add(next);
                        }
                    }
                }
                String farthest = null;
                int far = 0;
                for (Entity e : ranked) {
                    int d = distance.getOrDefault(e.id(), 0);
                    if (d > far) {
                        far = d;
                        farthest = e.id();
                    }
                }
                if (farthest == null) {
                    break;
                }
                seeds.add(farthest);
                int k = seeds.size();
                Map<String, Integer> owner = new LinkedHashMap<>();
                List<String> queue = new ArrayList<>(seeds);
                for (int i = 0; i < k; i++) {
                    owner.put(seeds.get(i), i);
                }
                for (int head = 0; head < queue.size(); head++) {
                    String current = queue.get(head);
                    for (String next : adjacent.getOrDefault(current, Set.of())) {
                        if (inside.contains(next) && !owner.containsKey(next)) {
                            owner.put(next, owner.get(current));
                            queue.add(next);
                        }
                    }
                }
                List<List<Entity>> parts = new ArrayList<>();
                for (int i = 0; i < k; i++) {
                    parts.add(new ArrayList<>());
                }
                for (Entity e : component) {
                    parts.get(owner.get(e.id())).add(e);
                }
                boolean ok = true;
                for (List<Entity> part : parts) {
                    ok &= part.size() == 1 || !tooBig(part);
                }
                if (ok) {
                    return parts;
                }
            }
            List<List<Entity>> singles = new ArrayList<>();
            component.forEach(e -> singles.add(new ArrayList<>(List.of(e))));
            return singles;
        }
    }

    /** 테이블들을 그 묶음 안에서 참조되는 수가 많은 순(같으면 이름, ID 순)으로 늘어놓는다. */
    private static List<Entity> mostReferenced(List<Entity> entities, Map<String, Set<String>> referencedBy) {
        Set<String> ids = new HashSet<>();
        entities.forEach(e -> ids.add(e.id()));
        Map<String, Integer> counts = new HashMap<>();
        for (Entity e : entities) {
            int count = 0;
            for (String child : referencedBy.getOrDefault(e.id(), Set.of())) {
                count += ids.contains(child) ? 1 : 0;
            }
            counts.put(e.id(), count);
        }
        List<Entity> ranked = new ArrayList<>(entities);
        ranked.sort(Comparator.comparing((Entity e) -> -counts.get(e.id())).thenComparing(Entity::name).thenComparing(Entity::id));
        return ranked;
    }

    /** 대문자로 바꾼 이름과 함께 든 엔티티. */
    private record Keyed(Entity entity, String key) {
    }

    /**
     * 관계로 묶이지 않고 남은 테이블을 이름의 공통 접두사로 묶는다. 대문자로 바꾼 이름을 한 글자씩 따라 내려가며, 가장 긴 접두사부터 그 접두사를
     * 가진 테이블 가운데 아직 묶이지 않은 것이 {@value #MIN_AREA_SIZE}개 이상이고 접두사가 {@value #MIN_NAME_PREFIX}자 이상이면 한 영역
     * ({@code <접두사>*})으로 묶는다. 예: {@code COMTNNTWRKINFO}, {@code COMTNNTWRKSVCMNTRNG}, {@code COMTNNTWRKSVCMNTRNGLOGINFO}는
     * {@code COMTNNTWRK*}이고, {@code COMTNN}으로 시작하는 나머지가 3개 이상이면 그것들이 {@code COMTNN*}이다.
     *
     * @param clusters 결과를 넣을 맵(영역 이름 → 이름순 테이블)
     * @return 묶이지 않은 테이블
     */
    private static List<Entity> clusterByNames(List<Entity> rest, Map<String, List<Entity>> clusters) {
        List<Keyed> items = new ArrayList<>();
        for (Entity e : rest) {
            items.add(new Keyed(e, e.name().toUpperCase(Locale.ROOT)));
        }
        items.sort(Comparator.comparing(Keyed::key).thenComparing(Keyed::entity, BY_NAME));
        List<Entity> remaining = new ArrayList<>();
        if (!items.isEmpty()) {
            visitNames(items, 0, clusters).forEach(item -> remaining.add(item.entity()));
        }
        return remaining;
    }

    /** 앞 from 글자가 같은 테이블들(이름순)을 묶고, 묶이지 않은 것을 돌려준다. */
    private static List<Keyed> visitNames(List<Keyed> list, int from, Map<String, List<Entity>> clusters) {
        if (list.size() < MIN_AREA_SIZE) {
            return list;
        }
        int common = commonLength(list, from);
        List<Keyed> remaining = new ArrayList<>();
        int start = 0;
        while (start < list.size()) {
            if (list.get(start).key().length() == common) {
                remaining.add(list.get(start));
                start++;
                continue;
            }
            char c = list.get(start).key().charAt(common);
            int end = start + 1;
            while (end < list.size() && list.get(end).key().length() > common && list.get(end).key().charAt(common) == c) {
                end++;
            }
            remaining.addAll(visitNames(list.subList(start, end), common + 1, clusters));
            start = end;
        }
        if (remaining.size() >= MIN_AREA_SIZE && common >= MIN_NAME_PREFIX) {
            List<Entity> entities = new ArrayList<>();
            remaining.forEach(item -> entities.add(item.entity()));
            entities.sort(BY_NAME);
            clusters.put(list.get(0).key().substring(0, common) + "*", entities);
            return List.of();
        }
        return remaining;
    }

    /** list의 이름(대문자)이 모두 함께 갖는 접두사의 길이. 앞 from 글자는 같다고 본다. */
    private static int commonLength(List<Keyed> list, int from) {
        String first = list.get(0).key();
        for (int n = from; n < first.length(); n++) {
            char c = first.charAt(n);
            for (int i = 1; i < list.size(); i++) {
                String key = list.get(i).key();
                if (key.length() <= n || key.charAt(n) != c) {
                    return n;
                }
            }
        }
        return first.length();
    }

    /** 정렬 키: 기타는 끝. */
    private static String order(String areaName) {
        return (areaName.startsWith(ETC) ? "￿" : "") + areaName;
    }

    /** 이름을 {@code _}로 나눈 앞 tokens개 토큰(대문자). 토큰이 모자라면 있는 만큼. */
    static String prefix(String tableName, int tokens) {
        List<String> parts = new ArrayList<>();
        for (String part : tableName.toUpperCase(Locale.ROOT).split("_")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        if (parts.isEmpty()) {
            return tableName.toUpperCase(Locale.ROOT);
        }
        // 마지막 토큰까지 쓰면 접두사가 아니라 이름 전체가 된다: 토큰이 둘 이상이면 마지막 하나는 남긴다.
        int take = Math.min(tokens, Math.max(1, parts.size() - 1));
        return String.join("_", parts.subList(0, take));
    }

    private static boolean fits(ErdModel model, List<Entity> entities, ErdOptions options, int maxChars) {
        return MermaidErdRenderer.render(areaModel(model, entities), options).length() <= maxChars;
    }

    /** 한도(글자 수, 테이블 수 maxTables) 안에 드는 가장 적은 조각 수로 이름순으로 자른다. */
    private static List<List<Entity>> chunk(ErdModel model, List<Entity> entities, ErdOptions options, int maxChars, int maxTables) {
        for (int parts = 1; parts <= entities.size(); parts++) {
            List<List<Entity>> chunks = new ArrayList<>();
            int size = (entities.size() + parts - 1) / parts;
            boolean ok = true;
            for (int from = 0; from < entities.size() && ok; from += size) {
                List<Entity> chunk = entities.subList(from, Math.min(entities.size(), from + size));
                ok = chunk.size() == 1 || (chunk.size() <= maxTables && fits(model, chunk, options, maxChars));
                chunks.add(new ArrayList<>(chunk));
            }
            if (ok) {
                return chunks;
            }
        }
        return List.of(entities);
    }

    /** 영역의 엔티티 + 관계로 이어진 밖의 엔티티(컬럼 없이 이름만) + 한쪽 끝이라도 영역 안인 관계. */
    private static ErdModel areaModel(ErdModel model, List<Entity> inside) {
        Set<String> ids = new TreeSet<>();
        inside.forEach(e -> ids.add(e.id()));
        Set<String> neighbors = new TreeSet<>();
        List<Relation> relations = new ArrayList<>();
        for (Relation r : model.relations()) {
            boolean child = ids.contains(r.childId());
            boolean parent = ids.contains(r.parentId());
            if (child || parent) {
                relations.add(r);
                if (!child) {
                    neighbors.add(r.childId());
                }
                if (!parent) {
                    neighbors.add(r.parentId());
                }
            }
        }
        List<Entity> entities = new ArrayList<>(inside);
        for (Entity e : model.entities()) {
            if (neighbors.contains(e.id())) {
                entities.add(new Entity(e.id(), e.name(), e.kind(), e.declared(), e.schema(), null, List.of(), e.totalColumns()));
            }
        }
        entities.sort(java.util.Comparator.comparing(Entity::name).thenComparing(Entity::id));
        return new ErdModel(entities, relations);
    }
}
