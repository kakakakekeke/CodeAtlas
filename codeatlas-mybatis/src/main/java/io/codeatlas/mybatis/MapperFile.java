package io.codeatlas.mybatis;

import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.SourceFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 파싱한 매퍼 파일 하나.
 *
 * @param dialect 파일 이름이나 폴더에서 읽은 DB 방언({@link DialectVariants#dialectOf}). 없으면 null
 */
record MapperFile(SourceFile source, XmlDocument doc, String namespace, String dialect) {

    String path() {
        return source.relativePath();
    }

    SourceLocation location(XmlElement element) {
        return doc.location(path(), element);
    }

    /** 요소의 시작 태그 안에 있는 속성({@code name="value"})의 위치. 찾지 못하면 요소의 위치. */
    SourceLocation attrLocation(XmlElement element, String attrName) {
        if (element.startOffset < 0 || element.bodyStart <= element.startOffset) {
            return location(element);
        }
        String tag = doc.raw().substring(element.startOffset, element.bodyStart);
        Matcher m = Pattern.compile("(?<![\\w:.-])" + Pattern.quote(attrName) + "\\s*=\\s*([\"'])").matcher(tag);
        if (!m.find()) {
            return location(element);
        }
        int close = tag.indexOf(m.group(1).charAt(0), m.end());
        int start = element.startOffset + m.start();
        int end = close < 0 ? element.startOffset + m.end() : element.startOffset + close + 1;
        return doc.location(path(), start, end);
    }
}
