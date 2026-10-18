package com.lm.loginmod;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public class AuthEvents {

    /** Cada cuántos ticks se revisa el movimiento de un jugador pendiente. */
    private static final int FREEZE_CHECK_INTERVAL = 4;
    /** Cada cuántos ticks se limpian los registros viejos de IP (6000 = 5 min). */
    private static final int IP_CLEANUP_INTERVAL = 6000;

    private int serverTicks = 0;

    private static boolean blocked(Player p) {
        return p instanceof ServerPlayer && AuthManager.isPending(p);
    }

    private static String hint(ServerPlayer p) {
        return AuthManager.isRegistered(AuthManager.key(p))
                ? "Inicia sesión con: /login <contraseña>"
                : "Regístrate con: /register <contraseña> <contraseña>";
    }

    // ---------- Entrada / salida ----------
    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;

        AuthManager.startSession(p);
        AuthManager.Session s = AuthManager.session(p);

        long wait = AuthManager.ipLockRemaining(s.ip);
        if (wait > 0) {
            s.kicked = true;
            p.connection.disconnect(Component.literal(
                    "Tu IP está bloqueada temporalmente por intentos fallidos. Espera " + ((wait + 59) / 60) + " min."));
            return;
        }

        // Bloqueo de clics de inventario
        try {
            var pipeline = p.connection.getConnection().channel().pipeline();
            if (pipeline.get(InventoryGuard.NAME) == null) {
                pipeline.addBefore("packet_handler", InventoryGuard.NAME,
                        new InventoryGuard(p.server, p.getUUID(), s));
            }
        } catch (Throwable t) {
            LoginMod.LOGGER.warn("LoginMod: no se pudo instalar el bloqueo de inventario", t);
        }

        p.sendSystemMessage(Component.literal(hint(p)));
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            AuthManager.endSession(p);
        }
    }

    // ---------- Un solo tick para todos los pendientes ----------
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post e) {
        // Limpieza periódica de IPs (corre aunque no haya jugadores pendientes)
        if (++serverTicks >= IP_CLEANUP_INTERVAL) {
            serverTicks = 0;
            AuthManager.cleanupIps();
        }

        Map<UUID, AuthManager.Session> pending = AuthManager.pending();
        if (pending.isEmpty()) return;

        int timeoutSeconds = Config.LOGIN_TIMEOUT_SECONDS.get();
        int timeoutTicks = timeoutSeconds * 20;

        Iterator<Map.Entry<UUID, AuthManager.Session>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, AuthManager.Session> entry = it.next();
            AuthManager.Session s = entry.getValue();
            if (s.kicked) continue;

            ServerPlayer p = e.getServer().getPlayerList().getPlayer(entry.getKey());
            if (p == null) {
                it.remove();
                continue;
            }

            s.ticks++;

            // Cierra chests, hornos, etc. (el inventario propio no es un contenedor externo)
            if (p.containerMenu != p.inventoryMenu) {
                p.closeContainer();
            }

            // Congelado: se revisa cada N ticks, escalonado por jugador para repartir la carga
            if ((s.ticks + p.getId()) % FREEZE_CHECK_INTERVAL == 0
                    && p.distanceToSqr(s.x, s.y, s.z) > 0.25) {
                p.connection.teleport(s.x, s.y, s.z, s.yRot, s.xRot);
            }

            // Action bar con cuenta regresiva
            if (s.ticks % 40 == 0) {
                int left = Math.max(timeoutSeconds - s.ticks / 20, 0);
                p.displayClientMessage(Component.literal(hint(p) + "  (" + left + "s)"), true);
            }

            // Tiempo límite
            if (s.ticks >= timeoutTicks) {
                s.kicked = true;
                p.connection.disconnect(Component.literal("Tiempo agotado para iniciar sesión."));
            }
        }
    }

    // ---------- Chat y comandos ----------
    @SubscribeEvent
    public void onChat(ServerChatEvent e) {
        if (blocked(e.getPlayer())) {
            e.setCanceled(true);
            e.getPlayer().sendSystemMessage(Component.literal("Debes iniciar sesión para chatear."));
        }
    }

    @SubscribeEvent
    public void onCommand(CommandEvent e) {
        var source = e.getParseResults().getContext().getSource();
        if (!(source.getEntity() instanceof Player p) || !blocked(p)) return;

        String cmd = e.getParseResults().getReader().getString().trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        String first = cmd.split("\\s+")[0].toLowerCase();

        if (!first.equals("login") && !first.equals("register")) {
            e.setCanceled(true);
            p.sendSystemMessage(Component.literal("Solo puedes usar /login o /register."));
        }
    }

    // ---------- Acciones ----------
    @SubscribeEvent
    public void onBreak(BlockEvent.BreakEvent e) {
        if (blocked(e.getPlayer())) e.setCanceled(true);
    }

    @SubscribeEvent
    public void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (e.getEntity() instanceof Player p && blocked(p)) e.setCanceled(true);
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (blocked(e.getEntity())) e.setCanceled(true);
    }

    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem e) {
        if (blocked(e.getEntity())) e.setCanceled(true);
    }

    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock e) {
        if (blocked(e.getEntity())) e.setCanceled(true);
    }

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract e) {
        if (blocked(e.getEntity())) e.setCanceled(true);
    }

    @SubscribeEvent
    public void onAttack(AttackEntityEvent e) {
        if (blocked(e.getEntity())) e.setCanceled(true);
    }

    @SubscribeEvent
    public void onToss(ItemTossEvent e) {
        if (blocked(e.getPlayer())) e.setCanceled(true);
    }

    @SubscribeEvent
    public void onPickup(ItemEntityPickupEvent.Pre e) {
        if (blocked(e.getPlayer())) e.setCanPickup(TriState.FALSE);
    }

    @SubscribeEvent
    public void onDamage(LivingIncomingDamageEvent e) {
        if (e.getEntity() instanceof Player p && blocked(p)) e.setCanceled(true);
    }
}