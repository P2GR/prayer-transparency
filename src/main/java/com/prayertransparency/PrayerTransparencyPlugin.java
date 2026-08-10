/*
 * Copyright (c) 2026, P2
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.prayertransparency;

import com.google.inject.Inject;
import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.HeadIcon;
import net.runelite.api.SpritePixels;
import net.runelite.api.gameval.SpriteID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Makes the overhead prayer icons floating above players semi-transparent.
 * <p>
 * OSRS draws the head icons (protect from melee/magic/ranged, smite, ...) as 2D
 * sprites into the client's software frame buffer in a short pass between the
 * 3D scene and the widgets. RuneLite exposes two overlay layers around that
 * pass:
 * <ul>
 *   <li>{@link net.runelite.client.ui.overlay.OverlayLayer#ABOVE_SCENE} - rendered
 *   right after the scene, before the head icons</li>
 *   <li>{@link net.runelite.client.ui.overlay.OverlayLayer#UNDER_WIDGETS} - rendered
 *   after the head icons, before the interfaces</li>
 * </ul>
 * <p>
 * The capture overlay snapshots the scene pixels under every prayer icon, then
 * the apply overlay blends the icon (now in the buffer) with that snapshot at
 * the configured opacity. There is no public RuneLite API to draw these sprites
 * with alpha, so the buffer is manipulated directly - the same buffer the GPU
 * plugin uploads as the interface texture, so this works in both software and
 * GPU rendering modes.
 */
@PluginDescriptor(
	name = "Prayer Transparency",
	description = "Makes overhead prayers transparent with a configurable opacity",
	tags = {"prayer", "overhead", "transparent", "alpha", "p2"}
)
public class PrayerTransparencyPlugin extends Plugin
{
	private static final String LEGACY_GROUP = "p2PrayerTransparency";
	private static final String[] CONFIG_KEYS = {
		"enabled", "transparency", "localPlayerOnly", "verticalOffset", "iconStyle"
	};

	@Inject
	private Client client;

	@Inject
	private PrayerTransparencyConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OverlayManager overlayManager;

	private PrayerTransparencyCaptureOverlay captureOverlay;
	private PrayerTransparencyApplyOverlay applyOverlay;

	/**
	 * Per-frame working state, only touched on the client thread between the
	 * two overlay renders.
	 */
	private final List<HeadIconRegion> regions = new ArrayList<>();
	private int[] scratch = new int[0];

	/**
	 * Clean prayer book icons (no overhead beige circle), keyed by head icon.
	 * Lazily loaded from the game's sprite index.
	 */
	private final Map<HeadIcon, SpritePixels> cleanIcons = new EnumMap<>(HeadIcon.class);
	/**
	 * Native overhead prayer sprites, keyed by head icon. Their exact dimensions
	 * define the only pixels this plugin may capture and modify.
	 */
	private final Map<HeadIcon, SpritePixels> gameIcons = new EnumMap<>(HeadIcon.class);

	@Provides
	PrayerTransparencyConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PrayerTransparencyConfig.class);
	}

	@Override
	protected void startUp()
	{
		migrateLegacyConfig();
		captureOverlay = new PrayerTransparencyCaptureOverlay(this, config);
		applyOverlay = new PrayerTransparencyApplyOverlay(this, config);
		overlayManager.add(captureOverlay);
		overlayManager.add(applyOverlay);
	}

	@Override
	protected void shutDown()
	{
		if (captureOverlay != null)
		{
			overlayManager.remove(captureOverlay);
		}
		if (applyOverlay != null)
		{
			overlayManager.remove(applyOverlay);
		}
		captureOverlay = null;
		applyOverlay = null;
		regions.clear();
		cleanIcons.clear();
		gameIcons.clear();
	}

	private void migrateLegacyConfig()
	{
		for (String key : CONFIG_KEYS)
		{
			if (configManager.getConfiguration(PrayerTransparencyConfig.GROUP, key) == null)
			{
				String legacyValue = configManager.getConfiguration(LEGACY_GROUP, key);
				if (legacyValue != null)
				{
					configManager.setConfiguration(PrayerTransparencyConfig.GROUP, key, legacyValue);
				}
			}
		}
	}

	Client getClient()
	{
		return client;
	}

	List<HeadIconRegion> getRegions()
	{
		return regions;
	}

	int[] getScratch()
	{
		return scratch;
	}

	/**
	 * Grows the reusable scratch buffer when needed; never shrinks it.
	 * <p>
	 * The buffer can grow mid-frame once several players are present, so the
	 * old contents (already-captured regions) must be preserved - otherwise
	 * every earlier region is orphaned and its erase/redraw reads zeros.
	 */
	int[] ensureScratch(int size)
	{
		if (scratch.length < size)
		{
			int newSize = Math.max(size, scratch.length * 2);
			scratch = Arrays.copyOf(scratch, newSize);
		}
		return scratch;
	}

	/**
	 * Returns the clean prayer book sprite for a head icon, or null when none
	 * exists (e.g. curse and combined head icons), in which case the plugin
	 * falls back to the game's own icon.
	 */
	SpritePixels getCleanIcon(HeadIcon icon)
	{
		if (icon == null)
		{
			return null;
		}
		return cleanIcons.computeIfAbsent(icon, this::loadCleanIcon);
	}

	SpritePixels getGameIcon(HeadIcon icon)
	{
		if (icon == null)
		{
			return null;
		}
		return gameIcons.computeIfAbsent(icon, this::loadGameIcon);
	}

	private SpritePixels loadGameIcon(HeadIcon icon)
	{
		SpritePixels[] sprites = client.getSprites(client.getIndexSprites(), SpriteID.HEADICONS_PRAYER, 0);
		if (sprites == null || icon.ordinal() >= sprites.length)
		{
			return null;
		}
		SpritePixels sprite = sprites[icon.ordinal()];
		if (sprite == null || sprite.getPixels() == null)
		{
			return null;
		}
		return sprite;
	}

	private SpritePixels loadCleanIcon(HeadIcon icon)
	{
		Integer spriteId = cleanIconSpriteId(icon);
		if (spriteId == null)
		{
			return null;
		}
		SpritePixels[] sprites = client.getSprites(client.getIndexSprites(), spriteId, 0);
		if (sprites == null || sprites.length == 0 || sprites[0] == null
			|| sprites[0].getPixels() == null)
		{
			return null;
		}
		return sprites[0];
	}

	private Integer cleanIconSpriteId(HeadIcon icon)
	{
		switch (icon)
		{
			case MELEE:
				return SpriteID.Prayeron.PROTECT_FROM_MELEE;
			case RANGED:
				return SpriteID.Prayeron.PROTECT_FROM_MISSILES;
			case MAGIC:
				return SpriteID.Prayeron.PROTECT_FROM_MAGIC;
			case REDEMPTION:
				return SpriteID.Prayeron.REDEMPTION;
			case RETRIBUTION:
				return SpriteID.Prayeron.RETRIBUTION;
			case SMITE:
				return SpriteID.Prayeron.SMITE;
			default:
				return null;
		}
	}
}
