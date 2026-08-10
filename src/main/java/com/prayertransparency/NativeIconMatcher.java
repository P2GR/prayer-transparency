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

/**
 * Locates a native prayer sprite without treating unrelated changed pixels as
 * part of that sprite. Matching is intentionally conservative: a missed frame
 * leaves the native icon untouched, while a false positive can erase another
 * overhead element from the frame buffer.
 */
final class NativeIconMatcher
{
	private static final int MIN_MATCH_PERCENT = 25;
	private static final int MIN_MATCH_PIXELS = 8;
	private static final int MIN_CLIPPED_MATCH_PIXELS = 3;
	private static final int COLOR_TOLERANCE = 3;
	private static final int SAMPLE_COUNT = 16;
	private static final int MAX_HORIZONTAL_OFFSET = 2;

	private NativeIconMatcher()
	{
	}

	static Match find(int[] pixels, int bufferWidth, int regionX, int regionY,
		int regionWidth, int regionHeight, int[] background, int backgroundOffset,
		int backgroundStride, int[] spritePixels, int spriteWidth, int spriteHeight,
		int sourceX)
	{
		Match best = Match.NONE;
		int[] sampleX = new int[SAMPLE_COUNT];
		int[] sampleY = new int[SAMPLE_COUNT];
		for (int candidateSourceX = sourceX - MAX_HORIZONTAL_OFFSET;
			candidateSourceX <= sourceX + MAX_HORIZONTAL_OFFSET; candidateSourceX++)
		{
			int totalOpaque = countOpaque(spritePixels, spriteWidth, spriteHeight,
				candidateSourceX, regionWidth);
			if (totalOpaque == 0)
			{
				continue;
			}
			int sampleCount = buildSamples(spritePixels, spriteWidth, spriteHeight,
				candidateSourceX, regionWidth, totalOpaque, sampleX, sampleY);

			// Include candidates clipped by the top or bottom of the canvas.
			int firstTop = regionY == 0 ? -spriteHeight + 1 : 0;
			int lastTop = Math.max(firstTop, regionHeight - spriteHeight);
			for (int top = firstTop; top <= lastTop; top++)
			{
				if (top >= 0 && top + spriteHeight <= regionHeight
					&& !sampleMatches(pixels, bufferWidth, regionX, regionY,
						background, backgroundOffset, backgroundStride, spritePixels, spriteWidth,
						candidateSourceX, top, sampleX, sampleY, sampleCount))
				{
					continue;
				}
				int opaque = 0;
				int changed = 0;
				int matches = 0;
				for (int spriteY = 0; spriteY < spriteHeight; spriteY++)
				{
					int row = top + spriteY;
					if (row < 0 || row >= regionHeight)
					{
						continue;
					}

					int bufferIndex = (regionY + row) * bufferWidth + regionX;
					int backgroundIndex = backgroundOffset + row * backgroundStride;
					int spriteIndex = spriteY * spriteWidth + candidateSourceX;
					for (int column = 0; column < regionWidth;
						column++, bufferIndex++, backgroundIndex++, spriteIndex++)
					{
						int spriteX = candidateSourceX + column;
						if (spriteX < 0 || spriteX >= spriteWidth)
						{
							continue;
						}
						int nativePixel = spritePixels[spriteIndex];
						if (nativePixel == 0)
						{
							continue;
						}
						opaque++;
						int current = pixels[bufferIndex];
						if (current != background[backgroundIndex])
						{
							changed++;
							if (matchesColor(current, nativePixel))
							{
								matches++;
							}
						}
					}
				}

				if (opaque == 0)
				{
					continue;
				}
				Match candidate = new Match(top, candidateSourceX, matches, changed, opaque, false);
				if (candidate.isBetterThan(best))
				{
					best = candidate;
				}
			}
		}

		if (best == Match.NONE)
		{
			return best;
		}
		int ratioRequirement = (best.opaque * MIN_MATCH_PERCENT + 99) / 100;
		int absoluteRequirement = best.opaque >= MIN_MATCH_PIXELS
			? MIN_MATCH_PIXELS
			: Math.min(best.opaque, MIN_CLIPPED_MATCH_PIXELS);
		boolean accepted = best.matches >= Math.max(ratioRequirement, absoluteRequirement);
		return new Match(best.top, best.sourceX, best.matches, best.changed, best.opaque, accepted);
	}

	private static int countOpaque(int[] spritePixels, int spriteWidth, int spriteHeight,
		int sourceX, int regionWidth)
	{
		int count = 0;
		for (int y = 0; y < spriteHeight; y++)
		{
			for (int column = 0; column < regionWidth; column++)
			{
				int x = sourceX + column;
				if (x >= 0 && x < spriteWidth && spritePixels[y * spriteWidth + x] != 0)
				{
					count++;
				}
			}
		}
		return count;
	}

	private static int buildSamples(int[] spritePixels, int spriteWidth, int spriteHeight,
		int sourceX, int regionWidth, int totalOpaque, int[] sampleX, int[] sampleY)
	{
		int wanted = Math.min(SAMPLE_COUNT, totalOpaque);
		int seen = 0;
		int samples = 0;
		for (int y = 0; y < spriteHeight && samples < wanted; y++)
		{
			for (int column = 0; column < regionWidth && samples < wanted; column++)
			{
				int x = sourceX + column;
				if (x < 0 || x >= spriteWidth || spritePixels[y * spriteWidth + x] == 0)
				{
					continue;
				}
				if ((long) seen * wanted >= (long) totalOpaque * samples)
				{
					sampleX[samples] = column;
					sampleY[samples] = y;
					samples++;
				}
				seen++;
			}
		}
		return samples;
	}

	private static boolean sampleMatches(int[] pixels, int bufferWidth, int regionX,
		int regionY, int[] background, int backgroundOffset, int backgroundStride,
		int[] spritePixels, int spriteWidth, int sourceX, int top, int[] sampleX, int[] sampleY,
		int sampleCount)
	{
		for (int sample = 0; sample < sampleCount; sample++)
		{
			int row = top + sampleY[sample];
			int column = sampleX[sample];
			int current = pixels[(regionY + row) * bufferWidth + regionX + column];
			int backgroundPixel = background[backgroundOffset + row * backgroundStride + column];
			int nativePixel = spritePixels[sampleY[sample] * spriteWidth + sourceX + column];
			if (current != backgroundPixel && matchesColor(current, nativePixel))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Once the matcher has located the prayer sprite, every changed pixel under
	 * its opaque footprint must be removed. Trying to classify differently
	 * colored pixels as health bars/text leaves camera-dependent prayer fragments
	 * behind when the renderer transforms or partially covers the sprite.
	 */
	static boolean isNativeFootprintPixel(int current, int background, int nativePixel)
	{
		return nativePixel != 0 && current != background;
	}

	/**
	 * GPU texture filtering can draw a one-pixel fringe just outside the cache
	 * sprite's opaque mask. Once the sprite placement is confirmed, changed
	 * pixels touching that mask are part of the rendered prayer footprint too.
	 */
	static boolean isNativeFringePixel(int current, int background, int[] spritePixels,
		int spriteWidth, int spriteHeight, int spriteX, int spriteY)
	{
		if (current == background)
		{
			return false;
		}

		for (int y = Math.max(0, spriteY - 1); y <= Math.min(spriteHeight - 1, spriteY + 1); y++)
		{
			for (int x = Math.max(0, spriteX - 1); x <= Math.min(spriteWidth - 1, spriteX + 1); x++)
			{
				if (spritePixels[y * spriteWidth + x] != 0)
				{
					return true;
				}
			}
		}
		return false;
	}

	static boolean isSafeTargetPixel(int current, int background, boolean ownVisibleIcon)
	{
		return current == background || ownVisibleIcon;
	}

	/**
	 * Native sprites normally match exactly. Small tolerance covers renderer
	 * rounding; an alpha-aware comparison covers premultiplied GPU pixels.
	 */
	static boolean matchesColor(int current, int expected)
	{
		int currentRgb = current & 0xFFFFFF;
		int expectedRgb = expected & 0xFFFFFF;
		if (currentRgb == expectedRgb || channelsClose(currentRgb, expectedRgb))
		{
			return true;
		}

		int alpha = current >>> 24;
		if (alpha <= 0 || alpha >= 255)
		{
			return false;
		}
		int red = Math.min(255, (currentRgb >>> 16 & 0xFF) * 255 / alpha);
		int green = Math.min(255, (currentRgb >>> 8 & 0xFF) * 255 / alpha);
		int blue = Math.min(255, (currentRgb & 0xFF) * 255 / alpha);
		return channelsClose(red << 16 | green << 8 | blue, expectedRgb);
	}

	private static boolean channelsClose(int first, int second)
	{
		return Math.abs((first >>> 16 & 0xFF) - (second >>> 16 & 0xFF)) <= COLOR_TOLERANCE
			&& Math.abs((first >>> 8 & 0xFF) - (second >>> 8 & 0xFF)) <= COLOR_TOLERANCE
			&& Math.abs((first & 0xFF) - (second & 0xFF)) <= COLOR_TOLERANCE;
	}

	static final class Match
	{
		private static final Match NONE = new Match(-1, 0, 0, 0, 0, false);

		private final int top;
		private final int sourceX;
		private final int matches;
		private final int changed;
		private final int opaque;
		private final boolean accepted;

		private Match(int top, int sourceX, int matches, int changed, int opaque, boolean accepted)
		{
			this.top = top;
			this.sourceX = sourceX;
			this.matches = matches;
			this.changed = changed;
			this.opaque = opaque;
			this.accepted = accepted;
		}

		private boolean isBetterThan(Match other)
		{
			if (matches != other.matches)
			{
				return matches > other.matches;
			}
			if (opaque != 0 && other.opaque != 0
				&& (long) matches * other.opaque != (long) other.matches * opaque)
			{
				return (long) matches * other.opaque > (long) other.matches * opaque;
			}
			return changed > other.changed;
		}

		int getTop()
		{
			return top;
		}

		int getSourceX()
		{
			return sourceX;
		}

		int getMatches()
		{
			return matches;
		}

		int getChanged()
		{
			return changed;
		}

		int getOpaque()
		{
			return opaque;
		}

		boolean isAccepted()
		{
			return accepted;
		}

		int ratio()
		{
			return opaque == 0 ? 0 : matches * 100 / opaque;
		}
	}
}
