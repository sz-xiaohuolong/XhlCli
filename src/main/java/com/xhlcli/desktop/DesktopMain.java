package com.xhlcli.desktop;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.*;

/** 私有 stdio 入口：stdout 仅承载协议，任何现有库的控制台输出转到 stderr。 */
public final class DesktopMain {
    public static void main(String[] args) throws Exception {
        PrintStream protocol=System.out;System.setOut(System.err);
        Path home=Path.of(args.length==2&&args[0].equals("--home")?args[1]:System.getProperty("user.home"));
        var requests=Executors.newCachedThreadPool();
        try(var service=new DesktopService(home,System.getenv(),event->send(protocol,event));
            var reader=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))) {
            send(protocol,DesktopJson.MAPPER.createObjectNode().put("event","ready"));
            String line;
            while((line=reader.readLine())!=null) {
                final String message=line;
                requests.submit(()->{
                    ObjectNode reply=DesktopJson.MAPPER.createObjectNode();
                    try {
                        if(message.length()>2_000_000)throw new IllegalArgumentException("请求过大");
                        var request=DesktopJson.MAPPER.readTree(message);reply.set("id",request.path("id"));
                        String method=request.path("method").asText();
                        var params=request.path("params") instanceof ObjectNode object?object:DesktopJson.MAPPER.createObjectNode();
                        reply.set("result",method.equals("config.test")?service.testConnection(params):service.handle(method,params));
                    }catch(Exception failure){reply.put("error",com.xhlcli.config.SecretRedactor.redact(failure.getMessage(),null));}
                    send(protocol,reply);
                });
            }
            requests.shutdown();requests.awaitTermination(35,TimeUnit.SECONDS);
        } finally {requests.shutdownNow();}
    }
    private static void send(PrintStream stream,ObjectNode value){synchronized(stream){stream.println(value);stream.flush();}}
}
