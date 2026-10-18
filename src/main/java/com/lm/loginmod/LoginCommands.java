package com.lm.loginmod;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public class LoginCommands {

    private static final String BUSY_MSG = "El servidor está ocupado, intenta de nuevo en unos segundos.";
    private static final String CONSOLE_MSG = "Este comando solo puede usarse desde dentro del juego.";
    private static final String NO_SPACES_MSG = "La contraseña no puede contener espacios.";

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        // Un solo argumento "greedy": se separa por espacios dentro del código.
        // Así las contraseñas pueden llevar símbolos (@, !, #, etc.).
        d.register(Commands.literal("register")
                .then(Commands.argument("passwords", StringArgumentType.greedyString())
                        .executes(LoginCommands::doRegister)));

        d.register(Commands.literal("login")
                .then(Commands.argument("password", StringArgumentType.greedyString())
                        .executes(LoginCommands::doLogin)));

        d.register(Commands.literal("changepassword")
                .then(Commands.argument("passwords", StringArgumentType.greedyString())
                        .executes(LoginCommands::doChange)));

        d.register(Commands.literal("loginadmin")
                .requires(src -> src.hasPermission(3))
                .then(Commands.literal("reset")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(LoginCommands::doReset)))
                .then(Commands.literal("setpass")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("password", StringArgumentType.greedyString())
                                        .executes(LoginCommands::doSetPass)))));
    }

    // ---------- Utilidades ----------
    private static void msg(ServerPlayer p, String text) {
        p.sendSystemMessage(Component.literal(text));
    }

    /** Devuelve el jugador que ejecuta el comando, o null (avisando) si viene de la consola. */
    private static ServerPlayer playerOrNull(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer p = ctx.getSource().getPlayer();
        if (p == null) {
            ctx.getSource().sendFailure(Component.literal(CONSOLE_MSG));
        }
        return p;
    }

    /** Separa el argumento por espacios. Devuelve null si no hay exactamente 'expected' partes. */
    private static String[] splitArgs(CommandContext<CommandSourceStack> ctx, String argName, int expected) {
        String raw = StringArgumentType.getString(ctx, argName).trim();
        String[] parts = raw.split("\\s+");
        return (parts.length == expected && !parts[0].isEmpty()) ? parts : null;
    }

    private static boolean hasSpace(String s) {
        return s.chars().anyMatch(Character::isWhitespace);
    }

    /** true si el error (o su causa) es que la cola de hash estaba llena. */
    private static boolean isBusyError(Throwable err) {
        while (err != null) {
            if (err instanceof RejectedExecutionException) return true;
            err = err.getCause();
        }
        return false;
    }

    // ---------- /register ----------
    private static int doRegister(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer p = playerOrNull(ctx);
        if (p == null) return 0;

        String[] parts = splitArgs(ctx, "passwords", 2);
        if (parts == null) {
            msg(p, "Uso: /register <contraseña> <contraseña> (sin espacios dentro de la contraseña)");
            return 0;
        }
        String pass = parts[0];
        String confirm = parts[1];
        String name = AuthManager.key(p);
        AuthManager.Session s = AuthManager.session(p);

        if (s == null || s.kicked) {
            msg(p, "Ya estás autenticado.");
            return 0;
        }
        if (AuthManager.isRegistered(name)) {
            msg(p, "Ya estás registrado. Usa /login <contraseña>");
            return 0;
        }
        if (!pass.equals(confirm)) {
            msg(p, "Las contraseñas no coinciden.");
            return 0;
        }
        int min = Config.MIN_PASSWORD_LENGTH.get();
        if (pass.length() < min) {
            msg(p, "La contraseña debe tener al menos " + min + " caracteres.");
            return 0;
        }
        int max = Config.MAX_PASSWORD_LENGTH.get();
        if (pass.length() > max) {
            msg(p, "La contraseña no puede tener más de " + max + " caracteres.");
            return 0;
        }
        if (AuthManager.isBusy()) {
            msg(p, BUSY_MSG);
            return 0;
        }
        if (!AuthManager.tryLock(p)) {
            msg(p, "Espera, procesando tu solicitud...");
            return 0;
        }

        MinecraftServer server = ctx.getSource().getServer();
        AuthManager.hashAsync(pass).whenComplete((hash, err) -> server.execute(() -> {
            AuthManager.unlock(p);
            if (isBusyError(err)) {
                msg(p, BUSY_MSG);
                return;
            }
            if (err != null || hash == null) {
                msg(p, "Error interno al registrar. Intenta de nuevo.");
                return;
            }
            if (AuthManager.session(p) != s) return; // se desconectó mientras tanto
            AuthManager.putHash(name, hash);
            AuthManager.endSession(p);
            msg(p, "Registro exitoso. ¡Bienvenido!");
        }));
        return 1;
    }

    // ---------- /login ----------
    private static int doLogin(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer p = playerOrNull(ctx);
        if (p == null) return 0;

        String pass = StringArgumentType.getString(ctx, "password").trim();
        String name = AuthManager.key(p);
        AuthManager.Session s = AuthManager.session(p);

        if (s == null || s.kicked) {
            msg(p, "Ya estás autenticado.");
            return 0;
        }
        if (!AuthManager.isRegistered(name)) {
            msg(p, "No estás registrado. Usa /register <contraseña> <contraseña>");
            return 0;
        }
        if (pass.isEmpty() || hasSpace(pass)) {
            // Ninguna contraseña válida lleva espacios: no cuenta como intento
            msg(p, NO_SPACES_MSG);
            return 0;
        }
        long wait = AuthManager.ipLockRemaining(s.ip);
        if (wait > 0) {
            msg(p, "Tu IP está bloqueada temporalmente. Intenta en " + wait + " s.");
            return 0;
        }
        if (AuthManager.isBusy()) {
            msg(p, BUSY_MSG);
            return 0;
        }
        if (!AuthManager.tryLock(p)) {
            msg(p, "Espera, procesando tu solicitud...");
            return 0;
        }

        MinecraftServer server = ctx.getSource().getServer();
        AuthManager.checkAsync(name, pass).whenComplete((ok, err) -> server.execute(() -> {
            AuthManager.unlock(p);
            if (isBusyError(err)) {
                // No es una contraseña incorrecta: no cuenta como intento
                msg(p, BUSY_MSG);
                return;
            }
            if (err != null || ok == null) {
                msg(p, "Error interno al iniciar sesión. Intenta de nuevo.");
                return;
            }
            if (AuthManager.session(p) != s) return; // se desconectó mientras tanto
            finishLogin(p, s, ok);
        }));
        return 1;
    }

    private static void finishLogin(ServerPlayer p, AuthManager.Session s, boolean ok) {
        if (ok) {
            AuthManager.ipSuccess(s.ip);
            AuthManager.endSession(p);
            msg(p, "Sesión iniciada. ¡Bienvenido de vuelta!");
            return;
        }

        s.attempts++;
        boolean ipLocked = AuthManager.ipFail(s.ip);
        int maxAttempts = Config.MAX_ATTEMPTS.get();

        if (ipLocked || s.attempts >= maxAttempts) {
            s.kicked = true;
            p.connection.disconnect(Component.literal(ipLocked
                    ? "Demasiados intentos desde tu IP. Espera unos minutos."
                    : "Demasiados intentos fallidos."));
        } else {
            msg(p, "Contraseña incorrecta. Intentos restantes: " + (maxAttempts - s.attempts));
        }
    }

    // ---------- /changepassword ----------
    private static int doChange(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer p = playerOrNull(ctx);
        if (p == null) return 0;

        String[] parts = splitArgs(ctx, "passwords", 2);
        if (parts == null) {
            msg(p, "Uso: /changepassword <actual> <nueva> (sin espacios dentro de la contraseña)");
            return 0;
        }
        String oldPass = parts[0];
        String newPass = parts[1];
        String name = AuthManager.key(p);
        String ip = AuthManager.ipOf(p);

        if (AuthManager.isPending(p)) {
            msg(p, "Primero debes iniciar sesión.");
            return 0;
        }
        if (!AuthManager.isRegistered(name)) {
            msg(p, "No tienes una cuenta registrada.");
            return 0;
        }
        long wait = AuthManager.ipLockRemaining(ip);
        if (wait > 0) {
            msg(p, "Tu IP está bloqueada temporalmente por intentos fallidos. Intenta en " + wait + " s.");
            return 0;
        }
        int min = Config.MIN_PASSWORD_LENGTH.get();
        if (newPass.length() < min) {
            msg(p, "La nueva contraseña debe tener al menos " + min + " caracteres.");
            return 0;
        }
        int max = Config.MAX_PASSWORD_LENGTH.get();
        if (newPass.length() > max) {
            msg(p, "La nueva contraseña no puede tener más de " + max + " caracteres.");
            return 0;
        }
        if (oldPass.equals(newPass)) {
            msg(p, "La nueva contraseña debe ser distinta a la actual.");
            return 0;
        }
        if (AuthManager.isBusy()) {
            msg(p, BUSY_MSG);
            return 0;
        }
        if (!AuthManager.tryLock(p)) {
            msg(p, "Espera, procesando tu solicitud...");
            return 0;
        }

        MinecraftServer server = ctx.getSource().getServer();
        AuthManager.checkAsync(name, oldPass)
                .thenCompose(ok -> ok
                        ? AuthManager.hashAsync(newPass)
                        : CompletableFuture.completedFuture((String) null))
                .whenComplete((hash, err) -> server.execute(() -> {
                    AuthManager.unlock(p);
                    if (isBusyError(err)) {
                        msg(p, BUSY_MSG);
                        return;
                    }
                    if (err != null) {
                        msg(p, "Error interno al cambiar la contraseña.");
                        return;
                    }
                    if (hash == null) {
                        // Contraseña actual incorrecta: cuenta como fallo de IP
                        boolean locked = AuthManager.ipFail(ip);
                        msg(p, locked
                                ? "Demasiados intentos fallidos. Tu IP quedó bloqueada temporalmente."
                                : "La contraseña actual es incorrecta.");
                        return;
                    }
                    AuthManager.ipSuccess(ip);
                    AuthManager.putHash(name, hash);
                    msg(p, "Contraseña cambiada correctamente.");
                }));
        return 1;
    }

    // ---------- /loginadmin setpass ----------
    private static int doSetPass(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        String pass = StringArgumentType.getString(ctx, "password").trim();

        if (!AuthManager.isRegistered(name)) {
            src.sendFailure(Component.literal("'" + name + "' no tiene una cuenta registrada."));
            return 0;
        }
        if (hasSpace(pass)) {
            src.sendFailure(Component.literal(NO_SPACES_MSG));
            return 0;
        }
        int min = Config.MIN_PASSWORD_LENGTH.get();
        if (pass.length() < min) {
            src.sendFailure(Component.literal("La contraseña debe tener al menos " + min + " caracteres."));
            return 0;
        }
        int max = Config.MAX_PASSWORD_LENGTH.get();
        if (pass.length() > max) {
            src.sendFailure(Component.literal("La contraseña no puede tener más de " + max + " caracteres."));
            return 0;
        }
        if (AuthManager.isBusy()) {
            src.sendFailure(Component.literal(BUSY_MSG));
            return 0;
        }

        MinecraftServer server = src.getServer();
        AuthManager.hashAsync(pass).whenComplete((hash, err) -> server.execute(() -> {
            if (isBusyError(err)) {
                src.sendFailure(Component.literal(BUSY_MSG));
                return;
            }
            if (err != null || hash == null) {
                src.sendFailure(Component.literal("Error interno al guardar la contraseña."));
                return;
            }
            if (!AuthManager.isRegistered(name)) { // la cuenta se borró mientras tanto
                src.sendFailure(Component.literal("'" + name + "' ya no tiene una cuenta registrada."));
                return;
            }
            AuthManager.putHash(name, hash);
            src.sendSuccess(() -> Component.literal("Contraseña de '" + name + "' actualizada."), true);
        }));
        return 1;
    }

    // ---------- /loginadmin reset ----------
    private static int doReset(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        boolean ok = AuthManager.unregister(name);
        ctx.getSource().sendSuccess(() -> Component.literal(ok
                ? "Cuenta de '" + name + "' eliminada. Deberá usar /register de nuevo."
                : "'" + name + "' no estaba registrado."), true);
        return ok ? 1 : 0;
    }
}