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

import javax.annotation.Nullable;
import net.runelite.api.SpritePixels;

/**
 * Describes one prayer icon: the region where the game drew it (original) and
 * the region it should be redrawn at (target, a vertical shift).
 * <p>
 * The pixel data lives in a shared scratch buffer owned by
 * {@link PrayerTransparencyPlugin}, laid out per region as four consecutive
 * {@code width * height} sections:
 * <pre>
 *   [captured scene behind the game icon] [captured scene at the target]
 *   [extracted icon pixels] [0/1 icon mask]
 * </pre>
 */
class HeadIconRegion
{
	private final int x;
	private final int y;
	private final int width;
	private final int height;
	/**
	 * Target origin in buffer coordinates. Unclamped - it may be negative or
	 * past the buffer edge when the icon is moved off-screen; writes are
	 * bounds-checked in the apply pass.
	 */
	private final int targetX;
	private final int targetY;
	private final int origBgOffset;
	private final int targetBgOffset;
	private final int iconOffset;
	private final int maskOffset;
	/**
	 * Optional clean prayer book sprite to draw instead of the game's icon
	 * (which includes the beige overhead circle). Null = draw the captured
	 * game pixels.
	 */
	@Nullable
	private final SpritePixels cleanIcon;

	HeadIconRegion(int x, int y, int width, int height, int targetX, int targetY,
		int origBgOffset, int targetBgOffset, int iconOffset, int maskOffset,
		@Nullable SpritePixels cleanIcon)
	{
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		this.targetX = targetX;
		this.targetY = targetY;
		this.origBgOffset = origBgOffset;
		this.targetBgOffset = targetBgOffset;
		this.iconOffset = iconOffset;
		this.maskOffset = maskOffset;
		this.cleanIcon = cleanIcon;
	}

	int getX()
	{
		return x;
	}

	int getY()
	{
		return y;
	}

	int getWidth()
	{
		return width;
	}

	int getHeight()
	{
		return height;
	}

	int getTargetX()
	{
		return targetX;
	}

	int getTargetY()
	{
		return targetY;
	}

	int getOrigBgOffset()
	{
		return origBgOffset;
	}

	int getTargetBgOffset()
	{
		return targetBgOffset;
	}

	int getIconOffset()
	{
		return iconOffset;
	}

	int getMaskOffset()
	{
		return maskOffset;
	}

	@Nullable
	SpritePixels getCleanIcon()
	{
		return cleanIcon;
	}
}
