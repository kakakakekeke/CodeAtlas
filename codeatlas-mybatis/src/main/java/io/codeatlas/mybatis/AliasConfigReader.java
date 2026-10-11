package io.codeatlas.mybatis;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.FileType;
import io.codeatlas.core.spi.SourceFile;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 타입 별칭 설정을 읽어 {@link TypeAliases}에 등록한다.
 *
 * <ul>
 *   <li>mybatis-config.xml(루트가 {@code configuration}): {@code <typeAlias alias type>}, {@code <package name>}</li>
 *   <li>Spring XML: 이름이 {@code typeAliasesPackage}인 {@code <property>}(SqlSessionFactoryBean)</li>
 *   <li>application.properties / yml: {@code mybatis.type-aliases-package}</li>
 * </ul>
 * 파일 하나를 읽다 실패해도 나머지는 계속 읽는다.
 */
final class AliasConfigReader {

    private static final String PROPERTY_NAME = "typeAliasesPackage";

    private final AnalysisContext ctx;
    private final TypeAliases aliases;
    private final Consumer<Diagnostic> diagnostics;
    /** 명시한 별칭을 먼저 등록하고 패키지는 나중에 등록하려고 모아 둔다. */
    private final List<String> packages = new ArrayList<>();

    AliasConfigReader(AnalysisContext ctx, TypeAliases aliases, Consumer<Diagnostic> diagnostics) {
        this.ctx = ctx;
        this.aliases = aliases;
        this.diagnostics = diagnostics;
    }

    void readAll() {
        for (SourceFile file : ctx.files().ofType(FileType.XML, FileType.SPRING_XML, FileType.PROPERTIES, FileType.YAML)) {
            try {
                switch (file.type()) {
                    case XML -> readMyBatisConfig(file);
                    case SPRING_XML -> readSpringXml(file);
                    case PROPERTIES -> readProperties(file);
                    case YAML -> readYaml(file);
                    default -> {
                    }
                }
            } catch (RuntimeException e) {
                diagnostics.accept(Diagnostic.warn(DiagnosticCodes.PARSE_FAILED,
                        "MyBatis 별칭 설정을 읽지 못함: " + e, file.location()));
            }
        }
        for (String spec : packages) {
            aliases.registerPackage(spec);
        }
    }

    private void readMyBatisConfig(SourceFile file) {
        String text = file.readText();
        if (!text.contains("<typeAliases")) {
            return;
        }
        XmlDocument doc = XmlTreeParser.parse(text);
        if (doc.root() == null || !doc.root().name.equals("configuration")) {
            return;
        }
        if (doc.failed()) {
            diagnostics.accept(Diagnostic.error(DiagnosticCodes.PARSE_FAILED,
                    "MyBatis 설정 XML 파싱 실패(오류 전까지 읽은 별칭만 쓴다): " + doc.errorMessage(),
                    doc.errorLine() > 0 ? file.location(doc.errorLine(), doc.errorColumn(), doc.errorLine(), doc.errorColumn())
                            : file.location()));
        }
        for (XmlElement typeAliases : doc.root().elements("typeAliases")) {
            for (XmlElement child : typeAliases.elements()) {
                if (child.name.equals("typeAlias")) {
                    String type = child.attr("type");
                    if (type != null) {
                        String alias = child.attr("alias");
                        aliases.register(alias != null ? alias : TypeAliases.simpleName(type), type);
                    }
                } else if (child.name.equals("package")) {
                    addPackages(child.attr("name"), file);
                }
            }
        }
    }

    private void readSpringXml(SourceFile file) {
        String text = file.readText();
        if (!text.contains(PROPERTY_NAME)) {
            return;
        }
        // 파싱 실패 진단은 Spring XML 담당 분석기가 남긴다. 여기서는 읽힌 데까지만 쓴다.
        XmlDocument doc = XmlTreeParser.parse(text);
        if (doc.root() != null) {
            walkSpring(doc.root(), file);
        }
    }

    private void walkSpring(XmlElement element, SourceFile file) {
        if (element.name.equals("property") && PROPERTY_NAME.equals(element.attr("name"))) {
            String value = element.attr("value");
            if (value == null) {
                List<XmlElement> values = element.elements("value");
                value = values.isEmpty() ? null : values.get(0).ownText();
            }
            addPackages(value, file);
        }
        // p-namespace 표기: <bean p:typeAliasesPackage="...">
        element.attrs.forEach((name, value) -> {
            if (name.endsWith(":" + PROPERTY_NAME)) {
                addPackages(value, file);
            }
        });
        for (XmlElement child : element.elements()) {
            walkSpring(child, file);
        }
    }

    private void readProperties(SourceFile file) {
        for (String line : file.readText().split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int separator = indexOfSeparator(trimmed);
            if (separator > 0 && isAliasPackageKey(trimmed.substring(0, separator))) {
                addPackages(trimmed.substring(separator + 1), file);
            }
        }
    }

    private static int indexOfSeparator(String line) {
        int equals = line.indexOf('=');
        int colon = line.indexOf(':');
        if (equals < 0) {
            return colon;
        }
        return colon < 0 ? equals : Math.min(equals, colon);
    }

    /** 들여쓰기로 중첩 키를 따라가는 최소한의 YAML 읽기. {@code a.b: v} 한 줄 표기도 받는다. */
    private void readYaml(SourceFile file) {
        Deque<int[]> indents = new ArrayDeque<>();
        Deque<String> keys = new ArrayDeque<>();
        for (String line : file.readText().split("\\R")) {
            String content = line.strip();
            if (content.isEmpty() || content.startsWith("#") || content.startsWith("-") || content.equals("---")) {
                continue;
            }
            int colon = content.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            while (!indents.isEmpty() && indents.peek()[0] >= indent) {
                indents.pop();
                keys.pop();
            }
            String key = unquote(content.substring(0, colon).strip());
            String value = content.substring(colon + 1).strip();
            StringBuilder path = new StringBuilder();
            for (java.util.Iterator<String> it = keys.descendingIterator(); it.hasNext(); ) {
                path.append(it.next()).append('.');
            }
            path.append(key);
            if (value.isEmpty() || value.startsWith("#")) {
                indents.push(new int[] {indent});
                keys.push(key);
            } else if (isAliasPackageKey(path.toString())) {
                int comment = value.indexOf(" #");
                addPackages(unquote(comment >= 0 ? value.substring(0, comment).strip() : value), file);
            }
        }
    }

    /** {@code mybatis.type-aliases-package}와 그 변형(camelCase, 밑줄, mybatis-plus). */
    private static boolean isAliasPackageKey(String key) {
        String normalized = key.strip().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return normalized.equals("mybatis.typealiasespackage") || normalized.equals("mybatisplus.typealiasespackage");
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                && value.charAt(value.length() - 1) == value.charAt(0)) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private void addPackages(String value, SourceFile file) {
        if (value == null) {
            return;
        }
        for (String spec : value.split("[,;\\s]+")) {
            if (spec.isEmpty()) {
                continue;
            }
            if (spec.contains("${") || spec.contains("#{")) {
                diagnostics.accept(Diagnostic.info(DiagnosticCodes.UNSUPPORTED_SYNTAX,
                        "별칭 패키지 값이 프로퍼티 치환식이라 읽지 못함: " + spec, file.location()));
                continue;
            }
            packages.add(spec);
        }
    }
}
