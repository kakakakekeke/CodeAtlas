package io.codeatlas.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.testing.TestProject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TypeAliasTest {

    private static final String NS = "com.a.TypeMapper";

    private static String javaClass(String pkg, String name) {
        return "package " + pkg + ";\n\npublic class " + name + " {\n}\n";
    }

    private static List<Edge> usesType(AnalysisContext ctx, String id) {
        return Mb.edges(ctx, Ids.mybatisStatement(NS, id), EdgeKind.USES_TYPE);
    }

    @Test
    @DisplayName("F-009-AC17 typeAlias, FQN은 RESOLVED 엣지, 내장 별칭과 JDK 타입은 엣지 없음")
    void configAliasFqnAndBuiltins() {
        String mapperXml = Mb.mapper(NS, """
                  <insert id="ins" parameterType="OrderVO">INSERT INTO TB_A (A) VALUES (#{a})</insert>
                  <select id="sel" resultType="com.a.vo.ItemVO">SELECT A FROM TB_A</select>
                  <select id="map" resultType="hashmap" parameterType="_int">SELECT A FROM TB_A</select>
                  <select id="jdk" parameterType="java.util.Map" resultType="java.lang.String">SELECT A FROM TB_A</select>
                  <select id="both" parameterType="orderVO" resultType="orderVO">SELECT A FROM TB_A</select>
                  <select id="arr" parameterType="string[]" resultType="orderVO[]">SELECT A FROM TB_A</select>
                """);
        try (TestProject project = TestProject.create()
                .file("src/main/resources/mybatis-config.xml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <!DOCTYPE configuration PUBLIC "-//mybatis.org//DTD Config 3.0//EN" "http://mybatis.org/dtd/mybatis-3-config.dtd">
                        <configuration>
                          <settings><setting name="mapUnderscoreToCamelCase" value="true"/></settings>
                          <typeAliases>
                            <typeAlias alias="orderVO" type="com.a.vo.OrderVO"/>
                            <typeAlias type="com.a.vo.NoAliasVO"/>
                          </typeAliases>
                        </configuration>
                        """)
                .file("src/main/resources/mapper/TypeMapper.xml", mapperXml)) {
            AnalysisContext ctx = Mb.run(project);

            assertThat(usesType(ctx, "ins")).singleElement().satisfies(e -> {
                assertThat(e.to()).isEqualTo(Ids.javaType("com.a.vo.OrderVO"));
                assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED);
                assertThat(e.attrString("role")).isEqualTo("parameter");
                assertThat(e.attrString("alias")).isEqualTo("OrderVO");
                int[] at = Mb.lineCol(mapperXml, "parameterType=\"OrderVO\"");
                assertThat(e.location().startLine()).isEqualTo(at[0]);
                assertThat(e.location().startColumn()).isEqualTo(at[1]);
            });
            assertThat(usesType(ctx, "sel")).singleElement().satisfies(e -> {
                assertThat(e.to()).isEqualTo(Ids.javaType("com.a.vo.ItemVO"));
                assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED);
                assertThat(e.attrString("role")).isEqualTo("result");
                assertThat(e.attrs()).doesNotContainKey("alias");
            });
            // 소스가 없는 타입은 참조 노드로 둔다(마무리 단계에서 미해결로 바뀌지 않게)
            Node itemVo = ctx.graph().node(Ids.javaType("com.a.vo.ItemVO")).orElseThrow();
            assertThat(itemVo.kind()).isEqualTo(NodeKind.JAVA_TYPE);
            assertThat(itemVo.declared()).isFalse();
            assertThat(itemVo.name()).isEqualTo("ItemVO");

            assertThat(usesType(ctx, "map")).isEmpty();
            assertThat(usesType(ctx, "jdk")).isEmpty();
            assertThat(Mb.stmt(ctx, NS, "map").attrString("resultType")).isEqualTo("hashmap");

            // 같은 타입이라도 역할이 다르면 엣지가 둘이다
            assertThat(usesType(ctx, "both")).extracting(e -> e.attrString("role")).containsExactlyInAnyOrder("parameter", "result");
            assertThat(usesType(ctx, "arr")).singleElement()
                    .satisfies(e -> assertThat(e.to()).isEqualTo(Ids.javaType("com.a.vo.OrderVO")));
            assertThat(ctx.graph().diagnostics()).isEmpty();
            assertThat(ctx.graph().resolveDanglingEdges()).isZero();
        }
    }

    @Test
    @DisplayName("F-009-AC17 alias를 생략한 typeAlias는 단순 이름이 별칭이다")
    void typeAliasWithoutAlias() {
        try (TestProject project = TestProject.create()
                .file("mybatis-config.xml", """
                        <configuration>
                          <typeAliases><typeAlias type="com.a.vo.NoAliasVO"/></typeAliases>
                        </configuration>
                        """)
                .file("mapper/TypeMapper.xml", Mb.mapper(NS, """
                          <select id="sel" resultType="noAliasVO">SELECT A FROM TB_A</select>
                        """))) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(usesType(ctx, "sel")).extracting(Edge::to).containsExactly(Ids.javaType("com.a.vo.NoAliasVO"));
        }
    }

    @Test
    @DisplayName("F-009-AC18 별칭 패키지: config의 package, Spring typeAliasesPackage, properties, yml")
    void aliasPackages() {
        try (TestProject project = TestProject.create()
                .file("src/main/resources/mybatis-config.xml", """
                        <configuration>
                          <typeAliases>
                            <package name="com.a.vo"/>
                          </typeAliases>
                        </configuration>
                        """)
                .file("src/main/resources/spring/context-mapper.xml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <beans xmlns="http://www.springframework.org/schema/beans">
                          <bean id="sqlSessionFactory" class="org.mybatis.spring.SqlSessionFactoryBean">
                            <property name="dataSource" ref="dataSource"/>
                            <property name="typeAliasesPackage" value="com.b.model; com.b.other"/>
                          </bean>
                        </beans>
                        """)
                .file("src/main/resources/application.properties", """
                        spring.datasource.url=jdbc:oracle:thin:@x
                        mybatis.type-aliases-package = com.c.dto
                        """)
                .file("src/main/resources/application.yml", """
                        spring:
                          application:
                            name: shop
                        mybatis:
                          mapper-locations: classpath:mapper/**/*.xml
                          type-aliases-package: com.d.dto
                        """)
                .file("src/main/java/com/a/vo/OrderVO.java", javaClass("com.a.vo", "OrderVO"))
                .file("src/main/java/com/a/vo/sub/DeepVO.java", javaClass("com.a.vo.sub", "DeepVO"))
                .file("src/main/java/com/b/model/Member.java", javaClass("com.b.model", "Member"))
                .file("src/main/java/com/b/other/Other.java", javaClass("com.b.other", "Other"))
                .file("module-x/src/main/java/com/c/dto/PayDto.java", javaClass("com.c.dto", "PayDto"))
                .file("src/main/java/com/d/dto/ShipDto.java", javaClass("com.d.dto", "ShipDto"))
                .file("src/main/resources/mapper/TypeMapper.xml", Mb.mapper(NS, """
                          <select id="a" resultType="orderVO">SELECT A FROM TB_A</select>
                          <select id="a2" resultType="deepvo">SELECT A FROM TB_A</select>
                          <select id="b" resultType="member" parameterType="other">SELECT A FROM TB_A</select>
                          <select id="c" resultType="payDto">SELECT A FROM TB_A</select>
                          <select id="d" resultType="ShipDto">SELECT A FROM TB_A</select>
                        """))) {
            AnalysisContext ctx = Mb.run(project);

            assertThat(usesType(ctx, "a")).extracting(Edge::to).containsExactly(Ids.javaType("com.a.vo.OrderVO"));
            assertThat(usesType(ctx, "a2")).extracting(Edge::to).containsExactly(Ids.javaType("com.a.vo.sub.DeepVO"));
            assertThat(usesType(ctx, "b")).extracting(Edge::to)
                    .containsExactlyInAnyOrder(Ids.javaType("com.b.model.Member"), Ids.javaType("com.b.other.Other"));
            assertThat(usesType(ctx, "c")).extracting(Edge::to).containsExactly(Ids.javaType("com.c.dto.PayDto"));
            assertThat(usesType(ctx, "d")).extracting(Edge::to).containsExactly(Ids.javaType("com.d.dto.ShipDto"));
            assertThat(ctx.graph().edges(EdgeKind.USES_TYPE))
                    .allSatisfy(e -> assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED));
            assertThat(ctx.graph().diagnostics()).isEmpty();
        }
    }

    @Test
    @DisplayName("F-009-AC18 @MapperScan만 쓰는 프로젝트의 yml 한 줄 표기와 와일드카드 패키지도 읽는다")
    void flatYamlAndWildcardPackage() {
        try (TestProject project = TestProject.create()
                .file("src/main/resources/application.yaml", "mybatis.type-aliases-package: \"com.e.**.vo\"\n")
                .file("src/main/java/com/e/order/vo/OrdVO.java", javaClass("com.e.order.vo", "OrdVO"))
                .file("src/main/java/com/e/order/svc/Svc.java", javaClass("com.e.order.svc", "Svc"))
                .file("mapper/TypeMapper.xml", Mb.mapper(NS, """
                          <select id="a" resultType="ordVO">SELECT A FROM TB_A</select>
                        """))) {
            AnalysisContext ctx = Mb.run(project);
            assertThat(usesType(ctx, "a")).singleElement().satisfies(e -> {
                assertThat(e.to()).isEqualTo(Ids.javaType("com.e.order.vo.OrdVO"));
                assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED);
            });
        }
    }

    @Test
    @DisplayName("F-009-AC19 별칭이 아니면 유일한 단순 이름은 INFERRED, 여럿이거나 없으면 미해결")
    void simpleNameFallback() {
        try (TestProject project = TestProject.create()
                .file("src/main/java/com/x/member/MemberVO.java", javaClass("com.x.member", "MemberVO"))
                .file("src/main/java/com/x/a/DupVO.java", javaClass("com.x.a", "DupVO"))
                .file("src/main/java/com/x/b/DupVO.java", javaClass("com.x.b", "DupVO"))
                .file("mapper/TypeMapper.xml", Mb.mapper(NS, """
                          <select id="one" resultType="MemberVO">SELECT A FROM TB_A</select>
                          <select id="dup" resultType="DupVO">SELECT A FROM TB_A</select>
                          <select id="none" resultType="NoSuchVO">SELECT A FROM TB_A</select>
                        """))) {
            AnalysisContext ctx = Mb.run(project);

            assertThat(usesType(ctx, "one")).singleElement().satisfies(e -> {
                assertThat(e.to()).isEqualTo(Ids.javaType("com.x.member.MemberVO"));
                assertThat(e.confidence()).isEqualTo(Confidence.INFERRED);
                assertThat(e.attrString("role")).isEqualTo("result");
            });
            assertThat(usesType(ctx, "dup")).singleElement().satisfies(e -> {
                assertThat(e.to()).isEqualTo(Ids.unresolved("mybatis-type", "DupVO"));
                assertThat(e.confidence()).isEqualTo(Confidence.UNRESOLVED);
                assertThat(e.attrString("role")).isEqualTo("result");
            });
            assertThat(usesType(ctx, "none")).singleElement().satisfies(e -> {
                assertThat(e.to()).isEqualTo(Ids.unresolved("mybatis-type", "NoSuchVO"));
                assertThat(e.confidence()).isEqualTo(Confidence.UNRESOLVED);
            });
            assertThat(ctx.graph().node(Ids.unresolved("mybatis-type", "NoSuchVO")).orElseThrow().kind())
                    .isEqualTo(NodeKind.UNRESOLVED);
            assertThat(Mb.diags(ctx, DiagnosticCodes.UNRESOLVED_REFERENCE)).hasSize(2);
        }
    }

    @Test
    @DisplayName("F-009-AC20 resultMap의 type·javaType·ofType으로 USES_TYPE을 만들고 컬럼 대응과 중첩 select를 속성으로 남긴다")
    void resultMapTypes() {
        try (TestProject project = TestProject.create()
                .file("mybatis-config.xml", """
                        <configuration>
                          <typeAliases>
                            <typeAlias alias="orderVO" type="com.a.vo.OrderVO"/>
                            <typeAlias alias="itemVO" type="com.a.vo.ItemVO"/>
                          </typeAliases>
                        </configuration>
                        """)
                .file("mapper/TypeMapper.xml", Mb.mapper(NS, """
                          <resultMap id="baseMap" type="orderVO">
                            <id property="orderId" column="ORDER_ID"/>
                            <result property="orderDt" column="ORDER_DT"/>
                          </resultMap>
                          <resultMap id="orderMap" type="orderVO" extends="baseMap">
                            <result property="totalAmt" column="TOTAL_AMT"/>
                            <association property="member" javaType="com.a.vo.MemberVO" columnPrefix="M_">
                              <id property="memberId" column="MEMBER_ID"/>
                              <result property="memberNm" column="MEMBER_NM"/>
                            </association>
                            <collection property="items" ofType="itemVO" javaType="arraylist" select="selectItems" column="ORDER_ID"/>
                            <collection property="pays" resultMap="com.a.Other.payMap"/>
                          </resultMap>
                          <select id="sel" parameterType="long" resultMap="orderMap">SELECT * FROM TB_ORDER</select>
                          <select id="selectItems" resultType="itemVO">SELECT * FROM TB_ITEM WHERE ORDER_ID = #{id}</select>
                          <select id="missing" resultMap="noSuchMap">SELECT * FROM TB_ORDER</select>
                        """))
                .file("mapper/Other.xml", Mb.mapper("com.a.Other", """
                          <resultMap id="payMap" type="com.a.vo.PayVO">
                            <result property="payAmt" column="PAY_AMT"/>
                          </resultMap>
                        """))) {
            AnalysisContext ctx = Mb.run(project);

            List<Edge> edges = usesType(ctx, "sel");
            assertThat(edges).extracting(Edge::to).containsExactlyInAnyOrder(
                    Ids.javaType("com.a.vo.OrderVO"), Ids.javaType("com.a.vo.MemberVO"),
                    Ids.javaType("com.a.vo.ItemVO"), Ids.javaType("com.a.vo.PayVO"));
            assertThat(edges).allSatisfy(e -> {
                assertThat(e.attrString("role")).isEqualTo("resultMap");
                assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED);
            });

            Node sel = Mb.stmt(ctx, NS, "sel");
            assertThat(sel.attrString("resultMap")).isEqualTo("orderMap");
            assertThat(sel.attrList("resultColumns")).containsExactly(
                    "ORDER_ID=orderId", "ORDER_DT=orderDt", "TOTAL_AMT=totalAmt",
                    "M_MEMBER_ID=member.memberId", "M_MEMBER_NM=member.memberNm", "PAY_AMT=pays.payAmt");
            assertThat(sel.attrList("nestedSelects")).containsExactly(NS + ".selectItems");

            assertThat(ctx.graph().nodes()).noneMatch(n -> n.id().contains("resultMap") || n.id().contains("orderMap"));
            assertThat(Mb.edges(ctx, sel.id(), EdgeKind.INCLUDES)).isEmpty();

            assertThat(usesType(ctx, "missing")).singleElement().satisfies(e -> {
                assertThat(e.to()).isEqualTo(Ids.unresolved("mybatis-resultmap", NS + ".noSuchMap"));
                assertThat(e.confidence()).isEqualTo(Confidence.UNRESOLVED);
                assertThat(e.attrString("role")).isEqualTo("resultMap");
            });
            assertThat(Mb.diags(ctx, DiagnosticCodes.UNRESOLVED_REFERENCE)).hasSize(1);
        }
    }
}
