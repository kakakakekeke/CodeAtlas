package io.codeatlas.java;

import java.util.Set;

/**
 * 분석기가 의미를 아는 프레임워크 타입의 FQN. classpath에 없고 와일드카드 import만 있을 때
 * {@code 패키지 + 단순 이름}이 여기에 있으면 그 FQN으로 본다.
 */
final class KnownTypes {

    static final String STEREOTYPE = "org.springframework.stereotype.";
    static final String CONTEXT = "org.springframework.context.annotation.";
    static final String FACTORY = "org.springframework.beans.factory.annotation.";
    static final String WEB = "org.springframework.web.bind.annotation.";
    static final String IBATIS = "org.apache.ibatis.annotations.";

    private static final Set<String> NAMES = Set.of(
            STEREOTYPE + "Controller", STEREOTYPE + "Service", STEREOTYPE + "Repository", STEREOTYPE + "Component",
            STEREOTYPE + "Indexed",
            CONTEXT + "Configuration", CONTEXT + "Bean", CONTEXT + "Primary", CONTEXT + "Scope", CONTEXT + "Lazy",
            CONTEXT + "ComponentScan", CONTEXT + "Import", CONTEXT + "Profile",
            FACTORY + "Autowired", FACTORY + "Qualifier", FACTORY + "Value",
            "org.springframework.beans.factory.ObjectProvider",
            WEB + "RestController", WEB + "ControllerAdvice", WEB + "RestControllerAdvice", WEB + "RequestMapping",
            WEB + "GetMapping", WEB + "PostMapping", WEB + "PutMapping", WEB + "DeleteMapping", WEB + "PatchMapping",
            WEB + "ResponseBody", WEB + "RequestBody", WEB + "RequestParam", WEB + "PathVariable", WEB + "ModelAttribute",
            WEB + "ExceptionHandler", WEB + "RequestMethod", WEB + "ResponseStatus",
            "org.springframework.web.servlet.ModelAndView",
            "org.springframework.web.servlet.ViewResolver",
            "org.springframework.web.servlet.view.InternalResourceViewResolver",
            "org.springframework.web.servlet.view.UrlBasedViewResolver",
            IBATIS + "Mapper", IBATIS + "Select", IBATIS + "Insert", IBATIS + "Update", IBATIS + "Delete", IBATIS + "Param",
            "org.apache.ibatis.session.SqlSession", "org.apache.ibatis.session.SqlSessionFactory",
            "org.mybatis.spring.SqlSessionTemplate", "org.mybatis.spring.support.SqlSessionDaoSupport",
            "org.mybatis.spring.batch.MyBatisPagingItemReader", "org.mybatis.spring.batch.MyBatisCursorItemReader",
            "org.mybatis.spring.batch.MyBatisBatchItemWriter",
            "org.mybatis.spring.batch.builder.MyBatisPagingItemReaderBuilder",
            "org.mybatis.spring.batch.builder.MyBatisCursorItemReaderBuilder",
            "org.mybatis.spring.batch.builder.MyBatisBatchItemWriterBuilder",
            "javax.annotation.Resource", "jakarta.annotation.Resource",
            "javax.inject.Inject", "jakarta.inject.Inject", "javax.inject.Named", "jakarta.inject.Named",
            "javax.inject.Provider", "jakarta.inject.Provider",
            "lombok.RequiredArgsConstructor", "lombok.AllArgsConstructor", "lombok.NonNull", "lombok.val", "lombok.var",
            "org.springframework.batch.core.Job", "org.springframework.batch.core.Step",
            "org.springframework.batch.core.job.builder.JobBuilder", "org.springframework.batch.core.step.builder.StepBuilder",
            "org.springframework.batch.core.configuration.annotation.JobBuilderFactory",
            "org.springframework.batch.core.configuration.annotation.StepBuilderFactory",
            "org.springframework.batch.core.step.tasklet.Tasklet",
            "org.springframework.batch.item.ItemReader", "org.springframework.batch.item.ItemProcessor",
            "org.springframework.batch.item.ItemWriter",
            "org.springframework.boot.autoconfigure.SpringBootApplication",
            "javax.servlet.Filter", "jakarta.servlet.Filter", "javax.servlet.http.HttpFilter", "jakarta.servlet.http.HttpFilter",
            "org.springframework.web.filter.OncePerRequestFilter", "org.springframework.web.filter.GenericFilterBean",
            "org.springframework.web.servlet.HandlerInterceptor", "org.springframework.web.servlet.AsyncHandlerInterceptor",
            "org.springframework.web.servlet.handler.HandlerInterceptorAdapter");

    /** 서블릿 필터 인터페이스·기반 클래스(F-004-R18). */
    private static final Set<String> FILTER_TYPES = Set.of(
            "javax.servlet.Filter", "jakarta.servlet.Filter", "javax.servlet.http.HttpFilter", "jakarta.servlet.http.HttpFilter");
    /** 패키지 이름에 이 조각이 있고 이름이 {@code Filter}로 끝나는 소스 없는 타입은 서블릿 필터의 기반 클래스로 본다. */
    private static final Set<String> WEB_PACKAGE_SEGMENTS = Set.of("web", "servlet", "webapp", "http", "catalina");
    private static final Set<String> INTERCEPTOR_TYPES = Set.of("org.springframework.web.context.request.WebRequestInterceptor",
            "org.springframework.web.context.request.AsyncWebRequestInterceptor");
    private static final String INTERCEPTOR_PACKAGE = "org.springframework.web.servlet.";

    private KnownTypes() {
    }

    static boolean contains(String fqn) {
        return NAMES.contains(fqn);
    }

    /**
     * 소스 없는 타입이 요청 처리의 진입점 역할을 하는 타입이면 그 역할: 서블릿 필터는 {@code filter},
     * Spring MVC의 핸들러 인터셉터는 {@code interceptor}. 아니면 null.
     */
    static String webRole(String fqn) {
        String simple = JavaProject.simpleName(fqn);
        if (FILTER_TYPES.contains(fqn)) {
            return "filter";
        }
        if (simple.endsWith("Filter") && !JdkTypes.isJdkName(fqn)) {
            // Spring(web.filter, security.web), Shiro(web.filter, web.servlet), Ehcache(constructs.web.filter) 등의 필터 기반 클래스
            String[] segments = JavaProject.packageOf(fqn).split("\\.");
            for (String segment : segments) {
                if (WEB_PACKAGE_SEGMENTS.contains(segment)) {
                    return "filter";
                }
            }
        }
        if (INTERCEPTOR_TYPES.contains(fqn) || (fqn.startsWith(INTERCEPTOR_PACKAGE) && simple.contains("Interceptor"))) {
            return "interceptor";
        }
        return null;
    }
}
