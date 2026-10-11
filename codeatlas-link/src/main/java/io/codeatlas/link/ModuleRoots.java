package io.codeatlas.link;

import io.codeatlas.core.spi.ProjectFiles;
import io.codeatlas.core.spi.SourceFile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 파일이 속한 빌드 모듈의 루트 디렉터리를 찾는다. 모듈 루트는 가장 가까운 조상 디렉터리 가운데
 * {@code pom.xml}, {@code build.gradle}, {@code build.gradle.kts}가 있는 곳이다.
 *
 * <p>여러 웹앱이 한 저장소에 든 프로젝트에서 "같은 앱 안의 연결인가"를 가리는 데 쓴다(요청↔엔드포인트, 빈 이름, 프로퍼티 값).
 */
final class ModuleRoots {

    private static final Set<String> BUILD_FILES = Set.of("pom.xml", "build.gradle", "build.gradle.kts");

    private static final Pattern WAR_PACKAGING = Pattern.compile("<packaging>\\s*war\\s*</packaging>");
    private static final Pattern GRADLE_WAR = Pattern.compile("(?m)^\\s*(?:id\\s*\\(?\\s*['\"]war['\"]|apply\\s+plugin\\s*:\\s*['\"]war['\"]|war\\s*$)");

    /** 빌드 파일이 있는 디렉터리(프로젝트 루트는 빈 문자열, 그 밖에는 끝에 {@code /}가 없는 상대 경로). */
    private final Set<String> roots = new HashSet<>();
    private final List<SourceFile> buildFiles = new ArrayList<>();
    private final List<String> webXmlFiles = new ArrayList<>();
    /** 독립 배포 단위로 보이는 모듈 루트. 처음 물을 때 빌드 파일을 읽어 채운다. */
    private Set<String> webApps;

    private ModuleRoots() {
    }

    static ModuleRoots of(ProjectFiles files) {
        ModuleRoots result = new ModuleRoots();
        for (SourceFile file : files.all()) {
            if (BUILD_FILES.contains(file.fileName())) {
                String path = file.relativePath();
                int slash = path.lastIndexOf('/');
                result.roots.add(slash < 0 ? "" : path.substring(0, slash));
                result.buildFiles.add(file);
            } else if (file.fileName().equals("web.xml") && !isTestPath(file.relativePath())) {
                result.webXmlFiles.add(file.relativePath());
            }
        }
        return result;
    }

    /**
     * 모듈이 독립 배포 단위(웹앱)로 보이는가: 그 모듈에 {@code web.xml}이 있거나 빌드 파일이 war로 묶는다고 적었다
     * ({@code <packaging>war</packaging>}, Gradle {@code war} 플러그인). Spring Boot 메인 클래스는 그래프에서 읽어
     * {@link #markWebApp(String)}로 더한다. 다른 웹앱의 같은 경로는 다른 URL이라 요청↔엔드포인트 연결을 막는 데 쓴다(F-012-R38).
     */
    boolean isWebApp(String moduleRoot) {
        if (moduleRoot == null) {
            return false;
        }
        return webApps().contains(moduleRoot);
    }

    /** 그래프에서 알아낸 독립 배포 단위(Spring Boot 메인 클래스가 있는 모듈)를 더한다. */
    void markWebApp(String moduleRoot) {
        if (moduleRoot != null) {
            webApps().add(moduleRoot);
        }
    }

    private Set<String> webApps() {
        if (webApps == null) {
            webApps = new HashSet<>();
            for (String webXml : webXmlFiles) {
                String root = rootOf(webXml);
                if (root != null) {
                    webApps.add(root);
                }
            }
            for (SourceFile file : buildFiles) {
                String path = file.relativePath();
                int slash = path.lastIndexOf('/');
                String root = slash < 0 ? "" : path.substring(0, slash);
                try {
                    String text = file.readText();
                    if (file.fileName().equals("pom.xml") ? WAR_PACKAGING.matcher(text).find() : GRADLE_WAR.matcher(text).find()) {
                        webApps.add(root);
                    }
                } catch (RuntimeException e) {
                    // 읽지 못한 빌드 파일은 건너뛴다
                }
            }
        }
        return webApps;
    }

    /** 빌드 파일을 하나도 못 본 프로젝트인가. */
    boolean isEmpty() {
        return roots.isEmpty();
    }

    /**
     * 파일의 모듈 루트.
     *
     * @return 프로젝트 루트가 모듈이면 빈 문자열, 조상에 빌드 파일이 없으면(프런트 전용 폴더 등) null
     */
    String rootOf(String file) {
        if (file == null) {
            return null;
        }
        String directory = file.replace('\\', '/');
        while (true) {
            int slash = directory.lastIndexOf('/');
            if (slash < 0) {
                return roots.contains("") ? "" : null;
            }
            directory = directory.substring(0, slash);
            if (roots.contains(directory)) {
                return directory;
            }
        }
    }

    /** 두 파일이 같은 모듈인가. 어느 한쪽의 모듈을 모르면 false. */
    boolean sameModule(String a, String b) {
        String rootA = rootOf(a);
        return rootA != null && rootA.equals(rootOf(b));
    }

    /** 테스트 소스({@code src/test/} 아래)의 파일인가. */
    static boolean isTestPath(String file) {
        if (file == null) {
            return false;
        }
        String path = "/" + file.replace('\\', '/');
        return path.contains("/src/test/");
    }
}
