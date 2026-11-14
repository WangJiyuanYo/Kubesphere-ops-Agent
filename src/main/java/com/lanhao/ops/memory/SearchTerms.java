package com.lanhao.ops.memory;

import java.util.*;
import java.util.regex.Pattern;

/** FTS5 unicode61 不负责中文分词，索引时显式生成汉字双字组及技术标识词。 */
public final class SearchTerms {
    private static final Pattern WORD=Pattern.compile("\\p{IsHan}+|[A-Za-z0-9_]+");
    private SearchTerms(){}
    public static List<String> tokens(String input,int limit) {
        var result=new LinkedHashSet<String>();
        var matcher=WORD.matcher(Objects.toString(input,""));
        while(matcher.find()&&result.size()<limit) {
            String word=matcher.group(); int[] chars=word.codePoints().toArray();
            if(Character.UnicodeScript.of(chars[0])==Character.UnicodeScript.HAN) {
                if(chars.length==1) result.add(word);
                else for(int i=0;i<chars.length-1&&result.size()<limit;i++) result.add(new String(chars,i,2));
            } else {
                result.add(word.toLowerCase(Locale.ROOT));
                String split=word.replaceAll("([A-Z]+)([A-Z][a-z])","$1 $2").replaceAll("([a-z0-9])([A-Z])","$1 $2");
                for(String part:split.split("[ _]+")) if(result.size()<limit&&!part.isBlank()) result.add(part.toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(result);
    }
    public static String index(String text){return String.join(" ",tokens(text,3000));}
    public static String match(String query){return tokens(query,32).stream().map(word->"\""+word+"\"").collect(java.util.stream.Collectors.joining(" OR "));}
}
