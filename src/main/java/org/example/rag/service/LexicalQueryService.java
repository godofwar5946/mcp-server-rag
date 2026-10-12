package org.example.rag.service;

import com.huaban.analysis.jieba.JiebaSegmenter;
import org.springframework.stereotype.Service;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** 只对查询分词，利用现有正文 trigram 索引匹配中文词，不改动已发布切片与向量。 */
@Service
public class LexicalQueryService {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z][a-zA-Z0-9_.+#/-]*|[0-9]+(?:[%._-][0-9]+)*%?");
    private static final Set<String> STOP = Set.of("为什么","为何","怎么","怎样","如何","什么","哪些","哪个","多少","是否","能否",
            "可以","能够","应该","需要","目前","当前","现在","已经","还有","一个","这个","那个","这些","那些","一下",
            "请问","帮我","关于","进行","通过","以及","但是","然后","如果","的时候","时候","the","is","a","an","of","to","and","or","how","why","what","does","can");
    // 分词器在调用中使用局部实例，共享词典由库负责初始化，避免请求之间共享可变分词状态。
    public List<String> terms(String query) {
        String normalized=Normalizer.normalize(query,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        LinkedHashSet<String> terms=new LinkedHashSet<>();
        IDENTIFIER.matcher(normalized).results().map(m->m.group()).filter(t->!STOP.contains(t)).forEach(terms::add);
        new JiebaSegmenter().sentenceProcess(normalized).stream().map(String::trim)
                .filter(t->t.codePointCount(0,t.length())>=2 && t.codePoints().allMatch(Character::isLetterOrDigit))
                .filter(t->!STOP.contains(t)).forEach(terms::add);
        return terms.stream().limit(12).toList();
    }

    /** LIKE 的通配符必须转义；C++、下划线、百分号等仍按原文匹配。 */
    public static String pattern(String text) {
        return "%"+text.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
    }
}
