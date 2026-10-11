package io.codeatlas.mybatis;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import java.util.List;

/** 테스트 공통 도우미. */
final class Mb {

    static final String HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n";

    private Mb() {
    }

    /** 선언과 DOCTYPE을 붙인 매퍼 XML. */
    static String mapper(String namespace, String body) {
        return HEADER + "<mapper namespace=\"" + namespace + "\">\n" + body + "\n</mapper>\n";
    }

    static AnalysisContext run(TestProject project) {
        return project.run(new MyBatisAnalyzer());
    }

    /** 매퍼 파일 하나짜리 프로젝트를 분석한다. */
    static AnalysisContext analyze(String namespace, String body) {
        try (TestProject project = TestProject.create().file("mapper/Test.xml", mapper(namespace, body))) {
            return run(project);
        }
    }

    static Node stmt(AnalysisContext ctx, String namespace, String id) {
        return ctx.graph().node(Ids.mybatisStatement(namespace, id)).orElseThrow(
                () -> new AssertionError("구문 노드 없음: " + namespace + "." + id));
    }

    /** 구문의 모든 내장 소스(selectKey 포함). */
    static List<EmbeddedSource> sources(AnalysisContext ctx, String namespace, String id) {
        String owner = Ids.mybatisStatement(namespace, id);
        return ctx.embedded(EmbeddedSource.SQL).stream().filter(s -> s.ownerNodeId().equals(owner)).toList();
    }

    /** selectKey가 아닌 전개본. */
    static List<EmbeddedSource> variants(AnalysisContext ctx, String namespace, String id) {
        return sources(ctx, namespace, id).stream().filter(s -> !"true".equals(s.attrs().get("selectKey"))).toList();
    }

    /** selectKey가 아닌 전개본의 본문(공백 정규화). */
    static List<String> texts(AnalysisContext ctx, String namespace, String id) {
        return variants(ctx, namespace, id).stream().map(s -> norm(s.text())).toList();
    }

    /** 전개본이 하나인 구문의 본문(공백 정규화). */
    static String text(AnalysisContext ctx, String namespace, String id) {
        List<String> texts = texts(ctx, namespace, id);
        if (texts.size() != 1) {
            throw new AssertionError("전개본이 하나가 아님: " + texts);
        }
        return texts.get(0);
    }

    static String norm(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    static List<Diagnostic> diags(AnalysisContext ctx, String code) {
        return ctx.graph().diagnostics().stream().filter(d -> d.code().equals(code)).toList();
    }

    static List<Edge> edges(AnalysisContext ctx, String from, EdgeKind kind) {
        return ctx.graph().outgoing(from, kind);
    }

    /** 본문에서 token이 처음 나오는 곳의 {줄, 열}(1부터). 줄 구분은 \n. */
    static int[] lineCol(String text, String token) {
        int offset = text.indexOf(token);
        if (offset < 0) {
            throw new AssertionError("토큰 없음: " + token);
        }
        int line = 1;
        int col = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new int[] {line, col};
    }

    /** 내장 소스 안의 token 위치를 호스트 파일 위치로 바꾼 {줄, 열}. */
    static int[] hostLineCol(EmbeddedSource source, String token) {
        int[] lc = lineCol(source.text(), token);
        SourceLocation loc = source.locationOf(lc[0], lc[1], lc[0], lc[1] + token.length());
        return new int[] {loc.startLine(), loc.startColumn()};
    }
}
