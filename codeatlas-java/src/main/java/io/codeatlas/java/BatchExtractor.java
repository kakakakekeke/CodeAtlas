package io.codeatlas.java;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Ids;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.MethodReference;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F-005 Spring Batch Java 설정. 잡·스텝 빌더 체인을 구문(타입의 단순 이름과 메서드 이름)으로 읽어 {@link BatchModel}에 모은다.
 * 바인딩에 기대지 않으므로 Spring Batch jar가 없어도 같은 결과가 나온다.
 */
final class BatchExtractor extends ASTVisitor {

    private static final Set<String> MYBATIS_ITEMS = Set.of(
            "MyBatisPagingItemReader", "MyBatisCursorItemReader", "MyBatisBatchItemWriter");
    private static final Set<String> MYBATIS_BUILDERS = Set.of(
            "MyBatisPagingItemReaderBuilder", "MyBatisCursorItemReaderBuilder", "MyBatisBatchItemWriterBuilder");
    private static final Set<String> USE_ROLES = Set.of("reader", "processor", "writer", "tasklet", "listener");
    private static final String QUALIFIER = KnownTypes.FACTORY + "Qualifier";

    /** 이어지는 잡 체인의 상태. */
    private static final class JobState {
        final BatchModel.JobDef def;
        BatchModel.StepRef current;
        String pendingOn;

        JobState(BatchModel.JobDef def) {
            this.def = def;
        }
    }

    private final Unit unit;
    private final JavaProject project;
    private final BatchModel model;
    private final Map<ASTNode, BatchModel.StepDef> stepByExpression = new IdentityHashMap<>();
    private final Map<ASTNode, JobState> jobByExpression = new IdentityHashMap<>();
    private final Map<String, BatchModel.StepDef> stepByVariable = new HashMap<>();
    private final Map<String, JobState> jobByVariable = new HashMap<>();

    BatchExtractor(Unit unit) {
        this.unit = unit;
        this.project = unit.project;
        this.model = project.batch;
    }

    void run() {
        unit.cu.accept(this);
    }

    // 인자 안의 체인이 바깥 체인보다 먼저 처리되도록 endVisit에서 처리한다
    @Override
    public void endVisit(MethodInvocation node) {
        mybatisSetter(node);
        boolean chainTop = !(node.getParent() instanceof MethodInvocation parent && parent.getExpression() == node);
        if (chainTop) {
            chain(node);
        }
    }

    @Override
    public void endVisit(VariableDeclarationFragment node) {
        IVariableBinding variable = node.resolveBinding();
        if (variable != null && node.getInitializer() != null) {
            bind(variable.getKey(), node.getInitializer());
        }
    }

    @Override
    public void endVisit(Assignment node) {
        if (node.getLeftHandSide() instanceof SimpleName name && name.resolveBinding() instanceof IVariableBinding variable) {
            bind(variable.getKey(), node.getRightHandSide());
        }
    }

    private void bind(String variableKey, Expression value) {
        Expression e = strip(value);
        BatchModel.StepDef step = stepByExpression.get(e);
        if (step != null) {
            stepByVariable.put(variableKey, step);
        }
        JobState job = jobByExpression.get(e);
        if (job != null) {
            jobByVariable.put(variableKey, job);
        }
    }

    private static Expression strip(Expression expression) {
        Expression e = expression;
        while (true) {
            if (e instanceof ParenthesizedExpression p) {
                e = p.getExpression();
            } else if (e instanceof CastExpression c) {
                e = c.getExpression();
            } else {
                return e;
            }
        }
    }

    // ---- 체인 ----

    private void chain(MethodInvocation top) {
        List<MethodInvocation> calls = new ArrayList<>();
        Expression root = top;
        while (root instanceof MethodInvocation call) {
            calls.add(0, call);
            root = call.getExpression();
        }
        if (root == null) {
            return;
        }
        root = strip(root);
        if (root instanceof ClassInstanceCreation creation) {
            String type = JavaProject.simpleName(Unit.written(creation.getType()));
            Expression nameArg = creation.arguments().isEmpty() ? null : (Expression) creation.arguments().get(0);
            if (type.equals("JobBuilder")) {
                job(top, root, nameArg, calls);
            } else if (type.equals("StepBuilder")) {
                step(top, root, nameArg, calls);
            } else if (MYBATIS_BUILDERS.contains(type)) {
                for (MethodInvocation call : calls) {
                    String name = call.getName().getIdentifier();
                    if ((name.equals("queryId") || name.equals("statementId")) && call.arguments().size() == 1) {
                        statement(call, (Expression) call.arguments().get(0));
                    }
                }
            }
            return;
        }
        MethodInvocation first = calls.get(0);
        if (first.getName().getIdentifier().equals("get") && first.arguments().size() == 1) {
            String factory = factoryKind(root);
            if (factory != null) {
                Expression nameArg = (Expression) first.arguments().get(0);
                List<MethodInvocation> rest = calls.subList(1, calls.size());
                if (factory.equals("job")) {
                    job(top, first.getName(), nameArg, rest);
                } else {
                    step(top, first.getName(), nameArg, rest);
                }
                return;
            }
        }
        if (root instanceof SimpleName name && name.resolveBinding() instanceof IVariableBinding variable) {
            // 지역 변수에 담아 둔 빌더를 이어 간다
            JobState job = jobByVariable.get(variable.getKey());
            if (job != null) {
                jobCalls(job, calls);
                jobByExpression.put(top, job);
                return;
            }
            BatchModel.StepDef step = stepByVariable.get(variable.getKey());
            if (step != null) {
                stepCalls(step, calls, top);
                stepByExpression.put(top, step);
            }
        }
    }

    /** 수신자가 JobBuilderFactory면 "job", StepBuilderFactory면 "step", 아니면 null. */
    private String factoryKind(Expression receiver) {
        String type = simpleTypeName(receiver);
        if (type != null) {
            return type.equals("JobBuilderFactory") ? "job" : type.equals("StepBuilderFactory") ? "step" : null;
        }
        String name = receiver instanceof SimpleName s ? s.getIdentifier()
                : receiver instanceof FieldAccess f ? f.getName().getIdentifier() : "";
        return name.equalsIgnoreCase("jobBuilderFactory") ? "job" : name.equalsIgnoreCase("stepBuilderFactory") ? "step" : null;
    }

    /** 식의 정적 타입의 단순 이름. 타입이 classpath에 없어도 선언에 쓴 이름을 준다. 알 수 없으면 null. */
    private static String simpleTypeName(Expression expression) {
        ITypeBinding type = expression.resolveTypeBinding();
        if (type == null && expression instanceof SimpleName name && name.resolveBinding() instanceof IVariableBinding variable) {
            type = variable.getType();
        }
        if (type == null) {
            return null;
        }
        ITypeBinding erasure = type.getErasure() == null ? type : type.getErasure();
        return erasure.isRecovered() ? JavaProject.simpleName(JavaProject.written(erasure)) : erasure.getName();
    }

    private String ownerMethodId(ASTNode node) {
        Unit.Owner owner = unit.owner(node);
        return owner == null ? null : owner.methodId();
    }

    private static MethodDeclaration enclosingMethod(ASTNode node) {
        for (ASTNode p = node.getParent(); p != null; p = p.getParent()) {
            if (p instanceof MethodDeclaration m) {
                return m;
            }
            if (p instanceof AbstractTypeDeclaration) {
                return null;
            }
        }
        return null;
    }

    // ---- 잡 ----

    private void job(MethodInvocation top, ASTNode at, Expression nameArg, List<MethodInvocation> calls) {
        String name = nameArg == null ? null : Constants.string(nameArg);
        if (name == null || name.isBlank()) {
            nameNotConstant("잡", at);
            return;
        }
        BatchModel.JobDef def = new BatchModel.JobDef();
        def.name = name;
        def.methodId = ownerMethodId(top);
        def.location = unit.loc(at);
        model.jobs.add(def);
        JobState state = new JobState(def);
        jobCalls(state, calls);
        jobByExpression.put(top, state);
    }

    private void jobCalls(JobState state, List<MethodInvocation> calls) {
        for (MethodInvocation call : calls) {
            String method = call.getName().getIdentifier();
            Expression arg = call.arguments().isEmpty() ? null : (Expression) call.arguments().get(0);
            switch (method) {
                case "start", "flow" -> {
                    if (arg != null) {
                        state.current = stepRef(state, arg);
                    }
                }
                case "next" -> {
                    if (arg != null) {
                        BatchModel.StepRef to = stepRef(state, arg);
                        if (state.current != null) {
                            state.def.nexts.add(new BatchModel.Next(state.current, to, null, unit.loc(call.getName())));
                        }
                        state.current = to;
                    }
                }
                case "on" -> state.pendingOn = arg == null ? null
                        : Constants.string(arg) != null ? Constants.string(arg) : arg.toString();
                case "to" -> {
                    if (arg != null) {
                        BatchModel.StepRef to = stepRef(state, arg);
                        if (state.current != null) {
                            state.def.nexts.add(new BatchModel.Next(state.current, to, state.pendingOn, unit.loc(call.getName())));
                        }
                        state.current = to;
                        state.pendingOn = null;
                    }
                }
                case "from" -> {
                    if (arg != null) {
                        state.current = stepRef(state, arg);
                    }
                }
                case "stopAndRestart" -> {
                    if (arg != null) {
                        stepRef(state, arg);
                    }
                    state.pendingOn = null;
                }
                case "end", "fail", "stop" -> state.pendingOn = null;
                default -> {
                }
            }
        }
    }

    private BatchModel.StepRef stepRef(JobState state, Expression argument) {
        Expression e = strip(argument);
        BatchModel.StepRef ref = new BatchModel.StepRef();
        ref.text = CallGraphExtractor.clip(argument.toString());
        ref.location = unit.loc(argument);
        ref.inline = stepByExpression.get(e);
        if (ref.inline == null) {
            if (e instanceof MethodInvocation call && (call.getExpression() == null || call.getExpression() instanceof ThisExpression)) {
                ASTNode type = Unit.enclosingType(call);
                ref.typeName = type == null ? null : unit.typeName(type);
                ref.methodName = call.getName().getIdentifier();
            } else if (e instanceof SimpleName || (e instanceof FieldAccess f && f.getExpression() instanceof ThisExpression)) {
                SimpleName name = e instanceof SimpleName s ? s : ((FieldAccess) e).getName();
                IVariableBinding variable = name.resolveBinding() instanceof IVariableBinding v ? v : null;
                if (variable != null && stepByVariable.containsKey(variable.getKey())) {
                    ref.inline = stepByVariable.get(variable.getKey());
                } else if (variable == null || variable.isField() || variable.isParameter()) {
                    String qualifier = variable == null ? null : qualifier(variable);
                    ref.beanName = qualifier != null ? qualifier : name.getIdentifier();
                } else {
                    // 메서드 호출 결과를 담은 지역 변수: Step s = step1();
                    Expression initializer = initializer(variable);
                    if (initializer != null && strip(initializer) instanceof MethodInvocation call
                            && (call.getExpression() == null || call.getExpression() instanceof ThisExpression)) {
                        ASTNode type = Unit.enclosingType(call);
                        ref.typeName = type == null ? null : unit.typeName(type);
                        ref.methodName = call.getName().getIdentifier();
                    }
                }
            }
        }
        // 같은 스텝을 다시 가리키면(start(a) ... from(a)) 먼저 만든 참조를 쓴다
        for (BatchModel.StepRef existing : state.def.steps) {
            if (same(existing, ref)) {
                return existing;
            }
        }
        state.def.steps.add(ref);
        return ref;
    }

    private static boolean same(BatchModel.StepRef a, BatchModel.StepRef b) {
        if (a.inline != null || b.inline != null) {
            return a.inline == b.inline;
        }
        if (a.methodName != null || b.methodName != null) {
            return a.methodName != null && a.methodName.equals(b.methodName) && String.valueOf(a.typeName).equals(String.valueOf(b.typeName));
        }
        if (a.beanName != null || b.beanName != null) {
            return a.beanName != null && a.beanName.equals(b.beanName);
        }
        return a.text.equals(b.text);
    }

    // ---- 스텝 ----

    private void step(MethodInvocation top, ASTNode at, Expression nameArg, List<MethodInvocation> calls) {
        String name = nameArg == null ? null : Constants.string(nameArg);
        if (name == null || name.isBlank()) {
            nameNotConstant("스텝", at);
            return;
        }
        BatchModel.StepDef def = new BatchModel.StepDef();
        def.name = name;
        ASTNode type = Unit.enclosingType(top);
        def.typeName = type == null ? null : unit.typeName(type);
        MethodDeclaration method = enclosingMethod(top);
        def.methodName = method == null ? null : method.getName().getIdentifier();
        def.methodId = ownerMethodId(top);
        def.location = unit.loc(at);
        model.steps.add(def);
        stepCalls(def, calls, top);
        stepByExpression.put(top, def);
    }

    private void stepCalls(BatchModel.StepDef def, List<MethodInvocation> calls, ASTNode chain) {
        for (MethodInvocation call : calls) {
            String method = call.getName().getIdentifier();
            Expression arg = call.arguments().isEmpty() ? null : (Expression) call.arguments().get(0);
            if (method.equals("chunk")) {
                def.stepType = "chunk";
                if (arg != null && Constants.eval(arg) instanceof Number n) {
                    def.chunkSize = n.intValue();
                }
            } else if (USE_ROLES.contains(method) && arg != null) {
                if (method.equals("tasklet")) {
                    def.stepType = "tasklet";
                }
                BatchModel.Use use = use(arg, chain, 0);
                use.role = method;
                use.text = CallGraphExtractor.clip(arg.toString());
                use.location = unit.loc(arg);
                def.uses.add(use);
            }
        }
    }

    /** 구성 요소 인자가 가리키는 대상을 찾는다. */
    private BatchModel.Use use(Expression argument, ASTNode chain, int depth) {
        BatchModel.Use use = new BatchModel.Use();
        Expression e = strip(argument);
        if (e instanceof LambdaExpression || e instanceof MethodReference) {
            use.targetId = ownerMethodId(chain);
            use.lambda = true;
        } else if (e instanceof ClassInstanceCreation creation) {
            use.targetId = createdType(creation);
        } else if (e instanceof MethodInvocation call) {
            if (call.getExpression() == null || call.getExpression() instanceof ThisExpression) {
                use.targetId = sameClassMethod(call);
            } else {
                // 인라인 빌더: new XxxBuilder<>()....build()
                Expression root = call;
                while (root instanceof MethodInvocation c) {
                    root = c.getExpression();
                }
                if (root != null && strip(root) instanceof ClassInstanceCreation creation) {
                    use.targetId = createdType(creation);
                }
            }
        } else if (e instanceof SimpleName || (e instanceof FieldAccess f && f.getExpression() instanceof ThisExpression)) {
            SimpleName name = e instanceof SimpleName s ? s : ((FieldAccess) e).getName();
            IVariableBinding variable = name.resolveBinding() instanceof IVariableBinding v ? v : null;
            if (variable != null && !variable.isField() && !variable.isParameter()) {
                Expression initializer = initializer(variable);
                if (initializer != null && depth < 3) {
                    return use(initializer, chain, depth + 1);
                }
            } else {
                ITypeBinding type = variable == null ? null : Unit.reduce(variable.getType());
                if (type != null && !type.isRecovered() && type.isFromSource() && !type.isInterface()
                        && !Modifier.isAbstract(type.getModifiers())) {
                    use.targetId = Ids.javaType(project.binary(type));
                } else {
                    String qualifier = variable == null ? null : qualifier(variable);
                    use.beanName = qualifier != null ? qualifier : name.getIdentifier();
                    use.fallbackType = type == null ? null : unit.ref(type);
                }
            }
        }
        return use;
    }

    private String createdType(ClassInstanceCreation creation) {
        if (creation.getAnonymousClassDeclaration() != null) {
            return Ids.javaType(unit.typeName(creation.getAnonymousClassDeclaration()));
        }
        return project.typeId(unit.ref(creation.getType()));
    }

    /** 수신자 없는 호출이 가리키는 같은 클래스(또는 상위 프로젝트 타입)의 메서드 ID. 찾지 못하면 null. */
    private String sameClassMethod(MethodInvocation call) {
        IMethodBinding binding = call.resolveMethodBinding();
        if (binding != null && binding.getDeclaringClass() != null && binding.getDeclaringClass().isFromSource()
                && !unit.isBadCall(call.getName().getStartPosition())) {
            return project.methodId(binding);
        }
        ASTNode type = Unit.enclosingType(call);
        MethodDeclaration found = null;
        if (type instanceof AbstractTypeDeclaration declaration) {
            for (Object member : declaration.bodyDeclarations()) {
                if (member instanceof MethodDeclaration m && !m.isConstructor()
                        && m.getName().getIdentifier().equals(call.getName().getIdentifier())) {
                    if (found != null) {
                        return null;
                    }
                    found = m;
                }
            }
        }
        return found == null ? null : unit.methodId(found);
    }

    private Expression initializer(IVariableBinding variable) {
        ASTNode declaration = unit.cu.findDeclaringNode(variable);
        return declaration instanceof VariableDeclarationFragment fragment ? fragment.getInitializer() : null;
    }

    /** 매개변수·필드 선언에 붙은 {@code @Qualifier} 값. */
    private String qualifier(IVariableBinding variable) {
        ASTNode declaration = unit.cu.findDeclaringNode(variable);
        List<?> modifiers = null;
        if (declaration instanceof SingleVariableDeclaration parameter) {
            modifiers = parameter.modifiers();
        } else if (declaration instanceof VariableDeclarationFragment fragment && fragment.getParent() instanceof FieldDeclaration field) {
            modifiers = field.modifiers();
        }
        if (modifiers == null) {
            return null;
        }
        Unit.Anno qualifier = Unit.find(unit.annos(modifiers), QUALIFIER);
        return qualifier == null ? null : Constants.string(qualifier.value("value"));
    }

    private void nameNotConstant(String what, ASTNode at) {
        ASTNode shown = at instanceof SimpleName && at.getParent() != null ? at.getParent() : at;
        project.graph.report(Diagnostic.warn(DiagnosticCodes.UNRESOLVED_REFERENCE,
                "배치 " + what + " 이름이 문자열 상수가 아니라 해석하지 못함: " + CallGraphExtractor.clip(shown.toString()), unit.loc(at)));
    }

    // ---- MyBatis 리더·라이터 ----

    private void mybatisSetter(MethodInvocation node) {
        String name = node.getName().getIdentifier();
        if ((name.equals("setQueryId") || name.equals("setStatementId")) && node.arguments().size() == 1
                && node.getExpression() != null) {
            String type = simpleTypeName(node.getExpression());
            if (type != null && MYBATIS_ITEMS.contains(type)) {
                statement(node, (Expression) node.arguments().get(0));
            }
        }
    }

    private void statement(MethodInvocation call, Expression id) {
        String methodId = ownerMethodId(call);
        if (methodId != null) {
            model.statements.computeIfAbsent(methodId, k -> new ArrayList<>())
                    .add(new BatchModel.Statement(Constants.string(id), id.toString(), unit.loc(call.getName())));
        }
    }
}
