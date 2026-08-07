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
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;

/**
 * Runs in {@link OverlayLayer#UNDER_WIDGETS}, i.e. after the game has drawn the
 * overhead head icons but before the interfaces.
 * <p>
 * For every region captured by {@link PrayerTransparencyCaptureOverlay} this
 * blends the (opaque) head icon that is now in the buffer with the scene
 * snapshot taken before the icon was drawn:
 * <pre>
 *   result = opacity * icon + (1 - opacity) * scene
 * </pre>
 * Pixels the icon did not touch are identical in both images and therefore
 * unchanged, so over-covering the icon with a generous region is harmless.
 */
@Slf4j
class PrayerTransparencyApplyOverlay extends Overlay
{
	private static final int LOG_EVERY_N_FRAMES = 60;

	private final PrayerTransparencyPlugin plugin;
	private final PrayerTransparencyConfig config;

	private int frameCounter;

	PrayerTransparencyApplyOverlay(PrayerTransparencyPlugin plugin, PrayerTransparencyConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setLayer(OverlayLayer.UNDER_WIDGETS);
		// Run last so the blend is applied over anything else drawn in this layer.
		setPriority(Overlay.PRIORITY_LOW);
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
		if (regions.isEmpty())
		{
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

		boolean debug = config.debugLogging();
		int[] scratch = plugin.getScratch();
		// Config is a 0-10 transparency scale (0 = fully visible, 10 = 250/255
		// transparent); convert to an alpha in 0-255 where 255 = fully opaque.
		int alpha = 255 - config.transparency() * 25;
		int inverseAlpha = 255 - alpha;
		boolean gpu = client.isGpu();
		int touchedPixels = 0;

		for (HeadIconRegion region : regions)
		{
			touchedPixels += eraseAndRedraw(pixels, bufferWidth, bufferHeight, scratch, region, alpha, inverseAlpha, gpu);
		}

		if (debug)
		{
			// Visual markers: magenta box where the game drew the icon, orange box
			// at the target location it is moved to.
			graphics.setColor(Color.MAGENTA);
			for (HeadIconRegion region : regions)
			{
				graphics.drawRect(region.getX(), region.getY(),
					region.getWidth() - 1, region.getHeight() - 1);
			}
			graphics.setColor(Color.ORANGE);
			for (HeadIconRegion region : regions)
			{
				graphics.drawRect(region.getTargetX(), region.getTargetY(),
					region.getWidth() - 1, region.getHeight() - 1);
			}

			// Self-test: paint a fixed 60x60 box at (40,40) straight into the buffer,
			// blended toward red. If this box is visible, raw buffer writes are being
			// displayed; if it is missing, we are writing to the wrong buffer.
			blendTestBox(pixels, bufferWidth, bufferHeight, 40, 40, 60, 60, alpha, inverseAlpha, gpu);

			frameCounter++;
			if (frameCounter % LOG_EVERY_N_FRAMES == 0)
			{
				log.debug("Apply: regions={} touchedPixels={} opacity={} gpu={}",
					regions.size(), touchedPixels, alpha, gpu);
				if (touchedPixels == 0)
				{
					log.warn("Apply: no icon pixels found in any region - the overhead icon was not "
						+ "drawn into this buffer between drawScene and drawAboveOverheads");
				}
			}
		}

		regions.clear();
		return null;
	}

	private static void blendTestBox(int[] pixels, int bufferWidth, int bufferHeight,
		int x, int y, int w, int h, int alpha, int inverseAlpha, boolean gpu)
	{
		if (x < 0 || y < 0 || x >= bufferWidth || y >= bufferHeight)
		{
			return;
		}
		if (x + w > bufferWidth)
		{
			w = bufferWidth - x;
		}
		if (y + h > bufferHeight)
		{
			h = bufferHeight - y;
		}
		if (w <= 0 || h <= 0)
		{
			return;
		}

		for (int row = 0; row < h; row++)
		{
			int dest = (y + row) * bufferWidth + x;
			for (int column = 0; column < w; column++)
			{
				int current = pixels[dest + column];
				if (gpu)
				{
					// Premultiplied red: RGB = (alpha, 0, 0) with alpha channel = alpha.
					pixels[dest + column] = (alpha << 24) | (alpha << 16);
				}
				else
				{
					int red = ((current >>> 16 & 0xFF) * alpha + 255 * inverseAlpha) / 255;
					int green = ((current >>> 8 & 0xFF) * alpha) / 255;
					int blue = ((current & 0xFF) * alpha) / 255;
					pixels[dest + column] = (current & 0xFF000000) | red << 16 | green << 8 | blue;
				}
			}
		}
	}

	/**
	 * Erases the game's icon from its original location and redraws its exact
	 * pixels at the target height with the configured transparency.
	 * <p>
	 * Three passes so the original and target regions can overlap safely:
	 * <ol>
	 *   <li>detect the icon pixels (anything differing from the captured scene)
	 *       from the untouched buffer, storing them and a 0/1 mask</li>
	 *   <li>erase: restore the captured scene over the original location</li>
	 *   <li>redraw: write the icon pixels at the target location, blended with
	 *       the captured target scene (software) or premultiplied with an alpha
	 *       channel (GPU)</li>
	 * </ol>
	 *
	 * @return the number of icon pixels handled
	 */
	private static int eraseAndRedraw(int[] pixels, int bufferWidth, int bufferHeight, int[] scratch,
		HeadIconRegion region, int alpha, int inverseAlpha, boolean gpu)
	{
		int x0 = region.getX();
		int y0 = region.getY();
		int width = region.getWidth();
		int height = region.getHeight();
		int targetX = region.getTargetX();
		int targetY = region.getTargetY();
		int origBgOffset = region.getOrigBgOffset();
		int targetBgOffset = region.getTargetBgOffset();
		int iconOffset = region.getIconOffset();
		int maskOffset = region.getMaskOffset();
		int touched = 0;

		// Clamp the original region against the current buffer in case the canvas
		// was resized mid-frame. The mask/icon scratch is indexed by the original
		// (dx, dy) layout, so width/height are shared with the target mapping.
		if (x0 < 0 || y0 < 0 || x0 >= bufferWidth || y0 >= bufferHeight)
		{
			return 0;
		}
		if (x0 + width > bufferWidth)
		{
			width = bufferWidth - x0;
		}
		if (y0 + height > bufferHeight)
		{
			height = bufferHeight - y0;
		}
		if (width <= 0 || height <= 0)
		{
			return 0;
		}

		// Pass 1: detect icon pixels from the untouched buffer.
		for (int row = 0; row < height; row++)
		{
			int dest = (y0 + row) * bufferWidth + x0;
			int slot = row * width;
			for (int column = 0; column < width; column++, dest++, slot++)
			{
				int current = pixels[dest];
				int background = scratch[origBgOffset + slot];
				if (current == background)
				{
					scratch[maskOffset + slot] = 0;
					continue;
				}
				scratch[maskOffset + slot] = 1;
				scratch[iconOffset + slot] = current;
				touched++;
			}
		}

		// Pass 2: erase the game's icon (restore the captured scene).
		for (int row = 0; row < height; row++)
		{
			int dest = (y0 + row) * bufferWidth + x0;
			int slot = row * width;
			for (int column = 0; column < width; column++, dest++, slot++)
			{
				if (scratch[maskOffset + slot] != 0)
				{
					pixels[dest] = scratch[origBgOffset + slot];
				}
			}
		}

		// Pass 3: redraw the icon at the target height. If a clean prayer book
		// sprite is configured, draw that instead of the game's captured icon
		// (which includes the beige overhead circle).
		if (region.getCleanIcon() != null)
		{
			drawCleanIcon(pixels, bufferWidth, bufferHeight, scratch, region, alpha, inverseAlpha, gpu);
		}
		else
		{
			for (int row = 0; row < height; row++)
			{
				int targetYRow = targetY + row;
				if (targetYRow < 0 || targetYRow >= bufferHeight)
				{
					continue;
				}
				int slot = row * width;
				for (int column = 0; column < width; column++, slot++)
				{
					if (scratch[maskOffset + slot] == 0)
					{
						continue;
					}
					int targetXColumn = targetX + column;
					if (targetXColumn < 0 || targetXColumn >= bufferWidth)
					{
						continue;
					}

					int iconRGB = scratch[iconOffset + slot];
					int dest = targetYRow * bufferWidth + targetXColumn;
					if (gpu)
					{
						// GPU mode: the buffer is ARGB and is composited over the GPU scene
						// with glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA). The fragui.glsl
						// shader outputs the texel RGB at full strength (no premultiply), so
						// to get result = icon * opacity + scene * (1 - opacity) we must
						// premultiply the icon RGB by the opacity ourselves AND set the alpha.
						int red = (iconRGB >>> 16 & 0xFF) * alpha / 255;
						int green = (iconRGB >>> 8 & 0xFF) * alpha / 255;
						int blue = (iconRGB & 0xFF) * alpha / 255;
						pixels[dest] = (alpha << 24) | red << 16 | green << 8 | blue;
					}
					else
					{
						// Software mode: the buffer is presented directly, so blend the icon
						// toward the scene snapshot taken at the target location.
						int targetBg = scratch[targetBgOffset + slot];
						int red = ((iconRGB >>> 16 & 0xFF) * alpha + (targetBg >>> 16 & 0xFF) * inverseAlpha) / 255;
						int green = ((iconRGB >>> 8 & 0xFF) * alpha + (targetBg >>> 8 & 0xFF) * inverseAlpha) / 255;
						int blue = ((iconRGB & 0xFF) * alpha + (targetBg & 0xFF) * inverseAlpha) / 255;
						pixels[dest] = (pixels[dest] & 0xFF000000) | red << 16 | green << 8 | blue;
					}
				}
			}
		}

		return touched;
	}

	/**
	 * Draws the clean prayer book sprite (no beige circle) at the exact
	 * position of the game's icon, scaled uniformly to the icon's on-screen
	 * size, with the configured transparency.
	 * <p>
	 * The icon's extent is measured from a central band of the (tall) region
	 * only - the game also draws overhead text below the icon between the two
	 * callbacks, which would otherwise stretch the measured bounding box
	 * vertically and squash the sprite.
	 */
	private static void drawCleanIcon(int[] pixels, int bufferWidth, int bufferHeight, int[] scratch,
		HeadIconRegion region, int alpha, int inverseAlpha, boolean gpu)
	{
		int width = region.getWidth();
		int height = region.getHeight();
		int targetX = region.getTargetX();
		int targetY = region.getTargetY();
		int maskOffset = region.getMaskOffset();
		int targetBgOffset = region.getTargetBgOffset();
		net.runelite.api.SpritePixels sprite = region.getCleanIcon();

		int spriteW = sprite.getWidth();
		int spriteH = sprite.getHeight();
		int[] spritePixels = sprite.getPixels();
		if (spritePixels == null || spriteW <= 0 || spriteH <= 0)
		{
			return;
		}

		// Measure the icon's on-screen extent from a band around the region
		// center. The band is deliberately narrow so overhead text drawn below
		// the icon does not inflate the height.
		int bandHalf = Math.min(56, height / 2);
		int regionCenterY = height / 2;
		int minX = width, minY = height, maxX = -1, maxY = -1;
		for (int row = regionCenterY - bandHalf; row <= regionCenterY + bandHalf; row++)
		{
			if (row < 0 || row >= height)
			{
				continue;
			}
			int slot = row * width;
			for (int column = 0; column < width; column++, slot++)
			{
				if (scratch[maskOffset + slot] != 0)
				{
					if (column < minX) minX = column;
					if (column > maxX) maxX = column;
					if (row < minY) minY = row;
					if (row > maxY) maxY = row;
				}
			}
		}
		if (maxX < minX || maxY < minY)
		{
			return;
		}

		// Uniform scale (preserve the sprite's aspect) so the icon is never
		// squashed, sized to the game icon's larger extent.
		int iconW = maxX - minX + 1;
		int iconH = maxY - minY + 1;
		int iconSize = Math.max(iconW, iconH);
		int drawW = iconSize;
		int drawH = Math.max(1, iconSize * spriteH / spriteW);

		// Center on the detected icon's actual center (i.e. exactly where the
		// game drew it), expressed in target-region coordinates so a vertical
		// offset still applies.
		int centerX = targetX + (minX + maxX) / 2;
		int centerY = targetY + (minY + maxY) / 2;
		int left = centerX - drawW / 2;
		int top = centerY - drawH / 2;

		for (int dy = 0; dy < drawH; dy++)
		{
			int srcY = dy * spriteH / drawH;
			int dstY = top + dy;
			if (dstY < 0 || dstY >= bufferHeight)
			{
				continue;
			}
			int dyr = dstY - targetY;
			for (int dx = 0; dx < drawW; dx++)
			{
				int srcX = dx * spriteW / drawW;
				int argb = spritePixels[srcY * spriteW + srcX];
				if (argb == 0)
				{
					// OSRS sprites use 0 as the transparent mask index.
					continue;
				}
				int dstX = left + dx;
				if (dstX < 0 || dstX >= bufferWidth)
				{
					continue;
				}

				int rgb = argb & 0xFFFFFF;
				int dest = dstY * bufferWidth + dstX;
				if (gpu)
				{
					int red = (rgb >>> 16 & 0xFF) * alpha / 255;
					int green = (rgb >>> 8 & 0xFF) * alpha / 255;
					int blue = (rgb & 0xFF) * alpha / 255;
					pixels[dest] = (alpha << 24) | red << 16 | green << 8 | blue;
				}
				else
				{
					int dxr = dstX - targetX;
					int targetBg = 0;
					if (dxr >= 0 && dyr >= 0 && dxr < width && dyr < height)
					{
						targetBg = scratch[targetBgOffset + dyr * width + dxr];
					}
					int red = ((rgb >>> 16 & 0xFF) * alpha + (targetBg >>> 16 & 0xFF) * inverseAlpha) / 255;
					int green = ((rgb >>> 8 & 0xFF) * alpha + (targetBg >>> 8 & 0xFF) * inverseAlpha) / 255;
					int blue = ((rgb & 0xFF) * alpha + (targetBg & 0xFF) * inverseAlpha) / 255;
					pixels[dest] = (pixels[dest] & 0xFF000000) | red << 16 | green << 8 | blue;
				}
			}
		}
	}
}
