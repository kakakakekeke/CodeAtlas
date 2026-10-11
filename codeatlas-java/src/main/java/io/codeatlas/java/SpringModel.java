package io.codeatlas.java;

import io.codeatlas.core.model.SourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** F-004에서 파일을 넘나들며 모으는 정보: 빈 정의와 주입 지점. 모든 파일을 본 뒤 {@link SpringLinker}가 엣지로 만든다. */
final class SpringModel {

    /**
     * 빈 정의 하나.
     *
     * @param name 빈 이름
     * @param typeName 빈 클래스가 프로젝트 타입이면 그 바이너리 이름, 아니면 null
     * @param supertypes 빈 클래스와 그 상위 타입 전부의 바이너리 이름
     * @param primary {@code @Primary} 여부
     */
    record Bean(String name, String typeName, Set<String> supertypes, boolean primary) {
    }

    /**
     * {@code @Bean} 메서드 하나.
     *
     * @param beanName 빈 이름
     * @param typeName 메서드를 선언한 타입의 바이너리 이름
     * @param methodName 메서드 이름
     * @param methodId 메서드 노드 ID
     */
    record BeanMethod(String beanName, String typeName, String methodName, String methodId) {
    }

    /** 주입 지점 하나. */
    static final class Injection {
        String fromId;
        TypeRef target;
        /** 대상이 프로젝트의 인터페이스·추상 클래스라 구현 후보를 찾아야 하는가. */
        boolean targetAbstract;
        String via;
        String field;
        String qualifier;
        boolean required = true;
        SourceLocation location;
    }

    /**
     * 반환식이 다른 프로젝트 메서드의 호출이다: 그 메서드가 돌려주는 뷰 이름을 따라가야 한다(F-004-R14).
     *
     * @param targetId 호출된 메서드의 노드 ID
     * @param text 반환식 원문(뷰 이름을 끝내 알 수 없을 때의 미해결 원문)
     * @param site 그 호출 지점의 {@code 줄:열}(호출한 메서드 이름의 자리). 합쳐 온 뷰 이름의 위치로 쓴다(F-004-R19)
     * @param arguments 호출 지점의 인자마다 문자열 상수 값(일부만 상수면 나머지는 {@code {*}}로 둔 것). 상수 조각이 없으면 null.
     *                  호출된 메서드가 인자로 뷰 이름을 만드는 도우미면 그 자리에 대입한다(F-004-R25)
     */
    record ViewDelegation(String targetId, String text, SourceLocation location, String site, List<String> arguments) {
    }

    /** 도우미의 반환식 틀에서 매개변수 자리를 적는 표시: {@code \u0001<매개변수 위치>\u0001}. */
    static final char PARAMETER_MARK = '\u0001';

    /** 메서드 하나의 반환식에서 읽은 뷰 이름 정보. 핸들러와, 핸들러가 부를 수 있는 String 반환 메서드(헬퍼)마다 하나. */
    static final class Views {
        final String methodId;
        /** 뷰 이름을 모으는 핸들러인가(매핑·{@code @ExceptionHandler}, {@code @ResponseBody} 아님). 아니면 헬퍼다. */
        final boolean handler;
        /** 그 메서드 자신의 코드에서 읽은 뷰 이름(나온 순서). */
        final Set<String> names = new LinkedHashSet<>();
        /** 뷰 이름 → 그 이름이 처음 나온 자리의 {@code 줄:열}(반환문, {@code ModelAndView} 생성식, {@code setViewName} 호출). */
        final Map<String, String> sites = new LinkedHashMap<>();
        /** 지역 변수에 대입된 리터럴 후보를 펼쳐 만든 뷰 이름(후보가 둘 이상일 때만). 링커가 이 이름의 RENDERS를 INFERRED로 만든다(F-004-R21). */
        final Set<String> candidates = new LinkedHashSet<>();
        final List<ViewDelegation> delegations = new ArrayList<>();
        /**
         * 도우미(핸들러 아님)의 반환식 가운데 매개변수가 섞인 것의 틀: 상수는 값, 매개변수는 {@link #PARAMETER_MARK}로 감싼 위치,
         * 알 수 없는 조각은 {@code {*}}. 호출 지점의 인자를 대입해 뷰 이름을 만든다(F-004-R25).
         */
        final List<String> parameterTemplates = new ArrayList<>();
        /** 값을 정하지 못한 반환식이 있다. 핸들러면 이미 미해결 엣지로 남겼다. */
        boolean dynamic;

        Views(String methodId, boolean handler) {
            this.methodId = methodId;
            this.handler = handler;
        }

        /** 뷰 이름 하나를 그 자리와 함께 적는다. 같은 이름이 다시 나오면 처음 자리를 둔다. */
        void add(String name, String site) {
            names.add(name);
            sites.putIfAbsent(name, site);
        }

        /** {@link #names}와 같은 순서의 자리 목록. */
        List<String> siteList() {
            List<String> list = new ArrayList<>();
            for (String name : names) {
                list.add(sites.getOrDefault(name, "0:0"));
            }
            return list;
        }
    }

    /**
     * 컨트롤러의 {@code @ModelAttribute} 메서드 하나(F-004-R15).
     *
     * @param signature 이름과 매개변수({@code get(java.lang.String)}). 하위 클래스가 재정의했는지 볼 때 쓴다
     * @param location 메서드 선언의 위치
     */
    record ModelAttributeMethod(String methodId, String signature, SourceLocation location) {
    }

    final List<Bean> beans = new ArrayList<>();
    /** 스테레오타입 빈 정의마다의 빈 노드(아직 그래프에 넣지 않은 것). 이름이 같은 정의를 모든 파일을 본 뒤에 하나로 정리한다(F-004-R22). */
    final List<io.codeatlas.core.model.Node> stereotypeBeans = new ArrayList<>();
    /** 메서드 ID → 뷰 이름 정보. */
    final Map<String, Views> views = new LinkedHashMap<>();
    /** 타입의 바이너리 이름 → 그 타입에 선언한 요청 핸들러 메서드 ID. */
    final Map<String, List<String>> handlers = new LinkedHashMap<>();
    /** 타입의 바이너리 이름 → 그 타입에 선언한 {@code @ModelAttribute} 메서드. */
    final Map<String, List<ModelAttributeMethod>> modelAttributes = new LinkedHashMap<>();
    final List<BeanMethod> beanMethods = new ArrayList<>();
    final List<Injection> injections = new ArrayList<>();
}
