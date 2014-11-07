package org.elasticsearch.river.mongodb;

import static org.elasticsearch.river.mongodb.MongoDBRiver.MONGODB_ADMIN_DATABASE;
import static org.elasticsearch.river.mongodb.MongoDBRiver.MONGODB_CONFIG_DATABASE;
import static org.elasticsearch.river.mongodb.MongoDBRiver.MONGODB_ID_FIELD;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.river.RiverSettings;

import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.CommandResult;
import com.mongodb.DB;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoException;
import com.mongodb.MongoInterruptedException;
import com.mongodb.MongoSocketException;
import com.mongodb.ReadPreference;
import com.mongodb.ServerAddress;

class Starter implements Runnable {
    private final ESLogger logger = ESLoggerFactory.getLogger(this.getClass().getName());
    private final MongoClientService mongoClientService;
    private final RiverSettings settings;
    private final MongoDBRiverDefinition definition;
    private final SharedContext context;
    private final Client client;
    private final List<Thread> tailerThreads;

    private DB adminDb;

    public Starter(MongoClientService mongoClientService, RiverSettings settings, MongoDBRiverDefinition definition, SharedContext context, Client client, List<Thread> tailerThreads) {
        this.mongoClientService = mongoClientService;
        this.settings = settings;
        this.definition = definition;
        this.context = context;
        this.client = client;
        this.tailerThreads = tailerThreads;
    }

    @Override
    public void run() {
        while (true) {
            try {
                if (isMongos()) {
                    DBCursor cursor = getConfigDb().getCollection("shards").find();
                    try {
                        while (cursor.hasNext()) {
                            DBObject item = cursor.next();
                            logger.debug("shards: {}", item.toString());
                            List<ServerAddress> servers = getServerAddressForReplica(item);
                            if (servers != null) {
                                String replicaName = item.get(MONGODB_ID_FIELD).toString();
                                Thread tailerThread = EsExecutors.daemonThreadFactory(settings.globalSettings(),
                                        "mongodb_river_slurper_" + replicaName + ":" + definition.getIndexName()).newThread(new Slurper(getReplicaMongoClient(servers), definition, context, client));
                                tailerThreads.add(tailerThread);
                            }
                        }
                    } finally {
                        cursor.close();
                    }
                } else {
                    logger.trace("Not mongos");
                    Thread tailerThread = EsExecutors.daemonThreadFactory(settings.globalSettings(), "mongodb_river_slurper:" + definition.getIndexName()).newThread(
                            new Slurper(getMongoClient(), definition, context, client));
                    tailerThreads.add(tailerThread);
                }

                for (Thread thread : tailerThreads) {
                    thread.start();
                }

                // All started, finish this thread.
                break;
            } catch (MongoInterruptedException mIEx) {
                logger.error("Mongo driver has been interrupted", mIEx);
                break;
            } catch (MongoSocketException mNEx) {
                // Ignore these
                logger.warn("Mongo gave a network exception", mNEx);
            } catch (MongoException mEx) {
                // Something else, stop.
                logger.error("Mongo gave an exception: giving up", mEx);
                break;
            }
        }
    }

    private boolean isMongos() {
        if (definition.isMongos() != null) {
            return definition.isMongos().booleanValue();
        } else {
            DB adminDb = getAdminDb();
            if (adminDb == null) {
                return false;
            }
            logger.trace("Found {} database", MONGODB_ADMIN_DATABASE);
            DBObject command = BasicDBObjectBuilder.start(
                    ImmutableMap.builder().put("serverStatus", 1).put("asserts", 0).put("backgroundFlushing", 0).put("connections", 0)
                            .put("cursors", 0).put("dur", 0).put("extra_info", 0).put("globalLock", 0).put("indexCounters", 0)
                            .put("locks", 0).put("metrics", 0).put("network", 0).put("opcounters", 0).put("opcountersRepl", 0)
                            .put("recordStats", 0).put("repl", 0).build()).get();
            logger.trace("About to execute: {}", command);
            CommandResult cr = adminDb.command(command, ReadPreference.primary());
            logger.trace("Command executed return : {}", cr);

            logger.info("MongoDB version - {}", cr.get("version"));
            if (logger.isTraceEnabled()) {
                logger.trace("serverStatus: {}", cr);
            }

            if (!cr.ok()) {
                logger.warn("serverStatus returns error: {}", cr.getErrorMessage());
                return false;
            }

            if (cr.get("process") == null) {
                logger.warn("serverStatus.process return null.");
                return false;
            }
            String process = cr.get("process").toString().toLowerCase();
            if (logger.isTraceEnabled()) {
                logger.trace("process: {}", process);
            }
            // Fix for https://jira.mongodb.org/browse/SERVER-9160
            return (process.contains("mongos"));
        }
    }

    private DB getAdminDb() {
        if (adminDb == null) {
            if(!definition.getMongoAdminAuthDatabase().isEmpty()) {
                adminDb = getMongoClient().getDB(definition.getMongoAdminAuthDatabase());
            } else {
                adminDb = getMongoClient().getDB(MONGODB_ADMIN_DATABASE);
            }
            if (logger.isTraceEnabled()) {
                logger.trace("MongoAdminUser: {} - authenticated: {}", definition.getMongoAdminUser(), adminDb.isAuthenticated());
            }
            if (!definition.getMongoAdminUser().isEmpty() && !definition.getMongoAdminPassword().isEmpty() && !adminDb.isAuthenticated()) {
                logger.info("Authenticate {} with {}", MONGODB_ADMIN_DATABASE, definition.getMongoAdminUser());

                try {
                    CommandResult cmd = adminDb.authenticateCommand(definition.getMongoAdminUser(), definition.getMongoAdminPassword()
                            .toCharArray());
                    if (!cmd.ok()) {
                        logger.error("Authentication failed for {}: {}", MONGODB_ADMIN_DATABASE, cmd.getErrorMessage());
                    } else {
                        logger.trace("authenticateCommand: {} - isAuthenticated: {}", cmd, adminDb.isAuthenticated());
                    }
                } catch (MongoException mEx) {
                    logger.warn("getAdminDb() failed", mEx);
                }
            }
            adminDb = adminDb.getMongo().getDB(MONGODB_ADMIN_DATABASE);
        }
        if (adminDb == null) {
            throw new ElasticsearchException(String.format("Could not get %s database from MongoDB", MONGODB_ADMIN_DATABASE));
        }
        return adminDb;
    }

    private DB getConfigDb() {
        DB configDb = getMongoClient().getDB(MONGODB_CONFIG_DATABASE);
        if (!definition.getMongoAdminUser().isEmpty() && !definition.getMongoAdminPassword().isEmpty() && getAdminDb().isAuthenticated()) {
            configDb = getAdminDb().getMongo().getDB(MONGODB_CONFIG_DATABASE);
            // } else if (!mongoDbUser.isEmpty() && !mongoDbPassword.isEmpty()
            // && !configDb.isAuthenticated()) {
            // logger.info("Authenticate {} with {}", mongoDb, mongoDbUser);
            // CommandResult cmd = configDb.authenticateCommand(mongoDbUser,
            // mongoDbPassword.toCharArray());
            // if (!cmd.ok()) {
            // logger.error("Authentication failed for {}: {}",
            // DB_CONFIG, cmd.getErrorMessage());
            // }
        }
        if (configDb == null) {
            throw new ElasticsearchException(String.format("Could not get %s database from MongoDB", MONGODB_CONFIG_DATABASE));
        }
        return configDb;
    }

    private MongoClient getMongoClient() {
        return mongoClientService.getMongoClient(definition, null);
    }

    private MongoClient getReplicaMongoClient(List<ServerAddress> servers) {
        return mongoClientService.getMongoClient(definition, servers);
    }

    private List<ServerAddress> getServerAddressForReplica(DBObject item) {
        String definition = item.get("host").toString();
        if (definition.contains("/")) {
            definition = definition.substring(definition.indexOf("/") + 1);
        }
        if (logger.isDebugEnabled()) {
            logger.debug("getServerAddressForReplica - definition: {}", definition);
        }
        List<ServerAddress> servers = new ArrayList<ServerAddress>();
        for (String server : definition.split(",")) {
            try {
                servers.add(new ServerAddress(server));
            } catch (UnknownHostException uhEx) {
                logger.warn("failed to execute bulk", uhEx);
            }
        }
        return servers;
    }
}
