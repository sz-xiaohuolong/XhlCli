package com.xhlcli.desktop;

import com.xhlcli.mcp.config.McpConfigLoader;
import com.xhlcli.mcp.manager.McpServerManager;
import com.xhlcli.skill.SkillRegistry;
import com.xhlcli.tool.ToolRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** 工作目录共享的扩展状态；跨轮次保留启停状态，应用退出时释放连接。 */
final class DesktopWorkspace implements AutoCloseable {
    final SkillRegistry skills;
    private final Path home,workspace;
    private final DesktopConfig config;
    private McpServerManager mcp;
    DesktopWorkspace(Path home,Path workspace,DesktopConfig config) {
        this.home=home;this.workspace=workspace;this.config=config;
        skills=DesktopSkills.registry(home,workspace);
        skills.scanAndReload();
    }
    SkillRegistry skillView(){return DesktopSkills.registry(home,workspace);}
    synchronized McpServerManager mcp() throws Exception {
        if(mcp==null) {
            var loaded=new McpConfigLoader(DesktopJson.MAPPER).load(home.resolve(".xhlcli/mcp.json"),workspace.resolve(".xhlcli/mcp.json"),config.environment(workspace));
            mcp=new McpServerManager(new ToolRegistry(List.of()),workspace,DesktopJson.MAPPER);
            mcp.registerServers(loaded);mcp.startAll(Duration.ofSeconds(3));
        }
        return mcp;
    }
    synchronized void reloadMcp() throws Exception {if(mcp!=null)mcp.close();mcp=null;mcp();}
    @Override public synchronized void close(){if(mcp!=null)mcp.close();}
}
