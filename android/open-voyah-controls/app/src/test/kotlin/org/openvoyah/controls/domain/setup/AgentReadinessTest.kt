package org.openvoyah.controls.domain.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvoyah.controls.R

class AgentReadinessTest {
    private fun probe(vararg lines: String) = lines.joinToString("\n")

    @Test
    fun parsesOneLinePerChannelAndIgnoresOtherOutput() {
        val reports = AgentReadiness.parse(probe(
            "Authenticated local root channel",
            "OVC_AGENT\tmain\tready\t",
            "OVC_AGENT\twindow\tstarting\tOVC_WINDOW_ERROR stage=install kind=completion_signature\r",
            "OVC_AGENT\tlocale-hiboard\twaiting_target\t",
            "OVC_AGENT\tcan\tunknown_phase\t",
            "OVC_AGENT\tbroken",
        ))
        assertEquals(listOf("main", "window", "locale-hiboard"), reports.map { it.channel })
        assertEquals(AgentReadiness.Phase.STARTING, reports[1].phase)
        assertEquals("OVC_WINDOW_ERROR stage=install kind=completion_signature", reports[1].detail)
    }

    @Test
    fun detailIsBoundedAndHasNoControlCharacters() {
        val report = AgentReadiness.parse("OVC_AGENT\tmain\tstarting\t" + "x".repeat(400) + "\u0007").single()
        assertEquals(160, report.detail.length)
        assertTrue(report.detail.all { it == 'x' })
    }

    @Test
    fun readyAgentsCompleteAndATranslationWaitingForItsAppDoesNotBlock() {
        val verdict = AgentReadiness.evaluate(AgentReadiness.parse(probe(
            "OVC_AGENT\tmain\tready\t",
            "OVC_AGENT\tlocale-bluetooth\twaiting_target\t",
        )), startedAt = 1_000, now = 9_000)
        assertTrue(verdict.complete)
        assertFalse(verdict.failed)
        assertEquals(StepState.Done(8_000), verdict.agents[SetupStepId.AGENT_LAUNCHER])
        assertEquals(StepState.Attention(StepNote(R.string.setup_agent_waiting_app)), verdict.agents[SetupStepId.AGENT_LOCALE_BLUETOOTH])
    }

    @Test
    fun aMissingWindowTargetKeepsWaitingUntilTheLimit() {
        val reports = AgentReadiness.parse("OVC_AGENT\twindow\twaiting_target\t")
        val waiting = AgentReadiness.evaluate(reports, startedAt = 0, now = 10_000)
        assertFalse(waiting.complete)
        assertEquals(StepState.Running(0, StepNote(R.string.setup_agent_waiting_target)), waiting.agents[SetupStepId.AGENT_WINDOW])
        val late = AgentReadiness.evaluate(reports, startedAt = 0, now = 75_000, timedOut = true)
        assertEquals(StepState.Failed(75_000, StepNote(R.string.setup_agent_target_missing, 75L)), late.agents[SetupStepId.AGENT_WINDOW])
    }

    @Test
    fun aStoppedSupervisorGetsAGraceThenFailsWithItsLastLine() {
        val reports = AgentReadiness.parse("OVC_AGENT\tcan\tstopped\tOVC_WORKER_ERROR Retry limit reached for this boot.")
        val early = AgentReadiness.evaluate(reports, startedAt = 0, now = 3_000)
        assertEquals(StepState.Running(0, StepNote(R.string.setup_agent_worker_starting)), early.agents[SetupStepId.AGENT_CAN])
        val late = AgentReadiness.evaluate(reports, startedAt = 0, now = AgentReadiness.WORKER_START_GRACE_MS)
        assertTrue(late.failed)
        assertEquals(StepState.Failed(AgentReadiness.WORKER_START_GRACE_MS,
            StepNote(R.string.setup_agent_stopped_detail, "OVC_WORKER_ERROR Retry limit reached for this boot.")),
            late.agents[SetupStepId.AGENT_CAN])
    }

    @Test
    fun aStartingAgentShowsItsLatestErrorAndFailsWithItOnTimeout() {
        val reports = AgentReadiness.parse("OVC_AGENT\tmain\tstarting\tOVC_PANEL_ERROR stage=click kind=SecurityException")
        val running = AgentReadiness.evaluate(reports, startedAt = 0, now = 5_000)
        assertEquals(StepState.Running(0, StepNote(R.string.setup_agent_error_detail, "OVC_PANEL_ERROR stage=click kind=SecurityException")),
            running.agents[SetupStepId.AGENT_LAUNCHER])
        val timedOut = AgentReadiness.evaluate(reports, startedAt = 0, now = 74_600, timedOut = true)
        assertEquals(StepState.Failed(74_600, StepNote(R.string.setup_agent_timeout_detail, 75L, "OVC_PANEL_ERROR stage=click kind=SecurityException")),
            timedOut.agents[SetupStepId.AGENT_LAUNCHER])
    }

    @Test
    fun aRowThatWasDoneKeepsItsTime() {
        val reports = AgentReadiness.parse("OVC_AGENT\tnavigation\tready\t")
        val first = AgentReadiness.evaluate(reports, startedAt = 0, now = 4_000)
        val second = AgentReadiness.evaluate(reports, startedAt = 0, now = 9_000, previous = first.agents)
        assertEquals(StepState.Done(4_000), second.agents[SetupStepId.AGENT_NAVIGATION])
    }

    @Test
    fun noReportIsNotComplete() {
        assertFalse(AgentReadiness.evaluate(emptyList(), 0, 1_000).complete)
    }

    @Test
    fun everyProbeChannelHasARow() {
        for (channel in listOf("main", "locale-setting", "locale-vehicle", "locale-bluetooth", "locale-hiboard", "window", "navigation", "can")) {
            assertTrue(channel, AgentReadiness.step(channel)?.agent == true)
        }
    }
}
