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
package com.alibaba.cloud.ai.lynxe.tool.textOperator;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.alibaba.cloud.ai.lynxe.tool.innerStorage.SmartContentSavingService;
import com.alibaba.cloud.ai.lynxe.tool.shortUrl.ShortUrlService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Global file write operator that performs write operations on files. This operator
 * provides access to files that can be accessed across all sub-plans within the same
 * execution context.
 *
 * Keywords: global files, root directory, root folder, root plan directory, global file
 * write operations, root file access, cross-plan files.
 *
 * Use this tool for write operations on global files, root directory files, or root
 * folder files.
 */
public class GlobalFileWriteOperator extends AbstractBaseTool<GlobalFileWriteOperator.WriteFileInput> {

	private static final Logger log = LoggerFactory.getLogger(GlobalFileWriteOperator.class);

	private static final String TOOL_NAME = "global_file_write_file_operator";

	/**
	 * Set of supported text file extensions
	 */
	private static final Set<String> SUPPORTED_EXTENSIONS = new HashSet<>(Set.of(".txt", ".md", ".markdown", // Plain
																												// text
																												// and
																												// Markdown
			".java", ".py", ".js", ".ts", ".jsx", ".tsx", // Common programming languages
			".html", ".htm", ".mhtml", ".css", ".scss", ".sass", ".less", // Web-related
			".xml", ".json", ".yaml", ".yml", ".properties", // Configuration files
			".sql", ".sh", ".bat", ".cmd", // Scripts and database
			".log", ".conf", ".ini", // Logs and configuration
			".gradle", ".pom", ".mvn", // Build tools
			".csv", ".rst", ".adoc", // Documentation and data
			".cpp", ".c", ".h", ".go", ".rs", ".php", ".rb", ".swift", ".kt", ".scala" // Additional
																						// programming
																						// languages
	));

	/**
	 * Input class for write file operations
	 */
	public static class WriteFileInput {

		private String action;

		@com.fasterxml.jackson.annotation.JsonProperty("file_path")
		private String filePath;

		@com.fasterxml.jackson.annotation.JsonProperty("contents")
		private String contents;

		@com.fasterxml.jackson.annotation.JsonProperty("old_string")
		private String oldString;

		@com.fasterxml.jackson.annotation.JsonProperty("new_string")
		private String newString;

		// Getters and setters
		public String getAction() {
			return action;
		}

		public void setAction(String action) {
			this.action = action;
		}

		public String getFilePath() {
			return filePath;
		}

		public void setFilePath(String filePath) {
			this.filePath = filePath;
		}

		public String getContents() {
			return contents;
		}

		public void setContents(String contents) {
			this.contents = contents;
		}

		public String getOldString() {
			return oldString;
		}

		public void setOldString(String oldString) {
			this.oldString = oldString;
		}

		public String getNewString() {
			return newString;
		}

		public void setNewString(String newString) {
			this.newString = newString;
		}

	}

	private final TextFileService textFileService;

	private final SmartContentSavingService innerStorageService;

	private final ObjectMapper objectMapper;

	private final ShortUrlService shortUrlService;

	private final ToolI18nService toolI18nService;

	public GlobalFileWriteOperator(TextFileService textFileService, SmartContentSavingService innerStorageService,
			ObjectMapper objectMapper, ShortUrlService shortUrlService, ToolI18nService toolI18nService) {
		this.textFileService = textFileService;
		this.innerStorageService = innerStorageService;
		this.objectMapper = objectMapper;
		this.shortUrlService = shortUrlService;
		this.toolI18nService = toolI18nService;
	}

	public ToolExecuteResult run(String toolInput) {
		log.info("WriteOperator toolInput: {}", toolInput);
		try {
			Map<String, Object> toolInputMap = objectMapper.readValue(toolInput,
					new TypeReference<Map<String, Object>>() {
					});

			String action = (String) toolInputMap.get("action");

			// Basic parameter validation
			if (action == null) {
				return new ToolExecuteResult("Error: action parameter is required");
			}

			return switch (action) {
				case "replace" -> {
					String filePath = (String) toolInputMap.get("file_path");
					if (filePath == null) {
						yield new ToolExecuteResult("Error: replace operation requires file_path parameter");
					}

					String oldString = (String) toolInputMap.get("old_string");
					String newString = (String) toolInputMap.get("new_string");

					if (oldString == null || newString == null) {
						yield new ToolExecuteResult(
								"Error: replace operation requires old_string and new_string parameters");
					}

					// Replace short URLs in oldString and newString
					oldString = replaceShortUrls(oldString);
					newString = replaceShortUrls(newString);

					yield replaceText(filePath, oldString, newString);
				}
				case "write" -> {
					String filePath = (String) toolInputMap.get("file_path");
					if (filePath == null) {
						yield new ToolExecuteResult("Error: write operation requires file_path parameter");
					}

					String contents = (String) toolInputMap.get("contents");
					if (contents == null) {
						yield new ToolExecuteResult("Error: write operation requires contents parameter");
					}

					// Replace short URLs in contents
					contents = replaceShortUrls(contents);

					yield writeFile(filePath, contents);
				}
				case "delete" -> {
					String filePath = (String) toolInputMap.get("file_path");
					if (filePath == null) {
						yield new ToolExecuteResult("Error: delete operation requires file_path parameter");
					}
					yield deleteFile(filePath);
				}
				default -> new ToolExecuteResult(
						"Unknown operation: " + action + ". Supported operations: replace, write, delete");
			};
		}
		catch (Exception e) {
			log.error("WriteOperator execution failed", e);
			return new ToolExecuteResult("Tool execution failed: " + e.getMessage());
		}
	}

	@Override
	public ToolExecuteResult run(WriteFileInput input) {
		log.info("WriteOperator input: action={}, filePath={}", input.getAction(), input.getFilePath());
		try {
			String action = input.getAction();
			String filePath = input.getFilePath();

			// Basic parameter validation
			if (action == null) {
				return new ToolExecuteResult("Error: action parameter is required");
			}
			if (filePath == null) {
				return new ToolExecuteResult("Error: file_path parameter is required");
			}

			// Replace short URLs in filePath
			filePath = replaceShortUrls(filePath);

			return switch (action) {
				case "replace" -> {
					String oldString = input.getOldString();
					String newString = input.getNewString();

					if (oldString == null || newString == null) {
						yield new ToolExecuteResult(
								"Error: replace operation requires old_string and new_string parameters");
					}

					// Replace short URLs in oldString and newString
					oldString = replaceShortUrls(oldString);
					newString = replaceShortUrls(newString);

					yield replaceText(filePath, oldString, newString);
				}
				case "write" -> {
					String contents = input.getContents();

					if (contents == null) {
						yield new ToolExecuteResult("Error: write operation requires contents parameter");
					}

					// Replace short URLs in contents
					contents = replaceShortUrls(contents);

					yield writeFile(filePath, contents);
				}
				case "delete" -> deleteFile(filePath);
				default -> new ToolExecuteResult(
						"Unknown operation: " + action + ". Supported operations: replace, write, delete");
			};
		}
		catch (Exception e) {
			log.error("WriteOperator execution failed", e);
			return new ToolExecuteResult("Tool execution failed: " + e.getMessage());
		}
	}

	/**
	 * Replace short URLs in a string with real URLs
	 * @param text The text that may contain short URLs
	 * @return The text with short URLs replaced by real URLs
	 */
	private String replaceShortUrls(String text) {
		if (text == null || text.isEmpty() || this.rootPlanId == null || this.rootPlanId.isEmpty()
				|| this.shortUrlService == null) {
			return text;
		}

		// Check if short URL feature is enabled
		Boolean enableShortUrl = textFileService.getLynxeProperties().getEnableShortUrl();
		if (enableShortUrl == null || !enableShortUrl) {
			return text; // Skip replacement if disabled
		}

		// Pattern to match short URLs: http://s@Url.a/ followed by digits
		Pattern shortUrlPattern = Pattern.compile(Pattern.quote(ShortUrlService.SHORT_URL_PREFIX) + "\\d+");
		Matcher matcher = shortUrlPattern.matcher(text);
		StringBuffer result = new StringBuffer();

		while (matcher.find()) {
			String shortUrl = matcher.group();
			String realUrl = shortUrlService.getRealUrl(this.rootPlanId, shortUrl);
			if (realUrl != null) {
				matcher.appendReplacement(result, Matcher.quoteReplacement(realUrl));
				log.debug("Replaced short URL {} with real URL {}", shortUrl, realUrl);
			}
			else {
				log.warn("Short URL not found in mapping: {}", shortUrl);
				// Keep the short URL if mapping not found
				matcher.appendReplacement(result, Matcher.quoteReplacement(shortUrl));
			}
		}
		matcher.appendTail(result);

		return result.toString();
	}

	/**
	 * Normalize file path by removing plan ID prefixes and relative path indicators
	 */
	private String normalizeFilePath(String filePath) {
		if (filePath == null || filePath.isEmpty()) {
			return filePath;
		}

		// Remove leading slashes and relative path indicators
		String normalized = filePath.trim();
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}

		// Remove "./" prefix if present
		if (normalized.startsWith("./")) {
			normalized = normalized.substring(2);
		}

		// Remove plan ID prefix (e.g., "plan-1763035234741/")
		if (normalized.matches("^plan-[^/]+/.*")) {
			normalized = normalized.replaceFirst("^plan-[^/]+/", "");
		}

		return normalized;
	}

	/**
	 * Validate and get the absolute path within the plan/subplan directory
	 */
	private Path validateGlobalPath(String filePath) throws IOException {
		if (this.rootPlanId == null || this.rootPlanId.isEmpty()) {
			throw new IOException("Error: rootPlanId is required for global file operations but is null or empty");
		}

		// Normalize the file path to remove plan ID prefixes
		String normalizedPath = normalizeFilePath(filePath);

		// Check file type for non-directory operations
		if (!normalizedPath.isEmpty() && !normalizedPath.endsWith("/") && !isSupportedFileType(normalizedPath)) {
			throw new IOException("Unsupported file type. Only text-based files are supported.");
		}

		// Get root plan directory
		Path rootPlanDirectory = textFileService.getRootPlanDirectory(this.rootPlanId);
		UnifiedDirectoryManager directoryManager = textFileService.getUnifiedDirectoryManager();

		// For WriteOperator, check root plan directory first, then subplan directory
		// if applicable
		// This allows accessing files in root plan directory even when in subplan context
		// Use the centralized method from UnifiedDirectoryManager
		Path rootPlanPath = directoryManager.resolveAndValidatePath(rootPlanDirectory, normalizedPath);

		// If file exists in root plan directory, use it
		if (Files.exists(rootPlanPath)) {
			return rootPlanPath;
		}

		// If currentPlanId exists and differs from rootPlanId, check subplan directory
		if (this.currentPlanId != null && !this.currentPlanId.isEmpty()
				&& !this.currentPlanId.equals(this.rootPlanId)) {
			Path subplanDirectory = rootPlanDirectory.resolve(this.currentPlanId);
			Path subplanPath = subplanDirectory.resolve(normalizedPath).normalize();

			// Ensure subplan path stays within subplan directory
			if (!subplanPath.startsWith(subplanDirectory)) {
				throw new IOException("Access denied: Invalid file path");
			}

			// If file exists in subplan directory, use it
			if (Files.exists(subplanPath)) {
				return subplanPath;
			}
		}

		// If file doesn't exist in either location, return root plan path for creation
		// (new files are created in root plan directory)
		return rootPlanPath;
	}

	/**
	 * Check if file type is supported
	 */
	private boolean isSupportedFileType(String filePath) {
		if (filePath == null || filePath.isEmpty()) {
			return false;
		}

		String extension = getFileExtension(filePath);
		return SUPPORTED_EXTENSIONS.contains(extension.toLowerCase());
	}

	/**
	 * Get file extension
	 */
	private String getFileExtension(String filePath) {
		if (filePath == null || filePath.isEmpty()) {
			return "";
		}

		int lastDotIndex = filePath.lastIndexOf('.');
		if (lastDotIndex == -1 || lastDotIndex == filePath.length() - 1) {
			return "";
		}

		return filePath.substring(lastDotIndex);
	}

	/**
	 * Delete a file
	 */
	private ToolExecuteResult deleteFile(String filePath) {
		try {
			Path absolutePath = validateGlobalPath(filePath);

			if (!Files.exists(absolutePath)) {
				return new ToolExecuteResult("Error: File does not exist: " + filePath);
			}

			Files.delete(absolutePath);

			log.info("Deleted file: {}", absolutePath);
			return new ToolExecuteResult("File deleted successfully: " + filePath);
		}
		catch (IOException e) {
			log.error("Error deleting file: {}", filePath, e);
			return new ToolExecuteResult("Error deleting file: " + e.getMessage());
		}
	}

	/**
	 * Replace text in file (StrReplace tool implementation) Performs exact string
	 * replacement with uniqueness validation
	 */
	private ToolExecuteResult replaceText(String filePath, String oldString, String newString) {
		try {
			Path absolutePath = validateGlobalPath(filePath);

			// Create file if it doesn't exist
			if (!Files.exists(absolutePath)) {
				Files.createDirectories(absolutePath.getParent());
				Files.createFile(absolutePath);
				log.info("Created new file automatically: {}", absolutePath);
			}

			// Validate that old_string and new_string are different
			if (oldString.equals(newString)) {
				return new ToolExecuteResult(
						"Error: new_string must be different from old_string. No changes would be made.");
			}

			String content = Files.readString(absolutePath);

			// Check if old_string exists in the file content
			if (!content.contains(oldString)) {
				log.warn("old_string not found in file: {}", absolutePath);
				return new ToolExecuteResult("Error: old_string was not found in file: " + filePath);
			}

			// Count occurrences to validate uniqueness
			int occurrenceCount = countOccurrences(content, oldString);
			if (occurrenceCount > 1) {
				return new ToolExecuteResult(String.format("Error: old_string is not unique (found %d occurrences). "
						+ "Please provide a larger string with more surrounding context to make it unique, "
						+ "or use a more specific match.", occurrenceCount));
			}

			// Perform replacement (only first occurrence since we validated uniqueness)
			String newContent = content.replaceFirst(Pattern.quote(oldString), Matcher.quoteReplacement(newString));
			Files.writeString(absolutePath, newContent);

			// Force flush to disk
			try (FileChannel channel = FileChannel.open(absolutePath, StandardOpenOption.WRITE)) {
				channel.force(true);
			}

			log.info("Text replaced in file: {}", absolutePath);
			return new ToolExecuteResult("Replacement successful in file: " + filePath);
		}
		catch (IOException e) {
			log.error("Error replacing text in file: {}", filePath, e);
			return new ToolExecuteResult("Error replacing text in file: " + e.getMessage());
		}
	}

	/**
	 * Count occurrences of a string in content (exact matching)
	 */
	private int countOccurrences(String content, String searchString) {
		int count = 0;
		int index = 0;
		while ((index = content.indexOf(searchString, index)) != -1) {
			count++;
			index += searchString.length();
		}
		return count;
	}

	/**
	 * Write file (Write tool implementation) Creates new files or overwrites existing
	 * files completely
	 */
	private ToolExecuteResult writeFile(String filePath, String contents) {
		try {
			if (contents == null) {
				return new ToolExecuteResult("Error: contents parameter is required");
			}

			Path absolutePath = validateGlobalPath(filePath);

			// Check if file exists before writing
			boolean fileExisted = Files.exists(absolutePath);

			// Create parent directories if they don't exist
			if (absolutePath.getParent() != null) {
				Files.createDirectories(absolutePath.getParent());
			}

			// Write contents to file (overwrites if exists, creates if doesn't exist)
			Files.writeString(absolutePath, contents);

			// Force flush to disk
			try (FileChannel channel = FileChannel.open(absolutePath, StandardOpenOption.WRITE)) {
				channel.force(true);
			}

			if (fileExisted) {
				log.info("File written (overwritten): {}", absolutePath);
				return new ToolExecuteResult("File written successfully (overwritten): " + filePath);
			}
			else {
				log.info("File written (created): {}", absolutePath);
				return new ToolExecuteResult("File written successfully (created): " + filePath);
			}
		}
		catch (IOException e) {
			log.error("Error writing file: {}", filePath, e);
			return new ToolExecuteResult("Error writing file: " + e.getMessage());
		}
	}

	@Override
	public String getCurrentToolStateString() {
		return "";
	}

	@Override
	public String getName() {
		return TOOL_NAME;
	}

	@Override
	public String getDescription() {
		return toolI18nService.getDescription("write-file-operator");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("write-file-operator");
	}

	@Override
	public Class<WriteFileInput> getInputType() {
		return WriteFileInput.class;
	}

	@Override
	public void cleanup(String planId) {
		if (planId != null) {
			log.info("Cleaning up file resources for plan: {}", planId);
			// Cleanup if needed - the TextFileService handles the main cleanup
		}
	}

	@Override
	public String getServiceGroup() {
		return "file-operations";
	}

	@Override
	public boolean isSelectable() {
		return true;
	}

}
