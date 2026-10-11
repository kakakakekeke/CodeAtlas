package com.example.shop.common;

import java.io.Serializable;
import java.util.Date;

/**
 * 모든 VO 의 공통 상위 클래스. 등록/수정 감사 컬럼을 가진다.
 */
public abstract class BaseVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 등록자 */
    private String regId;
    /** 등록일시 */
    private Date regDt;
    /** 수정자 */
    private String updId;
    /** 수정일시 */
    private Date updDt;

    public String getRegId() {
        return regId;
    }

    public void setRegId(String regId) {
        this.regId = regId;
    }

    public Date getRegDt() {
        return regDt;
    }

    public void setRegDt(Date regDt) {
        this.regDt = regDt;
    }

    public String getUpdId() {
        return updId;
    }

    public void setUpdId(String updId) {
        this.updId = updId;
    }

    public Date getUpdDt() {
        return updDt;
    }

    public void setUpdDt(Date updDt) {
        this.updDt = updDt;
    }
}
