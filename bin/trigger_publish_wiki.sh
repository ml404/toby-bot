#!/bin/bash

# Trigger the GitHub Actions workflow using GitHub API
curl -X POST \
  -H "Accept: application/vnd.github+json" \
  -H "Authorization: Bearer $GITHUB_TOKEN" \
  https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/actions/workflows/$WORKFLOW_FILE/dispatches \
  -d '{"ref": "master"}'
