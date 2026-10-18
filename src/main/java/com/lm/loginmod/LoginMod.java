package com.lm.loginmod;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(LoginMod.MODID)
public class LoginMod {
    public static final String MODID = "loginmod";
    public static final Logger LOGGER = LogUtils.getLogger();

    public LoginMod(ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(new AuthEvents());
        LogFilter.install();
        LOGGER.info("LoginMod cargado");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        AuthManager.load();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        AuthManager.flush();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LoginCommands.register(event.getDispatcher());
    }
}