package io.codeatlas.springxml;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/** {@code <import resource>}가 가리키는 파일을 프로젝트의 Spring XML 가운데서 찾는다. */
final class ImportResolver {

    private ImportResolver() {
    }

    /**
     * @param resource import의 resource 원문
     * @param importingFile import를 쓴 파일의 상대 경로
     * @param candidates 프로젝트의 Spring XML 상대 경로들
     * @return 찾은 파일들(경로순). 플레이스홀더가 있거나 찾지 못하면 빈 목록
     */
    static List<String> resolve(String resource, String importingFile, Collection<String> candidates) {
        List<String> found = new ArrayList<>();
        if (!BeanRegistry.isLiteral(resource)) {
            return found;
        }
        String pattern = resource.strip();
        boolean anywhere = false;
        if (pattern.startsWith("classpath*:")) {
            pattern = pattern.substring("classpath*:".length());
            anywhere = true;
        } else if (pattern.startsWith("classpath:")) {
            pattern = pattern.substring("classpath:".length());
            anywhere = true;
        } else if (pattern.startsWith("file:")) {
            return found;
        } else if (pattern.startsWith("/")) {
            // 웹 애플리케이션 루트나 클래스패스 루트 기준 경로
            anywhere = true;
        }
        Pattern regex;
        if (anywhere) {
            regex = Pattern.compile("(?:.*/)?" + globToRegex(stripLeadingSlashes(pattern)));
        } else {
            int slash = importingFile.lastIndexOf('/');
            String base = slash < 0 ? "" : importingFile.substring(0, slash + 1);
            regex = Pattern.compile(globToRegex(normalize(base + pattern)));
        }
        for (String candidate : candidates) {
            if (!candidate.equals(importingFile) && regex.matcher(candidate).matches()) {
                found.add(candidate);
            }
        }
        return found;
    }

    private static String stripLeadingSlashes(String path) {
        int i = 0;
        while (i < path.length() && path.charAt(i) == '/') {
            i++;
        }
        return path.substring(i);
    }

    /** {@code a/b/../c}와 {@code ./}를 정리한다. */
    private static String normalize(String path) {
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..") && !parts.isEmpty()) {
                parts.remove(parts.size() - 1);
            } else {
                parts.add(part);
            }
        }
        return String.join("/", parts);
    }

    /** Ant 경로 패턴({@code **}, {@code *}, {@code ?})을 정규식으로 바꾼다. */
    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (glob.startsWith("**/", i)) {
                regex.append("(?:.*/)?");
                i += 3;
            } else if (glob.startsWith("**", i)) {
                regex.append(".*");
                i += 2;
            } else if (c == '*') {
                regex.append("[^/]*");
                i++;
            } else if (c == '?') {
                regex.append("[^/]");
                i++;
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        return regex.toString();
    }
}
