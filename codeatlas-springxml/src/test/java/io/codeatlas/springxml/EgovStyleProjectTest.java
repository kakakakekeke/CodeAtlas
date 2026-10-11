package io.codeatlas.springxml;

import static io.codeatlas.core.model.EdgeKind.EXECUTES;
import static io.codeatlas.core.model.EdgeKind.INJECTS;
import static io.codeatlas.core.model.EdgeKind.NEXT;
import static io.codeatlas.core.model.EdgeKind.USES;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import java.net.URISyntaxException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 전자정부프레임워크 스타일로 만든 픽스처 프로젝트(src/test/resources/fixtures/egov-shop) 전체를 돌려 본다. */
class EgovStyleProjectTest {

    private static final String SPRING = "src/main/resources/egovframework/spring/";
    private static final String BATCH = "src/main/resources/egovframework/batch/";
    private static final String DISPATCHER = "src/main/webapp/WEB-INF/config/egovframework/springmvc/dispatcher-servlet.xml";

    private static AnalysisContext ctx;
    private static GraphModel graph;

    @BeforeAll
    static void analyze() throws URISyntaxException {
        Path root = Path.of(EgovStyleProjectTest.class.getResource("/fixtures/egov-shop").toURI());
        try (TestProject project = TestProject.at(root)) {
            ctx = project.run(new SpringXmlAnalyzer());
            graph = ctx.graph();
        }
    }

    @Test
    @DisplayName("F-017-AC12 픽스처 프로젝트의 모든 파일이 파싱되고 진단은 INFO뿐이다")
    void everythingParses() {
        assertThat(Fx.diagnostics(graph, DiagnosticCodes.PARSE_FAILED)).isEmpty();
        assertThat(graph.diagnostics()).allSatisfy(d -> assertThat(d.severity()).isEqualTo(Severity.INFO));
        // tx:advice, mvc:view-controller, aop:config, 프로파일로 나뉜 dataSource
        assertThat(graph.diagnostics()).extracting(d -> d.code()).containsOnly(
                DiagnosticCodes.UNSUPPORTED_SYNTAX, DiagnosticCodes.DUPLICATE_OBJECT_NAME);
    }

    @Test
    @DisplayName("F-017-AC1 뷰 리졸버의 prefix·suffix(p: 네임스페이스)와 props 값이 빈 attrs에 남는다")
    void dispatcherServletBeans() {
        Node resolver = Fx.node(graph, Ids.springBean("org.springframework.web.servlet.view.UrlBasedViewResolver#0"));
        assertThat(resolver.attrString("property.prefix")).isEqualTo("/WEB-INF/jsp/egovframework/shop/");
        assertThat(resolver.attrString("property.suffix")).isEqualTo(".jsp");
        assertThat(resolver.location().file()).isEqualTo(DISPATCHER);

        Node exceptionResolver = Fx.node(graph,
                Ids.springBean("org.springframework.web.servlet.handler.SimpleMappingExceptionResolver#0"));
        assertThat(exceptionResolver.attrList("property.exceptionMappings")).containsExactly(
                "org.springframework.dao.DataAccessException=cmmn/dataAccessFailure",
                "org.egovframe.rte.fdl.cmmn.exception.EgovBizException=cmmn/egovError");
        assertThat(Fx.node(graph, Ids.file(DISPATCHER)).attrList("componentScan")).containsExactly("egovframework.shop");
    }

    @Test
    @DisplayName("F-017-AC2 다른 파일에 정의된 빈과 애너테이션 빈으로 가는 주입이 구분되어 남는다")
    void crossFileInjection() {
        // context-mapper.xml -> context-datasource.xml (프로파일 둘이 같은 클래스라 해석된다)
        Edge dataSource = Fx.edge(graph, Ids.javaType("org.mybatis.spring.SqlSessionFactoryBean"), INJECTS,
                Ids.javaType("org.apache.commons.dbcp2.BasicDataSource"));
        assertThat(dataSource.location().file()).isEqualTo(SPRING + "context-mapper.xml");
        // 컬렉션 안의 ref (핸들러 매핑의 인터셉터 목록)
        Fx.edge(graph, Ids.javaType("org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping"), INJECTS,
                Ids.javaType("egovframework.shop.cmmn.web.LoginInterceptor"));
        // 애너테이션 빈(memberService)은 XML에 없다
        assertThat(Fx.edge(graph, Ids.javaType("egovframework.shop.cmmn.web.LoginInterceptor"), INJECTS, Ids.springBean("memberService"))
                .attrBoolean("unresolvedBeanRef")).isTrue();
        // name 속성으로 이름을 준 빈
        Fx.edge(graph, Ids.javaType("org.egovframe.rte.fdl.idgnr.impl.EgovTableIdGnrServiceImpl"), INJECTS,
                Ids.javaType("org.egovframe.rte.fdl.idgnr.impl.strategy.EgovIdGnrStrategyImpl"));
    }

    @Test
    @DisplayName("F-017-AC5 전자정부 MapperConfigurer의 basePackage와 SqlSessionFactoryBean 설정이 FILE attrs에 남는다")
    void mybatisSettings() {
        Node file = Fx.node(graph, Ids.file(SPRING + "context-mapper.xml"));
        assertThat(file.attrList("mybatisScan")).containsExactly("egovframework.shop.**.service.impl");
        assertThat(file.attrList("mybatisMapperLocations")).containsExactly("classpath:/egovframework/sqlmap/shop/mappers/**/*.xml");
        assertThat(file.attrString("mybatisConfigLocation")).isEqualTo("classpath:/egovframework/sqlmap/shop/sql-mapper-config.xml");
    }

    @Test
    @DisplayName("F-017-AC11 web.xml의 *.do 매핑이 남는다")
    void webXml() {
        Node file = Fx.node(graph, Ids.file("src/main/webapp/WEB-INF/web.xml"));
        assertThat(file.attrList("servletMappings")).containsExactly("*.do");
        assertThat(file.attrList("contextConfigLocations")).containsExactly("classpath*:egovframework/spring/context-*.xml");
    }

    @Test
    @DisplayName("F-017-AC9 다른 파일의 추상 잡·스텝을 parent로 물려받는다")
    void batchInheritanceAcrossFiles() {
        String job = Ids.batchJob("memberSyncJob");
        String sync = Ids.batchStep("memberSyncJob", "syncStep");

        Edge jobListener = Fx.edge(graph, job, USES, Ids.javaType("egovframework.shop.batch.listener.EgovJobListener"));
        assertThat(jobListener.attrString("inheritedFrom")).isEqualTo("eGovBaseJob");
        Edge stepListener = Fx.edge(graph, sync, USES, Ids.javaType("egovframework.shop.batch.listener.EgovStepListener"));
        assertThat(stepListener.attrString("inheritedFrom")).isEqualTo("eGovBaseStep");
        assertThat(stepListener.location().file()).isEqualTo(BATCH + "job/abstract/eGovBaseJob.xml");
        // parent 없는 스텝은 물려받지 않는다
        assertThat(graph.outgoing(Ids.batchStep("memberSyncJob", "reportStep"), USES)).extracting(Edge::to)
                .containsExactly(Ids.springBean("reportTasklet"));

        assertThat(Fx.node(graph, sync).attrString("commitInterval")).isEqualTo("#{jobParameters['commitInterval']}");
        assertThat(Fx.node(graph, sync).attrList("failOn")).containsExactly("*");
        assertThat(Fx.edge(graph, sync, NEXT, Ids.batchStep("memberSyncJob", "purgeStep")).attrString("on")).isEqualTo("COMPLETED");

        Node launcherFile = Fx.node(graph, Ids.file(BATCH + "context-batch-job-launcher.xml"));
        assertThat(launcherFile.attrList("importedFiles"))
                .containsExactly(BATCH + "job/abstract/eGovBaseJob.xml", BATCH + "job/memberSyncJob.xml");
    }

    @Test
    @DisplayName("F-017-AC10 스텝이 쓰는 리더·라이터의 구문, 인라인 SQL, 프로시저가 EXECUTES로 이어진다")
    void batchSql() {
        String sync = Ids.batchStep("memberSyncJob", "syncStep");
        String purge = Ids.batchStep("memberSyncJob", "purgeStep");

        Fx.edge(graph, sync, EXECUTES,
                Ids.mybatisStatement("egovframework.shop.batch.service.impl.MemberBatchMapper", "selectDormantMembers"));
        Fx.edge(graph, purge, EXECUTES, Ids.routine("SP_PURGE_WITHDRAWN_MEMBER"));

        assertThat(graph.nodes(NodeKind.SQL_STATEMENT)).hasSize(1);
        Node statement = graph.nodes(NodeKind.SQL_STATEMENT).iterator().next();
        assertThat(statement.location().file()).isEqualTo(BATCH + "job/memberSyncJob.xml");
        Fx.edge(graph, sync, EXECUTES, statement.id());
        Fx.edge(graph, purge, EXECUTES, statement.id());
        assertThat(ctx.embedded(EmbeddedSource.SQL)).singleElement().satisfies(sql -> {
            assertThat(sql.ownerNodeId()).isEqualTo(statement.id());
            assertThat(sql.text()).startsWith("INSERT INTO TB_MEMBER_HIST").endsWith("VALUES (:memberId, :gradeCd, SYSDATE)");
        });
    }
}
