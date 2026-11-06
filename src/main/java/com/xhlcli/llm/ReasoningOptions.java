package com.xhlcli.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** 只开放已确认支持的强度，不向未知模型猜测或发送参数。 */
public final class ReasoningOptions {
    private ReasoningOptions() {}
    public static List<String> levels(String provider,String model) {
        if("deepseek".equals(provider)&&Set.of("deepseek-flash","deepseek-v4-flash","deepseek-v4-pro").contains(model))
            return List.of("none","low","high","max");
        if("openai".equals(provider)&&Set.of("o1","o3","o3-mini","o4-mini","gpt-5","gpt-5-mini","gpt-5-nano","gpt-5.1","gpt-5.2","gpt-5.4","gpt-5.5").contains(model))
            return List.of("low","medium","high");
        return List.of();
    }
    public static List<String> levels(String id) {
        int split=id.indexOf(':');return split<0?List.of():levels(id.substring(0,split),id.substring(split+1));
    }
    public static String validate(String model,String effort) {
        if("default".equals(effort)||levels(model).contains(effort))return effort;
        throw new IllegalArgumentException("该模型不支持所选推理强度，请选择可用档位或恢复默认");
    }
    static void apply(ObjectNode body,String provider,String model,String effort) {
        validate(provider+":"+model,effort);
        if("default".equals(effort))return;
        if("deepseek".equals(provider)) {
            body.putObject("thinking").put("type","none".equals(effort)?"disabled":"enabled");
            if(!"none".equals(effort))body.put("reasoning_effort",effort);
        }else body.put("reasoning_effort",effort);
    }
}
