package org.example.rag.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * 可检索的 Java 与 MyBatis XML 符号类型。
 */
public enum CodeSymbolType {
    JAVA_CLASS,
    JAVA_INTERFACE,
    JAVA_ENUM,
    JAVA_RECORD,
    JAVA_ANNOTATION,
    JAVA_METHOD,
    JAVA_CONSTRUCTOR,
    XML_MAPPER,
    XML_SELECT,
    XML_INSERT,
    XML_UPDATE,
    XML_DELETE,
    XML_SQL_FRAGMENT;

    private static final Set<CodeSymbolType> CLASS_TYPES = EnumSet.of(
            JAVA_CLASS,
            JAVA_INTERFACE,
            JAVA_ENUM,
            JAVA_RECORD,
            JAVA_ANNOTATION
    );

    private static final Set<CodeSymbolType> METHOD_TYPES = EnumSet.of(
            JAVA_METHOD,
            JAVA_CONSTRUCTOR
    );

    private static final Set<CodeSymbolType> SQL_TYPES = EnumSet.of(
            XML_SELECT,
            XML_INSERT,
            XML_UPDATE,
            XML_DELETE,
            XML_SQL_FRAGMENT
    );

    public boolean isClassType() {
        return CLASS_TYPES.contains(this);
    }

    public boolean isMethodType() {
        return METHOD_TYPES.contains(this);
    }

    public boolean isSqlType() {
        return SQL_TYPES.contains(this);
    }
}
