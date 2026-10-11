package io.codeatlas.web;

import static io.codeatlas.web.G.fn;
import static io.codeatlas.web.G.mod;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Severity;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.testing.TestProject;
import io.codeatlas.web.analysis.JsProjectAnalysis;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 벤더(서드파티) 스크립트 건너뛰기(F-010-R17). */
class JsAnalyzerVendorTest {

    private static final String WEB = "src/main/webapp";

    private static TestProject project() {
        return TestProject.create()
                // (a) 경로 세그먼트가 라이브러리 이름·벤더 폴더
                .file(WEB + "/static/ckeditor/plugins/link/dialogs/link.js", "function ckLink() { $.get('/ck/link'); }\n")
                .file(WEB + "/static/ckeditor/ckeditor_source.js", "function ckCore() {}\n")
                .file(WEB + "/static/jquery/jquery-ui.custom.js", "function widget() {}\n")
                .file(WEB + "/static/zTree_v3/js/tree.core.js", "function zt() {}\n")
                .file(WEB + "/static/bootstrap/2.3.1/docs/assets/js/application.js", "function docs() {}\n")
                .file(WEB + "/js/lib/thing.js", "function thing() {}\n")
                .file("vendor/dep.js", "function dep() {}\n")
                .file("frontend/src/vendor/dep2.js", "function dep2() {}\n")
                .file("src/main/resources/META-INF/resources/webjars/x/1.0/x.js", "function webjar() {}\n")
                // (b) 파일 이름이 <이름>-<버전>.js, 알려진 라이브러리 파일 이름
                .file(WEB + "/js/prototype-1.5.1.js", "function $(id) { return document.getElementById(id); }\n")
                .file(WEB + "/js/jquery-ui_1.12.1.custom.js", "function widget2() {}\n")
                .file(WEB + "/js/swfobject.js", "function embedSWF() {}\n")
                // (c) 머리 주석의 라이선스 배너
                .file(WEB + "/js/banner.js", "/*! Foo v1.2.3 | (c) Foo Authors | foo.org/license */\nfunction foo() {}\n")
                .file(WEB + "/js/licensed.js", "/**\n * @license Bar\n */\nfunction bar() {}\n")
                .file(WEB + "/js/mit.js", "// Baz\n// Copyright (c) 2014 Baz Authors\n// Released under the MIT license\nfunction baz() {}\n")
                .file(WEB + "/js/jquery.form.js", """
                        /*!
                         * jQuery Form Plugin
                         * version: 3.51.0-2014.06.20
                         * Copyright (c) 2014 M. Alsup
                         * Dual licensed under the MIT and GPL licenses.
                         */
                        function ajaxSubmit() {}
                        """)
                // 프로젝트 코드: 건너뛰지 않는다
                .file(WEB + "/js/common.js", """
                        /*!
                         * Copyright &copy; 2012-2016 Our Company All rights reserved.
                         * 공통 스크립트
                         * @version 2014-4-29
                         */
                        function loading() { thing(); }
                        """)
                .file(WEB + "/js/order.js", "/**\n * 주문 화면\n */\nfunction closeOrder() { $.post('/order/close.do'); }\n")
                .file(WEB + "/js/member.js", "function join() {}\n")
                .file("src/lib/api.js", "export function api() {}\n")
                .file("src/plugins/chart.js", "export function chart() {}\n")
                // 업무 도메인 이름으로 쓰인 vendor(거래처)·plugin 폴더, jquery로 시작하는 프로젝트 파일은 벤더가 아니다
                .file("frontend/src/pages/vendor/VendorList.js", "export function VendorList() {}\n")
                .file(WEB + "/WEB-INF/views/plugin/list.js", "function pluginList() {}\n")
                .file(WEB + "/js/jquery.common.js", "function gridInit() {}\n")
                .file(WEB + "/js/bootstrap.js", "function startApp() {}\n")
                .file(WEB + "/js/jquery.min.js", "function a(){}");
    }

    @Test
    @DisplayName("F-010-AC24 벤더 스크립트(벤더 폴더·라이브러리 이름, <이름>-<버전>.js, 라이선스 배너)는 모듈 노드(skipped=vendor)만 만들고 디렉터리마다 FILE_SKIPPED(INFO) 1건으로 알린다")
    void vendorScriptsAreSkipped() {
        try (TestProject project = project()) {
            G g = new G(project.run(new JsAnalyzer()));

            for (String path : List.of(WEB + "/static/ckeditor/plugins/link/dialogs/link.js", WEB + "/static/ckeditor/ckeditor_source.js",
                    WEB + "/static/jquery/jquery-ui.custom.js", WEB + "/static/zTree_v3/js/tree.core.js",
                    WEB + "/static/bootstrap/2.3.1/docs/assets/js/application.js", WEB + "/js/lib/thing.js",
                    "vendor/dep.js", "frontend/src/vendor/dep2.js", "src/main/resources/META-INF/resources/webjars/x/1.0/x.js",
                    WEB + "/js/prototype-1.5.1.js", WEB + "/js/jquery-ui_1.12.1.custom.js", WEB + "/js/jquery.form.js",
                    WEB + "/js/swfobject.js", WEB + "/js/banner.js", WEB + "/js/licensed.js", WEB + "/js/mit.js")) {
                assertThat(g.node(mod(path)).attrString("skipped")).as(path).isEqualTo("vendor");
                assertThat(g.functions(path)).as(path).isEmpty();
                assertThat(g.hasEdge("file:" + path, EdgeKind.CONTAINS, mod(path))).isTrue();
            }
            // 벤더 파일의 요청도 만들지 않는다
            assertThat(g.requests()).containsExactly("POST /order/close.do");
            // 프로젝트 코드는 그대로 분석한다(머리 주석이 /*!로 시작해도 라이선스·버전 표기가 없으면 벤더가 아니다)
            assertThat(g.functions(WEB + "/js/common.js")).containsExactly("loading");
            assertThat(g.functions(WEB + "/js/order.js")).containsExactly("closeOrder");
            assertThat(g.functions(WEB + "/js/member.js")).containsExactly("join");
            assertThat(g.functions("src/lib/api.js")).containsExactly("api");
            assertThat(g.functions("src/plugins/chart.js")).containsExactly("chart");
            assertThat(g.functions("frontend/src/pages/vendor/VendorList.js")).containsExactly("VendorList");
            assertThat(g.functions(WEB + "/WEB-INF/views/plugin/list.js")).containsExactly("pluginList");
            assertThat(g.functions(WEB + "/js/jquery.common.js")).containsExactly("gridInit");
            assertThat(g.functions(WEB + "/js/bootstrap.js")).containsExactly("startApp");
            assertThat(g.node(mod(WEB + "/js/common.js")).attrString("skipped")).isNull();
            // 압축본은 종전대로 minified
            assertThat(g.node(mod(WEB + "/js/jquery.min.js")).attrString("skipped")).isEqualTo("minified");
            // 벤더 파일은 호출 대상이 아니다
            assertThat(g.edges(fn(WEB + "/js/common.js", "loading"), EdgeKind.CALLS)).isEmpty();

            // 개정(4차, F-010-R40): 요약 진단에 판정 근거(경로·옵션·이름·라이선스·생성물)가 붙는다
            List<Diagnostic> skipped = g.diagnostics(JsProjectAnalysis.FILE_SKIPPED);
            assertThat(skipped).allMatch(d -> d.severity() == Severity.INFO);
            assertThat(skipped.stream().map(Diagnostic::message).toList()).containsExactlyInAnyOrder(
                    "벤더 스크립트 2개 건너뜀(경로): " + WEB + "/static/ckeditor",
                    "벤더 스크립트 1개 건너뜀(경로): " + WEB + "/static/jquery",
                    "벤더 스크립트 1개 건너뜀(경로): " + WEB + "/static/zTree_v3",
                    "벤더 스크립트 1개 건너뜀(경로): " + WEB + "/static/bootstrap",
                    "벤더 스크립트 1개 건너뜀(경로): " + WEB + "/js/lib",
                    "벤더 스크립트 1개 건너뜀(경로): vendor",
                    "벤더 스크립트 1개 건너뜀(경로): frontend/src/vendor",
                    "벤더 스크립트 1개 건너뜀(경로): src/main/resources/META-INF/resources/webjars",
                    "벤더 스크립트 7개 건너뜀(이름 3, 라이선스 4): " + WEB + "/js",
                    "압축된 스크립트라 내용을 분석하지 않았다: " + WEB + "/js/jquery.min.js");
        }
    }

    @Test
    @DisplayName("F-010-AC25 web.skipVendor=false면 벤더 스크립트도 분석하고(vendor=true 표시), web.vendorPatterns의 glob에 맞는 파일은 벤더로 건너뛴다")
    void vendorOptions() {
        AnalysisOptions off = AnalysisOptions.builder().extra("web.skipVendor", "false").build();
        try (TestProject project = project().options(off)) {
            G g = new G(project.run(new JsAnalyzer()));

            assertThat(g.functions(WEB + "/static/ckeditor/plugins/link/dialogs/link.js")).containsExactly("ckLink");
            assertThat(g.node(mod(WEB + "/static/ckeditor/plugins/link/dialogs/link.js")).attrString("skipped")).isNull();
            assertThat(g.node(mod(WEB + "/static/ckeditor/plugins/link/dialogs/link.js")).attrBoolean("vendor")).isTrue();
            assertThat(g.node(mod(WEB + "/js/order.js")).attr("vendor")).isNull();
            assertThat(g.functions(WEB + "/js/banner.js")).containsExactly("foo");
            assertThat(g.requests()).contains("GET /ck/link");
            assertThat(g.diagnostics(JsProjectAnalysis.FILE_SKIPPED)).hasSize(1); // jquery.min.js
            // 분석은 하지만 전역 이름의 호출 대상으로는 쓰지 않는다
            assertThat(g.edges(fn(WEB + "/js/common.js", "loading"), EdgeKind.CALLS)).isEmpty();
        }
        AnalysisOptions patterns = AnalysisOptions.builder()
                .extra("web.vendorPatterns", "**/thirdparty/**, legacy-*.js,member.js, !**/js/lib/**,!swfobject.js").build();
        try (TestProject project = project().options(patterns)
                .file(WEB + "/thirdparty/x/grid.js", "function grid() {}\n")
                .file(WEB + "/js/legacy-menu.js", "function menu() {}\n")) {
            G g = new G(project.run(new JsAnalyzer()));

            assertThat(g.node(mod(WEB + "/thirdparty/x/grid.js")).attrString("skipped")).isEqualTo("vendor");
            assertThat(g.node(mod(WEB + "/js/legacy-menu.js")).attrString("skipped")).isEqualTo("vendor");
            assertThat(g.node(mod(WEB + "/js/member.js")).attrString("skipped")).isEqualTo("vendor");
            assertThat(g.functions(WEB + "/js/order.js")).containsExactly("closeOrder");
            // 기본 규칙도 함께 적용된다
            assertThat(g.node(mod(WEB + "/static/ckeditor/ckeditor_source.js")).attrString("skipped")).isEqualTo("vendor");
            // !로 시작하는 glob은 벤더가 아니라고 못 박는다(기본 규칙보다 먼저다)
            assertThat(g.functions(WEB + "/js/lib/thing.js")).containsExactly("thing");
            assertThat(g.node(mod(WEB + "/js/lib/thing.js")).attr("vendor")).isNull();
            assertThat(g.functions(WEB + "/js/swfobject.js")).containsExactly("embedSWF");
        }
    }
}
