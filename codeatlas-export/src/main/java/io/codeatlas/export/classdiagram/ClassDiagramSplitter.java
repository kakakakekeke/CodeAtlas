package io.codeatlas.export.classdiagram;

import io.codeatlas.export.classdiagram.ClassDiagramModel.Relation;
import io.codeatlas.export.classdiagram.ClassDiagramModel.TypeBox;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 한 장으로는 Mermaid의 글자 수 한도({@code maxTextSize}, 기본 50,000자)를 넘는 클래스 다이어그램을 이름순 조각으로 나눈다.
 * 조각에는 그 조각의 타입과, 관계로 이어진 다른 조각의 타입(이름만)과, 그 관계가 나온다.
 * 조각마다 한도 안에 드는 가장 자세한 멤버 표시 수준을 쓴다.
 */
public final class ClassDiagramSplitter {

    private ClassDiagramSplitter() {
    }

    /**
     * @param models 같은 범위를 멤버 표시 수준만 달리해 만든 모델. 자세한 것부터 놓고 마지막이 가장 간단한 것(멤버 없음)이다
     * @param options 렌더링 옵션(패키지 묶음 여부)
     * @param maxChars 조각 하나의 글자 수 한도. 타입 하나짜리 조각이 한도를 넘으면 그대로 둔다
     * @return 조각의 {@code classDiagram} 텍스트(이름순)
     */
    public static List<String> split(List<ClassDiagramModel> models, ClassDiagramOptions options, int maxChars) {
        ClassDiagramModel simplest = models.get(models.size() - 1);
        List<String> ids = new ArrayList<>();
        for (TypeBox t : simplest.types()) {
            if (!t.nameOnly()) {
                ids.add(t.id());
            }
        }
        for (int parts = 2; parts <= Math.max(2, ids.size()); parts++) {
            int size = Math.max(1, (ids.size() + parts - 1) / parts);
            List<List<String>> chunks = new ArrayList<>();
            boolean ok = true;
            for (int from = 0; from < ids.size() && ok; from += size) {
                List<String> chunk = ids.subList(from, Math.min(ids.size(), from + size));
                ok = chunk.size() == 1 || render(simplest, chunk, options).length() <= maxChars;
                chunks.add(chunk);
            }
            if (!ok && size > 1) {
                continue;
            }
            List<String> texts = new ArrayList<>();
            for (List<String> chunk : chunks) {
                String text = null;
                for (ClassDiagramModel model : models) {
                    text = render(model, chunk, options);
                    if (text.length() <= maxChars) {
                        break;
                    }
                }
                texts.add(text);
            }
            return texts;
        }
        return List.of(ClassDiagrams.render(simplest, options, ClassDiagramFormat.MERMAID));
    }

    private static String render(ClassDiagramModel model, List<String> chunk, ClassDiagramOptions options) {
        Set<String> inside = new TreeSet<>(chunk);
        Map<String, TypeBox> byId = new HashMap<>();
        model.types().forEach(t -> byId.put(t.id(), t));
        Set<String> neighbors = new TreeSet<>();
        List<Relation> relations = new ArrayList<>();
        for (Relation r : model.relations()) {
            boolean from = inside.contains(r.from());
            boolean to = inside.contains(r.to());
            if (from || to) {
                relations.add(r);
                if (!from) {
                    neighbors.add(r.from());
                }
                if (!to) {
                    neighbors.add(r.to());
                }
            }
        }
        List<TypeBox> types = new ArrayList<>();
        for (TypeBox t : model.types()) {
            if (inside.contains(t.id())) {
                types.add(t);
            } else if (neighbors.contains(t.id())) {
                types.add(new TypeBox(t.id(), t.binaryName(), t.packageName(), t.displayName(), t.typeKind(), t.isAbstract(), t.stereotype(),
                        t.typeParameters(), t.external(), true, List.of(), List.of()));
            }
        }
        return ClassDiagrams.render(new ClassDiagramModel(types, relations, List.of()), options, ClassDiagramFormat.MERMAID);
    }
}
