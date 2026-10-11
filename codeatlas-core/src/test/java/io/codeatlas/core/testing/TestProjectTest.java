package io.codeatlas.core.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.Analyzer;
import io.codeatlas.core.spi.Dialect;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.spi.FileType;
import io.codeatlas.core.spi.Linker;
import io.codeatlas.core.spi.SourceFile;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestProjectTest {

    /** 매퍼 XML마다 구문 노드를 만들고 본문을 sql 내장 소스로 넘기는 가짜 생산자. */
    static class FakeProducer implements Analyzer {
        @Override public String name() { return "fake-producer"; }
        @Override public void analyze(AnalysisContext ctx) {
            for (SourceFile file : ctx.files().ofType(FileType.MYBATIS_MAPPER)) {
                String id = Ids.mybatisStatement("ns", file.fileName());
                ctx.graph().declare(Node.builder(id, NodeKind.SQL_STATEMENT).name(file.fileName()).location(file.location(1)).build());
                ctx.graph().addEdge(Edge.of(file.nodeId(), id, EdgeKind.CONTAINS));
                ctx.addEmbedded(EmbeddedSource.sql(id, file.readText(), file.location(1), false));
            }
        }
    }

    /** sql 내장 소스마다 주인 노드에서 테이블로 READS 엣지를 다는 가짜 소비자. */
    static class FakeConsumer implements Analyzer {
        final List<String> log;
        FakeConsumer(List<String> log) { this.log = log; }
        @Override public String name() { return "fake-consumer"; }
        @Override public int order() { return 200; }
        @Override public void analyze(AnalysisContext ctx) {
            log.add("consumer saw " + ctx.embedded("sql").size());
            for (EmbeddedSource src : ctx.embedded(EmbeddedSource.SQL)) {
                ctx.graph().reference(Ids.table("orders"), NodeKind.DB_TABLE, "ORDERS");
                ctx.graph().addEdge(Edge.builder(src.ownerNodeId(), Ids.table("orders"), EdgeKind.READS).location(src.origin()).build());
            }
        }
    }

    @Test
    @DisplayName("F-001-AC19 생산자가 등록한 내장 소스를 순서가 뒤인 소비자가 꺼내 주인 노드에 엣지를 단다")
    void runsAnalyzersAndLinkers() {
        List<String> log = new ArrayList<>();
        Linker linker = new Linker() {
            @Override public String name() { return "fake-linker"; }
            @Override public void link(AnalysisContext ctx) { log.add("linker saw " + ctx.graph().edges(EdgeKind.READS).size()); }
        };
        try (TestProject project = TestProject.create()
                .file("mapper/A.xml", "<mapper namespace=\"ns\"><select id=\"a\">select * from orders</select></mapper>")
                .file("src/B.java", "class B {}")) {

            AnalysisContext ctx = project.run(new FakeConsumer(log), linker, new FakeProducer());

            assertThat(log).containsExactly("consumer saw 1", "linker saw 1");
            assertThat(ctx.graph().node("file:mapper/A.xml")).get().satisfies(n -> {
                assertThat(n.kind()).isEqualTo(NodeKind.FILE);
                assertThat(n.attrs()).containsEntry("fileType", "MYBATIS_MAPPER");
            });
            assertThat(ctx.graph().node("file:src/B.java")).isPresent();
            assertThat(ctx.graph().edges("mybatis:stmt:ns.A.xml", EdgeKind.READS, "db:table:ORDERS")).hasSize(1);
            assertThat(ctx.projectRoot()).isEqualTo(project.root());
            assertThat(Files.exists(project.root().resolve("mapper/A.xml"))).isTrue();
        }
    }

    @Test
    @DisplayName("close하면 직접 만든 임시 디렉터리를 지운다")
    void closeDeletesTempDir() {
        TestProject project = TestProject.create().file("a/b/c.sql", "select 1");
        Path root = project.root();
        assertThat(root).isDirectory();
        project.close();
        assertThat(root).doesNotExist();
    }

    @Test
    @DisplayName("기존 디렉터리와 문자열 맵으로도 만들 수 있고 옵션과 인코딩을 줄 수 있다")
    void otherFactories(@TempDir Path dir) {
        TestProject project = TestProject.at(dir)
                .files(Map.of("db/a.sql", "select 1 from dual", "db/b.sql", "select 2 from dual"))
                .file("db/kr.sql", "-- 한글", Charset.forName("MS949"))
                .options(AnalysisOptions.builder().dialect(Dialect.DB2).exclude("db/b.sql").build());

        AnalysisContext ctx = project.context();

        assertThat(ctx.options().dialect()).isEqualTo(Dialect.DB2);
        assertThat(ctx.files().all()).extracting(SourceFile::relativePath).containsExactly("db/a.sql", "db/kr.sql");
        assertThat(ctx.files().byRelativePath("db/kr.sql").orElseThrow().readText()).isEqualTo("-- 한글");
        assertThat(ctx.graph().nodes(NodeKind.FILE)).hasSize(2);
        assertThat(project.context().graph()).isNotSameAs(ctx.graph());
        project.close();
        assertThat(dir).isDirectory();
    }

    @Test
    @DisplayName("분석기 예외는 삼키지 않고 테스트로 전달한다")
    void exceptionsPropagate() {
        Analyzer failing = new Analyzer() {
            @Override public String name() { return "boom"; }
            @Override public void analyze(AnalysisContext ctx) throws Exception { throw new java.io.IOException("boom"); }
        };
        try (TestProject project = TestProject.create()) {
            assertThatThrownBy(() -> project.run(failing)).hasMessageContaining("boom").hasRootCauseInstanceOf(java.io.IOException.class);
        }
    }
}
