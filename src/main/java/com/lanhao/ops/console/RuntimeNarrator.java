package com.lanhao.ops.console;

import com.lanhao.ops.AgentProperties;
import com.lanhao.ops.diagnosis.*;
import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.evidence.Redactor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
public class RuntimeNarrator implements DiagnosticNarrator {
    private final ConsoleSettings settings;
    private final ModelGateway gateway;
    private final Redactor redactor;
    private final AgentProperties config;
    private final ObjectProvider<ChatModel> startupModel;
    public RuntimeNarrator(ConsoleSettings settings,ModelGateway gateway,Redactor redactor,AgentProperties config,ObjectProvider<ChatModel> startupModel) {
        this.settings=settings; this.gateway=gateway; this.redactor=redactor; this.config=config; this.startupModel=startupModel;
    }
    public Diagnosis explain(String facts,Action action) {
        try {
            var saved=settings.llm();
            if(saved.isPresent()) {
                if(!saved.get().enabled()) return RunbookNarrator.fallback(action,"控制台已关闭 LLM，使用规则手册");
                return new AiNarrator(redactor,"控制台 LLM / "+saved.get().model(),messages->gateway.structured(saved.get(),messages,AiNarrator.Answer.class)).explain(facts,action);
            }
            ChatModel legacy=config.aiEnabled()?startupModel.getIfAvailable():null;
            return legacy==null?RunbookNarrator.fallback(action,"规则手册"):new AiNarrator(legacy,redactor).explain(facts,action);
        } catch(RuntimeException e){return RunbookNarrator.fallback(action,ModelGateway.classify(e).getMessage()+"；已回退规则手册");}
    }
}
