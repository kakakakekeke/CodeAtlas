package broken;

import java.util.List;

/**
 * 일부러 문법 오류를 넣은 Java 파일.
 * 분석기가 죽지 않고 이 파일만 실패(PARSE_FAILED)로 기록하는지 보는 용도다.
 */
public class BrokenService {

    private List<String> names;

    public int count() {
        return names.size()          // 세미콜론 없음
    }

    public void unbalanced( {        // 매개변수 목록이 닫히지 않음
        if (names != null {
            names.clear();
        }
    // 메서드와 클래스의 닫는 중괄호가 없다
