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
package com.alibaba.cloud.ai.lynxe.tool.dirOperator;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.cloud.ai.lynxe.tool.AbstractBaseTool;
import com.alibaba.cloud.ai.lynxe.tool.code.ToolExecuteResult;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.SymbolicLinkDetector;
import com.alibaba.cloud.ai.lynxe.tool.filesystem.UnifiedDirectoryManager;
import com.alibaba.cloud.ai.lynxe.tool.i18n.ToolI18nService;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Directory operator that performs directory listing operations. This operator provides
 * access to directory listings that can be accessed across all sub-plans within the same
 * execution context.
 *
 * Keywords: directory listing, list files, list directories, root directory, root folder,
 * root plan directory, directory operations.
 *
 * Use this tool for listing files and directories in the root directory or
 * subdirectories.
 */
public class DirectoryOperator extends AbstractBaseTool<DirectoryOperator.ListFilesInput> {

	private static final Logger log = LoggerFactory.getLogger(DirectoryOperator.class);

	private static final String TOOL_NAME = "directory_operator";

	/**
	 * Input class for directory operations
	 */
	public static class ListFilesInput {

		@JsonProperty("action")
		private String action;

		@JsonProperty("path")
		private String path;

		@JsonProperty("file_path")
		private String filePath;

		@JsonProperty("glob_pattern")
		private String globPattern;

		@JsonProperty("target_directory")
		private String targetDirectory;

		// Getters and setters
		public String getAction() {
			return action;
		}

		public void setAction(String action) {
			this.action = action;
		}

		public String getPath() {
			return path != null ? path : filePath;
		}

		public void setPath(String path) {
			this.path = path;
		}

		public String getFilePath() {
			return filePath;
		}

		public void setFilePath(String filePath) {
			this.filePath = filePath;
		}

		public String getGlobPattern() {
			return globPattern;
		}

		public void setGlobPattern(String globPattern) {
			this.globPattern = globPattern;
		}

		public String getTargetDirectory() {
			return targetDirectory;
		}

		public void setTargetDirectory(String targetDirectory) {
			this.targetDirectory = targetDirectory;
		}

	}

	private final UnifiedDirectoryManager unifiedDirectoryManager;

	private final ObjectMapper objectMapper;

	private final ToolI18nService toolI18nService;

	private final SymbolicLinkDetector symlinkDetector;

	public DirectoryOperator(UnifiedDirectoryManager unifiedDirectoryManager, ObjectMapper objectMapper,
			ToolI18nService toolI18nService, SymbolicLinkDetector symlinkDetector) {
		this.unifiedDirectoryManager = unifiedDirectoryManager;
		this.objectMapper = objectMapper;
		this.toolI18nService = toolI18nService;
		this.symlinkDetector = symlinkDetector;
	}

	@Override
	public ToolExecuteResult run(ListFilesInput input) {
		log.info("DirectoryOperator input: action={}, path={}, glob_pattern={}, target_directory={}", input.getAction(),
				input.getPath(), input.getGlobPattern(), input.getTargetDirectory());
		try {
			String action = input.getAction();

			// Default to list_files if no action specified (backward compatibility)
			if (action == null) {
				String directoryPath = input.getPath();
				if (directoryPath == null) {
					directoryPath = "";
				}
				return listFiles(directoryPath);
			}

			// Handle different actions using switch expression
			return switch (action) {
				case "list_files" -> {
					String directoryPath = input.getPath();
					if (directoryPath == null) {
						directoryPath = "";
					}
					yield listFiles(directoryPath);
				}
				case "glob" -> {
					String globPattern = input.getGlobPattern();
					String targetDirectory = input.getTargetDirectory();
					if (globPattern == null || globPattern.isEmpty()) {
						yield new ToolExecuteResult("Error: glob_pattern parameter is required for glob operation");
					}
					yield globFiles(globPattern, targetDirectory);
				}
				default ->
					new ToolExecuteResult("Unknown operation: " + action + ". Supported operations: list_files, glob");
			};
		}
		catch (Exception e) {
			log.error("DirectoryOperator execution failed", e);
			return new ToolExecuteResult("Tool execution failed: " + e.getMessage());
		}
	}

	/**
	 * Normalize directory path by removing plan ID prefixes and relative path indicators
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
	 * List files in the plan/subplan directory
	 */
	private ToolExecuteResult listFiles(String directoryPath) {
		try {
			if (this.rootPlanId == null || this.rootPlanId.isEmpty()) {
				return new ToolExecuteResult("Error: rootPlanId is required for directory operations");
			}

			// Normalize the directory path to remove plan ID prefixes
			String normalizedPath = normalizeFilePath(directoryPath != null ? directoryPath : "");

			// For list_files, always use root plan directory as the base
			// This allows listing directories like "linked_external" that exist at root
			// plan level
			Path rootPlanDirectory = unifiedDirectoryManager.getRootPlanDirectory(this.rootPlanId);

			// If a subdirectory path is provided, resolve it within root plan directory
			Path targetDirectory = rootPlanDirectory;
			if (normalizedPath != null && !normalizedPath.isEmpty() && !normalizedPath.equals(".")
					&& !normalizedPath.equals("root")) {

				// Use the centralized method from UnifiedDirectoryManager
				targetDirectory = unifiedDirectoryManager.resolveAndValidatePath(rootPlanDirectory, normalizedPath);
			}

			// Ensure directory exists - create if needed for root plan directory
			if (!Files.exists(targetDirectory)) {
				if (normalizedPath == null || normalizedPath.isEmpty() || normalizedPath.equals(".")
						|| normalizedPath.equals("root")) {
					// Create root plan directory if it doesn't exist
					Files.createDirectories(targetDirectory);
				}
				else {
					return new ToolExecuteResult("Error: Directory does not exist: " + normalizedPath);
				}
			}

			if (!Files.isDirectory(targetDirectory)) {
				return new ToolExecuteResult("Error: Path is not a directory: " + normalizedPath);
			}

			StringBuilder result = new StringBuilder();
			result.append("Files: \n");
			if (normalizedPath != null && !normalizedPath.isEmpty() && !normalizedPath.equals(".")
					&& !normalizedPath.equals("root")) {
				result.append(normalizedPath).append("\n");
			}

			List<Path> files = Files.list(targetDirectory).sorted().toList();

			if (files.isEmpty()) {
				result.append("(empty directory)\n");
			}
			else {
				for (Path path : files) {
					try {
						String fileName = path.getFileName().toString();
						if (Files.isDirectory(path)) {
							result.append(String.format("[DIR] %s/\n", fileName));
						}
						else {
							long size = Files.size(path);
							String sizeStr = formatFileSize(size);
							result.append(String.format("[FILE] %s (%s)\n", fileName, sizeStr));
						}
					}
					catch (IOException e) {
						result.append(String.format("[ERROR] %s (error reading)\n", path.getFileName()));
					}
				}
			}

			return new ToolExecuteResult(result.toString());
		}
		catch (IOException e) {
			String pathToLog = normalizeFilePath(directoryPath != null ? directoryPath : "");
			log.error("Error listing files: {}", pathToLog, e);
			return new ToolExecuteResult("Error listing files: " + e.getMessage());
		}
	}

	/**
	 * Format file size in human-readable format
	 */
	private String formatFileSize(long size) {
		if (size < 1024)
			return size + " B";
		if (size < 1024 * 1024)
			return String.format("%.1f KB", size / 1024.0);
		if (size < 1024 * 1024 * 1024)
			return String.format("%.1f MB", size / (1024.0 * 1024));
		return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
	}

	/**
	 * Find files matching a glob pattern. This method searches for files matching the
	 * specified glob pattern, with results sorted by modification time (most recently
	 * modified first).
	 * @param globPattern The glob pattern to match files against (required)
	 * @param targetDirectory Optional target directory to search in (defaults to root
	 * plan directory)
	 * @return ToolExecuteResult with matching file paths sorted by modification time
	 */
	private ToolExecuteResult globFiles(String globPattern, String targetDirectory) {
		try {
			if (this.rootPlanId == null || this.rootPlanId.isEmpty()) {
				return new ToolExecuteResult("Error: rootPlanId is required for glob operations");
			}

			// Normalize glob pattern: auto-prefix with **/ if not starting with **/
			String normalizedPattern = normalizeGlobPattern(globPattern);

			// Determine search root directory
			Path searchRoot;
			if (targetDirectory != null && !targetDirectory.isEmpty()) {
				// Normalize target directory path
				String normalizedTargetDir = normalizeFilePath(targetDirectory);
				Path rootPlanDirectory = unifiedDirectoryManager.getRootPlanDirectory(this.rootPlanId);

				// Use the centralized method from UnifiedDirectoryManager
				searchRoot = unifiedDirectoryManager.resolveAndValidatePath(rootPlanDirectory, normalizedTargetDir);

				// Check if target directory exists
				if (!Files.exists(searchRoot)) {
					return new ToolExecuteResult("Error: Target directory does not exist: " + normalizedTargetDir);
				}

				if (!Files.isDirectory(searchRoot)) {
					return new ToolExecuteResult("Error: Target path is not a directory: " + normalizedTargetDir);
				}
			}
			else {
				// Default to root plan directory
				searchRoot = unifiedDirectoryManager.getRootPlanDirectory(this.rootPlanId);
			}

			// Create PathMatcher for glob pattern
			FileSystem fileSystem = FileSystems.getDefault();
			PathMatcher matcher = fileSystem.getPathMatcher("glob:" + normalizedPattern);

			// Find all matching files using safe traversal
			List<Path> matchingFiles = new ArrayList<>();
			Files.walkFileTree(searchRoot, symlinkDetector.createSafeFileVisitor(searchRoot, (file, attrs) -> {
				// Check if file matches the pattern
				Path relativePath = searchRoot.relativize(file);
				if (matcher.matches(relativePath)) {
					matchingFiles.add(file);
				}
			}, null // No special directory handling needed
			));

			// Sort by modification time (most recently modified first)
			matchingFiles.sort(Comparator.comparing((Path path) -> {
				try {
					FileTime lastModified = Files.getLastModifiedTime(path);
					return lastModified.toInstant();
				}
				catch (IOException e) {
					log.warn("Error getting modification time for file: {}", path, e);
					return java.time.Instant.EPOCH;
				}
			}).reversed());

			// Build result
			StringBuilder result = new StringBuilder();
			result.append(String.format("Glob results for pattern '%s':\n", globPattern));
			if (targetDirectory != null && !targetDirectory.isEmpty()) {
				result.append(String.format("Search directory: %s\n", normalizeFilePath(targetDirectory)));
			}
			result.append("=".repeat(60)).append("\n");

			if (matchingFiles.isEmpty()) {
				result.append("No files found matching the pattern.\n");
			}
			else {
				result.append(String.format("Found %d file(s):\n\n", matchingFiles.size()));
				for (Path path : matchingFiles) {
					try {
						Path relativePath = searchRoot.relativize(path);
						String relativePathStr = relativePath.toString().replace('\\', '/');
						long size = Files.size(path);
						String sizeStr = formatFileSize(size);
						FileTime lastModified = Files.getLastModifiedTime(path);
						result.append(String.format("%s (%s, modified: %s)\n", relativePathStr, sizeStr,
								lastModified.toString()));
					}
					catch (IOException e) {
						log.warn("Error reading file info: {}", path, e);
						Path relativePath = searchRoot.relativize(path);
						String relativePathStr = relativePath.toString().replace('\\', '/');
						result.append(String.format("%s (error reading file info)\n", relativePathStr));
					}
				}
			}

			return new ToolExecuteResult(result.toString());
		}
		catch (IOException e) {
			log.error("Error performing glob search: pattern={}, targetDirectory={}", globPattern, targetDirectory, e);
			return new ToolExecuteResult("Error performing glob search: " + e.getMessage());
		}
	}

	/**
	 * Normalize glob pattern by auto-prefixing with **&#47; if not starting with **&#47;
	 * @param globPattern The original glob pattern
	 * @return Normalized glob pattern with recursive prefix if needed
	 */
	private String normalizeGlobPattern(String globPattern) {
		if (globPattern == null || globPattern.isEmpty()) {
			return globPattern;
		}

		String trimmed = globPattern.trim();

		// If pattern doesn't start with **/, prepend it for recursive search
		if (!trimmed.startsWith("**/")) {
			// Handle patterns that start with / (absolute-like patterns)
			if (trimmed.startsWith("/")) {
				trimmed = trimmed.substring(1);
			}
			return "**/" + trimmed;
		}

		return trimmed;
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
		return toolI18nService.getDescription("directory-operator");
	}

	@Override
	public String getParameters() {
		return toolI18nService.getParameters("directory-operator");
	}

	@Override
	public Class<ListFilesInput> getInputType() {
		return ListFilesInput.class;
	}

	@Override
	public void cleanup(String planId) {
		if (planId != null) {
			log.info("Cleaning up directory resources for plan: {}", planId);
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
