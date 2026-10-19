/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.bukkit;

import com.fastasyncworldedit.core.configuration.Caption;
import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.util.TaskManager;
import com.fastasyncworldedit.bukkit.util.FoliaScheduler;
import com.sk89q.util.StringUtil;
import com.sk89q.wepif.VaultResolver;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseItemStack;
import com.sk89q.worldedit.bukkit.adapter.BukkitImplAdapter;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.extension.platform.AbstractPlayerActor;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.inventory.BlockBag;
import com.sk89q.worldedit.internal.cui.CUIEvent;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.session.SessionKey;
import com.sk89q.worldedit.util.HandSide;
import com.sk89q.worldedit.util.formatting.WorldEditText;
import com.sk89q.worldedit.util.formatting.component.TextUtils;
import com.sk89q.worldedit.util.formatting.text.Component;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.util.formatting.text.TranslatableComponent;
import com.sk89q.worldedit.util.formatting.text.adapter.bukkit.TextAdapter;
import com.sk89q.worldedit.util.formatting.text.event.ClickEvent;
import com.sk89q.worldedit.util.formatting.text.format.TextColor;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.gamemode.GameMode;
import com.sk89q.worldedit.world.gamemode.GameModes;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.permissions.PermissionAttachment;
import org.enginehub.linbus.tree.LinCompoundTag;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BukkitPlayer extends AbstractPlayerActor {

    private static final Logger LOGGER = LogManagerCompat.getLogger();

    private final Player player;
    private final WorldEditPlugin plugin;
    //FAWE start
    private PermissionAttachment permAttachment = null;

    /**
     * This constructs a new {@link BukkitPlayer} for the given {@link Player}.
     *
     * @param player The corresponding {@link Player} or null if you need a null WorldEdit player for some reason.
     * @deprecated Players are cached by the plugin. Should use {@link WorldEditPlugin#wrapPlayer(Player)}
     */
    @Deprecated
    public BukkitPlayer(@Nullable Player player) {
        super(player != null ? getExistingMap(WorldEditPlugin.getInstance(), player) : new ConcurrentHashMap<>());
        this.plugin = WorldEditPlugin.getInstance();
        this.player = player;
    }
    //FAWE end

    /**
     * This constructs a new {@link BukkitPlayer} for the given {@link Player}.
     *
     * @param plugin The running instance of {@link WorldEditPlugin}
     * @param player The corresponding {@link Player} or null if you need a null WorldEdit player for some reason.
     * @deprecated Players are cached by the plugin. Should use {@link WorldEditPlugin#wrapPlayer(Player)}
     */
    @Deprecated
    public BukkitPlayer(@Nonnull WorldEditPlugin plugin, @Nullable Player player) {
        this.plugin = plugin;
        this.player = player;
        //FAWE start
        if (player != null && Settings.settings().CLIPBOARD.USE_DISK) {
            BukkitPlayer cached = WorldEditPlugin.getInstance().getCachedPlayer(player);
            // Folia: do not automatically restore persisted clipboards while
            // wrapping a Bukkit Player. This constructor can also run in Paper's
            // asynchronous command tree builder, where FAWE clipboard loading
            // calls entity-bound methods and can deadlock SessionManager against
            // the player's region thread. Fresh copy/paste remains available.
            // Retain legacy clipboard restore on traditional Paper.
            if (cached == null && !FoliaScheduler.isFolia()) {
                loadClipboardFromDisk();
            }
        }
        //FAWE end
    }

    //FAWE start
    private static Map<String, Object> getExistingMap(WorldEditPlugin plugin, Player player) {
        BukkitPlayer cached = plugin.getCachedPlayer(player);
        if (cached != null) {
            return cached.getRawMeta();
        }
        return new ConcurrentHashMap<>();
    }
    //FAWE end

    @Override
    public UUID getUniqueId() {
        // Immutable player identity: safe during Folia join/quit, even before
        // the entity scheduler starts or after it retires.
        return player.getUniqueId();
    }

    @Override
    public BaseItemStack getItemInHand(HandSide handSide) {
        if (needsEntityRegion()) {
            return FoliaScheduler.callAtEntity(player, () -> getItemInHand(handSide));
        }
        ItemStack itemStack = handSide == HandSide.MAIN_HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        return BukkitAdapter.adapt(itemStack);
    }

    @Override
    public BaseBlock getBlockInHand(HandSide handSide) throws WorldEditException {
        if (needsEntityRegion()) {
            return FoliaScheduler.callAtEntity(player, () -> getBlockInHand(handSide));
        }
        ItemStack itemStack = handSide == HandSide.MAIN_HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        return BukkitAdapter.asBlockState(itemStack).toBaseBlock();
    }

    @Override
    public String getName() {
        // Player name is stable and does not require entity scheduling.
        return player.getName();
    }

    @Override
    public String getDisplayName() {
        if (needsEntityRegion()) {
            return FoliaScheduler.callAtEntity(player, this::getDisplayName);
        }
        return player.getDisplayName();
    }

    //FAWE start
    @Override
    public void giveItem(BaseItemStack itemStack) {
        if (needsEntityRegion()) {
            FoliaScheduler.callAtEntity(player, () -> {
                giveItem(itemStack);
                return null;
            });
            return;
        }
        final PlayerInventory inv = player.getInventory();
        ItemStack newItem = BukkitAdapter.adapt(itemStack);
        Runnable updateInventory = () -> {
            if (itemStack.getType().id().equalsIgnoreCase(WorldEdit.getInstance().getConfiguration().wandItem)) {
                inv.remove(newItem);
            }
            final ItemStack item = player.getInventory().getItemInMainHand();
            player.getInventory().setItemInMainHand(newItem);
            HashMap<Integer, ItemStack> overflow = inv.addItem(item);
            if (!overflow.isEmpty()) {
                for (Map.Entry<Integer, ItemStack> entry : overflow.entrySet()) {
                    ItemStack stack = entry.getValue();
                    if (stack.getType() != Material.AIR && stack.getAmount() > 0) {
                        Item dropped = player.getWorld().dropItem(player.getLocation(), stack);
                        PlayerDropItemEvent event = new PlayerDropItemEvent(player, dropped);
                        Bukkit.getPluginManager().callEvent(event);
                        if (event.isCancelled()) {
                            dropped.remove();
                        }
                    }
                }
            }
            player.updateInventory();
        };
        if (FoliaScheduler.isFolia()) {
            updateInventory.run();
        } else {
            TaskManager.taskManager().sync(() -> {
                updateInventory.run();
                return null;
            });
        }
    }
    //FAWE end

    @Deprecated
    @Override
    public void printRaw(String msg) {
        if (needsEntityRegion()) {
            FoliaScheduler.callAtEntity(player, () -> { printRaw(msg); return null; });
            return;
        }
        for (String part : msg.split("\n")) {
            player.sendMessage(part);
        }
    }

    @Deprecated
    @Override
    public void print(String msg) {
        if (needsEntityRegion()) {
            FoliaScheduler.callAtEntity(player, () -> { print(msg); return null; });
            return;
        }
        for (String part : msg.split("\n")) {
            player.sendMessage("§d" + part);
        }
    }

    @Deprecated
    @Override
    public void printDebug(String msg) {
        if (needsEntityRegion()) {
            FoliaScheduler.callAtEntity(player, () -> { printDebug(msg); return null; });
            return;
        }
        for (String part : msg.split("\n")) {
            player.sendMessage("§7" + part);
        }
    }

    @Deprecated
    @Override
    public void printError(String msg) {
        if (needsEntityRegion()) {
            FoliaScheduler.callAtEntity(player, () -> { printError(msg); return null; });
            return;
        }
        for (String part : msg.split("\n")) {
            player.sendMessage("§c" + part);
        }
    }

    @Override
    public void print(Component component) {
        if (needsEntityRegion()) {
            final Component originalComponent = component;
            FoliaScheduler.callAtEntity(player, () -> { print(originalComponent); return null; });
            return;
        }
        //FAWE start - Add FAWE prefix to all messages
        component = Caption.color(TranslatableComponent.of("prefix", component), getLocale());
        //FAWE end
        TextAdapter.sendMessage(player, WorldEditText.format(component, getLocale()));
    }

    @Override
    public boolean trySetPosition(Vector3 pos, float pitch, float yaw) {
        if (needsEntityRegion()) {
            return FoliaScheduler.callAtEntity(player, () -> trySetPosition(pos, pitch, yaw));
        }
        //FAWE start
        org.bukkit.World world = player.getWorld();
        if (pos instanceof com.sk89q.worldedit.util.Location) {
            com.sk89q.worldedit.util.Location loc = (com.sk89q.worldedit.util.Location) pos;
            Extent extent = loc.getExtent();
            if (extent instanceof World) {
                world = Bukkit.getWorld(((World) extent).getName());
            }
        }
        org.bukkit.World finalWorld = world;
        //FAWE end
        java.util.function.Supplier<Boolean> teleport = () -> player.teleport(new Location(
                finalWorld,
                pos.x(),
                pos.y(),
                pos.z(),
                yaw,
                pitch
        ));
        return FoliaScheduler.isFolia() ? teleport.get() : TaskManager.taskManager().sync(teleport);
    }

    @Override
    public String[] getGroups() {
        if (needsEntityRegion()) {
            return FoliaScheduler.callAtEntity(player, this::getGroups);
        }
        return plugin.getPermissionsResolver().getGroups(player);
    }

    @Override
    public BlockBag getInventoryBlockBag() {
        return new BukkitPlayerBlockBag(player);
    }

    @Override
    public GameMode getGameMode() {
        if (needsEntityRegion()) {
            return FoliaScheduler.callAtEntity(player, this::getGameMode);
        }
        return GameModes.get(player.getGameMode().name().toLowerCase(Locale.ROOT));
    }

    @Override
    public void setGameMode(GameMode gameMode) {
        if (needsEntityRegion()) {
            FoliaScheduler.callAtEntity(player, () -> { setGameMode(gameMode); return null; });
            return;
        }
        player.setGameMode(org.bukkit.GameMode.valueOf(gameMode.id().toUpperCase(Locale.ROOT)));
    }

    @Override
    public boolean hasPermission(String perm) {
        if (needsEntityRegion()) {
            return FoliaScheduler.callAtEntity(player, () -> hasPermission(perm));
        }
        return (!plugin.getLocalConfiguration().noOpPermissions && player.isOp())
                || plugin.getPermissionsResolver().hasPermission(
                player.getWorld().getName(), player, perm);
    }

    //FAWE start
    @Override
    public void setPermission(String permission, boolean value) {
        if (needsEntityRegion()) {
            FoliaScheduler.callAtEntity(player, () -> { setPermission(permission, value); return null; });
            return;
        }
        /*
         *  Permissions are used to managing WorldEdit region restrictions
         *   - The `/wea` command will give/remove the required bypass permission
         */
        boolean usesuperperms = VaultResolver.perms == null;
        if (VaultResolver.perms != null) {
            if (value) {
                if (!VaultResolver.perms.playerAdd(player, permission)) {
                    usesuperperms = true;
                }
            } else {
                if (!VaultResolver.perms.playerRemove(player, permission)) {
                    usesuperperms = true;
                }
            }
        }
        if (usesuperperms) {
            if (this.permAttachment == null) {
                this.permAttachment = plugin.getPermissionAttachmentManager().getOrAddAttachment(player);
            }
            if (this.permAttachment == null) {
                LOGGER.warn(
                        "Attempted to set permission for offline player `{}`, UUID: `{}`?!",
                        player.getName(),
                        player.getUniqueId()
                );
                return;
            }
            permAttachment.setPermission(permission, value);
        }
    }
    //FAWE end

    @Override
    public World getWorld() {
        if (needsEntityRegion()) {
            return FoliaScheduler.callAtEntity(player, this::getWorld);
        }
        return BukkitAdapter.adapt(player.getWorld());
    }

    @Override
    public void dispatchCUIEvent(CUIEvent event) {
        if (needsEntityRegion()) {
            FoliaScheduler.callAtEntity(player, () -> { dispatchCUIEvent(event); return null; });
            return;
        }
        String[] params = event.getParameters();
        String send = event.getTypeId();
        if (params.length > 0) {
            send = send + "|" + StringUtil.joinString(params, "|");
        }
        player.sendPluginMessage(plugin, WorldEditPlugin.CUI_PLUGIN_CHANNEL, send.getBytes(StandardCharsets.UTF_8));
    }

    public Player getPlayer() {
        // Exposing the stored reference is not a Bukkit entity operation.
        return player;
    }

    @Override
    public boolean isAllowedToFly() {
        if (needsEntityRegion()) {
            return FoliaScheduler.callAtEntity(player, this::isAllowedToFly);
        }
        return player.getAllowFlight();
    }

    @Override
    public void setFlying(boolean flying) {
        if (needsEntityRegion()) {
            FoliaScheduler.callAtEntity(player, () -> { setFlying(flying); return null; });
            return;
        }
        player.setFlying(flying);
    }

    @Override
    public BaseEntity getState() {
        throw new UnsupportedOperationException("Cannot create a state from this object");
    }

    @Override
    public com.sk89q.worldedit.util.Location getLocation() {
        if (needsEntityRegion()) {
            return FoliaScheduler.callAtEntity(player, this::getLocation);
        }
        Location nativeLocation = player.getLocation();
        Vector3 position = BukkitAdapter.asVector(nativeLocation);
        return new com.sk89q.worldedit.util.Location(
                getWorld(),
                position,
                nativeLocation.getYaw(),
                nativeLocation.getPitch()
        );
    }

    @Override
    public boolean setLocation(com.sk89q.worldedit.util.Location location) {
        if (needsEntityRegion()) {
            return FoliaScheduler.callAtEntity(player, () -> setLocation(location));
        }
        return player.teleport(BukkitAdapter.adapt(location));
    }

    @Override
    public Locale getLocale() {
        if (needsEntityRegion()) {
            return FoliaScheduler.callAtEntity(player, this::getLocale);
        }
        return TextUtils.getLocaleByMinecraftTag(player.getLocale());
    }

    @Override
    public void sendAnnouncements() {
        if (needsEntityRegion()) {
            FoliaScheduler.callAtEntity(player, () -> { sendAnnouncements(); return null; });
            return;
        }
        if (!WorldEditPlugin.getInstance().getLifecycledBukkitImplAdapter().isValid()) {
            //FAWE start - swap out EH download url with ours
            print(Caption.of(
                    "worldedit.version.bukkit.unsupported-adapter",
                    TextComponent.of("https://intellectualsites.github.io/download/fawe.html", TextColor.AQUA)
                            .clickEvent(ClickEvent.openUrl("https://intellectualsites.github.io/download/fawe.html"))
            ));
            //FAWE end
        }
    }

    @Nullable
    @Override
    public <T> T getFacet(Class<? extends T> cls) {
        return null;
    }

    @Override
    public SessionKey getSessionKey() {
        // SessionKeyImpl captures only the UUID and immutable player name.
        // Avoid scheduling against unspawned or retired Folia entities.
        return new SessionKeyImpl(this.player);
    }

    static class SessionKeyImpl implements SessionKey {
        // If not static, this will leak a reference

        private final UUID uuid;
        private final String name;

        SessionKeyImpl(Player player) {
            this.uuid = player.getUniqueId();
            this.name = player.getName();
        }

        @Override
        public UUID getUniqueId() {
            return uuid;
        }

        @Nullable
        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isActive() {
            // This is a thread safe call on CraftBukkit because it uses a
            // CopyOnWrite list for the list of players, but the Bukkit
            // specification doesn't require thread safety (though the
            // spec is extremely incomplete)
            return Bukkit.getServer().getPlayer(uuid) != null;
        }

        @Override
        public boolean isPersistent() {
            return true;
        }

    }

    @Override
    public <B extends BlockStateHolder<B>> void sendFakeBlock(BlockVector3 pos, @Nullable B block) {
        if (needsEntityRegion()) {
            FoliaScheduler.callAtEntity(player, () -> { sendFakeBlock(pos, block); return null; });
            return;
        }
        Location loc = new Location(player.getWorld(), pos.x(), pos.y(), pos.z());

        BaseBlock baseBlock;
        if (block != null) {
            baseBlock = block.toBaseBlock();
        } else {
            baseBlock = getExtent().getFullBlock(pos);
        }

        BlockData data = BukkitAdapter.adapt(baseBlock);

        player.sendBlockChange(loc, data);

        BukkitImplAdapter adapter = WorldEditPlugin.getInstance().getBukkitImplAdapter();
        if (adapter == null) {
            return;
        }
        LinCompoundTag nbtData = baseBlock.getNbt();
        if (nbtData == null || !(data.createBlockState() instanceof TileState tileState)) {
            return;
        }
        adapter.sendFakeNBT(player, pos, tileState, nbtData);
    }

    @Override
    public void sendFakeOP() {
        if (needsEntityRegion()) {
            FoliaScheduler.callAtEntity(player, () -> { sendFakeOP(); return null; });
            return;
        }
        BukkitImplAdapter adapter = WorldEditPlugin.getInstance().getBukkitImplAdapter();
        if (adapter != null) {
            adapter.sendFakeOP(player);
        }
    }

    //FAWE start
    @Override
    public void sendTitle(Component title, Component sub) {
        if (needsEntityRegion()) {
            FoliaScheduler.callAtEntity(player, () -> { sendTitle(title, sub); return null; });
            return;
        }
        String titleStr = WorldEditText.reduceToText(title, getLocale());
        String subStr = WorldEditText.reduceToText(sub, getLocale());
        player.sendTitle(titleStr, subStr, 0, 70, 20);
    }

    @Override
    public void unregister() {
        if (needsEntityRegion()) {
            FoliaScheduler.callAtEntity(player, () -> { unregister(); return null; });
            return;
        }
        player.removeMetadata("WE", WorldEditPlugin.getInstance());
        plugin.getPermissionAttachmentManager().removeAttachment(player);
        super.unregister();
    }

    private boolean needsEntityRegion() {
        return player != null && FoliaScheduler.isFolia() && !FoliaScheduler.owns(player);
    }
    //FAWE end
}
