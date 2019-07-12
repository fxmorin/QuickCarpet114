package quickcarpet.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.command.CommandSource;
import net.minecraft.server.command.ServerCommandSource;
import quickcarpet.logging.LogHandler;
import quickcarpet.logging.LogHandlers;
import quickcarpet.logging.Logger;
import quickcarpet.logging.LoggerRegistry;
import quickcarpet.settings.Settings;
import quickcarpet.utils.Messenger;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class LogCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        LiteralArgumentBuilder<ServerCommandSource> log = literal("log")
            .requires((player) -> Settings.commandLog)
            .executes((context) -> listLogs(context.getSource()))
            .then(literal("clear")
                .executes(c -> unsubFromAll(c.getSource(), c.getSource().getName()))
                .then(argument("player", StringArgumentType.word())
                    .suggests((c, b)-> CommandSource.suggestMatching(c.getSource().getPlayerNames(), b))
                    .executes(c -> unsubFromAll(c.getSource(), getString(c, "player")))));

        LiteralArgumentBuilder<ServerCommandSource> handlerArg = literal("handler");
        for (Map.Entry<String, LogHandler.LogHandlerCreator> c : LogHandlers.CREATORS.entrySet()) {
            LiteralArgumentBuilder<ServerCommandSource> handler = literal(c.getKey());
            if (c.getValue().usesExtraArgs()) {
                handlerArg.then(handler
                        .executes(ctx -> subscribe(ctx, c.getKey()))
                        .then(argument("extra", greedyString()).executes(ctx -> subscribe(ctx, c.getKey()))));
            } else {
                handlerArg.then(handler.executes(ctx -> subscribe(ctx, c.getKey())));
            }
        }

        LiteralArgumentBuilder<ServerCommandSource> playerArg = literal("player")
                .then(argument("player", StringArgumentType.word())
                .suggests( (c, b) -> CommandSource.suggestMatching(c.getSource().getPlayerNames(),b))
                .executes(LogCommand::subscribe)
                    .then(handlerArg));

        log.then(argument("log name", StringArgumentType.word())
            .suggests((c, b)-> CommandSource.suggestMatching(LoggerRegistry.getLoggerNames(),b))
            .executes(c -> toggleSubscription(c.getSource(), c.getSource().getName(), getString(c, "log name")))
            .then(literal("clear")
                .executes( (c) -> unsubFromLogger(
                    c.getSource(),
                    c.getSource().getName(),
                    getString(c, "log name"))))
            .then(playerArg)
            .then(handlerArg)
            .then(argument("option", StringArgumentType.word())
                .suggests(LogCommand::suggestLoggerOptions)
                .executes(LogCommand::subscribe)
                .then(playerArg)));

        dispatcher.register(log);
    }

    private static <T> T getOrNull(CommandContext<ServerCommandSource> context, String argument, Class<T> type) {
        try {
            return context.getArgument(argument, type);
        } catch (IllegalArgumentException e) {
            if (e.getMessage().startsWith("No such argument")) return null;
            throw e;
        }
    }

    private static <T> T getOrDefault(CommandContext<ServerCommandSource> context, String argument, T defaultValue) {
        T value = getOrNull(context, argument, (Class<T>) defaultValue.getClass());
        return value == null ? defaultValue : value;
    }

    private static int subscribe(CommandContext<ServerCommandSource> context, String handlerName) {
        String player = getOrDefault(context, "player", context.getSource().getName());
        String logger = getString(context, "log name");
        String option = getOrNull(context, "option", String.class);
        LogHandler handler = null;
        if (handlerName != null) {
            String extra = getOrNull(context, "extra", String.class);
            String[] extraArgs = extra == null ? new String[0] : extra.split(" ");
            handler = LogHandlers.createHandler(handlerName, extraArgs);
        }
        return subscribePlayer(context.getSource(), player, logger, option, handler);
    }

    private static int subscribe(CommandContext<ServerCommandSource> context) {
        return subscribe(context, null);
    }

    private static CompletableFuture<Suggestions> suggestLoggerOptions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        String loggerName = getString(context, "log name");
        Logger logger = LoggerRegistry.getLogger(loggerName);
        String[] options = logger == null ? new String[]{} : logger.getOptions();
        return CommandSource.suggestMatching(options, builder);
    }

    private static int listLogs(ServerCommandSource source) {
        PlayerEntity player;
        try {
            player = source.getPlayer();
        } catch (CommandSyntaxException e) {
            Messenger.m(source, "For players only");
            return 0;
        }
        Map<String, String> subs = LoggerRegistry.getPlayerSubscriptions(source.getName());
        if (subs == null) {
            subs = new HashMap<>();
        }
        List<String> all_logs = new ArrayList<>(LoggerRegistry.getLoggerNames());
        Collections.sort(all_logs);
        Messenger.m(player, "w _____________________");
        Messenger.m(player, "w Available logging options:");
        for (String lname : all_logs) {
            List<Object> comp = new ArrayList<>();
            String color = subs.containsKey(lname) ? "w" : "g";
            comp.add("w  - " + lname + ": ");
            Logger logger = LoggerRegistry.getLogger(lname);
            String[] options = logger.getOptions();
            if (options.length == 0) {
                if (subs.containsKey(lname)) {
                    comp.add("l Subscribed ");
                } else {
                    comp.add(color + " [Subscribe] ");
                    comp.add("^w subscribe to " + lname);
                    comp.add("!/log " + lname);
                }
            } else {
                for (String option : logger.getOptions()) {
                    if (subs.containsKey(lname) && subs.get(lname).equalsIgnoreCase(option)) {
                        comp.add("l [" + option + "] ");
                    } else {
                        comp.add(color + " [" + option + "] ");
                        comp.add("^w subscribe to " + lname + " " + option);
                        comp.add("!/log " + lname + " " + option);
                    }

                }
            }
            if (subs.containsKey(lname)) {
                comp.add("nb [X]");
                comp.add("^w Click to unsubscribe");
                comp.add("!/log " + lname);
            }
            Messenger.m(player, comp.toArray(new Object[0]));
        }
        return 1;
    }

    private static boolean areArgumentsInvalid(ServerCommandSource source, String playerName, String loggerName) {
        PlayerEntity player = source.getMinecraftServer().getPlayerManager().getPlayer(playerName);
        if (player == null) {
            Messenger.m(source, "r No player specified");
            return true;
        }
        if (loggerName != null && LoggerRegistry.getLogger(loggerName) == null) {
            Messenger.m(source, "r Unknown logger: ", "rb " + loggerName);
            return true;
        }
        return false;
    }

    private static int unsubFromAll(ServerCommandSource source, String playerName) {
        if (areArgumentsInvalid(source, playerName, null)) return 0;
        for (String logname : LoggerRegistry.getLoggerNames()) {
            LoggerRegistry.unsubscribePlayer(playerName, logname);
        }
        Messenger.m(source, "gi Unsubscribed from all logs");
        return 1;
    }

    private static int unsubFromLogger(ServerCommandSource source, String playerName, String loggerName) {
        if (areArgumentsInvalid(source, playerName, loggerName)) return 0;
        LoggerRegistry.unsubscribePlayer(playerName, loggerName);
        Messenger.m(source, "gi Unsubscribed from " + loggerName);
        return 1;
    }

    private static int toggleSubscription(ServerCommandSource source, String player_name, String loggerName) {
        if (areArgumentsInvalid(source, player_name, loggerName)) return 0;
        boolean subscribed = LoggerRegistry.togglePlayerSubscription(player_name, loggerName, null);
        if (subscribed) {
            Messenger.m(source, "gi " + player_name + " subscribed to " + loggerName + ".");
        } else {
            Messenger.m(source, "gi " + player_name + " unsubscribed from " + loggerName + ".");
        }
        return 1;
    }

    private static int subscribePlayer(ServerCommandSource source, String playerName, String loggerName, String option, LogHandler handler) {
        if (areArgumentsInvalid(source, playerName, loggerName)) return 0;
        LoggerRegistry.subscribePlayer(playerName, loggerName, option, handler);
        if (option != null) {
            Messenger.m(source, "gi Subscribed to " + loggerName + "(" + option + ")");
        } else {
            Messenger.m(source, "gi Subscribed to " + loggerName);
        }
        return 1;
    }
}
