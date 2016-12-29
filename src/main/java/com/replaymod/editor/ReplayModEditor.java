package com.replaymod.editor;

import com.replaymod.core.ReplayMod;
import com.replaymod.editor.handler.GuiHandler;
import com.replaymod.online.Setting;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.Logger;

@Mod(modid = ReplayModEditor.MOD_ID, useMetadata = true)
public class ReplayModEditor {
    public static final String MOD_ID = "replaymod-editor";

    @Mod.Instance(MOD_ID)
    public static ReplayModEditor instance;

    @Mod.Instance(ReplayMod.MOD_ID)
    private static ReplayMod core;

    public static Logger LOGGER;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ReplayModEditor.LOGGER = event.getModLog();

        core.getSettingsRegistry().register(Setting.class);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        new GuiHandler(this).register();
    }

    public ReplayMod getCore() {
        return core;
    }
}