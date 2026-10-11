package io.codeatlas.java;

import org.eclipse.jdt.core.dom.IModuleBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JDK 타입 판정(F-002-R13). 바인딩이 해석된 타입은 이름이 아니라 출처(JDK 시스템 모듈에서 온 타입인가)로 판정하고,
 * 출처를 알 수 없을 때만 이름 규칙을 쓴다. {@code javax.servlet}, {@code javax.persistence} 같은 Java EE 패키지와
 * {@code jakarta.*}는 JDK가 아니라 라이브러리다.
 */
final class JdkTypes {

    /**
     * {@code java.}, {@code jdk.}, {@code sun.}, {@code com.sun.}으로 시작하지 않지만 JDK에 들어 있는 패키지.
     * 이 이름과 그 하위 패키지를 JDK로 본다. 실행 중인 JDK의 시스템 모듈과 대조하는 테스트가 있다.
     */
    static final List<String> NON_JAVA_PREFIX_PACKAGES = List.of(
            "javax.accessibility", "javax.annotation.processing", "javax.crypto", "javax.imageio", "javax.lang.model",
            "javax.management", "javax.naming", "javax.net", "javax.print", "javax.rmi", "javax.script",
            "javax.security.auth", "javax.security.cert", "javax.security.sasl", "javax.smartcardio", "javax.sound",
            "javax.sql", "javax.swing", "javax.tools", "javax.transaction.xa",
            "javax.xml.catalog", "javax.xml.crypto", "javax.xml.datatype", "javax.xml.namespace", "javax.xml.parsers",
            "javax.xml.stream", "javax.xml.transform", "javax.xml.validation", "javax.xml.xpath",
            "org.ietf.jgss", "org.w3c.dom", "org.xml.sax", "netscape.javascript");

    /** 하위 패키지는 라이브러리일 수 있어 그 패키지에 직접 든 타입만 JDK로 보는 패키지({@code javax.xml.XMLConstants}). */
    private static final List<String> EXACT_PACKAGES = List.of("javax.xml");

    private static final String[] PREFIXES = {"java.", "jdk.", "sun.", "com.sun."};

    private JdkTypes() {
    }

    /** 이름 규칙: 바이너리 이름(또는 소스에 쓴 전체 이름)이 JDK 패키지에 속하는가. */
    static boolean isJdkName(String binaryName) {
        for (String prefix : PREFIXES) {
            if (binaryName.startsWith(prefix)) {
                return true;
            }
        }
        if (!binaryName.startsWith("javax.") && !binaryName.startsWith("org.") && !binaryName.startsWith("netscape.")) {
            return false;
        }
        for (String pkg : NON_JAVA_PREFIX_PACKAGES) {
            if (binaryName.length() > pkg.length() && binaryName.charAt(pkg.length()) == '.' && binaryName.startsWith(pkg)) {
                return true;
            }
        }
        for (String pkg : EXACT_PACKAGES) {
            if (binaryName.startsWith(pkg + ".") && binaryName.indexOf('.', pkg.length() + 1) < 0) {
                return true;
            }
        }
        return false;
    }

    private static final Map<String, Boolean> JAVA_LANG = new ConcurrentHashMap<>();

    /** 실행 중인 JDK의 {@code java.lang}에 그 단순 이름의 타입이 있는가(import 없이 쓸 수 있는 이름). */
    static boolean isJavaLangType(String simpleName) {
        if (simpleName.isEmpty() || simpleName.indexOf('.') >= 0 || !Character.isUpperCase(simpleName.charAt(0))) {
            return false;
        }
        return JAVA_LANG.computeIfAbsent(simpleName, name -> systemClass("java.lang." + name) != null);
    }

    /** 실행 중인 JDK의 클래스(초기화하지 않는다). 없으면 null. */
    static Class<?> systemClass(String binaryName) {
        try {
            return Class.forName(binaryName, false, ClassLoader.getPlatformClassLoader());
        } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    /**
     * 출처로 판정한다: JDK 시스템 모듈({@code java.*}, {@code jdk.*})에서 온 타입이면 true,
     * 소스나 classpath(이름 없는 모듈)에서 온 타입이면 false, 출처를 알 수 없으면(미해석·복구된 바인딩) null.
     */
    static Boolean byOrigin(ITypeBinding binding) {
        if (binding == null || binding.isRecovered()) {
            return null;
        }
        if (binding.isFromSource()) {
            return false;
        }
        IModuleBinding module;
        try {
            module = binding.getModule();
        } catch (RuntimeException e) {
            return null;
        }
        if (module == null) {
            return null;
        }
        String name = module.getName();
        if (name == null || name.isEmpty()) {
            return false;
        }
        return name.startsWith("java.") || name.startsWith("jdk.");
    }
}
