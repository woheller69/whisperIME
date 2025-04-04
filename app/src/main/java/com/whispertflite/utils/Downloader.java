package com.whispertflite.utils;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.whispertflite.R;
import com.whispertflite.databinding.ActivityDownloadBinding;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;


@SuppressWarnings("ResultOfMethodCallIgnored")
public class Downloader {
    static final String modelMultiLingualBaseOLD = "whisper-base.tflite"; //Todo Remove ...OLD... stuff later
    static final String modelMultiLingualBaseOLD2 = "whisper-base.EUROPEAN_UNION.tflite"; //Todo Remove ...OLD... stuff later
    static final String modelMultiLingualBase = "whisper-base.TOP_WORLD.tflite";
    static final String modelMultiLingualSmallOLD = "whisper-small.tflite";
    static final String modelMultiLingualSmall = "whisper-small.TOP_WORLD.tflite";
    static final String modelEnglishOnly = "whisper-tiny.en.tflite";
    static final String modelMultiLingualBaseURL = "https://huggingface.co/DocWolle/whisper_tflite_models/resolve/main/whisper-base.TOP_WORLD.tflite";
    static final String modelMultiLingualSmallURL = "https://huggingface.co/DocWolle/whisper_tflite_models/resolve/main/whisper-small.TOP_WORLD.tflite";
    static final String modelEnglishOnlyURL = "https://huggingface.co/DocWolle/whisper_tflite_models/resolve/main/whisper-tiny.en.tflite";
    static final String modelMultiLingualBaseOLDMD5 = "4b4fddfac6a24ffecc4972bc2137ba04";
    static final String modelMultiLingualBaseOLD2MD5 = "82adc0d42761f6d83fecd76d0325bcf5";
    static final String modelMultiLingualBaseMD5 = "9e43f385a916ac4b2e48760ce1fa70fc";
    static final String modelMultiLingualSmallOLDMD5 = "c4f948b3b42e7536bcedf78eec9481a6";
    static final String modelMultiLingualSmallMD5 = "d3badbb86c9bcc7312c19167acac7133";
    static final String modelEnglishOnlyMD5 ="2e745cdd5dfe2f868f47caa7a199f91a";
    static final long modelMultiLingualBaseSize = 107564368;
    static final long modelMultiLingualSmallSize = 307408944;
    static final long modelEnglishOnlySize = 41486616;
    static long downloadModelMultiLingualBaseSize = 0L;
    static long downloadModelMultiLingualSmallSize = 0L;
    static long downloadModelEnglishOnlySize = 0L;
    static boolean modelMultiLingualBaseFinished = false;
    static boolean modelEnglishOnlyFinished = false;
    static boolean modelMultiLingualSmallFinished = false;

    public static boolean checkUpdate(final Activity activity) {
        File modelMultiLingualBaseFile = new File(activity.getExternalFilesDir(null) + "/" + modelMultiLingualBase);
        File modelMultiLingualSmallFile = new File(activity.getExternalFilesDir(null) + "/" + modelMultiLingualSmall);
        if (!modelMultiLingualBaseFile.exists() || !modelMultiLingualSmallFile.exists()) {
            return true;   //update available
        } else {
            return false;  //no update
        }
    }
    public static boolean checkModels(final Activity activity) {
        copyAssetsToSdcard(activity);
        File modelMultiLingualBaseFile = new File(activity.getExternalFilesDir(null) + "/" + modelMultiLingualBase);
        File modelMultiLingualBaseOLDFile = new File(activity.getExternalFilesDir(null) + "/" + modelMultiLingualBaseOLD);
        File modelMultiLingualBaseOLD2File = new File(activity.getExternalFilesDir(null) + "/" + modelMultiLingualBaseOLD2);
        File modelMultiLingualSmallOLDFile = new File(activity.getExternalFilesDir(null) + "/" + modelMultiLingualSmallOLD);
        File modelMultiLingualSmallFile = new File(activity.getExternalFilesDir(null) + "/" + modelMultiLingualSmall);
        File modelEnglishOnlyFile = new File(activity.getExternalFilesDir(null) + "/" + modelEnglishOnly);
        String calcModelMultiLingualBaseMD5 = "";
        String calcModelMultiLingualBaseOLDMD5 = "";
        String calcModelMultiLingualBaseOLD2MD5 = "";
        String calcModelMultiLingualSmallMD5 = "";
        String calcModelMultiLingualSmallOLDMD5 = "";
        String calcModelEnglishOnlyMD5 = "";
        if (modelMultiLingualBaseFile.exists()) {
            try {
                calcModelMultiLingualBaseMD5 = calculateMD5(String.valueOf(Paths.get(modelMultiLingualBaseFile.getPath())));
            } catch (IOException | NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }
        if (modelMultiLingualBaseOLDFile.exists()) {
            try {
                calcModelMultiLingualBaseOLDMD5 = calculateMD5(String.valueOf(Paths.get(modelMultiLingualBaseOLDFile.getPath())));
            } catch (IOException | NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }
        if (modelMultiLingualBaseOLD2File.exists()) {
            try {
                calcModelMultiLingualBaseOLD2MD5 = calculateMD5(String.valueOf(Paths.get(modelMultiLingualBaseOLD2File.getPath())));
            } catch (IOException | NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }
        if (modelMultiLingualSmallFile.exists()) {
            try {
                calcModelMultiLingualSmallMD5 = calculateMD5(String.valueOf(Paths.get(modelMultiLingualSmallFile.getPath())));
            } catch (IOException | NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }
        if (modelMultiLingualSmallOLDFile.exists()) {
            try {
                calcModelMultiLingualSmallOLDMD5 = calculateMD5(String.valueOf(Paths.get(modelMultiLingualSmallOLDFile.getPath())));
            } catch (IOException | NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }
        if (modelEnglishOnlyFile.exists()) {
            try {
                calcModelEnglishOnlyMD5 = calculateMD5(String.valueOf(Paths.get(modelEnglishOnlyFile.getPath())));
            } catch (IOException | NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }

        if (modelMultiLingualBaseOLDFile.exists() && !(calcModelMultiLingualBaseOLDMD5.equals(modelMultiLingualBaseOLDMD5))) { modelMultiLingualBaseOLDFile.delete();}
        if (modelMultiLingualBaseOLD2File.exists() && !(calcModelMultiLingualBaseOLD2MD5.equals(modelMultiLingualBaseOLD2MD5))) { modelMultiLingualBaseOLD2File.delete();}
        if (modelMultiLingualSmallOLDFile.exists() && !(calcModelMultiLingualSmallOLDMD5.equals(modelMultiLingualSmallOLDMD5))) { modelMultiLingualSmallOLDFile.delete();}
        if (modelMultiLingualBaseFile.exists() && !(calcModelMultiLingualBaseMD5.equals(modelMultiLingualBaseMD5))) { modelMultiLingualBaseFile.delete(); modelMultiLingualBaseFinished = false;}
        if (modelMultiLingualSmallFile.exists() && !(calcModelMultiLingualSmallMD5.equals(modelMultiLingualSmallMD5))) { modelMultiLingualSmallFile.delete(); modelMultiLingualSmallFinished = false;}
        if (modelEnglishOnlyFile.exists() && !calcModelEnglishOnlyMD5.equals(modelEnglishOnlyMD5)) { modelEnglishOnlyFile.delete(); modelEnglishOnlyFinished = false; }

        return (calcModelMultiLingualSmallMD5.equals(modelMultiLingualSmallMD5) || calcModelMultiLingualSmallOLDMD5.equals(modelMultiLingualSmallOLDMD5)) && (calcModelMultiLingualBaseMD5.equals(modelMultiLingualBaseMD5) || calcModelMultiLingualBaseOLDMD5.equals(modelMultiLingualBaseOLDMD5) || calcModelMultiLingualBaseOLD2MD5.equals(modelMultiLingualBaseOLD2MD5)) && calcModelEnglishOnlyMD5.equals(modelEnglishOnlyMD5);
    }

    public static void deleteOldModels(final Activity activity){
        File modelMultiLingualBaseOLDFile = new File(activity.getExternalFilesDir(null) + "/" + modelMultiLingualBaseOLD);
        if (modelMultiLingualBaseOLDFile.exists()) modelMultiLingualBaseOLDFile.delete();
        File modelMultiLingualBaseOLD2File = new File(activity.getExternalFilesDir(null) + "/" + modelMultiLingualBaseOLD2);
        if (modelMultiLingualBaseOLD2File.exists()) modelMultiLingualBaseOLD2File.delete();
        File modelMultiLingualSmallOLDFile = new File(activity.getExternalFilesDir(null) + "/" + modelMultiLingualSmallOLD);
        if (modelMultiLingualSmallOLDFile.exists()) modelMultiLingualSmallOLDFile.delete();
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(activity);
        sp.edit().remove("modelName").apply();
        sp.edit().remove("recognitionServiceModelName").apply();
    }

    public static void downloadModels(final Activity activity, ActivityDownloadBinding binding) {
        checkModels(activity);

        binding.downloadProgress.setProgress(0);
        binding.downloadButton.setEnabled(false);


        File modelMultiLingualBaseFile = new File(activity.getExternalFilesDir(null)+ "/" + modelMultiLingualBase);
        if (!modelMultiLingualBaseFile.exists()) {
            modelMultiLingualBaseFinished = false;
            Log.d("WhisperASR", "multi-lingual base model file does not exist");
            Thread thread = new Thread(() -> {
                try {
                    URL url;

                    url = new URL(modelMultiLingualBaseURL);

                    Log.d("WhisperASR", "Download model");

                    URLConnection ucon = url.openConnection();
                    ucon.setReadTimeout(5000);
                    ucon.setConnectTimeout(10000);

                    InputStream is = ucon.getInputStream();
                    BufferedInputStream inStream = new BufferedInputStream(is, 1024 * 5);

                    modelMultiLingualBaseFile.createNewFile();

                    FileOutputStream outStream = new FileOutputStream(modelMultiLingualBaseFile);
                    byte[] buff = new byte[5 * 1024];

                    int len;
                    while ((len = inStream.read(buff)) != -1) {
                        outStream.write(buff, 0, len);
                        if (modelMultiLingualBaseFile.exists()) downloadModelMultiLingualBaseSize = modelMultiLingualBaseFile.length();
                        activity.runOnUiThread(() -> {
                            binding.downloadSize.setText((downloadModelEnglishOnlySize + downloadModelMultiLingualSmallSize + downloadModelMultiLingualBaseSize)/1024/1024 + " MB");
                            binding.downloadProgress.setProgress((int) (((double)(downloadModelEnglishOnlySize + downloadModelMultiLingualSmallSize + downloadModelMultiLingualBaseSize) / (modelEnglishOnlySize + modelMultiLingualSmallSize +  modelMultiLingualBaseSize)) * 100));
                        });
                    }
                    outStream.flush();
                    outStream.close();
                    inStream.close();
                    String calcModelMultiLingualBaseMD5="";
                    if (modelMultiLingualBaseFile.exists()) {
                        calcModelMultiLingualBaseMD5 = calculateMD5(String.valueOf(Paths.get(modelMultiLingualBaseFile.getPath())));
                    } else {
                        throw new IOException();  //throw exception if there is no modelMultiLingualSmallFile at this point
                    }

                    if (!(calcModelMultiLingualBaseMD5.equals(modelMultiLingualBaseMD5))){
                        modelMultiLingualBaseFile.delete();
                        modelMultiLingualBaseFinished = false;
                        activity.runOnUiThread(() -> {
                            Toast.makeText(activity, activity.getResources().getString(R.string.error_download), Toast.LENGTH_SHORT).show();
                            binding.downloadButton.setEnabled(true);
                        });
                    } else {
                        modelMultiLingualBaseFinished = true;
                        activity.runOnUiThread(() -> {
                            if (modelEnglishOnlyFinished && modelMultiLingualSmallFinished && modelMultiLingualBaseFinished) binding.buttonStart.setVisibility(View.VISIBLE);
                        });
                    }
                } catch (NoSuchAlgorithmException | IOException i) {
                    modelMultiLingualBaseFile.delete();
                    modelMultiLingualBaseFinished = false;
                    activity.runOnUiThread(() -> {
                        Toast.makeText(activity, activity.getResources().getString(R.string.error_download), Toast.LENGTH_SHORT).show();
                        binding.downloadButton.setEnabled(true);
                    });
                    Log.w("WhisperASR", activity.getResources().getString(R.string.error_download), i);
                }
            });
            thread.start();
        } else {
            downloadModelMultiLingualBaseSize = modelMultiLingualBaseSize;
            modelMultiLingualBaseFinished = true;
            activity.runOnUiThread(() -> {
                if (modelEnglishOnlyFinished && modelMultiLingualSmallFinished && modelMultiLingualBaseFinished) binding.buttonStart.setVisibility(View.VISIBLE);
            });
        }

        File modelMultiLingualSmallFile = new File(activity.getExternalFilesDir(null)+ "/" + modelMultiLingualSmall);
        if (!modelMultiLingualSmallFile.exists()) {
            modelMultiLingualSmallFinished = false;
            Log.d("WhisperASR", "multi-lingual small model file does not exist");
            Thread thread = new Thread(() -> {
                try {
                    URL url;

                    url = new URL(modelMultiLingualSmallURL);

                    Log.d("WhisperASR", "Download model");

                    URLConnection ucon = url.openConnection();
                    ucon.setReadTimeout(5000);
                    ucon.setConnectTimeout(10000);

                    InputStream is = ucon.getInputStream();
                    BufferedInputStream inStream = new BufferedInputStream(is, 1024 * 5);

                    modelMultiLingualSmallFile.createNewFile();

                    FileOutputStream outStream = new FileOutputStream(modelMultiLingualSmallFile);
                    byte[] buff = new byte[5 * 1024];

                    int len;
                    while ((len = inStream.read(buff)) != -1) {
                        outStream.write(buff, 0, len);
                        if (modelMultiLingualSmallFile.exists()) downloadModelMultiLingualSmallSize = modelMultiLingualSmallFile.length();
                        activity.runOnUiThread(() -> {
                            binding.downloadSize.setText((downloadModelEnglishOnlySize + downloadModelMultiLingualSmallSize + downloadModelMultiLingualBaseSize)/1024/1024 + " MB");
                            binding.downloadProgress.setProgress((int) (((double)(downloadModelEnglishOnlySize + downloadModelMultiLingualSmallSize + downloadModelMultiLingualBaseSize) / (modelEnglishOnlySize + modelMultiLingualSmallSize + modelMultiLingualBaseSize)) * 100));
                        });
                    }
                    outStream.flush();
                    outStream.close();
                    inStream.close();
                    String calcModelMultiLingualSmallMD5="";
                    if (modelMultiLingualSmallFile.exists()) {
                        calcModelMultiLingualSmallMD5 = calculateMD5(String.valueOf(Paths.get(modelMultiLingualSmallFile.getPath())));
                    } else {
                        throw new IOException();  //throw exception if there is no modelMultiLingualSmallFile at this point
                    }

                    if (!(calcModelMultiLingualSmallMD5.equals(modelMultiLingualSmallMD5))){
                        modelMultiLingualSmallFile.delete();
                        modelMultiLingualSmallFinished = false;
                        activity.runOnUiThread(() -> {
                            Toast.makeText(activity, activity.getResources().getString(R.string.error_download), Toast.LENGTH_SHORT).show();
                            binding.downloadButton.setEnabled(true);
                        });
                    } else {
                        modelMultiLingualSmallFinished = true;
                        activity.runOnUiThread(() -> {
                            if (modelEnglishOnlyFinished && modelMultiLingualSmallFinished && modelMultiLingualBaseFinished) binding.buttonStart.setVisibility(View.VISIBLE);
                        });
                    }
                } catch (NoSuchAlgorithmException | IOException i) {
                    modelMultiLingualSmallFile.delete();
                    modelMultiLingualSmallFinished = false;
                    activity.runOnUiThread(() -> {
                        Toast.makeText(activity, activity.getResources().getString(R.string.error_download), Toast.LENGTH_SHORT).show();
                        binding.downloadButton.setEnabled(true);
                    });
                    Log.w("WhisperASR", activity.getResources().getString(R.string.error_download), i);
                }
            });
            thread.start();
        } else {
            downloadModelMultiLingualSmallSize = modelMultiLingualSmallSize;
            modelMultiLingualSmallFinished = true;
            activity.runOnUiThread(() -> {
                if (modelEnglishOnlyFinished && modelMultiLingualSmallFinished && modelMultiLingualBaseFinished) binding.buttonStart.setVisibility(View.VISIBLE);
            });
        }

        File modelEnglishOnlyFile = new File(activity.getExternalFilesDir(null) + "/" + modelEnglishOnly);
        if (!modelEnglishOnlyFile.exists()) {
            modelEnglishOnlyFinished = false;
            Log.d("WhisperASR", "English only model file does not exist");
            Thread thread = new Thread(() -> {
                try {
                    URL url = new URL(modelEnglishOnlyURL);
                    Log.d("WhisperASR", "Download English only model");

                    URLConnection ucon = url.openConnection();
                    ucon.setReadTimeout(5000);
                    ucon.setConnectTimeout(10000);

                    InputStream is = ucon.getInputStream();
                    BufferedInputStream inStream = new BufferedInputStream(is, 1024 * 5);

                    modelEnglishOnlyFile.createNewFile();

                    FileOutputStream outStream = new FileOutputStream(modelEnglishOnlyFile);
                    byte[] buff = new byte[5 * 1024];

                    int len;
                    while ((len = inStream.read(buff)) != -1) {
                        outStream.write(buff, 0, len);
                        if (modelEnglishOnlyFile.exists()) downloadModelEnglishOnlySize = modelEnglishOnlyFile.length();
                        activity.runOnUiThread(() -> {
                            binding.downloadSize.setText((downloadModelEnglishOnlySize + downloadModelMultiLingualSmallSize + downloadModelMultiLingualBaseSize)/1024/1024 + " MB");
                            binding.downloadProgress.setProgress((int) (((double)(downloadModelEnglishOnlySize + downloadModelMultiLingualSmallSize + downloadModelMultiLingualBaseSize) / (modelEnglishOnlySize + modelMultiLingualSmallSize +  modelMultiLingualBaseSize)) * 100));
                        });
                    }
                    outStream.flush();
                    outStream.close();
                    inStream.close();

                    String calcEnglishOnlyModelMD5="";
                    if (modelEnglishOnlyFile.exists()) {
                        calcEnglishOnlyModelMD5 = calculateMD5(String.valueOf(Paths.get(modelEnglishOnlyFile.getPath())));
                    } else {
                        throw new IOException();  //throw exception if there is no modelMultiLingualSmallFile at this point
                    }

                    if (!calcEnglishOnlyModelMD5.equals(modelEnglishOnlyMD5)){
                        modelEnglishOnlyFile.delete();
                        modelEnglishOnlyFinished = false;
                        activity.runOnUiThread(() -> {
                            Toast.makeText(activity, activity.getResources().getString(R.string.error_download), Toast.LENGTH_SHORT).show();
                            binding.downloadButton.setEnabled(true);
                        });
                    } else {
                        modelEnglishOnlyFinished = true;
                        activity.runOnUiThread(() -> {
                            if (modelEnglishOnlyFinished && modelMultiLingualSmallFinished && modelMultiLingualBaseFinished) binding.buttonStart.setVisibility(View.VISIBLE);
                        });
                    }
                } catch (NoSuchAlgorithmException | IOException i) {
                    modelEnglishOnlyFile.delete();
                    modelEnglishOnlyFinished = false;
                    activity.runOnUiThread(() -> {
                        Toast.makeText(activity, activity.getResources().getString(R.string.error_download), Toast.LENGTH_SHORT).show();
                        binding.downloadButton.setEnabled(true);
                    });
                    Log.w("WhisperASR", activity.getResources().getString(R.string.error_download), i);
                }
            });
            thread.start();
        } else {
            downloadModelEnglishOnlySize = modelEnglishOnlySize;
            modelEnglishOnlyFinished = true;
            activity.runOnUiThread(() -> {
                if (modelEnglishOnlyFinished && modelMultiLingualSmallFinished && modelMultiLingualBaseFinished) binding.buttonStart.setVisibility(View.VISIBLE);
            });
        }

    }

    public static String calculateMD5(String filePath) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream is = new BufferedInputStream(new FileInputStream(filePath))) {
            byte[] buffer = new byte[8192]; // 8KB buffer
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead);
            }
        }
        byte[] hash = md.digest();
        return new BigInteger(1, hash).toString(16);
    }

    // Copy assets to destination folder
    public static void copyAssetsToSdcard(Context context) {
        String[] extensions = {"bin"};
        File sdcardDataFolder = context.getExternalFilesDir(null);
        AssetManager assetManager = context.getAssets();

        try {
            // List all files in the assets folder once
            String[] assetFiles = assetManager.list("");
            if (assetFiles == null) return;

            for (String assetFileName : assetFiles) {
                // Check if file matches any of the provided extensions
                for (String extension : extensions) {
                    if (assetFileName.endsWith("." + extension)) {
                        File outFile = new File(sdcardDataFolder, assetFileName);

                        // Skip if file already exists
                        if (outFile.exists()) break;

                        // Copy the file from assets to the destination folder
                        try (InputStream inputStream = assetManager.open(assetFileName);
                             OutputStream outputStream = new FileOutputStream(outFile)) {

                            byte[] buffer = new byte[1024];
                            int bytesRead;
                            while ((bytesRead = inputStream.read(buffer)) != -1) {
                                outputStream.write(buffer, 0, bytesRead);
                            }
                        }
                        break; // No need to check further extensions
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}