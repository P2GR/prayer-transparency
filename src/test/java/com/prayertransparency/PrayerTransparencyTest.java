package com.prayertransparency;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class PrayerTransparencyTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(PrayerTransparencyPlugin.class);
		RuneLite.main(args);
	}
}
