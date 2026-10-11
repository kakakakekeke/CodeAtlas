package io.codeatlas.java;

import io.codeatlas.core.model.SourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F-005에서 파일을 넘나들며 모으는 정보. 스텝이 어느 잡에 속하는지는 모든 파일을 본 뒤에야 알 수 있어
 * {@link BatchLinker}가 마지막에 노드와 엣지로 만든다.
 */
final class BatchModel {

    /** 스텝 빌더 체인 하나. */
    static final class StepDef {
        String name;
        /** 체인이 있는 타입과 메서드. 잡이 메서드 호출이나 빈 이름으로 스텝을 가리킬 때 찾는 키다. */
        String typeName;
        String methodName;
        String methodId;
        SourceLocation location;
        String stepType;
        Integer chunkSize;
        final List<Use> uses = new ArrayList<>();
    }

    /** 스텝의 구성 요소 하나. targetId가 있으면 해석된 것, beanName이 있으면 빈 이름으로 나중에 찾는 것, 둘 다 없으면 미해결. */
    static final class Use {
        String role;
        String targetId;
        boolean lambda;
        String beanName;
        TypeRef fallbackType;
        String text;
        SourceLocation location;
    }

    /** 잡 체인이 가리키는 스텝. inline, (typeName, methodName), beanName 중 하나로 찾고 모두 없으면 미해결이다. */
    static final class StepRef {
        StepDef inline;
        String typeName;
        String methodName;
        String beanName;
        String text;
        SourceLocation location;
    }

    /** 스텝 흐름 하나. */
    record Next(StepRef from, StepRef to, String on, SourceLocation location) {
    }

    /** 잡 빌더 체인 하나. */
    static final class JobDef {
        String name;
        String methodId;
        SourceLocation location;
        final List<StepRef> steps = new ArrayList<>();
        final List<Next> nexts = new ArrayList<>();
    }

    /**
     * MyBatis 리더·라이터에 준 구문 ID.
     *
     * @param value 상수 값. 상수가 아니면 null
     * @param text 식의 원문
     */
    record Statement(String value, String text, SourceLocation location) {
    }

    final List<StepDef> steps = new ArrayList<>();
    final List<JobDef> jobs = new ArrayList<>();
    /** 메서드 ID → 그 메서드 안에서 MyBatis 리더·라이터에 준 구문 ID. */
    final Map<String, List<Statement>> statements = new LinkedHashMap<>();
}
