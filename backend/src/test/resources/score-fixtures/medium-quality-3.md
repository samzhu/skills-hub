---
name: api-tester
description: Tests REST APIs using curl or similar tools. Can send GET, POST, PUT, DELETE requests and check responses. Good for testing API endpoints during development.
allowed-tools:
  - Bash(curl:*)
---
# API Tester

Tests REST API endpoints by sending HTTP requests and analyzing responses.

## Usage

Provide the endpoint URL and the type of request you want to make.

## Common requests

**GET request:**
```bash
curl -s http://localhost:8080/api/v1/resource | jq .
```

**POST with JSON:**
```bash
curl -s -X POST http://localhost:8080/api/v1/resource \
  -H "Content-Type: application/json" \
  -d '{"key": "value"}'
```

**With authentication:**
```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/protected
```

## Response checking

Look at the HTTP status code and response body. A 200 means success.
Check if the response matches what you expected.

If testing fails, check the server logs for more details.
