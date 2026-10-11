package io.codeatlas.java;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static io.codeatlas.java.Fx.edge;
import static io.codeatlas.java.Fx.hasEdge;
import static io.codeatlas.java.Fx.node;
import static io.codeatlas.java.Fx.targets;
import static org.assertj.core.api.Assertions.assertThat;

/** F-005 Spring Batch 해석 (Java 설정). */
class SpringBatchTest {

    private static String m(String type, String name, String... params) {
        return Ids.javaMethod(type, name, params);
    }

    private static final String CONFIG = "com.a.DailySalesJobConfig";

    private static final String[] DAILY = {
            "src/com/a/SalesProcessor.java", "package com.a; import org.springframework.stereotype.Component; @Component public class SalesProcessor { }",
            "src/com/a/SalesWriter.java", "package com.a; import org.springframework.stereotype.Component; @Component public class SalesWriter { }",
            "src/com/a/CloseOrderTasklet.java", "package com.a; import org.springframework.stereotype.Component; @Component public class CloseOrderTasklet { }",
            "src/com/a/DailySalesJobConfig.java", """
                    package com.a;
                    import org.mybatis.spring.batch.MyBatisPagingItemReader;
                    import org.springframework.batch.core.Job;
                    import org.springframework.batch.core.Step;
                    import org.springframework.batch.core.job.builder.JobBuilder;
                    import org.springframework.batch.core.repository.JobRepository;
                    import org.springframework.batch.core.step.builder.StepBuilder;
                    import org.springframework.context.annotation.Bean;
                    import org.springframework.context.annotation.Configuration;
                    import org.springframework.transaction.PlatformTransactionManager;
                    @Configuration
                    public class DailySalesJobConfig {
                        private static final int CHUNK_SIZE = 100;
                        private JobRepository jobRepository;
                        private PlatformTransactionManager transactionManager;
                        @Bean
                        public Job dailySalesJob(Step salesSumStep, Step closeOrderStep) {
                            return new JobBuilder("dailySalesJob", jobRepository)
                                    .start(salesSumStep)
                                    .next(closeOrderStep)
                                    .build();
                        }
                        @Bean
                        public Step salesSumStep(SalesProcessor salesProcessor, SalesWriter salesWriter) {
                            return new StepBuilder("salesSumStep", jobRepository)
                                    .<Object, Object>chunk(CHUNK_SIZE, transactionManager)
                                    .reader(closedOrderReader(null))
                                    .processor(salesProcessor)
                                    .writer(salesWriter)
                                    .build();
                        }
                        @Bean
                        public Step closeOrderStep(CloseOrderTasklet closeOrderTasklet) {
                            return new StepBuilder("closeOrderStep", jobRepository)
                                    .tasklet(closeOrderTasklet, transactionManager)
                                    .build();
                        }
                        @Bean
                        public MyBatisPagingItemReader<Object> closedOrderReader(String baseDt) {
                            MyBatisPagingItemReader<Object> reader = new MyBatisPagingItemReader<>();
                            reader.setQueryId("com.a.mapper.BatchMapper.selectClosedOrders");
                            reader.setPageSize(CHUNK_SIZE);
                            return reader;
                        }
                    }
                    """,
    };

    private static void assertDaily(GraphModel g) {
        Node job = node(g, Ids.batchJob("dailySalesJob"));
        assertThat(job.id()).isEqualTo("batch:job:dailySalesJob");
        assertThat(job.kind()).isEqualTo(NodeKind.BATCH_JOB);
        assertThat(job.attrString("definedBy")).isEqualTo(m(CONFIG, "dailySalesJob",
                "org.springframework.batch.core.Step", "org.springframework.batch.core.Step"));
        assertThat(job.location().startLine()).isEqualTo(18);

        String sum = "batch:step:dailySalesJob/salesSumStep";
        String close = "batch:step:dailySalesJob/closeOrderStep";
        Node sumStep = node(g, sum);
        assertThat(sumStep.kind()).isEqualTo(NodeKind.BATCH_STEP);
        assertThat(sumStep.name()).isEqualTo("salesSumStep");
        assertThat(sumStep.attrString("stepType")).isEqualTo("chunk");
        assertThat(sumStep.attrInt("chunkSize", -1)).isEqualTo(100);
        assertThat(node(g, close).attrString("stepType")).isEqualTo("tasklet");
        assertThat(targets(g, job.id(), EdgeKind.CONTAINS)).containsExactly(sum, close);
        assertThat(targets(g, sum, EdgeKind.NEXT)).containsExactly(close);
        assertThat(targets(g, close, EdgeKind.NEXT)).isEmpty();
        assertThat(g.nodes(NodeKind.BATCH_STEP)).hasSize(2);

        // F-005-AC3의 일부: 같은 클래스의 @Bean 메서드 호출 → METHOD, 프로젝트 타입 매개변수 → TYPE
        String reader = m(CONFIG, "closedOrderReader", "java.lang.String");
        assertThat(edge(g, sum, EdgeKind.USES, reader).attrString("role")).isEqualTo("reader");
        assertThat(edge(g, sum, EdgeKind.USES, Ids.javaType("com.a.SalesProcessor")).attrString("role")).isEqualTo("processor");
        assertThat(edge(g, sum, EdgeKind.USES, Ids.javaType("com.a.SalesWriter")).attrString("role")).isEqualTo("writer");
        assertThat(edge(g, close, EdgeKind.USES, Ids.javaType("com.a.CloseOrderTasklet")).attrString("role")).isEqualTo("tasklet");

        // F-005-AC5의 일부: 리더 빈 메서드의 queryId → 스텝에서 EXECUTES
        Edge executes = edge(g, sum, EdgeKind.EXECUTES, "mybatis:stmt:com.a.mapper.BatchMapper.selectClosedOrders");
        assertThat(executes.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(targets(g, reader, EdgeKind.EXECUTES)).isEmpty();
    }

    @Test
    @DisplayName("F-005-AC1 JobBuilder/StepBuilder 체인에서 잡·스텝·CONTAINS·NEXT·USES를 만들고 Step 매개변수를 빈 메서드의 스텝으로 잇는다 (jar 없음)")
    void jobAndStepsWithoutJar() {
        assertDaily(Fx.analyze(DAILY));
    }

    @Test
    @DisplayName("F-005-AC7 Spring Batch가 classpath에 있을 때도 같은 잡·스텝·엣지가 나온다")
    void jobAndStepsWithJar() {
        assertDaily(Fx.analyzeWithStubs(DAILY));
    }

    @Test
    @DisplayName("F-005-AC2 팩터리 체인과 on().to()/from() 조건 흐름, 메서드명과 다른 스텝 이름")
    void factoryChainAndConditionalFlow() {
        GraphModel g = Fx.analyze("src/com/a/SettleJobConfig.java", """
                package com.a;
                import org.springframework.batch.core.Job;
                import org.springframework.batch.core.Step;
                import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
                import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
                import org.springframework.context.annotation.Bean;
                public class SettleJobConfig {
                    private JobBuilderFactory jobBuilderFactory;
                    private StepBuilderFactory stepBuilderFactory;
                    @Bean
                    public Job settleJob() {
                        return jobBuilderFactory.get("settleJob")
                                .start(step1())
                                    .on("FAILED").to(errorStep())
                                .from(step1())
                                    .on("*").to(step2())
                                .end()
                                .build();
                    }
                    @Bean public Step step1() { return stepBuilderFactory.get("readStep").tasklet((c, x) -> null).build(); }
                    @Bean public Step step2() { return stepBuilderFactory.get("writeStep").tasklet((c, x) -> null).build(); }
                    @Bean public Step errorStep() { return stepBuilderFactory.get("errorStep").tasklet((c, x) -> null).build(); }
                }
                """);

        String read = "batch:step:settleJob/readStep";
        String write = "batch:step:settleJob/writeStep";
        String error = "batch:step:settleJob/errorStep";
        assertThat(targets(g, Ids.batchJob("settleJob"), EdgeKind.CONTAINS)).containsExactlyInAnyOrder(read, write, error);
        assertThat(edge(g, read, EdgeKind.NEXT, error).attrString("on")).isEqualTo("FAILED");
        assertThat(edge(g, read, EdgeKind.NEXT, write).attrString("on")).isEqualTo("*");
        assertThat(g.edges(EdgeKind.NEXT)).hasSize(2);
        // 람다 태스크릿은 감싼 메서드로
        Edge uses = edge(g, read, EdgeKind.USES, m("com.a.SettleJobConfig", "step1"));
        assertThat(uses.attrString("role")).isEqualTo("tasklet");
        assertThat(uses.attrBoolean("lambda")).isTrue();
        assertThat(g.node("batch:step:settleJob/step1")).isEmpty();
    }

    @Test
    @DisplayName("F-005-AC3 reader/processor/writer/tasklet/listener 인자의 대상을 METHOD·TYPE으로 해석한다")
    void usesTargets() {
        GraphModel g = Fx.analyze(
                "src/com/a/CleanupTasklet.java", "package com.a; public class CleanupTasklet { }",
                "src/com/a/StepLogger.java", "package com.a; public class StepLogger { }",
                "src/com/a/ItemProc.java", "package com.a; public interface ItemProc { }",
                "src/com/a/UpperProc.java", "package com.a; import org.springframework.stereotype.Component; @Component(\"itemProc\") public class UpperProc implements ItemProc { }",
                "src/com/a/Cfg.java", """
                        package com.a;
                        import org.springframework.batch.core.Step;
                        import org.springframework.batch.core.step.builder.StepBuilder;
                        import org.springframework.batch.item.ItemReader;
                        import org.springframework.batch.item.ItemWriter;
                        import org.springframework.context.annotation.Bean;
                        public class Cfg {
                            private StepLogger stepListener;
                            @Bean public ItemReader<String> reader() { return null; }
                            @Bean public Step chunkStep(ItemProc itemProc, ItemWriter<String> sharedWriter) {
                                return new StepBuilder("chunkStep", null)
                                        .<String, String>chunk(10, null)
                                        .reader(reader())
                                        .processor(itemProc)
                                        .writer(items -> { })
                                        .listener(stepListener)
                                        .build();
                            }
                            @Bean public Step otherStep(ItemWriter<String> sharedWriter) {
                                return new StepBuilder("otherStep", null).<String, String>chunk(5).reader(this.reader()).writer(sharedWriter).build();
                            }
                            @Bean public Step cleanStep() {
                                CleanupTasklet tasklet = new CleanupTasklet();
                                return new StepBuilder("cleanStep", null).tasklet(tasklet, null).build();
                            }
                        }
                        """,
                "src/com/a/WriterCfg.java", """
                        package com.a;
                        import org.springframework.batch.item.ItemWriter;
                        import org.springframework.context.annotation.Bean;
                        public class WriterCfg { @Bean public ItemWriter<String> sharedWriter() { return null; } }
                        """);

        String chunk = "batch:step:?/chunkStep";
        Function<String, String> role = to -> edge(g, chunk, EdgeKind.USES, to).attrString("role");
        assertThat(role.apply(m("com.a.Cfg", "reader"))).isEqualTo("reader");
        // 인터페이스 타입 매개변수 → 이름이 같은 스테레오타입 빈의 타입
        assertThat(role.apply(Ids.javaType("com.a.UpperProc"))).isEqualTo("processor");
        String chunkMethod = m("com.a.Cfg", "chunkStep", "com.a.ItemProc", "org.springframework.batch.item.ItemWriter");
        assertThat(role.apply(chunkMethod)).isEqualTo("writer");
        assertThat(edge(g, chunk, EdgeKind.USES, chunkMethod).attrBoolean("lambda")).isTrue();
        assertThat(role.apply(Ids.javaType("com.a.StepLogger"))).isEqualTo("listener");
        assertThat(targets(g, chunk, EdgeKind.USES)).hasSize(4);
        // 라이브러리 타입 매개변수 → 이름이 같은 @Bean 메서드(다른 클래스)
        assertThat(edge(g, "batch:step:?/otherStep", EdgeKind.USES, m("com.a.WriterCfg", "sharedWriter")).attrString("role")).isEqualTo("writer");
        assertThat(hasEdge(g, "batch:step:?/otherStep", EdgeKind.USES, m("com.a.Cfg", "reader"))).isTrue();
        assertThat(node(g, "batch:step:?/otherStep").attrInt("chunkSize", -1)).isEqualTo(5);
        // new로 만든 지역 변수 → TYPE
        assertThat(edge(g, "batch:step:?/cleanStep", EdgeKind.USES, Ids.javaType("com.a.CleanupTasklet")).attrString("role")).isEqualTo("tasklet");
    }

    @Test
    @DisplayName("F-005-AC4 잡에 쓰이지 않는 스텝은 batch:step:?/<이름>이고 두 잡이 함께 쓰는 스텝은 잡마다 노드가 생긴다")
    void orphanAndSharedSteps() {
        GraphModel g = Fx.analyze(
                "src/com/a/Work.java", "package com.a; public class Work { }",
                "src/com/a/Cfg.java", """
                        package com.a;
                        import org.springframework.batch.core.Job;
                        import org.springframework.batch.core.Step;
                        import org.springframework.batch.core.job.builder.JobBuilder;
                        import org.springframework.batch.core.step.builder.StepBuilder;
                        import org.springframework.context.annotation.Bean;
                        public class Cfg {
                            @Bean public Job jobA() { return new JobBuilder("jobA", null).start(shared()).build(); }
                            @Bean public Job jobB() { return new JobBuilder("jobB", null).start(shared()).next(inline()).build(); }
                            @Bean public Step shared() { return new StepBuilder("sharedStep", null).tasklet(new Work(), null).build(); }
                            @Bean public Step orphan() { return new StepBuilder("orphanStep", null).tasklet(new Work(), null).build(); }
                            Step inline() {
                                Step local = new StepBuilder("localStep", null).tasklet(new Work(), null).build();
                                return local;
                            }
                        }
                        """);

        assertThat(g.nodes(NodeKind.BATCH_STEP)).extracting(Node::id).containsExactlyInAnyOrder(
                "batch:step:jobA/sharedStep", "batch:step:jobB/sharedStep", "batch:step:jobB/localStep", "batch:step:?/orphanStep");
        for (String step : new String[]{"batch:step:jobA/sharedStep", "batch:step:jobB/sharedStep", "batch:step:?/orphanStep"}) {
            assertThat(targets(g, step, EdgeKind.USES)).containsExactly(Ids.javaType("com.a.Work"));
        }
        assertThat(hasEdge(g, "batch:step:jobB/sharedStep", EdgeKind.NEXT, "batch:step:jobB/localStep")).isTrue();
        assertThat(g.incoming("batch:step:?/orphanStep", EdgeKind.CONTAINS)).isEmpty();
    }

    @Test
    @DisplayName("F-005-AC5 MyBatis 리더·라이터의 queryId/statementId로 스텝에서 EXECUTES를 만들고 쓰이지 않으면 빈 메서드에서 만든다")
    void mybatisReaderWriter() {
        GraphModel g = Fx.analyze("src/com/a/Cfg.java", """
                package com.a;
                import org.mybatis.spring.batch.MyBatisBatchItemWriter;
                import org.mybatis.spring.batch.MyBatisPagingItemReader;
                import org.mybatis.spring.batch.builder.MyBatisBatchItemWriterBuilder;
                import org.springframework.batch.core.Step;
                import org.springframework.batch.core.step.builder.StepBuilder;
                import org.springframework.context.annotation.Bean;
                public class Cfg {
                    @Bean public Step loadStep() {
                        return new StepBuilder("loadStep", null).<Object, Object>chunk(10, null)
                                .reader(orderReader())
                                .writer(salesWriter())
                                .build();
                    }
                    @Bean public MyBatisPagingItemReader<Object> orderReader() {
                        MyBatisPagingItemReader<Object> reader = new MyBatisPagingItemReader<>();
                        reader.setQueryId("a.b.BatchMapper.selectClosedOrders");
                        return reader;
                    }
                    @Bean public MyBatisBatchItemWriter<Object> salesWriter() {
                        return new MyBatisBatchItemWriterBuilder<Object>().statementId("a.b.insertSales").build();
                    }
                    @Bean public MyBatisPagingItemReader<Object> unusedReader() {
                        MyBatisPagingItemReader<Object> reader = new MyBatisPagingItemReader<>();
                        reader.setQueryId("a.b.BatchMapper.selectUnused");
                        return reader;
                    }
                    @Bean public MyBatisPagingItemReader<Object> dynamicReader(String id) {
                        MyBatisPagingItemReader<Object> reader = new MyBatisPagingItemReader<>();
                        reader.setQueryId(id);
                        return reader;
                    }
                }
                """);

        String step = "batch:step:?/loadStep";
        assertThat(targets(g, step, EdgeKind.EXECUTES)).containsExactlyInAnyOrder(
                "mybatis:stmt:a.b.BatchMapper.selectClosedOrders", "mybatis:stmt:a.b.insertSales");
        assertThat(targets(g, m("com.a.Cfg", "orderReader"), EdgeKind.EXECUTES)).isEmpty();
        assertThat(targets(g, m("com.a.Cfg", "unusedReader"), EdgeKind.EXECUTES)).containsExactly("mybatis:stmt:a.b.BatchMapper.selectUnused");
        Edge dynamic = edge(g, m("com.a.Cfg", "dynamicReader", "java.lang.String"), EdgeKind.EXECUTES, Ids.unresolved("mybatis-statement", "id"));
        assertThat(dynamic.confidence()).isEqualTo(Confidence.UNRESOLVED);
    }

    @Test
    @DisplayName("F-005-AC6 풀 수 없는 스텝·구성 요소 인자는 미해결 엣지로, 상수가 아닌 이름은 WARN 진단으로 남긴다")
    void unresolved() {
        GraphModel g = Fx.analyze("src/com/a/Cfg.java", """
                package com.a;
                import java.util.List;
                import org.springframework.batch.core.Job;
                import org.springframework.batch.core.Step;
                import org.springframework.batch.core.job.builder.JobBuilder;
                import org.springframework.batch.core.step.builder.StepBuilder;
                public class Cfg {
                    Factory factory;
                    Job job(List<Step> steps, int i) {
                        return new JobBuilder("dynJob", null).start(first()).next(steps.get(i)).build();
                    }
                    Step first() { return new StepBuilder("first", null).<Object, Object>chunk(1, null).reader(factory.create()).build(); }
                    Step named(String name) { return new StepBuilder(name, null).tasklet(null, null).build(); }
                    interface Factory { Object create(); }
                }
                """);

        Edge step = edge(g, Ids.batchJob("dynJob"), EdgeKind.CONTAINS, Ids.unresolved("batch-step", "steps.get(i)"));
        assertThat(step.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(hasEdge(g, Ids.batchJob("dynJob"), EdgeKind.CONTAINS, "batch:step:dynJob/first")).isTrue();
        Edge component = edge(g, "batch:step:dynJob/first", EdgeKind.USES, Ids.unresolved("batch-component", "factory.create()"));
        assertThat(component.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(component.attrString("role")).isEqualTo("reader");
        assertThat(g.diagnostics()).anyMatch(d -> d.severity() == Severity.WARN && d.code().equals(DiagnosticCodes.UNRESOLVED_REFERENCE)
                && d.message().contains("StepBuilder") && d.location().startLine() == 13);
        assertThat(g.nodes(NodeKind.BATCH_STEP)).hasSize(1);
    }
}
