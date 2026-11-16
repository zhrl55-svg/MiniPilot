package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings IA after the 2026-11 cleanup:
 *
 *  - the main list is six grouped sections, not twelve (Agent Runtime and
 *    Message context each collapse to one row that opens a dedicated page);
 *  - model configuration reads in dependency order — provider → group →
 *    default role — and the three concepts never share one screen;
 *  - model groups are pure ordered routes; role bindings live on Default
 *    Models; the agent's runtime set lives under Agent Runtime.
 *
 * These are source-shape assertions because the screens are Compose
 * composables and the repo's unit tests do not run them; the point is to
 * keep the next row from silently rejoining the main list.
 */
class SettingsInformationArchitectureTest {

    private val settings = ProductionSources.read("ui/settings/SettingsScreen.kt")
    private val assistant = ProductionSources.read("ui/settings/AssistantSettingsScreen.kt")
    private val contextPage = ProductionSources.read("ui/settings/ContextSettingsScreen.kt")
    private val groups = ProductionSources.read("ui/settings/ModelGroupsScreen.kt")
    private val defaults = ProductionSources.read("ui/settings/ModelDefaultsScreen.kt")
    private val runtime = ProductionSources.read("ui/settings/RuntimeModelsScreen.kt")
    private val nav = ProductionSources.read("ui/navigation/AppNavigation.kt")

    @Test
    fun `main settings is six sections and delegates the long ones`() {
        assertEquals(
            "AI, General, Storage, Network, Device, Support",
            6,
            settings.substringBefore("internal fun SettingsSection(").split("SettingsSection(").size - 1,
        )
        assertTrue(settings.contains("R.string.settings_section_ai_assistant"))
        assertTrue(settings.contains("R.string.settings_section_general"))
        assertTrue(settings.contains("R.string.settings_section_device"))
        assertTrue(settings.contains("R.string.settings_section_support"))
        // The nine Agent Runtime rows and the seven context switches are no
        // longer inline; only their two entry rows remain.
        for (moved in listOf(
            "onAgentToolsClick", "onAgentsClick", "onSkillsClick", "onSoulClick",
            "onMemoryClick", "onMcpClick", "onEnvVarsClick", "onAssistantRoutesClick",
            "settings_context_time", "settings_context_battery", "settings_context_screen_text",
        )) {
            assertFalse("$moved must live on its own page", settings.contains(moved))
        }
        for (entry in listOf("onAgentRuntimeClick", "onDefaultModelsClick", "onContextClick")) {
            assertTrue(entry, settings.contains(entry))
        }
    }

    @Test
    fun `model configuration reads provider then group then role`() {
        val providers = settings.indexOf("onProvidersClick")
        val modelGroups = settings.indexOf("onModelGroupsClick")
        val roles = settings.indexOf("onDefaultModelsClick")
        assertTrue(providers in 0 until modelGroups && modelGroups < roles)
        assertTrue(settings.contains("R.string.settings_section_llm_providers_footer"))
    }

    @Test
    fun `model groups are routes, roles and runtime sets have their own pages`() {
        // A group screen is only the collection now. defaultPrimaryGroupId /
        // defaultSubGroupId still appear as the Primary/Sub badges on a row
        // (plus auto-binding the first group as Primary).
        assertFalse(groups.contains("voiceInputGroupId"))
        assertFalse(groups.contains("agentLoopModelEntryIds"))
        assertFalse(groups.contains("GroupDropdown("))
        assertTrue(groups.contains("R.string.model_groups_intro"))

        // Binding every role happens in exactly one place.
        for (binding in listOf(
            "defaultPrimaryGroupId", "defaultSubGroupId", "voiceInputGroupId",
            "voiceOutputGroupId", "visionGroupId",
        )) {
            assertTrue(binding, defaults.contains(binding))
        }
        assertTrue(defaults.contains("R.string.model_defaults_intro"))

        // The agent's runtime set is its own page under Agent Runtime.
        assertTrue(runtime.contains("agentLoopModelEntryIds"))
        assertTrue(runtime.contains("agentLoopGroupIds"))
        assertTrue(runtime.contains("R.string.runtime_models_intro"))
        assertTrue(assistant.contains("onRuntimeModelsClick"))
    }

    @Test
    fun `agent runtime and message context keep every moved row`() {
        for (row in listOf(
            "onAgentToolsClick", "onAgentsClick", "onSkillsClick", "onSoulClick",
            "onMemoryClick", "onMcpClick", "onEnvVarsClick", "onAssistantRoutesClick",
            "settings_assistant_mode",
        )) {
            assertTrue("$row missing from the Agent Runtime hub", assistant.contains(row))
        }
        for (row in listOf(
            "contextConfig.time", "contextConfig.battery", "contextConfig.weather",
            "contextConfig.location", "contextConfig.notifications",
            "contextConfig.currentApp", "contextConfig.screenText",
        )) {
            assertTrue("$row missing from Message context", contextPage.contains(row))
        }
    }

    @Test
    fun `every hub page has a route`() {
        for (route in listOf(
            "ASSISTANT_SETTINGS", "CONTEXT_SETTINGS", "MODEL_DEFAULTS", "RUNTIME_MODELS",
        )) {
            assertTrue("$route has no route constant", nav.contains("const val $route"))
            assertTrue("$route has no composable", nav.contains("composable(Routes.$route)"))
        }
    }
}
