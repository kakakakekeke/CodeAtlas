package io.codeatlas.core.spi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 스캔한 분석 대상 파일 목록. 상대 경로 순으로 정렬되어 있고 바뀌지 않는다. */
public final class ProjectFiles {

    private final List<SourceFile> all;
    private final Map<String, SourceFile> byRelativePath = new HashMap<>();

    private ProjectFiles(Collection<SourceFile> files) {
        List<SourceFile> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparing(SourceFile::relativePath));
        this.all = List.copyOf(sorted);
        for (SourceFile file : all) {
            byRelativePath.put(file.relativePath(), file);
        }
    }

    public static ProjectFiles of(Collection<SourceFile> files) {
        return new ProjectFiles(files);
    }

    /** 모든 파일. */
    public List<SourceFile> all() {
        return all;
    }

    /** 주어진 종류의 파일. */
    public List<SourceFile> ofType(FileType... types) {
        Set<FileType> wanted = EnumSet.noneOf(FileType.class);
        wanted.addAll(List.of(types));
        return all.stream().filter(f -> wanted.contains(f.type())).toList();
    }

    /** 상대 경로('/' 구분)로 찾는다. */
    public Optional<SourceFile> byRelativePath(String relativePath) {
        return Optional.ofNullable(byRelativePath.get(relativePath.replace('\\', '/')));
    }

    /** 파일 종류별 개수. 하나도 없는 종류는 들어 있지 않다. */
    public Map<FileType, Integer> countsByType() {
        Map<FileType, Integer> counts = new EnumMap<>(FileType.class);
        for (SourceFile file : all) {
            counts.merge(file.type(), 1, Integer::sum);
        }
        return counts;
    }

    public int size() {
        return all.size();
    }
}
