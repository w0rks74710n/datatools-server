package com.conveyal.datatools.manager;

import com.conveyal.datatools.manager.auth.Auth0Connection;

import com.conveyal.datatools.manager.controllers.DumpController;
import com.conveyal.datatools.manager.controllers.api.*;
import com.conveyal.datatools.editor.controllers.api.*;

import com.conveyal.datatools.manager.extensions.ExternalFeedResource;
import com.conveyal.datatools.manager.extensions.mtc.MtcFeedResource;
import com.conveyal.datatools.manager.extensions.transitfeeds.TransitFeedsFeedResource;
import com.conveyal.datatools.manager.extensions.transitland.TransitLandFeedResource;

import com.conveyal.datatools.common.status.MonitorableJob;
import com.conveyal.datatools.manager.models.Project;
import com.conveyal.datatools.manager.persistence.FeedStore;
import com.conveyal.datatools.manager.utils.CorsFilter;
import com.conveyal.gtfs.GTFSCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.io.Resources;
import org.apache.commons.io.Charsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.utils.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static spark.Spark.*;

public class DataManager {

    public static final Logger LOG = LoggerFactory.getLogger(DataManager.class);

    public static JsonNode config;
    public static JsonNode serverConfig;

    public static JsonNode gtfsPlusConfig;
    public static JsonNode gtfsConfig;

    public static final Map<String, ExternalFeedResource> feedResources = new HashMap<>();

    public static Map<String, Set<MonitorableJob>> userJobsMap = new HashMap<>();

    public static Map<String, ScheduledFuture> autoFetchMap = new HashMap<>();
    public final static ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    public static GTFSCache gtfsCache;

    public static String feedBucket;
    public static String bucketFolder;

//    public final AmazonS3Client s3Client;
    public static boolean useS3;
    public static final String apiPrefix = "/api/manager/";

    public static final String DEFAULT_ENV = "configurations/default/env.yml";
    public static final String DEFAULT_CONFIG = "configurations/default/server.yml";

    private static List<String> apiFeedSources = new ArrayList<>();

    public static void main(String[] args) throws IOException {

        // load config
        loadConfig(args);

        // set port
        if (getConfigProperty("application.port") != null) {
            port(Integer.parseInt(getConfigPropertyAsText("application.port")));
        }
        useS3 = getConfigPropertyAsText("application.data.use_s3_storage").equals("true");

        // initialize map of auto fetched projects
        for (Project p : Project.getAll()) {
            if (p.autoFetchFeeds != null && autoFetchMap.get(p.id) == null){
                if (p.autoFetchFeeds) {
                    ScheduledFuture scheduledFuture = ProjectController.scheduleAutoFeedFetch(p.id, p.autoFetchHour, p.autoFetchMinute, 1, p.defaultTimeZone);
                    autoFetchMap.put(p.id, scheduledFuture);
                }
            }
        }

        feedBucket = getConfigPropertyAsText("application.data.gtfs_s3_bucket");
        bucketFolder = FeedStore.s3Prefix;

        if (useS3) {
            LOG.info("Initializing gtfs-api for bucket {}/{} and cache dir {}", feedBucket, bucketFolder, FeedStore.basePath);
            gtfsCache = new GTFSCache(feedBucket, bucketFolder, FeedStore.basePath);
        }
        else {
            LOG.info("Initializing gtfs cache locally (no s3 bucket) {}", FeedStore.basePath);
            gtfsCache = new GTFSCache(null, FeedStore.basePath);
        }
        CorsFilter.apply();

        // core controllers
        ProjectController.register(apiPrefix);
        FeedSourceController.register(apiPrefix);
        FeedVersionController.register(apiPrefix);
        RegionController.register(apiPrefix);
        NoteController.register(apiPrefix);
        StatusController.register(apiPrefix);
        OrganizationController.register(apiPrefix);

        // Editor routes
        if ("true".equals(getConfigPropertyAsText("modules.editor.enabled"))) {
            String gtfs = IOUtils.toString(DataManager.class.getResourceAsStream("/gtfs/gtfs.yml"));
            gtfsConfig = yamlMapper.readTree(gtfs);
            AgencyController.register(apiPrefix);
            CalendarController.register(apiPrefix);
            RouteController.register(apiPrefix);
            RouteTypeController.register(apiPrefix);
            ScheduleExceptionController.register(apiPrefix);
            StopController.register(apiPrefix);
            TripController.register(apiPrefix);
            TripPatternController.register(apiPrefix);
            SnapshotController.register(apiPrefix);
            FeedInfoController.register(apiPrefix);
            FareController.register(apiPrefix);
        }

        // log all exceptions to system.out
        exception(Exception.class, (e, req, res) -> LOG.error("error", e));

        // module-specific controllers
        if (isModuleEnabled("deployment")) {
            DeploymentController.register(apiPrefix);
        }
        if (isModuleEnabled("gtfsapi")) {
            GtfsApiController.register(apiPrefix);
        }
        if (isModuleEnabled("gtfsplus")) {
            GtfsPlusController.register(apiPrefix);
            URL gtfsplus = DataManager.class.getResource("/gtfs/gtfsplus.yml");
            gtfsPlusConfig = yamlMapper.readTree(Resources.toString(gtfsplus, Charsets.UTF_8));
        }
        if (isModuleEnabled("user_admin")) {
            UserController.register(apiPrefix);
        }
        if (isModuleEnabled("dump")) {
            DumpController.register("/");
        }

        before(apiPrefix + "secure/*", (request, response) -> {
            if(request.requestMethod().equals("OPTIONS")) return;
            Auth0Connection.checkUser(request);
        });

        // lazy load by feed source id if new one is requested
//        if ("true".equals(getConfigPropertyAsText("modules.gtfsapi.load_on_fetch"))) {
//            before(apiPrefix + "*", (request, response) -> {
//                String feeds = request.queryParams("feed");
//                if (feeds != null) {
//                    String[] feedIds = feeds.split(",");
//                    for (String feedId : feedIds) {
//                        FeedSource fs = FeedSource.get(feedId);
//                        if (fs == null) {
//                            continue;
//                        }
//                        else if (!GtfsApiController.gtfsApi.registeredFeedSources.contains(fs.id) && !apiFeedSources.contains(fs.id)) {
//                            apiFeedSources.add(fs.id);
//
//                            LoadGtfsApiFeedJob loadJob = new LoadGtfsApiFeedJob(fs);
//                            new Thread(loadJob).start();
//                        halt(202, "Initializing feed load...");
//                        }
//                        else if (apiFeedSources.contains(fs.id) && !GtfsApiController.gtfsApi.registeredFeedSources.contains(fs.id)) {
//                            halt(202, "Loading feed, please try again later");
//                        }
//                    }
//
//                }
//            });
//        }
        // return "application/json" for all API routes
        after(apiPrefix + "*", (request, response) -> {
            response.type("application/json");
            response.header("Content-Encoding", "gzip");
        });
        // load index.html
        InputStream stream = DataManager.class.getResourceAsStream("/public/index.html");
        String index = IOUtils.toString(stream).replace("${S3BUCKET}", getConfigPropertyAsText("application.assets_bucket"));
        stream.close();

        // return 404 for any api response that's not found
        get(apiPrefix + "*", (request, response) -> {
            halt(404);
            return null;
        });
        
//        // return assets as byte array
//        get("/assets/*", (request, response) -> {
//            try (InputStream stream = DataManager.class.getResourceAsStream("/public" + request.pathInfo())) {
//                return IOUtils.toByteArray(stream);
//            } catch (IOException e) {
//                return null;
//            }
//        });
        // return index.html for any sub-directory
        get("/*", (request, response) -> {
            response.type("text/html");
            return index;
        });
        registerExternalResources();
    }

    public static boolean hasConfigProperty(String name) {
        // try the server config first, then the main config
        boolean fromServerConfig = hasConfigProperty(serverConfig, name);
        if(fromServerConfig) return fromServerConfig;

        return hasConfigProperty(config, name);
    }

    public static boolean hasConfigProperty(JsonNode config, String name) {
        String parts[] = name.split("\\.");
        JsonNode node = config;
        for(int i = 0; i < parts.length; i++) {
            if(node == null) return false;
            node = node.get(parts[i]);
        }
        return node != null;
    }

    public static JsonNode getConfigProperty(String name) {
        // try the server config first, then the main config
        JsonNode fromServerConfig = getConfigProperty(serverConfig, name);
        if(fromServerConfig != null) return fromServerConfig;

        return getConfigProperty(config, name);
    }

    public static JsonNode getConfigProperty(JsonNode config, String name) {
        String parts[] = name.split("\\.");
        JsonNode node = config;
        for(int i = 0; i < parts.length; i++) {
            if(node == null) {
                LOG.warn("Config property {} not found", name);
                return null;
            }
            node = node.get(parts[i]);
        }
        return node;
    }

    public static String getConfigPropertyAsText(String name) {
        JsonNode node = getConfigProperty(name);
        if (node != null) {
            return node.asText();
        } else {
            LOG.warn("Config property {} not found", name);
            return null;
        }
    }

    public static boolean isModuleEnabled(String moduleName) {
        return "true".equals(getConfigPropertyAsText("modules." + moduleName + ".enabled"));
    }

    public static boolean isExtensionEnabled(String extensionName) {
        return "true".equals(getConfigPropertyAsText("extensions." + extensionName + ".enabled"));
    }

    private static void registerExternalResources() {

        if (isExtensionEnabled("mtc")) {
            LOG.info("Registering MTC Resource");
            registerExternalResource(new MtcFeedResource());
        }

        if (isExtensionEnabled("transitland")) {
            LOG.info("Registering TransitLand Resource");
            registerExternalResource(new TransitLandFeedResource());
        }

        if (isExtensionEnabled("transitfeeds")) {
            LOG.info("Registering TransitFeeds Resource");
            registerExternalResource(new TransitFeedsFeedResource());
        }
    }
    private static void loadConfig (String[] args) throws IOException {
        FileInputStream configStream;
        FileInputStream serverConfigStream;

        if (args.length == 0) {
            LOG.warn("Using default env.yml: {}", DEFAULT_ENV);
            LOG.warn("Using default server.yml: {}", DEFAULT_CONFIG);
            configStream = new FileInputStream(new File(DEFAULT_ENV));
            serverConfigStream = new FileInputStream(new File(DEFAULT_CONFIG));
        }
        else {
            LOG.info("Loading env.yml: {}", args[0]);
            LOG.info("Loading server.yml: {}", args[1]);
            configStream = new FileInputStream(new File(args[0]));
            serverConfigStream = new FileInputStream(new File(args[1]));
        }

        config = yamlMapper.readTree(configStream);
        serverConfig = yamlMapper.readTree(serverConfigStream);
    }
    private static void registerExternalResource(ExternalFeedResource resource) {
        feedResources.put(resource.getResourceType(), resource);
    }
}
