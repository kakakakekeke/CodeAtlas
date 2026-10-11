package io.codeatlas.springxml;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.scan.FileClassifier;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.Analyzer;
import io.codeatlas.core.spi.FileType;
import io.codeatlas.core.spi.SourceFile;
import io.codeatlas.springxml.XmlParser.XmlParseException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spring XML 빈 설정, Spring Batch XML 잡 설정, web.xml을 해석하는 분석기(F-017).
 *
 * <p>두 단계로 돈다. 먼저 파일마다 XML을 읽어 정의를 모으고({@link BeanParser}),
 * 그다음 프로젝트 전체의 빈 이름을 풀어({@link BeanRegistry}) 그래프에 쓴다.
 * 파일 하나가 실패해도 진단을 남기고 나머지를 계속한다.
 */
public final class SpringXmlAnalyzer implements Analyzer {

    @Override
    public String name() {
        return "springxml";
    }

    @Override
    public int order() {
        return ORDER_PRODUCER;
    }

    @Override
    public void analyze(AnalysisContext ctx) {
        GraphModel graph = ctx.graph();

        List<FileModel> models = new ArrayList<>();
        List<String> springXmlPaths = new ArrayList<>();
        for (SourceFile file : ctx.files().ofType(FileType.SPRING_XML)) {
            ctx.progress(file.relativePath());
            springXmlPaths.add(file.relativePath());
            XmlElement root = parse(file, graph);
            if (root == null) {
                continue;
            }
            try {
                FileModel model = new FileModel(file);
                new BeanParser(model, graph).parseRoot(root);
                models.add(model);
            } catch (RuntimeException e) {
                graph.report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "Spring XML 해석 실패: " + e, file.location()));
            }
        }

        ctx.progress("Spring XML 빈·배치 정의 " + models.size() + "개 파일 해석");
        BeanRegistry registry = new BeanRegistry(models, graph, ModulePaths.of(ctx.files()));
        BeanGraphWriter beanWriter = new BeanGraphWriter(registry, graph);
        BatchGraphWriter batchWriter = new BatchGraphWriter(ctx, registry, beanWriter, models);
        for (BeanDef def : registry.all()) {
            try {
                beanWriter.writeBean(def);
            } catch (RuntimeException e) {
                graph.report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "빈 정의 해석 실패(" + def.name + "): " + e, def.location));
            }
        }
        for (FileModel model : models) {
            try {
                beanWriter.writeFileAttrs(model, resolveImports(model, springXmlPaths, graph));
                batchWriter.write(model);
            } catch (RuntimeException e) {
                graph.report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "Spring XML 해석 실패: " + e, model.file.location()));
            }
        }

        for (SourceFile file : ctx.files().ofType(FileType.XML)) {
            ctx.progress(file.relativePath());
            analyzeWebXml(file, graph);
        }
    }

    /** 파일을 읽어 파싱한다. 실패하면 PARSE_FAILED 진단을 남기고 null. */
    private static XmlElement parse(SourceFile file, GraphModel graph) {
        try {
            return XmlParser.parse(file.readText());
        } catch (XmlParseException e) {
            graph.report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "XML 파싱 실패: " + e.getMessage(),
                    e.line > 0 ? file.location(e.line, e.column, e.line, e.column) : file.location()));
        } catch (UncheckedIOException e) {
            graph.report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "파일을 읽지 못함: " + e.getMessage(), file.location()));
        } catch (RuntimeException e) {
            graph.report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "XML 파싱 실패: " + e, file.location()));
        }
        return null;
    }

    private static List<String> resolveImports(FileModel model, List<String> springXmlPaths, GraphModel graph) {
        List<String> imported = new ArrayList<>();
        for (String resource : model.imports) {
            List<String> found = ImportResolver.resolve(resource, model.path(), springXmlPaths);
            if (found.isEmpty()) {
                graph.report(Diagnostic.info(DiagnosticCodes.UNRESOLVED_REFERENCE,
                        "import 대상 파일을 프로젝트에서 찾지 못함: " + resource, model.file.location()));
            }
            for (String path : found) {
                if (!imported.contains(path)) {
                    imported.add(path);
                }
            }
        }
        return imported;
    }

    /** 루트가 web-app인 XML만 읽는다. 다른 XML은 건드리지 않는다. */
    private static void analyzeWebXml(SourceFile file, GraphModel graph) {
        try {
            if (!"web-app".equals(FileClassifier.sniffXmlRoot(file.absolutePath()))) {
                return;
            }
        } catch (IOException | RuntimeException e) {
            return;   // 루트도 읽지 못하는 파일은 이 분석기의 대상이 아니다
        }
        XmlElement root = parse(file, graph);
        if (root == null || !root.localName.equals("web-app")) {
            return;
        }
        try {
            Map<String, Object> attrs = WebXmlReader.read(root);
            if (!attrs.isEmpty()) {
                graph.putAttrs(file.nodeId(), attrs);
            }
        } catch (RuntimeException e) {
            graph.report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED, "web.xml 해석 실패: " + e, file.location()));
        }
    }
}
