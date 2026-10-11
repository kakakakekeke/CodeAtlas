package io.codeatlas.web.analysis;

import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.web.analysis.ModuleInfo.FnScope;
import io.codeatlas.web.analysis.ModuleInfo.ImportRecord;
import io.codeatlas.web.analysis.ModuleInfo.PendingAssign;
import io.codeatlas.web.analysis.ModuleInfo.PendingMember;
import io.codeatlas.web.analysis.ModuleInfo.PropPass;
import io.codeatlas.web.analysis.ModuleInfo.Site;
import io.codeatlas.web.analysis.ModuleInfo.SiteKind;
import io.codeatlas.web.analysis.ModuleInfo.Unit;
import io.codeatlas.web.syntax.SyntaxNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1단계: 구문 트리를 한 번 훑어 스코프, 심벌, 함수 노드 후보, import/export, 나중에 해석할 지점(호출, new, JSX, 대입)을 모은다.
 * 여기서는 아무것도 해석하지 않는다. 끌어올림과 다른 모듈의 선언이 다 모인 뒤 2단계에서 해석한다.
 */
final class Collector {

    /** {@code window.x = 값}으로 써도 전역 변수 선언이 아닌 브라우저의 창 속성. */
    private static final Set<String> WINDOW_PROPERTIES = Set.of("location", "document", "top", "parent", "self", "opener",
            "name", "status", "history", "event", "returnValue", "title");

    private final ModuleInfo module;
    private final Unit unit;

    // 훑는 동안의 문맥. 함수에 들어갈 때 저장하고 나올 때 되돌린다.
    private Scope scope;
    /** 가장 가까운, 노드가 있는 바깥 함수. */
    private FunctionInfo owner;
    /** 지금 선언되는 함수 이름 앞에 붙는 이름 경로. 모듈 최상위는 "". */
    private String path = "";
    /** 가장 가까운 이름 없는 함수(없으면 null). 이름이 겹칠 때 구분하는 데 쓴다. */
    private SyntaxNode anonymousFn;
    /** 지금 함수가 return하는 객체 리터럴의 멤버에 줄 이름 경로. 이름 없는 콜백 안에서는 null. */
    private String returnPath;
    private FnScope currentFn;

    Collector(ModuleInfo module, Unit unit) {
        this.module = module;
        this.unit = unit;
        this.scope = module.top;
    }

    void collect() {
        for (SyntaxNode child : unit.tree.root().children()) {
            visit(child);
        }
    }

    // ------------------------------------------------------------------ 방문

    private void visit(SyntaxNode node) {
        if (node == null || (!node.isNamed() && !node.isError())) {
            return;
        }
        switch (node.type()) {
            case "comment", "interface_declaration", "type_alias_declaration", "ambient_declaration",
                    "function_signature", "type_annotation", "type_arguments", "type_parameters", "decorator",
                    "abstract_method_signature", "method_signature", "index_signature", "import_alias",
                    "string", "number", "regex", "identifier", "property_identifier", "this", "super" -> {
                // 타입 선언과 데코레이터는 무시한다. 잎 노드는 볼 것이 없다.
            }
            case "import_statement" -> importStatement(node);
            case "export_statement" -> exportStatement(node);
            case "function_declaration", "generator_function_declaration" -> functionDeclaration(node);
            case "class_declaration", "abstract_class_declaration" -> classDeclaration(node);
            case "lexical_declaration" -> declarators(node, false);
            case "variable_declaration" -> declarators(node, true);
            case "assignment_expression" -> assignment(node);
            case "augmented_assignment_expression" -> augmented(node);
            case "call_expression" -> call(node);
            case "new_expression" -> newExpression(node);
            case "jsx_element", "jsx_self_closing_element" -> jsx(node);
            case "return_statement" -> returnStatement(node);
            case "function_expression", "arrow_function", "generator_function", "function" -> functionValue(node, null, null);
            case "class" -> classValue(node, null);
            case "object" -> objectLiteral(node, null);
            case "statement_block", "for_statement", "switch_body" -> block(node);
            case "for_in_statement" -> forIn(node);
            case "catch_clause" -> catchClause(node);
            case "enum_declaration" -> enumDeclaration(node);
            case "internal_module", "module" -> namespace(node);
            default -> visitChildren(node);
        }
    }

    private void visitChildren(SyntaxNode node) {
        for (SyntaxNode child : node.children()) {
            visit(child);
        }
    }

    private void block(SyntaxNode node) {
        Scope saved = scope;
        scope = new Scope(saved, module, false);
        visitChildren(node);
        scope = saved;
    }

    private void forIn(SyntaxNode node) {
        Scope saved = scope;
        scope = new Scope(saved, module, false);
        SyntaxNode left = node.field("left");
        if (left != null && node.field("kind") != null) {
            bindUnknown(left, scope);
        }
        visit(node.field("right"));
        visit(node.field("body"));
        scope = saved;
    }

    private void catchClause(SyntaxNode node) {
        Scope saved = scope;
        scope = new Scope(saved, module, false);
        SyntaxNode param = node.field("parameter");
        if (param != null) {
            bindUnknown(param, scope);
        }
        visit(node.field("body"));
        scope = saved;
    }

    // ------------------------------------------------------------------ import / export

    private void importStatement(SyntaxNode node) {
        module.usesModuleSyntax = true;
        SyntaxNode source = node.field("source");
        SyntaxNode requireClause = node.child("import_require_clause");
        if (requireClause != null) {
            // import x = require('./m')
            SyntaxNode src = requireClause.field("source");
            SyntaxNode name = requireClause.child("identifier");
            if (src != null && name != null) {
                String spec = Syntax.stringContent(src);
                module.imports.add(new ImportRecord(spec, node, unit));
                importSym(name.text(), spec, Sym.CJS);
            }
            return;
        }
        if (source == null) {
            return;
        }
        String spec = Syntax.stringContent(source);
        module.imports.add(new ImportRecord(spec, node, unit));
        SyntaxNode clause = node.child("import_clause");
        if (clause == null) {
            return;
        }
        for (SyntaxNode c : clause.namedChildren()) {
            switch (c.type()) {
                case "identifier" -> importSym(c.text(), spec, Sym.DEFAULT);
                case "namespace_import" -> {
                    SyntaxNode id = c.child("identifier");
                    if (id != null) {
                        importSym(id.text(), spec, Sym.NAMESPACE);
                    }
                }
                case "named_imports" -> {
                    for (SyntaxNode specifier : c.namedChildren()) {
                        if (!specifier.is("import_specifier")) {
                            continue;
                        }
                        SyntaxNode name = specifier.field("name");
                        SyntaxNode alias = specifier.field("alias");
                        if (name != null) {
                            String imported = name.is("string") ? Syntax.stringContent(name) : name.text();
                            importSym(alias != null ? alias.text() : imported, spec, imported);
                        }
                    }
                }
                default -> {
                }
            }
        }
    }

    private void importSym(String localName, String spec, String importedName) {
        Sym s = new Sym(localName, module);
        s.importRef = new Sym.ImportRef(spec, importedName);
        module.top.syms.put(localName, s);
    }

    private void exportStatement(SyntaxNode node) {
        module.usesModuleSyntax = true;
        SyntaxNode source = node.field("source");
        SyntaxNode clause = node.child("export_clause");
        if (source != null) {
            String spec = Syntax.stringContent(source);
            module.imports.add(new ImportRecord(spec, node, unit));
            if (clause != null) {
                for (SyntaxNode specifier : clause.namedChildren()) {
                    if (!specifier.is("export_specifier")) {
                        continue;
                    }
                    SyntaxNode name = specifier.field("name");
                    SyntaxNode alias = specifier.field("alias");
                    if (name != null) {
                        Sym s = new Sym(name.text(), module);
                        s.importRef = new Sym.ImportRef(spec, name.text());
                        module.exports.put(alias != null ? alias.text() : name.text(), s);
                    }
                }
            } else {
                SyntaxNode ns = node.child("namespace_export");
                if (ns != null) {
                    SyntaxNode id = Syntax.firstNamed(ns);
                    if (id != null) {
                        Sym s = new Sym(id.text(), module);
                        s.importRef = new Sym.ImportRef(spec, Sym.NAMESPACE);
                        module.exports.put(id.text(), s);
                    }
                } else {
                    module.starReexports.add(spec);
                }
            }
            return;
        }
        boolean isDefault = node.hasChild("default");
        SyntaxNode declaration = node.field("declaration");
        if (declaration != null) {
            exportDeclaration(declaration, isDefault);
            return;
        }
        SyntaxNode value = node.field("value");
        if (value != null) {
            // export default <식>
            Sym d = new Sym(Sym.DEFAULT, module);
            d.init = value;
            d.initScope = scope;
            d.namePath = Sym.DEFAULT;
            module.exports.put(Sym.DEFAULT, d);
            SyntaxNode inner = Syntax.unwrap(value);
            if (Syntax.isIdentifier(inner) || inner.is("member_expression")) {
                visit(value);
            } else {
                String own = Syntax.isFunction(inner) || Syntax.isClass(inner) ? nameOf(inner) : null;
                namedValue(value, own != null ? own : Sym.DEFAULT, null);
            }
            return;
        }
        if (clause != null) {
            for (SyntaxNode specifier : clause.namedChildren()) {
                if (!specifier.is("export_specifier")) {
                    continue;
                }
                SyntaxNode name = specifier.field("name");
                SyntaxNode alias = specifier.field("alias");
                if (name != null) {
                    Sym s = new Sym(name.text(), module);
                    s.init = name;
                    s.initScope = module.top;
                    module.exports.put(alias != null ? alias.text() : name.text(), s);
                }
            }
            return;
        }
        if (node.hasChild("=")) {
            // TypeScript: export = x
            SyntaxNode expr = Syntax.firstNamed(node);
            if (expr != null) {
                module.usesCommonJs = true;
                Sym s = new Sym("module.exports", module);
                s.init = expr;
                s.initScope = scope;
                module.cjsExports = s;
                visit(expr);
            }
            return;
        }
        visitChildren(node);
    }

    private void exportDeclaration(SyntaxNode declaration, boolean isDefault) {
        switch (declaration.type()) {
            case "function_declaration", "generator_function_declaration", "function_expression", "function" -> {
                String name = nameOf(declaration);
                if (name == null) {
                    Sym d = new Sym(Sym.DEFAULT, module);
                    d.init = declaration;
                    d.initScope = scope;
                    module.exports.put(Sym.DEFAULT, d);
                    functionValue(declaration, Sym.DEFAULT, null);
                } else {
                    Sym s = functionDeclaration(declaration);
                    module.exports.put(name, s);
                    if (isDefault) {
                        module.exports.put(Sym.DEFAULT, s);
                        module.exports.remove(name);
                    }
                }
            }
            case "class_declaration", "abstract_class_declaration", "class" -> {
                String name = nameOf(declaration);
                if (name == null) {
                    Sym d = new Sym(Sym.DEFAULT, module);
                    d.init = declaration;
                    d.initScope = scope;
                    module.exports.put(Sym.DEFAULT, d);
                    classValue(declaration, Sym.DEFAULT);
                } else {
                    Sym s = classDeclaration(declaration);
                    module.exports.put(isDefault ? Sym.DEFAULT : name, s);
                }
            }
            case "lexical_declaration", "variable_declaration" -> {
                visit(declaration);
                for (SyntaxNode d : declaration.namedChildren()) {
                    if (d.is("variable_declarator")) {
                        for (String name : bindingNames(d.field("name"))) {
                            Sym s = scope.lookup(name);
                            if (s != null) {
                                module.exports.put(name, s);
                            }
                        }
                    }
                }
            }
            case "enum_declaration", "internal_module", "module" -> {
                visit(declaration);
                String name = nameOf(declaration);
                Sym s = name == null ? null : scope.lookup(name);
                if (s != null) {
                    module.exports.put(name, s);
                }
            }
            default -> visit(declaration);
        }
    }

    // ------------------------------------------------------------------ 선언

    private static String nameOf(SyntaxNode node) {
        SyntaxNode name = node.field("name");
        return name == null ? null : name.text();
    }

    private String full(String name) {
        return path.isEmpty() ? name : path + "." + name;
    }

    private Sym functionDeclaration(SyntaxNode node) {
        String name = nameOf(node);
        if (name == null) {
            functionValue(node, null, null);
            return null;
        }
        Scope target = scope.varScope();
        Sym s = target.syms.get(name);
        if (s == null || s.importRef != null) {
            s = new Sym(name, module);
            target.syms.put(name, s);
        }
        FunctionInfo fn = register(node, full(name), "function");
        s.namePath = fn.namePath;
        s.preset = new Val.Func(fn, node, module);
        s.init = null;
        // 생성자 함수로 쓰일 때의 this: 이 함수의 인스턴스
        Sym instance = new Sym(name, module);
        instance.preset = new Val.Instance(s);
        instance.namePath = fn.namePath;
        enterFunction(node, fn, fn.namePath, instance, true);
        return s;
    }

    private void declarators(SyntaxNode declaration, boolean isVar) {
        for (SyntaxNode d : declaration.namedChildren()) {
            if (!d.is("variable_declarator")) {
                continue;
            }
            SyntaxNode nameNode = d.field("name");
            SyntaxNode value = d.field("value");
            if (nameNode == null) {
                continue;
            }
            Scope target = isVar ? scope.varScope() : scope;
            if (nameNode.is("identifier")) {
                String name = nameNode.text();
                Sym s = target.syms.get(name);
                boolean fresh = s == null || s.importRef != null;
                if (fresh) {
                    s = new Sym(name, module);
                    target.syms.put(name, s);
                }
                s.namePath = full(name);
                if (value != null) {
                    if (s.init == null && s.preset == null && !s.param && !s.multiAssigned()) {
                        s.init = value;
                        s.initScope = scope;
                    } else {
                        s.addAssign(value, scope);
                    }
                    namedValue(value, full(name), null);
                }
            } else {
                if (value != null) {
                    destructure(nameNode, value, scope, new ArrayList<>(), target);
                    stateDeclaration(nameNode, value, target);
                    visit(value);
                } else {
                    bindUnknown(nameNode, target);
                }
            }
        }
    }

    /**
     * {@code const [v, setV] = useState({ … })}: 상태 v의 값 후보로 초기 객체를 적어 두고, setV에 그 상태를 이어 둔다.
     * 초기값이 객체 리터럴일 때만 본다({@code useState(fn)}의 fn은 값이 아니라 초기화 함수다).
     */
    private void stateDeclaration(SyntaxNode pattern, SyntaxNode value, Scope target) {
        SyntaxNode call = Syntax.unwrap(value);
        if (!pattern.is("array_pattern") || call == null || !call.is("call_expression")) {
            return;
        }
        SyntaxNode callee = Syntax.callee(call);
        List<SyntaxNode> args = Syntax.arguments(call);
        List<SyntaxNode> names = Syntax.named(pattern);
        if (callee == null || args.isEmpty() || names.isEmpty() || !names.get(0).is("identifier")
                || !(callee.text().equals("useState") || callee.text().equals("React.useState"))) {
            return;
        }
        SyntaxNode initial = Syntax.unwrap(args.get(0));
        if (initial == null || !initial.is("object")) {
            return;
        }
        Sym state = new Sym(names.get(0).text(), module);
        state.state = true;
        state.init = initial;
        state.initScope = scope;
        target.syms.put(state.name, state);
        if (names.size() >= 2 && names.get(1).is("identifier")) {
            Sym setter = target.syms.get(names.get(1).text());
            if (setter != null) {
                setter.stateOf = state;
            }
        }
    }

    /** {@code setV({ … })}, {@code setV(prev => ({ … }))}: 넘긴 객체를 상태 v의 값 후보로 더한다. */
    private void stateUpdate(SyntaxNode callee, SyntaxNode call) {
        Sym setter = scope.lookup(callee.text());
        if (setter == null || setter.stateOf == null) {
            return;
        }
        List<SyntaxNode> args = Syntax.arguments(call);
        SyntaxNode arg = args.size() == 1 ? Syntax.unwrap(args.get(0)) : null;
        if (arg != null && (arg.is("object") || arg.is("arrow_function") || arg.is("function_expression"))) {
            setter.stateOf.addAssign(arg, scope);
        }
    }

    /** 구조 분해 패턴의 이름들을 값의 멤버 경로와 함께 선언한다. */
    private void destructure(SyntaxNode pattern, SyntaxNode value, Scope valueScope, List<String> prefix, Scope target) {
        if (!pattern.is("object_pattern")) {
            bindUnknown(pattern, target);
            return;
        }
        for (SyntaxNode c : pattern.namedChildren()) {
            switch (c.type()) {
                case "shorthand_property_identifier_pattern" -> destructured(c.text(), c.text(), value, valueScope, prefix, target);
                case "object_assignment_pattern" -> {
                    SyntaxNode left = c.field("left");
                    if (left != null && left.is("shorthand_property_identifier_pattern")) {
                        destructured(left.text(), left.text(), value, valueScope, prefix, target);
                    } else if (left != null) {
                        bindUnknown(left, target);
                    }
                    visit(c.field("right"));
                }
                case "pair_pattern" -> {
                    String key = Syntax.propertyName(c.field("key"));
                    SyntaxNode v = c.field("value");
                    if (v == null) {
                        continue;
                    }
                    if (v.is("assignment_pattern")) {
                        visit(v.field("right"));
                        v = v.field("left");
                    }
                    if (key == null || v == null) {
                        if (v != null) {
                            bindUnknown(v, target);
                        }
                    } else if (v.is("identifier")) {
                        destructured(v.text(), key, value, valueScope, prefix, target);
                    } else if (v.is("object_pattern")) {
                        List<String> next = new ArrayList<>(prefix);
                        next.add(key);
                        destructure(v, value, valueScope, next, target);
                    } else {
                        bindUnknown(v, target);
                    }
                }
                default -> bindUnknown(c, target);
            }
        }
    }

    private void destructured(String localName, String key, SyntaxNode value, Scope valueScope, List<String> prefix, Scope target) {
        Sym s = new Sym(localName, module);
        s.init = value;
        s.initScope = valueScope;
        List<String> p = new ArrayList<>(prefix);
        p.add(key);
        s.destruct = p;
        target.syms.put(localName, s);
    }

    /** 패턴 안의 이름을 전부 값 모르는 바인딩으로 선언한다. */
    private void bindUnknown(SyntaxNode pattern, Scope target) {
        for (String name : bindingNames(pattern)) {
            Sym s = new Sym(name, module);
            s.param = true;
            target.syms.put(name, s);
        }
    }

    private static List<String> bindingNames(SyntaxNode pattern) {
        List<String> out = new ArrayList<>();
        collectBindingNames(pattern, out);
        return out;
    }

    private static void collectBindingNames(SyntaxNode n, List<String> out) {
        if (n == null) {
            return;
        }
        switch (n.type()) {
            case "identifier", "shorthand_property_identifier_pattern" -> out.add(n.text());
            case "pair_pattern" -> collectBindingNames(n.field("value"), out);
            case "assignment_pattern", "object_assignment_pattern" -> collectBindingNames(n.field("left"), out);
            case "required_parameter", "optional_parameter" -> collectBindingNames(n.field("pattern"), out);
            case "object_pattern", "array_pattern", "rest_pattern" -> {
                for (SyntaxNode c : n.namedChildren()) {
                    collectBindingNames(c, out);
                }
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ 값에 이름 주기

    /**
     * 이름을 줄 수 있는 자리(변수 초기화, 대입의 오른쪽, 객체 프로퍼티 값, export default)의 값.
     *
     * @param fullName 이 값에 줄 이름 경로(바깥 경로 포함). ""이면 객체 멤버를 접두사 없이 이름 짓는다
     * @param thisSym 값이 함수일 때의 this(객체 메서드). 없으면 null
     */
    private void namedValue(SyntaxNode value, String fullName, Sym thisSym) {
        SyntaxNode inner = Syntax.unwrap(value);
        if (inner == null) {
            return;
        }
        if (Syntax.isFunction(inner)) {
            functionValue(inner, fullName.isEmpty() ? Sym.DEFAULT : fullName, thisSym);
        } else if (Syntax.isClass(inner)) {
            classValue(inner, fullName.isEmpty() ? Sym.DEFAULT : fullName);
        } else if (inner.is("object")) {
            objectLiteral(inner, fullName);
        } else if (inner.is("call_expression")) {
            SyntaxNode callee = Syntax.callee(inner);
            List<SyntaxNode> args = Syntax.arguments(inner);
            if (Syntax.isFunction(callee)) {
                // IIFE 모듈: var M = (function(){ ... return {...}; })()
                enterTransparent(callee, fullName, fullName);
                arguments(inner);
            } else if (callee != null && Syntax.FUNCTION_WRAPPERS.contains(callee.text()) && !args.isEmpty()
                    && Syntax.isFunction(Syntax.unwrap(args.get(0))) && !fullName.isEmpty()) {
                site(SiteKind.CALL, inner);
                functionValue(Syntax.unwrap(args.get(0)), fullName, thisSym);
                for (int i = 1; i < args.size(); i++) {
                    visit(args.get(i));
                }
            } else {
                visit(inner);
            }
        } else {
            visit(inner);
        }
    }

    /** 함수 값. fullName이 있으면 노드를 만들고, 없으면 자기 이름(이름 있는 함수 식)을 쓰거나 이름 없는 콜백으로 훑는다. */
    private void functionValue(SyntaxNode node, String fullName, Sym thisSym) {
        String name = fullName;
        if (name == null) {
            String own = node.is("arrow_function") ? null : nameOf(node);
            name = own == null ? null : full(own);
        }
        boolean arrow = node.is("arrow_function");
        if (name == null) {
            enterAnonymous(node, arrow);
            return;
        }
        FunctionInfo fn = register(node, name, thisSym != null ? "method" : arrow ? "arrow" : "function");
        Sym self = thisSym;
        boolean ownThis = !arrow;
        if (self == null && !arrow) {
            // 생성자 함수 패턴: var Foo = function(){ this.x = function(){} }
            Sym target = scope.lookup(lastSegment(name));
            if (target != null && target.init != null && Syntax.unwrap(target.init) == node) {
                self = new Sym(target.name, module);
                self.preset = new Val.Instance(target);
                self.namePath = name;
            }
        }
        enterFunction(node, fn, fn.namePath, self, ownThis);
    }

    private static String lastSegment(String namePath) {
        int dot = namePath.lastIndexOf('.');
        return dot < 0 ? namePath : namePath.substring(dot + 1);
    }

    // ------------------------------------------------------------------ 함수 진입

    private FunctionInfo register(SyntaxNode node, String rawNamePath, String kind) {
        // 인라인 스크립트의 함수 이름에 JSP 식이 섞였으면(function ${prefix}Open) 그 자리를 {*}로 쓴다. 내부 자리표시자를 밖에 내지 않는다
        String namePath = module.embedded ? rawNamePath.replace(Evaluator.JSP_EXPR, UrlValue.DYNAMIC_TOKEN) : rawNamePath;
        String id = Ids.jsFunction(module.path, namePath);
        FunctionInfo existing = module.functions.get(id);
        if (existing != null && existing.node != node && anonymousFn != null) {
            // 이름 없는 함수 안의 선언이 다른 선언과 겹쳤다. 계약의 <anonymous>@줄:열로 구분한다.
            SourceLocation at = unit.loc(anonymousFn);
            String base = path.isEmpty() ? "" : path + ".";
            String local = namePath.startsWith(base) ? namePath.substring(base.length()) : lastSegment(namePath);
            namePath = base + Ids.jsAnonymous(at.startLine(), at.startColumn()) + "." + local;
            id = Ids.jsFunction(module.path, namePath);
            existing = module.functions.get(id);
        }
        if (existing != null) {
            // 같은 이름의 재선언은 한 노드로 합친다.
            module.functionByNode.put(node, existing);
            return existing;
        }
        FunctionInfo fn = new FunctionInfo(id, namePath, kind, module, node, unit.loc(node), owner);
        fn.async = node.hasChild("async");
        fn.dynamicName = module.embedded && namePath.contains(UrlValue.DYNAMIC_TOKEN);
        module.functions.put(id, fn);
        module.functionByNode.put(node, fn);
        return fn;
    }

    /** 노드가 있는 함수 안으로. */
    private void enterFunction(SyntaxNode node, FunctionInfo fn, String namePath, Sym thisSym, boolean ownThis) {
        Scope savedScope = scope;
        FunctionInfo savedOwner = owner;
        String savedPath = path;
        SyntaxNode savedAnon = anonymousFn;
        String savedReturn = returnPath;
        FnScope savedFn = currentFn;

        scope = new Scope(savedScope, module, true);
        scope.ownThis = ownThis;
        scope.thisSym = thisSym;
        owner = fn;
        path = namePath;
        anonymousFn = null;
        returnPath = namePath;
        currentFn = new FnScope(scope);
        module.fnScopes.put(node, currentFn);
        parameters(node, fn);
        body(node);

        scope = savedScope;
        owner = savedOwner;
        path = savedPath;
        anonymousFn = savedAnon;
        returnPath = savedReturn;
        currentFn = savedFn;
    }

    /** 이름 없는 콜백 안으로. 노드를 만들지 않고 안의 것은 바깥 함수에 귀속한다. */
    private void enterAnonymous(SyntaxNode node, boolean arrow) {
        Scope savedScope = scope;
        SyntaxNode savedAnon = anonymousFn;
        String savedReturn = returnPath;
        FnScope savedFn = currentFn;

        scope = new Scope(savedScope, module, true);
        scope.ownThis = !arrow;
        scope.thisSym = null;
        anonymousFn = node;
        returnPath = null;
        currentFn = new FnScope(scope);
        module.fnScopes.put(node, currentFn);
        parameters(node, null);
        body(node);

        scope = savedScope;
        anonymousFn = savedAnon;
        returnPath = savedReturn;
        currentFn = savedFn;
    }

    /** IIFE 안으로. 노드는 만들지 않지만 안의 선언에 이름 경로(대입 대상)를 준다. */
    private void enterTransparent(SyntaxNode node, String namePath, String returnNamePath) {
        Scope savedScope = scope;
        String savedPath = path;
        SyntaxNode savedAnon = anonymousFn;
        String savedReturn = returnPath;
        FnScope savedFn = currentFn;

        scope = new Scope(savedScope, module, true);
        scope.ownThis = !node.is("arrow_function");
        scope.thisSym = null;
        if (namePath != null) {
            path = namePath;
            anonymousFn = null;
        } else {
            anonymousFn = node;
        }
        returnPath = returnNamePath;
        currentFn = new FnScope(scope);
        module.fnScopes.put(node, currentFn);
        parameters(node, null);
        body(node);

        scope = savedScope;
        path = savedPath;
        anonymousFn = savedAnon;
        returnPath = savedReturn;
        currentFn = savedFn;
    }

    private void parameters(SyntaxNode fnNode, FunctionInfo fn) {
        SyntaxNode single = fnNode.field("parameter");
        if (single != null) {
            param(single, fn, 0);
            return;
        }
        SyntaxNode params = fnNode.field("parameters");
        if (params == null) {
            return;
        }
        int index = 0;
        for (SyntaxNode p : Syntax.named(params)) {
            if (p.is("decorator")) {
                continue;
            }
            param(p, fn, index++);
        }
    }

    private void param(SyntaxNode p, FunctionInfo fn, int index) {
        SyntaxNode pattern = p;
        if (p.isAny("required_parameter", "optional_parameter")) {
            pattern = p.field("pattern");
            visit(p.field("value"));
        } else if (p.is("assignment_pattern")) {
            pattern = p.field("left");
            visit(p.field("right"));
        }
        if (pattern == null) {
            return;
        }
        if (pattern.is("identifier")) {
            Sym s = new Sym(pattern.text(), module);
            s.param = true;
            s.paramOwner = fn;
            s.paramIndex = fn == null ? -1 : index;
            scope.syms.put(s.name, s);
        } else {
            bindUnknown(pattern, scope);
            if (fn != null) {
                destructuredParam(pattern, fn, index);
            }
        }
    }

    /** 이름 있는 함수의 매개변수를 구조 분해해 꺼낸 이름에 그 함수를, 첫 매개변수(props)에서 꺼낸 이름에는 프로퍼티 이름도 적어 둔다. */
    private void destructuredParam(SyntaxNode pattern, FunctionInfo fn, int index) {
        for (String name : bindingNames(pattern)) {
            Sym s = scope.syms.get(name);
            if (s != null) {
                s.paramOwner = fn;
            }
        }
        if (index != 0 || !pattern.is("object_pattern")) {
            return;
        }
        for (SyntaxNode c : pattern.namedChildren()) {
            String key = null;
            SyntaxNode local = null;
            switch (c.type()) {
                case "shorthand_property_identifier_pattern" -> {
                    key = c.text();
                    local = c;
                }
                case "object_assignment_pattern" -> {
                    SyntaxNode left = c.field("left");
                    if (left != null && left.is("shorthand_property_identifier_pattern")) {
                        key = left.text();
                        local = left;
                    }
                }
                case "pair_pattern" -> {
                    key = Syntax.propertyName(c.field("key"));
                    local = c.field("value");
                    if (local != null && local.is("assignment_pattern")) {
                        local = local.field("left");
                    }
                }
                default -> {
                }
            }
            if (key == null || local == null || !local.isAny("identifier", "shorthand_property_identifier_pattern")) {
                continue;
            }
            Sym s = scope.syms.get(local.text());
            if (s != null) {
                s.propName = key;
            }
        }
    }

    private void body(SyntaxNode fnNode) {
        SyntaxNode body = fnNode.field("body");
        if (body == null) {
            return;
        }
        if (body.is("statement_block")) {
            // 함수 본문은 함수 스코프와 같은 스코프다.
            visitChildren(body);
        } else {
            // 화살표 함수의 식 본문은 그 자체가 반환값이다.
            returned(body);
        }
    }

    private void returnStatement(SyntaxNode node) {
        SyntaxNode arg = Syntax.firstNamed(node);
        if (arg != null) {
            returned(arg);
        }
    }

    private void returned(SyntaxNode arg) {
        if (currentFn != null) {
            currentFn.returns.add(new Sym.Assign(arg, scope));
        }
        SyntaxNode inner = Syntax.unwrap(arg);
        if (inner != null && inner.is("object") && returnPath != null) {
            objectLiteral(inner, returnPath);
        } else {
            visit(arg);
        }
    }

    // ------------------------------------------------------------------ 클래스

    private Sym classDeclaration(SyntaxNode node) {
        String name = nameOf(node);
        if (name == null) {
            classValue(node, null);
            return null;
        }
        Sym s = new Sym(name, module);
        scope.syms.put(name, s);
        classBody(node, full(name), s);
        return s;
    }

    private void classValue(SyntaxNode node, String fullName) {
        String name = fullName;
        if (name == null) {
            String own = nameOf(node);
            name = own == null ? null : full(own);
        }
        if (name == null) {
            // 이름 없는 클래스 식: 메서드를 이름 없는 콜백처럼 훑는다.
            SyntaxNode body = node.field("body");
            if (body != null) {
                for (SyntaxNode member : body.namedChildren()) {
                    if (member.is("method_definition")) {
                        enterAnonymous(member, false);
                    } else if (!member.is("decorator")) {
                        visit(member);
                    }
                }
            }
            return;
        }
        classBody(node, name, new Sym(lastSegment(name), module));
    }

    private Val.ClassInfo classBody(SyntaxNode node, String namePath, Sym classSym) {
        FunctionInfo cls = register(node, namePath, "class");
        SyntaxNode superExpr = null;
        SyntaxNode heritage = node.child("class_heritage");
        if (heritage != null) {
            SyntaxNode ext = heritage.child("extends_clause");
            superExpr = ext != null ? ext.field("value") : Syntax.firstNamed(heritage);
            if (superExpr != null && superExpr.isAny("extends_clause", "implements_clause")) {
                superExpr = null;
            }
        }
        cls.superClass = superExpr == null ? null : superExpr.text();
        Map<String, Sym> members = new LinkedHashMap<>();
        Val.ClassInfo info = new Val.ClassInfo(cls, members, superExpr, scope);
        module.literalVals.put(node, new Val.Cls(info));

        classSym.preset = new Val.Cls(info);
        classSym.namePath = cls.namePath;
        classSym.init = null;
        info.sym = classSym;
        Sym instance = new Sym(cls.shortName(), module);
        instance.preset = new Val.Instance(classSym);
        instance.namePath = cls.namePath;

        SyntaxNode body = node.field("body");
        if (body == null) {
            return info;
        }
        Scope savedScope = scope;
        FunctionInfo savedOwner = owner;
        String savedPath = path;
        SyntaxNode savedAnon = anonymousFn;
        String savedReturn = returnPath;
        scope = new Scope(savedScope, module, false);
        scope.ownThis = true;
        scope.thisSym = instance;
        owner = cls;
        path = cls.namePath;
        anonymousFn = null;
        returnPath = null;

        for (SyntaxNode member : body.namedChildren()) {
            switch (member.type()) {
                case "method_definition" -> {
                    String name = Syntax.propertyName(member.field("name"));
                    if (name == null) {
                        enterAnonymous(member, false);
                        continue;
                    }
                    FunctionInfo m = register(member, cls.namePath + "." + name, "method");
                    if (name.equals("render")) {
                        cls.hasRender = true;
                    }
                    Sym ms = new Sym(name, module);
                    ms.preset = new Val.Func(m, member, module);
                    ms.namePath = m.namePath;
                    members.putIfAbsent(name, ms);
                    enterFunction(member, m, m.namePath, member.hasChild("static") ? classSym : instance, true);
                }
                case "field_definition", "public_field_definition" -> {
                    SyntaxNode nameNode = member.field("property") != null ? member.field("property") : member.field("name");
                    String name = Syntax.propertyName(nameNode);
                    SyntaxNode value = member.field("value");
                    if (value == null) {
                        continue;
                    }
                    if (name == null) {
                        visit(value);
                        continue;
                    }
                    Sym fs = new Sym(name, module);
                    fs.init = value;
                    fs.initScope = scope;
                    fs.namePath = cls.namePath + "." + name;
                    members.putIfAbsent(name, fs);
                    SyntaxNode inner = Syntax.unwrap(value);
                    if (Syntax.isFunction(inner)) {
                        if (name.equals("render")) {
                            cls.hasRender = true;
                        }
                        functionValue(inner, fs.namePath, instance);
                    } else {
                        namedValue(value, fs.namePath, null);
                    }
                }
                case "decorator", "abstract_method_signature", "method_signature", "index_signature", "comment" -> {
                }
                default -> visit(member);
            }
        }
        scope = savedScope;
        owner = savedOwner;
        path = savedPath;
        anonymousFn = savedAnon;
        returnPath = savedReturn;
        return info;
    }

    // ------------------------------------------------------------------ 객체 리터럴

    /**
     * 객체 리터럴. namePath가 있으면 함수인 멤버에 {@code namePath.key} 이름을 주고, 없으면 멤버 함수를 이름 없는 콜백으로 훑는다.
     */
    private void objectLiteral(SyntaxNode node, String namePath) {
        Map<String, Sym> props = new LinkedHashMap<>();
        Val.Obj obj = new Val.Obj(props);
        module.literalVals.put(node, obj);
        Sym self = new Sym(namePath == null ? "<object>" : lastSegment(namePath), module);
        self.preset = obj;
        self.namePath = namePath;
        for (SyntaxNode member : node.namedChildren()) {
            switch (member.type()) {
                case "pair" -> {
                    String key = Syntax.propertyName(member.field("key"));
                    SyntaxNode value = member.field("value");
                    if (value == null) {
                        continue;
                    }
                    if (key == null) {
                        visit(member.field("key"));
                        visit(value);
                        continue;
                    }
                    Sym p = new Sym(key, module);
                    p.init = value;
                    p.initScope = scope;
                    props.putIfAbsent(key, p);
                    if (namePath != null) {
                        p.namePath = namePath.isEmpty() ? key : namePath + "." + key;
                        namedValue(value, p.namePath, self);
                    } else {
                        visit(value);
                    }
                }
                case "method_definition" -> {
                    String key = Syntax.propertyName(member.field("name"));
                    if (key == null || namePath == null) {
                        if (key != null) {
                            Sym p = new Sym(key, module);
                            p.init = member;
                            p.initScope = scope;
                            props.putIfAbsent(key, p);
                        }
                        enterAnonymous(member, false);
                        continue;
                    }
                    String full = namePath.isEmpty() ? key : namePath + "." + key;
                    FunctionInfo fn = register(member, full, "method");
                    Sym p = new Sym(key, module);
                    p.preset = new Val.Func(fn, member, module);
                    p.namePath = full;
                    props.putIfAbsent(key, p);
                    enterFunction(member, fn, fn.namePath, self, true);
                }
                case "shorthand_property_identifier" -> {
                    Sym p = new Sym(member.text(), module);
                    p.init = member;
                    p.initScope = scope;
                    props.putIfAbsent(member.text(), p);
                }
                default -> visit(member);
            }
        }
    }

    // ------------------------------------------------------------------ 대입

    private static boolean isEventHandlerName(String name) {
        return name.matches("on[a-z]+");
    }

    private void assignment(SyntaxNode node) {
        site(SiteKind.ASSIGN, node);
        SyntaxNode left = Syntax.unwrap(node.field("left"));
        SyntaxNode right = node.field("right");
        if (left == null || right == null) {
            visitChildren(node);
            return;
        }
        if (left.is("identifier")) {
            String name = left.text();
            String namePath = full(name);
            Sym now = scope.lookup(name);
            if (now != null && now.namePath != null) {
                namePath = now.namePath;
            }
            module.pendingAssigns.add(new PendingAssign(name, scope, right, false, namePath));
            namedValue(right, namePath, null);
            return;
        }
        List<String> chain = left.is("member_expression") ? Syntax.chain(left) : null;
        if (chain == null || chain.size() < 2) {
            visit(left);
            visit(right);
            return;
        }
        visitObjectPart(left);
        String root = chain.get(0);
        List<String> rest = new ArrayList<>(chain.subList(1, chain.size()));
        String last = rest.get(rest.size() - 1);
        boolean unboundRoot = scope.lookup(root) == null;

        // CommonJS: module.exports = X, module.exports.a = X, exports.a = X
        if (unboundRoot && root.equals("module") && rest.get(0).equals("exports") && rest.size() <= 2) {
            module.usesModuleSyntax = true;
            module.usesCommonJs = true;
            if (rest.size() == 1) {
                Sym s = new Sym("module.exports", module);
                s.init = right;
                s.initScope = scope;
                s.namePath = "";
                module.cjsExports = s;
                SyntaxNode inner = Syntax.unwrap(right);
                String own = Syntax.isFunction(inner) || Syntax.isClass(inner) ? nameOf(inner) : null;
                namedValue(right, own != null ? own : "", null);
            } else {
                exportMember(rest.get(1), right);
            }
            return;
        }
        if (unboundRoot && root.equals("exports") && rest.size() == 1) {
            module.usesModuleSyntax = true;
            module.usesCommonJs = true;
            exportMember(last, right);
            return;
        }
        // 이벤트 핸들러 등록(el.onclick = function(){})은 이름 없는 콜백으로 본다.
        if (isEventHandlerName(last) && Syntax.isFunction(Syntax.unwrap(right))) {
            visit(right);
            return;
        }
        // 전역: window.foo = X
        if (unboundRoot && rest.size() == 1 && (root.equals("window") || root.equals("globalThis") || root.equals("global"))
                && !WINDOW_PROPERTIES.contains(last)) {
            Sym s = module.top.syms.get(last);
            if (s == null) {
                s = new Sym(last, module);
                s.init = right;
                s.initScope = scope;
                s.namePath = last;
                module.top.syms.put(last, s);
            } else {
                s.addAssign(right, scope);
            }
            withAbsolutePath(() -> namedValue(right, last, null));
            return;
        }
        SyntaxNode rightInner = Syntax.unwrap(right);
        boolean nameable = Syntax.isFunction(rightInner) || Syntax.isClass(rightInner) || (rightInner != null && rightInner.is("object"));
        // this.x = X
        if (root.equals("this")) {
            Sym self = scope.lookupThis();
            Sym target = self == null ? null : self.preset instanceof Val.Instance i ? i.classSym() : self;
            if (target == null) {
                visit(right);
                return;
            }
            List<String> names = withoutPrototype(rest);
            Sym leaf = leaf(last, right);
            String base = self.namePath == null ? "" : self.namePath;
            String namePath = join(base, names);
            leaf.namePath = namePath;
            attach(target, names, leaf);
            if (nameable) {
                withAbsolutePath(() -> namedValue(right, namePath, Syntax.isFunction(rightInner) ? self : null));
            } else {
                visit(right);
            }
            return;
        }
        // 일반: A.b = X, A.prototype.b = X, A.prototype = {...}
        List<String> names = withoutPrototype(rest);
        Sym rootNow = scope.lookup(root);
        String base = rootNow != null && rootNow.namePath != null ? rootNow.namePath : root;
        String namePath = join(base, names);
        Sym leaf = names.isEmpty() ? null : leaf(last, right);
        if (leaf != null) {
            leaf.namePath = namePath;
            module.pendingMembers.add(new PendingMember(prepend(root, names), scope, leaf, nameable));
        } else if (rightInner != null && rightInner.is("object")) {
            // A.prototype = { a: function(){} }: 객체의 멤버를 A의 멤버로 붙인다.
            module.pendingMembers.add(new PendingMember(List.of(root), scope, leaf(root, right), true));
        }
        if (nameable) {
            boolean viaPrototype = rest.contains("prototype");
            Sym self = null;
            if (viaPrototype && rootNow != null && Syntax.isFunction(rightInner)) {
                self = new Sym(root, module);
                self.preset = new Val.Instance(rootNow);
                self.namePath = base;
            }
            Sym thisForFn = self;
            withAbsolutePath(() -> namedValue(right, namePath, thisForFn));
        } else {
            visit(right);
        }
    }

    private Sym leaf(String name, SyntaxNode value) {
        Sym leaf = new Sym(name, module);
        leaf.init = value;
        leaf.initScope = scope;
        return leaf;
    }

    /** 이미 알려진 심벌(this의 주인) 아래에 바로 붙인다. */
    private static void attach(Sym target, List<String> names, Sym leaf) {
        Sym cur = target;
        for (int i = 0; i < names.size() - 1; i++) {
            Sym next = cur.member(names.get(i));
            if (next == null) {
                next = new Sym(names.get(i), target.module);
                cur.putMember(names.get(i), next);
            }
            cur = next;
        }
        if (!names.isEmpty()) {
            cur.putMember(names.get(names.size() - 1), leaf);
        }
    }

    private static List<String> withoutPrototype(List<String> names) {
        List<String> out = new ArrayList<>(names);
        out.remove("prototype");
        return out;
    }

    private static List<String> prepend(String first, List<String> rest) {
        List<String> out = new ArrayList<>(rest.size() + 1);
        out.add(first);
        out.addAll(rest);
        return out;
    }

    private static String join(String base, List<String> names) {
        StringBuilder sb = new StringBuilder(base);
        for (String n : names) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(n);
        }
        return sb.toString();
    }

    /** 이름 경로를 절대 경로로 주는 값(대입 대상이 바깥에 선언된 것)을 방문한다. 바깥 경로를 잠시 비운다. */
    private void withAbsolutePath(Runnable action) {
        String saved = path;
        path = "";
        try {
            action.run();
        } finally {
            path = saved;
        }
    }

    private void exportMember(String name, SyntaxNode value) {
        Sym s = new Sym(name, module);
        s.init = value;
        s.initScope = scope;
        s.namePath = name;
        module.exports.put(name, s);
        withAbsolutePath(() -> namedValue(value, name, null));
    }

    private void augmented(SyntaxNode node) {
        SyntaxNode left = Syntax.unwrap(node.field("left"));
        SyntaxNode right = node.field("right");
        SyntaxNode op = node.field("operator");
        if (left != null && right != null && left.is("member_expression") && op != null && op.type().equals("+=")
                && "innerHTML".equals(Syntax.memberName(left))) {
            // el.innerHTML += '<a href=…>': DOM에 넣는 HTML 문자열을 읽을 지점(F-010-R45)
            site(SiteKind.ASSIGN, node);
        }
        if (left != null && right != null && left.is("identifier") && op != null && op.type().equals("+=")) {
            module.pendingAssigns.add(new PendingAssign(left.text(), scope, right, true, null));
        } else if (left != null && left.is("identifier") && right != null) {
            // 다른 복합 대입은 값을 알 수 없게 만든다.
            module.pendingAssigns.add(new PendingAssign(left.text(), scope, node, false, null));
        }
        visit(left);
        visit(right);
    }

    /** 멤버 식의 객체 쪽에 있는 호출을 놓치지 않게 훑는다. */
    private void visitObjectPart(SyntaxNode member) {
        SyntaxNode n = member;
        while (n != null && n.isAny("member_expression", "subscript_expression")) {
            if (n.is("subscript_expression")) {
                visit(n.field("index"));
            }
            n = Syntax.unwrap(n.field("object"));
        }
        if (n != null && !Syntax.isIdentifier(n) && !n.is("this")) {
            visit(n);
        }
    }

    // ------------------------------------------------------------------ 호출, new, JSX

    private void site(SiteKind kind, SyntaxNode node) {
        module.sites.add(new Site(kind, node, scope, owner, unit));
    }

    private void call(SyntaxNode node) {
        SyntaxNode callee = Syntax.callee(node);
        if (callee == null) {
            visitChildren(node);
            return;
        }
        if (Syntax.isFunction(callee)) {
            // IIFE: 이름 없는 함수의 즉시 실행. 안의 것은 바깥에 귀속한다.
            String own = callee.is("arrow_function") ? null : nameOf(callee);
            if (own != null) {
                functionValue(callee, null, null);
            } else {
                enterTransparent(callee, null, null);
            }
            arguments(node);
            return;
        }
        if (callee.is("import")) {
            List<SyntaxNode> args = Syntax.arguments(node);
            if (args.size() >= 1 && Syntax.unwrap(args.get(0)).is("string")) {
                module.imports.add(new ImportRecord(Syntax.stringContent(Syntax.unwrap(args.get(0))), node, unit));
            }
            return;
        }
        if (callee.is("identifier") && callee.text().equals("require") && scope.lookup("require") == null) {
            List<SyntaxNode> args = Syntax.arguments(node);
            if (args.size() == 1 && Syntax.unwrap(args.get(0)).is("string")) {
                module.usesModuleSyntax = true;
                module.usesCommonJs = true;
                module.imports.add(new ImportRecord(Syntax.stringContent(Syntax.unwrap(args.get(0))), node, unit));
                return;
            }
        }
        site(SiteKind.CALL, node);
        if (callee.is("identifier")) {
            stateUpdate(callee, node);
        }
        if (callee.isAny("member_expression", "subscript_expression")) {
            visitObjectPart(callee);
        } else if (!Syntax.isIdentifier(callee)) {
            visit(callee);
        }
        arguments(node);
    }

    private void newExpression(SyntaxNode node) {
        site(SiteKind.NEW, node);
        SyntaxNode ctor = Syntax.unwrap(node.field("constructor"));
        if (ctor != null && !Syntax.isIdentifier(ctor)) {
            if (ctor.is("member_expression")) {
                visitObjectPart(ctor);
            } else {
                visit(ctor);
            }
        }
        arguments(node);
    }

    /** 인자들. 함수 참조(식별자, 멤버 식)와 옵션 객체 안의 함수 참조(단축 프로퍼티 포함)는 REF 지점으로 남긴다. */
    private void arguments(SyntaxNode callOrNew) {
        SyntaxNode args = callOrNew.field("arguments");
        if (args == null) {
            return;
        }
        if (!args.is("arguments")) {
            visit(args);
            return;
        }
        for (SyntaxNode arg : Syntax.named(args)) {
            SyntaxNode inner = Syntax.unwrap(arg);
            if (inner == null) {
                continue;
            }
            if (isReference(inner)) {
                site(SiteKind.REF, inner);
                if (inner.is("member_expression")) {
                    visitObjectPart(inner);
                }
            } else if (inner.is("object")) {
                for (SyntaxNode member : inner.namedChildren()) {
                    if (member.is("pair")) {
                        SyntaxNode v = Syntax.unwrap(member.field("value"));
                        if (v != null && isReference(v)) {
                            site(SiteKind.REF, v);
                        }
                    } else if (member.is("shorthand_property_identifier")) {
                        // { success }: success: success와 같다
                        site(SiteKind.REF, member);
                    }
                }
                visit(inner);
            } else {
                visit(inner);
            }
        }
    }

    private static boolean isReference(SyntaxNode node) {
        return node.is("identifier") || (node.is("member_expression") && Syntax.chain(node) != null);
    }

    private void jsx(SyntaxNode node) {
        if (owner != null) {
            owner.hasJsx = true;
        }
        SyntaxNode open = node.is("jsx_self_closing_element") ? node : node.field("open_tag");
        if (open != null) {
            SyntaxNode name = open.field("name");
            SyntaxNode component = null;
            if (name != null && (name.is("member_expression")
                    || (name.is("identifier") && !name.text().isEmpty() && Character.isUpperCase(name.text().charAt(0))))) {
                module.sites.add(new Site(SiteKind.JSX, name, scope, owner, unit));
                component = name;
            }
            for (SyntaxNode attr : open.fields("attribute")) {
                jsxAttribute(attr, component);
                routeAttribute(attr, component, open);
            }
            if (component == null && name != null && name.is("identifier")) {
                hostAttribute(name.text(), open);
            }
        }
        for (SyntaxNode child : node.namedChildren()) {
            if (child == open || child.is("jsx_closing_element")) {
                continue;
            }
            visit(child);
        }
    }

    /** 라우터 컴포넌트의 경로 속성일 수 있는 것({@code to}, {@code href}, {@code path})을 나중에 해석하게 적어 둔다(F-010-R30). */
    private void routeAttribute(SyntaxNode attr, SyntaxNode component, SyntaxNode open) {
        if (component == null || !component.is("identifier") || !attr.is("jsx_attribute")) {
            return;
        }
        SyntaxNode name = attr.child("property_identifier");
        if (name == null || !ModuleInfo.ROUTE_ATTRIBUTES.contains(name.text())) {
            return;
        }
        SyntaxNode value = attr.child("string");
        if (value == null) {
            SyntaxNode expression = attr.child("jsx_expression");
            value = expression == null ? null : Syntax.unwrap(Syntax.firstNamed(expression));
        }
        if (value != null) {
            module.routeAttrs.add(new ModuleInfo.RouteAttr(component, name.text(), value, scope, owner, unit, open));
        }
    }

    /** JSX 속성의 값 식: 문자열이거나 중괄호 안의 식. 값이 없으면 null. */
    private static SyntaxNode attributeValue(SyntaxNode attr) {
        SyntaxNode value = attr.child("string");
        if (value == null) {
            SyntaxNode expression = attr.child("jsx_expression");
            value = expression == null ? null : Syntax.unwrap(Syntax.firstNamed(expression));
        }
        return value;
    }

    /** HTML 요소의 URL 속성({@code <a href>}, {@code <form action>}, {@code <img src>}, {@code <iframe src>})을 적어 둔다(F-010-R42). */
    private void hostAttribute(String element, SyntaxNode open) {
        String wanted = ModuleInfo.HOST_URL_ATTRIBUTES.get(element);
        if (wanted == null) {
            return;
        }
        SyntaxNode url = null;
        SyntaxNode method = null;
        for (SyntaxNode attr : open.fields("attribute")) {
            SyntaxNode name = attr.is("jsx_attribute") ? attr.child("property_identifier") : null;
            if (name == null) {
                continue;
            }
            if (name.text().equals(wanted)) {
                url = attributeValue(attr);
            } else if (name.text().equals("method") && element.equals("form")) {
                method = attributeValue(attr);
            }
        }
        if (url != null) {
            module.hostAttrs.add(new ModuleInfo.HostAttr(element, url, method, scope, owner, unit, open));
        }
    }

    /**
     * @param component 이 속성이 붙은 요소가 컴포넌트면 그 이름 식. HTML 요소면 null
     */
    private void jsxAttribute(SyntaxNode attr, SyntaxNode component) {
        if (attr.is("jsx_expression")) {
            visit(attr);
            return;
        }
        for (SyntaxNode c : attr.namedChildren()) {
            if (c.is("jsx_expression")) {
                SyntaxNode inner = Syntax.unwrap(Syntax.firstNamed(c));
                SyntaxNode propName = attr.child("property_identifier");
                SyntaxNode passed = component == null || propName == null || inner == null ? null : passedFunction(inner);
                if (passed != null) {
                    // 컴포넌트에 함수(참조나 리터럴)를 넘겼을 수 있다. 자식이 그 prop을 호출하는 지점과 나중에 잇는다.
                    module.propPasses.add(new PropPass(component, propName.text(), passed, scope));
                }
                if (inner != null && isReference(inner)) {
                    site(SiteKind.REF, inner);
                } else {
                    visit(c);
                }
            } else if (c.isAny("jsx_element", "jsx_self_closing_element")) {
                visit(c);
            }
        }
    }

    /** JSX 속성 값이 함수를 넘기는 모양이면 그 함수의 식: 참조, 함수 리터럴, {@code 참조.bind(...)}의 참조. 아니면 null. */
    private static SyntaxNode passedFunction(SyntaxNode value) {
        if (isReference(value) || Syntax.isFunction(value)) {
            return value;
        }
        if (value.is("call_expression")) {
            SyntaxNode callee = Syntax.callee(value);
            if (callee != null && callee.is("member_expression") && "bind".equals(Syntax.memberName(callee))) {
                SyntaxNode object = Syntax.unwrap(callee.field("object"));
                return object != null && isReference(object) ? object : null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ TypeScript

    private void enumDeclaration(SyntaxNode node) {
        String name = nameOf(node);
        SyntaxNode body = node.field("body");
        if (name == null || body == null) {
            return;
        }
        Map<String, Sym> props = new LinkedHashMap<>();
        for (SyntaxNode m : body.namedChildren()) {
            if (m.is("enum_assignment")) {
                String key = Syntax.propertyName(m.field("name"));
                if (key != null) {
                    Sym p = new Sym(key, module);
                    p.init = m.field("value");
                    p.initScope = scope;
                    props.put(key, p);
                }
            } else {
                String key = Syntax.propertyName(m);
                if (key != null) {
                    props.put(key, new Sym(key, module));
                }
            }
        }
        Sym s = new Sym(name, module);
        s.preset = new Val.Obj(props);
        scope.syms.put(name, s);
    }

    private void namespace(SyntaxNode node) {
        String name = nameOf(node);
        SyntaxNode body = node.field("body");
        if (name == null || body == null || name.contains("\"") || name.contains("'")) {
            return;
        }
        Scope savedScope = scope;
        String savedPath = path;
        Scope inner = new Scope(savedScope, module, true);
        Sym s = new Sym(name, module);
        s.preset = new Val.Obj(inner.syms);
        s.namePath = full(name);
        savedScope.syms.put(name, s);
        // 네임스페이스 안의 export는 모듈의 export가 아니다.
        Map<String, Sym> savedExports = new LinkedHashMap<>(module.exports);
        boolean savedSyntax = module.usesModuleSyntax;
        scope = inner;
        path = full(name);
        visitChildren(body);
        scope = savedScope;
        path = savedPath;
        module.exports.clear();
        module.exports.putAll(savedExports);
        module.usesModuleSyntax = savedSyntax;
    }

    // ------------------------------------------------------------------ 마무리

    /** 모듈의 모든 조각을 읽은 뒤: 식별자 대입을 심벌에 붙인다(끌어올림 때문에 미뤘다). */
    static void finish(ModuleInfo module) {
        for (PendingAssign a : module.pendingAssigns) {
            Sym s = a.scope().lookup(a.name());
            if (s == null) {
                if (a.append() || a.name().equals("location")) {
                    // location = url은 변수 선언이 아니라 화면 이동이다(전역 location을 가리는 심벌을 만들지 않는다)
                    continue;
                }
                // 선언 없는 대입은 암묵적 전역이다.
                s = new Sym(a.name(), module);
                s.init = a.value();
                s.initScope = a.scope();
                s.namePath = a.namePath();
                module.top.syms.put(a.name(), s);
                continue;
            }
            if (s.importRef != null) {
                continue;
            }
            if (a.append()) {
                s.addAppend(a.value(), a.scope());
            } else if (s.init == null && s.preset == null && !s.param && !s.multiAssigned() && s.destruct.isEmpty()) {
                s.init = a.value();
                s.initScope = a.scope();
            } else {
                s.addAssign(a.value(), a.scope());
            }
        }
        module.pendingAssigns.clear();
    }
}
