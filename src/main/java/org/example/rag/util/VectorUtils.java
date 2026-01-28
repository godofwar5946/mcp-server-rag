package org.example.rag.util;

import java.util.StringJoiner;

/**
 * 向量格式化工具。
 */
public final class VectorUtils {

    private VectorUtils() {
    }

    /**
     * 将 double[] 转换为 pgvector 可识别的字符串，例如：[0.1,0.2,0.3]
     */
    public static String toVectorString(double[] vector) {
        if (vector == null || vector.length == 0) {
            return "[]";
        }
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (double v : vector) {
            joiner.add(Double.toString(v));
        }
        return joiner.toString();
    }
}
