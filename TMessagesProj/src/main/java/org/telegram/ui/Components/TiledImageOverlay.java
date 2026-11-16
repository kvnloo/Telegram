package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.LruCache;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.TileGrid;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Draws full-resolution tiles of a large local image over its downscaled preview, decoding only the visible region
 * at the sample size the current zoom needs. Memory stays bounded by the tile cache regardless of image size.
 */
public class TiledImageOverlay {

    private static DispatchQueue queue;

    private final Runnable invalidate;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final Rect clip = new Rect();
    private final RectF dst = new RectF();
    private final Set<String> pending = new HashSet<>();
    private final LruCache<String, Bitmap> cache;

    private volatile BitmapRegionDecoder decoder;
    private volatile Set<String> wanted = Collections.emptySet();
    private volatile int generation;
    private File file;
    private int rawWidth, rawHeight, orientation;

    public TiledImageOverlay(Runnable invalidate) {
        this.invalidate = invalidate;
        int bytes = (int) Math.min(Runtime.getRuntime().maxMemory() / 6, 96L * 1024 * 1024);
        cache = new LruCache<String, Bitmap>(bytes) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    /** Binds a local file; a no-op when it is already bound. Pass null to unbind. */
    public void setSource(File newFile, int orientation) {
        if (newFile == null ? file == null : newFile.equals(file) && this.orientation == orientation) {
            return;
        }
        release();
        file = newFile;
        this.orientation = TileGrid.normalize(orientation);
        if (newFile == null) {
            return;
        }
        final int gen = generation;
        final String path = newFile.getAbsolutePath();
        queue().postRunnable(() -> {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            BitmapRegionDecoder d = null;
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                try {
                    d = BitmapRegionDecoder.newInstance(path, false);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            final BitmapRegionDecoder opened = d;
            final int w = bounds.outWidth, h = bounds.outHeight;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation) {
                    if (opened != null) queue().postRunnable(opened::recycle);
                    return;
                }
                decoder = opened;
                rawWidth = w;
                rawHeight = h;
                invalidate.run();
            });
        });
    }

    public boolean hasSource() {
        return decoder != null;
    }

    public File getFile() {
        return file;
    }

    /** Zoom (relative to fit) at which one source pixel spans two screen pixels; 0 when nothing is bound. */
    public float maxScale(float fittedWidth) {
        if (decoder == null) return 0;
        return TileGrid.zoomFor(orientedWidth(), fittedWidth, 2f);
    }

    /**
     * Call right after the preview is drawn, with the canvas still transformed so the preview occupies
     * (x, y, w, h). {@code previewLongSide} is the long side of the preview bitmap in pixels.
     */
    public void draw(Canvas canvas, float x, float y, float w, float h, int previewLongSide) {
        final BitmapRegionDecoder d = decoder;
        if (d == null || w <= 0 || h <= 0) return;
        int ow = orientedWidth(), oh = orientedHeight();
        canvas.getMatrix(matrix);
        float screenPerSource = matrix.mapRadius(1f) * w / ow;
        float previewDensity = previewLongSide / (float) Math.max(rawWidth, rawHeight);
        if (Math.min(1f, screenPerSource) <= previewDensity * 1.05f) {
            wanted = Collections.emptySet();
            return; // the preview already has enough pixels for this zoom
        }
        if (!canvas.getClipBounds(clip)) return;
        float[] visible = {
                Math.max(0, (clip.left - x) * ow / w), Math.max(0, (clip.top - y) * oh / h),
                Math.min(ow, (clip.right - x) * ow / w), Math.min(oh, (clip.bottom - y) * oh / h)
        };
        int sample = TileGrid.sampleSize(screenPerSource);
        int[] tiles = TileGrid.tiles(rawWidth, rawHeight, sample, TileGrid.toRaw(orientation, rawWidth, rawHeight, visible));
        Set<String> missing = new HashSet<>();
        for (int i = 0; i < tiles.length; i += 4) {
            String key = sample + ":" + tiles[i] + ":" + tiles[i + 1];
            Bitmap bitmap = cache.get(key);
            if (bitmap == null) {
                missing.add(key);
                continue;
            }
            float[] o = TileGrid.toOriented(orientation, rawWidth, rawHeight, new float[]{tiles[i], tiles[i + 1], tiles[i + 2], tiles[i + 3]});
            dst.set(x + o[0] * w / ow, y + o[1] * h / oh, x + o[2] * w / ow, y + o[3] * h / oh);
            if (orientation == 0) {
                canvas.drawBitmap(bitmap, null, dst, paint);
            } else {
                canvas.save();
                canvas.translate(dst.centerX(), dst.centerY());
                canvas.rotate(orientation);
                float hw = (orientation == 180 ? dst.width() : dst.height()) / 2f;
                float hh = (orientation == 180 ? dst.height() : dst.width()) / 2f;
                dst.set(-hw, -hh, hw, hh);
                canvas.drawBitmap(bitmap, null, dst, paint);
                canvas.restore();
            }
        }
        wanted = missing; // set before posting so the decode thread sees these keys as wanted
        for (int i = 0; i < tiles.length; i += 4) {
            String key = sample + ":" + tiles[i] + ":" + tiles[i + 1];
            if (missing.contains(key)) {
                request(d, key, sample, tiles[i], tiles[i + 1], tiles[i + 2], tiles[i + 3]);
            }
        }
    }

    private void request(BitmapRegionDecoder d, String key, int sample, int l, int t, int r, int b) {
        if (!pending.add(key)) return;
        final int gen = generation;
        queue().postRunnable(() -> {
            Bitmap bitmap = null;
            if (gen == generation && wanted.contains(key)) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                try {
                    bitmap = d.decodeRegion(new Rect(l, t, r, b), opts);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            final Bitmap result = bitmap;
            AndroidUtilities.runOnUIThread(() -> {
                pending.remove(key);
                if (result != null && gen == generation) {
                    cache.put(key, result);
                    invalidate.run();
                }
            });
        });
    }

    public void release() {
        generation++;
        wanted = Collections.emptySet();
        pending.clear();
        cache.evictAll();
        file = null;
        final BitmapRegionDecoder d = decoder;
        decoder = null;
        if (d != null) queue().postRunnable(d::recycle);
    }

    private int orientedWidth() {
        return orientation % 180 == 0 ? rawWidth : rawHeight;
    }

    private int orientedHeight() {
        return orientation % 180 == 0 ? rawHeight : rawWidth;
    }

    private static DispatchQueue queue() {
        if (queue == null) queue = new DispatchQueue("tiledImageOverlay");
        return queue;
    }
}
