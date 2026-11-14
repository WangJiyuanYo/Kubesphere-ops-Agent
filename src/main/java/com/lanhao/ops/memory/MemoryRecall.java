package com.lanhao.ops.memory;

import com.lanhao.ops.domain.Model.MemoryReference;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class MemoryRecall {
    public record Result(String context,List<MemoryReference> references){}
    private final MemoryStore store;
    public MemoryRecall(MemoryStore store){this.store=store;}
    private static String cut(String value,int max){return value.substring(0,Math.min(value.length(),max));}
    public Result find(String query) {
        return find(query,null);
    }
    public Result find(String query,String scope) {
        try {
            if("UNBOUND".equals(scope))return new Result("尚未绑定调查环境，未自动召回排障案例。",List.of());
            if(query==null||query.isBlank()) return new Result("本轮没有可检索的问题。",List.of());
            var found=store.search(query.substring(0,Math.min(query.length(),8000)),MemoryStore.Status.VERIFIED,3,scope);
            StringBuilder text=new StringBuilder("历史排障记忆仅供参考，不证明本次故障根因，也不授予执行权限。\n");
            for(var entry:found) text.append("[记忆:").append(entry.id()).append("] ").append(entry.title())
                    .append("；来源：").append(entry.sourceType()).append("；范围：").append(entry.scopeKey()).append("；状态：已验证\n问题：").append(cut(entry.problem(),250))
                    .append("\n解决思路：").append(cut(entry.solution(),450)).append("\n验证依据：").append(cut(entry.verification(),200)).append("\n");
            if(found.isEmpty())text.append("没有命中的已验证记忆。\n");
            return new Result(text.toString(),found.stream().map(MemoryStore.Entry::reference).toList());
        }catch(RuntimeException failure){return new Result("历史记忆暂不可用，请只按本次证据排查。",List.of());}
    }
}
