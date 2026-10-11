package io.codeatlas.java;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F-005 마무리. 모은 잡·스텝 정의를 이어 BATCH_JOB, BATCH_STEP, CONTAINS, NEXT, USES, EXECUTES를 만든다.
 * 스텝 ID에는 잡 이름이 들어가므로 잡이 가리키는 스텝을 먼저 풀고, 어느 잡에도 속하지 않은 스텝은 {@code batch:step:?/<이름>}으로 만든다.
 */
final class BatchLinker {

    static final String CAT_STEP = "batch-step";
    static final String CAT_COMPONENT = "batch-component";

    private final JavaProject project;
    private final GraphModel graph;
    private final BatchModel model;
    private final Map<String, List<BatchModel.StepDef>> stepsByMethod = new HashMap<>();
    private final Set<String> emittedSteps = new HashSet<>();
    /** 스텝에 연결된 MyBatis 리더·라이터 메서드. */
    private final Set<String> linkedMethods = new HashSet<>();

    BatchLinker(JavaProject project) {
        this.project = project;
        this.graph = project.graph;
        this.model = project.batch;
    }

    void run() {
        for (BatchModel.StepDef step : model.steps) {
            if (step.typeName != null && step.methodName != null) {
                stepsByMethod.computeIfAbsent(step.typeName + "#" + step.methodName, k -> new ArrayList<>()).add(step);
            }
        }
        Set<BatchModel.StepDef> used = new HashSet<>();
        for (BatchModel.JobDef job : model.jobs) {
            String jobId = Ids.batchJob(job.name);
            Node.Builder b = Node.builder(jobId, NodeKind.BATCH_JOB).name(job.name).qualifiedName(job.name).location(job.location);
            if (job.methodId != null) {
                b.attr("definedBy", job.methodId);
            }
            graph.declare(b.build());
            Map<BatchModel.StepRef, String> stepIds = new IdentityHashMap<>();
            Set<String> contained = new HashSet<>();
            for (BatchModel.StepRef ref : job.steps) {
                BatchModel.StepDef def = resolve(ref);
                if (def == null) {
                    graph.addUnresolvedEdge(jobId, EdgeKind.CONTAINS, CAT_STEP, ref.text, ref.location);
                    continue;
                }
                used.add(def);
                String stepId = step(job.name, def);
                stepIds.put(ref, stepId);
                if (contained.add(stepId)) {
                    graph.addEdge(Edge.of(jobId, stepId, EdgeKind.CONTAINS, ref.location));
                }
            }
            for (BatchModel.Next next : job.nexts) {
                String from = stepIds.get(next.from());
                String to = stepIds.get(next.to());
                if (from != null && to != null) {
                    Edge.Builder edge = Edge.builder(from, to, EdgeKind.NEXT).location(next.location());
                    if (next.on() != null) {
                        edge.attr("on", next.on());
                    }
                    graph.addEdge(edge.build());
                }
            }
        }
        for (BatchModel.StepDef def : model.steps) {
            if (!used.contains(def)) {
                step(null, def);
            }
        }
        // 스텝과 이어지지 않은 리더·라이터의 구문은 그 메서드에서 실행하는 것으로 남긴다. 상수가 아닌 ID는 항상 메서드에 미해결로 남긴다
        model.statements.forEach((methodId, statements) -> {
            for (BatchModel.Statement statement : statements) {
                if (statement.value() == null || !linkedMethods.contains(methodId)) {
                    SpringExtractor.addExecutes(graph, methodId, statement.value(), statement.text(), statement.location(), null);
                }
            }
        });
    }

    private BatchModel.StepDef resolve(BatchModel.StepRef ref) {
        if (ref.inline != null) {
            return ref.inline;
        }
        if (ref.methodName != null) {
            return single(stepsByMethod.get(ref.typeName + "#" + ref.methodName));
        }
        if (ref.beanName != null) {
            for (SpringModel.BeanMethod bean : project.spring.beanMethods) {
                if (bean.beanName().equals(ref.beanName)) {
                    BatchModel.StepDef def = single(stepsByMethod.get(bean.typeName() + "#" + bean.methodName()));
                    if (def != null) {
                        return def;
                    }
                }
            }
            return single(model.steps.stream().filter(s -> s.name.equals(ref.beanName)).toList());
        }
        return null;
    }

    private static BatchModel.StepDef single(List<BatchModel.StepDef> defs) {
        return defs != null && defs.size() == 1 ? defs.get(0) : null;
    }

    /** 스텝 노드와 USES, EXECUTES를 만든다(잡마다 한 번). */
    private String step(String jobName, BatchModel.StepDef def) {
        String stepId = Ids.batchStep(jobName, def.name);
        if (!emittedSteps.add(stepId)) {
            return stepId;
        }
        Node.Builder b = Node.builder(stepId, NodeKind.BATCH_STEP).name(def.name)
                .qualifiedName((jobName == null ? Ids.UNKNOWN_JOB : jobName) + "/" + def.name).location(def.location);
        if (def.methodId != null) {
            b.attr("definedBy", def.methodId);
        }
        if (def.stepType != null) {
            b.attr("stepType", def.stepType);
        }
        if (def.chunkSize != null) {
            b.attr("chunkSize", def.chunkSize);
        }
        graph.declare(b.build());

        for (BatchModel.Use use : def.uses) {
            String target = target(use);
            if (target == null) {
                project.unresolvedEdge(stepId, EdgeKind.USES, CAT_COMPONENT, use.text, use.location, Map.of("role", use.role));
                continue;
            }
            Edge.Builder edge = Edge.builder(stepId, target, EdgeKind.USES).location(use.location).attr("role", use.role);
            if (use.lambda) {
                edge.attr("lambda", true);
            }
            graph.addEdge(edge.build());
            executes(stepId, target);
        }
        // 스텝을 만드는 메서드 안에서 직접 구성한 리더·라이터
        if (def.methodId != null && def.typeName != null && def.methodName != null
                && single(stepsByMethod.get(def.typeName + "#" + def.methodName)) == def) {
            executes(stepId, def.methodId);
        }
        return stepId;
    }

    private void executes(String stepId, String methodId) {
        List<BatchModel.Statement> statements = model.statements.get(methodId);
        if (statements == null) {
            return;
        }
        linkedMethods.add(methodId);
        for (BatchModel.Statement statement : statements) {
            if (statement.value() != null) {
                SpringExtractor.addExecutes(graph, stepId, statement.value(), statement.text(), statement.location(), null);
            }
        }
    }

    /** 구성 요소의 대상 노드 ID. 빈 이름으로 찾을 때는 @Bean 메서드 → 스테레오타입 빈의 타입 → 선언 타입 순이다. */
    private String target(BatchModel.Use use) {
        if (use.targetId != null) {
            return use.targetId;
        }
        if (use.beanName != null) {
            for (SpringModel.BeanMethod bean : project.spring.beanMethods) {
                if (bean.beanName().equals(use.beanName)) {
                    return bean.methodId();
                }
            }
            for (SpringModel.Bean bean : project.spring.beans) {
                if (bean.name().equals(use.beanName) && bean.typeName() != null) {
                    return Ids.javaType(bean.typeName());
                }
            }
        }
        return use.fallbackType == null ? null : project.typeId(use.fallbackType);
    }
}
