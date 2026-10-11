package io.codeatlas.web;

import static io.codeatlas.web.G.fn;
import static io.codeatlas.web.G.mod;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.Analyzer;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import io.codeatlas.web.analysis.JsProjectAnalysis;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 3차 수정본을 공개 프로젝트에 다시 돌려 찾은 것(4차): 메서드를 모를 때 ANY로 뭉개 생긴 틀린 대응, 벤더 판정의 머리 주석 규칙,
 * hidden input의 URL, JSX 요소의 서버 요청, 루트로 가는 화면 이동, 인라인 스크립트 문자열 안의 EL 삼항.
 */
class JsAnalyzerCorpusRound4Test {

    private static final String WEB = "src/main/webapp";

    private static Analyzer producer(Consumer<AnalysisContext> body) {
        return new Analyzer() {
            @Override
            public String name() {
                return "fake-jsp";
            }

            @Override
            public void analyze(AnalysisContext ctx) {
                body.accept(ctx);
            }
        };
    }

    private static String page(AnalysisContext ctx, String jsp) {
        String pageId = Ids.jspPage(jsp);
        if (!ctx.graph().hasNode(pageId)) {
            ctx.graph().declare(Node.of(pageId, NodeKind.JSP_PAGE, jsp));
        }
        return pageId;
    }

    private static final Pattern EL = Pattern.compile("\\$\\{[^}]*\\}");

    /**
     * JSP에 쓴 스크립트를 jsp 분석기가 넘기는 꼴로 등록한다: EL은 {@code __JSP_EXPR__}로 바꾸고 치환 목록
     * ({@code <offset>,<length>,<원문 길이>:<원문>})을 싣는다. withSubstitutions가 false면 치환 목록을 싣지 않는다.
     */
    private static void inlineJsp(AnalysisContext ctx, String jsp, int line, String source, boolean withSubstitutions) {
        String pageId = page(ctx, jsp);
        if (!ctx.graph().hasNode(Ids.jsModule(jsp))) {
            ctx.graph().declare(Node.builder(Ids.jsModule(jsp), NodeKind.JS_MODULE).attr("inline", true).build());
            ctx.graph().addEdge(Edge.of(pageId, Ids.jsModule(jsp), EdgeKind.CONTAINS));
        }
        StringBuilder text = new StringBuilder();
        StringBuilder substitutions = new StringBuilder();
        Matcher m = EL.matcher(source);
        int position = 0;
        while (m.find()) {
            text.append(source, position, m.start());
            substitutions.append(text.length()).append(',').append("__JSP_EXPR__".length()).append(',')
                    .append(m.group().length()).append(':').append(m.group());
            text.append("__JSP_EXPR__");
            position = m.end();
        }
        text.append(source.substring(position));
        Map<String, String> attrs = withSubstitutions && substitutions.length() > 0
                ? Map.of("jspSubstitutions", substitutions.toString()) : Map.of();
        ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.JS, Ids.jsModule(jsp), text.toString(),
                SourceLocation.of(jsp, line, 1, line, 1), true, attrs));
    }

    private static Confidence confidence(G g, String from, String method, String path) {
        return g.edge(from, EdgeKind.REQUESTS, g.request(method, path).id()).confidence();
    }

    private static List<String> unresolvedRequests(G g, String from) {
        return g.edges(from, EdgeKind.REQUESTS).stream().map(Edge::to).filter(to -> to.startsWith("unresolved:")).toList();
    }

    @Test
    @DisplayName("F-010-AC53 메서드와 URL을 짝짓지 못하면 ANY로 버리지 않고 후보 메서드를 httpMethods로 남기고, URL이 같은 객체의 프로퍼티에서 파생된 식이면 그 객체 리터럴의 메서드와 짝을 유지한다")
    void methodCandidatesAreKept() {
        try (TestProject project = TestProject.create()
                .file("front/src/api/net.js", """
                        const SERVER_URL = "/api";
                        export function requestFetch(url, requestOptions, handler) {
                          fetch(SERVER_URL + url, requestOptions).then((r) => r.json()).then(handler);
                        }
                        """)
                .file("front/src/pages/Edit.jsx", """
                        import { useState } from "react";
                        import * as Net from "../api/net";
                        export default function Edit(props) {
                          const [modeInfo, setModeInfo] = useState({ mode: props.mode });
                          const [info, setInfo] = useState({});
                          const init = () => {
                            if (props.mode === "C") { setModeInfo({ ...modeInfo, method: "POST", editURL: "/schedule" }); }
                            else { setModeInfo({ ...modeInfo, method: "PUT", editURL: "/schedule" }); }
                            if (props.reply) { setInfo({ method: "POST", url: "/board" }); }
                            else { setInfo({ method: "PUT", url: "/board/edit" }); }
                          };
                          const save1 = () => {
                            const requestOptions = { method: modeInfo.method, body: "{}" };
                            const editURL = modeInfo.mode === "M" ? `${modeInfo.editURL}/${props.id}` : modeInfo.editURL;
                            Net.requestFetch(editURL, requestOptions, (resp) => {});
                          };
                          const save2 = () => {
                            const u = props.id ? info.url + "/" + props.id : info.url;
                            fetch(u, { method: info.method });
                          };
                          const save3 = () => {
                            let modeStr = props.c ? "POST" : "PUT";
                            fetch(info.url, { method: modeStr });
                          };
                          const save4 = () => { fetch(info.url, { method: props.m }); };
                          const del = () => { Net.requestFetch(`/schedule/${props.id}`, { method: "DELETE" }, () => {}); };
                          return <div onClick={save1}>x</div>;
                        }
                        """)) {
            G g = new G(project.run(new JsAnalyzer()));
            String file = "front/src/pages/Edit.jsx";

            // 두 리터럴(POST, PUT)의 editURL이 같다: URL마다 노드 하나, 메서드는 ANY이되 후보를 남긴다
            assertThat(g.requestsFrom(fn(file, "Edit.save1"))).containsExactlyInAnyOrder("ANY /api/schedule", "ANY /api/schedule/{*}");
            for (String path : List.of("/api/schedule", "/api/schedule/{*}")) {
                Node n = g.graph().node("http:request:ANY " + path + "@" + file + ":15").orElseThrow();
                assertThat(n.attrString("httpMethod")).isEqualTo("ANY");
                assertThat(n.attrs().get("httpMethods")).as(path).isEqualTo(List.of("POST", "PUT"));
                assertThat(g.edge(fn(file, "Edit.save1"), EdgeKind.REQUESTS, n.id()).confidence()).isEqualTo(Confidence.INFERRED);
            }
            // 리터럴마다 URL이 다르면 파생 식(삼항·+ 연결)이어도 그 리터럴의 메서드가 붙는다. PUT /board, POST /board/edit는 없다
            assertThat(g.requestsFrom(fn(file, "Edit.save2"))).containsExactlyInAnyOrder("POST /board", "POST /board/{*}",
                    "PUT /board/edit", "PUT /board/edit/{*}");
            assertThat(g.request("POST", "/board/{*}").attrs()).doesNotContainKey("httpMethods");
            assertThat(confidence(g, fn(file, "Edit.save2"), "PUT", "/board/edit")).isEqualTo(Confidence.INFERRED);
            // 서로 다른 변수에서 온 여러 후보: 짝을 모르므로 URL마다 ANY 하나 + 후보 메서드 전부
            assertThat(g.requestsFrom(fn(file, "Edit.save3"))).containsExactlyInAnyOrder("ANY /board", "ANY /board/edit");
            assertThat(g.graph().node("http:request:ANY /board@" + file + ":23").orElseThrow().attrs().get("httpMethods"))
                    .isEqualTo(List.of("POST", "PUT"));
            // 메서드를 아예 모르면 후보도 없다
            assertThat(g.requestsFrom(fn(file, "Edit.save4"))).containsExactlyInAnyOrder("ANY /board", "ANY /board/edit");
            assertThat(g.graph().node("http:request:ANY /board@" + file + ":25").orElseThrow().attrs()).doesNotContainKey("httpMethods");
            // 메서드를 아는 요청에는 붙지 않는다
            assertThat(g.requestsFrom(fn(file, "Edit.del"))).containsExactly("DELETE /api/schedule/{*}");
            assertThat(g.request("DELETE", "/api/schedule/{*}").attrs()).doesNotContainKey("httpMethods");
        }
    }

    private static String banner(String... lines) {
        return "/*\n * " + String.join("\n * ", lines) + "\n */\n";
    }

    @Test
    @DisplayName("F-010-AC54 머리 주석만으로 하는 벤더 판정은 정식 라이선스 문구·@license·알려진 프로젝트 URL이 있을 때만이고, 프로젝트가 그 함수를 5개 이상 부르거나 폴더에서 혼자만 걸린 파일은 벤더가 아니며, FILE_SKIPPED에 근거를 붙인다")
    void vendorBannerIsNarrowed() {
        String apache = banner("Copyright 2008-2009 the original author or authors.", "",
                "Licensed under the Apache License, Version 2.0 (the \"License\");",
                "you may not use this file except in compliance with the License.");
        try (TestProject project = TestProject.create()
                // 사내 스크립트의 머리 주석: 저작권 + @author + @version, 수정 이력의 버전, CC 문구
                .file(WEB + "/js/own1.js", """
                        /*!
                         * Copyright &copy; 2012-2016 Acme All rights reserved.
                         *
                         * common functions
                         * @author Kim
                         * @version 1.2.0
                         */
                        function foo() { return 1; }
                        """)
                .file(WEB + "/js/own2.js", """
                        /**
                         * @Class Name : order.js
                         * @author 홍길동
                         * @version 1.0
                         * Copyright (C) 2020 by ACME All right reserved.
                         */
                        function fn_search() { $.ajax({ url: '/order/list.do', type: 'post' }); }
                        """)
                .file(WEB + "/js/own4.js", """
                        /**
                         * Order screen helpers.
                         * Icons are licenced under a Creative Commons Attribution 2.5 License.
                         * @author Kim
                         */
                        function foo4() { return 1; }
                        """)
                .file(WEB + "/js/own5.js", "// 주문 화면 스크립트 v1.0.0 (c) ACME\nfunction fn_del() { $.post('/order/del.do', {}); }\n")
                // 정식 라이선스 문구가 있지만 이 폴더에서 혼자만 걸렸고 이름이 라이브러리 꼴이 아니다: 프로젝트 파일이다
                .file(WEB + "/js/own3.js", apache + "/**\n * @author Kim\n */\nfunction foo3() { return 1; }\n")
                // 폴더에서 혼자 걸렸어도 이름이 라이브러리 꼴이면 벤더다
                .file(WEB + "/app/a.js", "function a1() {}\n")
                .file(WEB + "/app/b.js", "function b1() {}\n")
                .file(WEB + "/app/jquery.form.js", """
                        /*!
                         * jQuery Form Plugin
                         * version: 3.51.0-2014.06.20
                         * Copyright (c) 2014 M. Alsup
                         * Dual licensed under the MIT and GPL licenses.
                         */
                        function ajaxSubmit() {}
                        """)
                .file(WEB + "/app2/x.js", "function x1() {}\n")
                .file(WEB + "/app2/widget.min.js", "/*! Widget | MIT License */\n" + "function w() {\n  return 1;\n}\n".repeat(5))
                // 같은 폴더에서 둘 이상 걸리면 그대로 벤더다(알려진 프로젝트 URL, SPDX, @license, BSD 조건)
                .file(WEB + "/app3/p.js", "function p1() {}\n")
                .file(WEB + "/app3/thing.js", "/*! Thing v2.0.1 | https://github.com/acme/thing */\nfunction thing() {}\n")
                .file(WEB + "/app3/spdx.js", "// SPDX-License-Identifier: MIT\nfunction spdx() {}\n")
                .file(WEB + "/app3/tagged.js", "/**\n * @license Bar\n */\nfunction tagged() {}\n")
                .file(WEB + "/app3/bsd.js", banner("Copyright (c) 2010, Someone", "Redistribution and use in source and binary forms, with or without",
                        "modification, are permitted provided that the following conditions are met:") + "function bsd() {}\n")
                .file(WEB + "/app3/gpl.js", banner("This program is free software; you can redistribute it under the terms of the",
                        "GNU General Public License as published by the Free Software Foundation.") + "function gpl() {}\n")
                // 둘이 걸린 폴더: 프로젝트의 다른 스크립트가 최상위 함수를 5개 이상 부르는 파일은 프로젝트 파일이다
                .file(WEB + "/app4/util.js", banner("Released under the MIT license") + """
                        function u1() {}
                        function u2() {}
                        function u3() {}
                        function u4() {}
                        function u5() {}
                        function u6() {}
                        """)
                .file(WEB + "/app4/other.js", banner("Released under the MIT license") + "function o1() {}\nfunction o2() {}\n")
                .file(WEB + "/app4/caller.js", "function run() { u1(); u2(); u3(); u4(); u5(); o1(); o2(); }\n")
                // 폴더에 혼자 있는 파일은 견줄 것이 없다: 벤더다
                .file(WEB + "/solo/lone.js", banner("Licensed under the MIT license.") + "function lone() {}\n")) {
            G g = new G(project.run(new JsAnalyzer()));

            for (String analyzed : List.of("/js/own1.js", "/js/own2.js", "/js/own3.js", "/js/own4.js", "/js/own5.js", "/app4/util.js")) {
                assertThat(g.node(mod(WEB + analyzed)).attrString("skipped")).as(analyzed).isNull();
                assertThat(g.node(mod(WEB + analyzed)).attr("vendor")).as(analyzed).isNull();
                assertThat(g.functions(WEB + analyzed)).as(analyzed).isNotEmpty();
            }
            assertThat(g.requests()).containsExactlyInAnyOrder("POST /order/list.do", "POST /order/del.do");
            for (String vendor : List.of("/app/jquery.form.js", "/app2/widget.min.js", "/app3/thing.js", "/app3/spdx.js",
                    "/app3/tagged.js", "/app3/bsd.js", "/app3/gpl.js", "/app4/other.js", "/solo/lone.js")) {
                assertThat(g.node(mod(WEB + vendor)).attrString("skipped")).as(vendor).isEqualTo("vendor");
                assertThat(g.functions(WEB + vendor)).as(vendor).isEmpty();
            }
            // 벤더로 남은 파일의 함수로는 잇지 않는다
            assertThat(g.targets(fn(WEB + "/app4/caller.js", "run"), EdgeKind.CALLS)).doesNotContain(fn(WEB + "/app4/other.js", "o1"));
            // 요약 진단에 판정 근거가 붙는다
            assertThat(g.diagnostics(JsProjectAnalysis.FILE_SKIPPED).stream().map(Diagnostic::message).toList())
                    .containsExactlyInAnyOrder(
                            "벤더 스크립트 1개 건너뜀(라이선스): " + WEB + "/app",
                            "벤더 스크립트 1개 건너뜀(라이선스): " + WEB + "/app2",
                            "벤더 스크립트 5개 건너뜀(라이선스): " + WEB + "/app3",
                            "벤더 스크립트 1개 건너뜀(라이선스): " + WEB + "/app4",
                            "벤더 스크립트 1개 건너뜀(라이선스): " + WEB + "/solo");
        }
        // 근거가 섞인 폴더는 근거마다 건수를 적는다
        try (TestProject project = TestProject.create()
                .file(WEB + "/static/ckeditor/ckeditor_source.js", "function ckCore() {}\n")
                .file(WEB + "/js/prototype-1.5.1.js", "function $(id) {}\n")
                .file(WEB + "/js/swfobject.js", "function embedSWF() {}\n")
                .file(WEB + "/js/mit.js", "// Baz\n// Copyright (c) 2014 Baz Authors\n// Released under the MIT license\nfunction baz() {}\n")
                .file(WEB + "/js/tagged.js", "/**\n * @license Bar\n */\nfunction tagged() {}\n")
                .file(WEB + "/js/common.js", "function loading() {}\n")) {
            G g = new G(project.run(new JsAnalyzer()));

            assertThat(g.diagnostics(JsProjectAnalysis.FILE_SKIPPED).stream().map(Diagnostic::message).toList())
                    .containsExactlyInAnyOrder(
                            "벤더 스크립트 1개 건너뜀(경로): " + WEB + "/static/ckeditor",
                            "벤더 스크립트 4개 건너뜀(이름 2, 라이선스 2): " + WEB + "/js");
        }
    }

    @Test
    @DisplayName("F-010-AC55 hidden input에 든 URL: frm.zip_url.value, document.폼.필드.value, $('#id').val()을 페이지의 필드 표(JSP_PAGE attrs.formFields)로 평가한다")
    void hiddenFieldUrls() {
        String insert = "web/user/insert.jsp";
        String update = "web/user/update.jsp";
        Analyzer fake = producer(ctx -> {
            inlineJsp(ctx, insert, 10, """
                    function openZip() { window.open(document.userManageVO.zip_url.value, 'zip'); }
                    function openCal() { var u = $('#calUrl').val(); window.showModalDialog(u, null, ''); }
                    function byId() { location.href = document.getElementById('calUrl').value; }
                    function noField() { window.open(document.userManageVO.nope.value); }
                    function setter() { $('#calUrl').val('x'); window.open($('#other').val()); }
                    """, true);
            ctx.graph().putAttrs(Ids.jspPage(insert), Map.of("formFields", List.of(
                    "userManageVO.zip_url=/sym/cmm/EgovCcmZipSearchPopup.do", "#userManageVO.zip_url=/sym/cmm/EgovCcmZipSearchPopup.do",
                    "userManageVO.cal_url=/sym/cmm/EgovNormalCalPopup.do?mode=1", "#calUrl=/sym/cmm/EgovNormalCalPopup.do?mode=1")));
            ctx.graph().addEdge(Edge.of(Ids.jspPage(insert), Ids.jsModule("js/EgovZipPopup.js"), EdgeKind.INCLUDES));
            page(ctx, update);
            ctx.graph().putAttrs(Ids.jspPage(update), Map.of("formFields",
                    List.of("frm2.zip_url=/sym/cmm/EgovCcmZipSearchPopup.do", "frm2.cal_url=/sym/cmm/EgovAdministCalPopup.do")));
            ctx.graph().addEdge(Edge.of(Ids.jspPage(update), Ids.jsModule("js/EgovZipPopup.js"), EdgeKind.INCLUDES));
        });
        try (TestProject project = TestProject.create().file(insert, "<html></html>").file(update, "<html></html>")
                .file("js/EgovZipPopup.js", """
                        function fn_egov_ZipSearch(frm, sZip) {
                          var url = frm.zip_url.value;
                          window.showModalDialog(url, null, "dialogWidth:550px");
                        }
                        function fn_egov_Cal(frm) { window.open(frm.cal_url.value); }
                        function fn_plain(frm) { window.open(frm.keyword.value); }
                        """)
                .file("js/unloaded.js", """
                        function lost(frm) { window.open(frm.zip_url.value); }
                        function lost2(frm) { window.open(frm.cal_url.value); }
                        """)) {
            G g = new G(project.run(fake, new JsAnalyzer()));

            // 폼을 아는 식: 주인 페이지의 표에서 읽는다
            assertThat(g.requestsFrom(fn(insert, "openZip"))).containsExactly("GET /sym/cmm/EgovCcmZipSearchPopup.do");
            Node zip = g.graph().node("http:request:GET /sym/cmm/EgovCcmZipSearchPopup.do@" + insert + ":10").orElseThrow();
            assertThat(g.edge(fn(insert, "openZip"), EdgeKind.REQUESTS, zip.id()).confidence()).isEqualTo(Confidence.RESOLVED);
            assertThat(zip.attrString("api")).isEqualTo("navigation");
            assertThat(zip.attrString("url")).isEqualTo("document.userManageVO.zip_url.value");
            assertThat(g.requestsFrom(fn(insert, "openCal"))).containsExactly("GET /sym/cmm/EgovNormalCalPopup.do");
            assertThat(g.graph().node("http:request:GET /sym/cmm/EgovNormalCalPopup.do@" + insert + ":11").orElseThrow()
                    .attrString("query")).isEqualTo("mode=1");
            assertThat(g.requestsFrom(fn(insert, "byId"))).containsExactly("GET /sym/cmm/EgovNormalCalPopup.do");
            // 표에 없는 필드, 값을 넣는 val(x)는 종전대로다
            assertThat(g.requestsFrom(fn(insert, "noField"))).isEmpty();
            assertThat(unresolvedRequests(g, fn(insert, "noField"))).hasSize(1);
            assertThat(g.requestsFrom(fn(insert, "setter"))).isEmpty();
            // 폼이 매개변수인 JS 파일: 그 스크립트를 싣는 페이지들의 표에서 필드 이름으로 찾는다(추론). 값이 갈리면 후보마다
            String file = "js/EgovZipPopup.js";
            assertThat(g.requestsFrom(fn(file, "fn_egov_ZipSearch"))).containsExactly("GET /sym/cmm/EgovCcmZipSearchPopup.do");
            Node popup = g.graph().node("http:request:GET /sym/cmm/EgovCcmZipSearchPopup.do@" + file + ":3").orElseThrow();
            assertThat(g.edge(fn(file, "fn_egov_ZipSearch"), EdgeKind.REQUESTS, popup.id()).confidence()).isEqualTo(Confidence.INFERRED);
            assertThat(popup.attrString("url")).isEqualTo("url");
            assertThat(g.requestsFrom(fn(file, "fn_egov_Cal"))).containsExactlyInAnyOrder("GET /sym/cmm/EgovNormalCalPopup.do",
                    "GET /sym/cmm/EgovAdministCalPopup.do");
            assertThat(g.requestsFrom(fn(file, "fn_plain"))).isEmpty();
            assertThat(unresolvedRequests(g, fn(file, "fn_plain"))).hasSize(1);
            // 어느 페이지도 싣지 않는 스크립트: 그 필드 이름의 URL이 프로젝트에 하나뿐일 때만 그것으로 본다(추론)
            assertThat(g.requestsFrom(fn("js/unloaded.js", "lost"))).containsExactly("GET /sym/cmm/EgovCcmZipSearchPopup.do");
            Node lost = g.graph().node("http:request:GET /sym/cmm/EgovCcmZipSearchPopup.do@js/unloaded.js:1").orElseThrow();
            assertThat(g.edge(fn("js/unloaded.js", "lost"), EdgeKind.REQUESTS, lost.id()).confidence()).isEqualTo(Confidence.INFERRED);
            assertThat(g.requestsFrom(fn("js/unloaded.js", "lost2"))).isEmpty();
            assertThat(unresolvedRequests(g, fn("js/unloaded.js", "lost2"))).hasSize(1);
        }
    }

    @Test
    @DisplayName("F-010-AC56 JSX의 HTML 요소 <a href>·<form action>·<img src>·<iframe src>의 값이 서버 경로면 요청(api=link|form|img|frame)으로 기록하고, 선언된 클라이언트 라우트와 같으면 clientRoute=true다")
    void jsxServerRequests() {
        try (TestProject project = TestProject.create()
                .file("src/App.jsx", """
                        import { Routes, Route, Link } from "react-router-dom";
                        import logo from "./logo.png";
                        export function App() { return <Routes><Route path="/admin/notice" element={<P />} /></Routes>; }
                        export function P(props) {
                          return (
                            <div>
                              <img src={`/api/file/${props.id}`} alt="x" />
                              <img src={logo} />
                              <img src="/assets/logo.png" />
                              <a href="/api/schedule/export?type=1">x</a>
                              <a href="/admin/notice">n</a>
                              <a href="#!">h</a>
                              <a href="https://example.com/x">e</a>
                              <a href={props.url}>d</a>
                              <a href="relative/path">r</a>
                              <form action="/api/upload.do" method="post"></form>
                              <form action={"/api/search"}></form>
                              <form action="/api/dyn" method={props.m}></form>
                              <form name="" method="" action=""></form>
                              <iframe src="/report/view.do" />
                              <Link to="/admin/notice">r</Link>
                              <script src="/js/app.js"></script>
                            </div>
                          );
                        }
                        """)) {
            G g = new G(project.run(new JsAnalyzer()));
            String file = "src/App.jsx";

            assertThat(g.requestsFrom(fn(file, "P"))).containsExactlyInAnyOrder("GET /api/file/{*}", "GET /api/schedule/export",
                    "GET /admin/notice", "POST /api/upload.do", "GET /api/search", "ANY /api/dyn", "GET /report/view.do");
            Node img = g.request("GET", "/api/file/{*}");
            assertThat(img.attrString("api")).isEqualTo("img");
            assertThat(img.id()).isEqualTo("http:request:GET /api/file/{*}@" + file + ":7");
            assertThat(img.attrString("url")).isEqualTo("`/api/file/${props.id}`");
            assertThat(img.attrs()).doesNotContainKey("clientRoute");
            Node link = g.request("GET", "/api/schedule/export");
            assertThat(link.attrString("api")).isEqualTo("link");
            assertThat(link.attrString("query")).isEqualTo("type=1");
            assertThat(confidence(g, fn(file, "P"), "GET", "/api/schedule/export")).isEqualTo(Confidence.RESOLVED);
            // 선언된 클라이언트 라우트로 가는 <a href>: 서버 요청이 아니다
            Node route = g.graph().node("http:request:GET /admin/notice@" + file + ":11").orElseThrow();
            assertThat(route.attrString("api")).isEqualTo("link");
            assertThat(route.attr("clientRoute")).isEqualTo(true);
            assertThat(g.graph().node("http:request:GET /admin/notice@" + file + ":21").orElseThrow().attrString("api")).isEqualTo("route");
            assertThat(g.request("POST", "/api/upload.do").attrString("api")).isEqualTo("form");
            assertThat(g.request("GET", "/api/search").attrString("api")).isEqualTo("form");
            assertThat(g.request("GET", "/report/view.do").attrString("api")).isEqualTo("frame");
            // 값을 모르는 속성, 정적 자원, 외부 URL, 상대 경로, 빈 값은 요청도 미해결도 아니다
            assertThat(unresolvedRequests(g, fn(file, "P"))).isEmpty();
        }
    }

    @Test
    @DisplayName("F-010-AC57 \"/\"로 가는 화면 이동(location.href = \"/\")은 미해결이 아니라 GET / 요청이고, 라우트 선언에 /가 있으면 clientRoute=true다")
    void rootNavigation() {
        try (TestProject project = TestProject.create()
                .file("src/url.js", "const URL = { MAIN: '/' };\nexport default URL;\n")
                .file("src/routes.jsx", """
                        import { Routes, Route } from "react-router-dom";
                        import URL from "./url";
                        export function AppRoutes() { return <Routes><Route path={URL.MAIN} element={<Main />} /></Routes>; }
                        function Main() { return <b>x</b>; }
                        """)
                .file("src/Callback.jsx", """
                        import URL from "./url";
                        export function Callback() {
                          const done = () => { window.location.href = "/"; };
                          const other = () => { location.replace(URL.MAIN); };
                          return <b onClick={done}>x</b>;
                        }
                        """)) {
            G g = new G(project.run(new JsAnalyzer()));
            String file = "src/Callback.jsx";

            assertThat(g.requestsFrom(fn(file, "Callback.done"))).containsExactly("GET /");
            Node root = g.graph().node("http:request:GET /@" + file + ":3").orElseThrow();
            assertThat(root.attrString("api")).isEqualTo("navigation");
            assertThat(root.attr("clientRoute")).isEqualTo(true);
            assertThat(g.requestsFrom(fn(file, "Callback.other"))).containsExactly("GET /");
            assertThat(unresolvedRequests(g, fn(file, "Callback.done"))).isEmpty();
            assertThat(unresolvedRequests(g, fn(file, "Callback.other"))).isEmpty();
        }
        // 라우트 선언이 없는 프로젝트: 서버의 루트로 가는 이동이다. 값을 모르는 이동과 다른 API의 "/"는 종전대로 미해결이다
        try (TestProject project = TestProject.create()
                .file("js/legacy.js", """
                        function goHome() { location.href = "/"; }
                        function goApp() { location.href = ctx + "/"; }
                        function goWhere(u) { location.href = u; }
                        function load() { $.get("/"); }
                        """)) {
            G g = new G(project.run(new JsAnalyzer()));

            assertThat(g.requestsFrom(fn("js/legacy.js", "goHome"))).containsExactly("GET /");
            assertThat(g.graph().node("http:request:GET /@js/legacy.js:1").orElseThrow().attrs()).doesNotContainKey("clientRoute");
            assertThat(g.requestsFrom(fn("js/legacy.js", "goApp"))).containsExactly("GET /");
            assertThat(unresolvedRequests(g, fn("js/legacy.js", "goWhere"))).hasSize(1);
            assertThat(g.requestsFrom(fn("js/legacy.js", "load"))).isEmpty();
            assertThat(unresolvedRequests(g, fn("js/legacy.js", "load"))).hasSize(1);
        }
    }

    @Test
    @DisplayName("F-010-AC58 인라인 스크립트의 문자열 안 EL이 cond ? '리터럴A' : '리터럴B' 꼴이면 {*} 대신 후보 URL 둘(INFERRED)로 펼친다")
    void elTernaryInInlineScript() {
        String jsp = "web/sys/list.jsp";
        String bare = "web/sys/bare.jsp";
        Analyzer fake = producer(ctx -> {
            inlineJsp(ctx, jsp, 8, """
                    function pick(t) { location.href = "${ctx}/sys/user/${t == 'a' ? 'form' : 'list'}?id=1"; }
                    function notify() { $.post('${ctx}/oa/oaNotify/${oaNotify.self?"self":""}', {}); }
                    function mixed() { $.get('${ctx}/sys/role/${a ? b : 'x'}/view'); }
                    function tpl(id) { $.get(`${ctx}/sys/menu/${empty m.id ? 'add' : 'edit'}/` + id); }
                    function plain() { $.get('${ctx}/sys/dict/${dict.type}'); }
                    """, true);
            inlineJsp(ctx, bare, 3, "function pick(t) { location.href = \"${ctx}/sys/area/${t == 'a' ? 'form' : 'list'}\"; }\n", false);
        });
        try (TestProject project = TestProject.create().file(jsp, "<html></html>").file(bare, "<html></html>")) {
            G g = new G(project.run(fake, new JsAnalyzer()));

            assertThat(g.requestsFrom(fn(jsp, "pick"))).containsExactlyInAnyOrder("GET /sys/user/form", "GET /sys/user/list");
            Node form = g.request("GET", "/sys/user/form");
            assertThat(form.attrString("url")).isEqualTo("\"${ctx}/sys/user/${t == 'a' ? 'form' : 'list'}?id=1\"");
            assertThat(form.attrString("query")).isEqualTo("id=1");
            assertThat(form.attrs()).containsEntry("dynamic", false);
            assertThat(confidence(g, fn(jsp, "pick"), "GET", "/sys/user/form")).isEqualTo(Confidence.INFERRED);
            assertThat(confidence(g, fn(jsp, "pick"), "GET", "/sys/user/list")).isEqualTo(Confidence.INFERRED);
            assertThat(g.requestsFrom(fn(jsp, "notify"))).containsExactlyInAnyOrder("POST /oa/oaNotify/self", "POST /oa/oaNotify/");
            // 갈래가 리터럴이 아닌 삼항, 삼항이 아닌 EL은 종전대로 {*}(RESOLVED)
            assertThat(g.requestsFrom(fn(jsp, "mixed"))).containsExactly("GET /sys/role/{*}/view");
            assertThat(confidence(g, fn(jsp, "mixed"), "GET", "/sys/role/{*}/view")).isEqualTo(Confidence.RESOLVED);
            assertThat(g.requestsFrom(fn(jsp, "plain"))).containsExactly("GET /sys/dict/{*}");
            // 템플릿 리터럴 안의 EL도 같다
            assertThat(g.requestsFrom(fn(jsp, "tpl"))).containsExactlyInAnyOrder("GET /sys/menu/add/{*}", "GET /sys/menu/edit/{*}");
            // 치환 목록이 없으면 원문을 모른다: {*} 그대로
            assertThat(g.requestsFrom(fn(bare, "pick"))).containsExactly("GET /sys/area/{*}");
        }
    }
}
