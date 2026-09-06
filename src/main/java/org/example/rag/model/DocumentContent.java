package org.example.rag.model;

import java.util.List;
import java.util.Map;

/** 原始页、工作表或标题作为检索证据的定位边界。 */
public record DocumentContent(String text, List<Section> sections) {
    public record Section(String text, Map<String,Object> metadata) { }
}
