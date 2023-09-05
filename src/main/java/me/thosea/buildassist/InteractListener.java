package me.thosea.buildassist;

import com.comphenix.protocol.PacketType.Play.Client;
import com.comphenix.protocol.PacketType.Play.Server;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.geysermc.geyser.api.GeyserApi;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.logging.Level;

public class InteractListener implements Listener {
	private static final Plugin plugin = JavaPlugin.getPlugin(BuildAssist.class);
	private static final ProtocolManager pm = ProtocolLibrary.getProtocolManager();

	private static final boolean isCombatUpdate = materialExists("ELYTRA");
	private static final boolean isWildUpdate = materialExists("SCULK");

	private static final Class<?> blockHitResultClass;
	private static final MethodHandle receiveClientPacketMethod;

	static {
		// Check if the packet is old or new
		for(Field field : Client.BLOCK_PLACE.getPacketClass().getDeclaredFields()) {
			if(field.getType() == MinecraftReflection.getBlockPositionClass()) {
				throw new IllegalStateException("Update your Minecraft version!");
			}
		}

		// Get the method that receives the client packet
		// We can't access the method directly because
		// it was renamed in the latest version of ProtocolLib

		MethodHandle method;
		Lookup look = MethodHandles.publicLookup();

		MethodType type = MethodType.methodType(
				void.class,
				Player.class, PacketContainer.class, boolean.class);
		try {
			method = look.findVirtual(pm.getClass(), "receiveClientPacket", type);
		} catch(Exception bad) {
			try {
				method = look.findVirtual(pm.getClass(), "recieveClientPacket", type);
			} catch(Exception veryBad) {
				String msg = "Could not find receiveClientPacket, is your version outdated?";
				throw new RuntimeException(msg);
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
						"net.minecraft.world.phys.MovingObjectPositionBlock");
			} catch(ClassNotFoundException e1) {
				throw new RuntimeException("Could not find block hit result class");
			}
		}
		blockHitResultClass = blockHitResultClass1;
	}

	private final boolean isGeyserPresent;

	public InteractListener(boolean isGeyserPresent) {
		this.isGeyserPresent = isGeyserPresent;
	}


	@EventHandler
	public void onInteract(PlayerInteractEvent event) {
		Player player = event.getPlayer();

		if(event.getAction() != Action.RIGHT_CLICK_AIR) return;
		if(isGeyserPresent && GeyserApi.api().isBedrockPlayer(player.getUniqueId())) return;
		if(event.getItem() == null || !event.getItem().getType().isBlock()) return;

		Location loc = player.getLocation();
		Location block = new Location(loc.getWorld(),
				loc.getBlockX(), loc.getBlockY() - 1,
				loc.getBlockZ());

		if(!block.getBlock().getType().isSolid()) return;
		if(loc.getPitch() < 42) return;

		BlockFace face = player.getFacing();

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
			Hand hand = event.getHand() == EquipmentSlot.HAND ? Hand.MAIN_HAND : Hand.OFF_HAND;
			packet.getHands().write(0, hand);
		}

		if(isWildUpdate) {
			packet.getIntegers().write(0, 0);
		}

		try {
			int x = block.getBlockX();
			int y = block.getBlockY();
			int z = block.getBlockZ();

			// Block pos, direction, block pos, inside
			Object blockHitResult = blockHitResultClass.getConstructor(
					MinecraftReflection.getVec3DClass(),
					EnumWrappers.getDirectionClass(),
					MinecraftReflection.getBlockPositionClass(),
					boolean.class
			).newInstance(
					BukkitConverters.getVectorConverter().getGeneric(new Vector(x, y, z)),
					EnumWrappers.getDirectionConverter().getGeneric(getDirection(face)),
					BlockPosition.getConverter().getGeneric(new BlockPosition(x, y, z)), false);

			((StructureModifier<Object>) packet.getSpecificModifier(blockHitResultClass)).write(
					0, blockHitResult
			);

			receiveClientPacketMethod.invoke(pm, player, packet, true);

			event.setCancelled(true);

			playSound(player, event.getItem(), loc);
			swing(player, event.getHand());
		} catch(Throwable ex) {
			plugin.getLogger().log(Level.SEVERE, "Could not process " + player.getName(), ex);
		}
	}

	private void playSound(Player player, ItemStack item, Location loc) {
		SoundGroup sound = item.getType().createBlockData().getSoundGroup();
		player.playSound(
				loc,
				sound.getPlaceSound(),
				(sound.getVolume() + 1f) / 2f,
				sound.getPitch() * 0.8f
		);
	}

	private void swing(Player player, EquipmentSlot hand) throws InvocationTargetException {
		PacketContainer animate = new PacketContainer(Server.ANIMATION);
		animate.getIntegers().write(0, player.getEntityId());

		if(isCombatUpdate) {
			animate.getIntegers().write(1, hand == EquipmentSlot.HAND ? 0 : 3);
		} else {
			animate.getBytes().write(0, (byte) 1);
		}

		pm.sendServerPacket(player, animate);
	}

	private Direction getDirection(BlockFace face) {
		switch(face) {
			case NORTH:
				return Direction.NORTH;
			case SOUTH:
				return Direction.SOUTH;
			case EAST:
				return Direction.EAST;
			case WEST:
				return Direction.WEST;
			case DOWN:
				return Direction.DOWN;
			case UP:
				return Direction.UP;
			default:
				throw new IllegalArgumentException("Unexpected block face: " + face);
		}
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
