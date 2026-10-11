package io.codeatlas.web.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 벤더(서드파티) 스크립트 판정(F-010-R17). 프로젝트가 직접 쓰지 않은 라이브러리 파일은 분석해도 화면 → 서버 흐름에 보탬이 없고
 * 분석 시간과 오탐만 늘린다.
 *
 * <p>규칙은 F-010 spec 세부 규칙 "벤더 판정"과 같다. jsp 모듈(F-011)은 의존을 공유하지 않으므로 경로 규칙을 같은 문구로 따로
 * 구현한다({@code io.codeatlas.jsp.VendorPaths}). 한쪽을 고치면 다른 쪽도 고친다.
 */
final class VendorRules {

    static final String OPTION_SKIP = "web.skipVendor";
    static final String OPTION_PATTERNS = "web.vendorPatterns";

    /** 어디에 있든 이름만으로 벤더 폴더인 디렉터리. */
    private static final Set<String> VENDOR_DIRECTORIES = Set.of("node_modules", "bower_components", "webjars");
    /**
     * 경로의 첫 세그먼트이거나 {@code src} 바로 아래이거나 정적 자원 폴더 아래일 때 벤더 폴더로 보는 디렉터리.
     * vendor는 업무 도메인 이름(거래처)으로도 쓰여서({@code src/pages/vendor}, {@code WEB-INF/views/vendor}) 아무 데서나 보지 않는다.
     */
    private static final Set<String> LOOSE_DIRECTORIES = Set.of("vendor", "vendors");
    /** 정적 자원 폴더 아래에 있을 때만 벤더 폴더로 보는 디렉터리. */
    private static final Set<String> CONDITIONAL_DIRECTORIES = Set.of("lib", "libs", "plugins", "plugin", "bootstrap",
            "layer", "angular");
    /** 정적 자원 폴더. 그 아래라도 {@code WEB-INF} 안쪽은 정적 자원이 아니다(화면 파일이다). */
    private static final Set<String> STATIC_ROOTS = Set.of("webapp", "webcontent", "webroot", "static", "public",
            "assets", "js", "javascript", "scripts", "script");
    /** 잘 알려진 라이브러리 이름(소문자). 디렉터리 이름이나 파일 이름이 이것과 같거나 뒤에 버전이 붙은 꼴이면 벤더다. */
    private static final Set<String> LIBRARIES = Set.of("ckeditor", "ckfinder", "tinymce", "easyui", "ztree", "echarts",
            "swfobject", "webuploader", "codemirror", "ueditor", "umeditor", "kindeditor", "xheditor", "summernote",
            "layui", "laydate", "my97datepicker", "select2", "jqgrid", "datatables", "fullcalendar", "pdfjs",
            "angularjs", "scriptaculous", "mootools", "extjs", "highcharts", "jqplot", "jstree",
            "treetable", "superslide", "supersized", "mcustomscrollbar", "malihu-custom-scrollbar-plugin", "jerichotab",
            "jbox", "artdialog", "lhgdialog", "fancybox", "uploadify", "plupload", "swfupload", "zeroclipboard",
            "syntaxhighlighter", "es5-shim", "es5-sham", "requirejs", "seajs", "fullpage", "sweetalert", "videojs",
            "video-js", "mathjax", "font-awesome", "fontawesome");
    /**
     * 파일 이름으로만 보는 라이브러리 이름. 폴더 이름으로는 흔한 낱말이라({@code prototype/}, {@code moment/}) 폴더에는 쓰지 않는다.
     * {@code bootstrap.js}는 앱 시작 코드의 이름으로도 흔해서 넣지 않는다(진짜 Bootstrap은 배너로 걸린다).
     * {@code jquery.<이름>.js}도 이름만으로는 보지 않는다: 프로젝트가 쓴 {@code jquery.common.js} 같은 파일이 있다
     * (진짜 플러그인은 배너로 걸린다).
     */
    private static final Set<String> FILE_LIBRARIES = Set.of("jquery", "jquery-ui", "jqueryui", "jquery-migrate", "layer",
            "angular", "prototype", "dojo", "yui", "zepto",
            "underscore", "lodash", "backbone", "knockout", "handlebars", "mustache", "moment", "raphael", "flot", "swiper",
            "prettify", "modernizr", "html5shiv", "respond", "json2", "excanvas", "require", "toastr");
    /** 이름이 이것으로 시작하기만 하면 벤더인 라이브러리(플러그인 생태계가 {@code jquery.form}, {@code bootstrap-table}처럼 이름을 잇는다). */
    private static final List<String> LIBRARY_PREFIXES = List.of("jquery", "bootstrap");

    /** {@code 2.3.1}, {@code v3.4}: 버전만으로 된 이름. */
    private static final Pattern VERSION_ONLY = Pattern.compile("v?\\d+(\\.\\d+)+");
    /** {@code jquery-1.12.4}, {@code es5-shim-15.3.4.5}, {@code zTree_v3}, {@code jquery-ui_1.12.1.custom}: 이름 뒤에 버전이 붙은 꼴. */
    private static final Pattern NAME_VERSION = Pattern.compile(
            "([a-z][a-z0-9_.-]*?)[-_.]?v?\\d+(?:\\.\\d+)*(?:[-_.](?:min|pack|packed|custom|full|all|src|debug|slim|bundle|rc\\d*|beta\\d*|alpha\\d*))*");
    /** 이름과 버전 사이에 구분자가 있고 버전에 점이 있는 꼴(모르는 이름에도 적용): {@code prototype-1.5.1}. */
    private static final Pattern DOTTED_VERSION_SUFFIX = Pattern.compile(
            "[a-z][a-z0-9_.-]*?[-_.]v?\\d+(?:\\.\\d+)+(?:[-_.](?:min|pack|packed|custom|full|all|src|debug|slim|bundle|rc\\d*|beta\\d*|alpha\\d*))*");
    private static final Pattern SCRIPT_EXTENSION = Pattern.compile("\\.(?:js|mjs|cjs|jsx|ts|tsx|mts|cts)$");
    private static final Pattern MIN_SUFFIX = Pattern.compile("[.-]min$");
    /** 빌드 산출물·배포판을 뜻하는 이름: {@code oryx.debug}, {@code foo-debug}, {@code lib.pack}, {@code app.bundle}(뒤에 다른 조각이 붙어도). */
    private static final Pattern BUILD_VARIANT = Pattern.compile(".+(?:[.-]debug|\\.pack|\\.packed|\\.bundle)(?:[.-].*)?");
    private static final int GENERATED_LENGTH = 200_000;
    private static final int GENERATED_FUNCTIONS = 200;
    /** 벤더 판정의 근거(FILE_SKIPPED 요약 진단에 한 낱말로 붙인다). 적는 순서대로다. */
    static final List<String> REASONS = List.of("경로", "옵션", "이름", "라이선스", "생성물");
    static final String REASON_PATH = "경로";
    static final String REASON_OPTION = "옵션";
    static final String REASON_NAME = "이름";
    static final String REASON_LICENSE = "라이선스";
    static final String REASON_GENERATED = "생성물";

    private static final String LICENSE_NAMES = "(?:MIT|BSD|Apache|GPL|LGPL|AGPL|GNU|MPL|Mozilla|ISC|WTFPL|EPL|Eclipse)";
    /**
     * 알려진 오픈소스 라이선스의 정식 문구(F-010-R40): {@code Licensed under the Apache License}, {@code MIT License},
     * {@code Permission is hereby granted, free of charge}, {@code GNU General Public License}, BSD 재배포 조건,
     * {@code Released under the MIT license}, SPDX 식별자. 낱말 license나 저작권 표기만으로는 아니다
     * (사내 스크립트의 {@code Copyright … @author … @version} 머리 주석, "아이콘은 CC 라이선스" 같은 안내문이 걸린다).
     */
    private static final Pattern LICENSE_PHRASE = Pattern.compile("(?i)"
            + "(?:licen[sc]ed|released|distributed|available)(?: under)? (?:the |an? )?(?:terms of (?:the )?)?" + LICENSE_NAMES + "\\b"
            + "|\\b(?:MIT|BSD|ISC|Apache|GPL|LGPL|AGPL|MPL)(?:[- ]style|[- ]?v?\\d[\\w.]*|/[\\w.]+| Software| Public)? licen[sc]e[sd]?\\b"
            + "|permission is hereby granted, free of charge"
            + "|redistribution and use in source and binary forms"
            + "|GNU (?:Lesser |Library |Affero )?General Public License"
            + "|Mozilla Public License"
            + "|SPDX-License-Identifier");
    private static final Pattern LICENSE_TAG = Pattern.compile("@license\\b");
    /** 라이선스 전문의 주소, {@code …/license}로 끝나는 주소({@code jquery.org/license}). */
    private static final Pattern LICENSE_URL = Pattern.compile("(?i)\\bopensource\\.org/licenses|\\bapache\\.org/licenses"
            + "|\\bgnu\\.org/licenses|\\b[\\w-]+(?:\\.[\\w-]+)+/(?:[\\w.-]+/)*licen[sc]e\\b");
    /**
     * 알려진 프로젝트 URL: 코드 호스팅, jQuery, npm. 버전 번호가 함께 있고 "All rights reserved"가 없을 때만 배너로 본다
     * (프로젝트가 자기 저장소 주소를 저작권 표기에 적는 경우가 있다: {@code Copyright © <a href="https://github.com/우리/프로젝트">}).
     */
    private static final Pattern PROJECT_URL = Pattern.compile(
            "(?i)\\bgithub\\.(?:com|io)/|\\bjquery\\.(?:com|org)\\b|\\bnpmjs\\.com\\b|\\bcode\\.google\\.com/p/");
    private static final Pattern VERSION_NUMBER = Pattern.compile("\\bv?\\d+\\.\\d+(?:\\.\\d+)*\\b|\\bv\\d+\\b");
    private static final Pattern ALL_RIGHTS_RESERVED = Pattern.compile("(?i)all rights? reserved");
    /** 주석 줄 머리의 꾸밈({@code *}, {@code //})과 줄바꿈. 문구가 여러 줄에 걸쳐도 맞추기 위해 지운다. */
    private static final Pattern COMMENT_DECORATION = Pattern.compile("\\s*[\\r\\n]+\\s*(?:\\*+(?!/)|//+)?\\s*");
    /** 라이브러리 배포판의 이름 꼴: 이름에 점 있는 버전이 들어 있거나({@code raphael.2.1.1}) {@code jquery}·{@code bootstrap}으로 시작한다. */
    private static final Pattern VERSIONED_NAME = Pattern.compile(".*[-_.]v?\\d+(?:\\.\\d+)+(?:[-_.].*)?");
    /** 머리 주석을 찾을 때 읽는 길이. */
    private static final int HEAD_LENGTH = 4000;

    private final boolean skip;
    private final List<Pattern> patterns = new ArrayList<>();
    /** {@code !}로 시작하는 glob: 벤더가 아니라고 못 박은 경로. 다른 규칙보다 먼저다. */
    private final List<Pattern> exclusions = new ArrayList<>();

    VendorRules(Map<String, String> options) {
        this.skip = !"false".equalsIgnoreCase(trim(options.get(OPTION_SKIP)));
        String raw = options.get(OPTION_PATTERNS);
        if (raw != null) {
            for (String glob : raw.split(",")) {
                String trimmed = glob.trim();
                if (trimmed.startsWith("!") && trimmed.length() > 1) {
                    exclusions.add(globToRegex(trimmed.substring(1).trim()));
                } else if (!trimmed.isEmpty()) {
                    patterns.add(globToRegex(trimmed));
                }
            }
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    /** 벤더로 판정한 파일의 내용 분석을 건너뛰는가({@code web.skipVendor}, 기본 true). */
    boolean skip() {
        return skip;
    }

    /**
     * 경로의 디렉터리 부분만 보고 벤더 폴더 안의 파일인지 판정한다.
     *
     * @return 벤더 폴더의 경로(판정에 쓴 세그먼트까지). 아니면 null
     */
    String vendorDirectory(String relativePath) {
        String[] segments = relativePath.split("/");
        boolean underStatic = false;
        // 화면 파일 영역(WEB-INF, templates) 안쪽인가. 그 안의 plugins/는 업무 폴더 이름일 수 있다
        boolean viewArea = false;
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i];
            String lower = segment.toLowerCase(Locale.ROOT);
            if (prefix.length() > 0) {
                prefix.append('/');
            }
            prefix.append(segment);
            if (lower.equals("web-inf")) {
                underStatic = false;
                viewArea = true;
                continue;
            }
            if (lower.equals("templates")) {
                viewArea = true;
            }
            boolean direct = i == 0 || segments[i - 1].equals("src");
            // plugins/<이름>/…: 플러그인마다 폴더를 둔 배치는 정적 자원 폴더 밖(모듈의 src/plugins 등)이어도 벤더다
            boolean pluginFolder = lower.equals("plugins") && !viewArea && i + 1 < segments.length - 1;
            if (VENDOR_DIRECTORIES.contains(lower) || (LOOSE_DIRECTORIES.contains(lower) && (underStatic || direct))
                    || (underStatic && CONDITIONAL_DIRECTORIES.contains(lower)) || pluginFolder
                    || isLibraryName(lower, false) || DOTTED_VERSION_SUFFIX.matcher(lower).matches()) {
                return prefix.toString();
            }
            if (underStatic && VERSION_ONLY.matcher(lower).matches()) {
                // static/bootstrap/2.3.1/…: 버전만으로 된 폴더. 라이브러리 폴더는 그 부모다
                return prefix.substring(0, prefix.length() - segment.length() - 1);
            }
            if (STATIC_ROOTS.contains(lower)) {
                underStatic = true;
            }
        }
        return null;
    }

    /** 파일 이름이 {@code <이름>-<버전>.js} 꼴이거나 잘 알려진 라이브러리 파일 이름인가. */
    boolean vendorFileName(String relativePath) {
        String name = relativePath.substring(relativePath.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        String stem = SCRIPT_EXTENSION.matcher(name).replaceFirst("");
        stem = MIN_SUFFIX.matcher(stem).replaceFirst("");
        return isLibraryName(stem, true) || DOTTED_VERSION_SUFFIX.matcher(stem).matches() || BUILD_VARIANT.matcher(stem).matches();
    }

    /**
     * 손으로 쓴 소스로 보기 어려운 큰 생성물인가: 20만 자를 넘고 {@code function}이 200번 넘게 나온다
     * (모델링 편집기의 {@code oryx.debug.js} 737KB 같은 것). 번들·라이브러리 묶음이다.
     */
    static boolean looksGenerated(String text) {
        if (text.length() <= GENERATED_LENGTH) {
            return false;
        }
        int count = 0;
        for (int i = text.indexOf("function"); i >= 0; i = text.indexOf("function", i + 8)) {
            if (++count > GENERATED_FUNCTIONS) {
                return true;
            }
        }
        return false;
    }

    /** {@code web.vendorPatterns}의 {@code !glob}에 맞아 벤더가 아니라고 못 박은 경로인가. */
    boolean excluded(String relativePath) {
        for (Pattern p : exclusions) {
            if (p.matcher(relativePath).matches()) {
                return true;
            }
        }
        return false;
    }

    /** {@code web.vendorPatterns}의 glob 가운데 하나에 맞는가. */
    boolean matchesPattern(String relativePath) {
        for (Pattern p : patterns) {
            if (p.matcher(relativePath).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param file 파일 이름(확장자와 {@code .min}을 뗀 것)이면 true, 디렉터리 이름이면 false
     */
    private static boolean isLibraryName(String lower, boolean file) {
        if (!file) {
            // 폴더: jquery, jquery-validation, jquery.form, jquery1.12, jqueryui, bootstrap-table, bootstrap3
            // (bootstrap/ 자체는 정적 자원 폴더 아래일 때만 본다: CONDITIONAL_DIRECTORIES)
            for (String prefix : LIBRARY_PREFIXES) {
                if (lower.equals(prefix)) {
                    return prefix.equals("jquery");
                }
                if (lower.startsWith(prefix)) {
                    char next = lower.charAt(prefix.length());
                    if (next == '-' || next == '.' || next == '_' || Character.isDigit(next)
                            || lower.equals("jqueryui") || lower.equals("bootstrapvalidator")) {
                        return true;
                    }
                }
            }
        }
        if (LIBRARIES.contains(lower) || (file && FILE_LIBRARIES.contains(lower))) {
            return true;
        }
        java.util.regex.Matcher m = NAME_VERSION.matcher(lower);
        return m.matches() && (LIBRARIES.contains(m.group(1)) || (file && FILE_LIBRARIES.contains(m.group(1))));
    }

    /**
     * 파일 머리 주석에 라이선스 배너가 있는가(F-010-R40): {@code @license} 태그, 알려진 오픈소스 라이선스의 정식 문구, 알려진
     * 프로젝트 URL 가운데 하나가 있다. 저작권 표기와 {@code @author}·{@code @version}만 있는 머리 주석
     * ({@code /*! Copyright © 우리 회사 … @version 1.2.0})은 배너가 아니다. 이것만으로 벤더가 되지는 않는다: 프로젝트 정합성
     * 검사({@code JsProjectAnalysis})를 거친다.
     */
    static boolean hasLicenseBanner(String text) {
        String head = leadingComments(text);
        if (head.isEmpty()) {
            return false;
        }
        head = COMMENT_DECORATION.matcher(head).replaceAll(" ");
        if (LICENSE_TAG.matcher(head).find() || LICENSE_PHRASE.matcher(head).find() || LICENSE_URL.matcher(head).find()) {
            return true;
        }
        return PROJECT_URL.matcher(head).find() && VERSION_NUMBER.matcher(head).find()
                && !ALL_RIGHTS_RESERVED.matcher(head).find();
    }

    /**
     * 파일 이름이 라이브러리 배포판의 꼴인가: {@code <이름>-<버전>.js}, {@code .min.js}, {@code jquery.<이름>.js}·
     * {@code bootstrap-<이름>.js}, 알려진 라이브러리 이름. 이름만으로 벤더가 되지는 않지만(파일 이름 규칙이 따로 있다),
     * 머리 주석으로 걸린 파일을 "폴더에서 혼자만 걸렸다"는 이유로 되살리지 않는 근거가 된다.
     */
    static boolean libraryShapedName(String relativePath) {
        String name = relativePath.substring(relativePath.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        String stem = SCRIPT_EXTENSION.matcher(name).replaceFirst("");
        if (MIN_SUFFIX.matcher(stem).find()) {
            return true;
        }
        for (String prefix : LIBRARY_PREFIXES) {
            if (stem.startsWith(prefix) && stem.length() > prefix.length() && !Character.isLetter(stem.charAt(prefix.length()))) {
                return true;
            }
        }
        return VERSIONED_NAME.matcher(stem).matches() || isLibraryName(stem, true);
    }

    /** 파일 맨 앞에 이어지는 주석들(코드가 나오기 전까지). 없으면 빈 문자열. */
    private static String leadingComments(String text) {
        int length = Math.min(text.length(), HEAD_LENGTH);
        int i = 0;
        int start = -1;
        while (i < length) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '﻿') {
                i++;
            } else if (text.startsWith("/*", i)) {
                if (start < 0) {
                    start = i;
                }
                int end = text.indexOf("*/", i + 2);
                if (end < 0 || end >= length) {
                    return text.substring(start, length);
                }
                i = end + 2;
            } else if (text.startsWith("//", i)) {
                if (start < 0) {
                    start = i;
                }
                int end = lineEnd(text, i);
                if (end < 0 || end >= length) {
                    return text.substring(start, length);
                }
                i = end + 1;
            } else {
                break;
            }
        }
        return start < 0 ? "" : text.substring(start, Math.min(i, length));
    }

    /** from 뒤의 첫 줄 끝(LF 또는 CR)의 위치. 없으면 -1. */
    private static int lineEnd(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                return i;
            }
        }
        return -1;
    }

    /**
     * glob을 정규식으로. {@code **}는 {@code /}를 포함한 임의의 글자, {@code *}는 {@code /}를 뺀 임의의 글자, {@code ?}는 한 글자.
     * {@code /}가 없는 glob은 경로의 어느 세그먼트(디렉터리나 파일 이름)에든 맞으면 된다.
     */
    private static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        boolean anySegment = glob.indexOf('/') < 0;
        if (anySegment) {
            sb.append("(?:.*/)?");
        }
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                        i++;
                        sb.append("(?:.*/)?");
                    } else {
                        sb.append(".*");
                    }
                } else {
                    sb.append("[^/]*");
                }
            } else if (c == '?') {
                sb.append("[^/]");
            } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        if (anySegment) {
            sb.append("(?:/.*)?");
        }
        return Pattern.compile(sb.toString());
    }
}
