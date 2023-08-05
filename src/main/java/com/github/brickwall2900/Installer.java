package com.github.brickwall2900;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.brickwall2900.IOUtilities.*;

public class Installer {
//    necessary?
//    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
    public static final Installer instance = new Installer();
    public static final int DOWNLOAD_ATTEMPTS = 10;

    public static void main(String[] args) {
        instance.run(args);
    }


    // bad programming practice - don't do this
    // ========================================

    private PrintStream out = System.out;
    private Scanner in = new Scanner(System.in);

    private boolean confirmAll, skipAssetDownload;
    private File clientJsonFile;
    private File outputDirectory;
    public void run(String[] args) {
        instance.init(args);
        instance.confirm();
        instance.readJson();
        instance.createDirectories();
        instance.copyFiles();
        instance.downloadClient();
        instance.downloadLibraries();
        if (!skipAssetDownload) {
            instance.downloadAssetJson();
            instance.readAssetJson();
            instance.downloadAllAssets();
        }
        instance.finish();
    }

    public void init(String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: Main [clientJsonFile] [outDirectory]");
        }

        clientJsonFile = new File(args[0]);
        outputDirectory = new File(args[1]);

        out.printf("clientJsonFile -> %s%n", clientJsonFile);
        out.printf("outputDirectory -> %s%n", outputDirectory);

        for (String arg : args) {
            if (arg.equalsIgnoreCase("-y") || arg.equalsIgnoreCase("--confirm-yes")) confirmAll = true;
            if (arg.equalsIgnoreCase("-sa") || arg.equalsIgnoreCase("--skip-assets")) skipAssetDownload = true;
        }

        if (confirmAll) {
            out.println("Confirming 'yes' to all questions!");
        }
        if (skipAssetDownload) {
            out.println("Skipping asset downloads! May result in missing resources!");
        }
    }

    private boolean checkYesOrNo() {
        return !confirmAll && in.nextLine().equalsIgnoreCase("yes");
    }

    public void confirm() {
        if (!confirmAll) {
            out.print("Are you sure you want to create a Minecraft instance " +
                    "(or do you even own the game?)" +
                    "? (yes/no) ");
            if (checkYesOrNo()) {
                out.println("Creating...");
            } else {
                System.exit(0);
            }
        }
    }


    private File assetFolder, libraryFolder, versionFolder;
    private File currentVersionFolder;

    public void createDirectories() {
        boolean alreadyExists = outputDirectory.exists();
        if (!confirmAll && alreadyExists) {
            out.print("Output directory is not empty! Continue? (yes/no) ");
            if (!checkYesOrNo()) {
                throw new IllegalArgumentException("Output directory is not empty!");
            }
        }

        boolean created = outputDirectory.mkdir();
        assetFolder = new File(outputDirectory, "assets");
        libraryFolder = new File(outputDirectory, "libraries");
        versionFolder = new File(outputDirectory, "versions");
        currentVersionFolder = new File(versionFolder, versionName);

        created &= assetFolder.mkdir();
        created &= libraryFolder.mkdir();
        created &= versionFolder.mkdir();
        created &= currentVersionFolder.mkdir();

        created |= outputDirectory.exists();
        created |= assetFolder.exists();
        created |= libraryFolder.exists();
        created |= versionFolder.exists();
        created |= currentVersionFolder.exists();

        out.printf("Creating: %s%n", outputDirectory);
        out.printf("Creating: %s%n", assetFolder);
        out.printf("Creating: %s%n", libraryFolder);
        out.printf("Creating: %s%n", versionFolder);
        out.printf("Creating: %s%n", currentVersionFolder);

        if (!created) {
            throw new RuntimeException("One or more folders failed to be created!");
        }
    }

    private File clientJsonDest;
    public void copyFiles() {
        out.println("Copying client.json");
        try {
            copySingleFile(clientJsonFile, clientJsonDest = new File(currentVersionFolder, versionName + ".json"));
        } catch (IOException e) {
            throw new RuntimeException("Error copying client.jar!", e);
        }
    }


    private JsonElement clientElement;
    private JsonObject clientObject;

    private JsonObject downloadsJson, clientDownloadJson;
    private JsonArray librariesJson;

    private String versionName;

    public void readJson() {
        out.println("Read initial members of client.json");
        try {
            clientElement = JsonParser.parseString(readFileToString(clientJsonFile));
            clientObject = clientElement.getAsJsonObject();
            downloadsJson = clientObject.getAsJsonObject("downloads");
            clientDownloadJson = downloadsJson.getAsJsonObject("client");

            versionName = clientObject.get("id").getAsString();
            out.println("Version: " + versionName);

            librariesJson = clientObject.getAsJsonArray("libraries");
        } catch (IOException e) {
            throw new RuntimeException("Error reading/parsing client.json!", e);
        }
    }

    private File clientJarDest;

    // FIRST TRY LETS FUCKING GO1!!!!!
    public void downloadClient() {
        out.println("Downloading client.jar");
        String urlPath = clientDownloadJson.get("url").getAsString();
        long size = clientDownloadJson.get("size").getAsLong();
        String sha1 = clientDownloadJson.get("sha1").getAsString();
        URL url;
        try {
             url = new URL(urlPath);
        } catch (MalformedURLException e) {
            throw new RuntimeException("URL is somehow malformed: " + urlPath, e);
        }

        clientJarDest = new File(currentVersionFolder, versionName + ".jar");
        if (clientJarDest.exists() && checkFileIntegrity(clientJarDest, size, sha1)) {
            out.println("client.jar is already downloaded and verified!");
            return;
        }
        for (int i = 0; i < DOWNLOAD_ATTEMPTS && !checkFileIntegrity(clientJarDest, size, sha1); i++) {
            try {
                downloadToFile(url, clientJarDest);
            } catch (IOException e) {
                throw new RuntimeException("Error in downloading client.jar!", e);
            }
        }
        out.println("client.jar downloaded and verified!");
        out.printf("Destination: %s%n", clientJarDest);
    }

    public void downloadLibraries() {
        out.println("Now downloading libraries");
        List<JsonElement> elements = librariesJson.asList();
        elements.stream()
                .parallel()
                .forEach(this::downloadJsonLibraryElement);
        out.println("Done downloading libraries!");
    }

    private void downloadJsonLibraryElement(JsonElement element) {
        JsonObject object = element.getAsJsonObject();
        if (checkLibraryRules(object.getAsJsonArray("rules"))) {
            JsonObject downloads = object.getAsJsonObject("downloads");
            JsonObject artifact = downloads.getAsJsonObject("artifact");
            String path = artifact.get("path").getAsString();
            String sha1 = artifact.get("sha1").getAsString();
            long size = artifact.get("size").getAsLong();
            String urlPath = artifact.get("url").getAsString();
            String name = object.get("name").getAsString();
            downloadLibrary(path, sha1, size, urlPath, name);
        }
    }

    private boolean checkLibraryRules(JsonArray rules) {
        if (rules != null) {
            Iterator<JsonElement> libraryElement = rules.iterator();
            while (libraryElement.hasNext()) {
                JsonElement element = libraryElement.next();
                JsonObject object = element.getAsJsonObject();
                String action = object.get("action").getAsString();
                JsonObject os = object.getAsJsonObject("os");
                String osName = os.get("name").getAsString();
                if (action.equalsIgnoreCase("allow")) {
                    switch (osName) {
                        case "osx" -> {
                            return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
                        }
                        case "linux" -> {
                            return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("nux");
                        }
                        case "windows" -> {
                            return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
                        }
                        default -> throw new IllegalStateException("Unexpected OS: " + osName);
                    }
                } else {
                    throw new IllegalStateException("Unexpected action: " + action);
                }
            }
        }
        // ..?
        return true;
    }

    private void downloadLibrary(String path, String sha1, long size, String urlPath, String name) {
        URL url;
        try {
            url = new URL(urlPath);
        } catch (MalformedURLException e) {
            throw new RuntimeException("URL is somehow malformed: " + urlPath, e);
        }

        File dest = new File(libraryFolder, path);
        Path folderDestPath = dest.toPath().getParent();
        File folderDest = folderDestPath.toFile();
        folderDest.mkdirs();
        if (dest.exists() && checkFileIntegrity(dest, size, sha1)) {
            out.printf("%s is already downloaded and verified!%n", name);
            return;
        }
        for (int i = 0; i < DOWNLOAD_ATTEMPTS && !checkFileIntegrity(dest, size, sha1); i++) {
            try {
                downloadToFile(url, dest);
            } catch (IOException e) {
                throw new RuntimeException("Error in downloading " + name, e);
            }
        }
        out.printf("%s downloaded and verified!%n", name);
    }

    private File assetJsonDest;

    public void downloadAssetJson() {
        out.println("Downloading assets.json");
        JsonObject assetIndex = clientObject.getAsJsonObject("assetIndex");
        String sha1 = assetIndex.get("sha1").getAsString();
        long size = assetIndex.get("size").getAsLong();
        int id = assetIndex.get("id").getAsInt();
        String urlPath = assetIndex.get("url").getAsString();
        URL url;
        try {
            url = new URL(urlPath);
        } catch (MalformedURLException e) {
            throw new RuntimeException("asset.json URL is malformed!", e);
        }

        assetJsonDest = new File(assetFolder, "indexes" + File.separatorChar + id + ".json");
        Path folderDestPath = assetJsonDest.toPath().getParent();
        File folderDest = folderDestPath.toFile();
        folderDest.mkdirs();
        if (assetJsonDest.exists() && checkFileIntegrity(assetJsonDest, size, sha1)) {
            out.println("asset.json is already downloaded and verified!");
            return;
        }
        for (int i = 0; i < DOWNLOAD_ATTEMPTS && !checkFileIntegrity(assetJsonDest, size, sha1); i++) {
            try {
                downloadToFile(url, assetJsonDest);
            } catch (IOException e) {
                throw new RuntimeException("Error in downloading asset.json!", e);
            }
        }
        out.println("asset.json downloaded and verified!");
    }

    private File assetObjectDir;

    // you see, we're not done with the JSON fuckery yet
    private JsonElement assetJsonElement;
    private JsonObject assetJsonObjects;
    private Map<String, JsonElement> assetObjectMap;

    public void readAssetJson() {
        assetObjectDir = new File(assetFolder, "objects");
        try {
            assetJsonElement = JsonParser.parseString(readFileToString(assetJsonDest));
            assetJsonObjects = assetJsonElement.getAsJsonObject().getAsJsonObject("objects");
            assetObjectMap = assetJsonObjects.asMap();
        } catch (IOException e) {
            throw new RuntimeException("Error reading/parsing asset.json!", e);
        }
    }

    public void downloadAllAssets() {
        out.printf("Downloading %d assets!%n", assetObjectMap.size());
        AtomicLong size = new AtomicLong();
        Thread downloadThread = startAssetDownloadThread(size);
        while (downloadThread.isAlive()) {
            out.printf("Progress: %d/%d%n", assetCount.get(), assetObjectMap.size());
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {}
        }
        out.printf("Downloaded %d assets! (%d bytes)%n", assetCount.get(), size.get());
    }

    private Thread startAssetDownloadThread(AtomicLong size) {
        Thread downloadThread = new Thread(() -> {
            JsonObject assetIndex = clientObject.getAsJsonObject("assetIndex");
            long totalAssetSize = assetIndex.get("totalSize").getAsLong();
            size.set(assetObjectMap.entrySet()
                    .stream()
                    .parallel()
                    .map(e -> e.getValue().getAsJsonObject())
                    .mapToLong(this::downloadAsset)
                    .sum());
            if (totalAssetSize != size.get()) {
                throw new IllegalStateException("Asset totalSize != size");
            }
        });
        downloadThread.setName("AssetDownloader");
        downloadThread.start();
        return downloadThread;
    }

    public static final String ASSET_URL = "https://resources.download.minecraft.net";

    private final AtomicInteger assetCount = new AtomicInteger();
    private long downloadAsset(JsonObject object) {
        String hash = object.get("hash").getAsString();
        long size = object.get("size").getAsLong();

        String urlDir = hash.substring(0, 2);
        String finalUrl = ASSET_URL + '/' + urlDir + '/' + hash;

        URL url;
        try {
            url = new URL(finalUrl);
        } catch (MalformedURLException e) {
            throw new RuntimeException("Asset (" + finalUrl + ") URL is malformed!", e);
        }

        File downloadedAsset = new File(assetObjectDir, urlDir + File.separatorChar + hash);
        Path folderDestPath = downloadedAsset.toPath().getParent();
        File folderDest = folderDestPath.toFile();
        folderDest.mkdirs();
        if (downloadedAsset.exists() && checkFileIntegrity(downloadedAsset, size, hash)) {
            assetCount.getAndIncrement();
            return size;
        }
        for (int i = 0; i < DOWNLOAD_ATTEMPTS && !checkFileIntegrity(downloadedAsset, size, hash); i++) {
            try {
                downloadToFile(url, downloadedAsset);
            } catch (IOException e) {
                throw new RuntimeException("Error in downloading asset " + hash + "!", e);
            }
        }
        assetCount.getAndIncrement();
        return size;
    }

    public void finish() {
        out.println("Minecraft " + versionName + " is done installing!");
    }
}