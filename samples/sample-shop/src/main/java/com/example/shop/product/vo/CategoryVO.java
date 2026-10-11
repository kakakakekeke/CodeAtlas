package com.example.shop.product.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 카테고리 VO (TB_CATEGORY). 자기 자신을 참조한다 (부모 1, 자식 N).
 */
public class CategoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer categoryId;
    private Integer parentId;
    private String categoryNm;
    private int lvl;
    private String path;
    /** 부모 카테고리 */
    private CategoryVO parent;
    /** 자식 카테고리 */
    private List<CategoryVO> children = new ArrayList<>();

    public Integer getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Integer categoryId) {
        this.categoryId = categoryId;
    }

    public Integer getParentId() {
        return parentId;
    }

    public void setParentId(Integer parentId) {
        this.parentId = parentId;
    }

    public String getCategoryNm() {
        return categoryNm;
    }

    public void setCategoryNm(String categoryNm) {
        this.categoryNm = categoryNm;
    }

    public int getLvl() {
        return lvl;
    }

    public void setLvl(int lvl) {
        this.lvl = lvl;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public CategoryVO getParent() {
        return parent;
    }

    public void setParent(CategoryVO parent) {
        this.parent = parent;
    }

    public List<CategoryVO> getChildren() {
        return children;
    }

    public void setChildren(List<CategoryVO> children) {
        this.children = children;
    }
}
