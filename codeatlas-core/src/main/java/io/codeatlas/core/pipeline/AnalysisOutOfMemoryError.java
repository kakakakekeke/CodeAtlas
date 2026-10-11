package io.codeatlas.core.pipeline;

/**
 * 분석 중 힙이 모자랐을 때 {@link Pipeline}이 던진다. 원래의 {@link OutOfMemoryError}는 {@link #getCause()}에 있다.
 * 메시지는 한 줄이고, 어느 단계의 어떤 구현체가 무엇을 처리하던 중이었는지와 대처 방법(큰 데이터 스크립트를 {@code --exclude}로 빼거나
 * {@code -Xmx}를 늘린다)을 담는다. {@code OutOfMemoryError}의 하위 타입이라 전부터 그것을 잡던 코드는 그대로 잡는다.
 *
 * <pre>{@code
 * try {
 *     pipeline.run(projectDir, options, outputDir);
 * } catch (AnalysisOutOfMemoryError e) {
 *     System.err.println(e.getMessage());   // 스택 대신 한 줄 안내
 * }
 * }</pre>
 */
public final class AnalysisOutOfMemoryError extends OutOfMemoryError {

    private static final long serialVersionUID = 1L;

    private final String stage;
    private final String component;
    private final String activity;

    AnalysisOutOfMemoryError(String stage, String component, String activity, OutOfMemoryError cause) {
        super(message(stage, component, activity));
        this.stage = stage;
        this.component = component;
        this.activity = activity;
        initCause(cause);
    }

    private static String message(String stage, String component, String activity) {
        StringBuilder s = new StringBuilder("메모리 부족: ").append(stage);
        s.append(component == null ? " 단계에서" : " '" + component + "' 실행 중").append(" 힙이 모자랐다");
        if (activity != null && !activity.isBlank()) {
            s.append("(처리 중: ").append(activity.replace('\n', ' ').replace('\r', ' ')).append(')');
        }
        s.append(". 큰 데이터 스크립트(INSERT 덤프 등)는 --exclude로 제외하거나 JVM 옵션 -Xmx로 힙을 늘려 다시 실행한다(예: -Xmx8g).");
        return s.toString();
    }

    /** 실패한 단계: {@code 분석기}, {@code 링커}, {@code 마무리}, {@code 저장}, {@code 출력기}. */
    public String stage() {
        return stage;
    }

    /** 실행 중이던 분석기·링커·출력기의 {@code name()}. 구현체와 무관한 단계면 null. */
    public String component() {
        return component;
    }

    /** 그 구현체가 {@code AnalysisContext.progress}로 마지막에 알린 것(보통 파일 경로). 알린 적이 없으면 null. */
    public String activity() {
        return activity;
    }
}
