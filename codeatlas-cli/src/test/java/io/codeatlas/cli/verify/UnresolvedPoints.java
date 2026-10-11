package io.codeatlas.cli.verify;

import io.codeatlas.core.model.Confidence;
import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.SourceLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * expected-unresolved.md의 표에 적힌 지점. 그 지점이 실제로 미해결·진단으로 나왔는지는 자동으로 단정하지 않고,
 * 같은 파일의 근처 줄에 있는 UNRESOLVED 엣지와 진단을 모아 사람이 대조할 수 있게 한다.
 */
public final class UnresolvedPoints {

    /** 지점의 줄과 이만큼 떨어진 것까지 "근처"로 본다. 매퍼 구문처럼 근거 위치가 구문 시작 줄로 잡히는 경우가 있어서 넉넉하게 둔다. */
    public static final int NEARBY_LINES = 10;

    private static final Pattern ROW = Pattern.compile("^\\|\\s*([A-Z]\\d+)\\s*\\|([^|]*)\\|\\s*(.*)$");
    private static final Pattern LOCATION = Pattern.compile("`([^`\\s]+\\.[A-Za-z0-9]+):(\\d+)(?:-(\\d+))?`");

    /**
     * @param id 표의 번호(U1, D4, I2 등)
     * @param file 표에 적힌 경로. 프로젝트 루트 기준이거나 뒷부분만 적힌 경로다
     * @param text 표의 나머지 칸
     */
    public record Point(String id, String file, int startLine, int endLine, String text) {

        public String label() {
            return file + ":" + startLine + (endLine > startLine ? "-" + endLine : "");
        }

        boolean near(SourceLocation location) {
            if (location == null || !(location.file().equals(file) || location.file().endsWith("/" + file))) {
                return false;
            }
            if (location.startLine() <= 0) {
                return false;
            }
            int end = Math.max(location.endLine(), location.startLine());
            return location.startLine() <= endLine + NEARBY_LINES && end >= startLine - NEARBY_LINES;
        }
    }

    /** 지점 근처에서 찾은 것. */
    public record Evidence(List<Edge> unresolvedEdges, List<Diagnostic> diagnostics) {

        public boolean isEmpty() {
            return unresolvedEdges.isEmpty() && diagnostics.isEmpty();
        }
    }

    private UnresolvedPoints() {
    }

    /** 표의 행 가운데 위치 칸에 {@code `경로:줄`} 또는 {@code `경로:줄-줄`}이 있는 것을 읽는다. 위치가 여럿이면 지점도 여럿이다. */
    public static List<Point> parse(String markdown) {
        List<Point> points = new ArrayList<>();
        for (String line : markdown.split("\\R")) {
            Matcher row = ROW.matcher(line.strip());
            if (!row.matches()) {
                continue;
            }
            Matcher location = LOCATION.matcher(row.group(2));
            while (location.find()) {
                int start = Integer.parseInt(location.group(2));
                int end = location.group(3) == null ? start : Integer.parseInt(location.group(3));
                String text = row.group(3).replaceAll("\\s*\\|\\s*$", "").strip();
                points.add(new Point(row.group(1), location.group(1), start, end, text));
            }
        }
        return points;
    }

    /** 지점과 같은 파일의 근처 줄({@link #NEARBY_LINES} 이내)에 근거 위치가 있는 UNRESOLVED 엣지와 진단. */
    public static Evidence evidence(GraphModel graph, Point point) {
        List<Edge> edges = graph.edges().stream()
                .filter(e -> e.confidence() == Confidence.UNRESOLVED && point.near(e.location()))
                .toList();
        List<Diagnostic> diagnostics = graph.diagnostics().stream().filter(d -> point.near(d.location())).toList();
        return new Evidence(edges, diagnostics);
    }
}
