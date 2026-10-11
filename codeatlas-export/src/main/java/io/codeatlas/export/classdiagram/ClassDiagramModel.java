package io.codeatlas.export.classdiagram;

import io.codeatlas.core.model.EdgeKind;
import java.util.List;
import java.util.Optional;

/**
 * 형식과 무관한 클래스 다이어그램 중간 모델. 타입과 멤버는 ID 순, 관계는 (from, to, 종류, 라벨) 순이다.
 *
 * @param unresolvedNames {@link ClassDiagramOptions#types()}로 준 시작 타입 이름 중 범위의 후보(external·익명·중첩 옵션을
 *                        통과한 타입)에서 찾지 못한 것. 준 순서대로, 원문 그대로, 중복 없이
 */
public record ClassDiagramModel(List<TypeBox> types, List<Relation> relations, List<String> warnings, List<String> unresolvedNames) {

    public ClassDiagramModel {
        types = List.copyOf(types);
        relations = List.copyOf(relations);
        warnings = List.copyOf(warnings);
        unresolvedNames = unresolvedNames == null ? List.of() : List.copyOf(unresolvedNames);
    }

    /** 찾지 못한 이름이 없는 모델. */
    public ClassDiagramModel(List<TypeBox> types, List<Relation> relations, List<String> warnings) {
        this(types, relations, warnings, List.of());
    }

    /**
     * @param id 노드 ID
     * @param binaryName 예: {@code com.a.Outer$Inner}
     * @param displayName 패키지를 뗀 이름. 예: {@code Outer.Inner}
     * @param typeKind {@code class}, {@code interface}, {@code enum}, {@code record}, {@code annotation}
     * @param stereotype 계약의 값 그대로(소문자). 없으면 null
     * @param nameOnly true면 이름만 그린다(범위 밖의 external 상위 타입)
     */
    public record TypeBox(String id, String binaryName, String packageName, String displayName, String typeKind,
            boolean isAbstract, String stereotype, List<String> typeParameters, boolean external, boolean nameOnly,
            List<Member> fields, List<Member> methods) {
        public TypeBox {
            typeParameters = List.copyOf(typeParameters);
            fields = List.copyOf(fields);
            methods = List.copyOf(methods);
        }
    }

    /**
     * 필드 또는 메서드.
     *
     * @param name 생성자는 타입의 단순 이름
     * @param visibility {@code +}, {@code -}, {@code #}, {@code ~}
     * @param type 필드 타입 또는 반환 타입(패키지를 뗀 표기). 없으면 null
     * @param parameterTypes 메서드 매개변수 타입(패키지를 뗀 표기)
     * @param parameterNames 매개변수 이름. 모르면 빈 목록
     */
    public record Member(String id, String name, char visibility, boolean isStatic, boolean isAbstract, String type,
            List<String> parameterTypes, List<String> parameterNames, boolean constructor, boolean method) {
        public Member {
            parameterTypes = List.copyOf(parameterTypes);
            parameterNames = List.copyOf(parameterNames);
        }
    }

    /**
     * @param from 엣지의 from 타입 ID(하위 타입, 소유 타입, 의존하는 쪽)
     * @param to 엣지의 to 타입 ID
     * @param label 연관의 필드명. 없으면 null
     * @param multiplicity 연관의 다중도({@code 1}, {@code *}). 없으면 null
     */
    public record Relation(String from, String to, EdgeKind kind, String label, String multiplicity) {
    }

    /** 바이너리 이름 또는 노드 ID로 타입을 찾는다. */
    public Optional<TypeBox> type(String binaryNameOrId) {
        return types.stream().filter(t -> t.binaryName().equals(binaryNameOrId) || t.id().equals(binaryNameOrId)).findFirst();
    }
}
