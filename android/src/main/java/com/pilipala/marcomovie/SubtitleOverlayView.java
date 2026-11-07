package com.pilipala.marcomovie;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.FrameLayout;
import androidx.media3.common.text.Cue;
import androidx.media3.ui.CaptionStyleCompat;
import java.util.Collections;
import java.util.List;

/** Wrapper around Media3 SubtitleView so shared playback controls do not depend on Media3 classes. */
final class SubtitleOverlayView extends FrameLayout {
    static final String[] STYLE_NAMES = {"白字黑边·透明底", "暖黄黑边·透明底", "白字阴影·透明底", "白字半透底", "青蓝黑边·透明底"};
    static final String[] STYLE_LABELS = STYLE_NAMES;

    static String bgStyleLabel(int preset) {
        return styleName(preset);
    }

    private static final String PREVIEW_LINE = "字幕实时预览 · 上下键调位置 · 左右键调大小 · 确认键换样式";

    final androidx.media3.ui.SubtitleView delegate;
    private List<Cue> currentCues = Collections.emptyList();
    private boolean previewActive;

    SubtitleOverlayView(Context context) {
        super(context);
        delegate = new androidx.media3.ui.SubtitleView(context);
        delegate.setApplyEmbeddedStyles(false);
        delegate.setApplyEmbeddedFontSizes(false);
        addView(delegate, new FrameLayout.LayoutParams(-1, -1));
        applyStylePreset(0);
    }

    static String styleName(int preset) {
        int idx = Prefs.clampSubtitleStyle(preset);
        return STYLE_NAMES[idx];
    }

    void setFractionalTextSize(float fractionOfHeight) {
        delegate.setApplyEmbeddedFontSizes(false);
        delegate.setFractionalTextSize(fractionOfHeight);
    }

    void setBottomPaddingFraction(float fractionOfHeight) {
        delegate.setBottomPaddingFraction(fractionOfHeight);
    }

    void setUserDefaultStyle() {
        applyStylePreset(0);
    }

    void applyStylePreset(int preset) {
        int idx = Prefs.clampSubtitleStyle(preset);
        delegate.setApplyEmbeddedStyles(false);
        Typeface bold = Typeface.create("sans-serif-medium", Typeface.BOLD);
        CaptionStyleCompat style;
        switch (idx) {
            case 1:
                // Warm cinema yellow text + crisp black outline, transparent background
                style =
                        new CaptionStyleCompat(
                                0xFFFFE066,
                                Color.TRANSPARENT,
                                Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                0xF0000000,
                                bold);
                break;
            case 2:
                // White text + soft drop shadow, transparent background
                style =
                        new CaptionStyleCompat(
                                Color.WHITE,
                                Color.TRANSPARENT,
                                Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                                0xE6000000,
                                bold);
                break;
            case 3:
                // White text + subtle 48% translucent dark background (never solid black!)
                style =
                        new CaptionStyleCompat(
                                Color.WHITE,
                                0x7A000000,
                                Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                0xAA000000,
                                bold);
                break;
            case 4:
                // Soft cyan text + crisp black outline, transparent background
                style =
                        new CaptionStyleCompat(
                                0xFF8BE9FD,
                                Color.TRANSPARENT,
                                Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                0xF0000000,
                                bold);
                break;
            case 0:
            default:
                // Default: crisp white text + black outline stroke, 100% transparent background
                style =
                        new CaptionStyleCompat(
                                Color.WHITE,
                                Color.TRANSPARENT,
                                Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                0xF0000000,
                                bold);
                break;
        }
        delegate.setStyle(style);
    }

    void setPreviewActive(boolean active) {
        this.previewActive = active;
        refreshCues();
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        if (delegate != null) {
            delegate.setPadding(left, top, right, bottom);
        } else {
            super.setPadding(left, top, right, bottom);
        }
    }

    void setCues(List<Cue> cues) {
        this.currentCues = cues != null ? cues : Collections.emptyList();
        refreshCues();
    }

    private void refreshCues() {
        if (!currentCues.isEmpty()) {
            delegate.setCues(currentCues);
        } else if (previewActive) {
            Cue sample = new Cue.Builder().setText(PREVIEW_LINE).build();
            delegate.setCues(Collections.singletonList(sample));
        } else {
            delegate.setCues(Collections.emptyList());
        }
    }
}
