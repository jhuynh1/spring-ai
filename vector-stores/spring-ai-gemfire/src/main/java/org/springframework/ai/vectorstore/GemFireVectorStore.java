/*
 * Copyright 2023 - 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.ai.vectorstore;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingClient;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import reactor.util.annotation.NonNull;

/**
 * A VectorStore implementation backed by GemFire. This store supports creating, updating,
 * deleting, and similarity searching of documents in a GemFire index.
 *
 * @author Geet Rawat
 */
public class GemFireVectorStore implements VectorStore, InitializingBean {

	private static final Logger logger = LoggerFactory.getLogger(GemFireVectorStore.class);

	private static final int DEFAULT_PORT = 8080;

	private static final String DEFAULT_HOST = "localhost";

	public static final String DEFAULT_URI = "http{ssl}://{host}:{port}/gemfire-vectordb/v1/indexes";

	private static final String EMBEDDINGS = "/embeddings";

	private final WebClient client;

	private final EmbeddingClient embeddingClient;

	// Create Index DEFAULT Values

	private static final String[] DEFAULT_VECTOR_FIELD = new String[] { "vector" };

	private static final String DEFAULT_SIMILARITY_FUNCTION = "COSINE";

	private static final int DEFAULT_BEAM_WIDTH = 100;

	private static final int DEFAULT_BUCKETS = 0;

	private static final int DEFAULT_CONNECTION = 16;

	private static final String DEFAULT_DOCUMENT_FIELD = "document";

	// Create Index Parameters

	public String indexName;

	private int beamWidth;

	private int maxConnections;

	private int buckets;

	private String vectorSimilarityFunction;

	private String[] fields;

	private final String documentField;

	// Query Defaults
	public static final String QUERY = "/query";

	private static final String DISTANCE_METADATA_FIELD_NAME = "distance";

	public static final class GemFireVectorStoreConfig {

		private final WebClient client;

		private final String indexName;

		private final int beamWidth;

		private final int maxConnections;

		private final String vectorSimilarityFunction;

		private String[] fields;

		private final int buckets;

		private final String documentField;

		public static Builder builder() {
			return new Builder();
		}

		private GemFireVectorStoreConfig(Builder builder) {
			String base = UriComponentsBuilder.fromUriString(DEFAULT_URI)
				.build(builder.sslEnabled ? "s" : "", builder.host, builder.port)
				.toString();
			this.indexName = builder.indexName;
			this.client = WebClient.create(base);
			this.beamWidth = builder.beamWidth;
			this.maxConnections = builder.maxConnections;
			this.buckets = builder.buckets;
			this.vectorSimilarityFunction = builder.vectorSimilarityFunction;
			this.fields = builder.fields;
			this.documentField = builder.documentField;
		}

		public static class Builder {

			private String host = DEFAULT_HOST;

			private int port = DEFAULT_PORT;

			private boolean sslEnabled;

			private long connectionTimeout;

			private long requestTimeout;

			private String indexName;

			private int beamWidth = DEFAULT_BEAM_WIDTH;

			private int maxConnections = DEFAULT_CONNECTION;

			private int buckets = DEFAULT_BUCKETS;

			private String vectorSimilarityFunction = DEFAULT_SIMILARITY_FUNCTION;

			private String[] fields = DEFAULT_VECTOR_FIELD;

			private String documentField = DEFAULT_DOCUMENT_FIELD;

			public Builder withHost(String host) {
				Assert.hasText(host, "host must have a value");
				this.host = host;
				return this;
			}

			public Builder withPort(int port) {
				Assert.isTrue(port > 0, "port must be postive");
				this.port = port;
				return this;
			}

			public Builder withSslEnabled(boolean sslEnabled) {
				this.sslEnabled = sslEnabled;
				return this;
			}

			public Builder withConnectionTimeout(long timeout) {
				Assert.isTrue(timeout >= 0, "timeout must be >= 0");
				this.connectionTimeout = timeout;
				return this;
			}

			public Builder withRequestTimeout(long timeout) {
				Assert.isTrue(timeout >= 0, "timeout must be >= 0");
				this.requestTimeout = timeout;
				return this;
			}

			public Builder withIndexName(String indexName) {
				Assert.hasText(indexName, "indexName must have a value");
				this.indexName = indexName;
				return this;
			}

			public Builder withBeamWidth(int beamWidth) {
				Assert.isTrue(beamWidth > 0, "beamWidth must be positive");
				Assert.isTrue(beamWidth < 3200, "beamWidth must be less than 3200");
				this.beamWidth = beamWidth;
				return this;
			}

			public Builder withMaxConnections(int maxConnections) {
				Assert.isTrue(maxConnections > 0, "maxConnections must be positive");
				Assert.isTrue(maxConnections <= 512, "maxConnections must be less than 512");
				this.maxConnections = maxConnections;
				return this;
			}

			public Builder withBuckets(int buckets) {
				Assert.isTrue(buckets >= 0, "bucket must be positive");
				this.buckets = buckets;
				return this;
			}

			public Builder withVectorSimilarityFunction(String vectorSimilarityFunction) {
				Assert.hasText(vectorSimilarityFunction, "vectorSimilarityFunction must have a value");
				this.vectorSimilarityFunction = vectorSimilarityFunction;
				return this;
			}

			public Builder withFields(String[] fields) {
				this.fields = fields;
				return this;
			}

			public Builder withDocumentField(String documentField) {
				Assert.hasText(documentField, "documentField must have a value");
				this.documentField = documentField;
				return this;
			}

			public GemFireVectorStoreConfig build() {
				return new GemFireVectorStoreConfig(this);
			}

		}

	}

	public GemFireVectorStore(GemFireVectorStoreConfig config, EmbeddingClient embedding) {
		Assert.notNull(config, "GemFireVectorStoreConfig must not be null");
		Assert.notNull(embedding, "EmbeddingClient must not be null");
		this.client = config.client;
		this.indexName = config.indexName;
		this.embeddingClient = embedding;
		this.beamWidth = config.beamWidth;
		this.maxConnections = config.maxConnections;
		this.buckets = config.buckets;
		this.vectorSimilarityFunction = config.vectorSimilarityFunction;
		this.fields = config.fields;
		this.documentField = config.documentField;
	}

	public static class CreateRequest {

		@JsonProperty("name")
		private String indexName;

		@JsonProperty("beam-width")
		private int beamWidth = DEFAULT_BEAM_WIDTH;

		@JsonProperty("max-connections")
		private int maxConnections = DEFAULT_CONNECTION;

		@JsonProperty("vector-similarity-function")
		private String vectorSimilarityFunction = DEFAULT_SIMILARITY_FUNCTION;

		@JsonProperty("fields")
		private String[] fields = DEFAULT_VECTOR_FIELD;

		@JsonProperty("buckets")
		private int buckets = DEFAULT_BUCKETS;

		public CreateRequest() {
		}

		public CreateRequest(String indexName) {
			this.indexName = indexName;
		}

		public String getIndexName() {
			return indexName;
		}

		public void setIndexName(String indexName) {
			this.indexName = indexName;
		}

		public int getBeamWidth() {
			return beamWidth;
		}

		public void setBeamWidth(int beamWidth) {
			this.beamWidth = beamWidth;
		}

		public int getMaxConnections() {
			return maxConnections;
		}

		public void setMaxConnections(int maxConnections) {
			this.maxConnections = maxConnections;
		}

		public String getVectorSimilarityFunction() {
			return vectorSimilarityFunction;
		}

		public void setVectorSimilarityFunction(String vectorSimilarityFunction) {
			this.vectorSimilarityFunction = vectorSimilarityFunction;
		}

		public String[] getFields() {
			return fields;
		}

		public void setFields(String[] fields) {
			this.fields = fields;
		}

		public int getBuckets() {
			return buckets;
		}

		public void setBuckets(int buckets) {
			this.buckets = buckets;
		}

	}

	private static final class UploadRequest {

		private final List<Embedding> embeddings;

		public List<Embedding> getEmbeddings() {
			return embeddings;
		}

		@JsonCreator
		public UploadRequest(@JsonProperty("embeddings") List<Embedding> embeddings) {
			this.embeddings = embeddings;
		}

		private static final class Embedding {

			private final String key;

			private List<Float> vector;

			@JsonInclude(JsonInclude.Include.NON_NULL)
			private Map<String, Object> metadata;

			public Embedding(@JsonProperty("key") String key, @JsonProperty("vector") List<Float> vector,
					String contentName, String content, @JsonProperty("metadata") Map<String, Object> metadata) {
				this.key = key;
				this.vector = vector;
				this.metadata = new HashMap<>(metadata);
				this.metadata.put(contentName, content);
			}

			public String getKey() {
				return key;
			}

			public List<Float> getVector() {
				return vector;
			}

			public Map<String, Object> getMetadata() {
				return metadata;
			}

		}

	}

	private static final class QueryRequest {

		@JsonProperty("vector")
		@NonNull
		private final List<Float> vector;

		@JsonProperty("top-k")
		private final int k;

		@JsonProperty("k-per-bucket")
		private final int kPerBucket;

		@JsonProperty("include-metadata")
		private final boolean includeMetadata;

		public QueryRequest(List<Float> vector, int k, int kPerBucket, boolean includeMetadata) {
			this.vector = vector;
			this.k = k;
			this.kPerBucket = kPerBucket;
			this.includeMetadata = includeMetadata;
		}

		public List<Float> getVector() {
			return vector;
		}

		public int getK() {
			return k;
		}

		public int getkPerBucket() {
			return kPerBucket;
		}

		public boolean isIncludeMetadata() {
			return includeMetadata;
		}

	}

	private static final class QueryResponse {

		private String key;

		private float score;

		private Map<String, Object> metadata;

		private String getContent(String field) {
			return (String) metadata.get(field);
		}

		public void setKey(String key) {
			this.key = key;
		}

		public void setScore(float score) {
			this.score = score;
		}

		public void setMetadata(Map<String, Object> metadata) {
			this.metadata = metadata;
		}

	}

	private static class DeleteRequest {

		@JsonProperty("delete-data")
		private boolean deleteData = true;

		public DeleteRequest() {
		}

		public DeleteRequest(boolean deleteData) {
			this.deleteData = deleteData;
		}

		public boolean isDeleteData() {
			return deleteData;
		}

		public void setDeleteData(boolean deleteData) {
			this.deleteData = deleteData;
		}

	}

	@Override
	public void add(List<Document> documents) {
		UploadRequest upload = new UploadRequest(documents.stream().map(document -> {
			// Compute and assign an embedding to the document.
			document.setEmbedding(this.embeddingClient.embed(document));
			List<Float> floatVector = document.getEmbedding().stream().map(Double::floatValue).toList();
			return new UploadRequest.Embedding(document.getId(), floatVector, documentField, document.getContent(),
					document.getMetadata());
		}).toList());

		ObjectMapper objectMapper = new ObjectMapper();
		String embeddingsJson = null;
		try {
			String embeddingString = objectMapper.writeValueAsString(upload);
			embeddingsJson = embeddingString.substring("{\"embeddings\":".length());
		}
		catch (JsonProcessingException e) {
			throw new RuntimeException(String.format("Embedding JSON parsing error: %s", e.getMessage()));
		}

		client.post()
			.uri("/" + indexName + EMBEDDINGS)
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(embeddingsJson)
			.retrieve()
			.bodyToMono(Void.class)
			.onErrorMap(WebClientException.class, this::handleHttpClientException)
			.block();
	}

	@Override
	public Optional<Boolean> delete(List<String> idList) {
		try {
			client.method(HttpMethod.DELETE)
				.uri("/" + indexName + EMBEDDINGS)
				.body(BodyInserters.fromValue(idList))
				.retrieve()
				.bodyToMono(Void.class)
				.block();
		}
		catch (Exception e) {
			logger.warn("Error removing embedding: " + e);
			return Optional.of(false);
		}
		return Optional.of(true);
	}

	@Override
	public List<Document> similaritySearch(SearchRequest request) {
		if (request.hasFilterExpression()) {
			throw new UnsupportedOperationException("GemFire currently does not support metadata filter expressions.");
		}
		List<Double> vector = this.embeddingClient.embed(request.getQuery());
		List<Float> floatVector = vector.stream().map(Double::floatValue).toList();
		System.out.println("JASON:" + indexName);
		return client.post()
			.uri("/" + indexName + QUERY)
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(new QueryRequest(floatVector, request.getTopK(), request.getTopK() /*
																							 * TopKPerBucket
																							 */, true))
			.retrieve()
			.bodyToFlux(QueryResponse.class)
			.filter(r -> r.score >= request.getSimilarityThreshold())
			.map(r -> {
				Map<String, Object> metadata = r.metadata;
				metadata.put(DISTANCE_METADATA_FIELD_NAME, 1 - r.score);
				String content = (String) metadata.remove(documentField);
				return new Document(r.key, content, metadata);
			})
			.collectList()
			.onErrorMap(WebClientException.class, this::handleHttpClientException)
			.block();
	}

	public void createIndex(String indexName) throws JsonProcessingException {
		CreateRequest createRequest = new CreateRequest(indexName);
		createRequest.setBeamWidth(beamWidth);
		createRequest.setMaxConnections(maxConnections);
		createRequest.setBuckets(buckets);
		createRequest.setVectorSimilarityFunction(vectorSimilarityFunction);
		createRequest.setFields(fields);

		ObjectMapper objectMapper = new ObjectMapper();
		String index = objectMapper.writeValueAsString(createRequest);
		System.out.println("JASON trying to create index:" + index);

		client.post()
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(index)
			.retrieve()
			.bodyToMono(Void.class)
			.onErrorMap(WebClientException.class, this::handleHttpClientException)
			.block();
	}

	public void deleteIndex(String indexName) {
		DeleteRequest deleteRequest = new DeleteRequest();
		client.method(HttpMethod.DELETE)
			.uri("/" + indexName)
			.body(BodyInserters.fromValue(deleteRequest))
			.retrieve()
			.bodyToMono(Void.class)
			.onErrorMap(WebClientException.class, this::handleHttpClientException)
			.block();
	}

	private Throwable handleHttpClientException(Throwable ex) {
		if (!(ex instanceof WebClientResponseException clientException)) {
			throw new RuntimeException(String.format("Got an unexpected error: %s", ex));
		}

		if (clientException.getStatusCode().equals(NOT_FOUND)) {
			throw new RuntimeException(String.format("Index %s not found: %s", indexName, ex));
		}
		else if (clientException.getStatusCode().equals(BAD_REQUEST)) {
			throw new RuntimeException(String.format("Bad Request: %s", ex));
		}
		else {
			throw new RuntimeException(String.format("Got an unexpected HTTP error: %s", ex));
		}
	}

	@Override
	public void afterPropertiesSet() {
		// if (!exists(this.indexName)) {
		// createIndex(this.indexName);
		// }
	}

}
