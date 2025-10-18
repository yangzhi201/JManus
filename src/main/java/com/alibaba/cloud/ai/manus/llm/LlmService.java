/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.manus.llm;

import com.alibaba.cloud.ai.manus.config.ManusProperties;
import com.alibaba.cloud.ai.manus.event.JmanusListener;
import com.alibaba.cloud.ai.manus.event.ModelChangeEvent;
import com.alibaba.cloud.ai.manus.model.entity.DynamicModelEntity;
import com.alibaba.cloud.ai.manus.model.repository.DynamicModelRepository;

import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LlmService implements JmanusListener<ModelChangeEvent> {

	private static final Logger log = LoggerFactory.getLogger(LlmService.class);

	private DynamicModelEntity defaultModel;

	private ChatClient diaChatClient;

	// Cached concurrent map for ChatClient instances with modelName as key
	private final Map<String, ChatClient> chatClientCache = new ConcurrentHashMap<>();

	private ChatMemory conversationMemory;

	private ChatMemory agentMemory;

	/*
	 * Required for creating custom chatModel
	 */
	@Autowired
	private ObjectProvider<RestClient.Builder> restClientBuilderProvider;

	@Autowired
	private ObjectProvider<WebClient.Builder> webClientBuilderProvider;

	@Autowired
	private ObjectProvider<ObservationRegistry> observationRegistry;

	@Autowired
	private ObjectProvider<ChatModelObservationConvention> observationConvention;

	@Autowired
	private ObjectProvider<ToolExecutionEligibilityPredicate> openAiToolExecutionEligibilityPredicate;

	@Autowired(required = false)
	private ManusProperties manusProperties;

	@Autowired
	private DynamicModelRepository dynamicModelRepository;

	@Autowired
	private ChatMemoryRepository chatMemoryRepository;

	@Autowired
	private LlmTraceRecorder llmTraceRecorder;

	@Autowired(required = false)
	private WebClient webClientWithDnsCache;

	public LlmService() {
	}

	/**
	 * Unified ChatClient builder method that uses the existing openAiApi() method
	 * @param model Dynamic model entity
	 * @param options Chat options (with internalToolExecutionEnabled already set)
	 * @return Configured ChatClient
	 */
	private ChatClient buildUnifiedChatClient(String modelName, DynamicModelEntity model, OpenAiChatOptions options) {
		// Use the existing openAiChatModel method which calls openAiApi()
		OpenAiChatModel chatModel = openAiChatModel(modelName, model, options);

		return ChatClient.builder(chatModel)
			.defaultAdvisors(new SimpleLoggerAdvisor())
			.defaultOptions(OpenAiChatOptions.fromOptions(options))
			.build();
	}

	private void initializeChatClientsWithModel(DynamicModelEntity model) {
		// Set the default model
		this.defaultModel = model;

		OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder().build();

		if (this.diaChatClient == null) {
			this.diaChatClient = buildDialogChatClient(model, defaultOptions);
			log.debug("Planning ChatClient init finish");
		}

	}

	private void tryLazyInitialization() {
		// Check if defaultModel is already cached
		if (defaultModel != null) {
			log.debug("Using cached default model: {}", defaultModel.getModelName());
			return;
		}

		try {
			DynamicModelEntity fetchedDefaultModel = dynamicModelRepository.findByIsDefaultTrue();
			if (fetchedDefaultModel == null) {
				List<DynamicModelEntity> availableModels = dynamicModelRepository.findAll();
				if (!availableModels.isEmpty()) {
					fetchedDefaultModel = availableModels.get(0);
				}
			}

			if (fetchedDefaultModel != null) {
				log.info("Lazy init ChatClient, using model: {}", fetchedDefaultModel.getModelName());
				initializeChatClientsWithModel(fetchedDefaultModel);
			}
		}
		catch (Exception e) {
			log.error("Lazy init ChatClient failed", e);
		}
	}

	public ChatClient getDefaultDynamicAgentChatClient() {

		return getDynamicAgentChatClient(null);
	}

	public ChatClient getDynamicAgentChatClient(String modelName) {
		if (defaultModel == null) {
			log.warn("Default model not initialized...");
			tryLazyInitialization();

			if (defaultModel == null) {
				throw new IllegalStateException("Default model not initialized, please specify model first");
			}
		}

		// Use DEFAULT_MODELNAME as key when modelName is null or empty
		String cacheKey = (modelName == null || modelName.isEmpty()) ? defaultModel.getModelName() : modelName;

		// Check cache first
		ChatClient cachedClient = chatClientCache.get(cacheKey);
		if (cachedClient != null) {
			log.debug("Using cached ChatClient for model: {}", cacheKey);
			return cachedClient;
		}

		// Use unified ChatOptions creation
		OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder().build();
		defaultOptions.setInternalToolExecutionEnabled(false);

		// Use unified ChatClient builder
		ChatClient client = buildUnifiedChatClient(modelName, defaultModel, defaultOptions);

		// Cache the ChatClient instance
		chatClientCache.put(cacheKey, client);

		log.info("Build and cache dynamic chat client for model: {}", cacheKey);
		return client;
	}

	public ChatMemory getAgentMemory(Integer maxMessages) {
		if (agentMemory == null) {
			agentMemory = MessageWindowChatMemory.builder()
				// in memory use by agent
				.chatMemoryRepository(new InMemoryChatMemoryRepository())
				.maxMessages(maxMessages)
				.build();
		}
		return agentMemory;
	}

	public void clearAgentMemory(String memoryId) {
		if (this.agentMemory != null) {
			this.agentMemory.clear(memoryId);
		}
	}

	public ChatClient getDiaChatClient() {
		if (diaChatClient == null) {
			// Try lazy initialization
			log.warn("Agent ChatClient not initialized...");
			tryLazyInitialization();

			if (diaChatClient == null) {
				throw new IllegalStateException("Agent ChatClient not initialized, please specify model first");
			}
		}
		return diaChatClient;
	}

	public void clearConversationMemory(String memoryId) {
		if (this.conversationMemory == null) {
			// Default to 100 messages if not specified elsewhere
			this.conversationMemory = MessageWindowChatMemory.builder()
				.chatMemoryRepository(chatMemoryRepository)
				.maxMessages(100)
				.build();
		}
		this.conversationMemory.clear(memoryId);
	}

	public ChatMemory getConversationMemory(Integer maxMessages) {
		if (conversationMemory == null) {
			conversationMemory = MessageWindowChatMemory.builder()
				.chatMemoryRepository(chatMemoryRepository)
				.maxMessages(maxMessages)
				.build();
		}
		return conversationMemory;
	}

	@Override
	public void onEvent(ModelChangeEvent event) {
		DynamicModelEntity dynamicModelEntity = event.getDynamicModelEntity();

		initializeChatClientsWithModel(dynamicModelEntity);

		if (dynamicModelEntity.getIsDefault()) {
			log.info("Model updated, clearing ChatClient cache");
			this.diaChatClient = null;
			this.defaultModel = null;
			// Clear the ChatClient cache when default model changes
			chatClientCache.clear();
			initializeChatClientsWithModel(dynamicModelEntity);
		}
	}

	/**
	 * Refresh the cached default model from database Call this method when you need to
	 * update the cache
	 */
	public void refreshDefaultModelCache() {
		log.info("Refreshing default model cache");
		this.defaultModel = null;
		this.diaChatClient = null;
		// Clear the ChatClient cache when refreshing default model
		chatClientCache.clear();
		tryLazyInitialization();
	}

	/**
	 * Clear specific ChatClient from cache by model name
	 * @param modelName The model name to remove from cache
	 */
	public void clearChatClientCache(String modelName) {
		if (modelName != null && !modelName.isEmpty()) {
			chatClientCache.remove(modelName);
			log.info("Cleared ChatClient cache for model: {}", modelName);
		}
	}

	/**
	 * Clear all ChatClient cache entries
	 */
	public void clearAllChatClientCache() {
		chatClientCache.clear();
		log.info("Cleared all ChatClient cache entries");
	}

	/**
	 * Get cache size for monitoring purposes
	 * @return Number of cached ChatClient instances
	 */
	public int getChatClientCacheSize() {
		return chatClientCache.size();
	}

	private ChatClient buildDialogChatClient(DynamicModelEntity dynamicModelEntity, OpenAiChatOptions defaultOptions) {
		// Enable internal tool execution for planning
		defaultOptions.setInternalToolExecutionEnabled(true);
		// use default model name as model name in dialog
		return buildUnifiedChatClient(dynamicModelEntity.getModelName(), dynamicModelEntity, defaultOptions);
	}

	private OpenAiChatModel openAiChatModel(String modelName, DynamicModelEntity dynamicModelEntity,
			OpenAiChatOptions defaultOptions) {
		if (modelName == null || modelName.isEmpty()) {
			log.warn("Model name is null or empty, using default model name: {}", dynamicModelEntity.getModelName());
			modelName = dynamicModelEntity.getModelName();
		}
		defaultOptions.setModel(modelName);
		if (defaultOptions.getTemperature() == null && dynamicModelEntity.getTemperature() != null) {
			defaultOptions.setTemperature(dynamicModelEntity.getTemperature());
		}
		if (defaultOptions.getTopP() == null && dynamicModelEntity.getTopP() != null) {
			defaultOptions.setTopP(dynamicModelEntity.getTopP());
		}
		Map<String, String> headers = dynamicModelEntity.getHeaders();
		if (headers == null) {
			headers = new HashMap<>();
		}
		headers.put("User-Agent", "JManus/3.0.2-SNAPSHOT");
		defaultOptions.setHttpHeaders(headers);
		var openAiApi = openAiApi(restClientBuilderProvider.getIfAvailable(RestClient::builder),
				webClientBuilderProvider.getIfAvailable(WebClient::builder), dynamicModelEntity);
		OpenAiChatOptions options = OpenAiChatOptions.fromOptions(defaultOptions);
		var chatModel = OpenAiChatModel.builder()
			.openAiApi(openAiApi)
			.defaultOptions(options)
			// .toolCallingManager(toolCallingManager)
			.toolExecutionEligibilityPredicate(
					openAiToolExecutionEligibilityPredicate.getIfUnique(DefaultToolExecutionEligibilityPredicate::new))
			// .retryTemplate(retryTemplate)
			.observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
			.build();

		observationConvention.ifAvailable(chatModel::setObservationConvention);

		return chatModel;
	}

	/**
	 * Create enhanced WebClient builder with DNS cache or timeout configuration
	 * @param webClientBuilder Default WebClient builder
	 * @param dynamicModelEntity Model entity for logging
	 * @return Enhanced WebClient builder
	 */
	private WebClient.Builder createEnhancedWebClientBuilder(WebClient.Builder webClientBuilder,
			DynamicModelEntity dynamicModelEntity) {
		// Use DNS-cached WebClient if available, otherwise use enhanced builder
		WebClient.Builder enhancedWebClientBuilder;
		if (webClientWithDnsCache != null) {
			log.info("Using DNS-cached WebClient for model: {}", dynamicModelEntity.getModelName());
			enhancedWebClientBuilder = webClientWithDnsCache.mutate();
		}
		else {
			log.warn("DNS-cached WebClient not available, using default WebClient builder");
			enhancedWebClientBuilder = webClientBuilder.clone()
				// Add 5 minutes default timeout setting
				.codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)) // 10MB
				.filter((request, next) -> next.exchange(request).timeout(Duration.ofMinutes(10)));
		}
		return enhancedWebClientBuilder;
	}

	private OpenAiApi openAiApi(RestClient.Builder restClientBuilder, WebClient.Builder webClientBuilder,
			DynamicModelEntity dynamicModelEntity) {
		Map<String, String> headers = dynamicModelEntity.getHeaders();
		MultiValueMap<String, String> multiValueMap = new LinkedMultiValueMap<>();
		if (headers != null) {
			headers.forEach((key, value) -> multiValueMap.add(key, value));
		}

		// Use enhanced WebClient builder
		WebClient.Builder enhancedWebClientBuilder = createEnhancedWebClientBuilder(webClientBuilder,
				dynamicModelEntity);

		String completionsPath = dynamicModelEntity.getCompletionsPath();

		return new OpenAiApi(dynamicModelEntity.getBaseUrl(), new SimpleApiKey(dynamicModelEntity.getApiKey()),
				multiValueMap, completionsPath, "/v1/embeddings", restClientBuilder, enhancedWebClientBuilder,
				RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER) {
			@Override
			public ResponseEntity<ChatCompletion> chatCompletionEntity(ChatCompletionRequest chatRequest,
					MultiValueMap<String, String> additionalHttpHeader) {
				llmTraceRecorder.recordRequest(chatRequest);
				return super.chatCompletionEntity(chatRequest, additionalHttpHeader);
			}

			@Override
			public Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest chatRequest,
					MultiValueMap<String, String> additionalHttpHeader) {
				llmTraceRecorder.recordRequest(chatRequest);
				return super.chatCompletionStream(chatRequest, additionalHttpHeader);
			}
		};
	}

}
