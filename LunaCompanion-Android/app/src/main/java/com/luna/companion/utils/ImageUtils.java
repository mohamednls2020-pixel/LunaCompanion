package com.luna.companion.utils;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Java utility class for image decoding, downscaling for multimodal vision GGUF
 * models, and clipboard/gallery Uri conversion to Base64 data URIs.
 */
public final class ImageUtils {
    private static final String TAG = "ImageUtils";
    private static final int MAX_VISION_DIMENSION = 672;
    private static final int JPEG_QUALITY = 86;

    private ImageUtils() {}

    @Nullable
    public static String uriToDataUri(Context context, Uri uri) {
        if (context == null || uri == null) return null;
        ContentResolver resolver = context.getContentResolver();
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream == null) return null;
            Bitmap original = BitmapFactory.decodeStream(stream);
            if (original == null) return null;
            Bitmap scaled = scaleDownIfNeeded(original, MAX_VISION_DIMENSION);
            if (scaled != original) {
                original.recycle();
            }
            return bitmapToDataUri(scaled);
        } catch (Exception e) {
            Log.e(TAG, "Failed to read image from URI", e);
            return null;
        }
    }

    @Nullable
    public static String readClipboardImageDataUri(Context context) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            return null;
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return null;
        }
        for (int i = 0; i < clip.getItemCount(); i++) {
            Uri itemUri = clip.getItemAt(i).getUri();
            if (itemUri != null) {
                String mime = context.getContentResolver().getType(itemUri);
                if (mime != null && mime.startsWith("image/")) {
                    return uriToDataUri(context, itemUri);
                }
            }
        }
        return null;
    }

    @Nullable
    public static byte[] decodeBase64Payload(@Nullable String base64OrDataUri) {
        if (base64OrDataUri == null || base64OrDataUri.trim().isEmpty()) {
            return null;
        }
        try {
            String raw = base64OrDataUri;
            int commaIdx = raw.indexOf(',');
            if (raw.startsWith("data:") && commaIdx != -1) {
                raw = raw.substring(commaIdx + 1);
            }
            return Base64.decode(raw, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid base64 image payload", e);
            return null;
        }
    }

    public static String describeImageDimensions(@Nullable byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) return "";
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, opts);
        if (opts.outWidth > 0 && opts.outHeight > 0) {
            return opts.outWidth + "x" + opts.outHeight + " (" + (imageBytes.length / 1024) + " KB)";
        }
        return (imageBytes.length / 1024) + " KB";
    }

    private static Bitmap scaleDownIfNeeded(Bitmap bitmap, int maxDim) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= maxDim && height <= maxDim) {
            return bitmap;
        }
        float ratio = Math.min((float) maxDim / width, (float) maxDim / height);
        int newWidth = Math.max(1, Math.round(width * ratio));
        int newHeight = Math.max(1, Math.round(height * ratio));
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
    }

    private static String bitmapToDataUri(Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
        bitmap.recycle();
        byte[] bytes = out.toByteArray();
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
    }
}
