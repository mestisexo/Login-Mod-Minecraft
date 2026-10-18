package com.lm.loginmod;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue LOGIN_TIMEOUT_SECONDS = BUILDER
            .comment("Segundos que tiene el jugador para hacer login antes de ser expulsado")
            .defineInRange("loginTimeoutSeconds", 60, 10, 600);

    public static final ModConfigSpec.IntValue MAX_ATTEMPTS = BUILDER
            .comment("Intentos fallidos por sesion antes de expulsar al jugador")
            .defineInRange("maxAttempts", 3, 1, 20);

    public static final ModConfigSpec.IntValue MIN_PASSWORD_LENGTH = BUILDER
            .comment("Largo minimo de la contrasena")
            .defineInRange("minPasswordLength", 6, 4, 64);

    public static final ModConfigSpec.IntValue MAX_PASSWORD_LENGTH = BUILDER
            .comment("Largo maximo de la contrasena")
            .defineInRange("maxPasswordLength", 64, 8, 128);

    public static final ModConfigSpec.IntValue IP_MAX_FAILS = BUILDER
            .comment("Fallos de contrasena desde una misma IP antes de bloquearla")
            .defineInRange("ipMaxFails", 6, 1, 100);

    public static final ModConfigSpec.IntValue IP_LOCK_SECONDS = BUILDER
            .comment("Segundos que dura el bloqueo de una IP")
            .defineInRange("ipLockSeconds", 300, 10, 86400);

    static final ModConfigSpec SPEC = BUILDER.build();
}