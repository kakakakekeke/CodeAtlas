package io.codeatlas.springxml;

import static io.codeatlas.core.model.EdgeKind.CONTAINS;
import static io.codeatlas.core.model.EdgeKind.NEXT;
import static io.codeatlas.core.model.EdgeKind.USES;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BatchJobXmlTest {

    private static final String PATH = "src/main/resources/egovframework/batch/job/orderJob.xml";

    private static String step(String job, String step) {
        return Ids.batchStep(job, step);
    }

    @Test
    @DisplayName("F-017-AC7 job, step, decision, split, flow가 BATCH_JOB·BATCH_STEP·CONTAINS·NEXT로 남는다")
    void jobStepsAndTransitions() {
        String xml = Fx.beans("""
                  <batch:job id="orderJob" restartable="true" job-repository="jobRepository">
                    <batch:step id="loadStep" next="decide">
                      <batch:tasklet ref="loadTasklet"/>
                    </batch:step>
                    <batch:decision id="decide" decider="orderDecider">
                      <batch:next on="SKIP" to="reportStep"/>
                      <batch:next on="*" to="parallel"/>
                    </batch:decision>
                    <batch:split id="parallel" task-executor="taskExecutor" next="processStep">
                      <batch:flow>
                        <batch:step id="splitA"><batch:tasklet ref="taskA"/></batch:step>
                      </batch:flow>
                      <batch:flow>
                        <batch:step id="splitB" next="splitB2"><batch:tasklet ref="taskB"/></batch:step>
                        <batch:step id="splitB2"><batch:tasklet ref="taskB2"/></batch:step>
                      </batch:flow>
                    </batch:split>
                    <batch:step id="processStep">
                      <batch:tasklet ref="processTasklet"/>
                      <batch:next on="COMPLETED" to="cleanup"/>
                      <batch:fail on="FAILED" exit-code="PROCESS_FAILED"/>
                      <batch:stop on="STOPPED" restart="loadStep"/>
                      <batch:end on="NOOP"/>
                    </batch:step>
                    <batch:flow id="cleanup" parent="cleanupFlow" next="reportStep"/>
                    <batch:step id="reportStep">
                      <batch:tasklet ref="reportTasklet"/>
                    </batch:step>
                  </batch:job>

                  <batch:flow id="cleanupFlow">
                    <batch:step id="cleanTemp" next="cleanLog"><batch:tasklet ref="cleanTempTasklet"/></batch:step>
                    <batch:step id="cleanLog"><batch:tasklet ref="cleanLogTasklet"/></batch:step>
                  </batch:flow>
                """);
        GraphModel graph = Fx.run(PATH, xml).graph();

        Node job = Fx.node(graph, Ids.batchJob("orderJob"));
        assertThat(job.kind()).isEqualTo(NodeKind.BATCH_JOB);
        assertThat(job.name()).isEqualTo("orderJob");
        assertThat(job.attrString("restartable")).isEqualTo("true");
        assertThat(job.location().startLine()).isEqualTo(Fx.lineOf(xml, "<batch:job id=\"orderJob\""));
        Fx.edge(graph, Ids.file(PATH), CONTAINS, job.id());

        assertThat(Fx.targets(graph, job.id(), CONTAINS)).containsExactlyInAnyOrder(
                step("orderJob", "loadStep"), step("orderJob", "decide"), step("orderJob", "parallel"),
                step("orderJob", "splitA"), step("orderJob", "splitB"), step("orderJob", "splitB2"),
                step("orderJob", "processStep"), step("orderJob", "cleanup"), step("orderJob", "reportStep"));
        Node load = Fx.node(graph, step("orderJob", "loadStep"));
        assertThat(load.kind()).isEqualTo(NodeKind.BATCH_STEP);
        assertThat(load.name()).isEqualTo("loadStep");
        assertThat(load.attrString("stepType")).isEqualTo("tasklet");
        assertThat(load.location().startLine()).isEqualTo(Fx.lineOf(xml, "<batch:step id=\"loadStep\""));

        // next 속성: on 없음
        Edge next = Fx.edge(graph, load.id(), NEXT, step("orderJob", "decide"));
        assertThat(next.attrs()).doesNotContainKey("on");

        // decision: 스텝으로 표현하고 stepType=decision, 결정자는 USES(role=decider)
        Node decide = Fx.node(graph, step("orderJob", "decide"));
        assertThat(decide.attrString("stepType")).isEqualTo("decision");
        assertThat(Fx.edge(graph, decide.id(), NEXT, step("orderJob", "reportStep")).attrString("on")).isEqualTo("SKIP");
        assertThat(Fx.edge(graph, decide.id(), NEXT, step("orderJob", "parallel")).attrString("on")).isEqualTo("*");
        assertThat(Fx.edge(graph, decide.id(), USES, Ids.springBean("orderDecider")).attrString("role")).isEqualTo("decider");

        // split: 스텝 하나 + 각 흐름의 첫 스텝으로 가는 NEXT
        Node split = Fx.node(graph, step("orderJob", "parallel"));
        assertThat(split.attrString("stepType")).isEqualTo("split");
        assertThat(Fx.edge(graph, split.id(), NEXT, step("orderJob", "splitA")).attrString("transition")).isEqualTo("split");
        assertThat(Fx.edge(graph, split.id(), NEXT, step("orderJob", "splitB")).attrString("transition")).isEqualTo("split");
        assertThat(Fx.hasEdge(graph, split.id(), NEXT, step("orderJob", "splitB2"))).isFalse();
        Fx.edge(graph, step("orderJob", "splitB"), NEXT, step("orderJob", "splitB2"));
        assertThat(Fx.edge(graph, split.id(), NEXT, step("orderJob", "processStep")).attrs()).doesNotContainKey("transition");

        // <next on to>, <stop on restart>는 엣지, <end>, <fail>은 도착 스텝이 없어 attrs
        Node process = Fx.node(graph, step("orderJob", "processStep"));
        Edge completed = Fx.edge(graph, process.id(), NEXT, step("orderJob", "cleanup"));
        assertThat(completed.attrString("on")).isEqualTo("COMPLETED");
        assertThat(completed.location().startLine()).isEqualTo(Fx.lineOf(xml, "<batch:next on=\"COMPLETED\""));
        Edge stop = Fx.edge(graph, process.id(), NEXT, step("orderJob", "loadStep"));
        assertThat(stop.attrString("on")).isEqualTo("STOPPED");
        assertThat(stop.attrString("transition")).isEqualTo("stop");
        assertThat(process.attrList("failOn")).containsExactly("FAILED");
        assertThat(process.attrList("endOn")).containsExactly("NOOP");
        assertThat(process.attrList("stopOn")).containsExactly("STOPPED");
        assertThat(graph.outgoing(process.id(), NEXT)).hasSize(2);

        // 흐름 참조: stepType=flow, 잡 밖 흐름의 첫 스텝으로 잇는다
        Node cleanup = Fx.node(graph, step("orderJob", "cleanup"));
        assertThat(cleanup.attrString("stepType")).isEqualTo("flow");
        assertThat(cleanup.attrString("flowRef")).isEqualTo("cleanupFlow");
        Fx.edge(graph, cleanup.id(), NEXT, step("orderJob", "reportStep"));
        assertThat(Fx.edge(graph, cleanup.id(), NEXT, step(null, "cleanTemp")).attrString("transition")).isEqualTo("flow");
        Node cleanTemp = Fx.node(graph, step(null, "cleanTemp"));
        assertThat(cleanTemp.id()).isEqualTo("batch:step:?/cleanTemp");
        assertThat(cleanTemp.attrString("flow")).isEqualTo("cleanupFlow");
        Fx.edge(graph, cleanTemp.id(), NEXT, step(null, "cleanLog"));
        assertThat(graph.nodes(NodeKind.BATCH_STEP)).hasSize(11);
    }

    @Test
    @DisplayName("F-017-AC7 기본 네임스페이스가 batch인 파일(beans:beans 루트)도 해석한다")
    void batchAsDefaultNamespace() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans:beans xmlns="http://www.springframework.org/schema/batch"
                             xmlns:beans="http://www.springframework.org/schema/beans"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                  <job id="simpleJob">
                    <step id="s1" next="s2">
                      <tasklet ref="t1"/>
                    </step>
                    <step id="s2">
                      <tasklet ref="t2"/>
                    </step>
                  </job>
                  <beans:bean id="t1" class="com.a.batch.FirstTasklet"/>
                </beans:beans>
                """;
        GraphModel graph = Fx.run(PATH, xml).graph();

        Fx.node(graph, Ids.batchJob("simpleJob"));
        Fx.edge(graph, Ids.batchJob("simpleJob"), CONTAINS, step("simpleJob", "s1"));
        Fx.edge(graph, Ids.batchJob("simpleJob"), CONTAINS, step("simpleJob", "s2"));
        Fx.edge(graph, step("simpleJob", "s1"), NEXT, step("simpleJob", "s2"));
        assertThat(Fx.node(graph, Ids.springBean("t1")).attrString("class")).isEqualTo("com.a.batch.FirstTasklet");
        assertThat(Fx.edge(graph, step("simpleJob", "s1"), USES, Ids.javaType("com.a.batch.FirstTasklet")).attrString("role"))
                .isEqualTo("tasklet");
        Fx.edge(graph, step("simpleJob", "s2"), USES, Ids.springBean("t2"));
    }

    @Test
    @DisplayName("F-017-AC8 tasklet, chunk의 reader·processor·writer, listener가 USES(role)로 남고 대상은 TYPE 또는 BEAN이다")
    void stepComponents() {
        String xml = Fx.beans("""
                  <batch:job id="settleJob">
                    <batch:step id="settleStep" next="logStep">
                      <batch:tasklet transaction-manager="txManager">
                        <batch:chunk reader="settleReader" processor="settleProcessor" writer="settleWriter"
                                     commit-interval="50">
                          <batch:listeners>
                            <batch:listener ref="chunkLogger"/>
                          </batch:listeners>
                        </batch:chunk>
                        <batch:listeners>
                          <batch:listener ref="stepLogger"/>
                          <batch:listener class="com.a.batch.SkipLogger"/>
                        </batch:listeners>
                      </batch:tasklet>
                    </batch:step>
                    <batch:step id="logStep" next="inlineStep">
                      <batch:tasklet ref="logTasklet"/>
                    </batch:step>
                    <batch:step id="inlineStep">
                      <batch:tasklet>
                        <batch:chunk commit-interval="#{jobParameters['commit']}">
                          <batch:reader>
                            <bean class="com.a.batch.InlineReader">
                              <property name="settleDao" ref="settleDao"/>
                            </bean>
                          </batch:reader>
                          <batch:writer><ref bean="settleWriter"/></batch:writer>
                        </batch:chunk>
                      </batch:tasklet>
                    </batch:step>
                    <batch:listeners>
                      <batch:listener ref="jobLogger"/>
                    </batch:listeners>
                  </batch:job>

                  <bean id="settleReader" class="com.a.batch.SettleReader" scope="step">
                    <property name="settleYm" value="#{jobParameters['settleYm']}"/>
                  </bean>
                  <bean id="settleWriter" class="com.a.batch.SettleWriter"/>
                  <bean id="stepLogger" class="com.a.batch.StepLogger"/>
                  <bean id="logTasklet" class="com.a.batch.LogTasklet"/>
                  <bean id="settleDao" class="com.a.batch.SettleDao"/>
                """);
        GraphModel graph = Fx.run(PATH, xml).graph();
        String settle = step("settleJob", "settleStep");

        Node settleStep = Fx.node(graph, settle);
        assertThat(settleStep.attrString("stepType")).isEqualTo("chunk");
        assertThat(settleStep.attrString("commitInterval")).isEqualTo("50");

        // XML에 class가 있는 빈은 TYPE
        Edge reader = Fx.edge(graph, settle, USES, Ids.javaType("com.a.batch.SettleReader"));
        assertThat(reader.attrString("role")).isEqualTo("reader");
        assertThat(reader.attrString("bean")).isEqualTo("settleReader");
        assertThat(reader.location().startLine()).isEqualTo(Fx.lineOf(xml, "<batch:chunk reader=\"settleReader\""));
        assertThat(Fx.edge(graph, settle, USES, Ids.javaType("com.a.batch.SettleWriter")).attrString("role")).isEqualTo("writer");
        // XML에 없는 빈(애너테이션 빈일 수 있다)은 BEAN
        Edge processor = Fx.edge(graph, settle, USES, Ids.springBean("settleProcessor"));
        assertThat(processor.attrString("role")).isEqualTo("processor");
        assertThat(Fx.node(graph, Ids.springBean("settleProcessor")).declared()).isFalse();
        // 리스너: ref(XML에 있음/없음), class
        assertThat(Fx.edge(graph, settle, USES, Ids.javaType("com.a.batch.StepLogger")).attrString("role")).isEqualTo("listener");
        assertThat(Fx.edge(graph, settle, USES, Ids.javaType("com.a.batch.SkipLogger")).attrString("role")).isEqualTo("listener");
        assertThat(Fx.edge(graph, settle, USES, Ids.springBean("chunkLogger")).attrString("role")).isEqualTo("listener");
        assertThat(graph.outgoing(settle, USES)).hasSize(6);

        // tasklet ref
        String log = step("settleJob", "logStep");
        assertThat(Fx.node(graph, log).attrString("stepType")).isEqualTo("tasklet");
        assertThat(Fx.edge(graph, log, USES, Ids.javaType("com.a.batch.LogTasklet")).attrString("role")).isEqualTo("tasklet");

        // chunk 안에 인라인으로 정의한 reader 빈과 <ref>
        String inline = step("settleJob", "inlineStep");
        assertThat(Fx.node(graph, inline).attrString("commitInterval")).isEqualTo("#{jobParameters['commit']}");
        assertThat(Fx.edge(graph, inline, USES, Ids.javaType("com.a.batch.InlineReader")).attrString("role")).isEqualTo("reader");
        assertThat(Fx.edge(graph, inline, USES, Ids.javaType("com.a.batch.SettleWriter")).attrString("role")).isEqualTo("writer");
        Fx.edge(graph, Ids.javaType("com.a.batch.InlineReader"), io.codeatlas.core.model.EdgeKind.INJECTS, Ids.javaType("com.a.batch.SettleDao"));

        // 잡 리스너
        assertThat(Fx.edge(graph, Ids.batchJob("settleJob"), USES, Ids.springBean("jobLogger")).attrString("role")).isEqualTo("listener");

        // late binding 식은 원문 그대로
        assertThat(Fx.node(graph, Ids.springBean("settleReader")).attrString("property.settleYm")).isEqualTo("#{jobParameters['settleYm']}");
        assertThat(Fx.node(graph, Ids.springBean("settleReader")).attrString("scope")).isEqualTo("step");
    }

    @Test
    @DisplayName("F-017-AC9 잡 밖의 독립 step은 batch:step:?/<스텝>이고 parent로 재사용한 스텝과 잡에 구성이 병합된다")
    void standaloneStepAndParents() {
        String xml = Fx.beans("""
                  <batch:step id="baseStep" abstract="true">
                    <batch:tasklet>
                      <batch:chunk commit-interval="100" reader="defaultReader"/>
                      <batch:listeners>
                        <batch:listener ref="stepLogger"/>
                      </batch:listeners>
                    </batch:tasklet>
                  </batch:step>
                  <batch:job id="baseJob" abstract="true">
                    <batch:listeners>
                      <batch:listener ref="jobLogger"/>
                    </batch:listeners>
                  </batch:job>

                  <batch:job id="memberJob" parent="baseJob">
                    <batch:step id="memberStep" parent="baseStep" next="copyStep">
                      <batch:tasklet>
                        <batch:chunk reader="memberReader" writer="memberWriter"/>
                      </batch:tasklet>
                    </batch:step>
                    <batch:step id="copyStep" parent="baseStep">
                      <batch:tasklet>
                        <batch:chunk writer="copyWriter" commit-interval="10"/>
                      </batch:tasklet>
                    </batch:step>
                  </batch:job>
                  <bean id="stepLogger" class="com.a.batch.StepLogger"/>
                """);
        GraphModel graph = Fx.run(PATH, xml).graph();

        // 독립 스텝: 잡을 모른다
        String base = step(null, "baseStep");
        Node baseStep = Fx.node(graph, base);
        assertThat(base).isEqualTo("batch:step:?/baseStep");
        assertThat(baseStep.attrBoolean("abstract")).isTrue();
        assertThat(baseStep.attrString("commitInterval")).isEqualTo("100");
        assertThat(graph.incoming(base, CONTAINS)).extracting(Edge::from).containsExactly(Ids.file(PATH));
        assertThat(Fx.edge(graph, base, USES, Ids.springBean("defaultReader")).attrString("role")).isEqualTo("reader");
        Fx.edge(graph, base, USES, Ids.javaType("com.a.batch.StepLogger"));

        // 자식 스텝: 준 값은 덮어쓰고 안 준 값은 물려받는다
        String member = step("memberJob", "memberStep");
        Node memberStep = Fx.node(graph, member);
        assertThat(memberStep.attrString("parent")).isEqualTo("baseStep");
        assertThat(memberStep.attrString("commitInterval")).isEqualTo("100");
        assertThat(memberStep.attrString("stepType")).isEqualTo("chunk");
        assertThat(Fx.edge(graph, member, USES, Ids.springBean("memberReader")).attrs()).doesNotContainKey("inheritedFrom");
        assertThat(Fx.hasEdge(graph, member, USES, Ids.springBean("defaultReader"))).isFalse();
        Fx.edge(graph, member, USES, Ids.springBean("memberWriter"));
        Edge inheritedListener = Fx.edge(graph, member, USES, Ids.javaType("com.a.batch.StepLogger"));
        assertThat(inheritedListener.attrString("role")).isEqualTo("listener");
        assertThat(inheritedListener.attrString("inheritedFrom")).isEqualTo("baseStep");

        String copy = step("memberJob", "copyStep");
        assertThat(Fx.node(graph, copy).attrString("commitInterval")).isEqualTo("10");
        Edge inheritedReader = Fx.edge(graph, copy, USES, Ids.springBean("defaultReader"));
        assertThat(inheritedReader.attrString("role")).isEqualTo("reader");
        assertThat(inheritedReader.attrString("inheritedFrom")).isEqualTo("baseStep");
        Fx.edge(graph, copy, USES, Ids.springBean("copyWriter"));

        // 잡 parent: 부모 잡의 리스너를 물려받는다
        Node baseJob = Fx.node(graph, Ids.batchJob("baseJob"));
        assertThat(baseJob.attrBoolean("abstract")).isTrue();
        Fx.edge(graph, baseJob.id(), USES, Ids.springBean("jobLogger"));
        Node memberJob = Fx.node(graph, Ids.batchJob("memberJob"));
        assertThat(memberJob.attrString("parent")).isEqualTo("baseJob");
        assertThat(Fx.edge(graph, memberJob.id(), USES, Ids.springBean("jobLogger")).attrString("inheritedFrom")).isEqualTo("baseJob");
        assertThat(Fx.targets(graph, memberJob.id(), CONTAINS)).containsExactlyInAnyOrder(member, copy);
    }
}
