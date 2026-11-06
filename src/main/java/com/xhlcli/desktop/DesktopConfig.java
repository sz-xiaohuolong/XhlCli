package com.xhlcli.desktop;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.xhlcli.config.*;
import com.xhlcli.llm.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

final class DesktopConfig {
    private final Path home;
    private final Map<String,String> environment;
    DesktopConfig(Path home, Map<String,String> environment) { this.home=home; this.environment=Map.copyOf(environment); }
    Map<String,String> environment(Path workspace) throws Exception {
        Map<String,String> result = new HashMap<>(ChatConfigLoader.readUserEnv(home.resolve(".xhlcli/config.json")));
        result.putAll(ChatConfigLoader.readDotEnv(workspace.resolve(".env"))); result.putAll(environment);
        return result;
    }
    ChatConfig limits(Path workspace) throws Exception { return ChatConfigLoader.load(new String[0],environment,workspace,home); }
    private ObjectNode userConfig() throws Exception {
        Path path = home.resolve(".xhlcli/config.json");
        if (!Files.exists(path)) return DesktopJson.MAPPER.createObjectNode();
        var value = DesktopJson.MAPPER.readTree(path.toFile());
        if (!(value instanceof ObjectNode object)) throw new IllegalArgumentException("用户配置不是 JSON 对象，原文件已保留");
        return object;
    }
    LlmProviderRegistry registry(Path workspace) throws Exception {
        var env = environment(workspace); var registry = new LlmProviderRegistry(env);
        var config = userConfig();
        for (String provider : List.of("deepseek","openai","anthropic","ollama")) {
            String model = config.path("providers").path(provider).path("model").asText("");
            if (provider.equals("deepseek")) model = limits(workspace).model();
            if (!model.isBlank()) registry.register(descriptor(registry,provider,model));
        }
        return registry;
    }
    ModelDescriptor descriptor(LlmProviderRegistry registry, String provider, String model) {
        var base = registry.findModel(provider+":"+model).orElseGet(()->registry.findModel(provider).orElseThrow(() -> new IllegalArgumentException("不支持的 Provider")));
        return new ModelDescriptor(provider+":"+model,provider,model,model,base.capabilities(),base.configured(),List.of(model));
    }
    LlmClient client(Path workspace, String model) throws Exception {
        return client(workspace,model,"default");
    }
    LlmClient client(Path workspace, String model, String effort) throws Exception {
        var registry = registry(workspace);
        var descriptor = registry.findModel(model).orElseGet(() -> {
            int split = model.indexOf(':');
            if (split<=0 || split==model.length()-1) throw new IllegalArgumentException("模型格式应为 provider:model");
            return descriptor(registry,model.substring(0,split),model.substring(split+1));
        });
        return registry.createClient(descriptor, DiagnosticSink.NO_OP,effort);
    }
    private boolean configured(String provider, Map<String,String> env, ObjectNode user) {
        if(provider.equals("ollama"))return !env.getOrDefault("OLLAMA_BASE_URL","").isBlank()
                ||!user.path("providers").path(provider).path("model").asText("").isBlank();
        return !env.getOrDefault(provider.toUpperCase(Locale.ROOT)+"_API_KEY","").isBlank()
                ||(provider.equals("anthropic")&&!env.getOrDefault("CLAUDE_API_KEY","").isBlank());
    }
    private String modelName(Path workspace, String provider, ObjectNode user, LlmProviderRegistry registry) throws Exception {
        if(provider.equals("deepseek"))return limits(workspace).model();
        String saved=user.path("providers").path(provider).path("model").asText("").strip();
        return saved.isBlank()?registry.findModel(provider).orElseThrow().modelName():saved;
    }
    private List<ModelDescriptor> configuredModels(Path workspace) throws Exception {
        var env=environment(workspace);var user=userConfig();var registry=registry(workspace);
        var models=new ArrayList<ModelDescriptor>();
        for(String provider:List.of("deepseek","openai","anthropic","ollama")) {
            if(configured(provider,env,user))models.add(descriptor(registry,provider,modelName(workspace,provider,user,registry)));
        }
        return models;
    }
    String defaultModel(Path workspace) throws Exception {
        return configuredModels(workspace).stream().findFirst().map(ModelDescriptor::id).orElse("");
    }
    ObjectNode describe(Path workspace) throws Exception {
        var env = environment(workspace); var user = userConfig();
        var result = DesktopJson.MAPPER.createObjectNode();
        var models=configuredModels(workspace);
        result.put("defaultModel",models.stream().findFirst().map(ModelDescriptor::id).orElse(""));
        result.set("models",DesktopJson.MAPPER.valueToTree(models));
        for(var item:result.withArray("models"))((ObjectNode)item).set("reasoningEfforts",DesktopJson.MAPPER.valueToTree(ReasoningOptions.levels(item.path("id").asText())));
        var providers=result.putArray("providers");
        var dotEnv=ChatConfigLoader.readDotEnv(workspace.resolve(".env"));
        for (String provider:List.of("deepseek","openai","anthropic","ollama")) {
            String key=provider.toUpperCase(Locale.ROOT)+"_API_KEY", base=provider.toUpperCase(Locale.ROOT)+"_BASE_URL";
            String source=environment.containsKey(key)?"进程环境变量":dotEnv.containsKey(key)?"项目 .env":"全局配置";
            if(provider.equals("ollama")) source="本地服务";
            String fallback=switch(provider) {case "openai"->"https://api.openai.com/v1";case "anthropic"->"https://api.anthropic.com";case "ollama"->"http://localhost:11434";default->"https://api.deepseek.com";};
            var item=providers.addObject(); item.put("id",provider); item.put("configured",configured(provider,env,user));
            item.put("source",source);item.put("baseUrl",env.getOrDefault(base,fallback));
            item.put("model",modelName(workspace,provider,user,registry(workspace)));
        }
        return result;
    }
    synchronized void save(ObjectNode input) throws Exception {
        String provider=input.path("provider").asText(), model=input.path("model").asText().trim(), url=input.path("baseUrl").asText().trim();
        if(!List.of("deepseek","openai","anthropic","ollama").contains(provider))throw new IllegalArgumentException("不支持的 Provider");
        if(model.isBlank()||model.length()>200)throw new IllegalArgumentException("请填写有效模型名称");
        URI uri=URI.create(url);
        if(!List.of("https","http").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("请填写不包含凭据的 HTTP/HTTPS 地址");
        var root=userConfig(); var item=root.withObject("/providers").withObject("/"+provider);
        item.put("model",model); item.put("baseUrl",url);
        String key=input.path("apiKey").asText().trim();
        if(!key.isBlank()) {
            item.put("apiKey",key);
            String legacy=switch(provider){case "deepseek"->"apiKey";case "openai"->"openaiApiKey";case "anthropic"->"anthropicApiKey";default->"";};
            if(!legacy.isBlank()&&root.has(legacy))root.put(legacy,key);
        }
        DesktopJson.writePrivate(home.resolve(".xhlcli/config.json"),root);
    }
    String redact(String text, Path workspace) {
        String result=SecretRedactor.redact(text,null);
        try { for(var entry:environment(workspace).entrySet()) if(entry.getKey().endsWith("API_KEY")&&!entry.getValue().isBlank())result=result.replace(entry.getValue(),"***"); }
        catch(Exception ignored) {}
        return result;
    }
    JsonNode redactNode(JsonNode value, Path workspace) {
        if(value.isTextual())return DesktopJson.MAPPER.getNodeFactory().textNode(redact(value.asText(),workspace));
        if(value.isObject()) {
            var copy=DesktopJson.MAPPER.createObjectNode();
            value.fields().forEachRemaining(entry->copy.set(entry.getKey(),redactNode(entry.getValue(),workspace)));
            return copy;
        }
        if(value.isArray()) {
            var copy=DesktopJson.MAPPER.createArrayNode();
            value.forEach(item->copy.add(redactNode(item,workspace)));return copy;
        }
        return value.deepCopy();
    }
    String environmentKey(Path workspace, String provider) {
        try { return environment(workspace).get(provider.toUpperCase(Locale.ROOT)+"_API_KEY"); }
        catch(Exception ignored) { return null; }
    }
}
