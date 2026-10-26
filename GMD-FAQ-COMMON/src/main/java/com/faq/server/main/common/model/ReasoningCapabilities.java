package com.gmd.faq.server.main.common.model;

import java.util.List;

public record ReasoningCapabilities(String profile, boolean canEnable, boolean canDisable,
        List<String> efforts, Integer minBudget, Integer maxBudget, boolean summary, String hint) {}
