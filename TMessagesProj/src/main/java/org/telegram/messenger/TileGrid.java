package org.telegram.messenger;

/**
 * Geometry for drawing a large image as region-decoded tiles. No Android types, so it runs in plain JVM tests.
 * Rectangles are float[4] {left, top, right, bottom}. "Raw" is the file's pixel grid; "oriented" is the grid after
 * the EXIF rotation (0/90/180/270 clockwise) that the viewer displays.
 */
public final class TileGrid {

    public static final int TILE = 512; // decoded pixels per tile side

    private TileGrid() {
    }

    /** Largest power-of-two sample size whose decoded pixels are still at least as dense as screen pixels. */
    public static int sampleSize(float screenPxPerSourcePx) {
        int sample = 1;
        while (sample < (1 << 20) && sample * 2 * screenPxPerSourcePx <= 1f) {
            sample *= 2;
        }
        return sample;
    }

    /** Tiles at {@code sample} that intersect {@code visible} (raw coordinates), as flat {l,t,r,b,...} clipped to the image. */
    public static int[] tiles(int width, int height, int sample, float[] visible) {
        int span = TILE * sample;
        int x0 = Math.max(0, (int) Math.floor(visible[0] / span));
        int y0 = Math.max(0, (int) Math.floor(visible[1] / span));
        int x1 = Math.min((width - 1) / span, (int) Math.floor((visible[2] - 1e-3f) / span));
        int y1 = Math.min((height - 1) / span, (int) Math.floor((visible[3] - 1e-3f) / span));
        if (x1 < x0 || y1 < y0 || visible[2] <= visible[0] || visible[3] <= visible[1]) {
            return new int[0];
        }
        int[] out = new int[(x1 - x0 + 1) * (y1 - y0 + 1) * 4];
        int i = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                out[i++] = x * span;
                out[i++] = y * span;
                out[i++] = Math.min(width, (x + 1) * span);
                out[i++] = Math.min(height, (y + 1) * span);
            }
        }
        return out;
    }

    /** Maps an oriented rect back to the raw grid of a {@code width x height} file. */
    public static float[] toRaw(int degrees, int width, int height, float[] r) {
        switch (normalize(degrees)) {
            case 90:
                return new float[]{r[1], height - r[2], r[3], height - r[0]};
            case 180:
                return new float[]{width - r[2], height - r[3], width - r[0], height - r[1]};
            case 270:
                return new float[]{width - r[3], r[0], width - r[1], r[2]};
            default:
                return r.clone();
        }
    }

    /** Maps a raw rect of a {@code width x height} file into the oriented grid. */
    public static float[] toOriented(int degrees, int width, int height, float[] r) {
        switch (normalize(degrees)) {
            case 90:
                return new float[]{height - r[3], r[0], height - r[1], r[2]};
            case 180:
                return new float[]{width - r[2], height - r[3], width - r[0], height - r[1]};
            case 270:
                return new float[]{r[1], width - r[2], r[3], width - r[0]};
            default:
                return r.clone();
        }
    }

    /** Zoom at which one source pixel covers {@code screenPxPerSourcePx} screen pixels, given the fitted width. */
    public static float zoomFor(int orientedWidth, float fittedWidth, float screenPxPerSourcePx) {
        return fittedWidth <= 0 ? 1f : orientedWidth / fittedWidth * screenPxPerSourcePx;
    }

    public static int normalize(int degrees) {
        return ((degrees % 360) + 360) % 360;
    }
}
