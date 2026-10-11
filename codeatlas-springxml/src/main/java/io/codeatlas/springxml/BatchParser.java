package io.codeatlas.springxml;

import io.codeatlas.springxml.BatchDefs.Component;
import io.codeatlas.springxml.BatchDefs.FlowDef;
import io.codeatlas.springxml.BatchDefs.JobDef;
import io.codeatlas.springxml.BatchDefs.StepDef;
import io.codeatlas.springxml.BatchDefs.Transition;
import java.util.List;

/** Spring Batch 네임스페이스 요소를 {@link FileModel}로 읽는다. */
final class BatchParser {

    private final FileModel model;
    private final BeanParser beanParser;

    BatchParser(FileModel model, BeanParser beanParser) {
        this.model = model;
        this.beanParser = beanParser;
    }

    /** beans 바로 아래의 배치 요소. 처리했으면 true, 모르는 요소면 false. */
    boolean parseTopLevel(XmlElement e, String profile) {
        switch (e.localName) {
            case "job" -> model.jobs.add(parseJob(e, profile));
            case "step" -> {
                StepDef step = parseStep(e, null, null, profile);
                if (step != null) {
                    model.standaloneSteps.add(step);
                }
            }
            case "flow" -> {
                FlowDef flow = new FlowDef();
                flow.id = e.attrTrim("id");
                flow.firstStep = parseFlowBody(e, null, flow.id, flow.steps, profile);
                model.flows.add(flow);
            }
            case "job-repository" -> {
                // 그래프에 남길 것이 없다
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private JobDef parseJob(XmlElement e, String profile) {
        JobDef job = new JobDef();
        job.id = e.attrTrim("id");
        job.parent = e.attrTrim("parent");
        job.abstractJob = "true".equalsIgnoreCase(e.attrTrim("abstract"));
        job.restartable = e.attrTrim("restartable");
        job.incrementer = e.attrTrim("incrementer");
        job.file = model.path();
        job.location = beanParser.location(e);
        parseFlowBody(e, job.id, null, job.steps, profile);
        for (XmlElement listeners : e.children(Ns.BATCH, "listeners")) {
            job.hasListeners = true;
            job.mergeListeners |= "true".equalsIgnoreCase(listeners.attrTrim("merge"));
            parseListeners(listeners, job.listeners, profile);
        }
        return job;
    }

    /**
     * 잡, 흐름, split 안 흐름의 본문(step, decision, flow, split)을 읽어 out에 더한다.
     *
     * @return 본문의 첫 스텝 이름. 없으면 null
     */
    private String parseFlowBody(XmlElement container, String job, String flow, List<StepDef> out, String profile) {
        String first = null;
        for (XmlElement child : container.children) {
            if (!child.namespace.equals(Ns.BATCH)) {
                continue;
            }
            StepDef step = switch (child.localName) {
                case "step" -> parseStep(child, job, flow, profile);
                case "decision" -> {
                    StepDef d = newStep(child, job, flow);
                    if (d != null) {
                        d.type = "decision";
                        String decider = child.attrTrim("decider");
                        if (decider != null) {
                            d.components.put("decider", new Component("decider", decider, null, null, d.location));
                        }
                        parseTransitions(child, d);
                    }
                    yield d;
                }
                case "flow" -> {
                    StepDef f = newStep(child, job, flow);
                    if (f != null) {
                        f.type = "flow";
                        f.flowRef = child.attrTrim("parent");
                        parseTransitions(child, f);
                    }
                    yield f;
                }
                case "split" -> {
                    StepDef s = newStep(child, job, flow);
                    if (s != null) {
                        s.type = "split";
                        parseTransitions(child, s);
                    }
                    yield s;
                }
                default -> null;
            };
            if (step == null) {
                continue;
            }
            out.add(step);
            if (first == null) {
                first = step.id;
            }
            if (child.localName.equals("split")) {
                // split 안의 흐름: 인라인이면 같은 잡의 스텝으로, parent 참조면 흐름 이름으로 남긴다
                for (XmlElement inlineFlow : child.children(Ns.BATCH, "flow")) {
                    String parent = inlineFlow.attrTrim("parent");
                    if (parent != null) {
                        step.entryFlowRefs.add(parent);
                    }
                    String entry = parseFlowBody(inlineFlow, job, flow, out, profile);
                    if (entry != null) {
                        step.entrySteps.add(entry);
                    }
                }
            }
        }
        return first;
    }

    private StepDef newStep(XmlElement e, String job, String flow) {
        String id = e.attrTrim("id");
        if (id == null) {
            return null;
        }
        StepDef step = new StepDef();
        step.id = id;
        step.job = job;
        step.flow = flow;
        step.file = model.path();
        step.location = beanParser.location(e);
        step.next = e.attrTrim("next");
        return step;
    }

    private StepDef parseStep(XmlElement e, String job, String flow, String profile) {
        StepDef step = newStep(e, job, flow);
        if (step == null) {
            return null;
        }
        step.parent = e.attrTrim("parent");
        step.abstractStep = "true".equalsIgnoreCase(e.attrTrim("abstract"));
        for (XmlElement child : e.children) {
            if (!child.namespace.equals(Ns.BATCH)) {
                continue;
            }
            switch (child.localName) {
                case "tasklet" -> parseTasklet(child, step, profile);
                case "partition" -> {
                    step.type = "partition";
                    step.partitionStep = child.attrTrim("step");
                    String partitioner = child.attrTrim("partitioner");
                    if (partitioner != null) {
                        step.components.put("partitioner",
                                new Component("partitioner", partitioner, null, null, beanParser.location(child)));
                    }
                }
                case "job" -> {
                    step.type = "job";
                    step.jobRef = child.attrTrim("ref");
                }
                case "flow" -> {
                    step.type = "flow";
                    step.flowRef = child.attrTrim("parent");
                }
                case "listeners" -> {
                    step.hasListeners = true;
                    step.mergeListeners |= "true".equalsIgnoreCase(child.attrTrim("merge"));
                    parseListeners(child, step.listeners, profile);
                }
                default -> {
                    // 전이는 아래에서 읽는다
                }
            }
        }
        parseTransitions(e, step);
        return step;
    }

    private void parseTasklet(XmlElement tasklet, StepDef step, String profile) {
        String ref = tasklet.attrTrim("ref");
        if (ref != null) {
            step.type = "tasklet";
            step.components.put("tasklet", new Component("tasklet", ref, null, null, beanParser.location(tasklet)));
        }
        for (XmlElement child : tasklet.children) {
            if (child.isBatch("chunk")) {
                parseChunk(child, step, profile);
            } else if (child.isBatch("listeners")) {
                step.hasListeners = true;
                step.mergeListeners |= "true".equalsIgnoreCase(child.attrTrim("merge"));
                parseListeners(child, step.listeners, profile);
            } else if (child.isBeans("bean") || child.isBeans("ref")) {
                Component inline = inlineComponent("tasklet", child, profile);
                if (inline != null) {
                    step.type = "tasklet";
                    step.components.put("tasklet", inline);
                }
            }
        }
    }

    private void parseChunk(XmlElement chunk, StepDef step, String profile) {
        step.type = "chunk";
        step.commitInterval = chunk.attrTrim("commit-interval");
        for (String role : List.of("reader", "processor", "writer")) {
            String ref = chunk.attrTrim(role);
            if (ref != null) {
                // 속성마다 위치를 따로 준다. 한 빈을 reader와 writer로 함께 써도 엣지가 합쳐지지 않는다
                step.components.put(role, new Component(role, ref, null, null, beanParser.location(chunk, role)));
            }
        }
        for (XmlElement child : chunk.children) {
            if (!child.namespace.equals(Ns.BATCH)) {
                continue;
            }
            switch (child.localName) {
                case "reader", "processor", "writer" -> {
                    for (XmlElement inner : child.children) {
                        Component inline = inlineComponent(child.localName, inner, profile);
                        if (inline != null) {
                            step.components.put(child.localName, inline);
                        }
                    }
                }
                case "listeners" -> {
                    step.hasListeners = true;
                    step.mergeListeners |= "true".equalsIgnoreCase(child.attrTrim("merge"));
                    parseListeners(child, step.listeners, profile);
                }
                default -> {
                    // skip-policy, retry-policy, streams, 예외 클래스 목록은 쓰지 않는다
                }
            }
        }
    }

    /** {@code <bean>} 또는 {@code <ref bean>}으로 직접 준 구성 요소. */
    private Component inlineComponent(String role, XmlElement e, String profile) {
        if (e.isBeans("bean")) {
            BeanDef inner = beanParser.parseDetachedInnerBean(e, profile);
            return new Component(role, null, null, inner, beanParser.location(e));
        }
        if (e.isBeans("ref")) {
            String bean = e.attrTrim("bean");
            if (bean == null) {
                bean = e.attrTrim("local");
            }
            return bean == null ? null : new Component(role, bean, null, null, beanParser.location(e));
        }
        return null;
    }

    private void parseListeners(XmlElement listeners, List<Component> out, String profile) {
        for (XmlElement listener : listeners.children(Ns.BATCH, "listener")) {
            String ref = listener.attrTrim("ref");
            String className = listener.attrTrim("class");
            if (ref != null) {
                out.add(new Component("listener", ref, null, null, beanParser.location(listener)));
            } else if (className != null) {
                out.add(new Component("listener", null, className, null, beanParser.location(listener)));
            } else {
                for (XmlElement inner : listener.children) {
                    Component inline = inlineComponent("listener", inner, profile);
                    if (inline != null) {
                        out.add(inline);
                    }
                }
            }
        }
    }

    private void parseTransitions(XmlElement e, StepDef step) {
        for (XmlElement child : e.children) {
            if (!child.namespace.equals(Ns.BATCH)) {
                continue;
            }
            switch (child.localName) {
                case "next" -> step.transitions.add(
                        new Transition("next", child.attrTrim("on"), child.attrTrim("to"), beanParser.location(child)));
                case "stop" -> step.transitions.add(
                        new Transition("stop", child.attrTrim("on"), child.attrTrim("restart"), beanParser.location(child)));
                case "end" -> step.transitions.add(new Transition("end", child.attrTrim("on"), null, beanParser.location(child)));
                case "fail" -> step.transitions.add(new Transition("fail", child.attrTrim("on"), null, beanParser.location(child)));
                default -> {
                    // 전이가 아니다
                }
            }
        }
    }
}
