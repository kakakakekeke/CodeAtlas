package io.codeatlas.springxml;

import static io.codeatlas.core.model.EdgeKind.EXECUTES;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.Dialect;
import io.codeatlas.core.testing.TestProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 배치 설정의 프로시저 이름 {@code A.B}를 무엇으로 읽는가(F-019-R33). PostgreSQL에는 패키지가 없으므로 {@code --dialect postgres}인 동안은
 * DB2처럼 스키마.루틴으로 읽는다. 방언을 주지 않았거나 Oracle이면 전처럼 패키지.프로시저다.
 */
class BatchProcedureNameDialectTest {

    private static final String PATH = "src/main/resources/batch/job/settleJob.xml";

    private static final String XML = Fx.beans("""
              <batch:job id="settleJob">
                <batch:step id="settleStep">
                  <batch:tasklet>
                    <batch:chunk reader="settleReader" writer="plainReader" commit-interval="100"/>
                  </batch:tasklet>
                </batch:step>
              </batch:job>

              <bean id="settleReader" class="org.springframework.batch.item.database.StoredProcedureItemReader">
                <property name="procedureName" value="batch.sp_settle"/>
              </bean>
              <bean id="plainReader" class="org.springframework.batch.item.database.StoredProcedureItemReader">
                <property name="procedureName" value="sp_plain"/>
              </bean>
            """);

    private static final String STEP = Ids.batchStep("settleJob", "settleStep");

    private static GraphModel run(Dialect dialect) {
        try (TestProject project = TestProject.create().file(PATH, XML)) {
            if (dialect != null) {
                project.options(AnalysisOptions.builder().dialect(dialect).build());
            }
            return project.run(new SpringXmlAnalyzer()).graph();
        }
    }

    @Test
    @DisplayName("F-019-AC31 --dialect postgres면 프로시저 이름 batch.sp_settle을 스키마.루틴으로 읽는다: 실행 대상이 전체 이름의 루틴 ID다(DB2와 같다)")
    void postgresReadsSchemaDotRoutine() {
        GraphModel postgres = run(Dialect.POSTGRES);

        Edge call = Fx.edge(postgres, STEP, EXECUTES, Ids.routine("batch.sp_settle"));
        assertThat(call.to()).isEqualTo("db:routine:SP_SETTLE");
        assertThat(call.attrString("procedureName")).isEqualTo("batch.sp_settle");
        assertThat(Fx.node(postgres, call.to()).kind()).isEqualTo(NodeKind.DB_ROUTINE);
        // 패키지.프로시저로 읽은 루틴은 없다
        assertThat(postgres.hasNode(Ids.routine("batch", "sp_settle"))).isFalse();
        // 점이 없는 이름은 방언과 무관하다
        Fx.edge(postgres, STEP, EXECUTES, Ids.routine("sp_plain"));

        // DB2와 같은 대상이다
        assertThat(Fx.targets(run(Dialect.DB2), STEP, EXECUTES)).containsExactlyInAnyOrderElementsOf(Fx.targets(postgres, STEP, EXECUTES));
    }

    @Test
    @DisplayName("F-019-AC31 옵션이 없거나 --dialect oracle이면 전처럼 A.B를 패키지.프로시저로 읽는다")
    void withoutPostgresUnchanged() {
        for (Dialect dialect : new Dialect[] {null, Dialect.AUTO, Dialect.ORACLE}) {
            GraphModel graph = run(dialect);

            Edge call = Fx.edge(graph, STEP, EXECUTES, Ids.routine("batch", "sp_settle"));
            assertThat(call.to()).as("dialect=%s", dialect).isEqualTo("db:routine:BATCH.SP_SETTLE");
            assertThat(graph.hasNode(Ids.routine("batch.sp_settle"))).as("dialect=%s", dialect).isFalse();
            Fx.edge(graph, STEP, EXECUTES, Ids.routine("sp_plain"));
        }
    }
}
