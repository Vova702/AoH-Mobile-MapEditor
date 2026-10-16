package com.mygame.mapeditor;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The "Export map" flow shared by the main menu and the editor screen:
 * asks for the options, runs {@link MapExporter#exportMap} in the background with a
 * progress dialog, then hands the finished zip to {@code onDone} (on the UI thread).
 */
final class ExportUi {

    private ExportUi() {
    }

    static void run(Activity activity, File mapDir, String mapName, Consumer<File> onDone) {
        int dp = (int) activity.getResources().getDisplayMetrics().density;
        var form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(24 * dp, 16 * dp, 24 * dp, 8 * dp);

        form.addView(label(activity, "Формат / Format", 0));
        var formatGroup = new RadioGroup(activity);
        var oldFmt = new RadioButton(activity);
        oldFmt.setId(View.generateViewId());
        oldFmt.setText("Старий / Old: data/provinces/<id>, scales/<k>/<id> (окремі файли)");
        var newFmt = new RadioButton(activity);
        newFmt.setId(View.generateViewId());
        newFmt.setText("Новий / New: provinces.pack, scales/<k>.pack (мало файлів; потрібна збірка гри з підтримкою pack)");
        formatGroup.addView(oldFmt);
        formatGroup.addView(newFmt);
        formatGroup.check(oldFmt.getId());
        form.addView(formatGroup);

        form.addView(label(activity, "Scales (через кому, порожньо = без) / comma separated, empty = none", 12 * dp));
        var scalesInput = new EditText(activity);
        scalesInput.setText("1,2");
        form.addView(scalesInput);

        form.addView(label(activity, "Ширина карти для зациклення по горизонталі, 0 = вимкнено / Wrap width, 0 = off", 12 * dp));
        var wrapInput = new EditText(activity);
        wrapInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        wrapInput.setText("0");
        form.addView(wrapInput);

        var keepLinks = new CheckBox(activity);
        keepLinks.setText("Зберігати старі зв'язки між провінціями / Keep old connections");
        keepLinks.setChecked(true);
        form.addView(keepLinks);

        var skeleton = new CheckBox(activity);
        skeleton.setText("Додати каркас карти: config.json, regions, province_names, cities, wonders / Add map skeleton files");
        skeleton.setChecked(true);
        form.addView(skeleton);

        new AlertDialog.Builder(activity)
                .setTitle("Експорт карти / Export map")
                .setView(form)
                .setPositiveButton(activity.getString(R.string.btn_ok), (d, w) -> {
                    var opt = new MapExporter.ExportOptions();
                    opt.format = formatGroup.getCheckedRadioButtonId() == newFmt.getId()
                            ? MapExporter.Format.NEW_PACK : MapExporter.Format.OLD_LOOSE_FILES;
                    opt.scales = parseScales(scalesInput.getText().toString());
                    try {
                        opt.wrapWidth = Math.max(0, Integer.parseInt(wrapInput.getText().toString().trim()));
                    } catch (NumberFormatException e) {
                        opt.wrapWidth = 0;
                    }
                    opt.keepOldConnections = keepLinks.isChecked();
                    opt.skeleton = skeleton.isChecked();
                    opt.mapName = mapName;
                    execute(activity, mapDir, mapName, opt, onDone);
                })
                .setNegativeButton(activity.getString(R.string.btn_cancel), null)
                .show();
    }

    private static TextView label(Activity a, String text, int topMargin) {
        var t = new TextView(a);
        t.setText(text);
        var lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        t.setLayoutParams(lp);
        return t;
    }

    static int[] parseScales(String text) {
        TreeSet<Integer> set = new TreeSet<>();
        for (String part : text.split("[,;\\s]+")) {
            if (part.isEmpty()) continue;
            try {
                int k = Integer.parseInt(part);
                if (k >= 1 && k <= 16) set.add(k);
            } catch (NumberFormatException ignored) {
            }
        }
        var out = new int[set.size()];
        int i = 0;
        for (int k : set) out[i++] = k;
        return out;
    }

    private static void execute(Activity activity, File mapDir, String mapName,
                                MapExporter.ExportOptions opt, Consumer<File> onDone) {
        int dp24 = (int) (activity.getResources().getDisplayMetrics().density * 24);
        var layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp24, dp24, dp24, dp24);

        var progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        layout.addView(progressBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        var progressText = new TextView(activity);
        progressText.setText("0%");
        progressText.setGravity(Gravity.CENTER);
        progressText.setTextSize(16);
        layout.addView(progressText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog progressDialog = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.dialog_title_exporting))
                .setView(layout)
                .setCancelable(false)
                .show();

        final String zipName = mapName + (opt.format == MapExporter.Format.NEW_PACK ? "_map_pack.zip" : "_map.zip");
        var ui = new Handler(Looper.getMainLooper());
        Executors.newSingleThreadExecutor().execute(() -> {
            File zip = null;
            String error = null;
            try {
                var exportDir = new File(activity.getFilesDir(), "export");
                zip = MapExporter.exportMap(mapDir, exportDir, zipName, opt, percent -> ui.post(() -> {
                    progressBar.setProgress(percent);
                    progressText.setText(percent + "%");
                }));
            } catch (Throwable e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }

            File resultZip = zip;
            String resultError = error;
            ui.post(() -> {
                if (progressDialog.isShowing()) progressDialog.dismiss();
                if (resultError != null) {
                    Toast.makeText(activity, activity.getString(R.string.toast_export_error, resultError),
                            Toast.LENGTH_LONG).show();
                } else {
                    onDone.accept(resultZip);
                }
            });
        });
    }
}
