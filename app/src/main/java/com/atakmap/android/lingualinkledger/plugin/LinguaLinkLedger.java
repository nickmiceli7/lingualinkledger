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
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.PopupWindow;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;

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

    private Button healthCheckButton;
    private Button helpButton;
    private Button createEditButton;
    private Button createEvidenceButton;
    private Button signEvidenceButton;
    private Button sendButton;
    private String selectedMsvPath; 

    final File TOOLS_DIR = FileSystemUtils.getItem("tools/lingualinkledger");
    final File NEW_PACKAGE_DIR = FileSystemUtils.getItem("tools/datapackage");
    final File HASHED_PACKAGE_DIR = new File(TOOLS_DIR, "hashed_packages");
    final File SENT_PACKAGE_DIR = new File(TOOLS_DIR, "sent_packages");

    private class DataPackageMetadata {
        String packageName;
        String description;
        String author;
        double latitude;
        double longitude;

        public DataPackageMetadata(String packageName, String description, String author, double latitude, double longitude) {
            this.packageName = packageName;
            this.description = description;
            this.author = author;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public boolean isValid() {
            return packageName != null && !packageName.trim().isEmpty();
        }
    }

    public LinguaLinkLedger(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        uiService = serviceController.getService(IHostUIService.class);

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
        if (uiService == null) return;
        uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
        if (uiService == null) return;
        uiService.removeToolbarItem(toolbarItem);
    }

    private void showPane() {
        mainView = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);
        setupButtonListeners();

        mainPane = new PaneBuilder(mainView)
                .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                .build();

        createDataPackageDirectories();

        if (!uiService.isPaneVisible(mainPane)) {
            uiService.showPane(mainPane, null);
        }
    }

    private void setupButtonListeners() {
        healthCheckButton = mainView.findViewById(R.id.health_check_button);
        helpButton = mainView.findViewById(R.id.help_button);
        createEditButton = mainView.findViewById(R.id.create_edit_dp_button);
        createEvidenceButton = mainView.findViewById(R.id.create_evidence_trace_button);
        signEvidenceButton = mainView.findViewById(R.id.sign_evidence_trace_button);
        sendButton = mainView.findViewById(R.id.send_dp_button);

        healthCheckButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHealthCheckDialog();
            }
        });

        helpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHelpDialog();
            }
        });

        createEditButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createOrEditDatapackage();
            }
        });

        createEvidenceButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PromptForEvidenceTraceSelection(v.getContext());
            }
        });

        signEvidenceButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSignRecordDialog();
            }
        });

        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PromptForMSVFileSelection(v.getContext());
            }
        });
    }

    private void showHelpDialog() {
        LayoutInflater inflater = (LayoutInflater) pluginContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View popupView = inflater.inflate(R.layout.help_dialog_layout, null);

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

    private void showHealthCheckDialog() {
        LayoutInflater inflater = (LayoutInflater) pluginContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View dialogView = inflater.inflate(R.layout.health_check_dialog, null);

        int width = (int) (mainView.getResources().getDisplayMetrics().widthPixels * 0.8);
        int height = WindowManager.LayoutParams.WRAP_CONTENT;
        final PopupWindow healthDialog = new PopupWindow(dialogView, width, height, true);

        LinearLayout loadingLayout = dialogView.findViewById(R.id.loading_layout);
        LinearLayout healthContent = dialogView.findViewById(R.id.health_content);
        TextView errorContent = dialogView.findViewById(R.id.error_content);
        TextView statusValue = dialogView.findViewById(R.id.status_value);
        TextView connectedValue = dialogView.findViewById(R.id.connected_value);
        TextView balanceValue = dialogView.findViewById(R.id.balance_value);
        TextView blockValue = dialogView.findViewById(R.id.block_value);
        TextView recordsValue = dialogView.findViewById(R.id.records_value);
        Button closeButton = dialogView.findViewById(R.id.health_close_button);

        closeButton.setOnClickListener(v -> healthDialog.dismiss());

        healthDialog.showAtLocation(mainView.getRootView(), Gravity.CENTER, 0, 0);

        BlockchainService.checkHealth(new BlockchainService.HealthCheckCallback() {
            @Override
            public void onSuccess(BlockchainService.HealthResponse response) {
                mainView.post(() -> {
                    loadingLayout.setVisibility(View.GONE);
                    statusValue.setText(response.status);
                    statusValue.setTextColor(response.status.equals("healthy") ? 0xFF00FF00 : 0xFFFF6666);
                    connectedValue.setText(response.connected ? "Yes" : "No");
                    connectedValue.setTextColor(response.connected ? 0xFF00FF00 : 0xFFFF6666);
                    balanceValue.setText(response.ethBalanceFormatted);
                    blockValue.setText(String.valueOf(response.latestBlock));
                    recordsValue.setText(String.valueOf(response.totalRecords));
                    healthContent.setVisibility(View.VISIBLE);
                });
            }

            @Override
            public void onError(String errorMessage) {
                mainView.post(() -> {
                    loadingLayout.setVisibility(View.GONE);
                    errorContent.setText("Failed to connect to gateway:\n" + errorMessage);
                    errorContent.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void createOrEditDatapackage() {
        showMetadataFormDialog();
    }

    private void showMetadataFormDialog() {
        LayoutInflater inflater = (LayoutInflater) pluginContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View dialogView = inflater.inflate(R.layout.metadata_form, null);

        final EditText packageNameField = dialogView.findViewById(R.id.metadata_package_name);
        final EditText descriptionField = dialogView.findViewById(R.id.metadata_description);
        final EditText authorField = dialogView.findViewById(R.id.metadata_author);
        final EditText latitudeField = dialogView.findViewById(R.id.metadata_latitude);
        final EditText longitudeField = dialogView.findViewById(R.id.metadata_longitude);

        MapView mapView = MapView.getMapView();
        if (mapView != null) {
            com.atakmap.coremap.maps.coords.GeoPointMetaData centerPointMeta = mapView.getPoint();
            if (centerPointMeta != null) {
                com.atakmap.coremap.maps.coords.GeoPoint centerPoint = centerPointMeta.get();
                if (centerPoint != null) {
                    latitudeField.setText(String.format("%.6f", centerPoint.getLatitude()));
                    longitudeField.setText(String.format("%.6f", centerPoint.getLongitude()));
                }
            }
        }

        final AlertDialog dialog = new AlertDialog.Builder(getMapView().getContext())
                .setView(dialogView)
                .create();

        Button cancelButton = dialogView.findViewById(R.id.metadata_cancel_button);
        Button createButton = dialogView.findViewById(R.id.metadata_create_button);

        cancelButton.setOnClickListener(v -> dialog.dismiss());

        createButton.setOnClickListener(v -> {
            String packageName = packageNameField.getText().toString().trim();
            String description = descriptionField.getText().toString().trim();
            String author = authorField.getText().toString().trim();

            double latitude = 0.0;
            double longitude = 0.0;
            try {
                latitude = Double.parseDouble(latitudeField.getText().toString().trim());
                longitude = Double.parseDouble(longitudeField.getText().toString().trim());
            } catch (NumberFormatException e) {
                Toast.makeText(pluginContext, "Invalid latitude or longitude", Toast.LENGTH_SHORT).show();
                return;
            }

            if (packageName.isEmpty()) {
                Toast.makeText(pluginContext, "Package name is required", Toast.LENGTH_SHORT).show();
                return;
            }

            DataPackageMetadata metadata = new DataPackageMetadata(
                    packageName, description, author, latitude, longitude);

            dialog.dismiss();
            createDataPackageWithMetadata(metadata);
        });

        dialog.show();
    }

    private void createDataPackageWithMetadata(DataPackageMetadata metadata) {
        MapView mapView = MapView.getMapView();

        try {
            File metadataFile = createMetadataFile(metadata);
            MissionPackageExportMarshal missionPackageExportMarshal =
                    new MissionPackageExportMarshal(mapView.getContext(), true);

            List<Exportable> exportables = new ArrayList<>();
            exportables.add(new Exportable() {
                @Override
                public boolean isSupported(Class<?> aClass) {
                    return true;
                }

                @Override
                public Object toObjectOf(Class<?> aClass, ExportFilters exportFilters)
                        throws FormatNotSupportedException {
                    return new MissionPackageExportWrapper(false, metadataFile.getAbsolutePath());
                }
            });

            missionPackageExportMarshal.execute(exportables);

            Toast.makeText(pluginContext,
                    "Data package '" + metadata.packageName + "' created successfully",
                    Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Log.e(TAG, "Error creating data package with metadata", e);
            Toast.makeText(pluginContext,
                    "Error creating data package: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private File createMetadataFile(DataPackageMetadata metadata) throws IOException {
        File metadataFile = new File(TOOLS_DIR, metadata.packageName + "_metadata.txt");

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(metadataFile))) {
            writer.write("Package Name: " + metadata.packageName + "\n");
            writer.write("Description: " + metadata.description + "\n");
            writer.write("Author: " + metadata.author + "\n");
            writer.write("Latitude: " + metadata.latitude + "\n");
            writer.write("Longitude: " + metadata.longitude + "\n");
            writer.write("Created: " + new java.util.Date().toString() + "\n");
        }
        return metadataFile;
    }

    private void createDataPackageDirectories() {
        createDirectoryIfDoesNotExist(TOOLS_DIR);
        createDirectoryIfDoesNotExist(NEW_PACKAGE_DIR);
        createDirectoryIfDoesNotExist(HASHED_PACKAGE_DIR);
        createDirectoryIfDoesNotExist(SENT_PACKAGE_DIR);
    }

    private void createDirectoryIfDoesNotExist(File directory) {
        if (!directory.exists()) {
            boolean created = directory.mkdir();
            if (created) {
                Log.w(TAG, "Directory created successfully: " + directory.getAbsolutePath());
            } else {
                Log.e(TAG, "Failed to create directory.");
            }
        }
    }

    private boolean moveFile(File sourceFile, File destinationDir) {
        try {
            if (!destinationDir.exists()) {
                destinationDir.mkdirs();
            }

            File destFile = new File(destinationDir, sourceFile.getName());

            try (InputStream in = new FileInputStream(sourceFile);
                 OutputStream out = new FileOutputStream(destFile)) {

                byte[] buffer = new byte[4096];
                int length;
                while ((length = in.read(buffer)) > 0) {
                    out.write(buffer, 0, length);
                }
            }
            return sourceFile.delete();

        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    // This handles the new "Send Data Package" workflow with the popup.
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
                        final PopupWindow hashPopup = showHashPopup(sendButton, fileHash);
                        sendButton.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (hashPopup != null && hashPopup.isShowing()) {
                                    hashPopup.dismiss();
                                }
                                packageToTak();
                            }
                        }, 2000);
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

    // This handles the Blockchain "Create Evidence Trace" upload.
    private void PromptForEvidenceTraceSelection(Context context) {
        final String[] fileExtensions = {"zip"};
        final String startingDirectory = "/sdcard/atak/tools/datapackage";
        final ImportManagerFileBrowser fileBrowser = ImportManagerFileBrowser.inflate(getMapView());
        fileBrowser.setTitle("Select a Mission Package to Trace");
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
                processSelectedFile(selectedMsvPath);
            }
        });
        final AlertDialog alertDialog = alertBuilder.create();
        fileBrowser.setAlertDialog(alertDialog);
        alertDialog.show();
    }

    private void processSelectedFile(String filePath) {
        String fileHash = generateFileHash(filePath);
        if (fileHash != null) {
            Log.d(TAG, "Generated file hash: " + fileHash);
            saveLocalHashFile(fileHash);
            showBlockchainDialog(fileHash);
        } else {
            Toast.makeText(pluginContext, "Failed to generate hash", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveLocalHashFile(String fileHash) {
        String fileName = fileHash.substring(Math.max(0, fileHash.length() - 6)) + ".txt.zip.dtg.sha256";
        File file = new File(HASHED_PACKAGE_DIR, fileName);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(fileHash);
            file.setWritable(false);
            Log.d(TAG, fileName + " stored in hashed_packages");
        } catch (IOException e) {
            Log.e(TAG, "Failed to save local hash file", e);
        }
    }

    private void showBlockchainDialog(String fileHash) {
        LayoutInflater inflater = (LayoutInflater) pluginContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View dialogView = inflater.inflate(R.layout.blockchain_loading_dialog, null);

        int width = (int) (mainView.getResources().getDisplayMetrics().widthPixels * 0.85);
        int height = WindowManager.LayoutParams.WRAP_CONTENT;
        final PopupWindow blockchainDialog = new PopupWindow(dialogView, width, height, false);

        ProgressBar loadingSpinner = dialogView.findViewById(R.id.loading_spinner);
        TextView successIcon = dialogView.findViewById(R.id.success_icon);
        TextView errorIcon = dialogView.findViewById(R.id.error_icon);
        TextView statusText = dialogView.findViewById(R.id.status_text);
        LinearLayout transactionDetails = dialogView.findViewById(R.id.transaction_details);
        TextView recordIdText = dialogView.findViewById(R.id.record_id_text);
        TextView txHashText = dialogView.findViewById(R.id.tx_hash_text);
        TextView errorDetails = dialogView.findViewById(R.id.error_details);
        Button closeButton = dialogView.findViewById(R.id.close_button);

        closeButton.setOnClickListener(v -> blockchainDialog.dismiss());
        blockchainDialog.showAtLocation(mainView.getRootView(), Gravity.CENTER, 0, 0);

        Log.d(TAG, "Submitting file hash to blockchain: " + fileHash);

        BlockchainService.submitFileHash(fileHash, new BlockchainService.BlockchainCallback() {
            @Override
            public void onSuccess(BlockchainService.BlockchainResponse response) {
                mainView.post(() -> {
                    loadingSpinner.setVisibility(View.GONE);
                    successIcon.setVisibility(View.VISIBLE);
                    statusText.setText("Transaction completed successfully!");
                    recordIdText.setText("Record ID: " + response.recordId);
                    txHashText.setText("TX Hash: " + response.transactionHash);
                    transactionDetails.setVisibility(View.VISIBLE);
                    closeButton.setVisibility(View.VISIBLE);

                    BlockchainService.saveTransactionRecord(HASHED_PACKAGE_DIR, fileHash, response);
                    Toast.makeText(pluginContext, "File fingerprinted to blockchain! Record ID: " + response.recordId, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onError(String errorMessage) {
                mainView.post(() -> {
                    loadingSpinner.setVisibility(View.GONE);
                    errorIcon.setVisibility(View.VISIBLE);
                    statusText.setText("Transaction failed");
                    errorDetails.setText("Error: " + errorMessage);
                    errorDetails.setVisibility(View.VISIBLE);
                    closeButton.setVisibility(View.VISIBLE);
                    Toast.makeText(pluginContext, "Blockchain transaction failed: " + errorMessage, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showSignRecordDialog() {
        LayoutInflater inflater = (LayoutInflater) pluginContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View dialogView = inflater.inflate(R.layout.sign_record_dialog, null);

        int width = (int) (mainView.getResources().getDisplayMetrics().widthPixels * 0.8);
        int height = WindowManager.LayoutParams.WRAP_CONTENT;
        final PopupWindow signDialog = new PopupWindow(dialogView, width, height, true);

        EditText recordIdInput = dialogView.findViewById(R.id.record_id_input);
        Spinner roleSpinner = dialogView.findViewById(R.id.role_spinner);
        EditText remarksInput = dialogView.findViewById(R.id.remarks_input);
        Button cancelButton = dialogView.findViewById(R.id.sign_cancel_button);
        Button submitButton = dialogView.findViewById(R.id.sign_submit_button);

        String[] roles = {"trucker", "processor"};
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(pluginContext, android.R.layout.simple_spinner_item, roles) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView textView = (TextView) view;
                textView.setTextColor(0xFFFFFFFF);
                textView.setTextSize(14);
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                TextView textView = (TextView) view;
                textView.setTextColor(0xFF000000);
                textView.setBackgroundColor(0xFFFFFFFF);
                textView.setPadding(16, 12, 16, 12);
                textView.setTextSize(14);
                return view;
            }
        };

        roleSpinner.setAdapter(adapter);

        cancelButton.setOnClickListener(v -> signDialog.dismiss());

        submitButton.setOnClickListener(v -> {
            String recordIdText = recordIdInput.getText().toString().trim();
            String selectedRole = (String) roleSpinner.getSelectedItem();
            String remarks = remarksInput.getText().toString().trim();

            if (recordIdText.isEmpty()) {
                Toast.makeText(pluginContext, "Please enter a record ID", Toast.LENGTH_SHORT).show();
                return;
            }

            if (remarks.isEmpty()) {
                Toast.makeText(pluginContext, "Please enter remarks", Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                int recordId = Integer.parseInt(recordIdText);
                signDialog.dismiss();
                performRecordSigning(recordId, selectedRole, remarks);
            } catch (NumberFormatException e) {
                Toast.makeText(pluginContext, "Please enter a valid record ID", Toast.LENGTH_SHORT).show();
            }
        });

        signDialog.showAtLocation(mainView.getRootView(), Gravity.CENTER, 0, 0);
    }

    private void performRecordSigning(int recordId, String role, String remarks) {
        LayoutInflater inflater = (LayoutInflater) pluginContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View dialogView = inflater.inflate(R.layout.blockchain_loading_dialog, null);

        int width = (int) (mainView.getResources().getDisplayMetrics().widthPixels * 0.85);
        int height = WindowManager.LayoutParams.WRAP_CONTENT;
        final PopupWindow blockchainDialog = new PopupWindow(dialogView, width, height, false);

        ProgressBar loadingSpinner = dialogView.findViewById(R.id.loading_spinner);
        TextView successIcon = dialogView.findViewById(R.id.success_icon);
        TextView errorIcon = dialogView.findViewById(R.id.error_icon);
        TextView statusText = dialogView.findViewById(R.id.status_text);
        LinearLayout transactionDetails = dialogView.findViewById(R.id.transaction_details);
        TextView recordIdText = dialogView.findViewById(R.id.record_id_text);
        TextView txHashText = dialogView.findViewById(R.id.tx_hash_text);
        TextView errorDetails = dialogView.findViewById(R.id.error_details);
        Button closeButton = dialogView.findViewById(R.id.close_button);

        statusText.setText("Signing record " + recordId + " as " + role + ". Please wait...");
        closeButton.setOnClickListener(v -> blockchainDialog.dismiss());
        blockchainDialog.showAtLocation(mainView.getRootView(), Gravity.CENTER, 0, 0);

        BlockchainService.verifyRecord(recordId, role, remarks, new BlockchainService.BlockchainCallback() {
            @Override
            public void onSuccess(BlockchainService.BlockchainResponse response) {
                mainView.post(() -> {
                    loadingSpinner.setVisibility(View.GONE);
                    successIcon.setVisibility(View.VISIBLE);
                    statusText.setText("Record signed successfully!");
                    recordIdText.setText("Record Verified (ID: " + recordId + ")");
                    txHashText.setText("TX Hash: " + response.transactionHash);
                    transactionDetails.setVisibility(View.VISIBLE);
                    closeButton.setVisibility(View.VISIBLE);
                    Toast.makeText(pluginContext, "Record " + recordId + " signed successfully as " + role, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onError(String errorMessage) {
                mainView.post(() -> {
                    loadingSpinner.setVisibility(View.GONE);
                    errorIcon.setVisibility(View.VISIBLE);
                    statusText.setText("Record signing failed");
                    errorDetails.setText("Error: " + errorMessage);
                    errorDetails.setVisibility(View.VISIBLE);
                    closeButton.setVisibility(View.VISIBLE);
                    Toast.makeText(pluginContext, "Failed to sign record: " + errorMessage, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private PopupWindow showHashPopup(View anchorView, String hashValue) {
        LayoutInflater inflater = (LayoutInflater) pluginContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View popupView = inflater.inflate(R.layout.popup_hash_value, null);

        TextView hashTextView = popupView.findViewById(R.id.hash_value_text);
        hashTextView.setText(hashValue);

        final PopupWindow popupWindow = new PopupWindow(popupView,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true);

        popupWindow.showAtLocation(anchorView.getRootView(), Gravity.CENTER, 0, 0);
        return popupWindow;
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
        } else {
            Toast.makeText(pluginContext, "No MSV file selected", Toast.LENGTH_SHORT).show();
        }
    }
}