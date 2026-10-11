package io.codeatlas.springxml;

import static io.codeatlas.core.model.EdgeKind.DEFINES_BEAN;
import static io.codeatlas.core.model.EdgeKind.INJECTS;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.Severity;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BeanConfigFeaturesTest {

    private static final String COMMON = "src/main/resources/egovframework/spring/context-common.xml";
    private static final String DATASOURCE = "src/main/resources/egovframework/spring/context-datasource.xml";

    @Test
    @DisplayName("F-017-AC4 parent 상속, alias, util 컬렉션, import, component-scan, 플레이스홀더를 해석한다")
    void inheritanceAliasUtilImportScan() {
        String common = Fx.beans("""
                  <import resource="context-datasource.xml"/>
                  <import resource="classpath:egovframework/spring/context-${env}.xml"/>
                  <context:component-scan base-package="com.a.service, com.a.web"/>
                  <context:property-placeholder location="classpath:/egovframework/egovProps/globals.properties"/>

                  <bean id="baseDao" abstract="true">
                    <property name="dataSource" ref="dataSource"/>
                    <property name="fetchSize" value="100"/>
                  </bean>
                  <bean id="orderDao" parent="baseDao" class="com.a.OrderDao">
                    <property name="fetchSize" value="${dao.fetchSize}"/>
                  </bean>
                  <bean id="genericDao" class="com.a.GenericDao" scope="prototype">
                    <property name="dataSource" ref="ds"/>
                  </bean>
                  <bean id="memberDao" parent="genericDao">
                    <property name="tableName" value="TB_MEMBER"/>
                  </bean>

                  <alias name="dataSource" alias="ds"/>
                  <bean id="report" class="com.a.Report">
                    <property name="source" ref="ds"/>
                    <property name="codes" ref="codeList"/>
                  </bean>
                  <util:list id="codeList">
                    <value>A</value>
                    <value>B</value>
                    <ref bean="extraCode"/>
                  </util:list>
                  <util:properties id="appProps" location="classpath:app.properties"/>
                """);
        String datasource = Fx.beans("""
                  <bean id="dataSource" class="org.apache.commons.dbcp2.BasicDataSource" destroy-method="close">
                    <property name="url" value="${Globals.Url}"/>
                  </bean>
                """);
        GraphModel graph = Fx.run(COMMON, common, DATASOURCE, datasource).graph();
        String dataSourceType = Ids.javaType("org.apache.commons.dbcp2.BasicDataSource");

        // 추상 템플릿 빈: 노드는 있지만 클래스가 없어 DEFINES_BEAN, INJECTS를 만들지 않는다
        Node baseDao = Fx.node(graph, Ids.springBean("baseDao"));
        assertThat(baseDao.attrBoolean("abstract")).isTrue();
        assertThat(baseDao.attrs()).doesNotContainKey("class");
        assertThat(graph.incoming(baseDao.id(), DEFINES_BEAN)).isEmpty();
        assertThat(graph.outgoing(baseDao.id(), INJECTS)).isEmpty();

        // 자식: 부모 프로퍼티 병합(자식 값이 덮어쓴다), 상속한 주입은 자식 클래스에서 나간다
        Node orderDao = Fx.node(graph, Ids.springBean("orderDao"));
        assertThat(orderDao.attrString("class")).isEqualTo("com.a.OrderDao");
        assertThat(orderDao.attrString("parent")).isEqualTo("baseDao");
        assertThat(orderDao.attrString("property.fetchSize")).isEqualTo("${dao.fetchSize}");
        Edge inherited = Fx.edge(graph, Ids.javaType("com.a.OrderDao"), INJECTS, dataSourceType);
        assertThat(inherited.attrString("property")).isEqualTo("dataSource");
        assertThat(inherited.attrString("qualifier")).isEqualTo("dataSource");
        assertThat(inherited.attrString("inheritedFrom")).isEqualTo("baseDao");

        // class 없는 자식은 부모의 class와 scope를 물려받는다
        Node memberDao = Fx.node(graph, Ids.springBean("memberDao"));
        assertThat(memberDao.attrString("class")).isEqualTo("com.a.GenericDao");
        assertThat(memberDao.attrString("scope")).isEqualTo("prototype");
        assertThat(memberDao.attrString("property.tableName")).isEqualTo("TB_MEMBER");
        Fx.edge(graph, Ids.javaType("com.a.GenericDao"), DEFINES_BEAN, memberDao.id());
        Fx.edge(graph, Ids.javaType("com.a.GenericDao"), DEFINES_BEAN, Ids.springBean("genericDao"));

        // alias로 참조해도 원래 빈의 클래스로 해석한다(다른 파일에 정의된 빈)
        Edge viaAlias = Fx.edge(graph, Ids.javaType("com.a.Report"), INJECTS, dataSourceType);
        assertThat(viaAlias.attrString("qualifier")).isEqualTo("ds");
        assertThat(Fx.node(graph, Ids.springBean("dataSource")).attrList("aliases")).containsExactly("ds");
        Fx.edge(graph, Ids.javaType("com.a.GenericDao"), INJECTS, dataSourceType);

        // util 컬렉션은 빈으로 잡는다. 클래스가 프로젝트 타입이 아니라 참조는 빈 노드로 간다
        Node codeList = Fx.node(graph, Ids.springBean("codeList"));
        assertThat(codeList.kind()).isEqualTo(NodeKind.SPRING_BEAN);
        assertThat(codeList.attrString("element")).isEqualTo("util:list");
        assertThat(codeList.attrList("values")).containsExactly("A", "B");
        assertThat(Fx.edge(graph, Ids.javaType("com.a.Report"), INJECTS, codeList.id()).attrBoolean("unresolvedBeanRef")).isTrue();
        Edge element = Fx.edge(graph, codeList.id(), INJECTS, Ids.springBean("extraCode"));
        assertThat(element.attrBoolean("unresolvedBeanSource")).isTrue();
        assertThat(element.attrBoolean("unresolvedBeanRef")).isTrue();
        assertThat(Fx.node(graph, Ids.springBean("appProps")).attrString("location")).isEqualTo("classpath:app.properties");

        // 플레이스홀더는 원문 그대로
        Node dataSource = Fx.node(graph, Ids.springBean("dataSource"));
        assertThat(dataSource.attrString("property.url")).isEqualTo("${Globals.Url}");
        assertThat(dataSource.attrString("destroyMethod")).isEqualTo("close");

        // import, component-scan은 FILE 노드 attrs
        Node file = Fx.node(graph, Ids.file(COMMON));
        assertThat(file.attrList("imports"))
                .containsExactly("context-datasource.xml", "classpath:egovframework/spring/context-${env}.xml");
        assertThat(file.attrList("importedFiles")).containsExactly(DATASOURCE);
        assertThat(file.attrList("componentScan")).containsExactly("com.a.service", "com.a.web");
        assertThat(file.attrList("propertyLocations")).containsExactly("classpath:/egovframework/egovProps/globals.properties");
    }

    @Test
    @DisplayName("F-017-AC5 MyBatis 연결 설정이 attrs로 남고 MapperFactoryBean 참조는 mapperInterface 타입으로 해석된다")
    void mybatisBeans() {
        String path = "src/main/resources/egovframework/spring/context-mapper.xml";
        String xml = Fx.beans("""
                  <bean id="sqlSession" class="org.mybatis.spring.SqlSessionFactoryBean">
                    <property name="dataSource" ref="dataSource"/>
                    <property name="configLocation" value="classpath:/egovframework/sqlmap/mybatis-config.xml"/>
                    <property name="mapperLocations">
                      <list>
                        <value>classpath:/egovframework/sqlmap/mappers/**/*.xml</value>
                        <value>classpath:/egovframework/sqlmap/ext/*.xml</value>
                      </list>
                    </property>
                    <property name="typeAliasesPackage" value="com.a.vo"/>
                  </bean>
                  <bean class="org.mybatis.spring.mapper.MapperScannerConfigurer">
                    <property name="basePackage" value="com.a.mapper"/>
                    <property name="sqlSessionFactoryBeanName" value="sqlSession"/>
                  </bean>
                  <mybatis:scan base-package="com.a.batch.mapper"/>
                  <bean id="memberMapper" class="org.mybatis.spring.mapper.MapperFactoryBean">
                    <property name="mapperInterface" value="com.a.legacy.MemberMapper"/>
                    <property name="sqlSessionFactory" ref="sqlSession"/>
                  </bean>
                  <bean id="memberService" class="com.a.MemberServiceImpl">
                    <property name="memberMapper" ref="memberMapper"/>
                  </bean>
                """);
        GraphModel graph = Fx.run(path, xml).graph();

        Node factory = Fx.node(graph, Ids.springBean("sqlSession"));
        assertThat(factory.attrString("property.configLocation")).isEqualTo("classpath:/egovframework/sqlmap/mybatis-config.xml");
        assertThat(factory.attrList("property.mapperLocations"))
                .containsExactly("classpath:/egovframework/sqlmap/mappers/**/*.xml", "classpath:/egovframework/sqlmap/ext/*.xml");
        assertThat(factory.attrString("property.typeAliasesPackage")).isEqualTo("com.a.vo");
        Node scanner = Fx.node(graph, Ids.springBean("org.mybatis.spring.mapper.MapperScannerConfigurer#0"));
        assertThat(scanner.attrString("property.basePackage")).isEqualTo("com.a.mapper");

        Node file = Fx.node(graph, Ids.file(path));
        assertThat(file.attrList("mybatisMapperLocations"))
                .containsExactly("classpath:/egovframework/sqlmap/mappers/**/*.xml", "classpath:/egovframework/sqlmap/ext/*.xml");
        assertThat(file.attrString("mybatisConfigLocation")).isEqualTo("classpath:/egovframework/sqlmap/mybatis-config.xml");
        assertThat(file.attrList("mybatisTypeAliasesPackage")).containsExactly("com.a.vo");
        assertThat(file.attrList("mybatisScan")).containsExactly("com.a.mapper", "com.a.batch.mapper");

        // MapperFactoryBean: 주입받는 쪽이 보는 타입은 매퍼 인터페이스다
        Node mapper = Fx.node(graph, Ids.springBean("memberMapper"));
        assertThat(mapper.attrString("class")).isEqualTo("org.mybatis.spring.mapper.MapperFactoryBean");
        assertThat(mapper.attrString("mapperInterface")).isEqualTo("com.a.legacy.MemberMapper");
        Edge injects = Fx.edge(graph, Ids.javaType("com.a.MemberServiceImpl"), INJECTS, Ids.javaType("com.a.legacy.MemberMapper"));
        assertThat(injects.attrString("qualifier")).isEqualTo("memberMapper");
        Fx.edge(graph, Ids.javaType("com.a.legacy.MemberMapper"), DEFINES_BEAN, mapper.id());
        // 팩터리 빈 자신이 받는 주입은 팩터리 클래스에서 나간다
        Fx.edge(graph, Ids.javaType("org.mybatis.spring.mapper.MapperFactoryBean"), INJECTS,
                Ids.javaType("org.mybatis.spring.SqlSessionFactoryBean"));
    }

    @Test
    @DisplayName("F-017-AC6 aop:config는 INFO 진단만 남기고 mvc:interceptors 안의 빈은 일반 빈으로 잡는다. mvc:view-controller는 엔드포인트가 된다(F-017-AC16)")
    void mvcAndAop() {
        String path = "src/main/webapp/WEB-INF/config/egovframework/springmvc/dispatcher-servlet.xml";
        String xml = Fx.beans("""
                  <mvc:annotation-driven/>
                  <tx:annotation-driven transaction-manager="txManager"/>
                  <mvc:view-controller path="/" view-name="main/index"/>
                  <mvc:interceptors>
                    <mvc:interceptor>
                      <mvc:mapping path="/**"/>
                      <bean class="com.a.web.LoginInterceptor">
                        <property name="memberService" ref="memberService"/>
                      </bean>
                    </mvc:interceptor>
                    <bean id="localeChange" class="org.springframework.web.servlet.i18n.LocaleChangeInterceptor"/>
                  </mvc:interceptors>
                  <aop:config>
                    <aop:pointcut id="serviceMethod" expression="execution(* com.a..impl.*Impl.*(..))"/>
                    <aop:advisor advice-ref="txAdvice" pointcut-ref="serviceMethod"/>
                  </aop:config>
                """);
        GraphModel graph = Fx.run(path, xml).graph();

        // 2026-10-11 고쳐 씀(F-017-R16): 전에는 mvc:view-controller도 진단만 남겼다
        assertThat(graph.nodes(NodeKind.HTTP_ENDPOINT)).extracting(Node::id).containsExactly(Ids.httpEndpoint("GET", "/"));
        List<Diagnostic> unsupported = Fx.diagnostics(graph, DiagnosticCodes.UNSUPPORTED_SYNTAX);
        assertThat(unsupported).hasSize(1).allSatisfy(d -> assertThat(d.severity()).isEqualTo(Severity.INFO));
        assertThat(unsupported).anySatisfy(d -> {
            assertThat(d.message()).contains("aop:config");
            assertThat(d.location().startLine()).isEqualTo(Fx.lineOf(xml, "<aop:config>"));
        });

        Node interceptor = Fx.node(graph, Ids.springBean("com.a.web.LoginInterceptor#0"));
        assertThat(interceptor.attrString("class")).isEqualTo("com.a.web.LoginInterceptor");
        Fx.edge(graph, Ids.javaType("com.a.web.LoginInterceptor"), DEFINES_BEAN, interceptor.id());
        assertThat(Fx.edge(graph, Ids.javaType("com.a.web.LoginInterceptor"), INJECTS, Ids.springBean("memberService"))
                .attrBoolean("unresolvedBeanRef")).isTrue();
        Fx.node(graph, Ids.springBean("localeChange"));
    }

    @Test
    @DisplayName("F-017-AC16 mvc:view-controller는 HTTP_ENDPOINT(GET <path>, viewController=true)가 되고 view-name을 viewNames에 남긴다. 핸들러 메서드가 없으므로 ROUTES_TO는 만들지 않는다")
    void viewControllerBecomesEndpoint() {
        String path = "src/main/webapp/WEB-INF/spring-mvc.xml";
        String xml = Fx.beans("""
                  <mvc:view-controller path="/" view-name="/index.jsp"/>
                  <mvc:view-controller path="cmmn/validator.do" view-name="cmmn/validator"/>
                  <mvc:view-controller path="/old" view-name="redirect:/new"/>
                  <mvc:view-controller path="/implicit"/>
                  <mvc:view-controller view-name="nowhere"/>
                """);
        GraphModel graph = Fx.run(path, xml).graph();

        Node root = graph.node(Ids.httpEndpoint("GET", "/")).orElseThrow();
        assertThat(root.declared()).isTrue();
        assertThat(root.attrString("httpMethod")).isEqualTo("GET");
        assertThat(root.attrString("path")).isEqualTo("/");
        assertThat(root.attrBoolean("viewController")).isTrue();
        assertThat(root.attrList("viewNames")).containsExactly("/index.jsp");
        assertThat(root.attrList("viewDeclarations")).containsExactly(
                path + ":" + Fx.lineOf(xml, "<mvc:view-controller path=\"/\"") + ":" + root.location().startColumn() + " /index.jsp");
        assertThat(root.location().file()).isEqualTo(path);
        assertThat(root.location().startLine()).isEqualTo(Fx.lineOf(xml, "<mvc:view-controller path=\"/\""));
        // 경로는 /로 시작하게 정규화한다
        assertThat(graph.node(Ids.httpEndpoint("GET", "/cmmn/validator.do")).orElseThrow().attrList("viewNames")).containsExactly("cmmn/validator");
        assertThat(graph.node(Ids.httpEndpoint("GET", "/old")).orElseThrow().attrList("viewNames")).containsExactly("redirect:/new");
        // view-name이 없으면 Spring이 경로에서 뷰 이름을 정한다: 엔드포인트만 만들고 viewNames는 남기지 않는다
        assertThat(graph.node(Ids.httpEndpoint("GET", "/implicit")).orElseThrow().attrs()).doesNotContainKey("viewNames");
        // path가 없는 요소는 엔드포인트를 만들 수 없다
        assertThat(graph.nodes(NodeKind.HTTP_ENDPOINT)).hasSize(4);
        assertThat(graph.edges(io.codeatlas.core.model.EdgeKind.ROUTES_TO)).isEmpty();
        assertThat(Fx.diagnostics(graph, DiagnosticCodes.UNSUPPORTED_SYNTAX)).isEmpty();
    }

    @Test
    @DisplayName("F-017-AC16 여러 모듈의 설정 파일이 같은 경로를 선언하면 엔드포인트 노드는 하나이고 viewDeclarations에 선언마다 <파일>:<줄>:<열> <view-name>이 쌓인다")
    void viewControllersInSeveralModules() {
        String xml = Fx.beans("""
                  <mvc:view-controller path="/" view-name="/index.jsp"/>
                """);
        try (io.codeatlas.core.testing.TestProject project = io.codeatlas.core.testing.TestProject.create()
                .file("web-a/src/main/resources/springMVC-servlet.xml", xml)
                .file("web-b/src/main/resources/springMVC-servlet.xml", xml.replace("/index.jsp", "/main"))) {
            GraphModel graph = project.run(new SpringXmlAnalyzer()).graph();

            assertThat(graph.nodes(NodeKind.HTTP_ENDPOINT)).hasSize(1);
            Node root = graph.node(Ids.httpEndpoint("GET", "/")).orElseThrow();
            assertThat(root.attrList("viewNames")).containsExactly("/index.jsp", "/main");
            assertThat(root.attrList("viewDeclarations")).hasSize(2);
            assertThat(root.attrList("viewDeclarations").get(0)).startsWith("web-a/src/main/resources/springMVC-servlet.xml:").endsWith(" /index.jsp");
            assertThat(root.attrList("viewDeclarations").get(1)).startsWith("web-b/src/main/resources/springMVC-servlet.xml:").endsWith(" /main");
        }
    }
}
