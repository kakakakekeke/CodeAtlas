package io.codeatlas.jsp;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.Analyzer;
import io.codeatlas.core.spi.FileType;
import io.codeatlas.core.spi.SourceFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * JSP(.jsp .jspf .tag)와 순수 HTML을 읽어 페이지 노드, 포함 관계, 폼·링크 요청을 그래프에 쓰고
 * 인라인 스크립트와 이벤트 핸들러를 {@code js} 내장 소스로 등록한다(F-011).
 * JavaScript는 파싱하지 않는다. 내장 소스는 order가 뒤인 web 분석기가 꺼내 분석한다.
 */
public final class JspAnalyzer implements Analyzer {

    @Override
    public String name() {
        return "jsp";
    }

    @Override
    public int order() {
        return ORDER_PRODUCER;
    }

    /** 벤더 폴더의 페이지를 건너뛰었음을 알리는 진단 코드(INFO, 폴더마다 1건). web의 것과 같은 코드다. */
    static final String FILE_SKIPPED = "FILE_SKIPPED";

    @Override
    public void analyze(AnalysisContext ctx) {
        ProjectIndex index = new ProjectIndex(ctx);
        VendorPaths vendor = new VendorPaths(ctx.options().extra());
        // 벤더 폴더 → 건너뛴 페이지 수. 진단은 파일마다가 아니라 폴더마다 1건이다.
        Map<String, Map<String, Integer>> skipped = new TreeMap<>();
        Layouts layouts = new Layouts();
        List<String> analyzed = new ArrayList<>();
        for (SourceFile file : ctx.files().ofType(FileType.JSP, FileType.HTML)) {
            ctx.progress(file.relativePath());
            try {
                VendorPaths.Skipped vendorPage = vendor.skipped(file.relativePath());
                PageAnalysis page = new PageAnalysis(ctx, index, file, layouts);
                if (vendorPage != null) {
                    page.declareSkipped("vendor");
                    skipped.computeIfAbsent(vendorPage.root(), k -> new java.util.HashMap<>())
                            .merge(vendorPage.reason(), 1, Integer::sum);
                } else {
                    page.run();
                    analyzed.add(file.relativePath());
                }
            } catch (RuntimeException | StackOverflowError e) {
                // 파일 하나의 실패가 전체를 멈추지 않는다
                ctx.graph().report(Diagnostic.error(DiagnosticCodes.PARSE_FAILED,
                        "JSP 분석 실패: " + file.relativePath() + " (" + e + ")", file.location()));
            }
        }
        try {
            // 레이아웃 프레임워크(SiteMesh, Tiles)가 붙이는 페이지 관계. 페이지와 포함 관계를 다 본 뒤에 만든다
            layouts.finish(ctx, index, analyzed);
        } catch (RuntimeException | StackOverflowError e) {
            ctx.graph().report(Diagnostic.warn(DiagnosticCodes.PARSE_FAILED, "레이아웃 설정 분석 실패 (" + e + ")", null));
        }
        // 요약 진단에 판정 근거를 붙인다(web의 벤더 스크립트 진단과 같은 꼴): 근거가 하나면 낱말만, 섞였으면 근거마다 건수
        skipped.forEach((directory, reasons) -> {
            int total = 0;
            StringBuilder why = new StringBuilder();
            for (String reason : VendorPaths.REASONS) {
                Integer count = reasons.get(reason);
                if (count != null) {
                    total += count;
                    why.append(why.length() == 0 ? "" : ", ").append(reason).append(reasons.size() > 1 ? " " + count : "");
                }
            }
            ctx.graph().report(Diagnostic.info(FILE_SKIPPED, "벤더 페이지 " + total + "개 건너뜀(" + why + "): " + directory,
                    SourceLocation.ofFile(directory)));
        });
    }
}
