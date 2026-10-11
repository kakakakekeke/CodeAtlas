package io.codeatlas.sql.routine;

import io.codeatlas.sql.analysis.FactsWriter;
import io.codeatlas.sql.analysis.Locator;
import io.codeatlas.sql.analysis.StatementFacts;
import io.codeatlas.sql.ast.Pos;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 익명 블록(또는 풀린 동적 SQL 블록)의 주인 구문 노드 하나에 안쪽 구문의 사실과 호출을 모은다. */
final class BlockSink implements BodyWalker.Sink {

    private final FactsWriter writer;
    private final Locator locator;

    BlockSink(FactsWriter writer, Locator locator) {
        this.writer = writer;
        this.locator = locator;
    }

    @Override
    public void statement(Pos pos, String text, Map<String, Object> attrs, Consumer<FactsWriter> fill) {
        fill.accept(writer);
        if (Boolean.TRUE.equals(attrs.get("dynamic"))) {
            StatementFacts facts = new StatementFacts();
            facts.markDynamic();
            writer.add(facts, locator);
        }
    }

    @Override
    public void call(String packageName, String name, Pos pos, boolean function) {
        StatementFacts facts = new StatementFacts();
        facts.noteType("CALL");
        facts.call(packageName == null ? List.of(name) : List.of(packageName, name), false, pos);
        writer.add(facts, locator);
    }
}
