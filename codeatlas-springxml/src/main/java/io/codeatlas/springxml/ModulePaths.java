package io.codeatlas.springxml;

import io.codeatlas.core.spi.ProjectFiles;
import io.codeatlas.core.spi.SourceFile;
import java.util.HashSet;
import java.util.Set;

/**
 * 설정 파일이 속한 빌드 모듈과 테스트 소스 여부(F-017-R13). 모듈 루트는 가장 가까운 조상 디렉터리 가운데
 * {@code pom.xml}, {@code build.gradle}, {@code build.gradle.kts}가 있는 곳이다(링커의 판정과 같은 규칙).
 * 빈 이름은 프로젝트 전체에서 노드 하나라, 이름이 같은 정의가 "같은 컨텍스트에서 겹친 것"인지
 * "다른 웹 모듈·테스트 설정의 것"인지를 이것으로 가린다.
 */
final class ModulePaths {

    private static final Set<String> BUILD_FILES = Set.of("pom.xml", "build.gradle", "build.gradle.kts");

    /** 빌드 파일이 있는 디렉터리(프로젝트 루트는 빈 문자열, 그 밖에는 끝에 {@code /}가 없는 상대 경로). */
    private final Set<String> roots = new HashSet<>();

    private ModulePaths() {
    }

    static ModulePaths of(ProjectFiles files) {
        ModulePaths result = new ModulePaths();
        for (SourceFile file : files.all()) {
            if (BUILD_FILES.contains(file.fileName())) {
                String path = file.relativePath();
                int slash = path.lastIndexOf('/');
                result.roots.add(slash < 0 ? "" : path.substring(0, slash));
            }
        }
        return result;
    }

    /** 파일의 모듈 루트. 프로젝트 루트가 모듈이면 빈 문자열, 조상에 빌드 파일이 없으면 null. */
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

    /** 테스트 소스({@code src/test/} 아래)의 파일인가. */
    static boolean isTestPath(String file) {
        return file != null && ("/" + file.replace('\\', '/')).contains("/src/test/");
    }

    /**
     * 같은 이름의 정의가 실제로 겹칠 수 있는 범위: 모듈 루트와 main·test 구분.
     * 조상에 빌드 파일이 없는 파일끼리는 한 범위로 본다(빌드 파일이 없는 프로젝트에서는 전체가 한 범위다).
     */
    String scopeOf(String file) {
        return rootOf(file) + (isTestPath(file) ? "|test" : "|main");
    }
}
