package io.codeatlas.sql.routine;

import io.codeatlas.sql.parser.Keywords;
import java.util.List;
import java.util.Set;

/** 그래프에 호출로 남기지 않는 내장 패키지·프로시저. 카탈로그에 정의된 이름은 여기 있어도 호출로 본다(판정은 호출하는 쪽이 한다). */
final class Builtins {

    private Builtins() {
    }

    /** 내장 패키지·스키마 이름(첫 조각). */
    private static final Set<String> QUALIFIERS = Set.of(
            "SYS", "STANDARD", "HTP", "HTF", "OWA", "XMLTYPE", "ANYDATA", "SYSIBMADM", "SYSCAT", "SYSSTAT", "SYSTOOLS", "SQLJ",
            "NULLID", "ORDSYS", "MDSYS", "CTXSYS", "WMSYS", "XDB", "LBACSYS", "PLITBLM", "DEBUG_EXTPROC", "SYSIBMINTERNAL");

    private static final String[] PREFIXES = {"OWA_", "CTX_", "SDO_", "WPG_", "HTMLDB_", "WWV_", "ORA_"};

    /** @param parts 호출 이름의 정규화한 조각 */
    static boolean isBuiltin(List<String> parts) {
        String first = parts.get(0);
        if (Keywords.isBuiltinCall(first)) {
            return true;
        }
        if (parts.size() == 1) {
            return false;
        }
        if (QUALIFIERS.contains(first)) {
            return true;
        }
        for (String p : PREFIXES) {
            if (first.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
