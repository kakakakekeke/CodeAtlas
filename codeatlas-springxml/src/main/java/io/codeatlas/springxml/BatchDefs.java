package io.codeatlas.springxml;

import io.codeatlas.core.model.SourceLocation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Spring Batch XML에서 읽은 잡, 스텝, 흐름 정의. */
final class BatchDefs {

    private BatchDefs() {
    }

    /** 스텝(또는 잡)의 구성 요소 하나. ref, class, 내부 빈 중 하나로 가리킨다. */
    static final class Component {
        final String role;
        final String ref;
        final String className;
        final BeanDef inner;
        final SourceLocation location;

        Component(String role, String ref, String className, BeanDef inner, SourceLocation location) {
            this.role = role;
            this.ref = ref;
            this.className = className;
            this.inner = inner;
            this.location = location;
        }
    }

    /** {@code <next on to>}, {@code <end on>}, {@code <fail on>}, {@code <stop on restart>}. */
    record Transition(String kind, String on, String to, SourceLocation location) {
    }

    static final class StepDef {
        String id;
        /** 속한 잡. 잡 밖의 스텝이면 null. */
        String job;
        /** 잡 밖 {@code <flow id>} 안의 스텝이면 그 흐름 이름. */
        String flow;
        /** tasklet, chunk, decision, flow, split, partition, job. 본문이 없어 알 수 없으면 step. */
        String type = "step";
        String parent;
        boolean abstractStep;
        String next;
        String commitInterval;
        String flowRef;
        String jobRef;
        String partitionStep;
        String file;
        SourceLocation location;
        /** role(tasklet, reader, processor, writer, decider, partitioner) → 구성 요소. */
        final Map<String, Component> components = new LinkedHashMap<>();
        final List<Component> listeners = new ArrayList<>();
        boolean hasListeners;
        boolean mergeListeners;
        final List<Transition> transitions = new ArrayList<>();
        /** split: 인라인 흐름들의 첫 스텝 이름(같은 잡). */
        final List<String> entrySteps = new ArrayList<>();
        /** split: {@code <flow parent>}로 참조한 흐름 이름. */
        final List<String> entryFlowRefs = new ArrayList<>();
    }

    static final class JobDef {
        String id;
        String parent;
        boolean abstractJob;
        String restartable;
        String incrementer;
        String file;
        SourceLocation location;
        final List<StepDef> steps = new ArrayList<>();
        final List<Component> listeners = new ArrayList<>();
        boolean hasListeners;
        boolean mergeListeners;
    }

    /** 잡 밖에 정의된 {@code <flow id>}. */
    static final class FlowDef {
        String id;
        String firstStep;
        final List<StepDef> steps = new ArrayList<>();
    }
}
