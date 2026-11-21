package org.openvoyah.controls.presentation.theme

import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchTimelineTest {
    private val stages = with(LaunchTimeline) {
        listOf(
            WINGS_START to WINGS_OPEN, WINGS_START to WINGS_VISIBLE, BODY_START to BODY_VISIBLE,
            TITLE_START to TITLE_VISIBLE, RULE_START to RULE_DRAWN, CAPTION_START to CAPTION_VISIBLE,
            GLINT_START to GLINT_END, CHARGE_START to CHARGE_FULL, CHARGE_FULL to CHARGE_FADE,
            CHARGE_FADE to CHARGE_END, PULSE_START to PULSE_END, EXIT_START to 1f,
        )
    }

    private fun millis(fraction: Float) = fraction * LaunchTimeline.DURATION_MILLIS

    @Test fun everyStageIsFiniteAndStartsAfterAPlainFirstFrame() {
        for ((start, end) in stages) assertTrue("$start..$end", start in 0f..1f && end in 0f..1f && start < end)
        assertTrue(stages.all { (start) -> start >= LaunchTimeline.WINGS_START })
    }

    @Test fun finishedMarkHoldsLongEnoughToBeNoticedBeforeItLeaves() {
        val entranceEnd = stages.filter { (start) -> start < LaunchTimeline.EXIT_START }.maxOf { (_, end) -> end }
        assertTrue("the entrance must finish before the exit", entranceEnd < LaunchTimeline.EXIT_START)
        assertTrue("the entrance keeps its pace", millis(entranceEnd) <= 1_800f)
        assertTrue("the resting mark holds for at least two seconds",
            millis(LaunchTimeline.EXIT_START - entranceEnd) >= 2_000f)
        assertTrue("the exit stays a short fade", millis(1f - LaunchTimeline.EXIT_START) in 250f..400f)
        assertTrue("the intro does not hold the screen for long", LaunchTimeline.DURATION_MILLIS <= 5_000)
    }
}
