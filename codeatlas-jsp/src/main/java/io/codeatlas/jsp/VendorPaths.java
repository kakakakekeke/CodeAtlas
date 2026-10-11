package io.codeatlas.jsp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 벤더(서드파티) 폴더 판정(F-011-R15). 라이브러리가 함께 싣고 오는 예제·대화상자·도움말 페이지(ckeditor 플러그인의 대화상자 등)의
 * 링크는 이 프로젝트의 화면 흐름이 아니다.
 *
 * <p>규칙은 F-011 spec 세부 규칙 "벤더 판정"의 경로 규칙과 같다. web 모듈(F-010)은 의존을 공유하지 않으므로 같은 규칙을 따로
 * 구현한다({@code io.codeatlas.web.analysis.VendorRules}). 한쪽을 고치면 다른 쪽도 고친다. 페이지에는 파일 이름 규칙과
 * 머리 주석 규칙을 쓰지 않는다(경로 규칙만).
 */
final class VendorPaths {

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
    /** 잘 알려진 라이브러리 이름(소문자). 디렉터리 이름이 이것과 같거나 뒤에 버전이 붙은 꼴이면 벤더다. */
    private static final Set<String> LIBRARIES = Set.of("ckeditor", "ckfinder", "tinymce", "easyui", "ztree", "echarts",
            "swfobject", "webuploader", "codemirror", "ueditor", "umeditor", "kindeditor", "xheditor", "summernote",
            "layui", "laydate", "my97datepicker", "select2", "jqgrid", "datatables", "fullcalendar", "pdfjs",
            "angularjs", "scriptaculous", "mootools", "extjs", "highcharts", "jqplot", "jstree",
            "treetable", "superslide", "supersized", "mcustomscrollbar", "malihu-custom-scrollbar-plugin", "jerichotab",
            "jbox", "artdialog", "lhgdialog", "fancybox", "uploadify", "plupload", "swfupload", "zeroclipboard",
            "syntaxhighlighter", "es5-shim", "es5-sham", "requirejs", "seajs", "fullpage", "sweetalert", "videojs",
            "video-js", "mathjax", "font-awesome", "fontawesome");
    /** 이름이 이것으로 시작하기만 하면 벤더인 라이브러리({@code jquery-validation}, {@code bootstrap-table}). */
    private static final List<String> LIBRARY_PREFIXES = List.of("jquery", "bootstrap");

    /** {@code 2.3.1}, {@code v3.4}: 버전만으로 된 이름. */
    private static final Pattern VERSION_ONLY = Pattern.compile("v?\\d+(\\.\\d+)+");
    /** {@code zTree_v3}, {@code webuploader-0.1.5}: 이름 뒤에 버전이 붙은 꼴. */
    private static final Pattern NAME_VERSION = Pattern.compile(
            "([a-z][a-z0-9_.-]*?)[-_.]?v?\\d+(?:\\.\\d+)*(?:[-_.](?:min|pack|packed|custom|full|all|src|debug|slim|bundle|rc\\d*|beta\\d*|alpha\\d*))*");
    /** 이름과 버전 사이에 구분자가 있고 버전에 점이 있는 꼴(모르는 이름에도 적용): {@code font-awesome-4.7.0}. */
    private static final Pattern DOTTED_VERSION_SUFFIX = Pattern.compile(
            "[a-z][a-z0-9_.-]*?[-_.]v?\\d+(?:\\.\\d+)+(?:[-_.](?:min|pack|packed|custom|full|all|src|debug|slim|bundle|rc\\d*|beta\\d*|alpha\\d*))*");

    private final boolean skip;
    private final List<Pattern> patterns = new ArrayList<>();
    /** {@code !}로 시작하는 glob: 벤더가 아니라고 못 박은 경로. 다른 규칙보다 먼저다. */
    private final List<Pattern> exclusions = new ArrayList<>();

    VendorPaths(Map<String, String> options) {
        String flag = options.get(OPTION_SKIP);
        this.skip = flag == null || !"false".equalsIgnoreCase(flag.trim());
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

    static final String REASON_PATH = "경로";
    static final String REASON_OPTION = "옵션";
    /** 판정 근거를 적는 순서(FILE_SKIPPED 요약 진단). */
    static final java.util.List<String> REASONS = java.util.List.of(REASON_PATH, REASON_OPTION);

    /**
     * 건너뛸 페이지의 판정.
     *
     * @param root 벤더 폴더의 경로({@code web.vendorPatterns}에 맞은 것은 파일의 폴더)
     * @param reason 판정 근거: {@value #REASON_PATH}(경로 규칙) 또는 {@value #REASON_OPTION}({@code web.vendorPatterns})
     */
    record Skipped(String root, String reason) {
    }

    /**
     * 벤더 폴더 안의 페이지여서 내용을 분석하지 않을 것이면 그 벤더 폴더의 경로({@code web.vendorPatterns}에 맞은 것은 파일의 폴더).
     * 분석할 페이지면 null. {@code web.skipVendor=false}면 항상 null이다.
     */
    String skippedRoot(String relativePath) {
        Skipped skipped = skipped(relativePath);
        return skipped == null ? null : skipped.root();
    }

    /** 건너뛸 페이지면 그 벤더 폴더와 판정 근거, 분석할 페이지면 null. */
    Skipped skipped(String relativePath) {
        if (!skip) {
            return null;
        }
        for (Pattern p : exclusions) {
            if (p.matcher(relativePath).matches()) {
                return null;
            }
        }
        String directory = vendorDirectory(relativePath);
        if (directory != null) {
            return new Skipped(directory, REASON_PATH);
        }
        for (Pattern p : patterns) {
            if (p.matcher(relativePath).matches()) {
                int slash = relativePath.lastIndexOf('/');
                return new Skipped(slash < 0 ? "." : relativePath.substring(0, slash), REASON_OPTION);
            }
        }
        return null;
    }

    /**
     * 경로의 디렉터리 부분만 보고 벤더 폴더 안의 파일인지 판정한다.
     *
     * @return 벤더 폴더의 경로(판정에 쓴 세그먼트까지). 아니면 null
     */
    static String vendorDirectory(String relativePath) {
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
                    || isLibraryName(lower) || DOTTED_VERSION_SUFFIX.matcher(lower).matches()) {
                return prefix.toString();
            }
            if (underStatic && VERSION_ONLY.matcher(lower).matches()) {
                // static/foo/2.3.1/…: 버전만으로 된 폴더. 라이브러리 폴더는 그 부모다
                return prefix.substring(0, prefix.length() - segment.length() - 1);
            }
            if (STATIC_ROOTS.contains(lower)) {
                underStatic = true;
            }
        }
        return null;
    }

    private static boolean isLibraryName(String lower) {
        for (String prefix : LIBRARY_PREFIXES) {
            if (lower.equals(prefix)) {
                // bootstrap/ 폴더는 정적 자원 폴더 아래일 때만(CONDITIONAL_DIRECTORIES)
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
        if (LIBRARIES.contains(lower)) {
            return true;
        }
        Matcher m = NAME_VERSION.matcher(lower);
        return m.matches() && LIBRARIES.contains(m.group(1));
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
