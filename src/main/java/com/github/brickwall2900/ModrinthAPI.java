package com.github.brickwall2900;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.StringReader;
import java.net.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ModrinthAPI {
    private static final String STAGING_API_URL = "https://staging-api.modrinth.com/v2/";
    private static final String MAIN_API_URL = "https://api.modrinth.com/v2/";
    private static final String USER_AGENT = "Spaghetti/brickwall2900/1.3";

    public static final Facet DEFAULT_FACET;
    public static final int CALL_RESET_TIME = 60_000;

    static {
        DEFAULT_FACET = new Facet();
        DEFAULT_FACET.start()
                .category("forge").or()
                .category("fabric").or()
                .category("quilt").or()
                .category("liteloader").or()
                .category("modloader").or()
                .category("rift").or()
                .category("neoforge").end()
                .start().projectType(Project.Type.MOD).end();
    }

    private final String accessToken;
    private final String apiUrl;

    private int calls, maxCalls = 200;
    private long lastCallTime;

    public ModrinthAPI(String accessToken) {
        this.accessToken = accessToken;
        apiUrl = MAIN_API_URL;
    }

    public int getMaxCalls() {
        return maxCalls;
    }

    public void setMaxCalls(int maxCalls) {
        this.maxCalls = maxCalls;
    }

    public String get(APICall call) throws IOException {
        if ((System.currentTimeMillis() - lastCallTime) >= CALL_RESET_TIME) {
            calls = 0;
        } else {
            calls++;
        }
        lastCallTime = System.currentTimeMillis();
        if (calls > maxCalls) {
            throw new SocketTimeoutException("Call limit reached!");
        }

        URL url = call.toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestMethod("GET");

        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            // Print all headers
//            Map<String, List<String>> headers = connection.getHeaderFields();
//            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
//                String key = entry.getKey();
//                List<String> values = entry.getValue();
//                System.out.println(key + ": " + String.join(", ", values));
//            }
            // TODO: implement rate limit: x-ratelimit-limit; x-ratelimit-remaining; x-ratelimit-reset;

            return IOUtilities.copyStreamToString(connection.getInputStream());
        } else {
            throw new ConnectException("Failed! HTTP error code: " + responseCode);
        }
    }

    // DEFAULT
    // https://api.modrinth.com/v2/search?limit=20&index=relevance&facets=[["categories:'forge'","categories:'fabric'","categories:'quilt'","categories:'liteloader'","categories:'modloader'","categories:'rift'","categories:'neoforge'"],["project_type:mod"]]
    public List<SearchResult> search(String query, int page, int limit, SearchIndex index, Facet facet) throws IOException {
        int offset = limit * page;

        // firstly, get json result
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", query);
        if (index != null) {
            parameters.put("index", index.name().toLowerCase());
        }
        parameters.put("offset", offset);
        parameters.put("limit", Math.min(limit, 100));
        parameters.put("facet", facet);
        APICall call = new APICall(apiUrl, "search", parameters);
        String jsonResult = get(call);

        // then, parse the JSON file :3
        List<SearchResult> results = new ArrayList<>();

        try (JsonReader reader = new JsonReader(new StringReader(jsonResult))) {
            JsonElement root = JsonParser.parseReader(reader);
            JsonObject rootObject = root.getAsJsonObject();
            JsonArray hits = rootObject.getAsJsonArray("hits");


            for (JsonObject resultObject : hits.asList().stream().map(JsonElement::getAsJsonObject).toList()) {
                SearchResult result = new SearchResult();
                result.read(resultObject);
                results.add(result);
            }
            return results;
        }
    }

    public Project getProject(String uid) throws IOException {
        // firstly, get json result
        APICall call = new APICall(apiUrl, "project", uid);
        System.out.println(call);
        String jsonResult = get(call);

        // then read project json lol
        Project project = new Project();
        try (JsonReader reader = new JsonReader(new StringReader(jsonResult))) {
            JsonElement root = JsonParser.parseReader(reader);
            JsonObject rootObject = root.getAsJsonObject();
            project.read(rootObject);
        }
        return project;
    }

    public List<Version> getVersions(Project project) throws IOException {
        return getVersions(project.id);
    }

    public List<Version> getVersions(String projectId) throws IOException {
        // firstly, get json result
        APICall call = new APICall(apiUrl, "project", projectId, "version");
        System.out.println(call);
        String jsonResult = get(call);

        // then read all the versions
        List<Version> versions = new ArrayList<>();
        try (JsonReader reader = new JsonReader(new StringReader(jsonResult))) {
            JsonElement root = JsonParser.parseReader(reader);
            JsonArray versionArray = root.getAsJsonArray();

            for (JsonObject versionObject : versionArray.asList().stream().map(JsonElement::getAsJsonObject).toList()) {
                Version version = new Version();
                version.read(versionObject);
                versions.add(version);
            }
        }
        return versions;
    }

    public static class SearchResult {
        public String slug;
        public String title;
        public String description;
        public String[] categories, displayCategories;
        public Side clientSide, serverSide;
        public Project.Type projectType;
        public int downloads, follows;
        public String projectId;
        public String author;
        public String[] versions;
        public LocalDateTime dateCreated, dateModified;
        public String latestVersion;
        public String license;

        private void read(JsonObject json) {
            DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ISO_DATE_TIME;

            this.slug = json.get("slug").getAsString();
            this.title = json.get("title").getAsString();
            this.description = json.get("description").getAsString();
            this.clientSide = Side.valueOf(json.get("client_side").getAsString().toUpperCase());
            this.serverSide = Side.valueOf(json.get("server_side").getAsString().toUpperCase());
            this.projectType = Project.Type.valueOf(json.get("project_type").getAsString().toUpperCase());
            this.downloads = json.get("downloads").getAsInt();
            this.projectId = json.get("project_id").getAsString();
            this.author = json.get("author").getAsString();
            this.follows = json.get("follows").getAsInt();
            this.dateCreated = LocalDateTime.parse(json.get("date_created").getAsString(), dateTimeFormatter);
            this.dateModified = LocalDateTime.parse(json.get("date_modified").getAsString(), dateTimeFormatter);
            JsonElement latestVersion = json.get("lastest_version");
            if (latestVersion != null) {
                this.latestVersion = latestVersion.getAsString();
            }

            this.license = json.get("license").getAsString();

            JsonArray categories = json.get("categories").getAsJsonArray();
            JsonArray versions = json.get("versions").getAsJsonArray();
            this.categories = categories.asList().stream().map(JsonElement::getAsString).toList().toArray(new String[0]);
            JsonElement displayCategoriesElement = json.get("displayCategories");
            if (displayCategoriesElement != null) {
                JsonArray displayCategories = displayCategoriesElement.getAsJsonArray();
                this.displayCategories = displayCategories.asList().stream().map(JsonElement::getAsString).toList().toArray(new String[0]);
            }
            this.versions = versions.asList().stream().map(JsonElement::getAsString).toList().toArray(new String[0]);
            if (latestVersion == null) {
                this.latestVersion = this.versions[this.versions.length - 1];
            }
        }

        @Override
        public String toString() {
            return "SearchResult{" +
                    "slug='" + slug + '\'' +
                    ", title='" + title + '\'' +
                    ", description='" + description + '\'' +
                    ", categories=" + Arrays.toString(categories) +
                    ", displayCategories=" + Arrays.toString(displayCategories) +
                    ", clientSide=" + clientSide +
                    ", serverSide=" + serverSide +
                    ", projectType=" + projectType +
                    ", downloads=" + downloads +
                    ", follows=" + follows +
                    ", projectId='" + projectId + '\'' +
                    ", author='" + author + '\'' +
                    ", versions=" + Arrays.toString(versions) +
                    ", dateCreated=" + dateCreated +
                    ", dateModified=" + dateModified +
                    ", latestVersion='" + latestVersion + '\'' +
                    ", license='" + license + '\'' +
                    '}';
        }

        public String toFriendlyString() {
            return String.format("%s (%s) by %s, %s @ %s", title, slug, author, description, latestVersion);
        }
    }

    public static class Project {
        public String slug;
        public String title;
        public String description;
        public String[] categories;
        public Side clientSide, serverSide;
        public String body;
        public Status status;
        public Type projectType;
        public int downloads;
        public String id;
        public String team;
        public LocalDateTime published, updated;
        public int followers;

        public String[] gameVersions;
        public String[] loaders;

        private void read(JsonObject json) {
            DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ISO_DATE_TIME;
            this.slug = json.get("slug").getAsString();
            this.title = json.get("title").getAsString();
            this.description = json.get("description").getAsString();
            this.clientSide = Side.valueOf(json.get("client_side").getAsString().toUpperCase());
            this.serverSide = Side.valueOf(json.get("server_side").getAsString().toUpperCase());
            this.body = json.get("body").getAsString();
            this.status = Project.Status.valueOf(json.get("status").getAsString().toUpperCase());
            this.projectType = Project.Type.valueOf(json.get("project_type").getAsString().toUpperCase());
            this.downloads = json.get("downloads").getAsInt();
            this.id = json.get("id").getAsString();
            this.team = json.get("team").getAsString();
            this.published = LocalDateTime.parse(json.get("published").getAsString(), dateTimeFormatter);
            this.updated = LocalDateTime.parse(json.get("updated").getAsString(), dateTimeFormatter);
            this.followers = json.get("followers").getAsInt();

            JsonArray categories = json.get("categories").getAsJsonArray();
            JsonArray gameVersionArray = json.getAsJsonArray("game_versions");
            JsonArray loadersArray = json.getAsJsonArray("loaders");
            this.categories = categories.asList().stream().map(JsonElement::getAsString).toList().toArray(new String[0]);

            if (gameVersions != null) {
                this.gameVersions = gameVersionArray.asList().stream().map(JsonElement::getAsString).toList().toArray(new String[0]);
            }

            if (loaders != null) {
                this.loaders = loadersArray.asList().stream().map(JsonElement::getAsString).toList().toArray(new String[0]);
            }
        }

        @Override
        public String toString() {
            return "Project{" +
                    "slug='" + slug + '\'' +
                    ", title='" + title + '\'' +
                    ", description='" + description + '\'' +
                    ", categories=" + Arrays.toString(categories) +
                    ", clientSide=" + clientSide +
                    ", serverSide=" + serverSide +
                    ", body='" + body + '\'' +
                    ", status=" + status +
                    ", projectType=" + projectType +
                    ", downloads=" + downloads +
                    ", id='" + id + '\'' +
                    ", team='" + team + '\'' +
                    ", published=" + published +
                    ", updated=" + updated +
                    ", followers=" + followers +
                    ", gameVersions=" + Arrays.toString(gameVersions) +
                    ", loaders=" + Arrays.toString(loaders) +
                    '}';
        }

        public enum Type {
            MOD, MODPACK, RESOURCEPACK, SHADER;
        }

        public enum Status {
            APPROVED, ARCHIVED, REJECTED, DRAFT,
            UNLISTED, PROCESSING, WITHELD, SCHEDULED,
            PRIVATE, UNKNOWN;
        }
    }

    public static class Version {
        public String name;
        public String number;
        public String changelog;
        public Dependency[] dependencies;
        public String[] gameVersions;
        public Type versionType;
        public String[] loaders;
        public boolean featured;
        public Status status;
        public String id;
        public String projectId;
        public String authorId;
        public LocalDateTime published;
        public int downloads;
        public VersionFile[] files;

        private void read(JsonObject json) {
            DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ISO_DATE_TIME;

            this.name = json.get("name").getAsString();
            this.number = json.get("version_number").getAsString();

            JsonElement changelog = json.get("changelog");
            if (changelog != null) {
                this.changelog = changelog.getAsString();
            }

            JsonArray dependencyArray = json.getAsJsonArray("dependencies");
            if (dependencyArray != null) {
                this.dependencies = new Dependency[dependencyArray.size()];
                List<JsonObject> dependencyObjects = dependencyArray.asList().stream().map(JsonElement::getAsJsonObject).toList();
                for (int i = 0; i < this.dependencies.length; i++) {
                    Dependency dependency = new Dependency();
                    dependency.read(dependencyObjects.get(i));
                    this.dependencies[i] = dependency;
                }
            }

            this.versionType = Type.valueOf(json.get("version_type").getAsString().toUpperCase());

            JsonArray gameVersions = json.get("game_versions").getAsJsonArray();
            this.gameVersions = gameVersions.asList().stream().map(JsonElement::getAsString).toList().toArray(new String[0]);

            this.featured = json.get("featured").getAsBoolean();
            this.status = Status.valueOf(json.get("status").getAsString().toUpperCase());
            this.name = json.get("id").getAsString();
            this.projectId = json.get("project_id").getAsString();
            this.authorId = json.get("author_id").getAsString();
            this.published = LocalDateTime.parse(json.get("date_published").getAsString(), dateTimeFormatter);
            this.downloads = json.get("downloads").getAsInt();

            JsonArray files = json.getAsJsonArray("files");
            this.files = new VersionFile[files.size()];
            List<JsonObject> fileObjectList = files.asList().stream().map(JsonElement::getAsJsonObject).toList();
            for (int i = 0; i < this.files.length; i++) {
                JsonObject fileObject = fileObjectList.get(i);
                VersionFile versionFile = new VersionFile();
                versionFile.read(fileObject);
                this.files[i] = versionFile;
            }
        }

        @Override
        public String toString() {
            return "Version{" +
                    "versionName='" + name + '\'' +
                    ", versionNumber='" + number + '\'' +
                    ", changelog='" + changelog + '\'' +
                    ", dependencies=" + Arrays.toString(dependencies) +
                    ", gameVersions=" + Arrays.toString(gameVersions) +
                    ", versionType=" + versionType +
                    ", loaders=" + Arrays.toString(loaders) +
                    ", featured=" + featured +
                    ", status=" + status +
                    ", id='" + id + '\'' +
                    ", projectId='" + projectId + '\'' +
                    ", authorId='" + authorId + '\'' +
                    ", published=" + published +
                    ", downloads=" + downloads +
                    '}';
        }

        public String toFriendlyString() {
            return String.format("%s (%s) @ %s", number, versionType, published);
        }

        public static class Dependency {
            public String versionId;
            public String projectId;
            public String fileName;
            public DependencyType dependencyType;

            private void read(JsonObject json) {
                JsonElement versionId = json.get("version_id");
                JsonElement projectId = json.get("project_id");
                JsonElement fileName = json.get("file_name");
                this.versionId = !versionId.isJsonNull() ? versionId.getAsString() : null;
                this.projectId = !projectId.isJsonNull() ? projectId.getAsString() : null;
                this.fileName = !fileName.isJsonNull() ? fileName.getAsString() : null;
                dependencyType = DependencyType.valueOf(json.get("dependency_type").getAsString().toUpperCase());
            }
        }

        public enum DependencyType {
            REQUIRED, OPTIONAL, INCOMPATIBLE, EMBEDDED;
        }

        public enum Type {
            RELEASE, BETA, ALPHA;
        }

        public enum Status {
            LISTED, ARCHIVED, DRAFT, UNLISTED,
            SCHEDULED, UNKNOWN;
        }

        public static class VersionFile {
            public Hashes hashes;
            public String url;
            public String fileName;
            public boolean primary;
            public int size;
            public boolean required;

            private void read(JsonObject json) {
                JsonObject hashes = json.getAsJsonObject("hashes");
                this.hashes = new Hashes(hashes);
                this.url = json.get("url").getAsString();
                this.fileName = json.get("filename").getAsString();
                this.primary = json.get("primary").getAsBoolean();
                this.size = json.get("size").getAsInt();
                JsonElement fileType = json.get("file_type");
                if (fileType != null && !fileType.isJsonNull()) {
                    this.required = fileType.getAsString().equals("required-resource-pack");
                }
            }
        }

        public record Hashes(String sha512, String sha1) {
            public Hashes(JsonObject json) {
                this(json.get("sha512").getAsString(), json.get("sha1").getAsString());
            }
        }
    }

    public enum SearchIndex {
        RELEVANCE, DOWNLOADS, FOLLOWS, NEWEST, UPDATED;
    }

    public static class Facet {
        private final StringBuilder builder = new StringBuilder();
        private String nextOperator;
        private boolean firstElement = true;

        public Facet() {
            builder.append('[');
        }

        private String consumeOperator() {
            String operator = nextOperator;
            nextOperator = null;
            return operator != null ? operator : "=";
        }

        public Facet or() {
            builder.append(", ");
            return this;
        }

        public Facet and() {
            builder.append(", [");
            return this;
        }

        public Facet start() {
            if (!firstElement) {
                or();
            }
            builder.append('[');
            firstElement = false;
            return this;
        }

        public Facet end() {
            builder.append(']');
            return this;
        }

        public Facet newProperty(String property, String value) {
            builder.append('\"').append(property).append('\"').append(consumeOperator()).append('\"').append(value).append('\"');
            return this;
        }

        public Facet category(String categories) {
            return newProperty("categories", categories);
        }

        public Facet projectType(Project.Type type) {
            return newProperty("project_type", type.name().toLowerCase());
        }

        public Facet version(String version) {
            return newProperty("versions", version);
        }

        public Facet clientSide(Side side) {
            return newProperty("clientSide", side.name().toUpperCase());
        }

        public Facet serverSide(Side side) {
            return newProperty("serverSide", side.name().toUpperCase());
        }

        public Facet nextOperator(String operator) {
            nextOperator = operator;
            return this;
        }

        public String toString() {
            return builder.toString() + ']';
        }

    }
    public enum Side {
        REQUIRED, OPTIONAL, UNSUPPORTED;
    }

    public record APICall(String baseURL, String path, Map<String, Object> parameters) {

        public APICall(String baseURL, String path) {
            this(baseURL, path, (Map<String, Object>)  null);
        }

        public APICall(String baseURL, String path, String object) {
            this(baseURL, path + "/" + object);
        }

        public APICall(String baseURL, String path, String... objects) {
            this(baseURL, path + "/" + String.join("/", objects));
        }

        public APICall(String baseURL, String path, String object, Map<String, Object> parameters) {
            this(baseURL, path + "/" + object, parameters);
        }

        public URL toURL() {
            try {
                return new URL(toString());
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }

        public URI toURI() {
            try {
                return new URI(toString());
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append(baseURL);
            if (!baseURL.endsWith("/")) {
                builder.append("/");
            }

            builder.append(path);
            if (parameters != null) {
                builder.append("?");

                List<Map.Entry<String, Object>> entrySet = List.copyOf(parameters.entrySet());
                for (int i = 0; i < entrySet.size(); i++) {
                    Map.Entry<String, Object> parameter = entrySet.get(i);
                    String key = parameter.getKey();
                    Object value = parameter.getValue();
                    if (value != null) {
                        builder.append(key).append("=").append(value);
                        if (i < entrySet.size() - 1) {
                            builder.append("&");
                        }
                    }
                }
            }

            return builder.toString();
        }
    }
}
