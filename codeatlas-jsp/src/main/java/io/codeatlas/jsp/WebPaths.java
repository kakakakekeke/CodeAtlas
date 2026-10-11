package io.codeatlas.jsp;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;

/** 웹 루트 판정과 경로 계산. 경로는 모두 프로젝트 루트 기준 상대 경로이고 구분자는 {@code /}다. */
final class WebPaths {

    private static final Set<String> WEB_ROOT_NAMES = Set.of("webapp", "webcontent", "webroot", "web");
    private static final Set<String> PAGE_EXTENSIONS = Set.of("jsp", "jspf", "jspx", "tag", "tagx", "html", "htm", "inc");

    private WebPaths() {
    }

    /**
     * 파일이 속한 웹 루트(끝에 {@code /} 없음, 프로젝트 루트면 빈 문자열).
     * {@code WEB-INF}의 부모 → 이름이 webapp·WebContent·WebRoot·web인 가장 가까운 조상
     * → {@code src/main/resources} 아래의 static·public·META-INF/resources → 프로젝트 루트 순으로 찾는다.
     */
    static String webRootOf(String relativePath) {
        String[] segments = relativePath.split("/");
        int directories = segments.length - 1;
        for (int i = 0; i < directories; i++) {
            if (segments[i].equalsIgnoreCase("WEB-INF")) {
                return join(segments, i);
            }
        }
        for (int i = directories - 1; i >= 0; i--) {
            if (WEB_ROOT_NAMES.contains(segments[i].toLowerCase(Locale.ROOT))) {
                return join(segments, i + 1);
            }
        }
        for (int i = 0; i + 1 < directories; i++) {
            if (!segments[i].equals("resources")) {
                continue;
            }
            String next = segments[i + 1];
            if (next.equals("static") || next.equals("public")) {
                return join(segments, i + 2);
            }
            if (next.equals("META-INF") && i + 2 < directories && segments[i + 2].equals("resources")) {
                return join(segments, i + 3);
            }
        }
        return "";
    }

    /** 파일이 든 디렉터리. 루트면 빈 문자열. */
    static String directoryOf(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        return slash < 0 ? "" : relativePath.substring(0, slash);
    }

    /**
     * {@code /}로 시작하는 경로는 웹 루트 기준, 아니면 {@code baseDirectory} 기준으로 풀어 프로젝트 기준 경로를 돌려준다.
     * 프로젝트 루트 위로 벗어나면 null.
     */
    static String resolve(String webRoot, String baseDirectory, String path) {
        String base = path.startsWith("/") ? webRoot : baseDirectory;
        Deque<String> stack = new ArrayDeque<>();
        for (String segment : (base + "/" + path).split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (stack.isEmpty()) {
                    return null;
                }
                stack.removeLast();
            } else {
                stack.addLast(segment);
            }
        }
        return String.join("/", stack);
    }

    /** 쿼리스트링과 fragment를 뗀다. */
    static String stripQuery(String path) {
        int cut = path.length();
        int question = path.indexOf('?');
        int hash = path.indexOf('#');
        if (question >= 0) {
            cut = question;
        }
        if (hash >= 0 && hash < cut) {
            cut = hash;
        }
        return path.substring(0, cut);
    }

    /** JSP·HTML 조각으로 볼 확장자인가. */
    static boolean hasPageExtension(String path) {
        String last = path.substring(path.lastIndexOf('/') + 1);
        int dot = last.lastIndexOf('.');
        return dot >= 0 && PAGE_EXTENSIONS.contains(last.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String join(String[] segments, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(segments[i]);
        }
        return sb.toString();
    }
}
