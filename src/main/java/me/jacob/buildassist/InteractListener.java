package me.jacob.buildassist;

import com.comphenix.protocol.PacketType.Play.Client;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.BukkitConverters;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.EnumWrappers.Direction;
import com.comphenix.protocol.wrappers.EnumWrappers.Hand;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundGroup;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.geysermc.api.Geyser;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Level;

public class InteractListener implements Listener {
	private static final Plugin plugin = JavaPlugin.getPlugin(BuildAssist.class);
	private static final ProtocolManager pm = ProtocolLibrary.getProtocolManager();

	private static final boolean isCombatUpdate = materialExists("ELYTRA");
	private static final boolean isWildUpdate = materialExists("SCULK");

	private static final Class<?> blockHitResultClass;
	private static final Method receiveClientPacketMethod;

	static {
		// Check if the packet is old or new
		for(Field field : Client.BLOCK_PLACE.getPacketClass().getDeclaredFields()) {
			if(field.getType() == MinecraftReflection.getBlockPositionClass()) {
				throw new IllegalStateException("Update your Minecraft version!");
			}
		}

		// Get the method that receives the client packet
		// We can't access the method directly because it was renamed in the latest version of ProtocolLib
		// and, we need to use the old name to work with older versions of ProtocolLib

		Method method;
		try {
			method = pm.getClass().getMethod("receiveClientPacket", Player.class, PacketContainer.class, boolean.class);
		} catch(Exception e) {
			try {
				method = pm.getClass().getMethod("recieveClientPacket", Player.class, PacketContainer.class, boolean.class);
			} catch(Exception e1) {
				// Yep, we can't find it
				throw new RuntimeException("Could not find the method that receives the client packet");
			}
		}

		receiveClientPacketMethod = method;

		Class<?> blockHitResultClass1;
		try {
			blockHitResultClass1 = Class.forName(
					MinecraftReflection.getMinecraftPackage() + ".MovingObjectPositionBlock"
			);
		} catch(ClassNotFoundException e) {
			try {
				blockHitResultClass1 = Class.forName(
						"net.minecraft.world.phys.MovingObjectPositionBlock"
				);
			} catch(ClassNotFoundException e1) {
				throw new RuntimeException("Could not find block hit result class");
			}
		}
		blockHitResultClass = blockHitResultClass1;
	}

	public boolean isGeyserPresent;

	@EventHandler
	public void onInteract(PlayerInteractEvent e) {
		Player p = e.getPlayer();

		// Checks
		if(e.getAction() != Action.RIGHT_CLICK_AIR)
			return;
		if(isGeyserPresent && Geyser.api().connectionByUuid(p.getUniqueId()) != null) {
			// Bedrock player
			return;
		}
		if(e.getItem() == null || !e.getItem().getType().isBlock())
			return;

		Location loc = p.getLocation();
		Location block = new Location(loc.getWorld(),
				loc.getBlockX(), loc.getBlockY() - 1,
				loc.getBlockZ());

		if(!block.getBlock().getType().isSolid())
			return;
		if(loc.getPitch() < 42)
			return;

		BlockFace face = p.getFacing();

		switch(face) {
			case NORTH:
				block.add(0, 0, -1);
				break;
			case SOUTH:
				block.add(0, 0, 1);
				break;
			case WEST:
				block.add(-1, 0, 0);
				break;
			case EAST:
				block.add(1, 0, 0);
				break;
			default:
				return;
		}

		Material blockType = block.getBlock().getType();
		if(blockType != Material.AIR && blockType != Material.WATER && blockType != Material.LAVA)
			return;

		// Place block
		PacketContainer packet = pm.createPacket(Client.USE_ITEM);

		if(isCombatUpdate) {
			// Use main hand or offhand?
			Hand hand = e.getHand() == EquipmentSlot.HAND ? Hand.MAIN_HAND : Hand.OFF_HAND;
			packet.getHands().write(0, hand);
		}

		if(isWildUpdate) {
			packet.getIntegers().write(0, 0);
		}

		try {
			// Block pos, direction, block pos, inside
			Object blockHitResult = blockHitResultClass.getConstructor(
					MinecraftReflection.getVec3DClass(),
					EnumWrappers.getDirectionClass(),
					MinecraftReflection.getBlockPositionClass(),
					boolean.class
			).newInstance(
					BukkitConverters.getVectorConverter().getGeneric(
							new Vector(block.getBlockX(), block.getBlockY(), block.getBlockZ())
					), EnumWrappers.getDirectionConverter().getGeneric(
							blockFaceToDirect(face)
					), BlockPosition.getConverter().getGeneric(
							new BlockPosition(
									block.getBlockX(), block.getBlockY(), block.getBlockZ()
							)), false);

			((StructureModifier<Object>) packet.getSpecificModifier(blockHitResultClass)).write(
					0, blockHitResult
			);

			receiveClientPacketMethod.invoke(pm, p, packet, true);
		} catch(Throwable t) {
			plugin.getLogger().log(Level.SEVERE, "Could not receive packet from " +
					p.getName(), t);
			return;
		}

		// Sounds
		// usually the client plays the block place sound
		// however this time the server placed the block for the player
		// therefore we still need to play the sound to the player that
		// placed the block

		SoundGroup sound = e.getItem().getType().createBlockData().getSoundGroup();
		p.playSound(
				loc,
				sound.getPlaceSound(),
				sound.getVolume(),
				sound.getPitch()
		);
		//</editor-fold>

		e.setCancelled(true);
	}

	private Direction blockFaceToDirect(BlockFace face) {
		return switch(face) {
			case NORTH -> Direction.NORTH;
			case SOUTH -> Direction.SOUTH;
			case EAST -> Direction.EAST;
			case WEST -> Direction.WEST;
			case UP -> Direction.UP;
			case DOWN -> Direction.DOWN;
			default -> throw new IllegalArgumentException("Unexpected block face: " + face);
		};
	}

	private static boolean materialExists(String name) {
		try {
			Material.valueOf(name);
			return true;
		} catch(IllegalArgumentException e) {
			return false;
		}
	}

}
