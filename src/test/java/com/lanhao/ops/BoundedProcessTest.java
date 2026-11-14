package com.lanhao.ops;

import com.lanhao.ops.command.BoundedProcess;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class BoundedProcessTest {
    private List<String> command(String...args){
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),
                "-cp",System.getProperty("java.class.path"),Child.class.getName()));command.addAll(List.of(args));return command;
    }
    @Test void passesArgumentsLiterallyWithoutShellAndCapturesExitCode() {
        String literal="a;echo injected $(whoami) | del *";
        var result=new BoundedProcess().run(command("echo",literal),Duration.ofSeconds(5));
        assertThat(result.state()).isEqualTo("SUCCEEDED");assertThat(result.output()).isEqualTo(literal);
        result=new BoundedProcess().run(command("fail"),Duration.ofSeconds(5));
        assertThat(result.state()).isEqualTo("FAILED");assertThat(result.exitCode()).isEqualTo(7);
    }
    @Test void timesOutAndBoundsOutputBeforeReturning() {
        long start=System.nanoTime();var timed=new BoundedProcess().run(command("wait"),Duration.ofMillis(300));
        assertThat(timed.state()).isEqualTo("TIMED_OUT");assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(4));
        var large=new BoundedProcess().run(command("large"),Duration.ofSeconds(5));
        assertThat(large.state()).isEqualTo("OUTPUT_LIMIT");assertThat(large.output().length()).isLessThanOrEqualTo(8192);
    }
    @Test void interruptedExecutionStopsTheChildAndDoesNotReportSuccess() throws Exception {
        var stopped=new java.util.concurrent.atomic.AtomicBoolean();
        Thread thread=Thread.ofPlatform().start(()->{
            try{new BoundedProcess().run(command("wait"),Duration.ofSeconds(5));}
            catch(com.lanhao.ops.workflow.StepControl.Aborted expected){stopped.set(true);}
        });
        Thread.sleep(150);thread.interrupt();thread.join(3000);assertThat(thread.isAlive()).isFalse();assertThat(stopped).isTrue();
    }
    public static class Child {
        public static void main(String[] args)throws Exception {
            switch(args[0]) {
                case "echo"->System.out.print(args[1]);
                case "fail"->{System.err.print("fixture failure");System.exit(7);}
                case "large"->{System.out.print("x".repeat(20000));System.out.flush();}
                case "wait"->Thread.sleep(10000);
                default->throw new IllegalArgumentException();
            }
        }
    }
}
