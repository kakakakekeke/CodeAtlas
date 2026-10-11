package io.codeatlas.springxml;

import io.codeatlas.core.model.SourceLocation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** XML에서 읽은 빈 정의 하나({@code <bean>}, util 컬렉션, {@code id}가 있는 확장 네임스페이스 요소, 내부 빈). 파일을 읽은 그대로이고 상속 병합은 {@link BeanRegistry}가 한다. */
final class BeanDef {

    /** 다른 빈을 가리키는 참조 하나. 이름으로 가리키거나({@code beanName}) 내부 빈({@code inner})이다. */
    static final class Ref {
        final String beanName;
        final BeanDef inner;
        final boolean idref;
        final SourceLocation location;

        private Ref(String beanName, BeanDef inner, boolean idref, SourceLocation location) {
            this.beanName = beanName;
            this.inner = inner;
            this.idref = idref;
            this.location = location;
        }

        static Ref named(String beanName, SourceLocation location) {
            return new Ref(beanName, null, false, location);
        }

        static Ref idref(String beanName, SourceLocation location) {
            return new Ref(beanName, null, true, location);
        }

        static Ref inner(BeanDef inner, SourceLocation location) {
            return new Ref(null, inner, false, location);
        }
    }

    /** 프로퍼티, 생성자 인자, lookup-method, 컬렉션 빈의 원소 묶음. 문자열 값과 참조를 함께 담는다. */
    static final class Prop {
        /** 병합할 때 쓰는 키. 자식 빈이 같은 키를 주면 부모 것을 덮어쓴다. */
        final String key;
        /** INJECTS의 attrs.property. 없으면 null. */
        final String property;
        /** 문자열 값을 남길 노드 attrs 키. 남기지 않으면 null. */
        final String attrKey;
        final SourceLocation location;
        /** 단일 문자열 값. 없으면 null. */
        String scalar;
        /** 컬렉션의 문자열 원소들(map·props는 {@code key=value}). 컬렉션이 아니면 null. */
        List<String> items;
        final List<Ref> refs = new ArrayList<>();

        Prop(String key, String property, String attrKey, SourceLocation location) {
            this.key = key;
            this.property = property;
            this.attrKey = attrKey;
            this.location = location;
        }

        void collection() {
            if (items == null) {
                items = new ArrayList<>();
            }
        }

        /** 문자열 값들. 단일 값이면 원소 하나. */
        List<String> strings() {
            if (items != null) {
                return items;
            }
            return scalar == null ? List.of() : List.of(scalar);
        }
    }

    /** 확정된 빈 이름. 이름 없는 빈은 {@link BeanRegistry}가 채운다. */
    String name;
    /** name 속성의 나머지 이름들. */
    final List<String> aliases = new ArrayList<>();
    String className;
    String parent;
    String factoryBean;
    String factoryMethod;
    String scope;
    boolean abstractBean;
    boolean primary;
    String initMethod;
    String destroyMethod;
    String autowire;
    String profile;
    /** 내부 빈이면 true. 이름으로 참조할 수 없다. */
    boolean inner;
    /** {@code <bean>}이 아닌 요소로 정의됐으면 그 이름(예: {@code util:list}, {@code jdbc:embedded-database}). */
    String element;
    /** util 컬렉션 요소({@code util:list|set|map|properties})로 정의됐다. 클래스를 알 수 없는 것으로 본다. */
    boolean utilCollection;
    /** 이름 없는 util 빈의 이름을 지을 때 쓰는 팩터리 클래스. */
    String generatedNameBase;
    String file;
    SourceLocation location;
    final Map<String, Prop> props = new LinkedHashMap<>();
    /** 노드 attrs에 그대로 남길 값(util:properties의 location 등). */
    final Map<String, Object> extraAttrs = new LinkedHashMap<>();
    /** 이 빈 안에 정의된 내부 빈들. */
    final List<BeanDef> innerBeans = new ArrayList<>();

    boolean isUtilCollection() {
        return utilCollection;
    }

    @Override
    public String toString() {
        return "bean " + name + (className == null ? "" : "(" + className + ")");
    }
}
