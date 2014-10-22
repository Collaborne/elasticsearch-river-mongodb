package org.elasticsearch.plugin.river.mongodb;

import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.river.mongodb.MongoClientService;

public class MongoClientServiceModule extends AbstractModule {
	@Override
	protected void configure() {
		bind(MongoClientService.class).asEagerSingleton();
	}
}
