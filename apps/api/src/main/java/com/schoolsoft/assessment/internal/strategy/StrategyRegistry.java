package com.schoolsoft.assessment.internal.strategy;

import com.schoolsoft.assessment.api.CurriculumStrategy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Resolves a {@code strategy_code} to the board's rules, falling back to plain
 * percentages. Implementations register themselves as beans, so adding a board
 * is one class and no edit here.
 */
@Service
public class StrategyRegistry {

    private final Map<String, CurriculumStrategy> byCode = new LinkedHashMap<>();
    private final CurriculumStrategy fallback;

    public StrategyRegistry(List<CurriculumStrategy> strategies, PercentageStrategy fallback) {
        for (CurriculumStrategy strategy : strategies) {
            byCode.put(strategy.strategyCode(), strategy);
        }
        this.fallback = fallback;
    }

    public CurriculumStrategy forCode(String strategyCode) {
        if (strategyCode == null) return fallback;
        CurriculumStrategy exact = byCode.get(strategyCode);
        if (exact != null) return exact;
        // A school on 'CBSE-CCE-2026' is still CBSE; match on the board prefix
        // before giving up, so a version bump does not silently change how a
        // cohort is graded.
        String prefix = strategyCode.contains("-") ? strategyCode.substring(0, strategyCode.indexOf('-')) : strategyCode;
        for (var entry : byCode.entrySet()) {
            if (entry.getKey().startsWith(prefix + "-")) return entry.getValue();
        }
        return fallback;
    }
}
