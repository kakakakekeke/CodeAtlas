package io.codeatlas.springxml;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.Dialect;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.springxml.BatchDefs.Component;
import io.codeatlas.springxml.BatchDefs.FlowDef;
import io.codeatlas.springxml.BatchDefs.JobDef;
import io.codeatlas.springxml.BatchDefs.StepDef;
import io.codeatlas.springxml.BatchDefs.Transition;
import io.codeatlas.springxml.BeanDef.Ref;
import io.codeatlas.springxml.BeanGraphWriter.Target;
import io.codeatlas.springxml.BeanRegistry.EffProp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 배치 잡·스텝 정의를 그래프에 쓴다: BATCH_JOB, BATCH_STEP, CONTAINS, NEXT, USES, EXECUTES. */
final class BatchGraphWriter {

    /** 상속을 병합한 구성 요소. inheritedFrom은 그것을 정의한 부모 스텝(잡) 이름, 자기 것이면 null. */
    private record EffComponent(Component component, String inheritedFrom) {
    }

    private static final class EffStep {
        final Map<String, EffComponent> components = new LinkedHashMap<>();
        final List<EffComponent> listeners = new ArrayList<>();
        String commitInterval;
        String type = "step";
    }

    private final AnalysisContext ctx;
    private final GraphModel graph;
    private final BeanRegistry registry;
    private final BeanGraphWriter beans;
    private final Map<String, StepDef> standaloneSteps = new HashMap<>();
    private final Map<String, FlowDef> flows = new HashMap<>();
    private final Map<String, JobDef> jobs = new HashMap<>();
    /** 이미 노드와 내장 소스를 등록한 인라인 SQL 구문 ID. */
    private final Set<String> declaredStatements = new HashSet<>();

    BatchGraphWriter(AnalysisContext ctx, BeanRegistry registry, BeanGraphWriter beans, List<FileModel> models) {
        this.ctx = ctx;
        this.graph = ctx.graph();
        this.registry = registry;
        this.beans = beans;
        for (FileModel model : models) {
            for (StepDef step : model.standaloneSteps) {
                standaloneSteps.putIfAbsent(step.id, step);
            }
            for (FlowDef flow : model.flows) {
                if (flow.id != null) {
                    flows.putIfAbsent(flow.id, flow);
                }
            }
            for (JobDef job : model.jobs) {
                if (job.id != null) {
                    jobs.putIfAbsent(job.id, job);
                }
            }
        }
    }

    void write(FileModel model) {
        for (JobDef job : model.jobs) {
            if (job.id != null) {
                writeJob(job);
            }
        }
        for (StepDef step : model.standaloneSteps) {
            writeStep(step);
        }
        for (FlowDef flow : model.flows) {
            for (StepDef step : flow.steps) {
                writeStep(step);
            }
        }
    }

    // ---- 잡 ----

    private void writeJob(JobDef job) {
        String jobId = Ids.batchJob(job.id);
        Node.Builder node = Node.builder(jobId, NodeKind.BATCH_JOB).name(job.id).qualifiedName(job.id).location(job.location);
        if (job.abstractJob) {
            node.attr("abstract", true);
        }
        node.attr("parent", job.parent);
        node.attr("restartable", job.restartable);
        node.attr("incrementer", job.incrementer);
        graph.declare(node.build());
        graph.addEdge(Edge.of(Ids.file(job.file), jobId, EdgeKind.CONTAINS, job.location));

        for (EffComponent listener : effectiveJobListeners(job, new HashSet<>())) {
            writeUses(jobId, listener, job.file);
        }
        for (StepDef step : job.steps) {
            writeStep(step);
        }
    }

    private List<EffComponent> effectiveJobListeners(JobDef job, Set<JobDef> visited) {
        List<EffComponent> result = new ArrayList<>();
        visited.add(job);
        JobDef parent = job.parent == null ? null : jobs.get(job.parent);
        if (parent != null && !visited.contains(parent) && (!job.hasListeners || job.mergeListeners)) {
            for (EffComponent inherited : effectiveJobListeners(parent, visited)) {
                result.add(new EffComponent(inherited.component(), inherited.inheritedFrom() != null ? inherited.inheritedFrom() : parent.id));
            }
        }
        for (Component own : job.listeners) {
            result.add(new EffComponent(own, null));
        }
        return result;
    }

    // ---- 스텝 ----

    private EffStep effective(StepDef step, Set<StepDef> visited) {
        EffStep result = new EffStep();
        visited.add(step);
        StepDef parent = step.parent == null ? null : standaloneSteps.get(step.parent);
        if (parent != null && !visited.contains(parent)) {
            EffStep inherited = effective(parent, visited);
            inherited.components.forEach((role, c) ->
                    result.components.put(role, new EffComponent(c.component(), c.inheritedFrom() != null ? c.inheritedFrom() : parent.id)));
            if (!step.hasListeners || step.mergeListeners) {
                for (EffComponent c : inherited.listeners) {
                    result.listeners.add(new EffComponent(c.component(), c.inheritedFrom() != null ? c.inheritedFrom() : parent.id));
                }
            }
            result.commitInterval = inherited.commitInterval;
            result.type = inherited.type;
        }
        step.components.forEach((role, c) -> result.components.put(role, new EffComponent(c, null)));
        for (Component own : step.listeners) {
            result.listeners.add(new EffComponent(own, null));
        }
        if (step.commitInterval != null) {
            result.commitInterval = step.commitInterval;
        }
        if (!step.type.equals("step")) {
            result.type = step.type;
        }
        return result;
    }

    private void writeStep(StepDef step) {
        String stepId = Ids.batchStep(step.job, step.id);
        EffStep effective = effective(step, new HashSet<>());

        Node.Builder node = Node.builder(stepId, NodeKind.BATCH_STEP).name(step.id)
                .qualifiedName((step.job == null ? Ids.UNKNOWN_JOB : step.job) + "/" + step.id).location(step.location);
        node.attr("stepType", effective.type);
        if (step.abstractStep) {
            node.attr("abstract", true);
        }
        node.attr("parent", step.parent);
        node.attr("commitInterval", effective.commitInterval);
        node.attr("flow", step.flow);
        node.attr("flowRef", step.flowRef);
        node.attr("jobRef", step.jobRef);
        putTransitionAttr(node, step, "end", "endOn");
        putTransitionAttr(node, step, "fail", "failOn");
        putTransitionAttr(node, step, "stop", "stopOn");
        graph.declare(node.build());

        if (step.job != null) {
            graph.addEdge(Edge.of(Ids.batchJob(step.job), stepId, EdgeKind.CONTAINS, step.location));
        } else {
            graph.addEdge(Edge.of(Ids.file(step.file), stepId, EdgeKind.CONTAINS, step.location));
        }

        for (EffComponent component : effective.components.values()) {
            writeUses(stepId, component, step.file);
            writeExecutes(stepId, component, step.file);
        }
        for (EffComponent listener : effective.listeners) {
            writeUses(stepId, listener, step.file);
        }
        writeTransitions(step, stepId);
    }

    private static void putTransitionAttr(Node.Builder node, StepDef step, String kind, String attrKey) {
        List<String> on = new ArrayList<>();
        for (Transition t : step.transitions) {
            if (t.kind().equals(kind)) {
                on.add(t.on() == null ? "*" : t.on());
            }
        }
        if (!on.isEmpty()) {
            node.attr(attrKey, on);
        }
    }

    private void writeTransitions(StepDef step, String stepId) {
        if (step.next != null) {
            graph.addEdge(Edge.of(stepId, Ids.batchStep(step.job, step.next), EdgeKind.NEXT, step.location));
        }
        for (Transition t : step.transitions) {
            if (t.to() == null) {
                continue;   // end, fail: 도착 스텝이 없다. 스텝 attrs에 남겼다
            }
            Edge.Builder edge = Edge.builder(stepId, Ids.batchStep(step.job, t.to()), EdgeKind.NEXT).location(t.location());
            edge.attr("on", t.on());
            if (t.kind().equals("stop")) {
                edge.attr("transition", "stop");
            }
            graph.addEdge(edge.build());
        }
        // split: 각 흐름의 첫 스텝으로
        for (String entry : step.entrySteps) {
            graph.addEdge(Edge.builder(stepId, Ids.batchStep(step.job, entry), EdgeKind.NEXT)
                    .location(step.location).attr("transition", "split").build());
        }
        for (String flowRef : step.entryFlowRefs) {
            writeFlowEntry(stepId, flowRef, "split", step.location);
        }
        // 흐름 참조: 잡 밖에 정의된 흐름의 첫 스텝으로
        if (step.flowRef != null) {
            writeFlowEntry(stepId, step.flowRef, "flow", step.location);
        }
        // 파티션: 실제로 도는 스텝으로
        if (step.partitionStep != null) {
            String target = standaloneSteps.containsKey(step.partitionStep)
                    ? Ids.batchStep(null, step.partitionStep) : Ids.batchStep(step.job, step.partitionStep);
            graph.addEdge(Edge.builder(stepId, target, EdgeKind.NEXT).location(step.location).attr("transition", "partition").build());
        }
    }

    private void writeFlowEntry(String stepId, String flowRef, String transition, SourceLocation location) {
        FlowDef flow = flows.get(flowRef);
        if (flow != null && flow.firstStep != null) {
            graph.addEdge(Edge.builder(stepId, Ids.batchStep(null, flow.firstStep), EdgeKind.NEXT)
                    .location(location).attr("transition", transition).build());
        }
    }

    // ---- USES ----

    private Target targetOf(Component component, String file) {
        if (component.className != null) {
            return new Target(beans.referenceType(component.className), true, null, null);
        }
        if (component.inner != null) {
            return beans.target(Ref.inner(component.inner, component.location));
        }
        return beans.target(component.ref, file);
    }

    private void writeUses(String ownerId, EffComponent effective, String file) {
        Component component = effective.component();
        if (component.ref != null && !BeanRegistry.isLiteral(component.ref)) {
            graph.addUnresolvedEdge(ownerId, EdgeKind.USES, "spring-bean-ref", component.ref, component.location);
            return;
        }
        Target target = targetOf(component, file);
        Edge.Builder edge = Edge.builder(ownerId, target.id(), EdgeKind.USES).location(component.location);
        edge.attr("role", component.role);
        edge.attr("bean", component.ref);
        edge.attr("inheritedFrom", effective.inheritedFrom());
        graph.addEdge(edge.build());
    }

    // ---- EXECUTES ----

    private void writeExecutes(String stepId, EffComponent effective, String file) {
        Component component = effective.component();
        BeanDef def;
        if (component.inner != null) {
            def = component.inner;
        } else if (component.ref != null && BeanRegistry.isLiteral(component.ref)) {
            def = registry.resolve(component.ref, file).def();
        } else {
            return;
        }
        writeExecutes(stepId, component.role, def, new HashSet<>());
    }

    /** 리더·라이터 빈이 실행하는 SQL을 찾는다. 위임 빈은 delegate·delegates를 따라간다. */
    private void writeExecutes(String stepId, String role, BeanDef def, Set<BeanDef> visited) {
        if (def == null || !visited.add(def)) {
            return;
        }
        Map<String, EffProp> props = registry.effectiveProps(def);
        String type = registry.effectiveClass(def);
        if (type != null) {
            switch (BeanRegistry.simpleName(type)) {
                case "MyBatisPagingItemReader", "MyBatisCursorItemReader" -> executesStatement(stepId, role, def, props.get("queryId"));
                case "MyBatisBatchItemWriter" -> executesStatement(stepId, role, def, props.get("statementId"));
                case "JdbcCursorItemReader", "JdbcBatchItemWriter" -> executesSql(stepId, role, def, props.get("sql"), null);
                case "JdbcPagingItemReader" -> executesPagingQuery(stepId, role, def, props);
                case "StoredProcedureItemReader" -> executesRoutine(stepId, role, def, props.get("procedureName"));
                default -> {
                    // SQL을 직접 가진 빈이 아니다
                }
            }
        }
        for (String key : List.of("delegate", "delegates")) {
            EffProp delegates = props.get(key);
            if (delegates != null) {
                for (Ref ref : delegates.prop().refs) {
                    writeExecutes(stepId, role, registry.definitionOf(ref), visited);
                }
            }
        }
    }

    private static String scalar(EffProp effective) {
        if (effective == null || effective.prop().scalar == null) {
            return null;
        }
        String value = effective.prop().scalar.strip();
        return value.isEmpty() ? null : value;
    }

    private void executesStatement(String stepId, String role, BeanDef def, EffProp effective) {
        String value = scalar(effective);
        if (value == null) {
            return;
        }
        SourceLocation location = effective.prop().location;
        int dot = value.lastIndexOf('.');
        if (!BeanRegistry.isLiteral(value) || dot <= 0 || dot == value.length() - 1) {
            // 네임스페이스를 알 수 없는 구문 ID는 추측하지 않는다
            graph.addUnresolvedEdge(stepId, EdgeKind.EXECUTES, "mybatis-statement", value, location);
            return;
        }
        String statementId = Ids.mybatisStatement(value.substring(0, dot), value.substring(dot + 1));
        graph.addEdge(Edge.builder(stepId, statementId, EdgeKind.EXECUTES).location(location)
                .attr("role", role).attr("bean", def.name).build());
    }

    private void executesSql(String stepId, String role, BeanDef def, EffProp effective, String composedSql) {
        String sql = composedSql != null ? composedSql : scalar(effective);
        if (sql == null || effective == null) {
            return;
        }
        SourceLocation location = effective.prop().location;
        String statementId = Ids.sqlStatement(location.file(), location.startLine());
        if (declaredStatements.add(statementId)) {
            Node.Builder node = Node.builder(statementId, NodeKind.SQL_STATEMENT).name(def.name + ".sql")
                    .qualifiedName(location.file() + ":" + location.startLine()).location(location);
            node.attr("sql", sql);
            node.attr("bean", def.name);
            if (composedSql != null) {
                node.attr("composed", true);
            }
            if (!BeanRegistry.isLiteral(sql)) {
                node.attr("dynamic", true);
            }
            graph.declare(node.build());
            // 속성값과 CDATA를 거친 본문이라 호스트 파일의 연속 구간이 아니다(exactPositions=false)
            ctx.addEmbedded(EmbeddedSource.sql(statementId, sql, location, false));
        }
        graph.addEdge(Edge.builder(stepId, statementId, EdgeKind.EXECUTES).location(location)
                .attr("role", role).attr("bean", def.name).build());
    }

    /** JdbcPagingItemReader: queryProvider(SqlPagingQueryProviderFactoryBean 등)의 절을 Spring과 같은 방식으로 잇는다. */
    private void executesPagingQuery(String stepId, String role, BeanDef def, Map<String, EffProp> props) {
        EffProp provider = props.get("queryProvider");
        if (provider == null) {
            return;
        }
        for (Ref ref : provider.prop().refs) {
            BeanDef providerDef = registry.definitionOf(ref);
            if (providerDef == null) {
                continue;
            }
            Map<String, EffProp> clauses = registry.effectiveProps(providerDef);
            String select = scalar(clauses.get("selectClause"));
            String from = scalar(clauses.get("fromClause"));
            if (select == null || from == null) {
                continue;
            }
            StringBuilder sql = new StringBuilder(withKeyword("SELECT", select)).append(' ').append(withKeyword("FROM", from));
            String where = scalar(clauses.get("whereClause"));
            if (where != null) {
                sql.append(' ').append(withKeyword("WHERE", where));
            }
            String group = scalar(clauses.get("groupClause"));
            if (group != null) {
                sql.append(' ').append(withKeyword("GROUP BY", group));
            }
            executesSql(stepId, role, def, clauses.get("selectClause"), sql.toString().replaceAll("\\s+", " "));
        }
    }

    private static String withKeyword(String keyword, String clause) {
        return clause.regionMatches(true, 0, keyword, 0, keyword.length()) ? clause : keyword + " " + clause;
    }

    private void executesRoutine(String stepId, String role, BeanDef def, EffProp effective) {
        String value = scalar(effective);
        if (value == null) {
            return;
        }
        SourceLocation location = effective.prop().location;
        if (!BeanRegistry.isLiteral(value)) {
            graph.addUnresolvedEdge(stepId, EdgeKind.EXECUTES, "db-routine", value, location);
            return;
        }
        String[] parts = value.split("\\.");
        String routineId;
        Dialect dialect = ctx.options().dialect();
        if (parts.length == 1 || dialect == Dialect.DB2 || dialect == Dialect.POSTGRES) {
            // DB2와 PostgreSQL에서 A.B는 스키마.프로시저다(패키지가 없다. F-019-R33)
            routineId = Ids.routine(value);
        } else {
            // A.B는 패키지.프로시저로, S.A.B는 스키마를 뺀 패키지.프로시저로 본다
            routineId = Ids.routine(parts[parts.length - 2], parts[parts.length - 1]);
        }
        graph.reference(routineId, NodeKind.DB_ROUTINE, Ids.remainder(routineId));
        graph.addEdge(Edge.builder(stepId, routineId, EdgeKind.EXECUTES).location(location)
                .attr("role", role).attr("bean", def.name).attr("procedureName", value).build());
    }
}
