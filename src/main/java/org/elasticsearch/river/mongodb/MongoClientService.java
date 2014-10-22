package org.elasticsearch.river.mongodb;

import static java.util.concurrent.TimeUnit.MINUTES;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.common.annotations.VisibleForTesting;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;

import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.ReadPreference;
import com.mongodb.ServerAddress;

public class MongoClientService extends AbstractLifecycleComponent<MongoClientService> {
	private final ConcurrentMap<List<ServerAddress>, MongoClient> mongoClients = new ConcurrentHashMap<List<ServerAddress>, MongoClient>();

	@Inject
	public MongoClientService(Settings settings) {
		super(settings);
	}

	@Override
	protected void doStart() throws ElasticSearchException {
	}

	@Override
	protected void doStop() throws ElasticSearchException {
	}

	@Override
	protected void doClose() throws ElasticSearchException {
		for (MongoClient mongoClient : mongoClients.values()) {
			mongoClient.close();
		}
	}

	public MongoClient getMongoClient(List<ServerAddress> servers) {
		MongoClient mongoClient = mongoClients.get(servers);
		if (mongoClient == null) {
			logger.info("Creating MongoClient for [{}]", servers);
			mongoClient = createMongoClient(servers);
			MongoClient otherMongoClient = mongoClients.putIfAbsent(servers, mongoClient);
			if (otherMongoClient != null) {
				logger.info("Raced in creating MongoClient for [{}]", servers);
				mongoClient.close();
				mongoClient = otherMongoClient;
			}
		}
		return mongoClient;
	}

	@VisibleForTesting
	protected MongoClient createMongoClient(List<ServerAddress> servers) {
		// TODO: MongoClientOptions should be configurable
		// XXX: The socketTimeout() argument needs to be bigger than the "while" that the QUERYOPTION_AWAITDATA makes the
		//      server wait.
		//      From mongo/src/mongo/db/instance.cpp ::receivedGetMore() and
		//      mongo/src/mongo/db/query/new_find.cpp ::newGetMore() this seems to be about ~16min.
		// XXX: The original river supports changing the read preference
		MongoClientOptions mco = MongoClientOptions.builder()
			.connectTimeout(15000)
			.socketTimeout((int) MINUTES.toMillis(30))
			.readPreference(ReadPreference.primaryPreferred())
			.connectionsPerHost(100)
			.threadsAllowedToBlockForConnectionMultiplier(100)
			.build();
		return new MongoClient(servers, mco);
	}
}
