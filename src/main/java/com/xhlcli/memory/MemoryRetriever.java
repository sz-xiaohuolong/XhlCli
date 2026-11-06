package com.xhlcli.memory;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** 小规模 JSONL 的可解释文本基线，不调用模型，也不推断事实替代关系。 */
public final class MemoryRetriever {
    private static final Pattern TERMS = Pattern.compile("[-./]*[a-z0-9_]+(?:[./:_-][a-z0-9_]+)*|\\p{IsHan}+");
    private static final Pattern REFERENCES = Pattern.compile("刚才|那个|这个|上述|之前提到|\\b(that|this|it)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHINESE_STOP = Pattern.compile("为什么|如何|什么|怎么|为何|怎样|请问|帮我|一下|看看|这个|那个|刚才|当初|之前|现在|我们|是否|能否|没有|没用|使用|采用|方案|项目|请");
    private static final Set<String> STOP = Set.of("the", "a", "an", "is", "are", "was", "were", "why", "how", "what", "we", "i", "it", "this", "that", "please", "use", "using", "not", "do", "did", "does", "to", "for", "of", "and", "in");
    private final Clock clock;

    public MemoryRetriever() { this(Clock.systemUTC()); }
    public MemoryRetriever(Clock clock) { this.clock = Objects.requireNonNull(clock); }

    public MemoryRecallResult recall(List<MemoryEntry> entries, MemoryQuery query) {
        Instant now = clock.instant();
        var eligible = entries.stream().filter(e -> (e.expiresAt() == null || Instant.parse(e.expiresAt()).isAfter(now))
                && (e.supersededBy() == null || e.supersededBy().isBlank())).toList();
        Map<String, Double> queryTerms = queryWeights(query.input());
        normalize(queryTerms, 1.0);
        Set<String> identifiers = new TreeSet<>(queryTerms.keySet());
        identifiers.removeIf(term -> Character.UnicodeScript.of(term.codePointAt(0)) == Character.UnicodeScript.HAN);
        if (REFERENCES.matcher(query.input()).find()) {
            Map<String, Double> recentTerms = new TreeMap<>();
            for (String recent : query.recentUserMessages()) {
                queryWeights(recent).forEach((term, weight) -> recentTerms.merge(term, weight, Math::max));
            }
            recentTerms.keySet().removeAll(queryTerms.keySet());
            normalize(recentTerms, queryTerms.isEmpty() ? 1.0 : 0.35);
            queryTerms.putAll(recentTerms);
        }
        List<Set<String>> documentTerms = eligible.stream().map(e -> terms(e.content())).toList();
        Map<String, Double> weights = new TreeMap<>();
        queryTerms.forEach((term, importance) -> {
            long frequency = documentTerms.stream().filter(t -> t.contains(term)).count();
            weights.put(term, importance * (1 + Math.log((eligible.size() + 1.0) / (frequency + 1.0))));
        });
        double totalWeight = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        List<MemoryRecallResult.Match> candidates = new ArrayList<>();
        for (int i = 0; i < eligible.size(); i++) {
            MemoryEntry entry = eligible.get(i);
            Set<String> tokens = documentTerms.get(i);
            List<String> matches = weights.keySet().stream().filter(tokens::contains).toList();
            double score = totalWeight == 0 ? 0 : matches.stream().mapToDouble(weights::get).sum() / totalWeight;
            boolean identifierMatched = identifiers.isEmpty() || identifiers.stream().anyMatch(tokens::contains);
            if (entry.pinned() || (identifierMatched && score > 0 && score >= query.minScore())) {
                candidates.add(new MemoryRecallResult.Match(entry, entry.content(), score, matches, false));
            }
        }
        candidates.sort(Comparator.<MemoryRecallResult.Match, Boolean>comparing(m -> m.entry().pinned()).reversed()
                .thenComparing(Comparator.comparingDouble(MemoryRecallResult.Match::score).reversed())
                .thenComparing(m -> m.entry().scope()).thenComparing(m -> m.entry().id()));
        Set<String> contents = new HashSet<>();
        List<MemoryRecallResult.Match> selected = new ArrayList<>();
        boolean budgetLimited = false;
        for (var candidate : candidates) {
            if (selected.size() >= query.maxEntries()) break;
            String normalized = candidate.entry().content().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
            if (contents.contains(normalized)) continue;
            var chosen = candidate;
            if (!fits(selected, chosen, query.tokenBudget())) {
                budgetLimited = true;
                chosen = excerpt(candidate, selected, weights, query.tokenBudget());
            }
            if (chosen != null) {
                selected.add(chosen);
                contents.add(normalized);
            }
        }
        return MemoryRecallResult.of(selected, false, budgetLimited, List.of());
    }

    private static boolean fits(List<MemoryRecallResult.Match> selected, MemoryRecallResult.Match candidate, int budget) {
        var proposed = new ArrayList<>(selected);
        proposed.add(candidate);
        return MemoryRecallResult.tokens(proposed) <= budget;
    }

    private static MemoryRecallResult.Match excerpt(MemoryRecallResult.Match candidate,
            List<MemoryRecallResult.Match> selected, Map<String, Double> weights, int budget) {
        // 优先选择命中最多的完整段落；段落自身过长就跳过，不截取开头。
        String[] paragraphs = candidate.entry().content().split("\\R\\s*\\R");
        List<String> ranked = Arrays.stream(paragraphs).map(String::trim).filter(s -> !s.isEmpty())
                .filter(s -> candidate.matchedTerms().stream().anyMatch(terms(s)::contains))
                .sorted(Comparator.comparingDouble((String p) -> terms(p).stream().mapToDouble(t -> weights.getOrDefault(t, 0.0)).sum()).reversed())
                .toList();
        for (String paragraph : ranked) {
            var match = new MemoryRecallResult.Match(candidate.entry(), paragraph, candidate.score(), candidate.matchedTerms(), true);
            if (fits(selected, match, budget)) return match;
        }
        return null;
    }

    private static Set<String> terms(String text) {
        return queryWeights(text).keySet();
    }

    private static void normalize(Map<String, Double> weights, double total) {
        double sum = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum > 0) weights.replaceAll((term, weight) -> weight / sum * total);
    }

    private static Map<String, Double> queryWeights(String text) {
        var result = new TreeMap<String, Double>();
        String normalized = CHINESE_STOP.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ");
        var matcher = TERMS.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            if (Character.UnicodeScript.of(token.codePointAt(0)) == Character.UnicodeScript.HAN) {
                var fragments = new TreeSet<String>();
                for (int length : new int[]{2, 3}) {
                    for (int i = 0; i + length <= token.length(); i++) fragments.add(token.substring(i, i + length));
                }
                // 一个连续中文片段总权重为一，避免重叠片段稀释技术标识符。
                for (String fragment : fragments) result.merge(fragment, 1.0 / fragments.size(), Math::max);
            } else if (!STOP.contains(token)) result.put(token, 1.0);
        }
        return result;
    }
}
