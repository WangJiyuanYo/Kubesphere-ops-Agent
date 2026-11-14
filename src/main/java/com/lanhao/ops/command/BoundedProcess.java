package com.lanhao.ops.command;

import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Component
public class BoundedProcess {
    public record Result(String state,String output,Integer exitCode){}
    public Result run(List<String> argv,Duration timeout) {
        Process process=null;
        try {
            ProcessBuilder builder=new ProcessBuilder(argv).redirectErrorStream(true);
            // 不从宿主继承模型密钥、平台 Token 或隐式 kubectl 配置。
            var inherited=System.getenv();builder.environment().clear();
            for(String key:List.of("PATH","SystemRoot","WINDIR","TEMP","TMP","HOME","USERPROFILE"))
                if(inherited.containsKey(key))builder.environment().put(key,inherited.get(key));
            process=builder.start();process.getOutputStream().close();Process active=process;
            var output=new ByteArrayOutputStream();var overflow=new java.util.concurrent.atomic.AtomicBoolean();
            try(var reader=Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> reading=reader.submit(()->{
                    try(var input=active.getInputStream()) {
                        byte[] buffer=new byte[1024];int count;
                        while((count=input.read(buffer))!=-1) {
                            int available=8192-output.size();if(available>0)output.write(buffer,0,Math.min(available,count));
                            if(count>available){overflow.set(true);kill(active);break;}
                        }
                    }catch(IOException ignored){}
                });
                boolean finished;
                try {finished=process.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS);}
                catch(InterruptedException interrupted){kill(process);Thread.currentThread().interrupt();throw new com.lanhao.ops.workflow.StepControl.Aborted();}
                if(!finished)kill(process);
                try{reading.get(1,TimeUnit.SECONDS);}catch(Exception unavailable){active.getInputStream().close();reading.cancel(true);}
                return new Result(overflow.get()?"OUTPUT_LIMIT":!finished?"TIMED_OUT":process.exitValue()==0?"SUCCEEDED":"FAILED",
                        output.toString(StandardCharsets.UTF_8),finished?process.exitValue():null);
            }
        }catch(IOException unavailable){return new Result("FAILED","kubectl 进程启动失败",null);}
        finally{if(process!=null)kill(process);}
    }
    private static void kill(Process p){p.descendants().forEach(h->{if(h.isAlive())h.destroyForcibly();});if(p.isAlive())p.destroyForcibly();}
}
