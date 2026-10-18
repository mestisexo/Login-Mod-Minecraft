package com.lm.loginmod;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.loading.FMLPaths;

public class AuthManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("loginmod-users.json");
    private static final int ITERATIONS = 120_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    // ---------- Límites ----------
    private static final int HASH_THREADS =
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    private static final int HASH_QUEUE_SIZE = 32;
    private static final int MAX_IP_RECORDS = 10_000;
    private static final long IP_RECORD_TTL_MS = 10 * 60 * 1000L; // 10 min sin actividad

    // Hilos para el hash (no bloquean el hilo principal del servidor).
    // Cola limitada: si se llena, se rechazan las tareas nuevas en vez de acumularlas.
    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(
            HASH_THREADS, HASH_THREADS,
            30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(HASH_QUEUE_SIZE),
            r -> {
                Thread t = new Thread(r, "loginmod-hash");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    static {
        POOL.allowCoreThreadTimeOut(true);
    }

    // Hilo único para escribir el archivo de usuarios
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "loginmod-io");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean saveQueued = new AtomicBoolean(false);
    private static final Object FILE_LOCK = new Object();

    // nombre en minúsculas -> "iteraciones:sal:hash"
    private static final Map<String, String> users = new ConcurrentHashMap<>();
    // jugadores que aún NO se han autenticado
    private static final Map<UUID, Session> pending = new ConcurrentHashMap<>();
    // jugadores con una operación de hash en curso
    private static final Set<UUID> working = ConcurrentHashMap.newKeySet();
    // control de fallos por IP
    private static final Map<String, IpRecord> ips = new ConcurrentHashMap<>();

    public static class Session {
        public final double x, y, z;
        public final float yRot, xRot;
        public final String ip;
        public int ticks = 0;
        public int attempts = 0;
        public boolean kicked = false;
        public volatile boolean active = true; // false cuando termina la sesión pendiente

        Session(ServerPlayer p) {
            this.x = p.getX();
            this.y = p.getY();
            this.z = p.getZ();
            this.yRot = p.getYRot();
            this.xRot = p.getXRot();
            this.ip = ipOf(p);
        }
    }

    private static class IpRecord {
        int fails = 0;
        long lockUntil = 0;
        long lastTouch = System.currentTimeMillis();
    }

    // ---------- Sesiones ----------
    public static void startSession(ServerPlayer p) {
        Session old = pending.put(p.getUUID(), new Session(p));
        if (old != null) old.active = false;
    }

    public static void endSession(ServerPlayer p) {
        Session s = pending.remove(p.getUUID());
        if (s != null) s.active = false;
    }

    public static Session session(Player p) {
        return pending.get(p.getUUID());
    }

    public static boolean isPending(Player p) {
        return pending.containsKey(p.getUUID());
    }

    public static Map<UUID, Session> pending() {
        return pending;
    }

    public static boolean tryLock(Player p) {
        return working.add(p.getUUID());
    }

    public static void unlock(Player p) {
        working.remove(p.getUUID());
    }

    // ---------- IP ----------
    public static String ipOf(ServerPlayer p) {
        SocketAddress a = p.connection.getRemoteAddress();
        if (a instanceof InetSocketAddress i && i.getAddress() != null) {
            return i.getAddress().getHostAddress();
        }
        return "local";
    }

    /** Segundos restantes de bloqueo para la IP (0 si no está bloqueada). */
    public static long ipLockRemaining(String ip) {
        IpRecord r = ips.get(ip);
        if (r == null) return 0;
        long left = r.lockUntil - System.currentTimeMillis();
        return left > 0 ? (left + 999) / 1000 : 0;
    }

    /** Registra un fallo. Devuelve true si la IP quedó bloqueada. */
    public static synchronized boolean ipFail(String ip) {
        long now = System.currentTimeMillis();

        if (ips.size() >= MAX_IP_RECORDS) {
            cleanupIps();
        }

        IpRecord r = ips.computeIfAbsent(ip, k -> new IpRecord());
        r.lastTouch = now;
        if (r.lockUntil != 0 && now >= r.lockUntil) {
            r.fails = 0;
            r.lockUntil = 0;
        }
        r.fails++;
        if (r.fails >= Config.IP_MAX_FAILS.get()) {
            r.fails = 0;
            r.lockUntil = now + Config.IP_LOCK_SECONDS.get() * 1000L;
            return true;
        }
        return false;
    }

    public static void ipSuccess(String ip) {
        ips.remove(ip);
    }

    /** Elimina registros de IP sin bloqueo activo y sin actividad reciente. */
    public static synchronized void cleanupIps() {
        long now = System.currentTimeMillis();
        ips.entrySet().removeIf(e -> {
            IpRecord r = e.getValue();
            boolean lockExpired = r.lockUntil == 0 || now >= r.lockUntil;
            return lockExpired && (now - r.lastTouch) > IP_RECORD_TTL_MS;
        });
    }

    // ---------- Usuarios ----------
    public static String key(ServerPlayer p) {
        return p.getGameProfile().getName().toLowerCase();
    }

    public static boolean isRegistered(String name) {
        return users.containsKey(name.toLowerCase());
    }

    /** Guarda un hash ya calculado (crear o cambiar contraseña). */
    public static void putHash(String name, String hash) {
        users.put(name.toLowerCase(), hash);
        saveAsync();
    }

    public static boolean unregister(String name) {
        boolean removed = users.remove(name.toLowerCase()) != null;
        if (removed) saveAsync();
        return removed;
    }

    // ---------- Operaciones asíncronas ----------
    /** true si la cola de hash está llena. */
    public static boolean isBusy() {
        return POOL.getQueue().remainingCapacity() == 0;
    }

    /** Si la cola está llena, devuelve un future que falla con RejectedExecutionException. */
    public static CompletableFuture<String> hashAsync(String password) {
        try {
            return CompletableFuture.supplyAsync(() -> hash(password), POOL);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Si la cola está llena, devuelve un future que falla con RejectedExecutionException. */
    public static CompletableFuture<Boolean> checkAsync(String name, String password) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                String stored = users.get(name.toLowerCase());
                return stored != null && verify(password, stored);
            }, POOL);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    // ---------- Archivo ----------
    public static void load() {
        synchronized (FILE_LOCK) {
            users.clear();
            if (!Files.exists(FILE)) return;
            try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                Map<String, String> data = GSON.fromJson(r, new TypeToken<Map<String, String>>() {}.getType());
                if (data != null) users.putAll(data);
                LoginMod.LOGGER.info("LoginMod: {} usuarios cargados", users.size());
            } catch (Exception e) {
                LoginMod.LOGGER.error("LoginMod: no se pudo leer {}", FILE, e);
            }
        }
    }

    /** Programa un guardado en segundo plano. Varios cambios seguidos se juntan en una sola escritura. */
    private static void saveAsync() {
        if (saveQueued.compareAndSet(false, true)) {
            try {
                IO.execute(() -> {
                    saveQueued.set(false); // los cambios posteriores programarán otra escritura
                    writeFile();
                });
            } catch (RejectedExecutionException e) {
                // IO ya fue cerrado (servidor apagándose): guardar directamente
                saveQueued.set(false);
                writeFile();
            }
        }
    }

    /** Guarda de forma síncrona. Llamar al apagar el servidor. */
    public static void flush() {
        writeFile();
    }

    /** Guarda y cierra los hilos. Llamar al apagar el servidor. */
    public static void shutdown() {
        IO.shutdown();
        try {
            IO.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        writeFile();
        POOL.shutdownNow();
    }

    private static void writeFile() {
        synchronized (FILE_LOCK) {
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            try {
                try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                    GSON.toJson(new HashMap<>(users), w);
                }
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LoginMod.LOGGER.error("LoginMod: no se pudo guardar {}", FILE, e);
            }
        }
    }

    // ---------- Hash ----------
    private static String hash(String password) {
        try {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            byte[] h = pbkdf2(password, salt, ITERATIONS);
            return ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt)
                    + ":" + Base64.getEncoder().encodeToString(h);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean verify(String password, String stored) {
        try {
            String[] p = stored.split(":");
            int iter = Integer.parseInt(p[0]);
            byte[] salt = Base64.getDecoder().decode(p[1]);
            byte[] expected = Base64.getDecoder().decode(p[2]);
            byte[] actual = pbkdf2(password, salt, iter);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iter) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iter, 256);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }
}