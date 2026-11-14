package com.fastasyncworldedit.bukkit.regions;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.CuboidArea;
import com.fastasyncworldedit.core.regions.FaweMask;
import com.fastasyncworldedit.core.util.TaskManager;
import com.fastasyncworldedit.bukkit.util.FoliaScheduler;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import com.sk89q.worldedit.regions.CuboidRegion;
import org.apache.logging.log4j.Logger;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

public class ResidenceFeature extends BukkitMaskManager implements Listener {

    private static final Logger LOGGER = LogManagerCompat.getLogger();

    public ResidenceFeature(final Plugin residencePlugin) {
        super(residencePlugin.getName());
        LOGGER.info("Plugin 'Residence' found. Using it now.");
    }

    public boolean isAllowed(Player player, ClaimedResidence residence, MaskType type) {
        if (FoliaScheduler.isFolia() && !FoliaScheduler.owns(player)) {
            return FoliaScheduler.callAtEntity(player, () -> isAllowed(player, residence, type));
        }
        return residence != null &&
                (residence.getOwner().equals(player.getName()) ||
                        residence.getOwner().equals(player.getUniqueId().toString()) ||
                        type == MaskType.MEMBER && (FoliaScheduler.isFolia()
                                ? residence.getPermissions().playerHas(player, "build", false)
                                : TaskManager.taskManager().sync(() -> residence
                                        .getPermissions()
                                        .playerHas(player, "build", false))));
    }

    @Override
    public FaweMask getMask(final com.sk89q.worldedit.entity.Player wePlayer, final MaskType type, boolean isWhitelist) {
        final Player player = BukkitAdapter.adapt(wePlayer);
        if (FoliaScheduler.isFolia() && !FoliaScheduler.owns(player)) {
            return FoliaScheduler.callAtEntity(player, () -> getMask(wePlayer, type, isWhitelist));
        }
        final Location location = player.getLocation();
        ClaimedResidence residence = Residence.getInstance().getResidenceManager().getByLoc(location);
        if (residence != null) {
            boolean isAllowed;
            while (!(isAllowed = isAllowed(player, residence, type)) && residence != null) {
                residence = residence.getSubzoneByLoc(location);
            }
            if (isAllowed) {
                final CuboidArea area = residence.getAreaArray()[0];
                final Location pos1 = area.getLowLoc();
                final Location pos2 = area.getHighLoc();
                final ClaimedResidence finalResidence = residence;
                return new FaweMask(new CuboidRegion(BukkitAdapter.asBlockVector(pos1), BukkitAdapter.asBlockVector(pos2))) {
                    @Override
                    public boolean isValid(com.sk89q.worldedit.entity.Player player, MaskType type) {
                        return isAllowed(BukkitAdapter.adapt(player), finalResidence, type);
                    }
                };
            }
        }
        return null;
    }

}
