package io.codeatlas.java;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TextBlock;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * 컴파일 타임 상수 식의 값을 구한다. JDT의 상수 계산을 먼저 쓰고,
 * 그것이 값을 주지 않을 때(바인딩이 불완전한 문맥)만 리터럴·연결·같은 파일의 final 변수를 직접 따라간다.
 */
final class Constants {

    /** 정적으로 알 수 없는 부분의 자리 표시. */
    static final String DYNAMIC = "{*}";

    private static final int MAX_DEPTH = 6;

    private Constants() {
    }

    /** 상수 값(String, Number, Boolean, Character). 상수가 아니면 null. */
    static Object eval(Expression expression) {
        return eval(expression, 0);
    }

    /** 문자열 상수 값. 문자열 상수가 아니면 null. */
    static String string(Expression expression) {
        return eval(expression, 0) instanceof String s ? s : null;
    }

    /**
     * 문자열 연결식에서 상수 부분은 값으로, 나머지는 {@code {*}}로 바꾼 문자열.
     * 상수 부분이 하나도 없으면 null.
     */
    static String template(Expression expression) {
        String constant = string(expression);
        if (constant != null) {
            return constant;
        }
        Expression e = unwrap(expression);
        if (!(e instanceof InfixExpression infix) || infix.getOperator() != InfixExpression.Operator.PLUS) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean anyConstant = false;
        for (Expression operand : operands(infix)) {
            Object value = eval(operand, 0);
            String part = value != null ? String.valueOf(value) : template(operand);
            if (part == null) {
                if (!sb.toString().endsWith(DYNAMIC)) {
                    sb.append(DYNAMIC);
                }
            } else {
                anyConstant |= !part.replace(DYNAMIC, "").isEmpty();
                sb.append(part);
            }
        }
        return anyConstant ? sb.toString().replace(DYNAMIC + DYNAMIC, DYNAMIC) : null;
    }

    private static Expression unwrap(Expression e) {
        while (e instanceof ParenthesizedExpression p) {
            e = p.getExpression();
        }
        return e;
    }

    private static List<Expression> operands(InfixExpression infix) {
        List<Expression> operands = new ArrayList<>();
        operands.add(infix.getLeftOperand());
        operands.add(infix.getRightOperand());
        for (Object o : infix.extendedOperands()) {
            operands.add((Expression) o);
        }
        return operands;
    }

    private static Object eval(Expression expression, int depth) {
        if (expression == null || depth > MAX_DEPTH) {
            return null;
        }
        Object value = expression.resolveConstantExpressionValue();
        if (value != null) {
            return value;
        }
        Expression e = unwrap(expression);
        if (e instanceof StringLiteral s) {
            return s.getLiteralValue();
        }
        if (e instanceof TextBlock t) {
            return t.getLiteralValue();
        }
        if (e instanceof BooleanLiteral b) {
            return b.booleanValue();
        }
        if (e instanceof InfixExpression infix && infix.getOperator() == InfixExpression.Operator.PLUS) {
            StringBuilder sb = new StringBuilder();
            boolean anyString = false;
            for (Expression operand : operands(infix)) {
                Object v = eval(operand, depth + 1);
                if (v == null) {
                    return null;
                }
                anyString |= v instanceof String;
                sb.append(v);
            }
            return anyString ? sb.toString() : null;
        }
        if (e instanceof Name name) {
            IBinding binding = name.resolveBinding();
            if (binding instanceof IVariableBinding variable) {
                if (variable.getConstantValue() != null) {
                    return variable.getConstantValue();
                }
                if (Modifier.isFinal(variable.getModifiers()) && e.getRoot() instanceof CompilationUnit cu) {
                    ASTNode declaration = cu.findDeclaringNode(variable.getVariableDeclaration());
                    if (declaration instanceof VariableDeclarationFragment fragment) {
                        return eval(fragment.getInitializer(), depth + 1);
                    }
                }
            }
        }
        return null;
    }
}
