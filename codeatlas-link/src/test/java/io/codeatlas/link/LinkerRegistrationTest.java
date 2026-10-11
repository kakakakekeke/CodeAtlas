package io.codeatlas.link;

import io.codeatlas.core.spi.Linker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class LinkerRegistrationTest {

    @Test
    @DisplayName("F-012-AC1 F-012-AC26 F-018-AC1 링커는 ServiceLoader로 등록되고 외부 표시 → 정규화 → 연결 → 추론 순서로 실행된다")
    void registeredInOrder() {
        List<Linker> linkers = ServiceLoader.load(Linker.class).stream().map(ServiceLoader.Provider::get)
                .filter(l -> l.getClass().getPackageName().equals("io.codeatlas.link"))
                .sorted(Comparator.comparingInt(Linker::order).thenComparing(Linker::name))
                .toList();

        assertThat(linkers).extracting(l -> l.getClass().getSimpleName()).containsExactly(
                "ExternalReferenceMarker", "DbObjectNormalizer", "MapperBindingLinker", "ViewLinker", "HttpMatchLinker",
                "BeanReferenceLinker", "InjectionCandidateLinker", "RelationInferenceLinker");
        assertThat(linkers).extracting(Linker::order).containsExactly(90, 100, 200, 220, 225, 230, 240, 300);   // HTTP 연결은 뷰 연결의 RENDERS를 쓴다(F-012-R18)
        assertThat(linkers).extracting(Linker::name).doesNotHaveDuplicates();
    }
}
