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

import { LlmCheckService } from '@/utils/llm-check'
import type { InputMessage } from "@/stores/memory"

export class DirectApiService {
  private static readonly BASE_URL = '/api/executor'

  // Send task directly (direct execution mode)
  public static async sendMessage(query: InputMessage): Promise<any> {
    return LlmCheckService.withLlmCheck(async () => {
      // Add Vue identification flag to distinguish from HTTP requests
      const requestBody = {
        ...query,
        isVueRequest: true
      }
      
      const response = await fetch(`${this.BASE_URL}/execute`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(requestBody)
      })
      if (!response.ok) throw new Error(`API request failed: ${response.status}`)
      return await response.json()
    })
  }

  // Send task using executeByToolNameAsync with default plan template
  public static async sendMessageWithDefaultPlan(query: InputMessage): Promise<any> {
    // Use default plan template ID as toolName
    const toolName = 'default-plan-id-001000222'
    
    // Create replacement parameters with user input
    const replacementParams = {
      'userRequirement': query.input
    }
    
    return this.executeByToolName(toolName, replacementParams, query.uploadedFiles, query.uploadKey)
  }

  // Unified method to execute by tool name (replaces both sendMessageWithDefaultPlan and PlanActApiService.executePlan)
  public static async executeByToolName(
    toolName: string, 
    replacementParams?: Record<string, string>, 
    uploadedFiles?: string[], 
    uploadKey?: string
  ): Promise<any> {
    return LlmCheckService.withLlmCheck(async () => {
      console.log('[DirectApiService] executeByToolName called with:', { toolName, replacementParams, uploadedFiles, uploadKey })
      
      const requestBody: Record<string, any> = {
        toolName: toolName,
        isVueRequest: true
      }
      
      // Include replacement parameters if present
      if (replacementParams && Object.keys(replacementParams).length > 0) {
        requestBody.replacementParams = replacementParams
        console.log('[DirectApiService] Including replacement params:', replacementParams)
      }
      
      // Include uploaded files if present
      if (uploadedFiles && uploadedFiles.length > 0) {
        requestBody.uploadedFiles = uploadedFiles
        console.log('[DirectApiService] Including uploaded files:', uploadedFiles.length)
      }
      
      // Include uploadKey if present
      if (uploadKey) {
        requestBody.uploadKey = uploadKey
        console.log('[DirectApiService] Including uploadKey:', uploadKey)
      }
      
      console.log('[DirectApiService] Making request to:', `${this.BASE_URL}/executeByToolNameAsync`)
      console.log('[DirectApiService] Request body:', requestBody)
      
      const response = await fetch(`${this.BASE_URL}/executeByToolNameAsync`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(requestBody)
      })
      
      console.log('[DirectApiService] Response status:', response.status, response.ok)
      
      if (!response.ok) {
        const errorText = await response.text()
        console.error('[DirectApiService] Request failed:', errorText)
        throw new Error(`Failed to execute: ${response.status}`)
      }
      
      const result = await response.json()
      console.log('[DirectApiService] executeByToolName response:', result)
      return result
    })
  }

  // Stop a running task by plan ID
  public static async stopTask(planId: string): Promise<any> {
    return LlmCheckService.withLlmCheck(async () => {
      console.log('[DirectApiService] Stopping task for planId:', planId)
      
      const response = await fetch(`${this.BASE_URL}/stopTask/${planId}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }
      })
      
      if (!response.ok) {
        const errorData = await response.json().catch(() => ({}))
        throw new Error(errorData.error || `Failed to stop task: ${response.status}`)
      }
      
      return await response.json()
    })
  }
}
