package io.github.samzhu.skillshub;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.grafana.LgtmStackContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.samzhu.skillshub.storage.StorageService;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	MongoDBContainer mongoContainer() {
		return new MongoDBContainer(DockerImageName.parse("mongo:8"));
	}

	@Bean
	@ServiceConnection
	LgtmStackContainer grafanaLgtmContainer() {
		return new LgtmStackContainer(DockerImageName.parse("grafana/otel-lgtm:latest"));
	}

	// @Primary 確保測試環境中 InMemoryStorageService 優先於 @Profile("local") 的 FileSystemStorageService
	@Bean
	@org.springframework.context.annotation.Primary
	StorageService storageService() {
		return new InMemoryStorageService();
	}

	static class InMemoryStorageService implements StorageService {
		private final java.util.Map<String, byte[]> store = new java.util.concurrent.ConcurrentHashMap<>();

		@Override
		public void upload(String path, byte[] data) {
			store.put(path, data);
		}

		@Override
		public byte[] download(String path) {
			var data = store.get(path);
			if (data == null) throw new RuntimeException("Not found: " + path);
			return data;
		}

		@Override
		public void delete(String path) {
			store.remove(path);
		}
	}

}
