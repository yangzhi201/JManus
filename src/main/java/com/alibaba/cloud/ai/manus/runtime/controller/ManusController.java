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
package com.alibaba.cloud.ai.manus.runtime.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.cloud.ai.manus.coordinator.entity.po.CoordinatorToolEntity;
import com.alibaba.cloud.ai.manus.coordinator.repository.CoordinatorToolRepository;
import com.alibaba.cloud.ai.manus.event.JmanusListener;
import com.alibaba.cloud.ai.manus.event.PlanExceptionClearedEvent;
import com.alibaba.cloud.ai.manus.event.PlanExceptionEvent;
import com.alibaba.cloud.ai.manus.exception.PlanException;
import com.alibaba.cloud.ai.manus.planning.service.IPlanParameterMappingService;
import com.alibaba.cloud.ai.manus.planning.service.PlanTemplateService;
import com.alibaba.cloud.ai.manus.recorder.entity.vo.AgentExecutionRecord;
import com.alibaba.cloud.ai.manus.recorder.entity.vo.PlanExecutionRecord;
import com.alibaba.cloud.ai.manus.recorder.service.NewRepoPlanExecutionRecorder;
import com.alibaba.cloud.ai.manus.recorder.service.PlanHierarchyReaderService;
import com.alibaba.cloud.ai.manus.runtime.entity.po.RootTaskManagerEntity;
import com.alibaba.cloud.ai.manus.runtime.entity.vo.ExecutionStep;
import com.alibaba.cloud.ai.manus.runtime.entity.vo.PlanExecutionResult;
import com.alibaba.cloud.ai.manus.runtime.entity.vo.PlanExecutionWrapper;
import com.alibaba.cloud.ai.manus.runtime.entity.vo.PlanInterface;
import com.alibaba.cloud.ai.manus.runtime.entity.vo.UserInputWaitState;
import com.alibaba.cloud.ai.manus.runtime.service.PlanIdDispatcher;
import com.alibaba.cloud.ai.manus.runtime.service.PlanningCoordinator;
import com.alibaba.cloud.ai.manus.runtime.service.RootTaskManagerService;
import com.alibaba.cloud.ai.manus.runtime.service.TaskInterruptionManager;
import com.alibaba.cloud.ai.manus.runtime.service.UserInputService;
import com.alibaba.cloud.ai.manus.workspace.conversation.entity.vo.Memory;
import com.alibaba.cloud.ai.manus.workspace.conversation.service.MemoryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

@RestController
@RequestMapping("/api/executor")
public class ManusController implements JmanusListener<PlanExceptionEvent> {

	private static final Logger logger = LoggerFactory.getLogger(ManusController.class);

	private final ObjectMapper objectMapper;

	private final Cache<String, Throwable> exceptionCache;

	@Autowired
	@Lazy
	private PlanningCoordinator planningCoordinator;

	@Autowired
	private PlanHierarchyReaderService planHierarchyReaderService;

	@Autowired
	private PlanIdDispatcher planIdDispatcher;

	@Autowired
	private UserInputService userInputService;

	@Autowired
	private MemoryService memoryService;

	@Autowired
	private NewRepoPlanExecutionRecorder planExecutionRecorder;

	@Autowired
	private CoordinatorToolRepository coordinatorToolRepository;

	@Autowired
	private PlanTemplateService planTemplateService;

	@Autowired
	private IPlanParameterMappingService parameterMappingService;

	@Autowired
	private RootTaskManagerService rootTaskManagerService;

	@Autowired
	private TaskInterruptionManager taskInterruptionManager;

	@Autowired
	public ManusController(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
		// Register JavaTimeModule to handle LocalDateTime serialization/deserialization
		this.objectMapper.registerModule(new JavaTimeModule());
		// Ensure pretty printing is disabled by default for compact JSON
		// this.objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
		// 10minutes timeout for plan exception
		this.exceptionCache = CacheBuilder.newBuilder().expireAfterWrite(10, TimeUnit.MINUTES).build();
	}

	private boolean isVue(Map<String, Object> request) {

		// Check if request is from Vue frontend
		Boolean isVueRequest = (Boolean) request.get("isVueRequest");
		if (isVueRequest != null) {
			return isVueRequest;
		}

		// Intelligent judgment: If isVueRequest is not explicitly set, judge by other
		// features
		// 1. Check if there are any Vue-specific field combinations
		String toolName = (String) request.get("toolName");
		@SuppressWarnings("unchecked")
		List<String> uploadedFiles = (List<String>) request.get("uploadedFiles");

		// If the plan template is executed and there is an uploaded file, it is likely
		// to
		// be the Vue front-end
		if (toolName != null && toolName.startsWith("planTemplate-") && uploadedFiles != null) {
			logger.info("🔍 [AUTO-DETECT] Detected Vue request pattern: toolName={}, hasFiles={}", toolName,
					uploadedFiles != null ? uploadedFiles.size() : 0);
			return true;
		}

		// By default, it is not a Vue request
		return false;

	}

	/**
	 * Execute plan by tool name synchronously (GET method)
	 * @param toolName Tool name
	 * @return Execution result directly
	 */
	@GetMapping("/executeByToolNameSync/{toolName}")
	public ResponseEntity<Map<String, Object>> executeByToolNameGetSync(@PathVariable("toolName") String toolName,
			@RequestParam(required = false, name = "allParams") Map<String, String> allParams) {
		if (toolName == null || toolName.trim().isEmpty()) {
			return ResponseEntity.badRequest().body(Map.of("error", "Tool name cannot be empty"));
		}

		// Get plan template ID from coordinator tool
		String planTemplateId = getPlanTemplateIdFromTool(toolName);
		if (planTemplateId == null) {
			return ResponseEntity.badRequest().body(Map.of("error", "Tool not found with name: " + toolName));
		}
		if (planTemplateId.trim().isEmpty()) {
			return ResponseEntity.badRequest()
				.body(Map.of("error", "No plan template ID associated with tool: " + toolName));
		}

		logger.info("Execute tool '{}' synchronously with plan template ID '{}', parameters: {}", toolName,
				planTemplateId, allParams);
		// Execute synchronously and return result directly
		return executePlanSync(planTemplateId, null, null, false, null);
	}

	/**
	 * Execute plan by tool name asynchronously If tool is not published, treat toolName
	 * as planTemplateId
	 * @param request Request containing tool name and parameters
	 * @return Task ID and status
	 */
	@PostMapping("/executeByToolNameAsync")
	public ResponseEntity<Map<String, Object>> executeByToolNameAsync(@RequestBody Map<String, Object> request) {
		String toolName = (String) request.get("toolName");
		if (toolName == null || toolName.trim().isEmpty()) {
			return ResponseEntity.badRequest().body(Map.of("error", "Tool name cannot be empty"));
		}
		boolean isVueRequest = isVue(request);

		// Log request source
		if (isVueRequest) {
			logger.info("🌐 [VUE] Received query request from Vue frontend: ");
		}
		else {
			logger.info("🔗 [HTTP] Received query request from HTTP client: ");
		}

		String planTemplateId = null;

		// First, try to find the coordinator tool by tool name
		planTemplateId = getPlanTemplateIdFromTool(toolName);
		if (planTemplateId != null) {
			// Tool is published, get plan template ID from coordinator tool
			logger.info("Found published tool: {} with plan template ID: {}", toolName, planTemplateId);
		}
		else {
			// Tool is not published, treat toolName as planTemplateId
			planTemplateId = toolName;
			logger.info("Tool not published, using toolName as planTemplateId: {}", planTemplateId);
		}

		if (planTemplateId == null || planTemplateId.trim().isEmpty()) {
			return ResponseEntity.badRequest().body(Map.of("error", "No plan template ID found for tool: " + toolName));
		}

		try {
			String conversationId = (String) request.get("conversationId");

			// Handle uploaded files if present
			@SuppressWarnings("unchecked")
			List<String> uploadedFiles = (List<String>) request.get("uploadedFiles");

			String uploadKey = (String) request.get("uploadKey");

			// Debug logging for uploaded files
			logger.info("🔍 [DEBUG] Request keys: {}", request.keySet());
			logger.info("🔍 [DEBUG] uploadedFiles from request: {}", uploadedFiles);
			logger.info("🔍 [DEBUG] uploadedFiles is null: {}", uploadedFiles == null);
			if (uploadedFiles != null) {
				logger.info("🔍 [DEBUG] uploadedFiles size: {}", uploadedFiles.size());
				logger.info("🔍 [DEBUG] uploadedFiles names: {}", uploadedFiles);
			}

			// Generate conversation ID if not provided
			if (!StringUtils.hasText(conversationId)) {
				conversationId = memoryService.generateConversationId();
			}

			String query = "Execute plan template: " + planTemplateId;
			// Create Memory VO and save it
			Memory memory = new Memory(conversationId, query);
			memoryService.saveMemory(memory);

			// Get replacement parameters for <<>> replacement
			@SuppressWarnings("unchecked")
			Map<String, Object> replacementParams = (Map<String, Object>) request.get("replacementParams");

			// Execute the plan template using the new unified method
			PlanExecutionWrapper wrapper = executePlanTemplate(planTemplateId, uploadedFiles, conversationId,
					replacementParams, isVueRequest, uploadKey);

			// Create or update task manager entity for database-driven interruption
			if (wrapper.getRootPlanId() != null) {
				rootTaskManagerService.createOrUpdateTask(wrapper.getRootPlanId(),
						RootTaskManagerEntity.DesiredTaskState.START);
			}

			// Start the async execution (fire and forget)
			wrapper.getResult().whenComplete((result, throwable) -> {
				if (throwable != null) {
					logger.error("Async plan execution failed for planId: {}", wrapper.getRootPlanId(), throwable);
					// Update task state to indicate failure
					rootTaskManagerService.updateTaskResult(wrapper.getRootPlanId(),
							"Execution failed: " + throwable.getMessage());
				}
				else {
					logger.info("Async plan execution completed for planId: {}", wrapper.getRootPlanId());
					// Update task state to indicate completion
					rootTaskManagerService.updateTaskResult(wrapper.getRootPlanId(),
							result != null ? result.getFinalResult() : "Execution completed");
				}
			});

			// Return task ID and initial status
			Map<String, Object> response = new HashMap<>();
			response.put("planId", wrapper.getRootPlanId());
			response.put("status", "processing");
			response.put("message", "Task submitted, processing");
			response.put("conversationId", conversationId);
			response.put("toolName", toolName);
			response.put("planTemplateId", planTemplateId);

			return ResponseEntity.ok(response);

		}
		catch (Exception e) {
			logger.error("Failed to start plan execution for tool: {} with planTemplateId: {}", toolName,
					planTemplateId, e);
			Map<String, Object> errorResponse = new HashMap<>();
			errorResponse.put("error", "Failed to start plan execution: " + e.getMessage());
			errorResponse.put("toolName", toolName);
			errorResponse.put("planTemplateId", planTemplateId);
			return ResponseEntity.internalServerError().body(errorResponse);
		}
	}

	/**
	 * Execute plan by tool name synchronously (POST method)
	 * @param request Request containing tool name
	 * @return Execution result directly
	 */
	@PostMapping("/executeByToolNameSync")
	public ResponseEntity<Map<String, Object>> executeByToolNameSync(@RequestBody Map<String, Object> request) {
		String toolName = (String) request.get("toolName");
		if (toolName == null || toolName.trim().isEmpty()) {
			return ResponseEntity.badRequest().body(Map.of("error", "Tool name cannot be empty"));
		}

		boolean isVueRequest = isVue(request);

		// Log request source
		if (isVueRequest) {
			logger.info("🌐 [VUE] Received query request from Vue frontend: ");
		}
		else {
			logger.info("🔗 [HTTP] Received query request from HTTP client: ");
		}

		// Get plan template ID from coordinator tool
		String planTemplateId = getPlanTemplateIdFromTool(toolName);
		if (planTemplateId == null) {
			return ResponseEntity.badRequest().body(Map.of("error", "Tool not found with name: " + toolName));
		}
		if (planTemplateId.trim().isEmpty()) {
			return ResponseEntity.badRequest()
				.body(Map.of("error", "No plan template ID associated with tool: " + toolName));
		}

		// Handle uploaded files if present
		@SuppressWarnings("unchecked")
		List<String> uploadedFiles = (List<String>) request.get("uploadedFiles");

		String uploadKey = (String) request.get("uploadKey");

		// Get replacement parameters for <<>> replacement
		@SuppressWarnings("unchecked")
		Map<String, Object> replacementParams = (Map<String, Object>) request.get("replacementParams");

		logger.info(
				"Executing tool '{}' synchronously with plan template ID '{}', uploadedFiles: {}, replacementParams: {}, uploadKey: {}",
				toolName, planTemplateId, uploadedFiles != null ? uploadedFiles.size() : "null",
				replacementParams != null ? replacementParams.size() : "null", uploadKey);

		return executePlanSync(planTemplateId, uploadedFiles, replacementParams, isVueRequest, uploadKey);
	}

	/**
	 * Get execution record overview (without detailed ThinkActRecord information) Note:
	 * This method returns basic execution information and does not include detailed
	 * ThinkActRecord steps for each agent execution.
	 * @param planId Plan ID
	 * @return JSON representation of execution record overview
	 */
	@GetMapping("/details/{planId}")
	public synchronized ResponseEntity<?> getExecutionDetails(@PathVariable("planId") String planId) {
		Throwable throwable = this.exceptionCache.getIfPresent(planId);
		if (throwable != null) {
			logger.error("Exception found in exception cache for planId: {}", planId, throwable);
			logger.error("Invalidating exception cache for planId: {}", planId);
			this.exceptionCache.invalidate(planId);
			throw new PlanException(throwable);
		}
		PlanExecutionRecord planRecord = planHierarchyReaderService.readPlanTreeByRootId(planId);

		if (planRecord == null) {
			return ResponseEntity.notFound().build();
		}

		// Check for user input wait state and merge it into the plan record
		// Since form input tools are now stored by root plan ID, check using the root
		// plan ID
		String rootPlanId = planRecord.getRootPlanId() != null ? planRecord.getRootPlanId() : planId;
		UserInputWaitState waitState = userInputService.getWaitState(rootPlanId);
		if (waitState != null && waitState.isWaiting()) {
			// Set the planId in the wait state to the root plan ID for proper submission
			// This ensures frontend submits to the correct plan ID where the form is
			// stored
			waitState.setPlanId(rootPlanId);
			planRecord.setUserInputWaitState(waitState);
			logger.info(
					"Root plan {} is waiting for user input. Set waitState planId to rootPlanId for proper submission.",
					rootPlanId);
		}
		else {
			planRecord.setUserInputWaitState(null); // Clear if not waiting
		}

		// Set rootPlanId if it's null, using currentPlanId as default
		if (planRecord.getRootPlanId() == null) {
			planRecord.setRootPlanId(planRecord.getCurrentPlanId());
			logger.info("Set rootPlanId to currentPlanId for plan: {}", planId);
		}

		try {
			// Use Jackson ObjectMapper to convert object to JSON string
			String jsonResponse = objectMapper.writeValueAsString(planRecord);
			return ResponseEntity.ok(jsonResponse);
		}
		catch (JsonProcessingException e) {
			logger.error("Error serializing PlanExecutionRecord to JSON for planId: {}", planId, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body("Error processing request: " + e.getMessage());
		}
	}

	/**
	 * Delete execution record for specified plan ID
	 * @param planId Plan ID
	 * @return Result of delete operation
	 */
	@DeleteMapping("/details/{planId}")
	public ResponseEntity<Map<String, String>> removeExecutionDetails(@PathVariable("planId") String planId) {
		PlanExecutionRecord planRecord = planHierarchyReaderService.readPlanTreeByRootId(planId);
		if (planRecord == null) {
			return ResponseEntity.notFound().build();
		}

		// Note: We don't need to remove execution records since they are already stored
		// in the database
		// The database serves as the persistent storage for all execution records
		return ResponseEntity.ok(Map.of("message", "Execution record found (no deletion needed)", "planId", planId));
	}

	/**
	 * Submits user input for a plan that is waiting.
	 * @param planId The ID of the plan.
	 * @param formData The user-submitted form data, expected as Map<String, String>.
	 * @return ResponseEntity indicating success or failure.
	 */
	@PostMapping("/submit-input/{planId}")
	public ResponseEntity<Map<String, Object>> submitUserInput(@PathVariable("planId") String planId,
			@RequestBody Map<String, String> formData) { // Changed formData to
		// Map<String, String>
		try {
			logger.info("Received user input for plan {}: {}", planId, formData);

			// Submit user input to the provided planId
			// Since getExecutionDetails now sets the correct planId in waitState, this
			// should work correctly
			boolean success = userInputService.submitUserInputs(planId, formData);
			if (success) {
				logger.info("Successfully submitted user input to plan {}", planId);
				return ResponseEntity.ok(Map.of("message", "Input submitted successfully", "planId", planId));
			}

			// No waiting plan found
			logger.warn("No waiting plan found for user input submission. Plan {} is not waiting for input.", planId);
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(Map.of("error", "No plan is currently waiting for user input.", "planId", planId));
		}
		catch (IllegalArgumentException e) {
			logger.error("Error submitting user input for plan {}: {}", planId, e.getMessage());
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(Map.of("error", e.getMessage(), "planId", planId));
		}
		catch (Exception e) {
			logger.error("Unexpected error submitting user input for plan {}: {}", planId, e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(Map.of("error", "An unexpected error occurred.", "planId", planId));
		}
	}

	/**
	 * Execute plan synchronously and build response with parameter replacement support
	 * @param planTemplateId The plan template ID to execute
	 * @param uploadedFiles List of uploaded file names (can be null)
	 * @param replacementParams Parameters for <<>> replacement (can be null)
	 * @param isVueRequest Flag indicating whether this is a Vue frontend request
	 * @param uploadKey Optional uploadKey provided by frontend (can be null)
	 * @return ResponseEntity with execution result
	 */
	private ResponseEntity<Map<String, Object>> executePlanSync(String planTemplateId, List<String> uploadedFiles,
			Map<String, Object> replacementParams, boolean isVueRequest, String uploadKey) {
		PlanExecutionWrapper wrapper = null;
		try {
			// Execute the plan template using the new unified method
			wrapper = executePlanTemplate(planTemplateId, uploadedFiles, null, replacementParams, isVueRequest,
					uploadKey);

			// Create or update task manager entity for database-driven interruption
			if (wrapper.getRootPlanId() != null) {
				rootTaskManagerService.createOrUpdateTask(wrapper.getRootPlanId(),
						RootTaskManagerEntity.DesiredTaskState.START);
			}

			PlanExecutionResult planExecutionResult = wrapper.getResult().get();

			// Update task result with execution result
			if (planExecutionResult != null) {
				rootTaskManagerService.updateTaskResult(wrapper.getRootPlanId(), planExecutionResult.getFinalResult());
			}

			// Return success with execution result
			Map<String, Object> response = new HashMap<>();
			response.put("status", "completed");
			response.put("result", planExecutionResult != null ? planExecutionResult.getFinalResult() : "No result");

			return ResponseEntity.ok(response);

		}
		catch (Exception e) {
			logger.error("Failed to execute plan template synchronously: {}", planTemplateId, e);

			// Update task result to indicate failure
			if (wrapper != null && wrapper.getRootPlanId() != null) {
				rootTaskManagerService.updateTaskResult(wrapper.getRootPlanId(), "Execution failed: " + e.getMessage());
			}

			Map<String, Object> errorResponse = new HashMap<>();
			errorResponse.put("error", "Execution failed: " + e.getMessage());
			errorResponse.put("status", "failed");
			return ResponseEntity.internalServerError().body(errorResponse);
		}
	}

	/**
	 * Execute a plan template by its ID with parameter replacement support
	 *
	 * key method
	 * @param planTemplateId The ID of the plan template to execute
	 * @param uploadedFiles List of uploaded file names (can be null)
	 * @param conversationId Conversation ID for the execution (can be null)
	 * @param replacementParams Parameters for <<>> replacement (can be null)
	 * @param isVueRequest Flag indicating whether this is a Vue frontend request
	 * @param uploadKey Optional uploadKey provided by frontend (can be null)
	 * @return PlanExecutionWrapper containing both PlanExecutionResult and rootPlanId
	 */
	private PlanExecutionWrapper executePlanTemplate(String planTemplateId, List<String> uploadedFiles,
			String conversationId, Map<String, Object> replacementParams, boolean isVueRequest, String uploadKey) {
		if (planTemplateId == null || planTemplateId.trim().isEmpty()) {
			logger.error("Plan template ID is null or empty");
			throw new IllegalArgumentException("Plan template ID cannot be null or empty");
		}
		String planJson = null;
		try {

			String currentPlanId;
			String rootPlanId;
			currentPlanId = planIdDispatcher.generatePlanId();
			rootPlanId = currentPlanId;
			logger.info("🆕 Generated new planId: {}", currentPlanId);

			// Generate conversation ID if not provided
			if (!StringUtils.hasText(conversationId)) {
				conversationId = memoryService.generateConversationId();
			}

			// Get the latest plan version JSON string
			planJson = planTemplateService.getLatestPlanVersion(planTemplateId);
			if (planJson == null) {
				throw new RuntimeException("Plan template not found: " + planTemplateId);
			}

			// Prepare parameters for replacement
			Map<String, Object> parametersForReplacement = new HashMap<>();
			if (replacementParams != null) {
				parametersForReplacement.putAll(replacementParams);
			}
			// Add the generated planId to parameters
			parametersForReplacement.put("planId", rootPlanId);

			// Replace parameter placeholders (<< >>) with actual input parameters
			if (!parametersForReplacement.isEmpty()) {
				try {
					logger.info("Replacing parameter placeholders in plan template with input parameters: {}",
							parametersForReplacement.keySet());
					planJson = parameterMappingService.replaceParametersInJson(planJson, parametersForReplacement);
					logger.debug("Parameter replacement completed successfully");
				}
				catch (Exception e) {
					String errorMsg = "Failed to replace parameters in plan template: " + e.getMessage();
					logger.error(errorMsg, e);
					CompletableFuture<PlanExecutionResult> failedFuture = new CompletableFuture<>();
					failedFuture.completeExceptionally(new RuntimeException(errorMsg, e));
					return new PlanExecutionWrapper(failedFuture, null);
				}
			}
			else {
				logger.debug("No parameter replacement needed - replacementParams: {}",
						replacementParams != null ? replacementParams.size() : 0);
			}

			// Parse the plan JSON to create PlanInterface
			PlanInterface plan = objectMapper.readValue(planJson, PlanInterface.class);

			// Handle uploaded files if present
			if (uploadedFiles != null && !uploadedFiles.isEmpty()) {
				logger.info("Uploaded files will be handled by the execution context for plan template: {}",
						uploadedFiles.size());

				// Attach uploaded files to each step's stepRequirement
				if (plan.getAllSteps() != null) {
					for (ExecutionStep step : plan.getAllSteps()) {
						if (step.getStepRequirement() != null) {
							String fileInfo = String.join(", ", uploadedFiles);
							String originalRequirement = step.getStepRequirement();
							step.setStepRequirement(originalRequirement + "\n \n  [Uploaded files: " + fileInfo + "]");
							logger.info("Attached uploaded files to step requirement: {}", step.getStepRequirement());
						}
					}
				}
			}

			// Log uploadKey if provided
			if (uploadKey != null) {
				logger.info("Executing plan with upload key: {}", uploadKey);
			}

			// Log uploadKey if provided
			if (uploadKey != null) {
				logger.info("Executing plan with upload key: {}", uploadKey);
			}

			// Execute using the PlanningCoordinator
			CompletableFuture<PlanExecutionResult> future = planningCoordinator.executeByPlan(plan, rootPlanId, null,
					currentPlanId, null, isVueRequest, uploadKey);

			// Return the wrapper containing both the future and rootPlanId
			return new PlanExecutionWrapper(future, rootPlanId);

		}
		catch (Exception e) {
			logger.error("Failed to execute plan template: {}", planTemplateId, e);
			logger.error("Failed to execute plan json : {}", planJson);
			CompletableFuture<PlanExecutionResult> failedFuture = new CompletableFuture<>();
			failedFuture.completeExceptionally(new RuntimeException("Plan execution failed: " + e.getMessage(), e));
			return new PlanExecutionWrapper(failedFuture, null);
		}
	}

	/**
	 * Get detailed agent execution record by stepId (includes ThinkActRecord details)
	 * @param stepId The step ID to query
	 * @return Detailed agent execution record with ThinkActRecord details
	 */
	@GetMapping("/agent-execution/{stepId}")
	public ResponseEntity<AgentExecutionRecord> getAgentExecutionDetail(@PathVariable("stepId") String stepId) {
		try {
			logger.info("Fetching agent execution detail for stepId: {}", stepId);

			AgentExecutionRecord detail = planExecutionRecorder.getAgentExecutionDetail(stepId);
			if (detail == null) {
				logger.warn("Agent execution detail not found for stepId: {}", stepId);
				return ResponseEntity.notFound().build();
			}

			logger.info("Successfully retrieved agent execution detail for stepId: {}", stepId);
			return ResponseEntity.ok(detail);
		}
		catch (Exception e) {
			logger.error("Error fetching agent execution detail for stepId: {}", stepId, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		}
	}

	/**
	 * Get plan template ID from coordinator tool by tool name
	 * @param toolName The tool name to look up
	 * @return Plan template ID if found, null if tool not found
	 */
	private String getPlanTemplateIdFromTool(String toolName) {
		CoordinatorToolEntity coordinatorTool = coordinatorToolRepository.findByToolName(toolName);
		if (coordinatorTool == null) {
			return null;
		}
		Boolean isHttpEnabled = coordinatorTool.getEnableHttpService();
		if (!isHttpEnabled) {
			return null;
		}
		return coordinatorTool.getPlanTemplateId();
	}

	@Override
	public void onEvent(PlanExceptionEvent event) {
		this.exceptionCache.put(event.getPlanId(), event.getThrowable());
	}

	@EventListener
	public void onPlanExceptionCleared(PlanExceptionClearedEvent event) {
		logger.info("Clearing exception cache for planId: {}", event.getPlanId());
		this.exceptionCache.invalidate(event.getPlanId());
	}

	/**
	 * Stop a running task by plan ID
	 * @param planId The plan ID to stop
	 * @return Response indicating success or failure
	 */
	@PostMapping("/stopTask/{planId}")
	public ResponseEntity<Map<String, Object>> stopTask(@PathVariable("planId") String planId) {
		try {
			logger.info("Received stop task request for planId: {}", planId);

			// Check if task is currently running using database state
			boolean isTaskRunning = taskInterruptionManager.isTaskRunning(planId);
			boolean taskExists = rootTaskManagerService.taskExists(planId);

			if (!isTaskRunning && !taskExists) {
				logger.warn("No active task found for planId: {}", planId);
				return ResponseEntity.badRequest()
					.body(Map.of("error", "No active task found for the given plan ID", "planId", planId));
			}

			// Mark task for stop in database (database-driven interruption)
			boolean taskMarkedForStop = taskInterruptionManager.stopTask(planId);

			// Update task result to indicate manual stop
			if (taskMarkedForStop) {
				rootTaskManagerService.updateTaskResult(planId, "Task manually stopped by user");
			}

			logger.info("Successfully marked task for stop for planId: {}", planId);
			return ResponseEntity
				.ok(Map.of("status", "stopped", "planId", planId, "message", "Task stop request processed successfully",
						"taskMarkedForStop", taskMarkedForStop, "wasRunning", isTaskRunning));

		}
		catch (Exception e) {
			logger.error("Failed to stop task for planId: {}", planId, e);
			return ResponseEntity.internalServerError()
				.body(Map.of("error", "Failed to stop task: " + e.getMessage(), "planId", planId));
		}
	}

	/**
	 * Get task status by plan ID
	 * @param planId The plan ID to check
	 * @return Task status information
	 */
	@GetMapping("/taskStatus/{planId}")
	public ResponseEntity<Map<String, Object>> getTaskStatus(@PathVariable("planId") String planId) {
		try {
			logger.info("Getting task status for planId: {}", planId);

			boolean isTaskRunning = taskInterruptionManager.isTaskRunning(planId);
			Optional<RootTaskManagerEntity> taskEntity = rootTaskManagerService.getTaskByRootPlanId(planId);

			Map<String, Object> response = new HashMap<>();
			response.put("planId", planId);
			response.put("isRunning", isTaskRunning);

			if (taskEntity.isPresent()) {
				RootTaskManagerEntity task = taskEntity.get();
				response.put("desiredState", task.getDesiredTaskState());
				response.put("startTime", task.getStartTime());
				response.put("endTime", task.getEndTime());
				response.put("lastUpdated", task.getLastUpdated());
				response.put("taskResult", task.getTaskResult());
				response.put("exists", true);
			}
			else {
				response.put("exists", false);
				response.put("desiredState", null);
				response.put("startTime", null);
				response.put("endTime", null);
				response.put("lastUpdated", null);
				response.put("taskResult", null);
			}

			return ResponseEntity.ok(response);

		}
		catch (Exception e) {
			logger.error("Failed to get task status for planId: {}", planId, e);
			return ResponseEntity.internalServerError()
				.body(Map.of("error", "Failed to get task status: " + e.getMessage(), "planId", planId));
		}
	}

}
