
package com.atakmap.android.lingualinkledger.plugin;

import static com.atakmap.android.maps.MapView.getMapView;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.widget.PopupWindow;
import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.importexport.ExportFilters;
import com.atakmap.android.importexport.Exportable;
import com.atakmap.android.importexport.FormatNotSupportedException;
import com.atakmap.android.importfiles.ui.ImportManagerFileBrowser;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.importexport.send.SendDialog;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;
import com.atakmap.android.missionpackage.export.MissionPackageExportMarshal;
import com.atakmap.android.missionpackage.export.MissionPackageExportWrapper;

public class LinguaLinkLedger implements IPlugin {

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane mainPane;
    String TAG = "LinguaLinkLedger";
    View mainView;

    private Button helpButton;
    private Button createEditButton;
    private Button fingerprintButton;
    private Button sendButton;
    private String selectedMsvPath; // Variable to store the selected MSV path

    // Directory that the plugin will store all of its data in
    final File TOOLS_DIR = FileSystemUtils.getItem("tools/lingualinkledger");

    // ATAK's default directory fro newly created data packages
    final File NEW_PACKAGE_DIR = FileSystemUtils.getItem("tools/datapackage");

    // Directory where hashed data packages are stored
    final File HASHED_PACKAGE_DIR = new File(TOOLS_DIR, "hashed_packages");

    // Directory where sent data packages are stored
    final File SENT_PACKAGE_DIR = new File(TOOLS_DIR, "sent_packages");

    public LinguaLinkLedger(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        // obtain the UI service
        uiService = serviceController.getService(IHostUIService.class);

        // initialize the toolbar button for the plugin

        // create the button
        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        pluginContext.getResources().getDrawable(R.drawable.ic_launcher),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showPane();
                    }
                })
                .build();
    }

    @Override
    public void onStart() {
        // the plugin is starting, add the button to the toolbar
        if (uiService == null)
            return;

        uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
        // the plugin is stopping, remove the button from the toolbar
        if (uiService == null)
            return;

        uiService.removeToolbarItem(toolbarItem);
    }

    private void showPane() {
        mainView = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);

        // Set up button click listeners
        setupButtonListeners();

        mainPane = new PaneBuilder(mainView)
                // relative location is set to default; pane will switch location dependent on
                // current orientation of device screen
                .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                // pane will take up 50% of screen width in landscape mode
                .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                // pane will take up 50% of screen height in portrait mode
                .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                .build();

        createDataPackageDirectories();

        // if the plugin pane is not visible, show it!
        if (!uiService.isPaneVisible(mainPane)) {
            uiService.showPane(mainPane, null);
        }
    }

    private void setupButtonListeners() {

        helpButton = mainView.findViewById(R.id.help_button);
        createEditButton = mainView.findViewById(R.id.create_edit_dp_button);
        fingerprintButton = mainView.findViewById(R.id.fingerprint_dp_button);
        sendButton = mainView.findViewById(R.id.send_dp_button);
        // handle button clicks
        // for now we are just displaying messages as a placeholder

        helpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHelpDialog();
            }
        });

        createEditButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // create/edit data package functionality
                Toast.makeText(pluginContext, "Create/Edit Data Package clicked", Toast.LENGTH_SHORT).show();

                // Allow the user to create or edit a data package
                createOrEditDatapackage();
            }
        });

        fingerprintButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // fingerprint data package functionality
                PromptForMSVFileSelection(v.getContext());
                Toast.makeText(pluginContext, "Fingerprint Data Package clicked", Toast.LENGTH_SHORT).show();
            }
        });

        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // send data package functionality
                packageToTak();
                Toast.makeText(pluginContext, "Send Data Package clicked", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showHelpDialog() {
        LayoutInflater inflater = (LayoutInflater) pluginContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View popupView = inflater.inflate(R.layout.help_dialog_layout, null);

        // Set width to 80% of screen width, height to wrap content
        int width = (int) (mainView.getResources().getDisplayMetrics().widthPixels * 0.8);
        int height = WindowManager.LayoutParams.WRAP_CONTENT;
        final PopupWindow popupWindow = new PopupWindow(popupView, width, height, true);

        Button exitButton = popupView.findViewById(R.id.help_exit_button);
        exitButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                popupWindow.dismiss();
            }
        });

        popupWindow.showAtLocation(mainView.getRootView(), Gravity.CENTER, 0, 0);
    }

    private void createOrEditDatapackage() {
        MapView mapView = MapView.getMapView();
        MissionPackageExportMarshal missionPackageExportMarshal = new MissionPackageExportMarshal(mapView.getContext(), true);
        List<Exportable> exportables = new ArrayList<>();
        exportables.add(new Exportable() {
            @Override
            public boolean isSupported(Class<?> aClass) {
                return true;
            }

            @Override
            public Object toObjectOf(Class<?> aClass, ExportFilters exportFilters) throws FormatNotSupportedException {
                return new MissionPackageExportWrapper(false, "/sdcard/atak/support/support.inf");
            }
        });
        try {
            missionPackageExportMarshal.execute(exportables);
        } catch (Exception e) {
            Log.d(TAG, "Error building a new datapackage with exportables: " + exportables, e);
        }
    }

    // Create all of the date package storage directories that are used.
    private void createDataPackageDirectories() {
        createDirectoryIfDoesNotExist(TOOLS_DIR);
        createDirectoryIfDoesNotExist(NEW_PACKAGE_DIR);
        createDirectoryIfDoesNotExist(HASHED_PACKAGE_DIR);
        createDirectoryIfDoesNotExist(SENT_PACKAGE_DIR);
    }

    // If the directory doesn't exist, create it
    private void createDirectoryIfDoesNotExist(File directory) {
        // Check if the directory exists
        if (!directory.exists()) {
            // Create the directory
            boolean created = directory.mkdir(); // Use mkdirs() to create parent directories as well

            // Make sure the creation was successful
            if (created) {
                Log.w(TAG, "Directory created successfully: " + directory.getAbsolutePath());
            } else {
                Log.e(TAG, "Failed to create directory.");
            }
        }
    }

    private boolean moveFile(File sourceFile, File destinationDir) {
        try {
            // Ensure destination directory exists
            if (!destinationDir.exists()) {
                destinationDir.mkdirs();
            }

            // Define the destination file path (keep same filename)
            File destFile = new File(destinationDir, sourceFile.getName());

            // Create streams
            try (InputStream in = new FileInputStream(sourceFile);
                 OutputStream out = new FileOutputStream(destFile)) {

                byte[] buffer = new byte[4096];
                int length;
                while ((length = in.read(buffer)) > 0) {
                    out.write(buffer, 0, length);
                }
            }

            // Delete the original file
            return sourceFile.delete();

        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private void PromptForMSVFileSelection(Context context) {
        final String[] fileExtensions = {"zip"};
        final String startingDirectory = "/sdcard/atak/tools/datapackage";
        final ImportManagerFileBrowser fileBrowser = ImportManagerFileBrowser.inflate(getMapView());
        fileBrowser.setTitle("Select a Mission Package");
        fileBrowser.setStartDirectory(startingDirectory);
        fileBrowser.setExtensionTypes(fileExtensions);
        AlertDialog.Builder alertBuilder = new AlertDialog.Builder(getMapView().getContext());
        alertBuilder.setView(fileBrowser);

        alertBuilder.setNegativeButton("Cancel", null);
        alertBuilder.setPositiveButton("Select", (dialog, num) -> {
            List<File> chosenFiles = fileBrowser.getSelectedFiles();
            if (chosenFiles.isEmpty()) {
                Toast.makeText(pluginContext, "No Files Selected", Toast.LENGTH_SHORT).show();
            } else {
                selectedMsvPath = chosenFiles.get(0).getAbsolutePath();
                Toast.makeText(pluginContext, "Selected File: " + selectedMsvPath, Toast.LENGTH_SHORT).show();

                if (selectedMsvPath != null) {
                    String fileHash = generateFileHash(selectedMsvPath);
                    String fileName= fileHash.substring(fileHash.length() - 6) + ".txt.zip.dtg.sha256";
                    File file = new File(HASHED_PACKAGE_DIR, fileName);

                    try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                        writer.write(fileHash);
                        file.setWritable(false);
                        Log.w(TAG, fileName + "stored in hashed_packages");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    if (fileHash != null) {
                        showHashPopup(fingerprintButton, fileHash); // Method to show the hash value in a popup
                    } else {
                        Toast.makeText(pluginContext, "Failed to generate hash", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(pluginContext, "No file selected", Toast.LENGTH_SHORT).show();
                }
            }
        });
        final AlertDialog alertDialog = alertBuilder.create();
        fileBrowser.setAlertDialog(alertDialog);
        alertDialog.show();
    }


    private void showHashPopup(View anchorView, String hashValue) {
        LayoutInflater inflater = (LayoutInflater) pluginContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View popupView = inflater.inflate(R.layout.popup_hash_value, null);

        TextView hashTextView = popupView.findViewById(R.id.hash_value_text);
        hashTextView.setText(hashValue);

        final PopupWindow popupWindow = new PopupWindow(popupView,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true);

        popupWindow.showAtLocation(anchorView.getRootView(), Gravity.CENTER, 0, 0);
    }

    private String generateFileHash(String filePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            InputStream fis = new FileInputStream(filePath);
            byte[] buffer = new byte[1024];
            int bytesRead;

            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            fis.close();

            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void packageToTak() {
        if (selectedMsvPath != null) {
            File msvFile = new File(selectedMsvPath);
            new SendDialog.Builder(getMapView()).addFile(msvFile).show();
//            boolean moved = moveFile(msvFile, HASHED_PACKAGE_DIR);
//
//            if (moved) {
//                Log.d("Move", "File moved successfully");
//            } else {
//                Log.e("Move", "Failed to move file");
//            }
        } else {
            Toast.makeText(pluginContext, "No MSV file selected", Toast.LENGTH_SHORT).show();
        }
    }
}