package com.xhlcli.team;

import java.util.*;
import static com.xhlcli.team.TeamOrchestrator.StepStatus.*;

/** 模型只能提出新图；编排器在切换之前校验依赖和成果保留。 */
final class TeamPlanValidator {
    private TeamPlanValidator() {}

    static boolean valid(List<TeamOrchestrator.ExecutionStep> steps, Set<String> completed) {
        if (steps.isEmpty() || steps.size() > 32) return false;
        Set<String> ids = new HashSet<>(completed);
        for (var step : steps) {
            if (!step.id().matches("[A-Za-z0-9_-]{1,64}") || step.description().isBlank()
                    || step.description().length() > 6000 || !ids.add(step.id())) return false;
        }
        Set<String> resolved = new HashSet<>(completed);
        for (int round = 0; round < steps.size(); round++) {
            for (var step : steps) {
                if (!ids.containsAll(step.dependencies()) || step.dependencies().contains(step.id())) return false;
                if (resolved.containsAll(step.dependencies())) resolved.add(step.id());
            }
        }
        return resolved.containsAll(ids);
    }

    static boolean validRevision(List<TeamOrchestrator.ExecutionStep> previous,
                                 List<TeamOrchestrator.ExecutionStep> replacement) {
        Set<String> completed = new HashSet<>();
        previous.stream().filter(s -> s.status() == COMPLETED).forEach(s -> completed.add(s.id()));
        if (!valid(replacement, completed)) return false;
        var oldRemaining = previous.stream().filter(s -> s.status() != COMPLETED).toList();
        if (sameGraph(oldRemaining, replacement)) return false;
        // 对比目标、类型和依赖结构；仅重命名 ID 也不能重新领取重试次数。
        var definitions = new HashMap<TaskDefinition, Integer>();
        var previousShapes = shapes(previous, definitions);
        var revised = new ArrayList<>(previous.stream().filter(s -> s.status() == COMPLETED).toList());
        revised.addAll(replacement);
        var revisedShapes = shapes(revised, definitions);
        for (var failed : previous) {
            if (failed.status() == FAILED && replacement.stream().anyMatch(n ->
                    previousShapes.get(failed.id()).equals(revisedShapes.get(n.id())))) return false;
        }
        // 后半程只允许修改失败步骤及其依赖后继，其余待办原样保留。
        if ((double) completed.size() / previous.size() >= 0.5) {
            Set<String> affected = new HashSet<>();
            previous.stream().filter(s -> s.status() == FAILED).forEach(s -> affected.add(s.id()));
            for (int i = 0; i < previous.size(); i++)
                for (var s : previous)
                    if (s.dependencies().stream().anyMatch(affected::contains)) affected.add(s.id());
            for (var s : oldRemaining) {
                if (!affected.contains(s.id()) && replacement.stream().noneMatch(n -> sameDefinition(s, n))) return false;
            }
        }
        return true;
    }

    private record TaskDefinition(String description, String type, Set<Integer> dependencies) {}

    private static Map<String, Integer> shapes(List<TeamOrchestrator.ExecutionStep> steps,
                                               Map<TaskDefinition, Integer> definitions) {
        Map<String, Integer> resolved = new HashMap<>();
        for (int round = 0; round < steps.size(); round++) {
            for (var step : steps) {
                if (resolved.containsKey(step.id()) || !resolved.keySet().containsAll(step.dependencies())) continue;
                Set<Integer> dependencies = new HashSet<>();
                step.dependencies().forEach(id -> dependencies.add(resolved.get(id)));
                var definition = new TaskDefinition(step.description(), step.type(), Set.copyOf(dependencies));
                resolved.put(step.id(), definitions.computeIfAbsent(definition, key -> definitions.size()));
            }
        }
        return resolved;
    }

    private static boolean sameGraph(List<TeamOrchestrator.ExecutionStep> a, List<TeamOrchestrator.ExecutionStep> b) {
        return a.size() == b.size() && a.stream().allMatch(s -> b.stream().anyMatch(n -> sameDefinition(s, n)));
    }
    private static boolean sameDefinition(TeamOrchestrator.ExecutionStep a, TeamOrchestrator.ExecutionStep b) {
        return a.id().equals(b.id()) && a.description().equals(b.description()) && a.type().equals(b.type())
                && new HashSet<>(a.dependencies()).equals(new HashSet<>(b.dependencies()));
    }
}
