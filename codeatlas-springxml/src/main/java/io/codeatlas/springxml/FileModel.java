package io.codeatlas.springxml;

import io.codeatlas.core.spi.SourceFile;
import java.util.ArrayList;
import java.util.List;

/** Spring XML 파일 하나에서 읽은 것. */
final class FileModel {

    final SourceFile file;
    /** 최상위 빈(문서 순). */
    final List<BeanDef> beans = new ArrayList<>();
    /** 배치 요소 안에 인라인으로 정의된 빈(내부 빈 취급). */
    final List<BeanDef> detachedInnerBeans = new ArrayList<>();
    /** {@code <alias name alias>}: [name, alias]. */
    final List<String[]> aliases = new ArrayList<>();
    final List<String> imports = new ArrayList<>();
    final List<String> componentScan = new ArrayList<>();
    final List<String> mybatisScan = new ArrayList<>();
    final List<String> propertyLocations = new ArrayList<>();
    final List<BatchDefs.JobDef> jobs = new ArrayList<>();
    final List<BatchDefs.StepDef> standaloneSteps = new ArrayList<>();
    final List<BatchDefs.FlowDef> flows = new ArrayList<>();

    FileModel(SourceFile file) {
        this.file = file;
    }

    String path() {
        return file.relativePath();
    }
}
