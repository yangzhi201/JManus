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
package com.alibaba.cloud.ai.lynxe.tool.innerStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.alibaba.cloud.ai.lynxe.config.LynxeProperties;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;

/**
 * Internal file storage service for storing intermediate data in MapReduce processes
 */
@Service
public class SmartContentSavingService implements ISmartContentSavingService {

	private static final Logger log = LoggerFactory.getLogger(SmartContentSavingService.class);

	private static final int CONTENT_LENGTH_THRESHOLD = 3000;

	private static final int TRUNCATE_PREFIX_LENGTH = 250;

	private static final int TRUNCATE_SUFFIX_LENGTH = 200;

	private final LynxeProperties lynxeProperties;

	private final UnifiedDirectoryManager unifiedDirectoryManager;

	public SmartContentSavingService(LynxeProperties lynxeProperties, UnifiedDirectoryManager unifiedDirectoryManager) {
		this.lynxeProperties = lynxeProperties;
		this.unifiedDirectoryManager = unifiedDirectoryManager;
	}

	public LynxeProperties getLynxeProperties() {
		return lynxeProperties;
	}

	/**
	 * Smart processing result class
	 */
	public static class SmartProcessResult {

		private final String fileName;

		private final String summary;

		public SmartProcessResult(String fileName, String summary) {
			this.fileName = fileName;
			this.summary = summary;
		}

		public String getFileName() {
			return fileName;
		}

		public String getSummary() {
			return summary;
		}

		@Override
		public String toString() {
			return String.format("SmartProcessResult{fileName='%s', summary='%s'}", fileName, summary);
		}

	}

	/**
	 * Intelligently process content, automatically store and return summary if content is
	 * too long
	 * @param planId Plan ID
	 * @param content Content
	 * @param callingMethod Calling method name
	 * @return Processing result containing filename and summary
	 */
	public SmartProcessResult processContent(String planId, String content, String callingMethod) {
		if (planId == null || content == null) {
			log.warn("processContent called with null parameters: planId={}, content={}, callingMethod={}", planId,
					content, callingMethod);
			return new SmartProcessResult(null, content != null ? content : "No content available");
		}

		// Check if content is empty
		if (content.trim().isEmpty()) {
			log.warn("processContent called with empty content: planId={}, callingMethod={}", planId, callingMethod);
			return new SmartProcessResult(null, "");
		}

		// Check if content exceeds threshold
		if (content.length() > CONTENT_LENGTH_THRESHOLD) {
			try {
				// Generate filename: callingMethod + 5-digit random number + .md
				int randomNum = ThreadLocalRandom.current().nextInt(10000, 100000);
				String fileName = callingMethod + randomNum + ".md";

				// Get root plan directory
				Path rootPlanDirectory = unifiedDirectoryManager.getRootPlanDirectory(planId);
				Path filePath = rootPlanDirectory.resolve(fileName);

				// Ensure directory exists
				Files.createDirectories(rootPlanDirectory);

				// Save full content to file
				Files.writeString(filePath, content);
				log.info("Saved long content ({} chars) to file: {}", content.length(), filePath);

				// Generate truncated summary: first 250 chars + "...[truncated]..." +
				// last 200 chars
				String truncatedSummary = generateTruncatedSummary(content);

				return new SmartProcessResult(fileName, truncatedSummary);
			}
			catch (IOException e) {
				log.error("Failed to save content to file for planId={}, callingMethod={}", planId, callingMethod, e);
				// Fall back to returning truncated content without saving
				String truncatedSummary = generateTruncatedSummary(content);
				return new SmartProcessResult(null, truncatedSummary);
			}
		}

		// Return content directly if below threshold
		log.debug("Returning content directly for plan {} (length: {})", planId, content.length());
		return new SmartProcessResult(null, content);
	}

	/**
	 * Generate truncated summary: first 250 chars + "...[truncated]..." + last 200 chars
	 * @param content Original content
	 * @return Truncated summary
	 */
	private String generateTruncatedSummary(String content) {
		if (content.length() <= TRUNCATE_PREFIX_LENGTH + TRUNCATE_SUFFIX_LENGTH) {
			return content;
		}

		String prefix = content.substring(0, TRUNCATE_PREFIX_LENGTH);
		String suffix = content.substring(content.length() - TRUNCATE_SUFFIX_LENGTH);
		return prefix + "...[truncated]..." + suffix;
	}

}
