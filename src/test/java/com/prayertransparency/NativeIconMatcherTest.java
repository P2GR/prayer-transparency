package com.prayertransparency;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativeIconMatcherTest
{
	private static final int BACKGROUND = 0x101010;
	private static final int PRAYER = 0xC08040;
	private static final int HEALTH_BAR = 0x00FF00;

	@Test
	public void findsNativeSpriteByColor()
	{
		Fixture fixture = new Fixture(12, 16, 5, 5);
		fixture.paintPrayer(6);

		NativeIconMatcher.Match match = fixture.match();

		assertTrue(match.isAccepted());
		assertEquals(6, match.getTop());
		assertEquals(25, match.getMatches());
	}

	@Test
	public void acceptsPartiallyOccludedPrayerWithoutMatchingOccluder()
	{
		Fixture fixture = new Fixture(12, 16, 5, 5);
		fixture.paintPrayer(6);
		fixture.paintHorizontalBar(6, 2, 2);

		NativeIconMatcher.Match match = fixture.match();

		assertTrue(match.isAccepted());
		assertEquals(15, match.getMatches());
		assertTrue(NativeIconMatcher.isNativeFootprintPixel(PRAYER, BACKGROUND, PRAYER));
		assertTrue(NativeIconMatcher.isNativeFootprintPixel(HEALTH_BAR, BACKGROUND, PRAYER));
	}

	@Test
	public void rejectsHealthBarWithoutPrayer()
	{
		Fixture fixture = new Fixture(12, 16, 5, 5);
		fixture.paintHorizontalBar(6, 0, 5);

		NativeIconMatcher.Match match = fixture.match();

		assertFalse(match.isAccepted());
		assertEquals(0, match.getMatches());
	}

	@Test
	public void findsSpriteClippedByTopOfCanvas()
	{
		Fixture fixture = new Fixture(12, 10, 5, 5);
		fixture.paintPrayer(-2);

		NativeIconMatcher.Match match = fixture.match();

		assertTrue(match.isAccepted());
		assertEquals(-2, match.getTop());
		assertEquals(15, match.getMatches());
	}

	@Test
	public void findsHorizontalRoundingOffset()
	{
		Fixture fixture = new Fixture(12, 16, 5, 5);
		fixture.paintPrayer(6, 4);

		NativeIconMatcher.Match match = fixture.match(12, -3);

		assertTrue(match.isAccepted());
		assertEquals(6, match.getTop());
		assertEquals(-4, match.getSourceX());
		assertEquals(25, match.getMatches());
	}

	@Test
	public void supportsSmallRoundingAndPremultipliedGpuColor()
	{
		assertTrue(NativeIconMatcher.matchesColor(0xC1813F, PRAYER));
		assertTrue(NativeIconMatcher.matchesColor(0x80402010, 0x804020));
		assertFalse(NativeIconMatcher.matchesColor(HEALTH_BAR, PRAYER));
	}

	@Test
	public void blocksForeignTargetPixels()
	{
		assertTrue(NativeIconMatcher.isSafeTargetPixel(BACKGROUND, BACKGROUND, false));
		assertTrue(NativeIconMatcher.isSafeTargetPixel(PRAYER, BACKGROUND, true));
		assertFalse(NativeIconMatcher.isSafeTargetPixel(HEALTH_BAR, BACKGROUND, false));
	}

	@Test
	public void erasesEveryChangedPixelInAcceptedNativeFootprint()
	{
		assertTrue(NativeIconMatcher.isNativeFootprintPixel(PRAYER, BACKGROUND, PRAYER));
		assertTrue(NativeIconMatcher.isNativeFootprintPixel(0xC1813F, BACKGROUND, PRAYER));
		assertTrue(NativeIconMatcher.isNativeFootprintPixel(HEALTH_BAR, BACKGROUND, PRAYER));
		assertFalse(NativeIconMatcher.isNativeFootprintPixel(BACKGROUND, BACKGROUND, PRAYER));
		assertFalse(NativeIconMatcher.isNativeFootprintPixel(PRAYER, BACKGROUND, 0));
	}

	@Test
	public void erasesOnlyChangedPixelsTouchingOpaqueSpriteAsFringe()
	{
		int[] sprite = {
			0, 0, 0,
			0, PRAYER, 0,
			0, 0, 0
		};

		assertTrue(NativeIconMatcher.isNativeFringePixel(PRAYER, BACKGROUND,
			sprite, 3, 3, 0, 0));
		assertTrue(NativeIconMatcher.isNativeFringePixel(PRAYER, BACKGROUND,
			sprite, 3, 3, 2, 2));
		assertFalse(NativeIconMatcher.isNativeFringePixel(BACKGROUND, BACKGROUND,
			sprite, 3, 3, 0, 0));
		assertFalse(NativeIconMatcher.isNativeFringePixel(PRAYER, BACKGROUND,
			sprite, 3, 3, -1, -1));
	}

	private static final class Fixture
	{
		private final int bufferWidth;
		private final int regionHeight;
		private final int spriteWidth;
		private final int spriteHeight;
		private final int[] pixels;
		private final int[] background;
		private final int[] sprite;

		private Fixture(int bufferWidth, int regionHeight, int spriteWidth, int spriteHeight)
		{
			this.bufferWidth = bufferWidth;
			this.regionHeight = regionHeight;
			this.spriteWidth = spriteWidth;
			this.spriteHeight = spriteHeight;
			pixels = new int[bufferWidth * regionHeight];
			background = new int[pixels.length];
			sprite = new int[spriteWidth * spriteHeight];
			java.util.Arrays.fill(pixels, BACKGROUND);
			java.util.Arrays.fill(background, BACKGROUND);
			java.util.Arrays.fill(sprite, PRAYER);
		}

		private void paintPrayer(int top)
		{
			paintPrayer(top, 0);
		}

		private void paintPrayer(int top, int left)
		{
			for (int y = 0; y < spriteHeight; y++)
			{
				int targetY = top + y;
				if (targetY < 0 || targetY >= regionHeight)
				{
					continue;
				}
				for (int x = 0; x < spriteWidth; x++)
				{
					pixels[targetY * bufferWidth + left + x] = PRAYER;
				}
			}
		}

		private void paintHorizontalBar(int top, int firstRow, int rows)
		{
			for (int y = firstRow; y < firstRow + rows; y++)
			{
				int targetY = top + y;
				if (targetY < 0 || targetY >= regionHeight)
				{
					continue;
				}
				for (int x = 0; x < spriteWidth; x++)
				{
					pixels[targetY * bufferWidth + x] = HEALTH_BAR;
				}
			}
		}

		private NativeIconMatcher.Match match()
		{
			return match(spriteWidth, 0);
		}

		private NativeIconMatcher.Match match(int regionWidth, int sourceX)
		{
			return NativeIconMatcher.find(pixels, bufferWidth, 0, 0,
				regionWidth, regionHeight, background, 0, bufferWidth,
				sprite, spriteWidth, spriteHeight, sourceX);
		}
	}
}
