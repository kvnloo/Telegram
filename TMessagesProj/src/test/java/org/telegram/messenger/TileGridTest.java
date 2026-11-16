package org.telegram.messenger;

import java.util.Arrays;

/** Plain-JVM checks for TileGrid. Run: javac -d out TileGrid.java TileGridTest.java && java -cp out org.telegram.messenger.TileGridTest */
public final class TileGridTest {
    private static int failures;

    public static void main(String[] args) {
        // Sample size: decoded pixels must stay at least as dense as screen pixels.
        eq("1:1 zoom decodes full resolution", 1, TileGrid.sampleSize(1f));
        eq("zoomed past 1:1 still full resolution", 1, TileGrid.sampleSize(3f));
        eq("half density allows sample 2", 2, TileGrid.sampleSize(0.5f));
        eq("0.3 px/src px rounds down to sample 2, never coarser than screen", 2, TileGrid.sampleSize(0.3f));
        eq("gigapixel fitted on a phone (40000 px into 1440) -> 16", 16, TileGrid.sampleSize(1440f / 40000f));

        // Tile enumeration covers exactly the visible area and clips to the image edge.
        int[] t = TileGrid.tiles(1300, 600, 1, new float[]{0, 0, 1300, 600});
        eq("3x2 grid for 1300x600 at 512", 6 * 4, t.length);
        eq("last column clipped to width", 1300, t[2 * 4 + 2]);
        eq("last row clipped to height", 600, t[5 * 4 + 3]);
        int[] one = TileGrid.tiles(40000, 30000, 1, new float[]{10000, 10000, 10200, 10200});
        eq("small viewport deep in a huge image needs one tile", 4, one.length);
        eq("that tile starts on the 512 grid", 9728, one[0]);
        eq("empty viewport -> no tiles", 0, TileGrid.tiles(1000, 1000, 1, new float[]{500, 500, 500, 900}).length);
        eq("viewport outside image -> no tiles", 0, TileGrid.tiles(1000, 1000, 1, new float[]{1200, 0, 1500, 100}).length);
        int[] coarse = TileGrid.tiles(4096, 4096, 4, new float[]{0, 0, 4096, 4096});
        eq("sample 4 tiles span 2048 source px", 4 * 4, coarse.length);

        // Orientation round-trips and lands in the right place.
        int w = 4000, h = 3000;
        float[] raw = {100, 200, 600, 900};
        for (int deg : new int[]{0, 90, 180, 270}) {
            float[] o = TileGrid.toOriented(deg, w, h, raw);
            eq("round trip at " + deg, Arrays.toString(raw), Arrays.toString(TileGrid.toRaw(deg, w, h, o)));
        }
        // Raw top-left pixel after a 90 cw rotation sits at the oriented top-right.
        eq("90 cw puts raw top-left at oriented top-right", Arrays.toString(new float[]{h - 1, 0, h, 1}),
                Arrays.toString(TileGrid.toOriented(90, w, h, new float[]{0, 0, 1, 1})));
        eq("270 cw puts raw top-left at oriented bottom-left", Arrays.toString(new float[]{0, w - 1, 1, w}),
                Arrays.toString(TileGrid.toOriented(270, w, h, new float[]{0, 0, 1, 1})));

        // Max zoom for 2 screen px per source px.
        eq("40000 px fitted to 1440 px reaches 2:1 at ~55.56x", 55.56f, Math.round(TileGrid.zoomFor(40000, 1440, 2f) * 100) / 100f);

        if (failures > 0) {
            System.out.println(failures + " failed");
            System.exit(1);
        }
        System.out.println("ok");
    }

    private static void eq(String name, Object want, Object got) {
        if (!want.equals(got)) {
            failures++;
            System.out.println("FAIL " + name + ": want " + want + " got " + got);
        }
    }
}
