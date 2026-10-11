package io.codeatlas.java;

/**
 * 타입 참조를 해석한 결과.
 *
 * @param kind PROJECT(소스가 있는 타입), EXTERNAL(소스가 없는 타입. classpath에 없어 import로 이름을 복원한 것 포함), UNRESOLVED(이름을 복원하지 못함)
 * @param name PROJECT·EXTERNAL은 바이너리 이름, UNRESOLVED는 소스에 쓴 이름
 */
record TypeRef(Kind kind, String name) {

    enum Kind { PROJECT, EXTERNAL, UNRESOLVED }

    static TypeRef project(String binaryName) {
        return new TypeRef(Kind.PROJECT, binaryName);
    }

    static TypeRef external(String binaryName) {
        return new TypeRef(Kind.EXTERNAL, binaryName);
    }

    static TypeRef unresolved(String writtenName) {
        return new TypeRef(Kind.UNRESOLVED, writtenName);
    }

    boolean isProject() {
        return kind == Kind.PROJECT;
    }

    boolean isUnresolved() {
        return kind == Kind.UNRESOLVED;
    }

    /** 단순 이름(마지막 '.' 또는 '$' 뒤). */
    String simpleName() {
        return JavaProject.simpleName(name);
    }
}
