package com.mygame.mapeditor;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class StartMenuActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_FILE_PICKER = 1001;
    private static final int REQUEST_CODE_TXT_PICKER = 1003;
    private static final int REQUEST_CODE_SCALE_PICKER = 1004;
    private static final int REQUEST_CODE_EXPORT = 1005;
    private static final int REQUEST_CODE_BACKGROUND_PICKER = 1008;

    private MapManager mapManager;
    private File pendingExportZip;
    private Uri pendingTxtUri;
    private boolean pickBackgroundPending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start_menu);

        mapManager = new MapManager(this);

        Button editMapButton = findViewById(R.id.btn_edit_map);
        Button selectMapButton = findViewById(R.id.btn_select_map);
        Button importMapButton = findViewById(R.id.btn_import_map);
        Button importTxtButton = findViewById(R.id.btn_import_txt);
        Button scaleMapButton = findViewById(R.id.btn_scale_map);
        Button exportMapButton = findViewById(R.id.btn_export_map);
        Button deleteMapButton = findViewById(R.id.btn_delete_map);
        Button languageButton = findViewById(R.id.btn_language);

        editMapButton.setOnClickListener(v -> {
            String activeMap = mapManager.getActiveMapName();
            if (activeMap == null) {
                Toast.makeText(StartMenuActivity.this,
                        getString(R.string.toast_select_map_first),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            var intent = new Intent(StartMenuActivity.this, MainActivity.class);
            intent.putExtra("MAP_NAME", activeMap);
            startActivity(intent);
        });

        selectMapButton.setOnClickListener(v -> showMapSelectionDialog());
        importMapButton.setOnClickListener(v -> openZipFilePicker());
        importTxtButton.setOnClickListener(v -> openTxtFilePicker());
        scaleMapButton.setOnClickListener(v -> openScaleZipPicker());
        exportMapButton.setOnClickListener(v -> startExport());
        deleteMapButton.setOnClickListener(v -> showDeleteMapDialog());
        languageButton.setOnClickListener(v -> showLanguageDialog());
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.attachBaseContext(newBase));
    }

    private void showMapSelectionDialog() {
        List<String> maps = mapManager.discoverMaps();
        if (maps.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_maps_found), Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = maps.toArray(new String[0]);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_title_select_map))
                .setItems(items, (dialog, which) -> {
                    String selected = maps.get(which);
                    mapManager.setActiveMapName(selected);
                    Toast.makeText(StartMenuActivity.this,
                            getString(R.string.toast_selected_map, selected),
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showDeleteMapDialog() {
        List<String> maps = mapManager.discoverMaps();
        if (maps.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_maps_to_delete), Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = maps.toArray(new String[0]);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_title_delete_map))
                .setItems(items, (dialog, which) -> {
                    String selected = maps.get(which);
                    new AlertDialog.Builder(StartMenuActivity.this)
                            .setTitle(getString(R.string.dialog_title_confirm_delete))
                            .setMessage(getString(R.string.dialog_msg_confirm_delete, selected))
                            .setPositiveButton(getString(R.string.btn_delete), (confirmation, which2) -> {
                                mapManager.deleteMapAsync(selected, new MapManager.DeleteMapCallback() {
                                    @Override
                                    public void onDeleteSuccess(String mapName) {
                                        Toast.makeText(StartMenuActivity.this,
                                                getString(R.string.toast_deleted_map, mapName),
                                                Toast.LENGTH_SHORT).show();
                                    }

                                    @Override
                                    public void onDeleteError(String errorMessage) {
                                        Toast.makeText(StartMenuActivity.this,
                                                getString(R.string.toast_failed_delete_map, errorMessage),
                                                Toast.LENGTH_LONG).show();
                                    }
                                });
                            })
                            .setNegativeButton(getString(R.string.btn_cancel), null)
                            .show();
                })
                .show();
    }

    private void openZipFilePicker() {
        var intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_CODE_FILE_PICKER);
    }

    private void openTxtFilePicker() {
        var intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_CODE_TXT_PICKER);
    }

    private void openScaleZipPicker() {
        var intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_CODE_SCALE_PICKER);
    }

    /**
     * Експорт поточної карти: будує editor_data.zip з бінарними файлами
     * провінцій з теки активної карти (з прогресом), а тоді відкриває
     * системне "Save As", щоб користувач вибрав, куди зберегти.
     */
    private void startExport() {
        String activeMap = mapManager.getActiveMapName();
        if (activeMap == null) {
            Toast.makeText(this, getString(R.string.toast_select_map_first), Toast.LENGTH_SHORT).show();
            return;
        }

        ExportUi.run(this, mapManager.getMapDirectory(activeMap), activeMap, zip -> {
            pendingExportZip = zip;
            openExportPicker(zip.getName());
        });
    }

    private void openExportPicker(String activeMap) { // parameter = suggested file name
        try {
            var intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_TITLE, activeMap);
            startActivityForResult(intent, REQUEST_CODE_EXPORT);
        } catch (Exception e) {
            Toast.makeText(StartMenuActivity.this,
                    getString(R.string.toast_export_error, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void saveExportToUri(Uri target) {
        if (target == null || pendingExportZip == null) return;
        new Thread(() -> {
            try {
                try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(target, "w");
                     var in = new FileInputStream(pendingExportZip)) {
                    if (pfd == null) throw new Exception("Could not open destination");
                    try (OutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
                        var buffer = new byte[16 * 1024];
                        int n;
                        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    }
                }
                runOnUiThread(() -> Toast.makeText(StartMenuActivity.this,
                        getString(R.string.toast_export_saved),
                        Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(StartMenuActivity.this,
                        getString(R.string.toast_export_error, e.getMessage()),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void showLanguageDialog() {
        String[] languages = {
                getString(R.string.lang_en),
                getString(R.string.lang_uk),
                getString(R.string.lang_ru)
        };
        String[] codes = {"en", "uk", "ru"};
        String currentLang = LocaleHelper.getPersistedLanguage(this);
        int checkedItem = 0;
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(currentLang)) {
                checkedItem = i;
                break;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_title_language))
                .setSingleChoiceItems(languages, checkedItem, (dialog, which) -> {
                    String code = codes[which];
                    dialog.dismiss();
                    applyLanguage(code);
                })
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show();
    }

    private void applyLanguage(String languageCode) {
        LocaleHelper.setLocale(this, languageCode);
        recreate();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        switch (requestCode) {
            case REQUEST_CODE_FILE_PICKER -> importZipFile(uri);
            case REQUEST_CODE_TXT_PICKER -> promptForBackground(uri);
            case REQUEST_CODE_BACKGROUND_PICKER -> {
                Uri txtUri = pendingTxtUri;
                pendingTxtUri = null;
                pickBackgroundPending = false;
                if (txtUri != null) {
                    importTxtMapWithBackground(txtUri, uri);
                }
            }
            case REQUEST_CODE_SCALE_PICKER -> scaleZipFile(uri);
            case REQUEST_CODE_EXPORT -> saveExportToUri(uri);
            default -> { }
        }
    }

    /** Asks whether to use a custom bg.png for the TXT map or the default one. */
    private void promptForBackground(Uri txtUri) {
        final String mapName = txtMapName(txtUri);
        if (mapName == null) return;

        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_title_select_background)
                .setMessage(R.string.dialog_msg_select_background)
                .setPositiveButton(R.string.btn_choose_bg, (d, w) -> openBackgroundPicker(txtUri))
                .setNegativeButton(R.string.btn_skip_bg, (d, w) -> importTxtMapWithBackground(txtUri, null))
                .setNeutralButton(R.string.btn_cancel, (d, w) -> {
                })
                .show();
    }

    private String txtMapName(Uri txtUri) {
        String name = MapManager.resolveFileName(getContentResolver(), txtUri);
        if (name == null || name.isEmpty()) return null;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void openBackgroundPicker(Uri txtUri) {
        var intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(Intent.createChooser(intent, getString(R.string.dialog_title_select_background)),
                REQUEST_CODE_BACKGROUND_PICKER);
        pendingTxtUri = txtUri;
        pickBackgroundPending = true;
    }

    private void scaleZipFile(Uri zipUri) {
        String fileName = MapManager.resolveFileName(getContentResolver(), zipUri);
        if (fileName == null || !fileName.toLowerCase(Locale.US).endsWith(".zip")) {
            Toast.makeText(this, getString(R.string.toast_select_zip), Toast.LENGTH_SHORT).show();
            return;
        }

        var layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int dp24 = (int) (getResources().getDisplayMetrics().density * 24);
        layout.setPadding(dp24, dp24, dp24, dp24);

        var progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        layout.addView(progressBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        var progressText = new TextView(this);
        progressText.setText("0%");
        progressText.setGravity(Gravity.CENTER);
        progressText.setTextSize(16);
        var textLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        textLp.topMargin = (int) (getResources().getDisplayMetrics().density * 12);
        layout.addView(progressText, textLp);

        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_title_importing))
                .setView(layout)
                .setCancelable(false)
                .show();

        mapManager.importZipArchive(zipUri, getContentResolver(),
                new MapManager.ZipImportCallback() {
                    @Override
                    public void onProgressUpdate(int percent) {
                        progressBar.setProgress(percent);
                        progressText.setText(percent + "%");
                    }

                    @Override
                    public void onZipImportSuccess(String mapName) {
                        if (progressDialog.isShowing()) progressDialog.dismiss();
                        mapManager.setActiveMapName(mapName);
                        showOriginalSizeDialog(mapManager.getMapDirectory(mapName));
                    }

                    @Override
                    public void onZipImportError(String errorMessage) {
                        if (progressDialog.isShowing()) progressDialog.dismiss();
                        Toast.makeText(StartMenuActivity.this,
                                getString(R.string.toast_failed_extract_zip, errorMessage),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    /**
     * Перший крок масштабування: розмір оригінального зображення
     * (координатний простір, у якому написані провінції).
     */
    private void showOriginalSizeDialog(File mapDir) {
        int[] prefilled = ProvinceScaler.readProvinceSpace(mapDir);
        showSizeDialog(
                getString(R.string.dialog_title_scale_original),
                prefilled != null ? prefilled[0] : 0,
                prefilled != null ? prefilled[1] : 0,
                (w, h) -> showNewSizeDialog(mapDir, w, h));
    }

    /**
     * Другий крок масштабування: розмір нового фону (bg.png),
     * попередньо заповнюється реальними розмірами bg.png з теки карти.
     */
    private void showNewSizeDialog(File mapDir, int origW, int origH) {
        int[] bg = ProvinceScaler.readImageSize(new File(mapDir, "bg.png"));
        showSizeDialog(
                getString(R.string.dialog_title_scale_new),
                bg != null ? bg[0] : 0,
                bg != null ? bg[1] : 0,
                (w, h) -> runScaling(mapDir, origW, origH, w, h));
    }

    /** Спільний діалог вводу ширини/висоти. */
    private void showSizeDialog(String title, int prefillW, int prefillH,
                                SizeDialogCallback callback) {
        int dp24 = (int) (getResources().getDisplayMetrics().density * 24);
        int dp12 = (int) (getResources().getDisplayMetrics().density * 12);

        var layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp24, dp24, dp24, dp24);

        var widthLabel = new TextView(this);
        widthLabel.setText(getString(R.string.dialog_label_width));
        widthLabel.setTextSize(14);
        layout.addView(widthLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        var widthInput = new EditText(this);
        widthInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (prefillW > 0) widthInput.setText(String.valueOf(prefillW));
        layout.addView(widthInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        var heightLabel = new TextView(this);
        heightLabel.setText(getString(R.string.dialog_label_height));
        heightLabel.setTextSize(14);
        var hLabelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hLabelLp.topMargin = dp12;
        layout.addView(heightLabel, hLabelLp);

        var heightInput = new EditText(this);
        heightInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (prefillH > 0) heightInput.setText(String.valueOf(prefillH));
        layout.addView(heightInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(layout)
                .setPositiveButton(getString(R.string.btn_ok), (dialog, which) -> {
                    int w = parseSize(widthInput);
                    int h = parseSize(heightInput);
                    if (w <= 0 || h <= 0) {
                        Toast.makeText(StartMenuActivity.this,
                                getString(R.string.toast_scale_invalid_size),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    callback.onSizes(w, h);
                })
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show();
    }

    private static int parseSize(EditText input) {
        try {
            return Integer.parseInt(input.getText().toString().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void runScaling(File mapDir, int origW, int origH, int newW, int newH) {
        var layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int dp24 = (int) (getResources().getDisplayMetrics().density * 24);
        layout.setPadding(dp24, dp24, dp24, dp24);

        var progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        layout.addView(progressBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        var progressText = new TextView(this);
        progressText.setText("0%");
        progressText.setGravity(Gravity.CENTER);
        progressText.setTextSize(16);
        var textLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        textLp.topMargin = (int) (getResources().getDisplayMetrics().density * 12);
        layout.addView(progressText, textLp);

        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_title_scaling))
                .setView(layout)
                .setCancelable(false)
                .show();

        Executors.newSingleThreadExecutor().execute(() -> {
            int[] result = null;
            String error = null;
            String warning = null;
            try {
                ProvinceScaler.Result r = ProvinceScaler.scaleMapDirectory(
                        mapDir, origW, origH, newW, newH,
                        MapManager.scalesDirectory(StartMenuActivity.this, mapDir.getName()),
                        percent -> new Handler(Looper.getMainLooper()).post(() -> {
                            progressBar.setProgress(percent);
                            progressText.setText(percent + "%");
                        }));
                result = new int[]{r.scaledFiles, r.skippedFiles};
                warning = r.warning;
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String err = error;
            final String warn = warning;
            final int[] res = result;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (progressDialog.isShowing()) progressDialog.dismiss();
                if (err != null) {
                    Toast.makeText(StartMenuActivity.this,
                            getString(R.string.toast_scale_failed, err),
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(StartMenuActivity.this,
                            getString(R.string.toast_scale_done, res[0], res[1]),
                            Toast.LENGTH_LONG).show();
                    if (warn != null) {
                        Toast.makeText(StartMenuActivity.this,
                                getString(R.string.toast_scale_warning, warn),
                                Toast.LENGTH_LONG).show();
                    }
                }
            });
        });
    }

    private interface SizeDialogCallback {
        void onSizes(int width, int height);
    }

    private void importTxtMapWithBackground(Uri txtUri, Uri backgroundUri) {
        String fileName = MapManager.resolveFileName(getContentResolver(), txtUri);
        if (fileName == null || !fileName.toLowerCase(Locale.US).endsWith(".txt")) {
            Toast.makeText(this, getString(R.string.toast_select_txt), Toast.LENGTH_SHORT).show();
            return;
        }

        String mapFolderName = fileName.substring(0, fileName.length() - 4);
        if (mapFolderName.isEmpty()) {
            mapFolderName = "ImportedMap";
        }

        var layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int dp24 = (int) (getResources().getDisplayMetrics().density * 24);
        layout.setPadding(dp24, dp24, dp24, dp24);

        var progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        layout.addView(progressBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        var progressText = new TextView(this);
        progressText.setText("0%");
        progressText.setGravity(Gravity.CENTER);
        progressText.setTextSize(16);
        var textLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        textLp.topMargin = (int) (getResources().getDisplayMetrics().density * 12);
        layout.addView(progressText, textLp);

        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_title_importing_txt))
                .setView(layout)
                .setCancelable(false)
                .show();

        mapManager.importTxtMap(txtUri, getContentResolver(), mapFolderName, backgroundUri,
                new MapManager.TxtImportCallback() {
                    @Override
                    public void onProgressUpdate(int percent) {
                        progressBar.setProgress(percent);
                        progressText.setText(percent + "%");
                    }

                    @Override
                    public void onTxtImportSuccess(String mapName) {
                        if (progressDialog.isShowing()) progressDialog.dismiss();
                        mapManager.setActiveMapName(mapName);
                        Toast.makeText(StartMenuActivity.this,
                                getString(R.string.toast_imported_txt, mapName),
                                Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onTxtImportError(String errorMessage) {
                        if (progressDialog.isShowing()) progressDialog.dismiss();
                        Toast.makeText(StartMenuActivity.this,
                                getString(R.string.toast_failed_import_txt, errorMessage),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void importZipFile(Uri zipUri) {
        String fileName = MapManager.resolveFileName(getContentResolver(), zipUri);
        if (fileName == null || !fileName.toLowerCase(Locale.US).endsWith(".zip")) {
            Toast.makeText(this, getString(R.string.toast_select_zip), Toast.LENGTH_SHORT).show();
            return;
        }

        var layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int dp24 = (int) (getResources().getDisplayMetrics().density * 24);
        layout.setPadding(dp24, dp24, dp24, dp24);

        var progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        layout.addView(progressBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        var progressText = new TextView(this);
        progressText.setText("0%");
        progressText.setGravity(Gravity.CENTER);
        progressText.setTextSize(16);
        var textLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        textLp.topMargin = (int) (getResources().getDisplayMetrics().density * 12);
        layout.addView(progressText, textLp);

        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_title_importing))
                .setView(layout)
                .setCancelable(false)
                .show();

        mapManager.importZipArchive(zipUri, getContentResolver(),
                new MapManager.ZipImportCallback() {
                    @Override
                    public void onProgressUpdate(int percent) {
                        progressBar.setProgress(percent);
                        progressText.setText(percent + "%");
                    }

                    @Override
                    public void onZipImportSuccess(String mapName) {
                        if (progressDialog.isShowing()) progressDialog.dismiss();
                        mapManager.setActiveMapName(mapName);
                        Toast.makeText(StartMenuActivity.this,
                                getString(R.string.toast_extracted_zip, mapName),
                                Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onZipImportError(String errorMessage) {
                        if (progressDialog.isShowing()) progressDialog.dismiss();
                        Toast.makeText(StartMenuActivity.this,
                                getString(R.string.toast_failed_extract_zip, errorMessage),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }
}
