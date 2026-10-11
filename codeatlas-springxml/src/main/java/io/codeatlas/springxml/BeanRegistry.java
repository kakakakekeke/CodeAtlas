package io.codeatlas.springxml;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.springxml.BeanDef.Prop;
import io.codeatlas.springxml.BeanDef.Ref;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 프로젝트의 모든 Spring XML에서 읽은 빈 정의를 이름으로 찾는다.
 * 컨텍스트 계층을 구분하지 않고 전체를 하나의 이름 공간으로 본다(spec 가정).
 */
final class BeanRegistry {

    /** 이름으로 빈을 찾은 결과. */
    record Resolved(String canonicalName, BeanDef def, String type) {
    }

    /** 상속을 병합한 프로퍼티 하나. inheritedFrom은 그 프로퍼티를 정의한 조상 빈 이름(자기 것이면 null). */
    record EffProp(Prop prop, String inheritedFrom) {
    }

    private final Map<String, List<BeanDef>> byName = new LinkedHashMap<>();
    /** 별칭 → 원래 이름. name 속성의 나머지 이름과 {@code <alias>}. */
    private final Map<String, String> aliasToName = new HashMap<>();
    private final List<BeanDef> all = new ArrayList<>();
    private final Map<String, Integer> counters = new HashMap<>();
    private final Map<BeanDef, String> classCache = new IdentityHashMap<>();
    /** effectiveClass를 계산하는 중인 빈(parent 순환 감지). 분석기는 한 스레드에서 돈다. */
    private final Set<BeanDef> resolvingClass = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<BeanDef> resolvingExposed = Collections.newSetFromMap(new IdentityHashMap<>());

    private final ModulePaths modules;

    BeanRegistry(List<FileModel> models, GraphModel graph, ModulePaths modules) {
        this.modules = modules;
        for (FileModel model : models) {
            for (BeanDef def : model.beans) {
                register(def);
            }
            for (BeanDef inner : model.detachedInnerBeans) {
                register(inner);
            }
        }
        for (FileModel model : models) {
            for (String[] alias : model.aliases) {
                aliasToName.putIfAbsent(alias[1], alias[0]);
            }
        }
        reportDuplicates(graph);
    }

    private void register(BeanDef def) {
        if (def.inner) {
            def.name = nameBase(def) + "#inner@" + def.file + ":" + def.location.startLine();
        } else {
            if (def.name == null) {
                String base = nameBase(def);
                int n = counters.merge(base, 1, Integer::sum) - 1;
                def.name = base + "#" + n;
            }
            byName.computeIfAbsent(def.name, k -> new ArrayList<>()).add(def);
            for (String alias : def.aliases) {
                aliasToName.putIfAbsent(alias, def.name);
            }
        }
        all.add(def);
        for (BeanDef inner : def.innerBeans) {
            register(inner);
        }
    }

    /** Spring의 이름 생성 규칙: 클래스명, 없으면 {@code <parent>$child}, {@code <factoryBean>$created}. */
    private static String nameBase(BeanDef def) {
        if (def.generatedNameBase != null) {
            return def.generatedNameBase;
        }
        if (def.className != null) {
            return def.className;
        }
        if (def.parent != null) {
            return def.parent + "$child";
        }
        if (def.factoryBean != null) {
            return def.factoryBean + "$created";
        }
        return "(unnamed)";
    }

    /**
     * 같은 이름이 같은 범위(모듈 루트 + main·test) 안에서 여러 번 정의된 것만 알린다(F-017-R13).
     * 다른 웹 모듈의 설정이나 테스트 설정에 같은 이름이 있는 것은 멀티모듈 프로젝트에서 정상이다.
     */
    private void reportDuplicates(GraphModel graph) {
        for (Map.Entry<String, List<BeanDef>> entry : byName.entrySet()) {
            List<BeanDef> defs = entry.getValue();
            if (defs.size() < 2) {
                continue;
            }
            Map<String, List<BeanDef>> byScope = new LinkedHashMap<>();
            for (BeanDef def : defs) {
                byScope.computeIfAbsent(modules.scopeOf(def.file), k -> new ArrayList<>()).add(def);
            }
            List<BeanDef> clashing = new ArrayList<>();
            for (List<BeanDef> sameScope : byScope.values()) {
                if (sameScope.size() > 1) {
                    clashing.addAll(sameScope);
                }
            }
            if (clashing.isEmpty()) {
                continue;
            }
            StringBuilder where = new StringBuilder();
            boolean allProfiled = true;
            for (BeanDef def : clashing) {
                where.append(where.isEmpty() ? "" : ", ").append(def.file).append(':').append(def.location.startLine());
                allProfiled &= def.profile != null;
            }
            String message = "같은 이름의 빈이 여러 번 정의됨: " + entry.getKey() + " (" + where + ")";
            // 프로파일로 나뉜 정의는 흔하고 정상이다
            graph.report(allProfiled
                    ? Diagnostic.info(DiagnosticCodes.DUPLICATE_OBJECT_NAME, message, clashing.get(1).location)
                    : Diagnostic.warn(DiagnosticCodes.DUPLICATE_OBJECT_NAME, message, clashing.get(1).location));
        }
    }

    /**
     * 이름이 같은 정의들(내부 빈 제외). 노드의 attrs를 정하는 순서다: main 쪽이 테스트 설정보다 먼저, 그 안에서는 파일 경로순·문서 순.
     * 첫 정의가 {@code spring:bean:<이름>} 노드의 attrs와 위치를 정하고 나머지는 {@code otherDefinitions}로만 남는다.
     */
    List<BeanDef> definitionsOf(String name) {
        List<BeanDef> defs = byName.get(name);
        if (defs == null || defs.size() < 2) {
            return defs == null ? List.of() : defs;
        }
        List<BeanDef> sorted = new ArrayList<>(defs);
        // 안정 정렬: 같은 파일의 정의는 문서 순 그대로다
        sorted.sort(java.util.Comparator.comparing((BeanDef d) -> ModulePaths.isTestPath(d.file)).thenComparing(d -> d.file));
        return sorted;
    }

    /** 내부 빈까지 포함한 모든 빈 정의(파일 순, 문서 순). */
    List<BeanDef> all() {
        return all;
    }

    /** 이 빈을 가리키는 별칭들(name 속성의 나머지 이름과 {@code <alias>}). */
    List<String> aliasesOf(BeanDef def) {
        Set<String> aliases = new LinkedHashSet<>(def.aliases);
        for (String alias : aliasToName.keySet()) {
            if (!byName.containsKey(alias) && canonical(alias).equals(def.name)) {
                aliases.add(alias);
            }
        }
        return List.copyOf(aliases);
    }

    private String canonical(String name) {
        String current = name;
        for (int i = 0; i < 20 && !byName.containsKey(current) && aliasToName.containsKey(current); i++) {
            current = aliasToName.get(current);
        }
        return current;
    }

    /**
     * 이름(또는 별칭)으로 빈을 찾는다. 같은 이름이 여러 번 정의됐으면 참조한 파일의 정의를 먼저 쓴다.
     * 그것이 없으면 참조한 쪽에서 보이는 정의로 좁힌다(F-017-R13): 테스트 설정의 정의는 테스트 설정에서만 후보이고,
     * 참조한 파일과 같은 모듈의 정의가 있으면 그것만 본다. 그러고도 정의들의 타입이 서로 다르면 고르지 않는다(def=null).
     */
    Resolved resolve(String refName, String fromFile) {
        String name = canonical(refName);
        BeanDef def = pick(name, fromFile);
        return new Resolved(name, def, def == null ? null : exposedType(def));
    }

    private BeanDef pick(String canonicalName, String fromFile) {
        List<BeanDef> defs = byName.get(canonicalName);
        if (defs == null) {
            return null;
        }
        if (defs.size() == 1) {
            return defs.get(0);
        }
        for (BeanDef def : defs) {
            if (def.file.equals(fromFile)) {
                return def;
            }
        }
        List<BeanDef> candidates = defs;
        if (fromFile != null) {
            if (!ModulePaths.isTestPath(fromFile)) {
                List<BeanDef> main = defs.stream().filter(d -> !ModulePaths.isTestPath(d.file)).toList();
                candidates = main.isEmpty() ? defs : main;
            }
            String module = modules.rootOf(fromFile);
            List<BeanDef> sameModule = module == null ? List.of()
                    : candidates.stream().filter(d -> module.equals(modules.rootOf(d.file))).toList();
            if (!sameModule.isEmpty()) {
                candidates = sameModule;
            }
        }
        Set<String> types = new HashSet<>();
        for (BeanDef def : candidates) {
            types.add(String.valueOf(exposedType(def)));
        }
        return types.size() > 1 ? null : candidates.get(0);
    }

    /** parent 속성이 가리키는 빈 정의. 타입 계산을 거치지 않는다(parent 순환에서도 끝나도록). */
    BeanDef parentOf(BeanDef def) {
        if (def.parent == null) {
            return null;
        }
        BeanDef parent = pick(canonical(def.parent), def.file);
        return parent == def ? null : parent;
    }

    /** 빈의 구현 클래스(자기 class, 없으면 부모의 class). 플레이스홀더이거나 알 수 없으면 null. */
    String effectiveClass(BeanDef def) {
        if (classCache.containsKey(def)) {
            return classCache.get(def);
        }
        if (!resolvingClass.add(def)) {
            return null;   // parent가 돌아서 자기에게 왔다
        }
        String result;
        try {
            if (def.className != null) {
                result = isLiteral(def.className) ? def.className : null;
            } else if (def.factoryBean != null || def.isUtilCollection()) {
                result = null;
            } else {
                BeanDef parent = parentOf(def);
                result = parent == null ? null : effectiveClass(parent);
            }
        } finally {
            resolvingClass.remove(def);
        }
        if (resolvingClass.isEmpty()) {
            // 순환을 끊느라 중간에 낸 null은 남기지 않고, 맨 바깥 호출의 결과만 기억한다
            classCache.put(def, result);
        }
        return result;
    }

    /**
     * 다른 빈이 이 빈을 주입받을 때 보는 타입. 보통은 구현 클래스이고,
     * MapperFactoryBean은 mapperInterface, util 컬렉션과 factory-bean 빈은 알 수 없음(null)이다.
     */
    String exposedType(BeanDef def) {
        if (def.isUtilCollection() || def.factoryBean != null) {
            return null;
        }
        if (!resolvingExposed.add(def)) {
            return null;   // 순환
        }
        try {
            String type = effectiveClass(def);
            if (type != null && simpleName(type).equals("MapperFactoryBean")) {
                String mapperInterface = mapperInterface(def);
                if (mapperInterface != null) {
                    return mapperInterface;
                }
            }
            return type;
        } finally {
            resolvingExposed.remove(def);
        }
    }

    /** MapperFactoryBean의 mapperInterface 값. 없거나 플레이스홀더면 null. */
    String mapperInterface(BeanDef def) {
        EffProp prop = effectiveProps(def).get("mapperInterface");
        if (prop != null && prop.prop().scalar != null && isLiteral(prop.prop().scalar)) {
            String value = prop.prop().scalar.strip();
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    String effectiveScope(BeanDef def) {
        Set<BeanDef> visited = new HashSet<>();
        for (BeanDef current = def; current != null && visited.add(current); current = parentOf(current)) {
            if (current.scope != null) {
                return current.scope;
            }
        }
        return "singleton";
    }

    /** 부모 체인의 프로퍼티를 병합한다. 같은 키는 자식 것이 이긴다. */
    Map<String, EffProp> effectiveProps(BeanDef def) {
        List<BeanDef> chain = new ArrayList<>();
        Set<BeanDef> visited = new HashSet<>();
        for (BeanDef current = def; current != null && visited.add(current); current = parentOf(current)) {
            chain.add(current);
        }
        Map<String, EffProp> merged = new LinkedHashMap<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            BeanDef owner = chain.get(i);
            for (Prop prop : owner.props.values()) {
                merged.remove(prop.key);
                merged.put(prop.key, new EffProp(prop, owner == def ? null : owner.name));
            }
        }
        return merged;
    }

    /** 참조가 가리키는 빈 정의. 내부 빈이면 그것, 이름이면 XML에서 찾은 것(없으면 null). */
    BeanDef definitionOf(Ref ref) {
        if (ref.inner != null) {
            return ref.inner;
        }
        if (ref.beanName == null || !isLiteral(ref.beanName)) {
            return null;
        }
        return resolve(ref.beanName, ref.location == null ? null : ref.location.file()).def();
    }

    /** {@code ${...}}나 {@code #{...}}가 없는 값인가. */
    static boolean isLiteral(String value) {
        return value != null && !value.contains("${") && !value.contains("#{");
    }

    static String simpleName(String fqn) {
        Objects.requireNonNull(fqn);
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
