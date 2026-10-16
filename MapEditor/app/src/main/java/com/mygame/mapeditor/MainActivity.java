package com.mygame.mapeditor;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_FILE_PICKER = 1001;
    private static final int REQUEST_CODE_EXPORT = 1002;

    private GamePanel gamePanel;
    private File pendingExportZip;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.attachBaseContext(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String mapName = getIntent().getStringExtra("MAP_NAME");
        if (mapName == null || mapName.isEmpty()) {
            mapName = "Default";
        }
        gamePanel = new GamePanel(this, mapName);
        setContentView(gamePanel);
        makeFullScreen();

        gamePanel.onImportRequested = () -> {
            try {
                var intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(intent, REQUEST_CODE_FILE_PICKER);
            } catch (Exception e) {
                Toast.makeText(MainActivity.this,
                        getString(R.string.toast_cannot_open_file_picker),
                        Toast.LENGTH_SHORT).show();
            }
        };

        gamePanel.onExportZipRequested = zipFile -> {
            pendingExportZip = zipFile;
            try {
                var intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/zip");
                intent.putExtra(Intent.EXTRA_TITLE, zipFile.getName());
                startActivityForResult(intent, REQUEST_CODE_EXPORT);
            } catch (Exception e) {
                Toast.makeText(MainActivity.this,
                        getString(R.string.toast_export_error, e.getMessage()),
                        Toast.LENGTH_LONG).show();
            }
        };

        gamePanel.loadCitiesAndMountainsAsync();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQUEST_CODE_FILE_PICKER) {
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    uris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (!uris.isEmpty()) {
                gamePanel.importFilesFromUris(uris);
            }
        } else if (requestCode == REQUEST_CODE_EXPORT) {
            Uri target = data.getData();
            if (target == null || pendingExportZip == null) return;
            new Thread(() -> {
                try {
                    copyFileToUri(pendingExportZip, target);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            getString(R.string.toast_export_saved),
                            Toast.LENGTH_LONG).show());
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            getString(R.string.toast_export_error, e.getMessage()),
                            Toast.LENGTH_LONG).show());
                }
            }).start();
        }
    }

    private void copyFileToUri(File source, Uri target) throws Exception {
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(target, "w");
             var in = new FileInputStream(source)) {
            if (pfd == null) throw new Exception("Could not open destination");
            try (OutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
                var buffer = new byte[16 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
        }
    }

    private void makeFullScreen() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            View decor = getWindow().getDecorView();
            decor.post(() -> {
                WindowInsetsController controller = decor.getWindowInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            });
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
}
