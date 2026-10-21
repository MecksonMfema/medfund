---
description: Create git commits and push (no approval needed)
---

# Commit and Push Changes

You are tasked with creating a git commit for all changes made during this session and pushing to the remote.

## Process:

1. **Analyze what changed:**
   - Review the conversation history and understand what was accomplished
   - Run `git status` to see current changes
   - Run `git diff` to understand the modifications

2. **Execute commit:**
   - Use `git add` with specific files (never use `-A` or `.`)
   - Create ONE commit with a clear, descriptive message
   - Use imperative mood ("Add feature" not "Added feature")
   - Focus on why the changes were made, not just what

3. **Push to remote:**
   - Run `git push` to push the commit to the remote
   - If the branch has no upstream, use `git push -u origin <branch-name>`

4. **Report results:**
   - Show `git log --oneline -1` to display the commit created
   - Confirm the push was successful
   - **For every `.md` file in the commit**, print a GitHub URL the user can open. Use `git diff-tree --no-commit-id --name-only --diff-filter=d -r HEAD` to list the touched files (the `--diff-filter=d` excludes deletions, which would 404), then for each path ending in `.md` emit `https://github.com/OWNER/REPO/blob/<branch>/<path>` — link the **branch** (from `git rev-parse --abbrev-ref HEAD`), not the commit SHA, so the URL always tracks the latest version of the file rather than pinning to this commit's snapshot. Derive `OWNER/REPO` from `git remote get-url origin` — converting `git@github.com:OWNER/REPO.git` or `https://github.com/OWNER/REPO.git`. Skip the section entirely if the commit has no `.md` files.

## Important:
- **DO NOT ask for user approval** - just commit and push
- **Create a SINGLE commit** - do not split into multiple commits
- **NEVER add co-author information or Claude attribution**
- Commits should be authored solely by the user
- Do not include any "Generated with Claude" messages
- Do not add "Co-Authored-By" lines
- Write commit messages as if the user wrote them

## Remember:
- You have the full context of what was done in this session
- The user trusts your judgment - they asked you to commit
