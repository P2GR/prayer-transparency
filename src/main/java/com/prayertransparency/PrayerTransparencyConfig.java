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

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(PrayerTransparencyConfig.GROUP)
public interface PrayerTransparencyConfig extends Config
{
	String GROUP = "prayertransparency";
	@ConfigItem(
		keyName = "enabled",
		name = "Transparent overhead prayers",
		description = "Makes overhead prayer icons above players semi-transparent",
		position = 0
	)
	default boolean enabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "transparency",
		name = "Overhead prayer transparency",
		description = "Transparency of the overhead prayer icons. 0 = fully visible, 10 = almost invisible (250/255 transparent)",
		position = 1
	)
	@Range(min = 0, max = 10)
	default int transparency()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "localPlayerOnly",
		name = "Local player only",
		description = "Only make your own overhead prayer transparent instead of all players",
		position = 2
	)
	default boolean localPlayerOnly()
	{
		return false;
	}

	@ConfigItem(
		keyName = "verticalOffset",
		name = "Icon vertical offset",
		description = "Moves the prayer icon up (positive) or down (negative) from its normal height, in local units (128 = one tile)",
		position = 3
	)
	@Range(min = -256, max = 256)
	default int verticalOffset()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "iconStyle",
		name = "Prayer icon style",
		description = "Game = redraw the game's own icon (includes the beige circle). Clean = replace it with the prayer book icon (no circle)",
		position = 4
	)
	default IconStyle iconStyle()
	{
		return IconStyle.GAME;
	}

	enum IconStyle
	{
		GAME,
		CLEAN
	}

}
