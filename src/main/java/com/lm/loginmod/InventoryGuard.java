package com.lm.loginmod;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;

/** Descarta los paquetes de inventario/hotbar de jugadores que aún no han iniciado sesión. */
public class InventoryGuard extends ChannelInboundHandlerAdapter {
    public static final String NAME = "loginmod_guard";

    /** Paquetes bloqueados por segundo a partir de los cuales se expulsa al jugador. */
    private static final int MAX_BLOCKED_PER_SECOND = 200;

    // Correcciones pendientes (máscara de bits)
    private static final int FIX_CONTAINER = 1;
    private static final int FIX_HOTBAR = 2;
    private static final int FIX_SWAP = 4;

    // No se guarda el ServerPlayer: solo el servidor, el UUID y la sesión
    private final MinecraftServer server;
    private final UUID id;
    private final AuthManager.Session session;

    private final AtomicInteger pendingFixes = new AtomicInteger();

    // Solo se tocan desde el hilo de Netty de este canal (no necesitan sincronización)
    private long windowStart = 0;
    private int windowCount = 0;
    private boolean kickQueued = false;

    public InventoryGuard(MinecraftServer server, UUID id, AuthManager.Session session) {
        this.server = server;
        this.id = id;
        this.session = session;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // Ya autenticado: el handler se quita solo y deja pasar el paquete
        if (!session.active) {
            ctx.pipeline().remove(this);
            super.channelRead(ctx, msg);
            return;
        }

        int fix = Compat.classify(msg);
        if (fix == 0) {
            super.channelRead(ctx, msg);
            return;
        }

        ReferenceCountUtil.release(msg);
        onBlocked(fix);
    }

    private void onBlocked(int fix) {
        if (kickQueued) return;

        // Antispam: demasiados paquetes bloqueados en 1 segundo = cliente anómalo
        long now = System.nanoTime();
        if (now - windowStart > 1_000_000_000L) {
            windowStart = now;
            windowCount = 0;
        }
        if (++windowCount > MAX_BLOCKED_PER_SECOND) {
            kickQueued = true;
            server.execute(this::kick);
            return;
        }

        // Solo se encola una tarea mientras haya correcciones sin ejecutar
        int prev = pendingFixes.getAndUpdate(v -> v | fix);
        if (prev == 0) {
            server.execute(this::runFixes);
        }
    }

    // ---------- Hilo principal del servidor ----------
    private void runFixes() {
        int mask = pendingFixes.getAndSet(0);
        if (mask == 0 || !session.active) return;

        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p == null) return;

        if ((mask & FIX_CONTAINER) != 0) {
            p.closeContainer();
            p.containerMenu.sendAllDataToRemote();
        }
        if ((mask & FIX_HOTBAR) != 0) {
            Compat.resendSelectedSlot(p);
        }
        if ((mask & FIX_SWAP) != 0) {
            p.inventoryMenu.sendAllDataToRemote();
        }
    }

    private void kick() {
        if (!session.active) return;
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p == null) return;
        session.kicked = true;
        p.connection.disconnect(Component.literal("Demasiados paquetes de inventario."));
    }

    // =====================================================================
    // Todo lo que depende de la versión de Minecraft está AQUÍ.
    // Si una actualización rompe la compilación, solo hay que tocar esta clase.
    // =====================================================================
    private static final class Compat {
        private static final MethodHandle SELECTED_SLOT = resolveSelectedSlot();

        static int classify(Object msg) {
            if (msg instanceof ServerboundContainerClickPacket
                    || msg instanceof ServerboundSetCreativeModeSlotPacket) {
                return FIX_CONTAINER;
            }
            if (msg instanceof ServerboundSetCarriedItemPacket) {
                return FIX_HOTBAR;
            }
            if (msg instanceof ServerboundPlayerActionPacket a
                    && a.getAction() == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND) {
                return FIX_SWAP;
            }
            return 0;
        }

        static void resendSelectedSlot(ServerPlayer p) {
            int slot = selectedSlot(p.getInventory());
            if (slot >= 0) {
                p.connection.send(new ClientboundSetCarriedItemPacket(slot));
            }
        }

        private static int selectedSlot(Inventory inv) {
            if (SELECTED_SLOT == null) return -1;
            try {
                return (int) SELECTED_SLOT.invoke(inv);
            } catch (Throwable t) {
                return -1;
            }
        }

        /** Minecraft 1.21.5+ usa getSelectedSlot(); las anteriores, el campo "selected". */
        private static MethodHandle resolveSelectedSlot() {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            try {
                return lookup.findVirtual(Inventory.class, "getSelectedSlot",
                        MethodType.methodType(int.class));
            } catch (ReflectiveOperationException ignored) {
                // probar con el campo
            }
            try {
                return lookup.findGetter(Inventory.class, "selected", int.class);
            } catch (ReflectiveOperationException e) {
                LoginMod.LOGGER.warn("LoginMod: no se pudo leer la ranura de la hotbar; "
                        + "el scroll se bloquea pero no se restaura el selector", e);
                return null;
            }
        }
    }
}