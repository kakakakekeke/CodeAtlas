package io.codeatlas.springxml;

import static io.codeatlas.core.model.EdgeKind.EXECUTES;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.EmbeddedSource;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BatchSqlExecutionTest {

    private static final String PATH = "src/main/resources/egovframework/batch/job/salesJob.xml";
    private static final String NS = "com.a.batch.mapper.BatchMapper";

    private static final String XML = Fx.beans("""
              <batch:job id="salesJob">
                <batch:step id="mybatisStep" next="jdbcStep">
                  <batch:tasklet>
                    <batch:chunk reader="orderReader" writer="salesWriter" commit-interval="100"/>
                  </batch:tasklet>
                </batch:step>
                <batch:step id="jdbcStep" next="procStep">
                  <batch:tasklet>
                    <batch:chunk reader="jdbcReader" writer="jdbcWriter" commit-interval="100"/>
                  </batch:tasklet>
                </batch:step>
                <batch:step id="procStep" next="pagingStep">
                  <batch:tasklet>
                    <batch:chunk reader="procReader" writer="compositeWriter" commit-interval="100"/>
                  </batch:tasklet>
                </batch:step>
                <batch:step id="pagingStep" next="looseStep">
                  <batch:tasklet>
                    <batch:chunk reader="pagingReader" writer="jdbcWriter" commit-interval="100"/>
                  </batch:tasklet>
                </batch:step>
                <batch:step id="looseStep">
                  <batch:tasklet>
                    <batch:chunk reader="shortIdReader" writer="pkgProcReader" commit-interval="1"/>
                  </batch:tasklet>
                </batch:step>
              </batch:job>

              <bean id="orderReader" class="org.mybatis.spring.batch.MyBatisPagingItemReader" scope="step">
                <property name="sqlSessionFactory" ref="sqlSession"/>
                <property name="queryId" value="com.a.batch.mapper.BatchMapper.selectOrders"/>
                <property name="parameterValues">
                  <map>
                    <entry key="saleDt" value="#{jobParameters['saleDt']}"/>
                  </map>
                </property>
              </bean>
              <bean id="salesWriter" class="org.mybatis.spring.batch.MyBatisBatchItemWriter">
                <property name="sqlSessionFactory" ref="sqlSession"/>
                <property name="statementId" value="com.a.batch.mapper.BatchMapper.insertSales"/>
              </bean>
              <bean id="jdbcReader" class="org.springframework.batch.item.database.JdbcCursorItemReader">
                <property name="dataSource" ref="dataSource"/>
                <property name="sql">
                  <value><![CDATA[
                    SELECT ORDER_ID, ORDER_AMT
                      FROM TB_ORDER
                     WHERE ORDER_DT < '#{jobParameters['saleDt']}'
                  ]]></value>
                </property>
              </bean>
              <bean id="jdbcWriter" class="org.springframework.batch.item.database.JdbcBatchItemWriter">
                <property name="dataSource" ref="dataSource"/>
                <property name="sql" value="INSERT INTO TB_SALES_SUM (SALE_DT, AMT) VALUES (:saleDt, :amt)"/>
              </bean>
              <bean id="procReader" class="org.springframework.batch.item.database.StoredProcedureItemReader">
                <property name="procedureName" value="sp_daily_sales"/>
              </bean>
              <bean id="compositeWriter" class="org.springframework.batch.item.support.CompositeItemWriter">
                <property name="delegates">
                  <list>
                    <ref bean="salesWriter"/>
                    <bean class="org.mybatis.spring.batch.MyBatisBatchItemWriter">
                      <property name="statementId" value="com.a.batch.mapper.BatchMapper.updateOrderFlag"/>
                    </bean>
                  </list>
                </property>
              </bean>
              <bean id="pagingReader" class="org.springframework.batch.item.database.JdbcPagingItemReader">
                <property name="queryProvider">
                  <bean class="org.springframework.batch.item.database.support.SqlPagingQueryProviderFactoryBean">
                    <property name="selectClause" value="SELECT ORDER_ID, AMT"/>
                    <property name="fromClause" value="TB_ORDER"/>
                    <property name="whereClause" value="WHERE ORDER_DT = :orderDt"/>
                    <property name="sortKey" value="ORDER_ID"/>
                  </bean>
                </property>
              </bean>
              <bean id="shortIdReader" class="org.mybatis.spring.batch.MyBatisCursorItemReader">
                <property name="queryId" value="selectWithoutNamespace"/>
              </bean>
              <bean id="pkgProcReader" class="org.springframework.batch.item.database.StoredProcedureItemReader">
                <property name="procedureName" value="PKG_ORDER.CLOSE_ORDER"/>
              </bean>

              <!-- 어떤 스텝에서도 쓰지 않는 빈: 엣지 없이 attrs로만 남는다 -->
              <bean id="unusedReader" class="org.mybatis.spring.batch.MyBatisCursorItemReader">
                <property name="queryId" value="com.a.batch.mapper.BatchMapper.selectUnused"/>
              </bean>
              <bean id="unusedJdbcReader" class="org.springframework.batch.item.database.JdbcCursorItemReader">
                <property name="sql" value="SELECT 1 FROM DUAL"/>
              </bean>
            """);

    private static String step(String name) {
        return Ids.batchStep("salesJob", name);
    }

    @Test
    @DisplayName("F-017-AC10 MyBatis 리더·라이터의 queryId/statementId가 EXECUTES(STEP -> mybatis:stmt)로 남는다")
    void mybatisReaderWriter() {
        GraphModel graph = Fx.run(PATH, XML).graph();

        Edge read = Fx.edge(graph, step("mybatisStep"), EXECUTES, Ids.mybatisStatement(NS, "selectOrders"));
        assertThat(read.to()).isEqualTo("mybatis:stmt:com.a.batch.mapper.BatchMapper.selectOrders");
        assertThat(read.attrString("role")).isEqualTo("reader");
        assertThat(read.attrString("bean")).isEqualTo("orderReader");
        assertThat(read.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(read.location().startLine()).isEqualTo(Fx.lineOf(XML, "<property name=\"queryId\""));
        assertThat(Fx.edge(graph, step("mybatisStep"), EXECUTES, Ids.mybatisStatement(NS, "insertSales")).attrString("role"))
                .isEqualTo("writer");
        assertThat(graph.outgoing(step("mybatisStep"), EXECUTES)).hasSize(2);

        // 위임 라이터(CompositeItemWriter)는 delegates를 따라간다
        Fx.edge(graph, step("procStep"), EXECUTES, Ids.mybatisStatement(NS, "insertSales"));
        Fx.edge(graph, step("procStep"), EXECUTES, Ids.mybatisStatement(NS, "updateOrderFlag"));

        // 네임스페이스 없는 구문 ID는 추측하지 않고 미해결로 남긴다
        Edge loose = Fx.edge(graph, step("looseStep"), EXECUTES, Ids.unresolved("mybatis-statement", "selectWithoutNamespace"));
        assertThat(loose.confidence()).isEqualTo(Confidence.UNRESOLVED);

        // 빈 attrs에는 원문이 남는다
        Node reader = Fx.node(graph, Ids.springBean("orderReader"));
        assertThat(reader.attrString("property.queryId")).isEqualTo("com.a.batch.mapper.BatchMapper.selectOrders");
        assertThat(reader.attrList("property.parameterValues")).containsExactly("saleDt=#{jobParameters['saleDt']}");
    }

    @Test
    @DisplayName("F-017-AC10 JDBC 리더·라이터의 sql은 SQL_STATEMENT 노드, EXECUTES, sql 내장 소스로 등록된다")
    void jdbcReaderWriter() {
        AnalysisContext ctx = Fx.run(PATH, XML);
        GraphModel graph = ctx.graph();

        int readerLine = Fx.lineOf(XML, "<property name=\"sql\">");
        String readerStmt = Ids.sqlStatement(PATH, readerLine);
        Node stmt = Fx.node(graph, readerStmt);
        assertThat(stmt.kind()).isEqualTo(NodeKind.SQL_STATEMENT);
        assertThat(stmt.declared()).isTrue();
        assertThat(stmt.attrString("bean")).isEqualTo("jdbcReader");
        assertThat(stmt.location().file()).isEqualTo(PATH);
        assertThat(stmt.location().startLine()).isEqualTo(readerLine);
        assertThat(Fx.edge(graph, step("jdbcStep"), EXECUTES, readerStmt).attrString("role")).isEqualTo("reader");

        int writerLine = Fx.lineOf(XML, "<property name=\"sql\" value=\"INSERT");
        String writerStmt = Ids.sqlStatement(PATH, writerLine);
        assertThat(Fx.edge(graph, step("jdbcStep"), EXECUTES, writerStmt).attrString("role")).isEqualTo("writer");
        // 같은 라이터 빈을 두 스텝이 쓰면 구문 노드는 하나, 엣지는 스텝마다
        Fx.edge(graph, step("pagingStep"), EXECUTES, writerStmt);

        // JdbcPagingItemReader: queryProvider의 절을 이어 붙인다
        int pagingLine = Fx.lineOf(XML, "<property name=\"selectClause\"");
        String pagingStmt = Ids.sqlStatement(PATH, pagingLine);
        Fx.edge(graph, step("pagingStep"), EXECUTES, pagingStmt);
        assertThat(Fx.node(graph, pagingStmt).attrBoolean("composed")).isTrue();

        List<EmbeddedSource> embedded = ctx.embedded(EmbeddedSource.SQL);
        assertThat(embedded).extracting(EmbeddedSource::ownerNodeId).containsExactlyInAnyOrder(readerStmt, writerStmt, pagingStmt);
        EmbeddedSource readerSql = embedded.stream().filter(e -> e.ownerNodeId().equals(readerStmt)).findFirst().orElseThrow();
        assertThat(readerSql.text()).startsWith("SELECT ORDER_ID, ORDER_AMT")
                .contains("FROM TB_ORDER").endsWith("WHERE ORDER_DT < '#{jobParameters['saleDt']}'");
        assertThat(readerSql.origin().file()).isEqualTo(PATH);
        assertThat(readerSql.origin().startLine()).isEqualTo(readerLine);
        EmbeddedSource writerSql = embedded.stream().filter(e -> e.ownerNodeId().equals(writerStmt)).findFirst().orElseThrow();
        // :name 바인드 변수는 그대로 둔다
        assertThat(writerSql.text()).isEqualTo("INSERT INTO TB_SALES_SUM (SALE_DT, AMT) VALUES (:saleDt, :amt)");
        EmbeddedSource pagingSql = embedded.stream().filter(e -> e.ownerNodeId().equals(pagingStmt)).findFirst().orElseThrow();
        assertThat(pagingSql.text()).isEqualTo("SELECT ORDER_ID, AMT FROM TB_ORDER WHERE ORDER_DT = :orderDt");

        assertThat(graph.nodes(NodeKind.SQL_STATEMENT)).hasSize(3);
    }

    @Test
    @DisplayName("F-017-AC10 StoredProcedureItemReader의 procedureName이 EXECUTES(STEP -> db:routine)로 남는다")
    void storedProcedureReader() {
        GraphModel graph = Fx.run(PATH, XML).graph();

        Edge call = Fx.edge(graph, step("procStep"), EXECUTES, Ids.routine("SP_DAILY_SALES"));
        assertThat(call.to()).isEqualTo("db:routine:SP_DAILY_SALES");
        assertThat(call.attrString("procedureName")).isEqualTo("sp_daily_sales");
        Node routine = Fx.node(graph, call.to());
        assertThat(routine.kind()).isEqualTo(NodeKind.DB_ROUTINE);
        assertThat(routine.declared()).isFalse();

        // A.B 꼴은 패키지 소속 루틴으로 본다
        Fx.edge(graph, step("looseStep"), EXECUTES, Ids.routine("PKG_ORDER", "CLOSE_ORDER"));
    }

    @Test
    @DisplayName("F-017-AC10 어떤 스텝도 쓰지 않는 리더 빈에서는 EXECUTES와 구문 노드가 생기지 않는다")
    void unusedBeansProduceNoEdges() {
        AnalysisContext ctx = Fx.run(PATH, XML);
        GraphModel graph = ctx.graph();

        assertThat(graph.edges(EXECUTES)).noneMatch(e -> e.to().endsWith("selectUnused"));
        assertThat(graph.hasNode(Ids.sqlStatement(PATH, Fx.lineOf(XML, "SELECT 1 FROM DUAL")))).isFalse();
        assertThat(ctx.embedded(EmbeddedSource.SQL)).noneMatch(e -> e.text().contains("DUAL"));
        assertThat(graph.edges(EXECUTES)).allMatch(e -> e.from().startsWith("batch:step:"));
        assertThat(Fx.node(graph, Ids.springBean("unusedReader")).attrString("property.queryId"))
                .isEqualTo("com.a.batch.mapper.BatchMapper.selectUnused");
        assertThat(Fx.node(graph, Ids.springBean("unusedJdbcReader")).attrString("property.sql")).isEqualTo("SELECT 1 FROM DUAL");
    }
}
