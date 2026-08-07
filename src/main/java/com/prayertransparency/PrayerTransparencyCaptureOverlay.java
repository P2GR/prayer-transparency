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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;

/**
 * Runs in {@link OverlayLayer#ABOVE_SCENE}, i.e. right after the 3D scene has
 * been rasterized but before the game draws the overhead head icons.
 * <p>
 * For every tracked player with an overhead prayer it records the rectangular
 * screen region the icon occupies and snapshots the scene pixels underneath it.
 * The snapshot is stored in the plugin's reusable scratch buffer so the
 * {@link PrayerTransparencyApplyOverlay} can blend a semi-transparent icon
 * back over the scene later in the same frame.
 */
@Slf4j
class PrayerTransparencyCaptureOverlay extends Overlay
{
	private static final int LOG_EVERY_N_FRAMES = 60;

	// Region geometry, baked in: only the icon's own pixels (those differing
	// from the captured scene) are modified, so the region only has to cover
	// the icon. It is deliberately TALL: the icon's screen height varies a lot
	// with camera pitch (and with the model's animation bob), while its
	// horizontal position is stable at the player's tile center. A wide short
	// box lets the icon drift out of range at steep camera angles.
	private static final int ICON_HALF_WIDTH = 48;
	private static final int ICON_HALF_HEIGHT = 128;
	// Extra height (local units, 128 = one tile) above the model top where the
	// head icon is drawn. 96 local units = 3/4 tile above the head.
	private static final int ICON_HEIGHT_OFFSET = 96;

	private final PrayerTransparencyPlugin plugin;
	private final PrayerTransparencyConfig config;

	private int frameCounter;

	PrayerTransparencyCaptureOverlay(PrayerTransparencyPlugin plugin, PrayerTransparencyConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setLayer(OverlayLayer.ABOVE_SCENE);
		// Run first so nothing else has drawn into the regions we snapshot.
		setPriority(Overlay.PRIORITY_HIGHEST);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (config == null || !config.enabled())
		{
			return null;
		}

		Client client = plugin.getClient();
		List<HeadIconRegion> regions = plugin.getRegions();
		regions.clear();

		BufferProvider bufferProvider = client.getBufferProvider();
		if (bufferProvider == null)
		{
			return null;
		}

		int[] pixels = bufferProvider.getPixels();
		int bufferWidth = bufferProvider.getWidth();
		int bufferHeight = bufferProvider.getHeight();
		if (pixels == null || bufferWidth <= 0 || bufferHeight <= 0)
		{
			return null;
		}

		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null)
		{
			return null;
		}

		boolean debug = config.debugLogging();
		Player localPlayer = client.getLocalPlayer();
		int plane = worldView.getPlane();
		int halfWidth = ICON_HALF_WIDTH;
		int halfHeight = ICON_HALF_HEIGHT;
		int heightOffset = ICON_HEIGHT_OFFSET;
		int verticalOffset = config.verticalOffset();
		int playersWithIcons = 0;
		int playersVisible = 0;

		// Snapshot the scene behind each icon into the shared scratch buffer. The
		// apply overlay consumes these regions later in the same frame.
		int[] scratch = plugin.getScratch();
		int scratchOffset = 0;
		for (Player player : worldView.players())
		{
			if (player == null)
			{
				continue;
			}
			playersVisible++;
			if (player.getOverheadIcon() == null)
			{
				continue;
			}
			playersWithIcons++;
			if (config.localPlayerOnly() && player != localPlayer)
			{
				continue;
			}

			Point canvas = iconCanvasPoint(client, player, plane, heightOffset);
			if (canvas == null)
			{
				if (debug)
				{
					log.debug("Capture: player {} has icon but projects off-canvas", player.getName());
				}
				continue;
			}

			int x0 = Math.max(0, canvas.getX() - halfWidth);
			int y0 = Math.max(0, canvas.getY() - halfHeight);
			int x1 = Math.min(bufferWidth - 1, canvas.getX() + halfWidth);
			int y1 = Math.min(bufferHeight - 1, canvas.getY() + halfHeight);
			if (x1 < x0 || y1 < y0)
			{
				if (debug)
				{
					log.debug("Capture: player {} icon center ({},{}) outside buffer", player.getName(), canvas.getX(), canvas.getY());
				}
				continue;
			}

			int regionWidth = x1 - x0 + 1;
			int regionHeight = y1 - y0 + 1;
			int regionSize = regionWidth * regionHeight;

			// Target region: same size, centered on the icon projected at the
			// configured vertical offset. Origin is left unclamped so the 1:1
			// pixel mapping to the original region stays exact.
			Point targetCanvas = iconCanvasPoint(client, player, plane, heightOffset + verticalOffset);
			int targetX = targetCanvas == null ? x0 : targetCanvas.getX() - halfWidth;
			int targetY = targetCanvas == null ? y0 : targetCanvas.getY() - halfHeight;

			scratch = plugin.ensureScratch(scratchOffset + regionSize * 4);
			int origBgOffset = scratchOffset;
			int targetBgOffset = scratchOffset + regionSize;
			int iconOffset = scratchOffset + regionSize * 2;
			int maskOffset = scratchOffset + regionSize * 3;

			// Capture the scene behind the game's icon.
			for (int row = 0; row < regionHeight; row++)
			{
				System.arraycopy(pixels, (y0 + row) * bufferWidth + x0,
					scratch, origBgOffset + row * regionWidth, regionWidth);
			}

			// Capture the scene at the target location (only the in-buffer part;
			// out-of-buffer slots stay 0 and are ignored in the apply pass).
			for (int row = 0; row < regionHeight; row++)
			{
				int ty = targetY + row;
				if (ty < 0 || ty >= bufferHeight)
				{
					continue;
				}
				for (int column = 0; column < regionWidth; column++)
				{
					int tx = targetX + column;
					if (tx < 0 || tx >= bufferWidth)
					{
						continue;
					}
					scratch[targetBgOffset + row * regionWidth + column] =
						pixels[ty * bufferWidth + tx];
				}
			}

			regions.add(new HeadIconRegion(x0, y0, regionWidth, regionHeight,
				targetX, targetY, origBgOffset, targetBgOffset, iconOffset, maskOffset,
				config.iconStyle() == PrayerTransparencyConfig.IconStyle.CLEAN
					? plugin.getCleanIcon(player.getOverheadIcon())
					: null));
			scratchOffset += regionSize * 4;

			if (debug)
			{
				log.debug("Capture: {} icon center=({},{}), region=({},{})-({},{}), target=({},{}), logicalHeight={}, offset={}+{}",
					player.getName(), canvas.getX(), canvas.getY(), x0, y0, x1, y1,
					targetX + halfWidth, targetY + halfHeight,
					player.getLogicalHeight(), heightOffset, verticalOffset);
			}
		}

		if (debug)
		{
			// Visual markers: cyan crosshair at the game's icon, orange at the
			// target location it is moved to.
			graphics.setColor(Color.CYAN);
			drawCrosshair(graphics, client, worldView, localPlayer, plane, heightOffset, config, verticalOffset, false);
			if (verticalOffset != 0)
			{
				graphics.setColor(Color.ORANGE);
				drawCrosshair(graphics, client, worldView, localPlayer, plane, heightOffset, config, verticalOffset, true);
			}

			frameCounter++;
			if (frameCounter % LOG_EVERY_N_FRAMES == 0)
			{
				log.debug("Capture: buffer={}x{} gpu={} plane={} playersVisible={} playersWithIcons={} regionsCaptured={} verticalOffset={}",
					bufferWidth, bufferHeight, client.isGpu(), plane,
					playersVisible, playersWithIcons, regions.size(), verticalOffset);
			}
		}

		return null;
	}

	private void drawCrosshair(Graphics2D graphics, Client client, WorldView worldView,
		Player localPlayer, int plane, int heightOffset, PrayerTransparencyConfig config,
		int verticalOffset, boolean atTarget)
	{
		for (Player player : worldView.players())
		{
			if (player == null || player.getOverheadIcon() == null)
			{
				continue;
			}
			if (config.localPlayerOnly() && player != localPlayer)
			{
				continue;
			}
			int offset = atTarget ? heightOffset + verticalOffset : heightOffset;
			Point canvas = iconCanvasPoint(client, player, plane, offset);
			if (canvas == null)
			{
				continue;
			}
			int cx = canvas.getX();
			int cy = canvas.getY();
			graphics.drawLine(cx - 8, cy, cx + 8, cy);
			graphics.drawLine(cx, cy - 8, cx, cy + 8);
		}
	}

	/**
	 * Projects the location where the game draws the overhead head icon for a
	 * player: centered on the player, a fixed amount above the top of the model.
	 */
	private Point iconCanvasPoint(Client client, Player player, int plane, int heightOffset)
	{
		if (player.getLocalLocation() == null)
		{
			return null;
		}
		return Perspective.localToCanvas(client, player.getLocalLocation(), plane,
			player.getLogicalHeight() + heightOffset);
	}
}
