// Maintainership: vonpawn (2020-2022), lejeffe (2022-2024), Vinnie-Singleton (2026-).
package com.npcidletimer;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Provides;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.inject.Inject;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "NPC Idle Timer"
)
public class NPCIdleTimerPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private NPCIdleTimerConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private NPCIdleTimerOverlay npcidletimeroverlay;

	@Getter(AccessLevel.PACKAGE)
	private Instant lastTickUpdate;

	@Getter(AccessLevel.PACKAGE)
	private long lastTrueTickUpdate;

	@Getter(AccessLevel.PACKAGE)
	private final Map<Integer, WanderingNPC> wanderingNPCs = new HashMap<>();

	private final LinkedHashMap<CacheKey, CachedTimer> rememberedTimers =
		new LinkedHashMap<CacheKey, CachedTimer>(16, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<CacheKey, CachedTimer> eldest)
			{
				return size() > Math.max(0, config.rememberLastN());
			}
		};

	private List<String> selectedNPCs = new ArrayList<>();

	@Provides
	NPCIdleTimerConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(NPCIdleTimerConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(npcidletimeroverlay);
		clientThread.invoke(() ->
		{
			selectedNPCs = getSelectedNPCs();
			rebuildAllNpcs();
		});
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(npcidletimeroverlay);
		wanderingNPCs.clear();
		rememberedTimers.clear();
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned npcSpawned)
	{
		if (client.isInInstancedRegion())
		{
			return;
		}

		final NPC npc = npcSpawned.getNpc();
		final String npcName = npc.getName();

		if (npcName == null || !selectedNPCs.contains(npcName.toLowerCase()))
		{
			return;
		}

		registerNpc(npc);
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned npcDespawned)
	{
		if (client.isInInstancedRegion())
		{
			return;
		}

		final NPC npc = npcDespawned.getNpc();
		final String npcName = npc.getName();

		if (npcName == null || !selectedNPCs.contains(npcName.toLowerCase()))
		{
			return;
		}

		final WanderingNPC wnpc = wanderingNPCs.remove(npc.getIndex());
		if (wnpc != null)
		{
			rememberTimer(wnpc);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN ||
			event.getGameState() == GameState.HOPPING)
		{
			// Clear remembered timers too: they're tied to this session.
			wanderingNPCs.clear();
			rememberedTimers.clear();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.isInInstancedRegion())
		{
			return;
		}

		lastTrueTickUpdate = client.getTickCount();
		lastTickUpdate = Instant.now();

		for (NPC npc : client.getNpcs())
		{
			final String npcName = npc.getName();

			if (npcName == null || !selectedNPCs.contains(npcName.toLowerCase()))
			{
				continue;
			}

			final WanderingNPC wnpc = wanderingNPCs.get(npc.getIndex());

			if (wnpc == null)
			{
				continue;
			}
			if (config.showOverlayTicks())
			{
				if (wnpc.getCurrentLocation().getX() != npc.getWorldLocation().getX() || wnpc.getCurrentLocation().getY() != npc.getWorldLocation().getY())
				{
					long currentTick = client.getTickCount();
					wnpc.setCurrentLocation(npc.getWorldLocation());
					wnpc.setTimeWithoutMoving(0);
					wnpc.setTrueStoppedMovingTick(currentTick);
					wnpc.setNpc(npc);
				}
				else
				{
					long currentTick = client.getTickCount();
					wnpc.setTimeWithoutMoving(lastTrueTickUpdate - wnpc.getTrueStoppedMovingTick());
				}
			}
			else {
				if (wnpc.getCurrentLocation().getX() != npc.getWorldLocation().getX() || wnpc.getCurrentLocation().getY() != npc.getWorldLocation().getY())
				{
					wnpc.setCurrentLocation(npc.getWorldLocation());
					wnpc.setTimeWithoutMoving(0);
					wnpc.setStoppedMovingTick(Instant.now());
					wnpc.setNpc(npc);
				}
				else
				{
					wnpc.setTimeWithoutMoving(lastTickUpdate.getEpochSecond() - wnpc.getStoppedMovingTick().getEpochSecond());
				}
			}
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		if (!configChanged.getGroup().equals("npcidletimerplugin"))
		{
			return;
		}

		clientThread.invoke(() ->
		{
			selectedNPCs = getSelectedNPCs();
			// If the user shrinks the cache, trim down now instead of letting old entries linger.
			while (rememberedTimers.size() > Math.max(0, config.rememberLastN()))
			{
				rememberedTimers.remove(rememberedTimers.keySet().iterator().next());
			}
			rebuildAllNpcs();
		});
	}

	@VisibleForTesting
	List<String> getSelectedNPCs()
	{
		final String configNPCs = config.npcToShowTimer().toLowerCase();

		if (configNPCs.isEmpty())
		{
			return Collections.emptyList();
		}

		return Text.fromCSV(configNPCs);
	}

	private void rebuildAllNpcs()
	{
		wanderingNPCs.clear();

		if (client.getGameState() != GameState.LOGGED_IN &&
			client.getGameState() != GameState.LOADING)
		{
			// NPCs are still in the client after logging out, ignore them
			return;
		}

		for (NPC npc : client.getNpcs())
		{
			final String npcName = npc.getName();

			if (npcName == null || !selectedNPCs.contains(npcName.toLowerCase()))
			{
				continue;
			}

			registerNpc(npc);
		}
	}

	private void registerNpc(NPC npc)
	{
		if (wanderingNPCs.containsKey(npc.getIndex()))
		{
			return;
		}

		final WanderingNPC wnpc = new WanderingNPC(npc);
		final CachedTimer cached = lookupRememberedTimer(npc);
		if (cached != null)
		{
			wnpc.setStoppedMovingTick(cached.stoppedMovingTick);
			wnpc.setTrueStoppedMovingTick(cached.trueStoppedMovingTick);
			wnpc.setTimeWithoutMoving(cached.timeWithoutMoving);
		}
		wanderingNPCs.put(npc.getIndex(), wnpc);
	}

	private void rememberTimer(WanderingNPC wnpc)
	{
		if (config.rememberLastN() <= 0 || wnpc.getNpcName() == null || wnpc.getCurrentLocation() == null)
		{
			return;
		}
		final CacheKey key = new CacheKey(wnpc.getNpcName().toLowerCase(), wnpc.getCurrentLocation());
		rememberedTimers.put(key, new CachedTimer(
			wnpc.getStoppedMovingTick(),
			wnpc.getTrueStoppedMovingTick(),
			wnpc.getTimeWithoutMoving(),
			Instant.now()
		));
	}

	private CachedTimer lookupRememberedTimer(NPC npc)
	{
		if (config.rememberLastN() <= 0 || npc.getName() == null)
		{
			return null;
		}
		final CacheKey key = new CacheKey(npc.getName().toLowerCase(), npc.getWorldLocation());
		final CachedTimer cached = rememberedTimers.remove(key);
		if (cached == null)
		{
			return null;
		}
		final long ageSeconds = Duration.between(cached.cachedAt, Instant.now()).getSeconds();
		if (ageSeconds > config.rememberMaxAgeSeconds())
		{
			return null;
		}
		return cached;
	}

	private static final class CacheKey
	{
		private final String name;
		private final WorldPoint location;

		CacheKey(String name, WorldPoint location)
		{
			this.name = name;
			this.location = location;
		}

		@Override
		public boolean equals(Object o)
		{
			if (this == o) return true;
			if (!(o instanceof CacheKey)) return false;
			CacheKey other = (CacheKey) o;
			return Objects.equals(name, other.name) && Objects.equals(location, other.location);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(name, location);
		}
	}

	private static final class CachedTimer
	{
		final Instant stoppedMovingTick;
		final long trueStoppedMovingTick;
		final long timeWithoutMoving;
		final Instant cachedAt;

		CachedTimer(Instant stoppedMovingTick, long trueStoppedMovingTick, long timeWithoutMoving, Instant cachedAt)
		{
			this.stoppedMovingTick = stoppedMovingTick;
			this.trueStoppedMovingTick = trueStoppedMovingTick;
			this.timeWithoutMoving = timeWithoutMoving;
			this.cachedAt = cachedAt;
		}
	}
}
