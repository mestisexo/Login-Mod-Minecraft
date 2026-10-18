package com.lm.loginmod;

import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;

/** Oculta de los logs los comandos que contienen contraseñas. */
public class LogFilter extends AbstractFilter {
    private static final String CMDS = "(login|register|changepassword|loginadmin\\s+setpass)";

    // "Steve issued server command: /login clave"
    private static final Pattern ISSUED = Pattern.compile(
            "(?i)issued server command:\\s*/?" + CMDS + "\\b");

    // Mensajes de error de vanilla que repiten el comando completo
    private static final Pattern IN_ERROR = Pattern.compile(
            "(?i)/" + CMDS + "\\b");

    public static void install() {
        try {
            LogFilter filter = new LogFilter();
            filter.start();
            ((Logger) LogManager.getRootLogger()).addFilter(filter);
            LoginMod.LOGGER.info("LoginMod: filtro de logs instalado");
        } catch (Throwable t) {
            LoginMod.LOGGER.warn("LoginMod: no se pudo instalar el filtro de logs", t);
        }
    }

    @Override
    public Result filter(LogEvent event) {
        if (event == null || event.getMessage() == null) return Result.NEUTRAL;
        String msg = event.getMessage().getFormattedMessage();
        if (msg == null || msg.indexOf('/') < 0 && !msg.contains("command")) return Result.NEUTRAL;

        if (ISSUED.matcher(msg).find()) return Result.DENY;

        // Errores de comando: solo si el mensaje habla de una excepción
        String lower = msg.toLowerCase();
        if ((lower.contains("exception") || lower.contains("command")) && IN_ERROR.matcher(msg).find()) {
            return Result.DENY;
        }
        return Result.NEUTRAL;
    }
}