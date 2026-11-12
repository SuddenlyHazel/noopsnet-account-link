// SPDX-License-Identifier: MPL-2.0
package net.noopsnet.accountlink;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod(value = "noopsnet_account_link", dist = Dist.DEDICATED_SERVER)
public final class NoopsAccountLink {
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final String secret;
    private final URI endpoint;

    public NoopsAccountLink() {
        String configuredSecret = "";
        URI configuredEndpoint = URI.create("http://127.0.0.1:8766");
        try {
            Path path =
                    Path.of(
                            System.getProperty(
                                    "noopsnet.linkConfig",
                                    "/etc/noopsnet-account-link/client.json"));
            JsonObject config = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            configuredSecret = config.get("shared_secret").getAsString();
            if (config.has("endpoint"))
                configuredEndpoint = URI.create(config.get("endpoint").getAsString());
            if (!"http".equals(configuredEndpoint.getScheme())
                    || !"127.0.0.1".equals(configuredEndpoint.getHost())
                    || configuredEndpoint.getRawUserInfo() != null
                    || configuredEndpoint.getQuery() != null
                    || configuredEndpoint.getFragment() != null
                    || !configuredEndpoint.getPath().isEmpty()
                    || configuredSecret.length() < 32)
                throw new IllegalArgumentException("Invalid local adapter configuration");
        } catch (Exception ignored) {
            // The optional feature fails closed without stopping the game or exposing secrets.
            configuredSecret = "";
        }
        secret = configuredSecret;
        endpoint = configuredEndpoint;
        NeoForge.EVENT_BUS.addListener(this::commands);
    }

    private void commands(RegisterCommandsEvent event) {
        var root =
                Commands.literal("discord")
                        .requires(source -> source.getEntity() instanceof ServerPlayer)
                        .executes(context -> help(context.getSource()));
        root.then(
                Commands.literal("link")
                        .executes(context -> request(context.getSource(), "link", null)));
        root.then(
                Commands.literal("status")
                        .executes(context -> request(context.getSource(), "status", null)));
        root.then(
                Commands.literal("confirm")
                        .then(
                                Commands.argument("discord_id", StringArgumentType.word())
                                        .executes(
                                                context ->
                                                        request(
                                                                context.getSource(),
                                                                "confirm",
                                                                StringArgumentType.getString(
                                                                        context, "discord_id")))));
        var unlink =
                Commands.literal("unlink").executes(context -> unlinkHelp(context.getSource()));
        unlink.then(
                Commands.literal("confirm")
                        .executes(context -> request(context.getSource(), "unlink", null)));
        root.then(unlink);
        event.getDispatcher().register(root);
    }

    private int unlinkHelp(CommandSourceStack source) {
        source.sendSuccess(
                () ->
                        Component.literal(
                                "To remove your link or cancel a request, "
                                        + "run /discord unlink confirm."),
                false);
        return 1;
    }

    private int help(CommandSourceStack source) {
        source.sendSuccess(
                () ->
                        Component.literal(
                                "Optional Discord linking: /discord link, /discord status, /discord"
                                    + " unlink. Links are private; no roles or server access are"
                                    + " granted."),
                false);
        return 1;
    }

    private int request(CommandSourceStack source, String action, String discordId) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        MinecraftServer server = source.getServer();
        if (!server.usesAuthentication()) {
            source.sendFailure(
                    Component.literal("Account linking requires an online-mode server."));
            return 0;
        }
        if (secret.isEmpty()) {
            source.sendFailure(
                    Component.literal(
                            "Account linking is not configured. Contact the server owner."));
            return 0;
        }
        if (discordId != null && !discordId.matches("[0-9]{17,20}")) {
            source.sendFailure(
                    Component.literal(
                            "Run /discord status and use the confirmation command shown there."));
            return 0;
        }
        UUID uuid = player.getUUID();
        if (!busy.add(uuid)) {
            source.sendFailure(Component.literal("Please wait for your previous linking request."));
            return 0;
        }
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid.toString());
        body.addProperty("player", player.getGameProfile().getName());
        if (discordId != null) body.addProperty("discord_id", discordId);
        HttpRequest req =
                HttpRequest.newBuilder(endpoint.resolve("/minecraft/" + action))
                        .timeout(Duration.ofSeconds(5))
                        .header("Authorization", "Bearer " + secret)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();
        // HTTP never blocks the Minecraft tick thread. Responses go only to the same session.
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .whenComplete(
                        (response, error) -> {
                            busy.remove(uuid);
                            server.execute(
                                    () -> handleResponse(server, player, uuid, response, error));
                        });
        return 1;
    }

    private void handleResponse(
            MinecraftServer server,
            ServerPlayer player,
            UUID uuid,
            HttpResponse<String> response,
            Throwable error) {
        if (server.getPlayerList().getPlayer(uuid) != player) return;
        try {
            if (error != null || response.statusCode() >= 500) throw new IllegalStateException();
            JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
            String message = result.get("message").getAsString();
            player.sendSystemMessage(Component.literal(message));
            if (result.has("copy_command")) {
                String command = result.get("copy_command").getAsString();
                if (command.matches("/minecraft link code:[A-Z2-7]{12}"))
                    player.sendSystemMessage(copyCommand(command));
            }
            if (result.has("confirm")) {
                String id = result.get("confirm").getAsString();
                if (id.matches("[0-9]{17,20}")) showConfirmation(player, id);
            }
        } catch (Exception ignored) {
            player.sendSystemMessage(
                    Component.literal(
                            "Account linking is temporarily unavailable. Try again shortly."));
        }
    }

    private Component copyCommand(String command) {
        var click = new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, command);
        var hover =
                new HoverEvent(
                        HoverEvent.Action.SHOW_TEXT,
                        Component.literal(
                                "Copy the full command, then paste it in Noops Community"
                                        + " Discord."));
        return Component.literal("[Copy Discord command]")
                .withStyle(
                        style ->
                                style.withColor(ChatFormatting.AQUA)
                                        .withUnderlined(true)
                                        .withClickEvent(click)
                                        .withHoverEvent(hover));
    }

    private void showConfirmation(ServerPlayer player, String id) {
        String command = "/discord confirm " + id;
        var click = new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command);
        player.sendSystemMessage(
                Component.literal("[Confirm my Discord account]")
                        .withStyle(
                                style ->
                                        style.withColor(ChatFormatting.GREEN)
                                                .withClickEvent(click)));
        player.sendSystemMessage(
                Component.literal(
                        "Or type "
                                + command
                                + ". If this is not your account, run /discord unlink confirm."));
    }
}
