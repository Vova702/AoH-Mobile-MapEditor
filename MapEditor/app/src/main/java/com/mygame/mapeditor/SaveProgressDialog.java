package com.mygame.mapeditor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Modal "Saving... NN%" dialog shown while the map is being saved. {@link #set} and
 * {@link #dismiss} may be called from any thread.
 *
 * All texts come from strings.xml (values, values-uk, values-ru), so they follow the app language.
 */
final class SaveProgressDialog {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AlertDialog dialog;
    private final ProgressBar bar;
    private final TextView percentText;
    private final TextView stageText;
    private final Activity activity;

    private SaveProgressDialog(Activity activity) {
        this.activity = activity;
        int pad = (int) (activity.getResources().getDisplayMetrics().density * 24);

        var layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad, pad, pad);

        stageText = new TextView(activity);
        stageText.setText(stageProvinces());
        stageText.setGravity(Gravity.CENTER);
        layout.addView(stageText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        layout.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        percentText = new TextView(activity);
        percentText.setText("0%");
        percentText.setGravity(Gravity.CENTER);
        percentText.setTextSize(18);
        layout.addView(percentText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        dialog = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.save_progress_title))
                .setView(layout)
                .setCancelable(false)
                .create();
    }

    /** Must be called on the UI thread. Returns null when the context is not an Activity (no dialog possible). */
    static SaveProgressDialog show(Context context) {
        if (!(context instanceof Activity activity)) return null;
        if (activity.isFinishing()) return null;
        try {
            var d = new SaveProgressDialog(activity);
            d.dialog.show();
            return d;
        } catch (RuntimeException e) {
            return null;
        }
    }

    String stageProvinces() {
        return activity.getString(R.string.save_progress_provinces);
    }

    String stageScales(int scale) {
        return activity.getString(R.string.save_progress_scales, scale);
    }

    /** @param stage text above the bar, null = keep the current one */
    void set(int percent, String stage) {
        final int p = Math.max(0, Math.min(100, percent));
        ui.post(() -> {
            bar.setProgress(p);
            percentText.setText(p + "%");
            if (stage != null) stageText.setText(stage);
        });
    }

    void dismiss() {
        ui.post(() -> {
            try {
                if (dialog.isShowing()) dialog.dismiss();
            } catch (RuntimeException ignored) {
                // the activity may already be gone
            }
        });
    }
}
