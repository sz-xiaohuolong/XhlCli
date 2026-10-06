package com.xhlcli.cli.terminal;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 终端统一自动补全器。
 * 覆盖一级斜杠命令、二级子命令、动态实体（模型/MCP/Skill）与项目相对路径补全。
 */
public final class TerminalCompleter implements Completer {

    private static final List<String> PRIMARY_COMMANDS = List.of(
            "/help", "/config", "/clear", "/history", "/model", "/mcp", "/skill",
            "/plan", "/team", "/search", "/search-text", "/index", "/prompt",
            "/browser", "/save", "/memory", "/context", "/compact", "/exit"
    );

    private static final Map<String, List<String>> SUBCOMMANDS = Map.of(
            "/model", List.of("list", "use", "status"),
            "/mcp", List.of("list", "status", "tools", "resources", "read", "restart", "stop", "start", "logs"),
            "/skill", List.of("list", "show", "enable", "disable", "reload"),
            "/prompt", List.of("show", "export"),
            "/browser", List.of("status", "connect", "disconnect", "tabs"),
            "/history", List.of("list", "clear"),
            "/memory", List.of("list", "search", "delete", "clear"),
            "/index", List.of("status", "clean")
    );

    private final Supplier<List<String>> modelSupplier;
    private final Supplier<List<String>> mcpSupplier;
    private final Supplier<List<String>> skillSupplier;
    private final Path projectDirectory;

    public TerminalCompleter(
            Supplier<List<String>> modelSupplier,
            Supplier<List<String>> mcpSupplier,
            Supplier<List<String>> skillSupplier,
            Path projectDirectory
    ) {
        this.modelSupplier = modelSupplier != null ? modelSupplier : Collections::emptyList;
        this.mcpSupplier = mcpSupplier != null ? mcpSupplier : Collections::emptyList;
        this.skillSupplier = skillSupplier != null ? skillSupplier : Collections::emptyList;
        this.projectDirectory = projectDirectory;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(candidates, "candidates");

        String word = line.word();
        int wordIndex = line.wordIndex();
        List<String> words = line.words();

        // 1. 项目文件路径补全（以 @ 触发）
        if (word != null && word.startsWith("@")) {
            completeProjectPath(word.substring(1), candidates);
            return;
        }

        // 2. 一级斜杠命令补全
        if (wordIndex == 0) {
            String prefix = word == null ? "" : word.toLowerCase(java.util.Locale.ROOT);
            for (String cmd : PRIMARY_COMMANDS) {
                if (cmd.startsWith(prefix)) {
                    candidates.add(new Candidate(cmd, cmd, "Commands", null, null, null, true));
                }
            }
            return;
        }

        if (words.isEmpty()) {
            return;
        }

        String primaryCmd = words.get(0).toLowerCase(java.util.Locale.ROOT);

        // 3. 二级子命令补全
        if (wordIndex == 1) {
            List<String> subs = SUBCOMMANDS.get(primaryCmd);
            if (subs != null) {
                String prefix = word == null ? "" : word.toLowerCase(java.util.Locale.ROOT);
                for (String sub : subs) {
                    if (sub.startsWith(prefix)) {
                        candidates.add(new Candidate(sub, sub, "Subcommands", null, null, null, true));
                    }
                }
            }
            return;
        }

        // 4. 三级动态实体补全
        if (wordIndex == 2 && words.size() >= 2) {
            String subCmd = words.get(1).toLowerCase(java.util.Locale.ROOT);
            String prefix = word == null ? "" : word.toLowerCase(java.util.Locale.ROOT);

            if ("/model".equals(primaryCmd) && "use".equals(subCmd)) {
                for (String m : modelSupplier.get()) {
                    if (m.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) {
                        candidates.add(new Candidate(m, m, "Models", null, null, null, true));
                    }
                }
            } else if ("/mcp".equals(primaryCmd) && isMcpServerSubcommand(subCmd)) {
                for (String s : mcpSupplier.get()) {
                    if (s.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) {
                        candidates.add(new Candidate(s, s, "MCP Servers", null, null, null, true));
                    }
                }
            } else if ("/skill".equals(primaryCmd) && isSkillSubcommand(subCmd)) {
                for (String sk : skillSupplier.get()) {
                    if (sk.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) {
                        candidates.add(new Candidate(sk, sk, "Skills", null, null, null, true));
                    }
                }
            }
        }
    }

    private boolean isMcpServerSubcommand(String sub) {
        return "status".equals(sub) || "restart".equals(sub) || "stop".equals(sub)
                || "start".equals(sub) || "logs".equals(sub);
    }

    private boolean isSkillSubcommand(String sub) {
        return "show".equals(sub) || "enable".equals(sub) || "disable".equals(sub);
    }

    private void completeProjectPath(String relativePrefix, List<Candidate> candidates) {
        if (projectDirectory == null || !Files.exists(projectDirectory)) {
            return;
        }

        try (Stream<Path> stream = Files.walk(projectDirectory, 3)) {
            stream.filter(p -> !isIgnored(p))
                    .map(p -> projectDirectory.relativize(p).toString().replace('\\', '/'))
                    .filter(rel -> !rel.isEmpty() && rel.startsWith(relativePrefix))
                    .limit(30)
                    .forEach(rel -> candidates.add(new Candidate("@" + rel, "@" + rel, "Files", null, null, null, true)));
        } catch (IOException ignored) {}
    }

    private boolean isIgnored(Path path) {
        String name = path.getFileName() != null ? path.getFileName().toString() : "";
        return name.startsWith(".") || name.equals("target") || name.equals("node_modules");
    }
}
