package ru.playerskill.skydbwatchdog;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class SkyDbWatchdog extends JavaPlugin implements Listener {

    private enum State {
        NORMAL,   // база доступна, фоновые проверки
        OUTAGE,   // база недоступна, ожидание kick-after-seconds
        LOCKED    // игроки кикнуты, вайтлист включён
    }

    private volatile State state = State.NORMAL;
    private volatile long outageStartedAt;
    private volatile long recoveryStartedAt;
    private volatile boolean commandForcedOutage;

    private HikariDataSource dataSource;
    private ScheduledExecutorService checker;
    private Path logsDirectory;

    private long normalCheckSeconds;
    private long outageCheckSeconds;
    private long kickAfterSeconds;
    private long recoveryConfirmSeconds;

    private final Map<UUID, Boolean> originalWhitelistEntries = new HashMap<UUID, Boolean>();
    private boolean originalWhitelistEnabled;

    private Class<?> vaultEconomyClass;
    private Object vaultEconomyProvider;
    private Object vaultEconomyProxy;
    private volatile boolean updatingVaultEconomy;
    private volatile String economyBlockedMessage;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readConfig();
        logsDirectory = getDataFolder().toPath().resolve("logs");

        getServer().getPluginManager().registerEvents(this, this);
        hookVaultEconomy();

        try {
            dataSource = createDataSource();
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE,
                    "Ne udalos sozdat pul soedineniy; proverki prodolzhatsya, no budut provalivatsya.", exception);
        }

        checker = createChecker();

        getLogger().info("Monitoring bazy zapushchen; pervaya proverka cherez " + normalCheckSeconds + " sec.");
        scheduleCheck(normalCheckSeconds);
    }

    private void readConfig() {
        economyBlockedMessage = getConfig().getString("messages.economy-blocked",
                "&cОперации с валютой временно недоступны: сбой базы данных.");
        normalCheckSeconds = Math.max(1L, getConfig().getLong("checks.check-interval-seconds", 30L));
        outageCheckSeconds = Math.max(1L, getConfig().getLong("checks.outage-check-interval-seconds", 10L));
        kickAfterSeconds = Math.max(1L, getConfig().getLong("checks.kick-after-seconds", 300L));
        // 0 — снимать вайтлист при первой же успешной проверке
        recoveryConfirmSeconds = Math.max(0L, getConfig().getLong("checks.recovery-confirm-seconds", 120L));
    }

    private ScheduledExecutorService createChecker() {
        return Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "SkyDbWatchdog-Database-Check");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    @Override
    public void onDisable() {
        unregisterVaultEconomyProxy();
        if (checker != null) {
            checker.shutdownNow();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("skydb.admin")) {
            sender.sendMessage(ChatColor.RED + "У вас нет права skydb.admin.");
            return true;
        }

        if (args.length == 0) {
            sendPluginInfo(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        if ("test".equals(sub)) {
            testDatabase(sender);
            return true;
        }
        if ("reload".equals(sub)) {
            reloadPlugin(sender);
            return true;
        }
        if ("test-outage".equals(sub)) {
            toggleForcedOutage(sender);
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Использование: /skydb [test|reload|test-outage]");
        return true;
    }

    private void sendPluginInfo(CommandSender sender) {
        String stateName;
        switch (state) {
            case NORMAL:
                stateName = "норма";
                break;
            case OUTAGE:
                stateName = "сбой БД (ожидание кика)";
                break;
            case LOCKED:
                stateName = "кик выполнен, вайтлист включён";
                break;
            default:
                stateName = state.name();
        }
        sender.sendMessage(ChatColor.GOLD + "==== SkyDbWatchdog ====");
        sender.sendMessage(ChatColor.YELLOW + "Версия: " + ChatColor.WHITE + getDescription().getVersion()
                + ChatColor.YELLOW + ", автор: " + ChatColor.WHITE + getDescription().getAuthors());
        sender.sendMessage(ChatColor.YELLOW + "Состояние: " + ChatColor.WHITE + stateName);
        if (commandForcedOutage) {
            sender.sendMessage(ChatColor.RED + "Активен тестовый режим сбоя (test-outage).");
        }
        sender.sendMessage(ChatColor.YELLOW + "База: " + ChatColor.WHITE
                + getConfig().getString("database.type", "mysql") + " "
                + getConfig().getString("database.address", "?")
                + "/" + getConfig().getString("database.name", "?"));
        sender.sendMessage(ChatColor.YELLOW + "Пул соединений: " + ChatColor.WHITE
                + (dataSource != null && !dataSource.isClosed() ? "работает" : "не создан"));
        sender.sendMessage(ChatColor.YELLOW + "Защита экономики: " + ChatColor.WHITE
                + (vaultEconomyProxy != null ? "активна" : "выключена"));
        sender.sendMessage(ChatColor.YELLOW + "Интервалы (сек): фоновая проверка "
                + ChatColor.WHITE + normalCheckSeconds
                + ChatColor.YELLOW + ", при сбое " + ChatColor.WHITE + outageCheckSeconds
                + ChatColor.YELLOW + ", до кика " + ChatColor.WHITE + kickAfterSeconds
                + ChatColor.YELLOW + ", подтверждение восстановления " + ChatColor.WHITE + recoveryConfirmSeconds);
        sender.sendMessage(ChatColor.YELLOW + "Подкоманды: " + ChatColor.WHITE
                + "/skydb test, /skydb reload, /skydb test-outage");
    }

    private void testDatabase(final CommandSender sender) {
        if (checker == null || checker.isShutdown()) {
            sender.sendMessage(ChatColor.RED + "Фоновая проверка не запущена.");
            return;
        }
        checker.execute(new Runnable() {
            @Override
            public void run() {
                final SQLException failure = testDatabaseConnection();
                Bukkit.getScheduler().runTask(SkyDbWatchdog.this, new Runnable() {
                    @Override
                    public void run() {
                        if (!isEnabled()) return;
                        if (failure == null) {
                            sender.sendMessage(ChatColor.GREEN + "Подключение к базе данных работает.");
                        } else {
                            sender.sendMessage(ChatColor.RED + "Ошибка подключения к базе данных: "
                                    + failure.getMessage());
                        }
                    }
                });
            }
        });
    }

    private void reloadPlugin(CommandSender sender) {
        // Останавливаем старый планировщик и пул, перечитываем конфиг, поднимаем заново.
        if (checker != null) {
            checker.shutdownNow();
        }
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }

        reloadConfig();
        readConfig();

        try {
            dataSource = createDataSource();
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE,
                    "Ne udalos sozdat pul soedineniy posle reload; proverki budut provalivatsya.", exception);
        }

        checker = createChecker();
        getLogger().info("Konfig perezagruzhen; monitoring zapushchen zanovo.");
        sender.sendMessage(ChatColor.GREEN + "Конфиг перезагружен. Новые интервалы: фоновая проверка "
                + normalCheckSeconds + " сек., при сбое " + outageCheckSeconds + " сек., до кика "
                + kickAfterSeconds + " сек., подтверждение восстановления " + recoveryConfirmSeconds + " сек.");
        // Состояние не сбрасываем: если шёл сбой — он продолжается с новыми настройками.
        scheduleCheck(normalCheckSeconds);
    }

    private void toggleForcedOutage(CommandSender sender) {
        if (!commandForcedOutage) {
            commandForcedOutage = true;
            sender.sendMessage(ChatColor.YELLOW + "Тестовый режим сбоя включён: плагин ведёт себя так, будто база недоступна.");
            sender.sendMessage(ChatColor.YELLOW + "Если в течение " + kickAfterSeconds
                    + " сек. не выключить — будет выполнен кик и включён вайтлист (как при реальном сбое).");
            // Если сейчас состояние нормальное — сразу переходим в режим сбоя.
            if (state == State.NORMAL) {
                beginOutage();
            }
            scheduleCheck(outageCheckSeconds);
            return;
        }
        // Выключаем: возвращаем сервер в норму, если он был не в ней.
        commandForcedOutage = false;
        sender.sendMessage(ChatColor.GREEN + "Тестовый режим сбоя выключен.");
        if (state != State.NORMAL) {
            recoverFromOutage();
        }
        scheduleCheck(normalCheckSeconds);
    }

    // ---------- База данных ----------

    private HikariDataSource createDataSource() {
        String type = getConfig().getString("database.type", "mysql").trim().toLowerCase();
        if (!type.equals("mysql") && !type.equals("mariadb")) {
            throw new IllegalArgumentException("database.type must be mysql or mariadb");
        }

        String address = getConfig().getString("database.address", "127.0.0.1:3306").trim();
        String host = address;
        int port = 3306;
        int separator = address.lastIndexOf(':');
        if (separator >= 0) {
            host = address.substring(0, separator).trim();
            try {
                port = Integer.parseInt(address.substring(separator + 1).trim());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid port in database.address: " + address, exception);
            }
        }
        if (host.isEmpty() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid database.address: " + address);
        }

        String database = getConfig().getString("database.name", "base_name").trim();
        if (database.isEmpty()) {
            throw new IllegalArgumentException("database.name cannot be empty");
        }
        String jdbcPrefix = type.equals("mariadb") ? "jdbc:mariadb://" : "jdbc:mysql://";
        String jdbcUrl = jdbcPrefix + host + ":" + port + "/" + database
                + (type.equals("mariadb")
                ? "?connectTimeout=5000&socketTimeout=5000"
                : "?connectTimeout=5000&socketTimeout=5000&useSSL=false&serverTimezone=UTC");

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(getConfig().getString("database.username", ""));
        config.setPassword(getConfig().getString("database.password", ""));
        config.setPoolName("SkyDbWatchdog");
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5000L);
        config.setValidationTimeout(3000L);
        config.setInitializationFailTimeout(-1L);
        return new HikariDataSource(config);
    }

    private void scheduleCheck(long delaySeconds) {
        if (checker != null && !checker.isShutdown()) {
            checker.schedule(this::checkDatabase, delaySeconds, TimeUnit.SECONDS);
        }
    }

    private void checkDatabase() {
        final SQLException checkFailure = testDatabaseConnection();
        final boolean connected = checkFailure == null;
        try {
            Bukkit.getScheduler().runTask(this, new Runnable() {
                @Override
                public void run() {
                    handleCheckResult(connected, checkFailure);
                }
            });
        } catch (IllegalStateException ignored) {
            // Плагин завершает работу — результат больше не нужен.
        }
    }

    private SQLException testDatabaseConnection() {
        try {
            if (dataSource == null) {
                throw new SQLException("The database connection pool is unavailable.");
            }
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT 1");
                 ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Database query returned no result.");
                }
            }
            return null;
        } catch (SQLException exception) {
            return exception;
        }
    }

    // ---------- Конечный автомат ----------

    private void handleCheckResult(boolean connected, SQLException failure) {
        if (!isEnabled()) return;

        // Тестовый режим: реальный результат проверки игнорируется, база считается недоступной.
        if (commandForcedOutage) {
            connected = false;
        }

        if (state == State.NORMAL) {
            if (connected) {
                scheduleCheck(normalCheckSeconds);
            } else {
                getLogger().warning("Baza dannyh nedostupna: " + (failure == null ? "(test mode)" : failure.getMessage()));
                beginOutage();
                scheduleCheck(outageCheckSeconds);
            }
            return;
        }

        // OUTAGE или LOCKED — проверяем чаще и следим за окном восстановления.
        if (connected) {
            if (recoveryStartedAt == 0L) {
                recoveryStartedAt = System.currentTimeMillis();
                getLogger().info("Baza dostupna; nablyudayu ustoychivost " + recoveryConfirmSeconds
                        + " sec. pered snatiem whitelist.");
            }
            if (System.currentTimeMillis() - recoveryStartedAt
                    >= TimeUnit.SECONDS.toMillis(recoveryConfirmSeconds)) {
                recoverFromOutage();
                scheduleCheck(normalCheckSeconds);
            } else {
                scheduleCheck(outageCheckSeconds);
            }
        } else {
            if (recoveryStartedAt != 0L) {
                getLogger().warning("Baza snova nedostupna vo vremya okna vosstanovleniya; otchet sbroshen.");
                recoveryStartedAt = 0L;
            }
            if (state == State.OUTAGE
                    && System.currentTimeMillis() - outageStartedAt >= TimeUnit.SECONDS.toMillis(kickAfterSeconds)) {
                enterLockedState();
            }
            scheduleCheck(outageCheckSeconds);
        }
    }

    private void beginOutage() {
        state = State.OUTAGE;
        outageStartedAt = System.currentTimeMillis();
        recoveryStartedAt = 0L;
        logOutagePlayers();
    }

    private void enterLockedState() {
        state = State.LOCKED;
        originalWhitelistEnabled = Bukkit.hasWhitelist();
        originalWhitelistEntries.clear();

        List<String> bypassPlayers = getConfig().getStringList("whitelist.bypass-players");
        for (String name : bypassPlayers) {
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            OfflinePlayer player = Bukkit.getOfflinePlayer(name.trim());
            originalWhitelistEntries.put(player.getUniqueId(), player.isWhitelisted());
            player.setWhitelisted(true);
        }
        Bukkit.setWhitelist(true);

        String reason = getConfig().getString("messages.kick",
                "&cОшибка базы данных, срочно сообщите об этом в группе тг - t.me/skygame001");
        String formattedReason = ChatColor.translateAlternateColorCodes('&', reason);
        // Кикаем всех, кроме тех, кому выдали bypass-вайтлист: они остаются чинить базу.
        for (Player player : new ArrayList<Player>(Bukkit.getOnlinePlayers())) {
            if (player.isWhitelisted()) {
                continue;
            }
            player.kickPlayer(formattedReason);
        }

        broadcast("whitelist-on", "&c[Сервер] Обнаружен сбой базы данных. Вход ограничен, операции с валютой приостановлены.");
        getLogger().severe("Sboy bazy prevysil " + kickAfterSeconds + " sec.: igroki kiknuty, whitelist vklyuchen.");
    }

    private void recoverFromOutage() {
        for (Map.Entry<UUID, Boolean> entry : originalWhitelistEntries.entrySet()) {
            if (!entry.getValue()) {
                Bukkit.getOfflinePlayer(entry.getKey()).setWhitelisted(false);
            }
        }
        Bukkit.setWhitelist(originalWhitelistEnabled);
        originalWhitelistEntries.clear();
        recoveryStartedAt = 0L;
        state = State.NORMAL;
        broadcast("whitelist-off", "&a[Сервер] База данных восстановлена. Вход открыт.");
        getLogger().info("Baza dannyh vosstanovlena; prezhnee sostoyanie whitelist vozvrashcheno.");
    }

    private void broadcast(String key, String fallback) {
        String prefix = getConfig().getString("messages.prefix", "");
        String text = prefix + getConfig().getString("messages." + key, fallback);
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', text));
    }

    private void logOutagePlayers() {
        final String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        final List<String> playerNames = new ArrayList<String>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            playerNames.add(player.getName());
        }
        final String players = playerNames.isEmpty() ? "none" : String.join(", ", playerNames);
        final String line = timestamp + " | players: " + players + System.lineSeparator();

        checker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Files.createDirectories(logsDirectory);
                    Files.write(logsDirectory.resolve("outages.log"), line.getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException exception) {
                    getLogger().log(Level.WARNING, "Ne udalos zapisat log sboya.", exception);
                }
            }
        });
    }

    // ---------- Защита Vault Economy ----------

    private void hookVaultEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            getLogger().info("Vault ne ustanovlen; zashchita ekonomiki nedostupna.");
            return;
        }
        try {
            ClassLoader vaultClassLoader = Bukkit.getPluginManager().getPlugin("Vault").getClass().getClassLoader();
            vaultEconomyClass = Class.forName("net.milkbowl.vault.economy.Economy", true, vaultClassLoader);
            refreshVaultEconomyProvider();
        } catch (ClassNotFoundException exception) {
            getLogger().log(Level.WARNING, "Ne udalos zagruzit API ekonomiki Vault; zashchita nedostupna.", exception);
        }
    }

    @EventHandler
    public void onEconomyServiceRegistered(ServiceRegisterEvent event) {
        refreshVaultEconomyForEvent(event.getProvider().getService(), event.getProvider().getProvider());
    }

    @EventHandler
    public void onEconomyServiceUnregistered(ServiceUnregisterEvent event) {
        refreshVaultEconomyForEvent(event.getProvider().getService(), event.getProvider().getProvider());
    }

    private void refreshVaultEconomyForEvent(Class<?> service, Object changedProvider) {
        if (vaultEconomyClass != null && service == vaultEconomyClass
                && changedProvider != vaultEconomyProxy && !updatingVaultEconomy) {
            refreshVaultEconomyProvider();
        }
    }

    private void refreshVaultEconomyProvider() {
        if (vaultEconomyClass == null || updatingVaultEconomy) {
            return;
        }

        Object selectedProvider = null;
        int selectedPriority = -1;
        for (Object registrationObject : Bukkit.getServicesManager().getRegistrations(vaultEconomyClass)) {
            RegisteredServiceProvider<?> registration = (RegisteredServiceProvider<?>) registrationObject;
            Object provider = registration.getProvider();
            if (provider == vaultEconomyProxy) {
                continue;
            }
            int priority = registration.getPriority().ordinal();
            if (priority > selectedPriority) {
                selectedProvider = provider;
                selectedPriority = priority;
            }
        }

        if (selectedProvider == null) {
            unregisterVaultEconomyProxy();
            vaultEconomyProvider = null;
            return;
        }
        if (selectedProvider == vaultEconomyProvider && vaultEconomyProxy != null) {
            return;
        }

        updatingVaultEconomy = true;
        try {
            if (vaultEconomyProxy != null) {
                Bukkit.getServicesManager().unregister(vaultEconomyClass, vaultEconomyProxy);
            }
            vaultEconomyProvider = selectedProvider;
            vaultEconomyProxy = Proxy.newProxyInstance(vaultEconomyClass.getClassLoader(),
                    new Class<?>[]{vaultEconomyClass}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
                            return invokeVaultEconomy(method, arguments);
                        }
                    });
            Bukkit.getServicesManager().register((Class) vaultEconomyClass, vaultEconomyProxy, this, ServicePriority.Highest);
            getLogger().info("Zashchita ekonomiki aktivna dlya provaydera "
                    + vaultEconomyProvider.getClass().getName() + ".");
        } finally {
            updatingVaultEconomy = false;
        }
    }

    private Object invokeVaultEconomy(Method method, Object[] arguments) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            if ("toString".equals(method.getName())) {
                return "SkyDbWatchdog Vault economy guard";
            }
            if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(vaultEconomyProxy);
            }
            if ("equals".equals(method.getName())) {
                return vaultEconomyProxy == (arguments == null ? null : arguments[0]);
            }
        }

        if (state != State.NORMAL && isEconomyMutation(method.getName())) {
            return blockedEconomyResult(method, arguments);
        }
        if (vaultEconomyProvider == null) {
            return defaultEconomyResult(method.getReturnType());
        }
        try {
            return method.invoke(vaultEconomyProvider, arguments);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private boolean isEconomyMutation(String methodName) {
        return "withdrawPlayer".equals(methodName) || "depositPlayer".equals(methodName)
                || "createPlayerAccount".equals(methodName) || "bankWithdraw".equals(methodName)
                || "bankDeposit".equals(methodName) || "createBank".equals(methodName)
                || "deleteBank".equals(methodName);
    }

    private Object blockedEconomyResult(Method method, Object[] arguments) throws Exception {
        if (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class) {
            return false;
        }
        if (!"net.milkbowl.vault.economy.EconomyResponse".equals(method.getReturnType().getName())) {
            return defaultEconomyResult(method.getReturnType());
        }

        ClassLoader loader = vaultEconomyClass.getClassLoader();
        Class<?> responseClass = Class.forName("net.milkbowl.vault.economy.EconomyResponse", true, loader);
        Class<?> responseTypeClass = Class.forName(
                "net.milkbowl.vault.economy.EconomyResponse$ResponseType", true, loader);
        Object failureType = null;
        for (Object constant : responseTypeClass.getEnumConstants()) {
            if ("FAILURE".equals(constant.toString())) {
                failureType = constant;
                break;
            }
        }
        double amount = 0.0D;
        if (arguments != null) {
            for (Object argument : arguments) {
                if (argument instanceof Number) {
                    amount = ((Number) argument).doubleValue();
                }
            }
        }
        Constructor<?> constructor = responseClass.getConstructor(double.class, double.class,
                responseTypeClass, String.class);
        return constructor.newInstance(amount, 0.0D, failureType, economyBlockedMessage);
    }

    private Object defaultEconomyResult(Class<?> returnType) {
        if (!returnType.isPrimitive() || returnType == void.class) {
            return null;
        }
        if (returnType == boolean.class) return false;
        if (returnType == char.class) return '\0';
        if (returnType == byte.class) return (byte) 0;
        if (returnType == short.class) return (short) 0;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == float.class) return 0.0F;
        if (returnType == double.class) return 0.0D;
        return null;
    }

    private void unregisterVaultEconomyProxy() {
        if (vaultEconomyClass == null || vaultEconomyProxy == null) {
            return;
        }
        updatingVaultEconomy = true;
        try {
            Bukkit.getServicesManager().unregister(vaultEconomyClass, vaultEconomyProxy);
            vaultEconomyProxy = null;
        } finally {
            updatingVaultEconomy = false;
        }
    }

    // ---------- Запрет входа во время сбоя ----------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        if (state == State.NORMAL) {
            return;
        }
        if (!event.getPlayer().isWhitelisted()) {
            event.disallow(PlayerLoginEvent.Result.KICK_WHITELIST,
                    ChatColor.translateAlternateColorCodes('&',
                            getConfig().getString("messages.join-blocked",
                                    "&cСервер временно закрыт: сбой базы данных. Попробуйте позже.")));
        }
    }
}
