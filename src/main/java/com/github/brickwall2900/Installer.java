package com.github.brickwall2900;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
//import com.squareup.tools.maven.resolution.*;
//import kotlin.Pair;
//import okhttp3.OkHttpClient;
//import okio.Okio;
//import org.apache.maven.model.Repository;
//import org.apache.maven.model.RepositoryPolicy;
//import org.apache.maven.model.building.DefaultModelBuilderFactory;
//import org.apache.maven.model.resolution.ModelResolver;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

import static com.github.brickwall2900.IOUtilities.*;

public class Installer implements LauncherProcess {
//    necessary?
//    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
    public static final Installer instance = new Installer();

    public static void main(String[] args) {
        instance.run(args);
    }


    // bad programming practice!! - don't do this
    // ==========================================

    private PrintStream out = System.out;
    private Scanner in = new Scanner(System.in);

    private boolean confirmAll, skipAssetDownload;
    private boolean replaceLibraries, skipFailedLibraries;
    private File clientJsonFile;
    private File outputDirectory;
    public void run(String[] args) {
        init(args);
        confirm();
        proc();
        finish();
    }

    public void proc() {
        readJson();
        createDirectories();
        copyFiles();
        downloadClient();
        downloadLibraries();
        if (!skipAssetDownload) {
            downloadAssetJson();
            readAssetJson();
            downloadAllAssets();
        }
    }

    public void init(String[] args) {
        String lastParsed = null;
        try {
            for (String arg : args) {
                lastParsed = arg;
                if (arg.equalsIgnoreCase("-y") || arg.equalsIgnoreCase("--confirm-yes")) confirmAll = true;
                if (arg.equalsIgnoreCase("-sa") || arg.equalsIgnoreCase("--skip-assets")) skipAssetDownload = true;
                if (arg.equalsIgnoreCase("-rl") || arg.equalsIgnoreCase("--replace-library")) replaceLibraries = true;
                if (arg.equalsIgnoreCase("-sfl") || arg.equalsIgnoreCase("--skip-failed-libraries")) skipFailedLibraries = true;
                if (arg.startsWith("--client-json=") || arg.startsWith("-client=")) {
                    clientJsonFile = new File(arg.split("=")[1]);
                }
                if (arg.startsWith("--out-directory=") || arg.startsWith("-dir=")) {
                    outputDirectory = new File(arg.split("=")[1]);
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Invalid argument at \"" + lastParsed + '\"');
        }

        if (clientJsonFile == null || outputDirectory == null) {
            System.err.println("Usage: Installer [--client-json=<client json file>] [--out-directory=<output game directory>] --confirm-yes? --skip-assets? --replace-library? --skip-failed-libraries?");
            System.err.println(" ..or: Installer [-client=<client json file>] [-dir=<output game directory>] -y? -sa? -rl? -sfl?");
            System.err.println("'?' means this is optional.");
            throw new NullPointerException("One or more arguments are missing!");
        }

        out.printf("clientJsonFile -> %s%n", clientJsonFile);
        out.printf("outputDirectory -> %s%n", outputDirectory);

        if (confirmAll) {
            out.println("Confirming 'yes' to all questions!");
        }
        if (skipAssetDownload) {
            out.println("Skipping asset downloads! May result in missing resources!");
        }
        if (replaceLibraries) {
            out.println("Re-downloading and replacing game libraries!");
        }
        if (skipFailedLibraries) {
            out.println("Skipping failed library downloads, this may result in the game failing to launch.");
        }
    }

    private boolean checkYesOrNo() {
        String next = in.nextLine();
        return !confirmAll && (next.equalsIgnoreCase("yes") || next.equalsIgnoreCase("y"));
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
    private File currentVersionFolder, nativesFolder;

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
        nativesFolder = new File(currentVersionFolder, "natives");

        created &= assetFolder.mkdir();
        created &= libraryFolder.mkdir();
        created &= versionFolder.mkdir();
        created &= currentVersionFolder.mkdir();
        created &= nativesFolder.mkdir();

        created |= outputDirectory.exists();
        created |= assetFolder.exists();
        created |= libraryFolder.exists();
        created |= versionFolder.exists();
        created |= currentVersionFolder.exists();
        created |= nativesFolder.exists();

        out.printf("Creating: %s%n", outputDirectory);
        out.printf("Creating: %s%n", assetFolder);
        out.printf("Creating: %s%n", libraryFolder);
        out.printf("Creating: %s%n", versionFolder);
        out.printf("Creating: %s%n", currentVersionFolder);
        out.printf("Creating: %s%n", nativesFolder);

        if (!created) {
            throw new RuntimeException("One or more folders failed to be created!");
        }
    }

    private File clientJsonDest;
    public void copyFiles() {
        clientJsonDest = new File(currentVersionFolder, versionName + ".json");
        try {
            if (!clientJsonDest.exists()) {
                out.println("Copying " + clientJsonFile);
                copySingleFile(clientJsonFile, clientJsonDest);
            }
        } catch (IOException e) {
            throw new RuntimeException("Error copying client.json!", e);
        }
    }


    private JsonElement clientElement;
    private JsonObject clientObject;

    private JsonObject downloadsJson, clientDownloadJson;
    private JsonArray librariesJson;

    private String versionName;

    public void readJson() {
        File clientJsonFile = new File(this.clientJsonFile.getAbsolutePath());
        out.println("Read initial members of " + clientJsonFile);
        try {
            JsonElement clientElement = JsonParser.parseString(readFileToString(clientJsonFile));
            clientObject = clientElement.getAsJsonObject();

            versionName = clientObject.get("id").getAsString();
            out.println("Version: " + versionName);

            JsonElement inheritsFrom = clientObject.get("inheritsFrom");
            boolean hasParent = inheritsFrom != null;
            if (hasParent) {
                String version = inheritsFrom.getAsString();
                out.println("Inherits from: " + version);
                this.clientJsonFile = new File(outputDirectory, "versions" + File.separatorChar + version + File.separatorChar + version + ".json");
                proc();
                // restore state
                this.clientJsonFile = clientJsonFile;
                clientObject = clientElement.getAsJsonObject();
                versionName = clientObject.get("id").getAsString();
                out.println("Now installing " + versionName);
            }
            downloadsJson = clientObject.getAsJsonObject("downloads");
            if (downloadsJson != null) {
                clientDownloadJson = downloadsJson.getAsJsonObject("client");
            }

            librariesJson = clientObject.getAsJsonArray("libraries");
        } catch (IOException e) {
            throw new RuntimeException("Error reading/parsing client.json!", e);
        }
    }

    private File clientJarDest;

    // FIRST TRY LETS FUCKING GO1!!!!!
    public void downloadClient() {
        if (clientDownloadJson == null) return;
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
        if (clientJarDest.exists() && checkFileIntegrity(clientJarDest, size, sha1, SHA1_ALGORITHM)) {
            out.println("client.jar is already downloaded and verified!");
            return;
        }
        for (int i = 0; i < DOWNLOAD_ATTEMPTS && !checkFileIntegrity(clientJarDest, size, sha1, SHA1_ALGORITHM); i++) {
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
        JsonObject object = null;
        try {
            object = element.getAsJsonObject();
            if (checkLibraryRules(object.getAsJsonArray("rules"))) {
                JsonObject downloads = object.getAsJsonObject("downloads");
                String path;
                String sha1;
                long size;
                String urlPath = null;
                String name = object.get("name").getAsString();
                if (downloads != null) {
                    JsonObject artifact = downloads.getAsJsonObject("artifact");
                    if (artifact != null) {
                        path = artifact.get("path").getAsString();
                        sha1 = artifact.get("sha1").getAsString();
                        size = artifact.get("size").getAsLong();
                        urlPath = artifact.get("url").getAsString();
                        downloadLibrary(path, sha1, size, urlPath, name);
                    }
                } else {
                    // what??? possibly maven?
                    JsonElement url = object.get("url");
                    if (url != null) urlPath = url.getAsString();
                    JsonArray checksums = object.getAsJsonArray("checksums");
                    downloadLibraryMaven(name, urlPath, checksums != null ? checksums.asList().stream().map(JsonElement::getAsString).toList().toArray(new String[0]) : null);
                }

                // downloading native libraries
                // I'm getting tortured.
                JsonObject natives = object.getAsJsonObject("natives");
                if (downloads != null && natives != null) {
                    String classifier = natives.get(OperatingSystem.detectOperatingSystem().name).getAsString();
                    classifier = classifier.replace("${arch}", System.getProperty("os.arch").replaceAll("[a-zA-Z]", ""));
                    JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                    JsonObject nativeArtifact = classifiers.getAsJsonObject(classifier);
                    path = nativeArtifact.get("path").getAsString();
                    sha1 = nativeArtifact.get("sha1").getAsString();
                    size = nativeArtifact.get("size").getAsLong();
                    urlPath = nativeArtifact.get("url").getAsString();
                    File nativeLib = downloadLibrary(path, sha1, size, urlPath, name);
                    if (nativeLib != null) {
                        extractToNatives(nativeLib, object);
                    }
                }
            }
        } catch (Exception ex) {
            if (skipFailedLibraries) {
                out.printf("Skipped a library since it failed to download: %s%n", ex);
            } else {
                throw new RuntimeException("Failed to download library!", ex);
            }
        }
    }

    private boolean checkLibraryRules(JsonArray rules) {
        if (rules != null) {
            for (JsonElement element : rules) {
                JsonObject object = element.getAsJsonObject();
                String action = object.get("action").getAsString();
                JsonObject os = object.getAsJsonObject("os");
                if (os != null) {
                    JsonElement osName = os.get("name");
                    JsonElement osArch = os.get("arch");
                    boolean osNameAllowed = osName == null;
                    boolean osArchAllowed = osArch == null;
                    if (osName != null) {
                        osNameAllowed = osName.getAsString().equals(OperatingSystem.detectOperatingSystem().name);
                    }
                    if (osArch != null) {
                        osArchAllowed = System.getProperty("os.arch").equalsIgnoreCase(osArch.getAsString());
                    }
                    if (action.equalsIgnoreCase("allow")) {
                        return osNameAllowed && osArchAllowed;
                    } else if (action.equalsIgnoreCase("disallow")) {
                        return !(osNameAllowed && osArchAllowed);
                    } else {
                        throw new IllegalStateException("Unexpected action: " + action);
                    }
                }
            }
        }
        // ..?
        return true;
    }

    private void extractToNatives(File nativeLib, JsonObject downloadObject) {
        JsonObject extract = downloadObject.getAsJsonObject("extract");
        JsonArray exclude = extract.getAsJsonArray("exclude");
        List<String> excludedItems = exclude.asList().stream().map(JsonElement::getAsString).toList();
        try {
            ZipFile file = new ZipFile(nativeLib);
            file.stream()
                .filter(e -> {
                    for (String excluded : excludedItems) {
                        if (e.getName().contains(excluded)) return false;
                    }
                    return !e.isDirectory();
                }).forEach(e -> {
                    try (InputStream inputStream = file.getInputStream(e)) {
                        File dest = new File(nativesFolder, e.getName());
                        copyStreamToFile(inputStream, dest);
                    } catch (IOException ex) {
                        throw new RuntimeException("Error while extracting native file!", ex);
                    }
                });
        } catch (IOException e) { }
    }

    private File downloadLibrary(String path, String sha1, long size, String urlPath, String name) {
        URL url = null;
        try {
            url = new URL(urlPath);
        } catch (MalformedURLException e) {
            if (skipFailedLibraries) {
                out.printf("Skipping %s since URL is somehow malformed: %s%n", name, urlPath);
            } else {
                throw new RuntimeException("URL is somehow malformed: " + urlPath, e);
            }
        }
        if (url == null) return null;

        File dest = new File(libraryFolder, path);
        Path folderDestPath = dest.toPath().getParent();
        File folderDest = folderDestPath.toFile();
        folderDest.mkdirs();
        if (replaceLibraries && dest.exists() && checkFileIntegrity(dest, size, sha1, SHA1_ALGORITHM)) {
            out.printf("%s is already downloaded and verified (%s)!%n", name, path);
            return dest;
        }
        for (int i = 0; i < DOWNLOAD_ATTEMPTS && !checkFileIntegrity(dest, size, sha1, SHA1_ALGORITHM); i++) {
            try {
                downloadToFile(url, dest);
            } catch (IOException e) {
//                throw new RuntimeException("Error in downloading " + name, e);
                if (skipFailedLibraries) {
                    out.printf("Skipped %s since library failed to download: %s%n", name, e);
                } else {
                    throw new RuntimeException("Error in downloading " + name, e);
                }
            }
        }
        out.printf("%s downloaded and verified! (%s)%n", name, path);
        return dest;
    }

    private record Repository(String url, String id) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Repository that = (Repository) o;
            return Objects.equals(url, that.url);
        }

        @Override
        public int hashCode() {
            return Objects.hash(url);
        }
    }

    private void downloadLibraryMaven(String name, String urlPath, String[] checksums) {
        Set<Repository> repositorySet = new HashSet<>();
        repositorySet.add(new Repository("https://repo.maven.apache.org/maven2", "maven-central"));
        Path local = FileSystems.getDefault().getPath(System.getProperty("user.home") + "/.m2/repository");
        // ahhh fuck it
        repositorySet.add(new Repository("https://repo1.maven.org/maven2", "maven-central2"));
        repositorySet.add(new Repository("https://maven.minecraftforge.net", "forge"));
        repositorySet.add(new Repository("https://maven.fabricmc.net", "fabric"));
        repositorySet.add(new Repository("https://repo.spongepowered.org/repository/maven-public", "uhh"));
        repositorySet.add(new Repository("https://plugins.gradle.org/m2", "help"));
        repositorySet.add(new Repository("https://jitpack.io", "pls"));
        repositorySet.add(new Repository("https://oss.sonatype.org/content/repositories/snapshots", "sonatype-nexus-snapshots"));
        repositorySet.add(new Repository("http://repository.ow2.org/nexus/content/repositories/snapshots", "ow2-snapshot"));
//        repositorySet.add(newRepo(local.toUri().toString(), "local"));

        if (urlPath != null) {
            repositorySet.add(new Repository(urlPath, "add"));
        }
        List<Repository> repositoryList = repositorySet.stream().toList();
        // arghhh kotlin lol
//        ArtifactFetcher fetcher = new HttpArtifactFetcher(local, new OkHttpClient());
//        ModelResolver modelResolver = new SimpleHttpResolver(local, fetcher, repositoryList, false);
//        ArtifactResolver artifactResolver = new ArtifactResolver(false, local, fetcher, new DefaultModelBuilderFactory(), repositoryList, modelResolver);
        try {
//            Artifact artifact = artifactResolver.artifactFor(name);
//            ResolvedArtifact pom = artifactResolver.resolveArtifact(artifact);
            File artifactFile;
            String path = mavenArtifactToPath(name, File.separatorChar), file = mavenArtifactToFile(name), urlMavenPath = mavenArtifactToPath(name, '/');
//            if (pom != null) {
//                FetchStatus fetchStatus = artifactResolver.downloadArtifact(pom);
//                if (!(fetchStatus.getClass().getName().contains("SUCCESSFUL"))) { // fucking hack it's 2:09 am ahhhhhhhhhhhhhhhhhh
//                    out.println("Artifact " + name + " from repositry download failed.");
//                     Caused by: java.lang.RuntimeException: Artifact org.ow2.asm:asm-all:5.0.3 download failed: com.squareup.tools.maven.resolution.FetchStatus$RepositoryFetchStatus$SUCCESSFUL$FOUND_IN_CACHE@708dd22a
//                     I wanna commit suicide
//                    out.println("Downloading the other way...");
//                    artifactFile = pomNonExistant(name, repositoryList, urlMavenPath, file, checksums);
//                } else {
//                    artifactFile = pom.getMain().getLocalFile().toFile();
//                }
//            } else {
                artifactFile = pomNonExistant(name, repositoryList, urlMavenPath, file, checksums);
//            }
            File dest = new File(libraryFolder, path + File.separatorChar + file + ".jar");
//            out.printf("[dbg] Dest: %s%n", dest);
            Path folderDestPath = dest.toPath().getParent();
            File folderDest = folderDestPath.toFile();
            folderDest.mkdirs();
            if (dest.exists()) {
                if (replaceLibraries && checksums != null && checkFileIntegrity(dest, checksums, SHA1_ALGORITHM)) {
                    out.printf("%s is already downloaded and verified (%s)!%n", name, path);
                    return;
                } else if (replaceLibraries) {
                    out.printf("%s has been downloaded and verified (%s)!%n", name, path);
                } else {
                    out.printf("Cannot determine file integrity on %s since checksum doesn't exist (%s). Replacing file anyway.%n", name, path);
                }
            }

            if (!artifactFile.exists()) _breakpoint();
            copySingleFile(artifactFile, dest);
            out.printf("%s downloaded and verified! (%s)%n", name, path);
        } catch (IOException e) {
            if (skipFailedLibraries) {
                out.printf("Skipped %s since library failed to download: %s%n", name, e);
            } else {
                throw new RuntimeException("Error in downloading " + name, e);
            }
        }
    }

    private File pomNonExistant(String name, Iterable<Repository> repositoryList, String urlMavenPath, String file, String[] checksums) throws IOException {
//        out.println("POM for " + name + " is non-existant!");
        File tmp = File.createTempFile(name.replace(":", "_"), null);
        tmp.createNewFile();
        Map<Repository, Exception> exceptionMap = new HashMap<>();
        for (Repository repository : repositoryList) {
            String base = repository.url();
            String finalPath = base + "/" + urlMavenPath + "/" + file + ".jar";
            URL url = new URL(finalPath);
            for (int i = 0; i < MAVEN_DOWNLOAD_ATTEMPTS; i++) {
                try {
                    downloadToFile(url, tmp);
                    if (checksums != null && checkFileIntegrity(tmp, checksums, SHA1_ALGORITHM)) {
                        out.printf("Successfully downloaded from %s! (%s)%n", name, url);
                    } else {
                        out.printf("Did we download the correct file from %s? Cannot determine file integrity. (%s)%n", name, url);
                    }
                    return tmp;
                } catch (IOException e) {
                    exceptionMap.put(repository, e);
//                    out.printf("*** NOT A FATAL ERROR... yet. *** Error in downloading artifact %s! (%s)%n", name, e);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Unable to download library file ").append(name).append(" from any repositories!\n");
        for (Map.Entry<Repository, Exception> entry : exceptionMap.entrySet()) {
            sb.append(entry.getKey().url).append(": ").append(entry.getValue()).append('\n');
        }
        throw new IOException(sb.toString());
    }

    private String mavenArtifactToPath(String artifact, char delimiter) {
        String[] colonSplit = artifact.split(":");
        String[] packageSplit = colonSplit[0].split("\\.");
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < packageSplit.length; i++) {
            stringBuilder.append(packageSplit[i]).append(delimiter);
        }
        for (int i = 1; i < colonSplit.length; i++) {
            stringBuilder.append(colonSplit[i]);
            if (i < colonSplit.length - 1) stringBuilder.append(delimiter);
        }
        return stringBuilder.toString();
    }

    private String mavenArtifactToFile(String artifact) {
        String[] colonSplit = artifact.split(":");
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(colonSplit[1]).append('-').append(colonSplit[2]);
        return stringBuilder.toString();
    }

    private File assetJsonDest;

    public void downloadAssetJson() {
        out.println("Downloading assets.json");
        JsonObject assetIndex = clientObject.getAsJsonObject("assetIndex");
        if (assetIndex != null) {
            String sha1 = assetIndex.get("sha1").getAsString();
            long size = assetIndex.get("size").getAsLong();
            String id = assetIndex.get("id").getAsString();
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
            if (assetJsonDest.exists() && checkFileIntegrity(assetJsonDest, size, sha1, SHA1_ALGORITHM)) {
                out.println("asset.json is already downloaded and verified!");
                return;
            }
            for (int i = 0; i < DOWNLOAD_ATTEMPTS && !checkFileIntegrity(assetJsonDest, size, sha1, SHA1_ALGORITHM); i++) {
                try {
                    downloadToFile(url, assetJsonDest);
                } catch (IOException e) {
                    throw new RuntimeException("Error in downloading asset.json!", e);
                }
            }
            out.println("asset.json downloaded and verified!");
        } else {
            out.println("Assets are non-existant!");
            assetJsonDest = null; // assetJsonDest set to null for checking
        }
    }

    private File assetObjectDir;

    // you see, we're not done with the JSON fuckery yet
    private JsonElement assetJsonElement;
    private JsonObject assetJsonObjects;
    private Map<String, JsonElement> assetObjectMap;

    public void readAssetJson() {
        if (assetJsonDest == null) return;
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
        if (assetJsonDest == null) return;
        out.printf("Downloading %d assets!%n", assetObjectMap.size());
        AtomicLong size = new AtomicLong();
        Thread downloadThread = startAssetDownloadThread(size);
        while (downloadThread.isAlive()) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {}
            out.printf("Progress: %d/%d%n", assetCount.get(), assetObjectMap.size());
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
        if (downloadedAsset.exists() && checkFileIntegrity(downloadedAsset, size, hash, SHA1_ALGORITHM)) {
            assetCount.getAndIncrement();
            return size;
        }
        for (int i = 0; i < DOWNLOAD_ATTEMPTS && !checkFileIntegrity(downloadedAsset, size, hash, SHA1_ALGORITHM); i++) {
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
        System.exit(0);
    }
}