package com.xhlcli.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlanReviewInputParserTest {

    @Test
    void blankInputMeansExecute() {
        PlanReviewInputParser.Decision decision = PlanReviewInputParser.parse("   ");

        assertEquals(PlanReviewInputParser.DecisionType.EXECUTE, decision.type());
        assertNull(decision.feedback());
    }

    @Test
    void executeKeywordsMeanExecute() {
        assertEquals(PlanReviewInputParser.DecisionType.EXECUTE, PlanReviewInputParser.parse("y").type());
        assertEquals(PlanReviewInputParser.DecisionType.EXECUTE, PlanReviewInputParser.parse("yes").type());
        assertEquals(PlanReviewInputParser.DecisionType.EXECUTE, PlanReviewInputParser.parse("run").type());
        assertEquals(PlanReviewInputParser.DecisionType.EXECUTE, PlanReviewInputParser.parse("/run").type());
    }

    @Test
    void cancelInputMeansCancel() {
        PlanReviewInputParser.Decision decision = PlanReviewInputParser.parse("/cancel");

        assertEquals(PlanReviewInputParser.DecisionType.CANCEL, decision.type());
        assertNull(decision.feedback());

        assertEquals(PlanReviewInputParser.DecisionType.CANCEL, PlanReviewInputParser.parse("esc").type());
        assertEquals(PlanReviewInputParser.DecisionType.CANCEL, PlanReviewInputParser.parse("cancel").type());
    }

    @Test
    void escapeInputMeansCancel() {
        PlanReviewInputParser.Decision decision = PlanReviewInputParser.parse("\u001B");

        assertEquals(PlanReviewInputParser.DecisionType.CANCEL, decision.type());
        assertNull(decision.feedback());
    }

    @Test
    void normalTextMeansSupplement() {
        PlanReviewInputParser.Decision decision = PlanReviewInputParser.parse("请先加一个 README 检查步骤");

        assertEquals(PlanReviewInputParser.DecisionType.SUPPLEMENT, decision.type());
        assertEquals("请先加一个 README 检查步骤", decision.feedback());
    }
}
