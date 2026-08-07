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

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
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
class PrayerTransparencyCaptureOverlay extends Overlay
{
	private static final int NATIVE_ANCHOR_HEIGHT = 15;
	private static final int MAX_OVERHEAD_STACK_HEIGHT = 192;
	private static final int NATIVE_EDGE_MARGIN = 4;

	private final PrayerTransparencyPlugin plugin;
	private final PrayerTransparencyConfig config;

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
		Client client = plugin.getClient();
		List<HeadIconRegion> regions = plugin.getRegions();
		// Always reset per-frame state, even when disabled, so no stale regions
		// survive from a frame where the plugin was active.
		regions.clear();

		if (config == null || !config.enabled())
		{
			// Disabled: never touch the buffer; the game renders its icons
			// natively, fully restored.
			return null;
		}

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

		Player localPlayer = client.getLocalPlayer();
		int plane = worldView.getPlane();
		int verticalOffset = config.verticalOffset();

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
			if (player.getOverheadIcon() == null)
			{
				continue;
			}
			if (config.localPlayerOnly() && player != localPlayer)
			{
				continue;
			}

			net.runelite.api.SpritePixels gameIcon = plugin.getGameIcon(player.getOverheadIcon());
			if (gameIcon == null)
			{
				continue;
			}

			// The game projects logicalHeight + 15, then stacks names, health bars,
			// skulls and the prayer icon upward in fixed screen pixels. Capture only
			// the icon-width lane; the apply pass locates the native sprite exactly.
			Point canvas = iconCanvasPoint(client, player, plane, NATIVE_ANCHOR_HEIGHT);
			if (canvas == null)
			{
				continue;
			}

			int nativeX = canvas.getX() - gameIcon.getMaxWidth() / 2 + gameIcon.getOffsetX()
				- NATIVE_EDGE_MARGIN;
			int nativeY = canvas.getY() - MAX_OVERHEAD_STACK_HEIGHT - NATIVE_EDGE_MARGIN;
			int nativeWidth = gameIcon.getWidth() + NATIVE_EDGE_MARGIN * 2;
			int nativeHeight = MAX_OVERHEAD_STACK_HEIGHT + NATIVE_EDGE_MARGIN * 2;
			int x0 = Math.max(0, nativeX);
			int y0 = Math.max(0, nativeY);
			int x1 = Math.min(bufferWidth, nativeX + nativeWidth);
			int y1 = Math.min(bufferHeight, nativeY + nativeHeight);
			if (x1 <= x0 || y1 <= y0)
			{
				continue;
			}

			int regionWidth = x1 - x0;
			int regionHeight = y1 - y0;
			int regionSize = regionWidth * regionHeight;

			// Keep native sprite bounds as the baseline. Apply only the projection
			// delta for a vertical offset, which naturally accounts for zoom.
			Point targetCanvas = iconCanvasPoint(client, player, plane,
				NATIVE_ANCHOR_HEIGHT + verticalOffset);
			int targetX = x0;
			int targetY = y0;
			if (verticalOffset != 0 && targetCanvas != null && canvas != null)
			{
				targetX += targetCanvas.getX() - canvas.getX();
				targetY += targetCanvas.getY() - canvas.getY();
			}

			scratch = plugin.ensureScratch(scratchOffset + regionSize * 5);
			int origBgOffset = scratchOffset;
			int targetBgOffset = scratchOffset + regionSize;
			int iconOffset = scratchOffset + regionSize * 2;
			int maskOffset = scratchOffset + regionSize * 3;
			int eraseMaskOffset = scratchOffset + regionSize * 4;

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
				targetX, targetY, canvas.getX(),
				origBgOffset, targetBgOffset, iconOffset, maskOffset, eraseMaskOffset,
				gameIcon, player.getOverheadIcon(), player.getName(),
				config.iconStyle() == PrayerTransparencyConfig.IconStyle.CLEAN
					? plugin.getCleanIcon(player.getOverheadIcon())
					: null));
			scratchOffset += regionSize * 5;
			plugin.debugEvery("capture-" + player.getName(),
				"capture: player={}, icon={}, logicalHeight={}, anchor=({},{}), lane=({},{} {}x{}), target=({},{}), verticalOffset={}, gpu={}",
				player.getName(), player.getOverheadIcon(), player.getLogicalHeight(), canvas.getX(), canvas.getY(),
				x0, y0, regionWidth, regionHeight, targetX, targetY, verticalOffset, client.isGpu());
		}

		return null;
	}

	/**
	 * Projects the actor anchor used by the native overhead renderer.
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
