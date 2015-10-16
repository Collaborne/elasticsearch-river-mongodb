package org.elasticsearch.river.mongodb.embed;

import de.flapdoodle.embed.mongo.Command;
import de.flapdoodle.embed.process.extract.UUIDTempNaming;
import de.flapdoodle.embed.process.io.directories.PropertyOrPlatformTempDir;

public class TokuArtifactStoreBuilder extends de.flapdoodle.embed.process.store.ArtifactStoreBuilder {

    public TokuArtifactStoreBuilder defaults(Command command) {
        tempDir().setDefault(new PropertyOrPlatformTempDir());
        executableNaming().setDefault(new UUIDTempNaming());
        download().setDefault(new TokuDownloadConfigBuilder().defaultsForCommand(command).build());
        downloader().setDefault(new TokuDownloader());
        return this;
    }
}
