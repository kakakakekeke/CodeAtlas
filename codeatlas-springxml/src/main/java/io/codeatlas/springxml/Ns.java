package io.codeatlas.springxml;

/** Spring 설정 XML에서 쓰는 네임스페이스 URI. 접두사는 파일마다 달라도 URI로 알아본다. */
final class Ns {

    static final String BEANS = "http://www.springframework.org/schema/beans";
    static final String P = "http://www.springframework.org/schema/p";
    static final String C = "http://www.springframework.org/schema/c";
    static final String CONTEXT = "http://www.springframework.org/schema/context";
    static final String UTIL = "http://www.springframework.org/schema/util";
    static final String MVC = "http://www.springframework.org/schema/mvc";
    static final String AOP = "http://www.springframework.org/schema/aop";
    static final String BATCH = "http://www.springframework.org/schema/batch";
    static final String MYBATIS = "http://mybatis.org/schema/mybatis-spring";

    private Ns() {
    }
}
