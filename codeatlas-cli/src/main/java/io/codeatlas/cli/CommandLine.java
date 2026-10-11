package io.codeatlas.cli;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 하위 명령의 인자를 해석한 결과. 값이 있는 옵션은 {@code --opt 값}과 {@code --opt=값} 둘 다 받고 여러 번 줄 수 있다.
 * {@code --}로 시작하지 않는 인자는 위치 인자다. {@code --help}, {@code -h}는 어느 명령에서나 받는다.
 *
 * <pre>{@code
 * CommandLine line = CommandLine.parse(args, List.of("--views"), List.of("--table", "--depth"));
 * int depth = line.number("--depth", 1, 0);
 * }</pre>
 */
final class CommandLine {

    private final List<String> positionals = new ArrayList<>();
    private final Set<String> flags = new LinkedHashSet<>();
    private final Map<String, List<String>> values = new LinkedHashMap<>();
    private boolean help;

    private CommandLine() {
    }

    /**
     * @param flagNames 값이 없는 옵션 이름({@code --} 포함)
     * @param valueNames 값이 있는 옵션 이름
     * @throws UsageException 모르는 옵션, 값이 빠진 옵션, 값을 붙인 플래그
     */
    static CommandLine parse(String[] args, Collection<String> flagNames, Collection<String> valueNames) {
        CommandLine line = new CommandLine();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--help") || arg.equals("-h")) {
                line.help = true;
                continue;
            }
            if (!arg.startsWith("--")) {
                line.positionals.add(arg);
                continue;
            }
            String name = arg;
            String value = null;
            int eq = arg.indexOf('=');
            if (eq > 0) {
                name = arg.substring(0, eq);
                value = arg.substring(eq + 1);
            }
            if (flagNames.contains(name)) {
                if (value != null) {
                    throw new UsageException(name + " 옵션은 값을 받지 않는다");
                }
                line.flags.add(name);
            } else if (valueNames.contains(name)) {
                if (value == null) {
                    if (i + 1 >= args.length) {
                        throw new UsageException(name + " 옵션에 값이 없다");
                    }
                    value = args[++i];
                }
                line.values.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            } else {
                throw new UsageException("알 수 없는 옵션: " + name);
            }
        }
        return line;
    }

    boolean help() {
        return help;
    }

    List<String> positionals() {
        return List.copyOf(positionals);
    }

    /** 플래그든 값 옵션이든 주어졌으면 true. */
    boolean has(String name) {
        return flags.contains(name) || values.containsKey(name);
    }

    /** 주어진 옵션 이름(준 순서, 플래그 먼저). */
    List<String> given() {
        List<String> names = new ArrayList<>(flags);
        names.addAll(values.keySet());
        return names;
    }

    /** 여러 번 준 값 전부(준 순서). 주지 않았으면 빈 목록. */
    List<String> values(String name) {
        return List.copyOf(values.getOrDefault(name, List.of()));
    }

    /** 마지막으로 준 값. 주지 않았으면 null. */
    String value(String name) {
        List<String> given = values.get(name);
        return given == null ? null : given.get(given.size() - 1);
    }

    /**
     * 정수 값.
     *
     * @param min 허용하는 가장 작은 값
     * @throws UsageException 정수가 아니거나 min보다 작으면
     */
    int number(String name, int defaultValue, int min) {
        String text = value(name);
        if (text == null) {
            return defaultValue;
        }
        int number;
        try {
            number = Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            throw new UsageException(name + " 값은 정수여야 한다: " + text);
        }
        if (number < min) {
            throw new UsageException(name + " 값은 " + min + " 이상이어야 한다: " + text);
        }
        return number;
    }

    /**
     * 정해진 값 중 하나. 대소문자를 가리지 않는다.
     *
     * @param choices 받는 값(소문자) → 뜻. 순서대로 오류 문구에 나온다
     * @throws UsageException 목록에 없는 값이면
     */
    <T> T choice(String name, Map<String, T> choices, T defaultValue) {
        String text = value(name);
        return text == null ? defaultValue : choice(name, text, choices);
    }

    static <T> T choice(String name, String text, Map<String, T> choices) {
        T found = choices.get(text.strip().toLowerCase(Locale.ROOT));
        if (found == null) {
            throw new UsageException(name + " 값을 알 수 없다: " + text + " (" + String.join(", ", choices.keySet()) + " 중 하나)");
        }
        return found;
    }
}
