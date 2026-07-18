package com.chestmaster.integration

import com.chestmaster.gui.ChestMasterConfigScreen
import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi

/**
 * Mod Menu entrypoint (declared as "modmenu" in fabric.mod.json).
 * Only loaded when Mod Menu is installed, so the compile-only dependency is safe.
 */
class ModMenuIntegration : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> =
        ConfigScreenFactory { parent -> ChestMasterConfigScreen(parent) }
}
