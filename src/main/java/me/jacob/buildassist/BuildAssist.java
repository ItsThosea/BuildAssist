package me.jacob.buildassist;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public final class BuildAssist extends JavaPlugin {

	public void onEnable() {

		new BukkitRunnable() {
			public void run() {
				ProtocolManager manager = ProtocolLibrary.getProtocolManager();

				if(manager == null)
					return;

				PluginManager pm = getServer().getPluginManager();

				InteractListener listener = new InteractListener();
				pm.registerEvents(listener, BuildAssist.this);

				listener.isGeyserPresent = pm.isPluginEnabled("Geyser-Spigot");
			}
		}.runTask(this);
	}
}
