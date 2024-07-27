package com.github.brickwall2900;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.github.brickwall2900.IOUtilities.*;

@Deprecated
public class MrpackDownloader implements LauncherProcess {
    public static final MrpackDownloader instance = new MrpackDownloader();

    public static void main(String[] args) {
        instance.run(args);
    }

    private PrintStream out = System.out;
    private Scanner in = new Scanner(System.in);

    public void run(String[] args) {
        init(args);
        readJson();
        confirmInfo();
        downloadFiles();
        copyOverrides();
        out.println("Done!");
    }

    private File inFile, gameDirectory;
    private boolean confirmAll;

    public void init(String[] args) {
        String lastParsed = null;
        try {
            for (String arg : args) {
                lastParsed = arg;
                if (arg.equalsIgnoreCase("-y") || arg.equalsIgnoreCase("--confirm-yes")) confirmAll = true;
                if (arg.startsWith("--input=") || arg.startsWith("-in=")) {
                    inFile = new File(arg.split("=")[1]);
                }
                if (arg.startsWith("--game-directory=") || arg.startsWith("-game-dir=")) {
                    gameDirectory = new File(arg.split("=")[1]);
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Invalid argument at \"" + lastParsed + '\"');
        }

        if (inFile == null || gameDirectory == null) {
            System.err.println("Usage: MrpackDownloader [--input=<client json file>] [--game-directory=<output game directory>] --confirm-yes?");
            System.err.println(" ..or: MrpackDownloader [-in=<client json file>] [-game-dir=<output game directory>] -y?");
            System.err.println("'?' means this is optional.");
            throw new NullPointerException("One or more arguments are missing!");
        }

        out.printf("inputFile -> %s%n", inFile);
        out.printf("gameDirectory -> %s%n", gameDirectory);

        if (confirmAll) {
            out.println("Confirming 'yes' to all questions!");
        }
    }

    private ZipFile zipFile;
    private JsonObject indexJson;

    public static final String JSON_ENTRY_NAME = "modrinth.index.json";

    public void readJson() {
        out.println("Reading...");
        try {
            zipFile = new ZipFile(inFile);
            ZipEntry entry = zipFile.getEntry(JSON_ENTRY_NAME);
            try (InputStream in = zipFile.getInputStream(entry);
                 InputStreamReader reader = new InputStreamReader(in)) {
                indexJson = JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (IOException e) {
            throw new RuntimeException("Error in reading ZIP or JSON file!", e);
        }
    }

    private boolean checkYesOrNo() {
        String next = in.nextLine();
        return !confirmAll && (next.equalsIgnoreCase("yes") || next.equalsIgnoreCase("y"));
    }

    public void confirmInfo() {
        int formatVersion = indexJson.get("formatVersion").getAsInt();
        if (formatVersion != 1) {
            throw new AssertionError("formatVersion != 1");
        }
        String game = indexJson.get("game").getAsString();
        String name = indexJson.get("name").getAsString();
        String summary = indexJson.get("summary").getAsString();
        String version = indexJson.get("versionId").getAsString();
        out.printf("Name: %s%n", name);
        out.printf("Version: %s%n", version);
        out.printf("Summary: %s%n", summary);
        out.printf("Game: %s%n", game);

        out.println("This will install required files without checking any dependencies.");
        out.printf("Do you want to install %s? (yes/no)", name);
        if (!checkYesOrNo()) {
            System.exit(0);
        }
    }

    public void downloadFiles() {
        out.println("Downloading files...");
        JsonArray files = indexJson.getAsJsonArray("files");
        Thread downloadThread = startModDownloadThread(files);
        while (downloadThread.isAlive()) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {}
            out.printf("Progress: %d/%d downloaded%n", downloadCount.get(), files.size());
        }
    }

    private Thread startModDownloadThread(JsonArray files) {
        Thread downloadThread = new Thread(() -> {
            files.asList().stream()
                    .parallel()
                    .map(JsonElement::getAsJsonObject)
                    .forEach(this::downloadFile);
        });
        downloadThread.setName("ModDownloader");
        downloadThread.start();
        return downloadThread;
    }

    private final AtomicInteger downloadCount = new AtomicInteger();

    private void downloadFile(JsonObject fileEntry) {
        String path = fileEntry.get("path").getAsString();
        JsonObject hashes = fileEntry.getAsJsonObject("hashes");
        String sha512 = hashes.get("sha512").getAsString();
        JsonArray downloads = fileEntry.getAsJsonArray("downloads");
        long fileSize = fileEntry.get("fileSize").getAsLong();

        for (JsonElement urlElement : downloads) {
            String urlPath = urlElement.getAsString();
            URL url;
            try {
                url = new URL(urlPath);
            } catch (MalformedURLException e) {
                throw new RuntimeException("(" + urlPath + ") URL is malformed!", e);
            }

            File downloadedMod = new File(gameDirectory, path);
            Path folderDestPath = downloadedMod.toPath().getParent();
            File folderDest = folderDestPath.toFile();
            folderDest.mkdirs();
            if (downloadedMod.exists() && checkFileIntegrity(downloadedMod, fileSize, sha512, SHA512_ALGORITHM)) {
                out.printf("%s already downloaded and verified!%n", downloadedMod.getName());
                downloadCount.getAndIncrement();
                break;
            }
            for (int i = 0; i < DOWNLOAD_ATTEMPTS && !checkFileIntegrity(downloadedMod, fileSize, sha512, SHA512_ALGORITHM); i++) {
                try {
                    downloadToFile(url, downloadedMod);
                } catch (IOException e) {
                    throw new RuntimeException("Error in downloading mod " + path + "!", e);
                }
            }
            if (checkFileIntegrity(downloadedMod, fileSize, sha512, SHA512_ALGORITHM)) {
                out.printf("%s downloaded and verified!%n", downloadedMod.getName());
                downloadCount.getAndIncrement();
                break;
            }
        }
    }

    public void copyOverrides() {
        out.println("Copying overrides...");
        zipFile.stream()
                .parallel()
                .filter(e -> e.getName().startsWith("overrides/"))
                .forEach(this::copyOverrideToGame);
    }

    private void copyOverrideToGame(ZipEntry overrideEntry) {
        String newPath = overrideEntry.getName().substring(10);
        File dropPath = new File(gameDirectory, newPath);
        if (!overrideEntry.isDirectory()) {
            Path folderDestPath = dropPath.toPath().getParent();
            File folderDest = folderDestPath.toFile();
            folderDest.mkdirs();

            try (BufferedInputStream src = new BufferedInputStream(zipFile.getInputStream(overrideEntry));
                 BufferedOutputStream dest = new BufferedOutputStream(new FileOutputStream(dropPath))) {
                src.transferTo(dest);
                out.printf("Copied %s!%n", dropPath);
            } catch (IOException e) {
                throw new RuntimeException("Error copying override: " + dropPath, e);
            }
        } else {
            dropPath.mkdirs();
        }
    }
}
