package me.thosea.buildassist;

import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public final class BuildAssist extends JavaPlugin {

	@Override
	public void onEnable() {
		new BukkitRunnable() {
			@Override
			public void run() {
				PluginManager pm = getServer().getPluginManager();

				InteractListener listener = new InteractListener(pm.isPluginEnabled("Geyser-Spigot"));
				pm.registerEvents(listener, BuildAssist.this);
			}
		}.runTask(this);
	}
}
