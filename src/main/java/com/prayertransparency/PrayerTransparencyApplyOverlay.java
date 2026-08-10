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
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

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
 * The native sprite is matched inside each captured lane, so only prayer
 * pixels are erased and redrawn.
 */
class PrayerTransparencyApplyOverlay extends Overlay
{
	private static final int ERASE_ONLY_PIXEL = -1;
	private final PrayerTransparencyPlugin plugin;
	private final PrayerTransparencyConfig config;

	PrayerTransparencyApplyOverlay(PrayerTransparencyPlugin plugin, PrayerTransparencyConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setLayer(OverlayLayer.UNDER_WIDGETS);
		setPosition(OverlayPosition.DYNAMIC);
		// Run before normal scene overlays. They should render over the adjusted
		// prayer, and must never become input to the native-sprite matcher.
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

		int[] scratch = plugin.getScratch();
		// Config is a 0-10 transparency scale (0 = fully visible, 10 = 250/255
		// transparent); convert to an alpha in 0-255 where 255 = fully opaque.
		int transparency = Math.max(0, Math.min(10, config.transparency()));
		int alpha = 255 - transparency * 25;
		int inverseAlpha = 255 - alpha;
		boolean gpu = client.isGpu();

		List<PreparedRegion> prepared = new ArrayList<>(regions.size());
		for (HeadIconRegion region : regions)
		{
			PreparedRegion preparedRegion = prepareRegion(plugin, pixels, bufferWidth, bufferHeight,
				scratch, region);
			if (preparedRegion != null)
			{
				prepared.add(preparedRegion);
			}
		}
		prepared = removeDuplicateMatches(prepared);
		for (PreparedRegion preparedRegion : prepared)
		{
			prepareDrawMask(pixels, bufferWidth, bufferHeight, scratch, preparedRegion);
		}

		// All detection must happen against the untouched post-overhead buffer.
		// Otherwise one nearby player's redraw can become another player's input.
		for (PreparedRegion preparedRegion : prepared)
		{
			restoreOriginal(pixels, bufferWidth, scratch, preparedRegion);
		}
		for (PreparedRegion preparedRegion : prepared)
		{
			drawRegion(pixels, bufferWidth, bufferHeight, scratch, preparedRegion,
				alpha, inverseAlpha, gpu);
		}

		regions.clear();
		return null;
	}

	/**
	 * Erases the game's icon from its original location and redraws its exact
	 * pixels at the target height with the configured transparency.
	 * <p>
	 * Three passes so the original and target regions can overlap safely:
	 * <ol>
	 *   <li>detect native icon pixels from the untouched buffer, storing them
	 *       and a 0/1 mask</li>
	 *   <li>erase: restore the captured scene over the original location</li>
	 *   <li>redraw: write the icon pixels at the target location, blended with
	 *       the captured target scene (software) or premultiplied with an alpha
	 *       channel (GPU)</li>
	 * </ol>
	 *
	 * @return prepared region, or {@code null} when matching is unsafe
	 */
	private static PreparedRegion prepareRegion(PrayerTransparencyPlugin plugin, int[] pixels,
		int bufferWidth, int bufferHeight, int[] scratch, HeadIconRegion region)
	{
		int x0 = region.getX();
		int y0 = region.getY();
		int stride = region.getWidth();
		int width = Math.min(stride, bufferWidth - x0);
		int height = Math.min(region.getHeight(), bufferHeight - y0);
		int origBgOffset = region.getOrigBgOffset();
		int iconOffset = region.getIconOffset();
		int maskOffset = region.getMaskOffset();
		int touched = 0;
		int fringe = 0;

		// Clamp the original region against the current buffer in case the canvas
		// was resized mid-frame. The mask/icon scratch is indexed by the original
		// (dx, dy) layout, so width/height are shared with the target mapping.
		if (x0 < 0 || y0 < 0 || x0 >= bufferWidth || y0 >= bufferHeight)
		{
			return null;
		}
		if (width <= 0 || height <= 0)
		{
			return null;
		}

		NativeIconMatcher.Match match = findNativeIcon(pixels, bufferWidth, scratch, region,
			width, height, stride);
		String debugKey = "match-" + region.getPlayerName() + '-' + region.getHeadIcon();
		if (!match.isAccepted())
		{
			plugin.debugEvery(debugKey,
				"match rejected: player={}, icon={}, bestTop={}, screenY={}, matches={}, changed={}, opaque={}, ratio={}%, lane=({},{} {}x{}), sprite={}x{}",
				region.getPlayerName(), region.getHeadIcon(), match.getTop(),
				match.getOpaque() == 0 ? -1 : region.getY() + match.getTop(), match.getMatches(), match.getChanged(),
				match.getOpaque(), match.ratio(), region.getX(), region.getY(), width, height,
				region.getNativeIcon().getWidth(), region.getNativeIcon().getHeight());
			return null;
		}
		int nativeTop = match.getTop();
		net.runelite.api.SpritePixels nativeIcon = region.getNativeIcon();
		int nativeWidth = nativeIcon.getWidth();
		int nativeHeight = nativeIcon.getHeight();
		int expectedSourceX = x0 - (region.getIconX() - nativeIcon.getMaxWidth() / 2
			+ nativeIcon.getOffsetX());
		int nativeSourceX = match.getSourceX();
		int[] nativePixels = nativeIcon.getPixels();

		// Matching establishes ownership of the opaque sprite footprint. Erase
		// every changed footprint pixel: color-based exceptions leave fragments
		// when camera movement changes rendering or another overhead overlaps it.
		for (int row = 0; row < height; row++)
		{
			int dest = (y0 + row) * bufferWidth + x0;
			int slot = row * stride;
			for (int column = 0; column < width; column++, dest++, slot++)
			{
				scratch[maskOffset + slot] = 0;
				int spriteX = nativeSourceX + column;
				int spriteY = row - nativeTop;
				int current = pixels[dest];
				int background = scratch[origBgOffset + slot];
				boolean insideNative = spriteX >= 0 && spriteX < nativeWidth
					&& spriteY >= 0 && spriteY < nativeHeight;
				int nativePixel = insideNative ? nativePixels[spriteY * nativeWidth + spriteX] : 0;
				if (NativeIconMatcher.isNativeFootprintPixel(current, background, nativePixel))
				{
					scratch[maskOffset + slot] = 1;
					scratch[iconOffset + slot] = nativePixel & 0xFFFFFF;
					touched++;
				}
				else if (nativePixel == 0
					&& NativeIconMatcher.isNativeFringePixel(current, background, nativePixels,
						nativeWidth, nativeHeight, spriteX, spriteY))
				{
					// Remove renderer fringe, but never redraw it at the shifted target.
					scratch[maskOffset + slot] = 1;
					scratch[iconOffset + slot] = ERASE_ONLY_PIXEL;
					touched++;
					fringe++;
				}
			}
		}

		plugin.debugEvery(debugKey,
			"match accepted: player={}, icon={}, top={}, screenY={}, xShift={}, matches={}, changed={}, opaque={}, ratio={}%, touched={}, fringe={}",
			region.getPlayerName(), region.getHeadIcon(), nativeTop, region.getY() + nativeTop,
			expectedSourceX - nativeSourceX, match.getMatches(), match.getChanged(),
			match.getOpaque(), match.ratio(), touched, fringe);

		return new PreparedRegion(region, width, height, stride, nativeTop, nativeSourceX);
	}

	private static void restoreOriginal(int[] pixels, int bufferWidth, int[] scratch,
		PreparedRegion prepared)
	{
		HeadIconRegion region = prepared.region;
		int x0 = region.getX();
		int y0 = region.getY();
		int maskOffset = region.getMaskOffset();
		int origBgOffset = region.getOrigBgOffset();

		// Restore every native icon before any target icon is drawn. This removes
		// all normal game prayers, including ones covered by another region.
		for (int row = 0; row < prepared.height; row++)
		{
			int dest = (y0 + row) * bufferWidth + x0;
			int slot = row * prepared.stride;
			for (int column = 0; column < prepared.width; column++, dest++, slot++)
			{
				if (scratch[maskOffset + slot] != 0)
				{
					pixels[dest] = scratch[origBgOffset + slot];
				}
			}
		}
	}

	private static void prepareDrawMask(int[] pixels, int bufferWidth, int bufferHeight,
		int[] scratch, PreparedRegion prepared)
	{
		HeadIconRegion region = prepared.region;
		int targetX = region.getTargetX();
		int targetY = region.getTargetY();
		int drawMaskOffset = region.getDrawMaskOffset();
		int targetBgOffset = region.getTargetBgOffset();
		int sourceMaskOffset = region.getMaskOffset();
		for (int row = 0; row < prepared.height; row++)
		{
			int targetRow = targetY + row;
			int slot = row * prepared.stride;
			for (int column = 0; column < prepared.width; column++, slot++)
			{
				scratch[drawMaskOffset + slot] = 0;
				int targetColumn = targetX + column;
				if (targetColumn < 0 || targetColumn >= bufferWidth
					|| targetRow < 0 || targetRow >= bufferHeight)
				{
					continue;
				}

				int sourceColumn = targetColumn - region.getX();
				int sourceRow = targetRow - region.getY();
				boolean ownVisibleIcon = sourceColumn >= 0 && sourceColumn < prepared.width
					&& sourceRow >= 0 && sourceRow < prepared.height
					&& scratch[sourceMaskOffset + sourceRow * prepared.stride + sourceColumn] != 0;
				int current = pixels[targetRow * bufferWidth + targetColumn];
				int background = scratch[targetBgOffset + slot];
				if (NativeIconMatcher.isSafeTargetPixel(current, background, ownVisibleIcon))
				{
					scratch[drawMaskOffset + slot] = 1;
				}
			}
		}
	}

	private static List<PreparedRegion> removeDuplicateMatches(List<PreparedRegion> candidates)
	{
		List<PreparedRegion> unique = new ArrayList<>(candidates.size());
		for (PreparedRegion candidate : candidates)
		{
			boolean duplicate = false;
			for (PreparedRegion accepted : unique)
			{
				if (candidate.sameNativePlacement(accepted))
				{
					duplicate = true;
					break;
				}
			}
			if (!duplicate)
			{
				unique.add(candidate);
			}
		}
		return unique;
	}

	private static void drawRegion(int[] pixels, int bufferWidth, int bufferHeight, int[] scratch,
		PreparedRegion prepared, int alpha, int inverseAlpha, boolean gpu)
	{
		HeadIconRegion region = prepared.region;
		int width = prepared.width;
		int height = prepared.height;
		int stride = prepared.stride;
		int targetX = region.getTargetX();
		int targetY = region.getTargetY();
		int targetBgOffset = region.getTargetBgOffset();
		int iconOffset = region.getIconOffset();
		int maskOffset = region.getMaskOffset();
		int drawMaskOffset = region.getDrawMaskOffset();

		// Redraw the icon at target height. If clean prayer book
		// sprite is configured, draw that instead of the game's captured icon
		// (which includes the beige overhead circle).
		if (region.getCleanIcon() != null)
		{
			drawCleanIcon(pixels, bufferWidth, bufferHeight, scratch, prepared, alpha, inverseAlpha, gpu);
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
				int slot = row * stride;
				for (int column = 0; column < width; column++, slot++)
				{
					if (scratch[maskOffset + slot] == 0
						|| scratch[iconOffset + slot] == ERASE_ONLY_PIXEL
						|| scratch[drawMaskOffset + slot] == 0)
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
	}

	private static final class PreparedRegion
	{
		private final HeadIconRegion region;
		private final int width;
		private final int height;
		private final int stride;
		private final int nativeTop;
		private final int nativeSourceX;

		private PreparedRegion(HeadIconRegion region, int width, int height, int stride,
			int nativeTop, int nativeSourceX)
		{
			this.region = region;
			this.width = width;
			this.height = height;
			this.stride = stride;
			this.nativeTop = nativeTop;
			this.nativeSourceX = nativeSourceX;
		}

		private boolean sameNativePlacement(PreparedRegion other)
		{
			net.runelite.api.SpritePixels sprite = region.getNativeIcon();
			net.runelite.api.SpritePixels otherSprite = other.region.getNativeIcon();
			int left = region.getX() - nativeSourceX;
			int otherLeft = other.region.getX() - other.nativeSourceX;
			return left == otherLeft
				&& region.getY() + nativeTop == other.region.getY() + other.nativeTop
				&& sprite.getWidth() == otherSprite.getWidth()
				&& sprite.getHeight() == otherSprite.getHeight();
		}
	}

	private static NativeIconMatcher.Match findNativeIcon(int[] pixels, int bufferWidth, int[] scratch,
		HeadIconRegion region, int width, int height, int stride)
	{
		net.runelite.api.SpritePixels sprite = region.getNativeIcon();
		int spriteWidth = sprite.getWidth();
		int spriteHeight = sprite.getHeight();
		int[] spritePixels = sprite.getPixels();
		int sourceX = region.getX() - (region.getIconX() - sprite.getMaxWidth() / 2
			+ sprite.getOffsetX());
		return NativeIconMatcher.find(pixels, bufferWidth, region.getX(), region.getY(),
			width, height, scratch, region.getOrigBgOffset(), stride,
			spritePixels, spriteWidth, spriteHeight, sourceX);
	}

	/**
	 * Draws the clean prayer book sprite (no beige circle) at the exact
	 * position of the game's icon, scaled uniformly to the icon's on-screen
	 * size, with the configured transparency.
	 * <p>
	 * Size and placement come from the canonical native sprite, so partial
	 * occlusion cannot make the clean icon resize or jump between frames.
	 */
	private static void drawCleanIcon(int[] pixels, int bufferWidth, int bufferHeight, int[] scratch,
		PreparedRegion prepared, int alpha, int inverseAlpha, boolean gpu)
	{
		HeadIconRegion region = prepared.region;
		int width = prepared.width;
		int height = prepared.height;
		int stride = prepared.stride;
		int targetX = region.getTargetX();
		int targetY = region.getTargetY();
		int drawMaskOffset = region.getDrawMaskOffset();
		int targetBgOffset = region.getTargetBgOffset();
		net.runelite.api.SpritePixels sprite = region.getCleanIcon();

		int spriteW = sprite.getWidth();
		int spriteH = sprite.getHeight();
		int[] spritePixels = sprite.getPixels();
		if (spritePixels == null || spriteW <= 0 || spriteH <= 0)
		{
			return;
		}

		// Measure canonical native bounds, not the visible per-frame mask.
		net.runelite.api.SpritePixels nativeSprite = region.getNativeIcon();
		int nativeW = nativeSprite.getWidth();
		int nativeH = nativeSprite.getHeight();
		int[] nativePixels = nativeSprite.getPixels();
		int minNativeX = nativeW, minNativeY = nativeH, maxNativeX = -1, maxNativeY = -1;
		for (int nativeY = 0; nativeY < nativeH; nativeY++)
		{
			for (int nativeX = 0; nativeX < nativeW; nativeX++)
			{
				if (nativePixels[nativeY * nativeW + nativeX] != 0)
				{
					if (nativeX < minNativeX) minNativeX = nativeX;
					if (nativeX > maxNativeX) maxNativeX = nativeX;
					if (nativeY < minNativeY) minNativeY = nativeY;
					if (nativeY > maxNativeY) maxNativeY = nativeY;
				}
			}
		}
		if (maxNativeX < minNativeX || maxNativeY < minNativeY)
		{
			return;
		}

		int iconW = maxNativeX - minNativeX + 1;
		int iconH = maxNativeY - minNativeY + 1;
		int iconSize = Math.max(iconW, iconH);
		int drawW = iconSize;
		int drawH = Math.max(1, iconSize * spriteH / spriteW);

		int nativeSourceX = prepared.nativeSourceX;
		int minX = minNativeX - nativeSourceX;
		int maxX = maxNativeX - nativeSourceX;
		int minY = prepared.nativeTop + minNativeY;
		int maxY = prepared.nativeTop + maxNativeY;
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
				int dxr = dstX - targetX;
				if (dxr < 0 || dyr < 0 || dxr >= width || dyr >= height
					|| scratch[drawMaskOffset + dyr * stride + dxr] == 0)
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
					int targetBg = scratch[targetBgOffset + dyr * stride + dxr];
					int red = ((rgb >>> 16 & 0xFF) * alpha + (targetBg >>> 16 & 0xFF) * inverseAlpha) / 255;
					int green = ((rgb >>> 8 & 0xFF) * alpha + (targetBg >>> 8 & 0xFF) * inverseAlpha) / 255;
					int blue = ((rgb & 0xFF) * alpha + (targetBg & 0xFF) * inverseAlpha) / 255;
					pixels[dest] = (pixels[dest] & 0xFF000000) | red << 16 | green << 8 | blue;
				}
			}
		}
	}
}
