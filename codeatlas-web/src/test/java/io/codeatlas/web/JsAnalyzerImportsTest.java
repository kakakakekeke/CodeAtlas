package io.codeatlas.web;

import static io.codeatlas.web.G.mod;
import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.testing.TestProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JsAnalyzerImportsTest {

    @Test
    @DisplayName("F-010-AC5 상대 경로, index, re-export, require, 동적 import, tsconfig 별칭을 IMPORTS로 잇고 패키지는 잇지 않는다")
    void imports() {
        String main = """
                import a from './util';
                import { b } from './lib';
                export { c } from './reexp';
                const d = require('./cjs');
                const lazy = () => import('./lazy');
                import Btn from '@/components/Button';
                import React from 'react';
                import missing from './nope';
                import './style.css';
                import data from './data.json';
                """;
        try (TestProject project = TestProject.create()
                .file("tsconfig.json", """
                        {
                          // 주석과 끝 쉼표가 있는 tsconfig
                          "compilerOptions": { "baseUrl": ".", "paths": { "@/*": ["src/*"], }, },
                        }
                        """)
                .file("src/main.js", main)
                .file("src/util.js", "export default 1;")
                .file("src/lib/index.ts", "export const b = 1;")
                .file("src/reexp.jsx", "export const c = 1;")
                .file("src/cjs.cjs", "module.exports = {};")
                .file("src/lazy.tsx", "export default 1;")
                .file("src/components/Button.tsx", "export default 1;")
                .file("src/style.css", "a{}")) {
            G g = new G(project.run(new JsAnalyzer()));
            String from = mod("src/main.js");

            assertThat(g.targets(from, EdgeKind.IMPORTS)).containsExactlyInAnyOrder(
                    mod("src/util.js"), mod("src/lib/index.ts"), mod("src/reexp.jsx"), mod("src/cjs.cjs"),
                    mod("src/lazy.tsx"), mod("src/components/Button.tsx"), "unresolved:js-module:src/nope");
            assertThat(g.edge(from, EdgeKind.IMPORTS, mod("src/util.js")).confidence()).isEqualTo(Confidence.RESOLVED);
            assertThat(g.edge(from, EdgeKind.IMPORTS, mod("src/util.js")).location().startLine()).isEqualTo(1);
            Edge unresolved = g.edge(from, EdgeKind.IMPORTS, "unresolved:js-module:src/nope");
            assertThat(unresolved.confidence()).isEqualTo(Confidence.UNRESOLVED);
            assertThat(g.diagnostics(DiagnosticCodes.UNRESOLVED_REFERENCE)).hasSize(1);
        }
    }

    @Test
    @DisplayName("F-010-AC5 frontend 하위의 tsconfig(baseUrl 없음)와 jsconfig의 관례(@/ → src/)를 해석하고, 설정 파일이 없으면 @/는 패키지로 본다")
    void aliasVariants() {
        try (TestProject project = TestProject.create()
                .file("frontend/tsconfig.json", "{ \"compilerOptions\": { \"paths\": { \"@/*\": [\"./src/*\"], \"~api\": [\"./src/api/index.ts\"] } } }")
                .file("frontend/src/a.ts", "import b from '@/b'; import api from '~api'; import x from '@/missing';")
                .file("frontend/src/b.ts", "export default 1;")
                .file("frontend/src/api/index.ts", "export default 1;")
                .file("legacy/jsconfig.json", "{ \"compilerOptions\": {} }")
                .file("legacy/src/c.js", "import d from '@/d'; import e from 'utils/e';")
                .file("legacy/src/d.js", "export default 1;")
                .file("plain/src/e.js", "import f from '@/f';")
                .file("plain/src/f.js", "export default 1;")) {
            G g = new G(project.run(new JsAnalyzer()));

            assertThat(g.targets(mod("frontend/src/a.ts"), EdgeKind.IMPORTS)).containsExactlyInAnyOrder(
                    mod("frontend/src/b.ts"), mod("frontend/src/api/index.ts"), "unresolved:js-module:frontend/src/missing");
            assertThat(g.targets(mod("legacy/src/c.js"), EdgeKind.IMPORTS)).containsExactly(mod("legacy/src/d.js"));
            assertThat(g.targets(mod("plain/src/e.js"), EdgeKind.IMPORTS)).isEmpty();
        }
    }
}
