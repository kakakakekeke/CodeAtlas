package com.example.shop.common;

import java.util.Date;

/**
 * 서비스 공통 상위 클래스 (제네릭).
 *
 * @param <T> 서비스가 다루는 VO 타입. erasure 는 BaseVO 다.
 */
public abstract class BaseService<T extends BaseVO> {

    /** 하위 클래스가 단건 조회를 구현한다. */
    protected abstract T doFind(Long id);

    /** 단건 조회. 없으면 업무 예외를 던진다. */
    public T getOrThrow(Long id) {
        T found = doFind(id);
        if (found == null) {
            throw new BizException("E404", CodeConstants.MSG_NOT_FOUND + " id=" + id);
        }
        return found;
    }

    /** 기본 검증. 하위 클래스가 재정의하면서 super.validate() 를 부른다. */
    protected void validate(T vo) {
        if (vo == null) {
            throw new BizException("입력값이 없습니다.");
        }
    }

    /** 감사 컬럼 채우기 */
    protected void audit(T vo, String userId) {
        Date now = new Date();
        if (vo.getRegId() == null) {
            vo.setRegId(userId);
            vo.setRegDt(now);
        }
        vo.setUpdId(userId);
        vo.setUpdDt(now);
    }
}
