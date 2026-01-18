package com.trading.autonomous;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * AI-powered code generator using Claude 3.5 Sonnet API.
 * Generates code fixes for detected errors with high quality and safety.
 */
public class ClaudeCodeGenerator {
    private static final Logger logger = LoggerFactory.getLogger(ClaudeCodeGenerator.class);
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    
    // Claude API configuration
    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-3-5-sonnet-20241022"; // Latest version
    private static final int MAX_TOKENS = 4096;
    
    public ClaudeCodeGenerator(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.objectMapper = new ObjectMapper();
        
        logger.info("🤖 ClaudeCodeGenerator initialized with model: {}", MODEL);
    }
    
    /**
     * Generate code fix for an error pattern.
     */
    public CodeFix generateFix(ErrorDetector.ErrorPattern pattern, 
                               String errorContext,
                               String relevantCode) {
        
        logger.info("🤖 Generating code fix for: {}", pattern.name());
        
        try {
            // 1. Build prompt
            String prompt = buildPrompt(pattern, errorContext, relevantCode);
            
            // 2. Call Claude API
            String response = callClaudeAPI(prompt);
            
            // 3. Parse response
            CodeFix fix = parseCodeFix(response, pattern);
            
            logger.info("✅ Code fix generated: {} lines", fix.codeLines().size());
            return fix;
            
        } catch (Exception e) {
            logger.error("❌ Failed to generate code fix", e);
            return CodeFix.failed("Code generation failed: " + e.getMessage());
        }
    }
    
    /**
     * Build prompt for Claude API.
     */
    private String buildPrompt(ErrorDetector.ErrorPattern pattern, 
                               String errorContext,
                               String relevantCode) {
        
        return String.format("""
            You are an expert Java developer working on a live trading bot. An error has been detected and you need to generate a code fix.
            
            **Error Pattern**: %s
            **Error Description**: %s
            **Suggested Fix**: %s
            **Error Context**: %s
            
            **Current Code**:
            ```java
            %s
            ```
            
            **Requirements**:
            1. Generate a complete, production-ready Java code fix
            2. Maintain existing code style and patterns
            3. Add proper error handling
            4. Include JavaDoc comments
            5. Ensure thread safety (bot uses virtual threads)
            6. Follow modern Java 23 best practices
            7. DO NOT change method signatures unless absolutely necessary
            8. Preserve all existing functionality
            
            **Output Format**:
            Provide your response in this exact format:
            
            EXPLANATION:
            [Brief explanation of the fix]
            
            FILE: [full path to file]
            ```java
            [complete fixed code]
            ```
            
            TESTS:
            ```java
            [unit tests for the fix]
            ```
            
            VALIDATION:
            [How to validate this fix works]
            """,
            pattern.name(),
            pattern.regex(),
            pattern.suggestedFix(),
            errorContext,
            relevantCode
        );
    }
    
    /**
     * Call Claude API with prompt.
     */
    private String callClaudeAPI(String prompt) throws IOException, InterruptedException {
        // Build request body
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", MODEL);
        requestBody.put("max_tokens", MAX_TOKENS);
        requestBody.put("messages", List.of(
            Map.of("role", "user", "content", prompt)
        ));
        
        String jsonBody = objectMapper.writeValueAsString(requestBody);
        
        // Build HTTP request
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(CLAUDE_API_URL))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
        
        // Send request
        logger.debug("📤 Sending request to Claude API...");
        HttpResponse<String> response = httpClient.send(request, 
            HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("Claude API error: " + response.statusCode() + " - " + response.body());
        }
        
        // Parse response
        JsonNode jsonResponse = objectMapper.readTree(response.body());
        String content = jsonResponse.get("content").get(0).get("text").asText();
        
        logger.debug("📥 Received response from Claude API ({} chars)", content.length());
        return content;
    }
    
    /**
     * Parse Claude's response into CodeFix object.
     */
    private CodeFix parseCodeFix(String response, ErrorDetector.ErrorPattern pattern) {
        try {
            // Extract sections
            String explanation = extractSection(response, "EXPLANATION:", "FILE:");
            String filePath = extractFilePath(response);
            String code = extractCode(response, "FILE:");
            String tests = extractCode(response, "TESTS:");
            String validation = extractSection(response, "VALIDATION:", null);
            
            // Parse code into lines
            List<String> codeLines = Arrays.asList(code.split("\n"));
            List<String> testLines = tests != null ? Arrays.asList(tests.split("\n")) : List.of();
            
            return new CodeFix(
                true,
                pattern.name(),
                explanation.trim(),
                filePath.trim(),
                codeLines,
                testLines,
                validation.trim(),
                null
            );
            
        } catch (Exception e) {
            logger.error("Failed to parse Claude response", e);
            return CodeFix.failed("Failed to parse AI response: " + e.getMessage());
        }
    }
    
    /**
     * Extract section from response.
     */
    private String extractSection(String response, String startMarker, String endMarker) {
        int start = response.indexOf(startMarker);
        if (start == -1) return "";
        
        start += startMarker.length();
        int end = endMarker != null ? response.indexOf(endMarker, start) : response.length();
        if (end == -1) end = response.length();
        
        return response.substring(start, end).trim();
    }
    
    /**
     * Extract file path from response.
     */
    private String extractFilePath(String response) {
        String fileSection = extractSection(response, "FILE:", "```");
        return fileSection.trim();
    }
    
    /**
     * Extract code block from response.
     */
    private String extractCode(String response, String marker) {
        int start = response.indexOf(marker);
        if (start == -1) return "";
        
        // Find code block
        int codeStart = response.indexOf("```java", start);
        if (codeStart == -1) return "";
        
        codeStart = response.indexOf("\n", codeStart) + 1;
        int codeEnd = response.indexOf("```", codeStart);
        if (codeEnd == -1) return "";
        
        return response.substring(codeStart, codeEnd).trim();
    }
    
    /**
     * Validate generated code (syntax check).
     */
    public boolean validateSyntax(CodeFix fix) {
        // Basic validation
        if (fix.codeLines().isEmpty()) {
            logger.error("❌ Generated code is empty");
            return false;
        }
        
        String code = String.join("\n", fix.codeLines());
        
        // Check for common syntax issues
        if (!hasBalancedBraces(code)) {
            logger.error("❌ Unbalanced braces in generated code");
            return false;
        }
        
        if (!hasValidPackage(code)) {
            logger.error("❌ Missing or invalid package declaration");
            return false;
        }
        
        logger.info("✅ Code syntax validation passed");
        return true;
    }
    
    private boolean hasBalancedBraces(String code) {
        int count = 0;
        for (char c : code.toCharArray()) {
            if (c == '{') count++;
            if (c == '}') count--;
            if (count < 0) return false;
        }
        return count == 0;
    }
    
    private boolean hasValidPackage(String code) {
        return code.contains("package com.trading");
    }
    
    /**
     * Code fix result.
     */
    public record CodeFix(
        boolean success,
        String errorPattern,
        String explanation,
        String filePath,
        List<String> codeLines,
        List<String> testLines,
        String validation,
        String errorMessage
    ) {
        public static CodeFix failed(String error) {
            return new CodeFix(false, "", "", "", List.of(), List.of(), "", error);
        }
        
        public String getFullCode() {
            return String.join("\n", codeLines);
        }
        
        public String getFullTests() {
            return String.join("\n", testLines);
        }
    }
}
