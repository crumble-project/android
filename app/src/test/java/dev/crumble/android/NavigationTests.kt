package dev.crumble.android

import dev.crumble.android.model.Route
import dev.crumble.android.model.SETTINGS_PAGES
import dev.crumble.android.model.WORKSPACE_PAGES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTests {
    @Test
    fun theWorkspaceHoldsOnlyTheWebsiteSidebarPages() {
        assertEquals(listOf(Route.Memory, Route.Tasks, Route.Activity), WORKSPACE_PAGES)
        assertTrue(WORKSPACE_PAGES.none { it in SETTINGS_PAGES })
    }

    @Test
    fun settingsPagesReturnToTheSettingsList() {
        SETTINGS_PAGES.forEach { assertEquals(Route.Settings, it.parent) }
        assertEquals(Route.MessageGateways, Route.MessageGateway("telegram").parent)
    }

    @Test
    fun otherPagesReturnHome() {
        (WORKSPACE_PAGES + Route.Settings).forEach { assertNull(it.parent) }
    }
}
